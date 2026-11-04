import { test } from "node:test";
import assert from "node:assert/strict";
import { createSecurity, type SecurityStore, BAN_THRESHOLD } from "../security.js";

function mk(allow: string[] = []) {
  let t = Date.parse("2026-11-04T18:00:00Z");
  const store: SecurityStore = { events: [], bans: {} };
  const s = createSecurity({ store: () => store, persist: () => {}, adminToken: "secret-token", allowIps: new Set(allow), now: () => t });
  return { s, store, tick: (ms: number) => (t += ms) };
}

test("5 falhas de admin em 10 min → banido 24 h; alerta agrupado com contagem", () => {
  const { s, store } = mk();
  for (let i = 0; i < BAN_THRESHOLD; i++) s.record("admin_401", "1.2.3.4");
  assert.ok(s.activeBan("1.2.3.4"));
  assert.equal(store.events.length, 1);
  assert.equal(store.events[0].count, BAN_THRESHOLD);
});

test("eventos espalhados (mais de 10 min) não banem", () => {
  const { s, tick } = mk();
  for (let i = 0; i < 6; i++) { s.record("rate_429", "5.5.5.5"); tick(3 * 60 * 1000); }
  assert.equal(s.activeBan("5.5.5.5"), null);
});

test("IP da lista segura nunca é banido; ban expira em 24 h; unban", () => {
  const { s, tick } = mk(["177.87.13.180"]);
  for (let i = 0; i < 10; i++) s.record("admin_401", "177.87.13.180");
  assert.equal(s.activeBan("177.87.13.180"), null);
  for (let i = 0; i < 5; i++) s.record("device_burst", "9.9.9.9");
  assert.ok(s.activeBan("9.9.9.9"));
  assert.equal(s.unban("9.9.9.9"), true);
  assert.equal(s.activeBan("9.9.9.9"), null);
  s.ban("8.8.8.8", "manual", false);
  tick(24 * 3600 * 1000 + 1);
  assert.equal(s.activeBan("8.8.8.8"), null);
});

test("token de admin válido passa mesmo com IP banido", () => {
  const { s } = mk();
  s.ban("7.7.7.7", "x", false);
  const mw = s.middleware(new Set(), () => "7.7.7.7");
  const call = (token?: string) => {
    let status = 0, nexted = false;
    const req: any = { header: (h: string) => (h === "x-admin-token" ? token : undefined), path: "/admin/devices" };
    const res: any = { status: (c: number) => { status = c; return { json: () => {} }; }, on: () => {} };
    mw(req, res, () => { nexted = true; });
    return nexted ? 200 : status;
  };
  assert.equal(call(), 403);
  assert.equal(call("wrong"), 403);
  assert.equal(call("secret-token"), 200);
});
