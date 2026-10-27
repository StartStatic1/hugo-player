/*
 * Configuração do site Hugo Player — mude aqui e todas as páginas atualizam.
 * downloaderCode: código do app Downloader (AFTVnews) que aponta para tvUrl.
 * supportWhatsappUrl: grupo da comunidade/suporte no WhatsApp (botões [data-support-wa]).
 *   O app lê o mesmo link pela API (SUPPORT_WHATSAPP_URL no servidor; padrão igual a este).
 * Se algo mudar, troque só aqui.
 */
window.HUGO_SITE = {
  downloaderCode: "3553860",
  tvUrl: "hugo.streamflixvip.online/apk",
  supportWhatsappUrl: "https://chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P",
};
(function () {
  var WA_ICON = '<svg class="wa-ico" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path fill="#25D366" d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Z"/><path fill="#fff" d="M17.3 14.6c-.3-.1-1.7-.8-1.9-.9-.3-.1-.5-.1-.7.1l-.9 1.1c-.2.2-.3.2-.6.1a8.2 8.2 0 0 1-4-3.5c-.3-.5.3-.5.9-1.6.1-.2 0-.4 0-.5l-.9-2.1c-.2-.6-.5-.5-.7-.5h-.6a1.1 1.1 0 0 0-.8.4 3.4 3.4 0 0 0-1 2.5 5.9 5.9 0 0 0 1.2 3.1 13.4 13.4 0 0 0 5.2 4.6c1.9.8 2.7.9 3.6.7a3.1 3.1 0 0 0 2-1.4 2.5 2.5 0 0 0 .2-1.4c-.1-.2-.3-.2-.6-.4Z"/></svg>';
  function apply() {
    var c = window.HUGO_SITE;
    document.querySelectorAll("[data-dl-code]").forEach(function (el) { el.textContent = c.downloaderCode; });
    document.querySelectorAll("[data-tv-url]").forEach(function (el) { el.textContent = c.tvUrl; });
    document.querySelectorAll("[data-dl-code-box]").forEach(function (el) { el.hidden = !c.downloaderCode; });
    document.querySelectorAll("[data-support-wa]").forEach(function (el) {
      if (!c.supportWhatsappUrl) { el.hidden = true; return; }
      el.href = c.supportWhatsappUrl;
      el.target = "_blank";
      el.rel = "noopener noreferrer";
      if (el.hasAttribute("data-wa-icon") && !el.querySelector(".wa-ico")) el.insertAdjacentHTML("afterbegin", WA_ICON);
      el.hidden = false;
    });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", apply); else apply();
})();
