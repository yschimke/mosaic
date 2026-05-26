@file:JvmName("Main")

package example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.NonInteractivePolicy.Ignore
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.Bitmap
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private const val IMG_PIXEL_WIDTH = 96
private const val IMG_PIXEL_HEIGHT = 96
private const val IMG_CELL_WIDTH = 24
private const val IMG_CELL_HEIGHT = 12

/**
 * Build a rotating animated gradient as a packed-ARGB bitmap. Frame N shifts the hue cycle so
 * consecutive frames are visibly different; that lets a screenshot harness verify the renderer
 * is actually pushing new pixels rather than caching.
 */
private fun gradientBitmap(frame: Int): Bitmap {
	val w = IMG_PIXEL_WIDTH
	val h = IMG_PIXEL_HEIGHT
	val pixels = IntArray(w * h)
	for (y in 0 until h) {
		for (x in 0 until w) {
			val r = ((x + frame * 4) * 255 / w) and 0xFF
			val g = ((y + frame * 2) * 255 / h) and 0xFF
			val b = ((x + y + frame * 6) * 255 / (w + h)) and 0xFF
			pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
		}
	}
	return Bitmap(width = w, height = h, pixels = pixels)
}

@Composable
fun RawImageDemo() {
	var frame by remember { mutableIntStateOf(0) }

	// A fresh bitmap per frame — the `remember(bitmap, ...)` keys inside Image will re-encode.
	val bitmap = remember(frame) { gradientBitmap(frame) }

	Column {
		Text("Kitty Graphics Image demo — frame $frame")
		Row {
			Image(
				bitmap = bitmap,
				cellWidth = IMG_CELL_WIDTH,
				cellHeight = IMG_CELL_HEIGHT,
			)
			Text(" <- ${IMG_PIXEL_WIDTH}x${IMG_PIXEL_HEIGHT}px real image")
		}
		Text("Press Ctrl-C to exit.")
	}

	LaunchedEffect(Unit) {
		while (true) {
			delay(200.milliseconds)
			frame += 1
		}
	}
}

fun main() {
	runMosaicBlocking(onNonInteractive = Ignore) {
		RawImageDemo()
	}
}
