package org.openmotion.editor.model

/**
 * Shader / transform driven layer effects.
 *
 * Ids 1..6 are applied inside the fragment shader (single-pass approximation).
 * Id 7 (SHAKE) is applied on the CPU as a transform jitter before building the
 * model matrix, so it works on every layer type including shapes and text.
 */
enum class EffectType(val displayName: String, val id: Int) {
    NONE("None", 0),
    BLUR("Gaussian Blur", 1),
    CHROMA_KEY("Chroma Key", 2),
    VIGNETTE("Vignette", 3),
    EXPOSURE("Exposure", 4),
    GLOW("Soft Glow", 5),
    RGB_SPLIT("RGB Split", 6),
    SHAKE("Camera Shake", 7);

    companion object {
        fun fromId(id: Int): EffectType = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * A single effect instance on a layer.
 *
 * @param intensity master 0..1 amount
 * @param param1    effect-specific (blur radius in texels, chroma threshold, glow threshold, ...)
 * @param param2    effect-specific (chroma edge softness, ...)
 * @param param3    effect-specific packed RGB int (chroma key color, ...)
 */
data class Effect(
    val type: EffectType,
    var enabled: Boolean = true,
    var intensity: Float = 0.5f,
    var param1: Float = 0f,
    var param2: Float = 0f,
    var param3: Int = 0x00FF00
) {
    companion object {
        /** Sensible defaults per effect type so a newly added effect looks right. */
        fun create(type: EffectType): Effect = when (type) {
            EffectType.BLUR -> Effect(type, intensity = 0.5f, param1 = 8f, param2 = 0f)
            EffectType.CHROMA_KEY -> Effect(type, intensity = 1f, param1 = 0.45f, param2 = 0.12f, param3 = 0x00FF00)
            EffectType.VIGNETTE -> Effect(type, intensity = 0.6f, param1 = 0.5f)
            EffectType.EXPOSURE -> Effect(type, intensity = 1f, param1 = 0.5f) // 0.5 = 0 stops
            EffectType.GLOW -> Effect(type, intensity = 0.6f, param1 = 0.65f) // param1 = bright-pass threshold
            EffectType.RGB_SPLIT -> Effect(type, intensity = 0.5f, param1 = 6f)
            EffectType.SHAKE -> Effect(type, intensity = 0.5f, param1 = 24f) // param1 = max pixel amplitude
            EffectType.NONE -> Effect(EffectType.NONE)
        }
    }

    /** Human label for the first adjustable slider. */
    fun param1Label(): String = when (type) {
        EffectType.BLUR -> "Radius"
        EffectType.CHROMA_KEY -> "Threshold"
        EffectType.VIGNETTE -> "Amount"
        EffectType.EXPOSURE -> "Stops"
        EffectType.GLOW -> "Threshold"
        EffectType.RGB_SPLIT -> "Offset"
        EffectType.SHAKE -> "Amplitude"
        EffectType.NONE -> "Amount"
    }
}
