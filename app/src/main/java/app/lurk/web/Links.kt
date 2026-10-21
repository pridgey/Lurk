package app.lurk.web

import android.net.Uri

/** URL rules every site shares: link schemes, and which links are images or videos. */
object Links {

    private val handOffSchemes = setOf("mailto", "tel", "sms", "geo", "market")
    private val inPageSchemes = setOf("about", "data", "blob", "javascript")

    /** What to do with a link that isn't http(s), or null for http(s) links. */
    fun schemeDecision(uri: Uri): Decision? {
        val scheme = uri.scheme?.lowercase() ?: return Decision.Stay
        if (scheme == "http" || scheme == "https") return null
        return when (scheme) {
            in handOffSchemes -> Decision.External
            in inPageSchemes -> Decision.Stay
            else -> Decision.Block
        }
    }

    private val imageHosts = setOf("i.redd.it", "preview.redd.it", "external-preview.redd.it", "i.imgur.com", "media.giphy.com")
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")

    /** Direct links to an image file on a known image host, which open in the image viewer. */
    fun isImage(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: return false
        return host in imageHosts && imageExtensions.any { path.endsWith(it) }
    }

    /** Any http(s) link to an image file, wherever it's hosted. */
    fun isImageFile(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val path = uri.path?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && imageExtensions.any { path.endsWith(it) }
    }

    private val videoFileExtensions = listOf(".mp4", ".webm", ".m4v", ".mov")

    /**
     * A link straight to a video file Lurk's own player can play, or null.
     * Imgur's .gifv pages have an .mp4 twin. Returns the file URL and whether it's GIF-like.
     */
    fun directVideo(uri: Uri): Pair<String, Boolean>? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path?.lowercase() ?: return null
        if (host.endsWith("imgur.com") && path.endsWith(".gifv")) {
            return uri.toString().replaceFirst(Regex("\\.gifv", RegexOption.IGNORE_CASE), ".mp4") to true
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && videoFileExtensions.any { path.endsWith(it) }) {
            return uri.toString() to false
        }
        return null
    }

    private val videoSites = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "twitch.tv", "streamable.com", "tiktok.com",
        "dailymotion.com", "dai.ly", "rumble.com", "kick.com", "bilibili.com", "odysee.com",
    )

    /** A page on a video site, shown in the video sheet rather than a Custom Tab. */
    fun isVideoPage(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (videoSites.none { host == it || host.endsWith(".$it") }) return false
        // Site home pages and channel listings aren't a single video.
        return (uri.path ?: "/").trim('/').isNotEmpty()
    }
}
