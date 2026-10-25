package com.cineflow.tv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Serves the Vite-built web app (assets/www, staged by CI from dist/) over
 * http://127.0.0.1:<port>/. Serving over HTTP instead of file:// avoids
 * module/CORS quirks in WebView. A CSS rule hiding the desktop window
 * controls is injected into index.html, and the bridge shim is referenced
 * as an external script (also injected, before any page script).
 *
 * The shim must NOT be inlined: index.html carries a CSP meta of
 * `script-src 'self'`, which makes WebView refuse inline scripts — an
 * inlined shim silently never runs and window.cineflow stays undefined.
 * Served as /bridge-shim.js from this server it is same-origin and loads
 * like any other bundle file.
 */
class WwwServer(private val context: Context, private val shimJs: String) : NanoHTTPD(0) {

    // Scan-to-type session, armed by CineflowBridge. While inputToken is
    // non-null, GET /input?t=<token> serves a phone-friendly form and
    // POST /input-submit delivers the typed text through onInputSubmitted
    // exactly once. The token is the only credential: the page is LAN-only
    // and the token is single-use and short-lived.
    @Volatile var inputToken: String? = null
    @Volatile var inputTitle: String = "扫码输入"
    @Volatile var onInputSubmitted: ((String) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        var path = session.uri.trimStart('/').substringBefore('?')
        if (path.isEmpty() || path.endsWith("/")) path += "index.html"
        // Basic path traversal guard.
        if (".." in path) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
        if (path == "bridge-shim.js") {
            val shimBytes = shimJs.toByteArray(Charsets.UTF_8)
            return newFixedLengthResponse(
                Response.Status.OK, "text/javascript",
                ByteArrayInputStream(shimBytes), shimBytes.size.toLong()
            )
        }
        if (path == "input") return inputPage(session)
        if (path == "input-submit") return inputSubmit(session)
        return try {
            val bytes = context.assets.open("www/$path").readBytes()
            if (path == "index.html") {
                val html = String(bytes, Charsets.UTF_8)
                val injected = html.replaceFirst(
                    "</head>",
                    "<style>.window-controls{display:none!important}</style>" +
                        "<script src=\"/bridge-shim.js\"></script></head>"
                )
                newFixedLengthResponse(Response.Status.OK, "text/html", injected)
            } else {
                val mime = getMimeTypeForFile(path)
                newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    // ------------------------------------------------------------------
    // scan-to-type pages (served to the phone browser, not the WebView)
    // ------------------------------------------------------------------

    private fun htmlPage(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", body)

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun inputPage(session: IHTTPSession): Response {
        val token = inputToken
        val given = session.parameters["t"]?.firstOrNull()
        if (token == null || given != token) {
            return htmlPage(
                Response.Status.FORBIDDEN,
                "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                    "<title>链接已失效</title><p style=\"font-family:sans-serif;padding:24px\">" +
                    "这个输入链接已失效，请在电视上重新打开扫码输入。</p>"
            )
        }
        val title = escapeHtml(inputTitle)
        val body = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
body{font-family:sans-serif;margin:0;padding:20px;background:#111;color:#eee}
h1{font-size:18px;margin:0 0 6px}
p{color:#aaa;font-size:13px;margin:0 0 14px}
textarea{width:100%;box-sizing:border-box;font-size:16px;padding:12px;border-radius:10px;border:1px solid #444;background:#1c1c1e;color:#fff}
button{margin-top:14px;width:100%;font-size:16px;padding:13px;border:0;border-radius:10px;background:#e50914;color:#fff}
</style></head><body>
<h1>$title</h1>
<p>在下面输入或粘贴，点发送后会直接填到电视上。</p>
<form method="POST" action="/input-submit">
<input type="hidden" name="t" value="$token">
<textarea name="text" rows="6" autofocus placeholder="在这里输入…"></textarea>
<button type="submit">发送到电视</button>
</form></body></html>"""
        return htmlPage(Response.Status.OK, body)
    }

    private fun inputSubmit(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return htmlPage(Response.Status.METHOD_NOT_ALLOWED, "<p>method not allowed</p>")
        }
        try {
            session.parseBody(HashMap())
        } catch (e: Exception) {
            return htmlPage(Response.Status.BAD_REQUEST, "<p>bad request</p>")
        }
        val token = inputToken
        val given = session.parms["t"]
        if (token == null || given != token) {
            return htmlPage(
                Response.Status.FORBIDDEN,
                "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                    "<p style=\"font-family:sans-serif;padding:24px\">链接已失效，请在电视上重新打开扫码输入。</p>"
            )
        }
        val text = session.parms["text"] ?: ""
        val callback = onInputSubmitted
        // One-shot: disarm before delivering so a resubmit cannot double-send.
        inputToken = null
        onInputSubmitted = null
        callback?.invoke(text)
        return htmlPage(
            Response.Status.OK,
            "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>已发送</title><p style=\"font-family:sans-serif;padding:24px\">✓ 已发送到电视，可以放下手机了。</p>"
        )
    }
}
