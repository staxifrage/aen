package org.openmotion.editor.model

import java.util.UUID

/**
 * Root video project document containing format specs and layer composition hierarchy.
 */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Untitled Project",
    var width: Int = 1080,
    var height: Int = 1920, // Default 9:16 vertical video (or configurable to 16:9, 1:1, 4:5)
    var frameRate: Int = 60,
    var durationMs: Long = 10000L,
    val layers: MutableList<Layer> = mutableListOf()
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1.0f

    /**
     * Returns all layers active at [timeMs], sorted from bottom to top by zIndex.
     */
    fun getActiveLayersAt(timeMs: Long): List<Layer> {
        return layers
            .filter { it.isActiveAt(timeMs) }
            .sortedBy { it.zIndex }
    }
}
