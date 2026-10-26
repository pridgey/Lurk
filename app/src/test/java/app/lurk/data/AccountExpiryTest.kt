package app.lurk.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccountExpiryTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun store(): AccountStore {
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().clear().commit()
        return AccountStore(context, multiProfile = true)
    }

    @Test
    fun expiryKeepsNameAndCommunities() {
        val s = store()
        val id = s.activeId
        s.update(id) { it.copy(username = "alice", knownAs = "alice", communities = listOf("pics")) }
        s.expire(id)

        val a = s.find(id)!!
        assertFalse(a.signedIn)
        assertTrue(a.expired)
        assertEquals("u/alice", a.label)
        assertEquals(listOf("pics"), a.communities)

        // Survives a restart.
        assertTrue(AccountStore(context, multiProfile = true).find(id)!!.expired)
    }

    @Test
    fun neverSignedInIsNotExpired() {
        val a = store().let { it.find(it.activeId)!! }
        assertFalse(a.expired)
        assertNull(a.label)
    }

    @Test
    fun lemmyExpiryDropsToken() {
        val s = store()
        val id = s.add(ServiceKind.Lemmy) { it.copy(username = "bob", instance = "lemmy.world", token = "jwt") }.id
        s.expire(id)
        val a = s.find(id)!!
        assertNull(a.token)
        assertEquals("bob@lemmy.world", a.label)
    }

    @Test
    fun absorbMovesSettingsAndRemovesOldCopy() {
        val s = store()
        val old = s.activeId
        s.update(old) { it.copy(knownAs = "alice", showNsfw = true) }
        s.setPin(old, "4821")
        val fresh = s.add().id
        s.setActive(fresh)
        s.update(fresh) { it.copy(username = "alice", knownAs = "alice") }

        s.absorb(from = old, into = fresh)

        assertNull(s.find(old))
        val a = s.find(fresh)!!
        assertTrue(a.showNsfw)
        assertTrue(a.locked)
        assertFalse(s.needsPin(fresh)) // just signed in with the password
    }
}
