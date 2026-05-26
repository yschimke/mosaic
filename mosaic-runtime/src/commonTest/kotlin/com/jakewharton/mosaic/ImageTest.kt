package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Bitmap
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.encodeHalfBlockRows
import com.jakewharton.mosaic.ui.encodeKittyGraphics
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class ImageTest {

	private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

	// region Kitty Graphics protocol — real image emission

	@Test fun kittyEscapeOpensAndClosesProtocolEnvelope() {
		val bitmap = Bitmap(width = 2, height = 2, pixels = IntArray(4) { rgb(255, 0, 0) })
		val escape = encodeKittyGraphics(bitmap, cellWidth = 4, cellHeight = 2)
		// `_G...\` is the protocol's APC envelope.
		assertThat(escape).startsWith("${ESC}_G")
		check(escape.endsWith("$ESC\\")) {
			"escape does not terminate with ST"
		}
	}

	@Test fun kittyEscapeIncludesPixelAndCellDimensions() {
		val bitmap = Bitmap(width = 8, height = 6, pixels = IntArray(48))
		val escape = encodeKittyGraphics(bitmap, cellWidth = 4, cellHeight = 3)
		// `s=` / `v=` are pixel size; `c=` / `r=` are cell footprint.
		assertThat(escape).contains("s=8")
		assertThat(escape).contains("v=6")
		assertThat(escape).contains("c=4")
		assertThat(escape).contains("r=3")
		// RGBA, transmit-and-display, do-not-move-cursor, quiet.
		assertThat(escape).contains("f=32")
		assertThat(escape).contains("a=T")
		assertThat(escape).contains("C=1")
		assertThat(escape).contains("q=2")
	}

	@Test fun largeBitmapIsChunked() {
		// A 64×64 RGBA bitmap is 64*64*4 = 16384 bytes of pixel data, base64 = ~21,848 chars.
		// That comfortably exceeds the 4096-char per-chunk cap, so the encoder must emit
		// multiple `_G...\` blocks with m=1 / m=0 framing.
		val w = 64
		val h = 64
		val bitmap = Bitmap(width = w, height = h, pixels = IntArray(w * h) { it })
		val escape = encodeKittyGraphics(bitmap, cellWidth = 16, cellHeight = 8)
		val chunkCount = escape.split("_G").size - 1
		check(chunkCount >= 2) { "expected >=2 chunks for 64x64 image, got $chunkCount" }
		// Intermediate chunks carry m=1, the final one m=0.
		assertThat(escape).contains("m=1")
		assertThat(escape).contains("m=0")
	}

	@Test fun zeroSizedBitmapEncodesAsEmptyEscape() {
		val bitmap = Bitmap(width = 0, height = 0, pixels = IntArray(0))
		val escape = encodeKittyGraphics(bitmap, cellWidth = 1, cellHeight = 1)
		assertThat(escape).isEqualTo("")
	}

	@Test fun imageComposableLaysOutAtCellDimensions() = runTest {
		// The image escape goes on row 0; rows 1..cellHeight-1 are empty payload reservations.
		// Either way `Text("HI")` immediately below should appear on lines[cellHeight].
		val bitmap = Bitmap(width = 4, height = 4, pixels = IntArray(16) { rgb(0, 255, 0) })
		val cellH = 3
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Column {
					Image(bitmap = bitmap, cellWidth = 5, cellHeight = cellH)
					Text("HI")
				}
			}
			val lines = snapshot.lines()
			assertThat(lines).hasSize(cellH + 1)
			assertThat(lines[cellH]).isEqualTo("HI")
		}
	}

	// endregion

	// region Half-block fallback — kept for non-Kitty terminals

	@Test fun halfBlockEncoderProducesHalfRowsRoundedUp() {
		// 3 columns × 5 rows -> ceil(5/2) = 3 terminal rows.
		val bitmap = Bitmap(
			width = 3,
			height = 5,
			pixels = IntArray(15) { rgb(it * 10, 0, 0) },
		)
		val rows = encodeHalfBlockRows(bitmap)
		assertThat(rows).hasSize(3)
		rows.forEach { row ->
			assertThat(row.count { it == '▀' }).isEqualTo(3)
		}
	}

	@Test fun halfBlockEncoderUsesTopAsForegroundAndBottomAsBackground() {
		val red = rgb(255, 0, 0)
		val blue = rgb(0, 0, 255)
		val bitmap = Bitmap(width = 1, height = 2, pixels = intArrayOf(red, blue))
		val row = encodeHalfBlockRows(bitmap).single()
		assertThat(row).contains("[38;2;255;0;0m")
		assertThat(row).contains("[48;2;0;0;255m")
		assertThat(row).contains("▀")
	}

	@Test fun halfBlockOddHeightPadsBottomAsBlack() {
		val red = rgb(255, 0, 0)
		val bitmap = Bitmap(width = 1, height = 1, pixels = intArrayOf(red))
		val row = encodeHalfBlockRows(bitmap).single()
		assertThat(row).contains("[38;2;255;0;0m")
		assertThat(row).contains("[48;2;0;0;0m")
	}

	// endregion
}
