import { Router, type Request, type Response, type NextFunction } from "express";
import { z } from "zod";

type LicenseType = "trial" | "premium" | "expired";
type LicensePlan = "trial" | "annual" | "lifetime";

export interface AdminDevice {
  deviceId: string;
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
}

export interface AdminDeps {
  devices: () => Record<string, AdminDevice>;
  persist: () => Promise<void>;
  adminToken: string;
  playlistCount: (deviceId: string) => number;
  deleteDevice: (deviceId: string) => void;
  resetDeviceKey: (deviceId: string) => string | null;
}

const planSchema = z.object({
  plan: z.enum(["annual", "lifetime"]),
  transactionNsu: z.string().trim().max(200).optional(),
});

function activeLicense(plan: LicensePlan): { type: LicenseType; plan: LicensePlan; expiresAt: string | null } {
  if (plan === "lifetime") return { type: "premium", plan, expiresAt: null };
  const expiresAt = new Date(Date.now() + 365 * 86400000).toISOString();
  return { type: "premium", plan, expiresAt };
}

export function createAdminRouter(deps: AdminDeps): Router {
  const r = Router();

  function requireAdmin(req: Request, res: Response, next: NextFunction) {
    const token = req.header("x-admin-token");
    if (!deps.adminToken || !token || token !== deps.adminToken) {
      return res.status(401).json({ error: "admin_unauthorized" });
    }
    next();
  }

  function findDevice(id: string): AdminDevice | undefined {
    const raw = id.trim().toUpperCase();
    return deps.devices()[raw] ?? Object.values(deps.devices()).find((d) => d.deviceId.toUpperCase() === raw);
  }

  r.use(requireAdmin);

  r.get("/devices", (req, res) => {
    const query = String(req.query.q ?? "").trim().toUpperCase();
    const devices = Object.values(deps.devices())
      .filter((d) => !query || d.deviceId.toUpperCase().includes(query))
      .map((d) => ({
        deviceId: d.deviceId,
        platform: d.platform,
        appVersion: d.appVersion,
        playlistCount: deps.playlistCount(d.deviceId),
        license: d.license,
        createdAt: d.createdAt,
        lastSeenAt: d.lastSeenAt,
        blockedAt: d.blockedAt,
        blockedReason: d.blockedReason,
        revokedAt: d.revokedAt,
        revokedReason: d.revokedReason,
      }));
    res.json({ devices });
  });

  r.post("/devices/:id/activate", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    const parsed = planSchema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error", details: parsed.error.flatten() });
    const license = activeLicense(parsed.data.plan);
    device.license = {
      ...license,
      paymentProvider: parsed.data.transactionNsu ? "manual-test" : device.license.paymentProvider,
      transactionNsu: parsed.data.transactionNsu ?? device.license.transactionNsu,
    };
    delete device.revokedAt;
    delete device.revokedReason;
    await deps.persist();
    res.json({ ok: true, deviceId: device.deviceId, license: device.license });
  });

  r.post("/devices/:id/test", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    const days = Math.max(1, Math.min(30, Number(req.body?.days ?? 7)));
    device.license = {
      type: "trial",
      plan: "trial",
      expiresAt: new Date(Date.now() + days * 86400000).toISOString(),
      paymentProvider: "admin-test",
    };
    delete device.revokedAt;
    delete device.revokedReason;
    await deps.persist();
    res.json({ ok: true, deviceId: device.deviceId, license: device.license });
  });

  r.post("/devices/:id/reset-key", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    const deviceKey = deps.resetDeviceKey(device.deviceId);
    if (!deviceKey) return res.status(404).json({ error: "device_not_found" });
    await deps.persist();
    res.json({
      ok: true,
      deviceId: device.deviceId,
      deviceKey,
      warning: "A Key anterior foi invalidada. Guarde esta Key agora; ela não será exibida novamente.",
    });
  });

  r.post("/devices/:id/block", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    device.blockedAt = new Date().toISOString();
    device.blockedReason = String(req.body?.reason ?? "bloqueado pelo administrador").slice(0, 200);
    await deps.persist();
    res.json({ ok: true, deviceId: device.deviceId, blockedAt: device.blockedAt });
  });

  r.post("/devices/:id/revoke", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    if (device.license.type !== "premium") return res.status(409).json({ error: "device_not_premium" });
    const now = new Date().toISOString();
    device.license = {
      type: "expired",
      plan: "trial",
      expiresAt: now,
      paymentProvider: "admin-revoked",
    };
    device.revokedAt = now;
    device.revokedReason = String(req.body?.reason ?? "licença revogada pelo administrador").slice(0, 200);
    await deps.persist();
    res.json({ ok: true, deviceId: device.deviceId, license: device.license, revokedAt: now });
  });

  r.post("/devices/:id/unblock", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    delete device.blockedAt;
    delete device.blockedReason;
    await deps.persist();
    res.json({ ok: true, deviceId: device.deviceId, unblocked: true });
  });

  r.delete("/devices/:id", async (req, res) => {
    const device = findDevice(req.params.id);
    if (!device) return res.status(404).json({ error: "device_not_found" });
    if (device.revokedAt) return res.status(409).json({ error: "revoked_device_protected" });
    const plan = device.license.plan ?? (device.license.type === "premium" ? "annual" : "trial");
    if (device.license.type === "premium" || plan !== "trial") {
      return res.status(409).json({ error: "premium_device_protected" });
    }
    const count = deps.playlistCount(device.deviceId);
    if (count > 0) return res.status(409).json({ error: "device_has_playlists", count });
    deps.deleteDevice(device.deviceId);
    await deps.persist();
    res.json({ ok: true, deleted: device.deviceId });
  });

  return r;
}
