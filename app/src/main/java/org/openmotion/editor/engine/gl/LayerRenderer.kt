package org.openmotion.editor.engine.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import org.openmotion.editor.model.BlendMode
import org.openmotion.editor.model.ColorGrading
import org.openmotion.editor.model.Effect
import org.openmotion.editor.model.EvaluatedTransform
import org.openmotion.editor.utils.MatrixUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 renderer responsible for compositing layer quads, blending, and transformations.
 *
 * Perf notes (all GL calls confined to one thread):
 *  - Uniform locations are queried once at init (glGetUniformLocation is not free).
 *  - MVP/model/identity matrices reuse preallocated FloatArrays — zero allocations on the hot path.
 *  - Quad geometry lives in a VBO, state bundled in a VAO.
 */
class LayerRenderer {

    private data class ProgramLocations(
        val program: Int,
        val mvp: Int,
        val texMatrix: Int,
        val opacity: Int,
        val blendMode: Int,
        val colorAdjust: Int,
        val tintColor: Int,
        val texelSize: Int,
        val sTexture: Int,
        val effectType: IntArray,   // [slot0, slot1]
        val effectParam: IntArray,  // [slot0, slot1]
        val effectColor: IntArray   // [slot0, slot1]
    )

    private var oesLoc: ProgramLocations? = null
    private var colorLoc: ProgramLocations? = null
    private var texture2DLoc: ProgramLocations? = null

    private var vboId: Int = 0
    private var vaoId: Int = 0

    // Reusable scratch arrays — never allocate in the render loop.
    private val mMvp = FloatArray(16)
    private val mModel = FloatArray(16)
    private val mIdentity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f,   0.0f, 0.0f,
         1.0f, -1.0f,   1.0f, 0.0f,
        -1.0f,  1.0f,   0.0f, 1.0f,
         1.0f,  1.0f,   1.0f, 1.0f
    )

    fun init() {
        val oesProgram = GlShader.createProgram(GlShader.VERTEX_SHADER_CODE, GlShader.FRAGMENT_SHADER_OES_CODE)
        val colorProgram = GlShader.createProgram(GlShader.VERTEX_SHADER_CODE, GlShader.FRAGMENT_SHADER_COLOR_CODE)
        val texture2DProgram = GlShader.createProgram(GlShader.VERTEX_SHADER_CODE, GlShader.FRAGMENT_SHADER_2D_CODE)
        oesLoc = locationsFor(oesProgram)
        colorLoc = colorLocationsFor(colorProgram)
        texture2DLoc = locationsFor(texture2DProgram)

        // VBO: upload quad once.
        val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices); position(0) }
        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBuffer.capacity() * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)

        // VAO: bundle attribute state.
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun locationsFor(program: Int): ProgramLocations = ProgramLocations(
        program = program,
        mvp = GLES30.glGetUniformLocation(program, "uMVPMatrix"),
        texMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix"),
        opacity = GLES30.glGetUniformLocation(program, "uOpacity"),
        blendMode = GLES30.glGetUniformLocation(program, "uBlendMode"),
        colorAdjust = GLES30.glGetUniformLocation(program, "uColorAdjust"),
        tintColor = GLES30.glGetUniformLocation(program, "uTintColor"),
        texelSize = GLES30.glGetUniformLocation(program, "uTexelSize"),
        sTexture = GLES30.glGetUniformLocation(program, "sTexture"),
        effectType = intArrayOf(GLES30.glGetUniformLocation(program, "uEffect0Type"), GLES30.glGetUniformLocation(program, "uEffect1Type")),
        effectParam = intArrayOf(GLES30.glGetUniformLocation(program, "uEffect0Param"), GLES30.glGetUniformLocation(program, "uEffect1Param")),
        effectColor = intArrayOf(GLES30.glGetUniformLocation(program, "uEffect0Color"), GLES30.glGetUniformLocation(program, "uEffect1Color"))
    )

    private fun colorLocationsFor(program: Int): ProgramLocations = ProgramLocations(
        program = program,
        mvp = GLES30.glGetUniformLocation(program, "uMVPMatrix"),
        texMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix"),
        opacity = GLES30.glGetUniformLocation(program, "uOpacity"),
        blendMode = -1, colorAdjust = -1, tintColor = -1, texelSize = -1, sTexture = -1,
        effectType = intArrayOf(-1, -1), effectParam = intArrayOf(-1, -1), effectColor = intArrayOf(-1, -1)
    )

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
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }
        }
    }

    private fun setEffectUniforms(loc: ProgramLocations, effects: List<Effect>, layerWidth: Float, layerHeight: Float) {
        val enabled = effects.filter { it.enabled }.take(2)
        val texW = if (layerWidth > 0f) 1f / layerWidth else 0f
        val texH = if (layerHeight > 0f) 1f / layerHeight else 0f
        GLES30.glUniform2f(loc.texelSize, texW, texH)
        for (slot in 0..1) {
            val eff = enabled.getOrNull(slot)
            if (eff == null) {
                GLES30.glUniform1i(loc.effectType[slot], 0)
                GLES30.glUniform4f(loc.effectParam[slot], 0f, 0f, 0f, 0f)
                GLES30.glUniform3f(loc.effectColor[slot], 0f, 0f, 0f)
            } else {
                GLES30.glUniform1i(loc.effectType[slot], eff.type.id)
                GLES30.glUniform4f(loc.effectParam[slot], eff.intensity, eff.param1, eff.param2, 0f)
                val r = ((eff.param3 shr 16) and 0xFF) / 255f
                val g = ((eff.param3 shr 8) and 0xFF) / 255f
                val b = (eff.param3 and 0xFF) / 255f
                GLES30.glUniform3f(loc.effectColor[slot], r, g, b)
            }
        }
    }

    private fun bindQuad() {
        GLES30.glBindVertexArray(vaoId)
    }

    private fun unbindQuad() {
        GLES30.glBindVertexArray(0)
    }

    private fun computeMvp(transform: EvaluatedTransform, projMatrix: FloatArray, width: Float, height: Float) {
        MatrixUtils.buildModelMatrixInto(
            out = mModel,
            posX = transform.posX, posY = transform.posY,
            scaleX = transform.scaleX, scaleY = transform.scaleY,
            rotationDeg = transform.rotationDegrees,
            width = width, height = height,
            anchorX = transform.anchorX, anchorY = transform.anchorY
        )
        android.opengl.Matrix.multiplyMM(mMvp, 0, projMatrix, 0, mModel, 0)
    }

    fun renderOesLayer(
        textureId: Int,
        texMatrix: FloatArray,
        transform: EvaluatedTransform,
        grading: ColorGrading,
        blendMode: BlendMode,
        projMatrix: FloatArray,
        layerWidth: Float,
        layerHeight: Float,
        effects: List<Effect> = emptyList()
    ) {
        val loc = oesLoc ?: return
        applyBlendMode(blendMode)
        GLES30.glUseProgram(loc.program)
        computeMvp(transform, projMatrix, layerWidth, layerHeight)

        GLES30.glUniformMatrix4fv(loc.mvp, 1, false, mMvp, 0)
        GLES30.glUniformMatrix4fv(loc.texMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform1f(loc.opacity, transform.opacity)
        GLES30.glUniform1i(loc.blendMode, blendMode.glModeId)
        GLES30.glUniform3f(loc.colorAdjust, grading.brightness, grading.contrast, grading.saturation)
        val r = ((grading.tintColorRgb shr 16) and 0xFF) / 255.0f
        val g = ((grading.tintColorRgb shr 8) and 0xFF) / 255.0f
        val b = (grading.tintColorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(loc.tintColor, r, g, b, 1.0f)
        setEffectUniforms(loc, effects, layerWidth, layerHeight)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(loc.sTexture, 0)

        bindQuad()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad()
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun renderColorLayer(
        colorRgba: FloatArray,
        transform: EvaluatedTransform,
        blendMode: BlendMode,
        projMatrix: FloatArray,
        layerWidth: Float,
        layerHeight: Float
    ) {
        val loc = colorLoc ?: return
        applyBlendMode(blendMode)
        GLES30.glUseProgram(loc.program)
        computeMvp(transform, projMatrix, layerWidth, layerHeight)
        GLES30.glUniformMatrix4fv(loc.mvp, 1, false, mMvp, 0)
        GLES30.glUniformMatrix4fv(loc.texMatrix, 1, false, mIdentity, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(loc.program, "uColor"), 1, colorRgba, 0)
        GLES30.glUniform1f(loc.opacity, transform.opacity)
        bindQuad()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad()
    }

    fun render2DTextureLayer(
        textureId: Int,
        texMatrix: FloatArray,
        transform: EvaluatedTransform,
        grading: ColorGrading,
        blendMode: BlendMode,
        projMatrix: FloatArray,
        layerWidth: Float,
        layerHeight: Float,
        effects: List<Effect> = emptyList()
    ) {
        val loc = texture2DLoc ?: return
        applyBlendMode(blendMode)
        GLES30.glUseProgram(loc.program)
        computeMvp(transform, projMatrix, layerWidth, layerHeight)

        GLES30.glUniformMatrix4fv(loc.mvp, 1, false, mMvp, 0)
        GLES30.glUniformMatrix4fv(loc.texMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform1f(loc.opacity, transform.opacity)
        GLES30.glUniform1i(loc.blendMode, blendMode.glModeId)
        GLES30.glUniform3f(loc.colorAdjust, grading.brightness, grading.contrast, grading.saturation)
        val r = ((grading.tintColorRgb shr 16) and 0xFF) / 255.0f
        val g = ((grading.tintColorRgb shr 8) and 0xFF) / 255.0f
        val b = (grading.tintColorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(loc.tintColor, r, g, b, 1.0f)
        setEffectUniforms(loc, effects, layerWidth, layerHeight)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(loc.sTexture, 0)

        bindQuad()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        oesLoc?.let { if (it.program != 0) GLES30.glDeleteProgram(it.program) }
        colorLoc?.let { if (it.program != 0) GLES30.glDeleteProgram(it.program) }
        texture2DLoc?.let { if (it.program != 0) GLES30.glDeleteProgram(it.program) }
        oesLoc = null; colorLoc = null; texture2DLoc = null
        if (vaoId != 0) { GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0); vaoId = 0 }
        if (vboId != 0) { GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0); vboId = 0 }
    }
}
