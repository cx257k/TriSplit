package jp.trisplit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Presets as launcher shortcuts: the first few appear when TriSplit's icon is long-pressed, and any
 * preset can be pinned to the home screen. Each opens [LaunchActivity] for that preset.
 */
object PresetShortcuts {
    const val EXTRA_PRESET_ID = "jp.trisplit.PRESET_ID"
    private const val ID_PREFIX = "preset-"
    private const val ICON_PX = 192

    private fun intentFor(context: Context, preset: Preset) =
        Intent(context, LaunchActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_PRESET_ID, preset.id)

    private fun shortcutFor(context: Context, preset: Preset, rank: Int = 0) =
        ShortcutInfoCompat.Builder(context, ID_PREFIX + preset.id)
            .setShortLabel(preset.name.ifBlank { context.getString(R.string.presets_heading) })
            .setLongLabel(preset.name.ifBlank { context.getString(R.string.presets_heading) })
            .setIcon(IconCompat.createWithBitmap(iconFor(context, preset)))
            .setIntent(intentFor(context, preset))
            .setRank(rank)
            .build()

    /** Asks the home screen to add a shortcut for [preset]; false if it cannot. */
    fun pin(context: Context, preset: Preset): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcutFor(context, preset), null) }
            .getOrDefault(false)
    }

    /**
     * Brings the shortcuts up to date with [presets]: the long-press menu shows the first ones,
     * pinned shortcuts follow renames and app changes, and those of deleted presets are disabled.
     */
    fun update(context: Context, presets: List<Preset>) {
        runCatching {
            // One place is taken by the static "open the chosen apps" shortcut.
            val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(0)
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                presets.take(room).mapIndexed { i, p -> shortcutFor(context, p, rank = i) },
            )
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }
                .filter { it.startsWith(ID_PREFIX) }
            val current = presets.associateBy { ID_PREFIX + it.id }
            ShortcutManagerCompat.updateShortcuts(context, pinned.mapNotNull { id -> current[id]?.let { shortcutFor(context, it) } })
            val gone = pinned.filter { it !in current }
            if (gone.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.preset_shortcut_deleted))
            }
        }
    }

    /** The preset's apps side by side on a rounded tile, like the three columns they open in. */
    private fun iconFor(context: Context, preset: Preset): Bitmap {
        val bitmap = createBitmap(ICON_PX, ICON_PX)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val corner = ICON_PX * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()), corner, corner, paint)
        val components = preset.components.mapNotNull(ComponentName::unflattenFromString)
        if (components.isEmpty()) return bitmap
        val inset = ICON_PX * 0.08f
        val cell = (ICON_PX - 2 * inset) / components.size
        val size = (cell * 0.9f).toInt().coerceAtMost((ICON_PX * 0.5f).toInt())
        components.forEachIndexed { i, cn ->
            val pm = context.packageManager
            val icon = runCatching { pm.getActivityIcon(cn) }
                .recoverCatching { pm.getApplicationIcon(cn.packageName) }
                .map { it.toBitmap(size, size) }
                .getOrNull()
                ?: return@forEachIndexed
            val left = inset + cell * i + (cell - size) / 2
            canvas.drawBitmap(icon, left, (ICON_PX - size) / 2f, null)
        }
        return bitmap
    }
}
