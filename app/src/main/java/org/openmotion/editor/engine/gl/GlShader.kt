package org.openmotion.editor.engine.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

/**
 * Compiles and manages OpenGL ES 3.0 shaders for multi-layer video compositing.
 */
class GlShader {

    companion object {
        private const val TAG = "GlShader"

        const val VERTEX_SHADER_CODE = """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;

            out vec2 vTexCoord;

            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // Fragment shader supporting OES External textures (hardware video frames)
        const val FRAGMENT_SHADER_OES_CODE = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;

            in vec2 vTexCoord;
            uniform samplerExternalOES sTexture;

            uniform float uOpacity;
            uniform int uBlendMode;
            uniform vec3 uColorAdjust; // x: brightness (-1..1), y: contrast (0..2), z: saturation (0..2)
            uniform vec4 uTintColor;

            out vec4 fragColor;

            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            void main() {
                vec4 baseColor = texture(sTexture, vTexCoord);
                
                // Color grading: Brightness & Contrast
                vec3 rgb = baseColor.rgb + vec3(uColorAdjust.x);
                rgb = (rgb - 0.5) * uColorAdjust.y + 0.5;

                // Color grading: Saturation
                vec3 hsv = rgb2hsv(rgb);
                hsv.y = clamp(hsv.y * uColorAdjust.z, 0.0, 1.0);
                rgb = hsv2rgb(hsv);

                // Tint modulation
                rgb = rgb * uTintColor.rgb;

                fragColor = vec4(clamp(rgb, 0.0, 1.0), baseColor.a * uOpacity);
            }
        """

        // Fragment shader for standard 2D textures (Images, Text rasterizations, offscreen FBOs)
        const val FRAGMENT_SHADER_2D_CODE = """#version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            uniform sampler2D sTexture;

            uniform float uOpacity;
            uniform int uBlendMode;
            uniform vec3 uColorAdjust;
            uniform vec4 uTintColor;

            out vec4 fragColor;

            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            void main() {
                vec4 baseColor = texture(sTexture, vTexCoord);

                vec3 rgb = baseColor.rgb + vec3(uColorAdjust.x);
                rgb = (rgb - 0.5) * uColorAdjust.y + 0.5;

                vec3 hsv = rgb2hsv(rgb);
                hsv.y = clamp(hsv.y * uColorAdjust.z, 0.0, 1.0);
                rgb = hsv2rgb(hsv);

                rgb = rgb * uTintColor.rgb;

                fragColor = vec4(clamp(rgb, 0.0, 1.0), baseColor.a * uOpacity);
            }
        """

        // Fragment shader for solid colors / procedural shapes
        const val FRAGMENT_SHADER_COLOR_CODE = """#version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            uniform vec4 uColor;
            uniform float uOpacity;

            out vec4 fragColor;

            void main() {
                fragColor = vec4(uColor.rgb, uColor.a * uOpacity);
            }
        """

        fun compileShader(type: Int, shaderCode: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)

            val compileStatus = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                Log.e(TAG, "Shader compilation error: $error")
                throw RuntimeException("Shader compilation failed: $error")
            }
            return shader
        }

        fun createProgram(vertexCode: String, fragmentCode: String): Int {
            val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexCode)
            val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentCode)

            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val error = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                Log.e(TAG, "Program linking error: $error")
                throw RuntimeException("Program linking failed: $error")
            }

            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            return program
        }
    }
}
