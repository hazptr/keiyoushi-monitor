package android.graphics

import java.awt.AlphaComposite
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.util.ArrayDeque

/**
 * Backed by a java.awt.Graphics2D on the target Bitmap's BufferedImage. save()/restore() is a
 * plain transform stack, like Android's (clip save/restore isn't tracked: no extension clips).
 */
class Canvas(private val bitmap: Bitmap) {
    internal val g: java.awt.Graphics2D = bitmap.awt.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }
    private val stack = ArrayDeque<AffineTransform>()

    fun save(): Int {
        stack.push(g.transform)
        return stack.size
    }

    fun restore() {
        if (stack.isNotEmpty()) g.transform = stack.pop()
    }

    fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())
    fun scale(sx: Float, sy: Float) = g.scale(sx.toDouble(), sy.toDouble())
    fun rotate(degrees: Float) = g.rotate(Math.toRadians(degrees.toDouble()))
    fun rotate(degrees: Float, px: Float, py: Float) = g.rotate(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble())

    fun drawColor(color: Int) {
        val savedComposite = g.composite
        g.composite = AlphaComposite.SrcOver
        g.color = AwtColor(color, true)
        g.fillRect(0, 0, bitmap.width, bitmap.height)
        g.composite = savedComposite
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = drawRectImpl(left, top, right, bottom, paint)
    fun drawRect(r: Rect, paint: Paint) = drawRectImpl(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
    fun drawRect(r: RectF, paint: Paint) = drawRectImpl(r.left, r.top, r.right, r.bottom, paint)

    private fun drawRectImpl(l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        g.color = AwtColor(paint.color, true)
        val rect = Rectangle2D.Float(l, t, r - l, b - t)
        if (paint.style == Paint.Style.STROKE) {
            g.stroke = java.awt.BasicStroke(paint.strokeWidth.takeIf { it > 0 } ?: 1f)
            g.draw(rect)
        } else {
            g.fill(rect)
        }
    }

    fun drawBitmap(bmp: Bitmap, left: Float, top: Float, paint: Paint?) {
        g.drawImage(bmp.awt, AffineTransform.getTranslateInstance(left.toDouble(), top.toDouble()), null)
    }

    fun drawBitmap(bmp: Bitmap, src: Rect?, dst: Rect, paint: Paint?) =
        drawBitmapRect(bmp, src, dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat())

    fun drawBitmap(bmp: Bitmap, src: Rect?, dst: RectF, paint: Paint?) =
        drawBitmapRect(bmp, src, dst.left, dst.top, dst.right, dst.bottom)

    fun drawBitmap(bmp: Bitmap, matrix: Matrix, paint: Paint?) {
        g.drawImage(bmp.awt, matrix.transform, null)
    }

    private fun drawBitmapRect(bmp: Bitmap, src: Rect?, dl: Float, dt: Float, dr: Float, db: Float) {
        val sl = src?.left ?: 0
        val st = src?.top ?: 0
        val sr = src?.right ?: bmp.width
        val sb = src?.bottom ?: bmp.height
        g.drawImage(bmp.awt, dl.toInt(), dt.toInt(), dr.toInt(), db.toInt(), sl, st, sr, sb, null)
    }
}
