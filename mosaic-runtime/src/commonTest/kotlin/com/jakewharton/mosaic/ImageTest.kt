package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Bitmap
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Image
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.encodeHalfBlockRows
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class ImageTest {

	private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

	@Test fun encoderProducesHalfRowsRoundedUp() {
		// 3 columns × 5 rows -> ceil(5/2) = 3 terminal rows.
		val bitmap = Bitmap(
			width = 3,
			height = 5,
			pixels = IntArray(15) { rgb(it * 10, 0, 0) },
		)
		val rows = encodeHalfBlockRows(bitmap)
		assertThat(rows).hasSize(3)
		// Each row contains exactly 3 ▀ glyphs.
		rows.forEach { row ->
			assertThat(row.count { it == '▀' }).isEqualTo(3)
		}
	}

	@Test fun encoderUsesTopPixelAsForegroundAndBottomAsBackground() {
		val red = rgb(255, 0, 0)
		val blue = rgb(0, 0, 255)
		val bitmap = Bitmap(width = 1, height = 2, pixels = intArrayOf(red, blue))
		val row = encodeHalfBlockRows(bitmap).single()
		// Foreground = top pixel red; background = bottom pixel blue.
		assertThat(row).contains("[38;2;255;0;0m")
		assertThat(row).contains("[48;2;0;0;255m")
		assertThat(row).contains("▀")
	}

	@Test fun oddHeightPadsBottomRowAsBlack() {
		val red = rgb(255, 0, 0)
		val bitmap = Bitmap(width = 1, height = 1, pixels = intArrayOf(red))
		val row = encodeHalfBlockRows(bitmap).single()
		// Single bitmap row -> top=red, bottom=0 (black).
		assertThat(row).contains("[38;2;255;0;0m")
		assertThat(row).contains("[48;2;0;0;0m")
	}

	@Test fun imageLaysOutAtBitmapDimensions() = runTest {
		// Verify that placing Image next to Text yields a sensible layout — specifically that
		// the row count below the image starts at the right offset (3 cell rows for a 6-tall
		// bitmap) regardless of payload byte length.
		val bitmap = Bitmap(
			width = 4,
			height = 6,
			pixels = IntArray(24) { rgb(it % 256, 0, 0) },
		)
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Column {
					Image(bitmap = bitmap)
					Text("HI")
				}
			}
			val lines = snapshot.lines()
			// 3 image rows (ceil(6/2)) + 1 text row = 4 lines.
			assertThat(lines).hasSize(4)
			assertThat(lines[3]).isEqualTo("HI")
		}
	}
}
