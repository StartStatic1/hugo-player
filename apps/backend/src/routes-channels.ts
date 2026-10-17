import { Router } from "express";

export const channelsRouter = Router();

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });

  try {
    const response = await fetch(playlistUrl);
    if (!response.ok) throw new Error("Failed to fetch playlist");
    const text = await response.text();

    const parsed = parseM3U(text);
    res.json(parsed);
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});

function parseM3U(content: string) {
  const lines = content.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const channels: { name: string; url: string; logo?: string; group?: string }[] = [];
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

      const next = lines[i + 1];
      if (next && !next.startsWith("#")) {
        channels.push({ name, url: next, logo, group });
        groups.set(group, (groups.get(group) || 0) + 1);
        i++;
      }
    }
  }

  const categories = Array.from(groups.entries()).map(([name, count]) => ({ name, count }));

  return {
    channels,
    categories,
    total: channels.length
  };
}
