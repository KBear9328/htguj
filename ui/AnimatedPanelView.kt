package com.example.mangareader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import com.example.mangareader.model.AnimatableRegion
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom View that renders a manga panel with per-region independent animation.
 * Strictly zooms the "scene" (the area within the detected black border) to fit the screen.
 */
class AnimatedPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var panelBitmap: Bitmap? = null
    private var regions: List<AnimatableRegion> = emptyList()
    private val displayMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    
    // The black border is part of the art, but we draw a clean frame to emphasize the "scene"
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 10f 
    }

    private var isAnimating = false
    private var lastFrameTime = 0L

    fun setPanel(bitmap: Bitmap, detectedRegions: List<AnimatableRegion>) {
        stopAnimations()
        panelBitmap = bitmap
        regions = detectedRegions
        recalcMatrix()
        invalidate()
    }

    fun startAnimations() {
        if (isAnimating) return
        isAnimating = true
        lastFrameTime = System.nanoTime()
        postFrameCallback()
    }

    fun stopAnimations() {
        isAnimating = false
        regions.forEach { 
            it.offsetX = 0f; it.offsetY = 0f; it.rotation = 0f; it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f 
        }
        invalidate()
    }

    private fun postFrameCallback() {
        if (!isAnimating) return
        postOnAnimation {
            val now = System.nanoTime()
            val dt  = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastFrameTime = now
            advanceAnimations(dt)
            invalidate()
            if (isAnimating) postFrameCallback()
        }
    }

    private fun advanceAnimations(dtSec: Float) {
        val bm = panelBitmap ?: return
        val baseScale = min(bm.width, bm.height) / 400f
        val charAmp = (baseScale * 7f).coerceIn(2f, 12f)
        val f1 = 1.15f 

        for (region in regions) {
            val np = region.currentPhase + dtSec * f1
            region.currentPhase = np

            when (region.regionType) {
                AnimatableRegion.RegionType.UPPER_BODY -> {
                    val bounce = sin(np.toDouble()).toFloat()
                    val sway = sin((np * 1.618).toDouble()).toFloat()
                    region.offsetX = sway * charAmp * 0.6f
                    region.offsetY = bounce * charAmp * 0.8f
                    region.rotation = sway * 1.2f
                    region.scaleX = 1f + bounce * 0.015f
                    region.scaleY = 1f - bounce * 0.02f
                }
                AnimatableRegion.RegionType.FACE -> {
                    val lag = 0.25f
                    val bounce = sin((np - lag).toDouble()).toFloat()
                    val sway = sin(((np - lag) * 1.618).toDouble()).toFloat()
                    region.offsetX = sway * charAmp * 0.4f
                    region.offsetY = bounce * charAmp * 0.6f
                    region.rotation = sway * 1.8f
                }
                AnimatableRegion.RegionType.HAIR -> {
                    val lag = 0.65f
                    val sway = sin(((np - lag) * 1.618).toDouble()).toFloat()
                    val drift = sin((np * 3.141).toDouble()).toFloat()
                    region.offsetX = sway * charAmp * 1.2f + drift * charAmp * 0.4f
                    region.offsetY = sin((np - lag).toDouble()).toFloat() * charAmp * 0.5f
                    region.rotation = sway * 3.5f + drift * 1f
                }
                else -> {
                    val bounce = sin(np.toDouble()).toFloat()
                    val sway = sin((np * 1.618).toDouble()).toFloat()
                    region.offsetX = sway * charAmp * 0.4f
                    region.offsetY = bounce * charAmp * 0.4f
                    region.rotation = sway * 0.7f
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = panelBitmap ?: return

        val panelRect = RectF(0f, 0f, bm.width.toFloat(), bm.height.toFloat())
        displayMatrix.mapRect(panelRect)

        // 1. Scene background (the manga panel art)
        canvas.save()
        canvas.clipRect(panelRect) 
        canvas.drawBitmap(bm, displayMatrix, paint)

        // 2. Animated layers (the characters)
        for (region in regions) {
            val src = region.sourceRect
            val mapped = floatArrayOf(src.left, src.top, src.right, src.bottom)
            displayMatrix.mapPoints(mapped)
            val dstLeft = mapped[0]; val dstTop = mapped[1]
            val dstW = mapped[2] - dstLeft; val dstH = mapped[3] - dstTop
            if (dstW < 1f || dstH < 1f) continue

            val pivotX = dstW / 2f; val pivotY = dstH / 2f
            paint.alpha = (region.alpha * 255).toInt()

            canvas.save()
            canvas.translate(dstLeft + region.offsetX, dstTop + region.offsetY)
            canvas.rotate(region.rotation, pivotX, pivotY)
            canvas.scale(region.scaleX, region.scaleY, pivotX, pivotY)
            canvas.drawBitmap(region.bitmap, null, RectF(0f, 0f, dstW, dstH), paint)
            canvas.restore()
        }
        canvas.restore()

        // 3. Clean Black Frame - Ensures the "scene" looks perfectly defined
        canvas.drawRect(panelRect, framePaint)
        
        paint.alpha = 255
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { 
        super.onSizeChanged(w,h,oldw,oldh)
        recalcMatrix() 
    }

    private fun recalcMatrix() {
        val bm = panelBitmap ?: return
        if (width == 0 || height == 0) return
        
        // Zoom to fit the entire screen width/height with zero margin for full immersion
        val scale = min(width.toFloat() / bm.width, height.toFloat() / bm.height)
        displayMatrix.setScale(scale, scale)
        displayMatrix.postTranslate((width - bm.width * scale) / 2f, (height - bm.height * scale) / 2f)
    }
}
