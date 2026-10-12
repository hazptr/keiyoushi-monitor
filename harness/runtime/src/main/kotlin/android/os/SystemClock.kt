package android.os

object SystemClock {
    private val start = System.nanoTime()

    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - start) / 1_000_000 + 1_000_000
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - start + 1_000_000_000_000
    @JvmStatic fun sleep(ms: Long) = Thread.sleep(ms)
}
