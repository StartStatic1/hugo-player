/** Carrega e cacheia catálogos (M3U ou Xtream). */
import { buildCatalogFromM3U, type Catalog } from "./catalog.js";
import { parseM3U } from "./m3u.js";
import { fetchJson, fetchText, TtlCache, UpstreamError } from "./net.js";
import { buildCatalogFromXtream, fetchSeriesInfo, parseXtreamUrl, type SeriesInfo, type XtreamCredentials } from "./xtream.js";

const CATALOG_TTL_MS = Number(process.env.CATALOG_TTL_MIN ?? 15) * 60_000;
const catalogCache = new TtlCache<Catalog>(CATALOG_TTL_MS, 10);
const seriesInfoCache = new TtlCache<SeriesInfo>(CATALOG_TTL_MS, 300);

export interface PlaylistRef {
  url: string;
  type?: "m3u" | "xtream" | string;
  /** EPG próprio da playlist (tem prioridade sobre o automático). */
  epgUrl?: string;
}

export function xtreamCredsFor(ref: PlaylistRef): XtreamCredentials | null {
  return parseXtreamUrl(ref.url, ref.type === "xtream");
}

export async function loadCatalog(ref: PlaylistRef, opts: { force?: boolean; liveFormat?: "m3u8" | "ts" } = {}): Promise<Catalog> {
  const creds = xtreamCredsFor(ref);
  const key = `${creds ? "x" : "m"}|${opts.liveFormat ?? "m3u8"}|${ref.url}`;
  return catalogCache.getOrLoad(
    key,
    async () => {
      if (creds) {
        try {
          const cat = await buildCatalogFromXtream(creds, (u) => fetchJson(u), { liveFormat: opts.liveFormat });
          if (cat.stats.live + cat.stats.movies + cat.stats.series > 0) return cat;
        } catch (e) {
          // Painel sem player_api: cai para o M3U (get.php)
          if (ref.type === "xtream" && !/get\.php/i.test(ref.url)) throw e;
        }
      }
      const text = await fetchText(ref.url);
      if (!text || !text.includes("#EXTINF")) throw new UpstreamError(422, "empty_playlist");
      return buildCatalogFromM3U(parseM3U(text));
    },
    opts.force
  );
}

export async function loadSeriesInfo(ref: PlaylistRef, seriesId: number): Promise<SeriesInfo> {
  const creds = xtreamCredsFor(ref);
  if (!creds) throw new UpstreamError(400, "not_xtream_playlist");
  return seriesInfoCache.getOrLoad(`${ref.url}|${seriesId}`, () => fetchSeriesInfo(creds, seriesId, (u) => fetchJson(u)));
}
