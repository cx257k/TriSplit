package jp.trisplit

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Invisible activity behind the launcher shortcuts and the Quick Settings tile: opens a preset (a
 * preset shortcut), the combination opened last (the tile), or the apps chosen on TriSplit's
 * screen (the static shortcut), then finishes.
 */
class LaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        if (isFrontScreen()) {
            finishWith(getString(R.string.front_screen_blocked))
            return
        }
        val presetId = intent.getStringExtra(PresetShortcuts.EXTRA_PRESET_ID)
        val apps: List<ComponentName> = if (presetId != null) {
            val preset = prefs.presets.firstOrNull { it.id == presetId }
            if (preset == null) {
                finishWith(getString(R.string.preset_shortcut_deleted))
                return
            }
            preset.components.mapNotNull(ComponentName::unflattenFromString)
        } else if (intent.getBooleanExtra(EXTRA_LAST, false)) {
            lastCombination(prefs) ?: run {
                finishWith(getString(R.string.launch_need_apps))
                return
            }
        } else {
            prefs.slots.filterNotNull().takeIf { it.size == Prefs.SLOT_COUNT } ?: run {
                finishWith(getString(R.string.launch_need_apps))
                return
            }
        }
        if (apps.size < 2) {
            finishWith(getString(R.string.launch_need_apps))
            return
        }
        // An app uninstalled since the preset was made would leave an empty column.
        apps.firstOrNull { !isInstalled(it) }?.let { missing ->
            finishWith(getString(R.string.launch_app_missing, missing.packageName))
            return
        }
        // appScope (via LaunchGuard) rather than lifecycleScope: the launch must finish even if this
        // activity goes away. A launch already running (a double tap) is not started again.
        val started = LaunchGuard.run {
            try {
                launchTiled(prefs, apps)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Toast.makeText(this@LaunchActivity, getString(R.string.error_message, t.javaClass.simpleName, t.message), Toast.LENGTH_LONG).show()
            } finally {
                // This activity is invisible; left open it would sit over the screen and eat touches.
                finish()
            }
        }
        if (!started) finish()
    }

    private suspend fun launchTiled(prefs: Prefs, apps: List<ComponentName>) {
        val problems = mutableListOf<String>()
        val handles = prefs.mode == LaunchMode.SHIZUKU && prefs.showHandles
        val session = TripleLauncher(this@LaunchActivity).launch(
            apps, prefs.mode, prefs.gapPx, prefs.allowDuplicateWindows, withBackdrop = handles,
        ) { if (!it.ok) problems += it.describe(this@LaunchActivity) }
        if (session.results.any { it.ok }) prefs.recordHistory(apps)
        if (handles) {
            DividerOverlayService.start(this@LaunchActivity, session)?.let {
                problems += it
                BackdropActivity.dismiss()
            }
        }
        if (problems.isNotEmpty()) {
            Toast.makeText(this@LaunchActivity, problems.joinToString("\n"), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_LAST = "jp.trisplit.LAST"

        /** The combination opened last, or else the apps chosen on TriSplit's screen. */
        fun lastCombination(prefs: Prefs): List<ComponentName>? =
            prefs.history.firstOrNull()?.mapNotNull(ComponentName::unflattenFromString)?.takeIf { it.size >= 2 }
                ?: prefs.slots.filterNotNull().takeIf { it.size == Prefs.SLOT_COUNT }
    }

    // By app, not activity: the launcher picks the app's current entry point if this one is gone.
    private fun isInstalled(component: ComponentName) =
        runCatching { packageManager.getApplicationInfo(component.packageName, 0) }.isSuccess

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
