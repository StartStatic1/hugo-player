import { parseXtreamUrl } from "./content/xtream.js";

/**
 * Acesso às rotas que precisam do link (com usuário/senha) de uma playlist.
 * Novo: o app manda só o playlistId + x-device-id/x-device-key e o servidor usa o link guardado.
 * Antigo (apps velhos, ?url=...): só aceito se o link for de uma playlist cadastrada; será removido.
 */
export interface StoredPlaylist {
  id: string;
  deviceId: string;
  url: string;
}

/** Chave de comparação: Xtream = host + usuário + senha (get.php/player_api tanto faz); M3U = link exato. */
export function playlistMatchKey(raw: string): string {
  const xt = parseXtreamUrl(raw);
  if (xt) return `xt|${xt.baseUrl.toLowerCase()}|${xt.username}|${xt.password}`;
  return `url|${raw.trim()}`;
}

export function isRegisteredUrl(url: string, playlists: StoredPlaylist[]): boolean {
  if (!url) return false;
  const k = playlistMatchKey(url);
  return playlists.some((p) => p.url && playlistMatchKey(p.url) === k);
}

export function playlistUrlFor(deviceId: string, playlistId: string, playlists: StoredPlaylist[]): string | null {
  const p = playlists.find((x) => x.id === playlistId && x.deviceId.toUpperCase() === deviceId.toUpperCase());
  return p?.url || null;
}

/** Host sem credenciais, para logs. */
export function safeHost(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return "?";
  }
}

const lastNote = new Map<string, { at: number; count: number }>();
/** Aviso de rota antiga (no máximo 1 linha a cada 10 min por rota), sem usuário/senha. */
export function noteDeprecated(route: string, url: string, now = Date.now()): void {
  const k = route;
  const n = lastNote.get(k) ?? { at: 0, count: 0 };
  n.count++;
  if (now - n.at > 10 * 60_000) {
    console.warn(`[deprecated] ${route} com ?url= (app antigo) — host=${safeHost(url)} chamadas=${n.count}`);
    n.at = now;
    n.count = 0;
  }
  lastNote.set(k, n);
}
