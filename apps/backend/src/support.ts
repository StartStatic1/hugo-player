/**
 * Link do suporte (grupo da comunidade no WhatsApp) entregue ao app em /channels/version.
 * Troca sem nova versão do app: SUPPORT_WHATSAPP_URL no .env. O site usa apps/web/site-config.js.
 */
export const DEFAULT_SUPPORT_WHATSAPP_URL = "https://chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P";

/** Só links do WhatsApp (grupo ou wa.me), sem parâmetros de rastreio. */
export function cleanSupportUrl(raw: string | undefined): string | null {
  const s = (raw ?? "").trim();
  if (!s) return null;
  try {
    const u = new URL(s);
    if (u.protocol !== "https:") return null;
    if (!/^(chat\.whatsapp\.com|wa\.me|api\.whatsapp\.com)$/i.test(u.hostname)) return null;
    return `https://${u.hostname.toLowerCase()}${u.pathname}`;
  } catch {
    return null;
  }
}

export function supportWhatsappUrl(): string {
  return cleanSupportUrl(process.env.SUPPORT_WHATSAPP_URL) ?? DEFAULT_SUPPORT_WHATSAPP_URL;
}
