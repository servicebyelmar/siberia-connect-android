package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScLockCandidatesTest {
    @Test
    fun cachedLegacySubscriptionMigratesToMergedEdge() {
        val path = "/ds275i4x6130qjd4/test-key"
        val merged = "https://sibirapp.duckdns.org$path"
        val legacy = "https://snezhnet.duckdns.org:2096$path"

        assertTrue(ScLock.needsSubUpdate(legacy, merged))
        assertTrue(ScLock.needsSubUpdate(legacy, legacy))
        assertFalse(ScLock.needsSubUpdate(merged, legacy))
        assertFalse(ScLock.needsSubUpdate(merged, merged))
        assertTrue(ScLock.needsSubUpdate(merged, "$merged-new"))
        assertFalse(ScLock.needsSubUpdate(merged, "https://example.com/test-key"))
    }

    @Test
    fun mergedSubscriptionKeepsKeyQueryAndLegacyFallbacks() {
        val path = "/ds275i4x6130qjd4/test-key?format=vless"
        val primary = "https://sibirapp.duckdns.org$path"
        val old = "https://snezhnet.duckdns.org:2096$path"
        val relay = "https://sibir-cloud-api.ru:9443$path"
        assertEquals(listOf(primary, old, relay), ScLock.subCandidates(old))
        assertEquals(listOf(primary, old, relay), ScLock.subCandidates(primary))
        val unrelated = "https://sibirapp.duckdns.org/other/test-key"
        assertEquals(listOf(unrelated), ScLock.subCandidates(unrelated))
    }
}
