package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Чистая логика фоновой телеметрии: фильтр ложных срабатываний и очистка лога (те же случаи, что в Windows-клиенте). */
class ScTelemetryGateTest {
    private val never = Double.POSITIVE_INFINITY

    private fun decide(reason: String?, net: Boolean = true, wake: Double = never, user: Double = never, last: Double = never) =
        ScTelemetryGate.decide(reason, net, wake, user, last)

    @Test fun `real failure with working internet is sent`() {
        for (r in ScTelemetryGate.REASONS) assertNull(decide(r), r)
    }

    @Test fun `no base internet is not a vpn failure`() {
        assertEquals("no_base_net", decide(ScTelemetryGate.NO_TRAFFIC, net = false))
        assertEquals("no_base_net", decide(ScTelemetryGate.NO_TRAFFIC, net = false, wake = 1.0, user = 1.0, last = 1.0))
    }

    @Test fun `user or app stop silences reports for 30 seconds`() {
        assertEquals("user_disconnect", decide(ScTelemetryGate.SESSION_DROPPED, user = 5.0))
        assertNull(decide(ScTelemetryGate.SESSION_DROPPED, user = 30.0))
    }

    @Test fun `first 10 seconds after wake are ignored`() {
        assertEquals("after_sleep", decide(ScTelemetryGate.TUNNEL_TIMEOUT, wake = 5.0))
        assertNull(decide(ScTelemetryGate.TUNNEL_TIMEOUT, wake = 10.0))
    }

    @Test fun `same reason is deduplicated for 10 minutes`() {
        assertEquals("duplicate", decide(ScTelemetryGate.TUNNEL_TIMEOUT, last = 540.0))
        assertNull(decide(ScTelemetryGate.TUNNEL_TIMEOUT, last = 600.0))
    }

    @Test fun `unknown reason is rejected`() {
        assertEquals("bad_reason", decide("AUTH_FAILED"))
        assertEquals("bad_reason", decide(null))
    }

    @Test fun `log is cleaned from secrets and bounded`() {
        val dirty = listOf(
            "connect vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?x=1",
            "uuid 11111111-2222-3333-4444-555555555555 ip 85.12.34.56",
            "X-App-Token: abcdef123456",
            "mail a.b@example.com",
            "code SC-ABCDE-FGHJK and SC-ABCD-EFGH-JKLM",
            "hex 0123456789abcdef0123456789abcdef",
            "v6 2001:db8:0:0:0:0:0:1",
            "sub https://sibirapp.duckdns.org/sub/abcd1234",
            "x".repeat(1000),
        )
        val clean = ScTelemetryGate.cleanLog(dirty)
        val joined = clean.joinToString("\n")
        for (secret in listOf("11111111-2222", "85.12.34.56", "1.2.3.4", "abcdef123456", "example.com", "ABCDE-FGHJK", "EFGH-JKLM",
            "0123456789abcdef", "2001:db8", "abcd1234")) {
            assertFalse(joined.contains(secret), secret)
        }
        assertTrue(clean.all { it.length <= ScTelemetryGate.MAX_LINE_LENGTH })
        val many = (0 until 100).map { "l$it" }
        val cut = ScTelemetryGate.cleanLog(many)
        assertEquals(25, cut.size)
        assertEquals("l75", cut.first())
        assertEquals("l99", cut.last())
        assertTrue(ScTelemetryGate.cleanLog(null).isEmpty())
        assertEquals(listOf("watchdog: сервер не отвечает, переключаюсь"), ScTelemetryGate.cleanLog(listOf("watchdog: сервер не отвечает, переключаюсь")))
    }
}
