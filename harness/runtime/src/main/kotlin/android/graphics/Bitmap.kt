package android.graphics

import java.awt.AlphaComposite
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Backed by java.awt.image.BufferedImage (always TYPE_INT_ARGB internally), enough to run the
 * descrambling/compositing extensions do in OkHttp interceptors. Also the type WebViewClient's
 * callbacks mention (favicon: Bitmap?); never produced by WebView here.
 */
class Bitmap internal constructor(internal val awt: BufferedImage) {

    enum class Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE,
        // Approximated as ARGB_8888 throughout: pixel storage is always 32-bit ARGB.
    }

    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    val width: Int get() = awt.width
    val height: Int get() = awt.height
    val config: Config get() = Config.ARGB_8888

    var isRecycled: Boolean = false
        private set

    fun recycle() {
        isRecycled = true
    }

    fun getPixel(x: Int, y: Int): Int = awt.getRGB(x, y)

    fun setPixel(x: Int, y: Int, color: Int) = awt.setRGB(x, y, color)

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        awt.getRGB(x, y, width, height, pixels, offset, stride)
    }

    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        awt.setRGB(x, y, width, height, pixels, offset, stride)
    }

    fun eraseColor(color: Int) {
        val g = awt.createGraphics()
        g.composite = AlphaComposite.Src
        g.color = AwtColor(color, true)
        g.fillRect(0, 0, width, height)
        g.dispose()
    }

    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(awt, 0, 0, null); dispose() }
        return Bitmap(out)
    }

    /**
     * WEBP has no Java ImageIO *encoder* (TwelveMonkeys' imageio-webp, added for decoding, is
     * read-only), so WEBP/WEBP_LOSSY/WEBP_LOSSLESS are written as PNG bytes instead. Callers that
     * send the result on with an "image/webp" content type are actually sending PNG (or, for
     * JPEG, real JPEG).
     */
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = when (format) {
        CompressFormat.JPEG -> writeJpeg(quality, stream)
        else -> ImageIO.write(awt, "png", stream)
    }

    private fun writeJpeg(quality: Int, stream: OutputStream): Boolean {
        // JPEG has no alpha channel; flatten onto white like Android's encoder does.
        val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            color = AwtColor.WHITE
            fillRect(0, 0, width, height)
            drawImage(awt, 0, 0, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = (quality.coerceIn(0, 100) / 100f)
        }
        ImageIO.createImageOutputStream(stream).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(rgb, null, null), param)
        }
        writer.dispose()
        return true
    }

    // @JvmStatic: Android's Bitmap.createBitmap/createScaledBitmap are real `static` methods, not
    // an object's INSTANCE.method(); without it, a compiled extension's `invokestatic` call fails
    // with IncompatibleClassChangeError.
    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config, hasAlpha: Boolean): Bitmap =
            createBitmap(width, height, config)

        /** Crop: a new bitmap holding the (x, y, width, height) region of [source]. */
        @JvmStatic
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                drawImage(source.awt.getSubimage(x, y, width, height), 0, 0, null)
                dispose()
            }
            return Bitmap(out)
        }

        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val out = BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
                drawImage(src.awt, 0, 0, dstWidth, dstHeight, null)
                dispose()
            }
            return Bitmap(out)
        }
    }
}
