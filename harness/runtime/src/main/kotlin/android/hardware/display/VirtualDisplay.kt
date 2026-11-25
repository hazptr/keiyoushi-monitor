package android.hardware.display

import android.view.Display
import android.view.Surface

/** android.hardware.display.VirtualDisplay: a display whose content goes to a [Surface]. */
open class VirtualDisplay internal constructor(private val display: Display, private var surface: Surface?) {
    open fun getDisplay(): Display = display
    open fun getSurface(): Surface? = surface
    open fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    open fun resize(width: Int, height: Int, densityDpi: Int) {}
    open fun release() {}
}
