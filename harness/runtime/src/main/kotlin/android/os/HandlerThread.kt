package android.os

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * android.os.HandlerThread: a thread with its own [Looper]; Handlers on that looper run their Runnables on it.
 * Daemon, so a thread the source never quits doesn't keep the run alive.
 */
open class HandlerThread @JvmOverloads constructor(name: String, @Suppress("UNUSED_PARAMETER") priority: Int = 0) : Thread(name) {
    @Volatile private var looper: Looper? = null
    private val ready = CountDownLatch(1)

    init {
        isDaemon = true
    }

    override fun run() {
        lateinit var l: Looper
        var worker: Thread? = null
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread({ Looper.bind(l); r.run() }, name).apply { isDaemon = true }.also { worker = it }
        }
        l = Looper({ worker ?: this }, executor)
        executor.execute {} // start the worker now so getThread() is it
        looper = l
        onLooperPrepared()
        ready.countDown()
        // The looper's queue runs on its worker thread; this thread lives until the looper quits, as on Android.
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) Unit
    }

    protected open fun onLooperPrepared() {}

    /** Blocks until the thread has started its looper; null if the thread isn't alive. */
    open fun getLooper(): Looper? {
        if (!isAlive && looper == null) return null
        ready.await()
        return looper
    }

    open fun quit(): Boolean = looper?.let { it.quit(); true } ?: false
    open fun quitSafely(): Boolean = looper?.let { it.quitSafely(); true } ?: false
    open fun getThreadId(): Int = id.toInt()
}
