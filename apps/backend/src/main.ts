import "dotenv/config";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { z } from "zod";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { tmdbRouter } from "./routes-tmdb.js";
import { recoveryRouter } from "./routes-recovery.js";
import { channelsRouter } from "./routes-channels.js";
import { createPlaylistRouter, type StoredPlaylist } from "./routes-playlists.js";
import { createEpgRouter } from "./routes-epg.js";
import type { PlaylistResolver } from "./routes-catalog.js";

const PORT = Number(process.env.PORT ?? 8080);
const DATA_FILE = path.resolve(process.env.DATA_FILE ?? "./data/db.json");
const TRIAL_DAYS = Number(process.env.TRIAL_DAYS ?? 7);

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

interface Db {
  devices: Record<string, Device>;
  playlists: StoredPlaylist[];
}

let db: Db = { devices: {}, playlists: [] };
let writeChain: Promise<void> = Promise.resolve();

async function loadDb(): Promise<void> {
  try {
    db = JSON.parse(await fs.readFile(DATA_FILE, "utf8")) as Db;
    db.devices ??= {};
    db.playlists ??= [];
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

const registerSchema = z.object({
  deviceId: z.string().min(4).max(128),
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
// M3U enviada pelo app (fallback 403) — text/plain até 40 MB
app.use("/channels/from-m3u", express.text({ type: ["text/*", "application/octet-stream", "text/plain"], limit: "40mb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 200 }));

app.use("/tmdb", tmdbRouter);
app.use("/recovery", recoveryRouter);
app.use("/channels", channelsRouter);

app.get("/health", (_req, res) => {
  res.json({ status: "ok", time: new Date().toISOString() });
});

app.post("/devices/register", async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
  const { deviceId, deviceKey, platform, appVersion } = parsed.data;
  const now = new Date();
  const existing = db.devices[deviceId];
  if (existing) {
    if (!verifyKey(deviceKey, existing)) return res.status(409).json({ error: "device_already_registered" });
    existing.lastSeenAt = now.toISOString();
    existing.platform = platform;
    if (appVersion) existing.appVersion = appVersion;
    await persist();
    return res.json({ registered: true, created: false });
  }
  const { salt, hash } = hashKey(deviceKey);
  const device: Device = {
    deviceId,
    keyHash: hash,
    keySalt: salt,
    platform,
    appVersion: appVersion ?? "",
    license: { type: "trial", expiresAt: new Date(now.getTime() + TRIAL_DAYS * 86400000).toISOString() },
    createdAt: now.toISOString(),
    lastSeenAt: now.toISOString(),
  };
  db.devices[deviceId] = device;
  await persist();
  res.status(201).json({ registered: true, created: true });
});

function authDevice(id: string | undefined, key: string | undefined): Device | null {
  if (!id || !key) return null;
  const device = db.devices[id.toUpperCase()] ?? db.devices[id];
  if (!device || !verifyKey(key.toUpperCase(), device) && !verifyKey(key, device)) return null;
  return device;
}

function requireDevice(req: Request, res: Response, next: NextFunction) {
  const device = authDevice(req.header("x-device-id"), req.header("x-device-key"));
  if (!device) return res.status(401).json({ error: "invalid_credentials" });
  (req as any).device = device;
  next();
}

app.get("/devices/status", requireDevice, (req, res) => {
  const device = (req as any).device as Device;
  const expiresAt = device.license.expiresAt ? new Date(device.license.expiresAt) : null;
  const expired = expiresAt !== null && expiresAt.getTime() < Date.now();
  res.json({
    deviceId: device.deviceId,
    platform: device.platform,
    license: {
      type: expired ? "expired" : device.license.type,
      expiresAt: device.license.expiresAt,
    },
    playlists: db.playlists.filter((p) => p.deviceId === device.deviceId).length,
    createdAt: device.createdAt,
    lastSeenAt: device.lastSeenAt,
  });
});

app.use(
  "/playlists",
  createPlaylistRouter({
    playlists: () => db.playlists,
    setPlaylists: (next) => {
      db.playlists = next;
    },
    persist,
    authenticate: (id, key) => authDevice(id, key)?.deviceId ?? null,
  }),
);

const resolvePlaylist: PlaylistResolver = (req) => {
  const device = authDevice(req.header("x-device-id"), req.header("x-device-key"));
  const playlistId = String(req.query.playlistId || "");
  if (playlistId && device) {
    const p = db.playlists.find((x) => x.id === playlistId && x.deviceId === device.deviceId);
    if (!p) return "unauthorized";
    return { type: p.type, url: p.url };
  }
  const url = String(req.query.url || "");
  if (url) return { type: (req.query.type as "m3u" | "xtream") || "m3u", url };
  return null;
};
app.use("/epg", createEpgRouter(resolvePlaylist));

app.use((_req, res) => res.status(404).json({ error: "not_found" }));

await loadDb();
app.listen(PORT, () => console.log(`Hugo Player API on port ${PORT}`));
