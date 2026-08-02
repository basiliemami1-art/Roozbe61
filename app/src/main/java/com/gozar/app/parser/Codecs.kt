package com.gozar.app.parser

import android.util.Base64
import java.net.URLDecoder

/**
 * Base64 in the wild is sloppy: aggregators mix URL-safe and standard alphabets,
 * strip padding, and wrap lines. Everything here is deliberately forgiving.
 */
object Codecs {

    fun decodeBase64(input: String): String? {
        val cleaned = input.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return null
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            1 -> return null // never valid
            else -> cleaned
        }
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun encodeBase64(input: String): String =
        Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * True when the text looks like one big base64 blob rather than a list of URIs.
     * Checked against a prefix so multi-megabyte subscriptions stay cheap.
     */
    fun looksBase64(text: String): Boolean {
        val t = text.trim()
        if (t.length < 24) return false
        if (t.contains("://")) return false
        val sample = t.take(1024).filterNot { it == '\n' || it == '\r' || it == ' ' }
        if (sample.isEmpty()) return false
        return sample.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
    }

    fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Throwable) {
        value
    }

    /** HTML entities that show up in Telegram's web preview markup. */
    fun unescapeHtml(input: String): String = input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}

/**
 * A tolerant stand-in for [java.net.URI], which rejects many of the characters
 * that appear unencoded in real-world config links.
 */
class LooseUri private constructor(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String?,
) {
    companion object {
        fun parse(raw: String): LooseUri? {
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = raw.substring(0, schemeEnd).lowercase()
            var rest = raw.substring(schemeEnd + 3)

            var fragment: String? = null
            val hashIndex = rest.indexOf('#')
            if (hashIndex >= 0) {
                fragment = Codecs.urlDecode(rest.substring(hashIndex + 1))
                rest = rest.substring(0, hashIndex)
            }

            var query = emptyMap<String, String>()
            val queryIndex = rest.indexOf('?')
            if (queryIndex >= 0) {
                query = parseQuery(rest.substring(queryIndex + 1))
                rest = rest.substring(0, queryIndex)
            }

            // Drop any trailing path segment; proxy links never carry a meaningful one.
            val slashIndex = rest.indexOf('/')
            if (slashIndex >= 0) rest = rest.substring(0, slashIndex)

            var userInfo: String? = null
            val atIndex = rest.lastIndexOf('@')
            if (atIndex >= 0) {
                userInfo = rest.substring(0, atIndex)
                rest = rest.substring(atIndex + 1)
            }

            val (host, port) = splitHostPort(rest) ?: return null
            if (host.isEmpty()) return null
            return LooseUri(scheme, userInfo, host, port, query, fragment)
        }

        /** Handles `host:port`, bare `host`, and bracketed IPv6 `[::1]:443`. */
        fun splitHostPort(input: String): Pair<String, Int>? {
            val value = input.trim()
            if (value.isEmpty()) return null
            if (value.startsWith("[")) {
                val close = value.indexOf(']')
                if (close < 0) return null
                val host = value.substring(1, close)
                val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
                return host to port
            }
            val colon = value.lastIndexOf(':')
            if (colon < 0) return value to 443
            val port = value.substring(colon + 1).toIntOrNull() ?: return value to 443
            return value.substring(0, colon) to port
        }

        private fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    out[Codecs.urlDecode(pair).lowercase()] = ""
                } else {
                    val key = Codecs.urlDecode(pair.substring(0, eq)).lowercase()
                    out[key] = Codecs.urlDecode(pair.substring(eq + 1))
                }
            }
            return out
        }
    }

    operator fun get(key: String): String? = query[key]?.takeIf { it.isNotEmpty() }

    fun first(vararg keys: String): String? {
        for (k in keys) get(k)?.let { return it }
        return null
    }
}
