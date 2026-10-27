import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import express from "express";
import { buildPlaylistUrl, checkPin, createPlaylistRouter, hashPin, type StoredPlaylist } from "../routes-playlists.js";

async function startApi() {
  let store: StoredPlaylist[] = [];
  let writes = 0;
  const app = express();
  app.use(express.json());
  app.use("/playlists", createPlaylistRouter({
    playlists: () => store,
    setPlaylists: next => { store = next; },
    persist: async () => { writes++; },
    authenticate: (id, key) => {
      const ok: Record<string, string> = { "AA:11:22:33:44:55": "KEY1234", "BB:00:00:00:00:01": "OTHER99" };
      if (!id || !key) return null;
      return ok[id.toUpperCase()] === key.toUpperCase() ? id.toUpperCase() : null;
    },
    pinAttemptsPerMinute: 1000,
  }));
  const server = app.listen(0);
  await new Promise(r => server.once("listening", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/playlists`;
  const call = async (method: string, path: string, body?: unknown, extra: Record<string, string> = {}, dev = ["AA:11:22:33:44:55", "key1234"]) => {
    const res = await fetch(base + path, {
      method,
      headers: { "content-type": "application/json", "x-device-id": dev[0], "x-device-key": dev[1], ...extra },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: res.status, json: (await res.json()) as any };
  };
  return { call, close: () => server.close(), store: () => store, writes: () => writes };
}

test("hash de PIN não guarda o PIN em texto", () => {
  const { salt, hash } = hashPin("1234");
  assert.notEqual(hash, "1234");
  assert.equal(checkPin("1234", { pinHash: hash, pinSalt: salt }), true);
  assert.equal(checkPin("4321", { pinHash: hash, pinSalt: salt }), false);
  assert.equal(checkPin(undefined, { pinHash: hash, pinSalt: salt }), false);
  assert.equal(checkPin(undefined, {}), true);
});

test("URL Xtream vira get.php m3u_plus; M3U é mantida", () => {
  const x = buildPlaylistUrl({ type: "xtream", url: "srv.test:8080", username: "u", password: "p" });
  assert.equal(x, "http://srv.test:8080/get.php?username=u&password=p&type=m3u_plus&output=m3u8");
  assert.equal(buildPlaylistUrl({ type: "m3u", url: "http://a.test/list.m3u?x=1|2" }), "http://a.test/list.m3u?x=1|2");
  assert.throws(() => buildPlaylistUrl({ url: "ftp://a.test/x" }));
});

test("CRUD completo + PIN", async () => {
  const api = await startApi();
  try {
    assert.equal((await api.call("GET", "/", undefined, {}, ["AA:11:22:33:44:55", "errada"])).status, 401);

    const a = await api.call("POST", "/", { name: "Principal", url: "http://a.test/list.m3u" });
    assert.equal(a.status, 201);
    const id = a.json.playlist.id as string;
    assert.equal(a.json.playlist.protected, false);

    const b = await api.call("POST", "/", { name: "Adultos", url: "http://b.test/list.m3u", pin: "4321" });
    assert.equal(b.status, 201);
    const pid = b.json.playlist.id as string;
    assert.equal(api.store().find(p => p.id === pid)?.pinHash?.length, 64);
    assert.equal(JSON.stringify(api.store()).includes("4321"), false);

    // Listagem esconde a URL da protegida
    let list = await api.call("GET", "/");
    assert.equal(list.json.playlists.length, 2);
    const prot = list.json.playlists.find((p: any) => p.id === pid);
    assert.equal(prot.protected, true);
    assert.equal(prot.url, null);
    assert.equal(list.json.playlists.find((p: any) => p.id === id).url, "http://a.test/list.m3u");

    // Verificação de PIN
    assert.equal((await api.call("POST", `/${pid}/verify-pin`, { pin: "0000" })).status, 403);
    const ok = await api.call("POST", `/${pid}/verify-pin`, { pin: "4321" });
    assert.equal(ok.status, 200);
    assert.equal(ok.json.playlist.url, "http://b.test/list.m3u");

    // Editar nome
    assert.equal((await api.call("PATCH", `/${id}`, { name: "  Casa  " })).json.playlist.name, "Casa");
    assert.equal((await api.call("PATCH", `/${id}`, { name: "" })).status, 400);
    // POST + X-HTTP-Method-Override (app Android)
    assert.equal((await api.call("POST", `/${id}`, { name: "Casa 2" }, { "x-http-method-override": "PATCH" })).json.playlist.name, "Casa 2");
    assert.equal((await api.call("PATCH", `/${id}`, { name: "Casa" })).json.playlist.name, "Casa");
    // Protegida exige PIN para editar
    assert.equal((await api.call("PATCH", `/${pid}`, { name: "X" })).status, 403);
    assert.equal((await api.call("PATCH", `/${pid}`, { name: "X" }, { "x-playlist-pin": "4321" })).status, 200);

    // Definir e remover PIN
    assert.equal((await api.call("PATCH", `/${id}`, { newPin: "12" })).status, 400);
    assert.equal((await api.call("PATCH", `/${id}`, { newPin: "123456" })).json.playlist.protected, true);
    assert.equal((await api.call("PATCH", `/${id}`, { removePin: true }, { "x-playlist-pin": "999999" })).status, 403);
    assert.equal((await api.call("PATCH", `/${id}`, { removePin: true }, { "x-playlist-pin": "123456" })).json.playlist.protected, false);

    // Outro dispositivo não enxerga nem apaga
    assert.equal((await api.call("DELETE", `/${id}`, undefined, {}, ["BB:00:00:00:00:01", "OTHER99"])).status, 404);
    assert.equal((await api.call("GET", "/", undefined, {}, ["BB:00:00:00:00:01", "OTHER99"])).json.playlists.length, 0);

    // Excluir (protegida exige PIN)
    assert.equal((await api.call("DELETE", `/${pid}`)).status, 403);
    assert.equal((await api.call("DELETE", `/${pid}`, undefined, { "x-playlist-pin": "4321" })).status, 200);
    assert.equal((await api.call("DELETE", `/${id}`)).status, 200);
    assert.equal((await api.call("DELETE", `/${id}`)).status, 404);
    list = await api.call("GET", "/");
    assert.equal(list.json.playlists.length, 0);
    assert.ok(api.writes() >= 6);
  } finally {
    api.close();
  }
});

test("EPG opcional por playlist: salvar, validar, trocar e remover", async () => {
  const api = await startApi();
  try {
    // playlist antiga (sem epgUrl) continua igual
    const a = await api.call("POST", "/", { name: "Sem EPG", url: "http://a.test/list.m3u" });
    assert.equal(a.status, 201);
    assert.equal(a.json.playlist.epgUrl, null);
    const id = a.json.playlist.id as string;

    const bad = await api.call("POST", "/", { name: "X", url: "http://a.test/l.m3u", epgUrl: "ftp://epg.test/guide.xml" });
    assert.equal(bad.status, 400);
    assert.equal((await api.call("POST", "/", { name: "X", url: "http://a.test/l.m3u", epgUrl: "nao e url" })).status, 400);

    const b = await api.call("POST", "/", { name: "Com EPG", url: "http://b.test/l.m3u", epgUrl: " https://epg.test/guide.xml.gz " });
    assert.equal(b.status, 201);
    assert.equal(b.json.playlist.epgUrl, "https://epg.test/guide.xml.gz");

    assert.equal((await api.call("PATCH", `/${id}`, { epgUrl: "http://epg.test/g.xml" })).json.playlist.epgUrl, "http://epg.test/g.xml");
    assert.equal((await api.call("PATCH", `/${id}`, { epgUrl: "javascript:alert(1)" })).status, 400);
    // nome sem mexer no EPG mantém o link
    assert.equal((await api.call("PATCH", `/${id}`, { name: "Nova" })).json.playlist.epgUrl, "http://epg.test/g.xml");
    assert.equal((await api.call("PATCH", `/${id}`, { epgUrl: "" })).json.playlist.epgUrl, null);
    assert.equal(api.store().find(p => p.id === id)?.epgUrl, undefined);
    assert.equal((await api.call("PATCH", `/${id}`, { epgUrl: null })).status, 200);

    // protegida não mostra o link sem PIN
    const c = await api.call("POST", "/", { name: "P", url: "http://c.test/l.m3u", pin: "1234", epgUrl: "http://epg.test/p.xml" });
    const list = await api.call("GET", "/");
    assert.equal(list.json.playlists.find((p: any) => p.id === c.json.playlist.id).epgUrl, null);
  } finally {
    api.close();
  }
});

test("link digitado na TV: conserta barra invertida e http repetido; host sem ponto é recusado", async () => {
  const { buildPlaylistUrl, cleanPlaylistInput } = await import("../routes-playlists.js");
  assert.equal(cleanPlaylistInput("http://http:\\\\exemplo.com:80/get.php?a=1"), "http://exemplo.com:80/get.php?a=1");
  assert.equal(cleanPlaylistInput("http://http://exemplo.com/x"), "http://exemplo.com/x");
  assert.equal(cleanPlaylistInput("http//exemplo.com/x"), "http://exemplo.com/x");
  assert.equal(cleanPlaylistInput(" HTTPS://exemplo.com/x "), "https://exemplo.com/x");
  assert.equal(cleanPlaylistInput("exemplo.com/x"), "exemplo.com/x");
  assert.equal(buildPlaylistUrl({ type: "m3u", url: "http://http:\\\\exemplo.com:80/get.php?a=1" }), "http://exemplo.com:80/get.php?a=1");
  assert.throws(() => buildPlaylistUrl({ type: "m3u", url: "http://http/get.php" }));
});
