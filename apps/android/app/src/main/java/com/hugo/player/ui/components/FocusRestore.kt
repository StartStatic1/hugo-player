package com.hugo.player.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Substitui Modifier.focusRestorer() em listas Lazy (bug do Compose 1.7 no D-pad:
 * "Release should only be called once" em LazyLayoutPinnableItem ao sair e voltar para a lista).
 * Ao entrar na lista, vai para o item [index] (que deve ter [requester]) se ele estiver na tela;
 * senão deixa o Compose escolher (Default). Nunca devolve um requester sem item ligado.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.lazyFocusRestore(state: LazyListState, requester: FocusRequester, index: () -> Int): Modifier =
    focusProperties {
        enter = {
            val i = runCatching { index() }.getOrDefault(-1)
            val visible = i >= 0 && runCatching { state.layoutInfo.visibleItemsInfo.any { it.index == i } }.getOrDefault(false)
            if (visible) requester else FocusRequester.Default
        }
    }

/** Índice "lazy" da categoria na coluna da TV ao vivo: 0 Favoritos, 1 Recentes, 2 Todos, 3.. grupos. */
fun liveCategoryLazyIndex(filter: String, fav: String, rec: String, all: String, categories: List<String>): Int = when (filter) {
    fav -> 0
    rec -> 1
    all -> 2
    else -> categories.indexOf(filter).let { if (it < 0) -1 else it + 3 }
}
