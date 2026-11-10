package com.hugo.player.update

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.hugo.player.BuildConfig

/**
 * In-App Updates da Google Play (build "play"):
 *  - ao abrir / voltar ao app (no máximo 1 aviso por sessão) pergunta à Play se tem versão nova;
 *  - prioridade >= 4 ou 7+ dias de atraso: IMMEDIATE (tela cheia da Google); senão FLEXIBLE
 *    (baixa em segundo plano e o app mostra "Atualização pronta. Reiniciar");
 *  - IMMEDIATE interrompido é retomado no onResume.
 * Qualquer erro só vai para o log (app instalado fora da Play, sem conta Google etc.).
 */
object InAppUpdates {
    private const val TAG = "HugoInAppUpdate"
    private const val PREFS = "hugo_in_app_update"
    private const val KEY_IMMEDIATE_VC = "immediate_version_code"

    private var manager: AppUpdateManager? = null
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    /** já mostrou o aviso da Google nesta sessão (processo) */
    @Volatile private var promptedThisSession = false

    fun register(activity: ComponentActivity) {
        if (!BuildConfig.PLAY_STORE) return
        try {
            val m = AppUpdateManagerFactory.create(activity.applicationContext)
            manager = m
            // precisa ser registrado no onCreate (antes do STARTED)
            val myLauncher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
                Log.i(TAG, "resultado do fluxo da Play: ${r.resultCode}")
            }
            launcher = myLauncher
            val listener = InstallStateUpdatedListener { s ->
                when (s.installStatus()) {
                    InstallStatus.DOWNLOADED -> InAppUpdateState.markReady(true)
                    InstallStatus.FAILED -> Log.w(TAG, "download falhou (código ${s.installErrorCode()})")
                    InstallStatus.CANCELED -> Log.i(TAG, "download cancelado")
                    else -> {}
                }
            }
            m.registerListener(listener)
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    runCatching { m.unregisterListener(listener) }
                    if (launcher === myLauncher) launcher = null
                }
            })
            InAppUpdateState.onComplete = {
                m.completeUpdate().addOnFailureListener { e -> Log.w(TAG, "completeUpdate falhou: ${e.javaClass.simpleName}") }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "In-App Updates indisponível: ${t.javaClass.simpleName}")
            manager = null
        }
    }

    fun onResume(activity: ComponentActivity) {
        val m = manager ?: return
        try {
            m.appUpdateInfo
                .addOnSuccessListener { info -> handle(activity, info) }
                .addOnFailureListener { e -> Log.i(TAG, "sem informação da Play: ${e.javaClass.simpleName}") }
        } catch (t: Throwable) {
            Log.w(TAG, "appUpdateInfo falhou: ${t.javaClass.simpleName}")
        }
    }

    private fun handle(activity: ComponentActivity, info: AppUpdateInfo) {
        try {
            // FLEXIBLE já baixado (inclusive numa sessão anterior): só falta reiniciar
            if (info.installStatus() == InstallStatus.DOWNLOADED) { InAppUpdateState.markReady(true); return }
            when (info.updateAvailability()) {
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    // IMMEDIATE interrompido (app saiu do primeiro plano): retoma a tela da Google
                    if (prefs(activity).getInt(KEY_IMMEDIATE_VC, -1) == info.availableVersionCode() &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    ) start(info, AppUpdateType.IMMEDIATE)
                }
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    if (promptedThisSession) return
                    val flow = InAppUpdatePolicy.choose(
                        priority = info.updatePriority(),
                        stalenessDays = info.clientVersionStalenessDays(),
                        immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                        flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    ) ?: return
                    val type = if (flow == InAppUpdatePolicy.Flow.IMMEDIATE) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
                    if (type == AppUpdateType.IMMEDIATE) prefs(activity).edit().putInt(KEY_IMMEDIATE_VC, info.availableVersionCode()).apply()
                    if (start(info, type)) promptedThisSession = true
                }
                else -> InAppUpdateState.markReady(false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fluxo de atualização falhou: ${t.javaClass.simpleName}")
        }
    }

    private fun start(info: AppUpdateInfo, type: Int): Boolean {
        val m = manager ?: return false
        val l = launcher ?: return false
        return try {
            m.startUpdateFlowForResult(info, l, AppUpdateOptions.newBuilder(type).build())
        } catch (t: Throwable) {
            Log.w(TAG, "não abriu o fluxo da Play: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
