package org.openmotion.editor.engine.gl

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log

data class ImageTextureInfo(
    val textureId: Int,
    val width: Float,
    val height: Float
)

/**
 * Loads and caches 2D static images into GPU textures.
 */
class ImageTextureHelper(private val context: Context) {

    companion object {
        private const val TAG = "ImageTextureHelper"
    }

    private val cache = mutableMapOf<String, ImageTextureInfo>()

    fun getOrCreateImageTexture(uriStr: String): ImageTextureInfo? {
        cache[uriStr]?.let { return it }

        try {
            val uri = Uri.parse(uriStr)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return null

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            val textureId = textures[0]

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            bitmap.recycle()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            val info = ImageTextureInfo(textureId, width, height)
            cache[uriStr] = info
            return info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image texture for $uriStr", e)
            return null
        }
    }

    fun release() {
        cache.values.forEach { info ->
            if (info.textureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(info.textureId), 0)
            }
        }
        cache.clear()
    }
}
