/** Modelo de catálogo unificado (M3U e Xtream) devolvido para o app. */
import { createHash } from "node:crypto";
import { classifyEntry, cleanShowName, extractYear, normalize, type ContentType } from "./classify.js";
import type { M3UPlaylist } from "./m3u.js";

export interface CatalogCategory {
  id: string;
  name: string;
  count: number;
}

export interface LiveItem {
  id: string;
  name: string;
  url: string;
  logo: string | null;
  group: string;
  categoryId: string;
  /** id usado para casar com o EPG (tvg-id ou epg_channel_id) */
  epgId: string | null;
  streamId?: number;
  catchup?: boolean;
  userAgent?: string;
}

export interface MovieItem {
  id: string;
  name: string;
  url: string;
  poster: string | null;
  group: string;
  categoryId: string;
  year?: number;
  rating?: number;
  streamId?: number;
  extension?: string;
}

export interface Episode {
  id: string;
  name: string;
  season: number;
  episode: number;
  url: string;
  cover?: string | null;
  plot?: string;
  durationSecs?: number;
}

export interface Season {
  season: number;
  episodes: Episode[];
}

export interface SeriesItem {
  id: string;
  name: string;
  cover: string | null;
  group: string;
  categoryId: string;
  year?: number;
  rating?: number;
  plot?: string;
  /** Xtream: as temporadas são carregadas sob demanda via /catalog/series-info */
  seriesId?: number;
  seasons: Season[];
  episodeCount: number;
}

export interface Section<T> {
  categories: CatalogCategory[];
  items: T[];
}

export interface Catalog {
  source: "m3u" | "xtream";
  epgUrl: string | null;
  generatedAt: string;
  live: Section<LiveItem>;
  movies: Section<MovieItem>;
  series: Section<SeriesItem>;
  stats: { live: number; movies: number; series: number; episodes: number };
}

export function shortId(...parts: string[]): string {
  return createHash("sha1").update(parts.join("\u0001")).digest("hex").slice(0, 12);
}

class CategoryIndex {
  private map = new Map<string, CatalogCategory>();
  idFor(name: string, prefix: string): string {
    const key = name || "Outros";
    let c = this.map.get(key);
    if (!c) {
      c = { id: `${prefix}-${shortId(prefix, key)}`, name: key, count: 0 };
      this.map.set(key, c);
    }
    return c.id;
  }
  bump(name: string) {
    const c = this.map.get(name || "Outros");
    if (c) c.count++;
  }
  list(): CatalogCategory[] {
    // Mantém a ordem original da lista do provedor (como no IBO / Shamel)
    return Array.from(this.map.values());
  }
}

export function buildCatalogFromM3U(pl: M3UPlaylist): Catalog {
  const liveCats = new CategoryIndex();
  const movieCats = new CategoryIndex();
  const seriesCats = new CategoryIndex();
  const live: LiveItem[] = [];
  const movies: MovieItem[] = [];
  const seriesMap = new Map<string, SeriesItem>();
  let episodes = 0;
  // Listas M3U repetem entradas com frequência: garante ids únicos
  const seen = new Map<string, number>();
  const uniq = (id: string) => {
    const n = seen.get(id) ?? 0;
    seen.set(id, n + 1);
    return n === 0 ? id : `${id}-${n}`;
  };

  for (const e of pl.entries) {
    const { type, episode } = classifyEntry({ name: e.name, group: e.group, url: e.url, attrs: e.attrs });
    const group = e.group || "Outros";

    if (type === "live") {
      const categoryId = liveCats.idFor(group, "live");
      liveCats.bump(group);
      live.push({
        id: uniq(shortId("live", e.url, e.name)),
        name: e.name,
        url: e.url,
        logo: e.logo ?? null,
        group,
        categoryId,
        epgId: e.tvgId ?? e.tvgName ?? null,
        catchup: Boolean(e.attrs["catchup"] || e.attrs["catchup-days"] || e.attrs["tvg-rec"]),
        userAgent: e.userAgent,
      });
    } else if (type === "movie") {
      const categoryId = movieCats.idFor(group, "movie");
      movieCats.bump(group);
      movies.push({
        id: uniq(shortId("movie", e.url)),
        name: e.name,
        url: e.url,
        poster: e.logo ?? null,
        group,
        categoryId,
        year: extractYear(e.name),
      });
    } else {
      const info = episode ?? { show: cleanShowName(e.tvgName || e.name), season: 1, episode: 0 };
      const categoryId = seriesCats.idFor(group, "series");
      const key = `${group}\u0001${normalize(info.show)}`;
      let s = seriesMap.get(key);
      if (!s) {
        s = {
          id: shortId("series", key),
          name: info.show,
          cover: e.logo ?? null,
          group,
          categoryId,
          year: extractYear(info.show),
          seasons: [],
          episodeCount: 0,
        };
        seriesMap.set(key, s);
        seriesCats.bump(group);
      }
      if (!s.cover && e.logo) s.cover = e.logo;
      let season = s.seasons.find((x) => x.season === info.season);
      if (!season) {
        season = { season: info.season, episodes: [] };
        s.seasons.push(season);
      }
      season.episodes.push({
        id: uniq(shortId("ep", e.url)),
        name: e.name,
        season: info.season,
        episode: info.episode || season.episodes.length + 1,
        url: e.url,
        cover: e.logo ?? null,
      });
      s.episodeCount++;
      episodes++;
    }
  }

  const series = Array.from(seriesMap.values());
  for (const s of series) {
    s.seasons.sort((a, b) => a.season - b.season);
    for (const se of s.seasons) se.episodes.sort((a, b) => a.episode - b.episode);
  }

  return {
    source: "m3u",
    epgUrl: pl.epgUrl,
    generatedAt: new Date().toISOString(),
    live: { categories: liveCats.list(), items: live },
    movies: { categories: movieCats.list(), items: movies },
    series: { categories: seriesCats.list(), items: series },
    stats: { live: live.length, movies: movies.length, series: series.length, episodes },
  };
}

export type { ContentType };
