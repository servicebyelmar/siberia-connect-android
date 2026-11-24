package com.v2ray.ang.ui.sc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScSplitPolicyTest {

    @Test
    fun storedModeWins() {
        assertEquals("all", ScSplitPolicy.resolveMode("all", true))
        assertEquals("smart", ScSplitPolicy.resolveMode("smart", false))
    }

    @Test
    fun existingUsersKeepTheirPreviousChoice() {
        // нового ключа режима ещё нет: берём прежний флаг «Российские сайты напрямую»
        assertEquals("all", ScSplitPolicy.resolveMode(null, false))
        assertEquals("smart", ScSplitPolicy.resolveMode(null, true))
        // новая установка: ни ключа, ни флага — умное разделение по умолчанию
        assertEquals("smart", ScSplitPolicy.resolveMode(null, null))
        assertEquals("smart", ScSplitPolicy.resolveMode("мусор", null))
    }

    @Test
    fun smartRulesNeedGeoLists() {
        assertTrue(ScSplitPolicy.useSmartRules("smart", true))
        assertFalse(ScSplitPolicy.useSmartRules("smart", false))   // списков нет — всё через VPN
        assertFalse(ScSplitPolicy.useSmartRules("all", true))
        assertEquals("smart", ScSplitPolicy.rulesKind("smart", true))
        assertEquals("base", ScSplitPolicy.rulesKind("all", true))
    }

    @Test
    fun withoutListsNoGeoRulesAreUsedInAnyMode() {
        // с geo-ссылками ядро не запускается — в обоих режимах нужен набор без них
        assertEquals("safe", ScSplitPolicy.rulesKind("smart", false))
        assertEquals("safe", ScSplitPolicy.rulesKind("all", false))
    }

    @Test
    fun userExceptionsAreNeverTouchedByModeSwitch() {
        assertEquals(ScSplitPolicy.AppExceptions.KEEP, ScSplitPolicy.appExceptions("all", true))
        assertEquals(ScSplitPolicy.AppExceptions.KEEP, ScSplitPolicy.appExceptions("smart", true))
        assertTrue(ScSplitPolicy.appsCustomized(true, 0))
        assertTrue(ScSplitPolicy.appsCustomized(false, 3))      // снимал приложения с автосписка
        assertFalse(ScSplitPolicy.appsCustomized(false, 0))
    }

    @Test
    fun autoExceptionsFollowTheMode() {
        assertEquals(ScSplitPolicy.AppExceptions.DISABLE, ScSplitPolicy.appExceptions("all", false))
        assertEquals(ScSplitPolicy.AppExceptions.ENABLE_AUTO, ScSplitPolicy.appExceptions("smart", false))
    }

    @Test
    fun rulesAreRewrittenOnlyWhenNeeded() {
        assertFalse(ScSplitPolicy.mustRewriteRules("smart", "smart"))        // совпадает — ничего не делаем
        assertTrue(ScSplitPolicy.mustRewriteRules("smart", "base"))          // смена режима
        assertTrue(ScSplitPolicy.mustRewriteRules("base", "safe"))           // списки пропали
        assertTrue(ScSplitPolicy.mustRewriteRules("safe", "smart"))          // списки вернулись
        // старая установка: правила не записывались — пользовательские не трогаем...
        assertFalse(ScSplitPolicy.mustRewriteRules(null, "smart"))
        assertFalse(ScSplitPolicy.mustRewriteRules(null, "base"))
        // ...кроме случая, когда без списков ядро не запустится
        assertTrue(ScSplitPolicy.mustRewriteRules(null, "safe"))
    }
}
