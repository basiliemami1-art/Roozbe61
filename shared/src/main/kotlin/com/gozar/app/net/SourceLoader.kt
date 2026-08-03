package com.gozar.app.net

import com.gozar.app.data.SourceSpec
import com.gozar.app.data.SubscriptionInfo
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

    /** Configs, plus whatever the panel said about the subscription itself. */
    data class Result(val configs: List<ProxyConfig>, val info: SubscriptionInfo?)

    suspend fun fetch(url: String): List<ProxyConfig> = load(url).configs

    suspend fun load(url: String): Result = withContext(Dispatchers.IO) {
        val (body, info) = download(resolveUrl(url))
        val text = if (Codecs.looksBase64(body)) Codecs.decodeBase64(body) ?: body else body
        Result(ConfigParser.parseMany(text), info)
    }

    private fun download(url: String): Pair<String, SubscriptionInfo?> {
        Http.client.newCall(Http.request(url)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            // Header names are case-insensitive in okhttp, which matters here:
            // panels spell this one every way imaginable.
            val info = SubscriptionInfo.parse(response.header(SubscriptionInfo.HEADER))
            return response.body?.string().orEmpty() to info
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
