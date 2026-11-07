package org.openmotion.editor.engine.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fullscreen quad program that composites a "layer" texture over a "destination" texture using
 * any of the 12 blend modes, computed in-shader. This is how we support blend modes that need the
 * destination pixel (Overlay, Soft Light, Color Burn, Difference, ...) which fixed-function
 * glBlendFunc cannot express.
 *
 * Pipeline per advanced-blend layer:
 *   1. Render layer (with its effects) into a layer FBO (transparent clear).
 *   2. draw(layerTexture, dstTexture, mode) into the *other* dst FBO (ping-pong).
 *   3. Swap dst pointers. Final result is blitted to the default framebuffer.
 */
class BlendProgram {
    private var program: Int = 0
    private var vboId: Int = 0
    private var uLayerLoc: Int = -1
    private var uDstLoc: Int = -1
    private var uBlendModeLoc: Int = -1

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // pos(x,y) + uv(u,v)
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

    fun init() {
        program = GlShader.createProgram(VERTEX, FRAGMENT)
        uLayerLoc = GLES30.glGetUniformLocation(program, "sLayer")
        uDstLoc = GLES30.glGetUniformLocation(program, "sDst")
        uBlendModeLoc = GLES30.glGetUniformLocation(program, "uBlendMode")

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVertices.capacity() * 4, quadVertices, GLES30.GL_STATIC_DRAW)
    }

    /**
     * Composites [layerTextureId] over [dstTextureId] with [blendModeId] (BlendMode.glModeId) into
     * the currently bound framebuffer (fullscreen). Caller binds the target FBO beforehand.
     */
    fun draw(layerTextureId: Int, dstTextureId: Int, blendModeId: Int) {
        GLES30.glDisable(GLES30.GL_BLEND) // composite replaces the dst pixel entirely
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layerTextureId)
        GLES30.glUniform1i(uLayerLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dstTextureId)
        GLES30.glUniform1i(uDstLoc, 1)

        GLES30.glUniform1i(uBlendModeLoc, blendModeId)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        val aPos = GLES30.glGetAttribLocation(program, "aPosition")
        val aTex = GLES30.glGetAttribLocation(program, "aTexCoord")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aTex)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vboId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
        program = 0
        vboId = 0
    }

    companion object {
        private const val VERTEX = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aTexCoord;
            }
        """
        // blendModeId matches BlendMode.glModeId (0 NORMAL .. 11 ADD)
        private const val FRAGMENT = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D sLayer;
            uniform sampler2D sDst;
            uniform int uBlendMode;
            out vec4 fragColor;

            vec3 blend(vec3 dst, vec3 src, int mode) {
                if (mode == 1) { // MULTIPLY
                    return dst * src;
                } else if (mode == 2) { // SCREEN
                    return vec3(1.0) - (vec3(1.0) - dst) * (vec3(1.0) - src);
                } else if (mode == 3) { // OVERLAY
                    bvec3 low = lessThan(dst, vec3(0.5));
                    vec3 a = 2.0 * dst * src;
                    vec3 b = vec3(1.0) - 2.0 * (vec3(1.0) - dst) * (vec3(1.0) - src);
                    return mix(b, a, vec3(low));
                } else if (mode == 4) { // DARKEN
                    return min(dst, src);
                } else if (mode == 5) { // LIGHTEN
                    return max(dst, src);
                } else if (mode == 6) { // COLOR DODGE
                    vec3 r = vec3(1.0) - src;
                    return min(vec3(1.0), dst / max(r, vec3(1.0e-4)));
                } else if (mode == 7) { // COLOR BURN
                    vec3 r = vec3(1.0) - dst;
                    return vec3(1.0) - min(vec3(1.0), r / max(src, vec3(1.0e-4)));
                } else if (mode == 8) { // HARD LIGHT
                    bvec3 low = lessThan(src, vec3(0.5));
                    vec3 a = 2.0 * src * dst;
                    vec3 b = vec3(1.0) - 2.0 * (vec3(1.0) - src) * (vec3(1.0) - dst);
                    return mix(b, a, vec3(low));
                } else if (mode == 9) { // SOFT LIGHT (W3C)
                    vec3 d2 = dst * dst;
                    return (vec3(1.0) - 2.0 * src) * d2 + 2.0 * src * dst;
                } else if (mode == 10) { // DIFFERENCE
                    return abs(dst - src);
                } else if (mode == 11) { // ADD / LINEAR DODGE
                    return min(vec3(1.0), dst + src);
                }
                // NORMAL (0)
                return src;
            }

            void main() {
                vec4 layer = texture(sLayer, vUv);
                vec4 dst = texture(sDst, vUv);
                vec3 blended = blend(dst.rgb, layer.rgb, uBlendMode);
                // Interpolate between dst and blended result by layer alpha (straight alpha).
                vec3 rgb = mix(dst.rgb, blended, layer.a);
                float a = layer.a + dst.a * (1.0 - layer.a);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), clamp(a, 0.0, 1.0));
            }
        """
    }
}
