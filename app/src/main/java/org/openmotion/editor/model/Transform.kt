package org.openmotion.editor.model

/**
 * Geometric transform and visual parameters for a layer.
 * Includes keyframed tracks for animated translation, scale, rotation, and opacity.
 */
data class Transform(
    var posX: Float = 0f,
    var posY: Float = 0f,
    var scaleX: Float = 1.0f,
    var scaleY: Float = 1.0f,
    var rotationDegrees: Float = 0f,
    var anchorX: Float = 0.5f, // Normalized 0..1 pivot
    var anchorY: Float = 0.5f,
    var opacity: Float = 1.0f,  // 0.0 to 1.0

    // Keyframe timelines for animated properties
    val posXKeyframes: MutableList<Keyframe> = mutableListOf(),
    val posYKeyframes: MutableList<Keyframe> = mutableListOf(),
    val scaleKeyframes: MutableList<Keyframe> = mutableListOf(),
    val rotationKeyframes: MutableList<Keyframe> = mutableListOf(),
    val opacityKeyframes: MutableList<Keyframe> = mutableListOf()
) {
    /**
     * Samples the evaluated transform values at given project time [timeMs].
     */
    fun evaluateAt(timeMs: Long): EvaluatedTransform {
        return EvaluatedTransform(
            posX = interpolateChannel(posXKeyframes, posX, timeMs),
            posY = interpolateChannel(posYKeyframes, posY, timeMs),
            scaleX = interpolateChannel(scaleKeyframes, scaleX, timeMs),
            scaleY = interpolateChannel(scaleKeyframes, scaleY, timeMs),
            rotationDegrees = interpolateChannel(rotationKeyframes, rotationDegrees, timeMs),
            anchorX = anchorX,
            anchorY = anchorY,
            opacity = interpolateChannel(opacityKeyframes, opacity, timeMs).coerceIn(0f, 1f)
        )
    }

    private fun interpolateChannel(keyframes: List<Keyframe>, defaultValue: Float, timeMs: Long): Float {
        if (keyframes.isEmpty()) return defaultValue
        if (keyframes.size == 1) return keyframes[0].value
        if (timeMs <= keyframes.first().timeMs) return keyframes.first().value
        if (timeMs >= keyframes.last().timeMs) return keyframes.last().value

        // Find surrounding keyframes
        for (i in 0 until keyframes.size - 1) {
            val kf0 = keyframes[i]
            val kf1 = keyframes[i + 1]
            if (timeMs in kf0.timeMs..kf1.timeMs) {
                val span = (kf1.timeMs - kf0.timeMs).toFloat()
                if (span <= 0f) return kf0.value
                val rawProgress = (timeMs - kf0.timeMs) / span
                val easedProgress = kf0.easing.evaluate(rawProgress)
                return kf0.value + (kf1.value - kf0.value) * easedProgress
            }
        }
        return defaultValue
    }
}

/**
 * Snapshot of evaluated transform parameters at a specific time sample.
 */
data class EvaluatedTransform(
    val posX: Float,
    val posY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
    val anchorX: Float,
    val anchorY: Float,
    val opacity: Float
)
