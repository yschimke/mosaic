package com.jakewharton.mosaic.ui

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.layout.KeyEvent
import kotlin.test.Test

/**
 * Behaviour of [BasicTextField] is split into [handleKey] (pure) and the composable shell.
 * Tests target the pure function so they don't need a Mosaic test harness.
 */
class BasicTextFieldTest {

	// region Insertion

	@Test fun printableCharInsertsAtCursorAndAdvances() {
		val before = TextFieldValue(text = "helo", cursor = 3)
		val after = handleKey(before, KeyEvent("l"))
		assertThat(after).isEqualTo(TextFieldValue(text = "hello", cursor = 4))
	}

	@Test fun spaceIsTreatedAsPrintable() {
		val after = handleKey(TextFieldValue("hi", 2), KeyEvent(" "))
		assertThat(after).isEqualTo(TextFieldValue(text = "hi ", cursor = 3))
	}

	@Test fun symbolInsertsLikeLetter() {
		val after = handleKey(TextFieldValue("", 0), KeyEvent("!"))
		assertThat(after).isEqualTo(TextFieldValue(text = "!", cursor = 1))
	}

	// endregion

	// region Deletion

	@Test fun backspaceRemovesCharBeforeCursor() {
		val after = handleKey(TextFieldValue("hello", 5), KeyEvent("Backspace"))
		assertThat(after).isEqualTo(TextFieldValue(text = "hell", cursor = 4))
	}

	@Test fun backspaceAtStartReturnsNull() {
		assertThat(handleKey(TextFieldValue("hi", 0), KeyEvent("Backspace"))).isNull()
	}

	@Test fun deleteRemovesCharAtCursor() {
		val after = handleKey(TextFieldValue("hello", 2), KeyEvent("Delete"))
		assertThat(after).isEqualTo(TextFieldValue(text = "helo", cursor = 2))
	}

	@Test fun deleteAtEndReturnsNull() {
		assertThat(handleKey(TextFieldValue("hi", 2), KeyEvent("Delete"))).isNull()
	}

	// endregion

	// region Cursor motion

	@Test fun arrowLeftMovesOneCell() {
		val after = handleKey(TextFieldValue("abc", 2), KeyEvent("ArrowLeft"))
		assertThat(after).isEqualTo(TextFieldValue(text = "abc", cursor = 1))
	}

	@Test fun arrowRightMovesOneCell() {
		val after = handleKey(TextFieldValue("abc", 1), KeyEvent("ArrowRight"))
		assertThat(after).isEqualTo(TextFieldValue(text = "abc", cursor = 2))
	}

	@Test fun arrowLeftAtStartReturnsNull() {
		assertThat(handleKey(TextFieldValue("abc", 0), KeyEvent("ArrowLeft"))).isNull()
	}

	@Test fun homeJumpsToStart() {
		val after = handleKey(TextFieldValue("abc", 3), KeyEvent("Home"))
		assertThat(after).isEqualTo(TextFieldValue(text = "abc", cursor = 0))
	}

	@Test fun endJumpsToEnd() {
		val after = handleKey(TextFieldValue("abc", 0), KeyEvent("End"))
		assertThat(after).isEqualTo(TextFieldValue(text = "abc", cursor = 3))
	}

	@Test fun ctrlAJumpsToStart() {
		val after = handleKey(TextFieldValue("abc", 3), KeyEvent("a", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue(text = "abc", cursor = 0))
	}

	@Test fun ctrlEJumpsToEnd() {
		val after = handleKey(TextFieldValue("abc", 1), KeyEvent("e", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue(text = "abc", cursor = 3))
	}

	// endregion

	// region Word-aware motion

	@Test fun ctrlArrowLeftSkipsToWordStart() {
		// "hello world", cursor at end (11), Ctrl-Left → cursor at start of "world" (6).
		val after = handleKey(TextFieldValue("hello world", 11), KeyEvent("ArrowLeft", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue("hello world", 6))
	}

	@Test fun ctrlArrowLeftSkipsTrailingWhitespaceThenWord() {
		// "hello   world", cursor between 'd' and end (13). Ctrl-Left skips "world" then stops
		// at start (8 = position before 'w').
		val after = handleKey(TextFieldValue("hello   world", 13), KeyEvent("ArrowLeft", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue("hello   world", 8))
	}

	@Test fun ctrlArrowRightSkipsToWordEnd() {
		// "hello world", cursor at start. Ctrl-Right → end of "hello" = 5.
		val after = handleKey(TextFieldValue("hello world", 0), KeyEvent("ArrowRight", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue("hello world", 5))
	}

	// endregion

	// region Emacs-style line edits

	@Test fun ctrlUClearsBeforeCursor() {
		// "hello world", cursor at 6 (between space and 'w'). Ctrl-U → "world", cursor at 0.
		val after = handleKey(TextFieldValue("hello world", 6), KeyEvent("u", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue("world", 0))
	}

	@Test fun ctrlKClearsAfterCursor() {
		val after = handleKey(TextFieldValue("hello world", 5), KeyEvent("k", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue("hello", 5))
	}

	@Test fun ctrlWDeletesWordBeforeCursor() {
		// "hello world|", Ctrl-W → "hello "
		val after = handleKey(TextFieldValue("hello world", 11), KeyEvent("w", ctrl = true))
		assertThat(after).isEqualTo(TextFieldValue("hello ", 6))
	}

	// endregion

	// region Pass-through

	@Test fun enterIsNotConsumed() {
		assertThat(handleKey(TextFieldValue("hi", 2), KeyEvent("Enter"))).isNull()
	}

	@Test fun escapeIsNotConsumed() {
		assertThat(handleKey(TextFieldValue("hi", 2), KeyEvent("Escape"))).isNull()
	}

	@Test fun tabIsNotConsumed() {
		assertThat(handleKey(TextFieldValue("hi", 2), KeyEvent("Tab"))).isNull()
	}

	@Test fun unknownCtrlComboIsNotConsumed() {
		assertThat(handleKey(TextFieldValue("hi", 2), KeyEvent("z", ctrl = true))).isNull()
	}

	// endregion

	// region Rendering

	@Test fun renderShowsCursorOnFirstCharWhenAtStart() {
		val rendered = renderWithCursor(TextFieldValue("abc", 0))
		assertThat(rendered.text).isEqualTo("abc")
		// First character spanned with Invert style; remaining unstyled.
		val styles = rendered.spanStyles
		assertThat(styles).hasSize(1)
		assertThat(styles[0].start).isEqualTo(0)
		assertThat(styles[0].end).isEqualTo(1)
	}

	@Test fun renderAppendsSpaceWhenCursorAtEnd() {
		val rendered = renderWithCursor(TextFieldValue("ab", 2))
		assertThat(rendered.text).isEqualTo("ab ")
		assertThat(rendered.spanStyles[0].start).isEqualTo(2)
		assertThat(rendered.spanStyles[0].end).isEqualTo(3)
	}

	// endregion

	// region Word boundaries (direct)

	@Test fun wordBoundaryLeftAtZeroStaysAtZero() {
		assertThat(wordBoundaryLeft("hello", 0)).isEqualTo(0)
	}

	@Test fun wordBoundaryLeftSkipsTrailingSpaceThenWord() {
		// "foo   bar", cursor at 9 (end). Skip nothing, then word "bar" → cursor 6.
		assertThat(wordBoundaryLeft("foo   bar", 9)).isEqualTo(6)
	}

	@Test fun wordBoundaryRightAtEndStays() {
		assertThat(wordBoundaryRight("hello", 5)).isEqualTo(5)
	}

	@Test fun wordBoundaryRightSkipsLeadingSpaceThenWord() {
		// "foo   bar", cursor at 3 (between "foo" and spaces). Skip spaces, then "bar" → 9.
		assertThat(wordBoundaryRight("foo   bar", 3)).isEqualTo(9)
	}

	// endregion
}
