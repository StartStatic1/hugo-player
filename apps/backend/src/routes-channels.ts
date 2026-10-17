import { Router } from "express";

export const channelsRouter = Router();

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 25000);

    const response = await fetch(playlistUrl, {
      signal: controller.signal,
      headers: { "User-Agent": "HugoPlayer/1.0" }
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

function classifyGroup(group: string): "live" | "movie" | "series" | "other" {
  const g = group.toLowerCase();
  if (g.includes("filme") || g.includes("movie") || g.includes("vod") || g.includes("cinema")) return "movie";
  if (g.includes("serie") || g.includes("series") || g.includes("temporada") || g.includes("season")) return "series";
  if (g.includes("live") || g.includes("tv") || g.includes("canal") || g.includes("sport") || g.includes("news") || g.includes("infantil") || g.includes("desenho")) return "live";
  return "other";
}

function parseM3U(content: string) {
  const lines = content.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const channels: { name: string; url: string; logo?: string; group?: string; type: string }[] = [];
  const groups = new Map<string, number>();

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("#EXTINF")) {
      const nameMatch = line.match(/,(.+)$/);
      const logoMatch = line.match(/tvg-logo="([^"]+)"/i);
      const groupMatch = line.match(/group-title="([^"]+)"/i);

      const name = nameMatch ? nameMatch[1].trim() : "Canal";
      const logo = logoMatch ? logoMatch[1] : undefined;
      const group = groupMatch ? groupMatch[1].trim() : "Outros";
      const type = classifyGroup(group);

      const next = lines[i + 1];
      if (next && !next.startsWith("#") && next.startsWith("http")) {
        channels.push({ name, url: next, logo, group, type });
        groups.set(group, (groups.get(group) || 0) + 1);
        i++;
      }
    }
  }

  const categories = Array.from(groups.entries())
    .map(([name, count]) => ({ name, count, type: classifyGroup(name) }))
    .sort((a, b) => b.count - a.count);

  return {
    channels: channels.slice(0, 500),
    categories,
    total: channels.length
  };
}
