package app.slate.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.R
import app.slate.core.DocFormat
import app.slate.core.DocKind
import app.slate.ui.theme.Slate

/** The slate-and-chalk brand mark. */
@Composable
fun SlateMark(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.slate_mark),
        contentDescription = "SLATE",
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.24f)),
    )
}

@Composable
fun kindColor(kind: DocKind?): Color = when (kind) {
    DocKind.PDF -> Slate.colors.pdf
    DocKind.WORD -> Slate.colors.word
    DocKind.SHEET -> Slate.colors.sheet
    DocKind.SLIDES -> Slate.colors.slides
    else -> Slate.colors.accent
}

/** Compact file-type badge: a page shape with the extension, tinted by family. */
@Composable
fun FileBadge(format: DocFormat?, size: Dp = 44.dp, isFolder: Boolean = false) {
    val c = Slate.colors
    val tint = if (isFolder) c.accent else kindColor(format?.kind)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(tint.copy(alpha = if (c.isDark) 0.16f else 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isFolder) {
            Icon(Icons.Outlined.Folder, null, tint = tint, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                (format?.ext ?: "?").uppercase(),
                color = tint,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.24f).sp,
                letterSpacing = 0.3.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = Slate.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(8.dp, 4.dp),
            )
        }
    }
}

/** Rounded surface card with a hairline border, the basic SLATE container. */
@Composable
fun SlateCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Slate.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.border),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Slate.colors.surfaceAlt),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Slate.colors.textSecondary, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Slate.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun LoadingState(message: String = "Openingâ€¦", progress: Float? = null) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress == null) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp), color = Slate.colors.accent)
        else CircularProgressIndicator(progress = { progress }, strokeWidth = 2.dp, modifier = Modifier.size(28.dp), color = Slate.colors.accent)
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Slate.colors.textSecondary)
    }
}

@Composable
fun ErrorState(title: String, message: String, onRetry: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(Icons.Outlined.ErrorOutline, title, message, actionLabel = if (onRetry != null) "Try again" else if (onClose != null) "Close" else null, onAction = onRetry ?: onClose)
    }
}

/** Single-line text prompt used for rename / new folder / find, etc. */
@Composable
fun TextPromptDialog(
    title: String,
    initial: String = "",
    label: String = "",
    confirm: String = "OK",
    selectBaseName: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember {
        val end = if (selectBaseName && initial.contains('.')) initial.lastIndexOf('.') else initial.length
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, end)))
    }
    val fr = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                label = if (label.isNotEmpty()) ({ Text(label) }) else null,
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
                shape = RoundedCornerShape(12.dp),
            )
            LaunchedEffect(Unit) { fr.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = value.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, color = Slate.colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Small pill used for filters / chips. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Slate.colors
    val bg = if (selected) c.text else c.surface
    val fg = if (selected) c.background else c.text
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) bg else c.border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun DocIcon(modifier: Modifier = Modifier) = Icon(Icons.Outlined.Description, null, modifier)
