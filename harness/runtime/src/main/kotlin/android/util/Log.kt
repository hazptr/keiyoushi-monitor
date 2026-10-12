package android.util

// Logs go to stderr so stdout stays clean for results.
object Log {
    var quiet = false

    private fun out(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
        if (!quiet || level == "E" || level == "W") {
            System.err.println("[$level/$tag] $msg")
            tr?.printStackTrace()
        }
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String): Int = out("V", tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = out("V", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String): Int = out("D", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = out("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String): Int = out("I", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = out("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String): Int = out("W", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = out("W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = out("W", tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String): Int = out("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = out("E", tag, msg, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = out("E", tag, msg)
    @JvmStatic fun println(priority: Int, tag: String?, msg: String): Int =
        out(listOf("V", "D", "I", "W", "E", "A").getOrElse(priority - 2) { "I" }, tag, msg)
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = true
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString().orEmpty()
}
