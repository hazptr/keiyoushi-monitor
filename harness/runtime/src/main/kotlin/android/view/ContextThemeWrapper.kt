package android.view

import android.content.Context
import android.content.ContextWrapper

open class ContextThemeWrapper(base: Context?, private val themeResId: Int) : ContextWrapper(base) {
    constructor() : this(null, 0)
}
