package android.graphics

import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/** Backed by java.awt.geom.Path2D (non-zero winding, Android's default fill type). Direction is accepted, not used. */
class Path() {
    enum class Direction { CW, CCW }
    enum class FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }

    internal val awt = Path2D.Float(Path2D.WIND_NON_ZERO)

    constructor(src: Path) : this() {
        awt.append(src.awt, false)
    }

    var fillType: FillType = FillType.WINDING
        set(v) {
            field = v
            awt.windingRule = if (v == FillType.EVEN_ODD || v == FillType.INVERSE_EVEN_ODD) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO
        }

    val isEmpty: Boolean get() = awt.currentPoint == null

    fun reset() = awt.reset()
    fun rewind() = awt.reset()

    // Android starts a contour at (0, 0) when lineTo/quadTo come without a moveTo; Path2D would throw.
    private fun ensureStart() {
        if (awt.currentPoint == null) awt.moveTo(0f, 0f)
    }

    fun moveTo(x: Float, y: Float) = awt.moveTo(x, y)
    fun lineTo(x: Float, y: Float) {
        ensureStart(); awt.lineTo(x, y)
    }
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        ensureStart(); awt.quadTo(x1, y1, x2, y2)
    }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        ensureStart(); awt.curveTo(x1, y1, x2, y2, x3, y3)
    }
    fun close() {
        if (awt.currentPoint != null) awt.closePath()
    }

    fun addRect(left: Float, top: Float, right: Float, bottom: Float, dir: Direction) =
        awt.append(Rectangle2D.Float(left, top, right - left, bottom - top), false)
    fun addRect(rect: RectF, dir: Direction) = addRect(rect.left, rect.top, rect.right, rect.bottom, dir)
    fun addRoundRect(rect: RectF, rx: Float, ry: Float, dir: Direction) =
        awt.append(RoundRectangle2D.Float(rect.left, rect.top, rect.width(), rect.height(), rx * 2, ry * 2), false)
    fun addRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, dir: Direction) =
        addRoundRect(RectF(left, top, right, bottom), rx, ry, dir)
    fun addCircle(x: Float, y: Float, radius: Float, dir: Direction) =
        awt.append(Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2), false)
    fun addOval(oval: RectF, dir: Direction) = awt.append(Ellipse2D.Float(oval.left, oval.top, oval.width(), oval.height()), false)
    fun addPath(src: Path) = awt.append(src.awt, false)
}
