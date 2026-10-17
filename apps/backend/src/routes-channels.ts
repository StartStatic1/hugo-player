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

    if (!response.ok) {
      return res.status(502).json({ error: `upstream_${response.status}` });
    }

    const text = await response.text();
    if (!text || text.length < 10) {
      return res.status(422).json({ error: "empty_playlist" });
    }

    const parsed = parseM3U(text);
    res.json(parsed);
  } catch (e: any) {
    if (e.name === "AbortError") {
      return res.status(504).json({ error: "timeout_fetching_playlist" });
    }
    res.status(500).json({ error: e.message || "fetch_failed" });
  }
});

function classify(name: string, group: string, url: string): "live" | "movie" | "series" | "other" {
  const g = (group || "").toLowerCase();
  const n = (name || "").toLowerCase();
  const u = (url || "").toLowerCase();

  // Series indicators
  if (g.includes("serie") || g.includes("series") || g.includes("temporada") || g.includes("season") || g.includes("dorama") || n.includes("s0") || n.includes("e0")) return "series";

  // Movie indicators
  if (g.includes("filme") || g.includes("movie") || g.includes("vod") || g.includes("cinema") || g.includes("netflix") || g.includes("hbo") || g.includes("prime") || g.includes("disney") || g.includes("paramount") || g.includes("globoplay") || g.includes("telecine") || n.includes("(20") || n.includes("(19")) return "movie";

  // Live indicators
  if (g.includes("live") || g.includes("tv") || g.includes("canal") || g.includes("sport") || g.includes("news") || g.includes("infantil") || g.includes("desenho") || g.includes("aberto") || g.includes("globo") || g.includes("record") || g.includes("sbt") || g.includes("band") || u.includes(".ts") || u.includes("live")) return "live";

  // Default: if URL looks like VOD (mp4, mkv) treat as movie
  if (u.includes(".mp4") || u.includes(".mkv") || u.includes(".avi")) return "movie";

  return "other";
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
        if (existing) {
          existing.count++;
        } else {
          groups.set(group, { count: 1, type });
        }
        i++;
      }
    }
  }

  const categories = Array.from(groups.entries())
    .map(([name, info]) => ({ name, count: info.count, type: info.type }))
    .sort((a, b) => b.count - a.count);

  return {
    channels: channels.slice(0, 1000),
    categories,
    total: channels.length,
    stats: {
      live: channels.filter(c => c.type === "live").length,
      movies: channels.filter(c => c.type === "movie").length,
      series: channels.filter(c => c.type === "series").length
    }
  };
}
