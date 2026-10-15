package jp.trisplit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val component: ComponentName, val label: String)

/**
 * Launchable apps, kept for the life of the process so the pickers open instantly. Labels load in
 * one pass; icons load lazily per row, because loading every icon up front took seconds.
 */
object AppCatalog {
    private const val ICON_PX = 96
    private const val ICON_CACHE_SIZE = 256

    @Volatile
    private var cache: List<AppEntry>? = null
    private val icons = LruCache<ComponentName, ImageBitmap>(ICON_CACHE_SIZE)

    /** The cached list, or a fresh one when nothing is cached yet. */
    suspend fun load(context: Context): List<AppEntry> = cache ?: refresh(context)

    /** Re-reads the app list (call early, e.g. when TriSplit opens) and caches it. */
    suspend fun refresh(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppEntry(ComponentName(it.activityInfo.packageName, it.activityInfo.name), AppLabels.of(context, it.activityInfo)) }
            .sortedBy { it.label.lowercase() }
    }.also { cache = it }

    suspend fun icon(context: Context, component: ComponentName): ImageBitmap? {
        icons.get(component)?.let { return it }
        return withContext(Dispatchers.IO) {
            // A saved component can be an entry point the app has since removed; its app icon still fits.
            val pm = context.packageManager
            runCatching { pm.getActivityIcon(component) }
                .recoverCatching { pm.getApplicationIcon(component.packageName) }
                .map { it.toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
                .getOrNull()
        }?.also { icons.put(component, it) }
    }
}
