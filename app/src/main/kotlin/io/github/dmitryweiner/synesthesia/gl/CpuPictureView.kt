package io.github.dmitryweiner.synesthesia.gl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import io.github.dmitryweiner.synesthesia.core.backingStore
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The picture without a GPU: the core's own CPU renderer, for a device that
 * cannot draw into a float texture (PLAN.md decision 4). The same driver, the
 * same frames, the same seed spots — only the field lives in RAM and arrives
 * here as pixels.
 *
 * It costs a whole simulation step per frame, so the step runs on a thread of
 * its own and this view only blits what it finished: the one thing every
 * device is good at. The quality ladder is the same one the GL path walks,
 * measured the same way, and it caps the picture's size here rather than a
 * surface's.
 */
class CpuPictureView(context: Context) : View(context) {
    var source: PictureSource? = null

    private val paint = Paint().apply { isFilterBitmap = true }
    private val destination = Rect()
    private val renders = Executors.newSingleThreadExecutor { r ->
        Thread(r, "syn-picture-cpu").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
    private val rendering = AtomicBoolean(false)
    private var stopped = false

    /** Written by the render thread, read while drawing. */
    @Volatile private var ready: Bitmap? = null

    private var scratch: Bitmap? = null
    private var pictureWidth = 0
    private var pictureHeight = 0
    private var seeded = false
    private var lastFrame = 0L

    init {
        // The picture is what the user is watching (PLAN.md decision 9).
        keepScreenOn = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopped = false
        postFrame()
    }

    override fun onDetachedFromWindow() {
        stopped = true
        renders.shutdown()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = ready ?: return
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called below
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val driver = source?.picture ?: return false
        if (width == 0 || height == 0) return false
        val x = (event.x / width).coerceIn(0f, 1f)
        // The picture's rows run top to bottom; the field's UV is Y-up.
        val y = (1f - event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> driver.pointerDown(x, y, System.nanoTime() / 1e9)
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

    /** One frame per display frame, as long as the last one has finished. */
    private fun postFrame() {
        if (stopped) return
        postOnAnimation {
            if (!stopped && rendering.compareAndSet(false, true)) {
                val now = System.nanoTime()
                val interval = if (lastFrame == 0L) 0.0 else (now - lastFrame) / 1e6
                lastFrame = now
                val (w, h) = sizeFor(now, interval)
                if (w > 0 && h > 0) {
                    renders.execute { render(now, w, h) }
                } else {
                    rendering.set(false)
                }
            }
            postFrame()
        }
    }

    /**
     * The picture's size for this frame: the rung's cap on the view, with the
     * ladder walked from the real interval between frames — the same probe the
     * GL path feeds.
     */
    private fun sizeFor(now: Long, intervalMs: Double): Pair<Int, Int> {
        val driver = source?.picture ?: return 0 to 0
        if (width == 0 || height == 0) return 0 to 0
        if (intervalMs > 0.0) driver.probeFrame(intervalMs)
        val store = backingStore(driver.rung().maxSide, width.toUInt(), height.toUInt())
        return store.width.toInt() to store.height.toInt()
    }

    private fun render(now: Long, width: Int, height: Int) {
        try {
            val source = source ?: return
            val driver = source.picture
            if (width != pictureWidth || height != pictureHeight) {
                driver.useCpuPicture(now.toUInt(), width.toUInt(), height.toUInt())
                scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pictureWidth = width
                pictureHeight = height
                seeded = false
            }
            if (!seeded || source.takeReseed()) {
                driver.cpuSeed(driver.reseed())
                seeded = true
            }
            driver.frame(now / 1e9, source.soundFrame(), width.toFloat() / height)
            val pixels = driver.cpuFrame() ?: return
            val bitmap = scratch ?: return
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            ready = bitmap
            postInvalidate()
        } finally {
            rendering.set(false)
        }
    }
}
