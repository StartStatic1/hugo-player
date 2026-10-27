import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanSupportUrl, supportWhatsappUrl, DEFAULT_SUPPORT_WHATSAPP_URL } from "../support.js";

test("link de suporte: só WhatsApp https, sem rastreio", () => {
  assert.equal(cleanSupportUrl("https://chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P?s=cl&p=a&mlu=4"), "https://chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P");
  assert.equal(cleanSupportUrl("https://wa.me/5511999999999"), "https://wa.me/5511999999999");
  assert.equal(cleanSupportUrl("http://chat.whatsapp.com/x"), null);
  assert.equal(cleanSupportUrl("https://evil.example/x"), null);
  assert.equal(cleanSupportUrl(""), null);
  const old = process.env.SUPPORT_WHATSAPP_URL;
  delete process.env.SUPPORT_WHATSAPP_URL;
  assert.equal(supportWhatsappUrl(), DEFAULT_SUPPORT_WHATSAPP_URL);
  process.env.SUPPORT_WHATSAPP_URL = "lixo";
  assert.equal(supportWhatsappUrl(), DEFAULT_SUPPORT_WHATSAPP_URL);
  if (old === undefined) delete process.env.SUPPORT_WHATSAPP_URL; else process.env.SUPPORT_WHATSAPP_URL = old;
});
