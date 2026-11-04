/* Hugo Player — Smart TV (Samsung Tizen / LG webOS) */
(function () {
  var h = UI.h, esc = UI.esc, img = UI.img;
  var S = { license: null, accounts: [], pl: null, meta: null, tab: "home", back: [] };
  var $ = function (id) { return document.getElementById(id); };
  var NF = function (n) { return (n || 0).toLocaleString("pt-BR"); };
  function toast(t) { var e = $("toast"); e.textContent = t; e.classList.add("on"); clearTimeout(toast.t); toast.t = setTimeout(function () { e.classList.remove("on"); }, 3200); }
  function hm(iso) { var d = new Date(iso); return ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2); }
  function view(html) { var v = $("view"); v.innerHTML = html || ""; v.className = "view"; void v.offsetWidth; v.className = "view in"; return v; }

  /* ---------- EPG (agora/a seguir em lote, com cache) ---------- */
  var Epg = (function () {
    var nn = {}, wait = {}, queue = [], timer = null, off = {};
    function key(it) { return it.e || it.n; }
    function flush() {
      timer = null; var pid = S.pl && S.pl.id; if (!pid || off[pid]) { queue = []; return; }
      var ids = queue.splice(0, 200);
      Api.nowNext(pid, ids).then(function (r) {
        if (r && r.available === false) off[pid] = 1;
        ids.forEach(function (id) { var v = r && r.channels && r.channels[id]; nn[pid + "|" + id] = { v: v || null, at: Date.now() }; (wait[id] || []).forEach(function (f) { f(v || null); }); delete wait[id]; });
      })["catch"](function () { ids.forEach(function (id) { (wait[id] || []).forEach(function (f) { f(null); }); delete wait[id]; }); });
      if (queue.length) timer = setTimeout(flush, 50);
    }
    return {
      nowNext: function (it) {
        var pid = S.pl.id, k = key(it), c = nn[pid + "|" + k];
        if (c && Date.now() - c.at < 5 * 60000 && (!c.v || !c.v.now || new Date(c.v.now.stop).getTime() > Date.now())) return Promise.resolve(c.v);
        return new Promise(function (ok) { (wait[k] = wait[k] || []).push(ok); if (queue.indexOf(k) < 0) queue.push(k); if (!timer) timer = setTimeout(flush, 120); });
      },
      day: function (it) {
        var self = this;
        return self.nowNext(it).then(function (v) { if (!v || !v.channelId) return []; return Api.epgChannel(S.pl.id, v.channelId, 12).then(function (r) { return r.programmes || []; }); })["catch"](function () { return []; });
      }
    };
  })();

  /* ---------- shell: menu de cima ---------- */
  var TABS = [["home", "Início"], ["live", "Ao vivo"], ["movie", "Filmes"], ["series", "Séries"], ["guide", "Guia"], ["search", "Buscar"], ["favs", "Favoritos"], ["settings", "Ajustes"]];
  function shell(on) {
    $("top").classList.toggle("hidden", !on);
    if (!on) return;
    var nav = $("nav"); nav.innerHTML = "";
    var items = TABS.map(function (t) { var el = h("div", "tab" + (S.tab === t[0] ? " sel" : ""), t[1]); nav.appendChild(el); return { el: el, onEnter: function () { go(t[0]); } }; });
    var z = UI.Flat(nav).set(items); z.index(Math.max(0, TABS.map(function (t) { return t[0]; }).indexOf(S.tab)));
    UI.add("nav", z, { D: "main" });
    $("plName").textContent = S.pl ? S.pl.name : "";
  }
  function go(tab, keepFocus) {
    S.tab = tab; S.back = [];
    UI.reset(); shell(true);
    ({ home: homeScreen, live: liveScreen, movie: function () { vodScreen("movie"); }, series: function () { vodScreen("series"); }, guide: guideScreen, search: searchScreen, favs: favsScreen, settings: settingsScreen })[tab]();
    if (!UI.focus("main")) UI.focus("nav");
  }
  function main(name) { UI.link(name, UI.current()); }

  /* ---------- tocar ---------- */
  function liveNumber(i) { return i + 1; }
  function playQueue(fetchAt, count, index, startAt, back) {
    Player.open({ pid: S.pl.id, count: count, index: index, startAt: startAt, at: fetchAt, epg: function (it) { return Epg.nowNext(it); }, numberOf: liveNumber, onClose: back });
  }
  function playItems(items, i, back) { playQueue(function (n) { return Promise.resolve(items[n]); }, items.length, i, resumeAt(items[i]), back); }
  function resumeAt(it) { if (!it || it.t === "live") return 0; var p = Progress.get(S.pl.id, it.u); return p && p.pos > 20 ? p.pos - 5 : 0; }
  function fav(it, node) {
    var on = Favs.toggle(S.pl.id, it); toast(on ? "★ Adicionado aos favoritos" : "Removido dos favoritos");
    if (node) node.classList.toggle("isfav", on);
  }

  /* ---------- cards ---------- */
  function poster(it) {
    return '<div class="poster' + (Favs.has(S.pl.id, it) ? " isfav" : "") + '"><div class="pimg">' + img(it.l) + '<div class="ph">' + esc((it.n || "?").slice(0, 1)) + "</div></div><div class=\"pt\">" + esc(it.n) + '</div><i class="star">★</i></div>';
  }
  function chRow(it, i) {
    return '<div class="chrow' + (Favs.has(S.pl.id, it) ? " isfav" : "") + '"><span class="num">' + (i + 1) + '</span><span class="clogo">' + img(it.l) + '</span><span class="cname">' + esc(it.n) + '</span><i class="star">★</i></div>';
  }
  function wide(p) {
    var pct = p.dur > 0 ? Math.min(100, p.pos / p.dur * 100) : 0;
    return '<div class="wide"><div class="wimg">' + img(p.l) + '<div class="ph">' + esc((p.n || "?").slice(0, 1)) + '</div></div><div class="wbar"><i style="width:' + pct + '%"></i></div><div class="pt">' + esc(p.n) + (p.se ? " · T" + p.se + "E" + p.ep : "") + "</div></div>";
  }

  /* ---------- Início: destaque + fileiras ---------- */
  function homeScreen() {
    var v = view('<div class="hero" id="hero"><div class="hbg" id="hbg"></div><div class="hgrad"></div><div class="hinfo"><div class="hkicker" id="hk">Em destaque</div><div class="htitle" id="ht"></div><div class="hdesc" id="hd"></div><div class="hbtns" id="hb"></div></div></div><div class="rows" id="rows"></div>');
    var rowsEl = $("rows"), rowNames = [], meta = S.meta;
    function setHero(it, kicker) {
      if (!it) return;
      $("hk").textContent = kicker || "Em destaque"; $("ht").textContent = it.n; $("hd").textContent = it._ov || "";
      $("hbg").style.backgroundImage = it._bd ? 'url("' + it._bd + '")' : (it.l ? 'url("' + it.l + '")' : "");
      $("hbg").classList.toggle("poster", !it._bd);
      if (!it._bdTried && it.t !== "live") {
        it._bdTried = 1;
        Api.tmdb(it.t === "series" ? "tv" : "movie", cleanTitle(it.sn || it.n)).then(function (r) {
          var x = r && r.results && r.results[0]; if (!x) return;
          if (x.backdrop_path) it._bd = /^data:/.test(x.backdrop_path) ? x.backdrop_path : "https://image.tmdb.org/t/p/w1280" + x.backdrop_path;
          it._ov = x.overview || "";
          if ($("ht") && $("ht").textContent === it.n) setHero(it, kicker);
        })["catch"](function () {});
      }
    }
    var heroItem = null;
    var btns = [h("div", "btn primary", "▶ Assistir"), h("div", "btn", "★ Favoritos")];
    btns.forEach(function (b) { $("hb").appendChild(b); });
    var heroZ = UI.Flat($("hb")).set([
      { el: btns[0], onEnter: function () { if (heroItem) openItem(heroItem, [heroItem], 0, refocus("hero")); } },
      { el: btns[1], onEnter: function () { if (heroItem) fav(heroItem); } }
    ]);
    UI.add("hero", heroZ, { U: "nav", D: function () { return rowNames[0]; } });
    UI.link("main", null); UI.add("main", heroZ, { U: "nav", D: function () { return rowNames[0]; } });

    function addRow(title, items, kind, onEnter) {
      if (!items.length) return;
      var name = "row" + rowNames.length, prev = rowNames[rowNames.length - 1] || "hero";
      var wrap = h("div", "row"), t = h("div", "rtitle", esc(title)), body = h("div", "rbody k-" + kind);
      wrap.appendChild(t); wrap.appendChild(body); rowsEl.appendChild(wrap);
      var cw = kind === "wide" ? 420 : kind === "chan" ? 300 : 232;
      var z = UI.HRow(body, items, { cw: cw, cls: "c-" + kind, render: kind === "wide" ? wide : kind === "chan" ? function (it) { return '<div class="chcard">' + img(it.l) + '<div class="pt">' + esc(it.n) + "</div></div>"; } : poster,
        onFocus: function (it) { wrapFocus(wrap); if (kind !== "chan") { heroItem = it.u ? it : heroItem; setHero(it, title); } },
        onEnter: function (it, i) { onEnter(it, i, items); }, onLong: function (it, i, n) { fav(it, n.firstChild); } });
      UI.add(name, z, { U: prev, D: function () { return rowNames[rowNames.indexOf(name) + 1]; } });
      rowNames.push(name);
    }
    function wrapFocus(w) { var y = w.offsetTop - 0; rowsEl.style.transform = "translateY(" + (-Math.max(0, y - 10)) + "px)"; $("hero").classList.toggle("dim", y > 0); }
    heroZ.focusIn = (function (f) { return function () { rowsEl.style.transform = ""; $("hero").classList.remove("dim"); f(); }; })(heroZ.focusIn);

    var cont = Progress.list(S.pl.id).slice(0, 20).map(function (p) { return { t: p.t, n: p.n, u: p.u, l: p.l, sn: p.sn, se: p.se, ep: p.ep, pos: p.pos, dur: p.dur }; });
    Promise.all([Store.range(meta, "movie", null, 0, 20), Store.range(meta, "series", null, 0, 20), Store.range(meta, "live", null, 0, 20)]).then(function (r) {
      addRow("Continuar assistindo", cont, "wide", function (it) { playQueue(function () { return Promise.resolve(it); }, 1, 0, Math.max(0, it.pos - 5), refocus("row0")); });
      var fv = Favs.list(S.pl.id).slice(0, 20);
      addRow("Filmes em destaque", r[0], "poster", function (it, i, items) { openItem(it, items, i, refocusCur()); });
      addRow("Séries para maratonar", r[1], "poster", function (it) { seriesScreen(it); });
      addRow("Canais ao vivo", r[2], "chan", function (it, i) { playQueue(function (n) { return Store.range(meta, "live", null, n, 1).then(function (a) { return a[0]; }); }, meta.counts.live, i, 0, refocusCur()); });
      addRow("Meus favoritos", fv, "poster", function (it, i, items) { openItem(it, items, i, refocusCur()); });
      heroItem = r[0][0] || r[1][0] || null; setHero(heroItem);
    });
  }
  function cleanTitle(n) { return String(n || "").replace(/\[[^\]]*\]|\([^)]*\)|\b(4K|FHD|HD|SD|UHD|DUB|LEG|DUBLADO|LEGENDADO)\b|\bS\d+E\d+.*$/gi, "").replace(/\s+\d{4}\s*$/, "").trim(); }
  function refocus(name) { return function () { UI.focus(name); }; }
  function refocusCur() { var z = UI.current(); return function () { if (z) UI.focus(z.name); }; }
  function openItem(it, items, i, back) {
    if (it.t === "series") return seriesScreen(it);
    if (it.t === "live") return playItems(items, i, back);
    playItems(items, i, back);
  }

  /* ---------- Ao vivo: grupos | canais | prévia com EPG ---------- */
  function groupsZone(el, t, onPick) {
    var gs = [["__all", S.meta.counts[t] || 0]].concat(S.meta.groups[t] || []);
    var z = UI.VGrid(el, { count: gs.length, cols: 1, cw: 380, ch: 66, cls: "gcell",
      fetch: function (f, n) { return Promise.resolve(gs.slice(f, f + n)); },
      render: function (g) { return '<div class="grp"><span>' + esc(g[0] === "__all" ? "Todos" : g[0]) + '</span><b>' + NF(g[1]) + "</b></div>"; },
      onFocus: function (g) { clearTimeout(z._t); z._t = setTimeout(function () { onPick(g[0] === "__all" ? null : g[0], g[1]); }, 220); },
      onEnter: function () { UI.focus("items"); } });
    return z;
  }
  function liveScreen() {
    view('<div class="cols3"><div class="gcol" id="g"></div><div class="lcol" id="l"></div><div class="pcol" id="p"><div class="pv"><div class="pvlogo" id="pvl"></div><div class="pvname" id="pvn"></div><div class="pvgrp" id="pvg"></div><div class="pvnow" id="pvnow"></div><div class="pvlist" id="pvlist"></div></div></div></div>');
    var meta = S.meta, group = null, count = meta.counts.live, items = null;
    function pick(g, n) {
      group = g; count = n;
      if (items) items.destroy();
      var el = $("l");
      items = UI.VGrid(el, { count: n, cols: 1, cw: 700, ch: 92, cls: "lcell",
        fetch: function (f, k) { return Store.range(meta, "live", group, f, k); }, render: chRow,
        onFocus: preview, onEnter: function (it, i) { var g0 = group; playQueue(function (x) { return Store.range(meta, "live", g0, x, 1).then(function (a) { return a[0]; }); }, n, i, 0, refocus("items")); },
        onLong: function (it, i) { fav(it); items.refresh(i); } });
      UI.add("items", items, { L: "main", U: "nav" });
    }
    var pvT;
    function preview(it) {
      $("pvl").innerHTML = img(it.l); $("pvn").textContent = it.n; $("pvg").textContent = it.g; $("pvnow").innerHTML = '<span class="muted">Carregando guia...</span>'; $("pvlist").innerHTML = "";
      clearTimeout(pvT); pvT = setTimeout(function () {
        Epg.nowNext(it).then(function (nn) {
          if ($("pvn").textContent !== it.n) return;
          if (!nn || !nn.now) { $("pvnow").innerHTML = '<span class="muted">Sem guia para este canal</span>'; return; }
          var s = new Date(nn.now.start).getTime(), e = new Date(nn.now.stop).getTime(), pct = Math.max(0, Math.min(100, (Date.now() - s) / (e - s) * 100));
          $("pvnow").innerHTML = '<div class="now"><span class="badge">AGORA</span> ' + hm(nn.now.start) + " – " + hm(nn.now.stop) + "</div><div class=\"nowt\">" + esc(nn.now.title) + '</div><div class="prog"><i style="width:' + pct + '%"></i></div>' + (nn.next ? '<div class="next"><span class="badge b2">A SEGUIR</span> ' + hm(nn.next.start) + " " + esc(nn.next.title) + "</div>" : "");
          Epg.day(it).then(function (ps) { if ($("pvn").textContent !== it.n) return; $("pvlist").innerHTML = ps.slice(2, 7).map(function (p) { return "<div><b>" + hm(p.start) + "</b> " + esc(p.title) + "</div>"; }).join(""); });
        });
      }, 300);
    }
    var gz = groupsZone($("g"), "live", pick);
    UI.add("main", gz, { R: "items", U: "nav" });
    pick(null, count);
  }

  /* ---------- Filmes / Séries ---------- */
  function vodScreen(t) {
    view('<div class="cols2"><div class="gcol" id="g"></div><div class="vcol" id="v"></div></div>');
    var meta = S.meta, grid = null;
    function pick(g, n) {
      if (grid) grid.destroy();
      grid = UI.VGrid($("v"), { count: n, cols: 6, cw: 232, ch: 392, cls: "pcell", lead: 0.05,
        fetch: function (f, k) { return Store.range(meta, t, g, f, k); }, render: poster,
        onEnter: function (it, i) { if (t === "series") return seriesScreen(it); var g0 = g; playQueue(function (x) { return Store.range(meta, t, g0, x, 1).then(function (a) { return a[0]; }); }, 1, 0, resumeAt(it), refocus("items")); },
        onLong: function (it, i) { fav(it); grid.refresh(i); } });
      UI.add("items", grid, { L: "main", U: "nav" });
    }
    UI.add("main", groupsZone($("g"), t, pick), { R: "items", U: "nav" });
    pick(null, meta.counts[t] || 0);
  }
  function seriesScreen(s) {
    var backTab = S.tab, prevZone = UI.current() && UI.current().name;
    UI.reset(); shell(true);
    view('<div class="sdetail"><div class="hbg" id="sbg"></div><div class="hgrad"></div><div class="sinfo"><div class="htitle">' + esc(s.n) + '</div><div class="hdesc" id="sdesc"></div><div class="seasons" id="seasons"></div></div><div class="eps" id="eps"></div></div>');
    $("sbg").style.backgroundImage = s.l ? 'url("' + s.l + '")' : ""; $("sbg").classList.add("poster");
    Api.tmdb("tv", cleanTitle(s.sn || s.n)).then(function (r) { var x = r && r.results && r.results[0]; if (!x || !$("sbg")) return; if (x.backdrop_path) { $("sbg").style.backgroundImage = 'url("' + (/^data:/.test(x.backdrop_path) ? x.backdrop_path : "https://image.tmdb.org/t/p/w1280" + x.backdrop_path) + '")'; $("sbg").classList.remove("poster"); } if (x.overview) $("sdesc").textContent = x.overview; })["catch"](function () {});
    var p = s.s ? Api.episodes(S.pl.id, s.s).then(function (r) { if (r.plot && $("sdesc") && !$("sdesc").textContent) $("sdesc").textContent = r.plot; return (r.episodes || []).map(function (e) { return { t: "ep", n: e.name, u: e.url, l: e.logo || s.l, se: e.season || 1, ep: e.episode, sn: s.n, dur: e.durationSecs }; }); })
      : Store.episodes(S.meta, s.sn || s.n).then(function (a) { return a.filter(function (x) { return x.t === "ep"; }).map(function (x) { return { t: "ep", n: x.n, u: x.u, l: x.l || s.l, se: x.se || 1, ep: x.ep, sn: s.sn }; }); });
    S.back.push(function () { go(backTab); });
    p.then(function (eps) {
      var seasons = []; eps.forEach(function (e) { if (seasons.indexOf(e.se) < 0) seasons.push(e.se); }); seasons.sort(function (a, b) { return a - b; });
      var list = null;
      function showSeason(sn) {
        var arr = eps.filter(function (e) { return e.se === sn; });
        if (list) list.destroy();
        list = UI.VGrid($("eps"), { count: arr.length, cols: 1, cw: 900, ch: 96, cls: "ecell",
          fetch: function (f, n) { return Promise.resolve(arr.slice(f, f + n)); },
          render: function (e) { var pr = Progress.get(S.pl.id, e.u), pct = pr && pr.dur ? pr.pos / pr.dur * 100 : 0; return '<div class="ep"><span class="epn">E' + (e.ep || "") + '</span><span class="ept">' + esc(e.n) + "</span>" + (pct ? '<span class="epbar"><i style="width:' + pct + '%"></i></span>' : "") + "</div>"; },
          onEnter: function (e, i) { playQueue(function (n) { return Promise.resolve(arr[n]); }, arr.length, i, resumeAt(e), function () { list.refresh(i); UI.focus("items"); }); } });
        UI.add("items", list, { U: "main", L: "main" });
      }
      var sz = UI.Flat($("seasons")).set(seasons.map(function (sn) { var el = h("div", "btn sm", "Temporada " + sn); $("seasons").appendChild(el); return { el: el, onFocus: function () { showSeason(sn); }, onEnter: function () { UI.focus("items"); } }; }));
      UI.add("main", sz, { D: "items", U: "nav", R: "items" });
      if (!seasons.length) { $("eps").innerHTML = '<div class="empty">Nenhum episódio encontrado.</div>'; UI.focus("nav"); return; }
      showSeason(seasons[0]); UI.focus("main");
    })["catch"](function (e) { toast("Erro ao abrir a série: " + e.message); UI.focus("nav"); });
  }

  /* ---------- Guia (EPG) ---------- */
  function guideScreen() {
    var SPAN = 3 * 3600000, W = 1180, t0 = Math.floor(Date.now() / 1800000) * 1800000;
    var ticks = ""; for (var k = 0; k < 6; k++) ticks += '<span style="left:' + (k * W / 6) + 'px">' + hm(new Date(t0 + k * 1800000).toISOString()) + "</span>";
    view('<div class="cols2 guide"><div class="gcol" id="g"></div><div class="gbox"><div class="gtime"><div class="gtl">Hoje</div><div class="gticks">' + ticks + '<i class="gnow" style="left:' + ((Date.now() - t0) / SPAN * W) + 'px"></i></div></div><div class="grows" id="gr"></div></div></div>');
    var meta = S.meta, grid = null;
    function pick(g, n) {
      if (grid) grid.destroy();
      grid = UI.VGrid($("gr"), { count: n, cols: 1, cw: 1480, ch: 96, cls: "grcell",
        fetch: function (f, c) { return Store.range(meta, "live", g, f, c); },
        render: function (it, i) {
          var id = "gp" + i + "_" + Math.random().toString(36).slice(2, 6);
          setTimeout(function () { Epg.day(it).then(function (ps) { var el = document.getElementById(id); if (!el) return; el.innerHTML = ps.length ? ps.map(function (p) { var s = Math.max(t0, new Date(p.start).getTime()), e = Math.min(t0 + SPAN, new Date(p.stop).getTime()); if (e <= s) return ""; var on = Date.now() >= new Date(p.start).getTime() && Date.now() < new Date(p.stop).getTime(); return '<div class="gp' + (on ? " on" : "") + '" style="left:' + ((s - t0) / SPAN * W) + "px;width:" + Math.max(4, (e - s) / SPAN * W - 6) + 'px"><b>' + hm(p.start) + "</b> " + esc(p.title) + "</div>"; }).join("") : '<div class="gp none" style="left:0;width:' + (W - 6) + 'px">Sem informação de programação</div>'; }); }, 0);
          return '<div class="grow"><div class="gch"><span class="num">' + (i + 1) + '</span><span class="clogo">' + img(it.l) + '</span><span class="cname">' + esc(it.n) + '</span></div><div class="gprogs" id="' + id + '"></div></div>';
        },
        onEnter: function (it, i) { var g0 = g; playQueue(function (x) { return Store.range(meta, "live", g0, x, 1).then(function (a) { return a[0]; }); }, n, i, 0, refocus("items")); } });
      UI.add("items", grid, { L: "main", U: "nav" });
    }
    UI.add("main", groupsZone($("g"), "live", pick), { R: "items", U: "nav" });
    pick(null, meta.counts.live || 0);
  }

  /* ---------- Buscar (teclado na tela) ---------- */
  function searchScreen() {
    view('<div class="search"><div class="skb"><div class="sq" id="sq"><span class="ph">Digite para buscar</span></div><div class="keys" id="keys"></div></div><div class="sres" id="sres"><div class="empty">Busque canais, filmes e séries.</div></div></div>');
    var q = "", KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".split("").concat(["␣", "⌫", "Limpar"]), T;
    var kz = UI.VGrid($("keys"), { count: KEYS.length, cols: 6, cw: 96, ch: 84, cls: "kcell",
      fetch: function (f, n) { return Promise.resolve(KEYS.slice(f, f + n)); },
      render: function (k) { return '<div class="key' + (k.length > 1 ? " w" : "") + '">' + k + "</div>"; },
      onEnter: function (k) { if (k === "⌫") q = q.slice(0, -1); else if (k === "Limpar") q = ""; else if (k === "␣") q += " "; else q += k.toLowerCase(); paint(); } });
    function paint() { $("sq").innerHTML = q ? esc(q) + '<i class="caret"></i>' : '<span class="ph">Digite para buscar</span>'; clearTimeout(T); T = setTimeout(run, 450); }
    var resNames = [];
    function run() {
      var box = $("sres"); resNames.forEach(function (n) { UI.link(n, {}); }); resNames = [];
      if (q.trim().length < 2) { box.innerHTML = '<div class="empty">Busque canais, filmes e séries.</div>'; return; }
      box.innerHTML = '<div class="empty">Buscando...</div>';
      var mine = q;
      Store.search(S.meta, q.trim(), 24).then(function (r) {
        if (mine !== q) return; box.innerHTML = "";
        [["movie", "Filmes"], ["series", "Séries"], ["live", "Canais"]].forEach(function (x) {
          var items = r[x[0]]; if (!items.length) return;
          var wrap = h("div", "row"), body = h("div", "rbody k-poster"); wrap.appendChild(h("div", "rtitle", x[1] + " <b>" + items.length + "</b>")); wrap.appendChild(body); box.appendChild(wrap);
          var name = "res" + resNames.length, prev = resNames[resNames.length - 1];
          UI.add(name, UI.HRow(body, items, { cw: 232, cls: "c-poster", render: poster, onFocus: function () { box.scrollTop = wrap.offsetTop - 10; }, onEnter: function (it, i) { openItem(it, items, i, refocusCur()); }, onLong: function (it, i, n) { fav(it, n.firstChild); } }), { L: "main", U: prev || "nav", D: function () { return resNames[resNames.indexOf(name) + 1]; } });
          resNames.push(name);
        });
        if (!resNames.length) box.innerHTML = '<div class="empty">Nada encontrado para “' + esc(q) + "”.</div>";
      });
    }
    UI.add("main", kz, { R: function () { return resNames[0]; }, U: "nav" });
  }

  /* ---------- Favoritos ---------- */
  function favsScreen() {
    var list = Favs.list(S.pl.id);
    view('<div class="page"><h1>Favoritos</h1><div class="hint">Segure OK (ou o botão vermelho) em qualquer canal, filme ou série para favoritar.</div><div class="fgrid" id="fg"></div></div>');
    if (!list.length) { $("fg").innerHTML = '<div class="empty">Nenhum favorito ainda.</div>'; return; }
    var grid = UI.VGrid($("fg"), { count: list.length, cols: 7, cw: 232, ch: 392, cls: "pcell",
      fetch: function (f, n) { return Promise.resolve(list.slice(f, f + n)); }, render: poster,
      onEnter: function (it, i) { openItem(it, list, i, refocus("main")); }, onLong: function (it) { fav(it); go("favs"); } });
    UI.add("main", grid, { U: "nav" });
  }

  /* ---------- Ajustes ---------- */
  function settingsScreen() {
    var idt = Api.identity(), l = S.license, m = S.meta;
    view('<div class="page settings"><h1>Ajustes</h1><div class="sgrid"><div class="scard"><div class="lbl">Device ID</div><div class="mono">' + esc(idt.deviceId) + '</div><div class="lbl">Device Key</div><div class="mono">' + esc(idt.deviceKey) + '</div><div class="lic ' + (l && l.active ? "ok" : "bad") + '">' + esc(licenseText(l)) + '</div><div class="hint">Contas e planos são gerenciados em <b>' + esc(HUGO.SITE.replace("https://", "")) + '</b></div></div><div class="scard"><div class="lbl">Conta em uso</div><div class="big2">' + esc(S.pl.name) + '</div><div class="stats"><span><b>' + NF(m.counts.live) + "</b> canais</span><span><b>" + NF(m.counts.movie) + "</b> filmes</span><span><b>" + NF(m.counts.series) + '</b> séries</span></div><div class="hint">Atualizada em ' + new Date(m.syncedAt).toLocaleString("pt-BR") + '</div></div></div><div class="sbtns" id="sb"></div><div class="about">Hugo Player Smart TV ' + esc(HUGO.VERSION) + " · " + esc(Api.platform()) + "</div></div>");
    var acts = [["Atualizar conteúdo", function () { syncScreen(S.pl, true); }], ["Trocar conta", function () { accountsScreen(); }], ["Atualizar licença", function () { Api.status().then(function (s) { S.license = s.license; go("settings"); toast("Licença atualizada"); }); }],
      ["Limpar continuar assistindo", function () { localStorage.removeItem("hugo.progress"); toast("Continuar assistindo limpo"); }], ["Limpar favoritos", function () { Favs.clear(); toast("Favoritos apagados"); }]];
    var z = UI.Flat($("sb")).set(acts.map(function (a) { var el = h("div", "btn", a[0]); $("sb").appendChild(el); return { el: el, onEnter: a[1] }; }));
    UI.add("main", z, { U: "nav" });
  }

  /* ---------- ativação / licença ---------- */
  function licenseText(l) {
    if (!l) return "Verificando licença...";
    var exp = l.expiresAt ? new Date(l.expiresAt).toLocaleDateString("pt-BR") : null;
    if (l.trialDenied) return "Este aparelho já usou o período de teste. Ative um plano no site.";
    if (!l.active) return "Licença vencida. Renove no site para continuar.";
    if (l.type === "trial") return "Período de teste ativo" + (exp ? " até " + exp : "");
    return "Plano ativo" + (exp ? " até " + exp : " (vitalício)");
  }
  function activationScreen() {
    UI.reset(); shell(false);
    var idt = Api.identity(), l = S.license, ok = l && l.active;
    view('<div class="center"><div class="brand big">Hugo<span>Player</span></div><div class="actcard"><div class="acol"><div class="lbl">Device ID</div><div class="mono xl">' + esc(idt.deviceId) + '</div><div class="lbl">Device Key</div><div class="mono xl">' + esc(idt.deviceKey) + '</div></div><div class="acol r"><div class="lic ' + (ok ? "ok" : "bad") + '">' + esc(licenseText(l)) + '</div><div class="steps"><div><b>1</b> Acesse <u>' + esc(HUGO.SITE.replace("https://", "")) + "</u></div><div><b>2</b> Digite o Device ID e a Key</div><div><b>3</b> Entre com sua conta (servidor, usuário e senha)</div></div></div></div><div class=\"abtns\" id=\"ab\"></div></div>");
    var items = [];
    if (ok) items.push(["Continuar", function () { localStorage.setItem("hugo.activated", "1"); start(); }, true]);
    items.push(["Verificar de novo", boot]);
    var z = UI.Flat($("ab")).set(items.map(function (a) { var el = h("div", "btn" + (a[2] ? " primary" : ""), a[0]); $("ab").appendChild(el); return { el: el, onEnter: a[1] }; }));
    UI.add("main", z); UI.focus("main");
  }

  /* ---------- contas + PIN ---------- */
  function accountsScreen() {
    UI.reset(); shell(false);
    view('<div class="center"><div class="brand">Hugo<span>Player</span></div><h2 class="ctitle">Quem vai assistir?</h2><div class="hint">Contas são gerenciadas em <b>' + esc(HUGO.SITE.replace("https://", "")) + '</b></div><div class="plcards" id="pc"><div class="empty">Carregando...</div></div><div class="abtns" id="pb"></div></div>');
    Api.accounts().then(function (list) {
      S.accounts = list; $("pc").innerHTML = "";
      var cards = list.map(function (p, i) {
        var el = h("div", "plcard", '<div class="plic">' + esc((p.name || "?").slice(0, 1).toUpperCase()) + '</div><div class="pln">' + esc(p.name) + '</div><div class="plt">' + (HUGO.STORE ? "Conta" : String(p.type || "").toUpperCase()) + (p.protected ? ' · <span class="lock">🔒 PIN</span>' : "") + "</div>");
        el.style.setProperty("--c", ["#22D3EE", "#7C5CFF", "#EC4899"][i % 3]); $("pc").appendChild(el);
        return { el: el, onEnter: function () { choose(p); }, onLong: function () { if (el.dataset.sure) { Api.deleteAccount(p.id).then(accountsScreen)["catch"](function (e) { toast(e.message); }); } else { el.dataset.sure = 1; toast("Segure OK de novo para excluir “" + p.name + "”"); } } };
      });
      if (!list.length) $("pc").innerHTML = '<div class="empty">Nenhuma conta ainda. Entre abaixo com servidor, usuário e senha.</div>';
      var add = h("div", "btn primary", "+ Entrar com uma conta"); $("pb").appendChild(add);
      UI.add("main", UI.Flat($("pc")).set(cards), { D: "btns" });
      UI.add("btns", UI.Flat($("pb")).set([{ el: add, onEnter: (window.HugoExtra && !HUGO.STORE) ? HugoExtra.addScreen : loginScreen }]), { U: "main" });
      if (!UI.focus("main")) UI.focus("btns");
    })["catch"](function (e) { $("pc").innerHTML = '<div class="empty">Erro: ' + esc(e.message) + "</div>"; });
  }
  function choose(p) {
    if (!p.protected || sessionStorage.getItem("hugo.pin." + p.id)) return syncScreen(p, false);
    pinPad(p.name, function (pin, done) {
      Api.verifyPin(p.id, pin).then(function () { sessionStorage.setItem("hugo.pin." + p.id, "1"); done(true); syncScreen(p, false); })["catch"](function (e) { done(false, e.status === 429 ? "Muitas tentativas. Aguarde." : "PIN incorreto"); });
    }, accountsScreen);
  }
  function pinPad(title, check, cancel) {
    UI.reset(); shell(false);
    view('<div class="center"><div class="lockic">🔒</div><h2 class="ctitle">' + esc(title) + '</h2><div class="hint">Digite o PIN da conta</div><div class="pindots" id="pd"></div><div class="pinerr" id="pe"></div><div class="pinpad" id="pp"></div></div>');
    var pin = "", K = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"];
    function dots() { var s = ""; for (var i = 0; i < 4; i++) s += '<i class="' + (i < pin.length ? "on" : "") + '"></i>'; $("pd").innerHTML = s; }
    var z = UI.VGrid($("pp"), { count: 12, cols: 3, cw: 140, ch: 110, cls: "pcellk", fetch: function () { return Promise.resolve(K); },
      render: function (k) { return '<div class="key">' + k + "</div>"; },
      onEnter: function (k) {
        if (k === "⌫") pin = pin.slice(0, -1); else if (k === "OK") { if (pin.length >= 4) check(pin, function (ok, msg) { if (!ok) { $("pe").textContent = msg; pin = ""; dots(); } }); }
        else if (pin.length < 8) pin += k;
        $("pe").textContent = ""; dots();
        if (pin.length === 4 && k !== "OK" && k !== "⌫") check(pin, function (ok, msg) { if (!ok) { $("pe").textContent = msg; pin = ""; dots(); } });
      } });
    UI.add("main", z); UI.focus("main"); dots();
    S.back = [cancel];
  }
  function loginScreen() {
    UI.reset(); shell(false);
    view('<div class="center"><div class="brand">Hugo<span>Player</span></div><h2 class="ctitle">Entrar</h2><div class="form"><label>Servidor</label><input id="f_url" placeholder="servidor.exemplo.com:8080"><label>Usuário</label><input id="f_user"><label>Senha</label><input id="f_pass" type="password"></div><div class="abtns" id="fb"></div><div class="hint">Use os dados da conta que você já possui. Contas são gerenciadas em <b>' + esc(HUGO.SITE.replace("https://", "")) + '</b>. O Hugo Player não fornece conteúdo.</div></div>');
    var fields = ["f_url", "f_user", "f_pass"].map(function (id) { var el = $(id); return { el: el, onEnter: function () { el.focus(); } }; });
    var save = h("div", "btn primary", "Entrar"), cancel = h("div", "btn", "Voltar"); $("fb").appendChild(save); $("fb").appendChild(cancel);
    UI.add("fields", UI.Flat(null, { horizontal: false }).set(fields), { D: "fbtns" });
    UI.add("fbtns", UI.Flat($("fb")).set([{ el: save, onEnter: function () {
      var host = $("f_url").value.trim(), user = $("f_user").value.trim(), pass = $("f_pass").value.trim();
      if (!host || !user || !pass) return toast("Preencha servidor, usuário e senha");
      if (!/^https?:\/\//i.test(host)) host = "http://" + host;
      var nm = host.replace(/^https?:\/\//i, "").split(/[/:]/)[0];
      Api.addAccount({ name: nm, type: "xtream", url: host, username: user, password: pass }).then(function () { toast("Conta conectada"); accountsScreen(); })["catch"](function (e) { toast("Erro: " + e.message); });
    } }, { el: cancel, onEnter: accountsScreen }]), { U: "fields" });
    S.back = [accountsScreen];
    UI.focus("fields");
  }

  /* ---------- baixar/abrir o conteúdo ---------- */
  function syncScreen(p, force) {
    UI.reset(); shell(false);
    Store.getMeta(p.id).then(function (m) {
      var fresh = m && m.k && !force && Date.now() - m.syncedAt < 24 * 3600000;
      if (fresh) return enter(p, m);
      view('<div class="center"><div class="brand">Hugo<span>Player</span></div><h2 class="ctitle">' + esc(p.name) + '</h2><div class="sync"><div class="sbar"><i id="sbar"></i></div><div class="stext" id="st">Conectando...</div></div>' + (m && m.k ? '<div class="hint">Você pode continuar usando o conteúdo guardado enquanto atualiza.</div>' : "") + "</div>");
      Store.sync(p.id, function (t, f) { if ($("st")) $("st").textContent = t; if ($("sbar") && f != null) $("sbar").style.width = Math.round(f * 100) + "%"; })
        .then(function (meta) { toast(NF(meta.counts.live) + " canais · " + NF(meta.counts.movie) + " filmes · " + NF(meta.counts.series) + " séries"); enter(p, meta); })
        ["catch"](function (e) {
          if (m && m.k) { toast("Não deu para atualizar: " + e.message + " — usando o conteúdo guardado"); return enter(p, m); }
          view('<div class="center"><h2 class="ctitle">' + esc(p.name) + '</h2><div class="empty">Não foi possível carregar: ' + esc(e.message) + '</div><div class="abtns" id="eb"></div></div>');
          var r = h("div", "btn primary", "Tentar de novo"), b = h("div", "btn", "Voltar"); $("eb").appendChild(r); $("eb").appendChild(b);
          UI.add("main", UI.Flat($("eb")).set([{ el: r, onEnter: function () { syncScreen(p, true); } }, { el: b, onEnter: accountsScreen }])); UI.focus("main");
        });
    });
  }
  function enter(p, meta) { S.pl = p; S.meta = meta; localStorage.setItem("hugo.lastAccount", p.id); go("home"); }

  /* ---------- teclas ---------- */
  var downAt = 0, longFired = false;
  document.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (Player.isOpen()) { e.preventDefault(); Player.key(k); return; }
    var ae = document.activeElement;
    if (ae && ae.tagName === "INPUT") {
      if (k === Keys.ENTER || k === Keys.DOWN || k === Keys.UP || Keys.BACK.indexOf(k) >= 0 && k !== 8) { ae.blur(); if (k === Keys.DOWN || k === Keys.UP) UI.key(k === Keys.DOWN ? "D" : "U"); e.preventDefault(); }
      return;
    }
    if (k === Keys.ENTER) { e.preventDefault(); if (!downAt) { downAt = Date.now(); longFired = false; } if (!longFired && Date.now() - downAt > 650) { longFired = true; UI.long(); } return; }
    if (k === 403) { UI.long(); return; }
    if (k === 33 || k === 34) { var c = UI.current(); if (c && c.page) c.page(k === 33 ? -1 : 1); return; }
    if (Keys.BACK.indexOf(k) >= 0) { e.preventDefault(); return back(); }
    var d = { 37: "L", 38: "U", 39: "R", 40: "D" }[k];
    if (d) { e.preventDefault(); UI.key(d); }
  });
  document.addEventListener("keyup", function (e) {
    if (e.keyCode !== Keys.ENTER || Player.isOpen()) return;
    var held = downAt ? Date.now() - downAt : 0; downAt = 0;
    if (longFired) return;
    if (held > 650) return UI.long();
    UI.enter();
  });
  function back() {
    if (S.back.length) return S.back.pop()();
    var c = UI.current();
    if (S.pl && c && c.name !== "nav" && $("top") && !$("top").classList.contains("hidden")) return UI.focus("nav");
    if (S.pl && S.tab !== "home") return go("home");
    try { if (window.tizen) tizen.application.getCurrentApplication().exit(); else if (window.webOS && webOS.platformBack) webOS.platformBack(); } catch (e) {}
  }

  /* ---------- início ---------- */
  function start() {
    var last = localStorage.getItem("hugo.lastAccount");
    Api.accounts().then(function (list) {
      S.accounts = list;
      var p = list.filter(function (x) { return x.id === last; })[0] || (list.length === 1 ? list[0] : null);
      if (p && !p.protected) syncScreen(p, false); else accountsScreen();
    })["catch"](function () { accountsScreen(); });
  }
  function splashDone() { var s = $("splash"); if (s) { s.classList.add("out"); setTimeout(function () { s.parentNode && s.parentNode.removeChild(s); }, 600); } }
  function boot() {
    Api.register()["catch"](function () {}).then(Api.status).then(function (s) {
      S.license = s.license; splashDone();
      if (s.license && s.license.active && localStorage.getItem("hugo.activated") === "1") start(); else activationScreen();
    })["catch"](function (e) { S.license = null; splashDone(); activationScreen(); toast("Sem conexão com o servidor: " + e.message); });
  }
  window.HugoApp = { go: go, S: S, accounts: accountsScreen, choose: choose, h: h, $: $, view: view, shell: shell, toast: toast };
  registerTvKeys(); Player.init();
  Store.open()["catch"](function () {}).then(function () { setTimeout(boot, HUGO.DEMO ? 300 : 0); });
})();
