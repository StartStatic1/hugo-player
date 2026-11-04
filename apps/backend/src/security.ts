import { timingSafeEqual } from "node:crypto";
import type { NextFunction, Request, Response } from "express";

/**
 * Alertas de atividade suspeita + banimento automático de IP (24 h).
 * - admin_401: token de admin errado em /admin
 * - rate_429: limite de requisições estourado (backend ou nginx)
 * - device_burst: mesmo IP criando >= 3 aparelhos em 10 min
 * - blocked_ip: acesso de IP bloqueado/banido
 * Banimento: >= BAN_THRESHOLD eventos de abuso (admin_401, rate_429, device_burst) em 10 min.
 * Token de admin válido SEMPRE passa (o dono nunca fica trancado); IPs da lista segura nunca são banidos.
 */
export type AlertKind = "admin_401" | "rate_429" | "device_burst" | "blocked_ip";
export interface AlertEvent { kind: AlertKind; ip: string; first: string; last: string; count: number; path?: string; ua?: string }
export interface Ban { ip: string; at: string; until: string; reason: string; auto: boolean }
export interface SecurityStore { events: AlertEvent[]; bans: Record<string, Ban> }

export const WINDOW_MS = 10 * 60 * 1000;
export const BAN_MS = 24 * 3600 * 1000;
export const BAN_THRESHOLD = 5;
const MAX_EVENTS = 500;
const ABUSE: AlertKind[] = ["admin_401", "rate_429", "device_burst"];

export interface SecurityDeps {
  store: () => SecurityStore;
  persist: () => void | Promise<void>;
  adminToken: string;
  allowIps: Set<string>;
  now?: () => number;
}

export function ensureSecurityStore<T extends { security?: SecurityStore }>(db: T): SecurityStore {
  db.security ??= { events: [], bans: {} };
  db.security.events ??= [];
  db.security.bans ??= {};
  return db.security;
}

export function createSecurity(deps: SecurityDeps) {
  const now = () => (deps.now ? deps.now() : Date.now());
  const hits = new Map<string, number[]>(); // ip -> horários de abuso (janela 10 min)
  let saveTimer: NodeJS.Timeout | null = null;
  const save = () => {
    if (saveTimer) return;
    saveTimer = setTimeout(() => { saveTimer = null; void deps.persist(); }, 2000);
    saveTimer.unref?.();
  };

  function validAdmin(req: Request): boolean {
    const t = req.header("x-admin-token");
    if (!deps.adminToken || !t) return false;
    const a = Buffer.from(t), b = Buffer.from(deps.adminToken);
    return a.length === b.length && timingSafeEqual(a, b);
  }

  function activeBan(ip: string): Ban | null {
    const s = deps.store();
    const b = s.bans[ip];
    if (!b) return null;
    if (new Date(b.until).getTime() <= now()) { delete s.bans[ip]; save(); return null; }
    return b;
  }

  function ban(ip: string, reason: string, auto: boolean, ms = BAN_MS): Ban | null {
    if (!ip || deps.allowIps.has(ip)) return null;
    const t = now();
    const b: Ban = { ip, at: new Date(t).toISOString(), until: new Date(t + ms).toISOString(), reason, auto };
    deps.store().bans[ip] = b;
    save();
    console.warn(`security: IP banido ${ip} (${reason})`);
    return b;
  }

  function record(kind: AlertKind, ip: string, info: { path?: string; ua?: string } = {}) {
    if (!ip) return;
    const t = now(), iso = new Date(t).toISOString();
    const s = deps.store();
    // agrupa: mesmo IP + tipo dentro de 10 min vira 1 alerta com contagem
    const ev = s.events.find((e) => e.ip === ip && e.kind === kind && t - new Date(e.last).getTime() <= WINDOW_MS);
    if (ev) { ev.count++; ev.last = iso; if (info.path) ev.path = info.path; }
    else {
      s.events.unshift({ kind, ip, first: iso, last: iso, count: 1, ...(info.path ? { path: info.path.slice(0, 80) } : {}), ...(info.ua ? { ua: info.ua.slice(0, 160) } : {}) });
      if (s.events.length > MAX_EVENTS) s.events.length = MAX_EVENTS;
    }
    save();
    if (ABUSE.includes(kind)) {
      const arr = (hits.get(ip) ?? []).filter((x) => t - x <= WINDOW_MS);
      arr.push(t);
      hits.set(ip, arr);
      if (arr.length >= BAN_THRESHOLD && !activeBan(ip)) {
        ban(ip, `automático: ${arr.length} eventos suspeitos em 10 min (${kind})`, true);
        hits.delete(ip);
      }
    }
  }

  /** Bloqueia IP banido/da lista negra — exceto com token de admin válido. Também anota 401/429 de /admin e limites. */
  function middleware(blockedIps: Set<string>, clientIp: (req: Request) => string) {
    return (req: Request, res: Response, next: NextFunction) => {
      const ip = clientIp(req);
      const admin = validAdmin(req);
      if (!admin && (blockedIps.has(ip) || activeBan(ip))) {
        record("blocked_ip", ip, { path: req.path, ua: req.header("user-agent") ?? "" });
        return res.status(403).json({ error: "forbidden" });
      }
      const path = req.path; // capturado antes dos routers montados reescreverem req.url
      res.on("finish", () => {
        if (res.statusCode === 401 && path.startsWith("/admin")) record("admin_401", ip, { path, ua: req.header("user-agent") ?? "" });
        else if (res.statusCode === 429) record("rate_429", ip, { path, ua: req.header("user-agent") ?? "" });
      });
      next();
    };
  }

  function summary() {
    const s = deps.store(), t = now();
    for (const ip of Object.keys(s.bans)) activeBan(ip);
    const day = s.events.filter((e) => t - new Date(e.last).getTime() <= 24 * 3600 * 1000);
    return { alerts: s.events.slice(0, 200), last24h: day.length, bans: Object.values(s.bans), allowIps: [...deps.allowIps] };
  }

  function unban(ip: string): boolean {
    const s = deps.store();
    if (!s.bans[ip]) return false;
    delete s.bans[ip];
    hits.delete(ip);
    save();
    return true;
  }

  return { record, middleware, summary, ban, unban, activeBan, validAdmin };
}
export type Security = ReturnType<typeof createSecurity>;
