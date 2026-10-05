package com.jhftyyyty.githubuploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedName by mutableStateOf("")
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) acceptZip(uri)
    }

    private fun isZipUri(uri: Uri): Boolean {
        val name = runCatching {
            DocumentFile.fromSingleUri(this, uri)?.name
        }.getOrNull().orEmpty()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        return name.lowercase().endsWith(".zip") ||
            mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            mime == "application/x-compress" ||
            mime == "application/octet-stream"
    }

    private fun acceptZip(uri: Uri, takePersistable: Boolean = false) {
        if (!isZipUri(uri)) return

        if (takePersistable) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        val name = runCatching {
            DocumentFile.fromSingleUri(this, uri)?.name
        }.getOrNull().orEmpty().ifBlank {
            uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "project.zip"
        }

        selectedUri = uri
        selectedName = name
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return

        val uri = incoming.data
        if (uri != null && (incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val candidates = mutableListOf<Uri>()
        incoming.data?.let(candidates::add)
        incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(candidates::add)
        if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
            incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(candidates::add)
        }
        incoming.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let(candidates::add)
            }
        }

        candidates.firstOrNull(::isZipUri)?.let { acceptZip(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreate(state: Bundle?) {
        installSplashScreen()
        super.onCreate(state)
        handleIncomingIntent(intent)
        setContent {
            val prefs = remember { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(
                    runCatching {
                        ThemeMode.valueOf(prefs.getString(PREF_THEME, ThemeMode.SYSTEM.name)!!)
                    }.getOrDefault(ThemeMode.SYSTEM)
                )
            }
            var language by remember {
                mutableStateOf(
                    runCatching {
                        LanguageMode.valueOf(prefs.getString(PREF_LANGUAGE, LanguageMode.SYSTEM.name)!!)
                    }.getOrDefault(LanguageMode.SYSTEM)
                )
            }
            val ar = when (language) {
                LanguageMode.SYSTEM -> java.util.Locale.getDefault().language.equals("ar", true)
                LanguageMode.ARABIC -> true
                LanguageMode.ENGLISH -> false
            }
            val dark = when (theme) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.AMOLED -> true
            }
            val t = AppStrings(ar)
            var screen by remember { mutableStateOf(Screen.HOME) }
            var helpOrigin by remember { mutableStateOf(Screen.HOME) }

            BackHandler(screen != Screen.HOME) {
                screen = if (screen == Screen.HELP) helpOrigin else Screen.HOME
            }

            AppTheme(dark) {
                SideEffect { updateSystemBars(window, dark) }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        t, selectedUri, selectedName, prefs.getBoolean(PREF_AUTO_NAMING, true),
                        { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        { screen = Screen.SETTINGS },
                        { helpOrigin = Screen.HOME; screen = Screen.HELP }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        t, theme, language, prefs.getBoolean(PREF_AUTO_NAMING, true),
                        { theme = it; prefs.edit().putString(PREF_THEME, it.name).apply() },
                        { language = it; prefs.edit().putString(PREF_LANGUAGE, it.name).apply() },
                        { prefs.edit().putBoolean(PREF_AUTO_NAMING, it).apply() },
                        { screen = Screen.HOME },
                        { helpOrigin = Screen.SETTINGS; screen = Screen.HELP }
                    )
                    Screen.HELP -> HelpScreen(t) { screen = helpOrigin }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    t: AppStrings,
    uri: Uri?,
    name: String,
    autoNaming: Boolean,
    pick: () -> Unit,
    settings: () -> Unit,
    help: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TokenStore(context) }

    var token by remember { mutableStateOf(store.get()) }
    var account by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(UploadMode.NEW) }
    var sync by remember { mutableStateOf(false) }
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
    var showToken by remember { mutableStateOf(false) }
    var oauthDialog by remember { mutableStateOf(false) }
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
                    IconButton(onClick = help) { Icon(Icons.Default.HelpOutline, t.help) }
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

                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; store.save(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t.token) },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showToken = !showToken }) {
                                Text(if (showToken) t.hide else t.show)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors()
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (BuildConfig.GITHUB_CLIENT_ID.isNotBlank()) {
                            Button(
                                enabled = !busy,
                                onClick = { oauthDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Language, null)
                                Spacer(Modifier.width(6.dp))
                                Text(t.browserLogin)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_URL))
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(t.createToken)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(t.operation, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

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
                    text = t.updateRepo,
                    selected = mode == UploadMode.EXISTING || mode == UploadMode.SYNC,
                    enabled = !busy,
                    onClick = { mode = UploadMode.EXISTING; sync = false; clearFeedback() },
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

                UploadMode.EXISTING, UploadMode.DOWNLOAD, UploadMode.SYNC -> {
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
                                    message = t.error + ": " + (it.message ?: "")
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

                    if (repos.isNotEmpty() && selected != null && mode == UploadMode.EXISTING) {
                        Spacer(Modifier.height(8.dp))
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Switch(sync, { sync = it })
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.syncMode, fontWeight = FontWeight.SemiBold)
                                    Text(t.syncHint, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
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
                enabled = enabled,
                onClick = {
                    scope.launch {
                        busy = true
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
                                val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
                                val file = File(dir, "job_" + System.currentTimeMillis() + ".zip")
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri!!)
                                        ?.use { input -> file.outputStream().use(input::copyTo) }
                                        ?: error(t.openZip)
                                }
                                val actual = if (mode == UploadMode.NEW) {
                                    UploadMode.NEW.name
                                } else if (sync) {
                                    UploadMode.SYNC.name
                                } else {
                                    UploadMode.EXISTING.name
                                }
                                val d = workDataOf(
                                    UploadWorker.KEY_FILE_PATH to file.absolutePath,
                                    UploadWorker.KEY_MODE to actual,
                                    UploadWorker.KEY_NEW_REPO to repoName.trim(),
                                    UploadWorker.KEY_DESCRIPTION to description.trim(),
                                    UploadWorker.KEY_PRIVATE to privateRepo,
                                    UploadWorker.KEY_OWNER to selected?.owner.orEmpty(),
                                    UploadWorker.KEY_REPO to selected?.name.orEmpty(),
                                    UploadWorker.KEY_FULL_NAME to selected?.fullName.orEmpty(),
                                    UploadWorker.KEY_BRANCH to selected?.defaultBranch.orEmpty()
                                )
                                WorkManager.getInstance(context).enqueueUniqueWork(
                                    WORK_NAME,
                                    ExistingWorkPolicy.REPLACE,
                                    OneTimeWorkRequestBuilder<UploadWorker>().setInputData(d).build()
                                )
                            }
                        } catch (e: Exception) {
                            busy = false
                            ok = false
                            message = t.error + ": " + (e.message ?: "")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 15.dp, horizontal = 18.dp)
            ) {
                Icon(if (mode == UploadMode.DOWNLOAD) Icons.Default.Download else Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) t.working else if (mode == UploadMode.DOWNLOAD) t.download else t.start)
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

    if (oauthDialog) {
        BrowserLoginDialog(
            t,
            { oauthDialog = false },
            { newToken ->
                token = newToken
                store.save(newToken)
                scope.launch {
                    account = try {
                        withContext(Dispatchers.IO) { GitHubApi.currentUser(newToken).login }
                    } catch (_: Exception) {
                        null
                    }
                }
                oauthDialog = false
            }
        )
    }
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

@Composable
private fun BrowserLoginDialog(t: AppStrings, close: () -> Unit, done: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<GitHubOAuth.DeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(t.browserLogin) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (device == null) t.browserLoginHint else t.enterCode + ": " + device!!.userCode)
                if (device != null) {
                    OutlinedButton(
                        { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(device!!.verificationUri))) },
                        Modifier.fillMaxWidth()
                    ) {
                        Text(t.openBrowser)
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            val fresh = device == null
                            val d = device ?: withContext(Dispatchers.IO) {
                                GitHubOAuth.requestDeviceCode()
                            }.also { device = it }
                            if (fresh) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.verificationUri)))
                            }
                            done(withContext(Dispatchers.IO) { GitHubOAuth.pollForToken(d) })
                        } catch (e: Exception) {
                            error = e.message ?: t.error
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text(if (busy) t.waiting else if (device == null) t.connect else t.check)
            }
        },
        dismissButton = { TextButton(close) { Text(t.cancel) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    t: AppStrings,
    theme: ThemeMode,
    language: LanguageMode,
    autoNaming: Boolean,
    changeTheme: (ThemeMode) -> Unit,
    changeLanguage: (LanguageMode) -> Unit,
    changeAutoNaming: (Boolean) -> Unit,
    back: () -> Unit,
    help: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text(t.settings, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(back) { Icon(Icons.Default.ArrowBack, t.back) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingRow(
                    Icons.Default.DarkMode,
                    t.theme,
                    when (theme) {
                        ThemeMode.SYSTEM -> t.system
                        ThemeMode.LIGHT -> t.light
                        ThemeMode.AMOLED -> t.amoled
                    }
                ) {
                    changeTheme(
                        when (theme) {
                            ThemeMode.SYSTEM -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.AMOLED
                            ThemeMode.AMOLED -> ThemeMode.SYSTEM
                        }
                    )
                }

                SettingRow(
                    Icons.Default.Language,
                    t.language,
                    when (language) {
                        LanguageMode.SYSTEM -> t.system
                        LanguageMode.ARABIC -> t.arabic
                        LanguageMode.ENGLISH -> t.english
                    }
                ) {
                    changeLanguage(
                        when (language) {
                            LanguageMode.SYSTEM -> LanguageMode.ARABIC
                            LanguageMode.ARABIC -> LanguageMode.ENGLISH
                            LanguageMode.ENGLISH -> LanguageMode.SYSTEM
                        }
                    )
                }

                SettingToggleRow(
                    Icons.Default.AutoAwesome,
                    t.autoNaming,
                    t.autoNamingSub,
                    autoNaming,
                    changeAutoNaming
                )

                SettingRow(
                    Icons.Default.Link,
                    t.projectLink,
                    t.openProject
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_PROJECT_URL))
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    help,
                    Modifier.fillMaxWidth().heightIn(min = 50.dp)
                ) {
                    Icon(Icons.Default.HelpOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text(t.help)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpScreen(t: AppStrings, back: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text(t.help, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(back) { Icon(Icons.Default.ArrowBack, t.back) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HelpCard(1, t.help1)
                HelpCard(2, t.help2)
                HelpCard(3, t.help3)
                HelpCard(4, t.help4)
                HelpCard(5, t.help5)
                HelpCard(6, t.help6)
                HelpCard(7, t.help7)
                HelpCard(8, t.help8)
                HelpCard(9, t.help9)
                HelpCard(10, t.help10)
                HelpCard(11, t.help11)
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun HelpCard(number: Int, text: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    number.toString(),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
