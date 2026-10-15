package jp.trisplit

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** The home-screen wallpaper image, read once through Shizuku and kept for the process. */
object WallpaperSource {
    /** Downsampled: the image is blurred anyway, and a full 2520 px bitmap costs memory for nothing. */
    private const val MAX_DECODE_PX = 1080

    @Volatile
    private var cached: ImageBitmap? = null

    suspend fun load(): ImageBitmap? {
        cached?.let { return it }
        return runCatching {
            val service = ShizukuBridge.service()
            withContext(Dispatchers.IO) {
                val pfd = service.getWallpaper(Process.myUid() / PER_USER_RANGE) ?: return@withContext null
                // The descriptor can be a pipe, which cannot be rewound; read it once into memory.
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE_PX) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.onFailure { Log.w(TripleLauncher.TAG, "wallpaper not available", it) }
            .getOrNull()
            ?.let(Bitmap::asImageBitmap)
            ?.also { cached = it }
    }

    private const val PER_USER_RANGE = 100_000
}

/**
 * Background that shows the home-screen wallpaper the way the launcher does: scaled to cover the
 * whole display and positioned so this window shows the part of it that lies under the window
 * (in split screen, the matching half). Blurred, with a scrim so labels stay readable. Without the
 * image (no Shizuku, or a live wallpaper without a still), a gradient of the wallpaper's colours.
 */
@Composable
fun WallpaperBackground(dark: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null) { value = WallpaperSource.load() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Keyed on the configuration: TriSplit's screens are not recreated on rotation or folding.
    val display = remember(context, LocalConfiguration.current) {
        context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        val wallpaper = image
        if (wallpaper != null) {
            Canvas(Modifier.fillMaxSize().blur(BLUR_RADIUS)) {
                // Cover the display (like the launcher), then shift by where this window sits on it.
                val scale = max(display.width().toFloat() / wallpaper.width, display.height().toFloat() / wallpaper.height)
                val width = (wallpaper.width * scale).roundToInt()
                val height = (wallpaper.height * scale).roundToInt()
                val left = (display.width() - width) / 2 - origin.x.roundToInt()
                val top = (display.height() - height) / 2 - origin.y.roundToInt()
                drawImage(wallpaper, dstOffset = IntOffset(left, top), dstSize = IntSize(width, height))
            }
        } else {
            // A binder call; once per theme, not on every recomposition.
            val brush = remember(context, dark) { wallpaperColorsBrush(context, dark) }
            Box(Modifier.fillMaxSize().background(brush))
        }
        Box(Modifier.fillMaxSize().background(if (dark) Color.Black.copy(alpha = SCRIM_ALPHA) else Color.White.copy(alpha = SCRIM_ALPHA)))
        content()
    }
}

private fun wallpaperColorsBrush(context: Context, dark: Boolean): Brush {
    val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    val stops = listOfNotNull(colors?.primaryColor, colors?.secondaryColor, colors?.tertiaryColor)
        .map { Color(it.toArgb()) }
    return when {
        stops.size >= 2 -> Brush.verticalGradient(stops)
        stops.size == 1 -> Brush.verticalGradient(listOf(stops[0], stops[0]))
        else -> Brush.verticalGradient(listOf(if (dark) Color.Black else Color.White, if (dark) Color.Black else Color.White))
    }
}

private val BLUR_RADIUS = 24.dp
private const val SCRIM_ALPHA = 0.45f
