package jp.trisplit

import android.content.ComponentName
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Launcher-style app drawer: a search field, then favourites, recent apps and all apps in a grid
 * whose column count follows the available width (split half, pop-up, portrait or landscape).
 * Tapping an app picks it; long-pressing toggles it as a favourite. [favoriteHint] replaces the
 * hint under the search field (e.g. when tapping edits favourites instead of picking). [compact]
 * suits a short window: a slim search field, no hint, smaller cells with one-line names.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>?,
    favorites: Set<String>,
    recents: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
    favoriteHint: String? = null,
    compact: Boolean = false,
    presets: List<Preset> = emptyList(),
    onPickPreset: ((Preset) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier) {
        if (compact) {
            CompactSearchField(query) { query = it }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.drawer_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                favoriteHint ?: stringResource(R.string.drawer_favorite_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (apps == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        val trimmed = query.trim()
        val favoriteApps = apps.filter { it.component.flattenToString() in favorites }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (compact) 68.dp else 80.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (trimmed.isNotEmpty()) {
                val matches = apps.filter {
                    it.label.contains(trimmed, ignoreCase = true) ||
                        it.component.packageName.contains(trimmed, ignoreCase = true)
                }
                if (matches.isEmpty()) {
                    header("none") { Text(stringResource(R.string.drawer_no_results), Modifier.padding(16.dp)) }
                }
                appItems("search", matches, favorites, onPick, onToggleFavorite, compact)
            } else {
                if (presets.isNotEmpty() && onPickPreset != null) {
                    header("presets") { SectionTitle(stringResource(R.string.drawer_presets)) }
                    items(presets, key = { "preset-${it.id}" }) { preset ->
                        PresetCell(preset) { onPickPreset(preset) }
                    }
                }
                if (favoriteApps.isNotEmpty()) {
                    header("favorites") { SectionTitle(stringResource(R.string.drawer_favorites)) }
                    appItems("favorites", favoriteApps, favorites, onPick, onToggleFavorite, compact)
                }
                if (recents.isNotEmpty()) {
                    header("recents") { SectionTitle(stringResource(R.string.drawer_recent)) }
                    appItems("recents", recents, favorites, onPick, onToggleFavorite, compact)
                }
                header("all") { SectionTitle(stringResource(R.string.drawer_all)) }
                appItems("all", apps, favorites, onPick, onToggleFavorite, compact)
            }
        }
    }
}

private fun LazyGridScope.header(key: String, content: @Composable () -> Unit) {
    item(key = "header-$key", span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.appItems(
    section: String,
    apps: List<AppEntry>,
    favorites: Set<String>,
    onPick: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    compact: Boolean,
) {
    items(apps, key = { "$section-${it.component.flattenToString()}" }) { app ->
        AppCell(app, app.component.flattenToString() in favorites, compact, { onPick(app) }, { onToggleFavorite(app) })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(app: AppEntry, favorite: Boolean, compact: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.drawer_toggle_favorite),
            )
            .padding(vertical = if (compact) 4.dp else 8.dp, horizontal = 4.dp),
    ) {
        Box {
            AppIcon(app.component, size = if (compact) 40.dp else 48.dp)
            if (favorite) {
                Text("★", color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).clearAndSetSemantics {})
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A slim search field for short windows, where the standard one takes too much height. */
@Composable
private fun CompactSearchField(query: String, onChange: (String) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = color),
        cursorBrush = SolidColor(color),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.drawer_search), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

/**
 * A preset as one drawer cell: its apps' icons side by side on a tinted tile, without a label,
 * like a launcher folder. The name is kept for accessibility.
 */
@Composable
private fun PresetCell(preset: Preset, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = preset.name }
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            preset.components.forEach { flat ->
                ComponentName.unflattenFromString(flat)?.let { AppIcon(it, size = 22.dp) }
            }
        }
    }
}

/** A preset's apps as icons, left to right, followed by its name. */
@Composable
fun PresetRow(
    preset: Preset,
    apps: List<AppEntry>?,
    modifier: Modifier = Modifier,
    showSubtitle: Boolean = true,
    warning: String? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        preset.components.forEach { flat ->
            ComponentName.unflattenFromString(flat)?.let { AppIcon(it, size = 32.dp) }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showSubtitle) {
                Text(
                    preset.components.joinToString(" · ") { flat ->
                        (apps?.firstOrNull { it.component.flattenToString() == flat }
                            ?: apps?.firstOrNull { it.component.packageName == flat.substringBefore('/') })
                            ?.label ?: flat.substringBefore('/')
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * Icon loaded off the main thread on first display, then served from [AppCatalog]'s cache.
 * [description] is read out by TalkBack; leave it null when a label next to the icon says it already.
 */
@Composable
fun AppIcon(component: ComponentName, size: Dp = 36.dp, description: String? = null) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, component) { value = AppCatalog.icon(context, component) }
    Box(Modifier.size(size).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
