/** Suporte à API Xtream Codes (player_api.php). */
import { shortId, type Catalog, type CatalogCategory, type Episode, type LiveItem, type MovieItem, type Season, type SeriesItem } from "./catalog.js";

export interface XtreamCredentials {
  baseUrl: string; // ex.: http://host:8080
  username: string;
  password: string;
}

export type JsonFetcher = (url: string) => Promise<unknown>;

/**
 * Detecta credenciais Xtream numa URL de playlist:
 *  - http://host:port/get.php?username=U&password=P&type=m3u_plus
 *  - http://host:port/player_api.php?username=U&password=P
 *  - qualquer URL com username+password na query quando type=xtream
 */
export function parseXtreamUrl(raw: string, forceXtream = false): XtreamCredentials | null {
  let u: URL;
  try {
    u = new URL(raw.trim());
  } catch {
    return null;
  }
  const username = u.searchParams.get("username");
  const password = u.searchParams.get("password");
  if (!username || !password) return null;
  const isXtreamPath = /\/(get|player_api|panel_api|xmltv)\.php$/i.test(u.pathname);
  if (!isXtreamPath && !forceXtream) return null;
  return { baseUrl: `${u.protocol}//${u.host}`, username, password };
}

export function apiUrl(c: XtreamCredentials, action?: string, extra: Record<string, string | number> = {}): string {
  const u = new URL("/player_api.php", c.baseUrl);
  u.searchParams.set("username", c.username);
  u.searchParams.set("password", c.password);
  if (action) u.searchParams.set("action", action);
  for (const [k, v] of Object.entries(extra)) u.searchParams.set(k, String(v));
  return u.toString();
}

export function xmltvUrl(c: XtreamCredentials): string {
  const u = new URL("/xmltv.php", c.baseUrl);
  u.searchParams.set("username", c.username);
  u.searchParams.set("password", c.password);
  return u.toString();
}

const enc = encodeURIComponent;
export function liveStreamUrl(c: XtreamCredentials, streamId: number | string, ext = "m3u8") {
  return `${c.baseUrl}/live/${enc(c.username)}/${enc(c.password)}/${streamId}.${ext}`;
}
export function movieStreamUrl(c: XtreamCredentials, streamId: number | string, ext = "mp4") {
  return `${c.baseUrl}/movie/${enc(c.username)}/${enc(c.password)}/${streamId}.${ext || "mp4"}`;
}
export function episodeStreamUrl(c: XtreamCredentials, episodeId: number | string, ext = "mp4") {
  return `${c.baseUrl}/series/${enc(c.username)}/${enc(c.password)}/${episodeId}.${ext || "mp4"}`;
}

type Obj = Record<string, any>;
const asArray = (v: unknown): Obj[] => (Array.isArray(v) ? (v as Obj[]) : []);
const str = (v: unknown): string => (v === null || v === undefined ? "" : String(v)).trim();
const num = (v: unknown): number | undefined => {
  const n = typeof v === "number" ? v : parseFloat(str(v));
  return Number.isFinite(n) ? n : undefined;
};
const img = (v: unknown): string | null => {
  const s = str(v);
  return /^https?:\/\//i.test(s) ? s : null;
};
const yearOf = (v: unknown): number | undefined => {
  const m = str(v).match(/(19|20)\d{2}/);
  return m ? parseInt(m[0], 10) : undefined;
};

function categoryList(raw: Obj[], prefix: string, counts: Map<string, number>): CatalogCategory[] {
  const out: CatalogCategory[] = raw.map((c) => ({
    id: `${prefix}-${str(c.category_id)}`,
    name: str(c.category_name) || "Outros",
    count: counts.get(str(c.category_id)) ?? 0,
  }));
  // Categorias referenciadas por streams mas ausentes da lista
  const known = new Set(raw.map((c) => str(c.category_id)));
  for (const [id, count] of counts) {
    if (!known.has(id)) out.push({ id: `${prefix}-${id}`, name: id ? `Categoria ${id}` : "Outros", count });
  }
  return out.filter((c) => c.count > 0);
}

function countBy(items: Obj[]): Map<string, number> {
  const m = new Map<string, number>();
  for (const i of items) {
    const id = str(i.category_id);
    m.set(id, (m.get(id) ?? 0) + 1);
  }
  return m;
}

export interface XtreamOptions {
  liveFormat?: "m3u8" | "ts";
}

export async function buildCatalogFromXtream(c: XtreamCredentials, fetchJson: JsonFetcher, opts: XtreamOptions = {}): Promise<Catalog> {
  const liveFormat = opts.liveFormat ?? "m3u8";
  const settled = await Promise.allSettled([
    fetchJson(apiUrl(c, "get_live_categories")),
    fetchJson(apiUrl(c, "get_live_streams")),
    fetchJson(apiUrl(c, "get_vod_categories")),
    fetchJson(apiUrl(c, "get_vod_streams")),
    fetchJson(apiUrl(c, "get_series_categories")),
    fetchJson(apiUrl(c, "get_series")),
  ]);
  const [liveCatsRaw, liveRaw, vodCatsRaw, vodRaw, seriesCatsRaw, seriesRaw] = settled.map((r) =>
    asArray(r.status === "fulfilled" ? r.value : [])
  );
  if (settled.every((r) => r.status === "rejected")) {
    throw new Error((settled[0] as PromiseRejectedResult).reason?.message ?? "xtream_unreachable");
  }

  const catName = (raw: Obj[]) => new Map(raw.map((x) => [str(x.category_id), str(x.category_name)]));
  const liveNames = catName(liveCatsRaw);
  const vodNames = catName(vodCatsRaw);
  const seriesNames = catName(seriesCatsRaw);

  const live: LiveItem[] = liveRaw.map((s) => {
    const id = num(s.stream_id) ?? 0;
    const cat = str(s.category_id);
    return {
      id: `x-live-${id}`,
      name: str(s.name) || `Canal ${id}`,
      url: liveStreamUrl(c, id, liveFormat),
      logo: img(s.stream_icon),
      group: liveNames.get(cat) || "Outros",
      categoryId: `live-${cat}`,
      epgId: str(s.epg_channel_id) || null,
      streamId: id,
      catchup: Number(s.tv_archive) === 1,
    };
  });

  const movies: MovieItem[] = vodRaw.map((s) => {
    const id = num(s.stream_id) ?? 0;
    const cat = str(s.category_id);
    const ext = str(s.container_extension) || "mp4";
    return {
      id: `x-movie-${id}`,
      name: str(s.name) || `Filme ${id}`,
      url: movieStreamUrl(c, id, ext),
      poster: img(s.stream_icon) ?? img(s.cover),
      group: vodNames.get(cat) || "Outros",
      categoryId: `movie-${cat}`,
      year: yearOf(s.year ?? s.releasedate ?? s.name),
      rating: num(s.rating),
      streamId: id,
      extension: ext,
    };
  });

  const series: SeriesItem[] = seriesRaw.map((s) => {
    const id = num(s.series_id) ?? 0;
    const cat = str(s.category_id);
    return {
      id: `x-series-${id}`,
      name: str(s.name) || `Série ${id}`,
      cover: img(s.cover),
      group: seriesNames.get(cat) || "Outros",
      categoryId: `series-${cat}`,
      year: yearOf(s.releaseDate ?? s.release_date ?? s.year),
      rating: num(s.rating),
      plot: str(s.plot) || undefined,
      seriesId: id,
      seasons: [],
      episodeCount: num(s.episode_count) ?? num(s.episodes) ?? 0,
    };
  });

  return {
    source: "xtream",
    epgUrl: xmltvUrl(c),
    generatedAt: new Date().toISOString(),
    live: { categories: categoryList(liveCatsRaw, "live", countBy(liveRaw)), items: live },
    movies: { categories: categoryList(vodCatsRaw, "movie", countBy(vodRaw)), items: movies },
    series: { categories: categoryList(seriesCatsRaw, "series", countBy(seriesRaw)), items: series },
    stats: { live: live.length, movies: movies.length, series: series.length, episodes: 0 },
  };
}

export interface SeriesInfo {
  seriesId: number;
  name: string;
  cover: string | null;
  plot?: string;
  year?: number;
  rating?: number;
  seasons: Season[];
}

export async function fetchSeriesInfo(c: XtreamCredentials, seriesId: number, fetchJson: JsonFetcher): Promise<SeriesInfo> {
  const data = (await fetchJson(apiUrl(c, "get_series_info", { series_id: seriesId }))) as Obj;
  const info: Obj = (data && data.info) || {};
  const episodesObj: Obj = (data && data.episodes) || {};
  const seasons: Season[] = [];
  // episodes pode vir como objeto {"1":[...]} ou array de arrays
  const entries: [string, Obj[]][] = Array.isArray(episodesObj)
    ? (episodesObj as Obj[][]).map((eps, i) => [String(asArray(eps)[0]?.season ?? i + 1), asArray(eps)])
    : Object.entries(episodesObj).map(([k, v]) => [k, asArray(v)]);
  for (const [seasonKey, eps] of entries) {
    const seasonNum = parseInt(seasonKey, 10) || 1;
    const episodes: Episode[] = eps.map((e, i) => {
      const epId = str(e.id);
      const epInfo: Obj = e.info || {};
      return {
        id: `x-ep-${epId || shortId(String(seriesId), seasonKey, String(i))}`,
        name: str(e.title) || `Episódio ${num(e.episode_num) ?? i + 1}`,
        season: num(e.season) ?? seasonNum,
        episode: num(e.episode_num) ?? i + 1,
        url: episodeStreamUrl(c, epId, str(e.container_extension) || "mp4"),
        cover: img(epInfo.movie_image) ?? img(info.cover),
        plot: str(epInfo.plot) || undefined,
        durationSecs: num(epInfo.duration_secs),
      };
    });
    episodes.sort((a, b) => a.episode - b.episode);
    seasons.push({ season: seasonNum, episodes });
  }
  seasons.sort((a, b) => a.season - b.season);
  return {
    seriesId,
    name: str(info.name),
    cover: img(info.cover),
    plot: str(info.plot) || undefined,
    year: yearOf(info.releaseDate ?? info.release_date),
    rating: num(info.rating),
    seasons,
  };
}

export interface ShortEpgEntry {
  title: string;
  description: string;
  start: string;
  stop: string;
}

function maybeBase64(s: string): string {
  if (!s) return "";
  if (!/^[A-Za-z0-9+/=\s]+$/.test(s) || s.length % 4 !== 0) return s;
  try {
    const d = Buffer.from(s, "base64").toString("utf8");
    // Se o resultado tiver muitos caracteres de controle, não era base64
    return /[\u0000-\u0008\u000e-\u001f\ufffd]/.test(d) ? s : d;
  } catch {
    return s;
  }
}

function safeIso(ts: number | undefined, text: string): string {
  if (ts) return new Date(ts * 1000).toISOString();
  const d = new Date(text.replace(" ", "T") + (/[zZ]|[+-]\d{2}:?\d{2}$/.test(text) ? "" : "Z"));
  return Number.isNaN(d.getTime()) ? "" : d.toISOString();
}

export async function fetchShortEpg(c: XtreamCredentials, streamId: number, fetchJson: JsonFetcher, limit = 4): Promise<ShortEpgEntry[]> {
  const data = (await fetchJson(apiUrl(c, "get_short_epg", { stream_id: streamId, limit }))) as Obj;
  return asArray(data?.epg_listings).map((e) => {
    const startTs = num(e.start_timestamp);
    const stopTs = num(e.stop_timestamp);
    return {
      title: maybeBase64(str(e.title)),
      description: maybeBase64(str(e.description)),
      start: safeIso(startTs, str(e.start)),
      stop: safeIso(stopTs, str(e.end ?? e.stop)),
    };
  });
}
