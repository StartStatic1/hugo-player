/* Modo demonstração (?demo=1): dados falsos para testar a interface sem tocar no servidor. */
var Mock = (function () {
  var pls = [{ id: "demo1", name: "Minha Lista", type: "xtream", protected: false }];
  function pic(t, c) { return "data:image/svg+xml;utf8," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="220" height="310"><rect width="100%" height="100%" fill="' + c + '"/><text x="50%" y="50%" fill="#fff" font-size="26" font-family="Arial" text-anchor="middle">' + t + '</text></svg>'); }
  var colors = ["#7c3aed", "#2563eb", "#db2777", "#059669", "#d97706", "#0891b2"];
  function catalog() {
    var ch = [], groups = ["Abertos", "Esportes", "Filmes 24h", "Notícias", "Infantil"];
    groups.forEach(function (g, gi) { for (var i = 1; i <= 8; i++) ch.push({ name: g + " " + i + " HD", url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", group: g, type: "live", logo: pic("TV", colors[gi]) }); });
    ["Ação", "Comédia", "Drama"].forEach(function (g, gi) { for (var i = 1; i <= 12; i++) ch.push({ name: "Filme " + g + " " + i, url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", group: g, type: "movie", logo: pic("Filme " + i, colors[(gi + i) % 6]) }); });
    ["Séries Netflix", "Animes"].forEach(function (g, gi) { for (var i = 1; i <= 10; i++) ch.push({ name: "Série " + i, url: "xtream://series/" + (gi * 100 + i), seriesId: gi * 100 + i, group: g, type: "series", logo: pic("Série " + i, colors[(gi * 3 + i) % 6]) }); });
    return { total: ch.length, channels: ch };
  }
  function handle(m, path, body) {
    var r;
    if (path.indexOf("/devices/register") === 0) r = { registered: true, trial: "granted" };
    else if (path.indexOf("/devices/status") === 0) r = { deviceId: "02:AB:CD:EF:12:34", license: { type: "trial", plan: "trial", active: true, expiresAt: new Date(Date.now() + 5 * 864e5).toISOString() }, playlists: pls.length };
    else if (path.indexOf("/channels/version") === 0) r = { versionName: "0.5.64" };
    else if (path.indexOf("/playlists") === 0 && m === "GET") r = { playlists: pls };
    else if (path.indexOf("/playlists") === 0 && m === "POST") { pls.push({ id: "demo" + (pls.length + 1), name: body.name, type: body.type }); r = { playlist: pls[pls.length - 1] }; }
    else if (path.indexOf("/playlists") === 0 && m === "DELETE") { pls = pls.filter(function (p) { return path.indexOf(p.id) < 0; }); r = {}; }
    else if (path.indexOf("/channels/series-episodes") === 0) { var e = []; for (var s = 1; s <= 2; s++) for (var i = 1; i <= 6; i++) e.push({ name: "Episódio " + i, season: s, episode: i, url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" }); r = { name: "Série", episodes: e }; }
    else if (path.indexOf("/channels") === 0) r = catalog();
    return new Promise(function (ok) { setTimeout(function () { ok(JSON.parse(JSON.stringify(r))); }, 120); });
  }
  return { handle: handle };
})();
