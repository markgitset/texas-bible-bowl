package net.markdrew.biblebowl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import net.markdrew.biblebowl.client.TbbApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.app = applicationContext
        enableEdgeToEdge()
        setContent {
            // BuildConfig bakes the backend URL at build time (the live Fly backend by default,
            // `-Ptbb.backendUrl=...` to override); :client can't see it, so pass it explicitly.
            App(api = remember { TbbApi(BuildConfig.BACKEND_URL) })
        }
    }
}
