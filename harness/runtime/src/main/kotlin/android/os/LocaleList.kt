package android.os

import java.util.Locale

// The JVM's default locale as the device's only language
class LocaleList(private vararg val locales: Locale) {
    fun size(): Int = locales.size

    operator fun get(index: Int): Locale = locales[index]

    fun toLanguageTags(): String = locales.joinToString(",") { it.toLanguageTag() }

    companion object {
        @JvmStatic
        fun getDefault(): LocaleList = LocaleList(Locale.getDefault())
    }
}
