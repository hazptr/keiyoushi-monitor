package android.graphics

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
 * Only what Canvas.drawRect/drawBitmap, android.text.StaticLayout and the extensions read: color,
 * style, basic stroke/alpha, textSize and typeface (textAlign is stored, unused).
 */
open class Paint @JvmOverloads constructor(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }

    var color: Int = Color.BLACK
    var style: Style = Style.FILL
    var isAntiAlias: Boolean = flags and ANTI_ALIAS_FLAG != 0
    var alpha: Int = 255
    var strokeWidth: Float = 0f
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

    constructor(other: Paint) : this(0) {
        set(other)
    }

    fun set(src: Paint) {
        color = src.color
        style = src.style
        isAntiAlias = src.isAntiAlias
        alpha = src.alpha
        strokeWidth = src.strokeWidth
        colorFilterValue = src.colorFilterValue
        typefaceValue = src.typefaceValue
        textSize = src.textSize
        textAlign = src.textAlign
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}
