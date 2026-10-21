package app.lurk.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import android.os.Looper
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageClipboardTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun recognizesImageTypes() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals("gif", ImageClipboard.extensionOf("GIF89a".toByteArray()))
        assertEquals("png", ImageClipboard.extensionOf(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A)))
        assertEquals("jpg", ImageClipboard.extensionOf(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("webp", ImageClipboard.extensionOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(ImageClipboard.extensionOf("<html>".toByteArray()))
        assertNull(ImageClipboard.extensionOf(ByteArray(0)))
    }

    @Test
    fun copiesAnImageAsAContentLink() {
        val source = File(context.cacheDir, "source.png")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            .let { bmp -> source.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        // The image loader finishes on the main thread, so step it until the copy is done.
        val job = MainScope().launch { ImageClipboard.copy(context, source.toURI().toString()) }
        val deadline = System.currentTimeMillis() + 20_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("copy didn't finish", job.isCompleted)

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        val uri = clip.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertTrue(clip.description.hasMimeType("image/png"))
        val copied = File(context.cacheDir, "clipboard").listFiles()!!.single()
        assertEquals("png", ImageClipboard.extensionOf(copied))
    }
}
