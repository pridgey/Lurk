package app.lurk.data

/**
 * A Discover topic. Communities have no categories of their own, so a community is in a
 * topic when one of its [words] is a word of its name or title (or inside a long name, as
 * "linux" in "linux_gaming"), or two of them turn up in its description.
 */
class Topic(
    val key: String,
    val label: String,
    words: String,
    /** Phrases matched whole, like "board games". */
    phrases: List<String> = emptyList(),
    /** Descriptions don't count: place names turn up in too many of them. */
    val namesOnly: Boolean = false,
) {
    val words: List<String> = words.split(' ')
    val phrases: List<String> = phrases
}

object Topics {
    /** Bump when the lists change, so the cached index is tagged again. */
    const val VERSION = 1

    val all = listOf(
        Topic(
            "news", "News & Politics",
            "news worldnews politics political elections election electoralism geopolitics government democrats " +
                "republicans uspolitics antifascism leftism socialism communism anarchism capitalism dystopia " +
                "nottheonion leopardsatemyface",
            listOf("world news"),
        ),
        Topic(
            "tech", "Technology",
            "technology tech programming programmer software hardware linux opensource foss selfhosted selfhosting " +
                "privacy android iphone apple homelab coding developer cybersecurity security infosec computers webdev " +
                "python rust javascript gadgets ai chatgpt firefox piracy degoogle unixporn sysadmin datahoarder " +
                "keyboards mechanicalkeyboards libre raspberrypi buildapc",
            listOf("open source", "self hosted"),
        ),
        Topic(
            "gaming", "Gaming",
            "gaming games gamer videogames pcgaming steam nintendo playstation xbox retrogaming rpg minecraft " +
                "emulation speedrunning gamedev",
            listOf("video games", "retro games"),
        ),
        Topic("tabletop", "Tabletop", "boardgames dnd ttrpg tabletop warhammer mtg chess", listOf("board games", "dungeons and dragons")),
        Topic(
            "ask", "Ask & Discuss",
            "asklemmy ask nostupidquestions questions youshouldknow showerthoughts til todayilearned explainlikeimfive " +
                "eli5 outoftheloop ama unpopularopinion discussion advice casualconversation",
        ),
        Topic("memes", "Memes & Humor", "memes meme humor humour funny jokes shitposting shitpost comics comic 196 greentext copypasta twitter tumblr xkcd"),
        Topic(
            "pics", "Pics & Videos",
            "pics pictures videos mildlyinteresting interestingasfuck interesting mildlyinfuriating dataisbeautiful " +
                "oddlysatisfying coolguides earthporn",
        ),
        Topic("fedi", "Lemmy & Fediverse", "fediverse lemmy mastodon kbin mbin piefed newcommunities activitypub"),
        Topic(
            "places", "Places",
            "europe canada usa unitedstates america uk unitedkingdom britain australia newzealand ireland germany " +
                "deutschland france ukraine india japan brazil mexico netherlands sweden finland norway denmark " +
                "poland italy spain",
            namesOnly = true,
        ),
        Topic("science", "Science", "science physics chemistry biology astronomy space spaceflight math mathematics neuroscience geology"),
        Topic("nature", "Climate & Nature", "climate environment environmentalist solarpunk sustainability ecology nature wildlife conservation green"),
        Topic("screen", "Movies & TV", "movies movie film films television tv anime manga startrek trek starwars cinema documentaries", listOf("star trek", "star wars")),
        Topic("music", "Music", "music metal hiphop jazz guitar punk vinyl synth songs bands podcasts"),
        Topic("books", "Books & Writing", "books book reading literature writing writers poetry scifi fantasy"),
        Topic(
            "sports", "Sports",
            "sports sport football soccer nba nfl hockey nhl baseball mlb running formula1 f1 cricket rugby tennis " +
                "golf mma wrestling",
        ),
        Topic("art", "Art & Photography", "art drawing painting design photography illustration pixelart artists"),
        Topic("food", "Food & Drink", "food cooking recipes baking coffee tea beer wine vegan vegetarian bbq"),
        Topic(
            "diy", "DIY & Garden",
            "diy woodworking crafts crafting knitting sewing lego 3dprinting maker electronics homeimprovement " +
                "gardening houseplants plants",
            listOf("3d printing", "home improvement"),
        ),
        Topic("animals", "Animals", "animals animal cats cat dogs dog pets birds birding aww"),
        Topic("health", "Health & Fitness", "health fitness mentalhealth meditation nutrition adhd autism disability medicine", listOf("mental health")),
        Topic(
            "money", "Money & Work",
            "finance investing personalfinance economics economy crypto bitcoin jobs work antiwork business frugal " +
                "linkedinlunatics",
            listOf("personal finance"),
        ),
        Topic("culture", "History & Ideas", "history philosophy religion atheism language languages linguistics culture"),
        Topic("travel", "Travel & Outdoors", "travel hiking camping outdoors backpacking"),
        Topic("transport", "Cars & Transit", "cars car motorcycles bicycles bikes cycling ev transit trains urbanism fuckcars"),
    )

    fun byKey(key: String): Topic? = all.firstOrNull { it.key == key }

    /** The keys of the topics a community is in. */
    fun of(name: String, title: String?, description: String): Set<String> {
        val lowerName = name.lowercase()
        val nameWords = words(lowerName)
        val titleWords = words(title.orEmpty())
        val descWords = words(description)
        return all.filterTo(LinkedHashSet()) { topic ->
            val strong = topic.words.any { w ->
                " $w " in nameWords || " $w " in titleWords || (w.length >= MIN_INSIDE && w in lowerName)
            } || topic.phrases.any { " $it " in titleWords }
            strong || (!topic.namesOnly && (topic.words + topic.phrases).count { " $it " in descWords } >= 2)
        }.mapTo(LinkedHashSet()) { it.key }
    }

    /** Lowercase words between spaces, with a space at each end, so " word " finds whole words. */
    private fun words(text: String): String =
        " " + text.lowercase().replace(NON_WORD, " ").trim() + " "

    private val NON_WORD = Regex("[^a-z0-9]+")

    /** Shorter words only count whole: "art" shouldn't find "smart". */
    private const val MIN_INSIDE = 5
}
