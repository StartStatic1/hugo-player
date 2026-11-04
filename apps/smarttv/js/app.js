/* Hugo Player — Smart TV (Samsung Tizen / LG webOS). */
(function () {
  var S = { license: null, playlists: [], playlist: null, cat: null, tab: "live", group: null, favs: JSON.parse(localStorage.getItem("hugo.favs") || "{}"), back: [] };
  var $ = function (id) { return document.getElementById(id); };
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function toast(t) { var e = $("toast"); e.textContent = t; e.classList.remove("hidden"); clearTimeout(toast.t); toast.t = setTimeout(function () { e.classList.add("hidden"); }, 3500); }
  function screen(html) { $("screen").innerHTML = html; }
  function on(sel, fn) { Array.prototype.forEach.call($("screen").querySelectorAll(sel), function (el) { el._enter = fn; }); }
  function saveFavs() { localStorage.setItem("hugo.favs", JSON.stringify(S.favs)); }
  function favKey(c) { return (S.playlist ? S.playlist.id : "") + "|" + c.type + "|" + c.name; }

  /* ---------- menu ---------- */
  var MENU = [["live", "Ao vivo"], ["movie", "Filmes"], ["series", "Séries"], ["favs", "Favoritos"], ["playlists", "Playlists"], ["settings", "Configurações"]];
  function menu(show) {
    var m = $("menu");
    if (!show) { m.classList.add("hidden"); return; }
    m.classList.remove("hidden");
    m.innerHTML = '<div class="logo">Hugo Player</div>' + MENU.map(function (x) { return '<div class="item focusable' + (S.tab === x[0] ? " active" : "") + '" data-tab="' + x[0] + '">' + x[1] + "</div>"; }).join("");
    Array.prototype.forEach.call(m.querySelectorAll(".item"), function (el) { el._enter = function () { go(el.dataset.tab); }; });
  }
  function go(tab) {
    S.tab = tab; S.group = null; menu(true);
    if (tab === "playlists") return playlistsScreen();
    if (tab === "settings") return settingsScreen();
    if (!S.cat) return playlistsScreen();
    if (tab === "favs") return favsScreen();
    if (tab === "live") return liveScreen();
    return vodScreen(tab);
  }

  /* ---------- ativação / licença (igual ao app Android) ---------- */
  function licenseText(l) {
    if (!l) return "Verificando licença...";
    var exp = l.expiresAt ? new Date(l.expiresAt).toLocaleDateString("pt-BR") : null;
    if (l.trialDenied) return "Este aparelho já usou o teste grátis. Ative um plano no site.";
    if (!l.active) return "Licença vencida. Renove no site para continuar.";
    if (l.type === "trial") return "Teste grátis ativo" + (exp ? " até " + exp : "");
    return "Plano ativo" + (exp ? " até " + exp : " (vitalício)");
  }
  function activationScreen() {
    menu(false);
    var idt = Api.identity(), l = S.license, ok = l && l.active;
    screen('<h1>Bem-vindo ao Hugo Player</h1>' +
      '<div class="card"><div class="label">Device ID</div><div class="big">' + esc(idt.deviceId) + '</div>' +
      '<div class="label" style="margin-top:20px">Device Key</div><div class="big">' + esc(idt.deviceKey) + "</div></div>" +
      '<div class="card"><div style="font-size:30px">' + esc(licenseText(l)) + '</div><div class="muted" style="font-size:24px;margin-top:12px">Para ativar um plano ou adicionar playlists pelo celular, acesse <b>' + esc(HUGO.SITE.replace("https://", "")) + "</b> com o Device ID e a Key acima.</div></div>" +
      (ok ? '<div class="btn primary focusable" id="cont">Continuar</div>' : "") + '<div class="btn focusable" id="recheck">Verificar de novo</div>');
    if ($("cont")) $("cont")._enter = function () { localStorage.setItem("hugo.activated", "1"); start(); };
    $("recheck")._enter = boot;
    Nav.first($("screen"));
  }

  /* ---------- playlists ---------- */
  function playlistsScreen() {
    S.tab = "playlists"; menu(true);
    screen("<h1>Playlists</h1><div class='muted' style='font-size:26px'>Carregando...</div>");
    Api.playlists().then(function (list) {
      S.playlists = list;
      screen("<h1>Playlists</h1>" + list.map(function (p, i) {
        return '<div class="card row"><div style="flex:1"><div style="font-size:32px">' + esc(p.name) + '</div><div class="muted" style="font-size:22px">' + (p.type === "xtream" ? "Xtream" : "M3U") + (p.protected ? " · Protegida por PIN" : "") + (S.playlist && S.playlist.id === p.id ? " · em uso" : "") +
          '</div></div><div class="btn primary focusable" data-open="' + i + '">Abrir</div><div class="btn focusable" data-del="' + i + '">Excluir</div></div>';
      }).join("") + (list.length ? "" : '<div class="muted" style="font-size:26px;margin-bottom:20px">Nenhuma playlist ainda.</div>') +
        '<div class="btn primary focusable" id="add">+ Adicionar playlist</div>');
      on("[data-open]", function () { openPlaylist(S.playlists[+this.dataset.open]); });
      on("[data-del]", function () { var p = S.playlists[+this.dataset.del]; if (confirmTwice(this)) Api.deletePlaylist(p.id).then(playlistsScreen).catch(function (e) { toast(e.message); }); });
      $("add")._enter = addScreen;
      Nav.first($("screen"));
    }).catch(function (e) { screen("<h1>Playlists</h1><div class='card'>Erro: " + esc(e.message) + "</div>"); });
  }
  function confirmTwice(el) { if (el.dataset.sure) return true; el.dataset.sure = "1"; el.textContent = "Confirmar?"; return false; }
  function addScreen() {
    screen('<h1>Adicionar playlist</h1>' +
      '<div class="label">Nome</div><input class="field focusable" id="f_name" value="Minha lista">' +
      '<div class="label">Tipo</div><div><div class="btn focusable" id="t_xtream">Xtream (usuário e senha)</div><div class="btn focusable" id="t_m3u">Link M3U</div></div>' +
      '<div id="xt"><div class="label">Servidor (http://...)</div><input class="field focusable" id="f_url">' +
      '<div class="label">Usuário</div><input class="field focusable" id="f_user"><div class="label">Senha</div><input class="field focusable" id="f_pass" type="password"></div>' +
      '<div class="btn primary focusable" id="save">Salvar</div>');
    var type = "xtream";
    function setType(t) { type = t; $("t_xtream").classList.toggle("primary", t === "xtream"); $("t_m3u").classList.toggle("primary", t === "m3u"); $("f_user").parentNode && ["f_user", "f_pass"].forEach(function (id) { $(id).style.display = t === "xtream" ? "" : "none"; $(id).previousElementSibling.style.display = t === "xtream" ? "" : "none"; }); }
    $("t_xtream")._enter = function () { setType("xtream"); }; $("t_m3u")._enter = function () { setType("m3u"); };
    setType("xtream");
    $("save")._enter = function () {
      var body = { name: $("f_name").value.trim() || "Minha lista", type: type, url: $("f_url").value.trim() };
      if (type === "xtream") { body.username = $("f_user").value.trim(); body.password = $("f_pass").value.trim(); }
      Api.addPlaylist(body).then(function () { toast("Playlist adicionada"); playlistsScreen(); }).catch(function (e) { toast("Erro: " + e.message); });
    };
    Nav.first($("screen"));
  }
  function openPlaylist(p) {
    if (p.protected) return toast("Playlist protegida por PIN: abra pelo app Android ou pelo site por enquanto.");
    screen("<h1>" + esc(p.name) + "</h1><div class='card' style='font-size:30px'>Baixando a lista... isso pode levar alguns segundos.</div>");
    Api.catalog(p.id).then(function (r) {
      var c = { live: [], movie: [], series: [] };
      (r.channels || []).forEach(function (x) { (c[x.type] || c.live).push(x); });
      S.playlist = p; S.cat = c; localStorage.setItem("hugo.lastPlaylist", p.id);
      toast(c.live.length + " canais · " + c.movie.length + " filmes · " + c.series.length + " séries");
      go("live");
    }).catch(function (e) {
      screen("<h1>" + esc(p.name) + "</h1><div class='card' style='font-size:28px'>Não foi possível carregar: " + esc(e.message) + '</div><div class="btn focusable" id="retry">Tentar de novo</div>');
      $("retry")._enter = function () { openPlaylist(p); }; Nav.first($("screen"));
    });
  }

  /* ---------- listas com grupos ---------- */
  function groupsOf(items) { var m = {}, o = []; items.forEach(function (x) { if (!m[x.group]) { m[x.group] = 0; o.push(x.group); } m[x.group]++; }); return o.map(function (g) { return [g, m[g]]; }); }
  var PAGE = 120;
  function twoPane(title, items, renderList) {
    var groups = [["__all", items.length]].concat(groupsOf(items));
    screen("<h1>" + title + '</h1><div class="cols"><div class="groups" data-scroll>' + groups.map(function (g, i) {
      return '<div class="g focusable" data-g="' + i + '">' + esc(g[0] === "__all" ? "Todos" : g[0]) + '<span class="n">' + g[1] + "</span></div>";
    }).join("") + '</div><div class="list" id="list" data-scroll></div></div>');
    function show(i) { Array.prototype.forEach.call($("screen").querySelectorAll(".g"), function (x) { x.classList.toggle("active", +x.dataset.g === i); }); var g = groups[i][0]; S.group = g; renderList(g === "__all" ? items : items.filter(function (x) { return x.group === g; }), 0); }
    Array.prototype.forEach.call($("screen").querySelectorAll(".g"), function (el) { el._onfocus = function () { show(+el.dataset.g); }; el._enter = function () { Nav.first($("list")); }; });
    show(0); Nav.first($("screen").querySelector(".groups"));
  }
  function moreButton(box, list, from, render) {
    if (from + PAGE >= list.length) return;
    var b = document.createElement("div"); b.className = "btn focusable"; b.textContent = "Mostrar mais (" + (list.length - from - PAGE) + ")";
    b._enter = function () { b.remove(); render(list, from + PAGE, true); Nav.focus(box.querySelectorAll(".focusable")[from + PAGE] || box.lastChild); };
    box.appendChild(b);
  }
  function liveScreen() {
    var items = S.cat.live;
    twoPane("Ao vivo", items, function render(list, from, append) {
      var box = $("list"); if (!append) { box.innerHTML = ""; box.scrollTop = 0; }
      list.slice(from, from + PAGE).forEach(function (c, i) {
        var d = document.createElement("div"); d.className = "ch focusable";
        d.innerHTML = '<span class="num">' + (from + i + 1) + "</span>" + (c.logo ? '<img src="' + esc(c.logo) + '" onerror="this.style.visibility=\'hidden\'">' : "<img>") + "<span>" + esc(c.name) + '</span><span class="fav">' + (S.favs[favKey(c)] ? "★" : "") + "</span>";
        d._enter = function () { Player.open(list, from + i, restoreFocus(d)); };
        d._long = function () { toggleFav(c, d); };
        box.appendChild(d);
      });
      moreButton(box, list, from, render);
    });
  }
  function vodScreen(type) {
    var items = S.cat[type];
    twoPane(type === "movie" ? "Filmes" : "Séries", items, function render(list, from, append) {
      var box = $("list"); if (!append) { box.innerHTML = ""; box.scrollTop = 0; box.className = "list grid"; }
      list.slice(from, from + PAGE).forEach(function (c, i) {
        var d = document.createElement("div"); d.className = "poster focusable";
        d.innerHTML = '<img src="' + esc(c.logo || "") + '" onerror="this.style.visibility=\'hidden\'"><div class="t">' + (S.favs[favKey(c)] ? "★ " : "") + esc(c.name) + "</div>";
        d._enter = function () { if (c.type === "series") seriesScreen(c); else Player.open(list, from + i, restoreFocus(d)); };
        d._long = function () { toggleFav(c, d); };
        box.appendChild(d);
      });
      moreButton(box, list, from, render);
    });
  }
  function seriesScreen(s) {
    S.back.push(function () { go("series"); });
    screen("<h1>" + esc(s.name) + "</h1><div class='muted' style='font-size:26px'>Carregando episódios...</div>");
    if (!s.seriesId) { // M3U: episódios já vêm na lista
      return showEpisodes(s, S.cat.series.filter(function (x) { return x.seriesName === s.seriesName; }));
    }
    Api.episodes(S.playlist.id, s.seriesId).then(function (r) { showEpisodes(s, (r.episodes || []).map(function (e) { e.type = "episode"; return e; })); })
      .catch(function (e) { toast("Erro: " + e.message); });
  }
  function showEpisodes(s, eps) {
    screen("<h1>" + esc(s.name) + '</h1><div class="list" data-scroll style="height:900px" id="eps">' + eps.map(function (e, i) {
      return '<div class="ch focusable" data-i="' + i + '"><span class="num">' + (e.season ? "T" + e.season + " E" + e.episode : i + 1) + "</span><span>" + esc(e.name) + "</span></div>";
    }).join("") + "</div>");
    on("[data-i]", function () { Player.open(eps, +this.dataset.i, restoreFocus(this)); });
    Nav.first($("eps"));
  }
  function favsScreen() {
    var all = S.cat.live.concat(S.cat.movie, S.cat.series).filter(function (c) { return S.favs[favKey(c)]; });
    screen('<h1>Favoritos</h1><div class="muted" style="font-size:24px;margin-bottom:18px">Segure OK (ou aperte o botão vermelho) num item para favoritar.</div><div class="list" data-scroll id="list" style="height:820px">' + (all.length ? "" : "<div class='card' style='font-size:28px'>Nenhum favorito ainda.</div>") + "</div>");
    all.forEach(function (c, i) {
      var d = document.createElement("div"); d.className = "ch focusable";
      d.innerHTML = "<span class='num'>" + ({ live: "TV", movie: "Filme", series: "Série" }[c.type]) + "</span><span>" + esc(c.name) + "</span>";
      d._enter = function () { if (c.type === "series") seriesScreen(c); else Player.open(all, i, restoreFocus(d)); };
      d._long = function () { toggleFav(c, d); favsScreen(); };
      $("list").appendChild(d);
    });
    Nav.first($("screen"));
  }
  function toggleFav(c, d) {
    var k = favKey(c); if (S.favs[k]) delete S.favs[k]; else S.favs[k] = 1; saveFavs();
    toast(S.favs[k] ? "Adicionado aos favoritos" : "Removido dos favoritos");
    var f = d.querySelector(".fav"); if (f) f.textContent = S.favs[k] ? "★" : "";
  }
  function restoreFocus(el) { return function () { if (document.body.contains(el)) Nav.focus(el); }; }

  /* ---------- configurações ---------- */
  function settingsScreen() {
    var idt = Api.identity();
    screen('<h1>Configurações</h1><div class="card"><div class="label">Device ID</div><div class="big" style="font-size:40px">' + esc(idt.deviceId) + '</div><div class="label" style="margin-top:14px">Device Key</div><div class="big" style="font-size:40px">' + esc(idt.deviceKey) + "</div></div>" +
      '<div class="card" style="font-size:28px">' + esc(licenseText(S.license)) + '<div class="muted" style="font-size:22px;margin-top:10px">Plataforma: ' + esc(Api.platform()) + " · versão " + esc(HUGO.VERSION) + "</div></div>" +
      '<div class="btn focusable" id="lic">Atualizar licença</div><div class="btn focusable" id="clr">Limpar favoritos</div>');
    $("lic")._enter = function () { Api.status().then(function (s) { S.license = s.license; settingsScreen(); toast("Licença atualizada"); }); };
    $("clr")._enter = function () { if (confirmTwice(this)) { S.favs = {}; saveFavs(); toast("Favoritos apagados"); } };
    Nav.first($("screen"));
  }

  /* ---------- teclas ---------- */
  var downAt = 0, longFired = false;
  document.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (Player.isOpen()) { e.preventDefault(); Player.key(k); return; }
    var cur = Nav.current();
    if (cur && cur.tagName === "INPUT" && document.activeElement === cur && (k === Keys.LEFT || k === Keys.RIGHT || k === 8)) return; // editando texto
    if (k === Keys.ENTER) {
      e.preventDefault();
      if (!downAt) { downAt = Date.now(); longFired = false; }
      if (!longFired && Date.now() - downAt > 700 && cur && cur._long) { longFired = true; cur._long(); }
      return;
    }
    if (k === 403 && cur && cur._long) { cur._long(); return; } // botão vermelho
    if (Keys.BACK.indexOf(k) >= 0) { e.preventDefault(); return back(); }
    var d = { 37: "L", 38: "U", 39: "R", 40: "D" }[k];
    if (d) { e.preventDefault(); Nav.move(d); }
  });
  document.addEventListener("keyup", function (e) {
    if (e.keyCode !== Keys.ENTER || Player.isOpen()) return;
    var cur = Nav.current(), held = downAt ? Date.now() - downAt : 0; downAt = 0;
    if (longFired) return;
    if (held > 700 && cur && cur._long) { cur._long(); return; }
    if (cur && cur.tagName === "INPUT") { cur.focus(); return; }
    if (cur && cur._enter) cur._enter.call(cur);
  });
  function back() {
    if (S.back.length) return S.back.pop()();
    if (S.tab !== "live" && S.cat) return go("live");
    try { if (window.tizen) tizen.application.getCurrentApplication().exit(); else if (window.webOS && webOS.platformBack) webOS.platformBack(); } catch (e) {}
  }

  /* ---------- início ---------- */
  function start() {
    var last = localStorage.getItem("hugo.lastPlaylist");
    Api.playlists().then(function (list) {
      S.playlists = list;
      var p = list.filter(function (x) { return x.id === last; })[0] || (list.length === 1 ? list[0] : null);
      if (p && !p.protected) { menu(true); openPlaylist(p); } else playlistsScreen();
    }).catch(function () { playlistsScreen(); });
  }
  function boot() {
    menu(false); screen("<h1>Hugo Player</h1><div class='muted' style='font-size:28px'>Conectando...</div>");
    Api.register().catch(function () {}).then(Api.status).then(function (s) {
      S.license = s.license;
      if (s.license && s.license.active && localStorage.getItem("hugo.activated") === "1") start(); else activationScreen();
    }).catch(function (e) { S.license = null; activationScreen(); toast("Sem conexão com o servidor: " + e.message); });
  }
  window.HugoApp = { go: go, S: S };
  registerTvKeys(); Player.init(); boot();
})();
