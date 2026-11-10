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

/** Remembering posts seen in feeds, and the setting that hides them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SeenPostsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences("seen_posts", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun seenPostsLastAcrossRestarts() {
        val first = SeenPosts(context, "a")
        first.mark(listOf("t3_one", "t3_two"))
        first.flush()

        val again = SeenPosts(context, "a")
        assertTrue(again.wasSeen("t3_one"))
        assertTrue(again.wasSeen("t3_two"))
        assertFalse(again.wasSeen("t3_three"))
    }

    @Test
    fun accountsKeepTheirOwn() {
        SeenPosts(context, "a").apply { mark(listOf("t3_one")); flush() }
        assertFalse(SeenPosts(context, "b").wasSeen("t3_one"))
    }

    @Test
    fun hideSeenSettingIsSavedPerAccount() {
        val store = AccountStore(context, multiProfile = true)
        val id = store.activeId
        store.update(id) { it.copy(hideSeen = true) }
        val reopened = AccountStore(context, multiProfile = true)
        assertTrue(reopened.find(id)!!.hideSeen)
    }
}
