/* Hugo API — mesmo fluxo do app Android: Device ID (formato MAC) + Key, registro, licença, playlists no servidor. */
var Api = (function () {
  var KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  function rnd(n) { var a = new Uint8Array(n); (window.crypto || window.msCrypto).getRandomValues(a); return a; }
  function genId() {
    var b = rnd(6); b[0] = (b[0] & 0xFE) | 0x02;
    return Array.prototype.map.call(b, function (x) { return ("0" + x.toString(16).toUpperCase()).slice(-2); }).join(":");
  }
  function genKey() { var b = rnd(8), s = ""; for (var i = 0; i < 8; i++) s += KEY_ALPHABET[b[i] % 32]; return s; }
  function identity() {
    var id = localStorage.getItem("hugo.deviceId"), key = localStorage.getItem("hugo.deviceKey");
    if (!id || !key) { id = genId(); key = genKey(); localStorage.setItem("hugo.deviceId", id); localStorage.setItem("hugo.deviceKey", key); }
    return { deviceId: id, deviceKey: key };
  }
  function platform() {
    if (window.tizen) return "samsung-tv";
    if (window.webOS || window.PalmSystem) return "lg-tv";
    return "smarttv-web";
  }
  function req(method, path, body, extra) {
    if (HUGO.DEMO) return Mock.handle(method, path, body);
    var idt = identity();
    var h = { "x-device-id": idt.deviceId, "x-device-key": idt.deviceKey };
    if (body) h["Content-Type"] = "application/json";
    for (var k in (extra || {})) h[k] = extra[k];
    return fetch(HUGO.API + path, { method: method, headers: h, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) {
        return r.text().then(function (t) {
          var j = null; try { j = t ? JSON.parse(t) : null; } catch (e) {}
          if (!r.ok) { var err = new Error((j && (j.message || j.error)) || ("HTTP " + r.status)); err.status = r.status; err.body = j; throw err; }
          return j;
        });
      });
  }
  var enc = encodeURIComponent;
  return {
    identity: identity,
    platform: platform,
    register: function () {
      var idt = identity();
      if (localStorage.getItem("hugo.registered") === "1") return Promise.resolve({ registered: true });
      return req("POST", "/devices/register", { deviceId: idt.deviceId, deviceKey: idt.deviceKey, platform: platform(), appVersion: HUGO.VERSION })
        .then(function (r) { localStorage.setItem("hugo.registered", "1"); return r; });
    },
    status: function () { return req("GET", "/devices/status"); },
    version: function () { return req("GET", "/channels/version"); },
    playlists: function () { return req("GET", "/playlists").then(function (r) { return r.playlists || []; }); },
    addPlaylist: function (p) { return req("POST", "/playlists", p); },
    deletePlaylist: function (id) { return req("DELETE", "/playlists/" + enc(id)); },
    // catálogo inteiro pelo id da playlist (o servidor usa o link guardado: sem usuário/senha na URL)
    catalog: function (id) { return req("GET", "/channels?playlistId=" + enc(id) + "&limit=80000"); },
    episodes: function (id, seriesId) { return req("GET", "/channels/series-episodes?playlistId=" + enc(id) + "&seriesId=" + enc(seriesId)); }
  };
})();
