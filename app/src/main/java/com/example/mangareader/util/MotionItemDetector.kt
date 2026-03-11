package com.example.mangareader.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.example.mangareader.model.AnimatableRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Detects motion items in a manga panel:
 *   SPEED_LINES   — parallel/radial ink streaks
 *   PROJECTILE    — small isolated high-contrast blob away from character
 *   SWINGING_WEAPON — elongated dark shape with high aspect ratio
 */
object MotionItemDetector {

    private const val ANALYSIS_W    = 300
    private const val DARK_THRESHOLD = 60
    private const val STREAK_MIN_LEN = 12

    suspend fun detectMotionRegions(panelBitmap: Bitmap): List<AnimatableRegion> =
        withContext(Dispatchers.Default) {
            val w  = panelBitmap.width
            val h  = panelBitmap.height
            val scale = ANALYSIS_W.toFloat() / w
            val ah = (h * scale).toInt().coerceAtLeast(1)
            val aw = ANALYSIS_W

            val small  = Bitmap.createScaledBitmap(panelBitmap, aw, ah, false)
            val pixels = IntArray(aw * ah)
            small.getPixels(pixels, 0, aw, 0, 0, aw, ah)
            small.recycle()

            val dark = Array(ah) { row -> BooleanArray(aw) { col -> brightness(pixels[row * aw + col]) < DARK_THRESHOLD } }

            val results = mutableListOf<AnimatableRegion>()
            results.addAll(detectSpeedLines(dark, aw, ah, panelBitmap, scale))
            results.addAll(detectProjectiles(dark, aw, ah, panelBitmap, scale))
            results.addAll(detectSwingingWeapons(dark, aw, ah, panelBitmap, scale))
            // Removed debris and impact waves as they often detect random background noise or text as squares
            results
        }

    private fun detectSpeedLines(dark: Array<BooleanArray>, aw: Int, ah: Int, source: Bitmap, scale: Float): List<AnimatableRegion> {
        val hCounts = countStreaks(dark, aw, ah, true)
        val vCounts = countStreaks(dark, aw, ah, false)
        val hScore  = hCounts.maxOrNull() ?: 0
        val vScore  = vCounts.maxOrNull() ?: 0
        val results = mutableListOf<AnimatableRegion>()
        
        // Only accept very high confidence speed lines
        if (hScore >= 5 || vScore >= 5) {
            if (hScore >= vScore) {
                val best  = hCounts.indexOfMax()
                val bandH = ah / hCounts.size
                results.add(makeRegion(source, normRect(0, best * bandH, aw, (best + 1) * bandH, aw, ah, scale),
                    AnimatableRegion.RegionType.SPEED_LINES, Pair(1f, 0f), priority = 2))
            } else {
                val best  = vCounts.indexOfMax()
                val bandW = aw / vCounts.size
                results.add(makeRegion(source, normRect(best * bandW, 0, (best + 1) * bandW, ah, aw, ah, scale),
                    AnimatableRegion.RegionType.SPEED_LINES, Pair(0f, 1f), priority = 2))
            }
        }
        return results
    }

    private fun countStreaks(dark: Array<BooleanArray>, aw: Int, ah: Int, horizontal: Boolean): IntArray {
        val numBands = 4; val counts = IntArray(numBands)
        if (horizontal) {
            for (row in 0 until ah) {
                val band = (row * numBands / ah).coerceIn(0, numBands - 1)
                var run = 0
                for (col in 0 until aw) {
                    if (dark[row][col]) run++ else { if (run >= STREAK_MIN_LEN && run < aw * 0.7f) counts[band]++; run = 0 }
                }
            }
        } else {
            for (col in 0 until aw) {
                val band = (col * numBands / aw).coerceIn(0, numBands - 1)
                var run = 0
                for (row in 0 until ah) {
                    if (dark[row][col]) run++ else { if (run >= STREAK_MIN_LEN && run < ah * 0.7f) counts[band]++; run = 0 }
                }
            }
        }
        return counts
    }

    private fun detectProjectiles(dark: Array<BooleanArray>, aw: Int, ah: Int, source: Bitmap, scale: Float): List<AnimatableRegion> {
        val gw = 10; val gh = 10; val cellW = aw / gw; val cellH = ah / gh
        val density = Array(gh) { gy -> FloatArray(gw) { gx ->
            var d = 0; var t = 0
            for (py in gy * cellH until min((gy + 1) * cellH, ah))
                for (px in gx * cellW until min((gx + 1) * cellW, aw)) { if (dark[py][px]) d++; t++ }
            if (t > 0) d.toFloat() / t else 0f
        }}
        
        // Find main character (highest density) to avoid detecting them as projectile
        var maxD = 0f; var charGx = 0; var charGy = 0
        for (gy in 0 until gh) for (gx in 0 until gw) if (density[gy][gx] > maxD) { maxD = density[gy][gx]; charGx = gx; charGy = gy }

        val results = mutableListOf<AnimatableRegion>()
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val d = density[gy][gx]
                // Small, isolated dark blob
                if (d < 0.2f || d > 0.6f) continue
                if (hypot((gx - charGx).toFloat(), (gy - charGy).toFloat()) < 3f) continue
                
                var neighborDensity = 0f; var neighbors = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = gx + dx; val ny = gy + dy
                    if (nx in 0 until gw && ny in 0 until gh) { neighborDensity += density[ny][nx]; neighbors++ }
                }
                if (neighbors > 0 && neighborDensity / neighbors > 0.15f) continue // Not isolated enough
                
                val dx = (gx - charGx).toFloat(); val dy = (gy - charGy).toFloat()
                val len = hypot(dx, dy).coerceAtLeast(0.01f)
                val rect = normRect(gx * cellW, gy * cellH, min((gx+1)*cellW, aw), min((gy+1)*cellH, ah), aw, ah, scale)
                results.add(makeRegion(source, rect, AnimatableRegion.RegionType.PROJECTILE, Pair(dx/len, dy/len), priority = 3))
            }
        }
        return results.take(2)
    }

    private fun detectSwingingWeapons(dark: Array<BooleanArray>, aw: Int, ah: Int, source: Bitmap, scale: Float): List<AnimatableRegion> {
        val results = mutableListOf<AnimatableRegion>()
        val gw = 12; val gh = 12; val cellW = aw / gw; val cellH = ah / gh
        val occ = Array(gh) { gy -> BooleanArray(gw) { gx ->
            var d = 0; var t = 0
            for (py in gy * cellH until min((gy+1)*cellH, ah))
                for (px in gx * cellW until min((gx+1)*cellW, aw)) { if (dark[py][px]) d++; t++ }
            t > 0 && d.toFloat() / t > 0.40f
        }}
        
        for (gx in 0 until gw) {
            var runStart = -1; var runLen = 0
            for (gy in 0 until gh) {
                if (occ[gy][gx]) { if (runStart < 0) runStart = gy; runLen++ }
                else {
                    if (runLen >= 4 && runLen < gh * 0.6f) {
                        results.add(makeRegion(source,
                            normRect(gx*cellW, runStart*cellH, min((gx+1)*cellW,aw), min((runStart+runLen)*cellH,ah), aw, ah, scale),
                            AnimatableRegion.RegionType.SWINGING_WEAPON, Pair(0f,1f),
                            swingAnchor = Pair(0.5f, 0f), priority = 3))
                    }
                    runStart = -1; runLen = 0
                }
            }
        }
        return results.take(1)
    }

    private fun brightness(pixel: Int): Int {
        return (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
    }

    private fun normRect(ax0: Int, ay0: Int, ax1: Int, ay1: Int, aw: Int, ah: Int, scale: Float): RectF {
        val inv = 1f / scale
        return RectF(ax0 * inv, ay0 * inv, ax1 * inv, ay1 * inv)
    }

    private fun makeRegion(source: Bitmap, rect: RectF, type: AnimatableRegion.RegionType,
                           motionVector: Pair<Float,Float> = Pair(0f,0f),
                           swingAnchor: Pair<Float,Float> = Pair(0.5f,0f),
                           priority: Int = 1): AnimatableRegion {
        val x = rect.left.toInt().coerceIn(0, source.width - 1)
        val y = rect.top.toInt().coerceIn(0, source.height - 1)
        val w = rect.width().toInt().coerceIn(1, source.width - x)
        val h = rect.height().toInt().coerceIn(1, source.height - y)
        return AnimatableRegion(
            bitmap = Bitmap.createBitmap(source, x, y, w, h),
            sourceRect = RectF(x.toFloat(), y.toFloat(), (x+w).toFloat(), (y+h).toFloat()),
            regionType = type, motionVector = motionVector, swingAnchor = swingAnchor, priority = priority)
    }

    private fun IntArray.indexOfMax(): Int { var b = 0; for (i in indices) if (this[i] > this[b]) b = i; return b }
}
