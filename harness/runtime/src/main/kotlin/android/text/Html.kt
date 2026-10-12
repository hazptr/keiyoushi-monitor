package android.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * android.text.Html, characters only (no spans), following AOSP's HtmlToSpannedConverter
 * (frameworks/base/core/java/android/text/Html.java):
 * - whitespace runs in text collapse to one space, and a space right after a newline (or at the start) is dropped;
 * - `<br>` is a newline;
 * - block elements (p, div, ul, li, blockquote, h1-h6) make sure the text ends with N newlines before
 *   and after them, N = 2 in FROM_HTML_MODE_LEGACY, 1 when the matching FROM_HTML_SEPARATOR_LINE_BREAK_* flag is set
 *   (and for li, a bullet span only, no characters);
 * - `<img>` becomes U+FFFC (the object replacement character);
 * - script/style/head content is dropped, entities are decoded.
 */
object Html {
    const val FROM_HTML_MODE_LEGACY = 0x00000000
    const val FROM_HTML_MODE_COMPACT = 0x0000003f
    const val FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH = 0x00000001
    const val FROM_HTML_SEPARATOR_LINE_BREAK_HEADING = 0x00000002
    const val FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM = 0x00000004
    const val FROM_HTML_SEPARATOR_LINE_BREAK_LIST = 0x00000008
    const val FROM_HTML_SEPARATOR_LINE_BREAK_DIV = 0x00000010
    const val FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE = 0x00000020
    const val FROM_HTML_OPTION_USE_CSS_COLORS = 0x00000100
    const val TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0x00000000
    const val TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 0x00000001

    @Deprecated("Use fromHtml(String, Int)")
    @JvmStatic
    fun fromHtml(source: String): Spanned = fromHtml(source, FROM_HTML_MODE_LEGACY)

    @JvmStatic
    fun fromHtml(source: String, flags: Int): Spanned = SpannedString(Converter(flags).convert(source))

    @JvmStatic
    fun fromHtml(source: String, flags: Int, imageGetter: Any?, tagHandler: Any?): Spanned = fromHtml(source, flags)

    @JvmStatic
    fun escapeHtml(text: CharSequence): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '&' -> append("&amp;")
                c.isSurrogate() && i + 1 < text.length -> {
                    append("&#").append(Character.toCodePoint(c, text[i + 1])).append(";")
                    i++
                }
                c.code > 0x7E || c < ' ' -> append("&#").append(c.code).append(";")
                c == ' ' -> {
                    while (i + 1 < text.length && text[i + 1] == ' ') {
                        append("&nbsp;")
                        i++
                    }
                    append(' ')
                }
                else -> append(c)
            }
            i++
        }
    }

    private class Converter(private val flags: Int) {
        private val sb = StringBuilder()

        private fun margin(flag: Int) = if (flags and flag != 0) 1 else 2

        fun convert(source: String): String {
            walk(Jsoup.parseBodyFragment(source).body())
            return sb.toString()
        }

        private fun walk(node: Node) {
            for (child in node.childNodes()) {
                when (child) {
                    is TextNode -> text(child.wholeText)
                    is Element -> element(child)
                }
            }
        }

        private fun element(e: Element) {
            val block = when (e.normalName()) {
                "br" -> {
                    sb.append('\n')
                    return
                }
                "img" -> {
                    sb.append('￼')
                    return
                }
                "script", "style", "head", "title" -> return
                "p" -> margin(FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH)
                "div" -> margin(FROM_HTML_SEPARATOR_LINE_BREAK_DIV)
                "ul" -> margin(FROM_HTML_SEPARATOR_LINE_BREAK_LIST)
                "li" -> margin(FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM)
                "blockquote" -> margin(FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE)
                "h1", "h2", "h3", "h4", "h5", "h6" -> margin(FROM_HTML_SEPARATOR_LINE_BREAK_HEADING)
                else -> 0
            }
            if (block > 0) appendNewlines(block)
            walk(e)
            if (block > 0) appendNewlines(block)
        }

        // AOSP appendNewlines: only if there is text, and only up to `count` trailing newlines.
        private fun appendNewlines(count: Int) {
            if (sb.isEmpty()) return
            var existing = 0
            var i = sb.length - 1
            while (i >= 0 && existing < count && sb[i] == '\n') {
                existing++
                i--
            }
            repeat(count - existing) { sb.append('\n') }
        }

        // AOSP handleCharacters: collapse ' ' and '\n' (and other whitespace) runs.
        private fun text(s: String) {
            for (c in s) {
                if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                    val prev = if (sb.isEmpty()) '\n' else sb[sb.length - 1]
                    if (prev != ' ' && prev != '\n') sb.append(' ')
                } else {
                    sb.append(c)
                }
            }
        }
    }
}
