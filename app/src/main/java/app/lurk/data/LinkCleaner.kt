package app.lurk.data

/**
 * Takes tracking parameters off links: campaign tags (utm_*), ad click ids (fbclid,
 * gclid, ...), mail and social share ids. Everything else, including the fragment,
 * stays as it was, so links keep working.
 */
object LinkCleaner {

    /** Dropped on every site. */
    private val anywhere = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "li_fat_id", "mc_cid", "mc_eid", "igshid", "igsh", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp",
        "hsctatracking", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "rb_clickid",
        "s_cid", "ref_src", "ref_url", "wt_mc", "wt.mc_id", "_openstat", "spm", "scm", "trk", "trkcampaign",
        "sc_campaign", "sc_channel", "sc_content", "sc_medium", "sc_outcome", "sc_geo", "sc_country",
        "cmpid", "cvid", "ocid", "ncid", "sr_share", "at_medium", "at_campaign", "at_custom1", "at_custom2",
        "share_id", "rdt_cid", "epik", "_branch_match_id", "_branch_referrer",
    )

    /** Dropped when they start with these. */
    private val prefixes = listOf("utm_", "pk_", "mtm_", "hsa_", "ga_", "itm_", "stm_")

    /** Dropped only on these sites, where they're share trackers rather than settings. */
    private val bySite = mapOf(
        "youtube.com" to setOf("si", "feature", "pp"),
        "youtu.be" to setOf("si", "feature"),
        "music.youtube.com" to setOf("si", "feature"),
        "open.spotify.com" to setOf("si"),
        "twitter.com" to setOf("s", "t"),
        "x.com" to setOf("s", "t"),
        "instagram.com" to setOf("igsh"),
        "tiktok.com" to setOf("is_from_webapp", "sender_device", "web_id", "_r", "_t"),
        "reddit.com" to setOf("share_id", "rdt"),
        "amazon.com" to setOf("ref", "pd_rd_r", "pd_rd_w", "pd_rd_wg", "pf_rd_p", "pf_rd_r", "pd_rd_i", "crid", "sprefix", "dib", "dib_tag"),
        "linkedin.com" to setOf("trackingid", "lipi", "refid", "trk"),
        "nytimes.com" to setOf("smid", "smtyp"),
        "bloomberg.com" to setOf("leadsource", "embedded-checkout", "srnd"),
    )

    fun clean(url: String): String {
        val query = url.indexOf('?')
        if (query < 0) return url
        val hash = url.indexOf('#', query).let { if (it < 0) url.length else it }
        val base = url.substring(0, query)
        val fragment = url.substring(hash)
        val params = url.substring(query + 1, hash).split('&').filter { it.isNotEmpty() }
        val site = siteRules(host(base))
        val kept = params.filterNot { param ->
            val key = decode(param.substringBefore('=')).lowercase()
            key in anywhere || prefixes.any { key.startsWith(it) } || key in site
        }
        if (kept.size == params.size) return url
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    /** The rules for [host] and the sites it's under (www.youtube.com, m.youtube.com, ...). */
    private fun siteRules(host: String): Set<String> {
        val out = HashSet<String>()
        bySite.forEach { (site, keys) -> if (host == site || host.endsWith(".$site")) out += keys }
        // Amazon's country sites: amazon.co.uk, amazon.de, ...
        if (Regex("""(^|\.)amazon\.[a-z.]{2,6}$""").containsMatchIn(host)) out += bySite.getValue("amazon.com")
        return out
    }

    private fun host(base: String): String =
        base.substringAfter("://", "").substringBefore('/').substringAfter('@').substringBefore(':').lowercase()

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
