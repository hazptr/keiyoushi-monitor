package org.chromium.support_lib_glue

import java.lang.reflect.InvocationHandler

/**
 * ext-run: the entry point androidx.webkit (and core's WebViewGlueBridge) looks up by name in the WebView
 * provider's class loader (WebView.getWebViewClassLoader()): it returns the factory behind every
 * WebViewCompat/WebSettingsCompat call. On a phone it's Chromium's glue; here it's keiyoushi.extrun.WebViewGlue,
 * which answers through the browser sidecar.
 */
object SupportLibReflectionUtil {
    @JvmStatic
    fun createWebViewProviderFactory(): InvocationHandler = keiyoushi.extrun.WebViewGlue.factory()
}
