import { assertPublicHttpUrl, IPTV_USER_AGENT, TtlCache, UpstreamError } from "../content/net.js";
import type { KnownChannels } from "../content/known-channels.js";
import { bodyToStream, parseXmltv, type EpgData } from "./xmltv.js";

const EPG_TTL_MS = Number(process.env.EPG_TTL_HOURS ?? 6) * 3600_000;
const epgCache = new TtlCache<EpgData>(EPG_TTL_MS, Number(process.env.EPG_CACHE_ENTRIES ?? 5));

/** Assinatura curta do filtro de canais (entra na chave do cache). */
function keepSignature(keep: KnownChannels | null | undefined): string {
  if (!keep) return "all";
  let h = 0;
  for (const v of [...keep.ids, "|", ...keep.names]) for (let i = 0; i < v.length; i++) h = (h * 31 + v.charCodeAt(i)) | 0;
  return `${keep.ids.size}.${keep.names.size}.${(h >>> 0).toString(36)}`;
}

/**
 * Baixa (gzip ou não) e interpreta o XMLTV em streaming; guarda no cache por EPG_TTL_HOURS.
 * keep: só os canais da playlist (bem menos memória em EPG grande).
 * 403 do provedor vira upstream_403 (o app então baixa direto no aparelho).
 */
export async function loadEpg(url: string, force = false, keep?: KnownChannels | null): Promise<EpgData> {
  return epgCache.getOrLoad(
    `${url}|${keepSignature(keep)}`,
    async () => {
      assertPublicHttpUrl(url);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), Number(process.env.EPG_TIMEOUT_MS ?? 120_000));
      try {
        const res = await fetch(url, { signal: controller.signal, redirect: "follow", headers: { "User-Agent": IPTV_USER_AGENT, Accept: "*/*" } });
        if (!res.ok || !res.body) throw new UpstreamError(502, `upstream_${res.status}`);
        const data = await parseXmltv(await bodyToStream(res.body), { keep: keep ?? null });
        if (data.channels.size === 0 && data.programmeCount === 0) throw new UpstreamError(422, "empty_epg");
        return data;
      } catch (e: any) {
        if (e?.name === "AbortError") throw new UpstreamError(504, "timeout_fetching_epg");
        if (e instanceof UpstreamError) throw e;
        throw new UpstreamError(502, "epg_fetch_failed");
      } finally {
        clearTimeout(timer);
      }
    },
    force
  );
}
