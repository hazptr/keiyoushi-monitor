package android.graphics

object Color {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val TRANSPARENT = 0
    const val DKGRAY = 0xFF444444.toInt()
    const val GRAY = 0xFF888888.toInt()
    const val LTGRAY = 0xFFCCCCCC.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()

    // @JvmStatic: Android's Color methods are real `static`; without it, a compiled extension's
    // `invokestatic` call fails with IncompatibleClassChangeError (see BitmapFactory for the same issue).
    @JvmStatic
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    @JvmStatic
    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
    @JvmStatic
    fun alpha(color: Int) = color ushr 24 and 0xFF
    @JvmStatic
    fun red(color: Int) = color shr 16 and 0xFF
    @JvmStatic
    fun green(color: Int) = color shr 8 and 0xFF
    @JvmStatic
    fun blue(color: Int) = color and 0xFF

    @JvmStatic
    fun parseColor(colorString: String): Int {
        val s = colorString.removePrefix("#")
        val v = s.toLong(16)
        return when (s.length) {
            6 -> 0xFF000000.toInt() or v.toInt()
            8 -> v.toInt()
            else -> throw IllegalArgumentException("Unknown color: $colorString")
        }
    }
}
