package android.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper

/**
 * android.app.Activity: a screen of the app. ext-run has one, the app's screen, launched and resumed at start-up
 * by [ActivityThread] so code that looks for the foreground Activity (core's topActivity()) finds it. Nothing is drawn.
 */
open class Activity : ContextThemeWrapper() {
    private var mApplication: Application? = null
    private var mFinished = false
    private var mDestroyed = false

    internal fun attach(application: Application, base: Context) {
        attachBaseContext(base)
        mApplication = application
    }

    fun getApplication(): Application = mApplication ?: throw IllegalStateException("Activity not attached")

    open fun isFinishing(): Boolean = mFinished
    open fun isDestroyed(): Boolean = mDestroyed

    open fun runOnUiThread(action: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else Handler(Looper.getMainLooper()).post(action)
    }

    internal fun performCreate() = getApplication().dispatchActivityCreated(this, null)
    internal fun performStart() = getApplication().dispatchActivityStarted(this)
    internal fun performResume() = getApplication().dispatchActivityResumed(this)
    internal fun performPause() = getApplication().dispatchActivityPaused(this)
    internal fun performStop() = getApplication().dispatchActivityStopped(this)

    internal fun performDestroy() {
        mFinished = true
        mDestroyed = true
        getApplication().dispatchActivityDestroyed(this)
    }
}
