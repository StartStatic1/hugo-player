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

const PORT = Number(process.env.PORT ?? 8080);
const DATA_FILE = path.resolve(process.env.DATA_FILE ?? "./data/db.json");
const TRIAL_DAYS = Number(process.env.TRIAL_DAYS ?? 7);
const CORS_ORIGIN = process.env.CORS_ORIGIN ?? "*";

type LicenseType = "trial" | "premium" | "expired";

interface Device {
  deviceId: string;
  keyHash: string;
  keySalt: string;
  platform: string;
  appVersion: string;
  license: { type: LicenseType; expiresAt: string | null };
  createdAt: string;
  lastSeenAt: string;
}

interface Playlist {
  id: string;
  deviceId: string;
  name: string;
  type: "m3u" | "xtream";
  url: string;
  username?: string;
  password?: string;
  epgUrl?: string;
  createdAt: string;
}

interface Db {
  devices: Record<string, Device>;
  playlists: Playlist[];
}

// ---------- JSON file store (serialized writes) ----------
let db: Db = { devices: {}, playlists: [] };
let writeChain: Promise<void> = Promise.resolve();

async function loadDb(): Promise<void> {
  try {
    db = JSON.parse(await fs.readFile(DATA_FILE, "utf8")) as Db;
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

// ---------- Crypto helpers ----------
function hashKey(key: string, salt = randomBytes(16).toString("hex")) {
  return { salt, hash: scryptSync(key, salt, 64).toString("hex") };
}

function verifyKey(key: string, device: Device): boolean {
  const candidate = scryptSync(key, device.keySalt, 64);
  const stored = Buffer.from(device.keyHash, "hex");
  return candidate.length === stored.length && timingSafeEqual(candidate, stored);
}

// ---------- Validation ----------
const deviceIdSchema = z
  .string()
  .trim()
  .toUpperCase()
  .regex(/^([0-9A-F]{2}:){5}[0-9A-F]{2}$/, "deviceId must be MAC-style XX:XX:XX:XX:XX:XX");
const deviceKeySchema = z
  .string()
  .trim()
  .toUpperCase()
  .regex(/^[A-HJ-NP-Z2-9]{8}$/, "deviceKey must be 8 unambiguous characters");

const registerSchema = z.object({
  deviceId: deviceIdSchema,
  deviceKey: deviceKeySchema,
  platform: z.enum(["android", "android-tv", "web"]).default("android"),
  appVersion: z.string().trim().min(1).max(32).optional(),
});

const playlistSchema = z
  .object({
    name: z.string().trim().min(1).max(80),
    type: z.enum(["m3u", "xtream"]).default("m3u"),
    url: z.string().trim().url().refine((u) => /^https?:\/\//i.test(u), "url must be http(s)"),
    username: z.string().max(128).optional(),
    password: z.string().max(128).optional(),
    epgUrl: z.string().trim().url().optional(),
  })
  .refine((p) => p.type !== "xtream" || (p.username && p.password), {
    message: "username and password are required for xtream playlists",
  });

// ---------- License ----------
function currentLicense(device: Device) {
  const { type, expiresAt } = device.license;
  const expired = expiresAt !== null && new Date(expiresAt).getTime() < Date.now();
  const effective: LicenseType = expired ? "expired" : type;
  const daysLeft =
    expiresAt === null ? null : Math.max(0, Math.ceil((new Date(expiresAt).getTime() - Date.now()) / 86_400_000));
  return { type: effective, active: effective !== "expired", expiresAt, daysLeft };
}

function publicDevice(device: Device) {
  return {
    deviceId: device.deviceId,
    platform: device.platform,
    appVersion: device.appVersion,
    license: currentLicense(device),
    createdAt: device.createdAt,
    lastSeenAt: device.lastSeenAt,
  };
}

function publicPlaylist(p: Playlist) {
  const { password: _password, deviceId: _deviceId, ...rest } = p;
  return { ...rest, hasPassword: Boolean(_password) };
}

// ---------- App ----------
const app = express();
app.disable("x-powered-by");
app.set("trust proxy", 1);
app.use(helmet());
app.use(
  cors({
    origin: CORS_ORIGIN === "*" ? true : CORS_ORIGIN.split(",").map((o) => o.trim()),
    allowedHeaders: ["Content-Type", "x-device-id", "x-device-key"],
  }),
);
app.use(express.json({ limit: "32kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: "draft-7", legacyHeaders: false }));

const registerLimiter = rateLimit({ windowMs: 15 * 60_000, limit: 20, standardHeaders: "draft-7", legacyHeaders: false });

interface AuthedRequest extends Request {
  device?: Device;
}

function credentialsFrom(req: Request) {
  const id = req.header("x-device-id") ?? (typeof req.query.deviceId === "string" ? req.query.deviceId : undefined);
  const key = req.header("x-device-key") ?? (typeof req.query.deviceKey === "string" ? req.query.deviceKey : undefined);
  return { id, key };
}

function requireDevice(req: AuthedRequest, res: Response, next: NextFunction) {
  const { id, key } = credentialsFrom(req);
  const parsedId = deviceIdSchema.safeParse(id);
  const parsedKey = deviceKeySchema.safeParse(key);
  if (!parsedId.success || !parsedKey.success) {
    return res.status(401).json({ error: "invalid_credentials" });
  }
  const device = db.devices[parsedId.data];
  if (!device || !verifyKey(parsedKey.data, device)) {
    return res.status(401).json({ error: "invalid_credentials" });
  }
  req.device = device;
  next();
}

app.use("/tmdb", tmdbRouter);

app.get("/health", (_req, res) => {
  res.json({ status: "ok", time: new Date().toISOString() });
});

app.post("/devices/register", registerLimiter, async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
  }
  const { deviceId, deviceKey, platform, appVersion } = parsed.data;
  const now = new Date();
  const existing = db.devices[deviceId];

  if (existing) {
    if (!verifyKey(deviceKey, existing)) {
      return res.status(409).json({ error: "device_already_registered" });
    }
    existing.lastSeenAt = now.toISOString();
    if (appVersion) existing.appVersion = appVersion;
    await persist();
    return res.status(200).json({ registered: true, created: false, device: publicDevice(existing) });
  }

  const { salt, hash } = hashKey(deviceKey);
  const device: Device = {
    deviceId,
    keyHash: hash,
    keySalt: salt,
    platform,
    appVersion: appVersion ?? "unknown",
    license: { type: "trial", expiresAt: new Date(now.getTime() + TRIAL_DAYS * 86_400_000).toISOString() },
    createdAt: now.toISOString(),
    lastSeenAt: now.toISOString(),
  };
  db.devices[deviceId] = device;
  await persist();
  return res.status(201).json({ registered: true, created: true, device: publicDevice(device) });
});

app.get("/devices/status", requireDevice, async (req: AuthedRequest, res) => {
  const device = req.device!;
  device.lastSeenAt = new Date().toISOString();
  await persist();
  const playlists = db.playlists.filter((p) => p.deviceId === device.deviceId).length;
  res.json({ device: publicDevice(device), playlists });
});

app.get("/playlists", requireDevice, (req: AuthedRequest, res) => {
  const items = db.playlists.filter((p) => p.deviceId === req.device!.deviceId).map(publicPlaylist);
  res.json({ playlists: items });
});

app.post("/playlists", requireDevice, async (req: AuthedRequest, res) => {
  const device = req.device!;
  if (!currentLicense(device).active) {
    return res.status(402).json({ error: "license_expired" });
  }
  const parsed = playlistSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
  }
  const count = db.playlists.filter((p) => p.deviceId === device.deviceId).length;
  if (count >= 20) {
    return res.status(409).json({ error: "playlist_limit_reached" });
  }
  const playlist: Playlist = {
    id: randomUUID(),
    deviceId: device.deviceId,
    ...parsed.data,
    createdAt: new Date().toISOString(),
  };
  db.playlists.push(playlist);
  await persist();
  res.status(201).json({ playlist: publicPlaylist(playlist) });
});

app.use((_req, res) => {
  res.status(404).json({ error: "not_found" });
});

app.use((err: Error & { type?: string }, _req: Request, res: Response, _next: NextFunction) => {
  if (err.type === "entity.parse.failed") {
    return res.status(400).json({ error: "invalid_json" });
  }
  console.error(err);
  res.status(500).json({ error: "internal_error" });
});

await loadDb();
app.listen(PORT, () => {
  console.log(`Hugo Player API listening on http://localhost:${PORT}`);
});
