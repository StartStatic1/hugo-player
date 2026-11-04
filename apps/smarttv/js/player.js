/* Player: Tizen usa AVPlay (nativo, toca HLS/TS/MP4); webOS e navegador usam <video> (+ hls.js quando precisa). */
var Player = (function () {
  var video, hls = null, useAv = false, open = false, osdTimer, onClose, queue = null, qi = 0, avState = "";
  function el(id) { return document.getElementById(id); }
  function osd(t, info) {
    if (t !== undefined) el("osdTitle").textContent = t;
    if (info !== undefined) el("osdInfo").textContent = info;
    el("osd").classList.remove("off"); clearTimeout(osdTimer);
    osdTimer = setTimeout(function () { el("osd").classList.add("off"); }, 4000);
  }
  function fmt(s) { s = Math.max(0, Math.floor(s)); var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60; return (h ? h + ":" : "") + ("0" + m).slice(-2) + ":" + ("0" + x).slice(-2); }
  function tick() {
    if (!open) return;
    var pos = 0, dur = 0;
    if (useAv) { try { pos = webapis.avplay.getCurrentTime() / 1000; dur = webapis.avplay.getDuration() / 1000; } catch (e) {} }
    else { pos = video.currentTime || 0; dur = isFinite(video.duration) ? video.duration : 0; }
    el("osdBar").style.width = dur > 0 ? (pos / dur * 100) + "%" : "0";
    if (dur > 0) el("osdInfo").textContent = fmt(pos) + " / " + fmt(dur);
    setTimeout(tick, 1000);
  }
  function stopAll() {
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
    if (useAv) { try { webapis.avplay.stop(); webapis.avplay.close(); } catch (e) {} el("avplay").classList.add("hidden"); }
    if (video) { try { video.pause(); video.removeAttribute("src"); video.load(); } catch (e) {} }
  }
  function err(msg) { osd(undefined, msg); }
  function play(item) {
    stopAll();
    var url = item.url;
    osd(item.name, "Carregando...");
    useAv = !!(window.webapis && webapis.avplay);
    if (useAv) {
      el("video").classList.add("hidden"); el("avplay").classList.remove("hidden");
      try {
        webapis.avplay.open(url);
        webapis.avplay.setDisplayRect(0, 0, 1920, 1080);
        webapis.avplay.setListener({
          onbufferingcomplete: function () { osd(undefined, ""); },
          onstreamcompleted: function () { next(1); },
          onerror: function () { err("Não foi possível reproduzir este conteúdo."); }
        });
        webapis.avplay.prepareAsync(function () { webapis.avplay.play(); avState = "play"; osd(undefined, ""); }, function () { err("Não foi possível abrir este conteúdo."); });
      } catch (e) { err("Erro ao reproduzir."); }
      return;
    }
    el("video").classList.remove("hidden");
    var isHls = /\.m3u8(\?|$)/i.test(url) || /\/live\//i.test(url) && !/\.(ts|mp4|mkv)(\?|$)/i.test(url);
    if (isHls && !video.canPlayType("application/vnd.apple.mpegurl") && window.Hls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, lowLatencyMode: false });
      hls.on(Hls.Events.ERROR, function (_, d) { if (d.fatal) err("Sem conexão com o servidor do canal."); });
      hls.loadSource(url); hls.attachMedia(video);
    } else video.src = url;
    var p = video.play(); if (p && p.catch) p.catch(function () {});
  }
  function next(d) {
    if (!queue) return;
    qi = (qi + d + queue.length) % queue.length;
    play(queue[qi]);
  }
  function toggle() {
    if (useAv) { try { if (avState === "play") { webapis.avplay.pause(); avState = "pause"; } else { webapis.avplay.play(); avState = "play"; } } catch (e) {} }
    else if (video.paused) video.play(); else video.pause();
    osd();
  }
  function seek(sec) {
    if (useAv) { try { webapis.avplay[sec > 0 ? "jumpForward" : "jumpBackward"](Math.abs(sec) * 1000); } catch (e) {} }
    else try { video.currentTime = Math.max(0, video.currentTime + sec); } catch (e) {}
    osd();
  }
  return {
    init: function () {
      video = el("video");
      video.addEventListener("playing", function () { osd(undefined, ""); });
      video.addEventListener("error", function () { err("Não foi possível reproduzir este conteúdo."); });
      video.addEventListener("ended", function () { next(1); });
    },
    isOpen: function () { return open; },
    open: function (list, index, close) {
      queue = list; qi = index; onClose = close; open = true;
      el("player").classList.remove("hidden");
      play(queue[qi]); tick();
    },
    close: function () {
      stopAll(); open = false; el("player").classList.add("hidden");
      if (onClose) onClose();
    },
    key: function (k) {
      if (Keys.BACK.indexOf(k) >= 0 || k === Keys.STOP) { this.close(); return; }
      var live = queue && queue[qi] && queue[qi].type === "live";
      if (k === Keys.ENTER || k === Keys.PLAY || k === Keys.PAUSE || k === Keys.PLAYPAUSE) return toggle();
      if (live && (k === Keys.UP || k === Keys.CHUP)) return next(-1);
      if (live && (k === Keys.DOWN || k === Keys.CHDOWN)) return next(1);
      if (k === Keys.RIGHT || k === Keys.FF) return seek(live ? 0 : 10);
      if (k === Keys.LEFT || k === Keys.RW) return seek(live ? 0 : -10);
      osd();
    }
  };
})();
