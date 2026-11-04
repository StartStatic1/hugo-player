/* Modo demonstração (?demo=1, ?demo=1&big=1 = 420 mil itens): dados falsos, nada vai ao servidor. */
var Mock = (function () {
  var BIG = /[?&]big=1/.test(location.search);
  var pls = [{ id: "demo1", name: "Minha Lista", type: "xtream", protected: false }, { id: "demo2", name: "Lista Kids", type: "m3u", protected: true }];
  var C = ["#22D3EE", "#7C5CFF", "#EC4899", "#0EA5E9", "#A855F7", "#F43F5E"];
  function pic(t, i, w, h) {
    var a = C[i % 6], b = C[(i + 2) % 6];
    return "data:image/svg+xml;utf8," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="' + (w || 300) + '" height="' + (h || 450) + '"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="' + a + '"/><stop offset="1" stop-color="' + b + '" stop-opacity=".55"/></linearGradient></defs><rect width="100%" height="100%" fill="#10131f"/><rect width="100%" height="100%" fill="url(#g)" opacity=".75"/><text x="50%" y="55%" fill="#fff" font-size="' + ((w || 300) / 9) + '" font-family="Arial" font-weight="700" text-anchor="middle">' + t + '</text></svg>');
  }
  function backdrop(t) {
    var i = t.length;
    return "data:image/svg+xml;utf8," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080"><defs><radialGradient id="a" cx="70%" cy="35%" r="70%"><stop offset="0" stop-color="' + C[i % 6] + '"/><stop offset=".55" stop-color="' + C[(i + 1) % 6] + '" stop-opacity=".45"/><stop offset="1" stop-color="#07080F"/></radialGradient></defs><rect width="100%" height="100%" fill="#07080F"/><rect width="100%" height="100%" fill="url(#a)"/><circle cx="1400" cy="380" r="260" fill="#fff" opacity=".06"/><circle cx="1600" cy="560" r="160" fill="#fff" opacity=".05"/><text x="1380" y="460" fill="#fff" opacity=".18" font-size="140" font-family="Arial" font-weight="800" text-anchor="middle">' + t + '</text></svg>');
  }
  var STREAM = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
  var LG = ["Abertos", "Esportes", "Notícias", "Infantil", "Filmes 24h", "Documentários", "Música", "Religiosos"];
  var MG = ["Lançamentos", "Ação", "Comédia", "Drama", "Terror", "Animação", "Ficção", "Romance"];
  var SG = ["Netflix", "HBO", "Animes", "Doramas", "Novelas"];
  var MOV = ["Matrix", "Interestelar", "Duna", "Oppenheimer", "Avatar", "Gladiador", "Coringa", "Top Gun", "Toy Story", "Vingadores", "Titanic", "Batman"];
  var SER = ["Breaking Bad", "Stranger Things", "The Last of Us", "Dark", "La Casa de Papel", "Round 6", "The Boys", "Wandinha"];
  var N = { live: BIG ? 12000 : 64, movie: BIG ? 300000 : 96, series: BIG ? 108000 : 40 };
  function item(t, i) {
    if (t === "live") { var g = LG[i % LG.length]; return { name: g + " " + (Math.floor(i / LG.length) + 1) + " HD", url: STREAM + "#l" + i, group: g, type: "live", epgId: "ch" + i, logo: pic("TV " + (i + 1), i, 160, 100) }; }
    if (t === "movie") { var n = MOV[i % MOV.length] + (i >= MOV.length ? " " + (Math.floor(i / MOV.length) + 1) : ""); return { name: n, url: STREAM + "#m" + i, group: MG[i % MG.length], type: "movie", logo: pic(MOV[i % MOV.length], i) }; }
    var sn = SER[i % SER.length] + (i >= SER.length ? " " + (Math.floor(i / SER.length) + 1) : "");
    return { name: sn, url: "xtream://series/" + (i + 1), seriesId: i + 1, seriesName: sn, group: SG[i % SG.length], type: "series", logo: pic(SER[i % SER.length], i + 3) };
  }
  function prog(key, i) {
    var now = Date.now(), slot = 30 * 60000, s = Math.floor(now / slot) * slot + (i - 1) * slot;
    var T = ["Jornal da Manhã", "Futebol ao Vivo", "Filme da Tarde", "Desenhos", "Documentário", "Show de Música", "Série Policial", "Debate"];
    var h = 0; for (var j = 0; j < key.length; j++) h += key.charCodeAt(j);
    return { title: T[(h + i) % T.length], start: new Date(s).toISOString(), stop: new Date(s + slot).toISOString(), desc: "Programação de demonstração." };
  }
  function q(path, k) { var m = new RegExp("[?&]" + k + "=([^&]*)").exec(path); return m ? decodeURIComponent(m[1]) : null; }
  function handle(m, path, body) {
    var r;
    if (path.indexOf("/devices/register") === 0) r = { registered: true, trial: "granted" };
    else if (path.indexOf("/devices/status") === 0) r = { deviceId: "02:AB:CD:EF:12:34", license: { type: "trial", plan: "trial", active: true, expiresAt: new Date(Date.now() + 5 * 864e5).toISOString() }, playlists: pls.length };
    else if (path.indexOf("/channels/version") === 0) r = { versionName: "0.5.64" };
    else if (/\/verify-pin$/.test(path)) { if (body && body.pin === "1234") r = { ok: true }; else return Promise.reject(Object.assign(new Error("PIN incorreto"), { status: 403 })); }
    else if (path.indexOf("/playlists") === 0 && m === "GET") r = { playlists: pls };
    else if (path.indexOf("/playlists") === 0 && m === "POST") { pls.push({ id: "demo" + (pls.length + 1), name: body.name, type: body.type }); r = { playlist: pls[pls.length - 1] }; }
    else if (path.indexOf("/playlists") === 0 && m === "DELETE") { pls = pls.filter(function (p) { return path.indexOf(p.id) < 0; }); r = {}; }
    else if (path.indexOf("/channels/series-episodes") === 0) { var e = []; for (var s = 1; s <= 3; s++) for (var i = 1; i <= 8; i++) e.push({ name: "Episódio " + i, season: s, episode: i, url: STREAM + "#e" + s + i, durationSecs: 2700 }); r = { name: "Série", plot: "Uma série de demonstração.", episodes: e }; }
    else if (path.indexOf("/epg/now-next") === 0) { r = { available: true, channels: {} }; (body.ids || []).forEach(function (id) { r.channels[id] = { channelId: id, now: prog(id, 1), next: prog(id, 2) }; }); }
    else if (path.indexOf("/epg/channel/") === 0) { var key = decodeURIComponent(path.split("/")[3].split("?")[0]), ps = []; for (var x = 1; x <= 10; x++) ps.push(prog(key, x)); r = { channelId: key, programmes: ps }; }
    else if (path.indexOf("/tmdb/search") === 0) { var qq = q(path, "q") || ""; r = { results: [{ backdrop_path: backdrop(qq), overview: "Sinopse de demonstração para “" + qq + "”. No app real vem do TMDB, com a imagem de fundo do título." }] }; }
    else if (path.indexOf("/channels") === 0) {
      var t = q(path, "type") || "live", page = +(q(path, "page") || 0), lim = +(q(path, "limit") || 50), n = N[t], out = [];
      for (var z = page * lim; z < Math.min(n, (page + 1) * lim); z++) out.push(item(t, z));
      r = { total: n, page: page, limit: lim, hasMore: (page + 1) * lim < n, channels: out };
    }
    return new Promise(function (ok) { setTimeout(function () { ok(JSON.parse(JSON.stringify(r))); }, 60); });
  }
  return { handle: handle, pic: pic };
})();
