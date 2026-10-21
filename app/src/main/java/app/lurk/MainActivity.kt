package app.lurk

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.lurk.data.AccountStore
import app.lurk.ui.LurkApp
import app.lurk.ui.LurkTheme
import app.lurk.web.WebProfiles
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class MainActivity : ComponentActivity() {
    private lateinit var store: AccountStore
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Animated GIFs in posts and the image viewer.
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                // Some image hosts (e.g. Lemmy and PieFed CDNs) turn away OkHttp's default user agent.
                .okHttpClient {
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder().header("User-Agent", IMAGE_USER_AGENT).build())
                        }
                        .build()
                }
                .components {
                    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                }
                .crossfade(true)
                .build(),
        )
        store = AccountStore(applicationContext, multiProfile = WebProfiles.supported)
        setContent {
            LurkTheme { LurkApp(store) }
        }
    }

    // Leaving briefly (a link in a browser tab, a share sheet) keeps a PIN account open;
    // being away longer asks for the PIN again.
    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && System.currentTimeMillis() - stoppedAt > RELOCK_AFTER_MS) store.lockAll()
    }

    private companion object {
        const val IMAGE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 Lurk"
        const val RELOCK_AFTER_MS = 2 * 60 * 1000L
    }
}
