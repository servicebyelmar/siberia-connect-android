package com.v2ray.ang.ui.sc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScActivationUiState(
    val code: String = "",
    val busy: Boolean = false,
    /** Текст ошибки с сервера или ключ ресурса ниже; null — ошибки нет. */
    val error: String? = null,
    val networkError: Boolean = false,
    /** > 0 — экран блокировки: ключ привязан к другому устройству до этого момента (epoch, мс). */
    val lockUntilMs: Long = 0L,
    val lockDeviceName: String = "",
    /** Прошло 72 ч: показываем кнопку «Перепривязать на это устройство». */
    val rebindAvailable: Boolean = false,
    val rebindDeviceName: String = "",
    /** Ссылка подписки после успешной активации — Activity вернёт её в ScActivity. */
    val activatedLink: String? = null,
)

class ScActivationViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val account = ScAccount(ScSecureStore.prefs(ctx)) { ScSecureStore.deviceUuid(ctx) }

    private val _state = MutableStateFlow(ScActivationUiState().withStoredLock())
    val state: StateFlow<ScActivationUiState> = _state.asStateFlow()

    private fun ScActivationUiState.withStoredLock(): ScActivationUiState {
        val until = ScSecureStore.lockUntil(ctx)
        val rb = ScSecureStore.rebindName(ctx)
        val base = if (rb != null) copy(rebindAvailable = true, rebindDeviceName = rb.takeIf { it != "-" }.orEmpty()) else this
        return if (until > System.currentTimeMillis()) base.copy(lockUntilMs = until, lockDeviceName = ScSecureStore.lockName(ctx)) else base
    }

    fun onCodeChange(v: String) = _state.update { it.copy(code = v.take(20), error = null) }

    private fun applyResult(res: ScAccount.Activation) = _state.update {
        when (res) {
            is ScAccount.Activation.Ok -> it.copy(busy = false, activatedLink = res.link, rebindAvailable = false)
            is ScAccount.Activation.Locked -> it.copy(busy = false, lockUntilMs = res.untilMs, lockDeviceName = res.deviceName, rebindAvailable = false)
            is ScAccount.Activation.Rebind -> it.copy(busy = false, rebindAvailable = true, rebindDeviceName = res.deviceName)
            is ScAccount.Activation.Failed -> it.copy(busy = false, error = res.message.ifEmpty { null }, networkError = res.message.isEmpty())
        }
    }

    /** Подтверждённая перепривязка: /rebind, новый ключ сохраняется, Activity вернёт его в главный экран. */
    fun rebind() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, networkError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            applyResult(runCatching { account.rebind() }.getOrElse { ScAccount.Activation.Failed("", true) })
        }
    }

    fun submit() {
        val s = _state.value
        if (s.busy || s.code.isBlank()) return
        _state.update { it.copy(busy = true, error = null, networkError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { account.activate(s.code) }.getOrElse { ScAccount.Activation.Failed("", true) }
            applyResult(res)
        }
    }

    /** Таймер дошёл до нуля: снимаем блокировку и снова показываем ввод кода. */
    fun onLockExpired() {
        ScSecureStore.clearLock(ctx)
        _state.update { it.copy(lockUntilMs = 0L, lockDeviceName = "") }
    }
}
