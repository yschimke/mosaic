package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent

/**
 * Modifier that receives [MouseEvent]s when they land inside the node's bounds. The runtime
 * computes those bounds from the node's measured position and size and routes events through a
 * pre/post traversal so containers can intercept before children or react after.
 *
 * The event coordinates are translated into node-local space (i.e. `(x, y)` of `(0, 0)` is the
 * top-left of the node, not the terminal). Callers don't have to care about node positioning.
 */
public interface MouseModifier : Modifier.Element {
	/**
	 * Called on the downward pass — root → leaf — through the node tree. Lets a container
	 * intercept a mouse event before its children see it. Return true to stop propagation,
	 * false to let the event continue down to children.
	 *
	 * Coordinates in [event] are in this node's local space; the runtime has already subtracted
	 * the node's position before invoking the modifier.
	 */
	public fun onPreMouseEvent(event: MouseEvent): Boolean

	/**
	 * Called on the upward pass — leaf → root — after children have had a chance. Return true
	 * to stop further bubbling, false to let the event keep traveling up to ancestors. This is
	 * the slot to put most click handlers in: lets nested clickable regions win without the
	 * outer one stealing the event first.
	 */
	public fun onMouseEvent(event: MouseEvent): Boolean
}

/**
 * Adding this [Modifier] lets a node react to mouse events that fall inside its laid-out bounds,
 * during the **upward** pass (child first, then this, then ancestors). The handler receives
 * coordinates relative to this node — `(0, 0)` is the top-left of the modified composable.
 *
 * Returning `true` from [onMouseEvent] stops the event from bubbling to ancestors.
 */
public fun Modifier.onMouseEvent(
	onMouseEvent: (event: MouseEvent) -> Boolean,
): Modifier = this then MouseModifierElement(null, onMouseEvent)

/**
 * Adding this [Modifier] lets a node intercept mouse events on the **downward** pass — before
 * any descendant sees them. Useful for blocking-style overlays (modal dialogs that should
 * swallow clicks even on the content "behind" them) and for hit-test gating in containers that
 * want to filter events globally before delegating.
 *
 * Returning `true` stops the event from reaching descendants and ancestors alike.
 */
public fun Modifier.onPreviewMouseEvent(
	onPreviewMouseEvent: (event: MouseEvent) -> Boolean,
): Modifier = this then MouseModifierElement(onPreviewMouseEvent, null)

private class MouseModifierElement(
	val onPreEvent: ((MouseEvent) -> Boolean)?,
	val onEvent: ((MouseEvent) -> Boolean)?,
) : MouseModifier {
	override fun onPreMouseEvent(event: MouseEvent) = onPreEvent?.invoke(event) ?: false
	override fun onMouseEvent(event: MouseEvent) = onEvent?.invoke(event) ?: false
}
