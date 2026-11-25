package com.chethan616.clearpdf.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The desk's cards in the order the user dragged them to (keys, saved per shelf), over [items] as the
 * desk would set them: the saved cards take the places saved cards have, in the saved order; a card
 * not yet placed by hand (a new note) keeps its own place.
 */
fun arrangeDesk(items: List<DeskItem>, saved: List<String>): List<DeskItem> {
    if (saved.isEmpty()) return items
    val rank = saved.withIndex().associate { it.value to it.index }
    val placed = items.filter { it.key in rank }.sortedBy { rank.getValue(it.key) }.iterator()
    return items.map { if (it.key in rank) placed.next() else it }
}

/** A card being carried across the desk: which, where the finger holds it and is, and the order as it goes. */
@Stable
class DeskDrag {
    var key by mutableStateOf<String?>(null)
        private set

    /** The order while a card is carried (null otherwise): the desk shows this. */
    var live by mutableStateOf<List<String>?>(null)
        private set

    /** Where the finger is, in the grid. */
    var pointer by mutableStateOf(Offset.Zero)
        internal set

    /** Where on the card the finger took hold of it. */
    var grab = Offset.Zero
        private set

    /** The card last traded places with, so a pair of different heights never flips back and forth. */
    internal var lastOver: String? = null

    internal fun start(k: String, at: Offset, finger: Offset, order: List<String>) {
        key = k
        grab = at
        pointer = finger
        live = order
        lastOver = null
    }

    /** The carried card takes [over]'s place, the cards between moving one along. */
    internal fun moveTo(over: String) {
        val k = key ?: return
        val list = live?.toMutableList() ?: return
        val from = list.indexOf(k)
        val to = list.indexOf(over)
        if (from < 0 || to < 0 || from == to) return
        list.removeAt(from)
        list.add(to, k)
        live = list
        lastOver = over
    }

    internal fun end(): List<String>? {
        val order = live
        key = null
        live = null
        lastOver = null
        return order
    }
}

private fun LazyStaggeredGridItemInfo.contains(p: Offset, inset: Float = 0f): Boolean {
    val ix = size.width * inset
    val iy = size.height * inset
    return p.x >= offset.x + ix && p.x < offset.x + size.width - ix && p.y >= offset.y + iy && p.y < offset.y + size.height - iy
}

/**
 * Cards are dragged to new places on the desk, as icons on a home screen: a card held still opens its
 * menu (its own long press, untouched); moved while held, the menu gives way ([onLift]) and the card
 * lifts and follows the finger, the others making room as it passes over them; near the top or the
 * bottom the desk scrolls along; let go, the new order is kept ([onDrop]). Only cards whose keys are in
 * [draggable] move. Watched before the cards see the touch, so a tap, a scroll and a plain long press
 * are theirs exactly as before.
 */
fun Modifier.deskDragging(
    grid: LazyStaggeredGridState,
    drag: DeskDrag,
    draggable: () -> Set<String>,
    order: () -> List<String>,
    onLift: () -> Unit,
    onDrop: (List<String>) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(down.position) } ?: return@awaitEachGesture
        val key = hit.key as? String ?: return@awaitEachGesture
        if (key !in draggable()) return@awaitEachGesture
        // held still for a long press (moved or lifted first: a scroll or a tap, not ours)
        val gaveUp = withTimeoutOrNull(longPress) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!c.pressed || (c.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
        if (gaveUp != null) return@awaitEachGesture
        // held: let go now and it was just the long press (the card's menu is open); move and it lifts
        var finger = down.position
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!c.pressed) return@awaitEachGesture
            finger = c.position
            if ((finger - down.position).getDistance() > slop) break
        }
        val now = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: hit
        drag.start(key, down.position - Offset(now.offset.x.toFloat(), now.offset.y.toFloat()), finger, order())
        onLift()
        val edge = 96.dp.toPx()
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                c.consume()
                drag.pointer = c.position
                // over another card's middle: it makes room (each pair trades once until the finger moves on)
                val over = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key != key && it.contains(c.position, 0.18f) }?.key as? String
                if (over == null) drag.lastOver = null
                else if (over != drag.lastOver && over in draggable()) drag.moveTo(over)
                // near an edge the desk scrolls along
                val h = size.height.toFloat()
                val dy = when {
                    c.position.y < edge -> -(edge - c.position.y) * 0.22f
                    c.position.y > h - edge -> (c.position.y - (h - edge)) * 0.22f
                    else -> 0f
                }
                if (dy != 0f) grid.dispatchRawDelta(dy)
            }
        } finally {
            drag.end()?.let(onDrop)
        }
    }
}

/** The carried card: above the others, a touch larger with its shadow, under the finger where it took hold. */
fun Modifier.carried(drag: DeskDrag, key: String, grid: LazyStaggeredGridState): Modifier =
    if (drag.key != key) this else this.zIndex(1f).graphicsLayer {
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        if (info != null) {
            translationX = drag.pointer.x - drag.grab.x - info.offset.x
            translationY = drag.pointer.y - drag.grab.y - info.offset.y
        }
        scaleX = 1.05f
        scaleY = 1.05f
        shadowElevation = 18.dp.toPx()
        shape = CardShape
        clip = false
    }
