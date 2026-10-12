@file:OptIn(InternalCoroutinesApi::class)

package keiyoushi.extrun

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.coroutines.CoroutineContext

/** Stand-in for Android's main thread: one daemon thread, so `Dispatchers.Main` works on the JVM. */
class ExtRunMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority = Int.MAX_VALUE
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = ExtRunMain
}

/** The one "main thread". `Dispatchers.Main` and `Handler(Looper.getMainLooper())` both run here. */
object MainThread {
    @Volatile
    lateinit var thread: Thread
        private set

    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "ext-run-main").apply { isDaemon = true }.also { t -> thread = t }
    }.also { it.execute {} } // start the thread now so `thread` is set

    fun isCurrent(): Boolean = Thread.currentThread() === thread

    /** Runs [block] on the main thread, logging instead of dying on an exception, like an app crash report. */
    fun post(block: Runnable) {
        executor.execute(guard(block))
    }

    fun guard(block: Runnable) = Runnable {
        try {
            block.run()
        } catch (t: Throwable) {
            System.err.println("ext-run: uncaught exception on the main thread (the app would crash):")
            t.printStackTrace()
        }
    }
}

private object ExtRunMain : MainCoroutineDispatcher() {
    private val thread = MainThread.executor.asCoroutineDispatcher()

    override val immediate: MainCoroutineDispatcher get() = this
    override fun dispatch(context: CoroutineContext, block: Runnable) = thread.dispatch(context, block)
    override fun toString() = "ExtRunMain"
}
