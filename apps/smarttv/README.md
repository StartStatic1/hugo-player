# Hugo Player — Smart TV (Samsung Tizen / LG webOS)

App web 1920x1080 controlado pelo controle remoto (D-pad). Usa a mesma API do app Android
(`api-hugo.streamflixvip.online`): Device ID (formato MAC) + Key, registro, licença/teste grátis,
playlists guardadas no servidor (o link com usuário/senha não aparece na tela).

- Ativação: mostra Device ID/Key e a licença (`/devices/status`); ativar plano pelo site.
- Playlists: listar, adicionar (Xtream ou M3U), excluir, abrir (`/channels?playlistId=`).
- Ao vivo com grupos, Filmes e Séries (episódios via `/channels/series-episodes`), Favoritos (segurar OK ou botão vermelho), Configurações.
- Player: Tizen usa AVPlay nativo; webOS/navegador usam `<video>` + hls.js. Ao vivo: ↑/↓ ou CH+/CH- trocam de canal; filmes: ←/→ 10 s.
- Teste no navegador sem servidor: `index.html?demo=1`.

## Pacotes
`./build.sh [pasta]` → `online.streamflixvip.hugo_<v>_all.ipk` (LG, precisa `npm i -g @webos-tools/cli`) e `HugoPlayer.wgt` (Samsung, sem assinatura).

- LG: instalar com `ares-setup-device` + `ares-install` (TV em modo desenvolvedor, conta LG Developer).
- Samsung: assinar com certificado Samsung (Tizen Studio > Certificate Manager, conta Samsung) e instalar com `tizen install` (TV em Developer Mode).
