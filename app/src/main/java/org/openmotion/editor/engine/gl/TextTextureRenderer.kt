package org.openmotion.editor.engine.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils

data class TextTextureInfo(
    val textureId: Int,
    val width: Float,
    val height: Float
)

/**
 * High performance text rasterizer that renders typography into OpenGL 2D textures.
 * Caches generated textures to prevent allocation churn during real-time playback.
 */
class TextTextureRenderer {

    private val cache = mutableMapOf<String, TextTextureInfo>()

    fun getOrCreateTextTexture(
        text: String,
        textSizeSp: Float = 48f,
        textColorHex: Int = Color.WHITE
    ): TextTextureInfo {
        val key = "$text-$textSizeSp-$textColorHex"
        cache[key]?.let { return it }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizeSp * 2f // 2x for sharp high-DPI rasterization
            color = textColorHex
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }

        val textWidth = paint.measureText(text).coerceAtLeast(10f)
        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(10f)

        val bmpWidth = (textWidth + 20).toInt().coerceAtLeast(2)
        val bmpHeight = (textHeight + 20).toInt().coerceAtLeast(2)

        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val yPos = -fontMetrics.top + 10
        canvas.drawText(text, 10f, yPos, paint)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val info = TextTextureInfo(
            textureId = textureId,
            width = textWidth / 2f,
            height = textHeight / 2f
        )
        cache[key] = info
        return info
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
