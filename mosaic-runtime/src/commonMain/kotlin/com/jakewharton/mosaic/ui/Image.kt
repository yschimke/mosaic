@file:JvmName("Image")

package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.modifier.Modifier
import kotlin.jvm.JvmName

/**
 * Renders a [bitmap] as a half-block image: one terminal cell encodes two image pixels stacked
 * vertically, with the upper-half-block glyph (`▀`) carrying the top pixel as foreground and the
 * bottom pixel as background. The composable lays out as `bitmap.width` columns by
 * `ceil(bitmap.height / 2)` rows.
 *
 * The output relies on truecolor SGR escapes that don't count toward visible cell width — Mosaic
 * cannot measure them via the regular [Text] path, so this composable delegates to [RawText] /
 * `drawRaw` and lays out at the bitmap's exact dimensions regardless of the byte length of the
 * SGR stream.
 *
 * Limitations:
 *  - Always emits truecolor SGR. Terminals without truecolor will render the SGR as garbage. A
 *    future iteration can downgrade to ANSI256 or grayscale based on terminal capabilities.
 *  - No scaling. If you need to fit a bitmap into a constrained area, resample upstream and
 *    pass the resampled bitmap in.
 *  - Alpha is ignored — fully transparent pixels still emit their RGB.
 *
 * @param bitmap The source pixel grid.
 * @param modifier Standard modifier chain.
 */
@Composable
@MosaicComposable
public fun Image(
	bitmap: Bitmap,
	modifier: Modifier = Modifier,
) {
	// `remember(bitmap)` keys the encoded payload on the bitmap instance. For animated bitmaps
	// callers pass a fresh instance per frame and we re-encode; for static art the encode runs
	// once.
	val lines = remember(bitmap) { encodeHalfBlockRows(bitmap) }
	val width = bitmap.width
	if (width == 0 || lines.isEmpty()) {
		// Nothing to draw; emit a zero-sized RawText so the layout slot is still occupied
		// (matching how Text("") behaves).
		RawText(content = "", displayWidth = 1, modifier = modifier)
		return
	}
	RawText(lines = lines, displayWidth = width, modifier = modifier)
}

private const val ESC = "\u001B"
private const val SGR_RESET = "${ESC}[0m"

/**
 * Encode each pair of bitmap rows as one terminal row containing `bitmap.width` `▀` glyphs,
 * each with a truecolor foreground (top pixel) and background (bottom pixel). Bitmap heights
 * that aren't a multiple of two get a final row where the bottom pixel is treated as black.
 *
 * The encoder issues fresh `38;2;` / `48;2;` SGR sequences per cell rather than diffing
 * neighbours. A run-length-aware version would shave bytes but the renderer's [RawText] path
 * already wraps the whole row in resets, and terminals coalesce repeated SGR cheaply.
 */
internal fun encodeHalfBlockRows(bitmap: Bitmap): List<String> {
	val w = bitmap.width
	val h = bitmap.height
	if (w == 0 || h == 0) return emptyList()
	val cellRows = (h + 1) / 2
	val out = ArrayList<String>(cellRows)
	for (cellRow in 0 until cellRows) {
		val sb = StringBuilder(w * 24)
		val topY = cellRow * 2
		val botY = topY + 1
		for (x in 0 until w) {
			val top = bitmap[x, topY]
			val bot = if (botY < h) bitmap[x, botY] else 0
			sb.append(ESC).append("[38;2;")
				.append(top.argbR).append(';').append(top.argbG).append(';').append(top.argbB).append('m')
			sb.append(ESC).append("[48;2;")
				.append(bot.argbR).append(';').append(bot.argbG).append(';').append(bot.argbB).append('m')
			sb.append('▀')
		}
		sb.append(SGR_RESET)
		out += sb.toString()
	}
	return out
}
