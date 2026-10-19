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
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Animated GIFs in posts and the image viewer.
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                }
                .crossfade(true)
                .build(),
        )
        val store = AccountStore(applicationContext, multiProfile = WebProfiles.supported)
        setContent {
            LurkTheme { LurkApp(store) }
        }
    }
}
