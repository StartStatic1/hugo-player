/**
 * Detecção de tipo de conteúdo (live / movie / series) para entradas M3U.
 *
 * Usa um sistema de pontuação combinando vários sinais:
 *  - caminho da URL no padrão Xtream (/live/, /movie/, /series/)  -> sinal forte
 *  - padrão de episódio no nome (S01E02, 1x02, T01E02, "Temporada 1 Episódio 2") -> forte
 *  - extensão do arquivo (.m3u8/.ts = live; .mp4/.mkv/.avi... = VOD)
 *  - atributos tvg-* / catchup (típicos de canais ao vivo)
 *  - palavras-chave no group-title em PT/EN/ES/AR
 *  - ano no nome "(2021)" -> filme (fraco)
 */

export type ContentType = "live" | "movie" | "series";

export interface ClassifyInput {
  name: string;
  group?: string;
  url: string;
  attrs?: Record<string, string>;
}

export interface EpisodeInfo {
  show: string;
  season: number;
  episode: number;
}

const VOD_EXT = /\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|mpg|mpeg|3gp|divx)(\?|#|$)/i;
const LIVE_EXT = /\.(m3u8|ts|m3u)(\?|#|$)/i;

/** Remove acentos e baixa caixa para comparar palavras-chave. */
export function normalize(s: string): string {
  return (s || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f\u064b-\u065f\u0670]/g, "")
    .toLowerCase()
    .trim();
}

/** Cria um verificador de palavras-chave (regex pré-compilada, com fronteira de palavra). */
function makeMatcher(words: string[]): (text: string) => boolean {
  const ascii: string[] = [];
  const other: string[] = [];
  for (const raw of words) {
    const w = normalize(raw);
    // Palavras em árabe não têm fronteira \b confiável; usa includes.
    if (/[^\x00-\x7f]/.test(w)) other.push(w);
    else ascii.push(w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  }
  const re = ascii.length ? new RegExp(`(^|[^a-z0-9])(${ascii.join("|")})([^a-z0-9]|$)`) : null;
  return (text: string) => (re ? re.test(text) : false) || other.some((w) => text.includes(w));
}

const SERIES_WORDS = [
  "series", "serie", "seriados", "seriado", "temporada", "temporadas", "season", "seasons",
  "tv shows", "tvshows", "shows", "dorama", "doramas", "k-drama", "kdrama", "anime series",
  "novelas turcas", "miniseries", "minisseries",
  // AR (já sem diacríticos)
  "مسلسلات", "مسلسل",
];

const MOVIE_WORDS = [
  "filmes", "filme", "movies", "movie", "vod", "cinema", "peliculas", "pelicula", "films", "film",
  "lancamentos", "lancamento", "colecao", "coletanea", "saga", "4k movies", "dublado", "legendado",
  "estrenos",
  // AR
  "افلام", "أفلام", "فيلم", "فلم",
];

const LIVE_WORDS = [
  "canais", "canal", "channels", "channel", "live", "ao vivo", "aovivo", "en vivo", "envivo",
  "tv", "24/7", "24h", "24 horas", "news", "noticias", "esportes", "esporte", "sports", "sport",
  "deportes", "futebol", "football", "soccer", "ppv", "abertos", "aberto", "radio", "radios",
  "infantil", "kids", "documentarios", "religiosos", "hd", "fhd", "uhd", "sd", "h265",
  "globo", "sbt", "record", "band", "premiere", "telecine", "hbo", "espn", "sportv",
  // AR
  "قنوات", "قناة", "مباشر", "بث مباشر",
];

const isSeriesGroup = makeMatcher(SERIES_WORDS);
const isMovieGroup = makeMatcher(MOVIE_WORDS);
const isLiveGroup = makeMatcher(LIVE_WORDS);

const EPISODE_PATTERNS: RegExp[] = [
  // S01E02, S1 E2, S01.E02, S01-E02
  /^(.*?)[\s._\-:|]*\bS(\d{1,2})[\s._\-]*E(\d{1,4})\b/i,
  // T01E02 (temporada, PT/ES)
  /^(.*?)[\s._\-:|]*\bT(\d{1,2})[\s._\-]*E(\d{1,4})\b/i,
  // 1x02
  /^(.*?)[\s._\-:|]*\b(\d{1,2})x(\d{1,4})\b/i,
  // Temporada 1 Episódio 2 / Season 1 Episode 2 / Temporada 1 Capitulo 2
  /^(.*?)[\s._\-:|]*\b(?:temporada|season|temp\.?)\s*(\d{1,2})[\s,._\-:|]*(?:episodio|episode|ep\.?|capitulo|cap\.?)\s*(\d{1,4})\b/i,
];

/** Extrai série/temporada/episódio do nome. Retorna null se não parecer episódio. */
export function parseEpisode(rawName: string): EpisodeInfo | null {
  const name = (rawName || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "");
  for (const re of EPISODE_PATTERNS) {
    const m = name.match(re);
    if (m) {
      const season = parseInt(m[2], 10);
      const episode = parseInt(m[3], 10);
      if (!Number.isFinite(season) || !Number.isFinite(episode)) continue;
      // Usa o trecho original (com acentos) para o nome da série
      const show = cleanShowName(rawName.slice(0, m[1].length)) || cleanShowName(rawName);
      return { show, season, episode };
    }
  }
  // "Episódio 3" sem temporada explícita → temporada 1
  const ep = name.match(/^(.*?)[\s._\-:|]*\b(?:episodio|episode|ep\.?|capitulo|cap\.?)\s*(\d{1,4})\b/i);
  if (ep && ep[1].trim().length > 0) {
    return { show: cleanShowName(rawName.slice(0, ep[1].length)), season: 1, episode: parseInt(ep[2], 10) };
  }
  return null;
}

export function cleanShowName(s: string): string {
  return (s || "")
    .replace(/[._]+/g, " ")
    .replace(/\s*[-:|]+\s*$/g, "")
    .replace(/\s{2,}/g, " ")
    .trim();
}

/** Ano entre parênteses/colchetes no nome, ex.: "Filme (2021)". */
export function extractYear(name: string): number | undefined {
  const m = (name || "").match(/[([](19[2-9]\d|20\d{2})[)\]]/) || (name || "").match(/\s-\s(19[2-9]\d|20\d{2})\s*$/);
  return m ? parseInt(m[1], 10) : undefined;
}

export function urlPathType(url: string): ContentType | null {
  let path = url;
  try {
    path = new URL(url).pathname;
  } catch {
    /* URL relativa ou inválida: usa string inteira */
  }
  const p = path.toLowerCase();
  if (/\/series\//.test(p)) return "series";
  if (/\/movies?\//.test(p) || /\/vod\//.test(p)) return "movie";
  if (/\/live\//.test(p)) return "live";
  return null;
}

export interface ClassifyResult {
  type: ContentType;
  scores: Record<ContentType, number>;
  episode: EpisodeInfo | null;
}

export function classifyEntry(input: ClassifyInput): ClassifyResult {
  const scores: Record<ContentType, number> = { live: 0, movie: 0, series: 0 };
  const g = normalize(input.group ?? "");
  const n = normalize(input.name);
  const url = input.url || "";
  const attrs = input.attrs ?? {};

  // 1) Caminho Xtream — o sinal mais confiável
  const pathType = urlPathType(url);
  if (pathType) scores[pathType] += 10;

  // 2) Padrão de episódio no nome
  const episode = parseEpisode(input.name);
  if (episode) scores.series += 6;

  // 3) Extensão
  let pathOnly = url;
  try { pathOnly = new URL(url).pathname; } catch { /* ignore */ }
  if (VOD_EXT.test(pathOnly)) {
    scores.movie += 3;
    scores.series += 2; // VOD pode ser filme ou episódio
  } else if (LIVE_EXT.test(pathOnly)) {
    scores.live += 2;
  } else if (!/\.[a-z0-9]{2,4}$/i.test(pathOnly)) {
    // sem extensão (ex.: http://host:port/user/pass/123) costuma ser live
    scores.live += 1;
  }

  // 4) Atributos tvg-* / catchup
  if (attrs["tvg-id"]) scores.live += 2;
  if (attrs["catchup"] || attrs["catchup-days"] || attrs["tvg-rec"] || attrs["timeshift"]) scores.live += 3;
  if (attrs["tvg-chno"]) scores.live += 1;

  // 5) Palavras-chave no grupo (peso maior) e no nome (peso menor)
  if (isSeriesGroup(g)) scores.series += 5;
  if (isMovieGroup(g)) scores.movie += 5;
  if (g.includes("24/7") || g.includes("24h")) scores.live += 3; // canais 24/7 rodam em loop
  if (isLiveGroup(g)) scores.live += 3;

  // 6) Ano no nome → filme (fraco)
  if (extractYear(input.name) && !episode) scores.movie += 2;
  // nomes de canais costumam ter "HD/FHD/4K" no final
  if (/\b(fhd|hd|sd|uhd|4k|h265|hevc)\s*$/i.test(n) && !episode) scores.live += 1;

  // Série só vence se houver episódio ou palavra-chave/caminho de série
  let type: ContentType = "live";
  const max = Math.max(scores.live, scores.movie, scores.series);
  if (max === 0) type = "live";
  else if (scores.series === max && scores.series > scores.movie) type = "series";
  else if (scores.movie === max) type = "movie";
  else if (scores.series === max) type = "series";
  else type = "live";

  return { type, scores, episode };
}
