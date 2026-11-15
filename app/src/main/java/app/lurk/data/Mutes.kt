package app.lurk.data

/**
 * An account's muted words, as rules: a word or phrase matches whole words, ignoring case
 * ("cat" mutes "Cat pics" but not "category"); a rule written /like this/ is a regular
 * expression. Matched against a post's title, text and flair.
 */
class MuteFilter(rules: List<String>) {

    private val patterns: List<Pair<String, Regex>> = rules.mapNotNull { rule -> compile(rule)?.let { rule to it } }

    val isEmpty: Boolean get() = patterns.isEmpty()

    /** The rule [post] trips, or null if it trips none. */
    fun match(post: Post): String? {
        if (patterns.isEmpty()) return null
        val text = buildString {
            append(post.title)
            append('\n')
            append(post.selftext)
            post.flair?.let { append('\n').append(it) }
        }
        return patterns.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
    }

    companion object {
        val None = MuteFilter(emptyList())

        /** Whether [rule] can be used: not blank, and a valid expression if it's one. */
        fun isValid(rule: String): Boolean = compile(rule) != null

        fun isRegex(rule: String): Boolean {
            val r = rule.trim()
            return r.length > 2 && r.startsWith("/") && r.endsWith("/")
        }

        private fun compile(rule: String): Regex? {
            val r = rule.trim()
            if (r.isEmpty()) return null
            if (isRegex(r)) {
                return runCatching { Regex(r.substring(1, r.length - 1), RegexOption.IGNORE_CASE) }.getOrNull()
            }
            // Whole words: no letter or digit right before or after, so "cat" skips "category".
            val start = if (r.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}])" else ""
            val end = if (r.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}])" else ""
            return Regex(start + Regex.escape(r) + end, RegexOption.IGNORE_CASE)
        }
    }
}
