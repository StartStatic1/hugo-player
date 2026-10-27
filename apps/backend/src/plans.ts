/** Planos pagos do Hugo Player — preço (centavos), duração e nome num lugar só. */
export type PaidPlan = "semiannual" | "annual" | "lifetime";
export const PAID_PLANS = ["semiannual", "annual", "lifetime"] as const;

/** Dias de validade a partir da ativação (null = sem vencimento). */
export const PLAN_DAYS: Record<PaidPlan, number | null> = { semiannual: 180, annual: 365, lifetime: null };
/** Preço em centavos (R$ 29,90 / R$ 44,90 / R$ 119,90). */
export const PLAN_PRICES: Record<PaidPlan, number> = { semiannual: 2990, annual: 4490, lifetime: 11990 };
export const PLAN_LABELS: Record<PaidPlan, string> = {
  semiannual: "Hugo Player Premium 6 meses",
  annual: "Hugo Player Premium Anual",
  lifetime: "Hugo Player Premium Vitalício",
};

/** Vencimento de um plano pago: soma a validade a partir de `fromMs` (null = vitalício). */
export function planExpiry(plan: PaidPlan, fromMs: number): string | null {
  const days = PLAN_DAYS[plan];
  return days === null ? null : new Date(fromMs + days * 86400000).toISOString();
}

/** Base da renovação: soma a partir do vencimento atual se a licença ainda vale; senão a partir de agora. */
export function renewalBase(
  license: { type: string; expiresAt: string | null } | undefined,
  nowMs: number,
): number {
  if (!license || license.type === "expired" || !license.expiresAt) return nowMs;
  const exp = new Date(license.expiresAt).getTime();
  return Number.isFinite(exp) && exp > nowMs ? exp : nowMs;
}

/** Revenda: só o plano anual; 1 crédito = 1 ativação de 1 ano. */
export const RESELLER_PLAN: PaidPlan = "annual";
export const RESELLER_CREDITS_PER_ACTIVATION = 1;

export interface ResellerPack { id: string; credits: number; amount: number; label: string }
/** Pacotes de créditos (preço em centavos). */
export const RESELLER_PACKS: readonly ResellerPack[] = [
  { id: "p5", credits: 5, amount: 12500, label: "5 créditos" },
  { id: "p10", credits: 10, amount: 22000, label: "10 créditos" },
  { id: "p50", credits: 50, amount: 100000, label: "50 créditos" },
  { id: "p100", credits: 100, amount: 180000, label: "100 créditos" },
] as const;
