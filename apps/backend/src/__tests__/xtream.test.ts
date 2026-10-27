import { test } from "node:test";
import assert from "node:assert/strict";
import { buildCatalogFromXtream, fetchSeriesInfo, fetchShortEpg, hmsToSecs, parseXtreamAccount, parseXtreamUrl } from "../content/xtream.js";

test("detecta credenciais Xtream", () => {
  const c = parseXtreamUrl("http://panel.example.test:8080/get.php?username=u1&password=p1&type=m3u_plus&output=ts")!;
  assert.deepEqual(c, { baseUrl: "http://panel.example.test:8080", username: "u1", password: "p1" });
  assert.ok(parseXtreamUrl("http://panel.example.test/player_api.php?username=a&password=b"));
  assert.equal(parseXtreamUrl("http://x.example.test/list.m3u?username=a&password=b"), null);
  assert.ok(parseXtreamUrl("http://x.example.test/list.m3u?username=a&password=b", true));
  assert.equal(parseXtreamUrl("http://x.example.test/list.m3u"), null);
});

const creds = { baseUrl: "http://panel.example.test:8080", username: "u1", password: "p1" };

function fakeApi(map: Record<string, unknown>) {
  const calls: string[] = [];
  const fetcher = async (url: string) => {
    const u = new URL(url);
    const action = u.searchParams.get("action") ?? "";
    calls.push(action);
    if (!(action in map)) throw new Error("404");
    return map[action];
  };
  return { fetcher, calls };
}

test("monta catálogo pela player_api", async () => {
  const { fetcher } = fakeApi({
    get_live_categories: [{ category_id: "1", category_name: "Canais Abertos" }],
    get_live_streams: [
      { stream_id: 11, name: "Canal 1", stream_icon: "http://img.example.test/c1.png", epg_channel_id: "c1.br", category_id: "1", tv_archive: 1 },
      { stream_id: 12, name: "Canal 2", stream_icon: "", epg_channel_id: null, category_id: "1" },
    ],
    get_vod_categories: [{ category_id: "5", category_name: "Filmes Ação" }],
    get_vod_streams: [{ stream_id: 21, name: "Filme A (2020)", stream_icon: "http://img.example.test/f.jpg", rating: "7.5", category_id: "5", container_extension: "mkv" }],
    get_series_categories: [{ category_id: "9", category_name: "Séries Drama" }],
    get_series: [{ series_id: 31, name: "Série B", cover: "http://img.example.test/s.jpg", category_id: "9", releaseDate: "2019-04-01", rating: "8" }],
  });
  const cat = await buildCatalogFromXtream(creds, fetcher);
  assert.equal(cat.source, "xtream");
  assert.equal(cat.epgUrl, "http://panel.example.test:8080/xmltv.php?username=u1&password=p1");
  assert.equal(cat.live.items[0].url, "http://panel.example.test:8080/live/u1/p1/11.m3u8");
  assert.equal(cat.live.items[0].epgId, "c1.br");
  assert.equal(cat.live.items[0].catchup, true);
  assert.equal(cat.live.items[1].logo, null);
  assert.equal(cat.movies.items[0].url, "http://panel.example.test:8080/movie/u1/p1/21.mkv");
  assert.equal(cat.movies.items[0].rating, 7.5);
  assert.equal(cat.movies.items[0].year, 2020);
  assert.equal(cat.series.items[0].seriesId, 31);
  assert.equal(cat.series.items[0].year, 2019);
  assert.deepEqual(cat.live.categories, [{ id: "live-1", name: "Canais Abertos", count: 2 }]);
});

test("catálogo Xtream tolera ação com erro", async () => {
  const { fetcher } = fakeApi({ get_live_categories: [], get_live_streams: [{ stream_id: 1, name: "X", category_id: "7" }] });
  const cat = await buildCatalogFromXtream(creds, fetcher, { liveFormat: "ts" });
  assert.equal(cat.stats.live, 1);
  assert.equal(cat.live.items[0].url.endsWith("/1.ts"), true);
  assert.equal(cat.live.categories[0].name, "Categoria 7");
});

test("get_series_info → temporadas e episódios", async () => {
  const { fetcher } = fakeApi({
    get_series_info: {
      info: { name: "Série B", cover: "http://img.example.test/s.jpg", plot: "Sinopse" },
      episodes: {
        "2": [{ id: "502", episode_num: 1, title: "S2E1", container_extension: "mp4", season: 2, info: { duration_secs: 3000 } }],
        "1": [
          { id: "402", episode_num: 2, title: "S1E2", container_extension: "mkv", season: 1, info: { movie_image: "http://img.example.test/e.jpg" } },
          { id: "401", episode_num: 1, title: "S1E1", container_extension: "mkv", season: 1, info: { plot: "Piloto", duration: "00:42:30" } },
        ],
      },
    },
  });
  const info = await fetchSeriesInfo(creds, 31, fetcher);
  assert.deepEqual(info.seasons.map((s) => s.season), [1, 2]);
  assert.deepEqual(info.seasons[0].episodes.map((e) => e.episode), [1, 2]);
  assert.equal(info.seasons[0].episodes[0].url, "http://panel.example.test:8080/series/u1/p1/401.mkv");
  assert.equal(info.seasons[0].episodes[1].cover, "http://img.example.test/e.jpg");
  assert.equal(info.seasons[1].episodes[0].durationSecs, 3000);
  // sinopse e duração "hh:mm:ss" do episódio
  assert.equal(info.seasons[0].episodes[0].plot, "Piloto");
  assert.equal(info.seasons[0].episodes[0].durationSecs, 2550);
  // sem miniatura própria: usa a capa da série
  assert.equal(info.seasons[0].episodes[0].cover, "http://img.example.test/s.jpg");
});

test("hmsToSecs", () => {
  assert.equal(hmsToSecs("01:02:03"), 3723);
  assert.equal(hmsToSecs("45:10"), 2710);
  assert.equal(hmsToSecs("x"), undefined);
  assert.equal(hmsToSecs(undefined), undefined);
});

test("get_short_epg decodifica base64", async () => {
  const b64 = (s: string) => Buffer.from(s, "utf8").toString("base64");
  const { fetcher } = fakeApi({
    get_short_epg: { epg_listings: [{ title: b64("Jornal Nacional"), description: b64("Notícias"), start_timestamp: "1760000000", stop_timestamp: "1760003600" }] },
  });
  const epg = await fetchShortEpg(creds, 11, fetcher);
  assert.equal(epg[0].title, "Jornal Nacional");
  assert.equal(epg[0].description, "Notícias");
  assert.equal(epg[0].start, new Date(1760000000 * 1000).toISOString());
});

test("vencimento da conta Xtream (user_info.exp_date)", async () => {
  assert.deepEqual(parseXtreamAccount({ user_info: { exp_date: "1795392000", status: "Active", max_connections: "2" } }), {
    expiresAt: new Date(1795392000 * 1000).toISOString(), unlimited: false, status: "Active", maxConnections: 2,
  });
  for (const v of [null, "", "0", "null", "Ilimitado", undefined]) {
    assert.equal(parseXtreamAccount({ user_info: { exp_date: v } })?.unlimited, true);
    assert.equal(parseXtreamAccount({ user_info: { exp_date: v } })?.expiresAt, null);
  }
  assert.equal(parseXtreamAccount({}), null);
  assert.equal(parseXtreamAccount(null), null);
  const { fetcher } = fakeApi({ "": { user_info: { exp_date: 1795392000 } }, get_live_streams: [{ stream_id: 1, name: "A", category_id: "1" }] });
  const cat = await buildCatalogFromXtream(creds, fetcher);
  assert.equal(cat.account?.expiresAt, new Date(1795392000 * 1000).toISOString());
  const { fetcher: f2 } = fakeApi({ get_live_streams: [{ stream_id: 1, name: "A", category_id: "1" }] });
  assert.equal((await buildCatalogFromXtream(creds, f2)).account, null);
});
