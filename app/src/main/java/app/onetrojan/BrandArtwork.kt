package app.onetrojan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable

internal class BrandBackgroundDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        canvas.drawColor(UiKit.color(UiKit.BACKGROUND))

        path.reset()
        path.moveTo(0f, h * 0.82f)
        path.cubicTo(w * 0.28f, h * 0.96f, w * 0.61f, h * 0.94f, w, h * 0.83f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        paint.shader = LinearGradient(
            0f,
            h * 0.82f,
            w,
            h,
            intArrayOf(Color.rgb(255, 79, 33), Color.rgb(255, 91, 39), Color.rgb(112, 38, 55)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(0f, h * 0.90f)
        path.cubicTo(w * 0.22f, h * 0.78f, w * 0.55f, h * 1.03f, w, h * 0.91f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        paint.shader = LinearGradient(
            0f,
            h * 0.90f,
            w,
            h * 0.95f,
            intArrayOf(Color.rgb(151, 31, 29), Color.rgb(255, 70, 27), Color.rgb(84, 33, 61)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

internal class StatusRibbonDrawable(radius: Float, strokeWidth: Int) : Drawable() {
    private val base = UiKit.rounded(UiKit.SURFACE, radius, UiKit.BORDER, strokeWidth)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        base.bounds = bounds
        base.draw(canvas)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()

        drawCorner(canvas, w, h)
        paint.alpha = 255
        paint.shader = null
    }

    private fun drawCorner(canvas: Canvas, w: Float, h: Float) {
        if (progress <= 0f) return
        val eased = easeOutCubic(progress)
        val startX = w * 0.56f
        val crestY = h * 0.60f
        path.reset()
        path.moveTo(startX, h)
        path.cubicTo(
            w * 0.77f,
            h * 0.98f,
            w * 0.91f,
            h * 0.88f,
            w,
            crestY,
        )
        path.lineTo(w, h)
        path.close()
        paint.style = Paint.Style.FILL
        paint.alpha = (255 * eased).toInt()
        paint.shader = LinearGradient(
            startX,
            h,
            w,
            crestY,
            Color.rgb(211, 47, 19),
            Color.rgb(255, 87, 38),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
    }

    private fun easeOutCubic(value: Float): Float {
        val inverse = 1f - value
        return 1f - inverse * inverse * inverse
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        base.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        base.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
