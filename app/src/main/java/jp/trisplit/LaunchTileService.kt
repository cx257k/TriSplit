package jp.trisplit

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: opens the combination opened last (or the apps chosen on TriSplit's screen)
 * in three columns, from anywhere, with one tap. Its subtitle names those apps.
 */
class LaunchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val apps = LaunchActivity.lastCombination(Prefs(this))
        tile.state = if (apps != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = apps?.joinToString(" / ") { label(it) }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, LaunchActivity::class.java)
            .putExtra(LaunchActivity.EXTRA_LAST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            // Android 12 and 13 only have the Intent version.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun label(component: ComponentName): String =
        AppLabels.ofPackage(this, component.packageName)
}
