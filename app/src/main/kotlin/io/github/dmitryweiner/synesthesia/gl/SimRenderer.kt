package io.github.dmitryweiner.synesthesia.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import io.github.dmitryweiner.synesthesia.core.PictureFrame
import io.github.dmitryweiner.synesthesia.core.SeedSpots
import io.github.dmitryweiner.synesthesia.core.fieldGrid
import io.github.dmitryweiner.synesthesia.core.maxRipples

/**
 * The picture, in seven passes (PLAN.md decision 4): the web app's shaders,
 * copied verbatim, with the uniforms the core hands over per frame
 * (`PictureDriver.frame`). Nothing here decides anything about the picture —
 * what to inject, how fast to react, what colour to be, how far the noise has
 * drifted all arrive in the [PictureFrame].
 *
 * What it does decide is what not to draw, which is the web app's lesson from
 * the machines with no GPU and still true on a phone:
 *
 *  * a pass whose output would change nothing is not run — an off Field
 *    variation card gets a 1×1 zero texture, and advection with no amount or
 *    no velocity would be an identity copy;
 *  * the paramfield and the velocity are kept while their inputs are
 *    unchanged (both are pure functions of their params and `evolveT`);
 *  * both are drawn at half the grid's side, where the detail is not there
 *    to lose.
 *
 * Lives on one thread — the GL one.
 */
class SimRenderer(private val assets: AssetManager, gridWidth: Int, gridHeight: Int) {
    private val common = assets.read("shaders/common.glsl")
    private fun pass(name: String) = Program(Program.compose(common, assets.read("shaders/$name")))

    private val seedPass = pass("seed.frag")
    private val reactPass = pass("react.frag")
    private val displayPass = pass("display.frag")
    private val velocityPass = pass("velocity.frag")
    private val advectPass = pass("advect.frag")
    private val paramFieldPass = pass("paramfield.frag")
    private val injectPass = pass("inject.frag")

    private var state = PingPong(gridWidth, gridHeight)
    private var velocity: FloatTarget
    private var paramField: FloatTarget
    private val zero = zeroTexture()

    /** What each cached field texture was drawn from; empty until it is drawn. */
    private val paramFieldKey = DoubleArray(8) { Double.NaN }
    private val velocityKey = DoubleArray(6) { Double.NaN }
    private var paramFieldDrawn = false
    private var velocityDrawn = false

    /** `uRipples`, packed: x, y, age, amp per ring, zero for the empty slots. */
    private val ripples = FloatArray(maxRipples().toInt() * 4)

    init {
        val field = fieldGrid(gridWidth.toUInt(), gridHeight.toUInt())
        velocity = FloatTarget(field.width.toInt(), field.height.toInt())
        paramField = FloatTarget(field.width.toInt(), field.height.toInt())
    }

    /** Grid width / height: the shaders keep their discs and noise round with it. */
    val aspect: Float get() = state.width.toFloat() / state.height.toFloat()

    val gridWidth: Int get() = state.width
    val gridHeight: Int get() = state.height

    /** A new grid, keeping the pattern (the quality probe moves it once, at boot). */
    fun resize(width: Int, height: Int) {
        if (width == state.width && height == state.height) return
        state.resize(width, height)
        val field = fieldGrid(width.toUInt(), height.toUInt())
        velocity.release()
        paramField.release()
        velocity = FloatTarget(field.width.toInt(), field.height.toInt())
        paramField = FloatTarget(field.width.toInt(), field.height.toInt())
        // New textures: whatever the keys say, they hold nothing.
        paramFieldDrawn = false
        velocityDrawn = false
    }

    /** Paints a fresh start into both halves of the ping-pong (`SimEngine.reseed`). */
    fun reseed(seed: SeedSpots) {
        seedPass.use()
        seedPass.i("uSpotCount", seed.count.toInt())
        seedPass.vec2Array("uSpots", seed.xy.toFloatArray(), seed.count.toInt())
        seedPass.f("uSpotRadius", seed.radius)
        seedPass.f("uAspect", aspect)
        repeat(2) {
            state.writeTarget().bind()
            seedPass.draw()
            state.swap()
        }
    }

    /**
     * One simulation step: what the frame asks to be injected, then the
     * reaction's substeps, then one advection step.
     */
    fun step(frame: PictureFrame) {
        for (disc in frame.injects) {
            injectPass.use()
            injectPass.sampler("uState", 0, state.readTexture)
            injectPass.f2("uCenter", disc.x, disc.y)
            injectPass.f("uRadius", disc.radius)
            injectPass.f("uAmount", disc.amount)
            injectPass.f("uAspect", aspect)
            state.writeTarget().bind()
            injectPass.draw()
            state.swap()
        }

        val field = updateParamField(frame)
        val advecting = frame.flow.advecting
        if (advecting) updateVelocity(frame)

        reactPass.use()
        reactPass.f("uFeed", frame.reaction.feed)
        reactPass.f("uKill", frame.reaction.kill)
        reactPass.f("uDiffU", frame.reaction.diffU)
        reactPass.f("uDiffV", frame.reaction.diffV)
        reactPass.f("uDt", 1.0f)
        reactPass.sampler("uParamField", 1, field)
        repeat(frame.reaction.substeps.toInt().coerceAtLeast(1)) {
            reactPass.sampler("uState", 0, state.readTexture)
            state.writeTarget().bind()
            reactPass.draw()
            state.swap()
        }

        if (advecting) {
            advectPass.use()
            advectPass.sampler("uState", 0, state.readTexture)
            advectPass.sampler("uVelocity", 1, velocity.texture)
            advectPass.f("uAdvectAmount", frame.flow.advectAmount)
            state.writeTarget().bind()
            advectPass.draw()
            state.swap()
        }
    }

    /** The state as colour, onto the surface. */
    fun draw(frame: PictureFrame, surfaceWidth: Int, surfaceHeight: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        displayPass.use()
        displayPass.sampler("uState", 0, state.readTexture)
        displayPass.f2("uTexel", 1f / state.width, 1f / state.height)
        displayPass.f3("uPalA", frame.palette.a)
        displayPass.f3("uPalB", frame.palette.b)
        displayPass.f3("uPalC", frame.palette.c)
        displayPass.f3("uPalD", frame.palette.d)
        displayPass.f("uBands", frame.palette.bands)
        displayPass.f("uRelief", frame.palette.relief)
        displayPass.f3("uLightDir", frame.palette.lightDir)
        displayPass.f("uGloss", frame.palette.gloss)
        displayPass.f("uAspect", aspect)
        displayPass.f("uExposure", frame.display.exposure)
        displayPass.f("uFlash", frame.display.flash)
        displayPass.f3("uTint", frame.display.tint)
        ripples.fill(0f)
        for ((i, r) in frame.ripples.withIndex()) {
            if (i * 4 >= ripples.size) break
            ripples[i * 4] = r.x
            ripples[i * 4 + 1] = r.y
            ripples[i * 4 + 2] = r.age
            ripples[i * 4 + 3] = r.amp
        }
        displayPass.vec4Array("uRipples", ripples, ripples.size / 4)
        displayPass.draw()
    }

    /**
     * The feed/kill offset texture to hand the reaction — redrawn only when
     * it would come out different. Ten fbm evaluations per cell, so "it would
     * be all zeros anyway" is worth a branch.
     */
    private fun updateParamField(frame: PictureFrame): Int {
        val f = frame.fieldVariation
        if (!f.active) return zero
        val same = unchanged(
            paramFieldKey,
            f.feedAmount.toDouble(), f.feedScale.toDouble(), f.feedWarp.toDouble(),
            f.killAmount.toDouble(), f.killScale.toDouble(), f.killWarp.toDouble(),
            frame.evolveT.toDouble(), aspect.toDouble(),
        )
        if (same && paramFieldDrawn) return paramField.texture
        paramFieldPass.use()
        paramFieldPass.f("uFeedVarAmount", f.feedAmount)
        paramFieldPass.f("uFeedVarScale", f.feedScale)
        paramFieldPass.f("uFeedVarWarp", f.feedWarp)
        paramFieldPass.f("uKillVarAmount", f.killAmount)
        paramFieldPass.f("uKillVarScale", f.killScale)
        paramFieldPass.f("uKillVarWarp", f.killWarp)
        paramFieldPass.f("uEvolveT", frame.evolveT)
        paramFieldPass.f("uAspect", aspect)
        paramField.bind()
        paramFieldPass.draw()
        paramFieldDrawn = true
        return paramField.texture
    }

    /** The same deal for the advection velocity; only called when it runs. */
    private fun updateVelocity(frame: PictureFrame) {
        val flow = frame.flow
        val same = unchanged(
            velocityKey,
            flow.curlStrength.toDouble(), flow.curlScale.toDouble(),
            flow.driftX.toDouble(), flow.driftY.toDouble(),
            frame.evolveT.toDouble(), aspect.toDouble(),
        )
        if (same && velocityDrawn) return
        velocityPass.use()
        velocityPass.f("uCurlStrength", flow.curlStrength)
        velocityPass.f("uCurlScale", flow.curlScale)
        // The core already turned Drift Y over for a Y-up surface.
        velocityPass.f2("uDrift", flow.driftX, flow.driftY)
        velocityPass.f("uEvolveT", frame.evolveT)
        velocityPass.f("uAspect", aspect)
        velocity.bind()
        velocityPass.draw()
        velocityDrawn = true
    }

    fun release() {
        state.release()
        velocity.release()
        paramField.release()
        GLES30.glDeleteTextures(1, intArrayOf(zero), 0)
        for (p in listOf(seedPass, reactPass, displayPass, velocityPass, advectPass, paramFieldPass, injectPass)) {
            p.release()
        }
    }

}

/**
 * True when `key` already holds these values; otherwise copies them in and
 * returns false. One call answers both "may I keep the texture?" and
 * "remember what I am about to draw".
 */
private fun unchanged(key: DoubleArray, vararg next: Double): Boolean {
    var same = true
    for (i in next.indices) {
        if (key[i] != next[i]) {
            same = false
            key[i] = next[i]
        }
    }
    return same
}

private fun AssetManager.read(path: String): String = open(path).use { it.readBytes().decodeToString() }

/** `List<Float>` from the core; GL wants an array. */
private fun List<Float>.toFloatArray(): FloatArray = FloatArray(size) { this[it] }
