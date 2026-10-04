package io.github.dmitryweiner.synesthesia.gl

import android.opengl.GLES30
import android.util.Log

/**
 * One GLSL program and the uniforms it takes, for the fullscreen passes the
 * picture is made of (PLAN.md decision 4).
 *
 * The fragment sources are the web app's, copied verbatim by
 * `scripts/sync-shaders.sh` and written without `#version` / `precision`
 * lines so that `common.glsl` can be prepended to each — [compose] puts the
 * three together exactly as the web app's `composeFragmentShader` does.
 *
 * Uniform locations are looked up once and kept: a pass sets a dozen of them
 * every frame, and the reaction runs up to 16 times a frame.
 */
class Program(fragmentSource: String) {
    private val id: Int = link(FULLSCREEN_VERTEX, fragmentSource)
    private val locations = HashMap<String, Int>()

    fun use() {
        GLES30.glUseProgram(id)
    }

    /** Draws one fullscreen triangle. The vertex shader needs no buffers. */
    fun draw() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    fun release() {
        GLES30.glDeleteProgram(id)
    }

    private fun location(name: String): Int =
        locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun f(name: String, v: Float) {
        GLES30.glUniform1f(location(name), v)
    }

    fun i(name: String, v: Int) {
        GLES30.glUniform1i(location(name), v)
    }

    fun f2(name: String, x: Float, y: Float) {
        GLES30.glUniform2f(location(name), x, y)
    }

    fun f3(name: String, v: List<Float>) {
        GLES30.glUniform3f(location(name), v[0], v[1], v[2])
    }

    fun f3(name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(location(name), x, y, z)
    }

    /** An array of `vec2`s, as `uSpots` wants it. */
    fun vec2Array(name: String, xy: FloatArray, count: Int) {
        GLES30.glUniform2fv(location(name), count, xy, 0)
    }

    /** An array of `vec4`s, as `uRipples` wants it. */
    fun vec4Array(name: String, values: FloatArray, count: Int) {
        GLES30.glUniform4fv(location(name), count, values, 0)
    }

    /** Binds `texture` to `unit` and points the sampler at it. */
    fun sampler(name: String, unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(location(name), unit)
    }

    companion object {
        private const val TAG = "SynGl"

        /**
         * Every pass draws one oversized triangle, its position from
         * `gl_VertexID` — no vertex buffers, no attributes (the web app's
         * `FULLSCREEN_VERT`).
         */
        private val FULLSCREEN_VERTEX = """
            #version 300 es
            out vec2 vUv;
            void main() {
              vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
              vUv = pos;
              gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        /** `common.glsl` + a pass body, under the pragmas they are written without. */
        fun compose(common: String, body: String): String =
            "#version 300 es\nprecision highp float;\n$common\n$body"

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            check(shader != 0) { "glCreateShader failed" }
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                // The log's line numbers are of the composed source, so print it.
                Log.e(TAG, numbered(source))
                error("${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"} shader: $log")
            }
            return shader
        }

        private fun link(vertexSource: String, fragmentSource: String): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES30.glCreateProgram()
            check(program != 0) { "glCreateProgram failed" }
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                error("program link: $log")
            }
            return program
        }

        private fun numbered(source: String): String =
            source.lineSequence().mapIndexed { i, line -> "${i + 1}: $line" }.joinToString("\n")
    }
}
