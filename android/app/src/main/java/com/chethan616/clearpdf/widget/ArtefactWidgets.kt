package com.chethan616.clearpdf.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chethan616.clearpdf.MainActivity
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.notes.Note
import com.chethan616.clearpdf.notes.NoteFile
import com.chethan616.clearpdf.notes.NoteKind
import com.chethan616.clearpdf.notes.Notes
import com.chethan616.clearpdf.ui.AppViewModel
import com.chethan616.clearpdf.ui.ThemeChoice
import com.chethan616.clearpdf.ui.glass.Feel
import com.chethan616.clearpdf.ui.glass.GlassSettings
import com.chethan616.clearpdf.ui.glass.Haptics
import com.chethan616.clearpdf.ui.home.CardShape
import com.chethan616.clearpdf.ui.home.ListCard
import com.chethan616.clearpdf.ui.home.LocalHomeVm
import com.chethan616.clearpdf.ui.home.NoteCard
import com.chethan616.clearpdf.ui.home.RecentCard
import com.chethan616.clearpdf.ui.home.StarredEmptyCard
import com.chethan616.clearpdf.ui.theme.ClearTheme
import com.chethan616.clearpdf.ui.theme.Tone
import com.chethan616.clearpdf.ui.theme.clear
import java.io.File

/** What a widget tap asks the app to open: a note by its id. */
const val EXTRA_OPEN_NOTE = "com.chethan616.clearpdf.OPEN_NOTE"

/** What a widget tap asks the app to open: a recent document by its uri. */
const val EXTRA_OPEN_RECENT = "com.chethan616.clearpdf.OPEN_RECENT"

/** A widget tap that asks for Files (Recent). */
const val EXTRA_OPEN_FILES = "com.chethan616.clearpdf.OPEN_FILES"

/** A widget tap that asks for Files ▸ Starred. */
const val EXTRA_OPEN_STARRED = "com.chethan616.clearpdf.OPEN_STARRED"

/**
 * The home screen widgets. Notes, Recent and Starred are the desk's very cards (`NoteCard`,
 * `RecentCard`), liquid glass and all, rendered off screen ([ComposeSnapshot]) and kept as pictures.
 * The To-Do list on Android 12+ is the card's picture with real check boxes over it: the home screen
 * flips a tick itself the moment it is tapped and only tells the app, so nothing is redrawn on the tap.
 *
 * Speed rules: a picture is drawn only when what it shows changed (a signature per widget); a resize
 * shows the last picture scaled whole (never stretched) and redraws once the size has settled; a
 * render that a newer one replaces is dropped before it starts.
 */
object Widgets {
    private val main = Handler(Looper.getMainLooper())

    private class Job(val key: String, val run: (next: () -> Unit) -> Unit)

    private val queue = ArrayDeque<Job>()
    private var busy = false

    /**
     * Runs the renders one after another on the main thread (one virtual display at a time). A job
     * queued under [key] replaces one of the same key still waiting: only the newest picture is drawn.
     */
    internal fun enqueue(key: String, job: (next: () -> Unit) -> Unit) {
        main.post {
            queue.removeAll { it.key == key }
            queue.addLast(Job(key) { next -> job(next) })
            drain()
        }
    }

    private fun drain() {
        if (busy) return
        val job = queue.removeFirstOrNull() ?: return
        busy = true
        job.run {
            main.post {
                busy = false
                drain()
            }
        }
    }

    /** What each widget shows now (by widget id): it is drawn again only when this changes. */
    private val shown = java.util.concurrent.ConcurrentHashMap<Int, String>()

    internal fun isShowing(id: Int, sig: String) = shown[id] == sig

    internal fun showing(id: Int, sig: String) { shown[id] = sig }

    internal fun forget(id: Int) { shown.remove(id) }

    private val todoSoon = Runnable { pendingCtx?.let { TodoWidget.syncAll(it) } }
    private val notesSoon = Runnable { pendingCtx?.let { NoteWidget.refreshChanged(it) } }
    @Volatile private var pendingCtx: Context? = null

    /**
     * The notes changed in the app: the lists follow at once (they cost nothing to redraw), the note
     * cards once the notes have been still for a moment, and only those whose note really changed.
     * Any thread.
     */
    fun refreshSoon(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val lists = ids(app, mgr, TodoWidget::class.java)
        val notes = ids(app, mgr, NoteWidget::class.java)
        if (lists.isEmpty() && notes.isEmpty()) return
        pendingCtx = app
        if (lists.isNotEmpty()) {
            main.removeCallbacks(todoSoon)
            main.postDelayed(todoSoon, 1200L)
        }
        if (notes.isNotEmpty()) {
            main.removeCallbacks(notesSoon)
            main.postDelayed(notesSoon, 1200L)
        }
    }

    /** Redraw every list and note widget (a widget was set to show another one). Any thread. */
    fun refreshAll(ctx: Context, then: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val todo = ids(app, mgr, TodoWidget::class.java)
        val notes = ids(app, mgr, NoteWidget::class.java)
        var left = todo.size + notes.size
        if (left == 0) {
            then?.invoke()
            return
        }
        val one = { if (--left == 0) then?.invoke() }
        todo.forEach { id -> forget(id); TodoWidget.update(app, mgr, id, one) }
        notes.forEach { id -> forget(id); NoteWidget.update(app, mgr, id, one) }
    }

    internal fun ids(ctx: Context, mgr: AppWidgetManager, provider: Class<*>): IntArray =
        mgr.getAppWidgetIds(ComponentName(ctx, provider))

    /** The widget's size in pixels (its current size on the home screen, portrait or landscape). */
    internal fun size(ctx: Context, options: Bundle): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        // Android 12+: the sizes the launcher really lays the widget out at (the min/max pair can be off)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = androidx.core.os.BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, android.util.SizeF::class.java)
            val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val pick = sizes?.let { list -> if (portrait) list.minByOrNull { it.width } else list.maxByOrNull { it.width } }
            if (pick != null && pick.width > 0f && pick.height > 0f) {
                fun px(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
                return px(pick.width) to px(pick.height)
            }
        }
        val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wDp = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 160)
        val hDp = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220)
        fun px(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.coerceAtLeast(40).toFloat(), dm).toInt()
        return px(wDp) to px(hDp)
    }

    internal fun activity(ctx: Context, requestKey: String, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .setData(Uri.parse("artefact://widget/$requestKey"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply(extras)
        return PendingIntent.getActivity(ctx, requestKey.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun open(ctx: Context, noteId: String?, requestKey: String): PendingIntent =
        activity(ctx, requestKey) { if (noteId != null) putExtra(EXTRA_OPEN_NOTE, noteId) }

    /** A broadcast to [provider] with [action] for widget [id]. */
    internal fun broadcast(ctx: Context, provider: Class<*>, action: String, id: Int): PendingIntent {
        val intent = Intent(ctx, provider).setAction(action)
            .setData(Uri.parse("artefact://widget/$action/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(ctx, "$action/$id".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun mediaFile(ctx: Context, name: String) = File(File(File(ctx.filesDir, "notes"), "media"), name)

    internal fun cornerPx(ctx: Context) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30f, ctx.resources.displayMetrics)

    internal fun toDp(ctx: Context, px: Int): Dp = (px / ctx.resources.displayMetrics.density).dp

    internal fun themeChoice(ctx: Context): ThemeChoice =
        runCatching { ThemeChoice.valueOf(ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme", "System") ?: "System") }.getOrDefault(ThemeChoice.System)

    /** A desk card's width: the desk is two columns, 16 dp from the screen's edges and 12 dp apart. */
    internal fun deskWidth(ctx: Context): Dp = ((ctx.resources.configuration.screenWidthDp - 44) / 2f).coerceIn(120f, 240f).dp

    /**
     * The card as the desk lays it out, as wide as the widget and at least as tall, on what the desk
     * shows behind it (the wallpaper's gradient), in the app's theme; [placed] gets its bounds.
     */
    @Composable
    internal fun Desk(choice: ThemeChoice, placed: (android.graphics.Rect) -> Unit, content: @Composable () -> Unit) {
        ClearTheme(choice) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().wrapContentHeight().clip(CardShape).background(Brush.verticalGradient(clear.wall))
                        .onGloballyPositioned { placed(it.boundsInRoot().toRect()) },
                ) { content() }
            }
        }
    }

    internal fun androidx.compose.ui.geometry.Rect.toRect() = android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    /** How the card sits in the widget (fit inside, centred, never enlarged): its scale and where its corner lands. */
    internal class Fit(val scale: Float, val dx: Float, val dy: Float)

    internal fun fit(boxW: Int, boxH: Int, cardW: Int, cardH: Int): Fit {
        val s = minOf(boxW / cardW.toFloat(), boxH / cardH.toFloat())
        // centred across, resting on the bottom: the card meets the label the launcher writes under it
        return Fit(s, (boxW - cardW * s) / 2f, boxH - cardH * s)
    }

    /** [card] drawn into a picture of the widget's own size where [fit] puts it (the image view shows it as is). */
    internal fun onBox(card: Bitmap, boxW: Int, boxH: Int): Bitmap {
        if (boxW <= 0 || boxH <= 0) return card
        val f = fit(boxW, boxH, card.width, card.height)
        val out = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(
            card, null,
            android.graphics.RectF(f.dx, f.dy, f.dx + card.width * f.scale, f.dy + card.height * f.scale),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /** Lays the whole-card tap target over the card itself (not the clear room around it). */
    internal fun placeCardTarget(views: RemoteViews, f: Fit, cardW: Int, cardH: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        views.setViewLayoutWidth(R.id.card_open, cardW * f.scale, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutHeight(R.id.card_open, cardH * f.scale, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutMargin(R.id.card_open, RemoteViews.MARGIN_LEFT, f.dx, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutMargin(R.id.card_open, RemoteViews.MARGIN_TOP, f.dy, TypedValue.COMPLEX_UNIT_PX)
    }

    /** The round "next" button in the fitted card's top right corner, or hidden. */
    internal fun placeNext(ctx: Context, views: RemoteViews, f: Fit?, cardW: Int, onTap: PendingIntent?) {
        if (f == null || onTap == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            views.setViewVisibility(R.id.card_next, android.view.View.GONE)
            return
        }
        val inset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, ctx.resources.displayMetrics)
        val side = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, ctx.resources.displayMetrics)
        views.setViewVisibility(R.id.card_next, android.view.View.VISIBLE)
        views.setViewLayoutMargin(R.id.card_next, RemoteViews.MARGIN_LEFT, f.dx + cardW * f.scale - inset - side, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutMargin(R.id.card_next, RemoteViews.MARGIN_TOP, f.dy + inset, TypedValue.COMPLEX_UNIT_PX)
        views.setOnClickPendingIntent(R.id.card_next, onTap)
    }

    internal val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6, R.id.row7)

    /** A row target laid over [r] (card pixels) of the fitted card. */
    internal fun placeRow(views: RemoteViews, rowId: Int, f: Fit, r: android.graphics.RectF) {
        views.setViewVisibility(rowId, android.view.View.VISIBLE)
        views.setViewLayoutWidth(rowId, r.width() * f.scale, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutHeight(rowId, r.height() * f.scale, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutMargin(rowId, RemoteViews.MARGIN_LEFT, f.dx + r.left * f.scale, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutMargin(rowId, RemoteViews.MARGIN_TOP, f.dy + r.top * f.scale, TypedValue.COMPLEX_UNIT_PX)
    }

    // ---- Recent and Starred: drawn by the app (its thumbnails live there), kept for when it is away ----

    /** One of the two document widgets: which files it shows and where its picture is kept. */
    internal enum class Shelf(val provider: Class<out AppWidgetProvider>, val file: String) {
        Recent(RecentWidget::class.java, "widget_recent"),
        Starred(StarredWidget::class.java, "widget_starred"),
    }

    private fun picture(ctx: Context, shelf: Shelf) = File(ctx.cacheDir, "${shelf.file}.png")

    private fun topFile(ctx: Context, shelf: Shelf) = File(ctx.cacheDir, "${shelf.file}_top.txt")

    /** The app's view model while it runs: the document widgets' thumbnails come from it. */
    @Volatile
    internal var liveVm: java.lang.ref.WeakReference<AppViewModel>? = null

    private val shelfSig = java.util.concurrent.ConcurrentHashMap<Shelf, String>()

    private fun rowsOf(vm: AppViewModel, shelf: Shelf) = when (shelf) {
        Shelf.Recent -> vm.recentRows()
        Shelf.Starred -> vm.starredRows()
    }

    /** The app's documents changed (or a Recent / Starred widget was resized): draw those widgets again if what they show changed. */
    fun refreshRecent(vm: AppViewModel, force: Boolean = false) {
        liveVm = java.lang.ref.WeakReference(vm)
        val app = vm.getApplication<android.app.Application>()
        val mgr = AppWidgetManager.getInstance(app)
        for (shelf in Shelf.entries) {
            val ids = ids(app, mgr, shelf.provider)
            if (ids.isEmpty()) continue
            // only when its list really changed (every return to the app asks), and not during the
            // moment the app is coming back, when its own screens are animating
            val rows = rowsOf(vm, shelf)
            val sig = rows.take(3).joinToString("|") { it.key + it.date } + "#" + rows.size + "#" + ids.joinToString(",") { id -> size(app, mgr.getAppWidgetOptions(id)).toString() }
            if (!force && sig == shelfSig[shelf]) continue
            shelfSig[shelf] = sig
            val job = shelfJobs.getOrPut(shelf) { Runnable { liveVm?.get()?.let { draw(it, shelf) } } }
            main.removeCallbacks(job)
            main.postDelayed(job, if (force) 450L else 1200L)
        }
    }

    private val shelfJobs = java.util.concurrent.ConcurrentHashMap<Shelf, Runnable>()

    private fun draw(vm: AppViewModel, shelf: Shelf) {
        val app = vm.getApplication<android.app.Application>()
        val mgr = AppWidgetManager.getInstance(app)
        val ids = ids(app, mgr, shelf.provider)
        if (ids.isEmpty()) return
        val rows = rowsOf(vm, shelf)
        val top = rows.firstOrNull()?.key
        if (rows.isEmpty() && shelf == Shelf.Recent) {
            picture(app, shelf).delete()
            ids.forEach { showDoc(app, mgr, it, shelf, null, null) }
            return
        }
        // one picture serves every widget of the shelf: the desk's card at its desk size, fitted to each
        enqueue(shelf.name) { next ->
            GlassSettings.load(app)
            val choice = themeChoice(app)
            val dm = app.resources.displayMetrics
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
            val deskW = deskWidth(app)
            val deskWPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, deskW.value, dm).toInt()
            val deskHPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 226f, dm).toInt()
            val starredTitle = app.getString(R.string.home_list_starred)
            ComposeSnapshot.render(
                app, deskWPx + 2 * pad, deskHPx + 2 * pad, 0f, settleMs = 900,
                content = {
                    ClearTheme(choice) {
                        CompositionLocalProvider(LocalHomeVm provides vm) {
                            Box(Modifier.fillMaxSize().padding(toDp(app, pad))) {
                                when {
                                    shelf == Shelf.Recent -> RecentCard(vm, rows, onAll = {}, modifier = Modifier.width(deskW))
                                    rows.isEmpty() -> StarredEmptyCard(Modifier.width(deskW))
                                    else -> RecentCard(vm, rows, onAll = {}, modifier = Modifier.width(deskW), title = starredTitle, cardTone = Tone.Lavender)
                                }
                            }
                        }
                    }
                },
            ) { frame ->
                if (frame != null) {
                    runCatching { picture(app, shelf).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    runCatching { if (top != null) topFile(app, shelf).writeText(top) else topFile(app, shelf).delete() }
                    ids(app, mgr, shelf.provider).forEach { showDoc(app, mgr, it, shelf, frame, top) }
                }
                next()
            }
        }
    }

    internal fun saved(ctx: Context, shelf: Shelf): Pair<Bitmap?, String?> =
        (runCatching { BitmapFactory.decodeFile(picture(ctx, shelf).path) }.getOrNull()) to runCatching { topFile(ctx, shelf).readText() }.getOrNull()?.ifBlank { null }

    /** A Recent or Starred widget showing [bitmap]: a tap opens Files on Recent or Starred. */
    internal fun showDoc(ctx: Context, mgr: AppWidgetManager, id: Int, shelf: Shelf, bitmap: Bitmap?, top: String?) {
        val app = ctx.applicationContext
        val views = RemoteViews(app.packageName, R.layout.widget_card)
        val (w, h) = size(app, mgr.getAppWidgetOptions(id))
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.card_image, onBox(bitmap, w, h))
            placeCardTarget(views, fit(w, h, bitmap.width, bitmap.height), bitmap.width, bitmap.height)
        }
        views.setContentDescription(R.id.card_image, app.getString(if (shelf == Shelf.Recent) R.string.desk_recent else R.string.home_list_starred))
        views.setOnClickPendingIntent(
            R.id.card_open,
            // the card opens its own list in Files (Recent or Starred), never a document by itself
            activity(app, "${shelf.file}/$id") { if (shelf == Shelf.Starred) putExtra(EXTRA_OPEN_STARRED, true) else putExtra(EXTRA_OPEN_FILES, true) },
        )
        placeNext(app, views, null, 0, null)
        ROWS.forEach { views.setViewVisibility(it, android.view.View.GONE) }
        runCatching { mgr.updateAppWidget(id, views) }
    }
}

/**
 * A list of the user's (the one chosen for it, else the latest), the desk's own `ListCard`, true
 * liquid glass and all, its items ticked right on the home screen. With more than one list, the round
 * button in its corner turns to the next.
 *
 * A tick is instant without a native copy of the card: every render draws the card twice, as it is
 * and with every item flipped, and keeps where each pill lies. A tap copies that one pill from the
 * flipped picture into the shown one (a few milliseconds of canvas work) and shows it; the two
 * pictures are then each other's flip again, exactly what a new render would draw, so nothing is
 * rendered after a tap at all. They are kept on disk too, so a tap is as quick when the app is gone.
 */
class TodoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        var left = ids.size
        if (left == 0) return pending.finish()
        ids.forEach { Widgets.forget(it); update(context, manager, it) { if (--left == 0) pending.finish() } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        // at once: the card as it is, scaled whole to the new size; drawn anew once the size settles
        val app = context.applicationContext
        drawn(app, id)?.let { show(app, manager, id, it) }
        val job = resizes.getOrPut(id) { Runnable { update(app, AppWidgetManager.getInstance(app), id) } }
        main.removeCallbacks(job)
        main.postDelayed(job, 350L)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            WidgetPicks.set(context, id, null)
            Widgets.forget(id)
            cache.remove(id)
            files(context, id).forEach { it.delete() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_TOGGLE -> {
                val note = intent.getStringExtra(EXTRA_NOTE) ?: return
                val item = intent.getStringExtra(EXTRA_ITEM) ?: return
                GlassSettings.load(app)
                val wasDone = drawnShowing(app, note).firstOrNull()?.note?.items?.firstOrNull { it.id == item }?.done
                    ?: (NoteFile.read(app).firstOrNull { it.id == note }?.items?.firstOrNull { it.id == item }?.done == true)
                runCatching { Haptics(app, android.view.View(app)).play(if (wasDone) Feel.ToggleOff else Feel.ToggleOn) }
                // first what is seen: the pill flips on the home screen now
                val flipped = flipNow(app, note, item)
                // then the truth: the file, and the app's own notes if it runs (so it never writes over the tick)
                NoteFile.toggle(app, note, item)
                Notes.live?.takeIf { it.isActive && it.loaded }?.let { live -> Handler(Looper.getMainLooper()).post { live.reload() } }
                if (!flipped) {
                    // no picture to flip in (a first tap after an update): drawn anew
                    val mgr = AppWidgetManager.getInstance(app)
                    Widgets.ids(app, mgr, TodoWidget::class.java).filter { WidgetPicks.resolve(app, it, NoteKind.List)?.id == note }.forEach { update(app, mgr, it) }
                }
                return
            }
            ACTION_NEXT -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                GlassSettings.load(app)
                runCatching { Haptics(app, android.view.View(app)).play(Feel.Tick) }
                WidgetPicks.next(app, id, NoteKind.List)
                val pending = goAsync()
                update(app, AppWidgetManager.getInstance(app), id) { pending.finish() }
                return
            }
        }
        super.onReceive(context, intent)
    }

    /** The card as last drawn for a widget: the picture shown, its every-item-flipped twin, and where each pill is. */
    internal class Drawn(val note: Note, val shown: Bitmap, val twin: Bitmap?, val spots: Map<String, android.graphics.RectF>)

    companion object {
        const val ACTION_TOGGLE = "com.chethan616.clearpdf.widget.TOGGLE"
        const val ACTION_NEXT = "com.chethan616.clearpdf.widget.NEXT_LIST"
        private const val EXTRA_NOTE = "note"
        private const val EXTRA_ITEM = "item"
        private val main = Handler(Looper.getMainLooper())
        private val resizes = HashMap<Int, Runnable>()
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, Drawn>()

        /** Bring every list widget in line with the notes (after a change in the app): only those whose list changed are drawn. */
        fun syncAll(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            Widgets.ids(app, mgr, TodoWidget::class.java).forEach { update(app, mgr, it) }
        }

        private fun signature(app: Context, mgr: AppWidgetManager, id: Int, note: Note?): String =
            "${Widgets.size(app, mgr.getAppWidgetOptions(id))}|${WidgetCards.isDark(app)}|${note?.id}|${note?.title}|" +
                note?.items?.joinToString(",") { "${it.id}:${it.text.hashCode()}:${it.done}" } + "|" + WidgetPicks.count(app, NoteKind.List)

        private fun files(ctx: Context, id: Int) = listOf("shown.png", "twin.png", "spots.json").map { File(ctx.cacheDir, "widget_list_${id}_$it") }

        /** The card last drawn for widget [id]: from memory, else from the cache folder (the app was away). */
        private fun drawn(app: Context, id: Int): Drawn? = cache[id] ?: runCatching {
            val (shownF, twinF, spotsF) = files(app, id)
            if (!shownF.isFile || !spotsF.isFile) return null
            val o = org.json.JSONObject(spotsF.readText())
            val note = NoteFile.read(app).firstOrNull { it.id == o.optString("note") } ?: return null
            val spots = HashMap<String, android.graphics.RectF>()
            o.optJSONObject("spots")?.let { s ->
                s.keys().forEach { k -> s.optJSONArray(k)?.let { a -> spots[k] = android.graphics.RectF(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat(), a.optDouble(3).toFloat()) } }
            }
            val shown = BitmapFactory.decodeFile(shownF.path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val twin = BitmapFactory.decodeFile(twinF.path)?.copy(Bitmap.Config.ARGB_8888, true)
            // only while the file still says what the picture shows (a change in the app since then: no)
            val doneNow = note.items.associate { it.id to it.done }
            val doneThen = o.optJSONObject("done")
            if (doneThen == null || doneNow.any { (k, v) -> doneThen.has(k) && doneThen.optBoolean(k) != v }) return null
            Drawn(note, shown, twin, spots).also { cache[id] = it }
        }.getOrNull()

        private fun drawnShowing(app: Context, noteId: String): List<Drawn> {
            val mgr = AppWidgetManager.getInstance(app)
            return Widgets.ids(app, mgr, TodoWidget::class.java).toList().mapNotNull { id -> drawn(app, id)?.takeIf { it.note.id == noteId } }
        }

        private fun save(app: Context, id: Int, d: Drawn) {
            Thread {
                runCatching {
                    val (shownF, twinF, spotsF) = files(app, id)
                    shownF.outputStream().use { d.shown.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (d.twin != null) twinF.outputStream().use { d.twin.compress(Bitmap.CompressFormat.PNG, 100, it) } else twinF.delete()
                    val spots = org.json.JSONObject()
                    d.spots.forEach { (k, r) -> spots.put(k, org.json.JSONArray(listOf(r.left, r.top, r.right, r.bottom))) }
                    val done = org.json.JSONObject()
                    d.note.items.forEach { done.put(it.id, it.done) }
                    spotsF.writeText(org.json.JSONObject().put("note", d.note.id).put("spots", spots).put("done", done).toString())
                }
            }.start()
        }

        /**
         * Item [itemId] of list [noteId] ticked or unticked on every widget showing it, at once: its pill
         * taken from the flipped picture (and the old one put in the flipped picture's place). False when
         * no widget had pictures to do it with.
         */
        fun flipNow(ctx: Context, noteId: String, itemId: String): Boolean {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            var any = false
            Widgets.ids(app, mgr, TodoWidget::class.java).forEach { id ->
                val d = drawn(app, id)?.takeIf { it.note.id == noteId } ?: return@forEach
                val twin = d.twin ?: return@forEach
                val note = d.note.copy(items = d.note.items.map { if (it.id == itemId) it.copy(done = !it.done) else it })
                val spot = d.spots[itemId]
                val (shown, other) = if (spot == null) d.shown to twin else {
                    // a pixel or two round the pill: its rim and the shade it casts on the card
                    val r = android.graphics.Rect(spot.left.toInt() - 2, spot.top.toInt() - 2, spot.right.toInt() + 3, spot.bottom.toInt() + 3)
                        .apply { intersect(0, 0, d.shown.width, d.shown.height) }
                    val newShown = d.shown.copy(Bitmap.Config.ARGB_8888, true)
                    val newTwin = twin.copy(Bitmap.Config.ARGB_8888, true)
                    val plain = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
                    android.graphics.Canvas(newShown).drawBitmap(twin, r, r, plain)
                    android.graphics.Canvas(newTwin).drawBitmap(d.shown, r, r, plain)
                    newShown to newTwin
                }
                val next = Drawn(note, shown, other, d.spots)
                cache[id] = next
                show(app, mgr, id, next)
                // what the widget shows is now this list as it will be: no render needed when the notes catch up
                Widgets.showing(id, signature(app, mgr, id, note))
                save(app, id, next)
                any = true
            }
            return any
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            // the list chosen for this widget, else the latest
            val list = WidgetPicks.resolve(app, id, NoteKind.List)
            val sig = signature(app, mgr, id, list)
            if (Widgets.isShowing(id, sig)) return done()
            val res = app.resources
            val shown = list ?: Note("", NoteKind.List)
            val flipped = shown.copy(items = shown.items.map { it.copy(done = !it.done) })
            Widgets.enqueue("list/$id") { next ->
                GlassSettings.load(app)
                val choice = Widgets.themeChoice(app)
                // the desk's own list card, exactly as the desk lays it out (its width, its own height, its
                // first four items and "N more"); the widget fits that very picture to its size
                val deskW = Widgets.deskWidth(app)
                val deskWPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, deskW.value, res.displayMetrics).toInt()
                fun draw(note: Note, then: (Bitmap?, Map<String, android.graphics.RectF>) -> Unit) {
                    val placed = LinkedHashMap<String, androidx.compose.ui.geometry.Rect>()
                    var card: android.graphics.Rect? = null
                    ComposeSnapshot.render(app, deskWPx, deskWPx * 3, Widgets.cornerPx(app), crop = { card }, content = {
                        Widgets.Desk(choice, placed = { card = it }) {
                            ListCard(note, onOpen = {}, onLong = {}, onToggle = {}, modifier = Modifier.width(deskW), onItemPlaced = { item, r -> placed[item.id] = r })
                        }
                    }) { frame ->
                        val top = card?.top ?: 0
                        then(frame, placed.mapValues { (_, r) -> android.graphics.RectF(r.left, r.top - top, r.right, r.bottom - top) })
                    }
                }
                draw(shown) { frame, spots ->
                    if (frame == null) {
                        // a render that did not come back keeps the card already there
                        drawn(app, id)?.let { show(app, mgr, id, it) } ?: run {
                            val (w, h) = Widgets.size(app, mgr.getAppWidgetOptions(id))
                            val bitmap = WidgetCards(app, w, h).list(
                                shown, res.getString(R.string.notes_untitled_list), res.getString(R.string.notes_list_empty),
                                moreText = { res.getQuantityString(R.plurals.notes_more_items, it, it) },
                            ).bitmap
                            show(app, mgr, id, Drawn(shown, bitmap, null, emptyMap()))
                        }
                        next()
                        done()
                        return@draw
                    }
                    // shown at once; its flipped twin is drawn after, for the taps
                    val d = Drawn(shown, frame.copy(Bitmap.Config.ARGB_8888, true), null, if (list != null) spots else emptyMap())
                    cache[id] = d
                    show(app, mgr, id, d)
                    Widgets.showing(id, sig)
                    if (list == null || shown.items.none { it.text.isNotBlank() }) {
                        next()
                        done()
                        return@draw
                    }
                    draw(flipped) { twin, _ ->
                        if (twin != null && twin.width == frame.width && twin.height == frame.height) {
                            val full = Drawn(shown, d.shown, twin.copy(Bitmap.Config.ARGB_8888, true), d.spots)
                            if (cache[id] === d) cache[id] = full
                            save(app, id, full)
                        }
                        next()
                        done()
                    }
                }
            }
        }

        /** Widget [id] showing [d]: the card fitted to the widget, a tap target over each of its pills. */
        private fun show(app: Context, mgr: AppWidgetManager, id: Int, d: Drawn) {
            val res = app.resources
            val (w, h) = Widgets.size(app, mgr.getAppWidgetOptions(id))
            val bitmap = d.shown
            val f = Widgets.fit(w, h, bitmap.width, bitmap.height)
            val views = RemoteViews(app.packageName, R.layout.widget_card)
            views.setImageViewBitmap(R.id.card_image, Widgets.onBox(bitmap, w, h))
            views.setContentDescription(R.id.card_image, d.note.title.ifBlank { res.getString(R.string.notes_untitled_list) })
            views.setOnClickPendingIntent(R.id.card_open, Widgets.open(app, d.note.id.ifEmpty { null }, "list/$id"))
            Widgets.placeCardTarget(views, f, bitmap.width, bitmap.height)
            val spots = d.note.items.filter { it.id in d.spots }.map { it.id to d.spots.getValue(it.id) }
            Widgets.ROWS.forEachIndexed { i, rowId ->
                val spot = spots.getOrNull(i)
                if (spot == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    // before Android 12 the targets can't be placed: the whole card opens the list
                    views.setViewVisibility(rowId, android.view.View.GONE)
                    return@forEachIndexed
                }
                val (itemId, r) = spot
                Widgets.placeRow(views, rowId, f, r)
                val toggle = Intent(app, TodoWidget::class.java).setAction(ACTION_TOGGLE)
                    .setData(Uri.parse("artefact://widget/toggle/${d.note.id}/$itemId"))
                    .putExtra(EXTRA_NOTE, d.note.id).putExtra(EXTRA_ITEM, itemId)
                views.setOnClickPendingIntent(
                    rowId,
                    PendingIntent.getBroadcast(app, "${d.note.id}/$itemId".hashCode(), toggle, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            // more than one list: the round button in the corner turns to the next
            val many = WidgetPicks.count(app, NoteKind.List) > 1
            Widgets.placeNext(app, views, f, bitmap.width, if (many) Widgets.broadcast(app, TodoWidget::class.java, ACTION_NEXT, id) else null)
            if (many) {
                views.setContentDescription(R.id.card_next, res.getString(R.string.widget_next_list))
                views.setInt(R.id.card_next, "setColorFilter", if (WidgetCards.isDark(app)) 0xF0FFFFFF.toInt() else 0xCC12302B.toInt())
            }
            runCatching { mgr.updateAppWidget(id, views) }
        }
    }
}

/**
 * A note as the desk's paper card (the one chosen for it, else the latest; the starter "Thought of the
 * day" on a fresh install). With more than one note, the round button in its corner turns to the next.
 */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        var left = ids.size
        if (left == 0) return pending.finish()
        ids.forEach { Widgets.forget(it); update(context, manager, it) { if (--left == 0) pending.finish() } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        // at once: the last picture, scaled whole to the new size; the card itself once the size settles
        val app = context.applicationContext
        last[id]?.let { show(app, manager, id, it.first, it.second) }
        val job = resizes.getOrPut(id) { Runnable { update(app, AppWidgetManager.getInstance(app), id) } }
        main.removeCallbacks(job)
        main.postDelayed(job, 350L)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPicks.set(context, it, null); Widgets.forget(it); last.remove(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT) {
            val app = context.applicationContext
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            GlassSettings.load(app)
            runCatching { Haptics(app, android.view.View(app)).play(Feel.Tick) }
            WidgetPicks.next(app, id, NoteKind.Text)
            val pending = goAsync()
            update(app, AppWidgetManager.getInstance(app), id) { pending.finish() }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_NEXT = "com.chethan616.clearpdf.widget.NEXT_NOTE"
        private val main = Handler(Looper.getMainLooper())
        private val resizes = HashMap<Int, Runnable>()

        /** The last picture each widget showed and the note it opens: shown at once while a new one draws. */
        private val last = java.util.concurrent.ConcurrentHashMap<Int, Pair<Bitmap, String?>>()

        private fun signature(app: Context, mgr: AppWidgetManager, id: Int, note: Note?): String =
            "${Widgets.size(app, mgr.getAppWidgetOptions(id))}|${WidgetCards.isDark(app)}|${note?.id}|${note?.title}|${note?.body?.hashCode()}|${note?.images}|${note?.updated}|" +
                WidgetPicks.count(app, NoteKind.Text)

        /** Redraw the note widgets whose note changed (after a change in the app). */
        fun refreshChanged(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            Widgets.ids(app, mgr, NoteWidget::class.java).forEach { update(app, mgr, it) }
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            val (w, h) = Widgets.size(app, mgr.getAppWidgetOptions(id))
            // the note chosen for this widget, else the latest
            val note = WidgetPicks.resolve(app, id, NoteKind.Text)
            val sig = signature(app, mgr, id, note)
            if (Widgets.isShowing(id, sig)) return done()
            val res = app.resources
            val shown = note ?: Note("", NoteKind.Text, title = res.getString(R.string.thought_title), body = res.getString(R.string.seed_note_body))
            Widgets.enqueue("note/$id") { next ->
                GlassSettings.load(app)
                val choice = Widgets.themeChoice(app)
                val side = minOf(w, h)
                val sideDp = Widgets.toDp(app, side)
                ComposeSnapshot.render(app, side, side, Widgets.cornerPx(app), content = {
                    ClearTheme(choice) {
                        Box(Modifier.size(sideDp).clip(CardShape).background(Brush.verticalGradient(clear.wall))) {
                            NoteCard(shown, onOpen = {}, onLong = {}, modifier = Modifier.fillMaxSize(), fitHeight = true)
                        }
                    }
                }) { frame ->
                    // a render that did not come back keeps the picture already there (never a different-looking stand-in)
                    val bitmap = frame ?: last[id]?.first ?: WidgetCards(app, w, h).note(shown, res.getString(R.string.notes_untitled_note)) { Widgets.mediaFile(app, it) }
                    last[id] = bitmap to note?.id
                    show(app, mgr, id, bitmap, note?.id)
                    if (frame != null) Widgets.showing(id, sig)
                    next()
                    done()
                }
            }
        }

        private fun show(app: Context, mgr: AppWidgetManager, id: Int, bitmap: Bitmap, noteId: String?) {
            val res = app.resources
            val (w, h) = Widgets.size(app, mgr.getAppWidgetOptions(id))
            val f = Widgets.fit(w, h, bitmap.width, bitmap.height)
            val views = RemoteViews(app.packageName, R.layout.widget_card)
            views.setImageViewBitmap(R.id.card_image, Widgets.onBox(bitmap, w, h))
            views.setContentDescription(R.id.card_image, res.getString(R.string.widget_note_name))
            views.setOnClickPendingIntent(R.id.card_open, Widgets.open(app, noteId, "note/$id"))
            Widgets.placeCardTarget(views, f, bitmap.width, bitmap.height)
            val many = WidgetPicks.count(app, NoteKind.Text) > 1
            Widgets.placeNext(app, views, f, bitmap.width, if (many) Widgets.broadcast(app, NoteWidget::class.java, ACTION_NEXT, id) else null)
            if (many) {
                views.setContentDescription(R.id.card_next, res.getString(R.string.widget_next_note))
                // the paper card's own ink: dark on the light card, white on the dark one
                views.setInt(R.id.card_next, "setColorFilter", if (WidgetCards.isDark(app)) 0xF0FFFFFF.toInt() else 0xCC3A3420.toInt())
            }
            Widgets.ROWS.forEach { views.setViewVisibility(it, android.view.View.GONE) }
            runCatching { mgr.updateAppWidget(id, views) }
        }
    }
}

/** The desk's Recent card: the newest documents' first pages fanned, opening Files ▸ Recent. */
class RecentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = docUpdate(context, manager, ids, Widgets.Shelf.Recent)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        docResized(context, manager, id, Widgets.Shelf.Recent)
}

/** Starred: the starred documents as the Recent card shows its own, opening Files ▸ Starred. */
class StarredWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = docUpdate(context, manager, ids, Widgets.Shelf.Starred)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        docResized(context, manager, id, Widgets.Shelf.Starred)
}

private fun docUpdate(context: Context, manager: AppWidgetManager, ids: IntArray, shelf: Widgets.Shelf) {
    val (bitmap, top) = Widgets.saved(context, shelf)
    ids.forEach { Widgets.showDoc(context, manager, it, shelf, bitmap, top) }
    Widgets.liveVm?.get()?.let { vm -> Handler(Looper.getMainLooper()).post { Widgets.refreshRecent(vm, force = true) } }
}

private fun docResized(context: Context, manager: AppWidgetManager, id: Int, shelf: Widgets.Shelf) {
    // at once: the last picture scaled whole to the new size (the same card at every size)
    val (bitmap, top) = Widgets.saved(context, shelf)
    Widgets.showDoc(context, manager, id, shelf, bitmap, top)
}
