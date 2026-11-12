import { Router, type NextFunction, type Request, type Response } from "express";
import rateLimit from "express-rate-limit";
import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";

/**
 * Configs WireGuard (wg-quick .conf) por país, entregues automaticamente ao app.
 *
 * - Guardadas FORA do git e do db.json: <data>/vpn/<CC>.conf (pasta 700, arquivo 600).
 * - O conteúdo (tem a chave privada) nunca vai para log nem para o painel: o admin só vê país, hash,
 *   data, quantidade de peers e o host do Endpoint.
 * - App: GET /vpn/countries e GET /vpn/config/:cc com x-device-id + x-device-key de um aparelho com
 *   licença ou teste ativo. ETag = hash do conteúdo (If-None-Match → 304).
 * - Regras por host (opcional): <data>/vpn/hosts.json { "host.com": "US" } → a fonte com esse host
 *   vem com vpnCountry no GET /playlists e o app liga a VPN antes de baixar/tocar.
 */

export const COUNTRY_RE = /^[A-Z]{2}$/;
const MAX_CONF_BYTES = 8 * 1024;
const B64_KEY = /^[A-Za-z0-9+/]{42,43}=$/;

export interface VpnMeta {
  country: string;
  hash: string;
  updatedAt: string;
  peers: number;
  endpointHost: string | null;
}

/** Validação simples de wg-quick: [Interface] com PrivateKey + Address e pelo menos um [Peer] com PublicKey + Endpoint. */
export function validateWgConf(text: string): { ok: true; peers: number; endpointHost: string | null } | { ok: false; error: string } {
  if (typeof text !== "string" || !text.trim()) return { ok: false, error: "empty_config" };
  if (Buffer.byteLength(text, "utf8") > MAX_CONF_BYTES) return { ok: false, error: "config_too_large" };
  let section = "";
  let iface = 0, priv = false, addr = false, peers = 0;
  let curPeerPub = false, curPeerEnd = false, endpointHost: string | null = null;
  const closePeer = (): string | null => {
    if (section !== "peer") return null;
    if (!curPeerPub) return "peer_without_public_key";
    if (!curPeerEnd) return "peer_without_endpoint";
    return null;
  };
  for (const raw of text.replace(/^\uFEFF/, "").split(/\r?\n/)) {
    const line = raw.replace(/#.*$/, "").trim();
    if (!line) continue;
    const sec = /^\[(\w+)\]$/.exec(line);
    if (sec) {
      const err = closePeer();
      if (err) return { ok: false, error: err };
      section = sec[1].toLowerCase();
      if (section === "interface") iface++;
      else if (section === "peer") { peers++; curPeerPub = false; curPeerEnd = false; }
      else return { ok: false, error: "unknown_section" };
      continue;
    }
    const kv = /^([A-Za-z]+)\s*=\s*(.*)$/.exec(line);
    if (!kv || !section) return { ok: false, error: "invalid_line" };
    const k = kv[1].toLowerCase(), v = kv[2].trim();
    if (section === "interface") {
      if (k === "privatekey") { if (!B64_KEY.test(v)) return { ok: false, error: "invalid_private_key" }; priv = true; }
      if (k === "address") addr = Boolean(v);
    } else {
      if (k === "publickey") { if (!B64_KEY.test(v)) return { ok: false, error: "invalid_public_key" }; curPeerPub = true; }
      if (k === "endpoint") {
        const m = /^\[?([^\]]+?)\]?:(\d{1,5})$/.exec(v);
        if (!m) return { ok: false, error: "invalid_endpoint" };
        curPeerEnd = true;
        endpointHost ??= m[1];
      }
    }
  }
  const err = closePeer();
  if (err) return { ok: false, error: err };
  if (iface !== 1) return { ok: false, error: "needs_one_interface" };
  if (!priv) return { ok: false, error: "missing_private_key" };
  if (!addr) return { ok: false, error: "missing_address" };
  if (peers < 1) return { ok: false, error: "missing_peer" };
  return { ok: true, peers, endpointHost };
}

export function confHash(text: string): string {
  return createHash("sha256").update(text, "utf8").digest("hex").slice(0, 32);
}

export function normalizeHost(h: string): string | null {
  let s = String(h ?? "").trim().toLowerCase();
  if (!s) return null;
  try { if (/^[a-z]+:\/\//.test(s)) s = new URL(s).hostname; } catch { return null; }
  s = s.replace(/\/.*$/, "").replace(/:\d+$/, "").replace(/^\[|\]$/g, "");
  return /^[a-z0-9.\-:]{1,253}$/.test(s) && /[.:]|^localhost$/.test(s) ? s : null;
}

export function createVpnStore(dir: string) {
  const cache = new Map<string, { text: string; meta: VpnMeta }>();
  let hosts: Record<string, string> = {};

  async function ensureDir() {
    await fs.mkdir(dir, { recursive: true, mode: 0o700 });
    await fs.chmod(dir, 0o700).catch(() => {});
  }

  async function load() {
    await ensureDir();
    cache.clear();
    for (const f of await fs.readdir(dir)) {
      const m = /^([A-Z]{2})\.conf$/.exec(f);
      if (!m) continue;
      const file = path.join(dir, f);
      try {
        const [text, st] = await Promise.all([fs.readFile(file, "utf8"), fs.stat(file)]);
        const v = validateWgConf(text);
        if (!v.ok) { console.warn(`vpn: ${m[1]}.conf inválida (${v.error}), ignorada`); continue; }
        await fs.chmod(file, 0o600).catch(() => {});
        cache.set(m[1], { text, meta: { country: m[1], hash: confHash(text), updatedAt: st.mtime.toISOString(), peers: v.peers, endpointHost: v.endpointHost } });
      } catch { /* arquivo ilegível: ignora */ }
    }
    try {
      const raw = JSON.parse(await fs.readFile(path.join(dir, "hosts.json"), "utf8"));
      hosts = {};
      for (const [h, c] of Object.entries(raw ?? {})) {
        const host = normalizeHost(h), cc = String(c).toUpperCase();
        if (host && COUNTRY_RE.test(cc)) hosts[host] = cc;
      }
    } catch { hosts = {}; }
  }

  async function writePrivate(file: string, data: string) {
    await ensureDir();
    const tmp = `${file}.tmp`;
    await fs.writeFile(tmp, data, { mode: 0o600 });
    await fs.chmod(tmp, 0o600);
    await fs.rename(tmp, file);
  }

  return {
    load,
    list: (): VpnMeta[] => [...cache.values()].map((x) => x.meta).sort((a, b) => a.country.localeCompare(b.country)),
    get: (cc: string) => cache.get(cc.toUpperCase()),
    async put(cc: string, text: string): Promise<VpnMeta> {
      const c = cc.toUpperCase();
      if (!COUNTRY_RE.test(c)) throw new Error("invalid_country");
      const normalized = text.replace(/^\uFEFF/, "").replace(/\r\n/g, "\n").trim() + "\n";
      const v = validateWgConf(normalized);
      if (!v.ok) throw new Error(v.error);
      await writePrivate(path.join(dir, `${c}.conf`), normalized);
      const meta = { country: c, hash: confHash(normalized), updatedAt: new Date().toISOString(), peers: v.peers, endpointHost: v.endpointHost };
      cache.set(c, { text: normalized, meta });
      return meta;
    },
    async remove(cc: string): Promise<boolean> {
      const c = cc.toUpperCase();
      if (!COUNTRY_RE.test(c)) return false;
      const had = cache.delete(c);
      await fs.rm(path.join(dir, `${c}.conf`), { force: true });
      return had;
    },
    hosts: () => ({ ...hosts }),
    async setHost(host: string, cc: string | null): Promise<Record<string, string>> {
      const h = normalizeHost(host);
      if (!h) throw new Error("invalid_host");
      if (cc === null) delete hosts[h];
      else {
        const c = cc.toUpperCase();
        if (!COUNTRY_RE.test(c)) throw new Error("invalid_country");
        hosts[h] = c;
      }
      await writePrivate(path.join(dir, "hosts.json"), JSON.stringify(hosts, null, 2));
      return { ...hosts };
    },
    /** País exigido pela fonte (pelo host do link), se houver regra. */
    countryForUrl(url: string | undefined | null): string | undefined {
      if (!url) return undefined;
      let h: string | null = null;
      try { h = normalizeHost(new URL(/^https?:\/\//i.test(url) ? url : `http://${url}`).hostname); } catch { return undefined; }
      if (!h) return undefined;
      for (let cur = h; cur.includes("."); cur = cur.slice(cur.indexOf(".") + 1)) if (hosts[cur]) return hosts[cur];
      return undefined;
    },
  };
}

export type VpnStore = ReturnType<typeof createVpnStore>;

export interface VpnRouterDeps {
  store: VpnStore;
  /** "ok" = aparelho com licença/teste ativo; "inactive" = Key certa mas sem licença; null = credenciais inválidas */
  authorize: (deviceId: string | undefined, deviceKey: string | undefined) => { deviceId: string; status: "ok" | "inactive" } | null;
  adminToken: string;
  /** limite por aparelho/IP em 10 min (default 30) */
  limitPer10Min?: number;
}

/** App: lista de países e config de um país. */
export function createVpnRouter(deps: VpnRouterDeps): Router {
  const r = Router();
  r.use(rateLimit({
    windowMs: 10 * 60_000,
    limit: deps.limitPer10Min ?? 30,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req) => `${req.ip}|${String(req.header("x-device-id") ?? "").toUpperCase()}`,
    message: { error: "too_many_requests" },
  }));
  r.use((req: Request, res: Response, next: NextFunction) => {
    res.setHeader("Cache-Control", "private, no-store");
    const a = deps.authorize(req.header("x-device-id"), req.header("x-device-key"));
    if (!a) return res.status(401).json({ error: "invalid_credentials" });
    if (a.status !== "ok") return res.status(403).json({ error: "license_inactive" });
    next();
  });
  r.get("/countries", (_req, res) => {
    res.json({ countries: deps.store.list().map((m) => ({ country: m.country, hash: m.hash })) });
  });
  r.get("/config/:cc", (req, res) => {
    const cc = String(req.params.cc ?? "").toUpperCase();
    if (!COUNTRY_RE.test(cc)) return res.status(400).json({ error: "invalid_country" });
    const item = deps.store.get(cc);
    if (!item) return res.status(404).json({ error: "vpn_config_not_found" });
    const etag = `"${item.meta.hash}"`;
    res.setHeader("ETag", etag);
    if ((req.header("if-none-match") ?? "").split(",").map((s) => s.trim().replace(/^W\//, "")).includes(etag)) return res.status(304).end();
    res.json({ country: cc, hash: item.meta.hash, updatedAt: item.meta.updatedAt, config: item.text });
  });
  return r;
}

/** Admin (x-admin-token): listar, enviar, remover configs e regras de host. Nunca devolve o conteúdo da config. */
export function createVpnAdminRouter(deps: Pick<VpnRouterDeps, "store" | "adminToken">): Router {
  const r = Router();
  r.use((req, res, next) => {
    const t = req.header("x-admin-token");
    if (!deps.adminToken || !t || t !== deps.adminToken) return res.status(401).json({ error: "admin_unauthorized" });
    res.setHeader("Cache-Control", "no-store");
    next();
  });
  const out = () => ({ configs: deps.store.list(), hosts: deps.store.hosts() });
  r.get("/", (_req, res) => res.json(out()));
  r.put("/:cc", async (req, res) => {
    const cc = String(req.params.cc ?? "").toUpperCase();
    if (!COUNTRY_RE.test(cc)) return res.status(400).json({ error: "invalid_country" });
    try {
      const meta = await deps.store.put(cc, String(req.body?.config ?? ""));
      console.log(`vpn: config ${cc} salva pelo admin (hash ${meta.hash.slice(0, 8)}, ${meta.peers} peer)`);
      res.json({ ok: true, config: meta, ...out() });
    } catch (e) {
      res.status(400).json({ error: (e as Error).message || "invalid_config" });
    }
  });
  r.delete("/:cc", async (req, res) => {
    const cc = String(req.params.cc ?? "").toUpperCase();
    if (!COUNTRY_RE.test(cc)) return res.status(400).json({ error: "invalid_country" });
    const removed = await deps.store.remove(cc);
    console.log(`vpn: config ${cc} removida pelo admin`);
    res.json({ ok: true, removed, ...out() });
  });
  r.post("/hosts", async (req, res) => {
    const host = String(req.body?.host ?? "");
    const raw = req.body?.country;
    const cc = raw === null || raw === "" || raw === undefined ? null : String(raw);
    try {
      await deps.store.setHost(host, cc);
      res.json({ ok: true, ...out() });
    } catch (e) {
      res.status(400).json({ error: (e as Error).message });
    }
  });
  return r;
}
