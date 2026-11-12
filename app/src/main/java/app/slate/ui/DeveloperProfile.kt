package app.slate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.slate.ui.components.SlateCard
import app.slate.ui.components.SlateMark
import app.slate.ui.theme.Slate

data class DeveloperContact(
    val label: String,
    val handle: String,
    val url: String,
    val icon: ImageVector,
)

object DeveloperInfo {
    const val NAME = "Aditya Kajala"
    const val INITIALS = "AK"
    const val ROLE = "B.Tech CSE (AI & ML) • Software Developer"
    const val BIO = "Creator of SLATE. Building fast, offline-first, advertisement-free apps and AI/ML tools."

    val contacts = listOf(
        DeveloperContact(
            label = "GitHub",
            handle = "@adityakajala1",
            url = "https://github.com/adityakajala1",
            icon = Icons.Outlined.Code,
        ),
        DeveloperContact(
            label = "LinkedIn",
            handle = "linkedin.com/in/adityakajala",
            url = "https://www.linkedin.com/in/adityakajala",
            icon = Icons.Outlined.WorkOutline,
        ),
        DeveloperContact(
            label = "Portfolio",
            handle = "adityakajala1.github.io/portfolio",
            url = "https://adityakajala1.github.io/portfolio/",
            icon = Icons.Outlined.Language,
        ),
    )

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "No browser app found to open link", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label link copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Dismissible popup shown when opening the app or opening any document.
 * Features a top-right cross (X) button and quick links to follow the developer.
 */
@Composable
fun FollowDeveloperDialog(
    onDismiss: () -> Unit,
    onOpenDeveloperPage: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val c = Slate.colors

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = c.surface,
            border = BorderStroke(1.dp, c.border),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(20.dp)) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = c.textSecondary,
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(c.surfaceAlt),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = DeveloperInfo.INITIALS,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = c.text,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Follow Developer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = DeveloperInfo.NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = c.textSecondary,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val github = DeveloperInfo.contacts[0]
                        val linkedin = DeveloperInfo.contacts[1]
                        Button(
                            onClick = {
                                DeveloperInfo.openUrl(context, github.url)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.text,
                                contentColor = c.background,
                            ),
                        ) {
                            Icon(github.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                DeveloperInfo.openUrl(context, linkedin.url)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, c.border),
                        ) {
                            Icon(linkedin.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("LinkedIn", maxLines = 1)
                        }
                    }

                    if (onOpenDeveloperPage != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenDeveloperPage) {
                            Text("View all contacts", color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated screen showcasing the developer and all contact links.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val c = Slate.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Developer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        SlateCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(c.surfaceAlt),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = DeveloperInfo.INITIALS,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = c.text,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = DeveloperInfo.NAME,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = DeveloperInfo.ROLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = DeveloperInfo.BIO,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        SlateCard {
            Text(
                text = "Contacts & Links",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))

            DeveloperInfo.contacts.forEachIndexed { index, contact ->
                if (index > 0) {
                    HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 8.dp))
                }
                ContactRow(
                    contact = contact,
                    onOpen = { DeveloperInfo.openUrl(context, contact.url) },
                    onCopy = { DeveloperInfo.copyToClipboard(context, contact.label, contact.url) },
                )
            }
        }

        SlateCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlateMark(size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Built with SLATE", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "100% offline-first, ad-free & open to feedback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ContactRow(
    contact: DeveloperContact,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = Slate.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.surfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(contact.icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(contact.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = contact.handle,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${contact.label}", tint = c.textSecondary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open ${contact.label}", tint = c.text, modifier = Modifier.size(18.dp))
        }
    }
}
