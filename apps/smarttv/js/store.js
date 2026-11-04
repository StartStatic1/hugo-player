/*
 * Catálogo no aparelho (IndexedDB), pensado para listas de 400 mil+ itens em TV fraca:
 *  - baixa por tipo e em páginas (nunca um JSON gigante na memória);
 *  - cada item guarda a posição no tipo (ai) e no grupo (gi) -> a grade virtual lê só a "janela" visível por faixa de índice;
 *  - atualização grava numa geração nova e só troca o ponteiro no fim (a lista antiga nunca some no meio);
 *  - a geração antiga é apagada depois, aos poucos.
 */
var Store = (function () {
  var DB = null, NAME = "hugo-catalog", VER = 1, PAGE = 5000;
  function open() {
    if (DB) return Promise.resolve(DB);
    return new Promise(function (ok, bad) {
      var r = indexedDB.open(NAME, VER);
      r.onupgradeneeded = function () {
        var d = r.result;
        var it = d.createObjectStore("items", { autoIncrement: true });
        it.createIndex("all", ["k", "t", "ai"]);
        it.createIndex("grp", ["k", "t", "g", "gi"]);
        it.createIndex("ser", ["k", "sn"]);
        d.createObjectStore("meta");
      };
      r.onsuccess = function () { DB = r.result; ok(DB); };
      r.onerror = function () { bad(r.error); };
    });
  }
  function tx(store, mode) { return DB.transaction(store, mode).objectStore(store); }
  function req(r) { return new Promise(function (ok, bad) { r.onsuccess = function () { ok(r.result); }; r.onerror = function () { bad(r.error); }; }); }
  function getMeta(pid) { return open().then(function () { return req(tx("meta", "readonly").get(pid)); }); }
  function putMeta(pid, m) { return req(tx("meta", "readwrite").put(m, pid)); }

  function putBatch(rows) {
    return new Promise(function (ok, bad) {
      var t = DB.transaction("items", "readwrite"), s = t.objectStore("items");
      for (var i = 0; i < rows.length; i++) s.add(rows[i]);
      t.oncomplete = ok; t.onerror = function () { bad(t.error); }; t.onabort = function () { bad(t.error || new Error("Sem espaço para guardar a lista")); };
    });
  }
  function deleteKey(k) {
    // apaga em lotes pequenos (não trava a TV)
    return open().then(function () {
      return new Promise(function (ok) {
        function step() {
          var t = DB.transaction("items", "readwrite"), idx = t.objectStore("items").index("all"), n = 0;
          var c = idx.openCursor(IDBKeyRange.bound([k, "", 0], [k, "\uffff", Infinity]));
          c.onsuccess = function () { var cur = c.result; if (cur && n < 3000) { cur["delete"](); n++; cur["continue"](); } };
          t.oncomplete = function () { if (n >= 3000) setTimeout(step, 30); else ok(); };
          t.onerror = function () { ok(); };
        }
        step();
      });
    });
  }

  /** Baixa e guarda a lista. onProgress(texto, fração) */
  function sync(pid, onProgress) {
    var k = pid + "#" + Date.now(), meta = { k: k, syncedAt: Date.now(), counts: {}, groups: {} }, prev;
    return open().then(function () { return getMeta(pid); }).then(function (m) {
      prev = m;
      var types = ["live", "movie", "series"], done = 0, grandTotal = 0;
      function type(ti) {
        if (ti >= types.length) return Promise.resolve();
        var t = types[ti], gcount = {}, gorder = [], ai = 0, seenSeries = {};
        function page(p) {
          return Api.catalogPage(pid, t, p, PAGE).then(function (r) {
            if (p === 0) grandTotal = r.total || 0;
            var rows = [];
            (r.channels || []).forEach(function (c) {
              var g = c.group || "Outros";
              // M3U: episódios soltos viram 1 card por série + episódios ("ep") achados pelo nome da série
              if (t === "series" && !c.seriesId && c.seriesName) {
                rows.push({ k: k, t: "ep", g: g, gi: 0, ai: 0, n: c.name || "", u: c.url, l: c.logo || "", nl: "", sn: c.seriesName, se: c.season, ep: c.episode });
                if (seenSeries[c.seriesName]) return;
                seenSeries[c.seriesName] = 1;
                c = { name: c.seriesName, url: "series:" + c.seriesName, logo: c.logo, seriesName: c.seriesName, group: g };
              }
              if (gcount[g] === undefined) { gcount[g] = 0; gorder.push(g); }
              var row = { k: k, t: t, g: g, gi: gcount[g]++, ai: ai++, n: c.name || "", u: c.url, l: c.logo || "", nl: (c.name || "").toLowerCase() };
              if (c.epgId) row.e = c.epgId;
              if (c.seriesId) row.s = c.seriesId;
              if (c.seriesName) row.sn = c.seriesName;
              if (c.season && t !== "series") { row.se = c.season; row.ep = c.episode; }
              rows.push(row);
            });
            done += rows.length;
            return putBatch(rows).then(function () {
              var label = { live: "Canais", movie: "Filmes", series: "Séries" }[t];
              onProgress && onProgress(label + ": " + ai.toLocaleString("pt-BR") + (grandTotal ? " de " + grandTotal.toLocaleString("pt-BR") : ""), (ti + (grandTotal ? Math.min(1, ai / grandTotal) : 0)) / 3);
              if (r.hasMore) return page(p + 1);
            });
          });
        }
        return page(0).then(function () {
          meta.counts[t] = ai;
          meta.groups[t] = gorder.map(function (g) { return [g, gcount[g]]; });
          return type(ti + 1);
        });
      }
      return type(0);
    }).then(function () {
      return putMeta(pid, meta); // troca instantânea
    }).then(function () {
      if (prev && prev.k) setTimeout(function () { deleteKey(prev.k); }, 2000);
      return meta;
    })["catch"](function (e) {
      setTimeout(function () { deleteKey(k); }, 2000);
      throw e;
    });
  }

  /** Janela [from, from+n) de um tipo (grupo null = todos). */
  function range(meta, t, g, from, n) {
    return open().then(function () {
      return new Promise(function (ok) {
        var out = [], s = tx("items", "readonly");
        var kr = g == null ? IDBKeyRange.bound([meta.k, t, from], [meta.k, t, from + n - 1])
          : IDBKeyRange.bound([meta.k, t, g, from], [meta.k, t, g, from + n - 1]);
        var c = s.index(g == null ? "all" : "grp").openCursor(kr);
        c.onsuccess = function () { var cur = c.result; if (cur) { out.push(cur.value); cur["continue"](); } else ok(out); };
        c.onerror = function () { ok(out); };
      });
    });
  }
  function episodes(meta, seriesName) {
    return open().then(function () {
      return new Promise(function (ok) {
        var out = [], c = tx("items", "readonly").index("ser").openCursor(IDBKeyRange.only([meta.k, seriesName]));
        c.onsuccess = function () { var cur = c.result; if (cur) { out.push(cur.value); cur["continue"](); } else ok(out); };
      });
    });
  }
  /** Busca por nome (para no limite; percorre o índice sem carregar tudo). */
  function search(meta, q, limit) {
    q = q.toLowerCase();
    var res = { live: [], movie: [], series: [] }, seen = {};
    return open().then(function () {
      return new Promise(function (ok) {
        var c = tx("items", "readonly").index("all").openCursor(IDBKeyRange.bound([meta.k, "", 0], [meta.k, "\uffff", Infinity]));
        c.onsuccess = function () {
          var cur = c.result; if (!cur) return ok(res);
          var v = cur.value, bucket = res[v.t];
          if (bucket && bucket.length < limit && v.nl.indexOf(q) >= 0) {
            var key = v.t === "series" ? "s" + (v.s || v.sn) : v.t + v.u;
            if (!seen[key]) { seen[key] = 1; bucket.push(v); }
          }
          if (res.live.length >= limit && res.movie.length >= limit && res.series.length >= limit) return ok(res);
          cur["continue"]();
        };
      });
    });
  }
  return { open: open, getMeta: getMeta, sync: sync, range: range, episodes: episodes, search: search, deleteKey: deleteKey,
    clear: function () { return open().then(function () { return Promise.all([req(tx("items", "readwrite").clear()), req(tx("meta", "readwrite").clear())]); }); } };
})();

/* Continuar assistindo + favoritos (localStorage, pequeno) */
var Progress = (function () {
  var KEY = "hugo.progress";
  function all() { try { return JSON.parse(localStorage.getItem(KEY) || "{}"); } catch (e) { return {}; } }
  function id(pid, url) { var h = 0; for (var i = 0; i < url.length; i++) h = (h * 31 + url.charCodeAt(i)) | 0; return pid + ":" + h; }
  return {
    get: function (pid, url) { return all()[id(pid, url)] || null; },
    save: function (pid, item, pos, dur) {
      var a = all(), k = id(pid, item.u);
      if (dur > 0 && pos / dur > 0.95) { delete a[k]; } else if (pos > 20) a[k] = { pid: pid, t: item.t, n: item.n, u: item.u, l: item.l, sn: item.sn, se: item.se, ep: item.ep, pos: pos, dur: dur, ts: Date.now() };
      var keys = Object.keys(a).sort(function (x, y) { return a[y].ts - a[x].ts; });
      keys.slice(60).forEach(function (x) { delete a[x]; });
      localStorage.setItem(KEY, JSON.stringify(a));
    },
    list: function (pid) { var a = all(); return Object.keys(a).map(function (k) { return a[k]; }).filter(function (x) { return x.pid === pid; }).sort(function (x, y) { return y.ts - x.ts; }); }
  };
})();
var Favs = (function () {
  var KEY = "hugo.favs2";
  function all() { try { return JSON.parse(localStorage.getItem(KEY) || "{}"); } catch (e) { return {}; } }
  function id(pid, it) { return pid + "|" + it.t + "|" + (it.t === "series" ? (it.s || it.sn || it.n) : it.u); }
  return {
    has: function (pid, it) { return !!all()[id(pid, it)]; },
    toggle: function (pid, it) { var a = all(), k = id(pid, it); if (a[k]) delete a[k]; else a[k] = { pid: pid, t: it.t, n: it.n, u: it.u, l: it.l, s: it.s, sn: it.sn, e: it.e, g: it.g, ts: Date.now() }; localStorage.setItem(KEY, JSON.stringify(a)); return !!a[k]; },
    list: function (pid) { var a = all(); return Object.keys(a).map(function (k) { return a[k]; }).filter(function (x) { return x.pid === pid; }).sort(function (x, y) { return y.ts - x.ts; }); },
    clear: function () { localStorage.removeItem(KEY); }
  };
})();
