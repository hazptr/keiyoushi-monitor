package android.util

// Minimal JVM shim of android.util.DisplayMetrics, values of the Build shim's phone (SM-S911B).
class DisplayMetrics {
    @JvmField var widthPixels: Int = 1080
    @JvmField var heightPixels: Int = 2340
    @JvmField var density: Float = 2.8125f
    @JvmField var densityDpi: Int = 450
    @JvmField var scaledDensity: Float = 2.8125f
    @JvmField var xdpi: Float = 425f
    @JvmField var ydpi: Float = 425f
}
