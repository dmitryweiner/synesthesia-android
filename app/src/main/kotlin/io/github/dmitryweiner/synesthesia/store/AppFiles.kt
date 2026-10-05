package io.github.dmitryweiner.synesthesia.store

import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * What the app keeps between runs (PLAN.md decision 7): the web app's JSON in
 * the app's own files, with the names the console uses —
 *
 *  * `last-point.json` — the point as it was left, restored on the next start,
 *    and `last-name.txt`, what it was called on screen;
 *  * `points.json` — the points the user kept, an array of `{name, state}`;
 *  * `view.txt` — which view was on screen, and whether the welcome has been
 *    shown. One line each, because a line is all they are; the settings
 *    decision 7 speaks of are the point's own, and those live in the point.
 *
 * so that a file copied off a phone opens in the console and the other way
 * round. Nothing here knows what is inside them: the core parses and writes
 * the point text ([io.github.dmitryweiner.synesthesia.core.PointList]), and
 * this only moves it to and from the disk.
 *
 * Writes go through a temporary file and a rename, so a save interrupted by
 * the system cannot leave half a point behind, and they happen on a thread of
 * their own — the last point is written every time a change settles, and the
 * main thread has a picture to draw.
 */
class AppFiles(private val dir: File) {
    private val writes = Executors.newSingleThreadExecutor { r ->
        Thread(r, "syn-store").apply { priority = Thread.MIN_PRIORITY }
    }

    /** The point as it was left, or null when there is none to restore. */
    fun lastPoint(): String? = read(LAST_POINT)

    /** Keeps the point as the one to come back to. */
    fun saveLastPoint(json: String) = write(LAST_POINT, json)

    /**
     * What the point was called on screen when it was left — "Fractal garden"
     * for a point a press or two away from it. The point itself claims no
     * name (that is what a press takes away), so the title would come back
     * empty without this.
     */
    fun lastName(): String? = read(LAST_NAME)?.trim()?.ifEmpty { null }

    fun saveLastName(name: String) = write(LAST_NAME, name)

    /** The points file's text, or null when nothing has been kept yet. */
    fun points(): String? = read(POINTS)

    fun savePoints(json: String) = write(POINTS, json)

    /** Which view was on screen when the app was last closed. */
    fun view(): String? = read(VIEW)

    fun saveView(name: String) = write(VIEW, name)

    /** True once the welcome has been shown, so it is shown once. */
    fun welcomeShown(): Boolean = read(WELCOME) != null

    fun rememberWelcomeShown() = write(WELCOME, "shown")

    /** For tests: waits until everything asked for has reached the disk. */
    fun awaitWrites(timeoutMs: Long): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        writes.execute { done.countDown() }
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return try {
            file.readText().ifBlank { null }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "could not read $name", e)
            null
        }
    }

    private fun write(name: String, json: String) {
        writes.execute {
            val file = File(dir, name)
            val temporary = File(dir, "$name.tmp")
            try {
                dir.mkdirs()
                temporary.writeText(json)
                if (!temporary.renameTo(file)) {
                    // Some filesystems refuse a rename onto an existing file.
                    file.delete()
                    check(temporary.renameTo(file)) { "could not replace $name" }
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "could not write $name", e)
                temporary.delete()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "could not write $name", e)
                temporary.delete()
            }
        }
    }

    private companion object {
        const val TAG = "SynStore"
        const val LAST_POINT = "last-point.json"
        const val POINTS = "points.json"
        const val LAST_NAME = "last-name.txt"
        const val VIEW = "view.txt"
        const val WELCOME = "welcome.txt"
    }
}
