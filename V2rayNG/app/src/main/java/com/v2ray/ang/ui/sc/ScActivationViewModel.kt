package com.v2ray.ang.ui.sc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** Вход выполнен (ссылка подписки может быть пустой — подписка не активна, статус покажет причину). null — ещё нет. */
    val activatedLink: String? = null,
    /** 1.2.6: вход по номеру подписки ждёт подтверждения в Telegram. */
    val waitMessage: String? = null,
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

    /** Ключ vless:// может быть длинным; пробелы и переводы строк при вставке чистим при отправке. */
    fun onCodeChange(v: String) = _state.update { it.copy(code = v.take(2000), error = null) }

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

    /** 1.2.6: «VPN-XXXX-XXXX» (с любыми пробелами и дефисами) — код активации; всё остальное разбирает сервер (/redeem). */
    private fun isActivationCode(raw: String): Boolean {
        val c = raw.uppercase().filter { it.isLetterOrDigit() }
        return c.length == 11 && c.startsWith("VPN")
    }

    fun submit() {
        val s = _state.value
        if (s.busy || s.code.isBlank()) return
        val raw = s.code.filterNot { it.code in 0x200B..0x200F || it.code == 0x2060 || it.code == 0xFEFF }.trim()
        _state.update { it.copy(busy = true, error = null, networkError = false, waitMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (isActivationCode(raw)) {
                applyResult(runCatching { account.activate(raw) }.getOrElse { ScAccount.Activation.Failed("", true) })
            } else {
                applyRedeem(runCatching { account.redeem(raw) }.getOrElse { ScAccount.Redeem.Failed("") })
            }
        }
    }

    private suspend fun applyRedeem(first: ScAccount.Redeem) {
        val res: ScAccount.Redeem = if (first is ScAccount.Redeem.WaitTelegram) waitTelegram(first) else first
        _state.update {
            when (res) {
                is ScAccount.Redeem.Ok -> it.copy(busy = false, waitMessage = null, activatedLink = res.link)
                is ScAccount.Redeem.Failed -> it.copy(busy = false, waitMessage = null, error = res.message.ifEmpty { null }, networkError = res.message.isEmpty())
                is ScAccount.Redeem.WaitTelegram -> it.copy(busy = false, waitMessage = null)
            }
        }
    }

    /** Номер подписки: ждём подтверждения в Telegram (опрос раз в 2,5 с, до 5 минут). */
    private suspend fun waitTelegram(w: ScAccount.Redeem.WaitTelegram): ScAccount.Redeem {
        _state.update { it.copy(waitMessage = w.message.ifEmpty { "Подтверди вход в Telegram — запрос уже в боте" }) }
        val until = System.currentTimeMillis() + 5 * 60_000L
        var polled: ScAccount.Redeem? = null
        while (polled == null && System.currentTimeMillis() < until) {
            delay(2500)
            polled = runCatching { account.redeemPoll(w.req) }.getOrNull()
        }
        return polled ?: ScAccount.Redeem.Failed("Время на подтверждение вышло — попробуй ещё раз")
    }
    /** Таймер дошёл до нуля: снимаем блокировку и снова показываем ввод кода. */
    fun onLockExpired() {
        ScSecureStore.clearLock(ctx)
        _state.update { it.copy(lockUntilMs = 0L, lockDeviceName = "") }
    }
}
