package com.cineflow.tv

import org.json.JSONArray
import org.json.JSONObject

/** Error thrown across the JS bridge; the shim surfaces code/message to the page. */
class BridgeError(val code: String, message: String) : Exception(message)

/** JSONObject helpers to keep the port terse. */
fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        when (v) {
            null -> o.put(k, JSONObject.NULL)
            else -> o.put(k, v)
        }
    }
    return o
}

fun JSONArray.take(n: Int): List<JSONObject> {
    val out = ArrayList<JSONObject>(minOf(n, length()))
    for (i in 0 until minOf(n, length())) {
        optJSONObject(i)?.let { out.add(it) }
    }
    return out
}

fun JSONArray.toObjectList(): List<JSONObject> = take(length())

fun List<JSONObject>.toJsonArray(): JSONArray {
    val a = JSONArray()
    for (o in this) a.put(o)
    return a
}
