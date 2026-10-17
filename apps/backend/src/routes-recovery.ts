import { Router } from "express";
import { createRecovery, type RecoveryEntry } from "./recovery.js";

const recoveries: RecoveryEntry[] = [];

export const recoveryRouter = Router();

recoveryRouter.post("/generate", (req, res) => {
  const { deviceId } = req.body;
  if (!deviceId) return res.status(400).json({ error: "missing_deviceId" });
  const entry = createRecovery(deviceId);
  recoveries.push(entry);
  res.json({ recoveryCode: entry.code, expiresIn: "use once" });
});

recoveryRouter.post("/restore", (req, res) => {
  const { code, newDeviceId, newDeviceKey } = req.body;
  if (!code || !newDeviceId) return res.status(400).json({ error: "missing_fields" });

  const entry = recoveries.find((r) => r.code === code && !r.used);
  if (!entry) return res.status(404).json({ error: "invalid_or_used_code" });

  entry.used = true;

  // Mark old device as transferred (in real DB this would update the device record)
  res.json({
    success: true,
    message: "License transferred. Old device deactivated.",
    restoredFrom: entry.deviceId,
    newDeviceId,
  });
});
