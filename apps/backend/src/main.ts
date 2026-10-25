import "dotenv/config";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { z } from "zod";
import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { tmdbRouter } from "./routes-tmdb.js";
import { recoveryRouter } from "./routes-recovery.js";
import { createTransferRouter, type TransferRecord } from "./routes-transfer.js";
import { channelsRouter, setChannelsAccess } from "./routes-channels.js";
import { isRegisteredUrl } from "./playlist-access.js";
import { createPlaylistRouter, type StoredPlaylist } from "./routes-playlists.js";
import { createEpgRouter } from "./routes-epg.js";
import { createAdminRouter } from "./routes-admin.js";
import type { PlaylistResolver } from "./routes-catalog.js";

const PORT = Number(process.env.PORT ?? 8080);
const DATA_FILE = path.resolve(process.env.DATA_FILE ?? "./data/db.json");
const TRIAL_DAYS = Number(process.env.TRIAL_DAYS ?? 7);
const ADMIN_TOKEN = process.env.ADMIN_TOKEN ?? "";
const INFINITEPAY_HANDLE = (process.env.INFINITEPAY_HANDLE ?? "").replace(/^\$/, "").trim();
const PUBLIC_WEB_URL = (process.env.PUBLIC_WEB_URL ?? "https://hugo.streamflixvip.online").replace(/\/+$/, "");
const PUBLIC_API_URL = (process.env.PUBLIC_API_URL ?? "https://api-hugo.streamflixvip.online").replace(/\/+$/, "");

type LicenseType = "trial" | "premium" | "expired";
type LicensePlan = "trial" | "annual" | "lifetime";

interface Device {
  deviceId: string;
  keyHash: string;
  keySalt: string;
  platform: string;
  appVersion: string;
  license: {
    type: LicenseType;
    plan?: LicensePlan;
    expiresAt: string | null;
    paymentProvider?: string;
    transactionNsu?: string;
  };
  createdAt: string;
  lastSeenAt: string;
  blockedAt?: string;
  blockedReason?: string;
  revokedAt?: string;
  revokedReason?: string;
  /** transferência de aparelho (licença + playlists) */
  transferredTo?: string;
  transferredFrom?: string;
  transferredAt?: string;
}

interface Db {
  devices: Record<string, Device>;
  playlists: StoredPlaylist[];
  orders: PaymentOrder[];
  transfers?: TransferRecord[];
}

type PaymentPlan = "annual" | "lifetime";
type PaymentStatus = "pending" | "paid" | "canceled" | "refunded" | "failed";

interface PaymentOrder {
  orderNsu: string;
  deviceId: string;
  plan: PaymentPlan;
  amount: number;
  status: PaymentStatus;
  checkoutUrl?: string;
  transactionNsu?: string;
  slug?: string;
  createdAt: string;
  paidAt?: string;
}

let db: Db = { devices: {}, playlists: [], orders: [] };
let writeChain: Promise<void> = Promise.resolve();

async function loadDb(): Promise<void> {
  try {
    db = JSON.parse(await fs.readFile(DATA_FILE, "utf8")) as Db;
    db.devices ??= {};
    db.playlists ??= [];
    db.orders ??= [];
  } catch {
    await fs.mkdir(path.dirname(DATA_FILE), { recursive: true });
    await persist();
  }
}

function persist(): Promise<void> {
  writeChain = writeChain.then(async () => {
    const tmp = `${DATA_FILE}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(db, null, 2));
    await fs.rename(tmp, DATA_FILE);
  });
  return writeChain;
}

function hashKey(key: string): { salt: string; hash: string } {
  const salt = randomBytes(16).toString("hex");
  const hash = scryptSync(key, salt, 32).toString("hex");
  return { salt, hash };
}

function verifyKey(key: string, device: Device): boolean {
  try {
    const a = Buffer.from(scryptSync(key, device.keySalt, 32).toString("hex"), "hex");
    const b = Buffer.from(device.keyHash, "hex");
    return a.length === b.length && timingSafeEqual(a, b);
  } catch {
    return false;
  }
}

function findDevice(id: string): Device | undefined {
  const raw = id.trim();
  return (
    db.devices[raw] ||
    db.devices[raw.toUpperCase()] ||
    db.devices[raw.toLowerCase()] ||
    Object.values(db.devices).find((d) => d.deviceId.toUpperCase() === raw.toUpperCase())
  );
}

function authDevice(id: string | undefined, key: string | undefined): Device | null {
  if (!id || !key) return null;
  const device = findDevice(id);
  if (!device) return null;
  if (device.blockedAt) return null;
  const k = key.trim();
  if (verifyKey(k, device) || verifyKey(k.toUpperCase(), device) || verifyKey(k.toLowerCase(), device)) {
    return device;
  }
  return null;
}

function hasActiveLicense(device: Device): boolean {
  if (device.blockedAt || device.license.type === "expired") return false;
  if (!device.license.expiresAt) return true;
  return new Date(device.license.expiresAt).getTime() >= Date.now();
}

const registerSchema = z.object({
  deviceId: z.string().min(4).max(128).refine((value) => !/^\d+$/.test(value.trim()), "Device ID não pode ser apenas numérico"),
  deviceKey: z.string().min(4).max(64),
  platform: z.string().max(64),
  appVersion: z.string().max(32).optional(),
});

const app = express();
app.disable("x-powered-by");
app.set("trust proxy", 1);
app.use(helmet());
app.use(cors({ origin: true }));
app.use(express.json({ limit: "32kb" }));
app.use("/channels/from-m3u", express.text({ type: ["text/*", "application/octet-stream", "text/plain"], limit: "100mb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 200 }));

app.use("/tmdb", tmdbRouter);
app.use("/recovery", recoveryRouter);
// /channels e /channels/series-episodes: playlistId + aparelho (novo) ou ?url= cadastrada (apps antigos)
setChannelsAccess({
  authenticate: (id, key) => authDevice(id, key)?.deviceId ?? null,
  playlists: () => db.playlists,
});
app.use("/channels", channelsRouter);
app.use("/admin", createAdminRouter({
  devices: () => db.devices,
  persist,
  adminToken: ADMIN_TOKEN,
  playlistCount: (deviceId) => db.playlists.filter((p) => p.deviceId.toUpperCase() === deviceId.toUpperCase()).length,
  resetDeviceKey: (deviceId) => {
    const key = Object.keys(db.devices).find((k) => db.devices[k].deviceId.toUpperCase() === deviceId.toUpperCase());
    if (!key) return null;
    const deviceKey = randomBytes(6).toString("base64url").slice(0, 8);
    const hashed = hashKey(deviceKey);
    db.devices[key].keySalt = hashed.salt;
    db.devices[key].keyHash = hashed.hash;
    return deviceKey;
  },
  deleteDevice: (deviceId) => {
    const key = Object.keys(db.devices).find((k) => db.devices[k].deviceId.toUpperCase() === deviceId.toUpperCase());
    if (key) delete db.devices[key];
  },
}));

app.get("/health", (_req, res) => {
  res.json({ status: "ok", time: new Date().toISOString() });
});

app.post("/devices/register", async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
  const deviceId = parsed.data.deviceId.trim();
  const deviceKey = parsed.data.deviceKey.trim();
  const platform = parsed.data.platform;
  const appVersion = parsed.data.appVersion;
  const now = new Date();
  const storeKey = deviceId.toUpperCase();
  const existing = findDevice(deviceId);
  if (existing) {
    if (verifyKey(deviceKey, existing) || verifyKey(deviceKey.toUpperCase(), existing)) {
      existing.lastSeenAt = now.toISOString();
      existing.platform = platform;
      if (appVersion) existing.appVersion = appVersion;
      db.devices[storeKey] = existing;
      await persist();
      return res.json({ registered: true, created: false });
    }
    // Um ID existente não pode trocar de Key só porque recebeu uma Key diferente.
    // A troca após reinstalação deve passar pelo fluxo explícito de recuperação.
    return res.status(409).json({ error: "device_key_mismatch", message: "Este Device ID já está registrado. Use a recuperação para trocar de aparelho." });
  }
  const { salt, hash } = hashKey(deviceKey);
  const device: Device = {
    deviceId: storeKey,
    keyHash: hash,
    keySalt: salt,
    platform,
    appVersion: appVersion ?? "",
    license: { type: "trial", plan: "trial", expiresAt: new Date(now.getTime() + TRIAL_DAYS * 86400000).toISOString() },
    createdAt: now.toISOString(),
    lastSeenAt: now.toISOString(),
  };
  db.devices[storeKey] = device;
  await persist();
  res.status(201).json({ registered: true, created: true });
});

function requireDevice(req: Request, res: Response, next: NextFunction) {
  const device = authDevice(req.header("x-device-id"), req.header("x-device-key"));
  if (!device) return res.status(401).json({ error: "invalid_credentials" });
  (req as any).device = device;
  next();
}

const paymentPlanSchema = z.object({ plan: z.enum(["annual", "lifetime"]) });
const paymentPrices: Record<PaymentPlan, number> = { annual: 2500, lifetime: 6000 };

async function confirmInfinitePayPayment(order: PaymentOrder, body: Record<string, any>): Promise<boolean> {
  const transactionNsu = String(body.transaction_nsu ?? "").trim();
  const slug = String(body.slug ?? body.invoice_slug ?? "").trim();
  if (!INFINITEPAY_HANDLE || !transactionNsu || !slug) return false;
  const response = await fetch("https://api.checkout.infinitepay.io/payment_check", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ handle: INFINITEPAY_HANDLE, order_nsu: order.orderNsu, transaction_nsu: transactionNsu, slug }),
  });
  if (!response.ok) return false;
  const checked = await response.json() as { paid?: boolean; amount?: number; paid_amount?: number };
  return checked.paid === true && Number(checked.amount ?? checked.paid_amount) === order.amount;
}

async function settleOrder(order: PaymentOrder, body: Record<string, any>): Promise<void> {
  const device = db.devices[order.deviceId];
  if (!device) throw new Error("device_not_found");
  const now = new Date();
  const currentExpiry = device.license.expiresAt ? new Date(device.license.expiresAt) : null;
  const expiresAt = order.plan === "lifetime"
    ? null
    : new Date(Math.max(now.getTime(), currentExpiry?.getTime() ?? 0) + 365 * 86400000).toISOString();
  device.license = {
    type: "premium",
    plan: order.plan,
    expiresAt,
    paymentProvider: "infinitepay",
    transactionNsu: String(body.transaction_nsu ?? body.transactionNsu ?? "").trim(),
  };
  delete device.revokedAt;
  delete device.revokedReason;
  order.status = "paid";
  order.transactionNsu = device.license.transactionNsu;
  order.slug = String(body.slug ?? body.invoice_slug ?? "").trim();
  order.paidAt = now.toISOString();
}

app.post("/payments/checkout", requireDevice, async (req, res) => {
  if (!INFINITEPAY_HANDLE) return res.status(503).json({ error: "payment_not_configured" });
  const parsed = paymentPlanSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "validation_error" });
  const device = (req as any).device as Device;
  const order: PaymentOrder = {
    orderNsu: `hugo-${randomUUID()}`,
    deviceId: device.deviceId,
    plan: parsed.data.plan,
    amount: paymentPrices[parsed.data.plan],
    status: "pending",
    createdAt: new Date().toISOString(),
  };
  db.orders.push(order);
  await persist();
  try {
    const response = await fetch("https://api.checkout.infinitepay.io/links", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        handle: INFINITEPAY_HANDLE,
        order_nsu: order.orderNsu,
        items: [{ quantity: 1, price: order.amount, description: order.plan === "annual" ? "Hugo Player Premium Anual" : "Hugo Player Premium Vitalício" }],
        redirect_url: `${PUBLIC_WEB_URL}/?payment=complete&order_nsu=${encodeURIComponent(order.orderNsu)}`,
        webhook_url: `${PUBLIC_API_URL}/payments/infinitepay/webhook`,
        options: { single_payment: true, payment_methods_enabled: ["credit_card", "pix"] },
      }),
    });
    const data = await response.json() as Record<string, any>;
    const checkoutUrl = data.url ?? data.checkout_url ?? data.checkoutUrl;
    if (!response.ok || typeof checkoutUrl !== "string") throw new Error("checkout_create_failed");
    order.checkoutUrl = checkoutUrl;
    await persist();
    res.json({ ok: true, orderNsu: order.orderNsu, plan: order.plan, amount: order.amount, checkoutUrl });
  } catch {
    order.status = "failed";
    await persist();
    res.status(502).json({ error: "checkout_create_failed" });
  }
});

app.get("/payments/orders/:orderNsu", requireDevice, (req, res) => {
  const device = (req as any).device as Device;
  const order = db.orders.find((item) => item.orderNsu === req.params.orderNsu && item.deviceId === device.deviceId);
  if (!order) return res.status(404).json({ error: "order_not_found" });
  res.json({ order });
});

app.post("/payments/infinitepay/webhook", async (req, res) => {
  const body = (req.body ?? {}) as Record<string, any>;
  const orderNsu = String(body.order_nsu ?? "").trim();
  const order = db.orders.find((item) => item.orderNsu === orderNsu);
  if (!order) return res.status(404).json({ error: "order_not_found" });
  if (order.status === "paid") return res.sendStatus(200);
  const event = String(body.event ?? body.status ?? "paid").toLowerCase();
  if (["canceled", "cancelled", "refunded", "payment_failed", "failed"].includes(event)) {
    order.status = event === "refunded" ? "refunded" : event === "canceled" || event === "cancelled" ? "canceled" : "failed";
    await persist();
    return res.sendStatus(200);
  }
  try {
    if (!(await confirmInfinitePayPayment(order, body))) return res.status(400).json({ error: "payment_not_confirmed" });
    await settleOrder(order, body);
    await persist();
    return res.sendStatus(200);
  } catch {
    return res.status(500).json({ error: "payment_processing_failed" });
  }
});

// A ativação não pode ser feita pelo próprio usuário apenas com o par Device ID + Key.
// Pagamentos/webhooks e testes administrativos usam /admin, protegido por ADMIN_TOKEN.
app.post("/devices/activate", (_req, res) => res.status(410).json({ error: "payment_required", message: "A ativação é feita pelo checkout ou pelo painel administrativo." }));

app.get("/devices/status", requireDevice, (req, res) => {
  const device = (req as any).device as Device;
  const expiresAt = device.license.expiresAt ? new Date(device.license.expiresAt) : null;
  const expired = expiresAt !== null && expiresAt.getTime() < Date.now();
  const active = !expired && device.license.type !== "expired";
  res.json({
    deviceId: device.deviceId,
    platform: device.platform,
    license: {
      type: expired ? "expired" : device.license.type,
      plan: device.license.plan ?? (device.license.type === "premium" ? "annual" : "trial"),
      active,
      expiresAt: device.license.expiresAt,
      paymentProvider: device.license.paymentProvider,
      transactionNsu: device.license.transactionNsu,
    },
    playlists: db.playlists.filter((p) => p.deviceId.toUpperCase() === device.deviceId.toUpperCase()).length,
    createdAt: device.createdAt,
    lastSeenAt: device.lastSeenAt,
  });
});

// Transferir playlists do aparelho antigo para o novo (as duas Keys precisam conferir).
app.use(
  "/devices",
  createTransferRouter({
    playlists: () => db.playlists,
    persist,
    verify: (id, key) => authDevice(id, key)?.deviceId ?? null,
    licenses: {
      get: (id) => findDevice(id)?.license,
      set: (id, license) => {
        const d = findDevice(id);
        if (!d) return;
        d.license = license;
        if (license.type !== "expired") { delete d.revokedAt; delete d.revokedReason; }
      },
      record: (rec) => {
        const f = findDevice(rec.from), t = findDevice(rec.to);
        if (f) { f.transferredTo = rec.to; f.transferredAt = rec.at; }
        if (t) { t.transferredFrom = rec.from; t.transferredAt = rec.at; }
        db.transfers = [...(db.transfers ?? []), rec].slice(-500);
      },
    },
  }),
);

app.use(
  "/playlists",
  createPlaylistRouter({
    playlists: () => db.playlists,
    setPlaylists: (next) => {
      db.playlists = next;
    },
    persist,
    authenticate: (id, key) => {
      const device = authDevice(id, key);
      return device && hasActiveLicense(device) ? device.deviceId : null;
    },
  }),
);

const resolvePlaylist: PlaylistResolver = (req) => {
  const device = authDevice(req.header("x-device-id"), req.header("x-device-key"));
  if (device && !hasActiveLicense(device)) return "unauthorized";
  const playlistId = String(req.query.playlistId || "");
  if (playlistId && device) {
    const p = db.playlists.find(
      (x) => x.id === playlistId && x.deviceId.toUpperCase() === device.deviceId.toUpperCase(),
    );
    if (!p) return "unauthorized";
    return { type: p.type, url: p.url, ...(p.epgUrl ? { epgUrl: p.epgUrl } : {}) };
  }
  const url = String(req.query.url || "");
  // link na URL (rota antiga): só de playlist cadastrada
  if (url) return isRegisteredUrl(url, db.playlists) ? { type: (req.query.type as "m3u" | "xtream") || "m3u", url } : "unauthorized";
  return null;
};
app.use("/epg", createEpgRouter(resolvePlaylist));

app.use((_req, res) => res.status(404).json({ error: "not_found" }));

await loadDb();
app.listen(PORT, () => console.log(`Hugo Player API on port ${PORT}`));
