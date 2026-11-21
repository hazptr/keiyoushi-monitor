package android.graphics

import java.awt.geom.AffineTransform

/**
 * Only the post* ops keiyoushi extensions use (pivot scale + translate for de-mirroring /
 * re-placing fragments); not a general 3x3 matrix like Android's. Like Android's, the post* ops
 * return a boolean (always true): compiled callers look them up with a `Z` return type.
 */
class Matrix {
    internal var transform = AffineTransform()

    fun postScale(sx: Float, sy: Float, px: Float, py: Float): Boolean {
        val t = AffineTransform()
        t.translate(px.toDouble(), py.toDouble())
        t.scale(sx.toDouble(), sy.toDouble())
        t.translate(-px.toDouble(), -py.toDouble())
        transform.preConcatenate(t)
        return true
    }

    fun postScale(sx: Float, sy: Float): Boolean = postScale(sx, sy, 0f, 0f)

    fun postTranslate(dx: Float, dy: Float): Boolean {
        transform.preConcatenate(AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble()))
        return true
    }

    fun postRotate(degrees: Float, px: Float, py: Float): Boolean {
        transform.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble()))
        return true
    }

    fun postRotate(degrees: Float): Boolean = postRotate(degrees, 0f, 0f)

    fun reset() {
        transform = AffineTransform()
    }
}
