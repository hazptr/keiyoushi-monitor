package android.app

import android.content.Context

/**
 * android.app.ActivityThread, the part code reads by reflection to find the resumed Activity: `currentActivityThread()`
 * and `mActivities`, whose records have `activity` and `paused`. Lifecycle calls must run on the main thread.
 */
class ActivityThread private constructor() {
    // Android: ArrayMap<IBinder, ActivityClientRecord>, keyed by the Activity's token
    private val mActivities = LinkedHashMap<Any, ActivityClientRecord>()

    class ActivityClientRecord internal constructor(@JvmField val activity: Activity) {
        @JvmField var paused = true

        @JvmField var stopped = true
    }

    /** Creates, starts and resumes an Activity of [application], as when the user opens the app. */
    internal fun launchActivity(application: Application, base: Context): Activity {
        val activity = Activity().apply { attach(application, base) }
        val record = ActivityClientRecord(activity)
        mActivities[Any()] = record
        activity.performCreate()
        activity.performStart()
        record.stopped = false
        activity.performResume()
        record.paused = false
        return activity
    }

    companion object {
        private val current = ActivityThread()

        @JvmStatic fun currentActivityThread(): ActivityThread = current
    }
}
