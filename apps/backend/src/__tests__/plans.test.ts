import { test } from "node:test";
import assert from "node:assert/strict";
import { planExpiry, PLAN_PRICES, PAID_PLANS } from "../plans.js";

test("semestral vence em 180 dias, anual em 365, vitalício sem vencimento", () => {
  const from = Date.parse("2026-01-01T00:00:00.000Z");
  assert.equal(planExpiry("semiannual", from), "2026-06-30T00:00:00.000Z");
  assert.equal(planExpiry("annual", from), "2027-01-01T00:00:00.000Z");
  assert.equal(planExpiry("lifetime", from), null);
});

test("preços novos em centavos", () => {
  assert.deepEqual(PAID_PLANS.map((p) => PLAN_PRICES[p]), [2990, 4490, 11990]);
});
