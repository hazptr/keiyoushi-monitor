package android.graphics

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.util.ArrayDeque

/**
 * Backed by a java.awt.Graphics2D on the target Bitmap's BufferedImage. save()/restore() keeps the
 * transform and the clip, like Android's. Bitmaps are sampled the way Android's software canvas
 * does: bilinear when the paint has FILTER_BITMAP_FLAG (on by default in `Paint()`), nearest
 * neighbour with `isFilterBitmap = false` or a null paint; the paint's alpha fades the bitmap.
 * Shapes and text honour style, stroke width/cap/join/miter and anti-aliasing.
 */
class Canvas(private val bitmap: Bitmap) {
    internal val g: java.awt.Graphics2D = bitmap.awt.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }
    private class State(val transform: AffineTransform, val clip: Shape?)
    private val stack = ArrayDeque<State>()

    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    fun save(): Int {
        stack.push(State(g.transform, g.clip))
        return stack.size
    }

    fun restore() {
        if (stack.isNotEmpty()) stack.pop().let { g.transform = it.transform; g.clip = it.clip }
    }

    fun getSaveCount(): Int = stack.size + 1

    fun restoreToCount(saveCount: Int) {
        while (stack.size >= saveCount.coerceAtLeast(1)) restore()
    }

    fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())
    fun scale(sx: Float, sy: Float) = g.scale(sx.toDouble(), sy.toDouble())
    fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        translate(px, py)
        scale(sx, sy)
        translate(-px, -py)
    }
    fun rotate(degrees: Float) = g.rotate(Math.toRadians(degrees.toDouble()))
    fun rotate(degrees: Float, px: Float, py: Float) = g.rotate(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble())
    fun concat(matrix: Matrix?) {
        matrix?.let { g.transform(it.transform) }
    }

    /** Intersects the clip; returns false when the result is empty (Android's return value). */
    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        g.clip(Rectangle2D.Float(left, top, right - left, bottom - top))
        return g.clip?.bounds2D?.isEmpty == false
    }
    fun clipRect(left: Int, top: Int, right: Int, bottom: Int): Boolean = clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    fun clipRect(r: Rect): Boolean = clipRect(r.left, r.top, r.right, r.bottom)
    fun clipRect(r: RectF): Boolean = clipRect(r.left, r.top, r.right, r.bottom)
    fun clipPath(path: Path): Boolean {
        g.clip(path.awt)
        return g.clip?.bounds2D?.isEmpty == false
    }

    fun drawColor(color: Int) {
        val savedComposite = g.composite
        val savedTransform = g.transform
        g.composite = AlphaComposite.SrcOver
        g.transform = AffineTransform()
        g.color = AwtColor(color, true)
        g.fillRect(0, 0, bitmap.width, bitmap.height)
        g.transform = savedTransform
        g.composite = savedComposite
    }

    fun drawARGB(a: Int, r: Int, gr: Int, b: Int) = drawColor(Color.argb(a, r, gr, b))
    fun drawRGB(r: Int, gr: Int, b: Int) = drawColor(Color.rgb(r, gr, b))
    fun drawPaint(paint: Paint) = drawColor(paint.color)

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        drawShape(Rectangle2D.Float(left, top, right - left, bottom - top), paint)
    fun drawRect(r: Rect, paint: Paint) = drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
    fun drawRect(r: RectF, paint: Paint) = drawRect(r.left, r.top, r.right, r.bottom, paint)

    // Android's rx/ry are corner radii; AWT's arc sizes are diameters.
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) =
        drawShape(RoundRectangle2D.Float(left, top, right - left, bottom - top, rx * 2, ry * 2), paint)
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, paint: Paint) = drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, paint)

    fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        drawShape(Ellipse2D.Float(left, top, right - left, bottom - top), paint)
    fun drawOval(r: RectF, paint: Paint) = drawOval(r.left, r.top, r.right, r.bottom, paint)

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) =
        drawShape(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2), paint)

    fun drawPath(path: Path, paint: Paint) = drawShape(path.awt, paint)

    /** Lines are always stroked on Android, whatever the paint's style. */
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        setUp(paint)
        g.draw(Line2D.Float(startX, startY, stopX, stopY))
    }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        if (text.isEmpty()) return
        val gv = paint.awtFont().createGlyphVector(paint.frc(), text)
        val w = gv.logicalBounds.width.toFloat()
        val dx = when (paint.textAlign) {
            Paint.Align.LEFT -> 0f
            Paint.Align.CENTER -> -w / 2f
            Paint.Align.RIGHT -> -w
        }
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            if (paint.isAntiAlias) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        )
        drawShape(gv.getOutline(x + dx, y), paint)
    }
    fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) = drawText(text.substring(start, end), x, y, paint)
    fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        drawText(text.subSequence(start, end).toString(), x, y, paint)

    private fun setUp(paint: Paint) {
        g.color = AwtColor(paint.color, true)
        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            if (paint.isAntiAlias) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF,
        )
        val cap = when (paint.strokeCap) {
            Paint.Cap.BUTT -> BasicStroke.CAP_BUTT
            Paint.Cap.ROUND -> BasicStroke.CAP_ROUND
            Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE
        }
        val join = when (paint.strokeJoin) {
            Paint.Join.MITER -> BasicStroke.JOIN_MITER
            Paint.Join.ROUND -> BasicStroke.JOIN_ROUND
            Paint.Join.BEVEL -> BasicStroke.JOIN_BEVEL
        }
        // Width 0 is Android's hairline: one pixel whatever the transform.
        g.stroke = BasicStroke(paint.strokeWidth.takeIf { it > 0 } ?: 1f, cap, join, paint.strokeMiter.coerceAtLeast(1f))
    }

    private fun drawShape(shape: Shape, paint: Paint) {
        setUp(paint)
        if (paint.style != Paint.Style.STROKE) g.fill(shape)
        if (paint.style != Paint.Style.FILL) g.draw(shape)
    }

    fun drawBitmap(bmp: Bitmap, left: Float, top: Float, paint: Paint?) =
        withBitmapPaint(paint) { g.drawImage(bmp.awt, AffineTransform.getTranslateInstance(left.toDouble(), top.toDouble()), null) }

    fun drawBitmap(bmp: Bitmap, src: Rect?, dst: Rect, paint: Paint?) =
        drawBitmapRect(bmp, src, dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat(), paint)

    fun drawBitmap(bmp: Bitmap, src: Rect?, dst: RectF, paint: Paint?) =
        drawBitmapRect(bmp, src, dst.left, dst.top, dst.right, dst.bottom, paint)

    fun drawBitmap(bmp: Bitmap, matrix: Matrix, paint: Paint?) =
        withBitmapPaint(paint) { g.drawImage(bmp.awt, matrix.transform, null) }

    private fun drawBitmapRect(bmp: Bitmap, src: Rect?, dl: Float, dt: Float, dr: Float, db: Float, paint: Paint?) {
        val sl = src?.left ?: 0
        val st = src?.top ?: 0
        val sr = src?.right ?: bmp.width
        val sb = src?.bottom ?: bmp.height
        withBitmapPaint(paint) { g.drawImage(bmp.awt, dl.toInt(), dt.toInt(), dr.toInt(), db.toInt(), sl, st, sr, sb, null) }
    }

    private inline fun withBitmapPaint(paint: Paint?, draw: () -> Unit) {
        val savedComposite = g.composite
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            if (paint?.isFilterBitmap == true) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        if (paint != null && paint.alpha < 255) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, paint.alpha / 255f)
        try {
            draw()
        } finally {
            g.composite = savedComposite
        }
    }
}
