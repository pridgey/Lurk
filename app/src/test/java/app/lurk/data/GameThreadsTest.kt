package app.lurk.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scoreboard, from titles as r/nba and r/soccer wrote them (October 2026). */
class GameThreadsTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private var next = 0

    /** A post made at [hour]:[minute] New York time on [day] October 2026. */
    private fun post(title: String, day: Int, hour: Int, minute: Int = 0): Post {
        val id = "t3_${next++}"
        val at = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toEpochSecond()
        return Post(
            name = id, id = id, title = title, author = "NBA_MOD", community = "nba", score = 0, likes = null,
            saved = false, numComments = 0, createdUtc = at, threadRef = "/r/nba/comments/$id/", url = "",
            domain = "self.nba", isSelf = true, selftext = "", selftextHtml = null, flair = null, over18 = false,
            spoiler = false, stickied = false, locked = false, media = null, thumbnail = null,
        )
    }

    @Test
    fun nbaGameAndPostGameBecomeOneFinal() {
        val game = post("Game Thread: Los Angeles Lakers (3-1) vs LA Clippers (1-2) Live Score | NBA | Oct 28, 2026", 28, 21)
        val result = post("[Post Game Thread] The Los Angeles Lakers (4-1) defeat the LA Clippers (1-3), 103-87.", 28, 23, 40)
        val days = GameThreads.board(listOf(result, game), zone)

        assertEquals(1, days.size)
        assertEquals(LocalDate.of(2026, 10, 28), days[0].date)
        val g = days[0].games.single()
        assertEquals("Los Angeles Lakers", g.first.name)
        assertEquals("LA Clippers", g.second.name)
        assertEquals(103, g.first.score)
        assertEquals(87, g.second.score)
        assertEquals("4-1", g.first.record)
        assertEquals("1-3", g.second.record)
        assertEquals(0, g.winner)
        assertNull(g.league) // "NBA" and the date aren't a league worth showing
        assertEquals(game, g.gameThread)
        assertEquals(result, g.postGame)
    }

    @Test
    fun winnerListedSecondInTheGameThread() {
        val game = post("Game Thread: Orlando Magic (1-2) vs Toronto Raptors (2-1) Live Score | NBA | Oct 28, 2026", 28, 19)
        val result = post(
            "[Post Game Thread] The Toronto Raptors (3-1) defeat the Orlando Magic (1-3) in a 30 point blowout, behind Scottie Barnes' two way dominance.",
            28, 21, 30,
        )
        val g = GameThreads.board(listOf(game, result), zone).single().games.single()
        assertEquals("Orlando Magic", g.first.name)
        assertEquals(1, g.winner) // the Raptors, without a score in the title
        assertNull(g.first.score)
    }

    @Test
    fun overtimeAndExtraWordsAfterTheScore() {
        val game = post("Game Thread: Brooklyn Nets (0-3) vs Boston Celtics (2-1) Live Score | NBA | Oct 27, 2026", 27, 19)
        val result = post(
            "[Post Game Thread] The Boston Celtics (3-1) defeat the Brooklyn Nets (0-4) in overtime, 125-124, behind Jayson Tatum's 40-point performance and game-winning buzzer-beater",
            27, 22,
        )
        val g = GameThreads.board(listOf(game, result), zone).single().games.single()
        assertEquals(124, g.first.score)
        assertEquals(125, g.second.score)
        assertEquals(1, g.winner)
        assertEquals("OT", g.note)
    }

    @Test
    fun postGameAfterMidnightStaysWithItsGame() {
        val game = post("Game Thread: Golden State Warriors (1-3) vs Minnesota Timberwolves (3-1) Live Score | NBA | Oct 28, 2026", 28, 22)
        val result = post(
            "[Post Game Thread] The Golden State Warriors (2-3) defeat the Minnesota Timberwolves (3-2), 119-114 behind a combined 70 points from Steph Curry and Yaxel Lendeborg.",
            29, 0, 50,
        )
        val day = GameThreads.board(listOf(game, result), zone).single()
        assertEquals(LocalDate.of(2026, 10, 28), day.date)
        assertEquals(119, day.games.single().first.score)
    }

    @Test
    fun postGameWithoutGameThreadStillShows() {
        val result = post(
            "[Post Game Thread] The Philadelphia 76ers (4-1) avoid the narrow trap game loss against the depleted Indiana Pacers (2-3), 114-109 as Embiid, Maxey, Brown and Simons all score 20+ points.",
            28, 22,
        )
        val g = GameThreads.board(listOf(result), zone).single().games.single()
        assertEquals("Philadelphia 76ers", g.first.name)
        assertEquals("Indiana Pacers", g.second.name)
        assertEquals(114, g.first.score)
        assertEquals(109, g.second.score)
        assertEquals(0, g.winner)
        assertNull(g.gameThread)
    }

    @Test
    fun sameTeamsOnDifferentNightsStayApart() {
        val first = post("Game Thread: Portland Trail Blazers (2-0) vs Los Angeles Lakers (3-0) Live Score | NBA | Oct 27, 2026", 27, 22)
        val second = post("Game Thread: Los Angeles Lakers (3-1) vs Portland Trail Blazers (3-0) Live Score | NBA | Oct 29, 2026", 29, 22)
        val result = post("[Post Game Thread] The Portland Trail Blazers (3-0) defeat the Los Angeles Lakers (3-1), 136-129.", 28, 0, 45)
        val days = GameThreads.board(listOf(first, second, result), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 27)), days.map { it.date })
        assertEquals(result, days[1].games.single().postGame)
        assertNull(days[0].games.single().postGame)
    }

    @Test
    fun indexThreadIsAnOtherThread() {
        val index = post("Daily Discussion Thread + Game Thread Index", 28, 9)
        val day = GameThreads.board(listOf(index), zone).single()
        assertTrue(day.games.isEmpty())
        assertEquals(index, day.others.single())
    }

    @Test
    fun soccerScoresSitBetweenTheTeams() {
        val match = post("Match Thread: Sunderland vs Brentford | English Carabao Cup", 28, 14)
        val result = post("Post Match Thread: Sunderland 3-2 Brentford | English Carabao Cup", 28, 16)
        val g = GameThreads.board(listOf(match, result), zone).single().games.single()
        assertEquals(3, g.first.score)
        assertEquals(2, g.second.score)
        assertEquals(0, g.winner)
        assertEquals("English Carabao Cup", g.league)
    }

    @Test
    fun soccerPenaltiesDecideADraw() {
        val match = post("Match Thread: Manchester City vs Brighton & Hove Albion | English Carabao Cup", 28, 14)
        val result = post("Post Match Thread: Manchester City 2-2 (4-3 Pen) Brighton & Hove Albion | English Carabao Cup", 28, 17)
        val g = GameThreads.board(listOf(match, result), zone).single().games.single()
        assertEquals(2, g.first.score)
        assertEquals(2, g.second.score)
        assertEquals(0, g.winner)
        assertEquals("4-3 pens", g.note)
    }

    @Test
    fun soccerPostMatchAloneFindsItsTeams() {
        val result = post("Post-Match Thread: Genoa 2-0 Juventus | Serie A", 28, 16)
        val g = GameThreads.board(listOf(result), zone).single().games.single()
        assertEquals("Genoa", g.first.name)
        assertEquals("Juventus", g.second.name)
        assertEquals(0, g.winner)
        assertEquals("Serie A", g.league)
    }

    @Test
    fun oldStyleTitlesWithAtSign() {
        val game = post("GAME THREAD: New York Knicks (0-0) @ Boston Celtics (0-0) - (October 22, 2024)", 22, 19)
        val g = GameThreads.parseGame(game, zone)!!
        assertEquals("New York Knicks", g.first.name)
        assertEquals("Boston Celtics", g.second.name)
        assertEquals("0-0", g.second.record)
        assertEquals(LocalDate.of(2024, 10, 22), g.day)
    }

    @Test
    fun nonGamePostsAreIgnored() {
        assertTrue(GameThreads.board(listOf(post("LeBron James hits 50k points", 28, 12)), zone).isEmpty())
    }
}
