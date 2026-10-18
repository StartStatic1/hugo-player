import { Router } from "express";
import fs from "fs";
import { classifyEntry, parseEpisode } from "./content/classify.js";
import { parseXtreamUrl, buildCatalogFromXtream, fetchSeriesInfo } from "./content/xtream.js";

export const channelsRouter = Router();

const playlistCache = new Map<string, { channels: any[]; categories: any[]; stats: any; timestamp: number }>();
const CACHE_TTL = 10 * 60 * 1000;


channelsRouter.get("/series-episodes", async (req, res) => {
  const playlistUrl = req.query.url as string;
  const seriesId = parseInt(req.query.seriesId as string);
  if (!playlistUrl || !seriesId) return res.status(400).json({ error: "missing_params" });
  const xt = parseXtreamUrl(playlistUrl);
  if (!xt) return res.status(400).json({ error: "not_xtream" });
  try {
    const fetchJson = async (url: string) => {
      const r = await fetch(url, { headers: { "User-Agent": "TiviMate/4.7.0" } });
      if (!r.ok) throw new Error("upstream_" + r.status);
      return r.json();
    };
    const info = await fetchSeriesInfo(xt, seriesId, fetchJson);
    const episodes = info.seasons.flatMap(s => s.episodes.map(e => ({
      name: e.name || `${info.name} S${String(s.season).padStart(2,"0")}E${String(e.episode).padStart(2,"0")}`,
      url: e.url,
      season: s.season,
      episode: e.episode,
      logo: info.cover,
    })));
    res.json({ name: info.name, cover: info.cover, plot: info.plot, episodes });
  } catch (e: any) {
    res.status(502).json({ error: e.message || "series_info_failed" });
  }
});



channelsRouter.get("/latest.apk", (_req, res) => {
  const localPaths = [
    "/root/hugo-player/apps/backend/public/latest.apk",
    "/root/hugo-player/apps/backend/latest.apk",
    "/var/www/hugo/latest.apk",
  ];
  for (const p of localPaths) {
    if (fs.existsSync(p)) {
      res.setHeader("Content-Type", "application/vnd.android.package-archive");
      res.setHeader("Content-Disposition", 'attachment; filename="hugo-player.apk"');
      return res.sendFile(p);
    }
  }
  res.redirect("https://github.com/StartStatic1/hugo-player/releases/latest/download/app-release.apk");
});

channelsRouter.get("/version", (_req, res) => {
  try {
    const file = "/root/hugo-player/apps/backend/update.json";
    if (fs.existsSync(file)) {
      const data = JSON.parse(fs.readFileSync(file, "utf8"));
      return res.json(data);
    }
  } catch (e: any) {
    console.error("update.json error:", e.message);
    return res.json({ error: e.message, versionCode: 2 });
  }
  res.json({
    versionCode: 2,
    versionName: "0.2.0",
    notes: "Séries com episódios reais, correções de UI e estabilidade.",
    apkUrl: "https://github.com/StartStatic1/hugo-player/releases/latest/download/app-release.apk",
  });
});

channelsRouter.get("/", async (req, res) => {
  const playlistUrl = req.query.url as string;
  const page = parseInt(String(req.query.page || "0"), 10) || 0;
  // Sync do app pede limit=80000; o teto antigo de 200 cortava filmes/séries (só vinha live)
  const rawLimit = parseInt(String(req.query.limit || "50"), 10) || 50;
  const limit = Math.min(Math.max(rawLimit, 1), 100_000);
  const type = req.query.type as string | undefined;
  const group = req.query.group as string | undefined;
  if (!playlistUrl) return res.status(400).json({ error: "missing_playlist" });

  try {
    const xt = parseXtreamUrl(playlistUrl);
    if (xt) {
      const fetchJson = async (url: string) => {
        const r = await fetch(url, { headers: { "User-Agent": "TiviMate/4.7.0" } });
        if (!r.ok) throw new Error("upstream_" + r.status);
        return r.json();
      };
      const catalog = await buildCatalogFromXtream(xt, fetchJson);
      const channels: any[] = [];
      for (const it of catalog.live.items) channels.push({ name: it.name, url: it.url, logo: it.logo, group: it.group, type: "live" });
      for (const it of catalog.movies.items) channels.push({ name: it.name, url: it.url, logo: it.poster, group: it.group, type: "movie" });
      for (const it of catalog.series.items) channels.push({ name: it.name, url: `xtream://series/${it.seriesId}`, logo: it.cover, group: it.group, type: "series", seriesName: it.name, seriesId: it.seriesId, episodeCount: it.episodeCount, plot: it.plot });
      const categories = [
        ...catalog.live.categories.map((c, i) => ({ name: c.name, count: c.count, type: "live", position: i })),
        ...catalog.movies.categories.map((c, i) => ({ name: c.name, count: c.count, type: "movie", position: i })),
        ...catalog.series.categories.map((c, i) => ({ name: c.name, count: c.count, type: "series", position: i })),
      ];
      const data = { channels, categories, stats: catalog.stats, timestamp: Date.now() };
      playlistCache.set(playlistUrl, data);
      let filtered = channels;
      if (type && type !== "all") filtered = filtered.filter((c: any) => c.type === type);
      if (group) filtered = filtered.filter((c: any) => c.group === group);
      const start = page * limit;
      return res.json({
        channels: filtered.slice(start, start + limit),
        categories,
        total: filtered.length,
        page,
        limit,
        hasMore: start + limit < filtered.length,
        source: "xtream",
      });
    }

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

function parseM3U(content: string) {
  const lines = content.split(/\r?\n/);
  const channels: { name: string; url: string; logo?: string; group?: string; type: string; seriesName?: string; season?: number; episode?: number }[] = [];
  const catMap = new Map<string, number>();
  let i = 0;
  while (i < lines.length) {
    const line = lines[i].trim();
    if (line.startsWith("#EXTINF")) {
      const nameMatch = line.match(/,(.*)$/);
      const groupMatch = line.match(/group-title="([^"]*)"/i);
      const logoMatch = line.match(/tvg-logo="([^"]*)"/i);
      const name = (nameMatch?.[1] || "").trim();
      const group = (groupMatch?.[1] || "Geral").trim();
      const logo = logoMatch?.[1] || "";
      let next = "";
      let j = i + 1;
      while (j < lines.length) {
        const t = lines[j].trim();
        if (t && !t.startsWith("#")) { next = t; break; }
        if (t.startsWith("#EXTINF")) break;
        j++;
      }
      if (next) {
        const result = classifyEntry({ name, group, url: next });
        const type = result.type;
        const ep = result.episode || (type === "series" ? parseEpisode(name) : null);
        channels.push({
          name,
          url: next,
          logo,
          group,
          type,
          seriesName: ep?.show,
          season: ep?.season,
          episode: ep?.episode,
        });
        catMap.set(group, (catMap.get(group) || 0) + 1);
      }
      i = j;
      continue;
    }
    i++;
  }
  const categories = Array.from(catMap.entries()).map(([name, count], idx) => ({ name, count, position: idx }));
  const stats = {
    total: channels.length,
    live: channels.filter(c => c.type === "live").length,
    movie: channels.filter(c => c.type === "movie").length,
    series: channels.filter(c => c.type === "series").length,
  };
  return { channels, categories, stats, timestamp: Date.now() };
}
