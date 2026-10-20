package app.lurk.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccountPinTest {

    private fun store(): AccountStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().clear().commit()
        return AccountStore(context, multiProfile = true)
    }

    @Test
    fun pinGuardsSwitchingAndRelocks() {
        val s = store()
        val main = s.activeId
        val private = s.add().id
        s.setPin(private, "4821")
        assertTrue(s.find(private)!!.pinHash != "4821") // stored hashed

        s.setActive(main)
        s.setActive(private)
        assertTrue(s.needsPin(private))
        assertEquals(main, s.elsewhere())
        assertFalse(s.unlock(private, "0000"))
        assertTrue(s.unlock(private, "4821"))
        assertFalse(s.needsPin(private))

        s.lockAll()
        assertTrue(s.needsPin(private))
        s.unlock(private, "4821")
        s.setActive(main)
        s.setActive(private)
        assertTrue(s.needsPin(private)) // switching away locked it again
    }

    @Test
    fun pinSurvivesRestartAndForgetRemoves() {
        val s = store()
        val private = s.add().id
        s.setPin(private, "123456")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val again = AccountStore(context, multiProfile = true)
        assertTrue(again.find(private)!!.locked)
        assertTrue(again.unlock(private, "123456"))
        again.forget(private)
        assertEquals(null, again.find(private))
    }
}
