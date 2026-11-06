package org.openmotion.editor.engine.decoder

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import org.openmotion.editor.engine.pipeline.FrameQueueManager
import java.io.IOException

/**
 * High-performance hardware-accelerated MediaCodec video track decoder.
 * Feeds frames directly to an OpenGL ES SurfaceTexture without CPU buffer copying.
 */
class TrackMediaDecoder(
    private val context: Context,
    val mediaUri: Uri,
    val trackId: String
) {
    companion object {
        private const val TAG = "TrackMediaDecoder"
        private const val TIMEOUT_US = 10000L
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    var frameQueueManager: FrameQueueManager? = null
        private set

    var textureId: Int = 0
        private set

    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set
    var durationUs: Long = 0L
        private set

    private var isEOS = false

    fun init() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val surfaceTexture = SurfaceTexture(textureId)
        frameQueueManager = FrameQueueManager(surfaceTexture, textureId)
        surface = Surface(surfaceTexture)

        extractor = MediaExtractor()
        try {
            extractor?.setDataSource(context, mediaUri, null)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to set data source for $mediaUri", e)
            return
        }

        val numTracks = extractor?.trackCount ?: 0
        var videoTrackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until numTracks) {
            val trackFormat = extractor?.getTrackFormat(i)
            val mime = trackFormat?.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (videoTrackIndex >= 0 && format != null) {
            extractor?.selectTrack(videoTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            decoder = MediaCodec.createDecoderByType(mime)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
        }
    }

    /**
     * Seeks media extractor and codec to target position for scrubbing.
     */
    fun seekTo(timeUs: Long) {
        val ext = extractor ?: return
        val dec = decoder ?: return

        ext.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        dec.flush()
        isEOS = false

        // Drain frames up to the requested time
        advanceTo(timeUs)
    }

    /**
     * Feeds the hardware decoder up to the specified presentation time.
     */
    fun advanceTo(targetTimeUs: Long) {
        val dec = decoder ?: return
        val ext = extractor ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        var done = false
        var iterations = 0

        while (!done && iterations < 30) {
            iterations++

            // Feed input
            if (!isEOS) {
                val inputIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = dec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = ext.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val presentationTimeUs = ext.sampleTime
                            dec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            ext.advance()
                        }
                    }
                }
            }

            // Pull output
            val outputIndex = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                val renderFrame = bufferInfo.presentationTimeUs >= targetTimeUs || isEOS
                dec.releaseOutputBuffer(outputIndex, renderFrame)
                if (renderFrame) {
                    done = true
                }
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                done = true
            }
        }
    }

    fun release() {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        }
        decoder = null

        extractor?.release()
        extractor = null

        surface?.release()
        surface = null

        frameQueueManager?.release()
        frameQueueManager = null

        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
