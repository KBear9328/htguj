package com.example.mangareader.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.example.mangareader.model.AnimatableRegion
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

// IoU deduplication helpers
private fun RectF.iou(other: RectF): Float {
    val iL = maxOf(left, other.left); val iT = maxOf(top, other.top)
    val iR = minOf(right, other.right); val iB = minOf(bottom, other.bottom)
    if (iR <= iL || iB <= iT) return 0f
    val inter = (iR - iL) * (iB - iT)
    val union = width() * height() + other.width() * other.height() - inter
    return if (union > 0f) inter / union else 0f
}

private fun List<AnimatableRegion>.distinctByOverlap(threshold: Float): List<AnimatableRegion> {
    val kept = mutableListOf<AnimatableRegion>()
    for (c in this) { if (kept.none { it.sourceRect.iou(c.sourceRect) > threshold }) kept.add(c) }
    return kept
}

object MangaRegionDetector {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
    )

    /**
     * Full detection pipeline:
     * 1. ML Kit face detection → FACE + HAIR + UPPER_BODY per face
     * 2. If no faces → ink-density heuristic fallback
     * 3. MotionItemDetector → SPEED_LINES, PROJECTILE, SWINGING_WEAPON, FLYING_DEBRIS, IMPACT_WAVE
     * 4. Merge all, deduplicate by IoU, sort by priority
     */
    suspend fun detectRegions(panelBitmap: Bitmap): List<AnimatableRegion> =
        withContext(Dispatchers.Default) {
            val faces = detectFaces(panelBitmap)
            val charRegions = if (faces.isNotEmpty()) buildRegionsFromFaces(panelBitmap, faces)
                              else buildRegionsFromHeuristics(panelBitmap)
            val motionRegions = MotionItemDetector.detectMotionRegions(panelBitmap)
            (charRegions + motionRegions)
                .sortedByDescending { it.priority }
                .distinctByOverlap(0.55f)
        }

    private suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { cont ->
            faceDetector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private fun buildRegionsFromFaces(bitmap: Bitmap, faces: List<Face>): List<AnimatableRegion> {
        val regions = mutableListOf<AnimatableRegion>()
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()

        for (face in faces) {
            val fb = face.boundingBox
            val faceRect = RectF(fb.left.toFloat().coerceIn(0f,w), fb.top.toFloat().coerceIn(0f,h),
                                 fb.right.toFloat().coerceIn(0f,w), fb.bottom.toFloat().coerceIn(0f,h))
            if (faceRect.width() < 8f || faceRect.height() < 8f) continue
            val fH = faceRect.height(); val fW = faceRect.width()

            // Hair region — above face
            val hairRect = RectF(max(0f, faceRect.left - fW*0.2f), max(0f, faceRect.top - fH*0.65f),
                                 min(w, faceRect.right + fW*0.2f), faceRect.top + fH*0.1f)
            if (hairRect.height() > 4f && hairRect.width() > 4f)
                regions.add(AnimatableRegion(cropBitmap(bitmap, hairRect), hairRect,
                    AnimatableRegion.RegionType.HAIR, priority = 2))

            // Face region — expanded slightly
            val expandedFace = RectF(max(0f, faceRect.left - fW*0.1f), max(0f, faceRect.top),
                                     min(w, faceRect.right + fW*0.1f), min(h, faceRect.bottom + fH*0.05f))
            regions.add(AnimatableRegion(cropBitmap(bitmap, expandedFace), expandedFace,
                AnimatableRegion.RegionType.FACE, priority = 3))

            // Upper body — below face
            val bodyRect = RectF(max(0f, faceRect.left - fW*0.8f), faceRect.bottom - fH*0.1f,
                                 min(w, faceRect.right + fW*0.8f), min(h, faceRect.bottom + fH*1.8f))
            if (bodyRect.height() > 8f && bodyRect.bottom < h*0.98f)
                regions.add(AnimatableRegion(cropBitmap(bitmap, bodyRect), bodyRect,
                    AnimatableRegion.RegionType.UPPER_BODY, priority = 1))
        }
        return regions
    }

    private fun buildRegionsFromHeuristics(bitmap: Bitmap): List<AnimatableRegion> {
        val regions = mutableListOf<AnimatableRegion>()
        val w = bitmap.width; val h = bitmap.height
        val aw = min(w, 160); val ah = min(h, 200)
        val small = Bitmap.createScaledBitmap(bitmap, aw, ah, false)
        val pixels = IntArray(aw * ah)
        small.getPixels(pixels, 0, aw, 0, 0, aw, ah); small.recycle()

        val gCols = 3; val gRows = 4; val cW = aw / gCols; val cH = ah / gRows
        val densities = Array(gRows) { gy -> FloatArray(gCols) { gx ->
            var dark = 0; var total = 0
            for (py in gy*cH until min((gy+1)*cH,ah)) for (px in gx*cW until min((gx+1)*cW,aw)) {
                val p = pixels[py*aw+px]
                if ((Color.red(p)*0.299+Color.green(p)*0.587+Color.blue(p)*0.114) < 80) dark++; total++
            }
            if (total > 0) dark.toFloat()/total else 0f
        }}

        val candidates = mutableListOf<Triple<Int,Int,Float>>()
        for (gy in 0 until gRows) for (gx in 0 until gCols)
            if (densities[gy][gx] > 0.12f) candidates.add(Triple(gy, gx, densities[gy][gx]))

        if (candidates.isEmpty()) {
            val r = RectF(w*0.1f, h*0.05f, w*0.9f, h*0.85f)
            return listOf(AnimatableRegion(cropBitmap(bitmap, r), r, AnimatableRegion.RegionType.FIGURE, priority = 1))
        }

        val sX = w.toFloat()/aw; val sY = h.toFloat()/ah
        for ((gy, gx, density) in candidates.sortedByDescending { it.third }.take(3)) {
            val rect = RectF((gx*cW*sX).coerceIn(0f,w.toFloat()), (gy*cH*sY).coerceIn(0f,h.toFloat()),
                             ((gx+1)*cW*sX).coerceIn(0f,w.toFloat()), ((gy+1)*cH*sY).coerceIn(0f,h.toFloat()))
            if (rect.width() < 8f || rect.height() < 8f) continue
            val type = when { gy == 0 -> AnimatableRegion.RegionType.HAIR; gy <= 1 -> AnimatableRegion.RegionType.FIGURE; else -> AnimatableRegion.RegionType.UPPER_BODY }
            regions.add(AnimatableRegion(cropBitmap(bitmap, rect), rect, type, priority = (density*10).toInt().coerceIn(1,5)))
        }
        return regions
    }

    private fun cropBitmap(source: Bitmap, rect: RectF): Bitmap {
        val x = rect.left.toInt().coerceIn(0, source.width-1)
        val y = rect.top.toInt().coerceIn(0, source.height-1)
        val w = rect.width().toInt().coerceIn(1, source.width-x)
        val h = rect.height().toInt().coerceIn(1, source.height-y)
        return Bitmap.createBitmap(source, x, y, w, h)
    }
}
