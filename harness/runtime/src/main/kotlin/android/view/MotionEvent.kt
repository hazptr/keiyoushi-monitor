package android.view

/**
 * android.view.MotionEvent as a plain value: what a source builds with obtain() to hand to View.dispatchTouchEvent.
 * A WebView forwards it to the sidecar's page as CDP touch input (keiyoushi.extrun.WebViewBridge.touch).
 */
class MotionEvent private constructor(
    private var downTime: Long,
    private var eventTime: Long,
    private var action: Int,
    private val properties: Array<PointerProperties>,
    private val coords: Array<PointerCoords>,
    private var metaState: Int,
    private var source: Int,
) {
    class PointerProperties() {
        @JvmField var id: Int = 0

        @JvmField var toolType: Int = TOOL_TYPE_UNKNOWN

        constructor(other: PointerProperties) : this() { copyFrom(other) }

        fun copyFrom(other: PointerProperties) {
            id = other.id
            toolType = other.toolType
        }

        fun clear() {
            id = INVALID_POINTER_ID
            toolType = TOOL_TYPE_UNKNOWN
        }
    }

    class PointerCoords() {
        @JvmField var x: Float = 0f

        @JvmField var y: Float = 0f

        @JvmField var pressure: Float = 0f

        @JvmField var size: Float = 0f

        @JvmField var touchMajor: Float = 0f

        @JvmField var touchMinor: Float = 0f

        @JvmField var toolMajor: Float = 0f

        @JvmField var toolMinor: Float = 0f

        @JvmField var orientation: Float = 0f

        constructor(other: PointerCoords) : this() { copyFrom(other) }

        fun copyFrom(o: PointerCoords) {
            x = o.x; y = o.y; pressure = o.pressure; size = o.size; touchMajor = o.touchMajor; touchMinor = o.touchMinor
            toolMajor = o.toolMajor; toolMinor = o.toolMinor; orientation = o.orientation
        }

        fun clear() = copyFrom(PointerCoords())
    }

    fun getDownTime(): Long = downTime
    fun getEventTime(): Long = eventTime
    fun getAction(): Int = action
    fun getActionMasked(): Int = action and ACTION_MASK
    fun getActionIndex(): Int = (action and ACTION_POINTER_INDEX_MASK) shr ACTION_POINTER_INDEX_SHIFT
    fun getPointerCount(): Int = coords.size
    fun getPointerId(index: Int): Int = properties[index].id
    fun getToolType(index: Int): Int = properties[index].toolType
    fun getX(): Float = coords[0].x
    fun getY(): Float = coords[0].y
    fun getX(index: Int): Float = coords[index].x
    fun getY(index: Int): Float = coords[index].y
    fun getPressure(): Float = coords[0].pressure
    fun getPressure(index: Int): Float = coords[index].pressure
    fun getSize(): Float = coords[0].size
    fun getTouchMajor(index: Int): Float = coords[index].touchMajor
    fun getTouchMinor(index: Int): Float = coords[index].touchMinor
    fun getOrientation(index: Int): Float = coords[index].orientation
    fun getMetaState(): Int = metaState
    fun getSource(): Int = source
    fun setSource(value: Int) { source = value }
    fun setAction(value: Int) { action = value }
    fun setLocation(x: Float, y: Float) = offsetLocation(x - coords[0].x, y - coords[0].y)
    fun offsetLocation(dx: Float, dy: Float) = coords.forEach { it.x += dx; it.y += dy }
    fun getPointerCoords(index: Int, out: PointerCoords) = out.copyFrom(coords[index])
    fun getPointerProperties(index: Int, out: PointerProperties) = out.copyFrom(properties[index])
    fun recycle() {}

    companion object {
        const val INVALID_POINTER_ID = -1
        const val ACTION_MASK = 0xff
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_POINTER_INDEX_MASK = 0xff00
        const val ACTION_POINTER_INDEX_SHIFT = 8
        const val TOOL_TYPE_UNKNOWN = 0
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_MOUSE = 3

        @JvmStatic
        fun obtain(
            downTime: Long, eventTime: Long, action: Int, pointerCount: Int,
            pointerProperties: Array<PointerProperties>, pointerCoords: Array<PointerCoords>,
            metaState: Int, buttonState: Int, xPrecision: Float, yPrecision: Float,
            deviceId: Int, edgeFlags: Int, source: Int, flags: Int,
        ): MotionEvent = MotionEvent(
            downTime, eventTime, action,
            Array(pointerCount) { PointerProperties(pointerProperties[it]) },
            Array(pointerCount) { PointerCoords(pointerCoords[it]) },
            metaState, source,
        )

        @JvmStatic
        fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, metaState: Int): MotionEvent =
            MotionEvent(
                downTime, eventTime, action,
                arrayOf(PointerProperties().apply { toolType = TOOL_TYPE_FINGER }),
                arrayOf(PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
                metaState, InputDevice.SOURCE_TOUCHSCREEN,
            )

        @JvmStatic
        fun obtain(other: MotionEvent): MotionEvent = MotionEvent(
            other.downTime, other.eventTime, other.action,
            Array(other.properties.size) { PointerProperties(other.properties[it]) },
            Array(other.coords.size) { PointerCoords(other.coords[it]) },
            other.metaState, other.source,
        )
    }
}
