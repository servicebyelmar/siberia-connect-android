package com.v2ray.ang.ui.sc

import com.v2ray.ang.ui.sc.ScConnectDiag.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.TimeZone

class ScConnectDiagTest {

    private val t0 = 1795505520000L                 // 24.11.2026 14:32:00 в UTC+7
    private val tz = TimeZone.getTimeZone("GMT+07:00")
    private var now = t0
    private val diag = ScConnectDiag { now }
    private fun at(sec: Int) { now = t0 + sec * 1000L }
    private fun render() = diag.render("1.2.6", tz, Locale.US)

    private val buran = "Буран · Нидерланды"
    private val ledokol = "Ледокол · Финляндия"

    @Test
    fun failure_withReserveAttempt() {
        at(0); diag.begin("active", true)
        diag.probes(listOf(buran to 120L, ledokol to null))
        at(5); diag.attempt(buran, Result.NO_REPLY)
        at(12); diag.attempt(ledokol, Result.CORE_TIMEOUT)
        diag.fail("резервные узлы закончились")

        assertEquals(
            """
            Диагностика Siberia Connect (приложена пользователем)
            Платформа: Android · версия 1.2.6
            Сбой: 24.11 14:32:05 (UTC+7)
            Результат: попытка подключения не удалась — резервные узлы закончились
            Аккаунт: подписка активна (проверено перед подключением)
            Попытки по узлам:
            1. 14:32:05 Буран · Нидерланды — запустилось, но узел не ответил через туннель
            2. 14:32:12 Ледокол · Финляндия — ядро не запустилось вовремя
            Замер задержки перед подключением: Буран · Нидерланды: 120 мс; Ледокол · Финляндия: нет ответа
            """.trimIndent(),
            render()
        )
    }

    @Test
    fun recovery_isReportedSeparately() {
        at(0); diag.begin("active", true)
        at(5); diag.attempt(buran, Result.NO_REPLY)
        at(12); diag.attempt(ledokol, Result.OK)
        at(14); diag.connected(ledokol)

        val text = render()
        assertTrue(text.contains("1. 14:32:05 Буран · Нидерланды — запустилось, но узел не ответил через туннель"))
        assertFalse(text.contains("Ледокол · Финляндия — подключились"))   // успешные попытки не перечисляются как сбой
        assertTrue(text.endsWith("Восстановление: соединение установилось в 14:32:14 (Ледокол · Финляндия)"))
        assertFalse(text.contains("Замер задержки"))                         // замера не было — строки нет
    }

    @Test
    fun noAttemptData_afterAccountFailure_noFabricatedValues() {
        at(0); diag.begin("expired", true)
        at(2); diag.fail("аккаунт не позволяет подключиться")

        assertEquals(
            """
            Диагностика Siberia Connect (приложена пользователем)
            Платформа: Android · версия 1.2.6
            Сбой: 24.11 14:32:02 (UTC+7)
            Результат: попытка подключения не удалась — аккаунт не позволяет подключиться
            Аккаунт: подписка закончилась (проверено перед подключением)
            Попытки по узлам: данных нет
            """.trimIndent(),
            render()
        )
    }

    @Test
    fun noAttemptData_manualMode_andUnconfirmedAccount() {
        at(0); diag.begin(null, false)                 // сеть не дала подтверждённого статуса аккаунта
        at(3); diag.fail("запуск не удался")

        val text = render()
        assertFalse(text.contains("Аккаунт:"))
        assertTrue(text.contains("Попытки по узлам: данных нет (узел выбран вручную, переключение на резервный отключено)"))
    }

    @Test
    fun noIncident_saysSo() {
        assertFalse(diag.hasIncident())
        assertEquals(
            """
            Диагностика Siberia Connect (приложена пользователем)
            Платформа: Android · версия 1.2.6
            Неудачных попыток подключения с запуска приложения не зафиксировано.
            """.trimIndent(),
            render()
        )
    }

    @Test
    fun successfulConnect_withoutFailure_createsNoIncident() {
        diag.begin("active", true)
        diag.attempt(buran, Result.OK)
        diag.connected(buran)
        assertFalse(diag.hasIncident())
    }

    @Test
    fun newFailureAfterRecovery_replacesOldIncident() {
        at(0); diag.begin("active", true)
        at(5); diag.attempt(buran, Result.NO_REPLY)
        at(9); diag.connected(ledokol)
        at(600); diag.begin("active", true)
        at(601); diag.attempt(ledokol, Result.CORE_ERROR)

        val text = render()
        assertTrue(text.contains("Сбой: 24.11 14:42:01"))
        assertFalse(text.contains("Восстановление"))
        assertFalse(text.contains(buran))
    }

    @Test
    fun privacy_ipAddressesTokensAndControlCharsNeverAppear() {
        at(0); diag.begin("active", true)
        diag.probes(listOf("203.0.113.7" to 50L, "2001:db8::1" to 60L, "AbCdEfGhIjKlMnOpQrStUvWxYz012345" to null))
        at(1); diag.attempt("node 198.51.100.4\u0000", Result.CORE_ERROR)
        at(2); diag.attempt("Буран\n· Нидерланды", Result.NO_REPLY)

        val text = render()
        assertFalse(Regex("""\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(text))
        assertFalse(text.contains("2001:db8"))
        assertFalse(text.contains("AbCdEfGh"))
        assertFalse(text.contains("\u0000"))
        assertTrue(text.contains("1. 14:32:01 узел — ядро сообщило об ошибке запуска"))
        assertTrue(text.contains("2. 14:32:02 Буран· Нидерланды — запустилось, но узел не ответил через туннель"))
    }

    @Test
    fun attempts_areCapped() {
        at(0); diag.begin("active", true)
        repeat(30) { diag.attempt("N$it", Result.NO_REPLY) }
        assertEquals(ScConnectDiag.MAX_ATTEMPTS, render().lines().count { it.matches(Regex("""\d+\. .*""")) })
    }
}
