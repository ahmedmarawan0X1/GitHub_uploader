package com.jhftyyyty.githubuploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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


