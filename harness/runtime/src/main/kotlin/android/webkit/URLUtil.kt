package android.webkit

/**
 * The scheme checks of AOSP frameworks/base core/java/android/webkit/URLUtil.java (prefix tests;
 * only http:// and https:// ignore case, as on Android). guessUrl, guessFileName and the decode helpers aren't here.
 */
object URLUtil {
    private const val ASSET_BASE = "file:///android_asset/"
    private const val RESOURCE_BASE = "file:///android_res/"
    private const val FILE_BASE = "file:"
    private const val PROXY_BASE = "file:///cookieless_proxy/"
    private const val CONTENT_BASE = "content:"

    private fun String?.startsIgnoreCase(prefix: String) = this != null && regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)

    @JvmStatic fun isAssetUrl(url: String?) = url != null && url.startsWith(ASSET_BASE)

    @JvmStatic fun isResourceUrl(url: String?) = url != null && url.startsWith(RESOURCE_BASE)

    @Deprecated("Cookieless proxy is no longer supported.")
    @JvmStatic fun isCookielessProxyUrl(url: String?) = url != null && url.startsWith(PROXY_BASE)

    @JvmStatic fun isFileUrl(url: String?) = url != null && url.startsWith(FILE_BASE) &&
        !url.startsWith(ASSET_BASE) && !url.startsWith(PROXY_BASE)

    @JvmStatic fun isAboutUrl(url: String?) = url != null && url.startsWith("about:")

    @JvmStatic fun isDataUrl(url: String?) = url != null && url.startsWith("data:")

    @JvmStatic fun isJavaScriptUrl(url: String?) = url != null && url.startsWith("javascript:")

    @JvmStatic fun isHttpUrl(url: String?) = url.startsIgnoreCase("http://")

    @JvmStatic fun isHttpsUrl(url: String?) = url.startsIgnoreCase("https://")

    @JvmStatic fun isNetworkUrl(url: String?) = !url.isNullOrEmpty() && (isHttpUrl(url) || isHttpsUrl(url))

    @JvmStatic fun isContentUrl(url: String?) = url != null && url.startsWith(CONTENT_BASE)

    @JvmStatic fun isValidUrl(url: String?) = !url.isNullOrEmpty() &&
        (isAssetUrl(url) || isResourceUrl(url) || isFileUrl(url) || isAboutUrl(url) || isHttpUrl(url) ||
            isHttpsUrl(url) || isJavaScriptUrl(url) || isContentUrl(url))
}
