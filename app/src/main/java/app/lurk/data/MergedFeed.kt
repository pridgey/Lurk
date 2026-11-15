package app.lurk.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * One feed read from several communities at once (copies of one community on different
 * instances). Each load reads a page from every copy that has more, puts them in the
 * feed's order, and leaves out posts already shown from another copy: the same link,
 * or the same title by the same person.
 */
class MergedFeed(
    private val members: List<String>,
    private val fetch: suspend (community: String, sort: Sort?, cursor: String?, refresh: Boolean) -> FeedPage,
) {
    /** Posts shown so far this time round, by [dupeKey]. */
    private val shown = HashSet<String>()

    suspend fun page(sort: Sort?, cursor: String?, refresh: Boolean): FeedPage {
        if (cursor == null) shown.clear()
        val todo: Map<String, String?> = if (cursor == null) members.associateWith { null } else decode(cursor)
        if (todo.isEmpty()) return FeedPage(emptyList(), null)
        val results = coroutineScope {
            todo.map { (member, next) ->
                async {
                    member to try {
                        Result.success(fetch(member, sort, next, refresh && next == null))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val pages = results.mapNotNull { (_, r) -> r.getOrNull() }
        // Every copy failing is a failure; some failing just leaves them out.
        if (pages.isEmpty()) throw results.first().second.exceptionOrNull() ?: ServiceException("Couldn't load posts")
        val merged = merge(pages.map { it.posts }, sort?.key).filter { shown.add(dupeKey(it)) }
        val after = results.mapNotNull { (member, r) -> r.getOrNull()?.after?.let { member to it } }.toMap()
        return FeedPage(merged, if (after.isEmpty()) null else encode(after), pages.firstNotNullOfOrNull { it.fetchedAt }, pages.firstNotNullOfOrNull { it.notice })
    }

    companion object {
        private const val PREFIX = "merged:"

        /**
         * Several feeds' pages as one list in [sortKey]'s order: newest first for New, by
         * score for the Top sorts, by comments for Most comments. Ranked sorts (Hot,
         * Active, ...) take turns, so each copy's own ranking holds. Duplicates are left
         * out, keeping the first.
         */
        fun merge(pages: List<List<Post>>, sortKey: String?): List<Post> {
            val all: List<Post> = when {
                sortKey.equals("New", ignoreCase = true) -> pages.flatten().sortedByDescending { it.createdUtc }
                sortKey.equals("Old", ignoreCase = true) -> pages.flatten().sortedBy { it.createdUtc }
                sortKey?.startsWith("Top", ignoreCase = true) == true -> pages.flatten().sortedByDescending { it.score }
                sortKey.equals("MostComments", ignoreCase = true) -> pages.flatten().sortedByDescending { it.numComments }
                else -> takeTurns(pages)
            }
            val keys = HashSet<String>()
            return all.filter { keys.add(dupeKey(it)) }
        }

        private fun takeTurns(pages: List<List<Post>>): List<Post> {
            val out = ArrayList<Post>(pages.sumOf { it.size })
            val longest = pages.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) pages.forEach { page -> page.getOrNull(i)?.let(out::add) }
            return out
        }

        /** What makes two posts the same post: the page they link to, or their title and author. */
        fun dupeKey(p: Post): String {
            if (!p.isSelf && p.url.isNotBlank()) {
                val link = LinkCleaner.clean(p.url).lowercase()
                    .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                return "link:$link"
            }
            return "text:" + p.title.trim().lowercase() + "|" + p.author.substringBefore('@').lowercase()
        }

        private fun encode(after: Map<String, String>): String = PREFIX + JSONObject(after as Map<*, *>).toString()

        private fun decode(cursor: String): Map<String, String?> {
            val o = runCatching { JSONObject(cursor.removePrefix(PREFIX)) }.getOrNull() ?: return emptyMap()
            return o.keys().asSequence().associateWith { o.optString(it) }
        }
    }
}
