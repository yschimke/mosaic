package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.layout.onMouseEvent
import com.jakewharton.mosaic.layout.onPreviewMouseEvent
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.MouseEvent.Button
import com.jakewharton.mosaic.terminal.MouseEvent.Type
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Mouse-modifier tests. Bypass the terminal event channel via [NodeSnapshots] to grab the root
 * node directly — that keeps these tests focused on the dispatch / hit-test / coordinate logic
 * rather than the end-to-end channel pipeline (which is exercised by integration tests under
 * `:mosaic-e2e`).
 */
class MouseEventTest {

	@Test fun handlerFiresWhenClickLandsInsideBox() = runTest {
		runMosaicTest(NodeSnapshots) {
			var captured: MouseEvent? = null
			val root = setContentAndSnapshot {
				Box(
					Modifier
						.size(10, 4)
						.onMouseEvent { event ->
							captured = event
							true
						},
				) {}
			}

			val handled = root.sendMouseEvent(
				MouseEvent(x = 3, y = 1, type = Type.Press, button = Button.Left),
			)
			assertThat(handled).isEqualTo(true)

			val event = captured
			check(event != null) { "no event captured" }
			// Box is at (0, 0), click landed at absolute (3, 1) → local (3, 1).
			assertThat(event.x).isEqualTo(3)
			assertThat(event.y).isEqualTo(1)
			assertThat(event.button).isEqualTo(Button.Left)
			assertThat(event.type).isEqualTo(Type.Press)
		}
	}

	@Test fun handlerDoesNotFireWhenClickOutsideBounds() = runTest {
		runMosaicTest(NodeSnapshots) {
			var captured: MouseEvent? = null
			val root = setContentAndSnapshot {
				Box(
					Modifier
						.size(5, 2)
						.onMouseEvent { event ->
							captured = event
							true
						},
				) {}
			}

			val handled = root.sendMouseEvent(
				MouseEvent(x = 50, y = 50, type = Type.Press, button = Button.Left),
			)
			assertThat(handled).isEqualTo(false)
			assertThat(captured).isNull()
		}
	}

	@Test fun localCoordinatesAreRelativeToNodePosition() = runTest {
		runMosaicTest(NodeSnapshots) {
			var capturedLocal: MouseEvent? = null
			val root = setContentAndSnapshot {
				Column(modifier = Modifier.size(40, 10)) {
					Box(
						Modifier
							.offset(x = 7, y = 3)
							.size(8, 4)
							.onMouseEvent { event ->
								capturedLocal = event
								true
							},
					) {}
				}
			}

			// Click at absolute (10, 4) — inside the offset box, at local (3, 1).
			root.sendMouseEvent(MouseEvent(x = 10, y = 4, type = Type.Press, button = Button.Left))

			val event = capturedLocal
			check(event != null) { "no event captured" }
			assertThat(event.x).isEqualTo(3)
			assertThat(event.y).isEqualTo(1)
		}
	}

	@Test fun previewInterceptsBeforeChildren() = runTest {
		runMosaicTest(NodeSnapshots) {
			val order = mutableListOf<String>()
			val root = setContentAndSnapshot {
				Box(
					Modifier
						.size(10, 4)
						.onPreviewMouseEvent {
							order += "parent-preview"
							true // intercept; child should not see it
						},
				) {
					Box(
						Modifier
							.size(10, 4)
							.onMouseEvent {
								order += "child-handle"
								true
							},
					) {}
				}
			}

			root.sendMouseEvent(MouseEvent(x = 1, y = 1, type = Type.Press, button = Button.Left))

			assertThat(order).isEqualTo(mutableListOf("parent-preview"))
		}
	}

	@Test fun upwardPassChildHandledFirstThenParent() = runTest {
		runMosaicTest(NodeSnapshots) {
			val order = mutableListOf<String>()
			val root = setContentAndSnapshot {
				Box(
					Modifier
						.size(10, 4)
						.onMouseEvent {
							order += "parent-handle"
							true
						},
				) {
					Box(
						Modifier
							.size(10, 4)
							.onMouseEvent {
								order += "child-handle"
								false // don't consume; let parent handle
							},
					) {}
				}
			}

			root.sendMouseEvent(MouseEvent(x = 1, y = 1, type = Type.Press, button = Button.Left))

			// Child fires first (deeper in the tree, upward pass), then parent.
			assertThat(order).isEqualTo(mutableListOf("child-handle", "parent-handle"))
		}
	}

	@Test fun mouseWheelDeliversThroughHandler() = runTest {
		runMosaicTest(NodeSnapshots) {
			var captured: MouseEvent? = null
			val root = setContentAndSnapshot {
				Box(
					Modifier
						.size(20, 10)
						.onMouseEvent { event ->
							captured = event
							true
						},
				) {}
			}

			root.sendMouseEvent(
				MouseEvent(x = 5, y = 5, type = Type.Press, button = Button.WheelDown),
			)

			val event = captured
			check(event != null) { "no event captured" }
			assertThat(event.button).isEqualTo(Button.WheelDown)
		}
	}
}
