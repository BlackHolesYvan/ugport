package com.ugport.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGpuProbeTest {

    @Test
    fun decodeJsStringHandlesBasicValues() {
        assertEquals("", decodeJsString(null))
        assertEquals("", decodeJsString(""))
        assertEquals("", decodeJsString("null"))
        assertEquals("abc", decodeJsString("\"abc\""))
        assertEquals("started", decodeJsString("started"))
        assertEquals("abc", decodeJsString("\"abc"))
    }

    @Test
    fun decodeJsStringHandlesJsonEscapes() {
        assertEquals("a\"b", decodeJsString("\"a\\\"b\""))
        assertEquals("a\\b", decodeJsString("\"a\\\\b\""))
        assertEquals("a;b", decodeJsString("\"a\\u003bb\""))
        assertEquals("a/b", decodeJsString("\"a\\/b\""))
        assertEquals("a\nb", decodeJsString("\"a\\nb\""))
    }

    @Test
    fun parseFullSnapshot() {
        val result = parseWebGpuProbe(
            "\"secure=true;protocol=https:;gpu=true;adapter=ok;webgl2=true;visible=true;\""
        )!!
        assertEquals(true, result.secure)
        assertEquals("https", result.protocol)
        assertEquals(true, result.gpu)
        assertEquals(GpuAdapterState.OK, result.adapter)
        assertEquals(true, result.webgl2)
        assertEquals(true, result.visible)
        assertNull(result.error)
    }

    @Test
    fun parseInsecureHttpSnapshot() {
        val result = parseWebGpuProbe(
            "\"secure=false;protocol=http:;gpu=false;adapter=none;webgl2=true;visible=true;\""
        )!!
        assertFalse(result.secure!!)
        assertEquals("http", result.protocol)
        assertEquals(false, result.gpu)
        assertEquals(GpuAdapterState.NONE, result.adapter)
    }

    @Test
    fun parseAdapterStates() {
        assertEquals(GpuAdapterState.PENDING, parseWebGpuProbe("\"adapter=pending;\"")!!.adapter)
        assertEquals(GpuAdapterState.TIMEOUT, parseWebGpuProbe("\"adapter=timeout;\"")!!.adapter)
        assertEquals(GpuAdapterState.ERROR, parseWebGpuProbe("\"adapter=error;\"")!!.adapter)
        assertEquals(GpuAdapterState.UNKNOWN, parseWebGpuProbe("\"adapter=wat;\"")!!.adapter)
    }

    @Test
    fun parseIgnoresUnknownAndDuplicateFields() {
        val result = parseWebGpuProbe(
            "\"adapter=ok;adapter=none;foo=bar;protocol=HTTPS:;gpu=1;secure=1;\""
        )!!
        assertEquals(GpuAdapterState.OK, result.adapter)
        assertEquals("https", result.protocol)
        assertEquals(true, result.gpu)
        assertEquals(true, result.secure)
    }

    @Test
    fun parseDecodesErrorComponent() {
        assertEquals(
            "a=b c",
            parseWebGpuProbe("\"adapter=error;error=a%3Db%20c;\"")!!.error,
        )
        assertEquals("%zz", parseWebGpuProbe("\"error=%zz;\"")!!.error)
    }

    @Test
    fun parseReturnsNullForEmptyValues() {
        assertNull(parseWebGpuProbe(null))
        assertNull(parseWebGpuProbe(""))
        assertNull(parseWebGpuProbe("\"\""))
        assertNull(parseWebGpuProbe("null"))
        assertNull(parseWebGpuProbe("\"started\""))
    }

    @Test
    fun httpVerdictWinsOverMissingGpu() {
        assertEquals(
            WebGpuVerdict.INSECURE_HTTP,
            webGpuVerdict(result(secure = false, protocol = "http", gpu = false, adapter = GpuAdapterState.NONE)),
        )
    }

    @Test
    fun localhostStyleHttpWithSecureContextFallsThrough() {
        assertEquals(
            WebGpuVerdict.AVAILABLE,
            webGpuVerdict(result(secure = true, protocol = "http", gpu = true, adapter = GpuAdapterState.OK)),
        )
    }

    @Test
    fun verdictCoversGpuStates() {
        assertEquals(
            WebGpuVerdict.WEBVIEW_UNSUPPORTED,
            webGpuVerdict(result(gpu = false, adapter = GpuAdapterState.NONE)),
        )
        assertEquals(
            WebGpuVerdict.GPU_UNAVAILABLE,
            webGpuVerdict(result(adapter = GpuAdapterState.NONE, visible = true)),
        )
        assertEquals(
            WebGpuVerdict.UNKNOWN,
            webGpuVerdict(result(adapter = GpuAdapterState.NONE, visible = false)),
        )
        assertEquals(
            WebGpuVerdict.UNKNOWN,
            webGpuVerdict(result(adapter = GpuAdapterState.TIMEOUT)),
        )
        assertEquals(
            WebGpuVerdict.UNKNOWN,
            webGpuVerdict(result(adapter = GpuAdapterState.ERROR)),
        )
        assertEquals(
            WebGpuVerdict.UNKNOWN,
            webGpuVerdict(result(adapter = GpuAdapterState.PENDING)),
        )
        assertEquals(WebGpuVerdict.UNKNOWN, webGpuVerdict(null))
    }

    @Test
    fun hintTextOnlyForUnavailableVerdicts() {
        assertNull(webGpuHintText(WebGpuVerdict.AVAILABLE))
        assertNull(webGpuHintText(WebGpuVerdict.UNKNOWN))
        assertTrue(webGpuHintText(WebGpuVerdict.INSECURE_HTTP)!!.isNotEmpty())
        assertTrue(webGpuHintText(WebGpuVerdict.INSECURE_CONTEXT)!!.isNotEmpty())
        assertTrue(webGpuHintText(WebGpuVerdict.WEBVIEW_UNSUPPORTED)!!.isNotEmpty())
        assertTrue(webGpuHintText(WebGpuVerdict.GPU_UNAVAILABLE)!!.isNotEmpty())
    }

    @Test
    fun scriptsUseSameResultKey() {
        assertTrue(WebGpuProbe.startScript().contains(WebGpuProbe.RESULT_KEY))
        assertTrue(WebGpuProbe.readScript().contains(WebGpuProbe.RESULT_KEY))
    }

    private fun result(
        secure: Boolean? = true,
        protocol: String = "https",
        gpu: Boolean? = true,
        adapter: GpuAdapterState = GpuAdapterState.OK,
        webgl2: Boolean? = true,
        visible: Boolean? = true,
    ) = WebGpuProbeResult(
        secure = secure,
        protocol = protocol,
        gpu = gpu,
        adapter = adapter,
        webgl2 = webgl2,
        visible = visible,
        error = null,
    )
}
