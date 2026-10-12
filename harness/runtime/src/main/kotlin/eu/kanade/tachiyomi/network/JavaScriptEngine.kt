package eu.kanade.tachiyomi.network

import android.content.Context

// The apps back this with QuickJS; not available on the JVM harness.
class JavaScriptEngine(context: Context) {
    @Suppress("RedundantSuspendModifier")
    suspend fun <T> evaluate(script: String): T = throw UnsupportedOperationException("ext-run: JavaScriptEngine (QuickJS) is not available on the JVM harness")
}
