import { PLAN_DAYS, type PaidPlan } from "./plans.js";

export interface RefundDevice {
  deviceId: string;
  license: { type: "trial" | "premium" | "expired"; plan?: string; expiresAt: string | null; paymentProvider?: string; transactionNsu?: string };
  revokedAt?: string;
  revokedReason?: string;
}

/**
 * Estorno de um pedido pago: tira a licença que ele deu.
 * Procura o aparelho que tem a licença desse pagamento (pode ter sido transferida); se a licença
 * já é outra (renovou depois), só desconta os dias do plano estornado.
 */
export function revokeRefundedOrder(devices: Record<string, RefundDevice>, order: { deviceId: string; plan: PaidPlan; transactionNsu?: string }, nowMs: number): string | null {
  const at = new Date(nowMs).toISOString();
  const nsu = order.transactionNsu?.trim();
  const holder = (nsu && Object.values(devices).find((d) => d.license.transactionNsu === nsu)) || devices[order.deviceId];
  if (!holder) return null;
  if (nsu && holder.license.transactionNsu === nsu) {
    holder.license = { type: "expired", plan: "trial", expiresAt: at, paymentProvider: "infinitepay-refunded", transactionNsu: nsu };
  } else {
    const days = PLAN_DAYS[order.plan];
    if (days === null || !holder.license.expiresAt) return holder.deviceId; // vitalício pago de outro jeito: não mexe
    const exp = new Date(holder.license.expiresAt).getTime() - days * 86400000;
    holder.license = exp > nowMs
      ? { ...holder.license, expiresAt: new Date(exp).toISOString() }
      : { ...holder.license, type: "expired", expiresAt: at };
  }
  holder.revokedAt = at;
  holder.revokedReason = "pagamento estornado";
  return holder.deviceId;
}
