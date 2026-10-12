package app.cash.quickjs

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.Closeable

// JVM stand-in for zhanghai/quickjs-java's QuickJs (the app ships the Android build), backed by
// GraalJS. Values convert as QuickJS-java does: string, Integer/Double, Boolean, null, Object[].
// `compile` returns the source as "bytecode"; `set`/`get` (interface proxies) are not supported.
class QuickJs private constructor() : Closeable {
    private val context: Context = Context.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    @JvmOverloads
    fun evaluate(script: String, fileName: String = "?"): Any? =
        convert(context.eval(Source.newBuilder("js", script, fileName).buildLiteral()))

    fun compile(sourceCode: String, fileName: String): ByteArray = sourceCode.toByteArray()

    fun execute(bytecode: ByteArray): Any? = evaluate(String(bytecode))

    fun <T> set(name: String, type: Class<T>, obj: T): Unit =
        throw UnsupportedOperationException("ext-run QuickJs: set() is not supported")

    fun <T> get(name: String, type: Class<T>): T =
        throw UnsupportedOperationException("ext-run QuickJs: get() is not supported")

    override fun close() = context.close()

    private fun convert(value: Value): Any? = when {
        value.isNull -> null
        value.isString -> value.asString()
        value.isBoolean -> value.asBoolean()
        value.isNumber -> if (value.fitsInInt()) value.asInt() else value.asDouble()
        value.hasArrayElements() -> Array(value.arraySize.toInt()) { convert(value.getArrayElement(it.toLong())) }
        else -> null
    }

    companion object {
        init {
            // The fat-jar classpath has no Truffle attach library; the interpreter is enough here.
            System.setProperty("polyglotimpl.AttachLibraryFailureAction", "ignore")
        }

        @JvmStatic
        fun create(): QuickJs = QuickJs()
    }
}
