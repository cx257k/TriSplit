package jp.trisplit

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the setup steps check; each is re-read when TriSplit comes back to the front. */
data class SetupState(
    val shizuku: ShizukuBridge.Status,
    val overlayAllowed: Boolean,
    val notificationsAllowed: Boolean,
)

private enum class Step { WELCOME, SHIZUKU, PERMISSION, FREEFORM, OVERLAY, NOTIFICATIONS, HANDLES, MORE }

/**
 * First-run guide, also reachable from Settings: what TriSplit does, then each thing it needs
 * (Shizuku running and allowed, pop-up windows, drawing over apps, notifications) with a button
 * that does it and a tick once done, then how to use it. Any step can be skipped.
 */
@Composable
fun OnboardingScreen(state: SetupState, onRequestNotifications: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = Step.entries
    var index by remember { mutableIntStateOf(0) }
    // Whether pop-up windows are on; null until read (it needs Shizuku).
    var freeformEnabled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.shizuku) {
        if (state.shizuku == ShizukuBridge.Status.READY) freeformEnabled = FreeformSetting.isEnabled()
    }
    val shizukuInstalled = remember(state) { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDots(steps.size, index)
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                targetState = steps[index],
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "step",
            ) { step ->
                Column(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = 640.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        Step.WELCOME -> {
                            ColumnsPicture()
                            Title(R.string.onboarding_welcome_title)
                            Body(R.string.onboarding_welcome_body)
                            Body(R.string.onboarding_welcome_needs)
                        }
                        Step.SHIZUKU -> {
                            Title(R.string.onboarding_shizuku_title)
                            Done(state.shizuku != ShizukuBridge.Status.NOT_RUNNING)
                            Body(R.string.onboarding_shizuku_body)
                            if (!shizukuInstalled) {
                                Body(R.string.onboarding_shizuku_install)
                                Button(onClick = { openShizukuDownload(context) }) { Text(stringResource(R.string.onboarding_shizuku_get)) }
                            }
                            Numbered(
                                R.string.onboarding_shizuku_step1,
                                R.string.onboarding_shizuku_step2,
                                R.string.onboarding_shizuku_step3,
                                R.string.onboarding_shizuku_step4,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { openDeveloperOptions(context) }) {
                                    Text(stringResource(R.string.onboarding_open_developer_options))
                                }
                                if (shizukuInstalled) {
                                    Button(onClick = {
                                        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(context::startActivity)
                                    }) { Text(stringResource(R.string.open_shizuku)) }
                                }
                            }
                            Hint(R.string.onboarding_shizuku_reboot)
                        }
                        Step.PERMISSION -> {
                            Title(R.string.onboarding_permission_title)
                            Done(state.shizuku == ShizukuBridge.Status.READY)
                            Body(R.string.onboarding_permission_body)
                            Button(
                                onClick = ShizukuBridge::requestPermission,
                                enabled = state.shizuku == ShizukuBridge.Status.NO_PERMISSION,
                            ) { Text(stringResource(R.string.shizuku_request_permission)) }
                            if (state.shizuku == ShizukuBridge.Status.NOT_RUNNING) Hint(R.string.onboarding_needs_shizuku)
                        }
                        Step.FREEFORM -> {
                            Title(R.string.onboarding_freeform_title)
                            Done(freeformEnabled == true)
                            Body(R.string.onboarding_freeform_body)
                            Button(
                                onClick = { scope.launch { if (FreeformSetting.enable()) freeformEnabled = true } },
                                enabled = state.shizuku == ShizukuBridge.Status.READY && freeformEnabled != true,
                            ) { Text(stringResource(R.string.enable_freeform)) }
                            if (state.shizuku != ShizukuBridge.Status.READY) Hint(R.string.onboarding_needs_shizuku)
                            Hint(R.string.enable_freeform_help)
                        }
                        Step.OVERLAY -> {
                            Title(R.string.onboarding_overlay_title)
                            Done(state.overlayAllowed)
                            Body(R.string.onboarding_overlay_body)
                            Button(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                                )
                            }, enabled = !state.overlayAllowed) { Text(stringResource(R.string.overlay_permission_button)) }
                        }
                        Step.NOTIFICATIONS -> {
                            Title(R.string.onboarding_notifications_title)
                            Done(state.notificationsAllowed)
                            Body(R.string.onboarding_notifications_body)
                            Button(onClick = onRequestNotifications, enabled = !state.notificationsAllowed) {
                                Text(stringResource(R.string.onboarding_notifications_button))
                            }
                        }
                        Step.HANDLES -> {
                            Title(R.string.onboarding_handles_title)
                            Body(R.string.onboarding_handles_body)
                            Bullets(
                                R.string.onboarding_handles_drag,
                                R.string.onboarding_handles_tap,
                                R.string.onboarding_handles_double_tap,
                                R.string.onboarding_handles_long_press,
                            )
                        }
                        Step.MORE -> {
                            Title(R.string.onboarding_more_title)
                            Bullets(
                                R.string.onboarding_more_split,
                                R.string.onboarding_more_home,
                                R.string.onboarding_more_close,
                                R.string.onboarding_more_presets,
                                R.string.onboarding_more_front,
                            )
                            Hint(R.string.onboarding_more_again)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    TextButton(onClick = { index-- }) { Text(stringResource(R.string.onboarding_back)) }
                } else {
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
                }
                Spacer(Modifier.weight(1f))
                if (index < steps.lastIndex) {
                    Button(onClick = { index++ }) { Text(stringResource(R.string.onboarding_next)) }
                } else {
                    Button(onClick = onFinish) { Text(stringResource(R.string.onboarding_finish)) }
                }
            }
        }
    }
}

/** Reads and switches on the developer options that pop-up windows need, through Shizuku. */
object FreeformSetting {
    suspend fun isEnabled(): Boolean? = runCatching {
        val service = ShizukuBridge.service()
        withContext(Dispatchers.IO) { service.exec(READ_FREEFORM_COMMAND) }
    }.getOrNull()?.trim()?.let { it == "exit=0: 1" }

    /** True if the settings were written. */
    suspend fun enable(): Boolean = runCatching {
        val service = ShizukuBridge.service()
        withContext(Dispatchers.IO) { service.exec(ENABLE_FREEFORM_COMMAND) }
    }.map { it.startsWith("exit=0") }.getOrDefault(false)
}

private fun openShizukuDownload(context: Context) {
    val store = Intent(Intent.ACTION_VIEW, "market://details?id=$SHIZUKU_PACKAGE".toUri())
    runCatching { context.startActivity(store) }
        .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, SHIZUKU_DOWNLOAD_URL.toUri())) }
}

private fun openDeveloperOptions(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
}

private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

/** Three columns, the layout TriSplit makes. */
@Composable
private fun ColumnsPicture() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(1f, 0.8f, 0.6f).forEach { alpha ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun Title(@StringRes text: Int) =
    Text(stringResource(text), style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(@StringRes text: Int) =
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Hint(@StringRes text: Int) =
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A tick once a step's requirement is met. */
@Composable
private fun Done(done: Boolean) {
    if (!done) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(stringResource(R.string.onboarding_done), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun Numbered(@StringRes vararg items: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, item ->
            Row {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 24.dp))
                Text(stringResource(item), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Bullets(@StringRes vararg items: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 20.dp))
                Text(stringResource(item), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
