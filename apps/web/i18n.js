/*
 * Hugo Player — idiomas do site (PT / EN / ES).
 * - Ordem: ?lang=pt|en|es  →  escolha salva (localStorage "hugo.lang")  →  idioma do navegador
 *   (pt* → PT, es* → ES, resto → EN; sem informação → PT).
 * - O HTML continua em português. Em EN/ES, elementos com data-i18n="chave" recebem o texto
 *   do dicionário (i18n-<página>.js); data-i18n-attr="placeholder:chave,title:chave" troca atributos.
 * - data-lang-only="pt" ou "en es": mostra o elemento só nesses idiomas.
 * - data-lang-section="pt|en|es": páginas com seções por idioma (Termos/Privacidade).
 * - No JS: T("texto em PT", {var}) devolve a tradução (ou o próprio PT).
 * - Seletor: <div data-lang-switch></div> em qualquer lugar do topo.
 */
(function () {
  "use strict";
  var KEY = "hugo.lang";
  var LOCALES = { pt: "pt-BR", en: "en-US", es: "es-ES" };
  var HTML_LANG = { pt: "pt-BR", en: "en", es: "es" };
  function norm(v) {
    v = String(v || "").toLowerCase();
    if (v.indexOf("pt") === 0) return "pt";
    if (v.indexOf("es") === 0) return "es";
    if (v.indexOf("en") === 0) return "en";
    return null;
  }
  var lang = null;
  try { lang = norm(new URLSearchParams(location.search).get("lang")); } catch (e) {}
  if (lang) { try { localStorage.setItem(KEY, lang); } catch (e) {} }
  if (!lang) { try { lang = norm(localStorage.getItem(KEY)); } catch (e) {} }
  if (!lang) {
    var nav = (navigator.languages && navigator.languages[0]) || navigator.language || navigator.userLanguage || "";
    lang = nav ? (norm(nav) || "en") : "pt";
  }

  var root = document.documentElement;
  root.setAttribute("lang", HTML_LANG[lang]);
  root.setAttribute("data-lang", lang);
  if (lang !== "pt") root.setAttribute("data-i18n-pending", "");

  var css =
    "html[data-i18n-pending] body{visibility:hidden}" +
    "html[data-lang=pt] [data-lang-only]:not([data-lang-only~=pt])," +
    "html[data-lang=en] [data-lang-only]:not([data-lang-only~=en])," +
    "html[data-lang=es] [data-lang-only]:not([data-lang-only~=es])," +
    "html[data-lang=pt] [data-lang-section]:not([data-lang-section=pt])," +
    "html[data-lang=en] [data-lang-section]:not([data-lang-section=en])," +
    "html[data-lang=es] [data-lang-section]:not([data-lang-section=es]){display:none!important}" +
    ".lang-switch{display:inline-flex;align-items:center;gap:2px;padding:3px;border:1px solid #1f2638;border-radius:10px;background:#0c0e18;flex:none}" +
    ".lang-switch a{display:inline-block;padding:6px 9px;border-radius:7px;font-size:12px;font-weight:700;letter-spacing:.4px;line-height:1;color:#94a3b8;text-decoration:none}" +
    ".lang-switch a:hover{color:#f1f5f9}.lang-switch a.on{background:#8b5cf6;color:#fff}" +
    ".lang-switch a:focus-visible{outline:none;box-shadow:0 0 0 3px #8b5cf699}";
  var st = document.createElement("style");
  st.textContent = css;
  (document.head || root).appendChild(st);

  var dict = { en: {}, es: {} };
  function get(key) { var d = dict[lang]; return d && Object.prototype.hasOwnProperty.call(d, key) ? d[key] : undefined; }
  function fill(s, vars) {
    if (!vars) return s;
    return String(s).replace(/\{(\w+)\}/g, function (m, k) { return vars[k] == null ? "" : vars[k]; });
  }
  function T(pt, vars) {
    if (pt == null) return pt;
    var tr = lang === "pt" ? undefined : get(pt);
    return fill(tr === undefined ? pt : tr, vars);
  }

  function hrefFor(l) {
    try { var u = new URL(location.href); u.searchParams.set("lang", l); return u.pathname + u.search + u.hash; }
    catch (e) { return "?lang=" + l; }
  }
  function renderSwitch(box) {
    var names = { pt: "Português", en: "English", es: "Español" };
    box.classList.add("lang-switch");
    box.setAttribute("role", "group");
    box.setAttribute("aria-label", T("Idioma"));
    box.innerHTML = ["pt", "en", "es"].map(function (l) {
      return '<a href="' + hrefFor(l) + '" hreflang="' + HTML_LANG[l] + '" lang="' + HTML_LANG[l] + '" data-set-lang="' + l + '" title="' + names[l] + '"' +
        (l === lang ? ' class="on" aria-current="true"' : "") + ">" + l.toUpperCase() + "</a>";
    }).join("");
    box.addEventListener("click", function (e) {
      var a = e.target.closest("[data-set-lang]");
      if (!a) return;
      try { localStorage.setItem(KEY, a.getAttribute("data-set-lang")); } catch (err) {}
    });
  }

  var applied = false;
  function apply() {
    if (applied) return;
    applied = true;
    document.querySelectorAll("[data-lang-switch]").forEach(renderSwitch);
    if (lang !== "pt") {
      document.querySelectorAll("[data-i18n]").forEach(function (el) {
        var v = get(el.getAttribute("data-i18n"));
        if (v === undefined) return;
        if (el.tagName === "TITLE") document.title = v; else el.innerHTML = v;
      });
      document.querySelectorAll("[data-i18n-attr]").forEach(function (el) {
        el.getAttribute("data-i18n-attr").split(",").forEach(function (pair) {
          var i = pair.indexOf(":");
          if (i < 0) return;
          var v = get(pair.slice(i + 1).trim());
          if (v !== undefined) el.setAttribute(pair.slice(0, i).trim(), v);
        });
      });
    }
    root.removeAttribute("data-i18n-pending");
  }
  setTimeout(function () { root.removeAttribute("data-i18n-pending"); }, 2500);
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", apply); else setTimeout(apply, 0);

  window.HUGO_I18N = {
    lang: lang,
    locale: LOCALES[lang],
    t: T,
    get: get,
    apply: apply,
    add: function (l, obj) { if (dict[l]) Object.keys(obj).forEach(function (k) { dict[l][k] = obj[k]; }); }
  };
  window.T = T;
})();
