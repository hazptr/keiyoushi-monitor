package android.graphics

import android.util.Size
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/**
 * android.graphics.ImageDecoder (API 28) over ImageIO, like BitmapFactory here (WebP via TwelveMonkeys,
 * no AVIF/HEIF). The header listener runs before the decode, as on Android; setTargetSize,
 * setTargetSampleSize and setCrop are applied, the allocator and the other hints are stored only.
 */
class ImageDecoder private constructor(private val info: ImageInfo) {
    abstract class Source internal constructor() {
        internal abstract fun bytes(): ByteArray
    }

    private class ByteArraySource(private val data: ByteArray) : Source() {
        override fun bytes() = data
    }

    class ImageInfo internal constructor(private val size: Size, private val mimeType: String) {
        fun getSize(): Size = size
        fun getMimeType(): String = mimeType
        fun isAnimated(): Boolean = false
        fun getColorSpace(): Any? = null
    }

    fun interface OnHeaderDecodedListener {
        fun onHeaderDecoded(decoder: ImageDecoder, info: ImageInfo, source: Source)
    }

    class DecodeException(message: String) : IOException(message)

    var allocator: Int = ALLOCATOR_DEFAULT
    var isMutableRequired: Boolean = false
    var isUnpremultipliedRequired: Boolean = false
    var memorySizePolicy: Int = MEMORY_POLICY_DEFAULT
    var crop: Rect? = null
    private var targetW = 0
    private var targetH = 0

    fun setTargetSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Dimensions must be positive! provided ($width, $height)" }
        targetW = width
        targetH = height
    }

    fun setTargetSampleSize(sampleSize: Int) {
        val s = sampleSize.coerceAtLeast(1)
        setTargetSize((info.getSize().getWidth() + s - 1) / s, (info.getSize().getHeight() + s - 1) / s)
    }

    fun setOnPartialImageListener(listener: Any?) = Unit
    fun setPostProcessor(processor: Any?) = Unit

    companion object {
        const val ALLOCATOR_DEFAULT = 0
        const val ALLOCATOR_SOFTWARE = 1
        const val ALLOCATOR_SHARED_MEMORY = 2
        const val ALLOCATOR_HARDWARE = 3
        const val MEMORY_POLICY_DEFAULT = 1
        const val MEMORY_POLICY_LOW_RAM = 0

        // @JvmStatic: real `static` methods on Android (see BitmapFactory).
        @JvmStatic
        fun createSource(data: ByteArray): Source = ByteArraySource(data)

        @JvmStatic
        fun createSource(data: ByteArray, offset: Int, length: Int): Source = ByteArraySource(data.copyOfRange(offset, offset + length))

        @JvmStatic
        fun createSource(buffer: ByteBuffer): Source = ByteArraySource(ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) })

        @JvmStatic
        fun createSource(file: File): Source = ByteArraySource(file.readBytes())

        @JvmStatic
        fun isMimeTypeSupported(mimeType: String): Boolean = ImageIO.getImageReadersByMIMEType(mimeType).hasNext()

        @JvmStatic
        @Throws(IOException::class)
        fun decodeBitmap(src: Source): Bitmap = decodeBitmap(src, null)

        @JvmStatic
        @Throws(IOException::class)
        fun decodeBitmap(src: Source, listener: OnHeaderDecodedListener?): Bitmap {
            val bytes = src.bytes()
            val (size, mime) = header(bytes) ?: throw DecodeException("Failed to create image decoder: not an image ImageIO can read")
            val decoder = ImageDecoder(ImageInfo(size, mime))
            listener?.onHeaderDecoded(decoder, decoder.info, src)
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw DecodeException("Failed to decode image ($mime)")
            decoder.crop?.let { c -> bmp = Bitmap.createBitmap(bmp, c.left, c.top, c.width(), c.height()) }
            if (decoder.targetW > 0 && (decoder.targetW != bmp.width || decoder.targetH != bmp.height)) {
                bmp = Bitmap.createScaledBitmap(bmp, decoder.targetW, decoder.targetH, true)
            }
            return bmp
        }

        private fun header(bytes: ByteArray): Pair<Size, String>? {
            val iis = ImageIO.createImageInputStream(bytes.inputStream()) ?: return null
            iis.use {
                val reader = ImageIO.getImageReaders(iis).takeIf { it.hasNext() }?.next() ?: return null
                return try {
                    reader.input = iis
                    val mime = reader.originatingProvider?.mimeTypes?.firstOrNull() ?: "image/${reader.formatName.lowercase()}"
                    Size(reader.getWidth(0), reader.getHeight(0)) to mime
                } catch (_: Exception) {
                    null
                } finally {
                    reader.dispose()
                }
            }
        }
    }
}
