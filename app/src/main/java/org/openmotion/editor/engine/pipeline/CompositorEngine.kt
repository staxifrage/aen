package org.openmotion.editor.engine.pipeline

import android.content.Context
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import org.openmotion.editor.engine.decoder.TrackMediaDecoder
import org.openmotion.editor.engine.gl.BlendProgram
import org.openmotion.editor.engine.gl.EglCore
import org.openmotion.editor.engine.gl.ImageTextureHelper
import org.openmotion.editor.engine.gl.LayerRenderer
import org.openmotion.editor.engine.gl.OffscreenTarget
import org.openmotion.editor.engine.gl.TextTextureRenderer
import org.openmotion.editor.model.BlendMode
import org.openmotion.editor.model.EffectType
import org.openmotion.editor.model.EvaluatedTransform
import org.openmotion.editor.model.Layer
import org.openmotion.editor.model.LayerType
import org.openmotion.editor.model.Project
import org.openmotion.editor.utils.MatrixUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance multi-layer video compositing engine.
 *
 * Threading model: everything GL runs on a dedicated HandlerThread ("CompositorGLThread").
 * Playback is paced by Choreographer (vsync) bound to that thread — never by wall-clock
 * postDelayed, which drifts and tears. Decoders are flushed on seek so scrubbing is exact.
 *
 * Blend modes: NORMAL/ADD/MULTIPLY/SCREEN use fixed-function blending (fast path). The other
 * eight need the destination pixel, so when any active layer uses one, we render into FBOs and
 * composite with [BlendProgram] (ping-pong), then blit to the window surface. If FBO setup fails
 * for any reason we fall back to the direct path — preview never crashes.
 */
class CompositorEngine(
    private val context: Context,
    var project: Project
) {
    companion object {
        private const val TAG = "CompositorEngine"
        // Blend modes expressible with fixed-function glBlendFunc. Everything else needs FBOs.
        private val FAST_BLEND = setOf(BlendMode.NORMAL, BlendMode.ADD, BlendMode.MULTIPLY, BlendMode.SCREEN)
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var glChoreographer: Choreographer? = null

    private var eglCore: EglCore? = null
    private var eglSurface: EGLSurface? = null
    private var layerRenderer: LayerRenderer? = null
    private var textTextureRenderer: TextTextureRenderer? = null
    private var imageTextureHelper: ImageTextureHelper? = null

    // FBO pipeline for advanced blend modes
    private var layerFbo: OffscreenTarget? = null
    private var dstA: OffscreenTarget? = null
    private var dstB: OffscreenTarget? = null
    private var blendProgram: BlendProgram? = null
    private var fboAvailable: Boolean = false

    private var viewportWidth: Int = 1080
    private var viewportHeight: Int = 1920

    // Preallocated — zero allocations per frame.
    private val mProj = FloatArray(16)
    private val mIdentityTex = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private val decoders = ConcurrentHashMap<String, TrackMediaDecoder>()

    @Volatile private var isPlaying = false
    private var currentPlayheadMs: Long = 0L
    private var playbackStartNanos: Long = 0L
    private var playbackStartPlayheadMs: Long = 0L
    @Volatile var playbackSpeed: Float = 1f
        set(value) { field = value.coerceIn(0.25f, 4f) }
    @Volatile var loopEnabled: Boolean = false

    var onPlayheadUpdated: ((Long) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyPlayhead(timeMs: Long) {
        mainHandler.post { onPlayheadUpdated?.invoke(timeMs) }
    }

    /** Vsync callback — runs on the GL thread because Choreographer is bound to that thread's looper. */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isPlaying) return
            if (playbackStartNanos == 0L) playbackStartNanos = frameTimeNanos
            val elapsedMs = ((frameTimeNanos - playbackStartNanos) / 1_000_000f) * playbackSpeed
            currentPlayheadMs = (playbackStartPlayheadMs + elapsedMs).toLong()

            if (currentPlayheadMs >= project.durationMs) {
                if (loopEnabled) {
                    playbackStartPlayheadMs = 0L
                    playbackStartNanos = frameTimeNanos
                    currentPlayheadMs = 0L
                    flushDecodersTo(0L)
                } else {
                    currentPlayheadMs = project.durationMs
                    isPlaying = false
                    renderFrameAt(currentPlayheadMs)
                    notifyPlayhead(currentPlayheadMs)
                    return
                }
            }
            renderFrameAt(currentPlayheadMs)
            notifyPlayhead(currentPlayheadMs)
            glChoreographer?.postFrameCallback(this)
        }
    }

    init {
        startGlThread()
    }

    private fun startGlThread() {
        glThread = HandlerThread("CompositorGLThread").apply { start() }
        glHandler = Handler(glThread!!.looper)
        // Choreographer.getInstance() must be called on the thread that receives vsync callbacks.
        glHandler?.post {
            try {
                glChoreographer = Choreographer.getInstance()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init Choreographer on GL thread", e)
            }
        }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        glHandler?.post {
            viewportWidth = width
            viewportHeight = height
            eglCore = EglCore()
            eglSurface = eglCore?.createWindowSurface(surface)
            eglSurface?.let { eglCore?.makeCurrent(it) }
            layerRenderer = LayerRenderer().apply { init() }
            textTextureRenderer = TextTextureRenderer()
            imageTextureHelper = ImageTextureHelper(context)
            android.opengl.Matrix.orthoM(mProj, 0, -width / 2f, width / 2f, -height / 2f, height / 2f, -1f, 1f)
            GLES30.glViewport(0, 0, width, height)

            // Best-effort FBO setup for advanced blend modes.
            fboAvailable = try {
                layerFbo?.release(); dstA?.release(); dstB?.release(); blendProgram?.release()
                layerFbo = OffscreenTarget(width, height)
                dstA = OffscreenTarget(width, height)
                dstB = OffscreenTarget(width, height)
                blendProgram = BlendProgram().apply { init() }
                true
            } catch (e: Exception) {
                Log.w(TAG, "FBO pipeline unavailable, using direct render path", e)
                false
            }

            renderFrameAt(currentPlayheadMs)
        }
    }

    private fun releaseFbos() {
        try { layerFbo?.release() } catch (_: Exception) {}
        try { dstA?.release() } catch (_: Exception) {}
        try { dstB?.release() } catch (_: Exception) {}
        try { blendProgram?.release() } catch (_: Exception) {}
        layerFbo = null; dstA = null; dstB = null; blendProgram = null; fboAvailable = false
    }

    fun detachSurface() {
        isPlaying = false
        glHandler?.post {
            releaseDecoders()
            releaseFbos()
            textTextureRenderer?.release(); textTextureRenderer = null
            imageTextureHelper?.release(); imageTextureHelper = null
            layerRenderer?.release(); layerRenderer = null
            eglSurface?.let { eglCore?.releaseSurface(it); eglSurface = null }
            eglCore?.release(); eglCore = null
        }
    }

    fun seekTo(timeMs: Long) {
        currentPlayheadMs = timeMs.coerceIn(0L, project.durationMs)
        glHandler?.post {
            flushDecodersTo(currentPlayheadMs)
            renderFrameAt(currentPlayheadMs)
        }
        notifyPlayhead(currentPlayheadMs)
    }

    /** Flushes every video decoder to its layer-relative position at [projectTimeMs]. */
    private fun flushDecodersTo(projectTimeMs: Long) {
        decoders.forEach { (layerId, decoder) ->
            val layer = project.layers.firstOrNull { it.id == layerId } ?: return@forEach
            val layerTimeUs = ((projectTimeMs - layer.startTimeMs + layer.mediaOffsetMs) * 1000L).coerceAtLeast(0L)
            try { decoder.seekTo(layerTimeUs) } catch (e: Exception) { Log.w(TAG, "Decoder seek failed", e) }
        }
    }

    fun togglePlayPause() { if (isPlaying) pause() else play() }

    fun play() {
        if (isPlaying) return
        isPlaying = true
        glHandler?.post {
            playbackStartPlayheadMs = currentPlayheadMs
            playbackStartNanos = 0L // set on first vsync frame
            if (glChoreographer == null) {
                glChoreographer = Choreographer.getInstance()
            }
            glChoreographer?.postFrameCallback(frameCallback)
        }
    }

    fun pause() { isPlaying = false }

    /**
     * Composites all active layers onto the preview surface.
     */
    private fun renderFrameAt(timeMs: Long) {
        val core = eglCore ?: return
        val surface = eglSurface ?: return
        val renderer = layerRenderer ?: return
        core.makeCurrent(surface)

        val activeLayers = project.getActiveLayersAt(timeMs)
        val useFbo = fboAvailable && activeLayers.any { it.blendMode !in FAST_BLEND }

        if (!useFbo) {
            OffscreenTarget.bindDefault()
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES30.glClearColor(0.07f, 0.075f, 0.086f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            for (layer in activeLayers) {
                renderLayer(layer, evaluatedTransform(layer, timeMs), renderer, timeMs)
            }
            core.swapBuffers(surface)
            return
        }

        // Advanced-blend path: ping-pong between two dst FBOs, layer rendered into a third FBO.
        var dst = dstA ?: return
        var other = dstB ?: return
        val lFbo = layerFbo ?: return
        val blender = blendProgram ?: return

        dst.bind()
        GLES30.glClearColor(0.07f, 0.075f, 0.086f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        for (layer in activeLayers) {
            val et = evaluatedTransform(layer, timeMs)
            if (layer.blendMode in FAST_BLEND) {
                dst.bind()
                renderLayer(layer, et, renderer, timeMs)
            } else {
                lFbo.bind()
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                renderLayer(layer, et, renderer, timeMs) // advanced modes fall back to NORMAL blend here
                other.bind()
                blender.draw(lFbo.textureId, dst.textureId, layer.blendMode.glModeId)
                val tmp = dst; dst = other; other = tmp
            }
        }

        // Blit final composite to the window surface.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, dst.fboId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glBlitFramebuffer(
            0, 0, dst.width, dst.height,
            0, 0, viewportWidth, viewportHeight,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        core.swapBuffers(surface)
    }

    /** Evaluates keyframes + applies CPU-side SHAKE effect jitter. */
    private fun evaluatedTransform(layer: Layer, timeMs: Long): EvaluatedTransform {
        var et = layer.transform.evaluateAt(timeMs)
        layer.effects.forEach { e ->
            if (e.enabled && e.type == EffectType.SHAKE) {
                val amp = e.intensity * e.param1
                val seed = timeMs * 0.091f + layer.id.hashCode().toFloat() * 0.001f
                et = et.copy(
                    posX = et.posX + kotlin.math.sin(seed) * amp,
                    posY = et.posY + kotlin.math.cos(seed * 1.7f) * amp
                )
            }
        }
        return et
    }

    private fun renderLayer(layer: Layer, transform: EvaluatedTransform, renderer: LayerRenderer, timeMs: Long) {
        when (layer.type) {
            LayerType.VIDEO -> renderVideoLayer(layer, transform, renderer, timeMs)
            LayerType.SHAPE -> renderShapeLayer(layer, transform, renderer)
            LayerType.IMAGE -> renderImageLayer(layer, transform, renderer)
            LayerType.TEXT -> renderTextLayer(layer, transform, renderer)
            LayerType.AUDIO -> { /* handled by audio mixer, not visual draw */ }
        }
    }

    private fun renderVideoLayer(
        layer: Layer,
        transform: EvaluatedTransform,
        renderer: LayerRenderer,
        timeMs: Long
    ) {
        val uriStr = layer.mediaSourceUri ?: return
        val decoder = decoders.getOrPut(layer.id) {
            TrackMediaDecoder(context, Uri.parse(uriStr), layer.id).apply { init() }
        }
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
                projMatrix = mProj,
                layerWidth = w,
                layerHeight = h,
                effects = layer.effects
            )
        }
    }

    private fun renderShapeLayer(
        layer: Layer,
        transform: EvaluatedTransform,
        renderer: LayerRenderer
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
            projMatrix = mProj,
            layerWidth = 300f,
            layerHeight = 300f
        )
    }

    private fun renderTextLayer(
        layer: Layer,
        transform: EvaluatedTransform,
        renderer: LayerRenderer
    ) {
        val rasterizer = textTextureRenderer ?: return
        val text = if (layer.textContent.isNotEmpty()) layer.textContent else layer.name
        val info = rasterizer.getOrCreateTextTexture(text, textSizeSp = 36f, textColorHex = android.graphics.Color.WHITE)
        renderer.render2DTextureLayer(
            textureId = info.textureId,
            texMatrix = mIdentityTex,
            transform = transform,
            grading = layer.colorGrading,
            blendMode = layer.blendMode,
            projMatrix = mProj,
            layerWidth = info.width,
            layerHeight = info.height,
            effects = layer.effects
        )
    }

    private fun renderImageLayer(
        layer: Layer,
        transform: EvaluatedTransform,
        renderer: LayerRenderer
    ) {
        val uriStr = layer.mediaSourceUri ?: return
        val helper = imageTextureHelper ?: return
        val info = helper.getOrCreateImageTexture(uriStr) ?: return
        renderer.render2DTextureLayer(
            textureId = info.textureId,
            texMatrix = mIdentityTex,
            transform = transform,
            grading = layer.colorGrading,
            blendMode = layer.blendMode,
            projMatrix = mProj,
            layerWidth = info.width,
            layerHeight = info.height,
            effects = layer.effects
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
            releaseFbos()
            textTextureRenderer?.release()
            imageTextureHelper?.release()
            layerRenderer?.release()
            eglSurface?.let { eglCore?.releaseSurface(it) }
            eglCore?.release()
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
        glChoreographer = null
    }
}
