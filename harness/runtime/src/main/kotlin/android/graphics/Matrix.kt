package android.graphics

import java.awt.geom.AffineTransform

/**
 * Only the post* ops keiyoushi extensions use (pivot scale + translate for de-mirroring /
 * re-placing fragments); not a general 3x3 matrix like Android's.
 */
class Matrix {
    internal var transform = AffineTransform()

    fun postScale(sx: Float, sy: Float, px: Float, py: Float) {
        val t = AffineTransform()
        t.translate(px.toDouble(), py.toDouble())
        t.scale(sx.toDouble(), sy.toDouble())
        t.translate(-px.toDouble(), -py.toDouble())
        transform.preConcatenate(t)
    }

    fun postScale(sx: Float, sy: Float) = postScale(sx, sy, 0f, 0f)

    fun postTranslate(dx: Float, dy: Float) {
        transform.preConcatenate(AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble()))
    }

    fun postRotate(degrees: Float, px: Float, py: Float) {
        transform.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble()))
    }

    fun postRotate(degrees: Float) = postRotate(degrees, 0f, 0f)
}
