package android.graphics

class RectF @JvmOverloads constructor(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)
    constructor(r: Rect) : this(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())

    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun exactCenterX() = centerX()
    fun exactCenterY() = centerY()

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun set(r: RectF) = set(r.left, r.top, r.right, r.bottom)
    fun set(r: Rect) = set(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())

    fun offset(dx: Float, dy: Float) {
        left += dx; right += dx; top += dy; bottom += dy
    }

    override fun toString() = "RectF($left, $top, $right, $bottom)"
}
