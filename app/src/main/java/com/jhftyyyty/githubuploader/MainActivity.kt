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
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

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

    override fun onDestroy() {
        selectedUri = null
        selectedName = ""
        super.onDestroy()
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
            val dark = when (theme) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.AMOLED -> true
            }
            val t = AppStrings(this@MainActivity, language)
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
