package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.TestTerminal
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.RawText
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class RawTextTest {

	/** Stable, easy-to-spot escape payload that regular `Text` would have measured at >10 cells. */
	private val truecolorBlock =
		"${CSI}38;2;255;0;0m${CSI}48;2;0;0;255m▀${ansiReset}${ansiClosingCharacter}"

	@Test fun displayWidthOverridesContentWidth() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				// One raw cell, a normal Text on the next row. If layout measured by codepoint
				// count, the row below would be pushed to match the payload's footprint — we
				// assert it isn't.
				Column {
					RawText(content = truecolorBlock, displayWidth = 1)
					Text("X")
				}
			}
			val lines = snapshot.lines()
			assertThat(lines[0]).isEqualTo(truecolorBlock)
			assertThat(lines[1]).isEqualTo("X")
		}
	}

	@Test fun multipleRowsLayoutAsRectangle() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Column {
					RawText(
						lines = listOf("AAA", "BBB", "CCC"),
						displayWidth = 3,
					)
					Text("done")
				}
			}
			val lines = snapshot.lines()
			assertThat(lines[0]).isEqualTo("AAA")
			assertThat(lines[1]).isEqualTo("BBB")
			assertThat(lines[2]).isEqualTo("CCC")
			assertThat(lines[3]).isEqualTo("done")
		}
	}

	@Test fun payloadIsEmittedVerbatimUnderAnsi() = runTest {
		val rendering = AnsiRendering(TestTerminal.Capabilities(ansiLevel = AnsiLevel.TRUECOLOR))
		runMosaicTest(RenderingSnapshots(rendering)) {
			val snapshot = setContentAndSnapshot {
				RawText(content = truecolorBlock, displayWidth = 1)
			}
			// The exact byte sequence must appear in the final output untouched.
			assertThat(snapshot).contains(truecolorBlock)
		}
	}

	@Test fun payloadIsResetBracketedSoStylesDontLeak() = runTest {
		val rendering = AnsiRendering(TestTerminal.Capabilities(ansiLevel = AnsiLevel.TRUECOLOR))
		runMosaicTest(RenderingSnapshots(rendering)) {
			// Payload intentionally leaves an open foreground colour. Without the renderer
			// bracketing it with resets, the trailing 'tail' Text would inherit red.
			val unterminated = "${CSI}31mhello"
			val resetSeq = "${ansiReset}${ansiClosingCharacter}"
			val snapshot = setContentAndSnapshot {
				Column {
					RawText(content = unterminated, displayWidth = 5)
					Text("tail")
				}
			}
			val payloadIdx = snapshot.indexOf(unterminated)
			check(payloadIdx >= 0) { "payload not in output" }
			val tailIdx = snapshot.indexOf("tail")
			check(tailIdx > payloadIdx) { "tail not after payload" }
			val resetIdx = snapshot.indexOf(resetSeq, startIndex = payloadIdx + unterminated.length)
			check(resetIdx in (payloadIdx + unterminated.length) until tailIdx) {
				"expected an SGR reset between payload and 'tail'"
			}
		}
	}

	@Test fun continuationCellsEmitNothing() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				// Single payload claiming 4 cells. The output row must be exactly the payload
				// — not the payload followed by 3 spaces.
				RawText(content = "ABCD", displayWidth = 4)
			}
			assertThat(snapshot.lines()[0]).isEqualTo("ABCD")
		}
	}
}
