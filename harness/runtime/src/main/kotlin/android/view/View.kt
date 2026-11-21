package android.view

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * android.view.View as a box in a hierarchy: parent, layout params, measured and laid-out size, translation,
 * visibility. Nothing is drawn; a WebView's pixels live in the browser sidecar.
 */
open class View @JvmOverloads constructor(
    private val mContext: Context?,
    @Suppress("UNUSED_PARAMETER") attrs: Any? = null,
) {
    internal var mParent: ViewParent? = null
    private var layoutParams: ViewGroup.LayoutParams? = null
    private var measuredWidth = 0
    private var measuredHeight = 0
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var translationX = 0f
    private var translationY = 0f
    private var alpha = 1f
    private var visibility = VISIBLE
    private var importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var background: Int? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun getContext(): Context? = mContext
    open fun getParent(): ViewParent? = mParent

    open fun getLayoutParams(): ViewGroup.LayoutParams? = layoutParams
    open fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        requireNotNull(params) { "Layout parameters cannot be null" }
        layoutParams = params
    }

    fun measure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
    }

    fun getMeasuredWidth(): Int = measuredWidth
    fun getMeasuredHeight(): Int = measuredHeight

    open fun layout(l: Int, t: Int, r: Int, b: Int) {
        left = l
        top = t
        right = r
        bottom = b
    }

    fun getLeft(): Int = left
    fun getTop(): Int = top
    fun getRight(): Int = right
    fun getBottom(): Int = bottom
    fun getWidth(): Int = right - left
    fun getHeight(): Int = bottom - top

    open fun getTranslationX(): Float = translationX
    open fun setTranslationX(value: Float) {
        translationX = value
    }

    open fun getTranslationY(): Float = translationY
    open fun setTranslationY(value: Float) {
        translationY = value
    }

    open fun getAlpha(): Float = alpha
    open fun setAlpha(value: Float) {
        alpha = value
    }

    open fun getVisibility(): Int = visibility
    open fun setVisibility(value: Int) {
        visibility = value
    }

    open fun getImportantForAccessibility(): Int = importantForAccessibility
    open fun setImportantForAccessibility(mode: Int) {
        importantForAccessibility = mode
    }

    open fun setBackgroundColor(color: Int) {
        background = color
    }

    open fun requestLayout() {}
    open fun invalidate() {}

    open fun post(action: Runnable): Boolean = handler.post(action)
    open fun postDelayed(action: Runnable, delayMillis: Long): Boolean = handler.postDelayed(action, delayMillis)
    open fun removeCallbacks(action: Runnable): Boolean = true.also { handler.removeCallbacks(action) }

    class MeasureSpec {
        companion object {
            private const val MODE_SHIFT = 30
            private const val MODE_MASK = 0x3 shl MODE_SHIFT
            const val UNSPECIFIED = 0
            const val EXACTLY = 1 shl MODE_SHIFT
            const val AT_MOST = 2 shl MODE_SHIFT

            @JvmStatic fun makeMeasureSpec(size: Int, mode: Int): Int = (size and MODE_MASK.inv()) or (mode and MODE_MASK)

            @JvmStatic fun getMode(measureSpec: Int): Int = measureSpec and MODE_MASK

            @JvmStatic fun getSize(measureSpec: Int): Int = measureSpec and MODE_MASK.inv()
        }
    }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0
        const val IMPORTANT_FOR_ACCESSIBILITY_YES = 1
        const val IMPORTANT_FOR_ACCESSIBILITY_NO = 2
        const val IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4
    }
}
