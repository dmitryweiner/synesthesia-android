package io.github.dmitryweiner.synesthesia.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.PictureDriver
import io.github.dmitryweiner.synesthesia.core.backingStore
import io.github.dmitryweiner.synesthesia.core.simGrid
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * What the picture needs from the app. The view knows nothing about playback,
 * and a test can hand it a driver of its own.
 */
interface PictureSource {
    /** The core's per-frame driver (PLAN.md decision 4). */
    val picture: PictureDriver

    /** The frame being *heard* now, or null when nothing plays. Any thread. */
    fun soundFrame(): AudioFrame?

    /** True once, when the session asked for a fresh start (🎲, a load). */
    fun takeReseed(): Boolean
}

/**
 * The picture on screen: a GL surface whose thread runs the seven passes
 * ([SimRenderer]) on what the core hands it per frame.
 *
 * The surface is the one the web app's canvas is: its size is capped by the
 * quality rung (`SurfaceHolder.setFixedSize`, the compositor scales the rest,
 * which is the one thing every device is good at), and the simulation grid
 * follows the same rung. Which rung this is is measured here, from the real
 * interval between frames, and then never moved again.
 *
 * A finger paints: the same disc and the same ripple an onset hit makes, in
 * the core, so a Swift app gets it for nothing.
 */
class PictureView(context: Context) : GLSurfaceView(context) {
    /** Set before the view is attached. */
    var source: PictureSource? = null

    /** Set when the device cannot render into a float texture (PLAN.md decision 4). */
    var onFloatTargetsMissing: (() -> Unit)? = null

    private val renderer = PassRenderer()

    @Volatile private var viewWidth = 0

    @Volatile private var viewHeight = 0

    init {
        setEGLContextClientVersion(3)
        // No depth, no stencil, no alpha: seven fullscreen passes and a blit.
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // The picture is what the user is watching (PLAN.md decision 9).
        keepScreenOn = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called below
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val driver = source?.picture ?: return false
        if (width == 0 || height == 0) return false
        val x = (event.x / width).coerceIn(0f, 1f)
        // The surface is Y-up (the fullscreen triangle), a view is Y-down.
        val y = (1f - event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                driver.pointerDown(x, y, nowSeconds())
            }
            MotionEvent.ACTION_MOVE -> driver.pointerMoved(x, y)
            MotionEvent.ACTION_UP -> {
                driver.pointerUp()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> driver.pointerUp()
            else -> return false
        }
        return true
    }

    private inner class PassRenderer : GLSurfaceView.Renderer {
        private var sim: SimRenderer? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var seeded = false
        private var lastFrame = 0L
        private var framesSeen = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            sim?.release()
            sim = null
            seeded = false
            lastFrame = 0L
            framesSeen = 0
            if (!floatTargetsAvailable()) {
                Log.w(TAG, "no float render targets: the picture needs the CPU path")
                post { onFloatTargetsMissing?.invoke() }
                return
            }
            // A placeholder grid: the real one needs the surface's size, which
            // arrives next, in onSurfaceChanged.
            sim = SimRenderer(context.assets, MIN_GRID, MIN_GRID)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            applyQuality()
        }

        override fun onDrawFrame(gl: GL10?) {
            val sim = sim ?: return
            val source = source ?: return
            val now = System.nanoTime()
            // What the device actually does, swap included — the interval
            // between two frames, not the time spent inside this call.
            if (lastFrame != 0L) {
                framesSeen++
                if (framesSeen > BOOT_WARMUP_FRAMES && source.picture.probeFrame((now - lastFrame) / 1e6)) {
                    applyQuality()
                }
            }
            lastFrame = now

            if (source.takeReseed()) sim.reseed(source.picture.reseed())
            val frame = source.picture.frame(now / 1e9, source.soundFrame(), sim.aspect)
            sim.step(frame)
            sim.draw(frame, surfaceWidth, surfaceHeight)
        }

        /**
         * The grid and the surface always move together: the display pass is
         * bound by the one and every reaction substep by the other.
         */
        private fun applyQuality() {
            val sim = sim ?: return
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val rung = source?.picture?.rung() ?: return
            val grid = simGrid(rung.res, surfaceWidth.toUInt(), surfaceHeight.toUInt())
            sim.resize(grid.width.toInt(), grid.height.toInt())
            if (!seeded) {
                sim.reseed(source?.picture?.reseed() ?: return)
                seeded = true
            }
            // Cap the surface itself, as the web app caps its canvas. The
            // view keeps its size; the compositor scales.
            val (vw, vh) = viewWidth to viewHeight
            if (vw > 0 && vh > 0) {
                val store = backingStore(rung.maxSide, vw.toUInt(), vh.toUInt())
                val (w, h) = store.width.toInt() to store.height.toInt()
                if (w != surfaceWidth || h != surfaceHeight) {
                    post { holder.setFixedSize(w, h) }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "SynPicture"

        /** Until the surface's size is known. */
        const val MIN_GRID = 64

        /**
         * Frames to let pass before the probe believes anything: shader
         * compilation, the first textures and the opening morph all land in
         * the first few and none of them is the steady state.
         */
        const val BOOT_WARMUP_FRAMES = 8

        fun nowSeconds(): Double = System.nanoTime() / 1_000_000_000.0
    }
}
