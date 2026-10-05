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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPanel(
    t: AppStrings,
    token: String,
    theme: ThemeMode,
    language: LanguageMode,
    autoNaming: Boolean,
    changeToken: (String) -> Unit,
    changeTheme: (ThemeMode) -> Unit,
    changeLanguage: (LanguageMode) -> Unit,
    changeAutoNaming: (Boolean) -> Unit,
    back: () -> Unit,
    help: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showToken by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var tokenValid by remember { mutableStateOf<Boolean?>(null) }
    var account by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token) {
        tokenValid = null
        account = null
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        t.settings,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        t.account,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SectionHeader(Icons.Default.Key, t.token)

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { tokenValid = null; account = null; changeToken(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(t.token) },
                            placeholder = { Text(t.tokenPlaceholder) },
                            singleLine = true,
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showToken = !showToken }) {
                                    Text(if (showToken) t.hide else t.show)
                                }
                            }
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = token.isNotBlank() && !verifying,
                                onClick = {
                                    scope.launch {
                                        verifying = true
                                        tokenValid = runCatching {
                                            val user = withContext(Dispatchers.IO) { GitHubApi.currentUser(token.trim()) }
                                            account = user.login
                                            true
                                        }.getOrElse { false }
                                        verifying = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.VerifiedUser, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (verifying) t.working else t.verifyToken)
                            }

                            OutlinedButton(
                                enabled = token.isNotBlank() && !verifying,
                                onClick = {
                                    changeToken("")
                                    tokenValid = null
                                    account = null
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DeleteOutline, null)
                                Spacer(Modifier.width(6.dp))
                                Text(t.removeToken)
                            }
                        }

                        when {
                            account != null -> TokenStatus(t.tokenValid + " · " + account!!, true)
                            tokenValid == false -> TokenStatus(t.tokenInvalid, false)
                            token.isBlank() -> Text(t.noToken, style = MaterialTheme.typography.bodySmall)
                        }

                        OutlinedButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_URL))) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text(t.createToken)
                        }
                    }
                }

                SectionHeader(Icons.Default.Tune, t.theme)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(4.dp)) {
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
                    }
                }

                SectionHeader(Icons.Default.MoreHoriz, t.help)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(6.dp)) {
                        SettingRow(Icons.Default.Link, t.projectLink, t.openProject) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_PROJECT_URL))) }
                        }
                        SettingRow(Icons.Default.HelpOutline, t.help, t.help6, help)
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TokenStatus(text: String, success: Boolean) {
    val container = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null)
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}