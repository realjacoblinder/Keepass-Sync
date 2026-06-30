package com.keepasssync.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.keepasssync.app.network.SyncApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    serverUrl: String,
    onChangeServer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Form state
    var masterName by remember { mutableStateOf("master") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }

    // Async state
    var lastUpdated by remember { mutableStateOf<Date?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Master name validation regex — mirrors front-end validation from web app
    val masterNameValid = masterName.isNotBlank() && masterName.matches(Regex("^[a-zA-Z0-9-]+$"))

    // --------------- File picker ---------------
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            // Resolve display name via ContentResolver
            context.contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        selectedFileName = cursor.getString(0)
                    }
                }
        }
    }

    // --------------- Download saver ---------------
    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { saveUri ->
            scope.launch {
                isDownloading = true
                statusMessage = null
                try {
                    val bytes = SyncApiClient.downloadDatabase(serverUrl, masterName)
                    context.contentResolver.openOutputStream(saveUri)?.use { out ->
                        out.write(bytes)
                    }
                    statusMessage = Pair(true, "Database downloaded successfully!")
                } catch (e: Exception) {
                    statusMessage = Pair(false, "Download failed: ${e.message}")
                } finally {
                    isDownloading = false
                }
            }
        }
    }

    // --------------- Auto-refresh status on masterName change ---------------
    LaunchedEffect(masterName) {
        if (masterNameValid) {
            delay(300) // debounce — matches the web app's 300ms
            try {
                val s = SyncApiClient.getStatus(serverUrl, masterName)
                lastUpdated = s.lastUpdated
            } catch (_: Exception) {
                lastUpdated = null
            }
        } else {
            lastUpdated = null
        }
    }

    // --------------- UI ---------------
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KeePass Sync") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = onChangeServer) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Change server")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Server info chip ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- Master database name ----
            OutlinedTextField(
                value = masterName,
                onValueChange = {
                    masterName = it
                    if (statusMessage?.first == false) statusMessage = null
                },
                label = { Text("Master Database Name") },
                placeholder = { Text("e.g., personal, work") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                isError = masterName.isNotBlank() && !masterNameValid,
                supportingText = if (masterName.isNotBlank() && !masterNameValid) {
                    { Text("Only alphanumeric characters and hyphens allowed") }
                } else null
            )

            // ---- Upload & Sync card ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Upload & Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Upload your local database to merge changes with the master copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // File picker
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing
                    ) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(selectedFileName ?: "Select .kdbx File")
                    }

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Master Password") },
                        placeholder = { Text("Enter master password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password"
                                    else "Show password"
                                )
                            }
                        },
                        enabled = !isSyncing
                    )

                    // Sync button
                    Button(
                        onClick = {
                            scope.launch {
                                isSyncing = true
                                statusMessage = null
                                try {
                                    val uri = selectedFileUri
                                        ?: throw Exception("No file selected")
                                    val bytes = context.contentResolver.openInputStream(uri)
                                        ?.use { it.readBytes() }
                                        ?: throw Exception("Could not read file")
                                    val result = SyncApiClient.syncDatabase(
                                        serverUrl,
                                        masterName,
                                        password,
                                        bytes,
                                        selectedFileName ?: "database.kdbx"
                                    )
                                    statusMessage = Pair(result.success, result.message)
                                    if (result.success) {
                                        password = ""
                                        selectedFileUri = null
                                        selectedFileName = null
                                        // Refresh status
                                        try {
                                            val s = SyncApiClient.getStatus(serverUrl, masterName)
                                            lastUpdated = s.lastUpdated
                                        } catch (_: Exception) { }
                                    }
                                } catch (e: Exception) {
                                    statusMessage = Pair(false, e.message ?: "Sync failed")
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing
                                && selectedFileUri != null
                                && password.isNotBlank()
                                && masterNameValid
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing…")
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync Database")
                        }
                    }
                }
            }

            // ---- Download card ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Download Master", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Download the latest merged master database to your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Status indicator
                    if (lastUpdated != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Last updated: ${java.text.DateFormat.getDateTimeInstance().format(lastUpdated!!)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "No master database found for '$masterName'",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Download button
                    OutlinedButton(
                        onClick = { downloadLauncher.launch("${masterName}.kdbx") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = lastUpdated != null && !isDownloading
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Downloading…")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download '$masterName' DB")
                        }
                    }
                }
            }

            // ---- Status message ----
            statusMessage?.let { (isSuccess, message) ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle
                            else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isSuccess) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSuccess) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                }
            }

            // Bottom spacer for scroll comfort
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
