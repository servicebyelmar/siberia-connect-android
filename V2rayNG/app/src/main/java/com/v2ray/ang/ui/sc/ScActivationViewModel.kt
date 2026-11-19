package com.v2ray.ang.ui.sc

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
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
    /** Блок «вход по QR» (device flow: эндпоинты tv/pair). */
    val pair: PairPhase = PairPhase.Loading,
    val pairUserCode: String = "",
    val pairUrl: String = "",
    val pairQr: Bitmap? = null,
)

enum class PairPhase { Loading, Waiting, Expired, Error }

class ScActivationViewModel internal constructor(
    app: Application,
    private val account: ScAccount,
    /** Диспетчер сети/QR; в тестах подменяется тестовым. */
    private val io: CoroutineDispatcher,
    /** Часы (мс); в тестах — виртуальное время. */
    private val clock: () -> Long,
) : AndroidViewModel(app) {
    private val ctx = app.applicationContext

    constructor(app: Application) : this(
        app,
        ScAccount(ScSecureStore.prefs(app.applicationContext)) { ScSecureStore.deviceUuid(app.applicationContext) },
        Dispatchers.IO,
        System::currentTimeMillis,
    )

    private val _state = MutableStateFlow(ScActivationUiState().withStoredLock())
    val state: StateFlow<ScActivationUiState> = _state.asStateFlow()

    private fun ScActivationUiState.withStoredLock(): ScActivationUiState {
        val until = ScSecureStore.lockUntil(ctx)
        val rb = ScSecureStore.rebindName(ctx)
        val base = if (rb != null) copy(rebindAvailable = true, rebindDeviceName = rb.takeIf { it != "-" }.orEmpty()) else this
        return if (until > clock()) base.copy(lockUntilMs = until, lockDeviceName = ScSecureStore.lockName(ctx)) else base
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
        viewModelScope.launch(io) {
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
        viewModelScope.launch(io) {
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
        val until = clock() + 5 * 60_000L
        var polled: ScAccount.Redeem? = null
        while (polled == null && clock() < until) {
            delay(2500)
            polled = runCatching { account.redeemPoll(w.req) }.getOrNull()
        }
        return polled ?: ScAccount.Redeem.Failed("Время на подтверждение вышло — попробуй ещё раз")
    }
    // ───────────── вход по QR / короткому коду (Device Authorization Flow) ─────────────

    /** ТВ ставит false, пока вход уже выполнен (экран входа скрыт): ON_START не должен заводить новую сессию. */
    @Volatile var pairingEnabled = true
    private var pairJob: Job? = null
    private var pairSession: ScAccount.TvPair? = null
    private var pairEndsAt = 0L

    /**
     * Экран на переднем плане: продолжаем опрос текущей сессии или, если её нет / время вышло, создаём новую.
     * Вызывается из ON_START; повторный вызов при уже идущем опросе ничего не делает.
     */
    fun resumePairing() {
        if (!pairingEnabled || pairJob?.isActive == true || _state.value.activatedLink != null) return
        val s = pairSession
        if (s != null && clock() < pairEndsAt) pairJob = viewModelScope.launch(io) { poll(s) } else newPairing()
    }

    /** ON_STOP / уход с экрана: опрос останавливается мгновенно (сессия остаётся — вернёмся, пока она жива). */
    fun pausePairing() {
        pairJob?.cancel()
        pairJob = null
    }

    /** «Обновить код» и первый запуск. */
    fun newPairing() {
        pairJob?.cancel()
        pairSession = null
        _state.update { it.copy(pair = PairPhase.Loading, pairUserCode = "", pairUrl = "", pairQr = null) }
        pairJob = viewModelScope.launch(io) {
            val p = runCatching { account.tvPairStart(ScSecureStore.deviceName().ifBlank { "Android" }) }.getOrNull()
            if (p == null) {
                _state.update { it.copy(pair = PairPhase.Error) }
                return@launch
            }
            val bmp = withContext(io) { qr(p.qrUrl) }
            pairSession = p
            pairEndsAt = clock() + p.expiresIn * 1000L
            _state.update { it.copy(pair = PairPhase.Waiting, pairUserCode = p.userCode, pairUrl = p.qrUrl, pairQr = bmp) }
            poll(p)
        }
    }

    private suspend fun poll(p: ScAccount.TvPair) {
        run {
            while (currentCoroutineContext().isActive && clock() < pairEndsAt) {
                delay(minOf(p.interval * 1000L, pairEndsAt - clock()).coerceAtLeast(0L))
                if (clock() >= pairEndsAt) break      // код истёк ровно в срок, а не на следующем тике
                val r = runCatching { account.tvPairPoll(p) }.getOrNull() ?: continue     // нет связи — повторим
                when (r) {
                    is ScAccount.TvPoll.Approved -> {
                        pairSession = null
                        _state.update { it.copy(activatedLink = r.link) }
                        break
                    }
                    ScAccount.TvPoll.Expired -> { markPairExpired(); break }
                    ScAccount.TvPoll.Pending -> Unit
                }
            }
            if (_state.value.activatedLink == null && clock() >= pairEndsAt) markPairExpired()
        }
    }

    private fun markPairExpired() {
        pairSession = null
        _state.update { it.copy(pair = PairPhase.Expired, pairQr = null) }
    }

    private fun qr(text: String, size: Int = 480): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** ТВ: после выхода из аккаунта экран входа должен начать с чистого листа. */
    fun resetSession() {
        pairJob?.cancel(); pairJob = null; pairSession = null
        _state.update { it.copy(activatedLink = null, code = "", error = null, busy = false, pair = PairPhase.Loading, pairQr = null) }
    }

    override fun onCleared() {
        pairJob?.cancel()
        super.onCleared()
    }

    /** Таймер дошёл до нуля: снимаем блокировку и снова показываем ввод кода. */
    fun onLockExpired() {
        ScSecureStore.clearLock(ctx)
        _state.update { it.copy(lockUntilMs = 0L, lockDeviceName = "") }
    }
}
