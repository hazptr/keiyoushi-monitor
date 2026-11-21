package android.app

import android.content.ContextWrapper
import android.os.Bundle

/** android.app.Application: a ContextWrapper (its base is attached by the app, here HarnessApplication) with Activity lifecycle callbacks. */
open class Application : ContextWrapper(null) {
    private val activityLifecycleCallbacks = ArrayList<ActivityLifecycleCallbacks>()

    open fun onCreate() {}

    fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
        synchronized(activityLifecycleCallbacks) { activityLifecycleCallbacks.add(callback) }
    }

    fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
        synchronized(activityLifecycleCallbacks) { activityLifecycleCallbacks.remove(callback) }
    }

    // As on Android, each dispatch walks a copy, so a callback may unregister itself.
    private fun callbacks(): Array<ActivityLifecycleCallbacks> =
        synchronized(activityLifecycleCallbacks) { activityLifecycleCallbacks.toTypedArray() }

    internal fun dispatchActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
        callbacks().forEach { it.onActivityCreated(activity, savedInstanceState) }

    internal fun dispatchActivityStarted(activity: Activity) = callbacks().forEach { it.onActivityStarted(activity) }
    internal fun dispatchActivityResumed(activity: Activity) = callbacks().forEach { it.onActivityResumed(activity) }
    internal fun dispatchActivityPaused(activity: Activity) = callbacks().forEach { it.onActivityPaused(activity) }
    internal fun dispatchActivityStopped(activity: Activity) = callbacks().forEach { it.onActivityStopped(activity) }
    internal fun dispatchActivityDestroyed(activity: Activity) = callbacks().forEach { it.onActivityDestroyed(activity) }

    interface ActivityLifecycleCallbacks {
        fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?)
        fun onActivityStarted(activity: Activity)
        fun onActivityResumed(activity: Activity)
        fun onActivityPaused(activity: Activity)
        fun onActivityStopped(activity: Activity)
        fun onActivitySaveInstanceState(activity: Activity, outState: Bundle)
        fun onActivityDestroyed(activity: Activity)
    }
}
