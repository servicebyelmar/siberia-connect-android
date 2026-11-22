package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class VlessFmtTest {
    @Test
    fun transportVariantsPreserveFullProfileNamesAndConnectionParameters() {
        val variants = listOf(
            Triple("Ледокол · Быстрый", "tcp", "flow=xtls-rprx-vision"),
            Triple("Ледокол · Резерв", "xhttp", "path=%2Fbackup")
        )
        for ((name, transport, options) in variants) {
            val fragment = URI(null, null, null, name).toASCIIString().substringAfter('#')
            val profile = requireNotNull(
                VlessFmt.parse(
                    "vless://00000000-0000-4000-8000-000000000001@example.com:443" +
                        "?encryption=none&security=reality&sni=example.org&pbk=fixture-key" +
                        "&type=$transport&$options#$fragment"
                )
            )
            assertEquals(name, profile.remarks)
            assertEquals(transport, profile.network)
            assertEquals("443", profile.serverPort)
            assertEquals("reality", profile.security)
            assertEquals("example.org", profile.sni)
            assertEquals("fixture-key", profile.publicKey)
            if (transport == "tcp") assertEquals("xtls-rprx-vision", profile.flow)
            else assertEquals("/backup", profile.path)
        }
    }
}
