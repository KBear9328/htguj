package com.example.mangareader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PanelHighlightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var panelNormRect: RectF? = null
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    private val dimPaint   = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 200, 50)
        strokeWidth = 4f; isAntiAlias = true
    }
    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val highlightRect = RectF()

    fun setPanel(normalizedRect: RectF, srcWidth: Int, srcHeight: Int) {
        panelNormRect = normalizedRect; sourceWidth = srcWidth; sourceHeight = srcHeight; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val norm = panelNormRect ?: return
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width  - sourceWidth  * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f
        highlightRect.set(
            offsetX + norm.left  * sourceWidth  * scale, offsetY + norm.top    * sourceHeight * scale,
            offsetX + norm.right * sourceWidth  * scale, offsetY + norm.bottom * sourceHeight * scale)
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(highlightRect, clearPaint)
        canvas.restore()
        canvas.drawRoundRect(highlightRect, 8f, 8f, borderPaint)
    }
}
