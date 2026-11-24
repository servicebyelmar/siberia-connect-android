package com.v2ray.ang.ui.sc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * diag_v1: краткая диагностика последней неудачной попытки подключения — для вложения в обращение.
 *
 * Только РЕГИСТРИРУЕТ то, что клиент и так уже знает (результаты проверки аккаунта, замера задержек,
 * ожидания запуска ядра и переключения на резервный). Ничего не запускает, не меняет и сети не использует.
 * Текст собирается по явному списку полей ([render]): платформа и версия, время сбоя, результат,
 * аккаунт (если был подтверждён), попытки по узлам, замер задержек, восстановление.
 * В текст не попадают журналы, ключи, токены, IP-адреса, идентификатор устройства и сырые сообщения ядра/сервера.
 * Данные живут только в памяти процесса: после перезапуска приложения их нет, и отчёт честно это говорит.
 */
class ScConnectDiag(private val clock: () -> Long = System::currentTimeMillis) {

    /** Чем закончилась попытка подключения к узлу. */
    enum class Result(val text: String) {
        OK("подключились"),
        CORE_TIMEOUT("ядро не запустилось вовремя"),
        NO_REPLY("запустилось, но узел не ответил через туннель"),
        CORE_ERROR("ядро сообщило об ошибке запуска"),
    }

    data class Attempt(val at: Long, val node: String, val result: Result)
    data class Probe(val node: String, val ms: Long?)   // ms == null — не ответил

    private class Incident(val failedAt: Long) {
        val attempts = ArrayList<Attempt>()
        var probes: List<Probe> = emptyList()
        var account: String? = null
        var reason: String? = null
        var recoveredAt: Long? = null
        var recoveredNode: String? = null
        var autoMode: Boolean = true
    }

    private var current: Incident? = null          // сбой (пока не восстановлено)
    private var pendingProbes: List<Probe> = emptyList()
    private var pendingAccount: String? = null
    private var pendingAuto: Boolean = true
    private var lastNode: String? = null

    /** Новый запуск подключения: подтверждённый статус аккаунта (null — не удалось узнать) и режим выбора. */
    @Synchronized
    fun begin(accountState: String?, auto: Boolean) {
        pendingAccount = accountState?.takeIf { it.isNotBlank() }
        pendingAuto = auto
        pendingProbes = emptyList()
    }

    /** Результат замера задержек до узлов (имя → мс, null/≤0 — не ответил). */
    @Synchronized
    fun probes(list: List<Pair<String, Long?>>) {
        pendingProbes = list.map { Probe(cleanName(it.first), it.second?.takeIf { ms -> ms > 0 }) }
    }

    /** Итог попытки к конкретному узлу. Любой не-OK открывает (или дополняет) сбой. */
    @Synchronized
    fun attempt(node: String, result: Result) {
        val name = cleanName(node)
        if (result == Result.OK) { lastNode = name; return }
        val inc = open()
        if (inc.attempts.size < MAX_ATTEMPTS) inc.attempts.add(Attempt(clock(), name, result))
    }

    /** Подключение не состоялось без попыток к узлам (аккаунт, нет ключа) или попытки закончились. */
    @Synchronized
    fun fail(reason: String) {
        val inc = open()
        if (inc.reason == null) inc.reason = reason
    }

    /** Статус «подключено»: если был открытый сбой — отмечаем восстановление отдельной строкой. */
    @Synchronized
    fun connected(node: String? = null) {
        val inc = current ?: return
        if (inc.recoveredAt == null) {
            inc.recoveredAt = clock()
            inc.recoveredNode = (node ?: lastNode)?.let(::cleanName)
        }
    }

    private fun open(): Incident {
        val cur = current
        if (cur != null && cur.recoveredAt == null) return cur
        return Incident(clock()).also {
            it.account = pendingAccount; it.probes = pendingProbes; it.autoMode = pendingAuto
            current = it
        }
    }

    /** Есть ли что показать: был ли хоть один зафиксированный сбой. */
    @Synchronized
    fun hasIncident(): Boolean = current != null

    /** Текст отчёта ровно в том виде, в каком его увидит поддержка. */
    @Synchronized
    fun render(version: String, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.US): String {
        val full = SimpleDateFormat("dd.MM HH:mm:ss", locale).apply { timeZone = tz }
        val hm = SimpleDateFormat("HH:mm:ss", locale).apply { timeZone = tz }
        val inc = current
        val sb = StringBuilder()
        sb.append("Диагностика Siberia Connect (приложена пользователем)\n")
        sb.append("Платформа: Android · версия ").append(cleanText(version, 20)).append('\n')
        if (inc == null) {
            sb.append("Неудачных попыток подключения с запуска приложения не зафиксировано.")
            return sb.toString()
        }
        sb.append("Сбой: ").append(full.format(Date(inc.failedAt))).append(" (").append(offset(tz, inc.failedAt)).append(")\n")
        sb.append("Результат: ").append(summary(inc)).append('\n')
        inc.account?.let { sb.append("Аккаунт: ").append(accountText(it)).append(" (проверено перед подключением)\n") }
        if (inc.attempts.isEmpty()) {
            sb.append("Попытки по узлам: данных нет")
            if (!inc.autoMode) sb.append(" (узел выбран вручную, переключение на резервный отключено)")
            sb.append('\n')
        } else {
            sb.append("Попытки по узлам:\n")
            inc.attempts.forEachIndexed { i, a ->
                sb.append(i + 1).append(". ").append(hm.format(Date(a.at))).append(' ').append(a.node)
                    .append(" — ").append(a.result.text).append('\n')
            }
        }
        if (inc.probes.isNotEmpty()) {
            sb.append("Замер задержки перед подключением: ")
            sb.append(inc.probes.joinToString("; ") { p -> p.node + ": " + (p.ms?.let { "$it мс" } ?: "нет ответа") })
            sb.append('\n')
        }
        inc.recoveredAt?.let { at ->
            sb.append("Восстановление: соединение установилось в ").append(hm.format(Date(at)))
            inc.recoveredNode?.let { sb.append(" (").append(it).append(')') }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun summary(inc: Incident): String =
        inc.reason?.let { "попытка подключения не удалась — $it" } ?: "попытка подключения не удалась"

    private fun accountText(state: String): String = when (state) {
        "active" -> "подписка активна"
        "expired" -> "подписка закончилась"
        "blocked" -> "доступ приостановлен"
        "device_limit" -> "достигнут лимит устройств"
        "no_account" -> "аккаунт не найден"
        "relogin" -> "нужен повторный вход"
        else -> "статус «${cleanText(state, 20)}»"
    }

    private fun offset(tz: TimeZone, at: Long): String {
        val m = tz.getOffset(at) / 60_000
        val sign = if (m < 0) "-" else "+"
        val a = kotlin.math.abs(m)
        return "UTC$sign${a / 60}" + if (a % 60 != 0) ":%02d".format(a % 60) else ""
    }

    companion object {
        const val MAX_ATTEMPTS = 12
        private val IP4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IP6 = Regex("""([0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}""")
        private val LONG_TOKEN = Regex("""[A-Za-z0-9_\-]{24,}""")

        /** Имя узла из названия профиля: без управляющих символов, IP-адресов и длинных «ключеподобных» строк. */
        fun cleanName(raw: String): String {
            val s = cleanText(raw, 40)
            if (s.isEmpty() || IP4.containsMatchIn(s) || IP6.containsMatchIn(s) || LONG_TOKEN.containsMatchIn(s)) return "узел"
            return s
        }

        fun cleanText(raw: String, max: Int): String =
            raw.filter { !it.isISOControl() }.trim().take(max)
    }
}
