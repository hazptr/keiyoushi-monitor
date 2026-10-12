package android.graphics

open class Rect @JvmOverloads constructor(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    constructor(r: Rect) : this(r.left, r.top, r.right, r.bottom)

    fun width() = right - left
    fun height() = bottom - top
    fun isEmpty() = left >= right || top >= bottom
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
    fun exactCenterX() = (left + right) / 2f
    fun exactCenterY() = (top + bottom) / 2f

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun set(r: Rect) = set(r.left, r.top, r.right, r.bottom)

    fun offset(dx: Int, dy: Int) {
        left += dx; right += dx; top += dy; bottom += dy
    }

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    override fun toString() = "Rect($left, $top, $right, $bottom)"
    override fun equals(other: Any?) = other is Rect && left == other.left && top == other.top && right == other.right && bottom == other.bottom
    override fun hashCode() = ((left * 31 + top) * 31 + right) * 31 + bottom
}
