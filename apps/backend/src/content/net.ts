/** Utilitários de rede: fetch com timeout, User-Agent IPTV, limite de tamanho e bloqueio de IPs internos. */
import { isIP } from "node:net";
import { gunzipSync } from "node:zlib";

export const IPTV_USER_AGENT = process.env.IPTV_USER_AGENT ?? "VLC/3.0.20 LibVLC/3.0.20";
const MAX_BYTES = Number(process.env.MAX_PLAYLIST_BYTES ?? 200 * 1024 * 1024);

export class UpstreamError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
  }
}

function isPrivateHost(hostname: string): boolean {
  const h = hostname.replace(/^\[|\]$/g, "").toLowerCase();
  if (h === "localhost" || h.endsWith(".localhost") || h.endsWith(".internal") || h === "metadata.google.internal") return true;
  const v = isIP(h);
  if (v === 4) {
    const [a, b] = h.split(".").map(Number);
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  if (v === 6) return h === "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80") || h === "::";
  return false;
}

/** Valida a URL informada pelo usuário (apenas http/https e hosts públicos). */
export function assertPublicHttpUrl(raw: string): URL {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    throw new UpstreamError(400, "invalid_url");
  }
  if (!/^https?:$/.test(u.protocol)) throw new UpstreamError(400, "invalid_url_protocol");
  if (process.env.ALLOW_PRIVATE_URLS !== "1" && isPrivateHost(u.hostname)) throw new UpstreamError(400, "private_address_not_allowed");
  return u;
}

export async function fetchBuffer(url: string, timeoutMs = 45_000): Promise<Buffer> {
  assertPublicHttpUrl(url);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      redirect: "follow",
      headers: { "User-Agent": IPTV_USER_AGENT, Accept: "*/*" },
    });
    if (!res.ok) throw new UpstreamError(502, `upstream_${res.status}`);
    const len = Number(res.headers.get("content-length") ?? 0);
    if (len > MAX_BYTES) throw new UpstreamError(413, "playlist_too_large");
    const chunks: Buffer[] = [];
    let total = 0;
    if (res.body) {
      for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
        total += chunk.byteLength;
        if (total > MAX_BYTES) throw new UpstreamError(413, "playlist_too_large");
        chunks.push(Buffer.from(chunk));
      }
    }
    let buf = Buffer.concat(chunks);
    // .gz (comum em EPG xmltv.gz)
    if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) buf = gunzipSync(buf);
    return buf;
  } catch (e: any) {
    if (e?.name === "AbortError") throw new UpstreamError(504, "timeout_fetching_playlist");
    if (e instanceof UpstreamError) throw e;
    throw new UpstreamError(502, e?.cause?.code ? `upstream_${e.cause.code}` : "fetch_failed");
  } finally {
    clearTimeout(timer);
  }
}

export async function fetchText(url: string, timeoutMs?: number): Promise<string> {
  return (await fetchBuffer(url, timeoutMs)).toString("utf8");
}

export async function fetchJson(url: string, timeoutMs = 45_000): Promise<unknown> {
  const text = await fetchText(url, timeoutMs);
  try {
    return JSON.parse(text);
  } catch {
    throw new UpstreamError(502, "invalid_json");
  }
}

export class TtlCache<T> {
  private map = new Map<string, { value: T; expires: number }>();
  constructor(private ttlMs: number, private max = 50) {}
  async getOrLoad(key: string, load: () => Promise<T>, force = false): Promise<T> {
    const now = Date.now();
    const hit = this.map.get(key);
    if (!force && hit && hit.expires > now) return hit.value;
    const value = await load();
    if (this.map.size >= this.max) {
      const first = this.map.keys().next().value;
      if (first) this.map.delete(first);
    }
    this.map.set(key, { value, expires: now + this.ttlMs });
    return value;
  }
}
