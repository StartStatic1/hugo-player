import { Router } from "express";
import { supportWhatsappUrl } from "./support.js";
import fs from "fs";
import { classifyEntry, parseEpisode } from "./content/classify.js";
import { parseXtreamUrl, buildCatalogFromXtream, fetchSeriesInfo, apiUrl, parseXtreamAccount } from "./content/xtream.js";
import { blockKey, isBlocked, markBlocked } from "./content/blocked-hosts.js";
import { rememberChannels } from "./content/known-channels.js";
import { cacheKey, statCatalogCache, readCatalogCache, writeCatalogCache, CATALOG_FRESH_MS, CATALOG_STALE_MS, CATALOG_FALLBACK_MS } from "./content/catalog-cache.js";
import { isRegisteredUrl, noteDeprecated, playlistUrlFor, type StoredPlaylist } from "./playlist-access.js";

export const channelsRouter = Router();

/** Ligado pelo main.ts (sem isso — testes — as rotas ficam como antes). */
export interface ChannelsAccess {
  /** deviceId se id+key conferem */
  authenticate(id?: string, key?: string): string | null;
  playlists(): StoredPlaylist[];
}
let access: ChannelsAccess | null = null;
export function setChannelsAccess(a: ChannelsAccess | null) {
  access = a;
}

/** Versão mínima obrigatória (config do admin) somada à resposta de /channels/version. */
let versionConfig: () => { minVersionCode: number } = () => ({ minVersionCode: 0 });
export function setVersionConfig(fn: () => { minVersionCode: number }) {
  versionConfig = fn;
}

/**
 * Resolve o link da playlist sem credenciais na URL:
 *  - ?playlistId=... + x-device-id/x-device-key  -> link guardado no servidor (novo)
 *  - ?url=... (apps antigos)                       -> só se for de uma playlist cadastrada (será removido)
 */
const sourceGate: import("express").RequestHandler = (req, res, next) => {
  if (!access) return next();
  const playlistId = String(req.query.playlistId || "");
  if (playlistId) {
    const deviceId = access.authenticate(req.header("x-device-id"), req.header("x-device-key"));
    if (!deviceId) return res.status(401).json({ error: "invalid_credentials" });
    const url = playlistUrlFor(deviceId, playlistId, access.playlists());
    if (!url) return res.status(404).json({ error: "playlist_not_found" });
    res.locals.playlistUrl = url;
    return next();
  }
  const url = String(req.query.url || "");
  if (!url) return next();
  if (!isRegisteredUrl(url, access.playlists())) return res.status(403).json({ error: "playlist_not_registered" });
  noteDeprecated(`GET /channels${req.path === "/" ? "" : req.path}`, url);
  res.locals.playlistUrl = url;
  next();
};

const BLOCKED_HINT =
  "Provedor bloqueou o IP do servidor (comum em VPS). O app no celular funciona; o painel barre data center.";

const UPDATE_JSON_REMOTE =
  process.env.UPDATE_JSON_URL ||
  "https://raw.githubusercontent.com/StartStatic1/hugo-player/main/apps/backend/update.json";

channelsRouter.get("/series-episodes", sourceGate, async (req, res) => {
  const playlistUrl = (res.locals.playlistUrl ?? req.query.url) as string;
  const seriesId = parseInt(req.query.seriesId as string);
  if (!playlistUrl || !seriesId) return res.status(400).json({ error: "missing_params" });
  const xt = parseXtreamUrl(playlistUrl);
  if (!xt) return res.status(400).json({ error: "not_xtream" });
  try {
    const fetchJson = async (url: string) => {
      const r = await fetch(url, { headers: { "User-Agent": "VLC/3.0.20 LibVLC/3.0.20" } });
      if (!r.ok) throw new Error("upstream_" + r.status);
      return r.json();
    };
    const info = await fetchSeriesInfo(xt, seriesId, fetchJson);
    const episodes = info.seasons.flatMap((s) =>
      s.episodes.map((e) => ({
        name: e.name || `${info.name} S${String(s.season).padStart(2, "0")}E${String(e.episode).padStart(2, "0")}`,
        url: e.url,
        season: s.season,
        episode: e.episode,
        // miniatura do próprio episódio quando o painel tem; senão a capa da série (apps antigos usam "logo")
        logo: e.cover ?? info.cover,
        ...(e.plot ? { plot: e.plot } : {}),
        ...(e.durationSecs ? { durationSecs: e.durationSecs } : {}),
      })),
    );
    res.json({ name: info.name, cover: info.cover, plot: info.plot, episodes });
  } catch (e: any) {
    res.status(502).json({ error: e.message || "series_info_failed" });
  }
});

/**
 * Só o vencimento da conta Xtream (user_info do login da player_api): consulta leve,
 * sem baixar a lista nem mexer no cache. M3U = { account: null, xtream: false }.
 */
channelsRouter.get("/account", sourceGate, async (req, res) => {
  const playlistUrl = (res.locals.playlistUrl ?? req.query.url) as string;
  if (!playlistUrl) return res.status(400).json({ error: "missing_playlist" });
  const xt = parseXtreamUrl(playlistUrl);
  if (!xt) return res.json({ xtream: false, account: null });
  try {
    const r = await fetch(apiUrl(xt), {
      headers: { "User-Agent": "VLC/3.0.20 LibVLC/3.0.20", Accept: "*/*" },
      signal: AbortSignal.timeout(12_000),
    });
    if (!r.ok) return res.status(502).json({ error: "upstream_" + r.status });
    const account = parseXtreamAccount(await r.json().catch(() => null));
    if (!account) return res.status(502).json({ error: "no_user_info" });
    res.json({ xtream: true, account });
  } catch (e: any) {
    res.status(504).json({ error: e?.name === "TimeoutError" ? "timeout" : "account_failed" });
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

const LATEST_APK_GITHUB = "https://github.com/StartStatic1/hugo-player/releases/latest/download/app-release.apk";

/** Versão publicada (update.json do GitHub; sem rede usa o arquivo local). */
export async function currentVersion(): Promise<Record<string, any>> {
  try {
    const r = await fetch(UPDATE_JSON_REMOTE, {
      headers: { "Cache-Control": "no-cache", Accept: "application/json" },
    });
    if (r.ok) {
      const data = await r.json();
      if (data && typeof data.versionCode === "number") return { ...data, ...versionConfig() };
    }
  } catch (e: any) {
    console.warn("update.json remote failed:", e.message);
  }
  try {
    const file = "/root/hugo-player/apps/backend/update.json";
    if (fs.existsSync(file)) {
      const data = JSON.parse(fs.readFileSync(file, "utf8"));
      return { ...data, ...versionConfig() };
    }
  } catch (e: any) {
    console.error("update.json local error:", e.message);
  }
  return {
    versionCode: 5,
    versionName: "0.5.0",
    notes: "Atualização disponível.",
    apkUrl: LATEST_APK_GITHUB,
  };
}

channelsRouter.get("/version", async (_req, res) => {
  res.json({ ...(await currentVersion()), supportWhatsappUrl: supportWhatsappUrl() });
});

/** Só links https do GitHub do projeto (ou do nosso domínio) — nunca redireciona para outro lugar. */
export function safeApkUrl(url: unknown): string {
  if (typeof url !== "string") return LATEST_APK_GITHUB;
  try {
    const u = new URL(url);
    const ok = u.protocol === "https:" && (
      (u.hostname === "github.com" && u.pathname.startsWith("/StartStatic1/hugo-player/releases/")) ||
      u.hostname.endsWith(".streamflixvip.online"));
    return ok ? u.toString() : LATEST_APK_GITHUB;
  } catch {
    return LATEST_APK_GITHUB;
  }
}

/** Link fixo de download: sempre manda (302) para o APK da versão publicada agora. */
export const downloadLatest: import("express").RequestHandler = async (_req, res) => {
  const v = await currentVersion();
  res.setHeader("Cache-Control", "no-store");
  if (v.versionName) res.setHeader("X-Hugo-Version", String(v.versionName));
  res.redirect(302, safeApkUrl(v.apkUrl));
};
channelsRouter.get("/download", downloadLatest);

/** Recebe M3U baixada no aparelho (fallback 403) e devolve o mesmo JSON do GET /channels. */
channelsRouter.post("/from-m3u", async (req, res) => {
  try {
    const rawLimit = parseInt(String(req.query.limit || "80000"), 10) || 80000;
    const limit = Math.min(Math.max(rawLimit, 1), 100_000);
    const type = req.query.type as string | undefined;
    const group = req.query.group as string | undefined;
    let text = "";
    if (typeof req.body === "string") text = req.body;
    else if (Buffer.isBuffer(req.body)) text = req.body.toString("utf8");
    else if (req.body && typeof (req.body as any).m3u === "string") text = (req.body as any).m3u;
    else text = String(req.body || "");
    if (!text || !text.includes("#EXTINF")) {
      return res.status(422).json({ error: "empty_playlist" });
    }
    if (text.length > 100 * 1024 * 1024) {
      return res.status(413).json({ error: "playlist_too_large" });
    }
    const data = parseM3U(text);
    let filtered = data.channels;
    if (type && type !== "all") filtered = filtered.filter((c: any) => c.type === type);
    if (group) filtered = filtered.filter((c: any) => c.group === group);
    const page = parseInt(String(req.query.page || "0"), 10) || 0;
    const start = page * limit;
    return res.json({
      channels: filtered.slice(start, start + limit),
      categories: data.categories,
      total: filtered.length,
      page,
      limit,
      hasMore: start + limit < filtered.length,
      source: "client_m3u",
      stats: data.stats,
    });
  } catch (e: any) {
    res.status(500).json({ error: e.message || "parse_failed" });
  }
});

class UpstreamHttpError extends Error {
  constructor(public status: number, public body: any) {
    super(String(body?.error || "upstream_error"));
  }
}

type Catalog = { channels: any[]; categories: any[]; stats?: any; account?: any; source?: string; timestamp: number };

/** Baixa e converte a playlist do provedor (Xtream API → get.php M3U → M3U puro). Lança UpstreamHttpError. */
async function buildCatalog(playlistUrl: string): Promise<Catalog> {
  const ua = "VLC/3.0.20 LibVLC/3.0.20";
  const xt = parseXtreamUrl(playlistUrl);
  if (xt) {
    let data: Catalog | null = null;
    const hostKey = blockKey(xt.baseUrl, xt.username);
    if (isBlocked(hostKey)) throw new UpstreamHttpError(502, { error: "upstream_403", hint: BLOCKED_HINT });
    let forbidden = false;
    try {
      const fetchJson = async (url: string) => {
        const r = await fetch(url, { headers: { "User-Agent": ua, Accept: "*/*" } });
        if (r.status === 403) forbidden = true;
        if (!r.ok) throw new Error("upstream_" + r.status);
        return r.json();
      };
      const catalog = await buildCatalogFromXtream(xt, fetchJson);
      if (catalog.stats.live + catalog.stats.movies + catalog.stats.series > 0) {
        const channels: any[] = [];
        for (const it of catalog.live.items)
          channels.push({ name: it.name, url: it.url, logo: it.logo, group: it.group, type: "live", epgId: it.epgId ?? undefined });
        for (const it of catalog.movies.items)
          channels.push({ name: it.name, url: it.url, logo: it.poster, group: it.group, type: "movie" });
        for (const it of catalog.series.items)
          channels.push({
            name: it.name,
            url: `xtream://series/${it.seriesId}`,
            logo: it.cover,
            group: it.group,
            type: "series",
            seriesName: it.name,
            seriesId: it.seriesId,
            episodeCount: it.episodeCount,
            plot: it.plot,
          });
        const categories = [
          ...catalog.live.categories.map((c, i) => ({ name: c.name, count: c.count, type: "live", position: i })),
          ...catalog.movies.categories.map((c, i) => ({ name: c.name, count: c.count, type: "movie", position: i })),
          ...catalog.series.categories.map((c, i) => ({ name: c.name, count: c.count, type: "series", position: i })),
        ];
        data = { channels, categories, stats: catalog.stats, timestamp: Date.now(), account: catalog.account ?? null, source: "xtream" };
      }
    } catch (e: any) {
      if (forbidden || e?.message === "upstream_403") {
        markBlocked(hostKey);
        console.warn("xtream api 403 (VPS IP blocked), skipping get.php:", blockKey(xt.baseUrl));
        throw new UpstreamHttpError(502, { error: "upstream_403", hint: BLOCKED_HINT });
      }
      console.warn("xtream api failed, trying get.php M3U:", e?.message);
    }
    if (data) return data;
    const m3uUrl =
      `${xt.baseUrl}/get.php?username=${encodeURIComponent(xt.username)}` +
      `&password=${encodeURIComponent(xt.password)}&type=m3u_plus&output=ts`;
    const parsed = await fetchM3U(m3uUrl, ua, () => markBlocked(hostKey));
    return { ...parsed, account: null, source: "m3u_fallback", timestamp: Date.now() };
  }
  const parsed = await fetchM3U(playlistUrl, ua, () => {});
  return { ...parsed, source: "m3u", timestamp: Date.now() };
}

async function fetchM3U(url: string, ua: string, on403: () => void) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 180000);
  try {
    const response = await fetch(url, { signal: controller.signal, redirect: "follow", headers: { "User-Agent": ua, Accept: "*/*" } });
    if (!response.ok) {
      if (response.status === 403) on403();
      throw new UpstreamHttpError(502, { error: `upstream_${response.status}`, hint: response.status === 403 ? BLOCKED_HINT : undefined });
    }
    const text = await response.text();
    if (!text || !text.includes("#EXTINF")) throw new UpstreamHttpError(422, { error: "empty_playlist" });
    return parseM3U(text);
  } finally {
    clearTimeout(timeout);
  }
}

/** Uma busca por playlist de cada vez (vários aparelhos abrindo juntos esperam a mesma). */
const inflight = new Map<string, Promise<Catalog>>();
/** No máximo 3 listas sendo baixadas/convertidas ao mesmo tempo (cada uma pode ocupar ~100 MB de RAM). */
let building = 0;
const waiters: (() => void)[] = [];
async function withBuildSlot<T>(fn: () => Promise<T>): Promise<T> {
  while (building >= 3) await new Promise<void>((r) => waiters.push(r));
  building++;
  try {
    return await fn();
  } finally {
    building--;
    waiters.shift()?.();
  }
}
function refreshCatalog(playlistUrl: string): Promise<Catalog> {
  const key = cacheKey(playlistUrl);
  let p = inflight.get(key);
  if (!p) {
    p = withBuildSlot(() => buildCatalog(playlistUrl))
      .then(async (data) => {
        rememberChannels(playlistUrl, data.channels.filter((c: any) => c.type === "live"));
        await writeCatalogCache(playlistUrl, data).catch((e) => console.warn("catalog cache write failed:", e?.message));
        return data;
      })
      .finally(() => inflight.delete(key));
    inflight.set(key, p);
  }
  return p;
}

/**
 * Cache em disco (JSON gzip) por playlist:
 *  - < CATALOG_FRESH_MS: responde do cache (≈ instantâneo), sem ir ao provedor;
 *  - até CATALOG_STALE_MS: responde do cache e atualiza em segundo plano;
 *  - provedor falhou (403/530/fora do ar) e existe cópia de até CATALOG_FALLBACK_MS: responde a cópia
 *    (o app não precisa baixar 100 MB no aparelho).
 */
async function loadCatalog(playlistUrl: string, forceFresh: boolean): Promise<{ data: Catalog; cache: string; file?: string }> {
  const meta = forceFresh ? null : await statCatalogCache(playlistUrl);
  const age = meta ? Date.now() - meta.mtimeMs : Infinity;
  if (meta && age < CATALOG_FRESH_MS) return { data: await readCatalogCache(meta.file), cache: "hit", file: meta.file };
  if (meta && age < CATALOG_STALE_MS) {
    refreshCatalog(playlistUrl).catch((e) => console.warn("background refresh failed:", e?.message));
    return { data: await readCatalogCache(meta.file), cache: "stale", file: meta.file };
  }
  try {
    return { data: await refreshCatalog(playlistUrl), cache: "miss" };
  } catch (e) {
    const old = await statCatalogCache(playlistUrl);
    if (old && Date.now() - old.mtimeMs < CATALOG_FALLBACK_MS) {
      console.warn("provider failed, serving cached copy:", (e as any)?.message);
      return { data: await readCatalogCache(old.file), cache: "fallback", file: old.file };
    }
    throw e;
  }
}

channelsRouter.get("/", sourceGate, async (req, res) => {
  const playlistUrl = (res.locals.playlistUrl ?? req.query.url) as string;
  const page = parseInt(String(req.query.page || "0"), 10) || 0;
  const rawLimit = parseInt(String(req.query.limit || "50"), 10) || 50;
  // apps pedem limit=80000 querendo "a lista inteira": a partir disso não corta (777mmega tem 80.383)
  const limit = rawLimit >= 80_000 ? 500_000 : Math.min(Math.max(rawLimit, 1), 100_000);
  const type = req.query.type as string | undefined;
  const group = req.query.group as string | undefined;
  if (!playlistUrl) return res.status(400).json({ error: "missing_playlist" });

  try {
    const { data, cache } = await loadCatalog(playlistUrl, req.query.fresh === "1");
    res.setHeader("X-Hugo-Cache", cache);
    res.setHeader("X-Hugo-Total", String(data.channels.length));
    let filtered = data.channels;
    if (type && type !== "all") filtered = filtered.filter((c: any) => c.type === type);
    if (group) filtered = filtered.filter((c: any) => c.group === group);
    const start = page * limit;
    // "total" vem ANTES de "channels": o app já sabe o tamanho real e mostra progresso de verdade
    return res.json({
      total: filtered.length,
      page,
      limit,
      hasMore: start + limit < filtered.length,
      source: data.source ?? "m3u",
      // vencimento da conta Xtream (null quando veio pelo M3U)
      account: data.account ?? null,
      stats: data.stats,
      categories: data.categories,
      channels: filtered.slice(start, start + limit),
    });
  } catch (e: any) {
    if (e instanceof UpstreamHttpError) return res.status(e.status).json(e.body);
    if (e.name === "AbortError") return res.status(504).json({ error: "timeout" });
    // provedor inalcançável da VPS (DNS, conexão recusada/caída, TLS): 502 → o app baixa a lista no próprio aparelho.
    if (isUpstreamNetworkError(e)) {
      console.warn("upstream unreachable from server:", networkErrorCode(e));
      return res.status(502).json({ error: "upstream_unreachable", hint: BLOCKED_HINT });
    }
    console.error("GET /channels failed:", e?.message);
    res.status(500).json({ error: e.message || "fetch_failed" });
  }
});

/** Erro de rede do fetch (undici): "fetch failed" com cause.code ECONNREFUSED/ENOTFOUND/ECONNRESET/UND_ERR_* etc. */
export function isUpstreamNetworkError(e: any): boolean {
  if (!e) return false;
  if (e.name === "TypeError" && /fetch failed/i.test(String(e.message))) return true;
  const code = networkErrorCode(e);
  return !!code && /^(E[A-Z]+|UND_ERR_.*|CERT_.*|ERR_TLS.*|DEPTH_ZERO.*|UNABLE_TO_.*|SELF_SIGNED.*)$/.test(code);
}
function networkErrorCode(e: any): string {
  return String(e?.cause?.code ?? e?.code ?? e?.cause?.name ?? "").slice(0, 40);
}

function parseM3U(content: string) {
  const lines = content.split(/\r?\n/);
  const channels: {
    name: string;
    url: string;
    logo?: string;
    group?: string;
    type: string;
    seriesName?: string;
    season?: number;
    episode?: number;
    epgId?: string;
  }[] = [];
  const catMap = new Map<string, number>();
  let i = 0;
  while (i < lines.length) {
    const line = lines[i].trim();
    if (line.startsWith("#EXTINF")) {
      const nameMatch = line.match(/,(.*)$/);
      const groupMatch = line.match(/group-title="([^"]*)"/i);
      const logoMatch = line.match(/tvg-logo="([^"]*)"/i);
      const tvgIdMatch = line.match(/tvg-id="([^"]*)"/i);
      const name = (nameMatch?.[1] || "").trim();
      const group = (groupMatch?.[1] || "Geral").trim();
      const logo = logoMatch?.[1] || "";
      let next = "";
      let j = i + 1;
      while (j < lines.length) {
        const t = lines[j].trim();
        if (t && !t.startsWith("#")) {
          next = t;
          break;
        }
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
          ...(type === "live" && tvgIdMatch?.[1]?.trim() ? { epgId: tvgIdMatch[1].trim() } : {}),
        });
        catMap.set(group, (catMap.get(group) || 0) + 1);
      }
      i = j;
      continue;
    }
    i++;
  }
  const categories = Array.from(catMap.entries()).map(([name, count], idx) => ({
    name,
    count,
    position: idx,
  }));
  const stats = {
    total: channels.length,
    live: channels.filter((c) => c.type === "live").length,
    movie: channels.filter((c) => c.type === "movie").length,
    series: channels.filter((c) => c.type === "series").length,
  };
  return { channels, categories, stats, timestamp: Date.now() };
}
