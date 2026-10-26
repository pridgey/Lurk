package app.lurk.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {

    // The shape of GET /repos/pridgey/Lurk/releases/tags/latest (trimmed).
    private val json = """
        {
          "tag_name": "latest",
          "name": "Lurk (build 27)",
          "body": "Built from abc. Download the .apk below.\n\nRecent changes:\n- Accounts: keep the name when a sign-in expires\n- Discover: sort menu\n",
          "assets": [
            { "name": "notes.txt", "size": 3, "browser_download_url": "https://github.com/x/notes.txt" },
            {
              "name": "Lurk-27.apk",
              "size": 10351179,
              "digest": "sha256:D62EF6CFA4BE83AB5FD0AC2ECBA3794BDD70AC82172CC634E0BEFF20B5B41F8F",
              "browser_download_url": "https://github.com/pridgey/Lurk/releases/download/latest/Lurk-27.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsBuildApkAndChanges() {
        val r = Updater.parseRelease(JSONObject(json))!!
        assertEquals(27, r.build)
        assertEquals("https://github.com/pridgey/Lurk/releases/download/latest/Lurk-27.apk", r.apkUrl)
        assertEquals(10351179L, r.size)
        assertEquals("d62ef6cfa4be83ab5fd0ac2ecba3794bdd70ac82172cc634e0beff20b5b41f8f", r.sha256)
        assertEquals(listOf("Accounts: keep the name when a sign-in expires", "Discover: sort menu"), r.changes)
    }

    @Test
    fun oldNotesHaveNoChanges() {
        val r = Updater.parseRelease(JSONObject(json.replace("Recent changes:\\n- Accounts: keep the name when a sign-in expires\\n- Discover: sort menu\\n", "")))!!
        assertEquals(emptyList<String>(), r.changes)
    }

    @Test
    fun noApkMeansNoRelease() {
        assertNull(Updater.parseRelease(JSONObject("""{"assets":[{"name":"x.zip","browser_download_url":"https://x"}]}""")))
        assertNull(Updater.parseRelease(JSONObject("{}")))
    }
}
