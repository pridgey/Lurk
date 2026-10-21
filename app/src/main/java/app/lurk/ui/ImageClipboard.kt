package app.lurk.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies images to the clipboard. Android clipboards hold images as content links, so the
 * file goes in a private cache folder that the FileProvider (see AndroidManifest.xml) shares
 * with whatever app it's pasted into. The original file is used when Coil has it cached,
 * so GIFs stay animated; otherwise the decoded image is saved as a PNG.
 */
object ImageClipboard {

    /** Throws if the image can't be loaded. */
    suspend fun copy(context: Context, url: String) {
        val app = context.applicationContext
        val loader = app.imageLoader
        val result = loader.execute(ImageRequest.Builder(app).data(url).allowHardware(false).build())
        val success = result as? SuccessResult ?: throw IllegalStateException("Couldn't load the image")

        val file = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "clipboard").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the latest copy is kept
            val cached = success.diskCacheKey?.let { key -> loader.diskCache?.openSnapshot(key) }
            cached?.use { snapshot ->
                val source = snapshot.data.toFile()
                val ext = extensionOf(source)
                if (ext != null) return@withContext source.copyTo(File(dir, "image-${System.currentTimeMillis()}.$ext"))
            }
            val bitmap = (success.drawable as? BitmapDrawable)?.bitmap ?: success.drawable.toBitmap()
            File(dir, "image-${System.currentTimeMillis()}.png").also { out ->
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        // The type is set here rather than looked up, so apps that check it before pasting see an image.
        val mime = "image/" + file.extension.replace("jpg", "jpeg")
        clipboard.setPrimaryClip(ClipData(ClipDescription("Image", arrayOf(mime)), ClipData.Item(uri)))
    }

    /** The file's image type, from its first bytes; null if it isn't a known image. */
    fun extensionOf(file: File): String? {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        return extensionOf(head.copyOf(maxOf(read, 0)))
    }

    fun extensionOf(head: ByteArray): String? {
        fun at(i: Int, vararg bytes: Int) = head.size >= i + bytes.size && bytes.indices.all { head[i + it] == bytes[it].toByte() }
        return when {
            at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> "gif"
            at(0, 0x89, 'P'.code, 'N'.code, 'G'.code) -> "png"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) -> "webp"
            else -> null
        }
    }
}
