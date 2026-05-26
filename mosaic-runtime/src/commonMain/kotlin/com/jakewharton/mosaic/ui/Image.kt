@file:JvmName("Image")
@file:OptIn(ExperimentalEncodingApi::class)

package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.env
import com.jakewharton.mosaic.modifier.Modifier
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.jvm.JvmName

/**
 * Rendering tiers for [Image], from highest fidelity to lowest. Picked automatically by
 * [detectImageRenderMode]; override via the `mode` parameter on [Image] if you want to force
 * one (e.g., to ASCII for snapshot tests, or to half-block when shipping to terminal multiplexers
 * that strip Kitty Graphics packets).
 */
public enum class ImageRenderMode {
	/** Real raster via Kitty Graphics Protocol. kitty / ghostty / WezTerm / Konsole 25.04+. */
	KittyGraphics,

	/**
	 * Truecolor SGR half-block (`▀`). Each terminal cell encodes two pixels stacked vertically.
	 * Requires a 24-bit-colour terminal but works in virtually every modern emulator and tmux.
	 */
	HalfBlock,

	/**
	 * Brightness-ramp ASCII art. No colour, no graphics protocol — works on TTY1, serial
	 * consoles, dumb terminals, plain-text snapshot harnesses. Each pixel becomes one cell.
	 */
	Ascii,
}

/**
 * Probe the environment to pick a sensible default rendering tier. Pure function of env vars
 * so it's cheap to call inside a composable's default-arg slot.
 *
 *  1. Kitty Graphics — selected when we recognise kitty, ghostty, or WezTerm via their
 *     well-known env vars / `$TERM` values.
 *  2. Half-block — selected when the terminal advertises truecolor (`COLORTERM=truecolor` /
 *     `24bit`) or a 256-colour `$TERM` (most emulators on those values still support
 *     truecolor SGR).
 *  3. ASCII — everything else.
 */
public fun detectImageRenderMode(): ImageRenderMode {
	// kitty advertises itself unambiguously via KITTY_WINDOW_ID; the TERM check catches
	// scenarios where the env var is stripped (sudo, su, ssh without --send-env).
	if (env("KITTY_WINDOW_ID") != null) return ImageRenderMode.KittyGraphics
	if (env("GHOSTTY_RESOURCES_DIR") != null) return ImageRenderMode.KittyGraphics
	if (env("TERM_PROGRAM") == "WezTerm") return ImageRenderMode.KittyGraphics
	val term = env("TERM").orEmpty()
	if (term.contains("kitty") || term.contains("ghostty")) {
		return ImageRenderMode.KittyGraphics
	}

	val colorterm = env("COLORTERM").orEmpty()
	if (colorterm == "truecolor" || colorterm == "24bit") return ImageRenderMode.HalfBlock
	// Most `*-256color` terminals (xterm-256color, screen-256color, tmux-256color, etc.) handle
	// truecolor SGR correctly even without COLORTERM set. Worst case the colour is slightly
	// off — better than dropping to ASCII.
	if (term.contains("256color") || term.contains("truecolor")) return ImageRenderMode.HalfBlock

	return ImageRenderMode.Ascii
}

/**
 * Renders [bitmap] as an image occupying exactly [cellWidth] × [cellHeight] terminal cells.
 * The output style is chosen by [mode] (default: auto-detected from the environment):
 *
 *  - [ImageRenderMode.KittyGraphics] — real raster, terminal resamples to the cell footprint.
 *  - [ImageRenderMode.HalfBlock] — bitmap is downsampled to `cellWidth × (cellHeight * 2)`
 *    pixels then emitted as `▀` glyphs coloured with truecolor SGR.
 *  - [ImageRenderMode.Ascii] — bitmap is downsampled to `cellWidth × cellHeight` and each pixel
 *    becomes one glyph from a 10-level brightness ramp.
 *
 * All three tiers produce the same cell footprint, so callers can switch tiers (or have the
 * detection switch for them) without their layout shifting.
 *
 * @param bitmap Source pixel grid (packed ARGB).
 * @param cellWidth Number of terminal cells across. Must be >= 1.
 * @param cellHeight Number of terminal cells down. Must be >= 1.
 * @param modifier Standard modifier chain.
 * @param mode Override the rendering tier. Default auto-detects via [detectImageRenderMode].
 */
@Composable
@MosaicComposable
public fun Image(
	bitmap: Bitmap,
	cellWidth: Int,
	cellHeight: Int,
	modifier: Modifier = Modifier,
	mode: ImageRenderMode = detectImageRenderMode(),
) {
	require(cellWidth >= 1) { "cellWidth must be >= 1, was $cellWidth" }
	require(cellHeight >= 1) { "cellHeight must be >= 1, was $cellHeight" }
	when (mode) {
		ImageRenderMode.KittyGraphics -> KittyGraphicsImage(bitmap, cellWidth, cellHeight, modifier)
		ImageRenderMode.HalfBlock -> HalfBlockImage(bitmap, cellWidth, cellHeight, modifier)
		ImageRenderMode.Ascii -> AsciiImage(bitmap, cellWidth, cellHeight, modifier)
	}
}

@Composable
@MosaicComposable
private fun KittyGraphicsImage(
	bitmap: Bitmap,
	cellWidth: Int,
	cellHeight: Int,
	modifier: Modifier,
) {
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

@Composable
@MosaicComposable
private fun HalfBlockImage(
	bitmap: Bitmap,
	cellWidth: Int,
	cellHeight: Int,
	modifier: Modifier,
) {
	// Each cell encodes 2 pixels stacked vertically -> resample to (cellWidth, cellHeight*2)
	// pixels so the encoder's natural output is exactly cellWidth × cellHeight cells.
	val lines = remember(bitmap, cellWidth, cellHeight) {
		val resampled = resampleNearest(bitmap, targetW = cellWidth, targetH = cellHeight * 2)
		encodeHalfBlockRows(resampled)
	}
	RawText(lines = lines, displayWidth = cellWidth, modifier = modifier)
}

@Composable
@MosaicComposable
private fun AsciiImage(
	bitmap: Bitmap,
	cellWidth: Int,
	cellHeight: Int,
	modifier: Modifier,
) {
	val lines = remember(bitmap, cellWidth, cellHeight) {
		val resampled = resampleNearest(bitmap, targetW = cellWidth, targetH = cellHeight)
		encodeAsciiRows(resampled)
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
 * Half-block fallback encoder: produce one row per pair of bitmap rows, each row a string of
 * `▀` glyphs coloured with SGR truecolor escapes (top pixel as foreground, bottom as
 * background). Pair with [RawText] at `displayWidth = bitmap.width`.
 *
 * Internal — usually you want [Image] which picks the right encoder for the terminal.
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

/**
 * Sorted dark→light. Ten glyphs (one extra at each end vs. the classic ` .:-=+*#%@` ramp gives
 * a cleaner gradient on smaller images). Chosen for ASCII compatibility — no Unicode required.
 */
private const val ASCII_RAMP = " .:-=+*#%@"

/**
 * ASCII fallback encoder: one glyph per bitmap pixel, picked from a brightness ramp. The
 * brightness is the Rec. 601 luma of the pixel's RGB (`0.299·R + 0.587·G + 0.114·B`), which is
 * the standard "perceived intensity" formula and produces noticeably better results than a
 * plain RGB mean on coloured images.
 *
 * Output: one row per bitmap row, exactly `bitmap.width` characters wide.
 */
internal fun encodeAsciiRows(bitmap: Bitmap): List<String> {
	val w = bitmap.width
	val h = bitmap.height
	if (w == 0 || h == 0) return emptyList()
	val out = ArrayList<String>(h)
	val rampLast = ASCII_RAMP.length - 1
	for (y in 0 until h) {
		val sb = StringBuilder(w)
		for (x in 0 until w) {
			val p = bitmap[x, y]
			// Rec. 601 luma in 0..255, then quantised onto the ramp.
			val luma = (p.argbR * 299 + p.argbG * 587 + p.argbB * 114) / 1000
			val idx = (luma * rampLast / 255).coerceIn(0, rampLast)
			sb.append(ASCII_RAMP[idx])
		}
		out += sb.toString()
	}
	return out
}

/**
 * Nearest-neighbour resample. Cheap, predictable, and produces no new colours — handy for
 * pixel-art-style content where bilinear would smear edges. Suitable for downscaling to cell
 * footprints in the half-block and ASCII tiers.
 */
internal fun resampleNearest(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
	require(targetW >= 1 && targetH >= 1)
	if (bitmap.width == targetW && bitmap.height == targetH) return bitmap
	val out = IntArray(targetW * targetH)
	val srcW = bitmap.width
	val srcH = bitmap.height
	if (srcW == 0 || srcH == 0) return Bitmap(targetW, targetH, out)
	for (y in 0 until targetH) {
		val srcY = (y.toLong() * srcH / targetH).toInt().coerceAtMost(srcH - 1)
		for (x in 0 until targetW) {
			val srcX = (x.toLong() * srcW / targetW).toInt().coerceAtMost(srcW - 1)
			out[y * targetW + x] = bitmap.pixels[srcY * srcW + srcX]
		}
	}
	return Bitmap(targetW, targetH, out)
}
