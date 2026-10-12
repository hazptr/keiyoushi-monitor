package android.util

import java.util.Base64 as JBase64

// android.util.Base64 on top of java.util.Base64. Flag values match Android's.
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic fun decode(str: String, flags: Int): ByteArray = decode(str.toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray {
        // Android's decoder ignores whitespace and accepts both alphabets' padding variants.
        var s = String(input, Charsets.US_ASCII).filterNot { it.isWhitespace() }
        s = if (flags and URL_SAFE != 0) s.replace('-', '+').replace('_', '/') else s
        return JBase64.getMimeDecoder().decode(s)
    }

    @JvmStatic fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray =
        decode(input.copyOfRange(offset, offset + len), flags)

    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String {
        var enc = if (flags and URL_SAFE != 0) JBase64.getUrlEncoder() else JBase64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        val s = enc.encodeToString(input)
        if (flags and NO_WRAP != 0) return s
        val sep = if (flags and CRLF != 0) "\r\n" else "\n"
        return s.chunked(76).joinToString(sep, postfix = sep)
    }

    @JvmStatic fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String =
        encodeToString(input.copyOfRange(offset, offset + len), flags)
}
