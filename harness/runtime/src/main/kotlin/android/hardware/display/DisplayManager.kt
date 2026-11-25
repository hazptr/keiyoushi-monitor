package android.hardware.display

import android.view.Display
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger

/** android.hardware.display.DisplayManager: the default display plus virtual displays the app creates. */
open class DisplayManager internal constructor() {
    open fun getDisplay(displayId: Int): Display? = if (displayId == Display.DEFAULT_DISPLAY) defaultDisplay else null
    open fun getDisplays(): Array<Display> = arrayOf(defaultDisplay)

    open fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int, surface: Surface?, flags: Int): VirtualDisplay? {
        require(width > 0 && height > 0 && densityDpi > 0) { "width, height, and densityDpi must be greater than 0" }
        return VirtualDisplay(Display(nextId.getAndIncrement(), name, width, height, densityDpi), surface)
    }

    companion object {
        const val DISPLAY_CATEGORY_PRESENTATION = "android.hardware.display.category.PRESENTATION"
        const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1
        const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 2
        const val VIRTUAL_DISPLAY_FLAG_SECURE = 4
        const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 8
        const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 16

        private val nextId = AtomicInteger(2)
        private val defaultDisplay by lazy {
            val m = android.content.res.Resources.getSystem().getDisplayMetrics()
            Display(Display.DEFAULT_DISPLAY, "Built-in Screen", m.widthPixels, m.heightPixels, m.densityDpi)
        }

        internal val instance by lazy { DisplayManager() }
    }
}
