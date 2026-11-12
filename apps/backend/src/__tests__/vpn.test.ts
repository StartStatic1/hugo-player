import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { mkdtempSync, statSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import express from "express";
import { createVpnAdminRouter, createVpnRouter, createVpnStore, validateWgConf } from "../vpn.js";

// chaves de exemplo (formato válido, não são de nenhum servidor)
const CONF = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.2.0.2/32
DNS = 10.2.0.1

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 0.0.0.0/0
Endpoint = 203.0.113.10:51820
`;

test("validateWgConf aceita wg-quick e recusa incompleto", () => {
  assert.deepEqual(validateWgConf(CONF), { ok: true, peers: 1, endpointHost: "203.0.113.10" });
  assert.equal(validateWgConf("").ok, false);
  assert.equal(validateWgConf(CONF.replace(/PrivateKey.*\n/, "")).ok, false);
  assert.equal(validateWgConf(CONF.replace(/Endpoint.*\n/, "")).ok, false);
  assert.equal(validateWgConf("[Interface]\nPrivateKey = abc\nAddress = 1.2.3.4/32\n").ok, false);
});

async function start() {
  const dir = mkdtempSync(path.join(tmpdir(), "vpn-"));
  const store = createVpnStore(path.join(dir, "vpn"));
  await store.load();
  const app = express();
  app.use(express.json());
  app.use("/admin/vpn", createVpnAdminRouter({ store, adminToken: "ADM" }));
  app.use("/vpn", createVpnRouter({
    store, adminToken: "ADM",
    authorize: (id, key) => id === "AA" && key === "K" ? { deviceId: "AA", status: "ok" } : id === "EX" && key === "K" ? { deviceId: "EX", status: "inactive" } : null,
  }));
  const server = app.listen(0);
  await new Promise((r) => server.once("listening", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return { dir, store, base, close: () => server.close() };
}

test("admin envia/remove config; app só recebe com licença ativa; ETag", async () => {
  const s = await start();
  try {
    const adm = (method: string, p: string, body?: unknown, token = "ADM") =>
      fetch(s.base + p, { method, headers: { "content-type": "application/json", "x-admin-token": token }, body: body ? JSON.stringify(body) : undefined });
    assert.equal((await adm("GET", "/admin/vpn", undefined, "x")).status, 401);
    assert.equal((await adm("PUT", "/admin/vpn/US", { config: "lixo" })).status, 400);
    const put = await adm("PUT", "/admin/vpn/us", { config: CONF });
    assert.equal(put.status, 200);
    const pj = await put.json() as any;
    assert.equal(pj.config.country, "US");
    assert.ok(!JSON.stringify(pj).includes("PrivateKey"), "admin nunca vê o conteúdo");
    const file = path.join(s.dir, "vpn", "US.conf");
    assert.equal(statSync(file).mode & 0o777, 0o600);
    assert.equal(statSync(path.join(s.dir, "vpn")).mode & 0o777, 0o700);
    assert.equal(readFileSync(file, "utf8"), CONF);

    const dev = (p: string, id?: string, key?: string, h: Record<string, string> = {}) =>
      fetch(s.base + p, { headers: { ...(id ? { "x-device-id": id } : {}), ...(key ? { "x-device-key": key } : {}), ...h } });
    assert.equal((await dev("/vpn/config/US")).status, 401);
    assert.equal((await dev("/vpn/config/US", "AA", "errada")).status, 401);
    assert.equal((await dev("/vpn/config/US", "EX", "K")).status, 403);
    assert.equal((await dev("/vpn/countries", "EX", "K")).status, 403);
    const ok = await dev("/vpn/config/US", "AA", "K");
    assert.equal(ok.status, 200);
    const oj = await ok.json() as any;
    assert.equal(oj.config, CONF);
    const etag = ok.headers.get("etag")!;
    assert.equal(etag, `"${oj.hash}"`);
    assert.equal((await dev("/vpn/config/US", "AA", "K", { "if-none-match": etag })).status, 304);
    assert.equal((await dev("/vpn/config/BR", "AA", "K")).status, 404);
    assert.equal((await dev("/vpn/config/USA", "AA", "K")).status, 400);
    const c = await (await dev("/vpn/countries", "AA", "K")).json() as any;
    assert.deepEqual(c.countries, [{ country: "US", hash: oj.hash }]);

    // recarregar do disco mantém
    const again = createVpnStore(path.join(s.dir, "vpn"));
    await again.load();
    assert.equal(again.get("US")?.meta.hash, oj.hash);

    // regra por host
    assert.equal((await adm("POST", "/admin/vpn/hosts", { host: "http://Prov.Example.com:8080/get.php", country: "us" })).status, 200);
    assert.equal(s.store.countryForUrl("http://prov.example.com:8080/get.php?username=a"), "US");
    assert.equal(s.store.countryForUrl("http://cdn.prov.example.com/x"), "US");
    assert.equal(s.store.countryForUrl("http://other.com/x"), undefined);
    await adm("POST", "/admin/vpn/hosts", { host: "prov.example.com", country: null });
    assert.equal(s.store.countryForUrl("http://prov.example.com/x"), undefined);

    assert.equal((await adm("DELETE", "/admin/vpn/US")).status, 200);
    assert.equal((await dev("/vpn/config/US", "AA", "K")).status, 404);
  } finally {
    s.close();
  }
});
