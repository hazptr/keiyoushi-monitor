package android.graphics

import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO

/** decodeByteArray/decodeStream via ImageIO; returns null on undecodable input, like Android. */
object BitmapFactory {

    init {
        // -cp dir/* flattens every dependency jar onto one classpath, which the plain classloader
        // scan usually finds fine; scanForPlugins() is a belt-and-suspenders registration so
        // TwelveMonkeys' WebP reader (imageio-webp) is never missed.
        ImageIO.scanForPlugins()
    }

    // Android's Options members are public Java fields (extensions compile to getfield/putfield).
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var outWidth: Int = -1
        @JvmField var outHeight: Int = -1
        @JvmField var outMimeType: String? = null
        @JvmField var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
        @JvmField var inSampleSize: Int = 1
        @JvmField var inMutable: Boolean = false
        @JvmField var inScaled: Boolean = true
        @JvmField var inDensity: Int = 0
        @JvmField var inTargetDensity: Int = 0
        @JvmField var inPremultiplied: Boolean = true
        @JvmField var inBitmap: Bitmap? = null
        @JvmField var outConfig: Bitmap.Config? = null
    }

    // Android's BitmapFactory methods are real `static`, not an object's INSTANCE.method(); without
    // @JvmStatic a compiled extension's `invokestatic` call fails with IncompatibleClassChangeError.
    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? =
        decode(data.inputStream(offset, length), opts)

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = decodeByteArray(data, offset, length, null)

    @JvmStatic
    fun decodeStream(stream: InputStream, outPadding: Rect?, opts: Options?): Bitmap? = decode(stream, opts)

    @JvmStatic
    fun decodeStream(stream: InputStream): Bitmap? = decodeStream(stream, null, null)

    private fun decode(stream: InputStream, opts: Options?): Bitmap? {
        val img = try {
            ImageIO.read(stream)
        } catch (_: Exception) {
            null
        } ?: return null
        opts?.outWidth = img.width
        opts?.outHeight = img.height
        if (opts?.inJustDecodeBounds == true) return null
        if (img.type == BufferedImage.TYPE_INT_ARGB) return Bitmap(img)
        val argb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        argb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        return Bitmap(argb)
    }
}
