package android.media

/** android.media.Image: one frame from an [ImageReader]. */
abstract class Image : AutoCloseable {
    abstract fun getFormat(): Int
    abstract fun getWidth(): Int
    abstract fun getHeight(): Int
    abstract fun getTimestamp(): Long
    abstract override fun close()
}
