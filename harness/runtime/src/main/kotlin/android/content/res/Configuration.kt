package android.content.res

/** android.content.res.Configuration with the system's values: a phone in portrait and light mode. */
class Configuration() {
    @JvmField var uiMode: Int = UI_MODE_TYPE_NORMAL or UI_MODE_NIGHT_NO
    @JvmField var orientation: Int = ORIENTATION_PORTRAIT

    constructor(o: Configuration) : this() {
        uiMode = o.uiMode
        orientation = o.orientation
    }

    fun isNightModeActive(): Boolean = uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES

    companion object {
        const val UI_MODE_TYPE_MASK = 0x0f
        const val UI_MODE_TYPE_NORMAL = 0x01
        const val UI_MODE_NIGHT_MASK = 0x30
        const val UI_MODE_NIGHT_UNDEFINED = 0x00
        const val UI_MODE_NIGHT_NO = 0x10
        const val UI_MODE_NIGHT_YES = 0x20
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
