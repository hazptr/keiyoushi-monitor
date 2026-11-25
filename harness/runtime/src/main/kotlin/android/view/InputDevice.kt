package android.view

// android.view.InputDevice: the source constants MotionEvent.obtain takes.
class InputDevice private constructor() {
    companion object {
        const val SOURCE_CLASS_POINTER = 0x2
        const val SOURCE_UNKNOWN = 0
        const val SOURCE_TOUCHSCREEN = 0x1002
        const val SOURCE_MOUSE = 0x2002
        const val SOURCE_STYLUS = 0x4002
    }
}
