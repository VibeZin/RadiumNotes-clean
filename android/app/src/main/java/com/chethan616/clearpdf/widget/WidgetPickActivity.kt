package com.chethan616.clearpdf.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.notes.Note
import com.chethan616.clearpdf.notes.NoteFile
import com.chethan616.clearpdf.notes.NoteKind
import com.chethan616.clearpdf.ui.LucideIcons
import com.chethan616.clearpdf.ui.SheetRow
import com.chethan616.clearpdf.ui.SheetSection
import com.chethan616.clearpdf.ui.glass.GlassSettings
import com.chethan616.clearpdf.ui.theme.ClearTheme
import com.chethan616.clearpdf.ui.theme.ClearType
import com.chethan616.clearpdf.ui.theme.clear

/** Which note or list each widget shows (by widget id); none chosen: the latest. */
internal object WidgetPicks {
    private const val PREFS = "widget_picks"

    fun get(ctx: Context, widgetId: Int): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pick_$widgetId", null)

    fun set(ctx: Context, widgetId: Int, noteId: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (noteId == null) remove("pick_$widgetId") else putString("pick_$widgetId", noteId)
        }.apply()
    }

    /** The notes of [kind] a widget can show, in the desk's order (pinned first, then the newest). */
    fun candidates(ctx: Context, kind: NoteKind): List<Note> =
        NoteFile.read(ctx).filter { it.kind == kind && if (kind == NoteKind.List) it.items.any { i -> i.text.isNotBlank() } else !it.isEmpty }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.created })

    /** How many notes of [kind] there are to turn through. */
    fun count(ctx: Context, kind: NoteKind): Int = candidates(ctx, kind).size

    /** The note [widgetId] shows: the one chosen for it, while it still exists, else the latest of [kind]. */
    fun resolve(ctx: Context, widgetId: Int, kind: NoteKind): Note? {
        val all = NoteFile.read(ctx).filter { it.kind == kind && if (kind == NoteKind.List) it.items.any { i -> i.text.isNotBlank() } else !it.isEmpty }
        val chosen = get(ctx, widgetId)?.let { id -> all.firstOrNull { it.id == id } }
        return chosen ?: all.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated }).firstOrNull()
    }

    /**
     * Turn widget [widgetId] to the next note of [kind] (the widget's round button), wrapping round.
     * The order is by creation, so ticking an item never reshuffles which list comes next.
     */
    fun next(ctx: Context, widgetId: Int, kind: NoteKind) {
        val all = candidates(ctx, kind)
        if (all.size < 2) return
        val now = resolve(ctx, widgetId, kind)?.id
        val i = all.indexOfFirst { it.id == now }
        set(ctx, widgetId, all[(i + 1).mod(all.size)].id)
    }
}

/**
 * Choosing what a widget shows, when it is placed and later from the launcher's widget settings (as
 * iOS's Edit Widget): "Latest" or one of the lists (To-Do widget) or notes (Notes widget), in the
 * app's own sheet rows on its wallpaper.
 */
class WidgetPickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val mgr = AppWidgetManager.getInstance(this)
        val provider = mgr.getAppWidgetInfo(widgetId)?.provider
        val lists = provider?.className == TodoWidget::class.java.name
        val kind = if (lists) NoteKind.List else NoteKind.Text
        val notes = NoteFile.read(this)
            .filter { it.kind == kind && if (lists) it.items.any { i -> i.text.isNotBlank() } else !it.isEmpty }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
        val current = WidgetPicks.get(this, widgetId)
        GlassSettings.load(this)
        enableEdgeToEdge()
        setContent {
            ClearTheme(Widgets.themeChoice(this)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(clear.wall))) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                        Text(
                            stringResource(if (lists) R.string.widget_pick_list else R.string.widget_pick_note),
                            style = ClearType.Headline, color = clear.ink, modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        SheetSection {
                            SheetRow(LucideIcons.Sparkles, stringResource(R.string.widget_pick_latest), { choose(widgetId, null) }, checked = current == null)
                        }
                        if (notes.isNotEmpty()) SheetSection {
                            notes.forEach { n ->
                                val title = n.title.ifBlank {
                                    if (lists) getString(R.string.notes_untitled_list)
                                    else n.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("# ") ?: getString(R.string.notes_untitled_note)
                                }
                                SheetRow(if (lists) LucideIcons.Check else LucideIcons.FileText, title, { choose(widgetId, n.id) }, checked = current == n.id)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, noteId: String?) {
        WidgetPicks.set(this, widgetId, noteId)
        Widgets.refreshAll(applicationContext)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    companion object {
        /** Whether [component] is one of the widgets that show a chosen note. */
        fun picks(component: ComponentName?) = component?.className == TodoWidget::class.java.name || component?.className == NoteWidget::class.java.name
    }
}
