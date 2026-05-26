@file:JvmName("BasicTextField")

package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import kotlin.jvm.JvmName

/**
 * A minimal single-line text input. Renders [value]'s text with the cursor shown as an inverted
 * cell, and (when [enabled]) consumes printable characters / cursor-motion keys from the
 * keyboard event stream to produce the next [TextFieldValue], emitted via [onValueChange].
 *
 * State is fully external — this composable does not own the text. Wire it up with a
 * `remember { mutableStateOf(TextFieldValue("")) }` (or your own state holder) and write back
 * inside [onValueChange]. That keeps the field reactive and lets callers do validation,
 * formatting, undo, etc. outside the composable.
 *
 * Supported keys when [enabled]:
 *  - Printable ASCII (`a`..`z`, `A`..`Z`, digits, symbols, space) — insert at cursor
 *  - `Backspace` — delete character to the left of the cursor
 *  - `Delete` — delete character under the cursor
 *  - `ArrowLeft` / `ArrowRight` — move the cursor one cell
 *  - `Ctrl+ArrowLeft` / `Ctrl+ArrowRight` — move the cursor one whitespace-delimited word
 *  - `Home` / `Ctrl+a` — jump to start
 *  - `End` / `Ctrl+e` — jump to end
 *  - `Ctrl+u` — clear from cursor back to start
 *  - `Ctrl+k` — clear from cursor to end
 *  - `Ctrl+w` — delete the word to the cursor's left
 *
 * Anything else (including `Enter`, `Tab`, `Escape`) is *not* consumed — return values from the
 * underlying [onKeyEvent] handler bubble back up so parent composables can attach submit /
 * cancel / focus-shift behaviour.
 *
 * Limitations: single-line only (no `Enter` insertion), no IME / clipboard / selection. Multi-
 * line and selection-aware variants are intentionally separate features for the future.
 *
 * @param value Current text + cursor.
 * @param onValueChange Emit the next [TextFieldValue] after a key is processed.
 * @param modifier Standard modifier chain.
 * @param enabled When false, key events pass through unhandled and the cursor still renders.
 */
@Composable
@MosaicComposable
public fun BasicTextField(
	value: TextFieldValue,
	onValueChange: (TextFieldValue) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	val rendered = renderWithCursor(value)
	val keyModifier = if (enabled) {
		Modifier.onKeyEvent { event ->
			val next = handleKey(value, event)
			if (next != null) {
				onValueChange(next)
				true
			} else {
				false
			}
		}
	} else {
		Modifier
	}
	Text(value = rendered, modifier = modifier then keyModifier)
}

private val CursorStyle = SpanStyle(textStyle = TextStyle.Invert)

internal fun renderWithCursor(value: TextFieldValue): AnnotatedString {
	val text = value.text
	val cursor = value.cursor
	// Append a single space sentinel when the cursor sits past the last character so the
	// inverted block has something to attach to.
	val cursorChar = if (cursor < text.length) text[cursor].toString() else " "
	return buildAnnotatedString {
		if (cursor > 0) append(text.substring(0, cursor))
		withStyle(CursorStyle) { append(cursorChar) }
		val tailStart = cursor + 1
		if (tailStart < text.length) append(text.substring(tailStart))
	}
}

/**
 * Pure function: given the current value and a key, return the next value or null if the key
 * is not consumed (so the caller can let it bubble). Split out for unit testing — the
 * composable wraps it but the behaviour lives here.
 */
internal fun handleKey(value: TextFieldValue, event: KeyEvent): TextFieldValue? {
	val text = value.text
	val cursor = value.cursor

	// Ctrl combos first; they take precedence over the plain key name lookup.
	if (event.ctrl && !event.alt) {
		return when (event.key) {
			"a" -> if (cursor == 0) null else value.copyAt(cursor = 0)
			"e" -> if (cursor == text.length) null else value.copyAt(cursor = text.length)
			"u" -> {
				if (cursor == 0) null
				else TextFieldValue(text = text.substring(cursor), cursor = 0)
			}
			"k" -> {
				if (cursor == text.length) null
				else TextFieldValue(text = text.substring(0, cursor), cursor = cursor)
			}
			"w" -> {
				if (cursor == 0) null
				else {
					val newCursor = wordBoundaryLeft(text, cursor)
					TextFieldValue(
						text = text.substring(0, newCursor) + text.substring(cursor),
						cursor = newCursor,
					)
				}
			}
			"ArrowLeft" -> {
				val newCursor = wordBoundaryLeft(text, cursor)
				if (newCursor == cursor) null else value.copyAt(cursor = newCursor)
			}
			"ArrowRight" -> {
				val newCursor = wordBoundaryRight(text, cursor)
				if (newCursor == cursor) null else value.copyAt(cursor = newCursor)
			}
			else -> null
		}
	}

	return when (event.key) {
		"Backspace" -> {
			if (cursor == 0) null
			else TextFieldValue(
				text = text.substring(0, cursor - 1) + text.substring(cursor),
				cursor = cursor - 1,
			)
		}
		"Delete" -> {
			if (cursor == text.length) null
			else TextFieldValue(
				text = text.substring(0, cursor) + text.substring(cursor + 1),
				cursor = cursor,
			)
		}
		"ArrowLeft" -> if (cursor == 0) null else value.copyAt(cursor = cursor - 1)
		"ArrowRight" -> if (cursor == text.length) null else value.copyAt(cursor = cursor + 1)
		"Home" -> if (cursor == 0) null else value.copyAt(cursor = 0)
		"End" -> if (cursor == text.length) null else value.copyAt(cursor = text.length)
		else -> {
			// Printable single-codepoint key — insert at cursor.
			if (event.key.length == 1 && event.key[0].code in 32..126) {
				TextFieldValue(
					text = text.substring(0, cursor) + event.key + text.substring(cursor),
					cursor = cursor + 1,
				)
			} else {
				null
			}
		}
	}
}

private fun TextFieldValue.copyAt(cursor: Int) = TextFieldValue(text, cursor)

/**
 * Find the start of the word at or before [cursor]: skip trailing whitespace, then skip
 * non-whitespace. Mirrors readline's `M-b` / shell's `Ctrl-w` semantics.
 */
internal fun wordBoundaryLeft(text: String, cursor: Int): Int {
	var i = cursor
	while (i > 0 && text[i - 1].isWhitespace()) i--
	while (i > 0 && !text[i - 1].isWhitespace()) i--
	return i
}

/** Mirror of [wordBoundaryLeft] for the rightward direction. */
internal fun wordBoundaryRight(text: String, cursor: Int): Int {
	var i = cursor
	while (i < text.length && text[i].isWhitespace()) i++
	while (i < text.length && !text[i].isWhitespace()) i++
	return i
}
