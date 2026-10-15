package jp.trisplit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.content.ComponentName
import android.content.res.Configuration
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Bundle
import java.util.UUID
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }
    private var shizukuStatus by mutableStateOf(ShizukuBridge.Status.NOT_RUNNING)
    private var overlayAllowed by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)

    /** The setup guide: on first run, and again from Settings. */
    private var showGuide by mutableStateOf(false)

    /** Bumped on every resume, so screens re-read what may have changed meanwhile; see [MainScreen]. */
    private var resumes by mutableIntStateOf(0)

    /** Set when TriSplit was picked as the other side of a native split screen: the partner and our bounds. */
    private var splitPartner by mutableStateOf<SplitContext?>(null)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshStatus() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        // Start reading the app list now so the pickers are ready by the time they are needed.
        appScope.launch { AppCatalog.refresh(applicationContext) }
        // Permissions are asked for in the setup guide, with an explanation, rather than up front.
        showGuide = !prefs.onboardingDone
        setContent {
            val dark = isSystemInDarkTheme()
            // Colours derived from the wallpaper (Material You), so TriSplit matches the home screen.
            val colors = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) {
                val partner = splitPartner
                if (partner != null) {
                    val ownTaskId = taskId
                    // A different split (new partner or kept app) starts with fresh picks.
                    key(partner.partner.taskId, partner.replaced?.taskId) {
                    WallpaperBackground(dark) {
                        CompleteSplitScreen(prefs, partner.partner, partner.ownBounds, partner.replaced, TripleLauncher(this@MainActivity)) {
                            // By now this activity may have been recreated; close TriSplit's task itself.
                            getSystemService(ActivityManager::class.java).appTasks
                                .firstOrNull { it.taskInfo?.taskId == ownTaskId }
                                ?.finishAndRemoveTask()
                        }
                    }
                    }
                } else if (showGuide) {
                    OnboardingScreen(
                        SetupState(shizukuStatus, overlayAllowed, notificationsAllowed),
                        onRequestNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onFinish = {
                            prefs.onboardingDone = true
                            showGuide = false
                        },
                    )
                } else {
                    MainScreen(prefs, shizukuStatus, overlayAllowed, resumes, TripleLauncher(this)) { showGuide = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        refreshStatus()
        refreshSplitPartner()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshSplitPartner()
    }

    /**
     * Picking TriSplit as the other side of One UI's split screen opens this activity in split
     * mode. Find the app on the other side so the split can be completed into three columns.
     */
    private fun refreshSplitPartner() {
        // Completing the split dismisses it; keep the screen until the completion finishes.
        if (completingSplit) return
        if (!isInMultiWindowMode || ShizukuBridge.status() != ShizukuBridge.Status.READY) {
            splitPartner = null
            return
        }
        lifecycleScope.launch {
            val started = SystemClock.uptimeMillis()
            val launcher = TripleLauncher(this@MainActivity)
            var found: SplitContext? = null
            // The split may still be forming right after TriSplit was picked, so retry briefly.
            for (attempt in 1..SPLIT_ATTEMPTS) {
                found = runCatching { launcher.findSplitPartner(taskId) }.getOrNull()
                if (found != null || !isInMultiWindowMode) break
                delay(SPLIT_RETRY_MS)
            }
            Log.i(TripleLauncher.TAG, "split partner ${found?.partner} replaced ${found?.replaced} in ${SystemClock.uptimeMillis() - started} ms")
            splitPartner = found
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    private fun refreshStatus() {
        shizukuStatus = ShizukuBridge.status()
        overlayAllowed = Settings.canDrawOverlays(this)
        notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // Binding starts a separate process the first time, which is slow; do it before it is needed.
        if (shizukuStatus == ShizukuBridge.Status.READY) appScope.launch { runCatching { ShizukuBridge.service() } }
    }
}

private const val SPLIT_ATTEMPTS = 16
private const val SPLIT_RETRY_MS = 150L
private const val MAX_SPLIT_LOG_LINES = 3

/** Below this height the split-screen picker switches to its one-line header; see [CompleteSplitScreen]. */
private val COMPACT_SPLIT_HEIGHT = 560.dp

/** A split completion is running; see [refreshSplitPartner]. Process-wide, as the activity may be recreated. */
private var completingSplit = false

private val COLUMN_NAMES = listOf(R.string.column_left, R.string.column_center, R.string.column_right)
private const val MAX_GAP_PX = 48

internal const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Prints 1 when pop-up (freeform) windows are switched on. */
internal const val READ_FREEFORM_COMMAND = "settings get global enable_freeform_support"

/** Turns on freeform support and lets apps that declare themselves non-resizable open in freeform too. */
internal const val ENABLE_FREEFORM_COMMAND =
    "settings put global enable_freeform_support 1 && settings put global force_resizable_activities 1"

@Composable
private fun MainScreen(
    prefs: Prefs,
    status: ShizukuBridge.Status,
    overlayAllowed: Boolean,
    resumes: Int,
    launcher: TripleLauncher,
    onShowGuide: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var slots by remember { mutableStateOf(prefs.slots) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var gap by remember { mutableIntStateOf(prefs.gapPx) }
    var allowDuplicateWindows by remember { mutableStateOf(prefs.allowDuplicateWindows) }
    var showHandles by remember { mutableStateOf(prefs.showHandles) }
    var autoOpenSplit by remember { mutableStateOf(prefs.autoOpenSplit) }
    var splitOnClose by remember { mutableStateOf(prefs.splitOnClose) }
    var closeOthersOnPromote by remember { mutableStateOf(prefs.closeOthersOnPromote) }
    var rememberWidths by remember { mutableStateOf(prefs.rememberWidths) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var recents by remember { mutableStateOf(emptyList<AppEntry>()) }
    var favorites by remember { mutableStateOf(prefs.favorites) }
    var editingFavorites by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(prefs.presets) }
    var history by remember { mutableStateOf(prefs.history) }
    // The preset being edited; a new, unsaved one when created with "New preset" or "Save as preset".
    var editingPreset by remember { mutableStateOf<Preset?>(null) }
    var deletingPreset by remember { mutableStateOf<Preset?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Whether pop-up windows are switched on; null until known (needs Shizuku to read).
    var freeformEnabled by remember { mutableStateOf<Boolean?>(null) }
    val toggleFavorite: (AppEntry) -> Unit = { app ->
        favorites = favorites.toggled(app.component.flattenToString())
        prefs.favorites = favorites
    }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var summary by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
    // Folded, on the front screen: settings and presets can be edited, but nothing opens tiled.
    val front = remember(LocalConfiguration.current) { context.isFrontScreen() }
    // Packages that can still be opened; null until the app list is read.
    val installed = apps?.map { it.component.packageName }?.toSet()
    fun isMissing(cn: ComponentName) = installed != null && cn.packageName !in installed

    fun enableFreeform() {
        scope.launch {
            val ok = FreeformSetting.enable()
            if (ok) freeformEnabled = true
            summary = resources.getString(R.string.log_freeform_setting, resources.getString(if (ok) R.string.freeform_on else R.string.freeform_failed))
        }
    }

    fun savePresets(updated: List<Preset>) {
        presets = updated
        prefs.presets = updated
        // Builds icons and makes several binder calls; off the main thread.
        appScope.launch(Dispatchers.Default) { PresetShortcuts.update(context.applicationContext, updated) }
    }

    // Each time TriSplit comes back: the notification ("Save as preset"), shortcuts and the tile
    // change presets and history behind this screen, and apps may have been installed or removed.
    // Without re-reading, the next save would write the stale lists back over those changes.
    LaunchedEffect(resumes) {
        presets = prefs.presets
        history = prefs.history
        favorites = prefs.favorites
        val loaded = if (resumes <= 1) AppCatalog.load(context) else AppCatalog.refresh(context)
        apps = loaded
        recents = runCatching { launcher.recentApps(loaded) }.getOrDefault(emptyList())
        // Pinned shortcuts made by an older version, or edited elsewhere, catch up.
        val current = presets
        withContext(Dispatchers.Default) { PresetShortcuts.update(context.applicationContext, current) }
    }

    LaunchedEffect(status) {
        if (status != ShizukuBridge.Status.READY) return@LaunchedEffect
        freeformEnabled = FreeformSetting.isEnabled()
    }

    // Opens [toLaunch] tiled, with the current launch settings; used by the button, presets and history.
    // In appScope (via LaunchGuard), so rotating or unfolding mid-launch does not cut it short.
    fun launchApps(toLaunch: List<ComponentName>) {
        if (front) return
        toLaunch.firstOrNull(::isMissing)?.let { missing ->
            summary = resources.getString(R.string.launch_app_missing, missing.packageName)
            return
        }
        LaunchGuard.run {
            log.clear()
            summary = null
            val handles = mode == LaunchMode.SHIZUKU && showHandles
            val session = launcher.launch(toLaunch, mode, gap, allowDuplicateWindows, withBackdrop = handles) {
                log.add(it.describe(context))
            }
            val failed = session.results.count { !it.ok }
            if (failed < session.results.size) {
                prefs.recordHistory(toLaunch)
                history = prefs.history
            }
            if (handles) {
                DividerOverlayService.start(context, session)?.let {
                    log.add(it)
                    BackdropActivity.dismiss()
                }
            }
            summary = if (failed == 0) {
                resources.getQuantityString(R.plurals.launch_summary_ok, session.results.size, session.results.size)
            } else {
                resources.getQuantityString(R.plurals.launch_summary_failed, failed, failed)
            }
            showLog = failed > 0
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                StatusChip(status, mode)
            }

            SetupCard(
                status = status,
                mode = mode,
                freeformEnabled = freeformEnabled,
                needsOverlay = mode == LaunchMode.SHIZUKU && showHandles && !overlayAllowed,
                onEnableFreeform = ::enableFreeform,
            )

            if (front) FrontScreenNotice()
            val canLaunch = (mode == LaunchMode.DIRECT || status == ShizukuBridge.Status.READY) && !LaunchGuard.busy && !front
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.apps_heading), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.apps_hint), style = MaterialTheme.typography.bodySmall)
                    ReorderableColumn(
                        items = slots.indices.toList(),
                        key = { it },
                        onMove = { from, to ->
                            slots = slots.moved(from, to)
                            prefs.slots = slots
                        },
                    ) { slot, position, dragging, handle ->
                        val cn = slots[slot]
                        AppSlotRow(
                            COLUMN_NAMES[position], cn, cn?.let { labelOf(apps, it) }, dragging, handle,
                            missing = cn != null && isMissing(cn),
                        ) { pickingSlot = slot }
                    }
                    val duplicates = TripleLauncher.duplicates(slots)
                    if (duplicates.isNotEmpty()) {
                        // Apps that declare two windows of themselves open them by themselves; for the
                        // rest, forcing a second window is a choice with no guarantee.
                        val unsupported = duplicates.filterNot { MultiInstance.supports(context.packageManager, it) }
                        if (unsupported.isEmpty()) {
                            Text(stringResource(R.string.duplicate_supported), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                stringResource(R.string.duplicate_warning_apps, unsupported.joinToString { labelOf(apps, it) }),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            SwitchRow(
                                stringResource(R.string.duplicate_switch),
                                allowDuplicateWindows,
                            ) { allowDuplicateWindows = it; prefs.allowDuplicateWindows = it }
                        }
                    }
                    val chosen = slots.filterNotNull()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { launchApps(chosen) },
                            enabled = chosen.size == Prefs.SLOT_COUNT && chosen.none(::isMissing) && canLaunch,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.launch_button)) }
                        OutlinedButton(
                            onClick = {
                                editingPreset = Preset(UUID.randomUUID().toString(), "", chosen.map { it.flattenToString() })
                            },
                            enabled = chosen.size == Prefs.SLOT_COUNT,
                        ) { Text(stringResource(R.string.save_as_preset)) }
                    }
                    if (LaunchGuard.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LaunchReport(summary, log, showLog) { showLog = !showLog }
                }
            }

            PresetsCard(
                apps = apps,
                presets = presets,
                canLaunch = canLaunch,
                isMissing = ::isMissing,
                onLaunch = { preset -> launchApps(preset.components.mapNotNull(ComponentName::unflattenFromString)) },
                onEdit = { preset -> editingPreset = preset },
                onPin = { preset ->
                    if (!PresetShortcuts.pin(context, preset)) {
                        Toast.makeText(context, R.string.preset_pin_unsupported, Toast.LENGTH_LONG).show()
                    }
                },
                onDelete = { preset -> deletingPreset = preset },
                onNew = { editingPreset = Preset(UUID.randomUUID().toString(), "", emptyList()) },
                onMove = { from, to -> savePresets(presets.moved(from, to)) },
            )

            // Combinations opened lately that are not saved as presets: one tap to open again or keep.
            // Compared by app, not activity: a preset may name an older entry point of the same app.
            val unsaved = history.filter { entry ->
                val packages = entry.map { it.substringBefore('/') }
                presets.none { it.packages == packages }
            }
            if (unsaved.isNotEmpty()) {
                HistoryCard(
                    apps = apps,
                    history = unsaved,
                    canLaunch = canLaunch,
                    isMissing = ::isMissing,
                    onLaunch = { entry -> launchApps(entry.mapNotNull(ComponentName::unflattenFromString)) },
                    onSave = { entry -> editingPreset = Preset(UUID.randomUUID().toString(), "", entry) },
                )
            }

            FavoritesCard(apps, favorites) { editingFavorites = true }

            SettingsCard(open = settingsOpen, onToggle = { settingsOpen = !settingsOpen }) {
                Text(stringResource(R.string.mode_heading), style = MaterialTheme.typography.titleSmall)
                ModeOption(stringResource(R.string.mode_shizuku), mode == LaunchMode.SHIZUKU) {
                    mode = LaunchMode.SHIZUKU; prefs.mode = mode
                }
                ModeOption(stringResource(R.string.mode_direct), mode == LaunchMode.DIRECT) {
                    mode = LaunchMode.DIRECT; prefs.mode = mode
                }
                Text(stringResource(R.string.gap_label, gap))
                val gapLabel = stringResource(R.string.gap_label, gap)
                Slider(
                    modifier = Modifier.semantics { contentDescription = gapLabel },
                    value = gap.toFloat(),
                    onValueChange = { gap = it.toInt() },
                    onValueChangeFinished = { prefs.gapPx = gap },
                    valueRange = 0f..MAX_GAP_PX.toFloat(),
                )
                SwitchRow(stringResource(R.string.auto_open_switch), autoOpenSplit) {
                    autoOpenSplit = it; prefs.autoOpenSplit = it
                }
                SwitchRow(stringResource(R.string.remember_widths_switch), rememberWidths) {
                    rememberWidths = it; prefs.rememberWidths = it
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(onClick = { requestTile(context) }) { Text(stringResource(R.string.add_tile_button)) }
                    Text(stringResource(R.string.add_tile_help), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text(stringResource(R.string.handles_heading), style = MaterialTheme.typography.titleSmall)
                SwitchRow(stringResource(R.string.handles_switch), showHandles) {
                    showHandles = it; prefs.showHandles = it
                }
                SwitchRow(stringResource(R.string.split_on_close_switch), splitOnClose) {
                    splitOnClose = it; prefs.splitOnClose = it
                }
                SwitchRow(stringResource(R.string.close_others_switch), closeOthersOnPromote) {
                    closeOthersOnPromote = it; prefs.closeOthersOnPromote = it
                }
                Text(stringResource(R.string.handles_help), style = MaterialTheme.typography.bodySmall)
                if (status == ShizukuBridge.Status.READY) {
                    HorizontalDivider()
                    OutlinedButton(onClick = ::enableFreeform) { Text(stringResource(R.string.enable_freeform)) }
                    Text(stringResource(R.string.enable_freeform_help), style = MaterialTheme.typography.bodySmall)
                }
                if (AppLanguage.supported) {
                    HorizontalDivider()
                    LanguageSection()
                }
                HorizontalDivider()
                OutlinedButton(onClick = onShowGuide) { Text(stringResource(R.string.onboarding_open)) }
            }
        }
    }

    editingPreset?.let { preset ->
        val isNew = presets.none { it.id == preset.id }
        PresetEditorDialog(
            preset = preset,
            isNew = isNew,
            apps = apps,
            recents = recents,
            favorites = favorites,
            onToggleFavorite = toggleFavorite,
            currentSlots = slots,
            onDismiss = { editingPreset = null },
            onSave = { saved ->
                savePresets(if (isNew) presets + saved else presets.map { if (it.id == saved.id) saved else it })
                editingPreset = null
            },
            onDelete = {
                editingPreset = null
                deletingPreset = preset
            },
        )
    }

    deletingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            confirmButton = {
                TextButton(onClick = {
                    savePresets(presets.filterNot { it.id == preset.id })
                    deletingPreset = null
                }) { Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingPreset = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.preset_delete_title, preset.name)) },
        )
    }

    if (editingFavorites) {
        FavoritesDialog(apps, recents, favorites, toggleFavorite) { editingFavorites = false }
    }

    pickingSlot?.let { slot ->
        AppPickerDialog(
            apps = apps,
            recents = recents,
            favorites = favorites,
            onToggleFavorite = toggleFavorite,
            onDismiss = { pickingSlot = null },
            onPick = { picked ->
                slots = slots.toMutableList().also { it[slot] = picked.component }
                prefs.slots = slots
                pickingSlot = null
            },
        )
    }
}

/** Shown on the front screen, where three columns do not fit. */
@Composable
private fun FrontScreenNotice() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(stringResource(R.string.front_screen_blocked), modifier = Modifier.padding(16.dp))
    }
}

/** Shizuku's state at a glance, top right; the setup card below says what to do about it. */
@Composable
private fun StatusChip(status: ShizukuBridge.Status, mode: LaunchMode) {
    val (label, ok) = when {
        mode == LaunchMode.DIRECT -> stringResource(R.string.status_direct) to true
        status == ShizukuBridge.Status.READY -> stringResource(R.string.status_ready) to true
        status == ShizukuBridge.Status.NO_PERMISSION -> stringResource(R.string.status_no_permission) to false
        else -> stringResource(R.string.status_not_running) to false
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/**
 * What still stands between the user and three columns, each with the button that fixes it:
 * Shizuku stopped or not allowed, pop-up windows off, overlay permission missing. Hidden when all
 * is well.
 */
@Composable
private fun SetupCard(
    status: ShizukuBridge.Status,
    mode: LaunchMode,
    freeformEnabled: Boolean?,
    needsOverlay: Boolean,
    onEnableFreeform: () -> Unit,
) {
    val context = LocalContext.current
    val shizukuIssue = mode == LaunchMode.SHIZUKU && status != ShizukuBridge.Status.READY
    val freeformIssue = status == ShizukuBridge.Status.READY && freeformEnabled == false
    if (!shizukuIssue && !freeformIssue && !needsOverlay) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_heading), style = MaterialTheme.typography.titleMedium)
            when {
                mode != LaunchMode.SHIZUKU -> Unit
                status == ShizukuBridge.Status.NOT_RUNNING -> {
                    Text(stringResource(R.string.shizuku_not_running))
                    val open = remember { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }
                    if (open != null) Button(onClick = { context.startActivity(open) }) { Text(stringResource(R.string.open_shizuku)) }
                }
                status == ShizukuBridge.Status.NO_PERMISSION -> {
                    Text(stringResource(R.string.shizuku_no_permission))
                    Button(onClick = ShizukuBridge::requestPermission) { Text(stringResource(R.string.shizuku_request_permission)) }
                }
            }
            if (freeformIssue) {
                Text(stringResource(R.string.setup_freeform))
                Button(onClick = onEnableFreeform) { Text(stringResource(R.string.enable_freeform)) }
            }
            if (needsOverlay) {
                Text(stringResource(R.string.setup_overlay))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                    )
                }) { Text(stringResource(R.string.overlay_permission_button)) }
            }
        }
    }
}

/** The last launch: one line saying how it went, with each window's result behind "Details". */
@Composable
private fun LaunchReport(summary: String?, log: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    if (summary == null && log.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(summary.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (log.isNotEmpty()) {
            TextButton(onClick = onToggle) {
                Text(stringResource(if (expanded) R.string.log_hide else R.string.log_show))
            }
        }
    }
    if (expanded) {
        log.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Everything that is set once and rarely changed, folded away by default. */
@Composable
private fun SettingsCard(open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.collapse else R.string.expand), onClick = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (open) "▲" else "▼",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            if (open) content()
        }
    }
}

/**
 * Shown when TriSplit is the other side of a native split screen next to [partner]. The remaining
 * columns are shown as chips; the active one is filled by tapping an app in the drawer below. Then
 * the partner leaves the split and all three open tiled.
 */
@Composable
private fun CompleteSplitScreen(
    prefs: Prefs,
    partner: VisibleTask,
    ownBounds: Bounds?,
    replaced: StageTask?,
    launcher: TripleLauncher,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var recents by remember { mutableStateOf(emptyList<AppEntry>()) }
    var favorites by remember { mutableStateOf(prefs.favorites) }
    // Dropped onto one side of an existing split, the app that was there stays: it takes the middle
    // column, and only the last app is left to choose.
    val replacedEntry = remember(replaced) {
        replaced?.let { r -> ComponentName.unflattenFromString(r.component)?.let { AppEntry(it, r.packageName) } }
    }
    var picks by remember {
        val firstPartnerColumn = VisibleTask.partnerColumn(partner, ownBounds, COLUMN_NAMES.size)
        val free = COLUMN_NAMES.indices.filter { it != firstPartnerColumn }
        mutableStateOf(List(free.size) { slot -> replacedEntry?.takeIf { free[slot] == COLUMN_NAMES.size / 2 } })
    }
    var activeSlot by remember { mutableIntStateOf(picks.indexOfFirst { it == null }.coerceAtLeast(0)) }
    var running by remember { mutableStateOf(false) }
    // A preset with more apps than free columns, waiting for the user to choose which to use.
    var choosingFrom by remember { mutableStateOf<PresetChoice?>(null) }

    // The column kept for the app that was already in the split, while it is still picked there.
    fun keptSlot() = replacedEntry?.let { kept ->
        picks.indexOfFirst { it?.component?.packageName == kept.component.packageName }.takeIf { it >= 0 }
    }

    // Fills the free columns left to right with [entries]; any column left empty becomes active.
    // The app that was already in the split keeps its column unless [entries] include it.
    fun fillPicks(entries: List<AppEntry>) {
        val kept = keptSlot()?.takeIf { slot -> entries.none { it.component.packageName == picks[slot]!!.component.packageName } }
        val queue = entries.iterator()
        picks = List(picks.size) { slot -> if (slot == kept) picks[slot] else if (queue.hasNext()) queue.next() else null }
        activeSlot = picks.indexOfFirst { it == null }.takeIf { it >= 0 } ?: 0
    }

    // Columns a preset can fill: all of them, but the kept app's.
    fun openSlots() = picks.size - (if (keptSlot() != null) 1 else 0)
    val log = remember { mutableStateListOf<String>() }
    // Starts on the partner's side; a preset that contains the partner can move it.
    var partnerColumn by remember { mutableIntStateOf(VisibleTask.partnerColumn(partner, ownBounds, COLUMN_NAMES.size)) }
    val presets = remember { prefs.presets }
    val partnerLabel = apps?.firstOrNull { it.component.packageName == partner.packageName }?.label
        ?: partner.packageName

    fun openSplit() {
        if (running || picks.any { it == null }) return
        if (context.isFrontScreen()) {
            Toast.makeText(context, R.string.front_screen_blocked, Toast.LENGTH_LONG).show()
            return
        }
        running = true
        completingSplit = true
        val appContext = context.applicationContext
        val chosen = picks.filterNotNull().map { it.component }
        // Anything thrown while preparing must not leave the screen stuck as "completing".
        runCatching {
            // Cover the screen with the finished layout until the windows are open, so TriSplit closing
            // and the apps' launch animations stay out of sight.
            val freeColumns = COLUMN_NAMES.indices.filter { it != partnerColumn }
            val packages = MutableList(COLUMN_NAMES.size) { partner.packageName }.also { list ->
                freeColumns.zip(chosen).forEach { (column, app) -> list[column] = app.packageName }
            }
            val area = launcher.area()
            // Where the apps are now: the partner's half, and TriSplit's half shared by the two picks.
            val from = ownBounds?.let { own ->
                fun clip(b: Bounds) = Bounds(maxOf(b.left, area.left), maxOf(b.top, area.top), minOf(b.right, area.right), minOf(b.bottom, area.bottom))
                val halves = TripleLayout.columns(clip(own), 0, freeColumns.size)
                List(COLUMN_NAMES.size) { column ->
                    if (column == partnerColumn) clip(partner.bounds) else halves[freeColumns.indexOf(column)]
                }
            }
            val partnerApp = ComponentName.unflattenFromString(partner.component) ?: ComponentName(partner.packageName, "")
            val target = launcher.columnsFor(launcher.splitOrder(partnerApp, partnerColumn, chosen), prefs.gapPx, area)
            DividerOverlayService.cover(appContext, area, prefs.gapPx, packages, from, liveColumn = partnerColumn, columns = target)
        }.onFailure {
            Log.w(TripleLauncher.TAG, "could not prepare the split completion", it)
            DividerOverlayService.uncover()
            running = false
            completingSplit = false
            return
        }
        // appScope, not the composition: leaving the split recreates this screen mid-way.
        DividerOverlayService.pauseForLaunch()
        appScope.launch {
            try {
                val session = launcher.completeSplit(
                    partner, partnerColumn, chosen, prefs.gapPx,
                    withBackdrop = prefs.showHandles,
                    existing = listOfNotNull(replaced).associate { it.packageName to it.taskId },
                    // Close as soon as the split is gone: left open, the system expands this screen
                    // to full screen and it shows through the columns not opened yet.
                    onPartnerMoved = onDone,
                ) { log.add(0, it.describe(context)) }
                if (session.results.any { it.ok }) prefs.recordHistory(session.results.sortedBy { it.column }.map { it.app })
                val problems = session.results.filterNot { it.ok }.map { it.describe(appContext) }.toMutableList()
                val handlesProblem = if (prefs.showHandles) DividerOverlayService.start(appContext, session) else null
                handlesProblem?.let { problems += it }
                // No handles took over the cover: lift it, and the backdrop goes with the handles.
                if (!prefs.showHandles || handlesProblem != null) {
                    DividerOverlayService.uncover()
                    BackdropActivity.dismiss()
                }
                if (problems.isNotEmpty()) {
                    // The screen may be gone already, so report in a toast rather than the log.
                    Toast.makeText(appContext, problems.joinToString("\n"), Toast.LENGTH_LONG).show()
                    // Allow another try only if nothing opened; otherwise it would open the apps twice.
                    if (session.results.none { it.ok }) running = false
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TripleLauncher.TAG, "split completion failed", t)
                DividerOverlayService.uncover()
                BackdropActivity.dismiss()
                Toast.makeText(appContext, appContext.getString(R.string.error_message, t.javaClass.simpleName, t.message), Toast.LENGTH_LONG).show()
                running = false
            } finally {
                completingSplit = false
                DividerOverlayService.resumeAfterLaunch()
            }
        }
    }

    // Opens as soon as both free columns are filled (app taps, a preset, or the preset chooser).
    LaunchedEffect(picks) {
        if (prefs.autoOpenSplit) openSplit()
    }

    LaunchedEffect(Unit) {
        val loaded = AppCatalog.load(context)
        apps = loaded
        // The kept app was named by its package until the app list arrived; use its real label.
        picks = picks.map { pick -> pick?.let { p -> loaded.firstOrNull { it.component.packageName == p.component.packageName } ?: p } }
        recents = runCatching { launcher.recentApps(loaded, exclude = setOf(partner.packageName)) }
            .onFailure { Log.w(TripleLauncher.TAG, "recent apps not available", it) }
            .getOrDefault(emptyList())
    }

    // Transparent over WallpaperBackground; contentColor must be set, or text defaults to black.
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        // A split-screen half can be short (One UI can split one side into top and bottom): then the
        // title, the columns and the button share one line, and the drawer gets the rest.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val compact = maxHeight < COMPACT_SPLIT_HEIGHT
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                // In a narrow split half the chips overflow; keep the column being chosen scrolled into view,
                // so after picking the centre app the right one shows up by itself.
                val chipScroll = rememberScrollState()
                val chipSpans = remember { mutableStateMapOf<Int, IntRange>() }
                LaunchedEffect(activeSlot, chipSpans[activeSlot]) {
                    val span = chipSpans[activeSlot] ?: return@LaunchedEffect
                    val viewport = chipScroll.viewportSize
                    val target = when {
                        span.first < chipScroll.value -> span.first
                        span.last > chipScroll.value + viewport -> span.last - viewport
                        else -> return@LaunchedEffect
                    }
                    chipScroll.animateScrollTo(target.coerceIn(0, chipScroll.maxValue))
                }
                @Composable
                fun ColumnChips(modifier: Modifier) = Row(
                    modifier.horizontalScroll(chipScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var pickIndex = 0
                    COLUMN_NAMES.forEachIndexed { column, name ->
                        if (column == partnerColumn) {
                            FilterChip(
                                selected = false,
                                enabled = false,
                                onClick = {},
                                label = { Text(stringResource(R.string.complete_split_slot, stringResource(name), partnerLabel)) },
                            )
                        } else {
                            val slot = pickIndex++
                            val pick = picks[slot]
                            FilterChip(
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    val x = coords.positionInParent().x.roundToInt()
                                    chipSpans[slot] = x..(x + coords.size.width)
                                },
                                selected = slot == activeSlot,
                                enabled = !running,
                                onClick = {
                                    // Tapping the active column again clears it.
                                    if (slot == activeSlot && pick != null) {
                                        picks = picks.toMutableList().also { it[slot] = null }
                                    }
                                    activeSlot = slot
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.complete_split_slot,
                                            stringResource(name),
                                            pick?.label ?: stringResource(R.string.slot_not_selected),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                if (compact) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.complete_split_title), style = MaterialTheme.typography.titleMedium)
                        ColumnChips(Modifier.weight(1f))
                        // With auto-open the last pick opens the columns; the button is shown when that
                        // is off, or when all columns are filled but nothing is running (a retry).
                        if (!prefs.autoOpenSplit || (picks.all { it != null } && !running)) {
                            TextButton(onClick = { openSplit() }, enabled = picks.all { it != null } && !running) {
                                Text(stringResource(R.string.complete_split_button))
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.complete_split_title), style = MaterialTheme.typography.titleLarge)
                    val replacedPick = picks.firstOrNull { it != null && it.component.packageName == replaced?.packageName }
                    Text(
                        if (replacedPick != null) {
                            stringResource(R.string.complete_split_help_one, partnerLabel, replacedPick.label)
                        } else {
                            stringResource(R.string.complete_split_help, partnerLabel)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ColumnChips(Modifier)
                    Button(
                        onClick = { openSplit() },
                        enabled = picks.all { it != null } && !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.complete_split_button)) }

                    log.take(MAX_SPLIT_LOG_LINES).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }

                AppDrawer(
                    apps = apps,
                    favorites = favorites,
                    recents = recents,
                    onPick = { app ->
                        // An app already in another column (the split partner, the app kept from
                        // the split, or an earlier pick) would share its one window between two
                        // columns, unless it can open a second window of itself.
                        val taken = app.component.packageName == partner.packageName ||
                            picks.withIndex().any { (i, p) -> i != activeSlot && p?.component?.packageName == app.component.packageName }
                        if (taken && !MultiInstance.supports(context.packageManager, app.component)) {
                            Toast.makeText(context, resources.getString(R.string.complete_split_already_open, app.label), Toast.LENGTH_SHORT).show()
                        } else if (!running) {
                            val updated = picks.toMutableList().also { it[activeSlot] = app }
                            picks = updated
                            // Move on to the next empty column, so two taps fill both.
                            updated.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { activeSlot = it }
                        }
                    },
                    onToggleFavorite = { app ->
                        favorites = favorites.toggled(app.component.flattenToString())
                        prefs.favorites = favorites
                    },
                    modifier = Modifier.weight(1f),
                    compact = compact,
                    presets = presets,
                    onPickPreset = { preset ->
                        if (!running) {
                            val installed = apps.orEmpty().map { it.component.packageName }.toSet()
                            val choices = preset.splitChoices(partner.packageName, installed)
                            val entries = choices.candidates.mapNotNull { flat ->
                                val cn = ComponentName.unflattenFromString(flat) ?: return@mapNotNull null
                                apps?.firstOrNull { it.component == cn } ?: AppEntry(cn, cn.packageName)
                            }
                            // A preset that contains the partner puts it in the preset's column; one that
                            // does not leaves it on its own side. Applied only once the preset's apps
                            // are used (not if its chooser is cancelled).
                            val column = choices.partnerColumn ?: VisibleTask.partnerColumn(partner, ownBounds, COLUMN_NAMES.size)
                            val kept = replacedEntry?.component?.packageName
                            val offered = entries.filter { it.component.packageName != kept || keptSlot() == null }
                            when {
                                offered.size > openSlots() -> choosingFrom = PresetChoice(preset, offered, column)
                                offered.isNotEmpty() -> {
                                    partnerColumn = column
                                    fillPicks(offered)
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    choosingFrom?.let { choice ->
        PresetChooserDialog(choice.preset.name, choice.entries, count = openSlots(), onDismiss = { choosingFrom = null }) { chosen ->
            partnerColumn = choice.partnerColumn
            fillPicks(chosen)
            choosingFrom = null
        }
    }
}

/** A preset with more apps than free columns, waiting for the user to choose which to use. */
private data class PresetChoice(val preset: Preset, val entries: List<AppEntry>, val partnerColumn: Int)

/**
 * Lets the user pick [count] of a preset's apps, for when the preset has more apps than there are
 * free columns. Tapping toggles an app; the order follows the preset.
 */
@Composable
private fun PresetChooserDialog(
    name: String,
    entries: List<AppEntry>,
    count: Int,
    onDismiss: () -> Unit,
    onChoose: (List<AppEntry>) -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onChoose(entries.filterIndexed { i, _ -> i in selected }) },
                enabled = selected.size == count,
            ) { Text(stringResource(R.string.preset_choose_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.preset_choose_title, count, name)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEachIndexed { i, entry ->
                    val isSelected = i in selected
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                            )
                            .toggleable(value = isSelected, role = Role.Checkbox) {
                                selected = when {
                                    isSelected -> selected - i
                                    selected.size < count -> selected + i
                                    else -> selected
                                }
                            }
                            .padding(8.dp),
                    ) {
                        AppIcon(entry.component, size = 44.dp)
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    )
}

private fun Set<String>.toggled(item: String): Set<String> = if (item in this) this - item else this + item

@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    // Changing the per-app locale recreates the activity, so the current value is simply re-read.
    val current = remember { AppLanguage.current(context) }
    Text(stringResource(R.string.language_heading), style = MaterialTheme.typography.titleSmall)
    listOf(
        AppLanguage.Option.SYSTEM to R.string.language_system,
        AppLanguage.Option.ENGLISH to R.string.language_english,
        AppLanguage.Option.JAPANESE to R.string.language_japanese,
        AppLanguage.Option.CHINESE_SIMPLIFIED to R.string.language_chinese_simplified,
        AppLanguage.Option.CHINESE_TRADITIONAL to R.string.language_chinese_traditional,
    ).forEach { (option, label) ->
        ModeOption(stringResource(label), option == current) {
            if (option != current) AppLanguage.set(context, option)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so TalkBack reads the label with it.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Favourite apps at a glance, with a button to edit them, on TriSplit's own screen. */
@Composable
private fun FavoritesCard(apps: List<AppEntry>?, favorites: Set<String>, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.favorites_heading), style = MaterialTheme.typography.titleMedium)
            val favoriteApps = apps.orEmpty().filter { it.component.flattenToString() in favorites }
            if (favoriteApps.isEmpty()) {
                Text(stringResource(R.string.favorites_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    favoriteApps.forEach { AppIcon(it.component, size = 40.dp, description = it.label) }
                }
            }
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.favorites_edit)) }
        }
    }
}

/** Asks the system to add TriSplit's tile to Quick Settings (it shows its own confirmation). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestTile(context: android.content.Context) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, LaunchTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.add_tile_done
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.add_tile_already
            else -> null
        }
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
}

private fun labelOf(apps: List<AppEntry>?, cn: ComponentName) =
    (apps?.firstOrNull { it.component == cn } ?: apps?.firstOrNull { it.component.packageName == cn.packageName })
        ?.label ?: cn.packageName

/**
 * One column's app, labelled with the column's position: tap to choose the app, long-press or drag
 * the handle to move it to another column.
 */
@Composable
private fun AppSlotRow(
    @StringRes columnName: Int,
    component: ComponentName?,
    label: String?,
    dragging: Boolean,
    handle: Modifier,
    missing: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(columnName),
            Modifier.width(64.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (component != null) {
            AppIcon(component, size = 32.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label ?: stringResource(R.string.slot_not_selected),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (label == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            if (missing) {
                Text(stringResource(R.string.app_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        DragHandle(handle)
    }
}

/**
 * Saved app combinations: tap one to open it, ✎ to edit, ⋮ for the home-screen shortcut or delete,
 * drag ≡ to reorder. A preset with an app that is no longer installed opens the editor instead.
 */
@Composable
private fun PresetsCard(
    apps: List<AppEntry>?,
    presets: List<Preset>,
    canLaunch: Boolean,
    isMissing: (ComponentName) -> Boolean,
    onLaunch: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    onPin: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onNew: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.presets_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onNew) { Text(stringResource(R.string.presets_new)) }
            }
            Text(
                stringResource(if (presets.isEmpty()) R.string.presets_empty else R.string.presets_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            ReorderableColumn(presets, key = { it.id }, onMove = onMove, spacing = 4.dp) { preset, _, dragging, handle ->
                val missing = preset.components.mapNotNull(ComponentName::unflattenFromString).any(isMissing)
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable(enabled = missing || canLaunch) { if (missing) onEdit(preset) else onLaunch(preset) }
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetRow(
                        preset, apps, Modifier.weight(1f),
                        warning = if (missing) stringResource(R.string.preset_missing) else null,
                    )
                    IconTextButton("✎", stringResource(R.string.presets_edit)) { onEdit(preset) }
                    Box {
                        IconTextButton("⋮", stringResource(R.string.preset_more)) { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.preset_add_home)) },
                                onClick = { menuOpen = false; onPin(preset) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onDelete(preset) },
                            )
                        }
                    }
                    DragHandle(handle)
                }
            }
        }
    }
}

/** Combinations opened lately (not saved as presets): tap to open again, or keep one as a preset. */
@Composable
private fun HistoryCard(
    apps: List<AppEntry>?,
    history: List<List<String>>,
    canLaunch: Boolean,
    isMissing: (ComponentName) -> Boolean,
    onLaunch: (List<String>) -> Unit,
    onSave: (List<String>) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.history_heading), style = MaterialTheme.typography.titleMedium)
            history.forEach { entry ->
                val missing = entry.mapNotNull(ComponentName::unflattenFromString).any(isMissing)
                val preset = Preset("", entry.joinToString(" / ") { flat -> ComponentName.unflattenFromString(flat)?.let { labelOf(apps, it) } ?: flat }, entry)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = canLaunch && !missing) { onLaunch(entry) }
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetRow(
                        preset, apps, Modifier.weight(1f), showSubtitle = false,
                        warning = if (missing) stringResource(R.string.preset_missing) else null,
                    )
                    TextButton(onClick = { onSave(entry) }) { Text(stringResource(R.string.history_save)) }
                }
            }
        }
    }
}

/** A compact button whose face is a symbol; [description] is what TalkBack reads. */
@Composable
private fun IconTextButton(symbol: String, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(symbol, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Creates or edits a preset: its name and the app in each column, chosen from the app drawer and
 * reordered by dragging. The apps chosen above on the main screen can be taken in one go. A blank
 * name is filled from the app names.
 */
@Composable
private fun PresetEditorDialog(
    preset: Preset,
    isNew: Boolean,
    apps: List<AppEntry>?,
    recents: List<AppEntry>,
    favorites: Set<String>,
    onToggleFavorite: (AppEntry) -> Unit,
    currentSlots: List<ComponentName?>,
    onDismiss: () -> Unit,
    onSave: (Preset) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(preset.name) }
    var components by remember {
        mutableStateOf(List(COLUMN_NAMES.size) { i -> preset.components.getOrNull(i)?.let(ComponentName::unflattenFromString) })
    }
    var pickingColumn by remember { mutableStateOf<Int?>(null) }
    fun labelOf(cn: ComponentName) = labelOf(apps, cn)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = components.filterNotNull()
                    val finalName = Preset.sanitize(name).ifBlank { chosen.joinToString(" / ", transform = ::labelOf) }
                    onSave(Preset(preset.id, finalName, chosen.map { it.flattenToString() }))
                },
                enabled = components.all { it != null },
            ) { Text(stringResource(R.string.presets_save_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(if (isNew) R.string.presets_new else R.string.presets_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.presets_name)) },
                    placeholder = { Text(stringResource(R.string.presets_name_auto)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.apps_hint), style = MaterialTheme.typography.bodySmall)
                ReorderableColumn(
                    items = components.indices.toList(),
                    key = { it },
                    onMove = { from, to -> components = components.moved(from, to) },
                ) { i, position, dragging, handle ->
                    val cn = components[i]
                    AppSlotRow(COLUMN_NAMES[position], cn, cn?.let(::labelOf), dragging, handle) { pickingColumn = i }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { components = currentSlots.toList() },
                        enabled = currentSlots.any { it != null },
                    ) { Text(stringResource(R.string.presets_from_current)) }
                    if (!isNew) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
    )

    pickingColumn?.let { column ->
        AppPickerDialog(
            apps = apps,
            recents = recents,
            favorites = favorites,
            onToggleFavorite = onToggleFavorite,
            onDismiss = { pickingColumn = null },
            onPick = { picked ->
                components = components.toMutableList().also { it[column] = picked.component }
                pickingColumn = null
            },
        )
    }
}

/** The app drawer in editing mode: tapping an app adds or removes it as a favourite. */
@Composable
private fun FavoritesDialog(
    apps: List<AppEntry>?,
    recents: List<AppEntry>,
    favorites: Set<String>,
    onToggle: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    WallpaperDrawerDialog(stringResource(R.string.favorites_edit), stringResource(R.string.favorites_done), onDismiss) {
        AppDrawer(
            apps = apps,
            favorites = favorites,
            recents = recents,
            onPick = onToggle,
            onToggleFavorite = onToggle,
            favoriteHint = stringResource(R.string.drawer_favorite_edit_hint),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>?,
    recents: List<AppEntry>,
    favorites: Set<String>,
    onToggleFavorite: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    onPick: (AppEntry) -> Unit,
) {
    WallpaperDrawerDialog(stringResource(R.string.pick_app_title), stringResource(R.string.cancel), onDismiss) {
        AppDrawer(
            apps = apps,
            favorites = favorites,
            recents = recents,
            onPick = onPick,
            onToggleFavorite = onToggleFavorite,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Full-screen dialog for the in-app drawers, on the blurred home-screen wallpaper like the
 * split-screen drawer: a title with a close button, then [content].
 */
@Composable
private fun WallpaperDrawerDialog(
    title: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WallpaperBackground(isSystemInDarkTheme()) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(closeLabel) }
                    }
                    content()
                }
            }
        }
    }
}
