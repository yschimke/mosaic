package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko

/**
 * Snapshot of a [BasicTextField]'s contents: the text plus the cursor position.
 *
 * The cursor is the *insert position*: `cursor == 0` means before the first character,
 * `cursor == text.length` means after the last. Typing inserts at the cursor and advances it
 * by one; backspace deletes the character to the cursor's left.
 *
 * Treat this as a value: emit a new instance from `onValueChange` rather than mutating in place.
 * That keeps Compose's diffing accurate and avoids subtle bugs where the cursor and text drift
 * out of sync.
 */
@[Immutable Poko]
public class TextFieldValue(
	public val text: String,
	public val cursor: Int = text.length,
) {
	init {
		require(cursor in 0..text.length) {
			"cursor $cursor out of range 0..${text.length} for text=\"$text\""
		}
	}

	/** Convenience for callers that only care about updating the text. Cursor pins to end. */
	public fun withText(newText: String): TextFieldValue =
		TextFieldValue(text = newText, cursor = newText.length)
}
