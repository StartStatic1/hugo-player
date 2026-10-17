import { randomBytes } from "node:crypto";

export interface RecoveryEntry {
  code: string;
  deviceId: string;
  createdAt: string;
  used: boolean;
}

export function generateRecoveryCode(): string {
  return randomBytes(4).toString("hex").toUpperCase();
}

export function createRecovery(deviceId: string): RecoveryEntry {
  return {
    code: generateRecoveryCode(),
    deviceId,
    createdAt: new Date().toISOString(),
    used: false,
  };
}
