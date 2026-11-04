/*
 * Player: Tizen = AVPlay nativo (HLS/TS/MP4); webOS/navegador = <video> + hls.js.
 * Overlay: canal/logo + EPG agora/a seguir (ao vivo) ou linha do tempo (filmes/séries). Salva a posição (continuar assistindo).
 */
var Player = (function () {
  var video, hls = null, useAv = false, open = false, hideT, tickT, saveT, o = null, idx = 0, item = null, avState = "", paused = false;
  function $(id) { return document.getElementById(id); }
  function fmt(s) { s = Math.max(0, Math.floor(s || 0)); var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60; return (h ? h + ":" : "") + ("0" + m).slice(-2) + ":" + ("0" + x).slice(-2); }
  function hm(iso) { var d = new Date(iso); return ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2); }
  function show(ms) { $("player").classList.add("ui"); clearTimeout(hideT); hideT = setTimeout(function () { if (!paused) $("player").classList.remove("ui"); }, ms || 4500); }
  function pos() { try { return useAv ? webapis.avplay.getCurrentTime() / 1000 : video.currentTime || 0; } catch (e) { return 0; } }
  function dur() { try { return useAv ? webapis.avplay.getDuration() / 1000 : (isFinite(video.duration) ? video.duration : 0); } catch (e) { return 0; } }
  function status(t) { $("pStatus").textContent = t || ""; $("pStatus").classList.toggle("hidden", !t); }
  function tick() {
    if (!open) return;
    var live = item && item.t === "live";
    if (!live) { var p = pos(), d = dur(); $("pBar").style.width = d > 0 ? (p / d * 100) + "%" : "0"; $("pTime").textContent = fmt(p) + " / " + fmt(d); }
    else if (item._now) { var s = new Date(item._now.start).getTime(), e = new Date(item._now.stop).getTime(); $("pBar").style.width = Math.max(0, Math.min(100, (Date.now() - s) / (e - s) * 100)) + "%"; }
    tickT = setTimeout(tick, 1000);
  }
  function saveProgress() { if (item && item.t !== "live" && o.pid) Progress.save(o.pid, item, pos(), dur()); }
  function stopAll() {
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
    if (useAv) { try { webapis.avplay.stop(); webapis.avplay.close(); } catch (e) {} }
    if (video) { try { video.pause(); video.removeAttribute("src"); video.load(); } catch (e) {} }
  }
  function paintInfo() {
    var live = item.t === "live";
    $("pLogo").innerHTML = item.l ? UI.img(item.l) : "";
    $("pTitle").textContent = item.n;
    $("pSub").textContent = live ? (o.numberOf ? o.numberOf(idx) + " · " : "") + (item.g || "") : (item.se ? "T" + item.se + " · E" + item.ep : (item.g || ""));
    $("pNow").innerHTML = ""; $("pNext").innerHTML = ""; $("pTime").textContent = live ? "AO VIVO" : "";
    $("pTime").classList.toggle("live", live);
    $("pHint").textContent = live ? "▲ ▼ ou CH+/CH− trocar canal · OK informações · Voltar sair" : "OK pausar · ◀ ▶ 10 s · ⏪ ⏩ 1 min · Voltar sair";
    if (live && o.epg) o.epg(item).then(function (nn) {
      if (!nn || item !== cur0) return;
      item._now = nn.now;
      if (nn.now) $("pNow").innerHTML = '<b>Agora</b> ' + hm(nn.now.start) + " " + UI.esc(nn.now.title);
      if (nn.next) $("pNext").innerHTML = '<b>A seguir</b> ' + hm(nn.next.start) + " " + UI.esc(nn.next.title);
    });
    var cur0 = item;
  }
  function play(startAt) {
    stopAll(); paintInfo(); status("Carregando..."); show();
    var url = item.u; paused = false;
    useAv = !!(window.webapis && webapis.avplay);
    $("video").classList.toggle("hidden", useAv); $("avplay").classList.toggle("hidden", !useAv);
    if (useAv) {
      try {
        webapis.avplay.open(url);
        webapis.avplay.setDisplayRect(0, 0, 1920, 1080);
        webapis.avplay.setListener({
          onbufferingstart: function () { status("Carregando..."); },
          onbufferingcomplete: function () { status(""); },
          onstreamcompleted: function () { if (item.t === "live") return; Progress.save(o.pid, item, dur(), dur()); step(1); },
          onerror: function () { status("Não foi possível reproduzir este conteúdo."); show(8000); }
        });
        webapis.avplay.prepareAsync(function () { if (startAt) try { webapis.avplay.seekTo(startAt * 1000); } catch (e) {} webapis.avplay.play(); avState = "play"; status(""); },
          function () { status("Não foi possível abrir este conteúdo."); });
      } catch (e) { status("Erro ao reproduzir."); }
      return;
    }
    var isHls = /\.m3u8(\?|#|$)/i.test(url) || (/\/live\//i.test(url) && !/\.(ts|mp4|mkv)(\?|$)/i.test(url));
    /* hls.js sempre que der (Chrome novo diz "maybe" para HLS nativo e falha); webOS usa o nativo */
    if (isHls && window.Hls && Hls.isSupported() && !(window.webOS || window.PalmSystem)) {
      hls = new Hls({ enableWorker: true, maxBufferLength: 20, backBufferLength: 10 });
      hls.on(Hls.Events.ERROR, function (_, d) { if (d.fatal) { status("Sem conexão com o servidor do canal."); show(8000); } });
      hls.loadSource(url); hls.attachMedia(video);
    } else video.src = url;
    if (startAt) { var once = function () { video.removeEventListener("loadedmetadata", once); try { video.currentTime = startAt; } catch (e) {} }; video.addEventListener("loadedmetadata", once); }
    var p = video.play(); if (p && p["catch"]) p["catch"](function () {});
  }
  function step(d) {
    if (!o.count || o.count < 2) return;
    saveProgress();
    var n = (idx + d + o.count) % o.count;
    o.at(n).then(function (it) { if (!it || !open) return; idx = n; item = it; play(0); });
  }
  function toggle() {
    if (useAv) { try { if (avState === "play") { webapis.avplay.pause(); avState = "pause"; paused = true; } else { webapis.avplay.play(); avState = "play"; paused = false; } } catch (e) {} }
    else if (video.paused) { video.play(); paused = false; } else { video.pause(); paused = true; }
    $("player").classList.toggle("paused", paused); show();
  }
  function seek(sec) {
    if (useAv) { try { webapis.avplay[sec > 0 ? "jumpForward" : "jumpBackward"](Math.abs(sec) * 1000); } catch (e) {} }
    else try { video.currentTime = Math.max(0, video.currentTime + sec); } catch (e) {}
    show();
  }
  return {
    init: function () {
      video = $("video");
      video.addEventListener("playing", function () { status(""); });
      video.addEventListener("waiting", function () { status("Carregando..."); });
      video.addEventListener("error", function () { status("Não foi possível reproduzir este conteúdo."); show(8000); });
      video.addEventListener("ended", function () { if (item && item.t !== "live") { Progress.save(o.pid, item, dur(), dur()); step(1); } });
    },
    isOpen: function () { return open; },
    /** opts: { pid, count, at(i)->Promise<item>, index, startAt, epg(item)->Promise<{now,next}>, numberOf(i), onClose } */
    open: function (opts) {
      o = opts; idx = opts.index || 0; open = true;
      $("player").classList.remove("hidden", "paused");
      o.at(idx).then(function (it) { item = it; play(opts.startAt || 0); tick(); });
      clearInterval(saveT); saveT = setInterval(saveProgress, 10000);
    },
    close: function () {
      saveProgress(); clearInterval(saveT); clearTimeout(tickT);
      stopAll(); open = false; $("player").classList.add("hidden");
      if (o && o.onClose) o.onClose();
    },
    key: function (k) {
      if (Keys.BACK.indexOf(k) >= 0 || k === Keys.STOP) { if ($("player").classList.contains("ui") && !paused && k !== Keys.STOP) { $("player").classList.remove("ui"); return; } return this.close(); }
      var live = item && item.t === "live";
      if (k === Keys.ENTER || k === Keys.PLAY || k === Keys.PAUSE || k === Keys.PLAYPAUSE) { if (live && k === Keys.ENTER) return show(); return toggle(); }
      if (k === Keys.UP || k === Keys.CHUP) return live ? step(-1) : show();
      if (k === Keys.DOWN || k === Keys.CHDOWN) return live ? step(1) : show();
      if (k === Keys.RIGHT || k === Keys.FF) return live ? show() : seek(k === Keys.FF ? 60 : 10);
      if (k === Keys.LEFT || k === Keys.RW) return live ? show() : seek(k === Keys.RW ? -60 : -10);
      if (k === Keys.CHUP || k === 33) return step(-1);
      show();
    }
  };
})();
