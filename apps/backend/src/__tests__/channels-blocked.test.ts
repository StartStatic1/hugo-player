import { test, beforeEach, after } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import express from "express";
import { channelsRouter } from "../routes-channels.js";
import { BLOCK_MAX_ENTRIES, BLOCK_TTL_MS, blockKey, blockedCount, clearBlocked, isBlocked, markBlocked } from "../content/blocked-hosts.js";

const realFetch = globalThis.fetch;
type Handler = (url: URL) => Response;
let handler: Handler = () => new Response("not found", { status: 404 });
const providerCalls: string[] = [];

globalThis.fetch = (async (input: any, init?: any) => {
  const url = new URL(typeof input === "string" ? input : input.url ?? String(input));
  if (url.hostname.endsWith(".example.test")) {
    providerCalls.push(url.pathname + (url.searchParams.get("action") ? `?${url.searchParams.get("action")}` : ""));
    return handler(url);
  }
  return realFetch(input, init);
}) as typeof fetch;

const app = express();
app.use("/channels", channelsRouter);
const server = app.listen(0);
await new Promise((r) => server.once("listening", r));
const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/channels`;
after(() => {
  server.close();
  globalThis.fetch = realFetch;
});

let n = 0;
/** URL única por teste para não bater no playlistCache nem em bloqueios de outro teste. */
function playlist() {
  n++;
  return `http://panel${n}.example.test:8080/get.php?username=u${n}&password=p&type=m3u_plus&output=ts`;
}
const get = async (pl: string) => {
  const r = await realFetch(`${base}/?url=${encodeURIComponent(pl)}&limit=100`);
  return { status: r.status, body: (await r.json()) as any };
};

beforeEach(() => {
  providerCalls.length = 0;
  clearBlocked();
});

test("403 na player_api: não tenta get.php e responde upstream_403", async () => {
  handler = () => new Response("forbidden", { status: 403 });
  const res = await get(playlist());
  assert.equal(res.status, 502);
  assert.equal(res.body.error, "upstream_403");
  assert.ok(res.body.hint);
  assert.ok(providerCalls.every((c) => c.startsWith("/player_api.php")), providerCalls.join(","));
  assert.ok(!providerCalls.some((c) => c.startsWith("/get.php")));
});

test("provedor bloqueado responde na hora sem contatar o provedor", async () => {
  handler = () => new Response("forbidden", { status: 403 });
  const pl = playlist();
  await get(pl);
  providerCalls.length = 0;
  const res = await get(pl);
  assert.equal(res.status, 502);
  assert.equal(res.body.error, "upstream_403");
  assert.equal(providerCalls.length, 0);
});

test("404 na player_api continua caindo no get.php", async () => {
  const m3u = '#EXTM3U\n#EXTINF:-1 group-title="News",Canal X\nhttp://panel.example.test/live/1.ts\n';
  handler = (url) => (url.pathname === "/get.php" ? new Response(m3u, { status: 200 }) : new Response("nf", { status: 404 }));
  const res = await get(playlist());
  assert.equal(res.status, 200);
  assert.equal(res.body.source, "m3u_fallback");
  assert.equal(res.body.total, 1);
  assert.ok(providerCalls.includes("/get.php"));
  assert.equal(blockedCount(), 0);
});

test("get.php 403 também marca o provedor como bloqueado", async () => {
  handler = (url) => (url.pathname === "/get.php" ? new Response("x", { status: 403 }) : new Response("nf", { status: 404 }));
  const pl = playlist();
  const res = await get(pl);
  assert.equal(res.status, 502);
  assert.equal(res.body.error, "upstream_403");
  providerCalls.length = 0;
  const again = await get(pl);
  assert.equal(again.body.error, "upstream_403");
  assert.equal(providerCalls.length, 0);
});

test("get.php com outro erro não marca bloqueio", async () => {
  handler = () => new Response("nf", { status: 404 });
  const res = await get(playlist());
  assert.equal(res.status, 502);
  assert.equal(res.body.error, "upstream_404");
  assert.equal(blockedCount(), 0);
});

test("player_api ok segue igual (source xtream)", async () => {
  handler = (url) => {
    const action = url.searchParams.get("action");
    if (action === "get_live_categories") return Response.json([{ category_id: "1", category_name: "Abertos" }]);
    if (action === "get_live_streams") return Response.json([{ stream_id: 1, name: "C1", category_id: "1" }]);
    return Response.json([]);
  };
  const res = await get(playlist());
  assert.equal(res.status, 200);
  assert.equal(res.body.source, "xtream");
  assert.equal(res.body.total, 1);
});

test("mapa de bloqueio expira e tem limite de tamanho", () => {
  const k = blockKey("http://Host.example.test:8080", "u");
  assert.equal(k, "host.example.test:8080|u");
  markBlocked(k, 0);
  assert.ok(isBlocked(k, BLOCK_TTL_MS - 1));
  assert.ok(!isBlocked(k, BLOCK_TTL_MS));
  for (let i = 0; i < BLOCK_MAX_ENTRIES + 50; i++) markBlocked(`h${i}|u`, 1);
  assert.equal(blockedCount(), BLOCK_MAX_ENTRIES);
  assert.ok(isBlocked(`h${BLOCK_MAX_ENTRIES + 49}|u`, 2));
  assert.ok(!isBlocked("h0|u", 2));
});

test("/account: só o login da player_api, devolve o vencimento", async () => {
  handler = (u) => u.pathname === "/player_api.php" && !u.searchParams.get("action")
    ? Response.json({ user_info: { exp_date: "1795392000", status: "Active" } })
    : new Response("nope", { status: 500 });
  const r = await realFetch(`${base}/account?url=${encodeURIComponent(playlist())}`);
  const body = (await r.json()) as any;
  assert.equal(r.status, 200);
  assert.equal(body.xtream, true);
  assert.equal(body.account.expiresAt, new Date(1795392000 * 1000).toISOString());
  assert.deepEqual(providerCalls, ["/player_api.php"]);
});

test("/account: exp_date null = sem data; provedor fora = erro (app tenta direto)", async () => {
  handler = () => Response.json({ user_info: { exp_date: null } });
  let r = await realFetch(`${base}/account?url=${encodeURIComponent(playlist())}`);
  let body = (await r.json()) as any;
  assert.equal(body.account.expiresAt, null);
  assert.equal(body.account.unlimited, true);
  handler = () => new Response("forbidden", { status: 403 });
  r = await realFetch(`${base}/account?url=${encodeURIComponent(playlist())}`);
  body = (await r.json()) as any;
  assert.equal(r.status, 502);
  assert.equal(body.error, "upstream_403");
});

test("provedor inalcançável (fetch failed) responde 502 upstream_unreachable, não 500", async () => {
  handler = () => { throw Object.assign(new TypeError("fetch failed"), { cause: { code: "ECONNREFUSED" } }); };
  const res = await get(playlist());
  assert.equal(res.status, 502);
  assert.equal(res.body.error, "upstream_unreachable");
});

test("M3U comum inalcançável também responde 502", async () => {
  handler = () => { throw Object.assign(new TypeError("fetch failed"), { cause: { code: "ENOTFOUND" } }); };
  n++;
  const res = await get(`http://cdn${n}.example.test/lista.m3u`);
  assert.equal(res.status, 502);
  assert.equal(res.body.error, "upstream_unreachable");
});
