package com.ugport.network

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {

    private val diagnostics = Diagnostics()

    @Test
    fun redactsSensitiveQueryParameters() {
        val url = "https://nas/ugreen/v1/x?token=abc&uid=u1&ugk=k&code=c&key=k2&sign=s&port=8080#frag"
        assertEquals(
            "https://nas/ugreen/v1/x?token=***&uid=***&ugk=***&code=***&key=***&sign=***&port=8080#frag",
            diagnostics.redactUrl(url),
        )
    }

    @Test
    fun keepsNonSensitiveParameters() {
        assertEquals(
            "https://nas/page?port=8080&path=%2Fpanel",
            diagnostics.redactUrl("https://nas/page?port=8080&path=%2Fpanel"),
        )
    }

    @Test
    fun masksTokensByLength() {
        assertEquals("<none>", diagnostics.maskToken(null))
        assertEquals("<none>", diagnostics.maskToken(""))
        assertEquals("***", diagnostics.maskToken("short"))
        assertEquals("abcdef...wxyz", diagnostics.maskToken("abcdefghijklmnopwxyz"))
    }
}
