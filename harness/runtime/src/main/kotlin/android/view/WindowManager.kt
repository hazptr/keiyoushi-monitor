package android.view

interface WindowManager {
    open class LayoutParams() : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) {
        @JvmField var flags: Int = 0
        @JvmField var gravity: Int = 0
        @JvmField var alpha: Float = 1f
        @JvmField var dimAmount: Float = 1f

        companion object {
            const val FLAG_DIM_BEHIND = 0x00000002
            const val FLAG_NOT_FOCUSABLE = 0x00000008
            const val FLAG_NOT_TOUCHABLE = 0x00000010
            const val FLAG_KEEP_SCREEN_ON = 0x00000080
            const val FLAG_FULLSCREEN = 0x00000400
            const val FLAG_SECURE = 0x00002000
        }
    }
}
