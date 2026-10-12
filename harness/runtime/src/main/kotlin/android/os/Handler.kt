package android.os

import keiyoushi.extrun.MainThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Handler on the main looper (the only one). Messages aren't supported, Runnables are. */
open class Handler @JvmOverloads constructor(
    private val looper: Looper = Looper.myLooper() ?: throw RuntimeException("Can't create handler inside thread that has not called Looper.prepare()"),
) {
    private val scheduled = ConcurrentHashMap<Runnable, MutableSet<ScheduledFuture<*>>>()

    fun getLooper(): Looper = looper

    fun post(r: Runnable): Boolean = postDelayed(r, 0)

    fun postAtFrontOfQueue(r: Runnable): Boolean = postDelayed(r, 0)

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        val set = scheduled.computeIfAbsent(r) { ConcurrentHashMap.newKeySet() }
        val holder = arrayOfNulls<ScheduledFuture<*>>(1)
        // Held while scheduling so a zero-delay task can't run before its future is recorded.
        synchronized(holder) {
            holder[0] = MainThread.executor.schedule(
                {
                    synchronized(holder) { set.remove(holder[0]) }
                    MainThread.guard(r).run()
                },
                delayMillis.coerceAtLeast(0),
                TimeUnit.MILLISECONDS,
            )
            set.add(holder[0]!!)
        }
        return true
    }

    fun postAtTime(r: Runnable, uptimeMillis: Long): Boolean = postDelayed(r, uptimeMillis - SystemClock.uptimeMillis())

    fun removeCallbacks(r: Runnable) {
        scheduled.remove(r)?.forEach { it.cancel(false) }
    }

    fun removeCallbacksAndMessages(@Suppress("UNUSED_PARAMETER") token: Any?) {
        scheduled.keys.toList().forEach(::removeCallbacks)
    }
}
