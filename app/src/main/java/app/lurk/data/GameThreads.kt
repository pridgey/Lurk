package app.lurk.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One side of a game. [score] and [record] are null when the threads don't say. */
data class GameTeam(val name: String, val record: String?, val score: Int?)

/**
 * A game, put together from its game thread and post-game thread. Either can be missing:
 * a game still going has no post-game thread yet, and some games only get the post-game one.
 */
data class Game(
    /** In the order the game thread lists them (away first, where it says). */
    val first: GameTeam,
    val second: GameTeam,
    /** 0 [first] won, 1 [second] won, null not known or a draw. */
    val winner: Int?,
    /** "OT", "6-5 Pen" and the like. */
    val note: String?,
    /** The competition, when the title names it: "Serie A", "English Carabao Cup". */
    val league: String?,
    val day: LocalDate,
    val gameThread: Post?,
    val postGame: Post?,
) {
    val isFinal: Boolean get() = postGame != null
    val key: String get() = (postGame ?: gameThread)?.name ?: "${first.name}-${second.name}-$day"
}

/** One day's games, and other thread posts from that day (e.g. a daily index thread). */
data class GameDay(val date: LocalDate, val games: List<Game>, val others: List<Post>)

/**
 * Game threads in a sports community, sorted into a scoreboard. Works from titles alone,
 * so it fits any community that names its threads the usual way:
 *
 * - "Game Thread: Lakers (3-1) vs LA Clippers (1-2) Live Score | NBA | Oct 28, 2026"
 * - "GAME THREAD: Knicks (0-0) @ Celtics (0-0) - (October 22, 2024)"
 * - "Match Thread: Liverpool vs Chelsea | English Carabao Cup"
 * - "[Post Game Thread] The Lakers (4-1) defeat the LA Clippers (1-3), 103-87."
 * - "Post Match Thread: Liverpool 1-1 (6-5 Pen) Chelsea | English Carabao Cup"
 */
object GameThreads {

    enum class Kind { Game, PostGame }

    /** What a search for game threads asks for, on sites that search titles (Reddit). */
    const val SEARCH = "title:\"game thread\" OR title:\"match thread\""

    private val marker = Regex("""(?i)\b(post[\s-]?(?:game|match)|game|match)\s+thread\b""")
    private val separator = Regex("""(?i)\s+(?:vs\.?|v\.?|@|at)\s+""")
    private val record = Regex("""\((\d{1,3}-\d{1,3}(?:-\d{1,3})?)\)""")
    private val paren = Regex("""\([^)]*\)""")
    private val score = Regex("""(?<![\d-])(\d{1,3})\s*[-–]\s*(\d{1,3})(?![\d-])""")
    private val penalties = Regex("""(?i)\((\d{1,2})\s*[-–]\s*(\d{1,2})\s*(?:pen|pens|penalties|p|aet)\.?\)""")
    private val overtime = Regex("""(?i)\b(\d?OT|overtime|double overtime|triple overtime|extra time|aet)\b""")
    private val lossVerb = Regex("""(?i)\b(lose|loses|lost|fall|falls|fell|drop|drops|dropped)\b""")
    private val winVerb = Regex(
        """(?i)\b(defeat|defeats|defeated|beat|beats|top|tops|edge|edges|down|downs|rout|routs|""" +
            """win|wins|won|eke|ekes|survive|survives|outlast|outlasts|blow out|blows out|hold off|holds off)\b""",
    )
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val date = Regex("""(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b""")
    private val boring = Regex("""(?i)^(live score|live|nba|nfl|nhl|mlb|wnba)$""")

    /** A post-game thread usually comes this long (or less) after its game thread. */
    private const val MAX_GAME_SECONDS = 18 * 3600L
    /** Late games end after midnight; a post this long after midnight still counts for the day before. */
    private const val DAY_ROLLOVER_SECONDS = 6 * 3600L

    fun kind(title: String): Kind? = marker.find(title)?.let {
        if (it.groupValues[1].lowercase().startsWith("post")) Kind.PostGame else Kind.Game
    }

    fun isGameThread(title: String): Boolean = marker.containsMatchIn(title)

    /** The day a post belongs to, counting the small hours as the night before. */
    fun dayOf(createdUtc: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochSecond(createdUtc - DAY_ROLLOVER_SECONDS).atZone(zone).toLocalDate()

    /** "Today", "Yesterday", or "Mon, Oct 26". */
    fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }

    /**
     * [posts] (any order, others mixed in) as days of games, newest day first. Each day's
     * games are in the order their game threads went up.
     */
    fun board(posts: List<Post>, zone: ZoneId = ZoneId.systemDefault()): List<GameDay> {
        val threads = posts.distinctBy { it.name }.filter { isGameThread(it.title) }.sortedBy { it.createdUtc }
        val games = ArrayList<Game>()
        val others = ArrayList<Post>()

        for (post in threads.filter { kind(it.title) == Kind.Game }) {
            val game = parseGame(post, zone)
            when {
                game == null -> others += post
                // The same game posted twice: keep the first.
                games.any { sameTeams(it, game) && it.day == game.day } -> Unit
                else -> games += game
            }
        }

        for (post in threads.filter { kind(it.title) == Kind.PostGame }) {
            val main = mainPart(post.title)
            // The latest game thread for these teams from before this post that isn't finished.
            val index = games.indices
                .filter { i ->
                    val g = games[i]
                    val opened = g.gameThread?.createdUtc ?: return@filter false
                    g.postGame == null &&
                        opened <= post.createdUtc + 3600 &&
                        post.createdUtc - opened < MAX_GAME_SECONDS &&
                        find(main, g.first.name) != null && find(main, g.second.name) != null
                }
                .maxByOrNull { games[it].gameThread!!.createdUtc }
            if (index != null) {
                games[index] = result(games[index], post)
            } else {
                val teams = teamsOf(main)
                if (teams == null) {
                    others += post
                } else {
                    val blank = Game(
                        first = GameTeam(teams.first, null, null),
                        second = GameTeam(teams.second, null, null),
                        winner = null, note = null, league = leagueOf(post.title),
                        day = dayOf(post.createdUtc, zone), gameThread = null, postGame = null,
                    )
                    games += result(blank, post)
                }
            }
        }

        val days = games.map { it.day } + others.map { dayOf(it.createdUtc, zone) }
        return days.distinct().sortedDescending().map { day ->
            GameDay(
                date = day,
                games = games.filter { it.day == day }
                    .sortedBy { (it.gameThread ?: it.postGame)!!.createdUtc },
                others = others.filter { dayOf(it.createdUtc, zone) == day }.sortedByDescending { it.createdUtc },
            )
        }
    }

    // --- Game threads ---

    /** A game thread's teams, records and date; null if its title doesn't name two teams. */
    fun parseGame(post: Post, zone: ZoneId = ZoneId.systemDefault()): Game? {
        val main = mainPart(post.title)
            // Old r/nba style: "... - (October 22, 2024)"
            .replace(Regex("""\s+-\s+\([^)]*\)\s*$"""), "")
            .replace(Regex("""(?i)\s+live score\s*$"""), "")
        val sides = main.split(separator, limit = 2)
        if (sides.size != 2) return null
        val first = side(sides[0]) ?: return null
        val second = side(sides[1]) ?: return null
        return Game(
            first = first,
            second = second,
            winner = null,
            note = null,
            league = leagueOf(post.title),
            day = dateIn(post.title) ?: dayOf(post.createdUtc + DAY_ROLLOVER_SECONDS, zone),
            gameThread = post,
            postGame = null,
        )
    }

    /** "Los Angeles Lakers (3-1)" as a team. */
    private fun side(full: String): GameTeam? {
        // Anything after " - " is a date or start time: "Toronto Maple Leafs (4-3-0) - 29 Oct 2026".
        val text = full.split(Regex("""\s+[-–]\s+""")).first()
        val rec = record.find(text)
        val name = clean((rec?.let { text.substring(0, it.range.first) } ?: text).replace(paren, " "))
        if (name.isEmpty() || name.length > 50) return null
        return GameTeam(name, rec?.groupValues?.get(1), null)
    }

    // --- Post-game threads ---

    /** [game] with the result [post] gives: scores, winner, records after the game. */
    fun result(game: Game, post: Post): Game {
        val main = mainPart(post.title)
        // Parentheses blanked out (same length, so positions line up) so records don't look like scores.
        val plain = paren.replace(main) { " ".repeat(it.value.length) }
        val a = find(main, game.first.name)
        val b = find(main, game.second.name)

        var firstScore: Int? = null
        var secondScore: Int? = null
        var winner: Int? = null
        val s = score.find(plain)
        if (a != null && b != null) {
            val firstAhead = a.first < b.first
            if (s != null) {
                val x = s.groupValues[1].toInt()
                val y = s.groupValues[2].toInt()
                val between = s.range.first > minOf(a.last, b.last) && s.range.first < maxOf(a.first, b.first)
                // (named first, named second)
                val (named1, named2) = if (between) {
                    // "Arsenal 2-1 Chelsea": each number beside its team.
                    x to y
                } else {
                    // "The Lakers defeat the Clippers, 103-87": the first team named won.
                    val lost = lossVerb.containsMatchIn(plain.substring(0, s.range.first))
                    if (lost) minOf(x, y) to maxOf(x, y) else maxOf(x, y) to minOf(x, y)
                }
                val f = if (firstAhead) named1 else named2
                val g = if (firstAhead) named2 else named1
                firstScore = f
                secondScore = g
                winner = when {
                    f > g -> 0
                    g > f -> 1
                    else -> null
                }
            } else if (winVerb.containsMatchIn(plain) || lossVerb.containsMatchIn(plain)) {
                // No score, but who won: "The Raptors defeat the Magic in a 30 point blowout".
                val firstNamedWon = !lossVerb.containsMatchIn(plain)
                winner = if (firstAhead == firstNamedWon) 0 else 1
            }
        }

        val pens = penalties.find(main)
        if (pens != null && a != null && b != null && winner == null && firstScore != null) {
            val x = pens.groupValues[1].toInt()
            val y = pens.groupValues[2].toInt()
            val firstWins = if (a.first < b.first) x > y else y > x
            if (x != y) winner = if (firstWins) 0 else 1
        }
        val note = when {
            pens != null -> "${pens.groupValues[1]}-${pens.groupValues[2]} pens"
            else -> overtime.find(plain)?.value?.let { if (it.equals("aet", true) || it.equals("extra time", true)) "AET" else "OT" }
        }

        return game.copy(
            first = game.first.copy(score = firstScore, record = a?.let { recordAfter(main, it) } ?: game.first.record),
            second = game.second.copy(score = secondScore, record = b?.let { recordAfter(main, it) } ?: game.second.record),
            winner = winner,
            note = note,
            league = game.league ?: leagueOf(post.title),
            postGame = post,
        )
    }

    /**
     * The two teams a post-game title names, for one without a game thread to match:
     * the names before each record ("The Lakers (4-1) defeat the LA Clippers (1-3)"), or
     * either side of the score ("Sunderland 3-2 Brentford").
     */
    fun teamsOf(main: String): Pair<String, String>? {
        val recs = record.findAll(main).toList()
        if (recs.size >= 2) {
            val first = trailingName(main.substring(0, recs[0].range.first))
            val second = trailingName(main.substring(recs[0].range.last + 1, recs[1].range.first))
            if (first != null && second != null) return first to second
        }
        val plain = paren.replace(main) { " ".repeat(it.value.length) }
        val s = score.find(plain) ?: return null
        val first = trailingName(plain.substring(0, s.range.first)) ?: return null
        val second = leadingName(plain.substring(s.range.last + 1)) ?: return null
        return first to second
    }

    // --- Pieces ---

    /** The part of a title after the "Game Thread" marker and before any " | " sections. */
    private fun mainPart(title: String): String {
        val m = marker.find(title) ?: return title
        return title.substring(m.range.last + 1)
            .trimStart(' ', ':', ']', ')', '-', '–', '|')
            .split(" | ").first()
            .trim()
    }

    /** The first " | " section that isn't a date or filler, e.g. "Serie A". */
    private fun leagueOf(title: String): String? =
        title.split(" | ").drop(1).map { it.trim() }
            .firstOrNull { it.isNotEmpty() && date.find(it) == null && !boring.matches(it) }

    private fun dateIn(title: String): LocalDate? {
        val m = date.find(title) ?: return null
        val month = months.indexOf(m.groupValues[1].lowercase()) + 1
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) }.getOrNull()
    }

    /** Where [team] is named in [text]: its full name, or failing that its last word ("Lakers"). */
    private fun find(text: String, team: String): IntRange? {
        fun at(name: String): IntRange? =
            Regex("""(?i)(?<![\p{L}\d])${Regex.escape(name)}(?![\p{L}\d])""").find(text)?.range
        at(team)?.let { return it }
        val last = team.split(' ').last()
        return if (last.length >= 4 && last != team) at(last) else null
    }

    private fun recordAfter(text: String, at: IntRange): String? =
        Regex("""^\s*\((\d{1,3}-\d{1,3}(?:-\d{1,3})?)\)""").find(text.substring(at.last + 1))?.groupValues?.get(1)

    private fun isNameWord(w: String) = w.isNotEmpty() && (w[0].isUpperCase() || w[0].isDigit() || w == "&")

    /** The run of capitalized words at the end of [text]: "defeat the LA Clippers " -> "LA Clippers". */
    private fun trailingName(text: String): String? {
        val words = text.trim().trimEnd(',', '.', ':').split(Regex("\\s+"))
        val run = words.takeLastWhile(::isNameWord)
        return clean(run.joinToString(" ")).takeIf { it.isNotEmpty() }
    }

    /** The run of capitalized words at the start of [text]: " Brentford in extra time" -> "Brentford". */
    private fun leadingName(text: String): String? {
        val words = text.trim().split(Regex("\\s+"))
        val run = words.takeWhile(::isNameWord).map { it.trimEnd(',', '.', ';', ':') }
        return clean(run.joinToString(" ")).takeIf { it.isNotEmpty() }
    }

    private fun clean(name: String): String =
        name.trim().replace(Regex("\\s+"), " ")
            .removePrefix("The ").removePrefix("the ")
            .trim(' ', ',', '.', ':', '-', '–', '[', ']')

    private fun sameTeams(a: Game, b: Game): Boolean {
        val x = setOf(a.first.name.lowercase(), a.second.name.lowercase())
        val y = setOf(b.first.name.lowercase(), b.second.name.lowercase())
        return x == y
    }
}
