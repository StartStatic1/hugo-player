import { Router } from "express";

export const channelsRouter = Router();

const playlistCache = new Map<string, { channels: any[]; categories: any[]; stats: any; timestamp: number }>();
const CACHE_TTL = 10 * 60 * 1000;

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  const page = parseInt(req.query.page as string) || 0;
  const limit = Math.min(parseInt(req.query.limit as string) || 200, 100000);
  const type = req.query.type as string;
  const group = req.query.group as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });

  try {
    const cached = playlistCache.get(playlistUrl);
    let data;
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      data = cached;
    } else {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 180000);
      const response = await fetch(playlistUrl, {
        signal: controller.signal,
        headers: { "User-Agent": "TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)" }
      });
      clearTimeout(timeout);
      if (!response.ok) return res.status(502).json({ error: `upstream_${response.status}` });
      const text = await response.text();
      if (!text || text.length < 10) return res.status(422).json({ error: "empty_playlist" });
      data = parseM3U(text);
      playlistCache.set(playlistUrl, { ...data, timestamp: Date.now() });
    }

    let filtered = data.channels;
    if (type && type !== "all") filtered = filtered.filter((c: any) => c.type === type);
    if (group) filtered = filtered.filter((c: any) => c.group === group);

    const start = page * limit;
    const channels = filtered.slice(start, start + limit);
    const hasMore = start + limit < filtered.length;

    res.json({
      channels,
      categories: data.categories,
      total: filtered.length,
      page,
      limit,
      hasMore,
      stats: data.stats
    });
  } catch (e: any) {
    if (e.name === "AbortError") return res.status(504).json({ error: "timeout" });
    res.status(500).json({ error: e.message || "fetch_failed" });
  }
});

function extractSeriesInfo(name: string): { seriesName: string; season: number; episode: number } | null {
  const patterns = [
    /(.+?)\s*[Ss](\d+)\s*[Ee](\d+)/i,
    /(.+?)\s*(\d+)x(\d+)/i,
    /(.+?)\s*[Tt]emp(?:orada)?\s*(\d+)\s*[Ee]p(?:is[oó]dio)?\s*(\d+)/i,
    /(.+?)\s*[Ss]eason\s*(\d+)\s*[Ee]pisode\s*(\d+)/i
  ];
  for (const p of patterns) {
    const m = name.match(p);
    if (m) {
      return {
        seriesName: m[1].trim().replace(/[-_]+$/, "").trim(),
        season: parseInt(m[2]),
        episode: parseInt(m[3])
      };
    }
  }
  return null;
}

function classify(name: string, group: string, url: string): "live" | "movie" | "series" {
  const g = (group || "").toLowerCase().trim();
  const u = (url || "").toLowerCase();

  if (extractSeriesInfo(name)) return "series";

  if (g.startsWith("canais") || g.startsWith("tv ao vivo") || g.startsWith("live") || g.includes("[24h]") ||
      g.includes("globo") || g.includes("record") || g.includes("sbt") || g.includes("band") ||
      g.includes("sport") || g.includes("noticias") || g.includes("telecine") || g.includes("premiere")) return "live";

  if (g.startsWith("series") || g.startsWith("séries") || g.startsWith("serie") ||
      g.includes("novela") || g.includes("dorama") || g.includes("anime") ||
      g.includes("turca") || g.includes("mexica") || g.includes("reel") ||
      g.includes("apple+") || g.includes("star+") || g.includes("disney") ||
      g.includes("hbo") || g.includes("netflix") || g.includes("prime") ||
      g.includes("paramount") || g.includes("legendada")) return "series";

  if (g.startsWith("filmes") || g.startsWith("filme") || g.includes("filme")) return "movie";

  if (u.includes("/series/") || u.includes("/serie/")) return "series";
  if (u.includes("/movie/") || u.includes("/movies/")) return "movie";
  if (u.includes(".mp4") || u.includes(".mkv") || u.includes(".avi")) return "movie";
  if (u.includes(".ts")) return "live";

  return "live";
}

function parseM3U(content: string) {
  const lines = content.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const channels: { name: string; url: string; logo?: string; group?: string; type: string; seriesName?: string; season?: number; episode?: number }[] = [];
  const groups = new Map<string, { count: number; type: string }>();

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("#EXTINF")) {
      const nameMatch = line.match(/,(.+)$/);
      const logoMatch = line.match(/tvg-logo="([^"]+)"/i);
      const groupMatch = line.match(/group-title="([^"]+)"/i);
      const name = nameMatch ? nameMatch[1].trim() : "Canal";
      const logo = logoMatch ? logoMatch[1] : undefined;
      const group = groupMatch ? groupMatch[1].trim() : "Outros";
      const next = lines[i + 1];
      if (next && !next.startsWith("#") && next.startsWith("http")) {
        const type = classify(name, group, next);
        const seriesInfo = type === "series" ? extractSeriesInfo(name) : null;
        channels.push({
          name,
          url: next,
          logo,
          group,
          type,
          seriesName: seriesInfo?.seriesName,
          season: seriesInfo?.season,
          episode: seriesInfo?.episode
        });
        const existing = groups.get(group);
        if (existing) existing.count++;
        else groups.set(group, { count: 1, type });
        i++;
      }
    }
  }

  const categories = Array.from(groups.entries())
    .map(([name, info]) => ({ name, count: info.count, type: info.type }))
    .sort((a, b) => b.count - a.count);

  return {
    channels,
    categories,
    stats: {
      live: channels.filter(c => c.type === "live").length,
      movies: channels.filter(c => c.type === "movie").length,
      series: channels.filter(c => c.type === "series").length
    }
  };
}
