/**
 * Configuração do app guardada no servidor (db.json → config), editável no admin:
 *  - minVersionCode: versão mínima obrigatória. App abaixo disso fica bloqueado até atualizar.
 */
export interface AppConfig {
  minVersionCode: number;
  updatedAt?: string;
}

export const DEFAULT_CONFIG: AppConfig = { minVersionCode: 0 };

export function normalizeConfig(raw: unknown, envMin?: string): AppConfig {
  const r = (raw && typeof raw === "object" ? raw : {}) as Partial<AppConfig>;
  const fromEnv = Number(envMin ?? 0);
  const min = Number.isInteger(r.minVersionCode) ? (r.minVersionCode as number) : Number.isInteger(fromEnv) ? fromEnv : 0;
  return { ...r, minVersionCode: Math.max(0, Math.min(100000, min)) };
}

/** true só quando sabemos a versão do app e ela é menor que a mínima. */
export function updateRequired(clientCode: number | null, min: number): boolean {
  return min > 0 && clientCode !== null && clientCode < min;
}

/**
 * Versão do app numa requisição. Apps a partir da 0.5.39 mandam `x-hugo-version-code`.
 * Apps antigos do Hugo usam HttpURLConnection (User-Agent "Dalvik/…") e não mandam nada:
 * tratamos como versão 0 (antiga). Navegador (site) e outros clientes → null (não bloqueia).
 */
export function clientVersionCode(header: string | undefined, userAgent: string | undefined, origin?: string | undefined): number | null {
  const h = (header ?? "").trim();
  if (/^\d{1,6}$/.test(h)) return Number(h);
  // site/portal e admin (navegador manda Origin/Referer; o app nunca manda): nunca bloqueia
  if ((origin ?? "").trim()) return null;
  if (/^Dalvik\//.test(userAgent ?? "")) return 0;
  return null;
}
