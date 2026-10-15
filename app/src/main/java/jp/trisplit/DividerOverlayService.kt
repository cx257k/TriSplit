package jp.trisplit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shows a draggable handle on each border between the tiled windows. Tapping a handle swaps the
 * two windows next to it. Rotating the screen re-tiles the windows for the new orientation,
 * keeping the column proportions and order.
 *
 * While shown, the windows are polled: the handles hide when any window is off screen (home,
 * another full-screen app, maximised), stop when one is closed, and follow a window the user
 * resized with One UI's own edge drag by resizing its neighbours.
 *
 * Resizing an app window makes the app relayout and redraw, which cannot keep up with a finger.
 * So, like One UI's own split screen, a drag only moves our overlay: a veil showing each column's
 * target size covers the apps, and the windows are resized once on release through
 * [SplitService.resizeTask]. Runs as a foreground service so the handles survive while TriSplit
 * itself is in the background.
 */
class DividerOverlayService : Service() {

    /** [display] is the full display the layout was made for; it tells a rotation from a fold. */
    private data class Layout(
        val area: Bounds, val display: Bounds, val gap: Int, val taskIds: List<Int>, val packages: List<String>,
    )

    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Window context for the overlays: it follows the display, so it reports rotations. */
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private val displayCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onDisplayChanged()

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    private var layout: Layout? = null
    private var dividers: List<Int> = emptyList()
    private var appliedColumns: List<Bounds> = emptyList()
    private val handles = mutableListOf<HandleView>()
    private var veil: VeilView? = null
    private var releaseJob: Job? = null
    private var pollJob: Job? = null
    private var dragging = false
    private var handlesShown = true

    /** Bounds seen by the previous poll; a change is only acted on once it holds for two polls. */
    private var lastPolled: List<Bounds>? = null

    /**
     * Set after we resize. If the next stable poll differs from what we asked for, the system
     * adjusted the bounds itself, so adopt them instead of fighting it.
     */
    private var verifyPending = false

    /** Whether the current verify cycle already re-tiled once; limits [poll] to one correction. */
    private var retiled = false

    /** Until this uptime, window moves are treated as the system settling after a rotation. */
    private var rotationSettleUntil = 0L

    /** A rotation re-tile is running; handles ignore new touches until it finishes. */
    private var rotating = false

    /**
     * A new launch is placing windows; see [pauseForLaunch]. The windows of the layout shown now may
     * be reused or covered by it, which would otherwise read as the group being hidden and restored.
     */
    private var launchPaused = false

    /** Told by the daemon about task changes, so the handles hide at once; see [onTasksChanged]. */
    private val taskEvents = object : ITaskEvents.Stub() {
        override fun onTasksChanged() {
            mainHandler.post { this@DividerOverlayService.onTasksChanged() }
        }
    }

    /** Other apps' pop-ups hidden behind the columns, and the chip offering them; see [updateBuriedChip]. */
    private var buriedIds: List<Int> = emptyList()
    private var buriedChip: BuriedChipView? = null

    /**
     * The app that was opened over the columns (e.g. from a notification) and made TriSplit tuck
     * them into the pop-up tray; when it is closed, the columns come back. See [pushAside].
     */
    private var pushedAsideBy: Int? = null

    /** Home screen apps: the home screen is not "another app opened over the columns". */
    private val homePackages by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    /** Consecutive polls in which only some of a hidden group were back; see [poll]. */
    private var partialPolls = 0

    /** Consecutive polls in which a window was missing; see [CLOSED_CONFIRM_POLLS]. */
    private var missingPolls = 0

    /** Consecutive polls in which a window was full screen or in split screen; see [CLOSED_CONFIRM_POLLS]. */
    private var promotedPolls = 0

    /** Window bounds last seen lifted by the keyboard; left alone while they stay so. See [poll]. */
    private var imeShifted: List<Bounds>? = null
    private var imeCheckedAt = 0L

    /** Last tap on a handle, to spot a double tap (equal widths). */
    private var lastTapIndex = -1
    private var lastTapAt = 0L

    /** stop() was called; a new layout must not be handed to this instance any more. */
    private var stopping = false

    /** Lifting a cover no handles took over; cancelled if a layout arrives after all. */
    private var endCoverJob: Job? = null

    /** All windows were out of view at once; the next one to reappear brings the rest back. */
    private var groupHidden = false

    /** Handing the windows over to native split screen; polling stops. */
    private var converting = false

    /** Bumped on rotation so a gesture that started before it is abandoned. */
    private var gestureGeneration = 0

    /** Swap button shown next to handle [swapButtonIndex] after a tap, or null. */
    /** The menu shown by tapping a handle: change the left app, swap, change the right app. */
    private var swapButton: View? = null

    /** The column whose app is being changed in [ColumnPickerActivity]; polling waits meanwhile. */
    private var replacing: Int? = null
    private var swapButtonIndex = -1

    /**
     * When an outside touch last dismissed the swap button. Tapping the same handle to close the
     * button first delivers that outside touch, so the tap must not reopen it right away.
     */
    private var swapButtonDismissedAt = 0L
    private var swapButtonDismissedIndex = -1
    private val swapButtonTimeout = Runnable { hideSwapButton() }

    private val density get() = resources.displayMetrics.density
    private val handleWidthPx get() = (HANDLE_WIDTH_DP * density).roundToInt()
    private val handleHeightPx get() = (HANDLE_HEIGHT_DP * density).roundToInt()
    private val minColumnWidthPx get() = (WindowLimits.DEFAULT_MIN_WIDTH_DP * density).roundToInt()

    /**
     * Size limits per column, updated by every poll. Some apps refuse to get narrower than their own
     * minimum (One UI's Clock: 788 px); clamping the dividers to it keeps the columns from
     * overlapping. A maximum makes the window smaller than its column (see [targetWindows]).
     */
    private var limits: List<WindowLimits> = emptyList()
    private val minWidths: List<Int> get() = limits.map { it.minWidth }

    /** Area for which the "too wide to fit" notice was already shown, so it shows once per orientation. */
    private var tooWideNoticeArea: Bounds? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The veil is up as a cover (see [cover]) for a layout still being opened. The handles take over
     * from it in [show]; without them [endCover] lifts it.
     */
    private var covering = false

    private fun stop() {
        stopping = true
        stopSelf()
    }

    /** Stops a service that was only covering (see [cover]) if no layout arrives in time. */
    private val stopIfIdle = Runnable { if (layout == null) stop() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        windowContext.registerComponentCallbacks(displayCallbacks)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.handles_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_EQUALIZE, ACTION_SAVE_PRESET, ACTION_CLOSE_ALL -> {
                if (layout == null && !covering) {
                    stop()
                } else when (intent.action) {
                    ACTION_EQUALIZE -> equalize()
                    ACTION_SAVE_PRESET -> saveAsPreset()
                    ACTION_CLOSE_ALL -> closeAll()
                }
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_COVER) {
            val area = intent.getIntArrayExtra(EXTRA_AREA)
            val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)
            if (area != null && packages != null && Settings.canDrawOverlays(this)) {
                val from = intent.getIntArrayExtra(EXTRA_FROM)?.toList()?.chunked(4)?.map { Bounds(it[0], it[1], it[2], it[3]) }
                val target = intent.getIntArrayExtra(EXTRA_COLUMNS)?.toList()?.chunked(4)?.map { Bounds(it[0], it[1], it[2], it[3]) }
                showCover(
                    Bounds(area[0], area[1], area[2], area[3]), intent.getIntExtra(EXTRA_GAP, 0), packages.toList(), from,
                    intent.getIntExtra(EXTRA_LIVE_COLUMN, -1), target,
                )
            } else if (layout == null) {
                stop()
            }
            return START_NOT_STICKY
        }
        val area = intent?.getIntArrayExtra(EXTRA_AREA)
        val taskIds = intent?.getIntArrayExtra(EXTRA_TASK_IDS)
        val packages = intent?.getStringArrayExtra(EXTRA_PACKAGES)
        if (area == null || taskIds == null || packages == null || taskIds.size < 2 ||
            !Settings.canDrawOverlays(this)
        ) {
            stop()
            return START_NOT_STICKY
        }
        show(
            Layout(
                Bounds(area[0], area[1], area[2], area[3]), windowManager.displayBounds(),
                intent.getIntExtra(EXTRA_GAP, 0), taskIds.toList(), packages.toList(),
            ),
            intent.getIntArrayExtra(EXTRA_COLUMNS)?.toList()?.chunked(4)?.map { Bounds(it[0], it[1], it[2], it[3]) },
        )
        return START_NOT_STICKY
    }

    /**
     * A rotation re-tiles the windows for the new orientation. Folding or unfolding switches to
     * another screen, where the layout no longer applies, so the handles close.
     */
    private fun onDisplayChanged() {
        val current = layout ?: return
        if (converting) return
        if (replacing != null) {
            ColumnPickerActivity.close()
            replacing = null
        }
        val display = windowManager.displayBounds()
        if (display == current.display) return
        val rotated = display.width == current.display.height && display.height == current.display.width
        if (!rotated) {
            stopWithMessage(R.string.handles_closed_screen_changed)
            return
        }
        Log.i(TripleLauncher.TAG, "rotation: ${current.display} -> $display dividers=$dividers")
        retile(current.copy(area = windowManager.usableArea(), display = display))
    }

    /**
     * After a rotation the system moves and rescales every freeform window by itself. Keep the
     * column proportions, cover the apps with the veil showing the new layout, and resize until
     * the windows hold the new bounds (the system adjustment can land after ours).
     */
    private fun retile(newLayout: Layout) {
        val old = layout ?: return
        if (converting) return
        dragging = false
        // Placed for the old area; the next poll puts it back where it belongs.
        hideBuriedChip()
        buriedIds = emptyList()
        gestureGeneration++
        hideSwapButton()
        rotating = true
        releaseJob?.cancel()
        finishHandoff()
        rotationSettleUntil = SystemClock.uptimeMillis() + ROTATION_GRACE_MS
        dividers = TripleLayout.scaleDividers(old.area, newLayout.area, newLayout.gap, minWidths, dividers)
        layout = newLayout
        moveHandles()
        val target = targetWindows()
        veil?.let {
            it.area = newLayout.area
            it.columns = currentColumns()
            windowManager.updateViewLayout(it, veilParams(newLayout.area))
            if (handlesShown) it.visibility = View.VISIBLE
        }
        lateinit var job: Job
        job = scope.launch {
            try {
                rotationRetile(target)
            } finally {
                // A newer rotation may have replaced this job; only the current one ends the state.
                if (releaseJob === job) rotating = false
            }
            verifyPending = true
            retiled = false
            lastPolled = null
            delay(VEIL_HOLD_MS)
            veil?.fadeOut(VEIL_FADE_MS)
        }
        releaseJob = job
    }

    private suspend fun rotationRetile(target: List<Bounds>) {
        run {
            for (attempt in 1..ROTATION_ATTEMPTS) {
                delay(ROTATION_SETTLE_MS)
                appliedColumns = emptyList() // the system moved every window, so resize all of them
                applyColumns(target)
                val actual = readStates()?.let(::boundsOf)
                Log.i(TripleLauncher.TAG, "rotation re-tile $attempt: target=$target actual=$actual")
                if (actual == target) break
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (replacing != null) ColumnPickerActivity.close()
        // No handles means no tiled layout to stand behind, unless a newer instance took over.
        if (instance == null) BackdropActivity.dismiss()
        // TriSplit no longer manages any window; the pop-up fixer may act on all of them now, and
        // task events stop (unless a newer instance took over and registered its own).
        // A detached scope: this service's own scope is cancelled below.
        val stopEvents = instance == null
        appScope.launch {
            runCatching {
                val service = ShizukuBridge.service()
                withContext(Dispatchers.IO) {
                    service.setManagedTasks(IntArray(0))
                    if (stopEvents) service.setTaskEvents(null)
                }
            }
        }
        mainHandler.removeCallbacks(stopIfIdle)
        windowContext.unregisterComponentCallbacks(displayCallbacks)
        removeOverlays()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Covers the screen with the veil drawn as the layout being opened, until [show] or [endCover].
     * With [from] (where each app is now, e.g. the halves of a split screen) the panels slide from
     * there into their columns, the way One UI animates its own split screen.
     */
    private fun showCover(
        area: Bounds, gap: Int, packages: List<String>, from: List<Bounds>?, liveColumn: Int, columns: List<Bounds>?,
    ) {
        endCoverJob?.cancel()
        removeOverlays()
        layout = null
        val target = columns?.takeIf { it.size == packages.size } ?: TripleLayout.columns(area, gap, packages.size)
        veil = VeilView(windowContext, area, veilApps(packages)).also {
            // Only the apps still to open get a panel; the one already open is left visible.
            it.fullCover = false
            it.liveColumns = if (liveColumn >= 0) setOf(liveColumn) else emptySet()
            it.columns = from?.takeIf { f -> f.size == target.size } ?: target
            windowManager.addView(it, veilParams(area))
            it.fadeIn(COVER_FADE_IN_MS)
            it.morphColumns(target, COVER_MORPH_MS)
        }
        covering = true
        mainHandler.removeCallbacks(stopIfIdle)
        mainHandler.postDelayed(stopIfIdle, PREPARE_TIMEOUT_MS)
    }

    /** Lifts a cover that no handles took over, once the windows had time to draw. */
    private fun endCover() {
        if (!covering || layout != null) return
        covering = false
        endCoverJob = scope.launch {
            delay(COVER_HOLD_MS)
            veil?.fadeOut(VEIL_FADE_MS) { if (layout == null) stop() } ?: stop()
        }
    }

    private fun veilApps(packages: List<String>) = packages.map { pkg ->
        val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        VeilView.App(info?.loadIcon(packageManager), info?.let { AppLabels.of(this, it) } ?: pkg)
    }

    /** [initialColumns] are the bounds the windows were opened with; equal columns if unknown. */
    private fun show(shown: Layout, initialColumns: List<Bounds>? = null) {
        mainHandler.removeCallbacks(stopIfIdle)
        endCoverJob?.cancel()
        val wasCovering = covering && veil != null
        covering = false
        removeOverlays(keepVeil = wasCovering)
        // Whatever an earlier layout was in the middle of (its jobs were just cancelled) is over.
        launchPaused = false
        pushedAsideBy = null
        if (replacing != null) ColumnPickerActivity.close()
        replacing = null
        dragging = false
        rotating = false
        converting = false
        groupHidden = false
        missingPolls = 0
        promotedPolls = 0
        rotationSettleUntil = 0L
        gestureGeneration++
        // The columns were worked out before the launch. If the screen rotated since, the area no
        // longer fits the display: record the display it was made for, so the first poll sees the
        // rotation and re-tiles.
        val display = shown.display
        val stale = shown.area.right > display.right || shown.area.bottom > display.bottom
        val newLayout = if (stale) shown.copy(display = Bounds(0, 0, display.height, display.width)) else shown
        layout = newLayout
        appliedColumns = initialColumns?.takeIf { it.size == newLayout.taskIds.size }
            ?: TripleLayout.columns(newLayout.area, newLayout.gap, newLayout.taskIds.size)
        dividers = TripleLayout.dividersOf(appliedColumns, newLayout.gap)
        limits = List(newLayout.taskIds.size) { WindowLimits(minColumnWidthPx) }
        tooWideNoticeArea = null

        // Added before the handles so it stacks underneath them. Hidden (and so not obscuring
        // touches to the apps) except during a drag, or briefly when taking over from a cover.
        if (wasCovering) {
            veil?.let {
                it.apps = veilApps(newLayout.packages)
                it.morphColumns(currentColumns(), COVER_MORPH_MS)
            }
        } else {
            veil = VeilView(windowContext, newLayout.area, veilApps(newLayout.packages)).also {
                it.columns = currentColumns()
                it.visibility = View.INVISIBLE
                windowManager.addView(it, veilParams(newLayout.area))
            }
        }

        dividers.indices.forEach { i ->
            val view = HandleView(windowContext).apply {
                setOnTouchListener(DragListener(i))
                // Taking over from a cover, the handles fade in with the windows.
                if (wasCovering) alpha = 0f
            }
            windowManager.addView(view, handleParams(dividers[i], newLayout.area))
            handles += view
        }
        handlesShown = true
        lastPolled = null
        verifyPending = true
        retiled = false
        reportTiled()
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                poll()
            }
        }
        scope.launch {
            runCatching {
                val service = ShizukuBridge.service()
                withContext(Dispatchers.IO) { service.setTaskEvents(taskEvents) }
            }.onFailure { if (it is CancellationException) throw it }
        }
        if (wasCovering) {
            // Keeps polling off (busy) until the freshly opened windows have drawn under the veil.
            releaseJob = scope.launch {
                delay(COVER_HOLD_MS)
                veil?.fadeOut(VEIL_FADE_MS) {
                    veil?.resetStyle()
                    handles.forEach { it.animate().alpha(1f).setDuration(VEIL_FADE_MS) }
                }
            }
        }
    }

    /**
     * Ends a cover hand-off cut short by a rotation, drag or swap: its fade-out end action, which
     * would show the handles and restore the veil's normal style, is dropped when cancelled.
     */
    private fun finishHandoff() {
        veil?.resetStyle()
        handles.forEach {
            it.animate().cancel()
            it.alpha = 1f
        }
    }

    /**
     * Tells the pop-up fixer (see SplitService) which apps are tiled at which bounds, and which
     * tasks TriSplit manages, so an app later opened as a pop-up at its column size can be restored
     * to an ordinary pop-up.
     */
    private fun reportTiled() {
        val current = layout ?: return
        val columns = appliedColumns.takeIf { it.size == current.packages.size } ?: return
        val popups = columns.indices.map { popupBoundsFor(current.area, it) }
        scope.launch {
            runCatching {
                val service = ShizukuBridge.service()
                withContext(Dispatchers.IO) {
                    service.markTiled(
                        current.packages.toTypedArray(),
                        columns.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toIntArray(),
                        popups.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toIntArray(),
                    )
                    service.setManagedTasks(current.taskIds.toIntArray())
                }
            }.onFailure { Log.w(TripleLauncher.TAG, "could not report tiled bounds", it) }
        }
    }

    /** An ordinary centred pop-up, nudged per column so several restored ones do not stack exactly. */
    private fun popupBoundsFor(area: Bounds, column: Int): Bounds {
        val width = maxOf((area.width * POPUP_WIDTH_FRACTION).roundToInt(), minColumnWidthPx)
        val height = (area.height * POPUP_HEIGHT_FRACTION).roundToInt()
        val nudge = (column - 1) * (POPUP_CASCADE_DP * density).roundToInt()
        val left = (area.left + (area.width - width) / 2 + nudge).coerceIn(area.left, area.right - width)
        val top = (area.top + (area.height - height) / 2 + nudge).coerceIn(area.top, area.bottom - height)
        return Bounds(left, top, left + width, top + height)
    }

    /** One [SplitService.getTaskStates] record per column, or null if it could not be read. */
    private suspend fun readStates(): List<TaskState>? {
        val current = layout ?: return null
        val service = runCatching { ShizukuBridge.service() }.getOrElse {
            if (it is CancellationException) throw it
            stopWithMessage(R.string.handles_closed_no_shizuku)
            return null
        }
        val raw = runCatching {
            withContext(Dispatchers.IO) { service.getTaskStates(current.taskIds.toIntArray()) }
        }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TripleLauncher.TAG, "getTaskStates failed", it)
            return null
        }
        return TaskState.parseAll(raw, current.taskIds.size)
    }

    private fun boundsOf(states: List<TaskState>) = states.map { it.bounds }

    /** The recent-apps overview covers the windows without making them invisible; hide the handles then. */
    private suspend fun recentsVisible(): Boolean = runCatching {
        val service = ShizukuBridge.service()
        withContext(Dispatchers.IO) { service.isRecentsVisible() }
    }.getOrDefault(false)

    private suspend fun poll() {
        val current = layout ?: return
        if (busy() || converting || launchPaused || replacing != null) return
        val states = readStates() ?: return
        // A swap, drag or rotation may have changed the layout while the states were read.
        if (layout !== current || busy() || converting) return
        // The system moves (and re-modes) the windows for a rotation or fold before our display
        // callback arrives; deal with that first, so those moves are not taken for the user's.
        if (windowManager.displayBounds() != current.display) {
            onDisplayChanged()
            return
        }
        // One window was made full screen or put into native split screen (its window menu, or
        // dragged to the edge): the user picked that app. Close the other two like their close
        // buttons, so One UI goes on with just that one (in split screen, it then offers to pick the
        // other side). With that setting off, just step aside.
        val promoted = states.indexOfFirst {
            it.found && it.visible &&
                (it.windowingMode == WINDOWING_MODE_FULLSCREEN || it.windowingMode == VisibleTask.WINDOWING_MODE_MULTI_WINDOW)
        }
        promotedPolls = if (promoted >= 0 && SystemClock.uptimeMillis() >= rotationSettleUntil) promotedPolls + 1 else 0
        // Closing the others cannot be undone; a mode seen in one poll may be a passing state.
        if (promoted >= 0 && promotedPolls < CLOSED_CONFIRM_POLLS) return
        if (promoted >= 0) {
            if (Prefs(this).closeOthersOnPromote) {
                closeOthers(current, promoted)
            } else {
                stopWithMessage(R.string.handles_closed_native_split)
            }
            return
        }
        // A window can vanish for a moment while the system moves it; only a window missing in two
        // polls in a row counts as closed.
        val closed = states.count { !it.found }
        missingPolls = if (closed > 0) missingPolls + 1 else 0
        if (closed > 0 && missingPolls < CLOSED_CONFIRM_POLLS) return
        if (closed > 0) {
            val remainingShown = states.filter { it.found }.all { it.visible }
            when {
                // The pop-up tray's close button (or Clear all in recents) closed all of them.
                closed == states.size -> stopWithMessage(R.string.handles_closed)
                // Only while the other two are on screen: from home (the windows minimised to the
                // pop-up tray) converting would pull them back up uninvited.
                closed == 1 && states.size == 3 && remainingShown && Prefs(this).splitOnClose ->
                    convertToSplit(current, states)
                else -> stopWithMessage(R.string.handles_closed_window_closed)
            }
            return
        }
        val newLimits = states.map { it.limits(density, minColumnWidthPx) }
        if (newLimits != limits) Log.i(TripleLauncher.TAG, "column limits: $newLimits")
        limits = newLimits
        if (!TripleLayout.minimumsFit(current.area, current.gap, minWidths) && tooWideNoticeArea != current.area) {
            tooWideNoticeArea = current.area
            Toast.makeText(this, R.string.handles_too_wide, Toast.LENGTH_LONG).show()
        }
        val recents = recentsVisible()
        if (layout !== current || busy()) return
        val shownCount = states.count { it.visible && it.freeform }
        if (!recents && shownCount == states.size) {
            val visible = visibleTasks()
            if (layout !== current) return
            appOverColumns(current, visible)?.let { app ->
                pushAside(current, app)
                return
            }
        }
        if (!recents && shownCount == 0 && pushedAsideBy != null) {
            if (returnWhenClosed(current, states)) return
        }
        if (!recents) {
            when {
                // All windows gone from view: home (One UI minimises them into one pop-up icon),
                // or another app on top.
                shownCount == 0 -> {
                    groupHidden = true
                    partialPolls = 0
                }
                // One came back (one app tapped in the pop-up tray): bring the others back with it.
                // Waits a poll, as "open all" in the tray brings them back one after another.
                groupHidden && shownCount < states.size -> {
                    if (++partialPolls < CLOSED_CONFIRM_POLLS) return
                    pushedAsideBy = null
                    groupHidden = false
                    partialPolls = 0
                    restoreGroup(current, states)
                    return
                }
                // All came back together ("open all" in the tray): put the backdrop behind them again.
                groupHidden && shownCount == states.size -> {
                    pushedAsideBy = null
                    groupHidden = false
                    partialPolls = 0
                    restoreBackdrop(current)
                }
                shownCount == states.size -> groupHidden = false
            }
        }
        val onScreen = shownCount == states.size && !recents
        // Another app's pop-up window over the handles: the handles are drawn above every app, so
        // they would float over it and catch touches meant for it. Hide them until the tiled
        // windows are in front again.
        val visible = if (onScreen) visibleTasks() else emptyList()
        if (layout !== current) return
        updateBuriedChip(current, visible)
        val covered = onScreen && popupOverHandles(current, visible)
        setHandlesShown(onScreen && !covered)
        if (!onScreen || covered) {
            lastPolled = null
            return
        }
        // The system moves the windows for a rotation before our display callback arrives. Without
        // this check those moves would read as the user resizing windows and shift the dividers.
        if (windowManager.displayBounds() != current.display) {
            onDisplayChanged()
            return
        }
        // The taskbar was shown or hidden (or another inset changed) without a rotation: the
        // space for the columns changed, so lay them out again in the new space.
        val area = windowManager.usableArea()
        if (area != current.area && !rotating) {
            Log.i(TripleLauncher.TAG, "usable area changed: ${current.area} -> $area")
            retile(current.copy(area = area))
            return
        }
        val actual = boundsOf(states)
        val stable = actual == lastPolled
        lastPolled = actual
        if (!stable || busy()) return
        if (keyboardLifted(actual)) return
        if (layout !== current || busy()) return
        if (SystemClock.uptimeMillis() < rotationSettleUntil) {
            // Late moves from the system's own rotation handling, not the user: put the windows
            // back and keep the dividers.
            if (actual != targetWindows()) {
                Log.i(TripleLauncher.TAG, "late rotation move undone: actual=$actual")
                appliedColumns = emptyList()
                applyColumns(targetWindows())
            }
            return
        }
        if (verifyPending) {
            verifyPending = false
            if (actual != appliedColumns) {
                // Our bounds did not stick (a reused task kept its old bounds, or the system adjusted
                // them). Rebuild the dividers from what is on screen and re-tile once; if the system
                // still adjusts after that, accept its bounds rather than fight it.
                dividers = TripleLayout.dividersFromBounds(current.area, current.gap, minWidths, actual)
                Log.i(TripleLauncher.TAG, "adopted system bounds: actual=$actual dividers=$dividers")
                moveHandles()
                appliedColumns = actual
                reportTiled()
                val target = targetWindows()
                // When the minimums cannot all fit, the system will push back whatever we do;
                // accept its bounds instead of re-tiling into the same overlap.
                val fits = TripleLayout.minimumsFit(current.area, current.gap, minWidths)
                if (target != actual && !retiled && fits) {
                    retiled = true
                    applyColumns(target)
                    verifyPending = true
                }
            }
            return
        }
        if (actual == appliedColumns) return
        dividers = TripleLayout.reconcile(current.area, current.gap, minWidths, dividers, appliedColumns, actual)
        Log.i(TripleLauncher.TAG, "followed external resize: actual=$actual dividers=$dividers")
        rememberWidths()
        moveHandles()
        appliedColumns = actual
        applyColumns(targetWindows())
        retiled = false
        verifyPending = true
    }

    /**
     * One of the three windows was closed: hand the other two to One UI's native split screen,
     * left one in the main stage, right one in the side stage. The veil, drawn as the two halves
     * of the split, covers the moment the main window goes full screen and the split animates in;
     * then the handles go, as native split screen has its own divider.
     */
    private suspend fun convertToSplit(current: Layout, states: List<TaskState>) {
        val (left, right) = states.indices.filter { states[it].found }
        if (!states[left].freeform || !states[right].freeform) {
            stopWithMessage(R.string.handles_closed_window_closed)
            return
        }
        converting = true
        gestureGeneration++
        hideSwapButton()
        releaseJob?.cancel()
        setHandlesShown(false)
        veil?.let {
            val apps = it.apps
            it.apps = listOf(apps[left], apps[right])
            it.columns = TripleLayout.columns(current.area, current.gap, 2)
            it.visibility = View.VISIBLE
        }
        val result = runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) {
                service.enterSplit(current.taskIds[left], current.taskIds[right], SPLIT_POSITION_BOTTOM_OR_RIGHT)
            }
        }.getOrElse { "enterSplit failed: $it" }
        Log.i(TripleLauncher.TAG, "converted to split: $result")
        delay(SPLIT_VEIL_HOLD_MS)
        stop()
    }

    /**
     * Going home minimizes the three windows into floating icons, and tapping one restores only that
     * window. Restore the other two as well, so the group comes back together like a native split
     * pair; the window the user tapped is brought forward last, so it stays on top.
     */
    private suspend fun restoreGroup(current: Layout, states: List<TaskState>) {
        val tapped = states.indices.filter { states[it].visible }
        val others = states.indices.filter { !states[it].visible }
        // The backdrop first, so it ends up behind the windows again.
        val order = listOfNotNull(BackdropActivity.taskId) + (others + tapped).map { current.taskIds[it] }
        Log.i(TripleLauncher.TAG, "restoring the group: ${others.map { current.taskIds[it] }} with ${tapped.map { current.taskIds[it] }}")
        runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) { service.moveTasksToFront(order.toIntArray()) }
        }.onFailure { Log.w(TripleLauncher.TAG, "could not restore the group", it) }
        lastPolled = null
        verifyPending = true
        retiled = false
    }

    /**
     * The windows came back without the backdrop (it is not part of the pop-up tray). Bring it back
     * behind them, then the windows in their current stacking order, so the top one stays on top.
     */
    private suspend fun restoreBackdrop(current: Layout) {
        val backdrop = BackdropActivity.taskId ?: return
        runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) {
                // Visible tasks come top first.
                val stacking = service.getVisibleTasks().mapNotNull(VisibleTask::parse).map { it.taskId }
                val windows = current.taskIds.sortedByDescending { id -> stacking.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
                service.moveTasksToFront((listOf(backdrop) + windows).toIntArray())
            }
        }.onFailure { Log.w(TripleLauncher.TAG, "could not restore the backdrop", it) }
        lastPolled = null
    }

    private suspend fun closeOthers(current: Layout, keep: Int) {
        converting = true
        hideSwapButton()
        setHandlesShown(false)
        val others = current.taskIds.filterIndexed { i, _ -> i != keep }
        Log.i(TripleLauncher.TAG, "column ${keep + 1} went full screen or split: closing ${others}")
        runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) { service.removeTasks(others.toIntArray()) }
        }.onFailure { Log.w(TripleLauncher.TAG, "could not close the other windows", it) }
        stop()
    }

    /** Resizes one batch at a time: a cancelled job's binder calls still finish on their thread. */
    private val resizeLock = Mutex()

    /**
     * One UI moves a pop-up window up while the keyboard shows in it, and back down after. Such a
     * move (columns unchanged, only the height or vertical position) is left alone instead of being
     * taken for the user's resize and undone, which would push the text field under the keyboard.
     */
    private suspend fun keyboardLifted(actual: List<Bounds>): Boolean {
        val expected = appliedColumns
        val sideways = actual.size != expected.size ||
            actual.indices.any { actual[it].left != expected[it].left || actual[it].right != expected[it].right }
        if (actual == expected || sideways) {
            imeShifted = null
            return false
        }
        val now = SystemClock.uptimeMillis()
        // Asked again now and then, in case the keyboard closed and the window stayed up.
        if (actual == imeShifted && now - imeCheckedAt < IME_RECHECK_MS) return true
        val shown = runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) { service.isImeVisible() }
        }.getOrDefault(false)
        imeCheckedAt = now
        imeShifted = if (shown) actual else null
        if (shown) Log.i(TripleLauncher.TAG, "window moved for the keyboard; leaving it: $actual")
        return shown
    }

    /** Tasks on screen, top first; empty if they cannot be read. */
    private suspend fun visibleTasks(): List<VisibleTask> = runCatching {
        val service = ShizukuBridge.service()
        withContext(Dispatchers.IO) { service.getVisibleTasks() }.mapNotNull(VisibleTask::parse)
    }.getOrElse {
        if (it is CancellationException) throw it
        emptyList()
    }

    /**
     * Other apps' pop-up windows hidden behind the columns. The columns are pop-up windows too and
     * fill the screen, so tapping one buries such a window with no way back to it (One UI ignores a
     * request to keep it on top). A chip at the top of the screen shows their icons; tapping one
     * brings that window back to the front.
     */
    private fun updateBuriedChip(current: Layout, visible: List<VisibleTask>) {
        val firstTiled = visible.indexOfFirst { it.taskId in current.taskIds }
        val buried = if (firstTiled < 0) emptyList() else visible.withIndex().filter { (i, task) ->
            i > firstTiled &&
                task.windowingMode == Freeform.WINDOWING_MODE_FREEFORM &&
                task.taskId !in current.taskIds && task.taskId != BackdropActivity.taskId &&
                visible.take(i).any { above -> above.taskId in current.taskIds && above.bounds.intersects(task.bounds) }
        }.map { it.value }
        val ids = buried.map { it.taskId }
        if (ids == buriedIds) return
        buriedIds = ids
        hideBuriedChip()
        if (buried.isEmpty()) return
        Log.i(TripleLauncher.TAG, "pop-ups behind the columns: $ids")
        val icons = buried.map { runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull() }
        val view = BuriedChipView(windowContext, icons) { index -> bringBack(ids[index]) }.apply {
            contentDescription = getString(R.string.buried_popups)
        }
        windowManager.addView(view, buriedChipParams(view, current.area))
        buriedChip = view
    }

    private fun hideBuriedChip() {
        buriedChip?.let { runCatching { windowManager.removeView(it) } }
        buriedChip = null
    }

    private fun bringBack(taskId: Int) {
        scope.launch {
            runCatching {
                val service = ShizukuBridge.service()
                withContext(Dispatchers.IO) { service.moveTasksToFront(intArrayOf(taskId)) }
            }.onFailure { Log.w(TripleLauncher.TAG, "could not bring pop-up $taskId back", it) }
            lastPolled = null
        }
    }

    /** Centred at the top of the columns, just under the status bar. */
    private fun buriedChipParams(view: BuriedChipView, area: Bounds): WindowManager.LayoutParams {
        val width = view.preferredWidth
        val height = view.preferredHeight
        val x = area.left + (area.width - width) / 2
        val y = area.top + (12 * density).roundToInt()
        return overlayParams(x, y, width, height, extraFlags = 0)
    }

    /**
     * Whether a window that is not one of the tiled ones is stacked above them and overlaps a
     * handle (e.g. an app opened as a pop-up over the three columns).
     */
    private fun popupOverHandles(current: Layout, visible: List<VisibleTask>): Boolean {
        // Visible tasks come top first. Handle i sits on the shared edge of windows i and i + 1, so a
        // pop-up covers it only if it is above at least one of those two and overlaps the handle.
        val order = visible.map { it.taskId }
        val handleTop = current.area.top + (current.area.height - handleHeightPx) / 2
        return dividers.indices.any { i ->
            val lower = maxOf(order.indexOf(current.taskIds[i]), order.indexOf(current.taskIds[i + 1]))
            if (lower <= 0) return@any false
            val handle = Bounds(dividers[i] - handleWidthPx / 2, handleTop, dividers[i] + handleWidthPx / 2, handleTop + handleHeightPx)
            visible.take(lower).any { task ->
                task.taskId !in current.taskIds && task.taskId != BackdropActivity.taskId &&
                    task.windowingMode == Freeform.WINDOWING_MODE_FREEFORM &&
                    handle.intersects(task.bounds)
            }
        }
    }

    /**
     * Something changed among the tasks (going home, recents, another app on top): hide the handles
     * right away if the windows are no longer all on screen, instead of at the next poll. Only
     * hiding happens here; closing, converting and following resizes stay with [poll] and its
     * debouncing, as a burst of events would make those decisions too early.
     */
    private fun onTasksChanged() {
        val current = layout ?: return
        if (!handlesShown || busy() || converting || launchPaused || replacing != null || quickCheck?.isActive == true) return
        quickCheck = scope.launch {
            val states = readStates() ?: return@launch
            if (layout !== current || !handlesShown) return@launch
            if (states.any { !it.visible } || recentsVisible()) {
                setHandlesShown(false)
                lastPolled = null
                return@launch
            }
            // An app just opened behind the columns: move them aside now rather than at the next poll.
            val visible = visibleTasks()
            if (layout !== current) return@launch
            appOverColumns(current, visible)?.let { pushAside(current, it) }
        }
    }

    /**
     * An app opened full screen (or in split screen) while the columns are on screen, e.g. from a
     * notification. One UI keeps pop-up windows above it, so it is stuck behind the columns. It is
     * recognised by sitting above the backdrop, which always stays right behind the columns.
     */
    private fun appOverColumns(current: Layout, visible: List<VisibleTask>): VisibleTask? {
        val backdrop = BackdropActivity.taskId ?: return null
        val backdropIndex = visible.indexOfFirst { it.taskId == backdrop }
        if (backdropIndex < 0) return null
        return visible.take(backdropIndex).firstOrNull { task ->
            task.taskId !in current.taskIds &&
                (task.windowingMode == WINDOWING_MODE_FULLSCREEN || task.windowingMode == VisibleTask.WINDOWING_MODE_MULTI_WINDOW) &&
                task.component.isNotEmpty() && task.packageName !in homePackages
        }
    }

    /** Tucks the columns into the pop-up tray so [app] can be used; see [returnWhenClosed]. */
    private suspend fun pushAside(current: Layout, app: VisibleTask) {
        // The quick check and the poll can both see it; the first one does it.
        if (pushedAsideBy != null) return
        pushedAsideBy = app.taskId
        Log.i(TripleLauncher.TAG, "${app.packageName} opened over the columns: minimising them")
        hideSwapButton()
        setHandlesShown(false)
        val minimized = runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) { service.minimizeTasks(current.taskIds.toIntArray()) }
        }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TripleLauncher.TAG, "could not minimise the columns", it)
            0
        }
        if (minimized == 0) {
            pushedAsideBy = null
            return
        }
        groupHidden = true
        partialPolls = 0
        lastPolled = null
    }

    /**
     * After [pushAside]: once that app is closed (back) and the backdrop is the screen again, the
     * columns come back by themselves. Going home instead leaves them in the pop-up tray, where one
     * tap brings all three back. True if the columns were brought back.
     */
    private suspend fun returnWhenClosed(current: Layout, states: List<TaskState>): Boolean {
        val visible = visibleTasks()
        if (layout !== current) return false
        val backdrop = BackdropActivity.taskId
        val top = visible.firstOrNull { it.windowingMode != Freeform.WINDOWING_MODE_FREEFORM }
        when {
            top == null -> return false
            // Home or another app: the user moved on; the tray brings the columns back.
            top.taskId != backdrop -> {
                if (top.packageName in homePackages || top.taskId != pushedAsideBy) pushedAsideBy = null
                return false
            }
        }
        Log.i(TripleLauncher.TAG, "the app opened over the columns was closed: bringing them back")
        pushedAsideBy = null
        groupHidden = false
        partialPolls = 0
        restoreGroup(current, states)
        return true
    }

    private var quickCheck: Job? = null

    /** A drag or the resize after it is in progress; polling must not interfere. */
    private fun busy() = dragging || releaseJob?.isActive == true

    private fun setHandlesShown(shown: Boolean) {
        if (shown == handlesShown) return
        handlesShown = shown
        if (!shown) hideSwapButton()
        handles.forEach { it.visibility = if (shown) View.VISIBLE else View.INVISIBLE }
    }

    private fun onDragStart() {
        dragging = true
        hideSwapButton()
        releaseJob?.cancel()
        finishHandoff()
        veil?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.columns = currentColumns()
            it.visibility = View.VISIBLE
        }
    }

    private fun onDragMove(newDividers: List<Int>) {
        dividers = newDividers
        moveHandles()
        veil?.columns = currentColumns()
    }

    /** Resize the windows once, then keep the veil up briefly so the apps can redraw underneath it. */
    private fun onDragEnd() {
        dragging = false
        val target = targetWindows()
        Log.i(TripleLauncher.TAG, "drag end: dividers=$dividers")
        rememberWidths()
        releaseJob?.cancel()
        releaseJob = scope.launch {
            applyColumns(target)
            verifyPending = true
            retiled = false
            lastPolled = null
            delay(VEIL_HOLD_MS)
            veil?.fadeOut(VEIL_FADE_MS)
        }
    }

    /** A tap on handle [index] toggles its swap button, like One UI split screen's divider menu. */
    private fun onHandleTap(index: Int) {
        // A second tap right after the first: equal widths, like double-tapping One UI's divider.
        val now = SystemClock.uptimeMillis()
        val doubleTap = index == lastTapIndex && now - lastTapAt < DOUBLE_TAP_MS
        lastTapIndex = if (doubleTap) -1 else index
        lastTapAt = now
        if (doubleTap) {
            equalize()
            return
        }
        val justDismissed = swapButtonDismissedIndex == index &&
            SystemClock.uptimeMillis() - swapButtonDismissedAt < OUTSIDE_TAP_WINDOW_MS
        if (swapButtonIndex == index || justDismissed) {
            hideSwapButton()
        } else {
            showSwapButton(index)
        }
    }

    private fun showSwapButton(index: Int) {
        val area = layout?.area ?: return
        hideSwapButton()
        fun button(icon: MenuIcon, description: String, action: () -> Unit) = SwapButtonView(windowContext, icon).apply {
            contentDescription = description
            setOnClickListener {
                hideSwapButton()
                action()
            }
        }
        val size = (SWAP_BUTTON_DP * density).roundToInt()
        val gap = (MENU_GAP_DP * density).roundToInt()
        // One window for all three: separate windows would each take a tap on another as "outside".
        val view = android.widget.LinearLayout(windowContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            listOf(
                button(MenuIcon.CHANGE_LEFT, getString(R.string.replace_column_button, columnName(index))) { startReplace(index) },
                button(MenuIcon.SWAP, getString(R.string.swap_windows)) { swap(index) },
                button(MenuIcon.CHANGE_RIGHT, getString(R.string.replace_column_button, columnName(index + 1))) { startReplace(index + 1) },
            ).forEachIndexed { i, child ->
                addView(child, android.widget.LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = gap })
            }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    swapButtonDismissedAt = SystemClock.uptimeMillis()
                    swapButtonDismissedIndex = index
                    hideSwapButton()
                    true
                } else {
                    false
                }
            }
        }
        // Added after the handles, so it stacks above them.
        windowManager.addView(view, swapButtonParams(index, area))
        swapButton = view
        swapButtonIndex = index
        mainHandler.postDelayed(swapButtonTimeout, SWAP_BUTTON_TIMEOUT_MS)
    }

    private fun hideSwapButton() {
        mainHandler.removeCallbacks(swapButtonTimeout)
        swapButton?.let { runCatching { windowManager.removeView(it) } }
        swapButton = null
        swapButtonIndex = -1
    }

    /** Centred on the divider, just above the handle; kept inside the area in either orientation. */
    private fun swapButtonParams(index: Int, area: Bounds): WindowManager.LayoutParams {
        val size = (SWAP_BUTTON_DP * density).roundToInt()
        val width = size * 3 + (MENU_GAP_DP * density).roundToInt() * 2
        val spacing = (8 * density).roundToInt()
        val handleTop = area.top + (area.height - handleHeightPx) / 2
        val x = (dividers[index] - width / 2).coerceIn(area.left, area.right - width)
        val y = (handleTop - spacing - size).coerceAtLeast(area.top)
        return overlayParams(x, y, width, size, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
    }

    private fun columnName(column: Int) =
        getString(listOf(R.string.column_left, R.string.column_center, R.string.column_right).getOrElse(column) { R.string.column_center })

    /**
     * Opens the app drawer over [column], so its app can be changed while the other two stay.
     * Polling waits until the drawer reports back (see [onColumnPicked] / [onColumnPickCancelled]).
     */
    private fun startReplace(column: Int) {
        val current = layout ?: return
        if (rotating || converting || busy() || replacing != null) return
        val slot = targetWindows().getOrNull(column) ?: return
        replacing = column
        val others = current.packages.filterIndexed { i, _ -> i != column }
        if (!ColumnPickerActivity.open(this, column, slot, current.packages[column], others)) replacing = null
    }

    private fun columnPicked(column: Int, app: ComponentName) {
        if (replacing != column) return
        scope.launch {
            try {
                replaceColumn(column, app)
            } finally {
                replacing = null
                lastPolled = null
            }
        }
    }

    /**
     * Opens [app] in [column]'s place and takes the app that was there out of the columns (back to
     * full screen, behind everything; it stays in recents). The handles then follow the new window.
     */
    private suspend fun replaceColumn(column: Int, app: ComponentName) {
        val current = layout ?: return
        val bounds = targetWindows().getOrNull(column) ?: return
        val oldTask = current.taskIds[column]
        val otherTasks = current.taskIds.filterIndexed { i, _ -> i != column }
        // Another column already shows this app: it needs a window of its own (the drawer only
        // offers that for apps that support it).
        val multipleTask = current.packages.filterIndexed { i, _ -> i != column }.contains(app.packageName)
        val newTask = IntArray(1)
        val result = runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) {
                service.startFreeform(
                    app.flattenToString(), bounds.left, bounds.top, bounds.right, bounds.bottom,
                    android.os.Process.myUid() / PER_USER_RANGE, multipleTask, newTask,
                ).also { if (newTask[0] >= 0) service.resizeTask(newTask[0], bounds.left, bounds.top, bounds.right, bounds.bottom) }
            }
        }.getOrElse { if (it is CancellationException) throw it else "failed: $it" }
        Log.i(TripleLauncher.TAG, "column ${column + 1} -> ${app.packageName}: $result (task=${newTask[0]})")
        val id = newTask[0]
        if (!result.startsWith(SplitService.OK_PREFIX) || id < 0 || id in otherTasks) {
            Toast.makeText(this, getString(R.string.replace_failed, AppLabels.ofPackage(this, app.packageName)), Toast.LENGTH_LONG).show()
            return
        }
        if (layout !== current || id == oldTask) return
        val updated = current.copy(
            taskIds = current.taskIds.mapIndexed { i, t -> if (i == column) id else t },
            packages = current.packages.mapIndexed { i, p -> if (i == column) app.packageName else p },
        )
        layout = updated
        limits = limits.mapIndexed { i, l -> if (i == column) WindowLimits(minColumnWidthPx) else l }
        appliedColumns = appliedColumns.mapIndexed { i, b -> if (i == column) bounds else b }
        veil?.apps = veilApps(updated.packages)
        reportTiled()
        runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) { Log.i(TripleLauncher.TAG, "old column window $oldTask: ${service.retireTask(oldTask)}") }
        }.onFailure { if (it is CancellationException) throw it }
        rememberWidths()
        updated.packages.mapNotNull { packageManager.getLaunchIntentForPackage(it)?.component }
            .takeIf { it.size == updated.packages.size }
            ?.let { Prefs(this).recordHistory(it) }
        verifyPending = true
        retiled = false
    }

    /**
     * Swaps the two windows next to handle [index]. The columns keep their place and size; the apps
     * trade columns. The new order lives in [layout], so later rotations and resizes keep it.
     */
    private fun swap(index: Int) {
        val current = layout ?: return
        if (rotating || converting) return
        releaseJob?.cancel()
        finishHandoff()
        layout = current.copy(
            taskIds = current.taskIds.swapped(index, index + 1),
            packages = current.packages.swapped(index, index + 1),
        )
        limits = limits.swapped(index, index + 1)
        Log.i(TripleLauncher.TAG, "swap columns ${index + 1} and ${index + 2}: tasks=${layout?.taskIds}")
        rememberWidths()
        val target = targetWindows()
        veil?.let {
            it.apps = it.apps.swapped(index, index + 1)
            it.columns = currentColumns()
            it.visibility = View.VISIBLE
        }
        // Only the two swapped windows move; mark their columns as not yet applied.
        appliedColumns = appliedColumns.mapIndexed { i, b -> if (i == index || i == index + 1) UNAPPLIED else b }
        releaseJob = scope.launch {
            applyColumns(target)
            verifyPending = true
            retiled = false
            lastPolled = null
            delay(VEIL_HOLD_MS)
            veil?.fadeOut(VEIL_FADE_MS)
        }
    }

    private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
        toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

    /** Bounds to give each window: its column, shrunk to the app's maximum size if it has one. */
    private fun targetWindows(): List<Bounds> =
        currentColumns().mapIndexed { i, slot -> limits.getOrNull(i)?.let { TripleLayout.windowIn(slot, it) } ?: slot }

    /** Column slots between the dividers; the veil draws these. */
    private fun currentColumns(): List<Bounds> {
        val current = layout ?: return emptyList()
        return TripleLayout.columnsFromDividers(current.area, current.gap, dividers)
    }

    private suspend fun applyColumns(columns: List<Bounds>) {
        val current = layout ?: return
        val service = runCatching { ShizukuBridge.service() }.getOrElse {
            if (it is CancellationException) throw it
            stopWithMessage(R.string.handles_closed_no_shizuku)
            return
        }
        val ok = runCatching {
            resizeLock.withLock {
                // Compared under the lock: a resize that ran just before may have moved a column already.
                val changed = columns.indices.filter { columns[it] != appliedColumns.getOrNull(it) }
                withContext(Dispatchers.IO) {
                    // Every column is tried, so one failure does not leave the others unmoved.
                    changed.map { i ->
                        val b = columns[i]
                        service.resizeTask(current.taskIds[i], b.left, b.top, b.right, b.bottom)
                    }.all { it }
                }
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TripleLauncher.TAG, "resize failed", it)
            if (it is ShizukuBridge.UnavailableException) {
                stopWithMessage(R.string.handles_closed_no_shizuku)
            } else {
                // A passing binder failure (the daemon restarting): check again rather than give up.
                lastPolled = null
                verifyPending = true
            }
            return
        }
        if (ok) {
            appliedColumns = columns
            reportTiled()
        } else {
            // A window may be mid-move (One UI animating it, or being promoted). Let the debounced
            // poll decide whether it is really gone instead of closing the handles right away.
            Log.i(TripleLauncher.TAG, "a resize did not apply; checking again")
            lastPolled = null
            verifyPending = true
        }
    }

    private fun overlayParams(x: Int, y: Int, width: Int, height: Int, extraFlags: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                extraFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position in raw display coordinates, the same space the task bounds use.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            this.x = x
            this.y = y
        }

    private fun handleParams(divider: Int, area: Bounds) = overlayParams(
        divider - handleWidthPx / 2, area.top + (area.height - handleHeightPx) / 2,
        handleWidthPx, handleHeightPx, extraFlags = 0,
    )

    private fun veilParams(area: Bounds) = overlayParams(
        area.left, area.top, area.width, area.height, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    )

    private fun moveHandles() {
        val area = layout?.area ?: return
        handles.forEachIndexed { i, view ->
            windowManager.updateViewLayout(view, handleParams(dividers[i], area))
        }
        swapButton?.let { windowManager.updateViewLayout(it, swapButtonParams(swapButtonIndex, area)) }
    }

    /** [keepVeil] keeps the veil window, e.g. a cover the new handles take over without a flash. */
    private fun removeOverlays(keepVeil: Boolean = false) {
        releaseJob?.cancel()
        pollJob?.cancel()
        hideSwapButton()
        hideBuriedChip()
        buriedIds = emptyList()
        handles.forEach { runCatching { windowManager.removeView(it) } }
        handles.clear()
        if (keepVeil) return
        veil?.let { runCatching { windowManager.removeView(it) } }
        veil = null
    }

    private fun stopWithMessage(@StringRes messageRes: Int) {
        val message = getString(messageRes)
        Log.i(TripleLauncher.TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        stop()
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DividerOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle(getString(R.string.handles_notification_title))
            .setContentText(getString(R.string.handles_notification_text))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.handles_equalize), action(ACTION_EQUALIZE, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.save_as_preset), action(ACTION_SAVE_PRESET, 2)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.handles_close_all), action(ACTION_CLOSE_ALL, 3)).build())
            .setDeleteIntent(stop)
            .build()
    }

    private fun action(name: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, DividerOverlayService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Back to equal columns (as far as the apps' minimum widths allow); also forgets remembered widths. */
    private fun equalize() {
        val current = layout ?: return
        if (rotating || busy() || converting) return
        hideSwapButton()
        finishHandoff()
        val equal = TripleLayout.columns(current.area, current.gap, current.taskIds.size)
        dividers = TripleLayout.fitDividers(current.area, current.gap, minWidths, TripleLayout.dividersOf(equal, current.gap))
        Log.i(TripleLauncher.TAG, "equal widths: dividers=$dividers")
        moveHandles()
        Prefs(this).saveWidthRatios(current.packages, null)
        val target = targetWindows()
        veil?.let {
            it.columns = currentColumns()
            if (handlesShown) it.visibility = View.VISIBLE
        }
        releaseJob?.cancel()
        releaseJob = scope.launch {
            applyColumns(target)
            verifyPending = true
            retiled = false
            lastPolled = null
            delay(VEIL_HOLD_MS)
            veil?.fadeOut(VEIL_FADE_MS)
        }
    }

    /** The apps on screen, left to right, become a preset (unless one with them already exists). */
    private fun saveAsPreset() {
        val current = layout ?: return
        val prefs = Prefs(this)
        val components = current.packages.mapNotNull { packageManager.getLaunchIntentForPackage(it)?.component }
        if (components.size != current.packages.size) return
        val flat = components.map { it.flattenToString() }
        val existing = prefs.presets
        if (existing.any { it.packages == current.packages }) {
            Toast.makeText(this, R.string.preset_already_saved, Toast.LENGTH_SHORT).show()
            return
        }
        val name = components.joinToString(" / ") { cn ->
            AppLabels.ofPackage(this, cn.packageName)
        }
        val updated = existing + Preset(java.util.UUID.randomUUID().toString(), Preset.sanitize(name), flat)
        prefs.presets = updated
        PresetShortcuts.update(this, updated)
        Toast.makeText(this, getString(R.string.preset_saved, name), Toast.LENGTH_SHORT).show()
    }

    /** Closes all the tiled windows, as if each one's close button was pressed. */
    private fun closeAll() {
        val current = layout ?: run { stop(); return }
        converting = true
        hideSwapButton()
        setHandlesShown(false)
        scope.launch {
            runCatching {
                val service = ShizukuBridge.service()
                withContext(Dispatchers.IO) { service.removeTasks(current.taskIds.toIntArray()) }
            }.onFailure { Log.w(TripleLauncher.TAG, "could not close the windows", it) }
            stop()
        }
    }

    /** Remembers the current widths for these apps, for the next time they are opened together. */
    private fun rememberWidths() {
        val current = layout ?: return
        Prefs(this).saveWidthRatios(current.packages, TripleLayout.ratiosOf(current.area, dividers))
    }

    /**
     * Drag moves divider [index]; a tap shows the swap button for the two windows next to it; holding still for
     * [LONG_PRESS_MS] closes the handles.
     */
    private inner class DragListener(private val index: Int) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@DividerOverlayService).scaledTouchSlop
        private var downRawX = 0f
        private var downDivider = 0
        private var dragging = false

        /**
         * Set by a DOWN in the current [gestureGeneration]. A MOVE without it (e.g. delivered across
         * a rotation) would measure from a stale or differently rotated start point and fling the
         * divider to its clamp limit, so it is ignored.
         */
        private var tracking = false
        private var generation = -1
        private val longPress = Runnable { stopWithMessage(R.string.handles_closed) }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val current = layout ?: return false
            if (event.actionMasked != MotionEvent.ACTION_DOWN && (!tracking || generation != gestureGeneration)) {
                reset(view as HandleView)
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (rotating) return true
                    tracking = true
                    generation = gestureGeneration
                    downRawX = event.rawX
                    downDivider = dividers[index]
                    dragging = false
                    (view as HandleView).active = true
                    mainHandler.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    if (!dragging && abs(dx) > touchSlop) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                        onDragStart()
                    }
                    if (dragging) {
                        val moved = TripleLayout.moveDivider(
                            current.area, current.gap, minWidths, dividers, index, downDivider + dx.roundToInt(),
                        )
                        if (moved != dividers) onDragMove(moved)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val tap = !dragging && event.eventTime - event.downTime < LONG_PRESS_MS
                    if (dragging) onDragEnd()
                    reset(view as HandleView)
                    if (tap) onHandleTap(index)
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDragEnd()
                    reset(view as HandleView)
                }
            }
            return true
        }

        private fun reset(view: HandleView) {
            mainHandler.removeCallbacks(longPress)
            view.active = false
            dragging = false
            tracking = false
        }
    }

    /** Pill-shaped grip; dark fill with a light outline so it shows on any app background. */
    private class HandleView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 32, 33, 36) }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.argb(160, 255, 255, 255)
        }
        private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2 * density
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()

        var active = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val pillWidth = (if (active) 14 else 8) * density
            val cx = width / 2f
            rect.set(cx - pillWidth / 2, 0f, cx + pillWidth / 2, height.toFloat())
            canvas.drawRoundRect(rect, pillWidth / 2, pillWidth / 2, fill)
            canvas.drawRoundRect(rect, pillWidth / 2, pillWidth / 2, outline)
            val gripHalf = height * 0.15f
            canvas.drawLine(cx, height / 2f - gripHalf, cx, height / 2f + gripHalf, grip)
        }
    }

    /** Round button with a swap (two opposing arrows) icon, styled like the handles. */
    /** A pill of app icons; tapping an icon calls [onPick] with its index. */
    private class BuriedChipView(
        context: Context,
        private val icons: List<Drawable?>,
        private val onPick: (Int) -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val iconSize = (36 * density).roundToInt()
        private val gap = (10 * density).roundToInt()
        private val padding = (8 * density).roundToInt()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 32, 33, 36) }
        private val rect = RectF()
        val preferredWidth = padding * 2 + icons.size * iconSize + (icons.size - 1) * gap
        val preferredHeight = padding * 2 + iconSize

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
            icons.forEachIndexed { i, icon ->
                val left = padding + i * (iconSize + gap)
                icon?.setBounds(left, padding, left + iconSize, padding + iconSize)
                icon?.draw(canvas)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val index = ((event.x - padding) / (iconSize + gap)).toInt().coerceIn(0, icons.lastIndex)
                performClick()
                onPick(index)
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    private enum class MenuIcon { CHANGE_LEFT, SWAP, CHANGE_RIGHT }

    private class SwapButtonView(context: Context, private val kind: MenuIcon = MenuIcon.SWAP) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 32, 33, 36) }
        private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 80, 82, 88) }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.argb(160, 255, 255, 255)
        }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2 * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = width / 2f - density
            canvas.drawCircle(cx, cy, radius, if (isPressed) pressedFill else fill)
            canvas.drawCircle(cx, cy, radius, outline)
            if (kind != MenuIcon.SWAP) {
                drawChange(canvas, cx, cy, pointsLeft = kind == MenuIcon.CHANGE_LEFT)
                return
            }
            val half = width * 0.22f
            val head = width * 0.09f
            val offset = height * 0.1f
            path.reset()
            // Upper arrow pointing right.
            path.moveTo(cx - half, cy - offset)
            path.lineTo(cx + half, cy - offset)
            path.moveTo(cx + half - head, cy - offset - head)
            path.lineTo(cx + half, cy - offset)
            path.lineTo(cx + half - head, cy - offset + head)
            // Lower arrow pointing left.
            path.moveTo(cx + half, cy + offset)
            path.lineTo(cx - half, cy + offset)
            path.moveTo(cx - half + head, cy + offset - head)
            path.lineTo(cx - half, cy + offset)
            path.lineTo(cx - half + head, cy + offset + head)
            canvas.drawPath(path, icon)
        }

        /** A 2x2 app grid with a small arrow on the side of the column it changes. */
        private fun drawChange(canvas: Canvas, cx: Float, cy: Float, pointsLeft: Boolean) {
            val cell = width * 0.11f
            val gap = width * 0.05f
            val shift = if (pointsLeft) width * 0.07f else -width * 0.07f
            for (row in 0..1) for (col in 0..1) {
                val left = cx + shift - cell - gap / 2 + col * (cell + gap)
                val top = cy - cell - gap / 2 + row * (cell + gap)
                canvas.drawRoundRect(left, top, left + cell, top + cell, cell * 0.3f, cell * 0.3f, icon)
            }
            val tipX = if (pointsLeft) cx - width * 0.3f else cx + width * 0.3f
            val backX = if (pointsLeft) tipX + width * 0.08f else tipX - width * 0.08f
            path.reset()
            path.moveTo(backX, cy - width * 0.08f)
            path.lineTo(tipX, cy)
            path.lineTo(backX, cy + width * 0.08f)
            canvas.drawPath(path, icon)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
    }

    /** Opaque panels showing each column's target size, with the app's icon and name, during a drag. */
    private class VeilView(context: Context, area: Bounds, apps: List<App>) : View(context) {
        class App(val icon: Drawable?, val label: String)

        private val density = resources.displayMetrics.density
        private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        private val backdrop = Paint().apply { color = if (night) Color.rgb(12, 12, 14) else Color.rgb(200, 202, 208) }
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (night) Color.rgb(44, 45, 50) else Color.rgb(246, 247, 250)
        }
        private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (night) Color.rgb(230, 230, 235) else Color.rgb(32, 33, 36)
            textSize = 15 * density
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        var columns: List<Bounds> = emptyList()
            set(value) {
                field = value
                invalidate()
            }

        /** App shown in each column, left to right. */
        var apps: List<App> = apps
            set(value) {
                field = value
                invalidate()
            }

        private var morph: ValueAnimator? = null

        /** Animates the panels from the current [columns] to [to], decelerating like system window moves. */
        fun morphColumns(to: List<Bounds>, durationMs: Long) {
            morph?.cancel()
            val from = columns
            if (from.size != to.size || from == to) {
                columns = to
                return
            }
            morph = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { animator ->
                    val f = animator.animatedValue as Float
                    columns = from.zip(to) { a, b ->
                        Bounds(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))
                    }
                }
                start()
            }
        }

        fun fadeIn(durationMs: Long) {
            animate().cancel()
            alpha = 0f
            visibility = VISIBLE
            animate().alpha(1f).setDuration(durationMs).start()
        }

        /** Fades out, then hides (not removes) the veil and runs [onEnd]. */
        fun fadeOut(durationMs: Long, onEnd: () -> Unit = {}) {
            if (visibility != VISIBLE) {
                onEnd()
                return
            }
            animate().cancel()
            animate().alpha(0f).setDuration(durationMs).withEndAction {
                visibility = INVISIBLE
                alpha = 1f
                onEnd()
            }.start()
        }

        private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).roundToInt()

        /** Display area the veil covers; column bounds are drawn relative to its top-left. */
        var area: Bounds = area
            set(value) {
                field = value
                invalidate()
            }

        /**
         * Whether the veil fills the whole area (drag, rotation, swap) or only draws panels, letting
         * the gaps and [liveColumns] show the real windows (covering a launch, like native split's
         * placeholders for apps still opening).
         */
        var fullCover = true
            set(value) {
                field = value
                invalidate()
            }

        /** Columns not drawn, because their app is already on screen and moves by itself. */
        var liveColumns: Set<Int> = emptySet()
            set(value) {
                field = value
                invalidate()
            }

        fun resetStyle() {
            fullCover = true
            liveColumns = emptySet()
        }

        override fun onDraw(canvas: Canvas) {
            if (fullCover) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdrop)
            val inset = 3 * density
            val radius = 16 * density
            val iconSize = (48 * density).roundToInt()
            columns.forEachIndexed { i, column ->
                if (i in liveColumns) return@forEachIndexed
                rect.set(
                    column.left - area.left + inset, column.top - area.top + inset,
                    column.right - area.left - inset, column.bottom - area.top - inset,
                )
                canvas.drawRoundRect(rect, radius, radius, panel)
                val app = apps.getOrNull(i) ?: return@forEachIndexed
                val cx = rect.centerX().roundToInt()
                val cy = rect.centerY().roundToInt()
                app.icon?.let {
                    it.setBounds(cx - iconSize / 2, cy - iconSize, cx + iconSize / 2, cy)
                    it.draw(canvas)
                }
                val label = TextUtils.ellipsize(app.label, text, rect.width() - 2 * radius, TextUtils.TruncateAt.END)
                canvas.drawText(label, 0, label.length, cx.toFloat(), cy + 28 * density, text)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "handles"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "jp.trisplit.action.STOP_HANDLES"
        private const val ACTION_COVER = "jp.trisplit.action.COVER"

        /** A cover that nothing takes over or lifts within this long goes away by itself. */
        private const val PREPARE_TIMEOUT_MS = 15_000L

        /** How long a cover stays after the windows opened, so they finish drawing underneath it. */
        private const val COVER_HOLD_MS = 450L
        private const val COVER_FADE_IN_MS = 120L

        /** Panels sliding from the split halves into the three columns. */
        private const val COVER_MORPH_MS = 320L

        /** Every veil fades out instead of vanishing. */
        private const val VEIL_FADE_MS = 200L

        private const val EXTRA_FROM = "from"
        private const val EXTRA_LIVE_COLUMN = "liveColumn"
        private const val EXTRA_COLUMNS = "columns"

        /** The running service, if any; it lives in this process, so a new layout can be handed over directly. */
        @Volatile
        private var instance: DividerOverlayService? = null
        private const val EXTRA_AREA = "area"
        private const val EXTRA_GAP = "gap"
        private const val EXTRA_TASK_IDS = "taskIds"
        private const val EXTRA_PACKAGES = "packages"

        private const val HANDLE_WIDTH_DP = 32
        private const val HANDLE_HEIGHT_DP = 140

        private const val LONG_PRESS_MS = 700L

        private const val SWAP_BUTTON_DP = 44
        private const val MENU_GAP_DP = 14
        private const val PER_USER_RANGE = 100_000
        private const val SWAP_BUTTON_TIMEOUT_MS = 4_000L

        /** An outside touch this recent (any tap is shorter than a long press) on the same handle means the tap was meant to close the button. */
        private const val OUTSIDE_TAP_WINDOW_MS = LONG_PRESS_MS + 100
        private const val DOUBLE_TAP_MS = 350L
        private const val IME_RECHECK_MS = 2_000L
        private const val ACTION_EQUALIZE = "jp.trisplit.action.EQUALIZE"
        private const val ACTION_SAVE_PRESET = "jp.trisplit.action.SAVE_PRESET"
        private const val ACTION_CLOSE_ALL = "jp.trisplit.action.CLOSE_ALL"

        /** Placeholder that never equals real bounds, forcing [applyColumns] to resize that column. */
        private val UNAPPLIED = Bounds(0, 0, 0, 0)

        /** How long the veil stays after resizing, so apps finish redrawing at the new size underneath it. */
        private const val VEIL_HOLD_MS = 250L
        private const val POLL_INTERVAL_MS = 300L

        /** After a rotation: wait this long for the system window moves, then resize; retried. */
        private const val ROTATION_SETTLE_MS = 250L
        private const val ROTATION_ATTEMPTS = 3

        /** Size of the ordinary pop-up a narrow column is restored to, as a share of the screen. */
        private const val POPUP_WIDTH_FRACTION = 0.6f
        private const val POPUP_HEIGHT_FRACTION = 0.75f
        private const val POPUP_CASCADE_DP = 24

        /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN (hidden). */
        private const val WINDOWING_MODE_FULLSCREEN = 1

        /** SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT. */
        private const val SPLIT_POSITION_BOTTOM_OR_RIGHT = 1

        /** Polls in a row a window must be missing before it counts as closed. */
        private const val CLOSED_CONFIRM_POLLS = 2

        /** How long the veil stays while the native split animates in. */
        private const val SPLIT_VEIL_HOLD_MS = 900L

        /** How long after a rotation window moves are put back instead of followed as user resizes. */
        private const val ROTATION_GRACE_MS = 2_000L

        /**
         * Covers [area] with the veil drawn as the layout about to open ([packages] per column),
         * while TriSplit is still on screen. [liveColumn] is an app already on screen (the split
         * partner): it gets no panel, so its real window stays visible as it moves into place. It hides TriSplit closing and the apps' launch
         * animations, so the screen goes straight from the picker to the finished columns. It also
         * starts the service while Android still allows a foreground service start; [start] then
         * hands the layout to this instance, or [uncover] lifts the cover.
         */
        fun cover(
            context: Context, area: Bounds, gap: Int, packages: List<String>, from: List<Bounds>? = null, liveColumn: Int = -1,
            columns: List<Bounds>? = null,
        ) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, DividerOverlayService::class.java)
                .setAction(ACTION_COVER)
                .putExtra(EXTRA_LIVE_COLUMN, liveColumn)
                .putExtra(EXTRA_AREA, intArrayOf(area.left, area.top, area.right, area.bottom))
                .putExtra(EXTRA_GAP, gap)
                .putExtra(EXTRA_PACKAGES, packages.toTypedArray())
            columns?.let { target ->
                intent.putExtra(EXTRA_COLUMNS, target.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toIntArray())
            }
            from?.let { bounds ->
                intent.putExtra(EXTRA_FROM, bounds.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toIntArray())
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TripleLauncher.TAG, "could not cover the screen", it) }
        }

        /** [ColumnPickerActivity] chose [app] for [column]. */
        fun onColumnPicked(column: Int, app: ComponentName) {
            instance?.columnPicked(column, app)
        }

        /** [ColumnPickerActivity] closed without a choice. */
        fun onColumnPickCancelled() {
            instance?.let {
                it.replacing = null
                it.lastPolled = null
            }
        }

        /** Stops the current handles from reacting while a launch rearranges windows. */
        fun pauseForLaunch() {
            instance?.launchPaused = true
        }

        /** Ends [pauseForLaunch] if no new layout took over (it would have ended the pause itself). */
        fun resumeAfterLaunch() {
            instance?.let {
                it.launchPaused = false
                it.lastPolled = null
            }
        }

        /** Lifts a [cover] when no handles will take over (handles off, or the launch failed). */
        fun uncover() {
            instance?.endCover()
        }

        /** Starts or replaces the handles for [session]. Returns why it did not start, or null on success. */
        fun start(context: Context, session: LaunchSession): String? {
            // Two columns given the same window: an app that cannot open a second window of itself.
            val shared = session.results.groupBy { it.taskId }.values.firstOrNull { it.size > 1 && it[0].taskId >= 0 }
            if (shared != null) {
                val app = shared[0].app.packageName
                val label = AppLabels.ofPackage(context, app)
                val message = context.getString(R.string.handles_same_window, label)
                Log.w(TripleLauncher.TAG, "$message (task=${session.taskIds})")
                return message
            }
            val problem = when {
                !session.resizable -> R.string.handles_not_resizable
                !Settings.canDrawOverlays(context) -> R.string.handles_no_overlay_permission
                else -> null
            }
            if (problem != null) {
                val message = context.getString(problem)
                Log.w(TripleLauncher.TAG, "$message (task=${session.taskIds})")
                return message
            }
            val a = session.area
            val packages = session.results.map { it.app.packageName }
            // Already running (prepared, or showing an earlier layout): hand the layout over directly.
            val columns = session.results.map { it.bounds }
            instance?.takeIf { !it.stopping }?.let { running ->
                running.show(Layout(a, running.windowManager.displayBounds(), session.gap, session.taskIds, packages), columns)
                return null
            }
            val intent = Intent(context, DividerOverlayService::class.java)
                .putExtra(EXTRA_AREA, intArrayOf(a.left, a.top, a.right, a.bottom))
                .putExtra(EXTRA_COLUMNS, columns.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toIntArray())
                .putExtra(EXTRA_GAP, session.gap)
                .putExtra(EXTRA_TASK_IDS, session.taskIds.toIntArray())
                .putExtra(EXTRA_PACKAGES, packages.toTypedArray())
            return runCatching { context.startForegroundService(intent) }.fold(
                onSuccess = { null },
                onFailure = {
                    Log.w(TripleLauncher.TAG, "could not start the handles", it)
                    context.getString(R.string.error_message, it.javaClass.simpleName, it.message)
                },
            )
        }
    }
}
