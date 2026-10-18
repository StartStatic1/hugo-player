# Análise RX Purple vs Hugo Player

## O que o RX Purple faz bem (das screenshots)

- **Agrupamento de qualidade**: TELE CINE ACTION aparece uma vez com badges SD/HD/FHD. O usuário escolhe a qualidade sem ver 3 canais separados.
- **EPG inline**: Na lista de canais já mostra o programa atual + próximos. No player também.
- **Séries**: Carrega Turcas, novelas etc. corretamente e chega a ~7000 séries. Provavelmente usa Xtream `get_series` + `get_series_info` em vez de explodir episódios no M3U plano.
- **Layout limpo**: Preview do canal + lista + EPG na mesma tela.
- **Contagem real**: Mostra total de canais e categorias com números corretos.

## Problemas atuais do Hugo

1. **Séries vazias** (Turcas, Dorama, Mexicanas): o classificador M3U plano não preenche `seriesName` em muitos títulos sem padrão S01E02, e o limite de itens corta a lista antes de chegar em algumas categorias.
2. **Limite 100k/250k**: listas grandes com muitos episódios estouram o limite e categorias no final ficam vazias.
3. **Canais live em Filmes**: grupos mistos ("FILMES & SERIES") classificam errado.
4. **Sem agrupamento de qualidade**: SD/HD/FHD do mesmo canal aparecem separados.
5. **EPG existe no backend** mas ainda não está bem integrado na UI de TV ao vivo.
6. **Favoritos, sinopse, legendas, dual audio**: ainda não implementados.

## Próximos passos recomendados

1. Priorizar **Xtream API** para playlists Xtream (já existe código em `xtream.ts`). Usar `get_series` / `get_series_info` para séries em vez do M3U plano.
2. Agrupar canais live por nome base + qualidade (SD/HD/FHD) na UI.
3. Mostrar EPG atual + próximos na lista de TV ao vivo e no player.
4. Adicionar Favoritos (tabela local + botão).
5. Tela de detalhes com sinopse (TMDB já está parcialmente no projeto).
6. Proteção no restore: exigir PIN ou registrar restaurações.

## Status

- Backend atualizado com palavras-chave mais fortes e fallback de seriesName.
- App: consulta de séries mais permissiva.
- Restaurar Device ID funciona.
- Contagem de séries agora é por título, não por episódio.

Quando voltar, sincronize com o APK mais recente e teste as categorias.
