package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test

class RenderMosaicTest {
	@Test fun rendersSingleFrame() {
		val output = renderMosaic {
			Column {
				Text("Hello")
				Text("World")
			}
		}
		assertThat(output).contains("Hello")
		assertThat(output).contains("World")
	}

	@Test fun preservesColor() {
		// TRUECOLOR capabilities mean styled text keeps its SGR escapes rather than being stripped.
		val output = renderMosaic {
			Text("hi", color = com.jakewharton.mosaic.ui.Color.Red)
		}
		assertThat(output).contains("$CSI")
	}

	@Test fun emptyContentRendersNothingVisible() {
		val output = renderMosaic {}
		assertThat(output).isEqualTo("")
	}
}
