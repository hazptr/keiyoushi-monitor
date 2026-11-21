package android.icu.text

/** android.icu.text.RuleBasedCollator over ICU4J's; see [Collator]. */
class RuleBasedCollator internal constructor(private val rules: com.ibm.icu.text.RuleBasedCollator) : Collator(rules) {

    constructor(rules: String) : this(com.ibm.icu.text.RuleBasedCollator(rules))

    var numericCollation: Boolean
        get() = rules.numericCollation
        set(value) {
            rules.numericCollation = value
        }

    var isCaseLevel: Boolean
        get() = rules.isCaseLevel
        set(value) {
            rules.isCaseLevel = value
        }

    fun getRules(): String = rules.rules

    override fun freeze(): Collator {
        rules.freeze()
        return this
    }

    override fun cloneAsThawed(): Collator = RuleBasedCollator(rules.cloneAsThawed())

    override fun clone(): Any = RuleBasedCollator(rules.clone() as com.ibm.icu.text.RuleBasedCollator)
}
