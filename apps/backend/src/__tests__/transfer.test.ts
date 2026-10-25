import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import express from "express";
import { createTransferRouter } from "../routes-transfer.js";
import type { StoredPlaylist } from "../routes-playlists.js";

const keys: Record<string, string> = { "AA:00:00:00:00:01": "OLDKEY1", "BB:00:00:00:00:02": "NEWKEY2", "CC:00:00:00:00:03": "OTHER33" };

async function start(limit = 100) {
  const store = [
    { id: "1", deviceId: "AA:00:00:00:00:01", name: "A", type: "m3u", url: "http://a.test/1.m3u", epgUrl: "http://a.test/epg.xml.gz", pinHash: "h", pinSalt: "s", createdAt: "x", updatedAt: "x" },
    { id: "2", deviceId: "AA:00:00:00:00:01", name: "B", type: "m3u", url: "http://a.test/2.m3u", createdAt: "x", updatedAt: "x" },
    { id: "3", deviceId: "CC:00:00:00:00:03", name: "C", type: "m3u", url: "http://a.test/3.m3u", createdAt: "x", updatedAt: "x" },
  ] as unknown as StoredPlaylist[];
  let writes = 0;
  const app = express();
  app.use(express.json());
  app.use("/devices", createTransferRouter({
    playlists: () => store,
    persist: async () => { writes++; },
    verify: (id, key) => (keys[id.toUpperCase()] === key.toUpperCase() ? id.toUpperCase() : null),
    failedAttemptsPer15Min: limit,
  }));
  const server = app.listen(0);
  await new Promise((r) => server.once("listening", r));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/devices/transfer`;
  const post = async (body: unknown) => {
    const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    return { status: res.status, json: (await res.json()) as any };
  };
  return { post, store, writes: () => writes, close: () => server.close() };
}

test("transfere playlists (com EPG e PIN) só com as duas Keys certas", async () => {
  const api = await start();
  try {
    assert.equal((await api.post({ fromId: "AA:00:00:00:00:01", fromKey: "errada", toId: "BB:00:00:00:00:02", toKey: "NEWKEY2" })).status, 401);
    assert.equal((await api.post({ fromId: "AA:00:00:00:00:01", fromKey: "OLDKEY1", toId: "BB:00:00:00:00:02", toKey: "errada" })).status, 401);
    assert.equal((await api.post({ fromId: "AA:00:00:00:00:01", fromKey: "oldkey1", toId: "aa:00:00:00:00:01", toKey: "OLDKEY1" })).status, 400);
    assert.equal(api.writes(), 0);
    const ok = await api.post({ fromId: "aa:00:00:00:00:01", fromKey: "oldkey1", toId: "BB:00:00:00:00:02", toKey: "NEWKEY2" });
    assert.equal(ok.status, 200);
    assert.equal(ok.json.moved, 2);
    const moved = api.store.filter((p) => p.deviceId === "BB:00:00:00:00:02");
    assert.equal(moved.length, 2);
    assert.equal(moved[0].epgUrl, "http://a.test/epg.xml.gz");
    assert.equal(moved[0].pinHash, "h");
    // outro aparelho intocado
    assert.equal(api.store.find((p) => p.id === "3")!.deviceId, "CC:00:00:00:00:03");
    // de novo: nada a mover
    assert.equal((await api.post({ fromId: "AA:00:00:00:00:01", fromKey: "OLDKEY1", toId: "BB:00:00:00:00:02", toKey: "NEWKEY2" })).json.moved, 0);
  } finally {
    api.close();
  }
});

test("limita tentativas erradas", async () => {
  const api = await start(3);
  try {
    const bad = { fromId: "AA:00:00:00:00:01", fromKey: "nope", toId: "BB:00:00:00:00:02", toKey: "NEWKEY2" };
    for (let i = 0; i < 3; i++) assert.equal((await api.post(bad)).status, 401);
    assert.equal((await api.post(bad)).status, 429);
  } finally {
    api.close();
  }
});
