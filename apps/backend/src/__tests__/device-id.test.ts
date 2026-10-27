import { test } from "node:test";
import assert from "node:assert/strict";
import { canonicalDeviceId, isAppDeviceId } from "../device-id.js";

test("Device ID digitado no site: corrige O→0, espaços, hífens e minúsculas", () => {
  assert.equal(canonicalDeviceId("F6:55:39:4F:OB:0C"), "F6:55:39:4F:0B:0C");
  assert.equal(canonicalDeviceId(" f6:55:39:4f:0b:0c "), "F6:55:39:4F:0B:0C");
  assert.equal(canonicalDeviceId("3A:95:97:E6:65: 05"), "3A:95:97:E6:65:05");
  assert.equal(canonicalDeviceId("F6-55-39-4F-0B-0C"), "F6:55:39:4F:0B:0C");
  assert.equal(canonicalDeviceId("F655394F0B0C"), "F6:55:39:4F:0B:0C");
  // não dá para adivinhar: fica como está (e o site recusa criar)
  assert.equal(canonicalDeviceId("8A:7C:87:D9:A09:F9"), "8A:7C:87:D9:A09:F9");
  assert.equal(canonicalDeviceId("LUCAS"), "LUCAS");
  assert.equal(isAppDeviceId("F6:55:39:4F:0B:0C"), true);
  assert.equal(isAppDeviceId("F2:04:76:AÍ:12:11"), false);
});
