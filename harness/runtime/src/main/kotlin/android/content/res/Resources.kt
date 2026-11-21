package android.content.res

import android.util.DisplayMetrics

// Minimal JVM shim of android.content.res.Resources: the system display metrics and configuration.
class Resources private constructor() {
    private val metrics = DisplayMetrics()
    private val configuration = Configuration()

    fun getDisplayMetrics(): DisplayMetrics = metrics
    fun getConfiguration(): Configuration = configuration

    companion object {
        private val system = Resources()

        @JvmStatic fun getSystem(): Resources = system
    }
}
