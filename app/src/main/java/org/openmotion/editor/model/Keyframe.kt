package org.openmotion.editor.model

/**
 * Keyframe interpolation curve definition.
 * Uses cubic bezier control points (p1x, p1y, p2x, p2y) equivalent to CSS / AfterEffects easing curves.
 */
data class EasingCurve(
    val p1x: Float = 0.25f,
    val p1y: Float = 0.1f,
    val p2x: Float = 0.25f,
    val p2y: Float = 1.0f
) {
    companion object {
        val LINEAR = EasingCurve(0.0f, 0.0f, 1.0f, 1.0f)
        val EASE_IN = EasingCurve(0.42f, 0.0f, 1.0f, 1.0f)
        val EASE_OUT = EasingCurve(0.0f, 0.0f, 0.58f, 1.0f)
        val EASE_IN_OUT = EasingCurve(0.42f, 0.0f, 0.58f, 1.0f)
    }

    /**
     * Evaluates normalized progress t [0..1] along the cubic Bezier curve.
     */
    fun evaluate(t: Float): Float {
        if (this == LINEAR) return t
        val clamped = t.coerceIn(0.0f, 1.0f)
        // Approximate cubic bezier solve for Y given X=clamped
        var currentT = clamped
        for (i in 0 until 5) {
            val currentX = sampleCurveX(currentT)
            val dx = sampleCurveDerivativeX(currentT)
            if (kotlin.math.abs(dx) < 1e-5) break
            currentT -= (currentX - clamped) / dx
            currentT = currentT.coerceIn(0.0f, 1.0f)
        }
        return sampleCurveY(currentT)
    }

    private fun sampleCurveX(t: Float): Float {
        val invT = 1.0f - t
        return 3.0f * invT * invT * t * p1x + 3.0f * invT * t * t * p2x + t * t * t
    }

    private fun sampleCurveDerivativeX(t: Float): Float {
        val invT = 1.0f - t
        return 3.0f * invT * invT * p1x + 6.0f * invT * t * (p2x - p1x) + 3.0f * t * t * (1.0f - p2x)
    }

    private fun sampleCurveY(t: Float): Float {
        val invT = 1.0f - t
        return 3.0f * invT * invT * t * p1y + 3.0f * invT * t * t * p2y + t * t * t
    }
}

/**
 * A discrete keyframe on a layer timeline for a scalar property.
 */
data class Keyframe(
    val timeMs: Long,
    val value: Float,
    val easing: EasingCurve = EasingCurve.EASE_IN_OUT
)
