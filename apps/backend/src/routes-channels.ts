import { Router } from "express";

export const channelsRouter = Router();

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  const page = parseInt(req.query.page as string) || 0;
  const limit = parseInt(req.query.limit as string) || 500;
  const type = req.query.type as string; // live, movie, series, or all
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 120000);
    const response = await fetch(playlistUrl, {
      signal: controller.signal,
      headers: { "User-Agent": "TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)" }
    });
    clearTimeout(timeout);
    if (!response.ok) return res.status(502).json({ error: `upstream_${response.status}` });
    const text = await response.text();
    if (!text || text.length < 10) return res.status(422).json({ error: "empty_playlist" });
    const parsed = parseM3U(text, page, limit, type);
    res.json(parsed);
  } catch (e: any) {
    if (e.name === "AbortError") return res.status(504).json({ error: "timeout" });
    res.status(500).json({ error: e.message || "fetch_failed" });
  }
});

function classify(name: string, group: string, url: string): "live" | "movie" | "series" {
  const g = (group || "").toLowerCase().trim();
  const u = (url || "").toLowerCase();

  if (g.startsWith("canais") || g.startsWith("tv ao vivo") || g.startsWith("live") || g.includes("[24h]") || g.includes("globo") || g.includes("record") || g.includes("sbt") || g.includes("band") || g.includes("sport") || g.includes("noticias") || g.includes("telecine") || g.includes("a&e") || g.includes("amc")) return "live";
  if (g.startsWith("series") || g.startsWith("séries") || g.startsWith("serie |") || g.includes("novela") || g.includes("dorama") || g.includes("temporada")) return "series";
  if (g.startsWith("filmes") || g.startsWith("filme |") || g.includes("filme")) return "movie";
  if (u.includes("/movie/") || u.includes("/movies/")) return "movie";
  if (u.includes("/series/") || u.includes("/serie/")) return "series";
  if (u.includes(".mp4") || u.includes(".mkv") || u.includes(".avi")) return "movie";
  if (u.includes(".ts")) return "live";
  return "live";
}

function parseM3U(content: string, page: number, limit: number, typeFilter?: string) {
  const lines = content.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const allChannels: { name: string; url: string; logo?: string; group?: string; type: string }[] = [];
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
        allChannels.push({ name, url: next, logo, group, type });
        const existing = groups.get(group);
        if (existing) existing.count++;
        else groups.set(group, { count: 1, type });
        i++;
      }
    }
  }

  // Filter by type if specified
  let filtered = allChannels;
  if (typeFilter && typeFilter !== "all") {
    filtered = allChannels.filter(c => c.type === typeFilter);
  }

  // Paginate
  const start = page * limit;
  const channels = filtered.slice(start, start + limit);
  const hasMore = start + limit < filtered.length;

  const categories = Array.from(groups.entries())
    .map(([name, info]) => ({ name, count: info.count, type: info.type }))
    .sort((a, b) => b.count - a.count);

  return {
    channels,
    categories,
    total: filtered.length,
    page,
    limit,
    hasMore,
    stats: {
      live: allChannels.filter(c => c.type === "live").length,
      movies: allChannels.filter(c => c.type === "movie").length,
      series: allChannels.filter(c => c.type === "series").length
    }
  };
}
