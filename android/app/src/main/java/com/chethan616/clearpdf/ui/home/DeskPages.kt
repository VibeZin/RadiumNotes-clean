package com.chethan616.clearpdf.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.notes.Note
import com.chethan616.clearpdf.notes.NoteKind
import com.chethan616.clearpdf.ui.AppViewModel
import com.chethan616.clearpdf.ui.LucideIcons
import com.chethan616.clearpdf.ui.glass.GlassBackButton
import com.chethan616.clearpdf.ui.glass.GlassCircle
import com.chethan616.clearpdf.ui.glass.GlassIcon
import com.chethan616.clearpdf.ui.glass.GlassMenuItem
import com.chethan616.clearpdf.ui.glass.GlassMotion
import com.chethan616.clearpdf.ui.glass.GlassPill
import com.chethan616.clearpdf.ui.glass.MorphButton
import com.chethan616.clearpdf.ui.glass.RoundedRectangle
import com.chethan616.clearpdf.ui.glass.pressable
import com.chethan616.clearpdf.ui.theme.Candy
import com.chethan616.clearpdf.ui.theme.ClearType
import com.chethan616.clearpdf.ui.theme.Tone
import com.chethan616.clearpdf.ui.theme.clear
import com.chethan616.clearpdf.ui.theme.glowing
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** A page of the desk's own besides the bento: the day's greeting, and the thoughts as a deck of cards. */
enum class DeskPage { Today, Thoughts }

/** A picture in a note, with the note it is in (the desk's Highlights). */
data class NotePicture(val note: Note, val name: String)

/** Every picture in the notes, the newest note's first. */
fun notePictures(notes: List<Note>): List<NotePicture> =
    notes.filter { it.images.isNotEmpty() }.sortedByDescending { it.updated }.flatMap { n -> n.images.map { NotePicture(n, it) } }

/** The documents made with Scan ("Scan 2026-…"), newest first: the desk's Snapshots. */
fun scanRows(rows: List<FileRow>): List<FileRow> = rows.filter { it.name.startsWith("Scan ", ignoreCase = true) }

// ---- the desk's two picture cards -----------------------------------------------------------------

/** Highlights: the pictures in the notes, the newest filling the card, "+N" and Photos to see them all. */
@Composable
fun HighlightsCard(pictures: List<NotePicture>, onOpen: () -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val first = pictures.first()
    StackCard(
        stringResource(R.string.desk_highlights), shortDate(first.note.updated),
        listOf(clear.tone(Tone.Blush), clear.tone(Tone.Peach)), onOpen, modifier, height = 214.dp,
        chips = {
            Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pictures.size > 1) PhotoChip("+${pictures.size - 1}", onAll)
                PhotoChip(stringResource(R.string.home_photos), onAll, icon = LucideIcons.Images)
            }
        },
    ) { NoteImage(first.name, Modifier.matchParentSize(), maxPx = 900) }
}

/** Snapshots: the documents made with Scan, the newest page filling the card, "+N" in the corner. */
@Composable
fun SnapshotsCard(vm: AppViewModel, scans: List<FileRow>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val first = scans.first()
    StackCard(
        stringResource(R.string.desk_snapshots), whenLabel(first.date),
        listOf(clear.tone(Tone.Butter)), onOpen, modifier, height = 196.dp,
        chips = { if (scans.size > 1) PhotoChip("+${scans.size - 1}", onOpen, Modifier.align(Alignment.BottomEnd).padding(12.dp)) },
    ) { PageFill(vm, first.thumbPath) }
}

// ---- The mock's measures ---------------------------------------------------------------------------

/*
 * Thought of the day and Today are built to the design mock (needed.png) one to one. Every measure
 * below is read off the mock in its own pixels (each phone screen there is 491 px wide, measured
 * from its bezel; positions are from the screen's top-left corner) and scaled to this screen's width,
 * so on the 354 dp phone 1 mock px is 0.721 dp. Type sizes were fitted to the mock's own glyphs with
 * Outfit (its face), weight by weight.
 */

/** Mock pixels to dp on this screen. */
@Composable
private fun mockScale(): Float = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / MockWidth

private const val MockWidth = 491f

/** The scale the type sizes below were fitted at (354 dp over 491 mock px). */
private const val FitScale = 354f / MockWidth

/** [px] mock pixels as dp at scale [k]. */
private fun m(px: Number, k: Float): androidx.compose.ui.unit.Dp = (px.toFloat() * k).dp

/** A fitted type size (sp at the fitting scale) at this screen's scale. */
private fun s(sp: Float, k: Float) = (sp * k / FitScale).sp

/** Outfit at [weight], [size], [tracking] (em) and [line] height. */
private fun outfit(weight: Int, size: androidx.compose.ui.unit.TextUnit, tracking: Float = 0f, line: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = com.chethan616.clearpdf.ui.theme.Outfit, fontWeight = androidx.compose.ui.text.font.FontWeight(weight), fontSize = size,
        letterSpacing = (tracking * size.value).sp, lineHeight = line,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None),
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )

/** Places this text so its first baseline is at [y] (and its left edge at [x], when given) in its parent. */
private fun Modifier.baselineAt(x: androidx.compose.ui.unit.Dp?, y: androidx.compose.ui.unit.Dp) = layout { measurable, c ->
    val p = measurable.measure(c)
    val b = p[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height
    layout(p.width, p.height) { p.place(x?.roundToPx() ?: 0, y.roundToPx() - b) }
}

/**
 * Children stacked by baselines, as the mock spaces them: each text's first baseline lands [gaps]`[i]`
 * below the last baseline (or the bottom, for a picture) of the one before; [after] closes it.
 */
@Composable
private fun BaselineStack(gaps: List<androidx.compose.ui.unit.Dp>, after: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    androidx.compose.ui.layout.Layout(content, modifier) { ms, c ->
        val ps = ms.map { it.measure(c.copy(minHeight = 0)) }
        var y = 0
        val ys = IntArray(ps.size)
        ps.forEachIndexed { i, p ->
            val fb = p[androidx.compose.ui.layout.FirstBaseline]
            val lb = p[androidx.compose.ui.layout.LastBaseline]
            val gap = gaps.getOrElse(i) { 0.dp }.roundToPx()
            if (fb != androidx.compose.ui.layout.AlignmentLine.Unspecified) {
                ys[i] = y + gap - fb
                y = ys[i] + lb
            } else {
                ys[i] = y + gap
                y = ys[i] + p.height
            }
        }
        layout(c.maxWidth, y + after.roundToPx()) { ps.forEachIndexed { i, p -> p.place(0, ys[i]) } }
    }

/**
 * The mock's soft fade of a title or a card's last lines into blur: sharp above [from] (a share of its
 * height), more and more blurred below, by [blur] at the bottom.
 */
@Composable
private fun FadeIntoBlur(from: Float, blur: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Box(
            Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, from to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) { content() }
        Box(
            Modifier.graphicsLayer {
                val r = blur.toPx()
                renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal)
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, from to Color.Transparent, 1f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
        ) { content() }
    }
}

// ---- Thought of the day -----------------------------------------------------------------------------

/** What the thoughts deck shows: words, pictures or voice. */
private enum class ThoughtShelf(val label: Int) { Text(R.string.desk_text), Images(R.string.desk_filter_images), Audios(R.string.thoughts_audios) }

/**
 * Thought of the day (the mock's second screen): Back and More, the title large and white fading into
 * blur on its second line, Text / Images / Audios in clear glass, and the notes as a deck: the front
 * card pure white over a cream one and a pink one peeking out above it. Swipe the front card away
 * and the next comes forward; tap it, or its pencil, to open it. "Create new" sits on the deck's foot.
 */
@Composable
fun ThoughtsScreen(vm: AppViewModel, desk: DeskState) {
    val k = mockScale()
    var shelf by rememberSaveable { mutableStateOf(ThoughtShelf.Text) }
    val all = vm.notes.all
    val notes = remember(all, shelf) {
        when (shelf) {
            ThoughtShelf.Text -> all.filter { it.kind == NoteKind.Text && !it.isEmpty }
            ThoughtShelf.Images -> all.filter { it.images.isNotEmpty() }
            ThoughtShelf.Audios -> all.filter { it.kind == NoteKind.Voice }
        }.sortedWith(compareByDescending<Note> { it.thought != 0L }.thenByDescending { it.updated })
    }
    var front by rememberSaveable(shelf) { mutableIntStateOf(0) }
    // voice memos play right on the card, as on the desk
    val scope = rememberCoroutineScope()
    val player = remember { com.chethan616.clearpdf.ui.viewer.VoicePlayer(scope) }
    var playing by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.release() } }
    fun play(n: Note) {
        val f = n.audio?.let(vm.notes::file) ?: return
        if (playing == n.id) player.toggle() else if (player.load(f)) { playing = n.id; player.toggle() }
    }
    // a long press on the card: the desk's own note menu
    var actionsFor by remember { mutableStateOf<Note?>(null) }
    var actionsAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(m(1068, k))) {
            // Back and More: the desk's own header buttons, where the desk's header puts them
            DeskHeaderRow(
                leading = { GlassBackButton({ vm.closeDeskPage() }) },
                trailing = {
                    MorphButton(
                        items = listOf(
                            GlassMenuItem(stringResource(R.string.create_note), LucideIcons.SquarePen) { vm.newNote(NoteKind.Text) },
                            GlassMenuItem(stringResource(R.string.create_voice), LucideIcons.Mic) { desk.startRecording(vm) },
                            GlassMenuItem(stringResource(R.string.today_title), LucideIcons.Sun) { vm.openDeskPage(DeskPage.Today) },
                            GlassMenuItem(stringResource(R.string.home_settings), LucideIcons.Settings) { vm.settingsOpen = true },
                        ),
                        label = stringResource(R.string.common_more_options),
                    ) { GlassIcon(LucideIcons.Ellipsis, null, Modifier.size(20.dp)) }
                },
            )
            // the title: Outfit 500 at 55.5 (fitted), baselines at 239 and 323 px, its second line in blur
            FadeIntoBlur(0.5f, m(3.2f, k), Modifier.fillMaxWidth().baselineAt(null, m(239, k))) {
                Text(
                    stringResource(R.string.thought_title),
                    style = outfit(500, s(55.5f, k), -0.016f, (84 * k).sp).copy(color = Color.White, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = m(40, k)),
                )
            }
            // the shelves: the desk's own chips (the chosen one filled), centred on the mock's row (397 px)
            Row(
                Modifier.fillMaxWidth().offset(y = m(397, k) - 23.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                ThoughtShelf.entries.forEach { sh -> DeskChip(stringResource(sh.label), sh == shelf) { shelf = sh } }
            }
            // the deck from 452 px (the pink card's top), Create new on its foot
            Column(Modifier.fillMaxWidth().offset(y = m(452, k)), horizontalAlignment = Alignment.CenterHorizontally) {
                if (notes.isEmpty()) {
                    Text(
                        stringResource(R.string.thoughts_empty), style = outfit(400, s(12.9f, k)).copy(color = Color(0xFF2C1E3D), textAlign = TextAlign.Center),
                        modifier = Modifier.padding(top = m(60, k), bottom = m(60, k)).padding(horizontal = m(40, k)),
                    )
                } else {
                    ThoughtDeck(
                        vm, notes, front.coerceIn(0, notes.lastIndex), k,
                        playing = { n -> playing == n.id && player.playing },
                        progress = { n -> if (playing == n.id && player.durationMs > 0) player.positionMs / player.durationMs.toFloat() else -1f },
                        onPlay = ::play,
                        onLong = { n, at -> actionsAnchor = at; actionsFor = n },
                    ) { front = (front + 1) % notes.size }
                }
                CreateNewPill(k, Modifier.offset(y = -m(13, k))) {
                    when (shelf) {
                        ThoughtShelf.Text -> vm.newNote(NoteKind.Text)
                        ThoughtShelf.Images -> { vm.newNote(NoteKind.Text); vm.pickNotePhoto() }
                        // records here, in the dock, and the memo joins the deck
                        ThoughtShelf.Audios -> desk.startRecording(vm)
                    }
                }
            }
        }
    }
    actionsFor?.let { n -> NoteActions(vm, n, actionsAnchor, onRename = { desk.naming = n.id }) { actionsFor = null } }
    desk.naming?.let { id -> vm.notes.get(id)?.let { n -> NameSheet(vm, n) { desk.naming = null } } }
}

/**
 * The desk's header row, as [DeskHeader] lays it out (the grid's 16 dp edge plus its own 6 and 4 dp,
 * 14 dp under the status bar): [leading] on the left, [trailing] on the right, the desk's own sizes.
 */
@Composable
internal fun DeskHeaderRow(leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 14.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** The mock's "Create new": a white pill (190 × 56 px) in a slate glass ring (212 × 79 px). */
@Composable
private fun CreateNewPill(k: Float, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(m(212, k), m(79, k)).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF4E5E78).copy(alpha = 0.94f))
            .pressable(onClick, pressedScale = 0.94f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(m(190, k), m(56, k)).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White)) {
            // the plus: 20 px, 24 px in from the pill's left
            androidx.compose.foundation.Canvas(Modifier.offset(m(24, k), m(18, k)).size(m(20, k))) {
                val w = 2.4f * k.dp.toPx()
                drawLine(Color.Black, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.Black, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text(
                stringResource(R.string.thoughts_create_new), style = outfit(400, s(15.3f, k), -0.014f).copy(color = Color.Black),
                modifier = Modifier.baselineAt(m(57, k), m(32, k)),
            )
        }
    }
}

/**
 * The deck: the pink card (inset 64 px, its top at 0), the cream card (inset 29 px, 24 px lower) and
 * the white front card (inset 34 px... 457, 60 px lower), as the mock stacks them. The front card
 * follows a sideways drag, leans, and past a third of its width flies off; the next comes forward.
 */
@Composable
private fun ThoughtDeck(
    vm: AppViewModel,
    notes: List<Note>,
    front: Int,
    k: Float,
    playing: (Note) -> Boolean,
    progress: (Note) -> Float,
    onPlay: (Note) -> Unit,
    onLong: (Note, androidx.compose.ui.geometry.Rect) -> Unit,
    onNext: () -> Unit,
) {
    // the deck as the mock stacks it: up to three of the notes themselves (never a blank card), the
    // front one white, the next a step back (36 px up, 29 px in from each side) in cream, the one after
    // two steps back (60 px up, 64 px in) in pink, each further back softer and more blurred
    val shown = (0 until minOf(3, notes.size)).map { notes[(front + it) % notes.size] }
    var frontHeight by remember { mutableIntStateOf(0) }
    // the deck's height follows the front card's on a spring, so Create new glides as cards change
    BoxWithConstraints(Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.spring(1f, 500f)).padding(top = m(60, k))) {
        val width = constraints.maxWidth.toFloat()
        // back to front, so the front card is drawn last; each card keeps its own place (keyed by its
        // note) as it moves from depth to depth, so a card swiped away tucks in behind the others
        for (depth in shown.indices.reversed()) {
            val n = shown[depth]
            androidx.compose.runtime.key(n.id) {
                DeckCard(
                    vm, n, depth, shown.size, k, width, frontHeight,
                    playing(n), { progress(n) }, { onPlay(n) }, { at -> onLong(n, at) },
                    onMeasured = { if (depth == 0) frontHeight = it },
                    onSwipedAway = onNext,
                )
            }
        }
    }
}

/**
 * One card of the deck at [depth] (0 front). The front card follows a sideways drag and leans; let go
 * past a quarter of the width (or flung), it slides aside and goes to the back of the deck while the
 * next one comes forward, both on springs; short of that it springs back.
 */
@Composable
private fun DeckCard(
    vm: AppViewModel,
    n: Note,
    depth: Int,
    count: Int,
    k: Float,
    width: Float,
    frontHeight: Int,
    isPlaying: Boolean,
    progress: () -> Float,
    onPlay: () -> Unit,
    onLong: (androidx.compose.ui.geometry.Rect) -> Unit,
    onMeasured: (Int) -> Unit,
    onSwipedAway: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val feel = com.chethan616.clearpdf.ui.glass.rememberFeel()
    // where in the deck it is, gliding between depths (0 front … 2 back)
    // (no overshoot: a bounce here read as the card wobbling between places)
    val d by animateFloatAsState(depth.toFloat(), androidx.compose.animation.core.spring(0.9f, 420f), label = "deckDepth")
    // its own sideways travel: the drag, then the slide aside and home again behind the others
    val x = remember { Animatable(0f) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = m(34, k)
    val front = depth == 0
    // a card behind takes the front card's height, so nothing of it shows below the front one
    val sized = if (!front && frontHeight > 0) Modifier.height(with(density) { frontHeight.toDp() }) else Modifier
    Box(
        Modifier.padding(horizontal = inset).fillMaxWidth().then(sized)
            .graphicsLayer {
                // depth 0 → 1 → 2: 0 → 36 → 60 px up; full width → 365 / 423 → 295 / 423 of it
                val up = if (d <= 1f) 36f * d else 36f + 24f * (d - 1f)
                val sc = if (d <= 1f) 1f - 0.137f * d else 0.863f - 0.166f * (d - 1f)
                translationY = -up * k * density.density
                scaleX = sc
                scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                translationX = x.value
                rotationZ = x.value / width * 9f
            }
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .then(
                if (front && count > 1) Modifier.pointerInput(n.id) {
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x
                            scope.launch {
                                // the side it goes to is the side it was dragged to (a flick only decides
                                // when the card has hardly moved), so it never crosses over to the other side
                                val dragged = kotlin.math.abs(x.value) > 12f * density.density
                                val side = if (dragged) kotlin.math.sign(x.value) else kotlin.math.sign(v)
                                val flung = kotlin.math.abs(v) > 1400f && kotlin.math.sign(v) == side
                                if (side != 0f && (kotlin.math.abs(x.value) > width / 4f || flung)) {
                                    feel(com.chethan616.clearpdf.ui.glass.Feel.Tick)
                                    // one throw, no stop: flung out to that side, a single spring carries it out and
                                    // straight back home; at the far point (where it turns) it goes to the back of the
                                    // deck, so it comes home behind the others with no pause at the edge
                                    var tucked = false
                                    val throwV = side * maxOf(kotlin.math.abs(v), 9000f)
                                    x.animateTo(0f, androidx.compose.animation.core.spring(1f, 140f, 1f), initialVelocity = throwV) {
                                        if (!tucked && kotlin.math.sign(velocity) != side) {
                                            tucked = true
                                            onSwipedAway()
                                        }
                                    }
                                    if (!tucked) onSwipedAway()
                                } else {
                                    // not far enough: back to the front, settling without swinging past
                                    x.animateTo(0f, androidx.compose.animation.core.spring(1f, 500f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { x.animateTo(0f, GlassMotion.morph()) } },
                    ) { change, dx ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { x.snapTo(x.value + dx) }
                    }
                } else Modifier,
            )
            .onSizeChanged { if (front) onMeasured(it.height) },
    ) {
        if (n.kind == NoteKind.Voice) {
            // a voice memo is the desk's own voice card: its periwinkle, its play mark, its glowing bars
            VoiceCard(n, isPlaying, { progress().let { if (it < 0f) 0f else it } }, onPlay, { onLong(bounds) }, Modifier.fillMaxWidth())
        } else {
            FrontCard(vm, n, k, isPlaying, progress, onPlay, { onLong(bounds) }, Modifier.fillMaxWidth(), active = front)
        }
        // a card behind only shows: a touch on the part of it that peeks out does nothing
        if (!front) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        // further back: tinted (cream, then pink) and blurred, as the mock's cards behind
        if (d > 0.01f) {
            val tint = if (d <= 1f) Color(0xFFFBFCF0) else androidx.compose.ui.graphics.lerp(Color(0xFFFBFCF0), Color(0xFFFCEEFD), (d - 1f).coerceIn(0f, 1f))
            Box(
                Modifier.matchParentSize().clip(RoundedRectangle(m(28, k)))
                    .background(tint.copy(alpha = (0.62f * d).coerceAtMost(0.8f))),
            )
        }
    }
}

private fun titleOf(n: Note): String =
    n.title.ifBlank { n.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("# ").orEmpty() }

/** A note's words split as the mock's card sets them: a line or sentence to lead in blue, the rest under it. */
private fun leadAndRest(n: Note): Pair<String?, String> {
    val text = n.body.lineSequence().map { it.trim().removePrefix("# ").replace("**", "") }.filter { it.isNotBlank() }.toList()
    val lines = if (n.title.isBlank()) text.drop(1) else text
    if (lines.isEmpty()) return null to ""
    if (lines.size > 1) return lines.first() to lines.drop(1).joinToString(" ")
    // one paragraph: its first sentence leads
    val one = lines.first()
    val end = Regex("[.!?](\\s|$)").find(one)?.range?.first
    return if (end != null && end + 1 < one.length) one.substring(0, end + 1) to one.substring(end + 1).trim() else one to ""
}

/**
 * The front card, as the mock's "Morning Reflection": white, 28 px corners; its name (Outfit 600,
 * 13.7) on a baseline 47 px down and 25 px in, when (9.2, grey) 22 px under it, the pencil on the
 * right; the lead in blue (600, 18.9, lines 31 px apart) 56 px under that, the rest (400, 12.9, lines
 * 25 px apart) 43 px under the lead, then its pictures (129 px tall) 31 px under the text, 26 px of card below.
 */
@Composable
private fun FrontCard(
    vm: AppViewModel,
    n: Note,
    k: Float,
    isPlaying: Boolean,
    progress: () -> Float,
    onPlay: () -> Unit,
    onLong: () -> Unit,
    modifier: Modifier,
    /** The front card: it takes taps; the ones behind only show. */
    active: Boolean = true,
) {
    // a voice memo plays (or pauses) on a tap; anything else opens
    val open = { if (n.kind == NoteKind.Voice) onPlay() else vm.openNote = n.id }
    val (lead, rest) = leadAndRest(n)
    Box(modifier.clip(RoundedRectangle(m(28, k))).background(Color.White).pressable(open, onLong, pressedScale = 0.98f, enabled = active)) {
        val gaps = buildList {
            add(m(47, k)); add(m(22, k))
            if (lead != null) add(m(56, k))
            if (rest.isNotBlank()) add(m(if (lead != null) 43 else 56, k))
            if (n.images.isNotEmpty()) add(m(31, k))
        }
        BaselineStack(gaps, m(26, k), Modifier.padding(horizontal = m(25, k))) {
            Text(titleOf(n).ifBlank { stringResource(R.string.notes_untitled_note) }, style = outfit(600, s(13.7f, k), -0.03f).copy(color = Color.Black), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = m(60, k)))
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.updated)) +
                    (if (n.kind == NoteKind.Voice && n.durationMs > 0) "  ·  " + com.chethan616.clearpdf.ui.viewer.clock(n.durationMs) else ""),
                style = outfit(400, s(9.2f, k), -0.02f).copy(color = Color(0xFF525658)),
            )
            if (lead != null) Text(lead, style = outfit(600, s(18.9f, k), -0.005f, (31 * k).sp).copy(color = Color(0xFF2B649B)), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (rest.isNotBlank()) Text(rest, style = outfit(400, s(12.9f, k), 0f, (25 * k).sp).copy(color = Color.Black), maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (n.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(m(16, k))) {
                    NoteImage(n.images[0], Modifier.weight(if (n.images.size > 1) 226f else 1f).height(m(129, k)).clip(RoundedRectangle(m(20, k))))
                    if (n.images.size > 1) NoteImage(n.images[1], Modifier.weight(136f).height(m(129, k)).clip(RoundedRectangle(m(20, k))))
                }
            }
        }
        // the pencil: a 54 px white button with a soft shadow, 16 px in from the right, 28 px down
        if (n.kind != NoteKind.Voice) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = m(28, k), end = m(16, k)).size(m(54, k))
                    .graphicsLayer { shadowElevation = 6.dp.toPx(); shape = androidx.compose.foundation.shape.CircleShape; clip = true; ambientShadowColor = Color(0x22000000); spotShadowColor = Color(0x22000000) }
                    .background(Color.White).pressable(open, pressedScale = 0.9f, enabled = active),
                contentAlignment = Alignment.Center,
            ) { Icon(LucideIcons.PencilOutline, stringResource(R.string.notes_open), tint = Color(0xFF1A1A1A), modifier = Modifier.size(m(26, k))) }
        }
    }
}

// ---- Today ------------------------------------------------------------------------------------------------

/**
 * Today (the mock's third screen): the mark and the menu, the owner's name small above "Good Morning"
 * (afternoon, evening, night) large, translucent white and fading into blur, and three cards laid over
 * it: the day's thought turned left, the latest picture (Highlights) turned right over it, and the date
 * on frosted glass over that.
 */
@Composable
fun TodayScreen(vm: AppViewModel) {
    val k = mockScale()
    val notes = vm.notes.all
    val pictures = remember(notes) { notePictures(notes) }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val thought = remember(res) { com.chethan616.clearpdf.notes.thoughtFor(res) }
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.today_morning
            in 12..16 -> R.string.today_afternoon
            in 17..21 -> R.string.today_evening
            else -> R.string.today_night
        },
    )
    val name = vm.ownerName
    var naming by remember { mutableStateOf(false) }
    // the open to-dos across the lists, the longest-waiting list's first
    val todos = remember(notes) {
        notes.filter { it.kind == com.chethan616.clearpdf.notes.NoteKind.List }.sortedBy { it.updated }
            .flatMap { n -> n.items.filter { !it.done && it.text.isNotBlank() } }
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(m(1068, k))) {
            // the desk's own header: its mark (a tap goes back to the desk) and its menu button
            DeskHeaderRow(
                leading = {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(R.drawable.artefact_mark), stringResource(R.string.app_name),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(clear.hero),
                        modifier = Modifier.height(50.dp).width(56.dp).pressable({ vm.closeDeskPage() }, pressedScale = 0.94f),
                    )
                },
                trailing = {
                    MorphButton(
                        items = listOf(
                            GlassMenuItem(stringResource(R.string.thought_title), LucideIcons.Sparkles) { vm.openDeskPage(DeskPage.Thoughts) },
                            GlassMenuItem(stringResource(R.string.home_tab_files), LucideIcons.FolderOpen) { vm.closeDeskPage(); vm.homeTab = HomeTab.Files },
                            GlassMenuItem(stringResource(R.string.home_tab_tools), LucideIcons.LayoutGrid) { vm.closeDeskPage(); vm.homeTab = HomeTab.Tools },
                            GlassMenuItem(stringResource(R.string.home_settings), LucideIcons.Settings) { vm.settingsOpen = true },
                        ),
                        label = stringResource(R.string.common_more_options),
                    ) { FourDots(Color.Unspecified) }
                },
            )
            // the name: Outfit 300 at 15, its baseline at 178 px; a tap names it (faint "Your name" until then)
            Box(Modifier.fillMaxWidth().baselineAt(null, m(178, k)), contentAlignment = Alignment.Center) {
                Text(
                    name.ifBlank { stringResource(R.string.settings_your_name) }.uppercase(),
                    style = outfit(300, s(15f, k), 0.01f).copy(color = Color.White.copy(alpha = if (name.isBlank()) 0.55f else 1f), textAlign = TextAlign.Center),
                    modifier = Modifier.pressable({ naming = true }, pressedScale = 0.94f).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            // the greeting: Outfit 500 at 73, baselines 264 and 354 px, white at 62 %, fading into blur
            FadeIntoBlur(0.55f, m(2.6f, k), Modifier.fillMaxWidth().baselineAt(null, m(264, k)).graphicsLayer { alpha = 0.62f }) {
                Text(
                    greeting, style = outfit(500, s(73f, k), 0f, (90 * k).sp).copy(color = Color.White, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = m(20, k)),
                )
            }
            // the day's thought: a 205 × 200 px frosted card centred at (152, 440), turned 6° left
            Box(
                Modifier.offset(m(152 - 102.5f, k), m(440 - 100, k)).size(m(205, k), m(200, k))
                    .graphicsLayer { rotationZ = -6f }
                    .clip(RoundedRectangle(m(22, k))).background(Color(0xFFFFF6F6).copy(alpha = 0.86f))
                    .pressable({ vm.openDeskPage(DeskPage.Thoughts) }, pressedScale = 0.96f),
            ) {
                Text(stringResource(R.string.thought_title), style = outfit(500, s(13.4f, k), -0.01f).copy(color = Color(0xFF3B6593)), modifier = Modifier.baselineAt(m(17, k), m(42, k)))
                Text(shortDate(System.currentTimeMillis()), style = outfit(400, s(10f, k)).copy(color = Color(0xFF9D9591)), modifier = Modifier.baselineAt(m(17, k), m(66, k)))
                FadeIntoBlur(0.48f, m(3.5f, k), Modifier.padding(start = m(17, k), end = m(14, k)).baselineAt(null, m(112, k))) {
                    Text(
                        (thought.quote + " " + thought.reflection).replace("**", ""), style = outfit(400, s(11.1f, k), 0f, (16 * k).sp).copy(color = Color(0xFF1E1515)),
                        maxLines = 5, overflow = TextOverflow.Clip,
                    )
                }
            }
            // the latest picture: 222 × 272 px centred at (343, 605), turned 2° right, over the thought
            val pic = pictures.firstOrNull()
            val scan = if (pic == null) scanRows(vm.recentRows()).firstOrNull() else null
            Box(
                Modifier.offset(m(343 - 111, k), m(605 - 136, k)).size(m(222, k), m(272, k))
                    .graphicsLayer { rotationZ = 2f; shadowElevation = 4.dp.toPx(); shape = RoundedRectangle(m(28, k)); clip = true }
                    .background(Color(0xFF9CCDE4))
                    // the picture's own note, or the scan itself; with neither, the desk's pictures
                    .pressable({
                        when {
                            pic != null -> vm.openNote = pic.note.id
                            scan != null -> scan.open()
                            else -> { vm.deskPages.clear(); vm.deskShelf = DeskFilter.Images }
                        }
                    }, pressedScale = 0.96f),
            ) {
                if (pic != null) NoteImage(pic.name, Modifier.matchParentSize(), maxPx = 900)
                else if (scan != null) PageFill(vm, scan.thumbPath)
                else Icon(LucideIcons.Images, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center).size(m(56, k)))
                // a scanned page is white: a soft shade under the label so it still reads
                if (pic == null && scan != null) Box(Modifier.fillMaxWidth().height(m(90, k)).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))
                Text(stringResource(R.string.desk_highlights), style = outfit(500, s(13.4f, k), -0.01f).copy(color = Color.White), modifier = Modifier.baselineAt(m(25, k), m(37, k)))
                Text(shortDate(pic?.note?.updated ?: scan?.date ?: System.currentTimeMillis()), style = outfit(500, s(10.6f, k)).copy(color = Color.White), modifier = Modifier.baselineAt(m(25, k), m(61, k)))
            }
            // the date: frosted glass, 220 × 202 px at (55, 700), 30 px corners
            val now = Date()
            Box(
                Modifier.offset(m(55, k), m(700, k)).size(m(220, k), m(202, k))
                    .graphicsLayer { shadowElevation = 3.dp.toPx(); shape = RoundedRectangle(m(30, k)); clip = true; ambientShadowColor = Color(0x22000000); spotShadowColor = Color(0x22000000) }
                    .background(Color(0xFFF4F8FF).copy(alpha = 0.78f))
                    // the day's to-dos: their lists on the desk (or the desk itself, all done)
                    .pressable({ vm.deskPages.clear(); if (todos.isNotEmpty()) vm.deskShelf = DeskFilter.Todo }, pressedScale = 0.96f),
            ) {
                Text(java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(now), style = outfit(700, s(37.6f, k), -0.035f).copy(color = Color.Black), maxLines = 1, modifier = Modifier.baselineAt(m(22, k), m(52, k)))
                Text(
                    java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd"), java.util.Locale.getDefault()).format(now),
                    style = outfit(300, s(32f, k)).copy(color = Color(0xFF737D87)), maxLines = 1, modifier = Modifier.baselineAt(m(22, k), m(96, k)),
                )
                // the overview: how many to-dos are left, and the next one
                Text(
                    if (todos.isEmpty()) stringResource(R.string.today_all_done)
                    else androidx.compose.ui.res.pluralStringResource(R.plurals.today_todos_left, todos.size, todos.size),
                    style = outfit(500, s(13.4f, k), -0.01f).copy(color = Color(0xFF3B6593)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = m(16, k)).baselineAt(m(22, k), m(133, k)),
                )
                todos.firstOrNull()?.let { next ->
                    Text(
                        next.text, style = outfit(400, s(11.1f, k)).copy(color = Color(0xFF1E1515)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = m(16, k)).baselineAt(m(22, k), m(153, k)),
                    )
                }
                Text(stringResource(R.string.today_overview), style = outfit(400, s(11.1f, k)).copy(color = Color(0xFF84909C)), modifier = Modifier.baselineAt(m(22, k), m(177, k)))
            }
        }
    }
    if (naming) {
        var typed by remember { mutableStateOf(vm.ownerName) }
        com.chethan616.clearpdf.ui.glass.GlassDialog(
            onDismiss = { naming = false }, title = stringResource(R.string.settings_your_name), icon = LucideIcons.UserRound, accent = com.chethan616.clearpdf.ui.theme.Candy.Tangerine,
            confirm = stringResource(R.string.common_done), onConfirm = { vm.updateOwnerName(typed); naming = false },
            dismiss = stringResource(R.string.common_cancel),
        ) { com.chethan616.clearpdf.ui.glass.GlassTextField(typed, { typed = it }, stringResource(R.string.settings_your_name_hint)) }
    }
}
