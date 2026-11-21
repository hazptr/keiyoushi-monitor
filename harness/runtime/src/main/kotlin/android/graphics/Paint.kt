package android.graphics

import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

open class ColorFilter

open class ColorMatrix(val values: FloatArray = IDENTITY.copyOf()) {
    companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}

/** Stored, not applied: no image renders through a ColorMatrix in this harness. */
class ColorMatrixColorFilter(val matrix: ColorMatrix) : ColorFilter() {
    constructor(array: FloatArray) : this(ColorMatrix(array))
}

/**
 * What Canvas, android.text.StaticLayout and the extensions read: flags (anti-alias, bitmap
 * filtering, dither), color/alpha (one ARGB int, as on Android: setAlpha changes the color's alpha),
 * style and stroke (width, cap, join, miter), text size/typeface/align/scale, and text measuring
 * with AWT fonts. Canvas.drawBitmap honours isFilterBitmap (bilinear vs nearest) and alpha.
 */
open class Paint @JvmOverloads constructor(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }

    /** Android's Paint.FontMetrics: public float fields, negative above the baseline. */
    open class FontMetrics {
        @JvmField var top: Float = 0f
        @JvmField var ascent: Float = 0f
        @JvmField var descent: Float = 0f
        @JvmField var bottom: Float = 0f
        @JvmField var leading: Float = 0f
    }

    // As on Android (API 28+): every Paint also carries the hidden defaults, FILTER_BITMAP_FLAG among them.
    var flags: Int = flags or HIDDEN_DEFAULT_PAINT_FLAGS
    var color: Int = Color.BLACK
    var style: Style = Style.FILL
    var isAntiAlias: Boolean
        get() = flags and ANTI_ALIAS_FLAG != 0
        set(v) = setFlag(ANTI_ALIAS_FLAG, v)
    var isFilterBitmap: Boolean
        get() = flags and FILTER_BITMAP_FLAG != 0
        set(v) = setFlag(FILTER_BITMAP_FLAG, v)
    var isDither: Boolean
        get() = flags and DITHER_FLAG != 0
        set(v) = setFlag(DITHER_FLAG, v)
    var isFakeBoldText: Boolean
        get() = flags and FAKE_BOLD_TEXT_FLAG != 0
        set(v) = setFlag(FAKE_BOLD_TEXT_FLAG, v)
    var alpha: Int
        get() = color ushr 24
        set(v) {
            color = (color and 0x00FFFFFF) or (v.coerceIn(0, 255) shl 24)
        }
    var strokeWidth: Float = 0f
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var strokeMiter: Float = 4f

    // Android's setColorFilter/setTypeface return their argument (compiled extensions call the
    // Typeface-returning setter), so these two are Java-style methods, not Kotlin properties.
    private var colorFilterValue: ColorFilter? = null
    private var typefaceValue: Typeface? = null
    fun getColorFilter(): ColorFilter? = colorFilterValue
    fun setColorFilter(filter: ColorFilter?): ColorFilter? = filter.also { colorFilterValue = it }
    fun getTypeface(): Typeface? = typefaceValue
    fun setTypeface(typeface: Typeface?): Typeface? = typeface.also { typefaceValue = it }
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var textScaleX: Float = 1f

    constructor(other: Paint) : this(0) {
        set(other)
    }

    private fun setFlag(flag: Int, on: Boolean) {
        flags = if (on) flags or flag else flags and flag.inv()
    }

    fun setARGB(a: Int, r: Int, g: Int, b: Int) {
        color = Color.argb(a, r, g, b)
    }

    fun set(src: Paint) {
        flags = src.flags
        color = src.color
        style = src.style
        strokeWidth = src.strokeWidth
        strokeCap = src.strokeCap
        strokeJoin = src.strokeJoin
        strokeMiter = src.strokeMiter
        colorFilterValue = src.colorFilterValue
        typefaceValue = src.typefaceValue
        textSize = src.textSize
        textAlign = src.textAlign
        textScaleX = src.textScaleX
    }

    fun reset() = set(Paint())

    // ---- text, measured with AWT fonts (glyphs and advances differ from a phone's Roboto/Noto) ----

    /** The AWT font this paint draws text with: typeface family/bold, textSize, textScaleX. */
    internal fun awtFont(): Font {
        val tf = getTypeface()
        val base = tf?.awtFont ?: Font(Font.SANS_SERIF, Font.PLAIN, 1)
        val style = if (tf === Typeface.DEFAULT_BOLD || base.isBold || isFakeBoldText) Font.BOLD else Font.PLAIN
        val family = when (tf) {
            Typeface.MONOSPACE -> Font(Font.MONOSPACED, style, 1)
            Typeface.SERIF -> Font(Font.SERIF, style, 1)
            else -> base.deriveFont(style)
        }
        val sized = family.deriveFont(textSize)
        return if (textScaleX == 1f) sized else sized.deriveFont(AffineTransform.getScaleInstance(textScaleX.toDouble(), 1.0))
    }

    internal fun frc() = FontRenderContext(null, isAntiAlias, true)

    fun measureText(text: String): Float = if (text.isEmpty()) 0f else awtFont().getStringBounds(text, frc()).width.toFloat()
    fun measureText(text: String, start: Int, end: Int): Float = measureText(text.substring(start, end))
    fun measureText(text: CharSequence, start: Int, end: Int): Float = measureText(text.subSequence(start, end).toString())
    fun measureText(text: CharArray, index: Int, count: Int): Float = measureText(String(text, index, count))

    /** Bounds of the drawn glyphs relative to (0, baseline), like Android's (top is negative). */
    fun getTextBounds(text: String, start: Int, end: Int, bounds: Rect) {
        val s = text.substring(start, end)
        if (s.isEmpty()) return bounds.set(0, 0, 0, 0)
        val r = awtFont().createGlyphVector(frc(), s).visualBounds
        bounds.set(Math.floor(r.minX).toInt(), Math.floor(r.minY).toInt(), Math.ceil(r.maxX).toInt(), Math.ceil(r.maxY).toInt())
    }

    fun getTextBounds(text: CharSequence, start: Int, end: Int, bounds: Rect) = getTextBounds(text.toString(), start, end, bounds)

    fun getFontMetrics(): FontMetrics = FontMetrics().also { getFontMetrics(it) }

    /** Fills [metrics] (when given) and returns the recommended line spacing, as Android does. */
    fun getFontMetrics(metrics: FontMetrics?): Float {
        val g = scratch.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            val font = awtFont()
            val lm = font.getLineMetrics("Ag", frc())
            val fm = g.getFontMetrics(font)
            metrics?.apply {
                ascent = -lm.ascent
                descent = lm.descent
                leading = lm.leading
                top = -maxOf(fm.maxAscent.toFloat(), lm.ascent)
                bottom = maxOf(fm.maxDescent.toFloat(), lm.descent)
            }
            return lm.ascent + lm.descent + lm.leading
        } finally {
            g.dispose()
        }
    }

    val fontSpacing: Float get() = getFontMetrics(null)

    fun ascent(): Float = getFontMetrics().ascent
    fun descent(): Float = getFontMetrics().descent

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
        const val DITHER_FLAG = 4
        const val UNDERLINE_TEXT_FLAG = 8
        const val STRIKE_THRU_TEXT_FLAG = 16
        const val FAKE_BOLD_TEXT_FLAG = 32
        const val LINEAR_TEXT_FLAG = 64
        const val SUBPIXEL_TEXT_FLAG = 128
        const val DEV_KERN_TEXT_FLAG = 256
        const val EMBEDDED_BITMAP_TEXT_FLAG = 1024

        // AOSP Paint.HIDDEN_DEFAULT_PAINT_FLAGS (FILTER_BITMAP_FLAG is on by default since API 28).
        private const val HIDDEN_DEFAULT_PAINT_FLAGS = DEV_KERN_TEXT_FLAG or EMBEDDED_BITMAP_TEXT_FLAG or FILTER_BITMAP_FLAG

        private val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    }
}
