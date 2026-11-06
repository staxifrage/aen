package org.openmotion.editor.engine.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import org.openmotion.editor.model.BlendMode
import org.openmotion.editor.model.ColorGrading
import org.openmotion.editor.model.EvaluatedTransform
import org.openmotion.editor.utils.MatrixUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 renderer responsible for compositing layer quads, blending, and transformations.
 */
class LayerRenderer {

    private var oesProgram: Int = 0
    private var colorProgram: Int = 0
    private var texture2DProgram: Int = 0

    // Quad geometry: Position (vec2) + TexCoord (vec2)
    private val quadVertices = floatArrayOf(
        // Pos (x, y)   Tex (u, v)
        -1.0f, -1.0f,   0.0f, 0.0f,
         1.0f, -1.0f,   1.0f, 0.0f,
        -1.0f,  1.0f,   0.0f, 1.0f,
         1.0f,  1.0f,   1.0f, 1.0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(quadVertices)
            position(0)
        }

    fun init() {
        oesProgram = GlShader.createProgram(GlShader.VERTEX_SHADER_CODE, GlShader.FRAGMENT_SHADER_OES_CODE)
        colorProgram = GlShader.createProgram(GlShader.VERTEX_SHADER_CODE, GlShader.FRAGMENT_SHADER_COLOR_CODE)
        texture2DProgram = GlShader.createProgram(GlShader.VERTEX_SHADER_CODE, GlShader.FRAGMENT_SHADER_2D_CODE)
    }

    /**
     * Configures OpenGL ES blend equations corresponding to selected BlendMode.
     */
    fun applyBlendMode(blendMode: BlendMode) {
        GLES30.glEnable(GLES30.GL_BLEND)
        when (blendMode) {
            BlendMode.NORMAL -> {
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }
            BlendMode.ADD -> {
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }
            BlendMode.MULTIPLY -> {
                GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }
            BlendMode.SCREEN -> {
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }
            else -> {
                // Fallback to standard alpha compositing
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }
        }
    }

    /**
     * Renders a hardware video frame from an OES texture.
     */
    fun renderOesLayer(
        textureId: Int,
        texMatrix: FloatArray,
        transform: EvaluatedTransform,
        grading: ColorGrading,
        blendMode: BlendMode,
        projMatrix: FloatArray,
        layerWidth: Float,
        layerHeight: Float
    ) {
        applyBlendMode(blendMode)

        GLES30.glUseProgram(oesProgram)

        // Compute Model Matrix and Model-View-Projection (MVP)
        val modelMatrix = MatrixUtils.buildModelMatrix(
            posX = transform.posX,
            posY = transform.posY,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            rotationDeg = transform.rotationDegrees,
            width = layerWidth,
            height = layerHeight,
            anchorX = transform.anchorX,
            anchorY = transform.anchorY
        )

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0)

        // Bind Uniforms
        val uMvpLoc = GLES30.glGetUniformLocation(oesProgram, "uMVPMatrix")
        val uTexLoc = GLES30.glGetUniformLocation(oesProgram, "uTexMatrix")
        val uOpacityLoc = GLES30.glGetUniformLocation(oesProgram, "uOpacity")
        val uBlendModeLoc = GLES30.glGetUniformLocation(oesProgram, "uBlendMode")
        val uColorAdjustLoc = GLES30.glGetUniformLocation(oesProgram, "uColorAdjust")
        val uTintColorLoc = GLES30.glGetUniformLocation(oesProgram, "uTintColor")

        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uTexLoc, 1, false, texMatrix, 0)
        GLES30.glUniform1f(uOpacityLoc, transform.opacity)
        GLES30.glUniform1i(uBlendModeLoc, blendMode.glModeId)
        GLES30.glUniform3f(uColorAdjustLoc, grading.brightness, grading.contrast, grading.saturation)

        // Parse tint hex
        val r = ((grading.tintColorRgb shr 16) and 0xFF) / 255.0f
        val g = ((grading.tintColorRgb shr 8) and 0xFF) / 255.0f
        val b = (grading.tintColorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(uTintColorLoc, r, g, b, 1.0f)

        // Bind Texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val sTextureLoc = GLES30.glGetUniformLocation(oesProgram, "sTexture")
        GLES30.glUniform1i(sTextureLoc, 0)

        // Set Vertex Attributes
        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)

        // Draw Quad
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Renders a solid shape / color layer.
     */
    fun renderColorLayer(
        colorRgba: FloatArray,
        transform: EvaluatedTransform,
        blendMode: BlendMode,
        projMatrix: FloatArray,
        layerWidth: Float,
        layerHeight: Float
    ) {
        applyBlendMode(blendMode)

        GLES30.glUseProgram(colorProgram)

        val modelMatrix = MatrixUtils.buildModelMatrix(
            posX = transform.posX,
            posY = transform.posY,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            rotationDeg = transform.rotationDegrees,
            width = layerWidth,
            height = layerHeight,
            anchorX = transform.anchorX,
            anchorY = transform.anchorY
        )

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0)

        val identityTex = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identityTex, 0)

        val uMvpLoc = GLES30.glGetUniformLocation(colorProgram, "uMVPMatrix")
        val uTexLoc = GLES30.glGetUniformLocation(colorProgram, "uTexMatrix")
        val uColorLoc = GLES30.glGetUniformLocation(colorProgram, "uColor")
        val uOpacityLoc = GLES30.glGetUniformLocation(colorProgram, "uOpacity")

        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uTexLoc, 1, false, identityTex, 0)
        GLES30.glUniform4fv(uColorLoc, 1, colorRgba, 0)
        GLES30.glUniform1f(uOpacityLoc, transform.opacity)

        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    /**
     * Renders a standard 2D texture (Image, Text glyph texture, or offscreen FBO).
     */
    fun render2DTextureLayer(
        textureId: Int,
        texMatrix: FloatArray,
        transform: EvaluatedTransform,
        grading: ColorGrading,
        blendMode: BlendMode,
        projMatrix: FloatArray,
        layerWidth: Float,
        layerHeight: Float
    ) {
        applyBlendMode(blendMode)

        GLES30.glUseProgram(texture2DProgram)

        val modelMatrix = MatrixUtils.buildModelMatrix(
            posX = transform.posX,
            posY = transform.posY,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            rotationDeg = transform.rotationDegrees,
            width = layerWidth,
            height = layerHeight,
            anchorX = transform.anchorX,
            anchorY = transform.anchorY
        )

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0)

        val uMvpLoc = GLES30.glGetUniformLocation(texture2DProgram, "uMVPMatrix")
        val uTexLoc = GLES30.glGetUniformLocation(texture2DProgram, "uTexMatrix")
        val uOpacityLoc = GLES30.glGetUniformLocation(texture2DProgram, "uOpacity")
        val uBlendModeLoc = GLES30.glGetUniformLocation(texture2DProgram, "uBlendMode")
        val uColorAdjustLoc = GLES30.glGetUniformLocation(texture2DProgram, "uColorAdjust")
        val uTintColorLoc = GLES30.glGetUniformLocation(texture2DProgram, "uTintColor")

        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uTexLoc, 1, false, texMatrix, 0)
        GLES30.glUniform1f(uOpacityLoc, transform.opacity)
        GLES30.glUniform1i(uBlendModeLoc, blendMode.glModeId)
        GLES30.glUniform3f(uColorAdjustLoc, grading.brightness, grading.contrast, grading.saturation)

        val r = ((grading.tintColorRgb shr 16) and 0xFF) / 255.0f
        val g = ((grading.tintColorRgb shr 8) and 0xFF) / 255.0f
        val b = (grading.tintColorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(uTintColorLoc, r, g, b, 1.0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        val sTextureLoc = GLES30.glGetUniformLocation(texture2DProgram, "sTexture")
        GLES30.glUniform1i(sTextureLoc, 0)

        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (oesProgram != 0) {
            GLES30.glDeleteProgram(oesProgram)
            oesProgram = 0
        }
        if (colorProgram != 0) {
            GLES30.glDeleteProgram(colorProgram)
            colorProgram = 0
        }
        if (texture2DProgram != 0) {
            GLES30.glDeleteProgram(texture2DProgram)
            texture2DProgram = 0
        }
    }
}
