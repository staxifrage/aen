package org.openmotion.editor.model

import java.util.UUID

enum class LayerType {
    VIDEO,
    IMAGE,
    AUDIO,
    SHAPE,
    TEXT
}

/**
 * Basic color grading parameters for a layer.
 */
data class ColorGrading(
    var brightness: Float = 0.0f,   // -1.0 to 1.0
    var contrast: Float = 1.0f,     // 0.0 to 2.0
    var saturation: Float = 1.0f,   // 0.0 to 2.0
    var tintColorRgb: Int = 0xFFFFFF // Hex RGB
)

/**
 * Multi-track editor layer representing any visual or auditory component.
 */
data class Layer(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Layer",
    var type: LayerType = LayerType.VIDEO,
    var startTimeMs: Long = 0L,
    var endTimeMs: Long = 5000L,
    var mediaOffsetMs: Long = 0L, // In-point offset into source media
    var mediaSourceUri: String? = null,
    var zIndex: Int = 0,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var blendMode: BlendMode = BlendMode.NORMAL,
    val transform: Transform = Transform(),
    val colorGrading: ColorGrading = ColorGrading(),
    var shapeType: String = "RECTANGLE",
    var textContent: String = ""
) {
    /**
     * Checks if this layer is active at the specified project playback position.
     */
    fun isActiveAt(timeMs: Long): Boolean {
        return isVisible && timeMs in startTimeMs..endTimeMs
    }

    val durationMs: Long
        get() = (endTimeMs - startTimeMs).coerceAtLeast(0L)
}
