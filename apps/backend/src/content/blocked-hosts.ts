/**
 * Memória curta de provedores que bloqueiam o IP do servidor (HTTP 403).
 * Enquanto a entrada vale, o GET /channels responde upstream_403 na hora e o
 * app baixa a lista direto no aparelho, sem esperar novas tentativas da VPS.
 * Só 403 marca bloqueio; qualquer outro erro segue o fluxo normal.
 */
export const BLOCK_TTL_MS = 6 * 60 * 60 * 1000;
export const BLOCK_MAX_ENTRIES = 500;

const blocked = new Map<string, number>(); // chave -> expira em (ms)

/** Chave por host:porta + usuário, para um 403 de uma conta não afetar outra. */
export function blockKey(baseUrl: string, username = ""): string {
  let host = baseUrl;
  try {
    host = new URL(baseUrl).host.toLowerCase();
  } catch {
    /* usa o texto cru */
  }
  return `${host}|${username}`;
}

export function isBlocked(key: string, now = Date.now()): boolean {
  const exp = blocked.get(key);
  if (exp === undefined) return false;
  if (exp <= now) {
    blocked.delete(key);
    return false;
  }
  return true;
}

export function markBlocked(key: string, now = Date.now()): void {
  blocked.delete(key);
  if (blocked.size >= BLOCK_MAX_ENTRIES) {
    for (const [k, exp] of blocked) if (exp <= now) blocked.delete(k);
    while (blocked.size >= BLOCK_MAX_ENTRIES) {
      const oldest = blocked.keys().next().value;
      if (oldest === undefined) break;
      blocked.delete(oldest);
    }
  }
  blocked.set(key, now + BLOCK_TTL_MS);
}

export function blockedCount(): number {
  return blocked.size;
}

export function clearBlocked(): void {
  blocked.clear();
}
