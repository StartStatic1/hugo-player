import { Router, type Request, type Response } from "express";
import { z } from "zod";
import { loadCatalog, xtreamCredsFor, type PlaylistRef } from "./content/service.js";
import { knownChannels } from "./content/known-channels.js";
import { fetchJson } from "./content/net.js";
import { fetchShortEpg, xmltvUrl } from "./content/xtream.js";
import { loadEpg } from "./epg/service.js";
import { nowNext, resolveChannel, type Programme } from "./epg/xmltv.js";
import { sendError, type PlaylistResolver } from "./routes-catalog.js";

const nowNextSchema = z.object({
  /** tvg-id / epg_channel_id ou nome do canal */
  ids: z.array(z.string().max(300)).min(1).max(1000),
  epgUrl: z.string().url().optional(),
  at: z.number().optional(),
});

const fmt = (p: Programme | null) =>
  p ? { title: p.title, start: new Date(p.start).toISOString(), stop: new Date(p.stop).toISOString(), ...(p.desc ? { desc: p.desc } : {}) } : null;

/**
 * De onde vem o EPG de uma playlist, nesta ordem:
 *  1) link próprio salvo na playlist (site/app);
 *  2) Xtream: xmltv.php do mesmo painel (sem baixar a lista);
 *  3) M3U: url-tvg do cabeçalho.
 */
export async function epgSourceFor(r: PlaylistRef): Promise<{ url: string; kind: "custom" | "xtream" | "m3u" } | null> {
  if (r.epgUrl) return { url: r.epgUrl, kind: "custom" };
  const creds = xtreamCredsFor(r);
  if (creds) return { url: xmltvUrl(creds), kind: "xtream" };
  try {
    const url = (await loadCatalog(r)).epgUrl;
    return url ? { url, kind: "m3u" } : null;
  } catch {
    return null;
  }
}

export function createEpgRouter(resolve: PlaylistResolver) {
  const router = Router();
  /** playlist da requisição (para filtrar o EPG pelos canais dela) */
  const refOf = (req: Request): PlaylistRef | null => {
    const r = resolve(req);
    return r && r !== "unauthorized" ? r : null;
  };

  /** URL do EPG: informada, link próprio da playlist, xmltv.php do Xtream ou url-tvg do M3U. */
  async function epgUrlFor(req: Request, res: Response, explicit?: string): Promise<string | null> {
    if (explicit) return explicit;
    const r = resolve(req);
    if (r === "unauthorized") {
      res.status(401).json({ error: "invalid_credentials" });
      return null;
    }
    if (!r) {
      res.status(400).json({ error: "missing_playlist_or_epg_url" });
      return null;
    }
    const src = await epgSourceFor(r);
    if (!src) {
      res.json({ epgUrl: null, channels: {}, available: false });
      return null;
    }
    return src.url;
  }

  /** Qual EPG a playlist usa (o app baixa direto no aparelho se a VPS levar 403). */
  router.get("/source", async (req, res) => {
    const r = resolve(req);
    if (r === "unauthorized") return res.status(401).json({ error: "invalid_credentials" });
    if (!r) return res.status(400).json({ error: "missing_playlist" });
    const src = await epgSourceFor(r);
    res.json({ epgUrl: src?.url ?? null, kind: src?.kind ?? null });
  });

  /** Agora / a seguir para vários canais de uma vez. */
  router.post("/now-next", async (req, res) => {
    const parsed = nowNextSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
    try {
      const url = await epgUrlFor(req, res, parsed.data.epgUrl);
      if (!url) return;
      const epg = await loadEpg(url, req.query.refresh === "1", parsed.data.epgUrl ? null : knownChannels(refOf(req)?.url));
      const at = parsed.data.at ?? Date.now();
      const channels: Record<string, { channelId: string; now: ReturnType<typeof fmt>; next: ReturnType<typeof fmt> }> = {};
      for (const key of parsed.data.ids) {
        const id = resolveChannel(epg, key);
        if (!id) continue;
        const nn = nowNext(epg, id, at);
        if (nn.now || nn.next) channels[key] = { channelId: id, now: fmt(nn.now), next: fmt(nn.next) };
      }
      res.json({ epgUrl: url, available: true, loadedAt: new Date(epg.loadedAt).toISOString(), channels });
    } catch (e) {
      sendError(res, e);
    }
  });

  /** Programação do dia de um canal (para um guia completo). */
  router.get("/channel/:key", async (req, res) => {
    try {
      const url = await epgUrlFor(req, res, req.query.epgUrl as string | undefined);
      if (!url) return;
      const epg = await loadEpg(url, false, req.query.epgUrl ? null : knownChannels(refOf(req)?.url));
      const id = resolveChannel(epg, req.params.key);
      if (!id) return res.status(404).json({ error: "channel_not_in_epg" });
      const limit = Math.min(Math.max(Number(req.query.limit ?? 48) || 48, 1), 200);
      const list = (epg.programmes.get(id) ?? []).filter((p) => p.stop > Date.now()).slice(0, limit);
      res.json({ channelId: id, programmes: list.map(fmt) });
    } catch (e) {
      sendError(res, e);
    }
  });

  /** Xtream: get_short_epg (alternativa quando não há xmltv.php). */
  router.get("/short/:streamId", async (req, res) => {
    const r = resolve(req);
    if (r === "unauthorized") return res.status(401).json({ error: "invalid_credentials" });
    if (!r) return res.status(400).json({ error: "missing_playlist" });
    const creds = xtreamCredsFor(r);
    if (!creds) return res.status(400).json({ error: "not_xtream_playlist" });
    try {
      const listings = await fetchShortEpg(creds, Number(req.params.streamId), (u) => fetchJson(u), Number(req.query.limit ?? 4));
      res.json({ streamId: Number(req.params.streamId), listings });
    } catch (e) {
      sendError(res, e);
    }
  });

  return router;
}
