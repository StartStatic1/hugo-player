import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import express from "express";
import {
  balanceOf, createResellerAdminRouter, createResellerRouter, ensureResellerStore,
  type ResellerDevice, type ResellerStore,
} from "../resellers.js";
import { RESELLER_PACKS } from "../plans.js";
import { revokeRefundedOrder } from "../refunds.js";
import { activeLicense } from "../routes-admin.js";

const DAY = 86400000;
const ADMIN = "test-admin-token";

async function start(opts: { now?: () => number; paid?: boolean } = {}) {
  const store: ResellerStore = ensureResellerStore({});
  const t0 = Date.parse("2026-11-01T12:00:00Z");
  const devices: Record<string, ResellerDevice & { key: string }> = {
    "AA:01": { deviceId: "AA:01", key: "KEY1", platform: "android-tv", createdAt: "2026-10-01T00:00:00Z", license: { type: "trial", plan: "trial", expiresAt: new Date(t0 + 3 * DAY).toISOString() } },
    "BB:02": { deviceId: "BB:02", key: "KEY2", platform: "android", createdAt: "2026-10-01T00:00:00Z", license: { type: "premium", plan: "lifetime", expiresAt: null } },
    "CC:03": { deviceId: "CC:03", key: "KEY3", platform: "android", createdAt: "2026-10-01T00:00:00Z", license: { type: "premium", plan: "annual", expiresAt: new Date(t0 + 100 * DAY).toISOString() } },
  };
  let writes = 0;
  const links: string[] = [];
  const deps = {
    store: () => store,
    persist: async () => { writes++; },
    findDevice: (id: string) => devices[id.toUpperCase()],
    verifyDevice: (id: string, key: string) => { const d = devices[id.toUpperCase()]; return d && d.key === key.toUpperCase() ? d : null; },
    adminToken: ADMIN,
    publicWebUrl: "https://web.test",
    publicApiUrl: "https://api.test",
    createCheckout: async (o: { orderNsu: string }) => { links.push(o.orderNsu); return `https://pay.test/${o.orderNsu}`; },
    confirmPayment: async () => opts.paid ?? true,
    now: opts.now ?? (() => t0),
    limits: { loginPerIpPer15Min: 1000, signupPerIpPerHour: 1000 },
  };
  const app = express();
  app.use(express.json());
  app.use("/reseller", createResellerRouter(deps));
  app.use("/admin/resellers", createResellerAdminRouter({ ...deps, allDevices: () => Object.values(devices) }));
  const server = app.listen(0);
  await new Promise((r) => server.once("listening", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const call = async (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) => {
    const res = await fetch(base + path, { method, headers: { "content-type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    let json: any = {};
    try { json = JSON.parse(text); } catch { /* 200 vazio */ }
    return { status: res.status, json, text };
  };
  const admin = (method: string, path: string, body?: unknown) => call(method, "/admin/resellers" + path, body, { "x-admin-token": ADMIN });
  const as = (token: string) => (method: string, path: string, body?: unknown) => call(method, "/reseller" + path, body, { authorization: `Bearer ${token}` });
  async function approvedReseller(email = "rev@test.com", credits = 0) {
    assert.equal((await call("POST", "/reseller/signup", { name: "Rev Teste", email, password: "senha-forte-1" })).status, 201);
    const id = store.resellers.find((r) => r.email === email)!.id;
    assert.equal((await admin("POST", `/${id}/approve`)).status, 200);
    if (credits) assert.equal((await admin("POST", `/${id}/credits`, { delta: credits, reason: "Pix teste" })).status, 200);
    const login = await call("POST", "/reseller/login", { email, password: "senha-forte-1" });
    assert.equal(login.status, 200);
    return { id, me: as(login.json.token), token: login.json.token as string };
  }
  return { call, admin, as, store, devices, writes: () => writes, links, approvedReseller, t0, close: () => server.close() };
}

test("cadastro fica pendente e não ativa nada até o admin aprovar; senha nunca aparece", async () => {
  const api = await start();
  try {
    const su = await api.call("POST", "/reseller/signup", { name: "Ana", email: "ANA@Test.com", password: "12345678" });
    assert.equal(su.status, 201);
    assert.equal(su.json.status, "pending");
    assert.equal((await api.call("POST", "/reseller/signup", { name: "Ana 2", email: "ana@test.com", password: "12345678" })).status, 409);
    assert.equal((await api.call("POST", "/reseller/signup", { name: "B", email: "b@test.com", password: "curta" })).status, 400);
    const r = api.store.resellers[0];
    assert.equal(r.email, "ana@test.com");
    assert.ok(!JSON.stringify(r).includes("12345678"), "senha não fica em texto");
    const login = await api.call("POST", "/reseller/login", { email: "ana@test.com", password: "12345678" });
    assert.equal(login.status, 200);
    assert.ok(!login.text.includes(r.passwordHash) && !login.text.includes("passwordSalt"));
    const me = api.as(login.json.token);
    assert.equal((await me("GET", "/me")).json.reseller.status, "pending");
    assert.equal((await me("POST", "/activate", { deviceId: "AA:01", deviceKey: "KEY1", confirm: true })).status, 403);
    assert.equal((await me("POST", "/orders", { packId: "p5" })).status, 403);
    // token não fica salvo em claro
    assert.ok(!JSON.stringify(api.store.resellerSessions).includes(login.json.token));
    const list = await api.admin("GET", "/");
    assert.ok(!list.text.includes("passwordHash") && !list.text.includes(r.passwordHash));
    assert.equal((await api.call("GET", "/admin/resellers/", undefined, { "x-admin-token": "errado" })).status, 401);
  } finally { api.close(); }
});

test("login errado trava depois de 5 tentativas; logout invalida a sessão; sessão expira", async () => {
  let now = Date.parse("2026-11-01T12:00:00Z");
  const api = await start({ now: () => now });
  try {
    const { me } = await api.approvedReseller();
    for (let i = 0; i < 5; i++) assert.equal((await api.call("POST", "/reseller/login", { email: "rev@test.com", password: "errada" })).status, 401);
    assert.equal((await api.call("POST", "/reseller/login", { email: "rev@test.com", password: "senha-forte-1" })).status, 429);
    assert.equal((await api.call("POST", "/reseller/login", { email: "naoexiste@test.com", password: "x" })).status, 401);
    assert.equal((await me("GET", "/me")).status, 200);
    now += 13 * 60 * 60_000; // passou das 12 h
    assert.equal((await me("GET", "/me")).status, 401);
    const again = await api.call("POST", "/reseller/login", { email: "rev@test.com", password: "senha-forte-1" });
    assert.equal(again.status, 200);
    const me2 = api.as(again.json.token);
    assert.equal((await me2("POST", "/logout")).status, 200);
    assert.equal((await me2("GET", "/me")).status, 401);
  } finally { api.close(); }
});

test("ativar: prévia não debita; confirmar debita 1 e soma 1 ano aos dias que restam", async () => {
  const api = await start();
  try {
    const { id, me } = await api.approvedReseller("rev@test.com", 2);
    const pv = await me("POST", "/activate", { deviceId: "aa:01", deviceKey: "key1" });
    assert.equal(pv.status, 200);
    assert.equal(pv.json.preview.cost, 1);
    assert.equal(pv.json.preview.balanceAfter, 1);
    assert.equal(balanceOf(api.store.creditLedger, id), 2);
    assert.equal(api.devices["AA:01"].license.type, "trial");
    const ok = await me("POST", "/activate", { deviceId: "aa:01", deviceKey: "key1", confirm: true });
    assert.equal(ok.status, 200);
    assert.equal(ok.json.balance, 1);
    const d = api.devices["AA:01"];
    assert.equal(d.license.type, "premium");
    assert.equal(d.license.plan, "annual");
    // 3 dias de teste + 365
    assert.equal(new Date(d.license.expiresAt!).getTime(), api.t0 + 368 * DAY);
    assert.equal(d.activatedBy?.resellerId, id);
    // renovação de cliente dele (sem Key) soma mais 1 ano
    const rn = await me("POST", "/renew", { deviceId: "AA:01", confirm: true });
    assert.equal(rn.status, 200);
    assert.equal(new Date(d.license.expiresAt!).getTime(), api.t0 + (368 + 365) * DAY);
    // sem saldo: 402 e nada muda
    const before = JSON.stringify(api.devices["CC:03"].license);
    assert.equal((await me("POST", "/activate", { deviceId: "CC:03", deviceKey: "KEY3", confirm: true })).status, 402);
    assert.equal(JSON.stringify(api.devices["CC:03"].license), before);
    assert.equal(balanceOf(api.store.creditLedger, id), 0);
    const clients = await me("GET", "/clients");
    assert.equal(clients.json.clients.length, 1);
    assert.equal(clients.json.clients[0].activations, 2);
    const led = await me("GET", "/ledger");
    assert.deepEqual(led.json.entries.map((e: any) => e.delta), [-1, -1, 2]);
  } finally { api.close(); }
});

test("ativação recusa: Key errada (trava após 10), vitalício, cliente de outro revendedor no renovar", async () => {
  const api = await start();
  try {
    const { me } = await api.approvedReseller("rev@test.com", 5);
    assert.equal((await me("POST", "/activate", { deviceId: "BB:02", deviceKey: "KEY2", confirm: true })).json.error, "device_lifetime");
    assert.equal((await me("POST", "/renew", { deviceId: "CC:03", confirm: true })).status, 404);
    for (let i = 0; i < 10; i++) assert.equal((await me("POST", "/activate", { deviceId: "CC:03", deviceKey: "ERRADA" })).status, 404);
    assert.equal((await me("POST", "/activate", { deviceId: "CC:03", deviceKey: "KEY3", confirm: true })).status, 429);
    assert.equal(balanceOf(api.store.creditLedger, api.store.resellers[0].id), 5);
  } finally { api.close(); }
});

test("pacote: link InfinitePay, webhook credita 1 vez; estorno tira; se já gastou fica negativo e bloqueia", async () => {
  const api = await start();
  try {
    const { id, me } = await api.approvedReseller();
    assert.equal((await me("POST", "/orders", { packId: "xx" })).status, 400);
    const o = await me("POST", "/orders", { packId: "p5" });
    assert.equal(o.status, 200);
    assert.equal(o.json.amount, 12500);
    assert.ok(o.json.checkoutUrl.startsWith("https://pay.test/hugo-cr-"));
    const hook = (body: object) => api.call("POST", "/reseller/payments/webhook", { order_nsu: o.json.orderNsu, ...body });
    assert.equal((await hook({ transaction_nsu: "T1", slug: "S1" })).status, 200);
    assert.equal((await hook({ transaction_nsu: "T1", slug: "S1" })).status, 200);
    assert.equal(balanceOf(api.store.creditLedger, id), 5);
    // gasta 2
    await me("POST", "/activate", { deviceId: "AA:01", deviceKey: "KEY1", confirm: true });
    await me("POST", "/activate", { deviceId: "CC:03", deviceKey: "KEY3", confirm: true });
    assert.equal(balanceOf(api.store.creditLedger, id), 3);
    // estorno: −5 → saldo −2, conta bloqueada
    assert.equal((await hook({ event: "refunded" })).status, 200);
    assert.equal((await hook({ event: "refunded" })).status, 200);
    assert.equal(balanceOf(api.store.creditLedger, id), -2);
    const r = api.store.resellers.find((x) => x.id === id)!;
    assert.equal(r.status, "blocked");
    // sessões caem? (bloqueio automático não derruba, mas não ativa)
    assert.equal((await me("POST", "/activate", { deviceId: "AA:01", deviceKey: "KEY1", confirm: true })).status, 403);
    // admin não consegue liberar com saldo negativo; acerta com +2 e libera
    assert.equal((await api.admin("POST", `/${id}/unblock`)).status, 409);
    assert.equal((await api.admin("POST", `/${id}/credits`, { delta: 2, reason: "acerto do estorno" })).status, 200);
    assert.equal((await api.admin("POST", `/${id}/unblock`)).status, 200);
    // histórico só cresce (nada editado)
    assert.deepEqual(api.store.creditLedger.map((e) => e.kind), ["purchase", "activation", "activation", "refund", "admin_add"]);
  } finally { api.close(); }
});

test("pagamento não confirmado pela InfinitePay não credita", async () => {
  const api = await start({ paid: false });
  try {
    const { id, me } = await api.approvedReseller();
    const o = await me("POST", "/orders", { packId: "p100" });
    assert.equal(o.json.amount, 180000);
    assert.equal((await api.call("POST", "/reseller/payments/webhook", { order_nsu: o.json.orderNsu, transaction_nsu: "T", slug: "S" })).status, 400);
    assert.equal((await me("POST", `/orders/${o.json.orderNsu}/check`, { transaction_nsu: "T", slug: "S" })).json.status, "pending");
    assert.equal(balanceOf(api.store.creditLedger, id), 0);
  } finally { api.close(); }
});

test("admin: remover créditos não deixa negativo; desfazer ativação devolve crédito e volta a licença", async () => {
  const api = await start();
  try {
    const { id, me } = await api.approvedReseller("rev@test.com", 3);
    assert.equal((await api.admin("POST", `/${id}/credits`, { delta: -5, reason: "teste" })).status, 409);
    assert.equal((await api.admin("POST", `/${id}/credits`, { delta: -1, reason: "" })).status, 400);
    const prev = JSON.stringify(api.devices["CC:03"].license);
    await me("POST", "/activate", { deviceId: "CC:03", deviceKey: "KEY3", confirm: true });
    assert.equal(balanceOf(api.store.creditLedger, id), 2);
    const acts = await api.admin("GET", `/activations/list?resellerId=${id}`);
    assert.equal(acts.json.activations.length, 1);
    const aid = acts.json.activations[0].id;
    assert.equal((await api.admin("POST", `/activations/${aid}/undo`, { reason: "ID errado" })).status, 200);
    assert.equal((await api.admin("POST", `/activations/${aid}/undo`, { reason: "ID errado" })).status, 409);
    assert.equal(balanceOf(api.store.creditLedger, id), 3);
    assert.equal(JSON.stringify(api.devices["CC:03"].license), prev);
    assert.equal(api.devices["CC:03"].activatedBy, undefined);
    // revendedor não tem rota de desfazer
    assert.equal((await me("POST", `/activations/${aid}/undo`, { reason: "x" })).status, 404);
    // bloqueado pelo admin: sessão cai
    assert.equal((await api.admin("POST", `/${id}/block`, { reason: "teste" })).status, 200);
    assert.equal((await me("GET", "/me")).status, 401);
  } finally { api.close(); }
});

test("pacotes aprovados: 5/R$125, 10/R$220, 50/R$1000, 100/R$1800", () => {
  assert.deepEqual(RESELLER_PACKS.map((p) => [p.credits, p.amount]), [[5, 12500], [10, 22000], [50, 100000], [100, 180000]]);
});

test("estorno de pedido do cliente revoga a licença (também se foi transferida)", () => {
  const now = Date.parse("2026-11-01T00:00:00Z");
  const devices: any = {
    OLD: { deviceId: "OLD", license: { type: "expired", plan: "trial", expiresAt: "x", paymentProvider: "transferred" } },
    NEW: { deviceId: "NEW", license: { type: "premium", plan: "annual", expiresAt: new Date(now + 300 * DAY).toISOString(), transactionNsu: "TX9" } },
  };
  assert.equal(revokeRefundedOrder(devices, { deviceId: "OLD", plan: "annual", transactionNsu: "TX9" }, now), "NEW");
  assert.equal(devices.NEW.license.type, "expired");
  assert.equal(devices.NEW.revokedReason, "pagamento estornado");
  // licença já renovada de outro jeito: só desconta os dias do plano estornado
  const d2: any = { A: { deviceId: "A", license: { type: "premium", plan: "annual", expiresAt: new Date(now + 500 * DAY).toISOString(), transactionNsu: "OUTRO" } } };
  revokeRefundedOrder(d2, { deviceId: "A", plan: "semiannual", transactionNsu: "TX1" }, now);
  assert.equal(new Date(d2.A.license.expiresAt).getTime(), now + 320 * DAY);
  assert.equal(d2.A.license.type, "premium");
});

test("ativação manual do admin soma aos dias que restam", () => {
  const now = Date.parse("2026-11-01T00:00:00Z");
  const cur = { type: "premium", expiresAt: new Date(now + 40 * DAY).toISOString() };
  assert.equal(new Date(activeLicense("annual", cur, now).expiresAt!).getTime(), now + 405 * DAY);
  assert.equal(new Date(activeLicense("semiannual", { type: "expired", expiresAt: new Date(now + 40 * DAY).toISOString() }, now).expiresAt!).getTime(), now + 180 * DAY);
  assert.equal(new Date(activeLicense("annual", { type: "trial", expiresAt: new Date(now - DAY).toISOString() }, now).expiresAt!).getTime(), now + 365 * DAY);
  assert.equal(activeLicense("lifetime", cur, now).expiresAt, null);
});
