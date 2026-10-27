import { test } from "node:test";
import assert from "node:assert/strict";
import { backfillExisting, decideTrial, hashAndroidId, newDeviceTrial, normalizeAndroidId, type TrialDevice, type TrialLedger } from "../trial-ledger.js";

const now = new Date("2026-10-26T12:00:00.000Z");
const SSAID = "3f2a9c41b7d0e8a5";

test("ANDROID_ID novo: teste liberado e registrado", () => {
  const ledger: TrialLedger = {};
  const d = decideTrial(SSAID, ledger);
  assert.equal(d.kind, "granted");
  const r = newDeviceTrial(d, "02:AA:00:00:00:01", now, 7, ledger);
  assert.equal(r.license.type, "trial");
  assert.equal(r.license.expiresAt, "2026-11-02T12:00:00.000Z");
  assert.equal(ledger[hashAndroidId(SSAID)].deviceId, "02:AA:00:00:00:01");
  assert.equal(r.trialFlag, undefined);
});

test("mesmo ANDROID_ID depois de reinstalar (ID/Key novos): teste recusado", () => {
  const ledger: TrialLedger = {};
  newDeviceTrial(decideTrial(SSAID, ledger), "02:AA:00:00:00:01", now, 7, ledger);
  const d = decideTrial(SSAID.toUpperCase(), ledger);
  assert.equal(d.kind, "denied");
  const r = newDeviceTrial(d, "02:BB:00:00:00:02", now, 7, ledger);
  assert.equal(r.license.type, "expired");
  assert.equal(r.trialDenied?.reason, "trial_already_used");
  assert.equal(r.trialDenied?.firstDeviceId, "02:AA:00:00:00:01");
  assert.equal(Object.keys(ledger).length, 1); // o registro não muda
});

test("ANDROID_ID nulo/falso: libera o teste (não trava cliente real) e marca para revisão", () => {
  for (const [raw, flag] of [[null, "no_android_id"], ["", "no_android_id"], ["9774d56d682e549c", "invalid_android_id"], ["0000000000000000", "invalid_android_id"], [undefined, "old_app"]] as const) {
    const ledger: TrialLedger = {};
    const d = decideTrial(raw, ledger);
    assert.equal(d.kind, "unverified");
    const r = newDeviceTrial(d, "02:CC:00:00:00:03", now, 7, ledger);
    assert.equal(r.license.type, "trial");
    assert.equal(r.trialFlag, flag);
    assert.equal(Object.keys(ledger).length, 0);
  }
  assert.equal(normalizeAndroidId("9774D56D682E549C"), null);
});

test("aparelho pago não muda; testes antigos continuam valendo e entram no registro", () => {
  const ledger: TrialLedger = {};
  const paid: TrialDevice = { deviceId: "02:DD:00:00:00:04", license: { type: "premium", plan: "lifetime", expiresAt: null } };
  assert.equal(backfillExisting(paid, "aaaabbbbccccdddd", ledger, now), true);
  assert.deepEqual(paid.license, { type: "premium", plan: "lifetime", expiresAt: null });
  assert.equal(Object.keys(ledger).length, 0); // pago não "gasta" teste

  const oldTrial: TrialDevice = { deviceId: "02:EE:00:00:00:05", license: { type: "trial", plan: "trial", expiresAt: "2026-10-30T00:00:00.000Z" }, trialFlag: "old_app" };
  backfillExisting(oldTrial, SSAID, ledger, now);
  assert.deepEqual(oldTrial.license, { type: "trial", plan: "trial", expiresAt: "2026-10-30T00:00:00.000Z" });
  assert.equal(oldTrial.trialFlag, undefined);
  // reinstalação desse aparelho agora não ganha outro teste
  assert.equal(decideTrial(SSAID, ledger).kind, "denied");
  // aparelho pago com o mesmo ANDROID_ID de quem já usou teste: continua pago
  assert.equal(paid.license.type, "premium");
});

test("app antigo com atualização obrigatória: teste pendente até mandar o ANDROID_ID", async () => {
  const { pendingOldAppTrial, resolvePendingTrial } = await import("../trial-ledger.js");
  const ledger: TrialLedger = {};
  // 1º aparelho: app antigo → pendente, sem acesso
  const p = pendingOldAppTrial(now);
  const dev: TrialDevice & { trialPending?: string } = { deviceId: "02:FF:00:00:00:06", ...p };
  assert.equal(dev.license.type, "expired");
  // atualizou e mandou ANDROID_ID novo → ganha o teste
  assert.equal(resolvePendingTrial(dev, SSAID, ledger, now, 7), true);
  assert.equal(dev.license.type, "trial");
  assert.equal(dev.trialPending, undefined);
  // outro aparelho pendente com o MESMO ANDROID_ID → recusado
  const dev2: TrialDevice & { trialPending?: string } = { deviceId: "02:FF:00:00:00:07", ...pendingOldAppTrial(now) };
  resolvePendingTrial(dev2, SSAID, ledger, now, 7);
  assert.equal(dev2.license.type, "expired");
  assert.equal(dev2.trialDenied?.reason, "trial_already_used");
  // sem o campo (ainda app antigo) → continua pendente
  const dev3: TrialDevice & { trialPending?: string } = { deviceId: "02:FF:00:00:00:08", ...pendingOldAppTrial(now) };
  assert.equal(resolvePendingTrial(dev3, undefined, ledger, now, 7), false);
  assert.equal(dev3.trialPending, "update_required");
});

test("cadastro pelo site: sem teste; o teste nasce no 1º uso do app (com trava por ANDROID_ID)", async () => {
  const { webPendingTrial, resolvePendingTrial, hashAndroidId } = await import("../trial-ledger.js");
  const now = new Date("2026-10-27T12:00:00Z");
  const ledger: Record<string, any> = {};
  const web: any = { deviceId: "AA:BB:CC:DD:EE:01", ...webPendingTrial(now) };
  assert.equal(web.license.type, "expired");
  assert.equal(web.trialPending, "app_first_use");
  // site de novo (sem ANDROID_ID): continua sem teste
  assert.equal(resolvePendingTrial(web, undefined, ledger, now, 7), false);
  assert.equal(web.license.type, "expired");
  // app abre com ANDROID_ID novo: ganha o teste
  assert.equal(resolvePendingTrial(web, "1a2b3c4d5e6f7a8b", ledger, now, 7), true);
  assert.equal(web.license.type, "trial");
  assert.equal(web.trialPending, undefined);
  // outro cadastro pelo site aberto no MESMO aparelho físico: teste negado
  ledger[hashAndroidId("1a2b3c4d5e6f7a8b")] ??= { deviceId: web.deviceId, at: now.toISOString(), source: "register" };
  const web2: any = { deviceId: "AA:BB:CC:DD:EE:02", ...webPendingTrial(now) };
  resolvePendingTrial(web2, "1a2b3c4d5e6f7a8b", ledger, now, 7);
  assert.equal(web2.license.type, "expired");
  assert.ok(web2.trialDenied);
});
