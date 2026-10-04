package io.github.dmitryweiner.synesthesia.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The textures the simulation lives in.
 *
 * `RG16F`, not `RGBA16F`, exactly as the web app has it: every target here
 * holds two channels — the state's u and v, the velocity's x and y, the
 * paramfield's feed and kill offsets — and the other two were dead weight in
 * a pass that is bound by how much of the state texture it can pull through
 * the cache.
 *
 * Two channels of half float also need an extension on OpenGL ES 3.0:
 * rendering *to* a float texture is not core until ES 3.2
 * ([floatTargetsAvailable]). A device without it gets the core's CPU picture
 * instead (PLAN.md decision 4).
 */
class FloatTarget(val width: Int, val height: Int, filter: Int = GLES30.GL_LINEAR) {
    val texture: Int
    val framebuffer: Int

    init {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, width, height, 0,
            GLES30.GL_RG, GLES30.GL_HALF_FLOAT, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        framebuffer = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "framebuffer incomplete: 0x${Integer.toHexString(status)}"
        }
    }

    /** Makes this target the one being drawn into, over its whole area. */
    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/**
 * Two of them, swapped after every pass that reads the state and writes it:
 * a shader cannot read the texture it is drawing into.
 */
class PingPong(width: Int, height: Int) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    private var front = FloatTarget(width, height)
    private var back = FloatTarget(width, height)

    /** What a pass reads. */
    val readTexture: Int get() = front.texture

    /** What it writes; call [swap] afterwards. */
    fun writeTarget(): FloatTarget = back

    fun swap() {
        val t = front
        front = back
        back = t
    }

    val readFramebuffer: Int get() = front.framebuffer

    /** A new grid, keeping the pattern: the old content is scaled into it. */
    fun resize(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        val next = FloatTarget(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, front.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, next.framebuffer)
        GLES30.glBlitFramebuffer(
            0, 0, this.width, this.height, 0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR,
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        front.release()
        back.release()
        front = next
        back = FloatTarget(width, height)
        this.width = width
        this.height = height
    }

    fun release() {
        front.release()
        back.release()
    }
}

/**
 * A 1×1 all-zero texture, bound in place of a pass that was skipped because
 * it would have written nothing but zeros (an off Field variation card). The
 * reaction samples it exactly as it samples the real field.
 */
fun zeroTexture(): Int {
    val tex = IntArray(1)
    GLES30.glGenTextures(1, tex, 0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
    val zeros = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, 1, 1, 0,
        GLES30.GL_RG, GLES30.GL_HALF_FLOAT, zeros,
    )
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    return tex[0]
}

/**
 * Whether this device can render into a float texture at all. ES 3.0 can
 * *sample* one but not draw into one; either extension makes the two-channel
 * half-float targets above legal, and every phone the app targets has one of
 * them. Must be called with a current context.
 */
fun floatTargetsAvailable(): Boolean {
    val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
    return extensions.contains("GL_EXT_color_buffer_half_float") ||
        extensions.contains("GL_EXT_color_buffer_float")
}
