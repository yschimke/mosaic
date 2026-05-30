package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.TextCanvas
import com.jakewharton.mosaic.TextSurface
import com.jakewharton.mosaic.layout.Placeable.PlacementScope
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.unit.Constraints

internal fun interface DebugPolicy {
	fun MosaicNode.renderDebug(): String
}

internal abstract class MosaicNodeLayer :
	Placeable(),
	Measurable,
	PlacementScope,
	MeasureScope {
	abstract val next: MosaicNodeLayer?

	private var measureResult: MeasureResult = NotMeasured

	final override var parentData: Any? = null

	final override val width get() = measureResult.width
	final override val height get() = measureResult.height

	override fun measure(constraints: Constraints): Placeable = apply {
		measureResult = doMeasure(constraints)
	}

	protected open fun doMeasure(constraints: Constraints): MeasureResult {
		val placeable = next!!.measure(constraints)
		return object : MeasureResult {
			override val width: Int get() = placeable.width
			override val height: Int get() = placeable.height

			override fun placeChildren() {
				placeable.place(0, 0)
			}
		}
	}

	final override var x = 0
		private set
	final override var y = 0
		private set

	final override fun placeAt(x: Int, y: Int) {
		this.x = x
		this.y = y
		measureResult.placeChildren()
	}

	open fun drawTo(canvas: TextSurface) {
		next?.drawTo(canvas)
	}

	open fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
		return next?.sendKeyEvent(keyEvent) ?: false
	}

	/**
	 * Dispatch a [MouseEvent] through this layer. Coordinates in [event] are absolute (terminal
	 * column / row, zero-based). The default implementation passes the event to [next] without
	 * translation; [MouseLayer] intercepts before/after recursion and translates to node-local
	 * coordinates for the [MouseModifier] callbacks; [BottomLayer] hit-tests children and
	 * recurses into the top-most that contains the cursor.
	 */
	open fun sendMouseEvent(event: MouseEvent): Boolean {
		return next?.sendMouseEvent(event) ?: false
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return next?.minIntrinsicWidth(height) ?: 0
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return next?.maxIntrinsicWidth(height) ?: 0
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return next?.minIntrinsicHeight(width) ?: 0
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return next?.maxIntrinsicHeight(width) ?: 0
	}
}

internal object NotMeasured : MeasureResult {
	override val width get() = 0
	override val height get() = 0
	override fun placeChildren() = throw UnsupportedOperationException("Not measured")
}

internal class MosaicNode(
	var measurePolicy: MeasurePolicy,
	var debugPolicy: DebugPolicy,
	val isStatic: Boolean,
) : Measurable {
	val children = ArrayList<MosaicNode>()

	private val bottomLayer: MosaicNodeLayer = BottomLayer(this)
	var topLayer: MosaicNodeLayer = bottomLayer
		private set

	override var parentData: Any? = null
		private set

	var testTag: String? = null
		private set

	fun setModifier(modifier: Modifier) {
		topLayer = modifier.foldOut(bottomLayer) { element, nextLayer ->
			var nextLayer = nextLayer
			// The Modifier class can inherit from several key Modifier types
			// with different processing logic.
			if (element is LayoutModifier) {
				nextLayer = LayoutLayer(element, nextLayer)
			}
			if (element is DrawModifier) {
				nextLayer = DrawLayer(element, nextLayer)
			}
			if (element is KeyModifier) {
				nextLayer = KeyLayer(element, nextLayer)
			}
			if (element is MouseModifier) {
				nextLayer = MouseLayer(element, nextLayer)
			}
			if (element is ParentDataModifier) {
				parentData = element.modifyParentData(parentData)
			}
			if (element is TestTagModifier) {
				testTag = element.tag
			}
			nextLayer
		}
	}

	override fun measure(constraints: Constraints): Placeable = topLayer.apply { measure(constraints) }

	val width: Int get() = topLayer.width
	val height: Int get() = topLayer.height
	val x: Int get() = topLayer.x
	val y: Int get() = topLayer.y

	fun measureAndPlace() {
		val placeable = measure(Constraints())
		topLayer.run { placeable.place(0, 0) }
	}

	/**
	 * Draw this node to a [TextSurface].
	 * A call to [measureAndPlace] must precede calls to this function.
	 */
	fun draw(): TextCanvas {
		val surface = TextSurface(width, height)
		topLayer.drawTo(surface)
		return surface
	}

	fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
		return topLayer.sendKeyEvent(keyEvent)
	}

	/**
	 * Entry point for routing a [MouseEvent] from the runtime. [event] coordinates are absolute
	 * (terminal cell row/column). The event traverses this node's layer chain — `MouseLayer`
	 * pre-handlers fire on the way in, the bottom layer hit-tests children, and `MouseLayer`
	 * post-handlers fire on the way back out.
	 */
	fun sendMouseEvent(event: MouseEvent): Boolean {
		return topLayer.sendMouseEvent(event)
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return topLayer.minIntrinsicWidth(height)
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return topLayer.maxIntrinsicWidth(height)
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return topLayer.minIntrinsicHeight(width)
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return topLayer.maxIntrinsicHeight(width)
	}

	override fun toString() = debugPolicy.run { renderDebug() }
}

private class BottomLayer(
	private val node: MosaicNode,
) : MosaicNodeLayer() {
	override val next: MosaicNodeLayer? get() = null

	override fun doMeasure(constraints: Constraints): MeasureResult {
		return node.measurePolicy.run { measure(node.children, constraints) }
	}

	override fun drawTo(canvas: TextSurface) {
		for (child in node.children) {
			if (child.width != 0 && child.height != 0) {
				child.topLayer.drawTo(canvas)
			}
		}
	}

	override fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
		for (child in node.children) {
			if (child.sendKeyEvent(keyEvent)) {
				return true
			}
		}
		return false
	}

	override fun sendMouseEvent(event: MouseEvent): Boolean {
		// Dispatch to children in reverse paint order so the top-most-painted (last drawn) gets
		// first crack at the event. We deliberately do not gate on the child's bounds here: a
		// child's own [MouseLayer] hit-tests in node-local space using its absolute placed
		// position, which already accounts for any repositioning applied by layout modifiers
		// (e.g. [Modifier.offset]). Gating on the child's outer layout slot — which an offset
		// leaves at the unshifted origin — would miss a child whose content was moved away from
		// it. A child not under the cursor reports no hit and returns false, so reverse-order
		// dispatch still resolves overlaps correctly.
		for (i in node.children.indices.reversed()) {
			val child = node.children[i]
			if (child.width == 0 || child.height == 0) continue
			if (child.sendMouseEvent(event)) return true
		}
		return false
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return node.measurePolicy.run { minIntrinsicWidth(node.children, height) }
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return node.measurePolicy.run { maxIntrinsicWidth(node.children, height) }
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return node.measurePolicy.run { minIntrinsicHeight(node.children, width) }
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return node.measurePolicy.run { maxIntrinsicHeight(node.children, width) }
	}
}

private class LayoutLayer(
	private val element: LayoutModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun doMeasure(constraints: Constraints): MeasureResult {
		return element.run { measure(next, constraints) }
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return element.minIntrinsicWidth(next, height)
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return element.maxIntrinsicWidth(next, height)
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return element.minIntrinsicHeight(next, width)
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return element.maxIntrinsicHeight(next, width)
	}
}

private class DrawLayer(
	private val element: DrawModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun drawTo(canvas: TextSurface) {
		val oldX = canvas.translationX
		val oldY = canvas.translationY
		canvas.translationX = x
		canvas.translationY = y
		val scope = object : TextCanvasDrawScope(canvas, width, height), ContentDrawScope {
			override fun drawContent() {
				next.drawTo(canvas)
			}
		}
		element.run { scope.draw() }
		canvas.translationX = oldX
		canvas.translationY = oldY
	}
}

private class KeyLayer(
	private val element: KeyModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun sendKeyEvent(keyEvent: KeyEvent) = element.onPreKeyEvent(keyEvent) ||
		next.sendKeyEvent(keyEvent) ||
		element.onKeyEvent(keyEvent)
}

private class MouseLayer(
	private val element: MouseModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun sendMouseEvent(event: MouseEvent): Boolean {
		// Translate to node-local coords for the modifier callbacks — the caller-facing API
		// promises `(0, 0)` is the top-left of the modified composable. This layer's `x` / `y`
		// are its absolute placed position, so they already include any offset a layout modifier
		// applied above us; the local point is therefore correct even for repositioned nodes.
		val localX = event.x - x
		val localY = event.y - y
		// Authoritative hit-test: the handlers only see events while the cursor is over this
		// node's bounds. The downstream `next` chain always receives the original absolute event
		// so descendants hit-test in the same coordinate space their `x` / `y` were placed in.
		val inBounds = localX in 0 until width && localY in 0 until height
		val localEvent = if (localX == event.x && localY == event.y) {
			event
		} else {
			MouseEvent(
				x = localX,
				y = localY,
				type = event.type,
				button = event.button,
				shift = event.shift,
				alt = event.alt,
				ctrl = event.ctrl,
			)
		}
		return (inBounds && element.onPreMouseEvent(localEvent)) ||
			next.sendMouseEvent(event) ||
			(inBounds && element.onMouseEvent(localEvent))
	}
}
