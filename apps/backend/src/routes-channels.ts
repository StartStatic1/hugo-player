import { Router } from "express";

export const channelsRouter = Router();

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_url" });

  try {
    const response = await fetch(playlistUrl);
    if (!response.ok) throw new Error("Failed to fetch playlist");
    const text = await response.text();

    const channels = parseM3U(text);
    res.json({ channels });
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});

function parseM3U(content: string) {
  const lines = content.split("\n").map(l => l.trim()).filter(Boolean);
  const channels: { name: string; url: string; logo?: string }[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("#EXTINF")) {
      const nameMatch = line.match(/,(.+)$/);
      const logoMatch = line.match(/tvg-logo="([^"]+)"/);
      const name = nameMatch ? nameMatch[1].trim() : "Canal";
      const logo = logoMatch ? logoMatch[1] : undefined;

      const nextLine = lines[i + 1];
      if (nextLine && !nextLine.startsWith("#")) {
        channels.push({ name, url: nextLine, logo });
        i++;
      }
    }
  }
  return channels;
}
