package android.view

import android.util.DisplayMetrics

/** android.view.Display: a logical display's id, name and size. */
open class Display internal constructor(private val id: Int, private val name: String, private val width: Int, private val height: Int, private val dpi: Int) {
    open fun getDisplayId(): Int = id
    open fun getName(): String = name
    open fun isValid(): Boolean = true
    open fun getRotation(): Int = 0

    @Deprecated("Use WindowMetrics") open fun getWidth(): Int = width

    @Deprecated("Use WindowMetrics") open fun getHeight(): Int = height

    open fun getMetrics(out: DisplayMetrics) {
        out.widthPixels = width
        out.heightPixels = height
        out.densityDpi = dpi
        out.density = dpi / 160f
        out.scaledDensity = out.density
    }

    open fun getRealMetrics(out: DisplayMetrics) = getMetrics(out)

    companion object {
        const val DEFAULT_DISPLAY = 0
    }
}
