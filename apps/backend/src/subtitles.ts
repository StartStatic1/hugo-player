import { promises as fs } from "node:fs";
import path from "node:path";
import { Router, type Request, type Response, type NextFunction } from "express";

/**
 * Legendas externas (OpenSubtitles REST v1) pelo servidor: a chave nunca vai no APK.
 * Env: OPENSUBTITLES_API_KEY (obrigatória) e, para mais downloads por dia,
 * OPENSUBTITLES_USERNAME + OPENSUBTITLES_PASSWORD (conta do opensubtitles.com).
 * Sem conta: 5 downloads/dia por IP do servidor (compartilhado). Cada legenda baixada fica
 * em cache no disco, então o mesmo filme não gasta outro download.
 */

const API = "https://api.opensubtitles.com/api/v1";
const UA = "HugoPlayer v1.0";

export interface SubtitleOption {
  fileId: number;
  lang: "pt-BR" | "pt-PT";
  release: string;
  downloads: number;
  hearingImpaired: boolean;
  machine: boolean;
  /** só placas/falas em outro idioma ("forced"): não serve como legenda do filme todo */
  forced?: boolean;
}

export interface SearchInput {
  title: string;
  year?: number;
  season?: number;
  episode?: number;
  tmdbId?: number;
}

/** OpenSubtitles exige parâmetros em ordem alfabética e valores em minúsculas (senão responde 301). */
export function buildSearchQuery(q: SearchInput): string {
  const p: Record<string, string> = { languages: "pt-br,pt-pt" };
  const title = q.title.toLowerCase().replace(/\s+/g, " ").trim();
  const isEpisode = q.season !== undefined && q.episode !== undefined;
  if (q.tmdbId && !isEpisode) p.tmdb_id = String(q.tmdbId);
  else if (q.tmdbId && isEpisode) p.parent_tmdb_id = String(q.tmdbId);
  else p.query = title;
  if (isEpisode) {
    p.type = "episode";
    p.season_number = String(q.season);
    p.episode_number = String(q.episode);
  } else {
    p.type = "movie";
    if (q.year) p.year = String(q.year);
  }
  return Object.keys(p)
    .sort()
    .map((k) => `${k}=${encodeURIComponent(p[k]).replace(/%2C/gi, ",")}`)
    .join("&");
}

/** Rótulos que indicam legenda para surdos (descreve sons: "[música]", "[risos]"). */
const SDH_RE = /(^|[^a-z])(sdh|cc|hi|closed[ ._-]?captions?|hearing[ ._-]?impaired)([^a-z]|$)/i;

const FORCED_RE = /(^|[^a-z])(forced|for[cç]ad[ao]s?|foreign[ ._-]?parts)([^a-z]|$)/i;

/** Pedaços do nome do release que ajudam a acertar a sincronia (fonte, resolução, grupo). */
export function releaseTokens(s: string): string[] {
  return (s ?? "")
    .toLowerCase()
    .replace(/web[ ._-]?dl/g, "webdl").replace(/web[ ._-]?rip/g, "webrip").replace(/blu[ ._-]?ray/g, "bluray")
    .split(/[^a-z0-9]+/)
    .filter((t) => t.length >= 2 && !/^(19|20)\d\d$/.test(t) && !["the", "and", "dublado", "legendado", "dual", "audio", "mkv", "mp4", "avi"].includes(t));
}

/** Quantos pedaços do release batem com o nome do arquivo/título do usuário (0 = sem dica). */
export function releaseMatch(release: string, hint: string | undefined): number {
  if (!hint) return 0;
  const want = new Set(releaseTokens(hint));
  if (!want.size) return 0;
  return new Set(releaseTokens(release).filter((t) => want.has(t))).size;
}

/**
 * Ordem da escolha automática: pt-BR antes de pt-PT; feitas por pessoas antes de tradução automática;
 * SEM descrição de sons (SDH) antes das SDH; depois o release que mais bate com o arquivo; depois mais baixadas.
 * "Forçadas" (só placas) vão para o fim. As SDH continuam na lista (para quem escolher manualmente), só não vêm primeiro.
 */
export function sortOptions(opts: SubtitleOption[], hint?: string): SubtitleOption[] {
  const score = (o: SubtitleOption) =>
    (o.forced ? 80 : 0) + (o.lang === "pt-BR" ? 0 : 20) + (o.machine ? 40 : 0) + (o.hearingImpaired ? 15 : 0) - Math.min(4, releaseMatch(o.release, hint)) * 2;
  return [...opts].sort((x, y) => score(x) - score(y) || y.downloads - x.downloads);
}

export function rankResults(data: any[], limit = 6, hint?: string): SubtitleOption[] {
  const out: SubtitleOption[] = [];
  for (const item of data ?? []) {
    const a = item?.attributes;
    const file = a?.files?.[0];
    const lang = String(a?.language ?? "").toLowerCase();
    if (!file?.file_id || (lang !== "pt-br" && lang !== "pt-pt")) continue;
    const release = String(a.release ?? file.file_name ?? "").slice(0, 80);
    out.push({
      fileId: Number(file.file_id),
      lang: lang === "pt-br" ? "pt-BR" : "pt-PT",
      release,
      downloads: Number(a.download_count ?? 0),
      hearingImpaired: Boolean(a.hearing_impaired) || SDH_RE.test(release) || SDH_RE.test(String(file.file_name ?? "")),
      machine: Boolean(a.machine_translated || a.ai_translated),
      forced: Boolean(a.foreign_parts_only) || FORCED_RE.test(release),
    });
  }
  return sortOptions(out, hint).slice(0, limit);
}

/** Remove BOM e limita o tamanho (Firestick tem pouca memória). */
export function cleanSrt(text: string): string | null {
  const t = text.replace(/^\uFEFF/, "");
  if (t.length > 1_500_000 || !/-->/.test(t)) return null;
  return t;
}

const norm = (s: string) => s.normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase().replace(/&/g, " e ").replace(/[^a-z0-9]+/g, " ").trim();

/**
 * Escolhe o TMDB certo para o título da lista (nomes em português buscam mal na OpenSubtitles;
 * pelo id do TMDB acha o filme certo). Na dúvida devolve undefined (usa a busca por texto).
 */
export function pickTmdbId(results: any[], title: string, year: number | undefined, kind: "movie" | "tv"): number | undefined {
  const want = norm(title);
  const yearOf = (r: any) => Number(String((kind === "movie" ? r.release_date : r.first_air_date) ?? "").slice(0, 4)) || undefined;
  const names = (r: any) => [r.title, r.original_title, r.name, r.original_name].filter(Boolean).map((n: string) => norm(n));
  const cands = (results ?? []).filter((r) => r && r.id);
  if (year) {
    const sameYear = cands.filter((r) => { const y = yearOf(r); return y !== undefined && Math.abs(y - year) <= 1; });
    const exact = sameYear.find((r) => names(r).includes(want));
    if (exact) return exact.id;
    const close = sameYear.find((r) => names(r).some((n) => n.includes(want) || want.includes(n)));
    return close?.id;
  }
  return cands.find((r) => names(r).includes(want))?.id;
}

/**
 * Busca reserva por texto (sem TMDB): a OpenSubtitles devolve qualquer coisa "parecida".
 * Só aceita resultado cujo título (filme ou série) bate com o pedido — na dúvida, nada
 * (legenda de outro filme é pior que nenhuma).
 */
export function titleMatches(item: any, title: string, year: number | undefined, isEpisode: boolean): boolean {
  const fd = item?.attributes?.feature_details ?? {};
  const want = norm(title);
  if (!want) return false;
  const raw = isEpisode ? [fd.parent_title, fd.parent_original_title] : [fd.title, fd.original_title, fd.movie_name];
  const names = raw
    .filter(Boolean)
    .map((n: string) => norm(String(n).replace(/^\s*\d{4}\s*-\s*/, "")))
    .filter((n: string) => n.length > 0);
  // "Duna" ↔ "Duna Parte Dois": aceita quando um começa com o outro (palavra inteira), nunca "no meio"
  const wordIn = (big: string, small: string) => small.length >= 4 && `${big} `.startsWith(`${small} `);
  const ok = names.some((n: string) => n === want || wordIn(n, want) || wordIn(want, n));
  if (!ok) return false;
  if (!isEpisode && year) {
    const y = Number(fd.year) || undefined;
    if (y !== undefined && Math.abs(y - year) > 1) return false;
  }
  return true;
}

/** Quanto tempo guardar um resultado: vazio dura pouco (filme novo ganha legenda em horas). */
export function searchCacheTtlMs(found: boolean, year: number | undefined, now = new Date()): number {
  if (found) return 24 * 3600_000;
  const recent = year !== undefined && year >= now.getUTCFullYear() - 1;
  return recent ? 30 * 60_000 : 3 * 3600_000;
}

export interface SubtitlesDeps {
  /** busca no TMDB (pt-BR) → resultados; opcional */
  tmdbSearch?: (kind: "movie" | "tv", query: string) => Promise<any[]>;
  apiKey?: string;
  username?: string;
  password?: string;
  cacheDir: string;
  /** só aparelhos com licença ativa (evita abuso da cota) */
  authenticate: (id?: string, key?: string) => boolean;
  fetchImpl?: typeof fetch;
  maxCachedFiles?: number;
}

export function createSubtitlesRouter(deps: SubtitlesDeps): Router {
  const r = Router();
  const f = deps.fetchImpl ?? fetch;
  const maxFiles = deps.maxCachedFiles ?? 600;
  const searchCache = new Map<string, { at: number; items: SubtitleOption[] }>();
  const inflight = new Map<number, Promise<string>>();
  let token: { value: string; base: string; at: number } | null = null;
  let quotaUntil = 0;
  /** login recusado: não tenta de novo por 1 h (a OpenSubtitles bloqueia a conta depois de ~10 erros) */
  let loginBlockedUntil = 0;

  function auth(req: Request, res: Response, next: NextFunction) {
    if (!deps.authenticate(req.header("x-device-id"), req.header("x-device-key"))) return res.status(401).json({ error: "invalid_credentials" });
    if (!deps.apiKey) return res.status(503).json({ error: "subtitles_not_configured" });
    next();
  }
  r.use(auth);

  const headers = (extra: Record<string, string> = {}) => ({ "Api-Key": deps.apiKey!, "User-Agent": UA, Accept: "application/json", ...extra });

  async function login(): Promise<{ value: string; base: string } | null> {
    if (!deps.username || !deps.password) return null;
    if (token && Date.now() - token.at < 23 * 3600_000) return token;
    if (Date.now() < loginBlockedUntil) return null;
    const res = await f(`${API}/login`, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({ username: deps.username, password: deps.password }),
    });
    if (!res.ok) {
      loginBlockedUntil = Date.now() + 3600_000;
      console.warn("opensubtitles login failed:", res.status, "- sem nova tentativa por 1 h (usa downloads anônimos)");
      return null;
    }
    const j = (await res.json()) as any;
    const base = j?.base_url ? `https://${String(j.base_url).replace(/^https?:\/\//, "")}/api/v1` : API;
    token = { value: String(j.token), base, at: Date.now() };
    return token;
  }

  r.get("/search", async (req, res) => {
    const title = String(req.query.title ?? "").trim().slice(0, 120);
    if (!title) return res.status(400).json({ error: "missing_title" });
    const num = (v: unknown) => (v !== undefined && /^\d{1,7}$/.test(String(v)) ? Number(v) : undefined);
    const season = num(req.query.season);
    const episode = num(req.query.episode);
    const year = num(req.query.year);
    let tmdbId = num(req.query.tmdbId);
    if (!tmdbId && deps.tmdbSearch) {
      const kind = season !== undefined && episode !== undefined ? "tv" : "movie";
      tmdbId = await deps.tmdbSearch(kind, title).then((r) => pickTmdbId(r, title, kind === "movie" ? year : undefined, kind)).catch(() => undefined);
    }
    const qs = buildSearchQuery({ title, year, season, episode, tmdbId });
    const hit = searchCache.get(qs);
    const hint = String(req.query.hint ?? "").slice(0, 200) || undefined;
    if (hit && Date.now() - hit.at < searchCacheTtlMs(hit.items.length > 0, year)) return res.json({ items: sortOptions(hit.items, hint).slice(0, 6), cached: true });
    try {
      const resp = await f(`${API}/subtitles?${qs}`, { headers: headers(), redirect: "follow" });
      if (resp.status === 429) return res.status(429).json({ error: "subtitle_rate_limited" });
      if (!resp.ok) return res.status(502).json({ error: `upstream_${resp.status}` });
      const data: any[] = ((await resp.json()) as any)?.data ?? [];
      const isEpisode = season !== undefined && episode !== undefined;
      // com TMDB o id já garante o filme certo; sem ele, confere o título de cada resultado
      const all = rankResults(tmdbId ? data : data.filter((d) => titleMatches(d, title, year, isEpisode)), 30);
      if (searchCache.size > 400) searchCache.delete(searchCache.keys().next().value as string);
      searchCache.set(qs, { at: Date.now(), items: all });
      res.json({ items: sortOptions(all, hint).slice(0, 6) });
    } catch {
      res.status(502).json({ error: "subtitle_search_failed" });
    }
  });

  async function pruneCache() {
    try {
      const names = (await fs.readdir(deps.cacheDir)).filter((n) => n.endsWith(".srt"));
      if (names.length <= maxFiles) return;
      const stats = await Promise.all(names.map(async (n) => ({ n, t: (await fs.stat(path.join(deps.cacheDir, n))).mtimeMs })));
      stats.sort((a, b) => a.t - b.t);
      for (const s of stats.slice(0, names.length - maxFiles)) await fs.unlink(path.join(deps.cacheDir, s.n)).catch(() => {});
    } catch { /* cache é opcional */ }
  }

  async function download(fileId: number): Promise<string> {
    const file = path.join(deps.cacheDir, `${fileId}.srt`);
    try {
      const cached = await fs.readFile(file, "utf8");
      const now = new Date();
      fs.utimes(file, now, now).catch(() => {});
      return cached;
    } catch { /* não está em cache */ }
    if (Date.now() < quotaUntil) throw Object.assign(new Error("quota"), { quota: true });
    const t = await login().catch(() => null);
    const base = t?.base ?? API;
    const resp = await f(`${base}/download`, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json", ...(t ? { Authorization: `Bearer ${t.value}` } : {}) }),
      body: JSON.stringify({ file_id: fileId, sub_format: "srt" }),
    });
    const j = (await resp.json().catch(() => ({}))) as any;
    if (resp.status === 406 || (resp.ok && j?.remaining !== undefined && !j?.link)) {
      quotaUntil = Date.parse(j?.reset_time_utc ?? "") || Date.now() + 3600_000;
      throw Object.assign(new Error("quota"), { quota: true });
    }
    if (resp.status === 401 && t) token = null;
    if (!resp.ok || !j?.link) throw new Error(`upstream_${resp.status}`);
    const srtResp = await f(String(j.link), { headers: { "User-Agent": UA } });
    if (!srtResp.ok) throw new Error(`upstream_${srtResp.status}`);
    const text = cleanSrt(await srtResp.text());
    if (!text) throw new Error("invalid_subtitle");
    await fs.mkdir(deps.cacheDir, { recursive: true });
    await fs.writeFile(file, text, "utf8");
    pruneCache();
    return text;
  }

  r.get("/file/:fileId", async (req, res) => {
    const fileId = Number(req.params.fileId);
    if (!Number.isInteger(fileId) || fileId <= 0) return res.status(400).json({ error: "invalid_file" });
    try {
      let p = inflight.get(fileId);
      if (!p) { p = download(fileId).finally(() => inflight.delete(fileId)); inflight.set(fileId, p); }
      const text = await p;
      res.setHeader("Content-Type", "application/x-subrip; charset=utf-8");
      res.setHeader("Cache-Control", "private, max-age=86400");
      res.send(text);
    } catch (e: any) {
      if (e?.quota) return res.status(429).json({ error: "subtitle_quota", resetAt: new Date(quotaUntil).toISOString() });
      res.status(502).json({ error: e?.message || "subtitle_download_failed" });
    }
  });

  return r;
}
