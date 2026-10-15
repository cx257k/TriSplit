package jp.trisplit

import android.content.Context
import android.view.WindowManager
import kotlin.math.min

/**
 * Whether TriSplit is on a phone-sized screen, such as the Fold's front (cover) screen: too narrow
 * for three columns, so TriSplit opens there for its settings only. Judged by the whole display,
 * not this window, so a split-screen half of the main screen does not count. Needs a UI context.
 */
fun Context.isFrontScreen(): Boolean {
    val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    return min(bounds.width(), bounds.height()) / resources.displayMetrics.density < MIN_THREE_COLUMN_DP
}

/** The smallest display side, in dp, that three columns need; the main screen is about 860. */
private const val MIN_THREE_COLUMN_DP = 600
