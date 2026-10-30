import fs from "fs";
import path from "path";
import crypto from "crypto";
import zlib from "zlib";
import { promisify } from "util";

const gzip = promisify(zlib.gzip);
const gunzip = promisify(zlib.gunzip);

/** Cache em disco do catálogo convertido (JSON gzip), um arquivo por playlist. Nada de credencial no nome. */
const dir = () => process.env.HUGO_CATALOG_CACHE_DIR || path.join(process.cwd(), "data", "catalog-cache");
/** Desligado nos testes (a menos que o teste aponte um diretório próprio) ou com HUGO_CATALOG_CACHE=off. */
const enabled = () =>
  process.env.HUGO_CATALOG_CACHE !== "off" && !(process.env.NODE_TEST_CONTEXT && !process.env.HUGO_CATALOG_CACHE_DIR);
export const CATALOG_FRESH_MS = 30 * 60 * 1000;
export const CATALOG_STALE_MS = 24 * 60 * 60 * 1000;
export const CATALOG_FALLBACK_MS = 72 * 60 * 60 * 1000;

export function cacheKey(playlistUrl: string): string {
  return crypto.createHash("sha256").update(playlistUrl.trim()).digest("hex").slice(0, 32);
}
function fileFor(playlistUrl: string) {
  return path.join(dir(), cacheKey(playlistUrl) + ".json.gz");
}

export async function statCatalogCache(playlistUrl: string): Promise<{ file: string; mtimeMs: number } | null> {
  if (!enabled()) return null;
  const file = fileFor(playlistUrl);
  try {
    const st = await fs.promises.stat(file);
    return { file, mtimeMs: st.mtimeMs };
  } catch {
    return null;
  }
}

export async function readCatalogCache(file: string): Promise<any> {
  const buf = await gunzip(await fs.promises.readFile(file));
  return JSON.parse(buf.toString("utf8"));
}

export async function writeCatalogCache(playlistUrl: string, data: unknown): Promise<void> {
  if (!enabled()) return;
  await fs.promises.mkdir(dir(), { recursive: true });
  const file = fileFor(playlistUrl);
  const tmp = file + "." + process.pid + ".tmp";
  await fs.promises.writeFile(tmp, await gzip(Buffer.from(JSON.stringify(data)), { level: 6 }));
  await fs.promises.rename(tmp, file);
}

/** Apaga arquivos com mais de 7 dias (chamado de hora em hora). */
export async function pruneCatalogCache(maxAgeMs = 7 * 24 * 60 * 60 * 1000): Promise<number> {
  let n = 0;
  try {
    for (const f of await fs.promises.readdir(dir())) {
      const p = path.join(dir(), f);
      const st = await fs.promises.stat(p).catch(() => null);
      if (st && Date.now() - st.mtimeMs > maxAgeMs) {
        await fs.promises.unlink(p).catch(() => {});
        n++;
      }
    }
  } catch {}
  return n;
}
setInterval(() => void pruneCatalogCache(), 60 * 60 * 1000).unref();
