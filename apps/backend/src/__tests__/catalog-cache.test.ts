import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { AddressInfo } from "node:net";

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "hugo-cat-"));
process.env.HUGO_CATALOG_CACHE_DIR = tmp;
const express = (await import("express")).default;
const { channelsRouter } = await import("../routes-channels.js");

const realFetch = globalThis.fetch;
let mode: "ok" | "down" = "ok";
let calls = 0;
const m3u = (n: number) =>
  "#EXTM3U\n" + Array.from({ length: n }, (_, i) => `#EXTINF:-1 group-title="G",Canal ${i}\nhttp://cdn.example.test/live/${i}.ts\n`).join("");
globalThis.fetch = (async (input: any, init?: any) => {
  const url = new URL(typeof input === "string" ? input : input.url ?? String(input));
  if (url.hostname === "lista.example.test") {
    calls++;
    if (mode === "down") return new Response("cf", { status: 530 });
    return new Response(m3u(85_000), { status: 200 });
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
  fs.rmSync(tmp, { recursive: true, force: true });
});
const pl = "http://lista.example.test/lista.m3u";
const get = async (limit: number) => {
  const r = await realFetch(`${base}/?url=${encodeURIComponent(pl)}&limit=${limit}`);
  return { status: r.status, cache: r.headers.get("x-hugo-cache"), body: (await r.json()) as any };
};

test("1ª abertura busca no provedor, a 2ª vem do cache em disco; limit=80000 não corta 85.000", async () => {
  const a = await get(80000);
  assert.equal(a.status, 200);
  assert.equal(a.cache, "miss");
  assert.equal(a.body.total, 85000);
  assert.equal(a.body.channels.length, 85000);
  assert.equal(a.body.hasMore, false);
  const b = await get(100);
  assert.equal(b.cache, "hit");
  assert.equal(b.body.channels.length, 100);
  assert.equal(b.body.total, 85000);
  assert.equal(calls, 1);
  assert.equal(fs.readdirSync(tmp).filter((f) => f.endsWith(".json.gz")).length, 1);
  assert.ok(!fs.readdirSync(tmp).some((f) => f.includes("lista")), "nome do arquivo não traz o link");
});

test("provedor fora do ar (530) com cópia recente: responde a cópia em vez de 502", async () => {
  mode = "down";
  const r = await realFetch(`${base}/?url=${encodeURIComponent(pl)}&limit=10&fresh=1`);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("x-hugo-cache"), "fallback");
  const body: any = await r.json();
  assert.equal(body.total, 85000);
});
