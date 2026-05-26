@file:JvmName("Image")
@file:OptIn(ExperimentalEncodingApi::class)

package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.modifier.Modifier
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.jvm.JvmName

/**
 * Renders [bitmap] as a real raster image via the Kitty Graphics Protocol. The composable
 * occupies [cellWidth] × [cellHeight] terminal cells; the terminal scales the pixel data to
 * that footprint.
 *
 * ## Where this displays as a real image
 *  - **kitty** — full support, the original implementation of the protocol
 *  - **ghostty** — implements the core transmission + placement subset, sufficient for this
 *    composable
 *  - **WezTerm**, **Konsole 25.04+** — also support the protocol
 *
 * ## Where this displays as garbage
 * Terminals that don't speak the Kitty Graphics Protocol will print the raw escape bytes,
 * which look like meaningless characters. If your target audience includes such terminals,
 * fall back to [encodeHalfBlockRows] (which renders an approximation with `▀` glyphs and
 * truecolor SGR) and emit it via [RawText] yourself. A future iteration can add capability
 * detection so this composable picks the right path automatically.
 *
 * ## Cursor behaviour
 * The escape sets `C=1` (do-not-move-cursor) so Mosaic's layout model keeps working — the
 * image is positioned at the cursor's location and stays there while the layer emits the
 * remaining (empty) rows of this composable's footprint. The image survives row-end newlines
 * because kitty/ghostty paint it as a graphical overlay independent of the text grid.
 *
 * @param bitmap Source pixel grid. RGBA layout (the alpha channel is transmitted; the
 *   terminal handles compositing).
 * @param cellWidth Number of terminal cells across the image will occupy. Must be >= 1.
 * @param cellHeight Number of terminal cells down the image will occupy. Must be >= 1.
 * @param modifier Standard modifier chain.
 */
@Composable
@MosaicComposable
public fun Image(
	bitmap: Bitmap,
	cellWidth: Int,
	cellHeight: Int,
	modifier: Modifier = Modifier,
) {
	require(cellWidth >= 1) { "cellWidth must be >= 1, was $cellWidth" }
	require(cellHeight >= 1) { "cellHeight must be >= 1, was $cellHeight" }
	val escape = remember(bitmap, cellWidth, cellHeight) {
		encodeKittyGraphics(bitmap, cellWidth, cellHeight)
	}
	val lines = remember(cellHeight, escape) {
		// The escape carries the whole image on the first row. The remaining rows reserve
		// the cell footprint so Mosaic's layout knows to leave space for the image — they
		// emit no visible content (empty payloads bracket-resets only) and the image is
		// painted as a graphical overlay independent of those cells.
		buildList(cellHeight) {
			add(escape)
			repeat(cellHeight - 1) { add("") }
		}
	}
	RawText(lines = lines, displayWidth = cellWidth, modifier = modifier)
}

private const val ESC = "\u001B"
private const val ST = "${ESC}\\"

// 4096 bytes is the protocol's per-chunk payload limit; base64 chars below that fit safely.
private const val CHUNK_CHARS = 4096

/**
 * Encode [bitmap] as a Kitty Graphics Protocol escape sequence ready to be emitted into a
 * terminal that supports it.
 *
 *  - `a=T` — transmit AND display in one step
 *  - `f=32` — 32-bit RGBA pixel format (matches our [Bitmap.pixels] layout once we shuffle
 *    each ARGB int into R, G, B, A byte order)
 *  - `s=<pixelW>`, `v=<pixelH>` — pixel dimensions of the transmitted raster
 *  - `c=<cellW>`, `r=<cellH>` — terminal-cell footprint the image should occupy; the terminal
 *    resamples the raster into that rectangle
 *  - `C=1` — do not move the cursor; keeps Mosaic's row-by-row rendering loop's cursor model
 *    consistent
 *  - `q=2` — suppress success/failure responses (we don't read stdin)
 *  - `m=1` on every chunk except the last; `m=0` on the last — protocol's chunked-transmission
 *    framing
 *
 * The chunked envelope keeps each `_G...\` block at or below the protocol's 4 KiB payload
 * cap. Without chunking, terminals reject oversized blocks.
 */
internal fun encodeKittyGraphics(bitmap: Bitmap, cellWidth: Int, cellHeight: Int): String {
	require(cellWidth >= 1 && cellHeight >= 1) { "cell dimensions must be positive" }
	val w = bitmap.width
	val h = bitmap.height
	if (w == 0 || h == 0) return ""

	// Unpack ARGB ints into RGBA bytes. Kitty's `f=32` wants R, G, B, A in that order.
	val rgba = ByteArray(bitmap.pixels.size * 4)
	for (i in bitmap.pixels.indices) {
		val p = bitmap.pixels[i]
		val off = i * 4
		rgba[off] = ((p ushr 16) and 0xFF).toByte()      // R
		rgba[off + 1] = ((p ushr 8) and 0xFF).toByte()   // G
		rgba[off + 2] = (p and 0xFF).toByte()             // B
		rgba[off + 3] = ((p ushr 24) and 0xFF).toByte()  // A
	}
	val base64 = Base64.encode(rgba)

	val sb = StringBuilder(base64.length + 128)
	var offset = 0
	var first = true
	while (offset < base64.length) {
		val end = minOf(offset + CHUNK_CHARS, base64.length)
		val isLast = end == base64.length
		sb.append(ESC).append("_G")
		if (first) {
			sb.append("a=T,f=32,s=").append(w).append(",v=").append(h)
				.append(",c=").append(cellWidth).append(",r=").append(cellHeight)
				.append(",C=1,q=2")
			if (!isLast) sb.append(",m=1") else sb.append(",m=0")
			first = false
		} else {
			sb.append(if (isLast) "m=0" else "m=1")
		}
		sb.append(';')
		sb.append(base64, offset, end)
		sb.append(ST)
		offset = end
	}
	return sb.toString()
}

/**
 * Fallback encoder: produce one row per pair of bitmap rows, each row a string of `▀` glyphs
 * coloured with SGR truecolor escapes (top pixel as foreground, bottom as background). This
 * works in any truecolor-capable terminal that doesn't support the Kitty Graphics Protocol,
 * but produces an ASCII approximation rather than a real image. Pair with [RawText] at
 * `displayWidth = bitmap.width`.
 */
internal fun encodeHalfBlockRows(bitmap: Bitmap): List<String> {
	val w = bitmap.width
	val h = bitmap.height
	if (w == 0 || h == 0) return emptyList()
	val cellRows = (h + 1) / 2
	val out = ArrayList<String>(cellRows)
	val sgrReset = "${ESC}[0m"
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
		sb.append(sgrReset)
		out += sb.toString()
	}
	return out
}
