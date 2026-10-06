package com.jhftyyyty.githubuploader

import com.jhftyyyty.githubuploader.core.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedName by mutableStateOf("")

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) acceptZip(uri)
    }

    private fun isZipUri(uri: Uri): Boolean {
        val name = runCatching { DocumentFile.fromSingleUri(this, uri)?.name }.getOrNull().orEmpty()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        return name.endsWith(".zip", true) ||
            mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            mime == "application/x-compress" ||
            mime == "application/octet-stream"
    }

    private fun acceptZip(uri: Uri, takePersistable: Boolean = false) {
        if (!isZipUri(uri)) return
        if (takePersistable) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = runCatching { DocumentFile.fromSingleUri(this, uri)?.name }.getOrNull()
            .orEmpty()
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "project.zip" }
        selectedUri = uri
        selectedName = name
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val candidates = buildList {
            incoming.data?.let(::add)
            incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
            incoming.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
            }
        }
        candidates.firstOrNull(::isZipUri)?.let { acceptZip(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            selectedUri = null
            selectedName = ""
        }
        super.onDestroy()
    }

    override fun onCreate(state: Bundle?) {
        installSplashScreen()
        super.onCreate(state)
        handleIncomingIntent(intent)
        PendingUploadStore.cleanupStale(this)

        setContent {
            val prefs = remember { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(
                    runCatching {
                        ThemeMode.valueOf(
                            prefs.getString(PREF_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
                        )
                    }.getOrDefault(ThemeMode.SYSTEM)
                )
            }
            var language by remember {
                mutableStateOf(
                    runCatching {
                        LanguageMode.valueOf(
                            prefs.getString(PREF_LANGUAGE, LanguageMode.SYSTEM.name) ?: LanguageMode.SYSTEM.name
                        )
                    }.getOrDefault(LanguageMode.SYSTEM)
                )
            }
            var token by remember { mutableStateOf(TokenStore(this@MainActivity).get()) }
            var account by remember { mutableStateOf<String?>(null) }
            var screen by remember { mutableStateOf(Screen.HOME) }
            var helpOrigin by remember { mutableStateOf(Screen.HOME) }

            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.AMOLED -> true
            }
            val t = AppStrings(this@MainActivity, language)

            BackHandler(screen != Screen.HOME) {
                screen = when (screen) {
                    Screen.HELP -> helpOrigin
                    Screen.SETTINGS -> Screen.HOME
                    Screen.HOME -> Screen.HOME
                }
            }

            AppTheme(dark) {
                SideEffect { updateSystemBars(window, dark) }

                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            t = t,
                            token = token,
                            uri = selectedUri,
                            name = selectedName,
                            autoNaming = prefs.getBoolean(PREF_AUTO_NAMING, true),
                            pick = {
                                picker.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            settings = { screen = Screen.SETTINGS },
                            account = account,
                            onAccountChanged = { account = it },
                        )

                        Screen.SETTINGS -> SettingsPanel(
                            t = t,
                            token = token,
                            theme = theme,
                            language = language,
                            autoNaming = prefs.getBoolean(PREF_AUTO_NAMING, true),
                            changeToken = { value ->
                                token = value
                                if (value.isBlank()) account = null
                                TokenStore(this@MainActivity).save(value)
                            },
                            changeTheme = { value ->
                                theme = value
                                prefs.edit().putString(PREF_THEME, value.name).apply()
                            },
                            changeLanguage = { value ->
                                language = value
                                prefs.edit().putString(PREF_LANGUAGE, value.name).apply()
                            },
                            changeAutoNaming = { value ->
                                prefs.edit().putBoolean(PREF_AUTO_NAMING, value).apply()
                            },
                            back = { screen = Screen.HOME },
                            help = { helpOrigin = Screen.SETTINGS; screen = Screen.HELP }
                        )

                        Screen.HELP -> HelpScreen(t) { screen = helpOrigin }
                    }

                    if (screen != Screen.HELP) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(92.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .offset {
                                    IntOffset(0, WindowInsets.ime.getBottom(this))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            NavigationBar(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(76.dp)
                                    .clip(MaterialTheme.shapes.extraLarge),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0)
                            ) {
                                NavigationBarItem(
                                    selected = screen == Screen.HOME,
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    onClick = { screen = Screen.HOME },
                                    icon = { Icon(Icons.Default.Home, null) },
                                    label = { Text(t.home) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.SETTINGS,
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    onClick = { screen = Screen.SETTINGS },
                                    icon = { Icon(Icons.Default.Settings, null) },
                                    label = { Text(t.settings) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
