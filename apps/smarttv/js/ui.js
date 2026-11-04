/*
 * Foco por "zonas" (menu, grupos, grade...): cada zona cuida das setas por dentro e, na borda,
 * o foco passa para a zona vizinha. Listas e grades são virtuais: só existem no DOM os itens visíveis
 * (aguenta 400 mil itens em TV fraca). Movimento por transform (GPU), sem blur.
 */
var UI = (function () {
  function h(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function img(src, cls) { return src ? '<img class="' + (cls || "") + '" src="' + esc(src) + '" loading="lazy" onerror="this.style.opacity=0">' : ""; }

  /* ---------- gerenciador ---------- */
  var zones = {}, cur = null, links = {};
  function reset() { zones = {}; links = {}; cur = null; }
  function add(name, z, nb) { z.name = name; zones[name] = z; links[name] = nb || {}; return z; }
  function link(name, nb) { links[name] = nb; }
  function focus(name, dir) {
    var z = zones[name]; if (!z || (z.empty && z.empty())) return false;
    if (cur && cur !== z) cur.blur();
    cur = z; z.focusIn(dir); return true;
  }
  function key(dir) {
    if (!cur) return;
    if (cur.move(dir)) return;
    var nb = links[cur.name] && links[cur.name][dir];
    if (typeof nb === "function") nb = nb();
    var list = nb ? [].concat(nb) : [];
    for (var i = 0; i < list.length; i++) if (focus(list[i], dir)) return;
  }
  function enter() { if (cur && cur.enter) cur.enter(); }
  function long() { if (cur && cur.long) cur.long(); }
  function current() { return cur; }

  /* ---------- zona de elementos fixos (botões, menu) ---------- */
  function Flat(el, opts) {
    opts = opts || {};
    var items = [], idx = 0, horiz = opts.horizontal !== false;
    var z = {
      set: function (arr) { items = arr; if (idx >= items.length) idx = 0; return z; },
      empty: function () { return !items.length; },
      focusIn: function () { if (opts.keep === false) idx = 0; paint(); },
      blur: function () { items.forEach(function (it) { it.el.classList.remove("f"); }); },
      move: function (d) {
        var n = horiz ? (d === "R" ? 1 : d === "L" ? -1 : 0) : (d === "D" ? 1 : d === "U" ? -1 : 0);
        if (!n || idx + n < 0 || idx + n >= items.length) return false;
        idx += n; paint(); return true;
      },
      enter: function () { var it = items[idx]; it && it.onEnter && it.onEnter(it); },
      long: function () { var it = items[idx]; it && it.onLong && it.onLong(it); },
      index: function (i) { if (i != null) idx = i; return idx; }
    };
    function paint() {
      items.forEach(function (it, i) { it.el.classList.toggle("f", i === idx); });
      var it = items[idx]; if (it && it.onFocus) it.onFocus(it);
      if (opts.scroll && it) { var r = it.el.offsetTop, H = el.clientHeight; if (r + it.el.offsetHeight > el.scrollTop + H - 40) el.scrollTop = r + it.el.offsetHeight - H + 40; if (r < el.scrollTop + 20) el.scrollTop = Math.max(0, r - 20); }
    }
    return z;
  }

  /* ---------- grade/lista virtual ---------- */
  // o: { count, cols, cw, ch (tamanho da célula com espaço), fetch(from,n)->Promise<[]>, render(item,i)->html, cls, onEnter, onFocus, onLong }
  function VGrid(el, o) {
    var cols = o.cols || 1, rowsVisible = Math.ceil(el.clientHeight / o.ch) + 2, idx = 0, top = 0, cache = {}, pending = {}, nodes = {}, active = false, gen = 0;
    var inner = h("div", "vinner"); inner.style.height = Math.ceil(o.count / cols) * o.ch + "px"; el.innerHTML = ""; el.appendChild(inner);
    var BLOCK = Math.max(60, cols * 12);
    function load(i) {
      var b = Math.floor(i / BLOCK); if (pending[b]) return; pending[b] = 1; var g = gen;
      o.fetch(b * BLOCK, BLOCK).then(function (arr) { if (g !== gen) return; arr.forEach(function (it, j) { cache[b * BLOCK + j] = it; }); draw(); });
    }
    function draw() {
      var r0 = Math.max(0, Math.floor(top / o.ch) - 1), r1 = r0 + rowsVisible + 1, keep = {};
      for (var r = r0; r <= r1; r++) for (var c = 0; c < cols; c++) {
        var i = r * cols + c; if (i >= o.count) break;
        keep[i] = 1;
        var it = cache[i];
        if (it === undefined) load(i);
        var n = nodes[i];
        if (!n) { n = nodes[i] = h("div", "cell " + (o.cls || "")); n.style.transform = "translate(" + c * o.cw + "px," + r * o.ch + "px)"; inner.appendChild(n); n._filled = false; }
        if (it !== undefined && !n._filled) { n.innerHTML = o.render(it, i); n._filled = true; n.classList.remove("ghost"); } else if (it === undefined) n.classList.add("ghost");
        n.classList.toggle("f", active && i === idx);
      }
      for (var k in nodes) if (!keep[k]) { inner.removeChild(nodes[k]); delete nodes[k]; }
    }
    function scrollTo() {
      var r = Math.floor(idx / cols), y = r * o.ch, H = el.clientHeight;
      if (y < top + o.ch * 0.5) top = Math.max(0, y - o.ch * (o.lead || 0.3));
      if (y + o.ch > top + H - o.ch * 0.3) top = y + o.ch - H + o.ch * 0.6;
      top = Math.max(0, Math.min(top, Math.max(0, Math.ceil(o.count / cols) * o.ch - H + 40)));
      inner.style.transform = "translateY(" + (-top) + "px)";
    }
    function focused() { draw(); if (active && o.onFocus && cache[idx] !== undefined) o.onFocus(cache[idx], idx); }
    var z = {
      empty: function () { return !o.count; },
      focusIn: function () { active = true; scrollTo(); focused(); },
      blur: function () { active = false; draw(); },
      move: function (d) {
        var n = idx;
        if (d === "R") { if (cols === 1 || idx % cols === cols - 1 || idx + 1 >= o.count) return false; n++; }
        if (d === "L") { if (cols === 1 || idx % cols === 0) return false; n--; }
        if (d === "D") { if (idx + cols >= o.count) { if (Math.floor(idx / cols) < Math.floor((o.count - 1) / cols)) n = o.count - 1; else return false; } else n += cols; }
        if (d === "U") { if (idx - cols < 0) return false; n -= cols; }
        idx = n; scrollTo(); focused(); return true;
      },
      page: function (dir) { idx = Math.max(0, Math.min(o.count - 1, idx + dir * cols * (rowsVisible - 2))); scrollTo(); focused(); },
      enter: function () { var it = cache[idx]; if (it !== undefined && o.onEnter) o.onEnter(it, idx); },
      long: function () { var it = cache[idx]; if (it !== undefined && o.onLong) o.onLong(it, idx, nodes[idx]); },
      index: function (i) { if (i != null) { idx = i; scrollTo(); } return idx; },
      item: function () { return cache[idx]; },
      refresh: function (i) { var n = nodes[i]; if (n && cache[i] !== undefined) { n.innerHTML = o.render(cache[i], i); } },
      destroy: function () { gen++; }
    };
    scrollTo(); draw();
    return z;
  }

  /* ---------- carrossel horizontal (itens já em memória) ---------- */
  function HRow(el, items, o) {
    var idx = 0, left = 0, active = false;
    var track = h("div", "track"); el.appendChild(track);
    items.forEach(function (it, i) { var n = h("div", "cell " + (o.cls || ""), o.render(it, i)); n.style.transform = "translateX(" + i * o.cw + "px)"; track.appendChild(n); it._n = n; });
    function paint() {
      var W = el.clientWidth, x = idx * o.cw;
      if (x < left + 10) left = Math.max(0, x - o.cw * 0.2);
      if (x + o.cw > left + W - 40) left = x + o.cw - W + o.cw * 0.6;
      track.style.transform = "translateX(" + (-left) + "px)";
      items.forEach(function (it, i) { it._n.classList.toggle("f", active && i === idx); });
      if (active && o.onFocus) o.onFocus(items[idx], idx);
    }
    return {
      empty: function () { return !items.length; },
      focusIn: function () { active = true; paint(); },
      blur: function () { active = false; paint(); },
      move: function (d) { if (d === "R" && idx < items.length - 1) { idx++; paint(); return true; } if (d === "L" && idx > 0) { idx--; paint(); return true; } return false; },
      enter: function () { o.onEnter && o.onEnter(items[idx], idx); },
      long: function () { o.onLong && o.onLong(items[idx], idx, items[idx]._n); }
    };
  }

  return { h: h, esc: esc, img: img, reset: reset, add: add, link: link, focus: focus, key: key, enter: enter, long: long, current: current, Flat: Flat, VGrid: VGrid, HRow: HRow };
})();
