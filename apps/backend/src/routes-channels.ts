import { Router } from "express";
import { classifyEntry } from "./content/classify.js";
import { parseM3U } from "./content/m3u.js";
import { fetchText } from "./content/net.js";
import { sendError } from "./routes-catalog.js";

/**
 * Endpoint legado usado pelo app (lista plana de canais + categorias).
 * Agora usa o mesmo parser/classificador do /catalog. Para o catálogo
 * completo separado (live/filmes/séries + Xtream) use GET /catalog.
 */
export const channelsRouter = Router();

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });
  const limit = Math.min(Number(req.query.limit ?? 1000) || 1000, 20000);
  const onlyType = req.query.type as string | undefined;

  try {
    const text = await fetchText(playlistUrl, 30_000);
    if (!text || text.length < 10) return res.status(422).json({ error: "empty_playlist" });
    res.json(buildChannels(text, limit, onlyType));
  } catch (e) {
    sendError(res, e);
  }
});

export function buildChannels(text: string, limit = 1000, onlyType?: string) {
  const pl = parseM3U(text);
  const all = pl.entries.map((e) => ({
    name: e.name,
    url: e.url,
    logo: e.logo,
    group: e.group,
    tvgId: e.tvgId,
    type: classifyEntry({ name: e.name, group: e.group, url: e.url, attrs: e.attrs }).type,
  }));
  const channels = onlyType ? all.filter((c) => c.type === onlyType) : all;

  const groups = new Map<string, { count: number; types: Record<string, number> }>();
  for (const c of channels) {
    const g = groups.get(c.group) ?? { count: 0, types: {} };
    g.count++;
    g.types[c.type] = (g.types[c.type] ?? 0) + 1;
    groups.set(c.group, g);
  }
  const categories = Array.from(groups.entries()).map(([name, info]) => ({
    name,
    count: info.count,
    // tipo predominante da categoria
    type: Object.entries(info.types).sort((a, b) => b[1] - a[1])[0][0],
  }));

  return {
    channels: channels.slice(0, limit),
    categories,
    total: channels.length,
    epgUrl: pl.epgUrl,
    stats: {
      live: all.filter((c) => c.type === "live").length,
      movies: all.filter((c) => c.type === "movie").length,
      series: all.filter((c) => c.type === "series").length,
    },
  };
}
