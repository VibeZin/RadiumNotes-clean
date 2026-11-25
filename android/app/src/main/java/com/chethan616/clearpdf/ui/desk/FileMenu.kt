package com.chethan616.clearpdf.ui.desk

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.ui.AppViewModel
import com.chethan616.clearpdf.ui.LucideIcons
import com.chethan616.clearpdf.ui.glass.Feel
import com.chethan616.clearpdf.ui.glass.GlassDialog
import com.chethan616.clearpdf.ui.glass.GlassMenuItem
import com.chethan616.clearpdf.ui.glass.GlassMotion
import com.chethan616.clearpdf.ui.glass.GlassTextField
import com.chethan616.clearpdf.ui.glass.LocalGlassInk
import com.chethan616.clearpdf.ui.glass.MorphMenuRow
import com.chethan616.clearpdf.ui.glass.RoundedRectangle
import com.chethan616.clearpdf.ui.glass.pressable
import com.chethan616.clearpdf.ui.glass.rememberFeel
import com.chethan616.clearpdf.ui.home.FileRow
import com.chethan616.clearpdf.ui.home.FileTool
import com.chethan616.clearpdf.ui.home.displayName
import com.chethan616.clearpdf.ui.home.whenLabel
import com.chethan616.clearpdf.ui.theme.Candy
import com.chethan616.clearpdf.ui.theme.ClearType
import com.chethan616.clearpdf.ui.theme.clear
import com.chethan616.clearpdf.ui.viewer.formatSize
import kotlinx.coroutines.launch

/**
 * A file's menu, open: the file, whether it was opened from Recent, and the window bounds of what its
 * glass grows out of. [ghost]: nothing round stands there (a long-pressed card), so the glass starts
 * as a drop over the card's corner and melts away there when it closes.
 */
data class FileMenuAt(val row: FileRow, val recent: Boolean, val anchor: Rect, val ghost: Boolean = false)

/** The corner of a card [bounds] that a long press's menu grows from: a round drop of [side], inset from the top right. */
fun cardMenuAnchor(bounds: Rect, density: androidx.compose.ui.unit.Density, side: Dp = 44.dp, inset: Dp = 10.dp): Rect = with(density) {
    val s = side.toPx()
    val i = inset.toPx()
    Rect(bounds.right - i - s, bounds.top + i, bounds.right - i, bounds.top + i + s)
}

/**
 * A file's menu on the app's frosted menu glass, unfolding beside the file's ⋯ (or the corner of a
 * long-pressed card). The file at the top with its star, one tap to star or unstar right there (the menu
 * stays open and the star pops); four quick actions; then its tools. A row closes the menu, which
 * folds back into the ⋯ before the row's action runs.
 */
@Composable
fun FileMenu(vm: AppViewModel, at: FileMenuAt, onGone: () -> Unit) {
    // the app's glass popover, unfolding beside what was pressed (no glass button grows out of the ⋯)
    var chosen by remember(at) { mutableStateOf(false) }
    com.chethan616.clearpdf.ui.glass.GlassMenuLayout(at.anchor, onDismiss = onGone, width = 292.dp, maxHeight = 600.dp) {
        Spacer(Modifier.height(6.dp))
        FileMenuBody(vm, at.row, at.recent, chosen) { action ->
            if (!chosen) {
                chosen = true
                onGone()
                // once the menu has folded away (as GlassMenu's rows wait)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ action() }, 220L)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ColumnScope.FileMenuBody(vm: AppViewModel, r: FileRow, recent: Boolean, shut: Boolean, choose: (() -> Unit) -> Unit) {
    val ink = LocalGlassInk.current.takeIf { it.isSpecified } ?: clear.ink
    val starred = vm.isStarred(r)
    // the file: its first page, its name, when and how big, and its star
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperThumb(vm, r.thumbPath, 36.dp, 47.dp, corner = 6.dp, name = r.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(displayName(r.name), style = ClearType.BodyStrong, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(whenLabel(r.date), r.size.takeIf { it > 0 }?.let { formatSize(it) }).joinToString("  ·  "),
                style = ClearType.Caption, color = ink.copy(alpha = 0.6f), maxLines = 1,
            )
        }
        Spacer(Modifier.width(6.dp))
        StarToggle(starred, enabled = !shut, size = 44.dp, filled = true) { vm.setStarred(r, !starred) }
    }
    // the four things done most, a tap each
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QuickAction(LucideIcons.Share2, stringResource(R.string.common_share), Candy.Sky, ink, !shut) { choose { vm.shareFile(r) } }
        QuickAction(LucideIcons.Pencil, stringResource(R.string.common_rename), Candy.Grape, ink, !shut) { choose { vm.fileRenaming = r } }
        QuickAction(LucideIcons.Printer, stringResource(R.string.home_print), Candy.Mint, ink, !shut) { choose { vm.printFile(r) } }
        QuickAction(LucideIcons.ExternalLink, stringResource(R.string.home_open_with), Candy.Tangerine, ink, !shut) { choose { vm.openWith(r) } }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.1f)))
    Spacer(Modifier.height(4.dp))
    @Composable
    fun row(label: String, icon: ImageVector, destructive: Boolean = false, action: () -> Unit) {
        MorphMenuRow(GlassMenuItem(label, icon, destructive, action), shut) { choose(action) }
    }
    row(stringResource(R.string.common_tool_edit_pdf), LucideIcons.FilePenLine) { vm.openFor(r, FileTool.Edit) }
    row(stringResource(R.string.common_tool_read_aloud), LucideIcons.Volume2) { vm.openFor(r, FileTool.ReadAloud) }
    row(stringResource(R.string.home_export_as), LucideIcons.FileOutput) { vm.openFor(r, FileTool.Export) }
    row(stringResource(R.string.common_tool_compress), LucideIcons.FileArchive) { vm.openFor(r, FileTool.Compress) }
    row(stringResource(R.string.common_tool_set_password), LucideIcons.FileLock2) { vm.openFor(r, FileTool.Protect) }
    row(stringResource(R.string.home_duplicate), LucideIcons.Copy) { vm.duplicateFile(r) }
    row(stringResource(R.string.common_save_copy), LucideIcons.FileDown) { vm.saveFileCopy(r) }
    if (recent) row(stringResource(R.string.home_remove_from_recent), LucideIcons.CircleMinus) { vm.removeRecent(r.key) }
    row(stringResource(R.string.common_delete), LucideIcons.Trash2, destructive = true) { vm.fileDeleting = r }
}

/** One of the menu's quick actions: a candy blob with its glyph, its name under it. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickAction(icon: ImageVector, label: String, accent: Color, ink: Color, enabled: Boolean, onClick: () -> Unit) {
    val c = clear
    Column(
        Modifier.weight(1f).clip(RoundedRectangle(16.dp)).pressable(onClick, pressedScale = 0.88f, enabled = enabled).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = if (c.dark) 0.3f else 0.2f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, style = ClearType.Caption, color = ink.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * A star you tap to star or unstar a file: it pops (a quick squash and a bouncy spring back, by
 * Settings ▸ Motion) and ticks. [filled] gives it a round lemon well when starred (the menu's); else it
 * is a bare glyph (a file row's), faint until starred.
 */
@Composable
fun StarToggle(starred: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 44.dp, filled: Boolean = false, onToggle: () -> Unit) {
    val c = clear
    val feel = rememberFeel()
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val ink = LocalGlassInk.current.takeIf { it.isSpecified } ?: c.ink
    val glyph by animateColorAsState(
        when {
            starred && filled -> Color(0xFF1C2433)
            starred -> Candy.Lemon
            else -> ink.copy(alpha = if (filled) 0.7f else 0.35f)
        },
        GlassMotion.settle(), label = "star",
    )
    val well by animateColorAsState(
        if (filled) (if (starred) Candy.Lemon else ink.copy(alpha = 0.08f)) else Color.Transparent,
        GlassMotion.settle(), label = "starWell",
    )
    val label = stringResource(if (starred) R.string.home_unstar else R.string.home_star)
    Box(
        modifier.size(size)
            .semantics { contentDescription = label }
            .pressable(
                {
                    feel(if (starred) Feel.Fall else Feel.Rise)
                    onToggle()
                    scope.launch {
                        pop.snapTo(0.72f)
                        pop.animateTo(1f, GlassMotion.pop())
                    }
                },
                pressedScale = 0.86f, role = Role.Switch, enabled = enabled, feel = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size * 0.82f)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    // a little turn as it pops, the way a sticker is slapped on
                    rotationZ = (1f - pop.value) * 70f
                }
                .clip(CircleShape).background(well),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (starred) LucideIcons.Star else LucideIcons.StarOutline, null, tint = glyph, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/** Renaming and deleting a file, asked once its menu has folded away (they outlive the menu). */
@Composable
fun FileDialogs(vm: AppViewModel) {
    vm.fileRenaming?.let { r ->
        var name by remember(r.key) { mutableStateOf(com.chethan616.clearpdf.engine.DocumentTypes.title(r.name)) }
        GlassDialog(
            onDismiss = { vm.fileRenaming = null }, title = stringResource(R.string.common_rename), icon = LucideIcons.Pencil, accent = Candy.Grape,
            confirm = stringResource(R.string.common_done), confirmEnabled = name.isNotBlank() && !name.startsWith("."),
            onConfirm = { vm.fileRenaming = null; vm.renameFile(r, name) },
            dismiss = stringResource(R.string.common_cancel),
        ) { GlassTextField(name, { name = it }, stringResource(R.string.home_enter_name)) }
    }
    vm.fileDeleting?.let { r ->
        GlassDialog(
            onDismiss = { vm.fileDeleting = null }, title = stringResource(R.string.home_delete_file_title), icon = LucideIcons.Trash2, destructive = true,
            confirm = stringResource(R.string.common_delete), onConfirm = { vm.fileDeleting = null; vm.deleteFile(r) },
            dismiss = stringResource(R.string.common_cancel),
        ) { Text(stringResource(R.string.home_delete_file_body, r.name), style = ClearType.Body, color = clear.inkSoft) }
    }
}
