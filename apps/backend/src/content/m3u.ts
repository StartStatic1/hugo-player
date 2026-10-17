/** Parser de M3U/M3U8 (#EXTM3U / #EXTINF) com extração de atributos tvg-*. */

export interface M3UEntry {
  name: string;
  url: string;
  attrs: Record<string, string>;
  group: string;
  logo?: string;
  tvgId?: string;
  tvgName?: string;
  duration: number;
  /** Cabeçalhos extras (#EXTVLCOPT http-user-agent / http-referrer) */
  userAgent?: string;
  referrer?: string;
}

export interface M3UPlaylist {
  epgUrl: string | null;
  headerAttrs: Record<string, string>;
  entries: M3UEntry[];
}

const ATTR_RE = /([a-zA-Z0-9_-]+)\s*=\s*"([^"]*)"/g;

export function parseAttrs(s: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of s.matchAll(ATTR_RE)) out[m[1].toLowerCase()] = m[2].trim();
  return out;
}

/** Encontra a vírgula que separa atributos do título (ignorando vírgulas dentro de aspas). */
function splitExtinf(line: string): { meta: string; title: string } {
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') inQuotes = !inQuotes;
    else if (c === "," && !inQuotes) return { meta: line.slice(0, i), title: line.slice(i + 1).trim() };
  }
  return { meta: line, title: "" };
}

export function parseM3U(content: string): M3UPlaylist {
  const lines = content.replace(/^\uFEFF/, "").split(/\r?\n/);
  let headerAttrs: Record<string, string> = {};
  const entries: M3UEntry[] = [];

  let pending: Omit<M3UEntry, "url"> | null = null;
  let pendingGroup: string | null = null; // #EXTGRP
  let ua: string | undefined;
  let ref: string | undefined;

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#EXTM3U")) {
      headerAttrs = parseAttrs(line);
      continue;
    }
    if (line.startsWith("#EXTINF")) {
      const { meta, title } = splitExtinf(line.slice("#EXTINF:".length));
      const attrs = parseAttrs(meta);
      const duration = parseFloat(meta.trim().split(/\s/)[0]) || -1;
      const name = title || attrs["tvg-name"] || "Sem nome";
      pending = {
        name,
        attrs,
        group: attrs["group-title"] || "",
        logo: attrs["tvg-logo"] || attrs["logo"] || undefined,
        tvgId: attrs["tvg-id"] || undefined,
        tvgName: attrs["tvg-name"] || undefined,
        duration,
      };
      continue;
    }
    if (line.startsWith("#EXTGRP:")) {
      pendingGroup = line.slice(8).trim();
      continue;
    }
    if (line.startsWith("#EXTVLCOPT:")) {
      const opt = line.slice(11);
      const [k, ...rest] = opt.split("=");
      const v = rest.join("=").trim();
      if (/http-user-agent/i.test(k)) ua = v;
      if (/http-referr?er/i.test(k)) ref = v;
      continue;
    }
    if (line.startsWith("#")) continue;

    // Linha de URL
    if (pending && /^(https?|rtmp|rtsp|udp|rtp):\/\//i.test(line)) {
      entries.push({
        ...pending,
        group: pending.group || pendingGroup || "Outros",
        url: line,
        userAgent: ua,
        referrer: ref,
      });
    }
    pending = null;
    pendingGroup = null;
    ua = undefined;
    ref = undefined;
  }

  const epgUrl = headerAttrs["url-tvg"] || headerAttrs["x-tvg-url"] || headerAttrs["tvg-url"] || null;
  return { epgUrl: epgUrl ? epgUrl.split(",")[0].trim() : null, headerAttrs, entries };
}
