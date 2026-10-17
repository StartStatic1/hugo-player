import { test } from "node:test";
import assert from "node:assert/strict";
import { classifyEntry, parseEpisode, extractYear, urlPathType } from "../content/classify.js";
import { parseM3U } from "../content/m3u.js";
import { buildCatalogFromM3U } from "../content/catalog.js";
import { SAMPLE_M3U } from "./fixtures.js";

const c = (name: string, group: string, url: string, attrs: Record<string, string> = {}) =>
  classifyEntry({ name, group, url, attrs }).type;

test("caminho Xtream decide o tipo", () => {
  assert.equal(urlPathType("http://h:80/live/u/p/1.m3u8"), "live");
  assert.equal(urlPathType("http://h:80/movie/u/p/1.mp4"), "movie");
  assert.equal(urlPathType("http://h:80/series/u/p/1.mkv"), "series");
  assert.equal(urlPathType("http://h:80/u/p/1"), null);
  // mesmo com grupo enganoso, o caminho vence
  assert.equal(c("Qualquer", "Filmes", "http://h/live/u/p/9.ts"), "live");
  assert.equal(c("Qualquer", "Canais", "http://h/movie/u/p/9.mp4"), "movie");
});

test("extensões: m3u8/ts = live, mp4/mkv = vod", () => {
  assert.equal(c("Canal X", "", "http://h/stream/1.m3u8"), "live");
  assert.equal(c("Canal X", "", "http://h/stream/1.ts"), "live");
  assert.equal(c("Algo", "", "http://h/vod/a.mp4"), "movie");
  assert.equal(c("Algo", "", "http://h/a.mkv?token=1"), "movie");
  assert.equal(c("Algo", "", "http://h/u/p/123"), "live");
});

test("palavras-chave do grupo em PT/EN/ES/AR", () => {
  assert.equal(c("A", "Filmes Dublados", "http://h/a.mp4"), "movie");
  assert.equal(c("A", "MOVIES 4K", "http://h/a"), "movie");
  assert.equal(c("A", "Películas Estrenos", "http://h/a"), "movie");
  assert.equal(c("A", "أفلام عربية", "http://h/a.mp4"), "movie");
  assert.equal(c("A", "مسلسلات رمضان", "http://h/a.mp4"), "series");
  assert.equal(c("A", "Séries Netflix", "http://h/a.mp4"), "series");
  assert.equal(c("A", "Canais Abertos", "http://h/a"), "live");
  assert.equal(c("A", "قنوات رياضية", "http://h/a"), "live");
  assert.equal(c("Desenhos", "24/7 Desenhos", "http://h/a.mp4"), "live");
  assert.equal(c("Globo SP", "Canais | Globo", "http://h/a.ts"), "live");
});

test("tvg-id/catchup puxam para live", () => {
  assert.equal(c("Algo", "Variedades", "http://h/u/p/1", { "tvg-id": "x", catchup: "default" }), "live");
});

test("padrões de episódio", () => {
  assert.deepEqual(parseEpisode("Breaking Test S01E02"), { show: "Breaking Test", season: 1, episode: 2 });
  assert.deepEqual(parseEpisode("Show.Name.S02.E10.720p"), { show: "Show Name", season: 2, episode: 10 });
  assert.deepEqual(parseEpisode("Minha Série T03E07"), { show: "Minha Série", season: 3, episode: 7 });
  assert.deepEqual(parseEpisode("Outra 1x03"), { show: "Outra", season: 1, episode: 3 });
  assert.deepEqual(parseEpisode("Show Temporada 2 Episódio 5"), { show: "Show", season: 2, episode: 5 });
  assert.deepEqual(parseEpisode("Show - Season 1 Episode 12"), { show: "Show", season: 1, episode: 12 });
  assert.equal(parseEpisode("Canal SporTV 2 HD"), null);
  assert.equal(parseEpisode("Filme (2020)"), null);
  assert.equal(c("Série Qualquer S01E01", "", "http://h/a.mp4"), "series");
});

test("ano do filme", () => {
  assert.equal(extractYear("Filme (2021)"), 2021);
  assert.equal(extractYear("Filme [1999]"), 1999);
  assert.equal(extractYear("Canal 24"), undefined);
});

test("parser M3U: atributos, vírgulas, EXTVLCOPT, url-tvg", () => {
  const pl = parseM3U(SAMPLE_M3U);
  assert.equal(pl.epgUrl, "http://epg.example.test/guide.xml.gz");
  assert.equal(pl.entries.length, 12);
  const last = pl.entries[11];
  assert.equal(last.name, "Canal Com, Vírgula");
  assert.equal(last.group, "Variedades, Música");
  assert.equal(last.userAgent, "CustomUA/1.0");
  assert.equal(pl.entries[0].logo, "http://img.example.test/news.png");
  assert.equal(pl.entries[0].tvgId, "news.br");
});

test("catálogo M3U separa live/filmes/séries e agrupa episódios", () => {
  const cat = buildCatalogFromM3U(parseM3U(SAMPLE_M3U));
  assert.equal(cat.stats.live, 4, JSON.stringify(cat.live.items.map((i) => i.name)));
  assert.equal(cat.stats.movies, 3, JSON.stringify(cat.movies.items.map((i) => i.name)));
  assert.equal(cat.stats.episodes, 5);
  assert.equal(cat.stats.series, 3);
  const show = cat.series.items.find((s) => s.name === "Série Exemplo")!;
  assert.ok(show, "série agrupada");
  assert.deepEqual(show.seasons.map((s) => s.season), [1, 2]);
  assert.deepEqual(show.seasons[0].episodes.map((e) => e.episode), [1, 2]);
  assert.equal(show.cover, "http://img.example.test/show.jpg");
  const news = cat.live.items.find((i) => i.epgId === "news.br")!;
  assert.equal(news.logo, "http://img.example.test/news.png");
  assert.equal(cat.movies.items.find((m) => m.name.startsWith("O Grande"))!.year, 2021);
  // categorias mantêm contagem
  assert.equal(cat.series.categories.find((x) => x.name === "Séries | Drama")!.count, 1);
});

test("ids únicos mesmo com entradas repetidas", () => {
  const dup = "#EXTM3U\n#EXTINF:-1 group-title=\"Canais\",A\nhttp://h.example.test/1.ts\n#EXTINF:-1 group-title=\"Canais\",A\nhttp://h.example.test/1.ts\n";
  const cat = buildCatalogFromM3U(parseM3U(dup));
  assert.equal(cat.live.items.length, 2);
  assert.notEqual(cat.live.items[0].id, cat.live.items[1].id);
});
