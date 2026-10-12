package android.text

import android.graphics.Canvas
import android.graphics.Typeface
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Text layout drawn with java.awt (LineBreakMeasurer for line breaks, the paint's size, bold
 * typeface and color). Line height follows Android's StaticLayout: (descent - ascent) * spacingMult
 * + spacingAdd per line. Fonts differ from a phone's (no Roboto/Noto here), so wrap points and the
 * exact height differ by a few pixels; the image is still the right shape and contains the text.
 */
abstract class Layout protected constructor(
    val text: CharSequence,
    val paint: TextPaint,
    val width: Int,
    val alignment: Alignment,
    val spacingMultiplier: Float,
    val spacingAdd: Float,
) {
    enum class Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }

    private class Line(val layout: TextLayout?, val top: Float, val baseline: Float, val bottom: Float)

    private val lines: List<Line> by lazy { breakLines() }

    internal open val includePad: Boolean get() = true

    private fun font(): Font {
        val base = paint.getTypeface()?.awtFont ?: Font(Font.SANS_SERIF, Font.PLAIN, 1)
        val style = if (paint.getTypeface() === Typeface.DEFAULT_BOLD || base.isBold) Font.BOLD else Font.PLAIN
        val family = when (paint.getTypeface()) {
            Typeface.MONOSPACE -> Font(Font.MONOSPACED, style, 1)
            Typeface.SERIF -> Font(Font.SERIF, style, 1)
            else -> base.deriveFont(style)
        }
        return family.deriveFont(paint.textSize)
    }

    private fun breakLines(): List<Line> {
        val frc = FontRenderContext(null, true, true)
        val font = font()
        val metrics = font.getLineMetrics("Ag", frc)
        val lineHeight = (metrics.ascent + metrics.descent) * spacingMultiplier + spacingAdd
        val out = mutableListOf<Line>()
        var y = 0f
        for (paragraph in text.toString().split('\n')) {
            if (paragraph.isEmpty()) {
                out += Line(null, y, y + metrics.ascent, y + lineHeight)
                y += lineHeight
                continue
            }
            val attr = AttributedString(paragraph).apply { addAttribute(TextAttribute.FONT, font) }
            val measurer = LineBreakMeasurer(attr.iterator, frc)
            while (measurer.position < paragraph.length) {
                val tl = measurer.nextLayout(width.toFloat().coerceAtLeast(1f))
                out += Line(tl, y, y + tl.ascent, y + lineHeight)
                y += lineHeight
            }
        }
        // Android's StaticLayout doesn't add the extra spacing below the last line.
        return out
    }

    open fun getHeight(): Int {
        val last = lines.lastOrNull() ?: return 0
        return ceil(last.bottom - (spacingAdd)).roundToInt().coerceAtLeast(0)
    }

    fun getLineCount(): Int = lines.size
    fun getLineTop(line: Int): Int = if (line >= lines.size) getHeight() else lines[line].top.roundToInt()
    fun getLineBottom(line: Int): Int = lines[line].bottom.roundToInt()
    fun getLineBaseline(line: Int): Int = lines[line].baseline.roundToInt()
    fun getLineWidth(line: Int): Float = lines[line].layout?.advance ?: 0f
    fun getLineMax(line: Int): Float = getLineWidth(line)

    fun draw(canvas: Canvas) {
        val g = canvas.g
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, if (paint.isAntiAlias) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        g.color = java.awt.Color(paint.color, true)
        for (line in lines) {
            val tl = line.layout ?: continue
            val x = when (alignment) {
                Alignment.ALIGN_NORMAL -> 0f
                Alignment.ALIGN_OPPOSITE -> width - tl.advance
                Alignment.ALIGN_CENTER -> (width - tl.advance) / 2f
            }
            tl.draw(g, x, line.baseline)
        }
    }
}

class StaticLayout internal constructor(
    source: CharSequence,
    paint: TextPaint,
    width: Int,
    align: Alignment,
    spacingMult: Float,
    spacingAdd: Float,
    override val includePad: Boolean,
    @Suppress("UNUSED_PARAMETER") viaBuilder: Unit,
) : Layout(source, paint, width, align, spacingMult, spacingAdd) {

    @Deprecated("Use StaticLayout.Builder")
    constructor(
        source: CharSequence,
        paint: TextPaint,
        width: Int,
        align: Alignment,
        spacingmult: Float,
        spacingadd: Float,
        includepad: Boolean,
    ) : this(source, paint, width, align, spacingmult, spacingadd, includepad, Unit)

    class Builder private constructor(
        private val source: CharSequence,
        private val start: Int,
        private val end: Int,
        private val paint: TextPaint,
        private val width: Int,
    ) {
        private var alignment = Alignment.ALIGN_NORMAL
        private var mult = 1f
        private var add = 0f
        private var includePad = true

        fun setAlignment(alignment: Alignment) = apply { this.alignment = alignment }
        fun setLineSpacing(spacingAdd: Float, spacingMult: Float) = apply { add = spacingAdd; mult = spacingMult }
        fun setIncludePad(includePad: Boolean) = apply { this.includePad = includePad }
        fun setMaxLines(maxLines: Int) = this
        fun setEllipsize(where: Any?) = this
        fun setBreakStrategy(breakStrategy: Int) = this
        fun setHyphenationFrequency(hyphenationFrequency: Int) = this
        fun setJustificationMode(justificationMode: Int) = this
        fun setUseLineSpacingFromFallbacks(useLineSpacingFromFallbacks: Boolean) = this
        fun setTextDirection(textDir: Any?) = this
        fun build(): StaticLayout = StaticLayout(source.subSequence(start, end), paint, width, alignment, mult, add, includePad, Unit)

        companion object {
            @JvmStatic
            fun obtain(source: CharSequence, start: Int, end: Int, paint: TextPaint, width: Int): Builder = Builder(source, start, end, paint, width)
        }
    }
}
