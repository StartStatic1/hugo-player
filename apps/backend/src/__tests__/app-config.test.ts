import { test } from "node:test";
import assert from "node:assert/strict";
import { clientVersionCode, normalizeConfig, updateRequired } from "../app-config.js";

test("versão mínima: só bloqueia quando sabemos que o app é mais antigo", () => {
  assert.equal(updateRequired(43, 44), true);
  assert.equal(updateRequired(44, 44), false);
  assert.equal(updateRequired(50, 44), false);
  assert.equal(updateRequired(null, 44), false); // navegador/desconhecido
  assert.equal(updateRequired(10, 0), false); // sem mínima
});

test("versão do app pela requisição", () => {
  assert.equal(clientVersionCode("44", "Dalvik/2.1.0 (Linux; U; Android 10)"), 44);
  assert.equal(clientVersionCode(undefined, "Dalvik/2.1.0 (Linux; U; Android 10)"), 0); // app antigo
  assert.equal(clientVersionCode(undefined, "Mozilla/5.0 (Windows NT 10.0)"), null); // site
  assert.equal(clientVersionCode("abc", "okhttp/4.12.0"), null);
});

test("config: valor salvo > env > 0, sempre dentro do limite", () => {
  assert.equal(normalizeConfig({ minVersionCode: 44 }, "10").minVersionCode, 44);
  assert.equal(normalizeConfig(undefined, "12").minVersionCode, 12);
  assert.equal(normalizeConfig({ minVersionCode: -5 }).minVersionCode, 0);
  assert.equal(normalizeConfig(null).minVersionCode, 0);
});

test("portal/admin no navegador nunca é bloqueado, nem em WebView Android", () => {
  assert.equal(clientVersionCode(undefined, "Mozilla/5.0 (Linux; Android 12; wv) AppleWebKit", "https://hugo.streamflixvip.online"), null);
  assert.equal(clientVersionCode(undefined, "Dalvik/2.1.0 (Linux; U; Android 12)", "https://hugo.streamflixvip.online/"), null);
  assert.equal(updateRequired(clientVersionCode(undefined, "Mozilla/5.0", undefined), 46), false);
  assert.equal(updateRequired(clientVersionCode(undefined, "Dalvik/2.1.0", undefined), 46), true); // app antigo de verdade
});
