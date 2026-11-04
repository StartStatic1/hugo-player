/* Navegação pelo controle remoto (D-pad): Samsung Tizen, LG webOS e teclado do PC. */
var Keys = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: [10009, 461, 8, 27], PLAY: 415, PAUSE: 19, PLAYPAUSE: 10252, STOP: 413, FF: 417, RW: 412, CHUP: 427, CHDOWN: 428, INFO: 457 };
function registerTvKeys() {
  try {
    if (window.tizen && tizen.tvinputdevice) ["MediaPlay", "MediaPause", "MediaPlayPause", "MediaStop", "MediaFastForward", "MediaRewind", "ChannelUp", "ChannelDown", "Info"]
      .forEach(function (k) { try { tizen.tvinputdevice.registerKey(k); } catch (e) {} });
  } catch (e) {}
}
var Nav = (function () {
  var current = null;
  function all(root) { return Array.prototype.slice.call((root || document).querySelectorAll(".focusable")).filter(function (el) { return el.offsetParent !== null; }); }
  function focus(el) {
    if (!el) return;
    if (current) current.classList.remove("focused");
    current = el; el.classList.add("focused");
    if (el.focus && (el.tagName === "INPUT")) el.focus(); else if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    var sc = el.closest ? el.closest("[data-scroll]") : null;
    if (sc) {
      var r = el.getBoundingClientRect(), s = sc.getBoundingClientRect();
      if (r.bottom > s.bottom - 20) sc.scrollTop += r.bottom - s.bottom + 40;
      if (r.top < s.top + 20) sc.scrollTop -= s.top - r.top + 40;
    }
    if (el.dataset.onfocus && window[el.dataset.onfocus]) window[el.dataset.onfocus](el);
    if (el._onfocus) el._onfocus(el);
  }
  function move(dir) {
    if (!current || !document.body.contains(current)) { focus(all()[0]); return; }
    var c = current.getBoundingClientRect(), cx = c.left + c.width / 2, cy = c.top + c.height / 2, best = null, bd = 1e9;
    all().forEach(function (el) {
      if (el === current) return;
      var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2, dx = x - cx, dy = y - cy, d;
      if (dir === "L" && r.right > c.left + 2) return; if (dir === "R" && r.left < c.right - 2) return;
      if (dir === "U" && r.bottom > c.top + 2) return; if (dir === "D" && r.top < c.bottom - 2) return;
      d = (dir === "L" || dir === "R") ? Math.abs(dx) + Math.abs(dy) * 3 : Math.abs(dy) + Math.abs(dx) * 3;
      if (d < bd) { bd = d; best = el; }
    });
    if (best) focus(best);
  }
  return {
    focus: focus, move: move, all: all,
    current: function () { return current; },
    first: function (root) { focus(all(root)[0]); }
  };
})();
