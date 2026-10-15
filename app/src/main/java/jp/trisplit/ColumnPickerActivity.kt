package jp.trisplit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app drawer, opened as a pop-up window over one column, to change that column's app while
 * the other two stay. Picking an app hands it to [DividerOverlayService]; going back, home or
 * anywhere else leaves the column as it was.
 */
class ColumnPickerActivity : ComponentActivity() {

    private var column = -1
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        instance = this
        column = intent.getIntExtra(EXTRA_COLUMN, -1)
        // Apps in the other columns: only apps that can open a second window may be picked again.
        val taken = intent.getStringArrayExtra(EXTRA_TAKEN).orEmpty().toSet()
        val current = intent.getStringExtra(EXTRA_CURRENT)
        val columnName = getString(COLUMN_NAMES.getOrElse(column) { R.string.column_center })
        val prefs = Prefs(this)
        // One UI nudges a new pop-up window aside from the bounds it was asked for; put it exactly
        // over the column.
        intent.getIntArrayExtra(EXTRA_BOUNDS)?.takeIf { it.size == 4 }?.let { b ->
            val task = taskId
            appScope.launch {
                runCatching {
                    val service = ShizukuBridge.service()
                    withContext(Dispatchers.IO) { service.resizeTask(task, b[0], b[1], b[2], b[3]) }
                }
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
                var recents by remember { mutableStateOf(emptyList<AppEntry>()) }
                var favorites by remember { mutableStateOf(prefs.favorites) }
                LaunchedEffect(Unit) {
                    val loaded = AppCatalog.load(this@ColumnPickerActivity)
                    apps = loaded
                    recents = runCatching {
                        TripleLauncher(this@ColumnPickerActivity).recentApps(loaded, exclude = taken + listOfNotNull(current))
                    }.getOrDefault(emptyList())
                }
                WallpaperBackground(dark) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .systemBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.replace_column_title, columnName),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            AppDrawer(
                                apps = apps,
                                favorites = favorites,
                                recents = recents,
                                compact = true,
                                onPick = { app -> pick(app.component, app.label, taken, current) },
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
            }
        }
    }

    private fun pick(app: ComponentName, label: String, taken: Set<String>, current: String?) {
        if (reported) return
        when {
            // The same app again: nothing to change.
            app.packageName == current -> cancel()
            app.packageName in taken && !MultiInstance.supports(packageManager, app) ->
                Toast.makeText(this, getString(R.string.complete_split_already_open, label), Toast.LENGTH_SHORT).show()
            else -> {
                reported = true
                DividerOverlayService.onColumnPicked(column, app)
                finishAndRemoveTask()
            }
        }
    }

    private fun cancel() {
        if (!reported) {
            reported = true
            DividerOverlayService.onColumnPickCancelled()
        }
        finishAndRemoveTask()
    }

    // Leaving the picker (home, recents, another app) counts as changing nothing.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) cancel()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (!reported) {
            reported = true
            DividerOverlayService.onColumnPickCancelled()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_COLUMN = "column"
        private const val EXTRA_TAKEN = "taken"
        private const val EXTRA_CURRENT = "current"
        private const val EXTRA_BOUNDS = "bounds"
        private val COLUMN_NAMES = listOf(R.string.column_left, R.string.column_center, R.string.column_right)

        @Volatile
        private var instance: ColumnPickerActivity? = null

        /** Opens the picker as a pop-up window at [bounds], the column being changed. False if it could not open. */
        fun open(context: Context, column: Int, bounds: Bounds, current: String, taken: List<String>): Boolean =
            runCatching {
                context.startActivity(
                    Intent(context, ColumnPickerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_COLUMN, column)
                        .putExtra(EXTRA_BOUNDS, intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        .putExtra(EXTRA_CURRENT, current)
                        .putExtra(EXTRA_TAKEN, taken.toTypedArray()),
                    Freeform.options(bounds).toBundle(),
                )
            }.isSuccess

        /** Closes an open picker without changing anything (e.g. the screen rotated). */
        fun close() {
            instance?.cancel()
        }
    }
}
