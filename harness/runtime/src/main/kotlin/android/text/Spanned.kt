package android.text

/** Text with markup. ext-run keeps only the characters (no spans): what `.toString()` callers see. */
interface Spanned : CharSequence

open class SpannedString(private val text: CharSequence) : Spanned {
    override val length: Int get() = text.length
    override fun get(index: Int): Char = text[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = SpannedString(text.subSequence(startIndex, endIndex))
    override fun toString(): String = text.toString()
    override fun equals(other: Any?): Boolean = other is SpannedString && other.text.toString() == text.toString()
    override fun hashCode(): Int = text.toString().hashCode()
}

class SpannableString(text: CharSequence) : SpannedString(text)
