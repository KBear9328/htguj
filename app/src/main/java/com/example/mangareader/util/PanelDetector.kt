package com.example.mangareader.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.example.mangareader.model.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PanelDetector {

    private const val ANALYSIS_WIDTH = 500 // Reduced for speed
    private const val DARK_THRESHOLD = 50 
    
    suspend fun detectPanels(pageBitmap: Bitmap): List<Panel> = withContext(Dispatchers.Default) {
        try {
            val scaleFactor = ANALYSIS_WIDTH.toFloat() / pageBitmap.width
            val scaledH = (pageBitmap.height * scaleFactor).toInt()
            val smallBitmap = Bitmap.createScaledBitmap(pageBitmap, ANALYSIS_WIDTH, scaledH, false)
            val pixels = IntArray(ANALYSIS_WIDTH * scaledH)
            smallBitmap.getPixels(pixels, 0, ANALYSIS_WIDTH, 0, 0, ANALYSIS_WIDTH, scaledH)

            // 1. Find potential horizontal and vertical "black line" border candidates
            val hBorders = findLineBorders(pixels, ANALYSIS_WIDTH, scaledH, true)
            val vBorders = findLineBorders(pixels, ANALYSIS_WIDTH, scaledH, false)

            val candidateRects = mutableListOf<RectF>()
            
            // 2. Iterate through border intersections to find valid panels
            // We limit the number of lines to prevent O(N^4) explosion if too many dark lines are found
            val maxLines = 15
            val limitedH = if (hBorders.size > maxLines) hBorders.take(maxLines) else hBorders
            val limitedV = if (vBorders.size > maxLines) vBorders.take(maxLines) else vBorders

            for (i in 0 until limitedH.size - 1) {
                for (j in i + 1 until limitedH.size) {
                    val top = limitedH[i]; val bottom = limitedH[j]
                    if (bottom - top < scaledH * 0.1f) continue
                    
                    for (k in 0 until limitedV.size - 1) {
                        for (l in k + 1 until limitedV.size) {
                            val left = limitedV[k]; val right = limitedV[l]
                            if (right - left < ANALYSIS_WIDTH * 0.15f) continue
                            
                            // 3. Check if this rectangle is defined by a black border
                            if (isRectangleFullyBordered(pixels, ANALYSIS_WIDTH, left, top, right, bottom)) {
                                candidateRects.add(RectF(
                                    left.toFloat() / ANALYSIS_WIDTH,
                                    top.toFloat() / scaledH,
                                    right.toFloat() / ANALYSIS_WIDTH,
                                    bottom.toFloat() / scaledH
                                ))
                            }
                        }
                    }
                }
            }
            
            smallBitmap.recycle()

            val filtered = filterNestedRects(candidateRects)
            val sorted = sortMangaOrder(if (filtered.isEmpty()) listOf(RectF(0.02f, 0.02f, 0.98f, 0.98f)) else filtered)

            sorted.mapIndexed { index, rect ->
                Panel(rect = rect, bitmap = cropPanel(pageBitmap, rect), readingOrder = index)
            }
        } catch (e: Exception) {
            listOf(Panel(rect = RectF(0f,0f,1f,1f), bitmap = pageBitmap, readingOrder = 0))
        }
    }

    private fun isRectangleFullyBordered(pixels: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
        var darkCount = 0
        var totalCount = 0
        val step = 4 // Subsample border check for speed
        
        for (x in l..r step step) {
            if (isDark(pixels[t * w + x])) darkCount++
            if (isDark(pixels[b * w + x])) darkCount++
            totalCount += 2
        }
        for (y in t..b step step) {
            if (isDark(pixels[y * w + l])) darkCount++
            if (isDark(pixels[y * w + r])) darkCount++
            totalCount += 2
        }
        
        return if (totalCount == 0) false else darkCount.toFloat() / totalCount > 0.70f
    }

    private fun findLineBorders(pixels: IntArray, w: Int, h: Int, horizontal: Boolean): List<Int> {
        val lines = mutableListOf<Int>()
        val limit = if (horizontal) h else w
        val span = if (horizontal) w else h
        
        for (i in 0 until limit step 2) {
            var darkInLine = 0
            val step = max(1, span / 40)
            var samples = 0
            for (j in 0 until span step step) {
                val p = if (horizontal) pixels[i * w + j] else pixels[j * w + i]
                if (isDark(p)) darkInLine++
                samples++
            }
            if (darkInLine.toFloat() / samples > 0.40f) {
                if (lines.isEmpty() || i - lines.last() > 12) lines.add(i)
            }
        }
        return lines
    }

    private fun isDark(pixel: Int): Boolean {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        return r < DARK_THRESHOLD && g < DARK_THRESHOLD && b < DARK_THRESHOLD
    }

    private fun filterNestedRects(rects: List<RectF>): List<RectF> {
        val sorted = rects.sortedByDescending { it.width() * it.height() }
        val result = mutableListOf<RectF>()
        for (r in sorted) {
            if (result.none { it.contains(r) }) result.add(r)
        }
        return result
    }

    private fun sortMangaOrder(panels: List<RectF>): List<RectF> {
        val rows = mutableListOf<MutableList<RectF>>()
        for (p in panels.sortedBy { it.top }) {
            var found = false
            for (row in rows) {
                if (abs(p.centerY() - row[0].centerY()) < 0.12f) {
                    row.add(p); found = true; break
                }
            }
            if (!found) rows.add(mutableListOf(p))
        }
        return rows.flatMap { row -> row.sortedByDescending { it.left } }
    }

    private fun cropPanel(source: Bitmap, rect: RectF): Bitmap {
        val x = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (rect.top * source.height).toInt().coerceIn(0, source.height - 1)
        val w = (rect.width() * source.width).toInt().coerceIn(1, source.width - x)
        val h = (rect.height() * source.height).toInt().coerceIn(1, source.height - y)
        return Bitmap.createBitmap(source, x, y, w, h)
    }
}
