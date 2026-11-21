package android.graphics.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat

open class ColorDrawable @JvmOverloads constructor(private var color: Int = 0) : Drawable() {
    open fun getColor(): Int = color
    open fun setColor(color: Int) {
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        if (color ushr 24 != 0) canvas.drawColor(color)
    }

    override fun setAlpha(alpha: Int) {
        color = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = when (color ushr 24) {
        255 -> PixelFormat.OPAQUE
        0 -> PixelFormat.TRANSPARENT
        else -> PixelFormat.TRANSLUCENT
    }
}
