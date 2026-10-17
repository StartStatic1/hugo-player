import { Router } from "express";

export const channelsRouter = Router();

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);
    const response = await fetch(playlistUrl, {
      signal: controller.signal,
      headers: { "User-Agent": "TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)" }
    });
    clearTimeout(timeout);
    if (!response.ok) return res.status(502).json({ error: `upstream_${response.status}` });
    const text = await response.text();
    if (!text || text.length < 10) return res.status(422).json({ error: "empty_playlist" });
    const parsed = parseM3U(text);
    res.json(parsed);
  } catch (e: any) {
    if (e.name === "AbortError") return res.status(504).json({ error: "timeout" });
    res.status(500).json({ error: e.message || "fetch_failed" });
  }
});

function classify(name: string, group: string, url: string): "live" | "movie" | "series" {
  const g = (group || "").toLowerCase();
  const n = (name || "").toLowerCase();
  const u = (url || "").toLowerCase();

  // 24H groups are live (looping content)
  if (g.startsWith("24h") || g.includes("24 horas") || g.includes("24hrs")) return "live";

  // Clear live groups
  if (g.includes("canais abertos") || g.includes("globo ") || g.includes("rede record") || g.includes("sbt") || g.includes("band ") || g.includes("sport") || g.includes("noticias") || g.includes("news")) return "live";

  // Streaming services = VOD
  if (g.includes("prime") || g.includes("netflix") || g.includes("disney") || g.includes("hbo") || g.includes("apple tv") || g.includes("crunchyroll") || g.includes("discovery") || g.includes("paramount") || g.includes("globoplay") || g.includes("look") || g.includes("claro video") || g.includes("directv")) return "movie";

  // Genre groups = usually movies
  if (g.includes("ação") || g.includes("comédia") || g.includes("drama") || g.includes("terror") || g.includes("romance") || g.includes("animação") || g.includes("suspense") || g.includes("lançamento") || g.includes("legendados")) return "movie";

  // Series indicators
  if (g.includes("serie") || g.includes("series") || g.includes("temporada") || g.includes("dorama") || n.match(/s\d{1,2}e\d{1,2}/i)) return "series";

  // URL hints
  if (u.includes(".ts") && !g.includes("filme") && !g.includes("movie")) return "live";
  if (u.includes(".mp4") || u.includes(".mkv")) return "movie";

  return "movie"; // default to movie for unknown VOD-style
}

function parseM3U(content: string) {
  const lines = content.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const channels: { name: string; url: string; logo?: string; group?: string; type: string }[] = [];
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
        channels.push({ name, url: next, logo, group, type });
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
    channels: channels.slice(0, 800),
    categories,
    total: channels.length,
    stats: {
      live: channels.filter(c => c.type === "live").length,
      movies: channels.filter(c => c.type === "movie").length,
      series: channels.filter(c => c.type === "series").length
    }
  };
}
