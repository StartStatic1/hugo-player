/**
 * Parser XMLTV em streaming (sax) — não carrega o arquivo inteiro na memória.
 * Guarda só os programas dentro de uma janela de tempo (padrão: -3 h .. +36 h).
 */
import sax from "sax";
import { Readable } from "node:stream";
import { createGunzip } from "node:zlib";
import { normalize } from "../content/classify.js";

export interface Programme {
  start: number; // epoch ms
  stop: number;
  title: string;
  desc?: string;
}

export interface EpgChannel {
  id: string;
  names: string[];
  icon?: string;
}

export interface EpgData {
  channels: Map<string, EpgChannel>;
  /** channelId -> programas ordenados por início */
  programmes: Map<string, Programme[]>;
  /** nome normalizado -> channelId */
  byName: Map<string, string>;
  loadedAt: number;
  programmeCount: number;
}

/** "20261017180000 +0000" | "20261017180000" (UTC) | "202610171800 -0300" */
export function parseXmltvTime(s: string | undefined): number | null {
  if (!s) return null;
  const m = s.trim().match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{2}):?(\d{2})?/) ??
    s.trim().match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?$/);
  if (!m) return null;
  const [, y, mo, d, h, mi, sec] = m;
  let t = Date.UTC(+y, +mo - 1, +d, +h, +mi, +(sec ?? 0));
  if (m[7]) {
    const sign = m[7].startsWith("-") ? -1 : 1;
    const oh = Math.abs(parseInt(m[7], 10));
    const om = parseInt(m[8] ?? "0", 10);
    t -= sign * (oh * 60 + om) * 60_000;
  }
  return t;
}

export function epgNameKey(name: string): string {
  return normalize(name)
    .replace(/\[.*?\]|\(.*?\)/g, " ")
    .replace(/\b(fhd|uhd|hd|sd|4k|h265|hevc)\b/g, " ")
    .replace(/[^a-z0-9\u0600-\u06ff]+/g, "");
}

export interface ParseOptions {
  now?: number;
  pastMs?: number;
  futureMs?: number;
  maxDescLength?: number;
}

export function parseXmltv(input: Readable | string, opts: ParseOptions = {}): Promise<EpgData> {
  const now = opts.now ?? Date.now();
  const from = now - (opts.pastMs ?? 3 * 3600_000);
  const to = now + (opts.futureMs ?? 36 * 3600_000);
  const maxDesc = opts.maxDescLength ?? 280;

  const data: EpgData = { channels: new Map(), programmes: new Map(), byName: new Map(), loadedAt: Date.now(), programmeCount: 0 };
  const parser = sax.createStream(false, { lowercase: true, trim: false });

  let curChannel: EpgChannel | null = null;
  let curProg: { channel: string; start: number; stop: number; title: string; desc: string } | null = null;
  let textTarget: "display-name" | "title" | "desc" | null = null;
  let textBuf = "";

  parser.on("opentag", (node) => {
    const a = node.attributes as Record<string, string>;
    switch (node.name) {
      case "channel":
        curChannel = { id: String(a.id ?? ""), names: [] };
        break;
      case "icon":
        if (curChannel && a.src) curChannel.icon = String(a.src);
        break;
      case "display-name":
        if (curChannel) { textTarget = "display-name"; textBuf = ""; }
        break;
      case "programme": {
        const start = parseXmltvTime(a.start);
        const stop = parseXmltvTime(a.stop) ?? (start !== null ? start + 3600_000 : null);
        if (start !== null && stop !== null && stop > from && start < to) {
          curProg = { channel: String(a.channel ?? ""), start, stop, title: "", desc: "" };
        } else {
          curProg = null;
        }
        break;
      }
      case "title":
        if (curProg && !curProg.title) { textTarget = "title"; textBuf = ""; }
        break;
      case "desc":
        if (curProg && !curProg.desc) { textTarget = "desc"; textBuf = ""; }
        break;
    }
  });

  const onText = (t: string) => { if (textTarget) textBuf += t; };
  parser.on("text", onText);
  parser.on("cdata", onText);

  parser.on("closetag", (name) => {
    if (textTarget && name === textTarget) {
      const value = textBuf.trim();
      if (textTarget === "display-name" && curChannel && value) curChannel.names.push(value);
      if (textTarget === "title" && curProg) curProg.title = value;
      if (textTarget === "desc" && curProg) curProg.desc = value.length > maxDesc ? value.slice(0, maxDesc - 1) + "…" : value;
      textTarget = null;
      textBuf = "";
    }
    if (name === "channel" && curChannel) {
      if (curChannel.id) {
        data.channels.set(curChannel.id, curChannel);
        for (const n of curChannel.names) {
          const k = epgNameKey(n);
          if (k && !data.byName.has(k)) data.byName.set(k, curChannel.id);
        }
      }
      curChannel = null;
    }
    if (name === "programme" && curProg) {
      const list = data.programmes.get(curProg.channel) ?? [];
      list.push({ start: curProg.start, stop: curProg.stop, title: curProg.title || "Sem título", ...(curProg.desc ? { desc: curProg.desc } : {}) });
      data.programmes.set(curProg.channel, list);
      data.programmeCount++;
      curProg = null;
    }
  });

  return new Promise((resolve, reject) => {
    // XML mal formado é comum em EPG de IPTV: ignora o erro e continua
    parser.on("error", function (this: any) {
      this._parser.error = null;
      this._parser.resume();
    });
    parser.on("end", () => {
      for (const list of data.programmes.values()) list.sort((a, b) => a.start - b.start);
      resolve(data);
    });
    const src = typeof input === "string" ? Readable.from([input]) : input;
    src.on("error", reject);
    src.pipe(parser);
  });
}

/** Resolve a chave pedida pelo app (tvg-id / epg_channel_id ou nome) para o id do canal no XMLTV. */
export function resolveChannel(data: EpgData, key: string): string | null {
  if (!key) return null;
  if (data.programmes.has(key) || data.channels.has(key)) return key;
  const lower = key.toLowerCase();
  for (const id of data.channels.keys()) if (id.toLowerCase() === lower) return id;
  return data.byName.get(epgNameKey(key)) ?? null;
}

export interface NowNext {
  now: Programme | null;
  next: Programme | null;
}

export function nowNext(data: EpgData, channelId: string, at = Date.now()): NowNext {
  const list = data.programmes.get(channelId) ?? [];
  let now: Programme | null = null;
  let next: Programme | null = null;
  for (let i = 0; i < list.length; i++) {
    const p = list[i];
    if (p.start <= at && p.stop > at) {
      now = p;
      next = list[i + 1] ?? null;
      break;
    }
    if (p.start > at) {
      next = p;
      break;
    }
  }
  return { now, next };
}

/** Converte o corpo da resposta HTTP em stream, descompactando .gz automaticamente. */
export async function bodyToStream(body: ReadableStream<Uint8Array>): Promise<Readable> {
  const nodeStream = Readable.fromWeb(body as any);
  const iterator = nodeStream[Symbol.asyncIterator]();
  const first = await iterator.next();
  const firstChunk: Buffer | null = first.done ? null : Buffer.from(first.value);
  async function* rest() {
    if (firstChunk) yield firstChunk;
    while (true) {
      const r = await iterator.next();
      if (r.done) return;
      yield Buffer.from(r.value);
    }
  }
  const combined = Readable.from(rest());
  if (firstChunk && firstChunk[0] === 0x1f && firstChunk[1] === 0x8b) return combined.pipe(createGunzip());
  return combined;
}
