package org.openmotion.editor.engine.gl

import android.opengl.GLES30
import android.util.Log

/**
 * Compiles and manages OpenGL ES 3.0 shaders for multi-layer video compositing.
 *
 * Fragment shaders support a 2-slot per-layer effect stack. Slots 1/6 (BLUR /
 * RGB_SPLIT) are applied at sample time with a 3x3 kernel; slots 2..5
 * (CHROMA_KEY / VIGNETTE / EXPOSURE / GLOW) are applied as post passes in a
 * single shader function. SHAKE (7) is handled on the CPU as a transform jitter.
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

        // Shared GLSL: hsv helpers + post-effect function (slot types match EffectType.id)
        private const val EFFECT_COMMON = """
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
            // param: x=intensity, y=param1, z=param2, w=unused
            vec4 applyEffect(int type, vec4 param, vec3 keyColor, vec4 col, vec2 uv) {
                vec3 rgb = col.rgb;
                float a = col.a;
                if (type == 2) { // CHROMA_KEY
                    float d = distance(rgb, keyColor);
                    float thr = param.y;
                    float edge = max(param.z, 0.001);
                    a *= smoothstep(thr + edge, thr - edge, d);
                } else if (type == 3) { // VIGNETTE
                    float d = distance(uv, vec2(0.5)) * 1.4142;
                    float vig = 1.0 - smoothstep(0.35, 1.0, d) * param.x;
                    rgb *= vig;
                } else if (type == 4) { // EXPOSURE (param.x 0..1 maps -2..+2 stops)
                    float stops = (param.x - 0.5) * 4.0;
                    rgb *= pow(2.0, stops);
                } else if (type == 5) { // GLOW: bright-pass additive lift
                    float thr = param.y;
                    vec3 bright = max(rgb - thr, 0.0);
                    rgb += bright * param.x * 0.8;
                }
                return vec4(rgb, a);
            }
        """

        private const val EFFECT_UNIFORMS = """
            uniform vec2 uTexelSize;
            uniform int uEffect0Type;
            uniform vec4 uEffect0Param;
            uniform vec3 uEffect0Color;
            uniform int uEffect1Type;
            uniform vec4 uEffect1Param;
            uniform vec3 uEffect1Color;
        """

        // Sampling + effect application body, parameterized by SAMPLE(uv) macro-like duplication.
        // We inline because sampler types differ between OES and 2D.
        private const val COLOR_GRADING_AND_EFFECTS = """
            vec2 uv = vTexCoord;
            float blurR = 0.0;
            float splitR = 0.0;
            if (uEffect0Type == 1) blurR += uEffect0Param.x * uEffect0Param.y;
            if (uEffect1Type == 1) blurR += uEffect1Param.x * uEffect1Param.y;
            if (uEffect0Type == 6) splitR += uEffect0Param.x * uEffect0Param.y;
            if (uEffect1Type == 6) splitR += uEffect1Param.x * uEffect1Param.y;

            vec4 baseColor;
            if (blurR > 0.001) {
                vec2 r = uTexelSize * blurR;
                vec4 sum = vec4(0.0);
                sum += texture(sTexture, uv + vec2(-r.x, -r.y)) * 1.0;
                sum += texture(sTexture, uv + vec2( 0.0, -r.y)) * 2.0;
                sum += texture(sTexture, uv + vec2( r.x, -r.y)) * 1.0;
                sum += texture(sTexture, uv + vec2(-r.x,  0.0)) * 2.0;
                sum += texture(sTexture, uv) * 4.0;
                sum += texture(sTexture, uv + vec2( r.x,  0.0)) * 2.0;
                sum += texture(sTexture, uv + vec2(-r.x,  r.y)) * 1.0;
                sum += texture(sTexture, uv + vec2( 0.0,  r.y)) * 2.0;
                sum += texture(sTexture, uv + vec2( r.x,  r.y)) * 1.0;
                baseColor = sum / 16.0;
            } else {
                baseColor = texture(sTexture, uv);
            }
            if (splitR > 0.001) {
                vec2 s = uTexelSize * splitR;
                baseColor.r = texture(sTexture, uv + vec2(s.x, 0.0)).r;
                baseColor.b = texture(sTexture, uv - vec2(s.x, 0.0)).b;
            }

            // Color grading: Brightness, Contrast, Saturation, Tint
            vec3 rgb = baseColor.rgb + vec3(uColorAdjust.x);
            rgb = (rgb - 0.5) * uColorAdjust.y + 0.5;
            vec3 hsv = rgb2hsv(rgb);
            hsv.y = clamp(hsv.y * uColorAdjust.z, 0.0, 1.0);
            rgb = hsv2rgb(hsv);
            rgb = rgb * uTintColor.rgb;
            baseColor = vec4(rgb, baseColor.a);

            // Post effect stack (2 slots)
            baseColor = applyEffect(uEffect0Type, uEffect0Param, uEffect0Color, baseColor, uv);
            baseColor = applyEffect(uEffect1Type, uEffect1Param, uEffect1Color, baseColor, uv);

            fragColor = vec4(clamp(baseColor.rgb, 0.0, 1.0), baseColor.a * uOpacity);
        """

        // Fragment shader supporting OES External textures (hardware video frames)
        const val FRAGMENT_SHADER_OES_CODE = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uOpacity;
            uniform int uBlendMode;
            uniform vec3 uColorAdjust;
            uniform vec4 uTintColor;
            $EFFECT_UNIFORMS
            out vec4 fragColor;
            $EFFECT_COMMON
            void main() {
                $COLOR_GRADING_AND_EFFECTS
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
            $EFFECT_UNIFORMS
            out vec4 fragColor;
            $EFFECT_COMMON
            void main() {
                $COLOR_GRADING_AND_EFFECTS
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
