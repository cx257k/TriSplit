package jp.trisplit

import kotlin.math.roundToInt

/**
 * Size limits of the app in one column, in px. A max of 0 or less means no limit. These come from
 * the app (its declared minimum size) and from One UI (its default minimum and per-app maximums).
 */
data class WindowLimits(val minWidth: Int, val maxWidth: Int = 0, val maxHeight: Int = 0) {
    companion object {
        /** One UI's default minimum freeform window size (TaskInfo.defaultMinSize), in dp. */
        const val DEFAULT_MIN_WIDTH_DP = 220
    }
}

/** One task's record from [SplitService.getTaskStates]. */
data class TaskState(
    val found: Boolean,
    val visible: Boolean,
    val windowingMode: Int,
    val bounds: Bounds,
    /** The app's own minimum width in px, or -1. */
    val minWidth: Int,
    /** The system default minimum window size, in dp. */
    val defaultMinSizeDp: Int,
    /** Maximum width and height in px, or -1 (One UI extension; usually unset). */
    val maxWidth: Int,
    val maxHeight: Int,
) {
    val freeform: Boolean get() = windowingMode == Freeform.WINDOWING_MODE_FREEFORM

    /** Limits for this task's column; [floorPx] is TriSplit's own minimum column width. */
    fun limits(density: Float, floorPx: Int) = WindowLimits(
        minWidth = maxOf(floorPx, minWidth, (defaultMinSizeDp * density).roundToInt()),
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    companion object {
        /** Ints per task; must match the array SplitService.getTaskStates builds. */
        const val SIZE = 11

        fun parseAll(raw: IntArray, count: Int): List<TaskState> = List(count) { i ->
            // A daemon from an older build may send fewer values: missing ones read as "not found".
            val r = IntArray(SIZE) { j -> raw.getOrElse(i * SIZE + j) { 0 } }
            TaskState(
                found = r[0] == 1, visible = r[1] == 1, windowingMode = r[2],
                bounds = Bounds(r[3], r[4], r[5], r[6]),
                minWidth = r[7], defaultMinSizeDp = r[8], maxWidth = r[9], maxHeight = r[10],
            )
        }
    }
}
