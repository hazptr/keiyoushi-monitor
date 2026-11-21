package android.icu.text

import java.util.Locale

/**
 * android.icu.text.Collator over ICU4J's com.ibm.icu.text.Collator (Android's android.icu is ICU4J
 * repackaged). Covers getInstance, strength, decomposition, compare, freeze and clone.
 */
abstract class Collator internal constructor(internal val icu: com.ibm.icu.text.Collator) :
    Comparator<Any?>,
    Cloneable {

    var strength: Int
        get() = icu.strength
        set(value) {
            icu.strength = value
        }

    var decomposition: Int
        get() = icu.decomposition
        set(value) {
            icu.decomposition = value
        }

    fun compare(source: String?, target: String?): Int = icu.compare(source, target)

    override fun compare(source: Any?, target: Any?): Int = icu.compare(source, target)

    fun equals(source: String?, target: String?): Boolean = icu.equals(source, target)

    fun isFrozen(): Boolean = icu.isFrozen

    abstract fun freeze(): Collator

    abstract fun cloneAsThawed(): Collator

    public abstract override fun clone(): Any

    companion object {
        const val PRIMARY = com.ibm.icu.text.Collator.PRIMARY
        const val SECONDARY = com.ibm.icu.text.Collator.SECONDARY
        const val TERTIARY = com.ibm.icu.text.Collator.TERTIARY
        const val QUATERNARY = com.ibm.icu.text.Collator.QUATERNARY
        const val IDENTICAL = com.ibm.icu.text.Collator.IDENTICAL
        const val NO_DECOMPOSITION = com.ibm.icu.text.Collator.NO_DECOMPOSITION
        const val CANONICAL_DECOMPOSITION = com.ibm.icu.text.Collator.CANONICAL_DECOMPOSITION

        @JvmStatic
        fun getInstance(): Collator = getInstance(Locale.getDefault())

        @JvmStatic
        fun getInstance(locale: Locale): Collator = wrap(com.ibm.icu.text.Collator.getInstance(locale))

        internal fun wrap(icu: com.ibm.icu.text.Collator): Collator = RuleBasedCollator(icu as com.ibm.icu.text.RuleBasedCollator)
    }
}
