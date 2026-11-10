package app.lurk.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Remembering posts seen in feeds, and the ones hidden. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SeenPostsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences("seen_posts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun seenAndHiddenLastAcrossRestarts() {
        val first = SeenPosts(context, "a")
        first.mark(listOf("t3_one", "t3_two"))
        first.hide("t3_three")
        first.flush()

        val again = SeenPosts(context, "a")
        assertTrue(again.wasSeen("t3_one"))
        assertTrue(again.wasSeen("t3_two"))
        assertTrue(again.isHidden("t3_three"))
        assertFalse(again.wasSeen("t3_four"))
    }

    @Test
    fun accountsKeepTheirOwn() {
        SeenPosts(context, "a").apply { mark(listOf("t3_one")); hide("t3_two"); flush() }
        val other = SeenPosts(context, "b")
        assertFalse(other.wasSeen("t3_one"))
        assertFalse(other.isHidden("t3_two"))
    }

    @Test
    fun unhideBringsItBack() {
        val seen = SeenPosts(context, "a")
        seen.hide("t3_one")
        seen.unhide("t3_one")
        assertFalse(seen.isHidden("t3_one"))
        assertFalse(SeenPosts(context, "a").isHidden("t3_one"))
    }
}
