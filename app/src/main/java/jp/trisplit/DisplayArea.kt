package jp.trisplit

import android.view.WindowInsets
import android.view.WindowManager

/**
 * Whole display minus status bar, navigation/taskbar and cutout, independent of any window's size.
 * Needs the WindowManager of a UI context (an activity or a window context).
 */
fun WindowManager.usableArea(): Bounds {
    val metrics = maximumWindowMetrics
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
    )
    val b = metrics.bounds
    return Bounds(b.left + insets.left, b.top + insets.top, b.right - insets.right, b.bottom - insets.bottom)
}

/** Full display bounds, including system bars. */
fun WindowManager.displayBounds(): Bounds =
    maximumWindowMetrics.bounds.let { Bounds(it.left, it.top, it.right, it.bottom) }
