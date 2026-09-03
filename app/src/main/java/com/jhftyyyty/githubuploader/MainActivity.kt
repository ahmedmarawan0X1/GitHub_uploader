package com.jhftyyyty.githubuploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream


class MainActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedName by mutableStateOf("")

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selectedUri = uri
            selectedName = DocumentFile.fromSingleUri(this, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "project.zip"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(runCatching {
                    ThemeMode.valueOf(prefs.getString(PREF_THEME, ThemeMode.SYSTEM.name)!!)
                }.getOrDefault(ThemeMode.LIGHT))
            }
            var language by remember {
                mutableStateOf(runCatching {
                    LanguageMode.valueOf(prefs.getString(PREF_LANGUAGE, LanguageMode.SYSTEM.name)!!)
                }.getOrDefault(LanguageMode.SYSTEM))
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

            LaunchedEffect(dark) { updateSystemBars(window, dark) }
            BackHandler(enabled = screen != Screen.HOME) {
                screen = when (screen) {
                    Screen.HELP -> Screen.SETTINGS
                    Screen.SETTINGS -> Screen.HOME
                    Screen.HOME -> Screen.HOME
                }
            }

            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    // سطح خارجي يغطي الشاشة بالكامل ويستخدم خلفية الـ ColorScheme الحالية.
                    // ده مهم خصوصًا في وضع AMOLED/Dark حتى لا تظل خلفية الشاشة فاتحة.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (screen) {
                        Screen.HOME -> HomeScreen(
                            t, prefs.getString(PREF_TOKEN, "") ?: "", selectedUri, selectedName,
                            { prefs.edit().putString(PREF_TOKEN, it).apply() },
                            { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            { screen = Screen.SETTINGS }
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            t, theme, language,
                            { theme = it; prefs.edit().putString(PREF_THEME, it.name).apply() },
                            { language = it; prefs.edit().putString(PREF_LANGUAGE, it.name).apply() },
                            { screen = Screen.HOME },
                            { screen = Screen.HELP }
                        )
                        Screen.HELP -> HelpScreen(t) { screen = Screen.SETTINGS }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    t: AppStrings, tokenPref: String, selectedUri: Uri?, selectedName: String,
    saveToken: (String) -> Unit, pickZip: () -> Unit, openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember(tokenPref) { mutableStateOf(tokenPref) }
    var repoName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var privateRepo by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(UploadMode.NEW) }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var selectedRepo by remember { mutableStateOf<RepoInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusSuccess by remember { mutableStateOf<Boolean?>(null) }
    var resultUrl by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // الهيدر ده مقصود يفضل LTR حتى لو لغة التطبيق عربية.
        // كده عنوان التطبيق ومكان زر الإعدادات يفضلوا بنفس توزيع الواجهة الإنجليزية.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        t.app,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        t.subtitle,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(
                    onClick = openSettings,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Default.Settings, t.settings)
                }
            }
        }

        OutlinedTextField(
            value = token, onValueChange = { token = it; saveToken(it) },
            modifier = Modifier.fillMaxWidth(), label = { Text(t.token) }, singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton({ showToken = !showToken }) { Text(if (showToken) t.hide else t.show) } }
        )

        FilledTonalButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_URL))) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t.newToken) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(mode == UploadMode.NEW, { mode = UploadMode.NEW }, label = { Text(t.createRepo) }, Modifier.weight(1f))
            FilterChip(mode == UploadMode.EXISTING, { mode = UploadMode.EXISTING }, label = { Text(t.updateRepo) }, Modifier.weight(1f))
        }

        if (mode == UploadMode.NEW) {
            OutlinedTextField(repoName, { repoName = it }, Modifier.fillMaxWidth(), label = { Text(t.repoName) }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text(t.description) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(privateRepo, { privateRepo = it })
                Spacer(Modifier.width(8.dp))
                Text(if (privateRepo) t.privateLabel else t.publicLabel)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(t.existing, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton(
                    enabled = token.isNotBlank() && !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            try {
                                repos = withContext(Dispatchers.IO) { GitHubApi.listRepositories(token.trim()) }
                                status = if (t.ar) "تم تحميل ${repos.size} مستودع" else "Loaded ${repos.size} repositories"
                                statusSuccess = true
                            } catch (e: Exception) {
                                status = "${t.error}: ${e.message}"
                                statusSuccess = false
                            } finally { loading = false }
                        }
                    }
                ) { Icon(Icons.Default.Refresh, t.refresh) }
            }

            if (repos.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(
                        selectedRepo?.fullName ?: "", {}, Modifier.fillMaxWidth().menuAnchor(),
                        readOnly = true, label = { Text(t.chooseRepo) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        repos.forEach { repo ->
                            DropdownMenuItem({ Text(repo.fullName) }, { selectedRepo = repo; expanded = false })
                        }
                    }
                }
            } else {
                Text(t.refreshHint, style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedButton(onClick = pickZip, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CloudUpload, null)
            Spacer(Modifier.width(8.dp))
            Text(if (selectedName.isBlank()) t.chooseZip else selectedName)
        }

        if (selectedName.isNotBlank()) Text("${t.selected}: $selectedName", style = MaterialTheme.typography.bodySmall)

        Button(
            enabled = !uploading && token.isNotBlank() && selectedUri != null &&
                    ((mode == UploadMode.NEW && repoName.isNotBlank()) || (mode == UploadMode.EXISTING && selectedRepo != null)),
            onClick = {
                uploading = true; progress = 0f; status = ""; statusSuccess = null; resultUrl = ""; saveToken(token)
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            GitHubApi.uploadZip(
                                context, selectedUri!!, token.trim(), mode, repoName.trim(),
                                description.trim(), privateRepo, selectedRepo
                            ) { done, total, text ->
                                progress = if (total == 0) 0f else done.toFloat() / total
                                progressText = text
                            }
                        }
                        status = t.success
                        statusSuccess = true
                        resultUrl = result
                    } catch (e: Exception) {
                        status = "${t.error}: ${e.message}"
                        statusSuccess = false
                        resultUrl = ""
                    } finally { uploading = false }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (uploading) t.uploading else t.execute) }

        if (uploading) {
            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            Text(progressText, style = MaterialTheme.typography.bodySmall)
        }
        if (status.isNotBlank()) {
            val successColor = androidx.compose.ui.graphics.Color(0xFF2E7D32)
            val errorColor = MaterialTheme.colorScheme.error
            val neutralColor = MaterialTheme.colorScheme.onSurface
            val statusColor = when (statusSuccess) {
                true -> successColor
                false -> errorColor
                null -> neutralColor
            }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (statusSuccess) {
                            true -> Icons.Default.CheckCircle
                            false -> Icons.Default.Error
                            null -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = status,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (statusSuccess == true && resultUrl.isNotBlank()) {
                OutlinedCard(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resultUrl)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                t.successLink,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                resultUrl,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(t.openLink, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        Text(t.noShare, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    t: AppStrings, theme: ThemeMode, language: LanguageMode,
    changeTheme: (ThemeMode) -> Unit, changeLanguage: (LanguageMode) -> Unit,
    back: () -> Unit, help: () -> Unit
) {
    val context = LocalContext.current
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        TopAppBar(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(t.settings, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, t.back) } }
        )
        Spacer(Modifier.height(10.dp))

        SettingCard(
            Icons.Default.Tune, t.appearance,
            when (theme) {
                ThemeMode.SYSTEM -> t.automatic
                ThemeMode.LIGHT -> t.light
                ThemeMode.AMOLED -> "AMOLED"
            }
        ) { themeDialog = true }

        SettingCard(
            Icons.Default.Language, t.language,
            when (language) { LanguageMode.SYSTEM -> t.device; LanguageMode.ARABIC -> t.arabic; LanguageMode.ENGLISH -> t.english }
        ) { languageDialog = true }

        SettingCard(Icons.Default.Link, t.projectLink, t.openProject, onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_PROJECT_URL)))
            }
        })

        SettingCard(Icons.Default.HelpOutline, t.help, t.helpSub, help)
    }

    if (themeDialog) {
        ChoiceDialog(
            t.appearance,
            listOf(t.automatic to ThemeMode.SYSTEM, t.light to ThemeMode.LIGHT, "AMOLED" to ThemeMode.AMOLED),
            theme, changeTheme, { themeDialog = false }
        )
    }
    if (languageDialog) {
        ChoiceDialog(
            t.language,
            listOf(t.device to LanguageMode.SYSTEM, t.arabic to LanguageMode.ARABIC, t.english to LanguageMode.ENGLISH),
            language, changeLanguage, { languageDialog = false }
        )
    }
}

@Composable
private fun SettingCard(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            ListItem(
                headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(value) },
                leadingContent = { Icon(icon, null) },
                trailingContent = { Icon(Icons.Default.ChevronLeft, null) }
            )
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            ListItem(
                headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(value)
                    }
                },
                leadingContent = { Icon(icon, null) }
            )
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<Pair<String, T>>, selected: T,
    select: (T) -> Unit, dismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == selected, { select(value) })
                        Text(label, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("OK") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpScreen(t: AppStrings, back: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        TopAppBar(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(t.help, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, t.back) } }
        )
        Text(t.explanation, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (t.ar) "التطبيق يرفع محتوى ملف ZIP إلى GitHub. يمكنك إنشاء Repository جديد أو تحديث Repository موجود مباشرة من الهاتف باستخدام GitHub API."
            else "The app uploads a ZIP file to GitHub. You can create a new repository or update an existing repository directly from the phone using the GitHub API."
        )
        Spacer(Modifier.height(18.dp))

        Text(t.tokenGuide, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (t.ar) "استخدم الرابط التالي لإنشاء Fine-grained Personal Access Token." else "Use the following link to create a Fine-grained Personal Access Token.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_URL))) },
            Modifier.fillMaxWidth()
        ) { Text(t.openToken) }

        Spacer(Modifier.height(16.dp))
        Text(t.fine, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            if (t.ar) {
                "• Repository access: All repositories or Only select repositories.\n" +
                "• Contents: Read and write\n• Administration: Read and write when creating a new repository\n" +
                "• Workflows: Read and write if the ZIP contains .github/workflows\n• Metadata: Read-only"
            } else {
                "• Repository access: All repositories, or select the repositories you need.\n" +
                "• Contents: Read and write\n• Administration: Read and write when creating a new Repository\n" +
                "• Workflows: Read and write if the ZIP contains .github/workflows\n• Metadata: Read-only"
            }
        )

        Spacer(Modifier.height(14.dp))
        Text(t.classic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (t.ar) "• الصلاحية المطلوبة: repo" else "• Classic Token: repo")

        Spacer(Modifier.height(14.dp))
        Text(if (t.ar) "استخدم زر الرجوع للعودة إلى صفحة الإعدادات." else "Use the back button to return to Settings.")

        Spacer(Modifier.height(14.dp))
        Text(t.security, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(t.noShare, style = MaterialTheme.typography.bodySmall)
    }
}

