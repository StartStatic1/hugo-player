// Hugo list relay: só lista M3U / API Xtream / EPG. Nunca vídeo. Sem logs de URL/credenciais.
const ALLOWED = /\/(get\.php|player_api\.php|xmltv\.php)$/i;
export default {
  async fetch(req, env) {
    if (req.method !== "GET") return new Response("method", { status: 405 });
    if (!env.RELAY_KEY || req.headers.get("x-hugo-relay") !== env.RELAY_KEY) return new Response("forbidden", { status: 403 });
    let target;
    try { target = new URL(atob(req.headers.get("x-hugo-target") || "")); } catch { return new Response("bad target", { status: 400 }); }
    if (!/^https?:$/.test(target.protocol) || !ALLOWED.test(target.pathname)) return new Response("not allowed", { status: 403 });
    const action = target.searchParams.get("action") || "";
    if (/stream_url|timeshift|play/i.test(action)) return new Response("not allowed", { status: 403 });
    const up = await fetch(target.toString(), {
      headers: { "User-Agent": req.headers.get("x-hugo-ua") || "VLC/3.0.20 LibVLC/3.0.20", "Accept": "*/*" },
      redirect: "follow",
      cf: { cacheTtl: 0 },
    });
    // redirecionou para algo que não é lista (ex.: vídeo): recusa
    const fin = new URL(up.url || target.toString());
    if (!ALLOWED.test(fin.pathname)) return new Response("not allowed", { status: 403 });
    const ct = (up.headers.get("content-type") || "").toLowerCase();
    if (/^(video|audio)\//.test(ct) || ct.includes("mpegurl") && !/get\.php/i.test(fin.pathname)) return new Response("not allowed", { status: 403 });
    const h = new Headers();
    h.set("content-type", up.headers.get("content-type") || "text/plain");
    h.set("cache-control", "no-store");
    h.set("x-hugo-upstream", String(up.status));
    return new Response(up.body, { status: up.status, headers: h });
  },
};
