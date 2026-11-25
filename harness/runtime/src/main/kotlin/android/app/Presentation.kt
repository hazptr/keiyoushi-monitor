package android.app

import android.content.Context
import android.view.Display

/** android.app.Presentation: a Dialog shown on another display (see Dialog: showing draws nothing in ext-run). */
open class Presentation @JvmOverloads constructor(outerContext: Context, private val display: Display, theme: Int = 0) : Dialog(outerContext, theme) {
    open fun getDisplay(): Display = display
    open fun getResources(): android.content.res.Resources = getContext().getResources()
}
