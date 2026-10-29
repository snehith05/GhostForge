package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Limits
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Every transformation from free text to something the spec format accepts,
 * in one place: the templates and the repair loop share it, so they can never
 * disagree about what "make this a valid identifier" means.
 *
 * All of it is deterministic. `lowercase()` and `titlecase()` use the
 * invariant locale and hex is built by hand rather than via `String.format`,
 * so the same text yields the same output on every device -- a Turkish-locale
 * phone included.
 */
internal object Idents {

    const val IDENTIFIER_MAX = 48
    private const val OPTION_MAX = 32
    private const val APP_ID_MAX = 64
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val COMBINING = Regex("""\p{M}+""")

    /** A dataset or field id matching [Limits.IDENTIFIER], or null if nothing usable survives. */
    fun identifier(raw: String): String? =
        snake(raw, IDENTIFIER_MAX, digitPrefix = "id_")?.takeIf { Limits.IDENTIFIER.matches(it) }

    /** An enum option matching [Limits.ENUM_OPTION], or null if nothing usable survives. */
    fun option(raw: String): String? =
        snake(raw, OPTION_MAX, digitPrefix = "opt_")?.takeIf { Limits.ENUM_OPTION.matches(it) }

    /**
     * An app id matching [Limits.APP_ID]. Never fails: text with no usable
     * Latin letters (Kannada, Hindi, emoji) falls back to a short hash of the
     * raw text, so the same description still always yields the same id.
     */
    fun appId(raw: String): String {
        val slug = fold(raw).lowercase().replace(NON_ALNUM, "-").trim('-')
        val based = if (slug.firstOrNull()?.isDigit() == true) "app-$slug" else slug
        val cut = cutAt(based, APP_ID_MAX, '-').trimEnd('-')
        return if (Limits.APP_ID.matches(cut)) cut else "app-" + sha256Hex(raw).take(8)
    }

    /** [base] itself, or with the smallest `_n` suffix not in [taken], kept within [maxLength]. */
    fun unique(base: String, taken: Set<String>, maxLength: Int = IDENTIFIER_MAX): String {
        if (base !in taken) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take(maxLength - suffix.length).trimEnd('_') + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    /**
     * [text] shortened to at most [max] UTF-16 units. Never splits a surrogate
     * pair -- a lone surrogate cannot be encoded as UTF-8, so the document
     * would no longer round-trip -- and backs off to the last space when one
     * falls in the final third, so names end on a whole word.
     */
    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else cutAt(text, max, ' ').trimEnd()

    /** "litres_requested" -> "Litres requested". */
    fun labelFromId(id: String): String =
        id.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.titlecase() }

    private fun snake(raw: String, max: Int, digitPrefix: String): String? {
        val s = fold(raw).lowercase().replace(NON_ALNUM, "_").trim('_')
        if (s.isEmpty()) return null
        val based = if (s.first().isDigit()) digitPrefix + s else s
        return based.take(max).trimEnd('_').ifEmpty { null }
    }

    /** "Café" -> "Cafe": drops combining marks so accented Latin keeps its letters. */
    private fun fold(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD).replace(COMBINING, "")

    private fun cutAt(text: String, max: Int, boundary: Char): String {
        if (text.length <= max) return text
        var cut = text.take(max)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        val at = cut.lastIndexOf(boundary)
        return if (at >= max * 2 / 3) cut.substring(0, at) else cut
    }

    private fun sha256Hex(raw: String): String {
        val digits = "0123456789abcdef"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                append(digits[(b.toInt() shr 4) and 0xF])
                append(digits[b.toInt() and 0xF])
            }
        }
    }
}
