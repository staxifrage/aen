package org.openmotion.editor.model

/**
 * Blend modes supported by the OpenGL ES compositing pipeline.
 * Maps directly to OpenGL ES blend formulas and custom fragment shader equations.
 */
enum class BlendMode(val displayName: String, val glModeId: Int) {
    NORMAL("Normal", 0),
    MULTIPLY("Multiply", 1),
    SCREEN("Screen", 2),
    OVERLAY("Overlay", 3),
    DARKEN("Darken", 4),
    LIGHTEN("Lighten", 5),
    COLOR_DODGE("Color Dodge", 6),
    COLOR_BURN("Color Burn", 7),
    HARD_LIGHT("Hard Light", 8),
    SOFT_LIGHT("Soft Light", 9),
    DIFFERENCE("Difference", 10),
    ADD("Add / Linear Dodge", 11);

    companion object {
        fun fromId(id: Int): BlendMode = entries.firstOrNull { it.glModeId == id } ?: NORMAL
    }
}
