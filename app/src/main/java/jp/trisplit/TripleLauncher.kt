package jp.trisplit

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class LaunchMode { SHIZUKU, DIRECT }

/**
 * Process-wide scope for work that must outlive the activity that started it. Completing a split
 * dismisses the split, which resizes or recreates TriSplit's activity part-way through.
 */
val appScope = MainScope()

/**
 * One tiled launch at a time, across the app: a double tap, or the button and a shortcut together,
 * would otherwise interleave two launches into six windows. Observable, so buttons can disable.
 */
object LaunchGuard {
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** Runs [block] in [appScope] unless a launch is already running; false if it was skipped. */
    fun run(block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        DividerOverlayService.pauseForLaunch()
        appScope.launch {
            try {
                block()
            } finally {
                busy = false
                DividerOverlayService.resumeAfterLaunch()
            }
        }
        return true
    }
}

data class LaunchResult(
    val column: Int,
    val app: ComponentName,
    val bounds: Bounds,
    val message: String,
    /** Task id of the opened window, or -1 when unknown (always unknown in DIRECT mode). */
    val taskId: Int = -1,
) {
    val ok: Boolean get() = message.startsWith(SplitService.OK_PREFIX)

    /** Line for the in-app log, in the app's language. */
    fun describe(context: Context) =
        context.getString(R.string.log_launch_result, column + 1, app.packageName, bounds, message)

    /** For logcat; not localized. */
    override fun toString() = "column ${column + 1} ${app.packageName} $bounds -> $message"
}

/** TriSplit opened in a split: the app beside it, TriSplit's bounds, and the app it replaced, if any. */
data class SplitContext(val partner: VisibleTask, val ownBounds: Bounds?, val replaced: StageTask?)

/** What was launched, kept so the divider handles can resize the same windows later. */
data class LaunchSession(val area: Bounds, val gap: Int, val results: List<LaunchResult>) {
    val taskIds: List<Int> get() = results.map { it.taskId }

    /** The handles need every window launched with bounds and a distinct, known task. */
    val resizable: Boolean
        get() = results.all { it.ok && it.taskId >= 0 } && taskIds.distinct().size == taskIds.size
}

/** Opens apps as freeform windows tiled into equal columns across the current display. */
class TripleLauncher(private val activity: Activity) {

    /** Tasks being placed in columns right now; see [manage]. */
    private val placing = mutableSetOf<Int>()

    /**
     * Tells the daemon these windows are TriSplit's, before the handles take over. Otherwise its
     * pop-up fixer can take a window that just became a column-sized pop-up (opened, or moved out of
     * split screen) for a pop-up left narrow, and resize it to pop-up size mid-launch.
     */
    private fun manage(service: ISplitService, taskId: Int) {
        if (taskId < 0) return
        placing += taskId
        runCatching { service.setManagedTasks(placing.toIntArray()) }
            .onFailure { Log.w(TAG, "could not mark task $taskId as managed", it) }
    }

    /**
     * When [allowDuplicateWindows] is set, an app that already appeared in an earlier column is
     * launched as an extra task so it gets its own window instead of reusing the first one.
     */
    suspend fun launch(
        requested: List<ComponentName>,
        mode: LaunchMode,
        gapPx: Int,
        allowDuplicateWindows: Boolean,
        withBackdrop: Boolean = false,
        onResult: (LaunchResult) -> Unit,
    ): LaunchSession {
        if (withBackdrop) showBackdrop()
        val apps = requested.map(::launchable)
        val area = activity.windowManager.usableArea()
        val columns = columnsFor(apps, gapPx, area)
        val userId = Process.myUid() / PER_USER_RANGE
        val results = apps.zip(columns).mapIndexed { i, (app, bounds) ->
            // A second window of the same app: always for apps that support it, otherwise only
            // when the user asked for it (it may still reuse the first window).
            val multipleTask = apps.subList(0, i).any { it.packageName == app.packageName } &&
                (allowDuplicateWindows || MultiInstance.supports(activity.packageManager, app))
            val result = runCatching {
                when (mode) {
                    LaunchMode.SHIZUKU -> startViaShizuku(i, app, bounds, userId, multipleTask)
                    LaunchMode.DIRECT -> {
                        activity.startActivity(
                            Freeform.intentFor(app, multipleTask), Freeform.options(bounds).toBundle(),
                        )
                        LaunchResult(i, app, bounds, SplitService.OK_PREFIX)
                    }
                }
            }.getOrElse { LaunchResult(i, app, bounds, errorMessage(it)) }
            Log.i(TAG, result.toString())
            onResult(result)
            // Give WindowManager time to settle each window before the next launch.
            delay(LAUNCH_INTERVAL_MS)
            result
        }
        val checked = if (mode == LaunchMode.SHIZUKU) markNotWindowed(results, onResult) else results
        return LaunchSession(area, gapPx, checked)
    }

    /**
     * Completes a native split screen into three columns. TriSplit was picked as the other side of
     * the split next to [partner]: the partner leaves the split for its column (keeping its side),
     * and [others] fill the remaining columns, left to right. [onPartnerMoved] runs once the partner
     * has left the split, so TriSplit can close before the system expands it to full screen.
     */
    suspend fun completeSplit(
        partner: VisibleTask,
        partnerColumn: Int,
        others: List<ComponentName>,
        gapPx: Int,
        withBackdrop: Boolean = false,
        existing: Map<String, Int> = emptyMap(),
        onPartnerMoved: () -> Unit = {},
        onResult: (LaunchResult) -> Unit,
    ): LaunchSession {
        val area = activity.windowManager.usableArea()
        if (withBackdrop) showBackdrop()
        val userId = Process.myUid() / PER_USER_RANGE
        val partnerApp = ComponentName.unflattenFromString(partner.component)
            ?: ComponentName(partner.packageName, "")
        val columns = columnsFor(splitOrder(partnerApp, partnerColumn, others), gapPx, area)
        val results = mutableListOf<LaunchResult>()

        val bounds = columns[partnerColumn]
        val moved = runCatching {
            val service = ShizukuBridge.service()
            val message = withContext(Dispatchers.IO) {
                manage(service, partner.taskId)
                service.moveToFreeform(partner.taskId, bounds.left, bounds.top, bounds.right, bounds.bottom).also {
                    // The daemon reports a failed move in its message; treat it as one.
                    check(it.startsWith(SplitService.OK_PREFIX)) { it }
                    // The bounds set while leaving the split do not always stick; set them again.
                    service.resizeTask(partner.taskId, bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
            }
            LaunchResult(partnerColumn, partnerApp, bounds, message, partner.taskId)
        }.getOrElse { error ->
            LaunchResult(partnerColumn, partnerApp, bounds, errorMessage(error)).let { failed ->
                // The partner's window is gone (closed, or replaced while the picker was open):
                // open the app in its column instead of leaving the column empty.
                runCatching { startViaShizuku(partnerColumn, launchable(partnerApp), bounds, userId, multipleTask = false) }
                    .getOrDefault(failed)
            }
        }
        Log.i(TAG, moved.toString())
        onResult(moved)
        results += moved
        if (moved.ok) onPartnerMoved()
        val usedExisting = mutableSetOf<String>()
        delay(LAUNCH_INTERVAL_MS)

        val freeColumns = columns.indices.filter { it != partnerColumn }
        others.zip(freeColumns).forEachIndexed { i, (app, column) ->
            // An app already on screen (the partner or an earlier pick) needs its own task,
            // otherwise its existing window is reused and one column stays empty.
            val multipleTask = app.packageName == partner.packageName || app in others.subList(0, i)
            // An app that was already in the split (the one TriSplit was dropped onto) keeps its
            // window: moved into its column rather than opened again.
            // Only once: the same app picked again for another column gets a window of its own.
            val existingTask = existing[app.packageName]?.takeIf { usedExisting.add(app.packageName) }
            val result = runCatching {
                val kept = existingTask?.let { task ->
                    // A window that is gone by now is opened afresh instead.
                    runCatching { moveIntoColumn(column, app, columns[column], task) }.getOrNull()?.takeIf { it.ok }
                }
                kept ?: startViaShizuku(column, launchable(app), columns[column], userId, multipleTask)
            }.getOrElse { LaunchResult(column, app, columns[column], errorMessage(it)) }
            Log.i(TAG, result.toString())
            onResult(result)
            results += result
            delay(LAUNCH_INTERVAL_MS)
        }
        // Windows that were taken out of the split can keep showing their split-era frame (reaching
        // up to the status bar) until they redraw, which an idle app only does when touched. A
        // one-pixel resize and back makes them lay out and draw again in their column.
        val movedIds = (listOf(partner.taskId) + existing.values).toSet()
        refresh(results.filter { it.ok && it.taskId in movedIds })
        return LaunchSession(area, gapPx, markNotWindowed(results.sortedBy { it.column }, onResult))
    }

    private suspend fun refresh(moved: List<LaunchResult>) {
        if (moved.isEmpty()) return
        runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) {
                moved.forEach { r -> service.resizeTask(r.taskId, r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom - 1) }
            }
            delay(REFRESH_MS)
            withContext(Dispatchers.IO) {
                moved.forEach { r -> service.resizeTask(r.taskId, r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom) }
            }
        }.onFailure { Log.w(TAG, "could not refresh moved windows", it) }
    }

    /**
     * Apps that do not support multi-window (and are not forced resizable) open full screen even
     * when asked for a pop-up window. Report those instead of OK, so no handles are shown for a
     * layout that is not there.
     */
    private suspend fun markNotWindowed(
        results: List<LaunchResult>, onResult: (LaunchResult) -> Unit,
    ): List<LaunchResult> {
        val ids = results.filter { it.ok && it.taskId >= 0 }.map { it.taskId }
        if (ids.isEmpty()) return results
        val states = runCatching {
            val service = ShizukuBridge.service()
            val raw = withContext(Dispatchers.IO) { service.getTaskStates(ids.toIntArray()) }
            ids.zip(TaskState.parseAll(raw, ids.size)).toMap()
        }.getOrElse { return results }
        return results.map { result ->
            val state = states[result.taskId]
            if (!result.ok || state == null || !state.found || state.freeform) return@map result
            val label = AppLabels.ofPackage(activity, result.app.packageName)
            result.copy(message = activity.getString(R.string.launch_not_windowed, label)).also {
                Log.i(TAG, it.toString())
                onResult(it)
            }
        }
    }

    /** Takes an existing task out of split screen into [bounds]. */
    private suspend fun moveIntoColumn(column: Int, app: ComponentName, bounds: Bounds, taskId: Int): LaunchResult {
        val service = ShizukuBridge.service()
        val message = withContext(Dispatchers.IO) {
            manage(service, taskId)
            service.moveToFreeform(taskId, bounds.left, bounds.top, bounds.right, bounds.bottom).also {
                service.resizeTask(taskId, bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
        return LaunchResult(column, app, bounds, message, taskId)
    }

    private suspend fun startViaShizuku(
        column: Int, app: ComponentName, bounds: Bounds, userId: Int, multipleTask: Boolean,
    ): LaunchResult {
        val service = ShizukuBridge.service()
        val taskId = IntArray(1)
        val message = withContext(Dispatchers.IO) {
            val started = service.startFreeform(
                app.flattenToString(),
                bounds.left, bounds.top, bounds.right, bounds.bottom, userId,
                multipleTask, taskId,
            )
            manage(service, taskId[0])
            // A task that was already open keeps its old bounds; put it in its column.
            if (taskId[0] >= 0) {
                service.resizeTask(taskId[0], bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            started
        }
        return LaunchResult(column, app, bounds, message, taskId[0])
    }

    /** Opens [BackdropActivity] first, so the windows launched next open above it. */
    private suspend fun showBackdrop() {
        BackdropActivity.show(activity)
        delay(BACKDROP_SETTLE_MS)
    }

    /** The display area the columns are laid out in. */
    fun area(): Bounds = activity.windowManager.usableArea()

    /**
     * Where each app will go: equal columns, widened where an app declares a minimum width (One UI's
     * Clock: 788 px). Read before launching, so windows open in their final place instead of the
     * system pushing them wider a moment later, which showed as a second jump after the launch.
     */
    fun columnsFor(apps: List<ComponentName>, gapPx: Int, area: Bounds = area()): List<Bounds> {
        val density = activity.resources.displayMetrics.density
        val floor = (WindowLimits.DEFAULT_MIN_WIDTH_DP * density).roundToInt()
        val minWidths = apps.map { app ->
            val declared = runCatching { activity.packageManager.getActivityInfo(app, 0).windowLayout?.minWidth }
                .getOrNull() ?: -1
            maxOf(floor, declared)
        }
        val equal = TripleLayout.columns(area, gapPx, apps.size)
        // The widths these apps had last time, if remembered; otherwise equal columns.
        val desired = Prefs(activity).widthRatios(apps.map { it.packageName })
            ?.let { TripleLayout.dividersFromRatios(area, it) }
            ?: TripleLayout.dividersOf(equal, gapPx)
        val dividers = TripleLayout.fitDividers(area, gapPx, minWidths, desired)
        return TripleLayout.columnsFromDividers(area, gapPx, dividers)
    }

    /** All apps of a completed split, left to right: [partner] in [partnerColumn], [others] in the rest. */
    fun splitOrder(partner: ComponentName, partnerColumn: Int, others: List<ComponentName>): List<ComponentName> {
        val free = others.iterator()
        return List(others.size + 1) { column -> if (column == partnerColumn) partner else free.next() }
    }

    /**
     * Recently used apps from [apps], most recent first, for the drawer's "Recent" row.
     * Apps in [exclude] (e.g. the one already on screen) are left out.
     */
    suspend fun recentApps(apps: List<AppEntry>, exclude: Set<String> = emptySet()): List<AppEntry> {
        val service = ShizukuBridge.service()
        val packages = withContext(Dispatchers.IO) { service.getRecentPackages() }
        val byPackage = apps.groupBy { it.component.packageName }
        return packages.filter { it !in exclude }.mapNotNull { byPackage[it]?.firstOrNull() }.take(RECENT_APPS_LIMIT)
    }

    /**
     * The split-screen app next to TriSplit's [ownTaskId], TriSplit's own bounds, and the app to
     * keep besides it: a third app on screen (TriSplit put in the top or bottom of one side of a
     * split), else the app TriSplit was dropped onto. Null outside a split.
     */
    suspend fun findSplitPartner(ownTaskId: Int): SplitContext? {
        val service = ShizukuBridge.service()
        val tasks = withContext(Dispatchers.IO) { service.getVisibleTasks() }.mapNotNull(VisibleTask::parse)
        // One UI opens split screen from recents through a helper screen of the home app, which
        // sits in the split for a moment; it is not the app the user picked.
        val ignored = homePackages() + activity.packageName
        val partner = VisibleTask.findSplitPartner(tasks, ownTaskId, ignored) ?: return null
        val stageTasks = runCatching {
            withContext(Dispatchers.IO) { service.getStageTasks() }.mapNotNull(StageTask::parse)
        }.getOrDefault(emptyList())
        // An app still on screen beats one hidden under TriSplit: both visible apps stay, and only
        // one is left to pick. (The hidden one, if any, comes back in the split when TriSplit goes.)
        val companion = VisibleTask.findCompanion(tasks, ownTaskId, ignored, partner)
            ?.let { StageTask(it.taskId, -1, true, it.component) }
        val kept = companion ?: StageTask.findReplaced(stageTasks, ownTaskId, ignored)
            ?.takeIf { it.packageName != partner.packageName }
        return SplitContext(partner, tasks.firstOrNull { it.taskId == ownTaskId }?.bounds, kept)
    }

    /**
     * [app] itself if it can be started, else its app's current entry point: a preset or history
     * entry can name an activity the app has since renamed or removed.
     */
    private fun launchable(app: ComponentName): ComponentName {
        val pm = activity.packageManager
        if (runCatching { pm.getActivityInfo(app, 0) }.isSuccess) return app
        return pm.getLaunchIntentForPackage(app.packageName)?.component ?: app
    }

    private fun homePackages(): Set<String> =
        activity.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            // Settings registers a fallback home (FallbackHome, negative priority); it is a real app here.
            .filter { it.priority >= 0 }
            .map { it.activityInfo.packageName }
            .toSet()

    private fun errorMessage(t: Throwable): String =
        if (t is ShizukuBridge.UnavailableException) {
            activity.getString(R.string.shizuku_unavailable)
        } else {
            activity.getString(R.string.error_message, t.javaClass.simpleName, t.message)
        }

    companion object {
        const val TAG = "TriSplit"
        private const val PER_USER_RANGE = 100_000
        private const val LAUNCH_INTERVAL_MS = 150L
        private const val REFRESH_MS = 120L
        private const val BACKDROP_SETTLE_MS = 150L
        private const val RECENT_APPS_LIMIT = 12

        /** Apps chosen for more than one column (by app: two entry points of one app share a window). */
        fun duplicates(apps: List<ComponentName?>): Set<ComponentName> =
            apps.filterNotNull().groupBy { it.packageName }.values.filter { it.size > 1 }.map { it.first() }.toSet()
    }
}
