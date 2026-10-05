package com.cineflow.tv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Network helpers. Mirrors the proxy semantics of Electron's session proxy:
 * 'system' / 'direct' -> no proxy, 'http(s)://host:port' -> HTTP proxy,
 * 'socks5://host:port' -> SOCKS proxy. Anything unparsable -> direct.
 */
object Net {
    fun parseProxy(value: String): Proxy {
        val v = value.trim().lowercase()
        if (v.isEmpty() || v == "system" || v == "direct") return Proxy.NO_PROXY
        val m = Regex("^(https?|socks5)://([^/:]+):(\\d+)").find(v) ?: return Proxy.NO_PROXY
        val (scheme, host, port) = m.destructured
        val type = if (scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return try {
            Proxy(type, InetSocketAddress(host, port.toInt()))
        } catch (e: Exception) {
            Proxy.NO_PROXY
        }
    }

    fun client(proxySetting: String): OkHttpClient = OkHttpClient.Builder()
        .proxy(parseProxy(proxySetting))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
}
