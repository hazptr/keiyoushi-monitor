package android.graphics

import java.awt.Font
import java.io.File

/**
 * Identity plus an optional AWT font. android.text.StaticLayout (the shim) draws with it: DEFAULT_BOLD
 * is bold, MONOSPACE/SERIF map to the AWT logical fonts, everything else is AWT's sans-serif.
 */
class Typeface private constructor(val awtFont: Font?) {
    companion object {
        // @JvmField: Android's Typeface.DEFAULT etc. are real static fields (getstatic in compiled
        // extensions), not a getter method; a plain `val` here would be a NoSuchFieldError.
        @JvmField val DEFAULT = Typeface(null)
        @JvmField val DEFAULT_BOLD = Typeface(null)
        @JvmField val MONOSPACE = Typeface(null)
        @JvmField val SANS_SERIF = Typeface(null)
        @JvmField val SERIF = Typeface(null)

        @JvmStatic
        fun createFromAsset(assetManager: Any?, path: String): Typeface = DEFAULT
        @JvmStatic
        fun createFromFile(path: String): Typeface = createFromFile(File(path))
        @JvmStatic
        fun createFromFile(file: File): Typeface = try {
            Typeface(Font.createFont(Font.TRUETYPE_FONT, file))
        } catch (_: Exception) {
            DEFAULT
        }
    }
}
