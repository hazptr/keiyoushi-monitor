package android.os

import keiyoushi.extrun.MainThread

/** Only the main looper exists; other threads have none, as on Android without `Looper.prepare()`. */
class Looper private constructor() {
    fun getThread(): Thread = MainThread.thread
    fun isCurrentThread(): Boolean = MainThread.isCurrent()
    fun quit() {}
    fun quitSafely() {}

    companion object {
        private val MAIN = Looper()

        @JvmStatic fun getMainLooper(): Looper = MAIN

        @JvmStatic fun myLooper(): Looper? = if (MainThread.isCurrent()) MAIN else null

        @JvmStatic fun prepare(): Unit = throw UnsupportedOperationException("ext-run: only the main Looper exists")
    }
}
