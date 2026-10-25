import { Router } from "express";
import rateLimit from "express-rate-limit";
import { z } from "zod";
import type { StoredPlaylist } from "./routes-playlists.js";

/**
 * Transferir playlists de um aparelho antigo (ID + Key) para o novo (ID + Key).
 * Ex.: o app foi reinstalado e gerou ID/Key novos. As duas Keys precisam estar certas.
 * As playlists são MOVIDAS (com link do EPG e PIN); o cadastro do aparelho antigo fica, só sem listas.
 */
export interface TransferDeps {
  playlists: () => StoredPlaylist[];
  persist: () => Promise<void>;
  /** ID normalizado do aparelho se ID + Key conferem (mesmo sem licença ativa), senão null */
  verify: (id: string, key: string) => string | null;
  /** tentativas ERRADAS por IP a cada 15 min */
  failedAttemptsPer15Min?: number;
}

const schema = z.object({
  fromId: z.string().trim().min(4).max(128),
  fromKey: z.string().trim().min(4).max(64),
  toId: z.string().trim().min(4).max(128),
  toKey: z.string().trim().min(4).max(64),
});

export function movePlaylists(list: StoredPlaylist[], fromId: string, toId: string): number {
  let moved = 0;
  const now = new Date().toISOString();
  for (const p of list) {
    if (p.deviceId.toUpperCase() === fromId.toUpperCase()) {
      p.deviceId = toId;
      p.updatedAt = now;
      moved++;
    }
  }
  return moved;
}

export function createTransferRouter(deps: TransferDeps): Router {
  const r = Router();
  const limiter = rateLimit({
    windowMs: 15 * 60_000,
    limit: deps.failedAttemptsPer15Min ?? 8,
    skipSuccessfulRequests: true,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: "too_many_attempts" },
  });

  r.post("/transfer", limiter, async (req, res) => {
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ error: "validation_error" });
    const { fromId, fromKey, toId, toKey } = parsed.data;
    const from = deps.verify(fromId, fromKey);
    const to = deps.verify(toId, toKey);
    // mesma resposta para ID inexistente ou Key errada (não revela quais IDs existem)
    if (!from) return res.status(401).json({ error: "invalid_old_device" });
    if (!to) return res.status(401).json({ error: "invalid_new_device" });
    if (from === to) return res.status(400).json({ error: "same_device" });
    const moved = movePlaylists(deps.playlists(), from, to);
    if (moved > 0) await deps.persist();
    console.log(`transfer: ${moved} playlist(s) ${from} -> ${to}`);
    res.json({ ok: true, moved, from, to });
  });
  return r;
}
