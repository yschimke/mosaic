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
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.RawText
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private const val ESC = "\u001B"
private const val SGR_RESET = "${ESC}[0m"

/**
 * Build one row of a truecolor image using upper-half-block glyphs (`▀`). Each cell encodes two
 * pixels stacked vertically — top pixel in foreground, bottom in background. The returned string
 * contains many SGR escape sequences whose byte length far exceeds the visual cell count, so the
 * sample is wrong if Mosaic ever measures it as plain text.
 */
private fun gradientRow(rowIndex: Int, totalRows: Int, width: Int): String {
	val sb = StringBuilder()
	for (col in 0 until width) {
		val topR = ((rowIndex * 2) * 255 / (totalRows * 2 - 1)).coerceIn(0, 255)
		val topG = (col * 255 / (width - 1)).coerceIn(0, 255)
		val topB = 255 - topG
		val botR = ((rowIndex * 2 + 1) * 255 / (totalRows * 2 - 1)).coerceIn(0, 255)
		val botG = topG
		val botB = topB
		sb.append(ESC).append("[38;2;").append(topR).append(';').append(topG).append(';').append(topB).append('m')
		sb.append(ESC).append("[48;2;").append(botR).append(';').append(botG).append(';').append(botB).append('m')
		sb.append('▀')
	}
	sb.append(SGR_RESET)
	return sb.toString()
}

private const val IMG_WIDTH = 24
private const val IMG_HEIGHT = 8

@Composable
fun RawImageDemo() {
	var frame by remember { mutableIntStateOf(0) }

	val rows = remember(frame) {
		List(IMG_HEIGHT) { rowIndex ->
			// Animate by shifting the row index. Mosaic recomposes; RawText re-lays-out at the
			// same displayWidth so adjacent UI never reflows.
			gradientRow(rowIndex = (rowIndex + frame) % IMG_HEIGHT, totalRows = IMG_HEIGHT, width = IMG_WIDTH)
		}
	}

	Column {
		Text("RawText demo — frame $frame")
		Row {
			RawText(lines = rows, displayWidth = IMG_WIDTH)
			Text(" <- ${IMG_WIDTH}x${IMG_HEIGHT} truecolor block")
		}
		Text("Press Ctrl-C to exit.")
	}

	LaunchedEffect(Unit) {
		while (true) {
			delay(150.milliseconds)
			frame += 1
		}
	}
}

fun main() {
	runMosaicBlocking(onNonInteractive = Ignore) {
		RawImageDemo()
	}
}
