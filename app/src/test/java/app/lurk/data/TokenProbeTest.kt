package app.lurk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading a pasted Reddit login cookie for the sign-in test. */
class TokenProbeTest {

    @Test
    fun takesTheBareValue() {
        assertEquals("abc.123", TokenProbe.parseCookie("  abc.123 "))
    }

    @Test
    fun takesItFromACookieLine() {
        assertEquals("abc.123", TokenProbe.parseCookie("reddit_session=abc.123"))
        assertEquals("abc.123", TokenProbe.parseCookie("loid=x; reddit_session=abc.123; token_v2=y;"))
    }

    @Test
    fun rejectsOtherText() {
        assertNull(TokenProbe.parseCookie(""))
        assertNull(TokenProbe.parseCookie("token_v2=y"))
        assertNull(TokenProbe.parseCookie("not a cookie"))
    }
}
