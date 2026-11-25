package com.chethan616.clearpdf.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.chethan616.clearpdf.ui.theme.clear
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.runtimeShaderEffect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The shape of liquid glass made of two pieces, as iOS 26's GlassEffectContainer joins them: the
 * button's circle and the menu's rounded rectangle, melted into one by a smooth minimum (a metaball
 * union) of their distance fields. While they are close, a neck of glass joins them; as [k] falls it
 * thins and lets go.
 */
private const val LiquidSdf = """
uniform float4 circle;
uniform float4 rect;
uniform float rectRadius;
uniform float k;

float sdCircle(float2 p, float2 c, float r) { return length(p - c) - r; }

float sdRRect(float2 p, float4 b, float r) {
    float2 c = (b.xy + b.zw) * 0.5;
    float2 hs = max((b.zw - b.xy) * 0.5, float2(0.0));
    float rr = min(r, min(hs.x, hs.y));
    float2 q = abs(p - c) - (hs - float2(rr));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rr;
}

float smin(float a, float b, float kk) {
    if (kk <= 0.001) return min(a, b);
    float h = clamp(0.5 + 0.5 * (b - a) / kk, 0.0, 1.0);
    return mix(b, a, h) - kk * h * (1.0 - h);
}

float sdLiquid(float2 p) {
    return smin(sdCircle(p, circle.xy, circle.z), sdRRect(p, rect, rectRadius), k);
}

float2 gradLiquid(float2 p) {
    float e = 1.0;
    float2 g = float2(sdLiquid(p + float2(e, 0.0)) - sdLiquid(p - float2(e, 0.0)),
                      sdLiquid(p + float2(0.0, e)) - sdLiquid(p - float2(0.0, e)));
    float l = length(g);
    return l > 0.0001 ? g / l : float2(0.0, -1.0);
}
"""

/** The glass's bending of what is behind it, along the liquid shape's edge (the app's lens, on a melted outline). */
private const val LiquidRefractionShader = """
uniform shader content;
uniform float2 offset;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depth;
uniform float dispersion;
$LiquidSdf

float circleMap(float x) { return 1.0 - sqrt(max(1.0 - x * x, 0.0)); }

// the same bend and colour split as the app's own glass (backdrop's rounded-rect refraction shaders),
// on the liquid outline: the split only toward the corners, a pixel or two, three taps
half4 main(float2 coord) {
    float2 p = coord + offset;
    float sd = sdLiquid(p);
    if (sd > 1.0) return half4(0.0);
    float cover = clamp(0.5 - sd, 0.0, 1.0);
    if (-sd >= refractionHeight) return content.eval(coord) * cover;
    float d = circleMap(1.0 - (-min(sd, 0.0)) / refractionHeight) * refractionAmount;
    // the centre and size each part of the glass bends around: the circle's own for the circle, the
    // menu's for the menu, blended across the neck (one centre for both bent the circle the wrong way)
    float dc = sdCircle(p, circle.xy, circle.z);
    float dr = sdRRect(p, rect, rectRadius);
    float w = clamp(0.5 + 0.5 * (dr - dc) / max(k, 1.0), 0.0, 1.0);
    float2 rc = (rect.xy + rect.zw) * 0.5;
    float2 rh = max((rect.zw - rect.xy) * 0.5, float2(1.0));
    float2 center = mix(rc, circle.xy, w);
    float2 halfSize = mix(rh, float2(max(circle.z, 1.0)), w);
    float2 centered = p - center;
    float2 g = gradLiquid(p);
    if (depth > 0.5 && length(centered) > 0.001) g = normalize(g + normalize(centered));
    float2 refracted = coord - d * g;
    if (dispersion > 0.5) {
        float intensity = clamp((centered.x * centered.y) / max(halfSize.x * halfSize.y, 1.0), -1.0, 1.0);
        float2 dc = -d * g * intensity;
        if (dot(dc, dc) >= 0.25) {
            half4 r = content.eval(refracted + dc * 0.4286);
            half4 gg = content.eval(refracted + dc * 0.0952);
            half4 bl = content.eval(refracted - dc * 0.6667);
            return half4(r.r, gg.g, bl.b, (r.a + gg.a + bl.a) / 3.0) * cover;
        }
    }
    return content.eval(refracted) * cover;
}
"""

/** The glass's wash and its lit rim, on the same melted outline. */
private const val LiquidSurfaceShader = """
uniform float4 wash;
uniform float rimAlpha;
$LiquidSdf

half4 main(float2 p) {
    float sd = sdLiquid(p);
    float cover = clamp(0.5 - sd, 0.0, 1.0);
    if (cover <= 0.0) return half4(0.0);
    float2 g = gradLiquid(p);
    float2 light = normalize(float2(-0.55, -1.0));
    float facing = max(dot(g, light), 0.0) + 0.45 * max(dot(-g, light), 0.0);
    float rim = (1.0 - smoothstep(0.0, 1.8, -sd)) * rimAlpha * (0.35 + 0.65 * facing);
    float a = wash.a * cover;
    float r = rim * cover;
    half3 col = half3(wash.rgb) * a + half3(1.0) * r;
    return half4(col, a + r * (1.0 - a));
}
"""

/**
 * [MorphButtonSurface] on Android 13 and later: the menu's glass flows out of the round button, as iOS
 * 26's glass does with a matched-geometry transition in a glass container. It starts as the button,
 * slides and grows to its place just below (or above) the button, and while the two are close they
 * are one piece of glass joined by a neck that thins and lets go as the menu settles. Closing, the menu
 * flows back up into the button and melts into it. The glass refracts along that liquid outline,
 * reads what is behind it (dark ink over light, white over dark) and follows the user's glass style.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun LiquidMorphSurface(
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
        // the button's own tint (a blue Done, a candy button): the glass keeps it at both ends
        val tint = MorphAnchors.tintAt(anchor) ?: Color.Unspecified
        BackHandler(enabled = !shut, onBack = onDismiss)
        val host = LocalGlassOverlayHost.current ?: return@GlassOverlay
        val bars = WindowInsets.systemBars
        val view = LocalView.current
        val density = LocalDensity.current

        // The circle itself becomes the menu, in three flowing movements, each starting as the one before
        // settles: it drips (a drop of glass runs down out of the circle, about a centimetre, joined to it
        // by a liquid neck), it spreads (the drop widens to the menu's full width, the circle melting into
        // it), it pours (down to the menu's full height). At rest there is only the menu, lying where the
        // button was. Closing runs it all back into the circle.
        // One motion, not three: a single spring drives the whole morph, and the three movements are
        // overlapping slices of it, so the speed flows from one into the next with no stop between them
        // (gated springs read as a machine). Past rest the spring's swing is the menu's own jelly.
        val p = remember { Animatable(0f) }
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
                // the states and pace the owner liked, now on a spring that bounces at Settings ▸ Motion's
                // bounce: past the menu and back, jelly-like (see geo: the swing is the menu's own stretch)
                p.animateTo(1f, GlassMotion.morphOpen())
            } else {
                // its button gone with its screen: dissolve too, never fold into a place that is no longer there
                if (!ghost && MorphAnchors.glyphAt(anchor) == null) fade.animateTo(0f, androidx.compose.animation.core.tween(150))
                // an option picked: a quicker fold that ends the moment it looks landed (the action waits on
                // it); dismissed: the unhurried fold
                else if (chosenAction != null) p.animateTo(0f, GlassMotion.morphChosen())
                else p.animateTo(0f, GlassMotion.morphClose())
                // no real button under it (a menu opened by a long press): the drop it folded back into melts away
                if (ghost) fade.animateTo(0f, androidx.compose.animation.core.tween(110))
                // the glass has landed: the real button takes its place and the morph is gone before the
                // row's action starts (an action that changes screen would otherwise slide the button away
                // while the morph's circle stayed put: two circles); then the action
                gone()
                presence?.done()
                val action = chosenAction
                chosenAction = null
                // straight after (on the next frame, once the morph has left the screen)
                if (action != null) android.os.Handler(android.os.Looper.getMainLooper()).post { action() }
            }
        }
        /** 0 while the glass is still the button's circle, 1 once it is liquid (raw progress, both ways). */
        fun liquidIn(): Float { val c = (p.value / 0.12f).coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
        fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        val local = Rect(host.windowToLocal(anchor.topLeft), host.windowToLocal(anchor.bottomRight))
        val px = { d: Dp -> with(density) { d.toPx() } }
        val widthPx = px(menuWidth)
        var full by remember { mutableIntStateOf(0) }
        val menuH = if (full > 0) full.toFloat() else local.height * 4f

        // the menu's place: over the button, from its corner (iOS 26), kept off the bars
        val origin = host.originInWindow
        val root = view.rootView
        val barTop = (bars.getTop(density) - origin.y).coerceAtLeast(0f)
        val barBottom = (bars.getBottom(density) - (root.height - origin.y - view.height)).coerceAtLeast(0f)
        val margin = px(12.dp)
        val screenW = view.width.toFloat()
        val screenH = view.height.toFloat()
        val fromRight = local.center.x > screenW / 2f
        val below = local.top + menuH < screenH - barBottom - margin
        val endL = (if (fromRight) local.right - widthPx else local.left).coerceIn(margin, (screenW - widthPx - margin).coerceAtLeast(margin))
        val endT = (if (below) local.top else local.bottom - menuH).coerceIn(barTop + margin, (screenH - barBottom - menuH - margin).coerceAtLeast(barTop + margin))
        val endR = endL + widthPx
        val endB = endT + menuH
        val r0 = min(local.width, local.height) / 2f
        // how far the drop runs down before it spreads: about 1.8 cm (less if the menu is shorter)
        val peek = (androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.ydpi / 2.54f * 1.8f).coerceAtMost((menuH - local.height).coerceAtLeast(0f))
        // room for the jelly: the menu stretches up to a tenth of its height past rest as it settles
        val swellPx = menuH * JellyStretch * JellyMax + px(6.dp)
        val radius24 = px(24.dp)
        val neckPx = px(22.dp)
        val opening = !shut

        // The glass is ONE node of a fixed size for the whole morph (the button and the menu's place,
        // with room for the lens and the swell): nothing is laid out, resized or re-allocated per frame.
        // Each frame only the shape (shader uniforms) and the clip change, read in draw.
        val pad = px(28.dp)
        val stage = Rect(min(local.left, endL) - pad, min(local.top, endT) - pad - swellPx, max(local.right, endR) + pad, max(local.bottom, endB) + pad + swellPx)

        /** Where everything is this frame (read only in draw and layer blocks). */
        class Geo(val menu: Rect, val radius: Float, val k: Float, val circleR: Float, val motion: Float)
        // set once the opening spring has first reached the menu: from then on its swing past and back is
        // the settling of the glass (jelly), not the path running backwards into the drop
        val arrived = remember { booleanArrayOf(false) }
        fun geo(): Geo {
            val raw = p.value
            if (opening && raw >= 1f) arrived[0] = true
            // the path through the four states (circle, tall drop, wide, menu) runs from 0 to 1; anything
            // past it is jelly, shown as the menu's own stretch below
            val pv = if (opening && arrived[0]) 1f else raw.coerceAtMost(1f)
            val jelly = if (full > 0 && (raw > 1f || (opening && arrived[0]))) (raw - 1f).coerceIn(-JellyMax, JellyMax) else 0f
            val d = ease(pv / 0.38f)
            val sp = ease((pv - 0.16f) / 0.46f)
            val po = if (full > 0) ease((pv - 0.36f) / 0.64f) else 0f
            // the drop is born inside the circle (a small core at its middle) and grows out of it, and sinks
            // back into it the same way: it never sits beside the circle as a bubble of its own
            val dc = d.coerceIn(0f, 1f)
            val dropW = lerp(local.width * 0.5f, local.width * 0.86f, dc)
            val rl = lerp(local.center.x - dropW / 2f, endL, sp)
            val rr = lerp(local.center.x + dropW / 2f, endR, sp)
            val path = if (below) {
                val top = lerp(local.center.y - local.height * 0.25f, endT, sp)
                val bottom = lerp(local.center.y + local.height * 0.25f, local.bottom + peek, d) + (endB - local.bottom - peek) * po
                Rect(rl, min(top, bottom - 1f), rr, max(bottom, top + 1f))
            } else {
                val bottom = lerp(local.center.y + local.height * 0.25f, endB, sp)
                val top = lerp(local.center.y - local.height * 0.25f, local.top - peek, d) - (local.top - peek - endT) * po
                Rect(rl, min(top, bottom - 1f), rr, max(bottom, top + 1f))
            }
            // jelly: past rest the menu runs on away from its button and draws in at the sides (taller and
            // narrower, a tall ellipse's moment), then swings back squat and wide, settling; it stays held
            // at the button's corner, as a drop of gel hangs from what it is stuck to
            val menu = if (jelly == 0f) path else {
                val h = path.height * (1f + JellyStretch * jelly)
                val w = path.width * (1f - JellySqueeze * jelly)
                val l = if (fromRight) path.right - w else path.left
                val t = if (below) path.top else path.bottom - h
                Rect(l, t, l + w, t + h)
            }
            val radius = min(min(menu.width, menu.height) / 2f, lerp(r0, radius24, po.coerceIn(0f, 1f)))
            // a liquid neck joins the circle and the drop while the drop is out of it, and fades as the
            // circle melts into the menu
            val apart = (d / 0.35f).coerceIn(0f, 1f)
            val k = neckPx * apart * apart * (3f - 2f * apart) * (1f - po.coerceIn(0f, 1f))
            val circleR = r0 * (1f - po.coerceIn(0f, 1f)).coerceAtLeast(0f)
            // how lively the lens and frost are: through the morph's path only, easing out as it lands. (Tied to
            // the jelly's swing it pulsed with every wobble, and the page behind (a white title on the dark
            // theme) flickered sharp and blurred, bent and straight, with it.)
            val motion = kotlin.math.sin(Math.PI.toFloat() * ((d + sp + po) / 3f).coerceIn(0f, 1f))
            return Geo(menu, radius, k, circleR, motion)
        }

        val luminance = rememberGlassLuminance(enabled = true)
        // Adaptive luminance on: at the button's end the glass is the button's own adaptive glass (its
        // reading of what is around it, its frost, colour and milk or smoke), so neither the opening nor
        // the landing ever shows a moment of plain glass where the adaptive button was
        val adaptiveOn = GlassSettings.adaptive
        val around = if (adaptiveOn) MorphAnchors.surroundAt(anchor) else null
        val fallbackLum = if (clear.dark) 0.14f else 0.86f
        fun aroundLum(): Float = around?.value ?: luminance?.value ?: fallbackLum
        val ink = adaptiveInk(luminance, clear.ink)
        val style = GlassSettings.style
        val dark = clear.dark
        val surface = remember { RuntimeShader(LiquidSurfaceShader) }
        val rimAlpha = (if (dark) 0.32f else 0.6f) * style.highlight.coerceIn(0f, 1f)
        // the menu's milk or smoke comes in as it opens and is gone by the time it is the circle again
        fun washIn() = ((p.value - 0.08f) / 0.4f).coerceIn(0f, 1f)
        // the luminance reading copies the backdrop: every few frames is plenty (its ink eases anyway)
        val frame = remember { intArrayOf(0) }

        fun RuntimeShader.shapeUniforms(g: Geo) {
            setFloatUniform("circle", local.center.x - stage.left, local.center.y - stage.top, g.circleR, 0f)
            setFloatUniform("rect", g.menu.left - stage.left, g.menu.top - stage.top, g.menu.right - stage.left, g.menu.bottom - stage.top)
            setFloatUniform("rectRadius", g.radius)
            setFloatUniform("k", g.k)
        }

        Box(
            Modifier
                .offset { IntOffset(stage.left.roundToInt(), stage.top.roundToInt()) }
                .size(with(density) { stage.width.toDp() }, with(density) { stage.height.toDp() })
                .graphicsLayer { alpha = fade.value * liquidIn() }
                .drawBackdrop(
                    backdrop = LocalBackdrop.current,
                    shape = { RectangleShape },
                    effects = {
                        val g = geo()
                        // adaptive blur: the button's clear glass frosts to a menu's as it opens, so rows read
                        // over a busy page (as MenuCard's), more over a bright page than a dark one, and a
                        // touch more while the glass is on the move
                        morphEffects(if (adaptiveOn) style.adaptiveBase else style, luminance?.value ?: if (dark) 0.14f else 0.86f, dark, washIn(), g.motion, px(1.dp), if (adaptiveOn) aroundLum() else null)
                        runtimeShaderEffect("LiquidMorphRefraction", LiquidRefractionShader, "content") {
                            setFloatUniform("offset", -padding, -padding)
                            // the button's own lens (12 / 24 dp at the user's style), a little livelier while it moves
                            setFloatUniform("refractionHeight", px((12f + 2f * g.motion).dp) * style.refractionHeight)
                            setFloatUniform("refractionAmount", px((24f + 4f * g.motion).dp) * style.refractionAmount)
                            setFloatUniform("depth", if (style.depthEffect) 1f else 0f)
                            setFloatUniform("dispersion", if (style.chromaticAberration) 1f else 0f)
                            shapeUniforms(g)
                        }
                    },
                    highlight = null,
                    shadow = null,
                    onDrawBackdrop = { drawIt ->
                        val rec = luminance?.recorder
                        if (rec != null && frame[0]++ % 6 == 0) rec(drawIt) else drawIt()
                    },
                    onDrawSurface = {
                        val g = geo()
                        val w = washIn()
                        val lum = luminance?.value ?: if (dark) 0.14f else 0.86f
                        // the milk or smoke a menu wears, thicker than a button's, so its rows read
                        val menuWash = adaptiveWash(lum).let { it.copy(alpha = (it.alpha * 1.7f).coerceAtMost(0.55f)) }.glassTint(style)
                        val plain = menuWash.let { it.copy(alpha = it.alpha * w) }
                        val wash = when {
                            tint.isSpecified -> androidx.compose.ui.graphics.lerp(tint.copy(alpha = 0.75f), plain, w)
                            // the adaptive button's own wash at its end, the menu's as it opens
                            adaptiveOn -> androidx.compose.ui.graphics.lerp(adaptiveWash(aroundLum()).let { it.copy(alpha = it.alpha * 0.7f) }, menuWash, w)
                            else -> plain
                        }
                        surface.setFloatUniform("wash", wash.red, wash.green, wash.blue, wash.alpha)
                        surface.setFloatUniform("rimAlpha", rimAlpha)
                        surface.shapeUniforms(g)
                        drawRect(ShaderBrush(surface))
                    },
                ),
        )

        // At the ends the glass IS the button: the very glass every round glass button wears (GlassCircle's
        // effects, lens and rim light), so the hand-off to the real button changes nothing. The liquid
        // shape takes over as the circle starts to drip, and gives it back before the circle lands.
        Box(
            Modifier
                .offset { IntOffset(local.left.roundToInt(), local.top.roundToInt()) }
                .size(with(density) { local.width.toDp() }, with(density) { local.height.toDp() })
                // on screen over the button now: the real button can step aside (from the next frame)
                .onGloballyPositioned { MorphAnchors.shownAt(anchor) }
                .graphicsLayer { alpha = fade.value * (1f - liquidIn()) }
                .drawBackdrop(
                    backdrop = LocalBackdrop.current,
                    shape = { androidx.compose.foundation.shape.CircleShape },
                    // exactly as GlassCircle draws itself, adaptive or not
                    effects = {
                        if (adaptiveOn) adaptiveGlassEffects(style.adaptiveBase, aroundLum(), px(2.dp), px(12.dp), px(24.dp))
                        else glassEffects(style, px(2.dp), px(12.dp), px(24.dp))
                    },
                    highlight = { glassHighlight(if (adaptiveOn) style.adaptiveBase else style) },
                    shadow = null,
                    onDrawSurface = {
                        // as GlassCircle paints a tinted button, or its adaptive milk or smoke
                        if (tint.isSpecified) {
                            drawRect(tint, blendMode = androidx.compose.ui.graphics.BlendMode.Hue)
                            drawRect(tint.copy(alpha = 0.75f))
                        } else if (adaptiveOn) {
                            drawRect(adaptiveWash(aroundLum()).let { it.copy(alpha = it.alpha * 0.7f) })
                        }
                    },
                ),
        )
        CompositionLocalProvider(LocalGlassInk provides ink, LocalMenuInk provides ink) {
          Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
            // the button's glyph, fading as its circle becomes the menu
            Box(
                Modifier
                    .offset { IntOffset(local.left.roundToInt(), local.top.roundToInt()) }
                    .size(with(density) { local.width.toDp() }, with(density) { local.height.toDp() })
                    .graphicsLayer { alpha = (1f - p.value / 0.22f).coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) { icon() }
            // the rows at the menu's resting place (a fixed node), clipped each frame to the glass's
            // current rectangle in their layer: shown through the menu glass as it arrives
            Box(
                Modifier
                    .offset { IntOffset(endL.roundToInt(), (endT - swellPx).roundToInt()) }
                    .size(with(density) { widthPx.toDp() }, with(density) { (menuH + 2f * swellPx).toDp() })
                    .graphicsLayer {
                        val g = geo()
                        val ox = endL
                        val oy = endT - swellPx
                        val r = Rect(g.menu.left - ox, g.menu.top - oy, g.menu.right - ox, g.menu.bottom - oy)
                        shape = RectClip(r, g.radius)
                        clip = true
                    },
            ) {
                Box(
                    Modifier
                        .align(if (below) Alignment.TopStart else Alignment.BottomStart)
                        .padding(top = if (below) with(density) { swellPx.toDp() } else 0.dp, bottom = if (below) 0.dp else with(density) { swellPx.toDp() })
                        .wrapContentHeight(if (below) Alignment.Top else Alignment.Bottom, unbounded = true)
                        .width(menuWidth)
                        .onSizeChanged { full = it.height }
                        .graphicsLayer { alpha = ((p.value - 0.55f) / 0.35f).coerceIn(0f, 1f) },
                ) {
                    Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(6.dp))
                        body(shut) { action -> if (chosenAction == null) { chosenAction = action; onDismiss() } }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
          }
        }
    }
}

/** A rounded rectangle at [r] inside its layer: the rows' clip, following the glass frame by frame. */
private class RectClip(private val r: Rect, private val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(radius)))
}

/** How much taller the menu runs per unit of the spring's swing past rest (the jelly). */
private const val JellyStretch = 0.24f

/** How much it draws in at the sides meanwhile (gel keeps its volume). */
private const val JellySqueeze = 0.1f

/** The furthest swing shown, either way (Jelly at its springiest). */
private const val JellyMax = 0.4f

/** [luminance] as -1 (dark) … 1 (light), eased so mid-greys stay near neutral (as AdaptiveGlass's). */
private fun signedLum(luminance: Float): Float = (luminance * 2f - 1f).let { kotlin.math.sign(it) * it * it }

/**
 * The morph's colour and frost: at [open] 0 the button's own glass (the user's style, a light frost),
 * at 1 a menu's frost that answers what is behind it (brighter and more frosted over a light page,
 * deeper over a dark one, as [MenuCard] with Adaptive luminance). [motion] adds a little frost while
 * the glass moves, so it reads as liquid rushing rather than a sharp shape sliding. [dp] is one dp.
 */
private fun com.kyant.backdrop.BackdropEffectScope.morphEffects(style: GlassStyle, luminance: Float, dark: Boolean, open: Float, motion: Float, dp: Float, buttonLuminance: Float? = null) {
    val l = signedLum(luminance.coerceIn(0f, 1f))
    val o = open.coerceIn(0f, 1f)
    val menuBrightness = if (l > 0f) lerp(0.08f, 0.3f, l) else lerp(if (dark) -0.12f else 0.04f, -0.24f, -l)
    // the button's end: plain glass, or (Adaptive luminance) the adaptive button's own colour and frost,
    // as adaptiveGlassEffects sets them from the reading around the button
    val b = buttonLuminance?.let { signedLum(it.coerceIn(0f, 1f)) }
    val buttonBrightness = if (b == null) style.brightness else (if (b > 0f) lerp(0.08f, 0.42f, b) else lerp(0.08f, -0.2f, -b)) + style.brightness
    val buttonContrast = if (b != null && b > 0f) lerp(1f, 0.25f, b) else 1f
    colorControls(
        brightness = lerp(buttonBrightness, menuBrightness, o),
        contrast = lerp(buttonContrast, if (l > 0f) lerp(1f, 0.5f, l) else 1f, o),
        saturation = lerp(style.saturation, 1.25f, o),
    )
    val buttonBlur = 2f * dp * style.blur * (if (b == null) 1f else if (b > 0f) lerp(1f, 1.5f, b) else lerp(1f, 0.6f, -b))
    val menuBlur = 22f * dp * (if (l > 0f) lerp(1f, 1.35f, l) else lerp(1f, 0.75f, -l))
    blur(lerp(buttonBlur, menuBlur, o) + 5f * dp * motion.coerceIn(0f, 1f))
}
