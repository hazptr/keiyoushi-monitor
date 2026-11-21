package android.app

// Minimal JVM shim of android.app.ActivityManager: memory info of the Build shim's phone (8 GB).
class ActivityManager internal constructor() {
    class MemoryInfo {
        @JvmField var totalMem: Long = 0
        @JvmField var availMem: Long = 0
        @JvmField var threshold: Long = 0
        @JvmField var lowMemory: Boolean = false
    }

    fun getMemoryInfo(outInfo: MemoryInfo) {
        outInfo.totalMem = 7_557_000_000L
        outInfo.availMem = 3_200_000_000L
        outInfo.threshold = 226_000_000L
    }

    fun isLowRamDevice(): Boolean = false
}
