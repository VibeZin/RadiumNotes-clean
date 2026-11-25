package com.chethan616.clearpdf.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.chethan616.clearpdf.ui.theme.Candy
import com.chethan616.clearpdf.ui.theme.ClearType
import com.chethan616.clearpdf.ui.theme.clear
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The menu's corners once it has opened, as [MenuCard]'s. */
private val MorphMenuRadius = 24.dp

/** How long a chosen row waits for the menu to fold back before it runs (as [GlassMenu]'s rows). */
private const val MorphRowDelayMillis = 200L

/**
 * A round glass button that is itself its menu, as iOS 26's: tapped, the circle stretches out of
 * its place (wider first, so for a moment it is a capsule), then grows down into the menu's rounded
 * rectangle, the same glass all the way, its glyph fading as the rows come in. Dismissed, the menu
 * flows back into the circle. There is never a button and a menu at once: while the glass is open the
 * button in the layout is hidden and [MorphButtonSurface] is the button.
 */
@Composable
fun MorphButton(
    items: List<GlassMenuItem>,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = HeaderButtonSize,
    menuWidth: Dp = 236.dp,
    enabled: Boolean = true,
    /** A wash over the button's glass (unspecified: none). */
    surface: Color = Color.Unspecified,
    icon: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var up by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    GlassCircle(
        onClick = { if (enabled && !up) { open = true; up = true } },
        modifier = modifier.onGloballyPositioned { anchor = it.boundsInWindow() }.graphicsLayer { alpha = if (up) 0f else 1f },
        size = size,
        label = label,
        surface = surface,
    ) { icon() }
    if (up) MorphButtonSurface(anchor, open, onDismiss = { open = false }, onGone = { up = false }, items = items, menuWidth = menuWidth, icon = icon)
}

/**
 * The circle at [anchor] as it opens into a menu of [items] and back. The caller hides its own button
 * from the first frame until [onGone] (the glass back in the circle), so the two are never seen
 * together. It opens when composed and closes when [open] turns false or its caller leaves.
 */
@Composable
fun MorphButtonSurface(
    anchor: Rect,
    open: Boolean,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    items: List<GlassMenuItem>,
    menuWidth: Dp = 236.dp,
    maxHeight: Dp = 480.dp,
    icon: (@Composable () -> Unit)? = null,
) = MorphButtonSurface(anchor, open, onDismiss, onGone, menuWidth, maxHeight, icon) { shut, choose ->
    items.forEach { item -> MorphMenuRow(item, shut) { choose(item.onClick) } }
}

/**
 * [MorphButtonSurface] holding any menu: [body] lays out its rows, and calls `choose(action)` for a
 * row that closes the menu (the glass folds back into the button first, then the action runs);
 * anything else (a star that flips in place) it does itself. [ghost]: no real button sits at
 * [anchor] (a menu a long press opened): the glass folds back into a drop there that melts away.
 */
@Composable
fun MorphButtonSurface(
    anchor: Rect,
    open: Boolean,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    menuWidth: Dp = 236.dp,
    maxHeight: Dp = 480.dp,
    icon: (@Composable () -> Unit)? = null,
    ghost: Boolean = false,
    body: @Composable androidx.compose.foundation.layout.ColumnScope.(shut: Boolean, choose: (() -> Unit) -> Unit) -> Unit,
) {
    // the round button it grows from is this glass now: hidden until the glass is back in its circle
    // (not cleared when the caller leaves: its menu is still folding back into the button then, in the
    // overlay; the button comes back only when that is done, through gone() below)
    // (the button steps aside only once the morph's glass is on screen: see MorphAnchors.shownAt; hidden
    // any earlier, the frame between the two showed nothing there, a blink)
    val gone: () -> Unit = {
        if (MorphAnchors.active == anchor) MorphAnchors.active = null
        onGone()
    }
    val glyph: @Composable () -> Unit = icon ?: MorphAnchors.glyphAt(anchor) ?: {}
    // Android 13+: the liquid version (the glass shaders it needs); before that the plain expansion
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        LiquidMorphSurface(anchor, open, onDismiss, gone, menuWidth, maxHeight, glyph, ghost, body)
    } else {
        RectMorphSurface(anchor, open, onDismiss, gone, menuWidth, maxHeight, glyph, ghost, body)
    }
}

/** Whether a menu at [anchor] opens from a round glass button (24 to 72 dp, near-square). */
internal fun isMorphAnchor(anchor: Rect, density: androidx.compose.ui.unit.Density): Boolean {
    val lo = with(density) { 24.dp.toPx() }
    val hi = with(density) { 72.dp.toPx() }
    return anchor.width in lo..hi && anchor.height in lo..hi && kotlin.math.abs(anchor.width - anchor.height) <= anchor.width * 0.2f
}

/**
 * Round glass buttons and the menu glass they become: the button at [active] hides while its glass is
 * a menu, and each button leaves its glyph here so the glass can show it while it is still the circle.
 */
object MorphAnchors {
    var active: Rect? by androidx.compose.runtime.mutableStateOf(null)
    private val glyphs = HashMap<Long, @Composable () -> Unit>()

    private fun key(r: Rect): Long = (r.left.toInt().toLong() / 4 shl 32) or (r.top.toInt().toLong() / 4 and 0xffffffffL)

    internal fun register(r: Rect, glyph: @Composable () -> Unit) { glyphs[key(r)] = glyph }

    internal fun unregister(r: Rect) { glyphs.remove(key(r)) }

    /** The morph's glass is laid out over the button at [r]: from the next frame the button can step aside. */
    internal fun shownAt(r: Rect) { if (active != r) active = r }

    internal fun glyphAt(r: Rect): (@Composable () -> Unit)? = glyphs[key(r)]

    private val tints = HashMap<Long, Color>()

    internal fun setTint(r: Rect, tint: Color) { if (tint.isSpecified) tints[key(r)] = tint else tints.remove(key(r)) }

    internal fun tintAt(r: Rect): Color? = tints[key(r)]

    private val surrounds = HashMap<Long, SurroundLuminance>()

    /** The adaptive reading around the button at [r] (null: Adaptive luminance is off). */
    internal fun setSurround(r: Rect, s: SurroundLuminance?) { if (s != null) surrounds[key(r)] = s else surrounds.remove(key(r)) }

    internal fun surroundAt(r: Rect): SurroundLuminance? = surrounds[key(r)]

    /** Whether the button laid out at [r] is the one being a menu now. */
    internal fun hides(r: Rect): Boolean {
        val a = active ?: return false
        return kotlin.math.abs(a.left - r.left) < 3f && kotlin.math.abs(a.top - r.top) < 3f && kotlin.math.abs(a.width - r.width) < 3f
    }
}

/** The morph without the liquid shaders (before Android 13): one rounded rectangle growing out of the button. */
@Composable
private fun RectMorphSurface(
    anchor: Rect,
    open: Boolean,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    menuWidth: Dp,
    maxHeight: Dp,
    icon: @Composable () -> Unit,
    ghost: Boolean,
    body: @Composable androidx.compose.foundation.layout.ColumnScope.(shut: Boolean, choose: (() -> Unit) -> Unit) -> Unit,
) {
    val gone by rememberUpdatedState(onGone)
    GlassOverlay(animatesOut = true) {
        val presence = LocalOverlayPresence.current
        val shut = !open || presence?.leaving == true
        // removed from the portal for good (finished or cut short): the button is back either way
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { gone() } }
        BackHandler(enabled = !shut, onBack = onDismiss)
        val host = LocalGlassOverlayHost.current ?: return@GlassOverlay
        val bars = WindowInsets.systemBars
        val view = LocalView.current
        val density = LocalDensity.current

        // Three flowing movements, each starting as the one before settles: the circle first drops
        // about a centimetre (a tall capsule), then spreads to the menu's full width, then pours down to
        // the menu's full height. Closing runs them back: the height drains, the width draws in, the
        // drop rises back into the circle.
        val drop = remember { Animatable(0f) }
        val wide = remember { Animatable(0f) }
        val tall = remember { Animatable(0f) }
        // a row chosen: the menu folds back into its button first, and the row's action runs only once
        // it is the button again (a row that opens Tools, Settings … never leaves glass on the next screen)
        var chosenAction by remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
        val runChosen = {
            val a = chosenAction
            chosenAction = null
            a?.invoke()
            Unit
        }
        val fade = remember { Animatable(1f) }
        LaunchedEffect(shut) {
            if (!shut) {
                coroutineScope {
                    // each on a spring at the user's bounce (Settings ▸ Motion), the next joining before the
                    // last has landed, so the glass swings past each state and the swing flows on
                    launch { drop.animateTo(1f, spring(maxOf(MotionSettings.damping(0.62f), 0.3f), MotionSettings.stiffness(318f), 0.0005f)) }
                    launch {
                        androidx.compose.runtime.snapshotFlow { drop.value >= 0.45f }.first { it }
                        wide.animateTo(1f, spring(maxOf(MotionSettings.damping(0.58f), 0.3f), MotionSettings.stiffness(272f), 0.0005f))
                    }
                    launch {
                        androidx.compose.runtime.snapshotFlow { wide.value >= 0.45f }.first { it }
                        tall.animateTo(1f, spring(maxOf(MotionSettings.damping(0.6f), 0.3f), MotionSettings.stiffness(181f), 0.0005f))
                    }
                }
            } else if (!ghost && MorphAnchors.glyphAt(anchor) == null) {
                fade.animateTo(0f, androidx.compose.animation.core.tween(150))
                gone()
                presence?.done()
                runChosen()
            } else {
                coroutineScope {
                    launch { tall.animateTo(0f, spring(1f, MotionSettings.stiffness(469f), 0.0005f)) }
                    launch {
                        androidx.compose.runtime.snapshotFlow { tall.value <= 0.4f }.first { it }
                        wide.animateTo(0f, spring(1f, MotionSettings.stiffness(393f), 0.0005f))
                    }
                    launch {
                        androidx.compose.runtime.snapshotFlow { tall.value <= 0.05f && wide.value <= 0.4f }.first { it }
                        drop.animateTo(0f, spring(1f, MotionSettings.stiffness(423f), 0.0005f))
                    }
                }
                if (ghost) fade.animateTo(0f, androidx.compose.animation.core.tween(110))
                gone()
                presence?.done()
                runChosen()
            }
        }
        // a centimetre on this screen: how far the glass drops while it spreads sideways
        val peekPx = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.ydpi / 2.54f

        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        val local = Rect(host.windowToLocal(anchor.topLeft), host.windowToLocal(anchor.bottomRight))
        val margin = with(density) { 12.dp.toPx() }
        val widthPx = with(density) { menuWidth.toPx() }
        var full by remember { mutableIntStateOf(0) }
        var fromRight by remember { mutableStateOf(true) }
        var fromBelow by remember { mutableStateOf(true) }
        // the glass's outline now: the app's own rounded rectangle (the lens needs that very shape), its
        // corners as round as its height allows while it is a capsule, settling to the menu's as it grows
        // tall, so circle, capsule and menu are one continuous curve
        val menuNow = if (full > 0) full.toFloat() else local.height
        val curW = lerp(local.width, widthPx, wide.value.coerceAtMost(MaxSwingWide))
        val curH = morphHeight(local.height, menuNow, peekPx, drop.value, if (full > 0) tall.value else 0f)
        val grown = ((curH - local.height) / (menuNow - local.height).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val radiusPx = min(min(curW, curH) / 2f, lerp(min(local.width, local.height) / 2f, with(density) { MorphMenuRadius.toPx() }, grown)).coerceAtLeast(0f)
        val shape = RoundedRectangle(with(density) { radiusPx.toDp() })
        // the glass bends what is behind it more while it is moving, and settles to the button's own lens
        val flow = kotlin.math.sin(Math.PI.toFloat() * tall.value.coerceIn(0f, 1f)) +
            0.6f * kotlin.math.sin(Math.PI.toFloat() * wide.value.coerceIn(0f, 1f)) +
            0.4f * kotlin.math.sin(Math.PI.toFloat() * drop.value.coerceIn(0f, 1f))
        val corner = when {
            fromBelow && fromRight -> Alignment.TopEnd
            fromBelow -> Alignment.TopStart
            fromRight -> Alignment.BottomEnd
            else -> Alignment.BottomStart
        }
        // the frost comes in as the glass opens (the button's own clear glass at first); never a new tint
        val frost = lerp(2f, 14f, tall.value.coerceIn(0f, 1f)).dp
        // the glass reads what is behind it and takes dark ink over light, white over dark (Night mode's
        // black pages, a white document, the aurora): the glyph and rows always stand out
        val luminance = rememberGlassLuminance(enabled = true)
        val inkColor = adaptiveInk(luminance, clear.ink)
        Layout(
            content = {
                CompositionLocalProvider(LocalGlassInk provides inkColor, LocalMenuInk provides inkColor) {
                    Box(
                        Modifier
                            .graphicsLayer { alpha = fade.value }
                            .glass(shape, wash = Color.Transparent, blur = frost, lensHeight = (12f + 7f * flow).dp, lensAmount = (24f + 18f * flow).dp, luminance = luminance)
                            .clip(shape),
                    ) {
                        // the button's glyph, where it was, fading as the glass opens
                        Box(
                            Modifier.align(corner)
                                .wrapContentSize(corner, unbounded = true)
                                .size(with(density) { local.width.toDp() }, with(density) { local.height.toDp() })
                                .graphicsLayer { alpha = (1f - drop.value / 0.5f).coerceIn(0f, 1f) },
                            contentAlignment = Alignment.Center,
                        ) { icon() }
                        // the rows where they will rest, coming in as the glass reaches them
                        Box(
                            // pinned to the button's corner (never centred in the glass while it is still small)
                            Modifier.align(corner)
                                .wrapContentWidth(if (fromRight) Alignment.End else Alignment.Start, unbounded = true)
                                .wrapContentHeight(if (fromBelow) Alignment.Top else Alignment.Bottom, unbounded = true)
                                .width(menuWidth)
                                .onSizeChanged { full = it.height }
                                .graphicsLayer { alpha = ((tall.value - 0.25f) / 0.5f).coerceIn(0f, 1f) },
                        ) {
                            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                                Spacer(Modifier.height(6.dp))
                                body(shut) { action -> if (chosenAction == null) { chosenAction = action; onDismiss() } }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().onGloballyPositioned { MorphAnchors.shownAt(anchor) },
        ) { measurables, constraints ->
            val origin = host.originInWindow
            val root = view.rootView
            val barTop = (bars.getTop(this) - origin.y).coerceAtLeast(0f)
            val barBottom = (bars.getBottom(this) - (root.height - origin.y - constraints.maxHeight)).coerceAtLeast(0f)
            val barLeft = (bars.getLeft(this, layoutDirection) - origin.x).coerceAtLeast(0f)
            val barRight = (bars.getRight(this, layoutDirection) - (root.width - origin.x - constraints.maxWidth)).coerceAtLeast(0f)
            val menuH = (if (full > 0) full else local.height.roundToInt()).toFloat()
            val right = local.center.x > constraints.maxWidth / 2f
            fromRight = right
            val below = local.top + menuH < constraints.maxHeight - barBottom - margin
            fromBelow = below
            val minX = barLeft + margin
            val maxX = (constraints.maxWidth - barRight - widthPx - margin).coerceAtLeast(minX)
            val minY = barTop + margin
            val maxY = (constraints.maxHeight - barBottom - menuH - margin).coerceAtLeast(minY)
            // the menu grows from the button's own corner and rests over the place the button was
            val endL = (if (right) local.right - widthPx else local.left).coerceIn(minX, maxX)
            val endT = (if (below) local.top else local.bottom - menuH).coerceIn(minY, maxY)
            val gw = wide.value.coerceAtMost(MaxSwingWide)
            val gh = if (full > 0) tall.value else 0f
            val l = lerp(local.left, endL, gw)
            val r = lerp(local.right, endL + widthPx, gw)
            // the height grows away from the button's edge (down from its top, or up from its bottom)
            val hNow = morphHeight(local.height, menuH, peekPx, drop.value, gh)
            val edgeT = lerp(local.top, endT, gw)
            val edgeB = lerp(local.bottom, endT + menuH, gw)
            val t = if (below) edgeT else edgeB - hNow
            val b = if (below) edgeT + hNow else edgeB
            val w = max(1, (r - l).roundToInt())
            val h = max(1, (b - t).roundToInt())
            val placeable = measurables.first().measure(Constraints.fixed(w, h))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(IntOffset(l.roundToInt(), t.roundToInt()))
            }
        }
    }
}

/** One of the menu's rows, as [GlassMenu]'s: the label, its glyph at the end; it runs once the glass has folded. */
@Composable
internal fun MorphMenuRow(item: GlassMenuItem, shut: Boolean, close: () -> Unit) {
    var chosen by remember { mutableStateOf(false) }
    val ink = LocalGlassInk.current
    Row(
        Modifier.fillMaxWidth().pressable({
            if (!chosen && !shut) {
                chosen = true
                // the surface runs the row's action once its glass has folded back into the button
                close()
            }
        }).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.label, style = ClearType.BodyStrong, color = if (item.destructive) Candy.Tomato else ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.icon != null) Icon(item.icon, null, tint = if (item.destructive) Candy.Tomato else ink.copy(alpha = 0.65f), modifier = Modifier.size(19.dp))
    }
}

/**
 * The glass's height: [drop] (0..1) takes it from the button's height down by [peek] (a centimetre,
 * or less if the menu is shorter), [tall] (0..1) pours it the rest of the way to the menu's height.
 * A spring past 1 swells it a touch further, as glass settling.
 */
private fun morphHeight(button: Float, menu: Float, peek: Float, drop: Float, tall: Float): Float {
    val first = min(peek, (menu - button).coerceAtLeast(0f))
    val rest = (menu - button - first).coerceAtLeast(0f)
    return (button + first * drop.coerceAtMost(1.15f) + rest * tall.coerceAtMost(MaxSwingTall)).coerceAtLeast(1f)
}

/** How far past the menu's width and height the springs may swing the glass (the jelly). */
private const val MaxSwingWide = 1.05f
private const val MaxSwingTall = 1.12f
