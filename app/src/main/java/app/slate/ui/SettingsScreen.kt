package app.slate.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.BuildConfig
import app.slate.SlateApp
import app.slate.core.io.DocIO
import app.slate.data.Settings
import app.slate.data.Thumbnails
import app.slate.files.formatBytes
import app.slate.ui.components.Pill
import app.slate.ui.components.SlateCard
import app.slate.ui.components.SlateMark
import app.slate.ui.theme.Slate
import app.slate.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    settings: Settings,
    onOpenDeveloper: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()

    var cacheBytes by remember { mutableLongStateOf(0L) }
    var libraryBytes by remember { mutableLongStateOf(0L) }

    fun recomputeSizes() {
        scope.launch {
            val (cSize, lSize) = withContext(Dispatchers.IO) {
                val c = context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                val l = DocIO.libraryDir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                c to l
            }
            cacheBytes = cSize
            libraryBytes = lSize
        }
    }

    LaunchedEffect(Unit) { recomputeSizes() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // Appearance / Theme
        SlateCard {
            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Choose Light, Dark, or follow your Android system theme.", style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("System", settings.theme == ThemeMode.SYSTEM, onClick = { app.settings.setTheme(ThemeMode.SYSTEM) })
                Pill("Light", settings.theme == ThemeMode.LIGHT, onClick = { app.settings.setTheme(ThemeMode.LIGHT) })
                Pill("Dark", settings.theme == ThemeMode.DARK, onClick = { app.settings.setTheme(ThemeMode.DARK) })
            }
        }

        // Reading & Editing Preferences
        SlateCard {
            Text("Reading & Editing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            PreferenceToggleRow(
                title = "Remember reading position",
                subtitle = "Reopen PDFs, slides, and documents at the exact page you left",
                checked = settings.rememberPosition,
                onCheckedChange = { app.settings.setRememberPosition(it) },
            )
            HorizontalDivider(color = Slate.colors.border, modifier = Modifier.padding(vertical = 8.dp))
            PreferenceToggleRow(
                title = "Auto-save recovery drafts",
                subtitle = "Keep local crash-recovery drafts while editing documents",
                checked = settings.autoSave,
                onCheckedChange = { app.settings.setAutoSave(it) },
            )
            HorizontalDivider(color = Slate.colors.border, modifier = Modifier.padding(vertical = 8.dp))
            PreferenceToggleRow(
                title = "Keep screen on while reading",
                subtitle = "Prevent screen from dimming inside viewers and editors",
                checked = settings.keepScreenOn,
                onCheckedChange = { app.settings.setKeepScreenOn(it) },
            )
            HorizontalDivider(color = Slate.colors.border, modifier = Modifier.padding(vertical = 8.dp))
            PreferenceToggleRow(
                title = "Show hidden dot-files",
                subtitle = "Display files and folders starting with '.' in the File Manager",
                checked = settings.showHidden,
                onCheckedChange = { app.settings.setShowHidden(it) },
            )
        }

        // Storage & Cache Management
        SlateCard {
            Text("Storage & Cache", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "SLATE Library: ${formatBytes(libraryBytes)} • Temp & Thumbnail Cache: ${formatBytes(cacheBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate.colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Thumbnails.clear(context)
                                DocIO.secureTemp(context).deleteRecursively()
                            }
                            recomputeSizes()
                            Toast.makeText(context, "Cleared cache and temporary files", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear Cache")
                }
                OutlinedButton(
                    onClick = {
                        app.recents.clear()
                        Toast.makeText(context, "Cleared recent history", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear Recents")
                }
            }
        }

        // Privacy Guarantee
        SlateCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, null, tint = Slate.colors.sheet)
                Spacer(Modifier.width(10.dp))
                Text("Privacy & Offline Architecture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "• 100% advertisement-free and subscription-free.\n" +
                    "• Zero internet permissions requested in the Android manifest — your documents never leave your device unless you explicitly share them.\n" +
                    "• No trackers, analytics, accounts, or background services.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate.colors.textSecondary,
            )
        }

        // Developer Profile & Contacts
        SlateCard(onClick = onOpenDeveloper) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, null, tint = Slate.colors.text)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Developer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${DeveloperInfo.NAME} • Contacts & Links", style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Open Developer Profile", tint = Slate.colors.textSecondary)
            }
        }

        // About Slate
        SlateCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlateMark(size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Slate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Text("Every document. One place.", style = MaterialTheme.typography.bodyMedium, color = Slate.colors.textSecondary)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = Slate.colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PreferenceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
