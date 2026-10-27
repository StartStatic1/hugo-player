import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import type { AddressInfo } from "node:net";
import express from "express";
import { buildSearchQuery, cleanSrt, createSubtitlesRouter, pickTmdbId, rankResults, releaseMatch, searchCacheTtlMs, sortOptions, titleMatches } from "../subtitles.js";

test("busca: parâmetros em ordem alfabética e minúsculas (OpenSubtitles exige)", () => {
  assert.equal(buildSearchQuery({ title: "Inception", year: 2010 }), "languages=pt-br,pt-pt&query=inception&type=movie&year=2010");
  assert.equal(buildSearchQuery({ title: "Dark", season: 1, episode: 3 }), "episode_number=3&languages=pt-br,pt-pt&query=dark&season_number=1&type=episode");
  assert.equal(buildSearchQuery({ title: "X", tmdbId: 27205, year: 2010 }), "languages=pt-br,pt-pt&tmdb_id=27205&type=movie&year=2010");
});

test("ranking: pt-BR primeiro, tradução automática por último", () => {
  const mk = (id: number, language: string, dl: number, extra: any = {}) => ({ attributes: { language, download_count: dl, release: `r${id}`, files: [{ file_id: id }], ...extra } });
  const r = rankResults([mk(1, "pt-PT", 900), mk(2, "pt-BR", 10), mk(3, "pt-BR", 500), mk(4, "pt-BR", 9999, { machine_translated: true }), mk(5, "en", 9999)]);
  assert.deepEqual(r.map((o) => o.fileId), [3, 2, 1, 4]);
});

test("srt: remove BOM e recusa o que não é legenda", () => {
  assert.equal(cleanSrt("\uFEFF1\n00:00:01,000 --> 00:00:02,000\nOi\n"), "1\n00:00:01,000 --> 00:00:02,000\nOi\n");
  assert.equal(cleanSrt("<html>erro</html>"), null);
});

test("rota: exige aparelho ativo, baixa 1x e guarda em cache; cota esgotada vira 429", async () => {
  let downloads = 0;
  let quota = false;
  const fakeFetch = (async (url: string, init?: any) => {
    const u = String(url);
    const json = (status: number, body: any) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    if (u.includes("/subtitles?")) return json(200, { data: [{ attributes: { language: "pt-BR", download_count: 5, release: "Filme.2010", feature_details: { title: "Filme", year: 2010 }, files: [{ file_id: 77 }] } }, { attributes: { language: "pt-BR", download_count: 999, release: "Outro.2010", feature_details: { title: "Outro Filme Qualquer", year: 2010 }, files: [{ file_id: 88 }] } }] });
    if (u.endsWith("/download")) {
      if (quota) return json(406, { remaining: -1, reset_time_utc: "2099-01-01T00:00:00.000Z" });
      downloads++;
      assert.equal(init.headers["Api-Key"], "k");
      return json(200, { link: "https://dl.test/77.srt", remaining: 4 });
    }
    if (u === "https://dl.test/77.srt") return new Response("1\n00:00:01,000 --> 00:00:02,000\nOlá\n", { status: 200 });
    return json(404, {});
  }) as typeof fetch;
  const app = express();
  app.use("/subtitles", createSubtitlesRouter({ apiKey: "k", cacheDir: mkdtempSync(path.join(tmpdir(), "subs-")), authenticate: (id) => id === "OK", fetchImpl: fakeFetch }));
  const server = app.listen(0);
  await new Promise((r) => server.once("listening", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/subtitles`;
  const get = (p: string, id = "OK") => fetch(base + p, { headers: { "x-device-id": id, "x-device-key": "x" } });
  try {
    assert.equal((await get("/search?title=Filme&year=2010", "NO")).status, 401);
    const s = (await (await get("/search?title=Filme&year=2010")).json()) as any;
    assert.equal(s.items[0].fileId, 77);
    assert.equal(s.items.length, 1); // o outro filme (título diferente) não entra
    const a = await get("/file/77");
    assert.equal(a.status, 200);
    assert.match(await a.text(), /Olá/);
    assert.equal((await get("/file/77")).status, 200); // do cache
    assert.equal(downloads, 1);
    quota = true;
    const q = await get("/file/78");
    assert.equal(q.status, 429);
    assert.equal(((await q.json()) as any).error, "subtitle_quota");
  } finally {
    server.close();
  }
});

test("TMDB: acha o filme pelo nome em português + ano; série pelo nome exato; na dúvida nada", () => {
  const movies = [
    { id: 1, title: "A Origem do Mal", original_title: "X", release_date: "2018-01-01" },
    { id: 27205, title: "A Origem", original_title: "Inception", release_date: "2010-07-15" },
  ];
  assert.equal(pickTmdbId(movies, "A Origem", 2010, "movie"), 27205);
  assert.equal(pickTmdbId(movies, "A Origem", 1990, "movie"), undefined);
  assert.equal(pickTmdbId([{ id: 70523, name: "Dark", original_name: "Dark", first_air_date: "2017-12-01" }], "Dark", undefined, "tv"), 70523);
  assert.equal(pickTmdbId([{ id: 5, name: "Dark Matter" }], "Dark", undefined, "tv"), undefined);
  assert.equal(buildSearchQuery({ title: "Dark", season: 1, episode: 1, tmdbId: 70523 }), "episode_number=1&languages=pt-br,pt-pt&parent_tmdb_id=70523&season_number=1&type=episode");
});

test("login recusado: tenta 1 vez e não repete a cada download (evita bloquear a conta)", async () => {
  let logins = 0;
  const fakeFetch = (async (url: string) => {
    const u = String(url);
    const json = (status: number, body: any) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    if (u.endsWith("/login")) { logins++; return json(401, { message: "invalid" }); }
    if (u.endsWith("/download")) return json(200, { link: "https://dl.test/x.srt", remaining: 3 });
    return new Response("1\n00:00:01,000 --> 00:00:02,000\nOi\n", { status: 200 });
  }) as typeof fetch;
  const app = express();
  app.use("/subtitles", createSubtitlesRouter({ apiKey: "k", username: "u", password: "p", cacheDir: mkdtempSync(path.join(tmpdir(), "subs-")), authenticate: () => true, fetchImpl: fakeFetch }));
  const server = app.listen(0);
  await new Promise((r) => server.once("listening", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/subtitles`;
  try {
    for (const id of [11, 12, 13]) assert.equal((await fetch(`${base}/file/${id}`, { headers: { "x-device-id": "a", "x-device-key": "b" } })).status, 200);
    assert.equal(logins, 1);
  } finally {
    server.close();
  }
});

test("busca reserva por texto: só aceita o filme/série com o mesmo título (nada de filme errado)", () => {
  const movie = (title: string, year?: number, movie_name?: string) => ({ attributes: { feature_details: { title, year, movie_name } } });
  assert.equal(titleMatches(movie("The Last Sunrise", 2024), "o sorveteiro", 2026, false), false);
  assert.equal(titleMatches(movie("The Whisper Man", 2025), "o sorveteiro", 2026, false), false);
  assert.equal(titleMatches(movie("O Sorveteiro", 2026), "o sorveteiro", 2026, false), true);
  assert.equal(titleMatches(movie("Inception", 2010, "2010 - Inception"), "Inception", 2010, false), true);
  assert.equal(titleMatches(movie("Inception", 2010), "Inception", 2018, false), false); // ano muito diferente
  assert.equal(titleMatches(movie("Duna: Parte Dois", 2024), "Duna", 2024, false), true);
  assert.equal(titleMatches(movie("Dunas", 2024), "Duna", 2024, false), false);
  const ep = { attributes: { feature_details: { title: "Episode 1", parent_title: "Dark" } } };
  assert.equal(titleMatches(ep, "Dark", undefined, true), true);
  assert.equal(titleMatches(ep, "Breaking Bad", undefined, true), false);
  assert.equal(titleMatches({}, "Qualquer", undefined, false), false);
});

test("cache de busca vazia dura pouco (filme novo ganha legenda logo)", () => {
  const now = new Date("2026-10-26T00:00:00Z");
  assert.equal(searchCacheTtlMs(true, 2026, now), 24 * 3600_000);
  assert.equal(searchCacheTtlMs(false, 2026, now), 30 * 60_000);
  assert.equal(searchCacheTtlMs(false, 2025, now), 30 * 60_000);
  assert.equal(searchCacheTtlMs(false, 2010, now), 3 * 3600_000);
  assert.equal(searchCacheTtlMs(false, undefined, now), 3 * 3600_000);
});

test("automática prefere legenda sem SDH e o release que bate com o arquivo", () => {
  const item = (id: number, release: string, extra: any = {}) => ({ attributes: { language: "pt-BR", download_count: 100, release, files: [{ file_id: id }], ...extra } });
  const r = rankResults([
    item(1, "Filme.2024.1080p.BluRay.x264", { hearing_impaired: true, download_count: 9000 }),
    item(2, "Filme.2024.SDH.1080p.WEB-DL", { download_count: 5000 }),
    item(3, "Filme.2024.720p.AMZ.WEB-DL", { download_count: 50 }),
    item(4, "Filme.2024.1080p.BluRay.x264-GRP", { download_count: 10 }),
  ], 6, "Filme (2024) 1080p BluRay");
  assert.deepEqual(r.map((o) => o.fileId), [4, 3, 1, 2]);
  assert.equal(r.find((o) => o.fileId === 2)!.hearingImpaired, true); // SDH pelo nome também
  assert.equal(releaseMatch("Filme.2024.1080p.BluRay.x264", "filme 1080p blu-ray"), 3);
  assert.equal(releaseMatch("x", undefined), 0);
  // sem dica: mais baixada entre as não-SDH
  assert.deepEqual(sortOptions(r).map((o) => o.fileId).slice(0, 2), [3, 4]);
});

test("legenda 'forçada' (só placas) nunca é a primeira escolha", () => {
  const item = (id: number, release: string, extra: any = {}) => ({ attributes: { language: "pt-BR", download_count: 10, release, files: [{ file_id: id }], ...extra } });
  const r = rankResults([item(1, "A.Origem.2010.1080p.BluRay.FORCED", { download_count: 9999 }), item(2, "Inception 2010 1080p", { foreign_parts_only: false }), item(3, "Outra", { foreign_parts_only: true, download_count: 5000 })], 6, "A Origem 1080p BluRay");
  assert.equal(r[0].fileId, 2);
  assert.equal(r.find((o) => o.fileId === 1)!.forced, true);
  assert.equal(r.find((o) => o.fileId === 3)!.forced, true);
});
