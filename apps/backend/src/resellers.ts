import { Router, type NextFunction, type Request, type Response } from "express";
import rateLimit from "express-rate-limit";
import { z } from "zod";
import { createHash, randomBytes, randomUUID, scrypt as scryptCb, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
import { PLAN_DAYS, RESELLER_CREDITS_PER_ACTIVATION, RESELLER_PACKS, RESELLER_PLAN, planExpiry, renewalBase } from "./plans.js";

/**
 * Revenda de ativações (plano anual) com créditos.
 * - Revendedor se cadastra → fica "pending" até o admin aprovar. Só "approved" compra/ativa.
 * - Saldo = soma do histórico de movimentos (creditLedger), que só recebe linhas novas (nunca edita/apaga).
 * - Ativar: confere ID + Key do cliente, debita 1 crédito e ativa 1 ano somando aos dias que restam,
 *   tudo no mesmo passo síncrono (sem await entre conferir saldo e gravar) → saldo nunca fica negativo.
 * - Estorno de pacote tira os créditos; se já gastou, o saldo fica negativo e a conta é bloqueada até acertar.
 * - Senhas: scrypt com sal; sessões: token aleatório guardado só como hash, com expiração.
 */

const scrypt = promisify(scryptCb) as (pw: string, salt: string, len: number) => Promise<Buffer>;

export type ResellerStatus = "pending" | "approved" | "rejected" | "blocked";

export interface Reseller {
  id: string;
  name: string;
  email: string;
  whatsapp?: string;
  passwordHash: string;
  passwordSalt: string;
  status: ResellerStatus;
  createdAt: string;
  updatedAt: string;
  approvedAt?: string;
  blockedAt?: string;
  blockedReason?: string;
  /** bloqueado automaticamente por saldo negativo (estorno) */
  negativeBalanceBlock?: boolean;
}

export type LedgerKind = "purchase" | "activation" | "admin_add" | "admin_remove" | "refund" | "undo";

export interface LedgerEntry {
  id: string;
  resellerId: string;
  at: string;
  /** + entra, − sai */
  delta: number;
  kind: LedgerKind;
  reason?: string;
  orderNsu?: string;
  deviceId?: string;
  activationId?: string;
  by: "system" | "admin" | "reseller";
}

export interface ResellerSession {
  tokenHash: string;
  resellerId: string;
  createdAt: string;
  expiresAt: string;
}

export interface CreditOrder {
  orderNsu: string;
  resellerId: string;
  packId: string;
  credits: number;
  amount: number;
  status: "pending" | "paid" | "canceled" | "refunded" | "failed";
  checkoutUrl?: string;
  transactionNsu?: string;
  slug?: string;
  createdAt: string;
  paidAt?: string;
  refundedAt?: string;
}

export interface DeviceLicense {
  type: "trial" | "premium" | "expired";
  plan?: string;
  expiresAt: string | null;
  paymentProvider?: string;
  transactionNsu?: string;
}

export interface ActivatedBy { resellerId: string; activationId: string; at: string; plan: string }

export interface ResellerActivation {
  id: string;
  resellerId: string;
  deviceId: string;
  plan: string;
  at: string;
  kind: "new" | "renewal";
  previousLicense: DeviceLicense;
  newExpiresAt: string | null;
  undoneAt?: string;
  undoReason?: string;
}

export interface ResellerDevice {
  deviceId: string;
  platform: string;
  createdAt: string;
  lastSeenAt?: string;
  license: DeviceLicense;
  blockedAt?: string;
  revokedAt?: string;
  revokedReason?: string;
  transferredTo?: string;
  activatedBy?: ActivatedBy;
}

export interface ResellerStore {
  resellers: Reseller[];
  creditLedger: LedgerEntry[];
  resellerSessions: ResellerSession[];
  creditOrders: CreditOrder[];
  resellerActivations: ResellerActivation[];
}

export function ensureResellerStore(db: Partial<ResellerStore>): ResellerStore {
  db.resellers ??= [];
  db.creditLedger ??= [];
  db.resellerSessions ??= [];
  db.creditOrders ??= [];
  db.resellerActivations ??= [];
  return db as ResellerStore;
}

export interface ResellerDeps {
  store: () => ResellerStore;
  persist: () => Promise<void>;
  /** aparelho pelo ID (sem conferir Key) */
  findDevice: (id: string) => ResellerDevice | undefined;
  /** aparelho só se a Key conferir */
  verifyDevice: (id: string, key: string) => ResellerDevice | null;
  adminToken: string;
  publicWebUrl: string;
  publicApiUrl: string;
  /** cria o link de pagamento (null = pagamento não configurado) */
  createCheckout?: (o: { orderNsu: string; amount: number; description: string; redirectUrl: string; webhookUrl: string }) => Promise<string>;
  /** confere com a InfinitePay se o pedido foi pago com esse valor */
  confirmPayment?: (o: { orderNsu: string; amount: number; transactionNsu: string; slug: string }) => Promise<boolean>;
  now?: () => number;
  /** limites (testes usam valores maiores) */
  limits?: Partial<typeof DEFAULT_LIMITS>;
}

export const DEFAULT_LIMITS = {
  /** tentativas de login erradas por e-mail antes de travar */
  loginFailsBeforeLock: 5,
  loginLockMs: 15 * 60_000,
  loginPerIpPer15Min: 30,
  signupPerIpPerHour: 5,
  /** ativações confirmadas por revendedor por hora */
  activationsPerHour: 30,
  /** ID/Key errados por revendedor antes de travar */
  activationFailsBeforeLock: 10,
  activationLockMs: 15 * 60_000,
  sessionTtlMs: 12 * 60 * 60_000,
  maxSessionsPerReseller: 10,
};

// ---------- regras puras (testadas) ----------

export function balanceOf(ledger: LedgerEntry[], resellerId: string): number {
  let s = 0;
  for (const e of ledger) if (e.resellerId === resellerId) s += e.delta;
  return s;
}

export async function hashPassword(password: string): Promise<{ hash: string; salt: string }> {
  const salt = randomBytes(16).toString("hex");
  const hash = (await scrypt(password, salt, 64)).toString("hex");
  return { hash, salt };
}

export async function verifyPassword(password: string, hash: string, salt: string): Promise<boolean> {
  try {
    const a = await scrypt(password, salt, 64);
    const b = Buffer.from(hash, "hex");
    return a.length === b.length && timingSafeEqual(a, b);
  } catch {
    return false;
  }
}

export function hashToken(token: string): string {
  return createHash("sha256").update(`hugo-reseller-session:${token}`).digest("hex");
}

function safeEqual(a: string, b: string): boolean {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

/** Dados públicos do revendedor (nunca hash/sal). */
export function publicReseller(r: Reseller, balance: number) {
  return {
    id: r.id, name: r.name, email: r.email, whatsapp: r.whatsapp ?? "", status: r.status,
    createdAt: r.createdAt, approvedAt: r.approvedAt, blockedAt: r.blockedAt, blockedReason: r.blockedReason,
    balance,
  };
}

/** Situação da licença do cliente para mostrar no painel. */
export function licenseStatus(l: DeviceLicense, nowMs: number): "active" | "expired" {
  if (l.type === "expired") return "expired";
  if (!l.expiresAt) return "active";
  return new Date(l.expiresAt).getTime() >= nowMs ? "active" : "expired";
}

/** Motivo para não ativar este aparelho (null = pode). */
export function activationBlocker(d: ResellerDevice): string | null {
  if (d.blockedAt) return "device_blocked";
  if (d.revokedAt) return "device_revoked";
  if (d.transferredTo) return "device_transferred";
  if (d.license.type === "premium" && (d.license.plan === "lifetime" || d.license.expiresAt === null)) return "device_lifetime";
  return null;
}

export const BLOCKER_MESSAGES: Record<string, string> = {
  device_blocked: "Este aparelho está bloqueado. Fale com o suporte.",
  device_revoked: "A licença deste aparelho foi revogada. Fale com o suporte.",
  device_transferred: "Este Device ID foi transferido para outro aparelho. Peça ao cliente o ID novo.",
  device_lifetime: "Este aparelho já tem licença vitalícia. Não precisa ativar.",
};

/**
 * Ativa/renova 1 ano debitando 1 crédito. Síncrono de propósito: conferir saldo + debitar + ativar
 * acontecem sem pausa (atômico no Node). Retorna erro ou a ativação feita.
 */
export function applyActivation(
  store: ResellerStore,
  reseller: Reseller,
  device: ResellerDevice,
  nowMs: number,
): { error: string; status: number } | { activation: ResellerActivation; balance: number } {
  if (reseller.status !== "approved") return { error: "reseller_not_approved", status: 403 };
  const blocker = activationBlocker(device);
  if (blocker) return { error: blocker, status: 409 };
  const cost = RESELLER_CREDITS_PER_ACTIVATION;
  const balance = balanceOf(store.creditLedger, reseller.id);
  if (balance < cost) return { error: "insufficient_credits", status: 402 };
  const at = new Date(nowMs).toISOString();
  const previous: DeviceLicense = { ...device.license };
  const newExpiresAt = planExpiry(RESELLER_PLAN, renewalBase(device.license, nowMs));
  const isRenewal = previous.type === "premium" && licenseStatus(previous, nowMs) === "active";
  const activation: ResellerActivation = {
    id: `act-${randomUUID()}`,
    resellerId: reseller.id,
    deviceId: device.deviceId,
    plan: RESELLER_PLAN,
    at,
    kind: isRenewal ? "renewal" : "new",
    previousLicense: previous,
    newExpiresAt,
  };
  store.creditLedger.push({
    id: `led-${randomUUID()}`, resellerId: reseller.id, at, delta: -cost, kind: "activation",
    deviceId: device.deviceId, activationId: activation.id, by: "reseller",
  });
  store.resellerActivations.push(activation);
  device.license = {
    type: "premium",
    plan: RESELLER_PLAN,
    expiresAt: newExpiresAt,
    paymentProvider: "reseller",
    transactionNsu: activation.id,
  };
  device.activatedBy = { resellerId: reseller.id, activationId: activation.id, at, plan: RESELLER_PLAN };
  return { activation, balance: balance - cost };
}

/**
 * Desfaz uma ativação (só admin): devolve o crédito e tira o ano do aparelho.
 * Se a licença ainda é a que essa ativação gerou → volta a anterior; senão tira 365 dias do vencimento atual.
 */
export function undoActivation(
  store: ResellerStore,
  activation: ResellerActivation,
  device: ResellerDevice | undefined,
  reason: string,
  nowMs: number,
): { error: string } | { ok: true; licenseRestored: "previous" | "minus_days" | "device_missing" } {
  if (activation.undoneAt) return { error: "already_undone" };
  const at = new Date(nowMs).toISOString();
  let how: "previous" | "minus_days" | "device_missing" = "device_missing";
  if (device) {
    if (device.license.transactionNsu === activation.id && device.license.expiresAt === activation.newExpiresAt) {
      device.license = { ...activation.previousLicense };
      how = "previous";
    } else if (device.license.expiresAt) {
      const days = PLAN_DAYS[RESELLER_PLAN] ?? 365;
      const exp = new Date(device.license.expiresAt).getTime() - days * 86400000;
      device.license = exp > nowMs
        ? { ...device.license, expiresAt: new Date(exp).toISOString() }
        : { ...device.license, type: "expired", expiresAt: new Date(Math.max(exp, 0)).toISOString() };
      how = "minus_days";
    }
    if (device.activatedBy?.activationId === activation.id) {
      const prev = [...store.resellerActivations].reverse()
        .find((a) => a.deviceId === device.deviceId && a.id !== activation.id && !a.undoneAt);
      if (prev) device.activatedBy = { resellerId: prev.resellerId, activationId: prev.id, at: prev.at, plan: prev.plan };
      else delete device.activatedBy;
    }
  }
  activation.undoneAt = at;
  activation.undoReason = reason;
  store.creditLedger.push({
    id: `led-${randomUUID()}`, resellerId: activation.resellerId, at, delta: RESELLER_CREDITS_PER_ACTIVATION,
    kind: "undo", reason, deviceId: activation.deviceId, activationId: activation.id, by: "admin",
  });
  return { ok: true, licenseRestored: how };
}

/** Pacote pago: credita uma vez só (idempotente pelo orderNsu). */
export function creditPaidOrder(store: ResellerStore, order: CreditOrder, nowMs: number, tx: { transactionNsu: string; slug: string }): boolean {
  if (store.creditLedger.some((e) => e.kind === "purchase" && e.orderNsu === order.orderNsu)) return false;
  const at = new Date(nowMs).toISOString();
  order.status = "paid";
  order.paidAt = at;
  order.transactionNsu = tx.transactionNsu;
  order.slug = tx.slug;
  store.creditLedger.push({
    id: `led-${randomUUID()}`, resellerId: order.resellerId, at, delta: order.credits, kind: "purchase",
    orderNsu: order.orderNsu, reason: `Pacote ${order.credits} créditos`, by: "system",
  });
  return true;
}

/** Estorno de pacote: tira os créditos (uma vez); saldo negativo → bloqueia a conta. */
export function refundOrder(store: ResellerStore, order: CreditOrder, nowMs: number): { removed: boolean; blocked: boolean } {
  const wasPaid = store.creditLedger.some((e) => e.kind === "purchase" && e.orderNsu === order.orderNsu);
  const already = store.creditLedger.some((e) => e.kind === "refund" && e.orderNsu === order.orderNsu);
  const at = new Date(nowMs).toISOString();
  order.status = "refunded";
  order.refundedAt ??= at;
  if (!wasPaid || already) return { removed: false, blocked: false };
  store.creditLedger.push({
    id: `led-${randomUUID()}`, resellerId: order.resellerId, at, delta: -order.credits, kind: "refund",
    orderNsu: order.orderNsu, reason: "Pagamento do pacote estornado", by: "system",
  });
  let blocked = false;
  const r = store.resellers.find((x) => x.id === order.resellerId);
  if (r && balanceOf(store.creditLedger, r.id) < 0) {
    r.status = "blocked";
    r.blockedAt = at;
    r.blockedReason = "Saldo negativo após estorno de pacote. Acerte o valor com o administrador.";
    r.negativeBalanceBlock = true;
    r.updatedAt = at;
    blocked = true;
  }
  return { removed: true, blocked };
}

// ---------- rotas ----------

const signupSchema = z.object({
  name: z.string().trim().min(2).max(80),
  email: z.string().trim().toLowerCase().email().max(120),
  whatsapp: z.string().trim().max(30).optional(),
  password: z.string().min(8).max(200),
});
const loginSchema = z.object({ email: z.string().trim().toLowerCase().max(120), password: z.string().min(1).max(200) });
const deviceSchema = z.object({
  deviceId: z.string().trim().min(4).max(128),
  deviceKey: z.string().trim().min(4).max(64),
  confirm: z.boolean().optional(),
});

/** Limitador simples em memória por chave (e-mail ou revendedor). */
class FailCounter {
  private m = new Map<string, { n: number; until: number; first: number }>();
  constructor(private max: number, private lockMs: number) {}
  locked(key: string, now: number): number {
    const e = this.m.get(key);
    if (!e) return 0;
    if (e.until > now) return e.until - now;
    if (now - e.first > this.lockMs) this.m.delete(key);
    return 0;
  }
  fail(key: string, now: number) {
    const e = this.m.get(key);
    if (!e || (e.until && e.until <= now) || now - e.first > this.lockMs) { this.m.set(key, { n: 1, until: 0, first: now }); return; }
    e.n++;
    if (e.n >= this.max) e.until = now + this.lockMs;
  }
  reset(key: string) { this.m.delete(key); }
}

function deviceView(d: ResellerDevice, nowMs: number) {
  return {
    deviceId: d.deviceId,
    platform: d.platform,
    createdAt: d.createdAt,
    lastSeenAt: d.lastSeenAt,
    plan: d.license.plan ?? d.license.type,
    type: d.license.type,
    expiresAt: d.license.expiresAt,
    status: d.blockedAt ? "blocked" : d.revokedAt ? "revoked" : d.transferredTo ? "transferred" : licenseStatus(d.license, nowMs),
  };
}

export function createResellerRouter(deps: ResellerDeps): Router {
  const r = Router();
  const L = { ...DEFAULT_LIMITS, ...(deps.limits ?? {}) };
  const now = () => (deps.now ? deps.now() : Date.now());
  const loginFails = new FailCounter(L.loginFailsBeforeLock, L.loginLockMs);
  const activationFails = new FailCounter(L.activationFailsBeforeLock, L.activationLockMs);
  const recentActivations = new Map<string, number[]>();

  const loginLimiter = rateLimit({ windowMs: 15 * 60_000, limit: L.loginPerIpPer15Min, standardHeaders: true, legacyHeaders: false, message: { error: "too_many_attempts" } });
  const signupLimiter = rateLimit({ windowMs: 60 * 60_000, limit: L.signupPerIpPerHour, standardHeaders: true, legacyHeaders: false, message: { error: "too_many_attempts" } });

  function pruneSessions() {
    const s = deps.store();
    const t = now();
    s.resellerSessions = s.resellerSessions.filter((x) => new Date(x.expiresAt).getTime() > t);
  }

  function requireReseller(req: Request, res: Response, next: NextFunction) {
    const auth = req.header("authorization") ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!token || token.length > 200) return res.status(401).json({ error: "not_logged_in" });
    const s = deps.store();
    const th = hashToken(token);
    const sess = s.resellerSessions.find((x) => safeEqual(x.tokenHash, th));
    if (!sess || new Date(sess.expiresAt).getTime() <= now()) return res.status(401).json({ error: "session_expired" });
    const reseller = s.resellers.find((x) => x.id === sess.resellerId);
    if (!reseller) return res.status(401).json({ error: "not_logged_in" });
    (req as any).reseller = reseller;
    (req as any).sessionHash = th;
    next();
  }

  r.get("/packs", (_req, res) => res.json({ packs: RESELLER_PACKS, plan: RESELLER_PLAN, creditsPerActivation: RESELLER_CREDITS_PER_ACTIVATION }));

  r.post("/signup", signupLimiter, async (req, res) => {
    const parsed = signupSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten().fieldErrors });
    const s = deps.store();
    const { name, email, whatsapp, password } = parsed.data;
    if (s.resellers.some((x) => x.email === email)) return res.status(409).json({ error: "email_taken" });
    const { hash, salt } = await hashPassword(password);
    if (s.resellers.some((x) => x.email === email)) return res.status(409).json({ error: "email_taken" });
    const at = new Date(now()).toISOString();
    const reseller: Reseller = {
      id: `rs-${randomUUID()}`, name, email, ...(whatsapp ? { whatsapp } : {}),
      passwordHash: hash, passwordSalt: salt, status: "pending", createdAt: at, updatedAt: at,
    };
    s.resellers.push(reseller);
    await deps.persist();
    console.log(`reseller signup ${reseller.id}`);
    res.status(201).json({ ok: true, status: "pending" });
  });

  r.post("/login", loginLimiter, async (req, res) => {
    const parsed = loginSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error" });
    const { email, password } = parsed.data;
    const wait = loginFails.locked(email, now());
    if (wait > 0) return res.status(429).json({ error: "too_many_attempts", retryInSeconds: Math.ceil(wait / 1000) });
    const s = deps.store();
    const reseller = s.resellers.find((x) => x.email === email);
    // confere a senha mesmo sem conta (tempo parecido; não revela quais e-mails existem)
    const ok = reseller
      ? await verifyPassword(password, reseller.passwordHash, reseller.passwordSalt)
      : (await verifyPassword(password, "00".repeat(64), "0".repeat(32)), false);
    if (!reseller || !ok) {
      loginFails.fail(email, now());
      return res.status(401).json({ error: "invalid_login" });
    }
    loginFails.reset(email);
    if (reseller.status === "rejected") return res.status(403).json({ error: "reseller_rejected" });
    pruneSessions();
    const token = randomBytes(32).toString("base64url");
    const t = now();
    const mine = s.resellerSessions.filter((x) => x.resellerId === reseller.id);
    if (mine.length >= L.maxSessionsPerReseller) {
      const oldest = mine.sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0];
      s.resellerSessions = s.resellerSessions.filter((x) => x !== oldest);
    }
    s.resellerSessions.push({
      tokenHash: hashToken(token), resellerId: reseller.id,
      createdAt: new Date(t).toISOString(), expiresAt: new Date(t + L.sessionTtlMs).toISOString(),
    });
    await deps.persist();
    res.json({ ok: true, token, expiresAt: new Date(t + L.sessionTtlMs).toISOString(), reseller: publicReseller(reseller, balanceOf(s.creditLedger, reseller.id)) });
  });

  r.use(["/logout", "/logout-all", "/me", "/orders", "/activate", "/renew", "/clients", "/ledger"], requireReseller);

  r.post("/logout", async (req, res) => {
    const s = deps.store();
    const th = (req as any).sessionHash as string;
    s.resellerSessions = s.resellerSessions.filter((x) => x.tokenHash !== th);
    await deps.persist();
    res.json({ ok: true });
  });

  r.post("/logout-all", async (req, res) => {
    const s = deps.store();
    const me = (req as any).reseller as Reseller;
    s.resellerSessions = s.resellerSessions.filter((x) => x.resellerId !== me.id);
    await deps.persist();
    res.json({ ok: true });
  });

  r.get("/me", (req, res) => {
    const s = deps.store();
    const me = (req as any).reseller as Reseller;
    res.json({ reseller: publicReseller(me, balanceOf(s.creditLedger, me.id)), packs: RESELLER_PACKS });
  });

  r.get("/ledger", (req, res) => {
    const s = deps.store();
    const me = (req as any).reseller as Reseller;
    const entries = s.creditLedger.filter((e) => e.resellerId === me.id).slice(-500).reverse()
      .map(({ id, at, delta, kind, reason, deviceId, orderNsu }) => ({ id, at, delta, kind, reason, deviceId, orderNsu }));
    res.json({ balance: balanceOf(s.creditLedger, me.id), entries });
  });

  r.get("/clients", (req, res) => {
    const s = deps.store();
    const me = (req as any).reseller as Reseller;
    const t = now();
    const byDevice = new Map<string, { last: ResellerActivation; count: number }>();
    for (const a of s.resellerActivations) {
      if (a.resellerId !== me.id || a.undoneAt) continue;
      const cur = byDevice.get(a.deviceId);
      byDevice.set(a.deviceId, { last: a, count: (cur?.count ?? 0) + 1 });
    }
    const clients = [...byDevice.values()].map(({ last, count }) => {
      const d = deps.findDevice(last.deviceId);
      return {
        ...(d ? deviceView(d, t) : { deviceId: last.deviceId, status: "missing" }),
        lastActivationAt: last.at,
        activations: count,
        canRenew: Boolean(d && !activationBlocker(d)),
      };
    }).sort((a, b) => String(b.lastActivationAt).localeCompare(String(a.lastActivationAt)));
    res.json({ clients });
  });

  // ---- comprar pacote ----
  r.post("/orders", async (req, res) => {
    const me = (req as any).reseller as Reseller;
    if (me.status !== "approved") return res.status(403).json({ error: "reseller_not_approved" });
    if (!deps.createCheckout) return res.status(503).json({ error: "payment_not_configured" });
    const pack = RESELLER_PACKS.find((p) => p.id === String(req.body?.packId ?? ""));
    if (!pack) return res.status(400).json({ error: "invalid_pack" });
    const s = deps.store();
    const order: CreditOrder = {
      orderNsu: `hugo-cr-${randomUUID()}`, resellerId: me.id, packId: pack.id, credits: pack.credits,
      amount: pack.amount, status: "pending", createdAt: new Date(now()).toISOString(),
    };
    s.creditOrders.push(order);
    await deps.persist();
    try {
      order.checkoutUrl = await deps.createCheckout({
        orderNsu: order.orderNsu,
        amount: order.amount,
        description: `Hugo Player · ${pack.credits} créditos de revenda (ativação anual)`,
        redirectUrl: `${deps.publicWebUrl}/revendedor?payment=complete&order_nsu=${encodeURIComponent(order.orderNsu)}`,
        webhookUrl: `${deps.publicApiUrl}/reseller/payments/webhook`,
      });
      await deps.persist();
      res.json({ ok: true, orderNsu: order.orderNsu, credits: order.credits, amount: order.amount, checkoutUrl: order.checkoutUrl });
    } catch {
      order.status = "failed";
      await deps.persist();
      res.status(502).json({ error: "checkout_create_failed" });
    }
  });

  r.get("/orders", (req, res) => {
    const me = (req as any).reseller as Reseller;
    const orders = deps.store().creditOrders.filter((o) => o.resellerId === me.id).slice(-50).reverse()
      .map(({ orderNsu, packId, credits, amount, status, createdAt, paidAt }) => ({ orderNsu, packId, credits, amount, status, createdAt, paidAt }));
    res.json({ orders });
  });

  /** Volta do checkout: confere com a InfinitePay (não confia no navegador) e credita se pago. */
  r.post("/orders/:nsu/check", async (req, res) => {
    const me = (req as any).reseller as Reseller;
    const s = deps.store();
    const order = s.creditOrders.find((o) => o.orderNsu === req.params.nsu && o.resellerId === me.id);
    if (!order) return res.status(404).json({ error: "order_not_found" });
    if (order.status === "paid" || order.status === "refunded") return res.json({ status: order.status, balance: balanceOf(s.creditLedger, me.id) });
    const transactionNsu = String(req.body?.transaction_nsu ?? "").trim().slice(0, 200);
    const slug = String(req.body?.slug ?? "").trim().slice(0, 200);
    if (deps.confirmPayment && transactionNsu && slug) {
      const paid = await deps.confirmPayment({ orderNsu: order.orderNsu, amount: order.amount, transactionNsu, slug }).catch(() => false);
      if (paid && creditPaidOrder(s, order, now(), { transactionNsu, slug })) await deps.persist();
    }
    res.json({ status: order.status, balance: balanceOf(s.creditLedger, me.id) });
  });

  r.post("/payments/webhook", async (req, res) => {
    const body = (req.body ?? {}) as Record<string, any>;
    const s = deps.store();
    const order = s.creditOrders.find((o) => o.orderNsu === String(body.order_nsu ?? "").trim());
    if (!order) return res.status(404).json({ error: "order_not_found" });
    const event = String(body.event ?? body.status ?? "paid").toLowerCase();
    if (["refunded", "chargeback", "chargedback"].includes(event)) {
      const out = refundOrder(s, order, now());
      await deps.persist();
      console.log(`reseller pack refund ${order.orderNsu} removed=${out.removed} blocked=${out.blocked}`);
      return res.sendStatus(200);
    }
    if (order.status === "paid") return res.sendStatus(200);
    if (["canceled", "cancelled", "payment_failed", "failed"].includes(event)) {
      if (order.status === "pending") order.status = event.startsWith("cancel") ? "canceled" : "failed";
      await deps.persist();
      return res.sendStatus(200);
    }
    const transactionNsu = String(body.transaction_nsu ?? "").trim();
    const slug = String(body.slug ?? body.invoice_slug ?? "").trim();
    try {
      if (!deps.confirmPayment || !transactionNsu || !slug) return res.status(400).json({ error: "payment_not_confirmed" });
      if (!(await deps.confirmPayment({ orderNsu: order.orderNsu, amount: order.amount, transactionNsu, slug }))) {
        return res.status(400).json({ error: "payment_not_confirmed" });
      }
      if (creditPaidOrder(s, order, now(), { transactionNsu, slug })) await deps.persist();
      return res.sendStatus(200);
    } catch {
      return res.status(500).json({ error: "payment_processing_failed" });
    }
  });

  // ---- ativar cliente ----
  function activationGuard(me: Reseller, res: Response): boolean {
    if (me.status !== "approved") { res.status(403).json({ error: "reseller_not_approved" }); return false; }
    const wait = activationFails.locked(me.id, now());
    if (wait > 0) { res.status(429).json({ error: "too_many_attempts", retryInSeconds: Math.ceil(wait / 1000) }); return false; }
    return true;
  }

  function hourlyOk(me: Reseller): boolean {
    const t = now();
    const list = (recentActivations.get(me.id) ?? []).filter((x) => t - x < 60 * 60_000);
    recentActivations.set(me.id, list);
    return list.length < L.activationsPerHour;
  }

  function preview(d: ResellerDevice, balance: number) {
    const t = now();
    return {
      device: deviceView(d, t),
      cost: RESELLER_CREDITS_PER_ACTIVATION,
      plan: RESELLER_PLAN,
      newExpiresAt: planExpiry(RESELLER_PLAN, renewalBase(d.license, t)),
      renewal: d.license.type === "premium" && licenseStatus(d.license, t) === "active",
      balance,
      balanceAfter: balance - RESELLER_CREDITS_PER_ACTIVATION,
    };
  }

  async function doActivate(me: Reseller, d: ResellerDevice, confirm: boolean, res: Response) {
    const s = deps.store();
    const blocker = activationBlocker(d);
    if (blocker) return res.status(409).json({ error: blocker, message: BLOCKER_MESSAGES[blocker] });
    const balance = balanceOf(s.creditLedger, me.id);
    if (!confirm) return res.json({ preview: preview(d, balance) });
    if (!hourlyOk(me)) return res.status(429).json({ error: "activation_rate_limited" });
    const out = applyActivation(s, me, d, now());
    if ("error" in out) return res.status(out.status).json({ error: out.error });
    recentActivations.get(me.id)!.push(now());
    await deps.persist();
    console.log(`reseller ${me.id} activated ${d.deviceId} (${out.activation.kind})`);
    res.json({ ok: true, activation: { id: out.activation.id, kind: out.activation.kind, expiresAt: out.activation.newExpiresAt }, device: deviceView(d, now()), balance: out.balance });
  }

  r.post("/activate", async (req, res) => {
    const me = (req as any).reseller as Reseller;
    if (!activationGuard(me, res)) return;
    const parsed = deviceSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error" });
    const d = deps.verifyDevice(parsed.data.deviceId, parsed.data.deviceKey);
    if (!d) {
      activationFails.fail(me.id, now());
      return res.status(404).json({ error: "invalid_device", message: "Device ID ou Key não conferem. Confira na tela do app do cliente." });
    }
    activationFails.reset(me.id);
    await doActivate(me, d, parsed.data.confirm === true, res);
  });

  /** Renovar um cliente que ESTE revendedor já ativou (sem pedir a Key de novo). */
  r.post("/renew", async (req, res) => {
    const me = (req as any).reseller as Reseller;
    if (!activationGuard(me, res)) return;
    const id = String(req.body?.deviceId ?? "").trim();
    const s = deps.store();
    const mine = s.resellerActivations.some((a) => a.resellerId === me.id && !a.undoneAt && a.deviceId.toUpperCase() === id.toUpperCase());
    const d = mine ? deps.findDevice(id) : undefined;
    if (!d) return res.status(404).json({ error: "not_your_client" });
    await doActivate(me, d, req.body?.confirm === true, res);
  });

  return r;
}

// ---------- admin ----------

export function createResellerAdminRouter(deps: ResellerDeps & { allDevices: () => ResellerDevice[] }): Router {
  const r = Router();
  const now = () => (deps.now ? deps.now() : Date.now());
  r.use((req, res, next) => {
    const token = req.header("x-admin-token") ?? "";
    if (!deps.adminToken || !token || !safeEqual(token, deps.adminToken)) return res.status(401).json({ error: "admin_unauthorized" });
    next();
  });

  const find = (id: string) => deps.store().resellers.find((x) => x.id === id);
  const reasonSchema = z.object({ reason: z.string().trim().min(3).max(300) });

  r.get("/", (_req, res) => {
    const s = deps.store();
    const resellers = s.resellers.map((x) => {
      const acts = s.resellerActivations.filter((a) => a.resellerId === x.id && !a.undoneAt);
      return {
        ...publicReseller(x, balanceOf(s.creditLedger, x.id)),
        activations: acts.length,
        clients: new Set(acts.map((a) => a.deviceId)).size,
        purchased: s.creditLedger.filter((e) => e.resellerId === x.id && e.kind === "purchase").reduce((n, e) => n + e.delta, 0),
      };
    }).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    res.json({ resellers, packs: RESELLER_PACKS });
  });

  async function setStatus(req: Request, res: Response, status: ResellerStatus) {
    const x = find(req.params.id);
    if (!x) return res.status(404).json({ error: "reseller_not_found" });
    const s = deps.store();
    const at = new Date(now()).toISOString();
    if (status === "approved" && balanceOf(s.creditLedger, x.id) < 0) {
      return res.status(409).json({ error: "negative_balance", message: "Saldo negativo: lance créditos para acertar antes de liberar." });
    }
    x.status = status;
    x.updatedAt = at;
    if (status === "approved") { x.approvedAt ??= at; delete x.blockedAt; delete x.blockedReason; delete x.negativeBalanceBlock; }
    if (status === "blocked") { x.blockedAt = at; x.blockedReason = String(req.body?.reason ?? "bloqueado pelo administrador").slice(0, 300); }
    if (status !== "approved") s.resellerSessions = s.resellerSessions.filter((z) => z.resellerId !== x.id);
    await deps.persist();
    res.json({ ok: true, reseller: publicReseller(x, balanceOf(s.creditLedger, x.id)) });
  }
  r.post("/:id/approve", (req, res) => setStatus(req, res, "approved"));
  r.post("/:id/unblock", (req, res) => setStatus(req, res, "approved"));
  r.post("/:id/reject", (req, res) => setStatus(req, res, "rejected"));
  r.post("/:id/block", (req, res) => setStatus(req, res, "blocked"));

  r.post("/:id/credits", async (req, res) => {
    const x = find(req.params.id);
    if (!x) return res.status(404).json({ error: "reseller_not_found" });
    const parsed = z.object({ delta: z.number().int().min(-10000).max(10000).refine((v) => v !== 0), reason: z.string().trim().min(3).max(300) }).safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", message: "Informe a quantidade (diferente de 0) e o motivo." });
    const s = deps.store();
    const bal = balanceOf(s.creditLedger, x.id);
    const { delta, reason } = parsed.data;
    // remover não pode deixar negativo; adicionar sempre pode (inclusive para acertar saldo negativo)
    if (delta < 0 && bal + delta < 0) return res.status(409).json({ error: "would_go_negative", balance: bal });
    const at = new Date(now()).toISOString();
    s.creditLedger.push({ id: `led-${randomUUID()}`, resellerId: x.id, at, delta, kind: delta > 0 ? "admin_add" : "admin_remove", reason, by: "admin" });
    await deps.persist();
    console.log(`admin credits ${x.id} ${delta > 0 ? "+" : ""}${delta}`);
    res.json({ ok: true, balance: bal + delta });
  });

  r.post("/:id/reset-password", async (req, res) => {
    const x = find(req.params.id);
    if (!x) return res.status(404).json({ error: "reseller_not_found" });
    const temp = randomBytes(9).toString("base64url");
    const { hash, salt } = await hashPassword(temp);
    x.passwordHash = hash; x.passwordSalt = salt; x.updatedAt = new Date(now()).toISOString();
    const s = deps.store();
    s.resellerSessions = s.resellerSessions.filter((z) => z.resellerId !== x.id);
    await deps.persist();
    res.json({ ok: true, temporaryPassword: temp, warning: "Mostrada só agora. Passe ao revendedor por um canal privado." });
  });

  r.get("/:id/ledger", (req, res) => {
    const x = find(req.params.id);
    if (!x) return res.status(404).json({ error: "reseller_not_found" });
    const s = deps.store();
    res.json({ balance: balanceOf(s.creditLedger, x.id), entries: s.creditLedger.filter((e) => e.resellerId === x.id).slice().reverse() });
  });

  r.get("/activations/list", (req, res) => {
    const s = deps.store();
    const rid = String(req.query.resellerId ?? "");
    const t = now();
    const list = s.resellerActivations.filter((a) => !rid || a.resellerId === rid).slice(-1000).reverse().map((a) => {
      const d = deps.findDevice(a.deviceId);
      return {
        id: a.id, resellerId: a.resellerId, resellerName: find(a.resellerId)?.name ?? "", deviceId: a.deviceId, plan: a.plan, at: a.at,
        kind: a.kind, newExpiresAt: a.newExpiresAt, undoneAt: a.undoneAt, undoReason: a.undoReason,
        device: d ? deviceView(d, t) : null,
      };
    });
    res.json({ activations: list });
  });

  r.post("/activations/:aid/undo", async (req, res) => {
    const parsed = reasonSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", message: "Informe o motivo." });
    const s = deps.store();
    const a = s.resellerActivations.find((x) => x.id === req.params.aid);
    if (!a) return res.status(404).json({ error: "activation_not_found" });
    const out = undoActivation(s, a, deps.findDevice(a.deviceId), parsed.data.reason, now());
    if ("error" in out) return res.status(409).json(out);
    await deps.persist();
    console.log(`admin undo activation ${a.id} (${out.licenseRestored})`);
    res.json({ ...out, balance: balanceOf(s.creditLedger, a.resellerId) });
  });

  r.get("/orders/list", (_req, res) => {
    const s = deps.store();
    res.json({ orders: s.creditOrders.slice(-500).reverse().map(({ checkoutUrl: _c, ...o }) => ({ ...o, resellerName: find(o.resellerId)?.name ?? "" })) });
  });

  return r;
}
