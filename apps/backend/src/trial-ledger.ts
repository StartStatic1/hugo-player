import { createHash } from "node:crypto";

/**
 * Teste grátis uma vez por aparelho físico, pelo ANDROID_ID (SSAID) do app.
 * O SSAID sobrevive a desinstalar/limpar dados (só muda com reset de fábrica).
 * Guardamos só um hash do ANDROID_ID, nunca o valor puro.
 */

/** Valores conhecidos como falsos/repetidos (bug de aparelhos antigos/emuladores). */
const BOGUS = new Set(["9774d56d682e549c", "0000000000000000", "unknown", "null", "android_id"]);

export interface TrialLedgerEntry {
  /** primeiro Device ID que usou o teste com este aparelho */
  deviceId: string;
  at: string;
  source: "register" | "backfill";
}
export type TrialLedger = Record<string, TrialLedgerEntry>;

/** Motivo de marcar um aparelho para revisão (teste liberado sem conferir o ANDROID_ID). */
export type TrialFlag = "no_android_id" | "invalid_android_id" | "old_app";

export type TrialDecision =
  | { kind: "granted"; hash: string }
  | { kind: "denied"; hash: string; firstDeviceId: string }
  | { kind: "unverified"; flag: TrialFlag };

export function normalizeAndroidId(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const v = raw.trim().toLowerCase();
  if (!v || BOGUS.has(v)) return null;
  if (!/^[0-9a-f]{8,64}$/.test(v)) return null;
  if (/^(.)\1+$/.test(v)) return null; // tudo igual (000…, fff…)
  return v;
}

export function hashAndroidId(id: string): string {
  return createHash("sha256").update(`hugo-ssaid:v1:${id}`).digest("hex");
}

/**
 * Decide o teste de um aparelho NOVO.
 * `raw` undefined = app antigo (não manda o campo); null/""/inválido = app novo sem ANDROID_ID usável.
 * Sem ANDROID_ID usável o teste é liberado (não travar cliente real), mas fica marcado para revisão.
 */
export function decideTrial(raw: unknown, ledger: TrialLedger): TrialDecision {
  if (raw === undefined) return { kind: "unverified", flag: "old_app" };
  const id = normalizeAndroidId(raw);
  if (!id) return { kind: "unverified", flag: raw === null || (typeof raw === "string" && raw.trim() === "") ? "no_android_id" : "invalid_android_id" };
  const hash = hashAndroidId(id);
  const used = ledger[hash];
  return used ? { kind: "denied", hash, firstDeviceId: used.deviceId } : { kind: "granted", hash };
}

export function recordTrial(ledger: TrialLedger, hash: string, deviceId: string, at: Date, source: TrialLedgerEntry["source"]): void {
  if (!ledger[hash]) ledger[hash] = { deviceId, at: at.toISOString(), source };
}

interface LicenseLike { type: string; plan?: string; expiresAt: string | null }
export interface TrialDevice {
  deviceId: string;
  license: LicenseLike;
  androidIdHash?: string;
  trialFlag?: TrialFlag;
  trialDenied?: { reason: "trial_already_used"; firstDeviceId: string; at: string };
}

/** Por que o teste ficou pendente (sem acesso até resolver ou o admin liberar). */
export type TrialPendingReason = "update_required" | "app_first_use" | "no_android_id" | "ip_limit";

/** Regras extras do teste: sem ANDROID_ID só libera em TV/web (allowUnverified); IP no limite → pendente. */
export interface TrialPolicy {
  /** libera teste sem ANDROID_ID (Smart TV Samsung/LG/web não têm ANDROID_ID). Padrão: false. */
  allowUnverified?: boolean;
  /** este IP já atingiu o limite de aparelhos/testes nas últimas 24 h */
  ipLimited?: boolean;
}

/** Licença inicial de um aparelho novo conforme a decisão. Não mexe em nada já existente. */
export function newDeviceTrial(
  decision: TrialDecision,
  deviceId: string,
  now: Date,
  trialDays: number,
  ledger: TrialLedger,
  policy: TrialPolicy = {},
): Pick<TrialDevice, "license" | "androidIdHash" | "trialFlag" | "trialDenied"> & { trialPending?: TrialPendingReason } {
  const expired = { type: "expired", plan: "trial", expiresAt: now.toISOString() };
  if (decision.kind === "denied") {
    return {
      license: expired,
      androidIdHash: decision.hash,
      trialDenied: { reason: "trial_already_used", firstDeviceId: decision.firstDeviceId, at: now.toISOString() },
    };
  }
  // sem ANDROID_ID usável (app novo sem ID, ID falso ou app antigo): sem teste — fica pendente
  if (decision.kind === "unverified" && !policy.allowUnverified) {
    return { license: expired, trialFlag: decision.flag, trialPending: decision.flag === "old_app" ? "update_required" : "no_android_id" };
  }
  // IP no limite (aparelhos/testes em 24 h): pendente; o teste sai num registro depois de 24 h ou pelo admin
  if (policy.ipLimited) {
    return { license: expired, ...(decision.kind === "granted" ? { androidIdHash: decision.hash } : { trialFlag: decision.flag }), trialPending: "ip_limit" };
  }
  const license = { type: "trial", plan: "trial", expiresAt: new Date(now.getTime() + trialDays * 86400000).toISOString() };
  if (decision.kind === "granted") {
    recordTrial(ledger, decision.hash, deviceId, now, "register");
    return { license, androidIdHash: decision.hash };
  }
  return { license, trialFlag: decision.flag };
}

/**
 * Aparelho que JÁ existe mandando o ANDROID_ID (ex.: app atualizado). Só anota o hash;
 * nunca muda a licença. Se ele é/foi um teste, entra no registro para que uma
 * reinstalação futura não ganhe outro teste.
 */
export function backfillExisting(device: TrialDevice, raw: unknown, ledger: TrialLedger, now: Date): boolean {
  if (device.androidIdHash) return false;
  const id = normalizeAndroidId(raw);
  if (!id) return false;
  const hash = hashAndroidId(id);
  device.androidIdHash = hash;
  const wasTrial = (device.license.plan ?? "trial") === "trial" && device.license.type !== "premium";
  if (wasTrial) recordTrial(ledger, hash, device.deviceId, now, "backfill");
  if (device.trialFlag) delete device.trialFlag;
  return true;
}

/**
 * Aparelho novo vindo de app antigo (sem ANDROID_ID) enquanto a atualização é obrigatória:
 * o teste fica "pendente" (sem acesso) até o app atualizado mandar o ANDROID_ID.
 */
export function pendingOldAppTrial(now: Date): { license: LicenseLike; trialPending: "update_required" } {
  return { license: { type: "expired", plan: "trial", expiresAt: now.toISOString() }, trialPending: "update_required" };
}

/** Aparelho criado pelo site (antes de abrir o app): sem licença até o app decidir o teste ou um plano ser pago. */
export function webPendingTrial(now: Date): { license: LicenseLike; trialPending: "app_first_use" } {
  return { license: { type: "expired", plan: "trial", expiresAt: now.toISOString() }, trialPending: "app_first_use" };
}

/** Aparelho com teste pendente se registrou de novo: decide o teste agora (com as mesmas regras). */
export function resolvePendingTrial(
  device: TrialDevice & { trialPending?: string },
  raw: unknown,
  ledger: TrialLedger,
  now: Date,
  trialDays: number,
  policy: TrialPolicy = {},
): boolean {
  if (!device.trialPending) return false;
  if (device.license.type === "premium") { delete device.trialPending; return true; }
  // app antigo (sem o campo) só resolve em TV/web
  if (raw === undefined && !policy.allowUnverified) return false;
  const r = newDeviceTrial(decideTrial(raw, ledger), device.deviceId, now, trialDays, ledger, policy);
  if (r.trialPending) {
    device.trialPending = r.trialPending;
    if (r.androidIdHash) device.androidIdHash = r.androidIdHash;
    if (r.trialFlag) device.trialFlag = r.trialFlag;
    if (r.trialDenied) { device.trialDenied = r.trialDenied; delete device.trialPending; }
    return true;
  }
  device.license = { ...device.license, ...r.license };
  if (r.androidIdHash) device.androidIdHash = r.androidIdHash;
  if (r.trialFlag) device.trialFlag = r.trialFlag; else delete device.trialFlag;
  if (r.trialDenied) device.trialDenied = r.trialDenied;
  delete device.trialPending;
  return true;
}
