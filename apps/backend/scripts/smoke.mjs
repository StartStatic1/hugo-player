// Smoke test ponta a ponta: sobe um "provedor" falso (M3U + XMLTV.gz + Xtream API)
// e a API do Hugo Player, e confere /catalog, /catalog/series e /epg.
// Uso: npm run build && npm run smoke   (nenhuma URL real é acessada)
import http from "node:http";
import { spawn } from "node:child_process";
import { gzipSync } from "node:zlib";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import assert from "node:assert/strict";
import { SAMPLE_M3U, SAMPLE_XMLTV } from "../dist/__tests__/fixtures.js";

const UP = 18799;
const API = 18091;
const up = `http://127.0.0.1:${UP}`;

const xtream = {
  get_live_categories: [{ category_id: "1", category_name: "Canais Abertos" }],
  get_live_streams: [{ stream_id: 11, name: "Canal Notícias", stream_icon: `${up}/c1.png`, epg_channel_id: "news.br", category_id: "1" }],
  get_vod_categories: [{ category_id: "5", category_name: "Filmes" }],
  get_vod_streams: [{ stream_id: 21, name: "Filme A (2020)", stream_icon: `${up}/f.jpg`, category_id: "5", container_extension: "mp4" }],
  get_series_categories: [{ category_id: "9", category_name: "Séries" }],
  get_series: [{ series_id: 31, name: "Série B", cover: `${up}/s.jpg`, category_id: "9" }],
  get_series_info: { info: { name: "Série B" }, episodes: { "1": [{ id: "401", episode_num: 1, title: "Piloto", container_extension: "mkv", season: 1 }] } },
};

const m3u = SAMPLE_M3U.replace("http://epg.example.test/guide.xml.gz", `${up}/guide.xml.gz`);
const upstream = http.createServer((req, res) => {
  const u = new URL(req.url, up);
  if (u.pathname === "/list.m3u") return res.end(m3u);
  if (u.pathname === "/guide.xml.gz" || u.pathname === "/xmltv.php") return res.end(gzipSync(SAMPLE_XMLTV(new Date())));
  if (u.pathname === "/player_api.php") {
    const a = u.searchParams.get("action");
    if (a && a in xtream) return res.end(JSON.stringify(xtream[a]));
    return res.end(JSON.stringify({ user_info: { auth: 1 } }));
  }
  res.statusCode = 404;
  res.end();
});
await new Promise((r) => upstream.listen(UP, r));

const dataDir = mkdtempSync(path.join(tmpdir(), "hugo-smoke-"));
const api = spawn(process.execPath, ["dist/main.js"], {
  env: { ...process.env, PORT: String(API), ALLOW_PRIVATE_URLS: "1", DATA_FILE: path.join(dataDir, "db.json") },
  stdio: ["ignore", "inherit", "inherit"],
});

const base = `http://127.0.0.1:${API}`;
const H = { "x-device-id": "AA:BB:CC:DD:EE:01", "x-device-key": "SMOKE123", "content-type": "application/json" };
const j = async (p, init) => {
  const r = await fetch(base + p, init);
  const body = await r.json();
  if (!r.ok) throw new Error(`${p} -> ${r.status} ${JSON.stringify(body)}`);
  return body;
};

try {
  for (let i = 0; i < 50; i++) {
    try { await fetch(base + "/health"); break; } catch { await new Promise((r) => setTimeout(r, 100)); }
  }
  await j("/devices/register", { method: "POST", headers: H, body: JSON.stringify({ deviceId: H["x-device-id"], deviceKey: H["x-device-key"] }) });
  const m = await j("/playlists", { method: "POST", headers: H, body: JSON.stringify({ name: "M3U", url: `${up}/list.m3u` }) });
  const x = await j("/playlists", { method: "POST", headers: H, body: JSON.stringify({ name: "Xtream", type: "xtream", url: up, username: "u", password: "p" }) });

  // M3U
  const cat = await j(`/catalog?playlistId=${m.playlist.id}`, { headers: H });
  assert.deepEqual(cat.stats, { live: 4, movies: 3, series: 3, episodes: 5 });
  console.log("✔ /catalog M3U", cat.stats);
  const show = cat.series.items.find((s) => s.name === "Série Exemplo");
  const det = await j(`/catalog/series/${show.id}?playlistId=${m.playlist.id}`, { headers: H });
  assert.equal(det.seasons.length, 2);
  console.log("✔ /catalog/series M3U", det.seasons.map((s) => `T${s.season}:${s.episodes.length}`).join(" "));
  const keys = cat.live.items.map((c) => c.epgId ?? c.name);
  const epg = await j("/epg/now-next", { method: "POST", headers: H, body: JSON.stringify({ playlistId: m.playlist.id, ids: keys }) });
  assert.equal(epg.channels["news.br"].now.title, "Jornal Agora");
  assert.equal(epg.channels["sport1"].next.title, "Mesa Redonda");
  console.log("✔ /epg/now-next M3U (url-tvg .gz)", Object.keys(epg.channels));

  // Xtream
  const xc = await j(`/catalog?playlistId=${x.playlist.id}`, { headers: H });
  assert.equal(xc.source, "xtream");
  assert.equal(xc.live.items[0].url, `${up}/live/u/p/11.m3u8`);
  console.log("✔ /catalog Xtream", xc.stats);
  const xs = await j(`/catalog/series/x-series-31?playlistId=${x.playlist.id}`, { headers: H });
  assert.equal(xs.seasons[0].episodes[0].url, `${up}/series/u/p/401.mkv`);
  console.log("✔ /catalog/series Xtream (get_series_info)");
  const xe = await j("/epg/now-next", { method: "POST", headers: H, body: JSON.stringify({ playlistId: x.playlist.id, ids: ["news.br"] }) });
  assert.equal(xe.channels["news.br"].now.title, "Jornal Agora");
  console.log("✔ /epg/now-next Xtream (xmltv.php)");

  // Auth
  const bad = await fetch(`${base}/catalog?playlistId=${m.playlist.id}`, { headers: { ...H, "x-device-key": "ERRADA1" } });
  assert.equal(bad.status, 401);
  console.log("✔ 401 com chave errada");
  console.log("SMOKE OK");
} finally {
  api.kill();
  upstream.close();
}
