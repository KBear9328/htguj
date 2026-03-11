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
 * Refined to eliminate random spinning artifacts and reverberation.
 */
class AnimatedPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var panelBitmap: Bitmap? = null
    private var regions: List<AnimatableRegion> = emptyList()
    private val displayMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    
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
        
        // Very subtle breathing for characters
        val charAmp = (baseScale * 2f).coerceIn(0.5f, 3.5f)
        val f1 = 0.7f 

        for (region in regions) {
            val np = region.currentPhase + dtSec * f1
            region.currentPhase = np

            when (region.regionType) {
                AnimatableRegion.RegionType.UPPER_BODY, 
                AnimatableRegion.RegionType.FACE, 
                AnimatableRegion.RegionType.FIGURE -> {
                    val breathing = sin(np.toDouble()).toFloat()
                    region.offsetY = breathing * charAmp
                    region.scaleY = 1f - breathing * 0.003f // Negligible squash
                }
                
                AnimatableRegion.RegionType.HAIR -> {
                    val sway = sin((np * 0.5).toDouble()).toFloat()
                    region.offsetX = sway * charAmp * 0.2f
                }

                AnimatableRegion.RegionType.PROJECTILE -> {
                    // Projectiles move in a single direction, no spinning squares
                    val t = (region.currentPhase * 1.5f) % 2f
                    val cycleT = if (t > 1f) 2f - t else t // Ping-pong movement
                    val (mvx, mvy) = region.motionVector
                    region.offsetX = mvx * cycleT * 15f * baseScale
                    region.offsetY = mvy * cycleT * 15f * baseScale
                    region.rotation = 0f // No more spinning
                }
                
                AnimatableRegion.RegionType.SPEED_LINES -> {
                    val t = (region.currentPhase * 3f) % 1f
                    region.scaleX = 1f + t * 0.03f
                    region.scaleY = 1f + t * 0.03f
                    region.alpha = 1f - t
                }

                else -> { 
                    region.offsetX = 0f; region.offsetY = 0f; region.rotation = 0f
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = panelBitmap ?: return

        val panelRect = RectF(0f, 0f, bm.width.toFloat(), bm.height.toFloat())
        displayMatrix.mapRect(panelRect)

        canvas.save()
        canvas.clipRect(panelRect) 
        canvas.drawBitmap(bm, displayMatrix, paint)

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
        val scale = min(width.toFloat() / bm.width, height.toFloat() / bm.height)
        displayMatrix.setScale(scale, scale)
        displayMatrix.postTranslate((width - bm.width * scale) / 2f, (height - bm.height * scale) / 2f)
    }
}
