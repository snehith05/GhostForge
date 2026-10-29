package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Limits

/** Which template a description was understood as. Declaration order is the tie-break. */
enum class TemplateId { REQUEST_TRACKER, INCIDENT_LOG, INVENTORY, ROSTER, CHECKLIST, REGISTRY, RECORDS }

/** A list the user spelled out, e.g. "statuses: pending, approved, done". */
enum class ListKind { STATUS, ASSIGNEES, CATEGORIES, SEVERITY, SHIFTS }

/**
 * What the deterministic interpreter understood. Public so the UI can say
 * "understood as a request tracker, because of: request, deliver" -- a
 * rule-based guess should be explainable, not magic.
 *
 * @param matched the keywords that decided [template], in the order they appeared
 * @param lists option lists found in the text, already slugged into enum options
 */
data class Interpretation(
    val template: TemplateId,
    val matched: List<String>,
    val name: String,
    val description: String,
    val lists: Map<ListKind, List<String>>,
)

/**
 * Plain language to [Interpretation] with no model: weighted keywords choose
 * a template, and only lists the user explicitly spelled out ("volunteers:
 * Asha, Ravi") shape it further. Deliberately conservative -- a phrase that
 * merely mentions people does not invent a roster. English-only for now;
 * anything else falls back to [TemplateId.RECORDS], which still works.
 */
internal object Interpreter {

    /** Only this much of a description is read, which bounds the cost of a pasted essay. */
    const val MAX_INPUT_CHARS = 4_000

    private const val MAX_ITEM_WORDS = 3

    fun interpret(raw: String): Interpretation {
        val bounded = Idents.truncate(raw, MAX_INPUT_CHARS)
        val text = clean(bounded)
        val (template, matched) = classify(text)
        return Interpretation(
            template = template,
            matched = matched,
            name = deriveName(text).ifEmpty { Templates.displayName(template) },
            description = text,
            lists = extractLists(bounded),
        )
    }

    /**
     * Whitespace runs (any Unicode space, line breaks included) become one
     * space; other control characters and unpaired surrogates are dropped. A
     * lone surrogate is never meaningful text and cannot be encoded as UTF-8,
     * so letting one through would produce a document that cannot travel.
     */
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        var space = false
        for (k in raw.indices) {
            val c = raw[k]
            when {
                c.isHighSurrogate() && !(k + 1 < raw.length && raw[k + 1].isLowSurrogate()) -> Unit
                c.isLowSurrogate() && !(k > 0 && raw[k - 1].isHighSurrogate()) -> Unit
                c.isWhitespace() -> space = out.isNotEmpty()
                c.isISOControl() -> Unit
                else -> {
                    if (space) out.append(' ')
                    space = false
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    /** Plural folding only -- just enough that "requests" scores like "request". Applied identically to keywords and input. */
    fun stem(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && SIBILANT_PLURALS.any(word::endsWith) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && NOT_PLURAL.none(word::endsWith) -> word.dropLast(1)
        else -> word
    }

    private val SIBILANT_PLURALS = listOf("sses", "ches", "shes", "xes")
    private val NOT_PLURAL = listOf("ss", "us", "is")
    private val TOKEN_SPLIT = Regex("""[^\p{L}\p{N}]+""")

    private val stemmedKeywords: Map<TemplateId, Map<String, Int>> by lazy {
        TemplateId.entries.associateWith { t ->
            buildMap { Templates.keywords(t).forEach { (word, weight) -> merge(stem(word), weight, ::maxOf) } }
        }
    }

    private fun classify(text: String): Pair<TemplateId, List<String>> {
        val tokens = text.lowercase().replace("to-do", "todo")
            .split(TOKEN_SPLIT).filter { it.isNotEmpty() }.map(::stem)
        var best = TemplateId.RECORDS
        var bestScore = 0
        var bestMatched = emptyList<String>()
        for (t in TemplateId.entries) {
            val keywords = stemmedKeywords.getValue(t)
            val matched = tokens.filter { it in keywords }.distinct()
            val score = matched.sumOf { keywords.getValue(it) }
            if (score > bestScore) {
                best = t
                bestScore = score
                bestMatched = matched
            }
        }
        return best to bestMatched
    }

    // --- name -----------------------------------------------------------------

    /** Longest first, so "an app to" is stripped whole rather than as "an" and then "app to". */
    private val FILLERS: List<String> = listOf(
        "please", "kindly", "i want to", "i need to", "i would like to", "we want to", "we need to",
        "we would like to", "help me", "help us", "can you", "could you", "let me", "let us",
        "make", "create", "build", "generate", "set up", "an app to", "an app for", "app to",
        "app for", "a tool to", "a tool for", "a way to", "something to", "to", "keep track of",
        "track", "manage", "monitor", "record", "log", "keep a list of", "keep count of",
        "a list of", "list of", "list", "count", "organise", "organize", "coordinate", "a", "an", "the",
    ).sortedByDescending { it.length }

    private val SMALL_WORDS = setOf("a", "an", "the", "and", "or", "of", "for", "to", "in", "on", "at", "by", "from", "with")

    /** A name ends at the first sentence break, or where a list or aside begins. */
    private val NAME_END = Regex("""[.!?](\s|$)|[:(;]|\s[-–—]\s""")

    /** The first clause, minus "please help me make an app to"-style preamble, in Title Case. Empty if nothing is left. */
    private fun deriveName(text: String): String {
        var s = text.substring(0, NAME_END.find(text)?.range?.first ?: text.length).trim()
        var stripped = true
        while (stripped) {
            stripped = false
            for (f in FILLERS) {
                if (s.regionMatches(0, f, 0, f.length, ignoreCase = true) && (s.length == f.length || s[f.length] == ' ')) {
                    s = s.substring(f.length).trimStart()
                    stripped = true
                    break
                }
            }
        }
        return s.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            if (w != w.lowercase() || (i > 0 && w in SMALL_WORDS)) w else w.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }

    // --- lists ----------------------------------------------------------------

    private val LIST_KEYWORDS: Map<String, ListKind> = buildMap {
        fun kind(k: ListKind, vararg words: String) = words.forEach { put(it, k) }
        kind(ListKind.STATUS, "status", "statuses", "stage", "stages")
        kind(
            ListKind.ASSIGNEES, "volunteer", "volunteers", "member", "members", "team", "teams", "staff",
            "assignee", "assignees", "worker", "workers", "helper", "helpers",
        )
        kind(ListKind.CATEGORIES, "category", "categories", "type", "types", "group", "groups", "kind", "kinds")
        kind(ListKind.SEVERITY, "severity", "severities", "priority", "priorities", "urgency", "level", "levels")
        kind(ListKind.SHIFTS, "shift", "shifts", "slot", "slots")
    }

    /**
     * "statuses: a, b" or "statuses - a, b". The keyword must sit right
     * before the delimiter, so "volunteers to shifts: morning" is a shift
     * list. The list ends at a sentence break or just before the next
     * "keyword:", so two lists on one line stay two lists.
     */
    private val COLON_LIST = Regex(
        """(\p{L}+)\s*(?::|\s[-–—]\s)\s*(.+?)(?=\s+\p{L}+\s*(?::|\s[-–—]\s)|[.;:()]|\z)""",
    )

    /** "priority (low, medium, high)". */
    private val PAREN_LIST = Regex("""(\p{L}+)\s*\(([^()]*)\)""")

    private val ITEM_SPLIT = Regex("""\s*(?:,|/|&|\band\b|\bor\b)\s*""", RegexOption.IGNORE_CASE)
    private val LINE_BREAKS = Regex("""[\r\n]+""")

    private fun extractLists(raw: String): Map<ListKind, List<String>> {
        // Line breaks end a list, so they become semicolons before whitespace is collapsed.
        val text = clean(raw.replace(LINE_BREAKS, "; "))
        val found = (COLON_LIST.findAll(text).map { it to false } + PAREN_LIST.findAll(text).map { it to true })
            .sortedBy { it.first.range.first }
        val lists = linkedMapOf<ListKind, List<String>>()
        for ((match, parenthesised) in found) {
            val kind = LIST_KEYWORDS[match.groupValues[1].lowercase()] ?: continue
            if (kind in lists) continue
            val items = match.groupValues[2].split(ITEM_SPLIT)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.split(' ').size <= MAX_ITEM_WORDS }
                .mapNotNull(Idents::option)
                .distinct()
                .take(Limits.MAX_ENUM_OPTIONS)
            // A parenthesis needs a real list: "volunteers (about 20)" is a remark, not a roster.
            if (items.isEmpty() || (parenthesised && items.size < 2)) continue
            lists[kind] = items
        }
        return lists
    }
}
