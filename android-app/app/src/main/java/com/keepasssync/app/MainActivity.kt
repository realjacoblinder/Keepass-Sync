package com.keepasssync.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.keepasssync.app.ui.screens.ServerSetupScreen
import com.keepasssync.app.ui.screens.SyncScreen
import com.keepasssync.app.ui.theme.KeePassSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeePassSyncTheme {
                KeePassSyncApp()
            }
        }
    }
}

@Composable
fun KeePassSyncApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("keepass_sync", Context.MODE_PRIVATE)
    }

    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var screen by remember { mutableStateOf(if (serverUrl.isNotBlank()) "sync" else "setup") }

    when (screen) {
        "setup" -> ServerSetupScreen(
            initialUrl = serverUrl,
            onConnect = { url ->
                serverUrl = url
                prefs.edit().putString("server_url", url).apply()
                screen = "sync"
            }
        )
        "sync" -> SyncScreen(
            serverUrl = serverUrl,
            onChangeServer = {
                screen = "setup"
            }
        )
    }
}
