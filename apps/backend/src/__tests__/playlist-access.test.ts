import { test } from "node:test";
import assert from "node:assert/strict";
import { isRegisteredUrl, playlistUrlFor, playlistMatchKey } from "../playlist-access.js";

const pls = [
  { id: "p1", deviceId: "AB:CD", url: "http://srv.example:8080/get.php?username=u1&password=s1&type=m3u_plus&output=ts" },
  { id: "p2", deviceId: "EF:01", url: "https://lista.example/minha.m3u" },
];

test("registered xtream matches regardless of path/params", () => {
  assert.equal(isRegisteredUrl("http://SRV.example:8080/player_api.php?username=u1&password=s1", pls), true);
  assert.equal(isRegisteredUrl("http://srv.example:8080/get.php?username=u1&password=errada", pls), false);
  assert.equal(isRegisteredUrl("http://outro.example/get.php?username=u1&password=s1", pls), false);
});

test("m3u needs exact link", () => {
  assert.equal(isRegisteredUrl("https://lista.example/minha.m3u", pls), true);
  assert.equal(isRegisteredUrl("https://lista.example/outra.m3u", pls), false);
  assert.equal(isRegisteredUrl("", pls), false);
});

test("playlistId only resolves for its own device", () => {
  assert.equal(playlistUrlFor("ab:cd", "p1", pls), pls[0].url);
  assert.equal(playlistUrlFor("EF:01", "p1", pls), null);
  assert.equal(playlistUrlFor("AB:CD", "nope", pls), null);
  assert.ok(playlistMatchKey(pls[0].url).startsWith("xt|"));
});
