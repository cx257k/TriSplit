package jp.trisplit

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column whose rows can be reordered by dragging: long-press anywhere on a row, or grab the
 * [DragHandle] passed to [content]. The dragged row follows the finger and the others make room;
 * [onMove] is called once, on release, with the row's original and new positions.
 *
 * [content] gets the item, its current position (for labels that belong to the position, like
 * "Left"), whether it is being dragged, and the modifier for its drag handle.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable (item: T, position: Int, dragging: Boolean, handle: Modifier) -> Unit,
) {
    // Display order during a drag, as keys. One list for the life of the column: the drag gestures
    // keep the list they started with, so a new list per change of [items] would leave them moving
    // a stale copy. It follows the caller's list whenever no drag is in progress.
    val order = remember { items.map(key).toMutableStateList() }
    val byKey = remember(items) { items.associateBy(key) }
    var draggedKey by remember { mutableStateOf<Any?>(null) }
    val keys = items.map(key)
    if (draggedKey == null && order.toList() != keys) {
        order.clear()
        order.addAll(keys)
    }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<Any, Int>() }
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val haptics = LocalHapticFeedback.current
    val latestItems by rememberUpdatedState(items)
    val latestOnMove by rememberUpdatedState(onMove)

    fun start(k: Any) {
        draggedKey = k
        offset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Moves the dragged key past a neighbour once it is dragged over half of that neighbour.
    fun drag(k: Any, dy: Float) {
        offset += dy
        var position = order.indexOf(k)
        while (position < order.lastIndex) {
            val next = (heights[order[position + 1]] ?: break) + spacingPx
            if (offset <= next / 2) break
            order.removeAt(position)
            order.add(position + 1, k)
            offset -= next
            position++
        }
        while (position > 0) {
            val previous = (heights[order[position - 1]] ?: break) + spacingPx
            if (offset >= -previous / 2) break
            order.removeAt(position)
            order.add(position - 1, k)
            offset += previous
            position--
        }
    }

    /** [commit] is false for a cancelled gesture: the row goes back where it was. */
    fun end(k: Any, commit: Boolean = true) {
        val from = latestItems.indexOfFirst { key(it) == k }
        val to = order.indexOf(k)
        draggedKey = null
        offset = 0f
        // Back to the caller's order: it applies the move itself, and keys that are positions
        // compare equal before and after, so remember(items) would not reset this.
        order.clear()
        order.addAll(latestItems.map(key))
        if (commit && from >= 0 && to >= 0 && from != to) latestOnMove(from, to)
    }

    suspend fun PointerInputScope.dragGestures(k: Any, afterLongPress: Boolean) {
        val onDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, amount: androidx.compose.ui.geometry.Offset ->
            change.consume()
            drag(k, amount.y)
        }
        if (afterLongPress) {
            detectDragGesturesAfterLongPress(onDragStart = { start(k) }, onDragEnd = { end(k) }, onDragCancel = { end(k, commit = false) }, onDrag = onDrag)
        } else {
            detectDragGestures(onDragStart = { start(k) }, onDragEnd = { end(k) }, onDragCancel = { end(k, commit = false) }, onDrag = onDrag)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        order.forEachIndexed { position, k ->
            val item = byKey[k] ?: return@forEachIndexed
            key(k) {
                val dragging = draggedKey == k
                Box(
                    Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) offset else 0f
                            val scale = if (dragging) 1.02f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .then(if (dragging) Modifier.shadow(8.dp, MaterialTheme.shapes.medium) else Modifier)
                        .pointerInput(k) { dragGestures(k, afterLongPress = true) },
                ) {
                    content(item, position, dragging, Modifier.pointerInput(k) { dragGestures(k, afterLongPress = false) })
                }
            }
        }
    }
}

/** The "≡" grip at the end of a reorderable row; [modifier] is the handle modifier from [ReorderableColumn]. */
@Composable
fun DragHandle(modifier: Modifier) {
    val description = stringResource(R.string.reorder_handle)
    Text(
        "≡",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .semantics { contentDescription = description }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .wrapContentSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** This list with the element at [from] moved to [to]. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }
