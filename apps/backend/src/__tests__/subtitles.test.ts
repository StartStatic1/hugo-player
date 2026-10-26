import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import type { AddressInfo } from "node:net";
import express from "express";
import { buildSearchQuery, cleanSrt, createSubtitlesRouter, pickTmdbId, rankResults } from "../subtitles.js";

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
    if (u.includes("/subtitles?")) return json(200, { data: [{ attributes: { language: "pt-BR", download_count: 5, release: "Filme.2010", files: [{ file_id: 77 }] } }] });
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
