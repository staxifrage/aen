package org.openmotion.editor.engine.pipeline

import android.graphics.SurfaceTexture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages zero-copy SurfaceTexture frame synchronization.
 * Prevents queue overrun and ensures frames are only latched onto the GPU when needed.
 */
class FrameQueueManager(
    val surfaceTexture: SurfaceTexture,
    val textureId: Int
) : SurfaceTexture.OnFrameAvailableListener {

    private val frameAvailable = AtomicBoolean(false)
    val texMatrix = FloatArray(16)

    init {
        surfaceTexture.setOnFrameAvailableListener(this)
        android.opengl.Matrix.setIdentityM(texMatrix, 0)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
    }

    /**
     * Latches the latest video frame into the OpenGL ES texture if available.
     * Returns true if a new frame was latched.
     */
    fun updateIfAvailable(): Boolean {
        if (frameAvailable.compareAndSet(true, false)) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            return true
        }
        return false
    }

    fun release() {
        surfaceTexture.setOnFrameAvailableListener(null)
        surfaceTexture.release()
    }
}
