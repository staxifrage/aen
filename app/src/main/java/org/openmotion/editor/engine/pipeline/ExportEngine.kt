package org.openmotion.editor.engine.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.openmotion.editor.engine.gl.EglCore
import org.openmotion.editor.engine.gl.LayerRenderer
import org.openmotion.editor.engine.gl.TextTextureRenderer
import org.openmotion.editor.model.LayerType
import org.openmotion.editor.model.Project
import org.openmotion.editor.utils.MatrixUtils
import java.io.File

/**
 * Offscreen hardware-accelerated video export pipeline.
 * Encodes the multi-layer project timeline to an MP4 video file using MediaCodec and MediaMuxer.
 */
class ExportEngine(
    private val context: Context,
    private val project: Project
) {
    companion object {
        private const val TAG = "ExportEngine"
        private const val MIME_TYPE = "video/avc"
        private const val I_FRAME_INTERVAL = 1
        private const val TIMEOUT_US = 10000L
    }

    private var exportThread: HandlerThread? = null
    private var exportHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isCancelled = false

    fun startExport(
        outputFile: File,
        onProgress: (Float) -> Unit,
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        exportThread = HandlerThread("VideoExportThread").apply { start() }
        exportHandler = Handler(exportThread!!.looper)

        exportHandler?.post {
            try {
                performExport(outputFile, onProgress)
                mainHandler.post { onComplete(outputFile) }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                mainHandler.post { onError(e) }
            } finally {
                exportThread?.quitSafely()
                exportThread = null
                exportHandler = null
            }
        }
    }

    fun cancelExport() {
        isCancelled = true
    }

    private fun performExport(outputFile: File, onProgress: (Float) -> Unit) {
        val width = project.width
        val height = project.height
        val fps = project.frameRate
        val bitrate = (width * height * 4.0).toInt().coerceIn(4_000_000, 25_000_000)

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val eglCore = EglCore()
        val eglSurface: EGLSurface = eglCore.createWindowSurface(inputSurface)
        eglCore.makeCurrent(eglSurface)

        val renderer = LayerRenderer().apply { init() }
        val textRenderer = TextTextureRenderer()

        val bufferInfo = MediaCodec.BufferInfo()
        val totalFrames = ((project.durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)
        val frameDurationMs = 1000L / fps
        val projMatrix = MatrixUtils.createOrthoMatrix(width.toFloat(), height.toFloat())

        try {
            for (frameIndex in 0 until totalFrames) {
                if (isCancelled) break

                val currentTimeMs = frameIndex * frameDurationMs
                val frameTimeNs = frameIndex * (1_000_000_000L / fps)

                // 1. Render multi-layer frame onto encoder input surface
                GLES30.glViewport(0, 0, width, height)
                GLES30.glClearColor(0.07f, 0.075f, 0.086f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

                val activeLayers = project.getActiveLayersAt(currentTimeMs)
                for (layer in activeLayers) {
                    val evaluatedTransform = layer.transform.evaluateAt(currentTimeMs)
                    when (layer.type) {
                        LayerType.SHAPE -> {
                            val r = ((layer.colorGrading.tintColorRgb shr 16) and 0xFF) / 255.0f
                            val g = ((layer.colorGrading.tintColorRgb shr 8) and 0xFF) / 255.0f
                            val b = (layer.colorGrading.tintColorRgb and 0xFF) / 255.0f
                            renderer.renderColorLayer(
                                colorRgba = floatArrayOf(r, g, b, 1.0f),
                                transform = evaluatedTransform,
                                blendMode = layer.blendMode,
                                projMatrix = projMatrix,
                                layerWidth = 300f,
                                layerHeight = 300f
                            )
                        }
                        LayerType.TEXT -> {
                            val text = if (layer.textContent.isNotEmpty()) layer.textContent else layer.name
                            val info = textRenderer.getOrCreateTextTexture(text, textSizeSp = 48f)
                            val identity = FloatArray(16)
                            android.opengl.Matrix.setIdentityM(identity, 0)
                            renderer.render2DTextureLayer(
                                textureId = info.textureId,
                                texMatrix = identity,
                                transform = evaluatedTransform,
                                grading = layer.colorGrading,
                                blendMode = layer.blendMode,
                                projMatrix = projMatrix,
                                layerWidth = info.width,
                                layerHeight = info.height
                            )
                        }
                        else -> {
                            // Other layers
                        }
                    }
                }

                // Set presentation time on Android EGL
                EGLExt.eglPresentationTimeANDROID(
                    android.opengl.EGL14.eglGetCurrentDisplay(),
                    eglSurface,
                    frameTimeNs
                )
                eglCore.swapBuffers(eglSurface)

                // Drain encoder output
                var encoderOutputAvailable = true
                while (encoderOutputAvailable) {
                    val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            encoderOutputAvailable = false
                        }
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                throw RuntimeException("Format changed after muxer started")
                            }
                            val newFormat = encoder.outputFormat
                            trackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputBufferIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                                ?: throw RuntimeException("Encoder buffer was null")

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }

                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                }

                val progress = (frameIndex + 1).toFloat() / totalFrames.toFloat()
                mainHandler.post { onProgress(progress) }
            }

            // Signal End of Stream
            encoder.signalEndOfInputStream()

            // Drain remaining frames
            var draining = true
            while (draining) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        draining = false
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    draining = false
                }
            }
        } finally {
            try {
                renderer.release()
                textRenderer.release()
                eglCore.releaseSurface(eglSurface)
                eglCore.release()

                encoder.stop()
                encoder.release()
                inputSurface.release()

                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up export pipeline", e)
            }
        }
    }
}
