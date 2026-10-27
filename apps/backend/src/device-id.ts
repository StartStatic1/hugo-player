/**
 * Device ID do app: 6 bytes em hexadecimal "AA:BB:CC:DD:EE:FF" (DeviceIdentity.generateId).
 * No site as pessoas digitam à mão e erram: letra O no lugar de zero, espaços, hífens, minúsculas.
 * Antes isso criava um aparelho "web" novo (fantasma) com teste grátis, separado do aparelho de verdade.
 */
const MAC_RE = /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/;

export function canonicalDeviceId(raw: string): string {
  const s = (raw ?? "").trim().toUpperCase();
  // só "conserta" quando, tirando separadores e trocando O→0, sobram exatamente 12 dígitos hex
  const compact = s.replace(/[\s:\-._]/g, "").replace(/O/g, "0");
  if (/^[0-9A-F]{12}$/.test(compact)) return compact.match(/.{2}/g)!.join(":");
  return s;
}

export function isAppDeviceId(id: string): boolean {
  return MAC_RE.test(id);
}
