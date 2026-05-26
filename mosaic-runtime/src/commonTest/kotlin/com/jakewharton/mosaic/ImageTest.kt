package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasLength
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Bitmap
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.ImageRenderMode
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.encodeAsciiRows
import com.jakewharton.mosaic.ui.encodeHalfBlockRows
import com.jakewharton.mosaic.ui.encodeKittyGraphics
import com.jakewharton.mosaic.ui.resampleNearest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class ImageTest {

	private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

	// region Kitty Graphics protocol — tier 1, real raster

	@Test fun kittyEscapeOpensAndClosesProtocolEnvelope() {
		val bitmap = Bitmap(width = 2, height = 2, pixels = IntArray(4) { rgb(255, 0, 0) })
		val escape = encodeKittyGraphics(bitmap, cellWidth = 4, cellHeight = 2)
		// `_G...\` is the protocol's APC envelope (ESC _G ... ESC \).
		assertThat(escape).startsWith("${ESC}_G")
		check(escape.endsWith("$ESC\\")) { "escape does not terminate with ST" }
	}

	@Test fun kittyEscapeIncludesPixelAndCellDimensions() {
		val bitmap = Bitmap(width = 8, height = 6, pixels = IntArray(48))
		val escape = encodeKittyGraphics(bitmap, cellWidth = 4, cellHeight = 3)
		assertThat(escape).contains("s=8")
		assertThat(escape).contains("v=6")
		assertThat(escape).contains("c=4")
		assertThat(escape).contains("r=3")
		assertThat(escape).contains("f=32")
		assertThat(escape).contains("a=T")
		assertThat(escape).contains("C=1")
		assertThat(escape).contains("q=2")
	}

	@Test fun largeBitmapIsChunked() {
		// 64×64 RGBA = 16384 bytes -> base64 ~21,848 chars. Comfortably above the 4096-char
		// per-chunk cap so the encoder must emit multiple `_G...\` blocks with m=1 / m=0 framing.
		val w = 64
		val h = 64
		val bitmap = Bitmap(width = w, height = h, pixels = IntArray(w * h) { it })
		val escape = encodeKittyGraphics(bitmap, cellWidth = 16, cellHeight = 8)
		val chunkCount = escape.split("_G").size - 1
		check(chunkCount >= 2) { "expected >=2 chunks for 64x64 image, got $chunkCount" }
		assertThat(escape).contains("m=1")
		assertThat(escape).contains("m=0")
	}

	@Test fun zeroSizedBitmapEncodesAsEmptyEscape() {
		val bitmap = Bitmap(width = 0, height = 0, pixels = IntArray(0))
		val escape = encodeKittyGraphics(bitmap, cellWidth = 1, cellHeight = 1)
		assertThat(escape).isEqualTo("")
	}

	@Test fun kittyImageLaysOutAtCellDimensions() = runTest {
		val bitmap = Bitmap(width = 4, height = 4, pixels = IntArray(16) { rgb(0, 255, 0) })
		val cellH = 3
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Column {
					Image(
						bitmap = bitmap,
						cellWidth = 5,
						cellHeight = cellH,
						mode = ImageRenderMode.KittyGraphics,
					)
					Text("HI")
				}
			}
			val lines = snapshot.lines()
			assertThat(lines).hasSize(cellH + 1)
			assertThat(lines[cellH]).isEqualTo("HI")
		}
	}

	// endregion

	// region Half-block — tier 2, truecolor SGR approximation

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

	@Test fun halfBlockImageLaysOutAtRequestedCellHeight() = runTest {
		// A 10×10 source rendered into 4 cells wide × 3 cells tall: the encoder resamples
		// internally to 4 × 6 pixels, then emits 3 half-block rows.
		val bitmap = Bitmap(
			width = 10,
			height = 10,
			pixels = IntArray(100) { rgb(255, 0, 0) },
		)
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Column {
					Image(
						bitmap = bitmap,
						cellWidth = 4,
						cellHeight = 3,
						mode = ImageRenderMode.HalfBlock,
					)
					Text("done")
				}
			}
			val lines = snapshot.lines()
			assertThat(lines).hasSize(4)
			// Image rows contain ▀ glyphs.
			repeat(3) { row ->
				assertThat(lines[row].count { it == '▀' }).isEqualTo(4)
			}
			assertThat(lines[3]).isEqualTo("done")
		}
	}

	// endregion

	// region ASCII — tier 3, no colour

	@Test fun asciiEncoderProducesOneGlyphPerPixel() {
		val bitmap = Bitmap(
			width = 4,
			height = 3,
			pixels = IntArray(12) { rgb(255, 255, 255) },
		)
		val rows = encodeAsciiRows(bitmap)
		assertThat(rows).hasSize(3)
		rows.forEach { row ->
			assertThat(row).hasLength(4)
		}
	}

	@Test fun asciiEncoderPicksDarkGlyphForDarkPixel() {
		// Black -> first glyph in the ramp (space).
		val bitmap = Bitmap(width = 1, height = 1, pixels = intArrayOf(rgb(0, 0, 0)))
		val row = encodeAsciiRows(bitmap).single()
		assertThat(row).isEqualTo(" ")
	}

	@Test fun asciiEncoderPicksBrightGlyphForBrightPixel() {
		// White -> last glyph in the ramp (@).
		val bitmap = Bitmap(width = 1, height = 1, pixels = intArrayOf(rgb(255, 255, 255)))
		val row = encodeAsciiRows(bitmap).single()
		assertThat(row).isEqualTo("@")
	}

	@Test fun asciiUsesRec601LumaNotRgbAverage() {
		// Pure green (luma 0.587) is brighter than pure red (luma 0.299) is brighter than pure
		// blue (luma 0.114). A naive RGB-mean encoder would call all three identical brightness.
		val green = encodeAsciiRows(
			Bitmap(width = 1, height = 1, pixels = intArrayOf(rgb(0, 255, 0))),
		).single()
		val red = encodeAsciiRows(
			Bitmap(width = 1, height = 1, pixels = intArrayOf(rgb(255, 0, 0))),
		).single()
		val blue = encodeAsciiRows(
			Bitmap(width = 1, height = 1, pixels = intArrayOf(rgb(0, 0, 255))),
		).single()
		// The ramp is sorted dark→light, so brighter inputs map to later indices.
		val ramp = " .:-=+*#%@"
		check(ramp.indexOf(green[0]) > ramp.indexOf(red[0])) {
			"green ($green) should be brighter than red ($red) under Rec.601"
		}
		check(ramp.indexOf(red[0]) > ramp.indexOf(blue[0])) {
			"red ($red) should be brighter than blue ($blue) under Rec.601"
		}
	}

	@Test fun asciiImageLaysOutAtRequestedCellSize() = runTest {
		val bitmap = Bitmap(
			width = 20,
			height = 8,
			pixels = IntArray(160) { rgb(128, 128, 128) },
		)
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Column {
					Image(
						bitmap = bitmap,
						cellWidth = 5,
						cellHeight = 2,
						mode = ImageRenderMode.Ascii,
					)
					Text("done")
				}
			}
			val lines = snapshot.lines()
			assertThat(lines).hasSize(3)
			repeat(2) { row ->
				// Each ASCII row is exactly cellWidth glyphs.
				assertThat(lines[row]).hasLength(5)
			}
			assertThat(lines[2]).isEqualTo("done")
		}
	}

	// endregion

	// region Resampling

	@Test fun resampleNoopWhenAlreadyAtTargetSize() {
		val bitmap = Bitmap(width = 4, height = 4, pixels = IntArray(16) { it })
		val resampled = resampleNearest(bitmap, targetW = 4, targetH = 4)
		// Same instance returned (cheap path).
		check(resampled === bitmap) { "expected identity, got new bitmap" }
	}

	@Test fun resampleDownscalesPreservingExtremes() {
		// 4×4 with each row a unique colour. Downscale to 2×2 should still cover the dominant
		// colours per quadrant.
		val red = rgb(255, 0, 0)
		val green = rgb(0, 255, 0)
		val blue = rgb(0, 0, 255)
		val white = rgb(255, 255, 255)
		val src = Bitmap(
			width = 4,
			height = 4,
			pixels = intArrayOf(
				red, red, green, green,
				red, red, green, green,
				blue, blue, white, white,
				blue, blue, white, white,
			),
		)
		val out = resampleNearest(src, targetW = 2, targetH = 2)
		assertThat(out.width).isEqualTo(2)
		assertThat(out.height).isEqualTo(2)
		assertThat(out[0, 0]).isEqualTo(red)
		assertThat(out[1, 0]).isEqualTo(green)
		assertThat(out[0, 1]).isEqualTo(blue)
		assertThat(out[1, 1]).isEqualTo(white)
	}

	// endregion
}
