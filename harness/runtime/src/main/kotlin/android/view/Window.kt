package android.view

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.FrameLayout

/** android.view.Window: attributes and a content view, kept but never drawn (a Dialog's window, see android.app.Dialog). */
open class Window(private val context: Context) {
    private var attributes = WindowManager.LayoutParams()
    private val content = FrameLayout(context)
    private var background: Drawable? = null

    fun getContext(): Context = context

    open fun getAttributes(): WindowManager.LayoutParams = attributes
    open fun setAttributes(a: WindowManager.LayoutParams) {
        attributes = a
    }

    open fun addFlags(flags: Int) = setFlags(flags, flags)
    open fun clearFlags(flags: Int) = setFlags(0, flags)
    open fun setFlags(flags: Int, mask: Int) {
        attributes.flags = (attributes.flags and mask.inv()) or (flags and mask)
    }

    open fun setLayout(width: Int, height: Int) {
        attributes.width = width
        attributes.height = height
    }

    open fun setGravity(gravity: Int) {
        attributes.gravity = gravity
    }

    open fun setDimAmount(amount: Float) {
        attributes.dimAmount = amount
    }

    open fun setBackgroundDrawable(drawable: Drawable?) {
        background = drawable
    }

    /** The view goes into the window's content frame, so its parent is that frame, as on Android. */
    open fun setContentView(view: View) =
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    open fun setContentView(view: View, params: ViewGroup.LayoutParams) {
        content.removeAllViews()
        content.addView(view, params)
    }

    open fun getDecorView(): View = content
}
