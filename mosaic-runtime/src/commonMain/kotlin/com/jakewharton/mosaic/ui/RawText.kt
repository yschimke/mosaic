@file:JvmName("RawText")

package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.modifier.Modifier
import kotlin.jvm.JvmName

/**
 * A text-like composable whose byte content is emitted verbatim and whose layout footprint is
 * the caller-supplied [displayWidth] × `lines.size`.
 *
 * Unlike [Text], which derives width from code-point count, [RawText] is the right tool when
 * [lines] embed escape sequences that don't occupy visible cells:
 *
 *  - SGR-coloured half-block image rows (truecolor `▀` runs)
 *  - Image-protocol envelopes that draw a raster into a single text-row span (sixel, Kitty
 *    graphics, iTerm2 inline images)
 *  - Hand-rolled style-stream effects where the visible width is known up front but the
 *    code-point count is misleading
 *
 * The renderer wraps each row's content in SGR reset on both sides so any styling inside
 * [lines] does not bleed into surrounding composition state.
 *
 * @param lines One [String] per row; each string is emitted as a single payload regardless of
 *   length. Newlines inside an entry are not interpreted — split them yourself.
 * @param displayWidth The number of cells each row occupies. Must be >= 1.
 * @param modifier Standard modifier chain.
 */
@Composable
@MosaicComposable
public fun RawText(
	lines: List<String>,
	displayWidth: Int,
	modifier: Modifier = Modifier,
) {
	require(displayWidth >= 1) { "displayWidth must be >= 1, was $displayWidth" }
	val rowCount = lines.size
	Layout(
		debugInfo = {
			"""RawText(rows=$rowCount, width=$displayWidth)"""
		},
		measurePolicy = {
			layout(displayWidth, rowCount)
		},
		modifier = modifier.drawBehind {
			for (row in 0 until rowCount) {
				drawRaw(row = row, column = 0, displayWidth = displayWidth, content = lines[row])
			}
		},
	)
}

/**
 * Single-row convenience overload. Equivalent to `RawText(listOf(content), displayWidth, modifier)`.
 */
@Composable
@MosaicComposable
public fun RawText(
	content: String,
	displayWidth: Int,
	modifier: Modifier = Modifier,
) {
	RawText(lines = listOf(content), displayWidth = displayWidth, modifier = modifier)
}
