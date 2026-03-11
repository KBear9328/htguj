package com.example.mangareader.model

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * A detected region inside a manga panel that will be independently animated.
 * 
 * We use a regular class (not data class) for the stateful parts to avoid
 * hashCode changes during animation which would break Map-based lookups.
 */
class AnimatableRegion(
    val bitmap: Bitmap,
    val sourceRect: RectF,
    val regionType: RegionType,
    val priority: Int = 1,
    val motionVector: Pair<Float, Float> = Pair(0f, 0f),
    val swingAnchor: Pair<Float, Float> = Pair(0.5f, 0f)
) {
    enum class RegionType {
        FACE, HAIR, UPPER_BODY, HAND, FIGURE, GENERAL,
        SPEED_LINES, PROJECTILE, SWINGING_WEAPON, FLYING_DEBRIS, IMPACT_WAVE, EFFECT
    }

    // Runtime animation state - these are NOT used in equals/hashCode
    var currentPhase: Float = 0f
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var alpha: Float = 1f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimatableRegion) return false
        return sourceRect == other.sourceRect && regionType == other.regionType
    }

    override fun hashCode(): Int {
        var result = sourceRect.hashCode()
        result = 31 * result + regionType.hashCode()
        return result
    }
}
