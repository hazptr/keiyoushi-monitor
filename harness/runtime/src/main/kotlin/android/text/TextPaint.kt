package android.text

import android.graphics.Paint

open class TextPaint @JvmOverloads constructor(flags: Int = 0) : Paint(flags) {
    @JvmField var baselineShift: Int = 0
    @JvmField var bgColor: Int = 0
    @JvmField var linkColor: Int = 0
    @JvmField var density: Float = 1f
    @JvmField var drawableState: IntArray? = null

    constructor(p: Paint) : this(0) {
        set(p)
    }
}
