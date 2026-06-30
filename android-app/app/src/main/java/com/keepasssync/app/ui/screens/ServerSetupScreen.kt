package com.keepasssync.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keepasssync.app.network.SyncApiClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupScreen(
    initialUrl: String,
    onConnect: (String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var isChecking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        var trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            error = "Please enter a server URL"
            return
        }
        // Auto-prepend http:// if no scheme provided
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        url = trimmed

        scope.launch {
            isChecking = true
            error = null
            try {
                val reachable = SyncApiClient.checkServer(trimmed)
                if (reachable) {
                    onConnect(trimmed)
                } else {
                    error = "Could not reach server at $trimmed"
                }
            } catch (e: Exception) {
                error = "Connection failed: ${e.message}"
            } finally {
                isChecking = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KeePass Sync") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Connect to Server",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Enter the URL where your KeePass Sync server is running",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.100:3000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                isError = error != null,
                supportingText = error?.let { msg -> { Text(msg) } }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { connect() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isChecking && url.isNotBlank()
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text("Connect")
                }
            }
        }
    }
}
