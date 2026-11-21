package android.graphics

import java.awt.AlphaComposite
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.IntBuffer
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

    val byteCount: Int get() = width * height * 4
    val allocationByteCount: Int get() = byteCount
    val rowBytes: Int get() = width * 4
    val isMutable: Boolean get() = true
    fun hasAlpha(): Boolean = true

    /**
     * Android's ARGB_8888 memory layout: R, G, B, A bytes per pixel, premultiplied by alpha. A
     * ByteBuffer is read as those bytes, an IntBuffer as one such pixel per (little-endian) int.
     */
    fun copyPixelsFromBuffer(src: Buffer) {
        val n = width * height
        val px = IntArray(n)
        when (src) {
            is ByteBuffer -> {
                val b = src.duplicate()
                if (b.remaining() < n * 4) throw RuntimeException("Buffer not large enough for pixels")
                for (i in 0 until n) {
                    val r = b.get().toInt() and 0xFF
                    val g = b.get().toInt() and 0xFF
                    val bl = b.get().toInt() and 0xFF
                    val a = b.get().toInt() and 0xFF
                    px[i] = unpremultiply(a, r, g, bl)
                }
                src.position(src.position() + n * 4)
            }
            is IntBuffer -> {
                val b = src.duplicate()
                if (b.remaining() < n) throw RuntimeException("Buffer not large enough for pixels")
                for (i in 0 until n) {
                    val v = b.get()
                    px[i] = unpremultiply(v ushr 24, v and 0xFF, v shr 8 and 0xFF, v shr 16 and 0xFF)
                }
                src.position(src.position() + n)
            }
            else -> throw RuntimeException("unsupported Buffer subclass")
        }
        awt.setRGB(0, 0, width, height, px, 0, width)
    }

    fun copyPixelsToBuffer(dst: Buffer) {
        val n = width * height
        val px = awt.getRGB(0, 0, width, height, null, 0, width)
        when (dst) {
            is ByteBuffer -> {
                if (dst.remaining() < n * 4) throw RuntimeException("Buffer not large enough for pixels")
                for (c in px) {
                    val p = premultiply(c)
                    dst.put((p shr 16).toByte()); dst.put((p shr 8).toByte()); dst.put(p.toByte()); dst.put((p ushr 24).toByte())
                }
            }
            is IntBuffer -> {
                if (dst.remaining() < n) throw RuntimeException("Buffer not large enough for pixels")
                for (c in px) {
                    val p = premultiply(c)
                    dst.put((p and 0xFF00FF00.toInt()) or (p shr 16 and 0xFF) or (p and 0xFF shl 16))
                }
            }
            else -> throw RuntimeException("unsupported Buffer subclass")
        }
    }

    private fun unpremultiply(a: Int, r: Int, g: Int, b: Int): Int {
        if (a == 255 || a == 0) return (a shl 24) or (r shl 16) or (g shl 8) or b
        fun u(c: Int) = minOf(255, (c * 255 + a / 2) / a)
        return (a shl 24) or (u(r) shl 16) or (u(g) shl 8) or u(b)
    }

    private fun premultiply(c: Int): Int {
        val a = c ushr 24
        if (a == 255) return c
        fun p(v: Int) = (v * a + 127) / 255
        return (a shl 24) or (p(c shr 16 and 0xFF) shl 16) or (p(c shr 8 and 0xFF) shl 8) or p(c and 0xFF)
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

        /** From ARGB color ints (non-premultiplied, as Android takes them), row by row. */
        @JvmStatic
        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap =
            createBitmap(colors, 0, width, width, height, config)

        @JvmStatic
        fun createBitmap(colors: IntArray, offset: Int, stride: Int, width: Int, height: Int, config: Config): Bitmap {
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            out.setRGB(0, 0, width, height, colors, offset, stride)
            return Bitmap(out)
        }

        @JvmStatic
        fun createBitmap(source: Bitmap): Bitmap = source.copy(source.config, true)

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
