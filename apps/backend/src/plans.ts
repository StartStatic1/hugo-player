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
