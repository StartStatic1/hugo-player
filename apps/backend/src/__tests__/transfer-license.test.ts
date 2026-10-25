import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import express from "express";
import { createTransferRouter, licenseRank, pickTransferLicense, type TransferLicense, type TransferRecord } from "../routes-transfer.js";
import type { StoredPlaylist } from "../routes-playlists.js";

const day = 86400000;
const iso = (ms: number) => new Date(Date.now() + ms).toISOString();
const keys: Record<string, string> = { "OLD:01": "OLDKEY1", "NEW:02": "NEWKEY2" };

async function start(lic: Record<string, TransferLicense>) {
  const store = [{ id: "1", deviceId: "OLD:01", name: "A", type: "m3u", url: "http://a.test/1.m3u", createdAt: "x", updatedAt: "x" }] as unknown as StoredPlaylist[];
  const records: TransferRecord[] = [];
  const app = express();
  app.use(express.json());
  app.use("/devices", createTransferRouter({
    playlists: () => store,
    persist: async () => {},
    verify: (id, key) => (keys[id.toUpperCase()] === key.toUpperCase() ? id.toUpperCase() : null),
    failedAttemptsPer15Min: 100,
    licenses: { get: (id) => lic[id], set: (id, l) => { lic[id] = l; }, record: (r) => { records.push(r); } },
  }));
  const server = app.listen(0);
  await new Promise((r) => server.once("listening", r));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/devices/transfer`;
  const post = async (body: unknown) => {
    const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    return { status: res.status, json: (await res.json()) as any };
  };
  return { post, lic, records, close: () => server.close() };
}
const ok = { fromId: "OLD:01", fromKey: "OLDKEY1", toId: "NEW:02", toKey: "NEWKEY2" };

test("vitalício vai para o novo e o antigo perde", async () => {
  const api = await start({
    "OLD:01": { type: "premium", plan: "lifetime", expiresAt: null, paymentProvider: "infinitepay", transactionNsu: "N1" },
    "NEW:02": { type: "trial", plan: "trial", expiresAt: iso(7 * day) },
  });
  try {
    const r = await api.post(ok);
    assert.equal(r.status, 200);
    assert.equal(r.json.license, "moved");
    assert.equal(r.json.moved, 1);
    assert.equal(api.lic["NEW:02"].plan, "lifetime");
    assert.equal(api.lic["NEW:02"].transactionNsu, "N1");
    assert.equal(api.lic["OLD:01"].type, "expired");
    assert.equal(api.records.length, 1);
    assert.equal(api.records[0].from, "OLD:01");
    assert.equal(api.records[0].to, "NEW:02");
    // repetir não devolve a licença ao antigo nem tira do novo
    await api.post(ok);
    assert.equal(api.lic["NEW:02"].plan, "lifetime");
    assert.equal(api.lic["OLD:01"].type, "expired");
  } finally { api.close(); }
});

test("não rebaixa: novo com licença melhor fica com a dele; antigo perde mesmo assim", async () => {
  const api = await start({
    "OLD:01": { type: "premium", plan: "annual", expiresAt: iso(30 * day) },
    "NEW:02": { type: "premium", plan: "annual", expiresAt: iso(300 * day) },
  });
  try {
    const r = await api.post(ok);
    assert.equal(r.json.license, "kept_new");
    assert.ok(new Date(api.lic["NEW:02"].expiresAt!).getTime() > Date.now() + 200 * day);
    assert.equal(api.lic["OLD:01"].type, "expired");
  } finally { api.close(); }
});

test("Key errada ou mesmo aparelho: nada muda", async () => {
  const before = { "OLD:01": { type: "premium", plan: "lifetime", expiresAt: null } as TransferLicense, "NEW:02": { type: "trial", plan: "trial", expiresAt: iso(day) } as TransferLicense };
  const api = await start({ ...before });
  try {
    assert.equal((await api.post({ ...ok, fromKey: "errada" })).status, 401);
    assert.equal((await api.post({ ...ok, toKey: "errada" })).status, 401);
    assert.equal((await api.post({ fromId: "OLD:01", fromKey: "OLDKEY1", toId: "old:01", toKey: "OLDKEY1" })).status, 400);
    assert.equal(api.lic["OLD:01"].plan, "lifetime");
    assert.equal(api.lic["NEW:02"].plan, "trial");
    assert.equal(api.records.length, 0);
  } finally { api.close(); }
});

test("ordem: vitalício > vencimento mais longe > mais perto > teste > expirado", () => {
  const now = Date.now();
  const life = licenseRank({ type: "premium", plan: "lifetime", expiresAt: null }, now);
  const far = licenseRank({ type: "premium", plan: "annual", expiresAt: iso(300 * day) }, now);
  const near = licenseRank({ type: "premium", plan: "annual", expiresAt: iso(10 * day) }, now);
  const trialLong = licenseRank({ type: "trial", plan: "trial", expiresAt: iso(400 * day) }, now);
  const dead = licenseRank({ type: "premium", plan: "annual", expiresAt: iso(-day) }, now);
  assert.ok(life > far && far > near && near > trialLong && trialLong > dead);
  assert.equal(licenseRank({ type: "expired", expiresAt: null }, now), 0);
  // antigo expirado não sobrescreve o teste do novo
  const p = pickTransferLicense({ type: "premium", plan: "annual", expiresAt: iso(-day) }, { type: "trial", plan: "trial", expiresAt: iso(day) });
  assert.equal(p.moved, false);
  assert.equal(p.license?.plan, "trial");
});
