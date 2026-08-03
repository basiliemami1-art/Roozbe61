package com.gozar.app.net

import com.gozar.app.data.SourceSpec
import com.gozar.app.model.ProxyConfig
import com.gozar.app.parser.Codecs
import com.gozar.app.parser.ConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns one source — a subscription endpoint or a Telegram channel — into
 * parsed configs. Everything platform-specific (where the list of sources
 * lives, where the results are stored) stays with the caller.
 */
object SourceLoader {

    /** Resolves the stored form of a source to a fetchable URL. */
    fun resolveUrl(url: String): String = when {
        url.startsWith("tg:") -> "https://t.me/s/${url.removePrefix("tg:")}"
        else -> url
    }

    suspend fun fetch(url: String): List<ProxyConfig> = withContext(Dispatchers.IO) {
        val body = download(resolveUrl(url))
        val text = if (Codecs.looksBase64(body)) Codecs.decodeBase64(body) ?: body else body
        ConfigParser.parseMany(text)
    }

    private fun download(url: String): String {
        Http.client.newCall(Http.request(url)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    /**
     * Normalises whatever a user pasted into the "add source" field. Accepts a
     * full URL, `t.me/foo`, `@foo`, or a bare channel name.
     */
    fun normalizeUserInput(input: String): SourceSpec? {
        val value = input.trim()
        if (value.isEmpty()) return null
        Regex("""(?:https?://)?t\.me/(?:s/)?([A-Za-z0-9_]{4,})""").find(value)?.let { match ->
            val channel = match.groupValues[1]
            return SourceSpec("@$channel", "tg:$channel", SourceSpec.Kind.TELEGRAM)
        }
        if (value.startsWith("@")) {
            val channel = value.removePrefix("@")
            if (channel.matches(Regex("[A-Za-z0-9_]{4,}"))) {
                return SourceSpec("@$channel", "tg:$channel", SourceSpec.Kind.TELEGRAM)
            }
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val name = value.substringAfterLast('/').ifBlank { value }.take(40)
            return SourceSpec(name, value, SourceSpec.Kind.SUBSCRIPTION)
        }
        if (value.matches(Regex("[A-Za-z0-9_]{4,}"))) {
            return SourceSpec("@$value", "tg:$value", SourceSpec.Kind.TELEGRAM)
        }
        return null
    }
}
