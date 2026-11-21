package android.view

import android.content.Context

/** android.view.ViewGroup: children with their layout params. A view is in at most one parent, as on Android. */
abstract class ViewGroup @JvmOverloads constructor(context: Context?, attrs: Any? = null) : View(context, attrs), ViewParent {
    private val children = ArrayList<View>()

    open fun addView(child: View) = addView(child, -1)

    open fun addView(child: View, index: Int) =
        addView(child, index, child.getLayoutParams() ?: generateDefaultLayoutParams())

    open fun addView(child: View, width: Int, height: Int) =
        addView(child, -1, generateDefaultLayoutParams().apply { this.width = width; this.height = height })

    open fun addView(child: View, params: LayoutParams) = addView(child, -1, params)

    open fun addView(child: View, index: Int, params: LayoutParams) {
        check(child.mParent == null) {
            "The specified child already has a parent. You must call removeView() on the child's parent first."
        }
        child.setLayoutParams(params)
        if (index < 0) children.add(child) else children.add(index, child)
        child.mParent = this
    }

    open fun removeView(view: View) {
        if (children.remove(view)) view.mParent = null
    }

    open fun removeViewAt(index: Int) = removeView(children[index])

    open fun removeAllViews() {
        children.forEach { it.mParent = null }
        children.clear()
    }

    open fun getChildCount(): Int = children.size
    open fun getChildAt(index: Int): View? = children.getOrNull(index)
    open fun indexOfChild(child: View): Int = children.indexOf(child)

    protected open fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    open class LayoutParams(@JvmField var width: Int, @JvmField var height: Int) {
        constructor(source: LayoutParams) : this(source.width, source.height)

        companion object {
            const val MATCH_PARENT = -1

            @Deprecated("Use MATCH_PARENT")
            const val FILL_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    open class MarginLayoutParams(width: Int, height: Int) : LayoutParams(width, height) {
        @JvmField var leftMargin = 0
        @JvmField var topMargin = 0
        @JvmField var rightMargin = 0
        @JvmField var bottomMargin = 0

        constructor(source: LayoutParams) : this(source.width, source.height)

        open fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
            leftMargin = left
            topMargin = top
            rightMargin = right
            bottomMargin = bottom
        }
    }
}
