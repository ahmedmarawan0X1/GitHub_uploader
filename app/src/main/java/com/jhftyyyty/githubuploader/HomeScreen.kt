package com.jhftyyyty.githubuploader

import com.jhftyyyty.githubuploader.core.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    t: AppStrings,
    token: String,
    uri: Uri?,
    name: String,
    autoNaming: Boolean,
    pick: () -> Unit,
    settings: () -> Unit,
    help: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
     var account by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(UploadMode.NEW) }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<RepoInfo?>(null) }
    var repoName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var privateRepo by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<UploadReview?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(name, autoNaming, mode) {
        if (autoNaming && mode == UploadMode.NEW && name.isNotBlank()) {
            repoName = name.substringBeforeLast(".").ifBlank { name }
        }
    }

    fun clearFeedback() {
        message = ""
        ok = null
        result = ""
        progressText = ""
        progress = 0f
    }

    LaunchedEffect(token) {
        account = if (token.isBlank()) null else runCatching {
            withContext(Dispatchers.IO) { GitHubApi.currentUser(token).login }
        }.getOrNull()
    }

    LaunchedEffect(Unit) {
        val wm = WorkManager.getInstance(context)
        while (true) {
            val up = withContext(Dispatchers.IO) {
                wm.getWorkInfosForUniqueWork(WORK_NAME).get().firstOrNull()
            }
            val down = withContext(Dispatchers.IO) {
                wm.getWorkInfosForUniqueWork(DownloadWorker.WORK_NAME).get().firstOrNull()
            }
            val info = if (mode == UploadMode.DOWNLOAD) down else up
            if (info != null) {
                busy = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                val total = info.progress.getInt(UploadWorker.KEY_TOTAL, 0)
                val done = info.progress.getInt(UploadWorker.KEY_DONE, 0)
                progress = if (total > 0) done.toFloat() / total else 0f
                progressText = info.progress.getString(UploadWorker.KEY_TEXT).orEmpty()

                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        busy = false
                        ok = true
                        message = if (mode == UploadMode.DOWNLOAD) t.downloadDone else t.done
                        result = if (mode == UploadMode.DOWNLOAD) {
                            info.outputData.getString(DownloadWorker.KEY_RESULT_PATH).orEmpty()
                        } else {
                            info.outputData.getString(UploadWorker.KEY_RESULT_URL).orEmpty()
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        busy = false
                        ok = false
                        message = info.outputData.getString(DownloadWorker.KEY_ERROR).orEmpty().ifBlank { t.error }
                        result = ""
                    }
                    WorkInfo.State.CANCELLED -> {
                        busy = false
                        ok = false
                        message = t.cancelled
                        result = ""
                    }
                    else -> Unit
                }
            }
            delay(700)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(t.app, fontWeight = FontWeight.Bold)
                        Text(t.subtitle, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = settings) { Icon(Icons.Default.Settings, t.settings) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            Spacer(Modifier.height(10.dp))

            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccountCircle,
                            null,
                            Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account ?: t.notConnected, fontWeight = FontWeight.SemiBold)
                            Text(t.authHint, style = MaterialTheme.typography.bodySmall)
                        }
                        if (account != null) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    TextButton(onClick = settings) {
                        Text(if (token.isBlank()) t.noToken else t.settings)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        t.operation,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OperationButton(
                            text = t.newRepo,
                            selected = mode == UploadMode.NEW,
                            enabled = !busy,
                            onClick = { mode = UploadMode.NEW; clearFeedback() },
                            modifier = Modifier.weight(1f)
                        )
                        OperationButton(
                            text = t.existing,
                            selected = mode == UploadMode.EXISTING,
                            enabled = !busy,
                            onClick = { mode = UploadMode.EXISTING; clearFeedback() },
                            modifier = Modifier.weight(1f)
                        )
                        OperationButton(
                            text = t.download,
                            selected = mode == UploadMode.DOWNLOAD,
                            enabled = !busy,
                            onClick = { mode = UploadMode.DOWNLOAD; clearFeedback() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            when (mode) {
                UploadMode.NEW -> {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        repoName,
                        { repoName = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(t.repoName) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        description,
                        { description = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(t.description) }
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(privateRepo, { privateRepo = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (privateRepo) t.privateRepo else t.publicRepo)
                    }
                }

                UploadMode.EXISTING, UploadMode.DOWNLOAD -> {
                    Spacer(Modifier.height(12.dp))
                    Text(t.repository, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    Spacer(Modifier.height(6.dp))
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (!busy && repos.isNotEmpty()) expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selected?.fullName ?: t.noRepositories,
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            readOnly = true,
                            label = { Text(t.chooseRepo) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Storage, null)
                            },
                            colors = OutlinedTextFieldDefaults.colors()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            repos.forEach { r ->
                                DropdownMenuItem(
                                    text = { Text(r.fullName) },
                                    onClick = {
                                        selected = r
                                        expanded = false
                                        clearFeedback()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        enabled = token.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    repos = withContext(Dispatchers.IO) { GitHubApi.listRepositories(token) }
                                    account = withContext(Dispatchers.IO) { GitHubApi.currentUser(token).login }
                                }.onFailure {
                                    message = GitHubApi.friendlyError(it.message ?: t.error)
                                    ok = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text(t.refreshRepositories)
                    }

                }
            }

            if (mode != UploadMode.DOWNLOAD) {
                Spacer(Modifier.height(12.dp))
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FolderZip, null, Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.source, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (name.isBlank()) t.noFile else name,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(enabled = !busy, onClick = pick) {
                            Text(t.chooseZip)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            val enabled = !busy &&
                token.isNotBlank() &&
                if (mode == UploadMode.DOWNLOAD) {
                    selected != null
                } else {
                    uri != null && (
                        (mode == UploadMode.NEW && repoName.isNotBlank()) ||
                            (mode != UploadMode.NEW && selected != null)
                        )
                }

            Button(
                enabled = if (busy) true else enabled,
                onClick = {
                    if (busy) {
                        WorkManager.getInstance(context).cancelUniqueWork(
                            if (mode == UploadMode.DOWNLOAD) DownloadWorker.WORK_NAME else WORK_NAME
                        )
                    } else scope.launch {
                        if (mode != UploadMode.DOWNLOAD) preparing = true
                        busy = mode == UploadMode.DOWNLOAD
                        ok = null
                        message = ""
                        result = ""
                        try {
                            if (mode == UploadMode.DOWNLOAD) {
                                val d = workDataOf(
                                    DownloadWorker.KEY_OWNER to selected!!.owner,
                                    DownloadWorker.KEY_REPO to selected!!.name,
                                    DownloadWorker.KEY_FULL_NAME to selected!!.fullName,
                                    DownloadWorker.KEY_BRANCH to selected!!.defaultBranch
                                )
                                WorkManager.getInstance(context).enqueueUniqueWork(
                                    DownloadWorker.WORK_NAME,
                                    ExistingWorkPolicy.REPLACE,
                                    OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(d).build()
                                )
                            } else {
                                val file = PendingUploadStore.create(context)
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri!!)
                                        ?.use { input -> file.outputStream().use(input::copyTo) }
                                        ?: error(t.openZip)
                                }
                                val summary = withContext(Dispatchers.IO) {
                                    GitHubApi.previewZip(context, file.absolutePath, token, mode, selected)
                                }
                                previewFile = file
                                preview = summary
                            }
                        } catch (e: Exception) {
                            busy = false
                            preparing = false
                            ok = false
                            message = GitHubApi.friendlyError(e.message ?: t.error)
                        } finally {
                            preparing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 15.dp, horizontal = 18.dp)
            ) {
                Icon(if (mode == UploadMode.DOWNLOAD) Icons.Default.Download else Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) t.cancel else if (preparing) t.working else if (mode == UploadMode.DOWNLOAD) t.download else t.review)
            }

            if (busy || progressText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                if (busy) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (progressText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(progressText, style = MaterialTheme.typography.bodySmall)
                }
                if (busy && mode != UploadMode.DOWNLOAD) {
                    Spacer(Modifier.height(4.dp))
                    Text(t.resuming, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                StatusCard(
                    success = ok == true,
                    message = message,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (result.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                if (mode == UploadMode.DOWNLOAD) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DownloadDone, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.savedToDownloads, fontWeight = FontWeight.SemiBold)
                                Text(t.downloadFolder, style = MaterialTheme.typography.bodySmall)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    data = Uri.parse(result)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                            )
                                        }
                                    }
                                ) {
                                    Text(t.openFile)
                                }
                            }
                        }
                    }
                } else {
                    OutlinedCard(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(result))
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.result, fontWeight = FontWeight.SemiBold)
                                Text(t.openOnGitHub, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    preview?.let { summary ->
        AlertDialog(
            onDismissRequest = {
                previewFile?.delete()
                previewFile = null
                preview = null
            },
            title = { Text(t.previewTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewRow(t.files, summary.files.toString())
                    PreviewRow(t.added, summary.additions.toString())
                    PreviewRow(t.modified, summary.modified.toString())
                    PreviewRow(t.unchanged, summary.unchanged.toString())
                    PreviewRow(t.ignored, summary.ignored.toString())
                    if (mode == UploadMode.EXISTING) PreviewRow(t.preserved, summary.remoteOnly.toString())
                    PreviewRow(t.size, formatBytes(summary.totalBytes))
                    Text(t.help11, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = previewFile ?: return@TextButton
                    val d = workDataOf(
                        UploadWorker.KEY_FILE_PATH to file.absolutePath,
                        UploadWorker.KEY_MODE to mode.name,
                        UploadWorker.KEY_NEW_REPO to repoName.trim(),
                        UploadWorker.KEY_DESCRIPTION to description.trim(),
                        UploadWorker.KEY_PRIVATE to privateRepo,
                        UploadWorker.KEY_OWNER to selected?.owner.orEmpty(),
                        UploadWorker.KEY_REPO to selected?.name.orEmpty(),
                        UploadWorker.KEY_FULL_NAME to selected?.fullName.orEmpty(),
                        UploadWorker.KEY_BRANCH to selected?.defaultBranch.orEmpty()
                    )
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                    val request = OneTimeWorkRequestBuilder<UploadWorker>()
                        .setInputData(d)
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                    busy = true
                    preview = null
                    previewFile = null
                }) { Text(t.startNow) }
            },
            dismissButton = {
                TextButton(onClick = {
                    previewFile?.delete()
                    previewFile = null
                    preview = null
                }) { Text(t.close) }
            }
        )
    }
}



@Composable
private fun PreviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format("%.1f KB", value / 1024f)
    else -> String.format("%.1f MB", value / (1024f * 1024f))
}

@Composable
private fun OperationButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 54.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(text, maxLines = 2)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 54.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(text, maxLines = 2)
        }
    }
}



@Composable
private fun StatusCard(success: Boolean, message: String, modifier: Modifier = Modifier) {
    val colors = if (success) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    }
    Card(colors = colors, modifier = modifier) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                null
            )
            Spacer(Modifier.width(10.dp))
            Text(message, fontWeight = FontWeight.Medium)
        }
    }
}


