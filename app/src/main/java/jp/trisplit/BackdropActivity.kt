package jp.trisplit

import android.app.ActivityOptions
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Sits full screen behind the tiled windows, showing the home-screen wallpaper.
 *
 * The tiled windows are freeform and start below the status bar, so the status bar takes its
 * colours from whatever full-screen app is behind them. Without this, that was the app opened
 * before (TriSplit's own screen, or any other), and the status bar changed whenever it did. With
 * it, the area around the windows looks like the home screen and the status bar stays steady,
 * its icons matched to the wallpaper's brightness. The app that was behind is also stopped.
 *
 * When one tiled window is sent to split screen from its window menu, One UI pairs it with the
 * full-screen task behind it, which is this backdrop, instead of offering its app picker. The
 * backdrop then becomes that picker: the app drawer, and the chosen app opens on this side.
 */
class BackdropActivity : ComponentActivity() {

    private var inSplit by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        instance = this
        inSplit = isInMultiWindowMode
        val colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val darkIcons = colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                if (inSplit) {
                    WallpaperBackground(dark) { OtherSidePicker() }
                } else {
                    // Some apps leave the navigation bar's strip at the bottom of a pop-up window
                    // transparent instead of drawing behind it; over the wallpaper that showed as a
                    // coloured band under the window. A plain strip in the theme's colour hides it.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        inSplit = isInMultiWindowMode
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The app drawer, for the side of the split this backdrop ended up in. */
    @androidx.compose.runtime.Composable
    private fun OtherSidePicker() {
        val context = LocalContext.current
        val prefs = remember { Prefs(context) }
        var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
        var recents by remember { mutableStateOf(emptyList<AppEntry>()) }
        var favorites by remember { mutableStateOf(prefs.favorites) }
        LaunchedEffect(Unit) {
            val loaded = AppCatalog.load(context)
            apps = loaded
            recents = runCatching { TripleLauncher(this@BackdropActivity).recentApps(loaded) }.getOrDefault(emptyList())
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.split_other_side_title), style = MaterialTheme.typography.titleLarge)
                AppDrawer(
                    apps = apps,
                    favorites = favorites,
                    recents = recents,
                    onPick = ::openHere,
                    onToggleFavorite = { app ->
                        val key = app.component.flattenToString()
                        favorites = if (key in favorites) favorites - key else favorites + key
                        prefs.favorites = favorites
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    /**
     * Opens [app] in this side of the split, then removes the backdrop under it. The option keeps
     * the launch in the focused stage (this one) rather than letting it replace the other side.
     */
    private fun openHere(app: AppEntry) {
        val options = ActivityOptions.makeBasic()
        // Without the option the app may open on the other side; still better than nothing.
        runCatching { HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchInFocusedStageRoot", true) }
            .onFailure { Log.w(TripleLauncher.TAG, "setLaunchInFocusedStageRoot unavailable", it) }
        val opened = runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                options.toBundle(),
            )
        }.onFailure { Log.w(TripleLauncher.TAG, "could not open ${app.component} beside the split", it) }
        // Removing the backdrop ends the split; only do it once the app is really opening here.
        if (opened.isFailure) {
            val error = opened.exceptionOrNull()
            Toast.makeText(this, getString(R.string.error_message, error?.javaClass?.simpleName, error?.message), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            // Let the app take this side first; removing the backdrop earlier would end the split.
            delay(OPEN_SETTLE_MS)
            finishAndRemoveTask()
        }
    }

    companion object {
        /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN (hidden). */
        private const val WINDOWING_MODE_FULLSCREEN = 1
        private const val OPEN_SETTLE_MS = 600L

        @Volatile
        private var instance: BackdropActivity? = null

        /** The backdrop's task, to bring it back with the windows; null when there is none. */
        val taskId: Int? get() = instance?.taskId

        /**
         * Opens the backdrop full screen. Launched from a split-screen half, a new task would join
         * the split, so full screen is requested explicitly.
         */
        fun show(context: Context) {
            runCatching {
                val options = ActivityOptions.makeBasic()
                HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN)
                context.startActivity(
                    Intent(context, BackdropActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    options.toBundle(),
                )
            }.onFailure { Log.w(TripleLauncher.TAG, "could not show the backdrop", it) }
        }

        /** Removes the backdrop, unless it is serving as the split screen's app picker. */
        fun dismiss() {
            val backdrop = instance ?: return
            if (backdrop.isInMultiWindowMode) return
            backdrop.finishAndRemoveTask()
        }
    }
}
