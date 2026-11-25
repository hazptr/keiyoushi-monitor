package android.view

/** android.view.Surface: a buffer queue a producer draws into. Nothing draws into one in ext-run. */
open class Surface internal constructor() {
    @Volatile private var valid = true
    open fun isValid(): Boolean = valid
    open fun release() {
        valid = false
    }
}
