package com.hugo.player.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Atualização pela própria Google Play (In-App Updates), só no build "play".
 * O código da Play fica em src/play (InAppUpdates); site/Fire TV usam o stub de src/noPlay (não faz nada).
 */
object InAppUpdateState {
    private val _ready = MutableStateFlow(false)
    /** a versão nova já foi baixada em segundo plano (fluxo FLEXIBLE): mostrar "Reiniciar" */
    val ready: StateFlow<Boolean> = _ready
    internal var onComplete: (() -> Unit)? = null

    fun markReady(v: Boolean) { _ready.value = v }
    /** instala a versão baixada (o app reinicia) */
    fun complete() { runCatching { onComplete?.invoke() } }
}

/** Qual fluxo usar quando a Play diz que tem versão nova. */
object InAppUpdatePolicy {
    enum class Flow { FLEXIBLE, IMMEDIATE }
    const val IMMEDIATE_PRIORITY = 4
    const val IMMEDIATE_STALENESS_DAYS = 7

    fun choose(priority: Int, stalenessDays: Int?, immediateAllowed: Boolean, flexibleAllowed: Boolean): Flow? {
        val wantImmediate = priority >= IMMEDIATE_PRIORITY || (stalenessDays ?: 0) >= IMMEDIATE_STALENESS_DAYS
        return when {
            wantImmediate && immediateAllowed -> Flow.IMMEDIATE
            flexibleAllowed -> Flow.FLEXIBLE
            immediateAllowed -> Flow.IMMEDIATE
            else -> null
        }
    }
}
