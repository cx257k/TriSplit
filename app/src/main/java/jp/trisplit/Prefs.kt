package jp.trisplit

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit

/** Saved preset: the three apps (left to right), launch mode, gap and options. */
class Prefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("preset", Context.MODE_PRIVATE)

    var slots: List<ComponentName?>
        get() = List(SLOT_COUNT) { i -> prefs.getString("slot$i", null)?.let(ComponentName::unflattenFromString) }
        set(value) = prefs.edit { value.forEachIndexed { i, cn -> putString("slot$i", cn?.flattenToString()) } }

    var mode: LaunchMode
        get() = prefs.getString("mode", null)?.let { runCatching { LaunchMode.valueOf(it) }.getOrNull() }
            ?: LaunchMode.SHIZUKU
        set(value) = prefs.edit { putString("mode", value.name) }

    var gapPx: Int
        get() = prefs.getInt("gap", 0)
        set(value) = prefs.edit { putInt("gap", value) }

    /** Open a second window when the same app is chosen for more than one column. */
    var allowDuplicateWindows: Boolean
        get() = prefs.getBoolean("allowDuplicateWindows", false)
        set(value) = prefs.edit { putBoolean("allowDuplicateWindows", value) }

    /** Show draggable divider handles after a Shizuku launch. */
    var showHandles: Boolean
        get() = prefs.getBoolean("showHandles", true)
        set(value) = prefs.edit { putBoolean("showHandles", value) }

    /**
     * When one tiled window is made full screen or put into split screen from its window menu,
     * close the other two, as if their close buttons were pressed.
     */
    var closeOthersOnPromote: Boolean
        get() = prefs.getBoolean("closeOthersOnPromote", true)
        set(value) = prefs.edit { putBoolean("closeOthersOnPromote", value) }

    /** When one of the three tiled windows is closed, put the other two in native split screen. */
    var splitOnClose: Boolean
        get() = prefs.getBoolean("splitOnClose", true)
        set(value) = prefs.edit { putBoolean("splitOnClose", value) }

    /** In the split-screen drawer, open the three columns as soon as both free columns are filled. */
    var autoOpenSplit: Boolean
        get() = prefs.getBoolean("autoOpenSplit", true)
        set(value) = prefs.edit { putBoolean("autoOpenSplit", value) }

    /** Favourite apps for the app drawer, as flattened component names. */
    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", null)?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet("favorites", value) }

    /** Saved app combinations, in the order they were saved. */
    var presets: List<Preset>
        get() = Preset.decode(prefs.getString("presets", null))
        set(value) = prefs.edit { putString("presets", Preset.encode(value)) }

    /** The first-run setup guide was finished or skipped. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) = prefs.edit { putBoolean("onboardingDone", value) }

    /** Open a combination at the widths it last had, instead of equal columns. */
    var rememberWidths: Boolean
        get() = prefs.getBoolean("rememberWidths", true)
        set(value) = prefs.edit { putBoolean("rememberWidths", value) }

    /** Divider positions last used for these apps, left to right, as fractions of the width. */
    fun widthRatios(packages: List<String>): List<Float>? {
        if (!rememberWidths) return null
        return prefs.getString(ratioKey(packages), null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { r -> r.size == packages.size - 1 && r.zipWithNext().all { (a, b) -> a < b } && r.all { it in 0f..1f } }
    }

    fun saveWidthRatios(packages: List<String>, ratios: List<Float>?) = prefs.edit {
        if (ratios == null) remove(ratioKey(packages)) else putString(ratioKey(packages), ratios.joinToString(","))
    }

    private fun ratioKey(packages: List<String>) = "ratios:" + packages.joinToString("|")

    /** Combinations opened lately, most recent first, as flattened component names. */
    var history: List<List<String>>
        get() = Preset.decode(prefs.getString("history", null)).map { it.components }
        set(value) = prefs.edit {
            putString("history", Preset.encode(value.mapIndexed { i, c -> Preset("h$i", "", c) }))
        }

    /** Remembers [components] as the latest combination opened. */
    fun recordHistory(components: List<ComponentName>) {
        if (components.isEmpty()) return
        // Recorded by each app's launcher entry: a window's own activity (a login or detail screen,
        // when taken over from split screen) may not be startable from outside the app.
        val pm = context.packageManager
        val entry = components.map { cn -> (pm.getLaunchIntentForPackage(cn.packageName)?.component ?: cn).flattenToString() }
        val packages = entry.map { it.substringBefore('/') }
        history = (listOf(entry) + history.filter { old -> old.map { it.substringBefore('/') } != packages }).take(HISTORY_SIZE)
    }

    companion object {
        const val SLOT_COUNT = 3
        private const val HISTORY_SIZE = 5
    }
}
