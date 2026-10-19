package app.lurk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.lurk.data.AccountStore
import app.lurk.ui.LurkApp
import app.lurk.ui.LurkTheme
import app.lurk.web.WebProfiles

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = AccountStore(applicationContext, multiProfile = WebProfiles.supported)
        setContent {
            LurkTheme { LurkApp(store) }
        }
    }
}
