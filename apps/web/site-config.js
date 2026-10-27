/*
 * Configuração do site Hugo Player — mude aqui e todas as páginas atualizam.
 * downloaderCode: código do app Downloader (AFTVnews) que aponta para tvUrl.
 * Se o código mudar, troque só este número.
 */
window.HUGO_SITE = {
  downloaderCode: "3553860",
  tvUrl: "hugo.streamflixvip.online/apk",
};
document.addEventListener("DOMContentLoaded", function () {
  var c = window.HUGO_SITE;
  document.querySelectorAll("[data-dl-code]").forEach(function (el) { el.textContent = c.downloaderCode; });
  document.querySelectorAll("[data-tv-url]").forEach(function (el) { el.textContent = c.tvUrl; });
  document.querySelectorAll("[data-dl-code-box]").forEach(function (el) { el.hidden = !c.downloaderCode; });
});
