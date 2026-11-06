package org.openmotion.editor.engine.pipeline

import android.content.Context
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import org.openmotion.editor.engine.decoder.TrackMediaDecoder
import org.openmotion.editor.engine.gl.EglCore
import org.openmotion.editor.engine.gl.ImageTextureHelper
import org.openmotion.editor.engine.gl.LayerRenderer
import org.openmotion.editor.engine.gl.TextTextureRenderer
import org.openmotion.editor.model.Layer
import org.openmotion.editor.model.LayerType
import org.openmotion.editor.model.Project
import org.openmotion.editor.utils.MatrixUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance multi-layer video compositing engine.
 * Runs on a dedicated GL thread, orchestrating decoders, layer stacking, and zero-copy rendering.
 */
class CompositorEngine(
    private val context: Context,
    var project: Project
) {
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglCore: EglCore? = null
    private var eglSurface: EGLSurface? = null
    private var layerRenderer: LayerRenderer? = null
    private var textTextureRenderer: TextTextureRenderer? = null
    private var imageTextureHelper: ImageTextureHelper? = null

    private var viewportWidth: Int = 1080
    private var viewportHeight: Int = 1920

    private val decoders = ConcurrentHashMap<String, TrackMediaDecoder>()

    private var isPlaying = false
    private var currentPlayheadMs: Long = 0L
    private var playbackStartTimeMs: Long = 0L
    private var playbackStartPlayheadMs: Long = 0L

    var onPlayheadUpdated: ((Long) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyPlayhead(timeMs: Long) {
        mainHandler.post {
            onPlayheadUpdated?.invoke(timeMs)
        }
    }

    init {
        startGlThread()
    }

    private fun startGlThread() {
        glThread = HandlerThread("CompositorGLThread").apply { start() }
        glHandler = Handler(glThread!!.looper)
    }

    /**
     * Initializes the OpenGL ES pipeline on the preview target Surface.
     */
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        glHandler?.post {
            viewportWidth = width
            viewportHeight = height

            eglCore = EglCore()
            eglSurface = eglCore?.createWindowSurface(surface)
            eglSurface?.let { eglCore?.makeCurrent(it) }

            layerRenderer = LayerRenderer()
            layerRenderer?.init()

            textTextureRenderer = TextTextureRenderer()
            imageTextureHelper = ImageTextureHelper(context)

            GLES30.glViewport(0, 0, width, height)
            renderFrameAt(currentPlayheadMs)
        }
    }

    fun detachSurface() {
        glHandler?.post {
            releaseDecoders()
            textTextureRenderer?.release()
            textTextureRenderer = null
            imageTextureHelper?.release()
            imageTextureHelper = null

            layerRenderer?.release()
            layerRenderer = null

            eglSurface?.let {
                eglCore?.releaseSurface(it)
                eglSurface = null
            }
            eglCore?.release()
            eglCore = null
        }
    }

    /**
     * Seeks playhead to specific timestamp and triggers immediate re-composite.
     */
    fun seekTo(timeMs: Long) {
        currentPlayheadMs = timeMs.coerceIn(0L, project.durationMs)
        glHandler?.post {
            renderFrameAt(currentPlayheadMs)
        }
        notifyPlayhead(currentPlayheadMs)
    }

    /**
     * Toggles playback.
     */
    fun togglePlayPause() {
        if (isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        if (isPlaying) return
        isPlaying = true
        playbackStartTimeMs = System.currentTimeMillis()
        playbackStartPlayheadMs = currentPlayheadMs

        glHandler?.post(object : Runnable {
            override fun run() {
                if (!isPlaying) return
                val elapsed = System.currentTimeMillis() - playbackStartTimeMs
                currentPlayheadMs = (playbackStartPlayheadMs + elapsed)

                if (currentPlayheadMs >= project.durationMs) {
                    currentPlayheadMs = project.durationMs
                    isPlaying = false
                    notifyPlayhead(currentPlayheadMs)
                    return
                }

                renderFrameAt(currentPlayheadMs)
                notifyPlayhead(currentPlayheadMs)

                // Schedule next 60 FPS frame (~16ms)
                glHandler?.postDelayed(this, 16L)
            }
        })
    }

    fun pause() {
        isPlaying = false
    }

    /**
     * Composites all active layers onto the preview surface.
     */
    private fun renderFrameAt(timeMs: Long) {
        val core = eglCore ?: return
        val surface = eglSurface ?: return
        val renderer = layerRenderer ?: return

        core.makeCurrent(surface)

        // Clear canvas with dark gray workspace background
        GLES30.glClearColor(0.07f, 0.075f, 0.086f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val projMatrix = MatrixUtils.createOrthoMatrix(viewportWidth.toFloat(), viewportHeight.toFloat())

        // Fetch layers active at current time, ordered by zIndex
        val activeLayers = project.getActiveLayersAt(timeMs)

        for (layer in activeLayers) {
            val evaluatedTransform = layer.transform.evaluateAt(timeMs)

            when (layer.type) {
                LayerType.VIDEO -> {
                    renderVideoLayer(layer, evaluatedTransform, renderer, projMatrix, timeMs)
                }
                LayerType.SHAPE -> {
                    renderShapeLayer(layer, evaluatedTransform, renderer, projMatrix)
                }
                LayerType.IMAGE -> {
                    renderImageLayer(layer, evaluatedTransform, renderer, projMatrix)
                }
                LayerType.TEXT -> {
                    renderTextLayer(layer, evaluatedTransform, renderer, projMatrix)
                }
                LayerType.AUDIO -> {
                    // Audio processing handles mixer nodes, not visual draws
                }
            }
        }

        core.swapBuffers(surface)
    }

    private fun renderVideoLayer(
        layer: Layer,
        transform: org.openmotion.editor.model.EvaluatedTransform,
        renderer: LayerRenderer,
        projMatrix: FloatArray,
        timeMs: Long
    ) {
        val uriStr = layer.mediaSourceUri ?: return
        val decoder = decoders.getOrPut(layer.id) {
            TrackMediaDecoder(context, Uri.parse(uriStr), layer.id).apply { init() }
        }

        // Relative layer playback time in microseconds
        val layerTimeUs = (timeMs - layer.startTimeMs + layer.mediaOffsetMs) * 1000L
        decoder.advanceTo(layerTimeUs)

        decoder.frameQueueManager?.let { queue ->
            queue.updateIfAvailable()
            val w = if (decoder.videoWidth > 0) decoder.videoWidth.toFloat() else 400f
            val h = if (decoder.videoHeight > 0) decoder.videoHeight.toFloat() else 400f

            renderer.renderOesLayer(
                textureId = queue.textureId,
                texMatrix = queue.texMatrix,
                transform = transform,
                grading = layer.colorGrading,
                blendMode = layer.blendMode,
                projMatrix = projMatrix,
                layerWidth = w,
                layerHeight = h
            )
        }
    }

    private fun renderShapeLayer(
        layer: Layer,
        transform: org.openmotion.editor.model.EvaluatedTransform,
        renderer: LayerRenderer,
        projMatrix: FloatArray
    ) {
        val r = ((layer.colorGrading.tintColorRgb shr 16) and 0xFF) / 255.0f
        val g = ((layer.colorGrading.tintColorRgb shr 8) and 0xFF) / 255.0f
        val b = (layer.colorGrading.tintColorRgb and 0xFF) / 255.0f
        val color = floatArrayOf(
            if (layer.colorGrading.tintColorRgb == 0xFFFFFF) 0.0f else r,
            if (layer.colorGrading.tintColorRgb == 0xFFFFFF) 0.9f else g,
            if (layer.colorGrading.tintColorRgb == 0xFFFFFF) 1.0f else b,
            1.0f
        )
        renderer.renderColorLayer(
            colorRgba = color,
            transform = transform,
            blendMode = layer.blendMode,
            projMatrix = projMatrix,
            layerWidth = 300f,
            layerHeight = 300f
        )
    }

    private fun renderTextLayer(
        layer: Layer,
        transform: org.openmotion.editor.model.EvaluatedTransform,
        renderer: LayerRenderer,
        projMatrix: FloatArray
    ) {
        val rasterizer = textTextureRenderer ?: return
        val text = if (layer.textContent.isNotEmpty()) layer.textContent else layer.name
        val info = rasterizer.getOrCreateTextTexture(text, textSizeSp = 36f, textColorHex = android.graphics.Color.WHITE)

        val identityTex = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identityTex, 0)

        renderer.render2DTextureLayer(
            textureId = info.textureId,
            texMatrix = identityTex,
            transform = transform,
            grading = layer.colorGrading,
            blendMode = layer.blendMode,
            projMatrix = projMatrix,
            layerWidth = info.width,
            layerHeight = info.height
        )
    }

    private fun renderImageLayer(
        layer: Layer,
        transform: org.openmotion.editor.model.EvaluatedTransform,
        renderer: LayerRenderer,
        projMatrix: FloatArray
    ) {
        val uriStr = layer.mediaSourceUri ?: return
        val helper = imageTextureHelper ?: return
        val info = helper.getOrCreateImageTexture(uriStr) ?: return

        val identityTex = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identityTex, 0)

        renderer.render2DTextureLayer(
            textureId = info.textureId,
            texMatrix = identityTex,
            transform = transform,
            grading = layer.colorGrading,
            blendMode = layer.blendMode,
            projMatrix = projMatrix,
            layerWidth = info.width,
            layerHeight = info.height
        )
    }

    private fun releaseDecoders() {
        decoders.values.forEach { it.release() }
        decoders.clear()
    }

    fun release() {
        pause()
        glHandler?.post {
            releaseDecoders()
            textTextureRenderer?.release()
            textTextureRenderer = null
            imageTextureHelper?.release()
            imageTextureHelper = null
            layerRenderer?.release()
            layerRenderer = null
            eglSurface?.let { eglCore?.releaseSurface(it) }
            eglCore?.release()
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }
}
