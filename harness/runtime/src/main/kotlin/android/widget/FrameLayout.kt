package android.widget

import android.content.Context
import android.view.ViewGroup

open class FrameLayout @JvmOverloads constructor(context: Context?, attrs: Any? = null) : ViewGroup(context, attrs) {
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    open class LayoutParams @JvmOverloads constructor(
        width: Int,
        height: Int,
        @JvmField var gravity: Int = UNSPECIFIED_GRAVITY,
    ) : ViewGroup.MarginLayoutParams(width, height) {
        constructor(source: ViewGroup.LayoutParams) : this(source.width, source.height)

        companion object {
            const val UNSPECIFIED_GRAVITY = -1
        }
    }
}
