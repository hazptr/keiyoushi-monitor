package android.os

import keiyoushi.extrun.MainThread
import java.util.concurrent.ScheduledExecutorService

/**
 * The main looper, plus one per started [HandlerThread]. Other threads have none, as on Android without
 * `Looper.prepare()`. Each looper runs its messages on one thread ([executor]).
 */
class Looper internal constructor(private val owner: () -> Thread, internal val executor: ScheduledExecutorService) {
    fun getThread(): Thread = owner()
    fun isCurrentThread(): Boolean = Thread.currentThread() === owner()
    fun quit() {
        if (this !== MAIN) executor.shutdownNow()
    }

    fun quitSafely() {
        if (this !== MAIN) executor.shutdown()
    }

    companion object {
        private val MAIN = Looper({ MainThread.thread }, MainThread.executor)
        private val current = ThreadLocal<Looper>()

        @JvmStatic fun getMainLooper(): Looper = MAIN

        @JvmStatic fun myLooper(): Looper? = if (MainThread.isCurrent()) MAIN else current.get()

        internal fun bind(looper: Looper) = current.set(looper)

        @JvmStatic fun prepare(): Unit = throw UnsupportedOperationException("ext-run: only the main Looper and HandlerThread loopers exist")
    }
}
