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

function hashKey(key: string, salt = randomBytes(16).toString("hex")) {
  return { salt, hash: scryptSync(key, salt, 64).toString("hex") };
}

function verifyKey(key: string, device: Device): boolean {
  const candidate = scryptSync(key, device.keySalt, 64);
  const stored = Buffer.from(device.keyHash, "hex");
  return candidate.length === stored.length && timingSafeEqual(candidate, stored);
}

const deviceIdSchema = z.string().trim().toUpperCase().regex(/^([0-9A-F]{2}:){5}[0-9A-F]{2}$/);
const deviceKeySchema = z.string().trim().toUpperCase().regex(/^[A-HJ-NP-Z2-9]{8}$/);

const registerSchema = z.object({
  deviceId: deviceIdSchema,
  deviceKey: deviceKeySchema,
  platform: z.enum(["android", "android-tv", "web"]).default("android"),
  appVersion: z.string().trim().min(1).max(32).optional(),
});

const app = express();
app.disable("x-powered-by");
app.set("trust proxy", 1);
app.use(helmet());
app.use(cors({ origin: CORS_ORIGIN === "*" ? true : CORS_ORIGIN.split(",").map(o => o.trim()) }));
app.use(express.json({ limit: "32kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 120 }));

app.use("/tmdb", tmdbRouter);
app.use("/recovery", recoveryRouter);

app.get("/health", (_req, res) => {
  res.json({ status: "ok", time: new Date().toISOString() });
});

app.post("/devices/register", async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "validation_error" });
  const { deviceId, deviceKey, platform, appVersion } = parsed.data;
  const now = new Date();
  const existing = db.devices[deviceId];
  if (existing) {
    if (!verifyKey(deviceKey, existing)) return res.status(409).json({ error: "device_already_registered" });
    existing.lastSeenAt = now.toISOString();
    await persist();
    return res.json({ registered: true, device: existing });
  }
  const { salt, hash } = hashKey(deviceKey);
  const device: Device = {
    deviceId, keyHash: hash, keySalt: salt, platform,
    appVersion: appVersion ?? "unknown",
    license: { type: "trial", expiresAt: new Date(now.getTime() + TRIAL_DAYS * 86400000).toISOString() },
    createdAt: now.toISOString(), lastSeenAt: now.toISOString()
  };
  db.devices[deviceId] = device;
  await persist();
  res.status(201).json({ registered: true, device });
});

app.use((_req, res) => res.status(404).json({ error: "not_found" }));

await loadDb();
app.listen(PORT, () => console.log(`Hugo Player API on port ${PORT}`));
