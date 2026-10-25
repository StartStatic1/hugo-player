/**
 * Canais ao vivo vistos por playlist (tvg-id e nome normalizado), lembrados quando
 * a lista passa pelo servidor. O EPG usa isso para guardar só a programação dos
 * canais da playlist (EPG grande ocupa centenas de MB se guardar tudo).
 */
import { epgNameKey } from "../epg/xmltv.js";

export interface KnownChannels {
  ids: Set<string>;
  names: Set<string>;
  at: number;
}

const TTL_MS = 12 * 3600_000;
const MAX_PLAYLISTS = 20;
const known = new Map<string, KnownChannels>();

export function rememberChannels(playlistUrl: string, items: Iterable<{ name?: string | null; epgId?: string | null }>): void {
  const ids = new Set<string>();
  const names = new Set<string>();
  for (const it of items) {
    if (it.epgId) ids.add(String(it.epgId).toLowerCase());
    if (it.name) {
      const k = epgNameKey(String(it.name));
      if (k) names.add(k);
    }
  }
  if (ids.size === 0 && names.size === 0) return;
  known.delete(playlistUrl);
  known.set(playlistUrl, { ids, names, at: Date.now() });
  while (known.size > MAX_PLAYLISTS) known.delete(known.keys().next().value as string);
}

export function knownChannels(playlistUrl: string | undefined | null): KnownChannels | null {
  if (!playlistUrl) return null;
  const k = known.get(playlistUrl);
  if (!k) return null;
  if (Date.now() - k.at > TTL_MS) {
    known.delete(playlistUrl);
    return null;
  }
  return k;
}
