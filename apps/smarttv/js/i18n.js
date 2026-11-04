/* Idioma da interface (pt-BR / en). Automático pelo idioma da TV; inglês se não for português.
 * Ajustes > Idioma grava a escolha em localStorage (hugo.lang).
 * Os textos do app continuam escritos em português no código; este módulo traduz o que aparece
 * na tela (nós de texto, placeholders e toasts) por dicionário exato + regras. */
var I18N = (function () {
  function detect() {
    var o = null; try { o = localStorage.getItem("hugo.lang"); } catch (e) {}
    if (o === "pt" || o === "en") return o;
    var l = (navigator.language || navigator.userLanguage || "en").toLowerCase();
    return l.indexOf("pt") === 0 ? "pt" : "en";
  }
  var lang = detect();
  var D = {
    "Início": "Home", "Ao vivo": "Live", "Filmes": "Movies", "Séries": "Series", "Guia": "Guide", "Buscar": "Search", "Favoritos": "Favorites", "Ajustes": "Settings",
    "★ Adicionado aos favoritos": "★ Added to favorites", "Removido dos favoritos": "Removed from favorites", "Em destaque": "Featured", "EM DESTAQUE": "FEATURED",
    "▶ Assistir": "▶ Watch", "★ Favoritos": "★ Favorite", "Continuar assistindo": "Continue watching", "Filmes em destaque": "Featured movies", "Séries para maratonar": "Series to binge",
    "Canais ao vivo": "Live channels", "Meus favoritos": "My favorites", "Carregando guia...": "Loading guide...", "Sem guia para este canal": "No guide for this channel",
    "A SEGUIR": "NEXT", "A seguir": "Next", "AGORA": "NOW", "Agora": "Now", "AO VIVO": "LIVE", "Hoje": "Today", "Todos": "All", "Outros": "Other",
    "Nenhum episódio encontrado.": "No episodes found.", "Sem informação de programação": "No program information", "Digite para buscar": "Type to search",
    "Busque canais, filmes e séries.": "Search channels, movies and series.", "Buscando...": "Searching...", "Carregando...": "Loading...", "Conectando...": "Connecting...",
    "Segure OK (ou o botão vermelho) em qualquer canal, filme ou série para favoritar.": "Hold OK (or the red button) on any channel, movie or series to add it to favorites.",
    "Nenhum favorito ainda.": "No favorites yet.", "Contas e planos são gerenciados em": "Accounts and plans are managed at", "Conta em uso": "Current account",
    "Atualizar conteúdo": "Refresh content", "Trocar conta": "Switch account", "Atualizar licença": "Refresh license", "Licença atualizada": "License refreshed",
    "Limpar continuar assistindo": "Clear continue watching", "Continuar assistindo limpo": "Continue watching cleared", "Limpar favoritos": "Clear favorites", "Favoritos apagados": "Favorites cleared",
    "Verificando licença...": "Checking license...", "Este aparelho já usou o período de teste. Ative um plano no site.": "This TV has already used its trial. Activate a plan on the website.",
    "Licença vencida. Renove no site para continuar.": "License expired. Renew on the website to continue.", "Período de teste ativo": "Trial active", "Plano ativo": "Plan active", "Plano ativo (vitalício)": "Plan active (lifetime)",
    "Acesse": "Go to", "Digite o Device ID e a Key": "Enter the Device ID and Key", "Entre com sua conta (servidor, usuário e senha)": "Sign in with your account (server, username and password)",
    "Continuar": "Continue", "Verificar de novo": "Check again", "Quem vai assistir?": "Who's watching?", "Contas são gerenciadas em": "Accounts are managed at",
    "Nenhuma conta ainda. Entre abaixo com servidor, usuário e senha.": "No accounts yet. Sign in below with server, username and password.", "+ Entrar com uma conta": "+ Sign in to an account",
    "Muitas tentativas. Aguarde.": "Too many attempts. Please wait.", "PIN incorreto": "Wrong PIN", "Digite o PIN da conta": "Enter the account PIN", "Conta": "Account",
    "Entrar": "Sign in", "Voltar": "Back", "Servidor": "Server", "Usuário": "Username", "Senha": "Password", "servidor.exemplo.com:8080": "server.example.com:8080",
    "Use os dados da conta que você já possui. Contas são gerenciadas em": "Use the details of an account you already have. Accounts are managed at",
    ". O Hugo Player não fornece conteúdo.": ". Hugo Player does not provide any content.", "Preencha servidor, usuário e senha": "Fill in server, username and password",
    "Conta conectada": "Account connected", "Você pode continuar usando o conteúdo guardado enquanto atualiza.": "You can keep using the saved content while it refreshes.",
    "Tentar de novo": "Try again", "Limpar": "Clear", "Erro ao reproduzir.": "Playback error.", "Não foi possível reproduzir este conteúdo.": "This content could not be played.",
    "Não foi possível abrir este conteúdo.": "This content could not be opened.", "Sem conexão com o servidor do canal.": "No connection to the channel server.",
    "Sem espaço para guardar o catálogo": "Not enough space to save the catalog", "Language: English": "Language: English",
    "▲ ▼ ou CH+/CH− trocar canal · OK informações · Voltar sair": "▲ ▼ or CH+/CH− change channel · OK info · Back exit",
    "OK pausar · ◀ ▶ 10 s · ⏪ ⏩ 1 min · Voltar sair": "OK pause · ◀ ▶ 10 s · ⏪ ⏩ 1 min · Back exit"
  };
  var R = [
    [/^Período de teste ativo até (.+)$/, "Trial active until $1"], [/^Plano ativo até (.+)$/, "Plan active until $1"],
    [/^Conta ·$/, "Account ·"], [/^Temporada (\d+)$/, "Season $1"], [/^Episódio (\d+)$/, "Episode $1"],
    [/^(\S+) canais · (\S+) filmes · (\S+) séries$/, "$1 channels · $2 movies · $3 series"], [/^canais$/, "channels"], [/^filmes$/, "movies"], [/^séries$/, "series"],
    [/^Atualizada em (.+)$/, "Updated $1"], [/^Erro: (.+)$/, "Error: $1"], [/^Erro ao abrir a série: (.+)$/, "Could not open the series: $1"],
    [/^Não deu para atualizar: (.+) — usando o conteúdo guardado$/, "Could not refresh: $1 — using saved content"], [/^Não foi possível carregar: (.+)$/, "Could not load: $1"],
    [/^Sem conexão com o servidor: (.+)$/, "No connection to the server: $1"], [/^Segure OK de novo para excluir “(.+)”$/, "Hold OK again to remove “$1”"],
    [/^Nada encontrado para “(.+)”$/, "Nothing found for “$1”"], [/^(Canais|Filmes|Séries): (\S+)( de (\S+))?$/, function (m, a, b, c, d) { return { Canais: "Channels", Filmes: "Movies", Séries: "Series" }[a] + ": " + b + (d ? " of " + d : ""); }],
    [/^(\d+) min$/, "$1 min"]
  ];
  function tr(s) {
    if (lang === "pt" || !s) return s;
    var k = s.trim(); if (!k) return s;
    var out = D[k];
    if (out == null) for (var i = 0; i < R.length; i++) if (R[i][0].test(k)) { out = k.replace(R[i][0], R[i][1]); break; }
    return out == null ? s : s.replace(k, out);
  }
  function walk(n) {
    if (n.nodeType === 3) { var t = tr(n.nodeValue); if (t !== n.nodeValue) n.nodeValue = t; return; }
    if (n.nodeType !== 1 || n.tagName === "SCRIPT" || n.tagName === "STYLE") return;
    if (n.placeholder) { var p = tr(n.placeholder); if (p !== n.placeholder) n.placeholder = p; }
    for (var c = n.firstChild; c; c = c.nextSibling) walk(c);
  }
  function start() {
    document.documentElement.lang = lang === "pt" ? "pt-BR" : "en";
    if (lang === "pt") return;
    walk(document.body);
    new MutationObserver(function (ms) { ms.forEach(function (m) { if (m.type === "characterData") walk(m.target); else for (var i = 0; i < m.addedNodes.length; i++) walk(m.addedNodes[i]); }); })
      .observe(document.body, { childList: true, subtree: true, characterData: true });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
  // Tizen: idioma do sistema (assíncrono); só ajusta se o usuário não escolheu e se diferir
  try {
    if (window.tizen && tizen.systeminfo && !localStorage.getItem("hugo.lang")) tizen.systeminfo.getPropertyValue("LOCALE", function (l) {
      var sys = String(l && l.language || "").toLowerCase().indexOf("pt") === 0 ? "pt" : "en";
      if (sys !== lang) { localStorage.setItem("hugo.lang", sys); location.reload(); }
    });
  } catch (e) {}
  return {
    lang: lang, t: tr, locale: lang === "pt" ? "pt-BR" : "en-US",
    set: function (l) { localStorage.setItem("hugo.lang", l); location.reload(); }
  };
})();
