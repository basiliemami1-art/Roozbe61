package com.gozar.app.net

import com.gozar.app.data.SourceSpec
import com.gozar.app.model.ProxyConfig
import com.gozar.app.parser.Codecs
import com.gozar.app.parser.ConfigParser

/**
 * Works out what the user just pasted.
 *
 * One field rather than two. Asking someone to know whether the thing in their
 * clipboard is "a config" or "a subscription" is asking them to know how this
 * app is built — and the answer is obvious from the text itself.
 */
object Paste {

    sealed interface Result {
        /** One or more share links, of any protocol the parser understands. */
        data class Configs(val configs: List<ProxyConfig>) : Result

        /** A subscription endpoint or a Telegram channel to keep polling. */
        data class Source(val spec: SourceSpec) : Result
    }

    /**
     * Config links are tried first on purpose. A bare word would otherwise be
     * read as a Telegram channel, and a `trojan://…` link happens to look like
     * a URL — so the more specific test has to run before the looser one.
     */
    fun read(text: String): Result? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // Someone pasting a whole base64 subscription body should get its
        // contents, not a failure.
        val decoded = if (Codecs.looksBase64(trimmed)) {
            Codecs.decodeBase64(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val configs = ConfigParser.parseMany(decoded)
        if (configs.isNotEmpty()) return Result.Configs(configs)

        return SourceLoader.normalizeUserInput(trimmed)?.let { Result.Source(it) }
    }
}
