package jp.trisplit

import android.app.ActivityManager
import android.app.TaskInfo
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Runs inside a process that Shizuku starts as the shell user, so it holds the shell's
 * activity-management permissions and can launch and resize any app's freeform window.
 * That process cannot read the app's resources, so its result messages are untranslated
 * English diagnostics.
 */
class SplitService : ISplitService.Stub() {

    init {
        HiddenApiBypass.addHiddenApiExemptions("")
    }

    private val atm: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            ?: error("ActivityTaskManager service unavailable")
    }

    private val resizeTaskMethod: Method? by lazy {
        atm.javaClass.methods.firstOrNull { m ->
            m.name == "resizeTask" && m.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType),
            )
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        // A command that hangs would hold this binder thread (and TriSplit's caller) forever.
        val watchdog = timeouts.schedule({
            process.destroyForcibly()
            // Other processes of a pipeline can keep the output open; closing it ends the read.
            runCatching { process.inputStream.close() }
        }, EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val output = runCatching { process.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
        val code = process.waitFor()
        watchdog.cancel(false)
        return if (output.isEmpty()) "exit=$code" else "exit=$code: $output"
    }

    private val timeouts = Executors.newSingleThreadScheduledExecutor()

    override fun startFreeform(
        component: String, left: Int, top: Int, right: Int, bottom: Int, userId: Int,
        multipleTask: Boolean, taskId: IntArray,
    ): String {
        taskId[0] = -1
        val cn = ComponentName.unflattenFromString(component) ?: return "Invalid component: $component"
        val cookie = Binder()
        val intent = Freeform.intentFor(cn, multipleTask)
        val options = Freeform.options(Bounds(left, top, right, bottom), cookie).toBundle()
        return try {
            val code = startActivityAsShell(intent, options, userId)
            if (code < START_SUCCESS) return "Failed (code=$code)"
            taskId[0] = runCatching { findTaskId(cookie, cn.packageName) }.getOrDefault(-1)
            "$OK_PREFIX (code=$code, task=${taskId[0]})"
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            // Last resort: freeform without bounds; the window still opens, just not tiled.
            val fallback = exec(
                "am start --windowingMode ${Freeform.WINDOWING_MODE_FREEFORM} --user $userId -n '${cn.flattenToShortString()}'",
            )
            "Reflection call failed (${cause.javaClass.simpleName}: ${cause.message}) -> am start: $fallback"
        }
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val method = resizeTaskMethod
        if (method != null) {
            val ok = runCatching {
                method.invoke(atm, taskId, Rect(left, top, right, bottom), RESIZE_MODE_PRESERVE_WINDOW) as Boolean
            }
            ok.getOrNull()?.let { return it }
        }
        // Same operation through the shell command; slower because it spawns a process.
        return exec("am task resize $taskId $left $top $right $bottom").startsWith("exit=0")
    }

    /**
     * Calls IActivityTaskManager.startActivityAsUser by reflection. Its parameter list has changed
     * between Android releases, so arguments are filled by type rather than by position:
     * the first String is the calling package, the last int is the user id, everything else is
     * null/0/false.
     */
    private fun startActivityAsShell(intent: Intent, options: Bundle, userId: Int): Int {
        val method = atm.javaClass.methods
            .filter { it.name == "startActivityAsUser" }
            .maxByOrNull { it.parameterCount }
            ?: error("startActivityAsUser not found")
        val types = method.parameterTypes
        val userIdIndex = types.indexOfLast { it == Int::class.javaPrimitiveType }
        var callingPackageSet = false
        val args = Array<Any?>(types.size) { i ->
            when (types[i]) {
                Intent::class.java -> intent
                Bundle::class.java -> options
                String::class.java -> if (!callingPackageSet) SHELL_PACKAGE.also { callingPackageSet = true } else null
                Int::class.javaPrimitiveType -> if (i == userIdIndex) userId else 0
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return method.invoke(atm, *args) as Int
    }

    /**
     * Finds the task carrying [cookie]. A task that already existed (e.g. the app was open) does not
     * get the cookie, so fall back to the most recent task of [packageName].
     */
    private fun findTaskId(cookie: IBinder, packageName: String): Int {
        val tasks = runningTasks()
        val cookiesField = runCatching { TaskInfo::class.java.getField("launchCookies") }.getOrNull()
        val byCookie = tasks.firstOrNull { task ->
            (cookiesField?.get(task) as? List<*>)?.contains(cookie) == true
        }
        val task = byCookie ?: tasks.firstOrNull { it.baseActivity?.packageName == packageName }
        return task?.taskId ?: -1
    }

    /**
     * For each id in [taskIds], [TASK_STATE_SIZE] ints: found (0/1), visible (0/1), windowing mode,
     * left, top, right, bottom, the app's own minimum width in px (-1 if none), the system's
     * default minimum size in dp, then maximum width and height in px (-1 if none). See [TaskState].
     * A task that no longer exists is all zeros.
     */
    override fun getTaskStates(taskIds: IntArray): IntArray = binderSafe {
        // TriSplit's handles poll these tasks while it manages them; that doubles as "managed".
        markManaged(taskIds)
        val tasks = runningTasks().associateBy { it.taskId }
        val out = IntArray(taskIds.size * TASK_STATE_SIZE)
        taskIds.forEachIndexed { i, id ->
            val task = tasks[id] ?: return@forEachIndexed
            val (mode, bounds) = windowOf(task) ?: return@forEachIndexed
            intArrayOf(
                1, if (isVisible(task)) 1 else 0, mode,
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                hiddenInt(task, "minWidth", -1), hiddenInt(task, "defaultMinSize", 0),
                hiddenInt(task, "maxWidth", -1), hiddenInt(task, "maxHeight", -1),
            ).copyInto(out, i * TASK_STATE_SIZE)
        }
        out
    }

    /**
     * Visible tasks, most recent first, one per line as
     * "taskId,windowingMode,left,top,right,bottom,baseActivity" (see [VisibleTask.parse]).
     */
    override fun getVisibleTasks(): Array<String> = binderSafe {
        runningTasks().filter(::isVisible).mapNotNull { task ->
            val (mode, b) = windowOf(task) ?: return@mapNotNull null
            val component = task.baseActivity?.flattenToString() ?: ""
            "${task.taskId},$mode,${b.left},${b.top},${b.right},${b.bottom},$component"
        }.toTypedArray()
    }

    /**
     * Moves a task out of split screen into a freeform window, the way desktop windowing does it:
     * one WindowContainerTransaction that reparents the task from the split root to the display
     * area, switches it to freeform and sets its bounds. When one side of the split is emptied,
     * the system dismisses the split by itself.
     */
    override fun moveToFreeform(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): String =
        try {
            applyToTask(taskId) { wct ->
                wct.reparent(null)
                wct.setWindowingMode(Freeform.WINDOWING_MODE_FREEFORM)
                wct.setBounds(Rect(left, top, right, bottom))
            }
            "$OK_PREFIX (moved task=$taskId)"
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            "moveToFreeform failed (${cause.javaClass.simpleName}: ${cause.message})"
        }

    /**
     * Native split screen is owned by SystemUI's window manager shell, which offers no API to other
     * apps, but its shell command "splitscreen moveToSideStage" is open to the shell user. It puts
     * one task in the side stage and the topmost full-screen task in the main stage, so the main
     * task is first made full screen and brought to the top.
     */
    override fun enterSplit(mainTaskId: Int, sideTaskId: Int, sidePosition: Int): String =
        try {
            // The shell command throws inside SystemUI, crashing it, if a task is already in split
            // screen (e.g. the user just moved one there from its window menu). Check first.
            val running = runningTasks()
            val tasks = running.associateBy { it.taskId }
            val modes = listOf(mainTaskId, sideTaskId).map { id -> tasks[id]?.let { windowOf(it)?.first } }
            // Other apps' tasks too: a split stage that still holds one (even hidden) is enough.
            val inSplit = running.any { it.taskId != mainTaskId && it.taskId != sideTaskId && windowOf(it)?.first == VisibleTask.WINDOWING_MODE_MULTI_WINDOW }
            when {
                modes.any { it == null } -> return "enterSplit skipped: a task is gone"
                modes.any { it == VisibleTask.WINDOWING_MODE_MULTI_WINDOW } -> return "enterSplit skipped: already in split screen"
                inSplit -> return "enterSplit skipped: another app is in split screen"
            }
            applyToTask(mainTaskId) { wct ->
                wct.setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                wct.setBounds(Rect())
                wct.reorder(toTop = true)
            }
            val result = exec("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $sideTaskId $sidePosition")
            if (result.startsWith("exit=0")) "$OK_PREFIX (split main=$mainTaskId side=$sideTaskId)" else "enterSplit failed: $result"
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            "enterSplit failed (${cause.javaClass.simpleName}: ${cause.message})"
        }

    /**
     * IActivityTaskManager.moveTaskToFront for each task, in order. A freeform window minimized to a
     * floating icon (e.g. by going home) comes back at its previous bounds.
     * Parameters are filled by type: the first String is the calling package, the first int the task.
     */
    // ---- Pop-up fixer ----
    //
    // Android remembers each app's last pop-up (freeform) bounds and reuses them the next time the
    // app opens as a pop-up. After TriSplit, that is a tall, narrow column. The record is private
    // to the system, so instead: watch task changes, and when a tiled app opens as a pop-up at
    // exactly its column bounds outside TriSplit, resize it to an ordinary pop-up. That also
    // overwrites the system's record. This service is a Shizuku daemon, so it keeps watching
    // after TriSplit's own process has gone.

    private class Tiled(val packageName: String, val tiled: Rect, val popup: Rect)

    private val tiled = mutableListOf<Tiled>()
    /**
     * When each task TriSplit manages was last reported. Per task, so the handles of one layout and
     * a launch of the next do not overwrite each other. TriSplit can be frozen or killed, so each
     * entry expires after [MANAGED_TTL_MS].
     */
    private val managed = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Every task TriSplit has managed; see [fixNarrowPopups]. */
    private val everManaged = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Windowing mode of each task at the previous check, to spot a switch into pop-up view. */
    private val lastModes = HashMap<Int, Int>()

    private val fixer = Executors.newSingleThreadScheduledExecutor()
    private var pendingCheck: ScheduledFuture<*>? = null

    /** Watch checks in a row with nothing to do; see [fixNarrowPopups]. */
    private var watchedChecks = 0
    private var listening = false

    init {
        // Records survive restarts of this service (e.g. Shizuku after a reboot).
        fixer.execute {
            loadTiled()
            // Windows already open when this service (re)starts were not just opened: without this,
            // after an update or a Shizuku restart every window left in its column would count as a
            // new pop-up and be resized.
            runCatching {
                runningTasks().forEach { task ->
                    everManaged += task.taskId
                    windowOf(task)?.let { lastModes[task.taskId] = it.first }
                }
            }.onFailure { Log.w(DAEMON_TAG, "could not read the open windows", it) }
            if (tiled.isNotEmpty()) startListening()
        }
    }

    override fun markTiled(packages: Array<String>, tiledBounds: IntArray, popupBounds: IntArray) {
        fixer.execute {
            packages.forEachIndexed { i, pkg ->
                tiled.removeAll { it.packageName == pkg }
                tiled += Tiled(pkg, rectAt(tiledBounds, i), rectAt(popupBounds, i))
            }
            saveTiled()
            watchedChecks = 0
            startListening()
        }
    }

    override fun setManagedTasks(taskIds: IntArray) {
        markManaged(taskIds)
        scheduleCheck()
    }

    private fun markManaged(taskIds: IntArray) {
        val now = SystemClock.uptimeMillis()
        taskIds.forEach { managed[it] = now }
        everManaged.addAll(taskIds.toList())
        // Forget tasks no longer reported, so the map stays small.
        managed.entries.removeIf { now - it.value >= MANAGED_TTL_MS }
    }

    private fun isManaged(taskId: Int) =
        managed[taskId]?.let { SystemClock.uptimeMillis() - it < MANAGED_TTL_MS } == true

    /**
     * Runs a binder entry point so that a checked exception from reflection (InvocationTargetException)
     * reaches the caller as a RuntimeException instead of escaping the binder thread, which would
     * kill this process.
     */
    private inline fun <T> binderSafe(block: () -> T): T =
        try {
            block()
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Log.w(DAEMON_TAG, "binder call failed", cause)
            throw IllegalStateException("${cause.javaClass.simpleName}: ${cause.message}")
        }

    /** One line per app: "package l t r b l t r b" (column, then pop-up bounds). */
    private fun saveTiled() {
        runCatching {
            // Written beside the file and renamed over it, so a kill mid-write keeps the old records.
            val temp = File("$TILED_FILE.tmp")
            temp.writeText(
                tiled.joinToString("\n") { t ->
                    (listOf(t.packageName) + listOf(t.tiled, t.popup).flatMap { listOf(it.left, it.top, it.right, it.bottom) })
                        .joinToString(" ")
                },
            )
            check(temp.renameTo(File(TILED_FILE))) { "rename failed" }
        }.onFailure { Log.w(DAEMON_TAG, "could not save tiled records", it) }
    }

    private fun loadTiled() {
        val lines = runCatching { File(TILED_FILE).takeIf { it.exists() }?.readLines() }.getOrNull() ?: return
        lines.forEach { line ->
            val parts = line.trim().split(' ')
            if (parts.size != 9) return@forEach
            val n = parts.drop(1).map { it.toIntOrNull() ?: return@forEach }
            tiled += Tiled(parts[0], Rect(n[0], n[1], n[2], n[3]), Rect(n[4], n[5], n[6], n[7]))
        }
    }

    private fun rectAt(values: IntArray, i: Int) = Rect(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3])

    /**
     * Registers for task stack changes. ITaskStackListener is hidden, so the listener is a proxy
     * whose binder treats every (one-way) callback as "something changed" and re-checks the tasks.
     */
    private fun startListening() {
        if (listening) return
        runCatching {
            val listenerClass = Class.forName("android.app.ITaskStackListener")
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    scheduleCheck()
                    notifyTaskEvents()
                    return true
                }
            }
            val listener = java.lang.reflect.Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, _ ->
                if (method.name == "asBinder") binder else null
            }
            atm.javaClass.getMethod("registerTaskStackListener", listenerClass).invoke(atm, listener)
            listening = true
            Log.i(DAEMON_TAG, "listening for task changes")
        }.onFailure { Log.w(DAEMON_TAG, "registerTaskStackListener failed", it) }
    }

    /** TriSplit's handles, told about task changes as they happen; see [setTaskEvents]. */
    @Volatile
    private var taskEvents: ITaskEvents? = null

    override fun setTaskEvents(listener: ITaskEvents?) {
        taskEvents = listener
        // Task changes are only heard once the listener is registered.
        if (listener != null) fixer.execute { startListening() }
    }

    private fun notifyTaskEvents() {
        val listener = taskEvents ?: return
        // One-way: this never waits for TriSplit. A dead TriSplit stops the events.
        runCatching { listener.onTasksChanged() }.onFailure { if (taskEvents === listener) taskEvents = null }
    }

    private fun scheduleCheck(delayMs: Long = FIX_DEBOUNCE_MS) {
        fixer.execute {
            pendingCheck?.cancel(false)
            pendingCheck = fixer.schedule({ fixNarrowPopups() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Only windows that *open* as a pop-up count: a new task, or one switched into pop-up view from
     * full screen or split screen. A window the user keeps where TriSplit tiled it, after the handles
     * are gone, is left alone.
     */
    private fun fixNarrowPopups() {
        val tasks = runCatching { runningTasks() }.getOrElse {
            Log.w(DAEMON_TAG, "fixNarrowPopups failed", it)
            return
        }
        val previousModes = HashMap(lastModes)
        lastModes.clear()
        tasks.forEach { task -> windowOf(task)?.let { lastModes[task.taskId] = it.first } }
        if (tiled.isEmpty()) return
        var changed = false
        runCatching {
            tasks.forEach { task ->
                if (isManaged(task.taskId) || !isVisible(task)) return@forEach
                val (mode, bounds) = windowOf(task) ?: return@forEach
                if (mode != Freeform.WINDOWING_MODE_FREEFORM) return@forEach
                val previous = previousModes[task.taskId]
                val opened = task.taskId !in everManaged || (previous != null && previous != Freeform.WINDOWING_MODE_FREEFORM)
                val pkg = task.baseActivity?.packageName ?: return@forEach
                val entry = tiled.firstOrNull { it.packageName == pkg } ?: return@forEach
                // The system may place the window elsewhere; the column's size is what gives it away.
                val columnSized = abs(entry.tiled.width() - bounds.width()) <= SIZE_TOLERANCE_PX &&
                    abs(entry.tiled.height() - bounds.height()) <= SIZE_TOLERANCE_PX
                if (!columnSized) {
                    // A pop-up of this app at a size of its own (e.g. resized by the user): that is
                    // what Android remembers now, so the column no longer needs undoing. Forgetting it
                    // also means a size the user picks later, even a narrow one, is never reverted.
                    tiled.remove(entry)
                    changed = true
                    return@forEach
                }
                if (!opened) return@forEach
                resizeTask(task.taskId, entry.popup.left, entry.popup.top, entry.popup.right, entry.popup.bottom)
                Log.i(DAEMON_TAG, "restored pop-up size of $pkg (task ${task.taskId}): $bounds -> ${entry.popup}")
                tiled.remove(entry)
                changed = true
            }
        }.onFailure { Log.w(DAEMON_TAG, "fixNarrowPopups failed", it) }
        if (changed) saveTiled()
        // Resizing a pop-up sends no task stack callback. While a tiled app's window is still out as
        // a pop-up outside TriSplit, look again shortly, so a size the user gives it by hand is seen
        // (and its record dropped) before the window is closed. Nothing left to watch: no polling.
        val watching = runCatching {
            tasks.any { task ->
                !isManaged(task.taskId) && isVisible(task) &&
                    windowOf(task)?.first == Freeform.WINDOWING_MODE_FREEFORM &&
                    tiled.any { it.packageName == task.baseActivity?.packageName }
            }
        }.getOrDefault(false)
        // Not forever: a window the user simply keeps in its column would poll for as long as it is
        // open. Any change (a record dropped or restored) starts the watch over.
        watchedChecks = if (watching && !changed) watchedChecks + 1 else 0
        if (watching && watchedChecks < MAX_WATCHED_CHECKS) scheduleCheck(WATCH_INTERVAL_MS)
    }

    /**
     * Split-screen tasks including hidden ones: dropping an app onto one side of a split puts it on
     * top of that side's app, which stays underneath in the same stage (the same parent task).
     */
    override fun getStageTasks(): Array<String> = binderSafe {
        runningTasks().mapNotNull { task ->
            val (mode, _) = windowOf(task) ?: return@mapNotNull null
            if (mode != VisibleTask.WINDOWING_MODE_MULTI_WINDOW) return@mapNotNull null
            val component = task.baseActivity?.flattenToString() ?: return@mapNotNull null
            "${task.taskId},${hiddenInt(task, "parentTaskId", -1)},${if (isVisible(task)) 1 else 0},$component"
        }.toTypedArray()
    }

    /**
     * One UI lifts a pop-up window out of the keyboard's way while it shows. Read rarely (only when
     * a window moved up or down on its own), so a dumpsys is cheap enough.
     */
    override fun isImeVisible(): Boolean =
        exec("dumpsys input_method | grep -m1 mInputShown").contains("mInputShown=true")

    override fun removeTasks(taskIds: IntArray) {
        removeNow(taskIds.toList())
        // An app still starting up (a splash screen handing over to its next screen) can open that
        // next screen in the task right after it was removed, which brings the window back. Look
        // again a little later and close any that came back.
        REMOVE_RECHECK_MS.forEach { delayMs ->
            fixer.schedule({
                val alive = runCatching { runningTasks().map { it.taskId }.toSet() }.getOrDefault(emptySet())
                val back = taskIds.filter { it in alive }
                if (back.isNotEmpty()) {
                    Log.i(DAEMON_TAG, "closing again tasks that came back: $back")
                    removeNow(back)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun removeNow(taskIds: List<Int>) {
        val method = runCatching { atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType) }
            .getOrElse { Log.w(DAEMON_TAG, "removeTask unavailable", it); return }
        taskIds.forEach { taskId ->
            runCatching { method.invoke(atm, taskId) }
                .onFailure { Log.w(DAEMON_TAG, "removeTask($taskId) failed", it) }
        }
    }

    override fun moveTasksToFront(taskIds: IntArray) = binderSafe {
        val method = atm.javaClass.methods
            .filter { it.name == "moveTaskToFront" }
            .maxByOrNull { it.parameterCount }
            ?: error("moveTaskToFront not found")
        val types = method.parameterTypes
        val firstInt = types.indexOfFirst { it == Int::class.javaPrimitiveType }
        val firstString = types.indexOfFirst { it == String::class.java }
        taskIds.forEach { taskId ->
            val args = Array<Any?>(types.size) { i ->
                when {
                    i == firstString -> SHELL_PACKAGE
                    i == firstInt -> taskId
                    types[i] == Int::class.javaPrimitiveType -> 0
                    types[i] == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
            runCatching { method.invoke(atm, *args) }
                .onFailure { System.err.println("moveTaskToFront($taskId) failed: $it") }
        }
    }

    /** Builds one WindowContainerTransaction for [taskId] by reflection and applies it. */
    private fun applyToTask(taskId: Int, build: (TaskTransaction) -> Unit) {
        val task = runningTasks().firstOrNull { it.taskId == taskId } ?: error("Task $taskId not found")
        val transaction = TaskTransaction(TaskInfo::class.java.getField("token").get(task) ?: error("no token"))
        build(transaction)
        val organizer = atm.javaClass.getMethod("getWindowOrganizerController").invoke(atm)
            ?: error("WindowOrganizerController unavailable")
        organizer.javaClass.methods
            .first { it.name == "applyTransaction" && it.parameterCount == 1 }
            .invoke(organizer, transaction.wct)
    }

    /** The hidden WindowContainerTransaction calls used here, on a single task's token. */
    private class TaskTransaction(private val token: Any) {
        private val tokenClass = Class.forName("android.window.WindowContainerToken")
        private val wctClass = Class.forName("android.window.WindowContainerTransaction")
        val wct: Any = wctClass.getConstructor().newInstance()

        /** A null [parent] means the display area: out of any split root. */
        fun reparent(parent: Any?) {
            wctClass.getMethod("reparent", tokenClass, tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, parent, true)
        }

        fun setWindowingMode(mode: Int) {
            wctClass.getMethod("setWindowingMode", tokenClass, Int::class.javaPrimitiveType).invoke(wct, token, mode)
        }

        /** An empty rect clears the bounds, so the task fills its parent. */
        fun setBounds(bounds: Rect) {
            wctClass.getMethod("setBounds", tokenClass, Rect::class.java).invoke(wct, token, bounds)
        }

        fun reorder(toTop: Boolean) {
            wctClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, toTop)
        }
    }

    /** A hidden int field of TaskInfo, or [default] if this Android build lacks it. */
    private fun hiddenInt(task: TaskInfo, name: String, default: Int): Int =
        runCatching { TaskInfo::class.java.getField(name).getInt(task) }.getOrDefault(default)

    /**
     * Freeform windows stay "visible" while the recent-apps overview is shown over them, so the
     * overview has to be detected by itself.
     */
    override fun isRecentsVisible(): Boolean = binderSafe {
        runningTasks().any { isVisible(it) && activityTypeOf(it) == ACTIVITY_TYPE_RECENTS }
    }

    /**
     * The recent-apps history (what the overview shows), most recent first. Falls back to running
     * tasks, which miss apps whose tasks were closed but still appear in the overview.
     */
    override fun getRecentPackages(): Array<String> {
        val recents = runCatching { recentTaskPackages() }
            .onFailure { System.err.println("getRecentTasks failed: $it") }
            .getOrNull()
        val packages = recents ?: runningTasks()
            .filter { activityTypeOf(it) == ACTIVITY_TYPE_STANDARD }
            .mapNotNull { it.baseActivity?.packageName }
        return packages.distinct().toTypedArray()
    }

    /**
     * IActivityTaskManager.getRecentTasks(maxNum, flags, userId) by reflection; it returns a
     * ParceledListSlice of RecentTaskInfo.
     */
    private fun recentTaskPackages(): List<String> {
        val method = atm.javaClass.methods.first { it.name == "getRecentTasks" && it.parameterCount == 3 }
        val slice = method.invoke(atm, MAX_RECENT_TASKS, RECENT_IGNORE_UNAVAILABLE, 0) ?: return emptyList()
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        return list.filterIsInstance<ActivityManager.RecentTaskInfo>()
            // Tasks that are not running may report an undefined type; only drop home, recents and the like.
            .filter { activityTypeOf(it) !in NON_APP_ACTIVITY_TYPES }
            .mapNotNull { it.baseIntent?.component?.packageName ?: it.baseActivity?.packageName }
    }

    /**
     * Apps cannot read the wallpaper image without READ_WALLPAPER_INTERNAL, which the shell user
     * has. Calls IWallpaperManager.getWallpaperWithFeature by reflection, filling arguments by type
     * as its parameter list differs between releases: first String is the calling package, first
     * int is which wallpaper (system), last int the user, and the cropped image is requested.
     */
    override fun getWallpaper(userId: Int): ParcelFileDescriptor? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "wallpaper") as IBinder
        val manager = Class.forName("android.app.IWallpaperManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("wallpaper service unavailable")
        val method = manager.javaClass.methods
            .filter { it.name == "getWallpaperWithFeature" }
            .maxByOrNull { it.parameterCount }
            ?: error("getWallpaperWithFeature not found")
        val types = method.parameterTypes
        val firstInt = types.indexOfFirst { it == Int::class.javaPrimitiveType }
        val lastInt = types.indexOfLast { it == Int::class.javaPrimitiveType }
        var callingPackageSet = false
        val args = Array<Any?>(types.size) { i ->
            when (types[i]) {
                String::class.java -> if (!callingPackageSet) SHELL_PACKAGE.also { callingPackageSet = true } else null
                Int::class.javaPrimitiveType -> when (i) {
                    firstInt -> WALLPAPER_FLAG_SYSTEM
                    lastInt -> userId
                    else -> 0
                }
                Boolean::class.javaPrimitiveType -> true
                Bundle::class.java -> Bundle()
                else -> null
            }
        }
        method.invoke(manager, *args) as ParcelFileDescriptor?
    }.onFailure {
        val cause = (it as? InvocationTargetException)?.targetException ?: it
        System.err.println("getWallpaper failed: $cause")
    }.getOrNull()

    private fun activityTypeOf(task: TaskInfo): Int =
        runCatching {
            val windowConfig = windowConfigOf(task) ?: return@runCatching -1
            windowConfig.javaClass.getMethod("getActivityType").invoke(windowConfig) as Int
        }.getOrDefault(-1)

    private fun isVisible(task: TaskInfo): Boolean = TaskInfo::class.java.getField("isVisible").getBoolean(task)

    /** The hidden TaskInfo.configuration.windowConfiguration. */
    private fun windowConfigOf(task: TaskInfo): Any? {
        val configuration = TaskInfo::class.java.getField("configuration").get(task) ?: return null
        return Configuration::class.java.getField("windowConfiguration").get(configuration)
    }

    /** Windowing mode and bounds from the hidden TaskInfo.configuration.windowConfiguration. */
    private fun windowOf(task: TaskInfo): Pair<Int, Rect>? {
        val windowConfig = windowConfigOf(task) ?: return null
        val bounds = windowConfig.javaClass.getMethod("getBounds").invoke(windowConfig) as Rect
        val mode = windowConfig.javaClass.getMethod("getWindowingMode").invoke(windowConfig) as Int
        return mode to bounds
    }

    /** getTasks(maxNum, ..., displayId): first int is maxNum, the remaining ints are INVALID_DISPLAY (all displays). */
    private fun runningTasks(): List<ActivityManager.RunningTaskInfo> {
        val method = atm.javaClass.methods
            .filter { it.name == "getTasks" }
            .maxByOrNull { it.parameterCount }
            ?: return emptyList()
        val types = method.parameterTypes
        val firstInt = types.indexOfFirst { it == Int::class.javaPrimitiveType }
        val args = Array<Any?>(types.size) { i ->
            when (types[i]) {
                Int::class.javaPrimitiveType -> if (i == firstInt) MAX_TASKS else INVALID_DISPLAY
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return (method.invoke(atm, *args) as List<*>).filterIsInstance<ActivityManager.RunningTaskInfo>()
    }

    companion object {
        /** Results starting with this mean the window was launched with bounds. */
        const val OK_PREFIX = "OK"
        const val TASK_STATE_SIZE = TaskState.SIZE
        private const val SHELL_PACKAGE = "com.android.shell"

        /** ActivityManager.START_SUCCESS (hidden). Negative codes are errors; positive ones mean an existing task was reused. */
        private const val START_SUCCESS = 0

        /** ActivityTaskManager.RESIZE_MODE_PRESERVE_WINDOW: resize without relaunching the activity. */
        private const val RESIZE_MODE_PRESERVE_WINDOW = 1
        private const val MAX_TASKS = 50
        private const val INVALID_DISPLAY = -1

        /** WindowConfiguration.ACTIVITY_TYPE_* (hidden). */
        private const val ACTIVITY_TYPE_STANDARD = 1
        private const val ACTIVITY_TYPE_RECENTS = 3

        /** ACTIVITY_TYPE_HOME, _RECENTS, _ASSISTANT, _DREAM. */
        private val NON_APP_ACTIVITY_TYPES = setOf(2, ACTIVITY_TYPE_RECENTS, 4, 5)

        private const val MAX_RECENT_TASKS = 40

        /** ActivityManager.RECENT_IGNORE_UNAVAILABLE: skip tasks whose app is gone. */
        private const val RECENT_IGNORE_UNAVAILABLE = 0x0002

        /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN (hidden). */
        private const val WINDOWING_MODE_FULLSCREEN = 1

        /** Task changes come in bursts; check once they settle. */
        private const val FIX_DEBOUNCE_MS = 250L

        /** Watch checks (one a second) without a change before the watch stops. */
        private const val MAX_WATCHED_CHECKS = 120

        /** Longest a shell command may run; see [exec]. */
        private const val EXEC_TIMEOUT_MS = 15_000L

        /** When to check that removed tasks stayed closed; see [removeTasks]. */
        private val REMOVE_RECHECK_MS = longArrayOf(500, 1500, 3000)

        /** Tasks count as managed for this long after TriSplit last polled them. */
        private const val MANAGED_TTL_MS = 2_000L
        private const val SIZE_TOLERANCE_PX = 8

        /** How often a leftover tiled pop-up's size is checked; see [fixNarrowPopups]. */
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val DAEMON_TAG = "TriSplitDaemon"

        /** Where the pop-up fixer keeps its records; writable by the shell user it runs as. */
        private const val TILED_FILE = "/data/local/tmp/trisplit-tiled.txt"

        /** WallpaperManager.FLAG_SYSTEM: the home-screen wallpaper. */
        private const val WALLPAPER_FLAG_SYSTEM = 1
    }
}
