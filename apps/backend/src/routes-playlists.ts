import { Router, type NextFunction, type Request, type Response } from "express";
import rateLimit from "express-rate-limit";
import { z } from "zod";
import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";

/**
 * Gerenciamento de playlists por dispositivo (site + app).
 * - CRUD: GET / POST / PATCH /:id / DELETE /:id
 * - PIN opcional por playlist (guardado só como hash scrypt + salt).
 *   Playlists protegidas não expõem a URL na listagem; é preciso
 *   mandar o PIN (header x-playlist-pin ou POST /:id/verify-pin).
 */

export interface StoredPlaylist {
  id: string;
  deviceId: string;
  name: string;
  type: "m3u" | "xtream";
  url: string;
  createdAt: string;
  updatedAt?: string;
  pinHash?: string;
  pinSalt?: string;
}

export interface PlaylistDeps {
  /** Lista mutável de playlists (todas as do banco). */
  playlists: () => StoredPlaylist[];
  setPlaylists: (next: StoredPlaylist[]) => void;
  persist: () => Promise<void>;
  /** Autentica pelo par Device ID + Key; retorna o deviceId normalizado ou null. */
  authenticate: (deviceId: string | undefined, deviceKey: string | undefined) => string | null;
  /** Limite de tentativas de PIN por minuto (default 20). */
  pinAttemptsPerMinute?: number;
}

export const pinSchema = z.string().trim().regex(/^\d{4,8}$/, "PIN deve ter de 4 a 8 dígitos");

export function hashPin(pin: string, salt = randomBytes(16).toString("hex")) {
  return { salt, hash: scryptSync(pin, salt, 32).toString("hex") };
}

export function checkPin(pin: string | undefined, p: Pick<StoredPlaylist, "pinHash" | "pinSalt">): boolean {
  if (!p.pinHash || !p.pinSalt) return true;
  if (!pin) return false;
  const candidate = scryptSync(String(pin).trim(), p.pinSalt, 32);
  const stored = Buffer.from(p.pinHash, "hex");
  return candidate.length === stored.length && timingSafeEqual(candidate, stored);
}

const baseFields = {
  name: z.string().trim().min(1).max(80),
  type: z.enum(["m3u", "xtream"]).default("m3u"),
  url: z.string().trim().min(5).max(2000),
  username: z.string().trim().min(1).max(200).optional(),
  password: z.string().trim().min(1).max(200).optional(),
};

export const createSchema = z.object({ ...baseFields, pin: pinSchema.optional() });

export const patchSchema = z
  .object({
    name: baseFields.name.optional(),
    type: baseFields.type.optional(),
    url: baseFields.url.optional(),
    username: baseFields.username,
    password: baseFields.password,
    /** Define/troca o PIN. */
    newPin: pinSchema.optional(),
    /** Remove a proteção por PIN. */
    removePin: z.boolean().optional(),
  })
  .refine(v => !(v.newPin && v.removePin), { message: "newPin e removePin são exclusivos" });

export function buildPlaylistUrl(p: { type?: "m3u" | "xtream"; url: string; username?: string; password?: string }): string {
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(p.url) && !/^https?:\/\//i.test(p.url)) throw new Error("invalid_protocol");
  const raw = /^https?:\/\//i.test(p.url) ? p.url : `http://${p.url}`;
  const base = new URL(raw); // lança se inválida
  if (!/^https?:$/.test(base.protocol)) throw new Error("invalid_protocol");
  if (p.type !== "xtream" || !p.username || !p.password) return raw;
  const u = new URL("/get.php", `${base.protocol}//${base.host}`);
  u.searchParams.set("username", p.username);
  u.searchParams.set("password", p.password);
  u.searchParams.set("type", "m3u_plus");
  u.searchParams.set("output", "m3u8");
  return u.toString();
}

export function publicView(p: StoredPlaylist, unlocked: boolean) {
  const isProtected = Boolean(p.pinHash);
  return {
    id: p.id,
    name: p.name,
    type: p.type,
    protected: isProtected,
    url: isProtected && !unlocked ? null : p.url,
    createdAt: p.createdAt,
    updatedAt: p.updatedAt ?? p.createdAt,
  };
}

function pinFrom(req: Request): string | undefined {
  const h = req.header("x-playlist-pin");
  if (h) return h;
  const b = (req.body ?? {}) as Record<string, unknown>;
  return typeof b.pin === "string" ? b.pin : undefined;
}

export function createPlaylistRouter(deps: PlaylistDeps): Router {
  const r = Router();

  const pinLimiter = rateLimit({
    windowMs: 60_000,
    limit: deps.pinAttemptsPerMinute ?? 20,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: req => `${req.ip}|${String(req.header("x-device-id") ?? "").toUpperCase()}`,
    message: { error: "too_many_pin_attempts" },
  });

  function requireDevice(req: Request, res: Response, next: NextFunction) {
    const deviceId = deps.authenticate(req.header("x-device-id"), req.header("x-device-key"));
    if (!deviceId) return res.status(401).json({ error: "invalid_credentials" });
    res.locals.deviceId = deviceId;
    next();
  }

  function findOwned(req: Request, res: Response): StoredPlaylist | null {
    const p = deps.playlists().find(x => x.id === req.params.id && x.deviceId === res.locals.deviceId);
    if (!p) {
      res.status(404).json({ error: "playlist_not_found" });
      return null;
    }
    return p;
  }

  r.use(requireDevice);

  r.get("/", (req, res) => {
    const pin = req.header("x-playlist-pin");
    const items = deps
      .playlists()
      .filter(p => p.deviceId === res.locals.deviceId)
      .map(p => publicView(p, Boolean(pin) && checkPin(pin, p)));
    res.json({ playlists: items });
  });

  r.post("/", async (req, res) => {
    const parsed = createSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
    let url: string;
    try {
      url = buildPlaylistUrl(parsed.data);
    } catch {
      return res.status(400).json({ error: "invalid_url" });
    }
    const now = new Date().toISOString();
    const playlist: StoredPlaylist = {
      id: randomUUID(),
      deviceId: res.locals.deviceId,
      name: parsed.data.name,
      type: parsed.data.type,
      url,
      createdAt: now,
      updatedAt: now,
    };
    if (parsed.data.pin) {
      const { salt, hash } = hashPin(parsed.data.pin);
      playlist.pinSalt = salt;
      playlist.pinHash = hash;
    }
    deps.setPlaylists([...deps.playlists(), playlist]);
    await deps.persist();
    res.status(201).json({ playlist: publicView(playlist, true) });
  });

  r.post("/:id/verify-pin", pinLimiter, (req, res) => {
    const p = findOwned(req, res);
    if (!p) return;
    if (!p.pinHash) return res.json({ ok: true, playlist: publicView(p, true) });
    if (!checkPin(pinFrom(req), p)) return res.status(403).json({ error: "invalid_pin" });
    res.json({ ok: true, playlist: publicView(p, true) });
  });

  r.patch("/:id", pinLimiter, async (req, res) => {
    const p = findOwned(req, res);
    if (!p) return;
    if (!checkPin(pinFrom(req), p)) return res.status(403).json({ error: p.pinHash ? "invalid_pin" : "pin_required" });
    const parsed = patchSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
    const v = parsed.data;
    if (v.url !== undefined || v.type !== undefined) {
      try {
        p.url = buildPlaylistUrl({ type: v.type ?? p.type, url: v.url ?? p.url, username: v.username, password: v.password });
      } catch {
        return res.status(400).json({ error: "invalid_url" });
      }
      if (v.type) p.type = v.type;
    }
    if (v.name !== undefined) p.name = v.name;
    if (v.removePin) {
      delete p.pinHash;
      delete p.pinSalt;
    } else if (v.newPin) {
      const { salt, hash } = hashPin(v.newPin);
      p.pinSalt = salt;
      p.pinHash = hash;
    }
    p.updatedAt = new Date().toISOString();
    await deps.persist();
    res.json({ playlist: publicView(p, true) });
  });

  r.delete("/:id", pinLimiter, async (req, res) => {
    const p = findOwned(req, res);
    if (!p) return;
    if (!checkPin(pinFrom(req), p)) return res.status(403).json({ error: "invalid_pin" });
    deps.setPlaylists(deps.playlists().filter(x => x.id !== p.id));
    await deps.persist();
    res.json({ deleted: true, id: p.id });
  });

  return r;
}
