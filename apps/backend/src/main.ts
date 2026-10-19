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

function hashKey(key: string, salt: string): string {
  return scryptSync(key, salt, 32).toString("hex");
}

function verifyKey(key: string, salt: string, hash: string): boolean {
  try {
    const a = Buffer.from(hashKey(key, salt), "hex");
    const b = Buffer.from(hash, "hex");
    return a.length === b.length && timingSafeEqual(a, b);
  } catch {
    return false;
  }
}

function newDeviceKey(): string {
  return randomBytes(4).toString("hex").toUpperCase();
}

const app = express();
app.set("trust proxy", 1);
app.use(helmet());
app.use(cors({ origin: true }));
app.use(express.json({ limit: "32kb" }));
// M3U enviada pelo app (fallback 403) — só text/plain, limite 40 MB
app.use("/channels/from-m3u", express.text({ type: ["text/*", "application/octet-stream", "text/plain"], limit: "40mb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 200 }));

app.use("/tmdb", tmdbRouter);
app.use("/recovery", recoveryRouter);
app.use("/channels", channelsRouter);

app.get("/health", (_req, res) => res.json({ ok: true }));

app.post("/devices/register", async (req, res) => {
  const schema = z.object({
    deviceId: z.string().min(4).max(128),
    platform: z.string().max(64).optional(),
    appVersion: z.string().max(32).optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "invalid_body" });
  const { deviceId, platform = "android", appVersion = "0" } = parsed.data;
  const existing = db.devices[deviceId];
  if (existing) {
    existing.lastSeenAt = new Date().toISOString();
    existing.appVersion = appVersion;
    await persist();
    return res.json({
      deviceId,
      alreadyRegistered: true,
      license: existing.license,
    });
  }
  const key = newDeviceKey();
  const salt = randomBytes(16).toString("hex");
  const expires = new Date();
  expires.setDate(expires.getDate() + TRIAL_DAYS);
  db.devices[deviceId] = {
    deviceId,
    keyHash: hashKey(key, salt),
    keySalt: salt,
    platform,
    appVersion,
    license: { type: "trial", expiresAt: expires.toISOString() },
    createdAt: new Date().toISOString(),
    lastSeenAt: new Date().toISOString(),
  };
  await persist();
  res.json({ deviceId, deviceKey: key, license: db.devices[deviceId].license });
});

app.get("/devices/status", (req, res) => {
  const deviceId = String(req.headers["x-device-id"] || "");
  const deviceKey = String(req.headers["x-device-key"] || "");
  const d = db.devices[deviceId];
  if (!d || !verifyKey(deviceKey, d.keySalt, d.keyHash)) {
    return res.status(401).json({ error: "unauthorized" });
  }
  d.lastSeenAt = new Date().toISOString();
  res.json({ deviceId, license: d.license });
});

const resolvePlaylist: PlaylistResolver = (id) => db.playlists.find((p) => p.id === id) ?? null;

app.use(
  "/playlists",
  createPlaylistRouter({
    list: () => db.playlists,
    get: (id) => db.playlists.find((p) => p.id === id),
    save: async (p) => {
      const i = db.playlists.findIndex((x) => x.id === p.id);
      if (i >= 0) db.playlists[i] = p;
      else db.playlists.push(p);
      await persist();
    },
    remove: async (id) => {
      db.playlists = db.playlists.filter((p) => p.id !== id);
      await persist();
    },
  }),
);

app.use("/epg", createEpgRouter(resolvePlaylist));

app.use((_req, res) => res.status(404).json({ error: "not_found" }));
app.use((err: any, _req: Request, res: Response, _next: NextFunction) => {
  console.error(err);
  res.status(500).json({ error: err?.message || "internal_error" });
});

await loadDb();
app.listen(PORT, () => console.log(`hugo-api on :${PORT}`));
