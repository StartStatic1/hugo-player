import "dotenv/config";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import cors from "cors";
import rateLimit from "express-rate-limit";
import compression from "compression";
import { z } from "zod";
import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { tmdbRouter } from "./routes-tmdb.js";
import { recoveryRouter } from "./routes-recovery.js";
import { channelsRouter } from "./routes-channels.js";
import { createCatalogRouter, type PlaylistResolver } from "./routes-catalog.js";
import { createEpgRouter } from "./routes-epg.js";

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

const deviceIdSchema = z.string().trim().toUpperCase();
const deviceKeySchema = z.string().trim().toUpperCase().min(4).max(16);

const registerSchema = z.object({
  deviceId: deviceIdSchema,
  deviceKey: deviceKeySchema,
  platform: z.enum(["android", "android-tv", "web"]).default("android"),
  appVersion: z.string().optional(),
});

const playlistSchema = z.object({
  name: z.string().trim().min(1).max(80),
  type: z.enum(["m3u", "xtream"]).default("m3u"),
  url: z.string().trim().min(5),
  // Xtream: opcionalmente servidor + usuário + senha separados
  username: z.string().trim().min(1).max(200).optional(),
  password: z.string().trim().min(1).max(200).optional(),
});

/** Para Xtream com usuário/senha separados, monta a URL get.php (usada também pelo /catalog). */
function normalizePlaylistUrl(p: z.infer<typeof playlistSchema>): string {
  if (p.type !== "xtream" || !p.username || !p.password) return p.url;
  const base = new URL(/^https?:\/\//i.test(p.url) ? p.url : `http://${p.url}`);
  const u = new URL("/get.php", `${base.protocol}//${base.host}`);
  u.searchParams.set("username", p.username);
  u.searchParams.set("password", p.password);
  u.searchParams.set("type", "m3u_plus");
  u.searchParams.set("output", "m3u8");
  return u.toString();
}

const app = express();
app.disable("x-powered-by");
app.set("trust proxy", 1);
app.use(helmet());
app.use(compression());
app.use(cors({ origin: true }));
const jsonSmall = express.json({ limit: "32kb" });
// /epg aceita corpos maiores (lista de ids); demais rotas ficam com 32 KB
app.use((req, res, next) => (req.path.startsWith("/epg") ? next() : jsonSmall(req, res, next)));
app.use(rateLimit({ windowMs: 60_000, limit: 200 }));

app.use("/tmdb", tmdbRouter);
app.use("/recovery", recoveryRouter);
app.use("/channels", channelsRouter);
/**
 * Resolve a playlist da requisição: playlistId (query ou body) + credenciais
 * do dispositivo, ou URL direta (?url=, compatível com /channels).
 */
const resolvePlaylist: PlaylistResolver = (req) => {
  const playlistId = (req.query.playlistId as string | undefined) ?? (req.body?.playlistId as string | undefined);
  if (playlistId) {
    const device = authDevice(req.header("x-device-id"), req.header("x-device-key"));
    if (!device) return "unauthorized";
    const p = db.playlists.find((x) => x.id === playlistId && x.deviceId === device.deviceId);
    return p ? { url: p.url, type: p.type } : null;
  }
  const url = (req.query.url as string | undefined) ?? (req.body?.url as string | undefined);
  const type = (req.query.type as string | undefined) ?? (req.body?.type as string | undefined);
  return url ? { url, type } : null;
};

app.use("/catalog", createCatalogRouter(resolvePlaylist));
app.use("/epg", express.json({ limit: "256kb" }), createEpgRouter(resolvePlaylist));

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
    await persist();
    return res.json({ registered: true, created: false });
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
  res.status(201).json({ registered: true, created: true });
});

function authDevice(id: string | undefined, key: string | undefined): Device | null {
  if (!id || !key) return null;
  const device = db.devices[id.toUpperCase()];
  if (!device || !verifyKey(key.toUpperCase(), device)) return null;
  return device;
}

function requireDevice(req: Request, res: Response, next: NextFunction) {
  const device = authDevice(req.header("x-device-id"), req.header("x-device-key"));
  if (!device) return res.status(401).json({ error: "invalid_credentials" });
  (req as any).device = device;
  next();
}

app.get("/playlists", requireDevice, (req, res) => {
  const device = (req as any).device as Device;
  const items = db.playlists.filter(p => p.deviceId === device.deviceId).map(p => ({
    id: p.id, name: p.name, type: p.type, url: p.url
  }));
  res.json({ playlists: items });
});

app.post("/playlists", requireDevice, async (req, res) => {
  const device = (req as any).device as Device;
  const parsed = playlistSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
  let url: string;
  try {
    url = normalizePlaylistUrl(parsed.data);
  } catch {
    return res.status(400).json({ error: "invalid_url" });
  }
  const playlist: Playlist = {
    id: randomUUID(),
    deviceId: device.deviceId,
    name: parsed.data.name,
    type: parsed.data.type,
    url,
    createdAt: new Date().toISOString(),
  };
  db.playlists.push(playlist);
  await persist();
  res.status(201).json({ playlist: { id: playlist.id, name: playlist.name } });
});

app.use((_req, res) => res.status(404).json({ error: "not_found" }));

await loadDb();
app.listen(PORT, () => console.log(`Hugo Player API on port ${PORT}`));
