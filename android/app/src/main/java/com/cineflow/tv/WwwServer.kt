package com.cineflow.tv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Serves the Vite-built web app (assets/www, staged by CI from dist/) over
 * http://127.0.0.1:<port>/. Serving over HTTP instead of file:// avoids
 * module/CORS quirks in WebView. The bridge shim + a CSS rule hiding the
 * desktop window controls are injected into index.html before any page script.
 */
class WwwServer(private val context: Context, private val shimJs: String) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        var path = session.uri.trimStart('/').substringBefore('?')
        if (path.isEmpty() || path.endsWith("/")) path += "index.html"
        // Basic path traversal guard.
        if (".." in path) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
        return try {
            val bytes = context.assets.open("www/$path").readBytes()
            if (path == "index.html") {
                val html = String(bytes, Charsets.UTF_8)
                // NOTE: bridge-shim.js must not contain the literal "</script".
                val injected = html.replaceFirst(
                    "</head>",
                    "<style>.window-controls{display:none!important}</style><script>$shimJs</script></head>"
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
}
