package keiyoushi.extrun

import android.webkit.WebView
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom

/**
 * A WebView held open for someone (an agent, the operator) to drive: `browse`, `--pause` on a challenge the
 * sidecar couldn't pass, a source's overlay with `--pause`. The view is announced in the sidecar's socket dir
 * (`held/<token>.json`, which `ext-run cdp|resume|held` read on the host) and a banner says how to drive it.
 * [await] then waits for `ext-run resume <token>` (the `<token>.resume` file) or EXT_RUN_HOLD_MAX_S.
 */
object Hold {
    /** `--pause`: hold a challenge the run can't pass on its own, instead of failing it. */
    val pause = System.getenv("EXT_RUN_PAUSE") == "1"
    private val maxS = System.getenv("EXT_RUN_HOLD_MAX_S")?.toLongOrNull() ?: 1800
    private val container = System.getenv("EXT_RUN_WV_CTR").orEmpty()
    private val dir = File(File(System.getenv("EXT_RUN_WEBVIEW_SOCK") ?: "/ext-run-webview/x").parentFile, "held")

    class Handle(val token: String, val view: WebView) {
        val file = File(dir, "$token.json")
        val resume = File(dir, "$token.resume")
    }

    fun announce(view: WebView, why: String, waits: Boolean = true): Handle {
        val token = ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val h = Handle(token, view)
        val target = WebViewBridge.targetOf(view)
        dir.mkdirs()
        h.file.writeText(
            buildJsonObject {
                put("token", token)
                put("wid", view.id)
                put("target", target)
                put("container", container)
                put("url", view.getUrl())
                put("why", why)
                put("started", System.currentTimeMillis() / 1000)
            }.toString(),
        )
        val done = if (waits) {
            "    done:  ext-run resume $token   (the run goes on; gives up after ${maxS}s)"
        } else {
            "    (the run's own code decides when this closes; it doesn't wait for resume)"
        }
        System.err.println(
            """
            |  ⏸ ext-run HOLD $token: $why
            |    page:  ${view.getUrl()}
            |    look:  ext-run cdp $token shot          (prints a PNG path: Read it)   | ext-run cdp $token url
            |    act:   ext-run cdp $token click-text 'Log in' | fill 'input[type=email]' 'me@x' | type 'text' | key Enter
            |           ext-run cdp $token click X Y | scroll 600 | open <url> | cf (tick Turnstile) | eval '<js>' | cookies
            |$done
            """.trimMargin(),
        )
        return h
    }

    /** Waits for `ext-run resume`, the view being destroyed, [until], or EXT_RUN_HOLD_MAX_S; true if resumed. */
    fun await(h: Handle, until: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + maxS * 1000
        try {
            while (System.currentTimeMillis() < deadline) {
                if (h.resume.exists()) {
                    System.err.println("  ▶ ext-run HOLD ${h.token}: resumed")
                    return true
                }
                if (h.view.destroyed || until()) return false
                Thread.sleep(500)
            }
            System.err.println("  ! ext-run HOLD ${h.token}: nobody resumed it within ${maxS}s")
            return false
        } finally {
            release(h)
        }
    }

    fun release(h: Handle) {
        dir.listFiles { f -> f.name.startsWith(h.token) }?.forEach { it.delete() }
    }
}
