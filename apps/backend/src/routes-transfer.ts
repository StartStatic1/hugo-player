import { Router } from "express";
import rateLimit from "express-rate-limit";
import { z } from "zod";
import type { StoredPlaylist } from "./routes-playlists.js";

/**
 * Transferir playlists E licença de um aparelho antigo (ID + Key) para o novo (ID + Key).
 * Ex.: o app foi reinstalado e gerou ID/Key novos. As duas Keys precisam estar certas.
 * - Playlists são MOVIDAS (com link do EPG e PIN).
 * - Licença: o novo fica com a MELHOR das duas (vitalício > vencimento mais longe > mais perto > teste);
 *   o antigo fica expirado (cadastro mantido), para uma licença não valer em dois aparelhos.
 * - Registro da transferência nos dois aparelhos e no histórico (admin).
 */
export interface TransferLicense {
  type: "trial" | "premium" | "expired";
  plan?: "trial" | "semiannual" | "annual" | "lifetime";
  expiresAt: string | null;
  paymentProvider?: string;
  transactionNsu?: string;
}

export interface TransferRecord {
  from: string;
  to: string;
  at: string;
  playlists: number;
  license: "moved" | "kept_new";
  plan: string;
}

export interface TransferLicenseDeps {
  get: (deviceId: string) => TransferLicense | undefined;
  set: (deviceId: string, license: TransferLicense) => void;
  /** grava from/to/at nos aparelhos e no histórico */
  record: (rec: TransferRecord) => void;
}

export interface TransferDeps {
  /** licenças dos aparelhos (se ausente, só move playlists) */
  licenses?: TransferLicenseDeps;
  playlists: () => StoredPlaylist[];
  persist: () => Promise<void>;
  /** ID normalizado do aparelho se ID + Key conferem (mesmo sem licença ativa), senão null */
  verify: (id: string, key: string) => string | null;
  /** tentativas ERRADAS por IP a cada 15 min */
  failedAttemptsPer15Min?: number;
}

const schema = z.object({
  fromId: z.string().trim().min(4).max(128),
  fromKey: z.string().trim().min(4).max(64),
  toId: z.string().trim().min(4).max(128),
  toKey: z.string().trim().min(4).max(64),
});

/** Força de uma licença: 0 = sem valor; teste < pago com vencimento (pela data) < vitalício. */
export function licenseRank(l: TransferLicense | undefined, now = Date.now()): number {
  if (!l || l.type === "expired") return 0;
  const exp = l.expiresAt ? new Date(l.expiresAt).getTime() : null;
  if (exp !== null && (!Number.isFinite(exp) || exp < now)) return 0;
  if (l.type === "premium") {
    if (l.plan === "lifetime" || exp === null) return Number.MAX_SAFE_INTEGER;
    return 2e13 + exp; // qualquer pago vence qualquer teste
  }
  return 1e13 + (exp ?? now); // teste
}

/** Licença que o aparelho NOVO deve ficar (a melhor) e se veio do antigo. */
export function pickTransferLicense(from: TransferLicense | undefined, to: TransferLicense | undefined, now = Date.now()) {
  const rf = licenseRank(from, now), rt = licenseRank(to, now);
  if (rf > rt && from) return { license: { ...from }, moved: true as const };
  return { license: to ? { ...to } : undefined, moved: false as const };
}

export function expiredByTransfer(now = new Date()): TransferLicense {
  return { type: "expired", plan: "trial", expiresAt: now.toISOString(), paymentProvider: "transferred" };
}

export function movePlaylists(list: StoredPlaylist[], fromId: string, toId: string): number {
  let moved = 0;
  const now = new Date().toISOString();
  for (const p of list) {
    if (p.deviceId.toUpperCase() === fromId.toUpperCase()) {
      p.deviceId = toId;
      p.updatedAt = now;
      moved++;
    }
  }
  return moved;
}

export function createTransferRouter(deps: TransferDeps): Router {
  const r = Router();
  const limiter = rateLimit({
    windowMs: 15 * 60_000,
    limit: deps.failedAttemptsPer15Min ?? 8,
    skipSuccessfulRequests: true,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: "too_many_attempts" },
  });

  r.post("/transfer", limiter, async (req, res) => {
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error" });
    const { fromId, fromKey, toId, toKey } = parsed.data;
    const from = deps.verify(fromId, fromKey);
    const to = deps.verify(toId, toKey);
    // mesma resposta para ID inexistente ou Key errada (não revela quais IDs existem)
    if (!from) return res.status(401).json({ error: "invalid_old_device" });
    if (!to) return res.status(401).json({ error: "invalid_new_device" });
    if (from === to) return res.status(400).json({ error: "same_device" });
    const moved = movePlaylists(deps.playlists(), from, to);
    let license: "moved" | "kept_new" | "none" = "none";
    let plan = "";
    if (deps.licenses) {
      const L = deps.licenses;
      const nowDate = new Date();
      const pick = pickTransferLicense(L.get(from), L.get(to), nowDate.getTime());
      if (pick.license) L.set(to, pick.license);
      // o antigo perde a licença (cadastro fica) — uma licença não vale em dois aparelhos
      L.set(from, expiredByTransfer(nowDate));
      license = pick.moved ? "moved" : "kept_new";
      plan = pick.license?.plan ?? pick.license?.type ?? "";
      L.record({ from, to, at: nowDate.toISOString(), playlists: moved, license, plan });
    }
    if (moved > 0 || deps.licenses) await deps.persist();
    console.log(`transfer: ${moved} playlist(s), license=${license} ${from} -> ${to}`);
    res.json({ ok: true, moved, license, plan, from, to });
  });
  return r;
}
