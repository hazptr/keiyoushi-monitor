package android.graphics.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect

abstract class Drawable {
    private val bounds = Rect()

    abstract fun draw(canvas: Canvas)
    abstract fun setAlpha(alpha: Int)
    abstract fun setColorFilter(colorFilter: ColorFilter?)

    @Deprecated("Deprecated in Android")
    abstract fun getOpacity(): Int

    fun getBounds(): Rect = bounds
    open fun setBounds(left: Int, top: Int, right: Int, bottom: Int) = bounds.set(left, top, right, bottom)
    open fun setBounds(bounds: Rect) = setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    open fun getIntrinsicWidth(): Int = -1
    open fun getIntrinsicHeight(): Int = -1
    open fun invalidateSelf() {}
}
