package android.media

import android.os.Handler
import android.view.Surface

/**
 * android.media.ImageReader: frames drawn into its [Surface] come out as [Image]s. In ext-run nothing renders
 * through a Surface (the sidecar's Chrome draws the page), so no frame ever arrives and the listener never runs.
 */
open class ImageReader private constructor(private val width: Int, private val height: Int, private val format: Int, private val maxImages: Int) : AutoCloseable {
    private val surface = Surface()
    @Volatile private var closed = false

    fun interface OnImageAvailableListener {
        fun onImageAvailable(reader: ImageReader)
    }

    open fun getWidth(): Int = width
    open fun getHeight(): Int = height
    open fun getImageFormat(): Int = format
    open fun getMaxImages(): Int = maxImages

    open fun getSurface(): Surface {
        check(!closed) { "ImageReader is closed" }
        return surface
    }

    open fun setOnImageAvailableListener(listener: OnImageAvailableListener?, handler: Handler?) {
        check(!closed) { "ImageReader is closed" }
    }

    open fun acquireLatestImage(): Image? = null
    open fun acquireNextImage(): Image? = null

    override fun close() {
        closed = true
        surface.release()
    }

    companion object {
        @JvmStatic
        fun newInstance(width: Int, height: Int, format: Int, maxImages: Int): ImageReader {
            require(width > 0 && height > 0) { "The image dimensions must be positive" }
            require(maxImages > 0) { "Maximum outstanding image count must be at least 1" }
            return ImageReader(width, height, format, maxImages)
        }
    }
}
