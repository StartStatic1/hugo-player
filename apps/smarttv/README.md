# Hugo Player — Smart TV (Samsung Tizen / LG webOS)

App web 1920x1080 para controle remoto (D-pad), visual escuro premium (ciano #22D3EE, roxo #7C5CFF, rosa #EC4899).
Usa a mesma API do app Android (`api-hugo.streamflixvip.online`): Device ID (formato MAC) + Key, registro, licença/teste grátis
e playlists guardadas no servidor (o link com usuário/senha nunca aparece na tela).

## Telas
- Splash, ativação (Device ID/Key + licença), "Quem vai assistir?" (playlists), PIN com teclado numérico (`/playlists/:id/verify-pin`).
- Início: destaque com imagem de fundo (TMDB), fileiras Continuar assistindo, Filmes, Séries, Canais, Favoritos.
- Ao vivo: grupos | canais | prévia com EPG agora/a seguir e próximos programas.
- Filmes / Séries: grupos + grade de pôsteres virtual; série com temporadas e episódios (progresso por episódio).
- Guia (EPG) em grade de horários (3 h), Buscar com teclado na tela, Favoritos (segurar OK ou botão vermelho), Ajustes.
- Player: AVPlay (Tizen) ou `<video>` + hls.js; overlay com logo, canal, agora/a seguir, linha do tempo; continua de onde parou.

## Listas grandes
`js/store.js` baixa por tipo em páginas de 5.000 e guarda no IndexedDB com índice por posição no tipo e no grupo:
a grade lê só a janela visível (testado com 420 mil itens). Atualizar grava uma geração nova e só troca no fim; a antiga é apagada aos poucos.

## Testar no PC
`index.html?demo=1` (dados falsos, nada vai ao servidor; PIN da lista demo protegida = 1234) e `?demo=1&big=1` (420 mil itens).

## Pacotes
`./build.sh [pasta]` → `online.streamflixvip.hugo_<v>_all.ipk` (LG, `npm i -g @webos-tools/cli`) e `HugoPlayer.wgt` (Samsung, sem assinatura).
- LG: `ares-setup-device` + `ares-install` (TV em Developer Mode, conta LG Developer).
- Samsung: assinar com certificado Samsung (Tizen Studio › Certificate Manager, conta Samsung) e `tizen install` (TV em Developer Mode).
