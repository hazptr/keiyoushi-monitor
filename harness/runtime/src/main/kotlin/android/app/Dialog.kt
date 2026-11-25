package android.app

import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window

/**
 * android.app.Dialog: a window over an Activity. Showing it draws nothing (a WebView in it is the sidecar's browser
 * window), but show/dismiss/cancel keep Android's state and listeners: the listeners run as messages on the main
 * thread, and dismiss() off it is posted there.
 */
open class Dialog @JvmOverloads constructor(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") themeResId: Int = 0,
) : DialogInterface {
    private val handler = Handler() // as Android's: needs the main thread
    private val window = Window(context)
    private var showing = false
    private var hidden = false
    private var canceled = false
    private var onCancel: DialogInterface.OnCancelListener? = null
    private var onDismiss: DialogInterface.OnDismissListener? = null
    private var onShow: DialogInterface.OnShowListener? = null
    private var contentView: View? = null
    private var held: keiyoushi.extrun.Hold.Handle? = null // ext-run: --pause announced this dialog's WebView

    private fun android.view.ViewGroup.findWebView(): android.webkit.WebView? {
        for (i in 0 until getChildCount()) {
            when (val c = getChildAt(i)) {
                is android.webkit.WebView -> return c
                is android.view.ViewGroup -> c.findWebView()?.let { return it }
            }
        }
        return null
    }

    fun getContext(): Context = context
    fun getWindow(): Window? = window
    open fun isShowing(): Boolean = showing && !hidden

    open fun setContentView(view: View) {
        contentView = view
        window.setContentView(view)
    }

    open fun setContentView(view: View, params: android.view.ViewGroup.LayoutParams?) {
        contentView = view
        if (params != null) window.setContentView(view, params) else window.setContentView(view)
    }

    // Nobody presses back or taps outside in ext-run
    open fun setCancelable(flag: Boolean) {}
    open fun setCanceledOnTouchOutside(cancel: Boolean) {}

    open fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) {
        onCancel = listener
    }

    open fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        onDismiss = listener
    }

    open fun setOnShowListener(listener: DialogInterface.OnShowListener?) {
        onShow = listener
    }

    open fun show() {
        if (showing) {
            hidden = false
            return
        }
        canceled = false
        showing = true
        hidden = false
        val content = contentView?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        System.err.println("  ! app: a Dialog$content is showing over the app's screen; in the app the user sees it")
        // ext-run: with --pause a WebView shown to the user can be driven while the source waits (`ext-run cdp`).
        (contentView as? android.webkit.WebView ?: (contentView as? android.view.ViewGroup)?.findWebView())
            ?.takeIf { keiyoushi.extrun.Hold.pause && held == null }
            ?.let { held = keiyoushi.extrun.Hold.announce(it, "a source's WebView is shown to the user", waits = false) }
        onShow?.let { l -> handler.post { l.onShow(this) } }
    }

    open fun hide() {
        hidden = true
    }

    override fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) dismissDialog() else handler.post(::dismissDialog)
    }

    private fun dismissDialog() {
        if (!showing) return
        showing = false
        hidden = false
        held?.let(keiyoushi.extrun.Hold::release)
        held = null
        onDismiss?.let { l -> handler.post { l.onDismiss(this) } }
    }

    override fun cancel() {
        if (!canceled) {
            canceled = true
            onCancel?.let { l -> handler.post { l.onCancel(this) } }
        }
        dismiss()
    }
}
