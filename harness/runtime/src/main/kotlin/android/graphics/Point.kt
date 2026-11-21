package android.graphics

class Point @JvmOverloads constructor(@JvmField var x: Int = 0, @JvmField var y: Int = 0) {
    constructor(src: Point) : this(src.x, src.y)

    fun set(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    override fun equals(other: Any?) = other is Point && x == other.x && y == other.y
    override fun hashCode() = 31 * x + y
    override fun toString() = "Point($x, $y)"
}
