package com.ugport.web

import java.net.URLDecoder

/** requestAdapter() 的最终状态；PENDING 表示脚本还在等 Promise。 */
internal enum class GpuAdapterState { OK, NONE, PENDING, TIMEOUT, ERROR, UNKNOWN }

internal data class WebGpuProbeResult(
    val secure: Boolean?,
    val protocol: String,
    val gpu: Boolean?,
    val adapter: GpuAdapterState,
    val webgl2: Boolean?,
    val visible: Boolean?,
    val error: String?,
)

internal enum class WebGpuVerdict {
    AVAILABLE,
    INSECURE_HTTP,
    INSECURE_CONTEXT,
    WEBVIEW_UNSUPPORTED,
    GPU_UNAVAILABLE,
    UNKNOWN,
}

/**
 * 页面内 WebGPU 能力探针。
 *
 * evaluateJavascript 不会等待 Promise，所以脚本同步返回一份快照，异步的
 * requestAdapter() 只更新 window.__ugWebGpuProbeResult；原生侧先读同步快照，
 * 只有 adapter 仍为 pending 时才用 postDelayed 继续读。
 */
object WebGpuProbe {

    const val RESULT_KEY = "__ugWebGpuProbeResult"

    fun startScript(): String = START_SCRIPT

    fun readScript(): String = "window.$RESULT_KEY || ''"

    private val START_SCRIPT = """
        (function(){
          var KEY = '$RESULT_KEY';
          function enc(v){
            try { return encodeURIComponent(String(v == null ? '' : v)).slice(0, 160); }
            catch (e) { return 'bad'; }
          }
          try {
            var W = window;
            var secure = (typeof W.isSecureContext === 'boolean') ? W.isSecureContext : null;
            var proto = String(location.protocol || '').replace(':', '');
            var gpu = !!(navigator && navigator.gpu &&
                         typeof navigator.gpu.requestAdapter === 'function');
            var webgl2 = null;
            try {
              var c = document.createElement('canvas');
              var gl = c.getContext && c.getContext('webgl2');
              webgl2 = !!gl;
              if (gl) {
                var ext = gl.getExtension('WEBGL_lose_context');
                if (ext) ext.loseContext();
              }
            } catch (e) {}
            var visible = (document.visibilityState || '') === 'visible';
            var adapter = gpu ? 'pending' : 'none';

            function snapshot(extra){
              var s = 'secure=' + (secure === null ? 'unknown' : String(secure)) +
                      ';protocol=' + proto +
                      ';gpu=' + (gpu ? 'true' : 'false') +
                      ';adapter=' + adapter +
                      ';webgl2=' + (webgl2 === null ? 'unknown' : String(webgl2)) +
                      ';visible=' + (visible ? 'true' : 'false') +
                      (extra ? ';' + extra : '') + ';';
              try { W[KEY] = s; } catch (e) {}
              return s;
            }

            if (!gpu) return snapshot('');

            var settled = false;
            var timer = setTimeout(function(){
              if (settled) return;
              settled = true;
              adapter = 'timeout';
              snapshot('');
            }, 2500);

            try {
              navigator.gpu.requestAdapter().then(function(a){
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                adapter = a ? 'ok' : 'none';
                snapshot('');
              }).catch(function(err){
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                adapter = 'error';
                snapshot('error=' + enc(err && err.message));
              });
            } catch (e) {
              settled = true;
              clearTimeout(timer);
              adapter = 'error';
              snapshot('error=' + enc(e && e.message));
            }
            return snapshot('');
          } catch (e) {
            try {
              window[KEY] = 'error=' + enc(e && e.message) + ';';
            } catch (_) {}
            try { return window[KEY] || ''; } catch (_) { return ''; }
          }
        })();
    """.trimIndent()
}

/** 解析 evaluateJavascript 的返回值；空串、null 字面量、started 都视为无结果。 */
internal fun parseWebGpuProbe(raw: String?): WebGpuProbeResult? {
    val text = decodeJsString(raw).trim()
    if (text.isEmpty() || text == "null" || text == "started") return null
    val body = text.trimEnd(';')
    if (body.isEmpty()) return null
    val fields = LinkedHashMap<String, String>()
    for (part in body.split(';')) {
        if (part.isEmpty()) continue
        val index = part.indexOf('=')
        if (index <= 0) continue
        val key = part.substring(0, index).trim().lowercase()
        if (key.isEmpty() || fields.containsKey(key)) continue
        fields[key] = part.substring(index + 1)
    }
    if (fields.isEmpty()) return null
    val protocol = fields["protocol"].orEmpty().trim().trimEnd(':').lowercase().let {
        if (it == "http" || it == "https") it else ""
    }
    return WebGpuProbeResult(
        secure = parseProbeBool(fields["secure"]),
        protocol = protocol,
        gpu = parseProbeBool(fields["gpu"]),
        adapter = when (fields["adapter"]?.trim()?.lowercase()) {
            "ok", "yes" -> GpuAdapterState.OK
            "none", "no" -> GpuAdapterState.NONE
            "pending" -> GpuAdapterState.PENDING
            "timeout" -> GpuAdapterState.TIMEOUT
            "error" -> GpuAdapterState.ERROR
            else -> GpuAdapterState.UNKNOWN
        },
        webgl2 = parseProbeBool(fields["webgl2"]),
        visible = parseProbeBool(fields["visible"]),
        error = fields["error"]?.takeIf { it.isNotBlank() }?.let(::decodeComponent),
    )
}

/** 把 evaluateJavascript 回调的 JSON 字符串字面量还原成普通字符串。 */
internal fun decodeJsString(raw: String?): String {
    val text = raw ?: return ""
    if (text == "null") return ""
    if (text.length < 2 || !text.startsWith('"')) return text
    if (!text.endsWith('"')) return text.trim('"')
    val body = text.substring(1, text.length - 1)
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\' || i + 1 >= body.length) {
            out.append(c)
            i++
            continue
        }
        val next = body[i + 1]
        when (next) {
            '"' -> { out.append('"'); i += 2 }
            '\\' -> { out.append('\\'); i += 2 }
            '/' -> { out.append('/'); i += 2 }
            'b' -> { out.append('\b'); i += 2 }
            'f' -> { out.append('\u000C'); i += 2 }
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'u' -> {
                val hex = if (i + 5 < body.length) body.substring(i + 2, i + 6) else ""
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 6
                } else {
                    out.append('\\').append('u')
                    i += 2
                }
            }
            else -> {
                out.append('\\')
                out.append(next)
                i += 2
            }
        }
    }
    return out.toString()
}

internal fun webGpuVerdict(result: WebGpuProbeResult?): WebGpuVerdict {
    if (result == null) return WebGpuVerdict.UNKNOWN
    // HTTP 必须先判，否则 http 页面会因为没有 navigator.gpu 被误报成 WebView 不支持
    if (result.protocol == "http" && result.secure != true) return WebGpuVerdict.INSECURE_HTTP
    if (result.secure == false) return WebGpuVerdict.INSECURE_CONTEXT
    if (result.gpu == false) return WebGpuVerdict.WEBVIEW_UNSUPPORTED
    if (result.adapter == GpuAdapterState.NONE && result.visible != false) {
        return WebGpuVerdict.GPU_UNAVAILABLE
    }
    if (result.adapter == GpuAdapterState.OK) return WebGpuVerdict.AVAILABLE
    // timeout/error/pending 不提示，避免把探测失败当成能力缺失
    return WebGpuVerdict.UNKNOWN
}

internal fun webGpuHintText(verdict: WebGpuVerdict): String? = when (verdict) {
    WebGpuVerdict.INSECURE_HTTP ->
        "当前页面使用 HTTP 打开，WebGPU 需要 HTTPS。若服务支持，请改用 HTTPS 地址。"
    WebGpuVerdict.INSECURE_CONTEXT ->
        "当前页面不是安全上下文，WebGPU 不可用。"
    WebGpuVerdict.WEBVIEW_UNSUPPORTED ->
        "当前 WebView 未启用 WebGPU，相关网页功能可能无法使用。" +
        "可在应用商店更新 Android System WebView。"
    WebGpuVerdict.GPU_UNAVAILABLE ->
        "当前设备无法提供 WebGPU 适配器，使用 WebGPU 的页面功能可能受限。"
    WebGpuVerdict.AVAILABLE,
    WebGpuVerdict.UNKNOWN,
    -> null
}

private fun parseProbeBool(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> null
}

private fun decodeComponent(value: String): String = runCatching {
    // encodeURIComponent 不会编码 '+'，先保护起来，避免 URLDecoder 把它变成空格
    URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}.getOrDefault(value)
