/*
 * Hugo Player — servidor de teste para revisão das lojas (Samsung/LG).
 * API compatível com Xtream (player_api.php, get.php, xmltv.php) servindo SOMENTE
 * conteúdo livre: filmes abertos da Blender Foundation (CC BY) e streams públicos de teste.
 * Sem dependências. Config por env: PORT, REVIEW_USER, REVIEW_PASS, PUBLIC_URL.
 */
const http = require("http");
const { URL } = require("url");
const PORT = +(process.env.PORT || 8095);
const USER = process.env.REVIEW_USER || "reviewer";
const PASS = process.env.REVIEW_PASS || "change-me";
const PUBLIC_URL = (process.env.PUBLIC_URL || "http://localhost:" + PORT).replace(/\/$/, "");

const A = "https://archive.org/download/";
const HLS = {
  bbb: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
  tos: "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
  tos2: "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.mp4/.m3u8",
  bipbop: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
};
const CC = "Blender Foundation — CC BY 3.0 / 4.0 (blender.org)";
const MOVIES = [
  { id: 1001, cat: "1", name: "Big Buck Bunny", year: 2008, url: A + "BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4", plot: "Curta de animação aberto da Blender Foundation. " + CC },
  { id: 1002, cat: "1", name: "Elephants Dream", year: 2006, url: A + "ElephantsDream/ed_1024_512kb.mp4", plot: "Primeiro filme aberto da Blender Foundation. " + CC },
  { id: 1003, cat: "1", name: "Sintel", year: 2010, url: A + "Sintel/sintel-2048-stereo_512kb.mp4", plot: "Curta de fantasia aberto da Blender Foundation. " + CC },
  { id: 1004, cat: "2", name: "Tears of Steel", year: 2012, url: HLS.tos2, ext: "m3u8", plot: "Curta de ficção científica aberto da Blender Foundation. " + CC },
];
const LIVE = [
  { id: 1, cat: "1", name: "Demo 1 — Animação", url: HLS.bbb, epg: "demo1" },
  { id: 2, cat: "1", name: "Demo 2 — Ficção", url: HLS.tos, epg: "demo2" },
  { id: 3, cat: "1", name: "Demo 3 — Cinema aberto", url: HLS.tos2, epg: "demo3" },
  { id: 4, cat: "2", name: "Demo 4 — Sinal de teste", url: HLS.bipbop, epg: "demo4" },
];
const SERIES = [{ series_id: 501, cat: "1", name: "Filmes Abertos", plot: "Os filmes abertos da Blender Foundation como episódios. " + CC,
  eps: [[2001, "Elephants Dream", A + "ElephantsDream/ed_1024_512kb.mp4", 654, "mp4"], [2002, "Big Buck Bunny", A + "BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4", 596, "mp4"], [2003, "Sintel", A + "Sintel/sintel-2048-stereo_512kb.mp4", 888, "mp4"], [2004, "Tears of Steel", HLS.tos2, 734, "m3u8"]] }];
const EP = {}; SERIES.forEach((s) => s.eps.forEach((e) => (EP[e[0]] = e[2])));
const COLORS = ["#22D3EE", "#7C5CFF", "#EC4899", "#0EA5E9", "#A855F7", "#F43F5E"];
const art = (t, w, h) => PUBLIC_URL + "/art/" + w + "x" + h + "/" + encodeURIComponent(t) + ".svg";
function svg(t, w, h) {
  let s = 0; for (const c of t) s += c.charCodeAt(0);
  const a = COLORS[s % 6], b = COLORS[(s + 2) % 6], esc = t.replace(/[<&>"]/g, "");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}" stop-opacity=".55"/></linearGradient></defs><rect width="100%" height="100%" fill="#10131f"/><rect width="100%" height="100%" fill="url(#g)" opacity=".8"/><text x="50%" y="52%" fill="#fff" font-size="${Math.round(w / 11)}" font-family="Arial" font-weight="700" text-anchor="middle">${esc}</text><text x="50%" y="${h - 14}" fill="#fff" opacity=".6" font-size="${Math.max(10, Math.round(w / 28))}" font-family="Arial" text-anchor="middle">DEMO</text></svg>`;
}
const now = () => Math.floor(Date.now() / 1000);
const TITLES = ["Sessão Aberta", "Curtas da Tarde", "Mostra de Animação", "Clássicos Abertos", "Bastidores", "Sessão Noturna"];
function progs(ch, n) {
  const slot = 1800, base = Math.floor(now() / slot) * slot, out = [];
  for (let i = 0; i < n; i++) {
    const st = base + i * slot, t = TITLES[(ch.id + Math.floor(st / slot)) % TITLES.length];
    out.push({ id: String(st), epg_id: ch.epg, title: Buffer.from(t).toString("base64"), lang: "pt", start: new Date(st * 1000).toISOString().slice(0, 19).replace("T", " "), end: new Date((st + slot) * 1000).toISOString().slice(0, 19).replace("T", " "), description: Buffer.from("Programação de demonstração com conteúdo livre (" + CC + ").").toString("base64"), channel_id: ch.epg, start_timestamp: String(st), stop_timestamp: String(st + slot) });
  }
  return out;
}
function api(q) {
  const a = q.get("action");
  const pu = new URL(PUBLIC_URL);
  if (!a) return { user_info: { username: USER, password: PASS, message: "Conta de teste para revisão", auth: 1, status: "Active", exp_date: String(now() + 3650 * 86400), is_trial: "0", active_cons: "0", created_at: "1762200000", max_connections: "5", allowed_output_formats: ["m3u8", "ts"] }, server_info: { url: pu.hostname, port: pu.port || "80", https_port: pu.port || "443", server_protocol: pu.protocol.replace(":", ""), rtmp_port: "0", timezone: "America/Fortaleza", timestamp_now: now(), time_now: new Date().toISOString().slice(0, 19).replace("T", " ") } };
  if (a === "get_live_categories") return [{ category_id: "1", category_name: "Demonstração", parent_id: 0 }, { category_id: "2", category_name: "Sinais de teste", parent_id: 0 }];
  if (a === "get_vod_categories") return [{ category_id: "1", category_name: "Animação (livre)", parent_id: 0 }, { category_id: "2", category_name: "Ficção (livre)", parent_id: 0 }];
  if (a === "get_series_categories") return [{ category_id: "1", category_name: "Coleções livres", parent_id: 0 }];
  if (a === "get_live_streams") return LIVE.map((c, i) => ({ num: i + 1, name: c.name, stream_type: "live", stream_id: c.id, stream_icon: art(c.name.split(" — ")[0], 320, 200), epg_channel_id: c.epg, added: "1762200000", category_id: c.cat, tv_archive: 0 }));
  if (a === "get_vod_streams") return MOVIES.map((m, i) => ({ num: i + 1, name: m.name, stream_type: "movie", stream_id: m.id, stream_icon: art(m.name, 300, 450), rating: "7", year: String(m.year), added: "1762200000", category_id: m.cat, container_extension: m.ext || "mp4" }));
  if (a === "get_vod_info") { const m = MOVIES.find((x) => x.id == q.get("vod_id")); return m ? { info: { name: m.name, plot: m.plot, releasedate: m.year + "-01-01", movie_image: art(m.name, 300, 450) }, movie_data: { stream_id: m.id, name: m.name, container_extension: m.ext || "mp4" } } : {}; }
  if (a === "get_series") return SERIES.map((s, i) => ({ num: i + 1, name: s.name, series_id: s.series_id, cover: art(s.name, 300, 450), plot: s.plot, releaseDate: "2006-01-01", rating: "8", category_id: s.cat, episode_count: s.eps.length }));
  if (a === "get_series_info") { const s = SERIES.find((x) => x.series_id == q.get("series_id")); if (!s) return {}; return { info: { name: s.name, cover: art(s.name, 300, 450), plot: s.plot, releaseDate: "2006-01-01" }, episodes: { 1: s.eps.map((e, i) => ({ id: String(e[0]), episode_num: i + 1, title: e[1], container_extension: e[4], season: 1, info: { duration_secs: e[3], plot: e[1] + " — " + CC, movie_image: art(e[1], 480, 270) } })) } }; }
  if (a === "get_short_epg" || a === "get_simple_data_table") { const c = LIVE.find((x) => x.id == q.get("stream_id")); return { epg_listings: c ? progs(c, +(q.get("limit") || 4)) : [] }; }
  return [];
}
function m3u() {
  let s = "#EXTM3U\n";
  for (const c of LIVE) s += `#EXTINF:-1 tvg-id="${c.epg}" tvg-logo="${art(c.name.split(" — ")[0], 320, 200)}" group-title="Demonstração",${c.name}\n${PUBLIC_URL}/live/${USER}/${PASS}/${c.id}.m3u8\n`;
  for (const m of MOVIES) s += `#EXTINF:-1 tvg-logo="${art(m.name, 300, 450)}" group-title="Filmes livres",${m.name}\n${PUBLIC_URL}/movie/${USER}/${PASS}/${m.id}.${m.ext || "mp4"}\n`;
  return s;
}
function xmltv() {
  const x = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;");
  const f = (ts) => new Date(ts * 1000).toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";
  let s = '<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="hugo-review-server">\n';
  for (const c of LIVE) s += `<channel id="${c.epg}"><display-name>${x(c.name)}</display-name></channel>\n`;
  for (const c of LIVE) for (const p of progs(c, 48)) s += `<programme start="${f(+p.start_timestamp)}" stop="${f(+p.stop_timestamp)}" channel="${c.epg}"><title>${x(Buffer.from(p.title, "base64").toString())}</title><desc>${x(Buffer.from(p.description, "base64").toString())}</desc></programme>\n`;
  return s + "</tv>\n";
}
const authOk = (u, p) => u === USER && p === PASS;
http.createServer((req, res) => {
  const u = new URL(req.url, "http://x"), q = u.searchParams, p = u.pathname;
  const send = (code, body, type, extra) => { res.writeHead(code, Object.assign({ "Content-Type": type || "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" }, extra || {})); res.end(typeof body === "string" ? body : JSON.stringify(body)); };
  if (p === "/" || p === "/health") return send(200, { ok: true, service: "hugo-review-server", content: "Somente conteúdo livre (Blender Foundation, CC BY) e streams públicos de teste." });
  let m = /^\/art\/(\d+)x(\d+)\/(.+)\.svg$/.exec(p);
  if (m) return send(200, svg(decodeURIComponent(m[3]).slice(0, 40), Math.min(+m[1], 1920), Math.min(+m[2], 1080)), "image/svg+xml", { "Cache-Control": "public, max-age=86400" });
  if (/^\/(player_api|panel_api)\.php$/.test(p)) { if (!authOk(q.get("username"), q.get("password"))) return send(401, { user_info: { auth: 0 } }); return send(200, api(q)); }
  if (p === "/get.php") { if (!authOk(q.get("username"), q.get("password"))) return send(401, "", "text/plain"); return send(200, m3u(), "audio/x-mpegurl"); }
  if (p === "/xmltv.php") { if (!authOk(q.get("username"), q.get("password"))) return send(401, "", "text/plain"); return send(200, xmltv(), "application/xml; charset=utf-8"); }
  m = /^\/(?:(live|movie|series)\/)?([^/]+)\/([^/]+)\/(\d+)(?:\.\w+)?$/.exec(p);
  if (m) {
    if (!authOk(decodeURIComponent(m[2]), decodeURIComponent(m[3]))) return send(401, "", "text/plain");
    const id = +m[4], kind = m[1] || "live";
    const target = kind === "live" ? (LIVE.find((c) => c.id === id) || {}).url : kind === "movie" ? (MOVIES.find((x) => x.id === id) || {}).url : EP[id];
    if (!target) return send(404, "", "text/plain");
    res.writeHead(302, { Location: target, "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" }); return res.end();
  }
  send(404, { error: "not_found" });
}).listen(PORT, "127.0.0.1", () => console.log("hugo-review-server on 127.0.0.1:" + PORT));
