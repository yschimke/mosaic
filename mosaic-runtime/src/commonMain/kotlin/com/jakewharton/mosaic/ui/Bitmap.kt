package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Immutable

/**
 * A minimal, platform-neutral image backing store: a packed-ARGB pixel grid with explicit width
 * and height. Callers are responsible for decoding their image format (PNG, JPEG, …) into this
 * representation — Mosaic deliberately does not depend on any platform image library so that
 * decoding lives where it belongs (the host JVM, an asset pipeline, a generated test fixture).
 *
 * Pixel layout: `pixels[y * width + x]` is the packed ARGB value at `(x, y)`. Bits 24..31 alpha,
 * 16..23 red, 8..15 green, 0..7 blue. The alpha channel is currently ignored by [Image] but the
 * format keeps room for it.
 */
@Immutable
public class Bitmap(
	public val width: Int,
	public val height: Int,
	public val pixels: IntArray,
) {
	init {
		require(width >= 0 && height >= 0) { "Bitmap dimensions must be non-negative ($width × $height)" }
		require(pixels.size == width * height) {
			"Bitmap pixel buffer is ${pixels.size}, expected ${width * height} for $width × $height"
		}
	}

	public operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

	public companion object {
		/** Convenience: build a bitmap from a list of `width × height` packed-ARGB rows. */
		public fun fromRows(width: Int, rows: List<IntArray>): Bitmap {
			require(rows.all { it.size == width }) { "every row must be exactly $width wide" }
			val flat = IntArray(width * rows.size)
			for ((y, row) in rows.withIndex()) {
				row.copyInto(flat, destinationOffset = y * width)
			}
			return Bitmap(width = width, height = rows.size, pixels = flat)
		}
	}
}

internal inline val Int.argbR: Int get() = (this ushr 16) and 0xFF
internal inline val Int.argbG: Int get() = (this ushr 8) and 0xFF
internal inline val Int.argbB: Int get() = this and 0xFF
