package android.util

class Size(private val w: Int, private val h: Int) {
    fun getWidth(): Int = w
    fun getHeight(): Int = h
    override fun equals(other: Any?) = other is Size && other.w == w && other.h == h
    override fun hashCode() = h xor ((w shl 16) or (w ushr 16))
    override fun toString() = "${w}x$h"
}
