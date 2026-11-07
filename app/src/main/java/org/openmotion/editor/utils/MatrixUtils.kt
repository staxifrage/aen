package org.openmotion.editor.utils

import android.opengl.Matrix

/**
 * High performance matrix manipulation helpers for OpenGL ES 2D/3D compositing.
 */
object MatrixUtils {

    /**
     * Creates an orthographic projection matrix mapped to viewport coordinates.
     */
    fun createOrthoMatrix(width: Float, height: Float): FloatArray {
        val m = FloatArray(16)
        Matrix.orthoM(m, 0, -width / 2f, width / 2f, -height / 2f, height / 2f, -1.0f, 1.0f)
        return m
    }

    /**
     * Builds a model transformation matrix into [out] (must be length >= 16).
     * Reuse a single array across frames to avoid GC churn — callers are confined to the GL thread.
     */
    fun buildModelMatrixInto(
        out: FloatArray,
        posX: Float,
        posY: Float,
        scaleX: Float,
        scaleY: Float,
        rotationDeg: Float,
        width: Float,
        height: Float,
        anchorX: Float = 0.5f,
        anchorY: Float = 0.5f
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, posX, posY, 0f)
        if (rotationDeg != 0f) {
            Matrix.rotateM(out, 0, rotationDeg, 0f, 0f, 1f)
        }
        Matrix.scaleM(out, 0, scaleX, scaleY, 1.0f)
        val pivotOffsetX = (0.5f - anchorX) * width
        val pivotOffsetY = (0.5f - anchorY) * height
        Matrix.translateM(out, 0, pivotOffsetX, pivotOffsetY, 0f)
        Matrix.scaleM(out, 0, width / 2f, height / 2f, 1.0f)
    }

    /**
     * Builds a model transformation matrix combining translation, rotation, scale, and anchor pivot.
     * Allocates a new array — prefer [buildModelMatrixInto] on the render hot path.
     */
    fun buildModelMatrix(
        posX: Float,
        posY: Float,
        scaleX: Float,
        scaleY: Float,
        rotationDeg: Float,
        width: Float,
        height: Float,
        anchorX: Float = 0.5f,
        anchorY: Float = 0.5f
    ): FloatArray {
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)

        // 1. Translation to target screen coordinate
        Matrix.translateM(model, 0, posX, posY, 0f)

        // 2. Rotation around Z axis
        if (rotationDeg != 0f) {
            Matrix.rotateM(model, 0, rotationDeg, 0f, 0f, 1f)
        }

        // 3. Scaling
        Matrix.scaleM(model, 0, scaleX, scaleY, 1.0f)

        // 4. Anchor / Pivot offset
        val pivotOffsetX = (0.5f - anchorX) * width
        val pivotOffsetY = (0.5f - anchorY) * height
        Matrix.translateM(model, 0, pivotOffsetX, pivotOffsetY, 0f)

        // 5. Quad dimension sizing
        Matrix.scaleM(model, 0, width / 2f, height / 2f, 1.0f)

        return model
    }
}
