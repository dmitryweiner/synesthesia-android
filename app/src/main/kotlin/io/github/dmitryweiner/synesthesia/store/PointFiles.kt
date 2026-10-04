package io.github.dmitryweiner.synesthesia.store

import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Where the points are kept (PLAN.md decision 7): the web app's JSON in the
 * app's own files, with the names the console uses —
 *
 *  * `last-point.json` — the point as it was left, restored on the next start;
 *  * `points.json` — the points the user kept, an array of `{name, state}`.
 *
 * so that a file copied off a phone opens in the console and the other way
 * round. Nothing here knows what is inside them: the core parses and writes
 * the text ([io.github.dmitryweiner.synesthesia.core.PointList]), and this
 * only moves it to and from the disk.
 *
 * Writes go through a temporary file and a rename, so a save interrupted by
 * the system cannot leave half a point behind, and they happen on a thread of
 * their own — the last point is written every time a change settles, and the
 * main thread has a picture to draw.
 */
class PointFiles(private val dir: File) {
    private val writes = Executors.newSingleThreadExecutor { r ->
        Thread(r, "syn-store").apply { priority = Thread.MIN_PRIORITY }
    }

    /** The point as it was left, or null when there is none to restore. */
    fun lastPoint(): String? = read(LAST_POINT)

    /** Keeps the point as the one to come back to. */
    fun saveLastPoint(json: String) = write(LAST_POINT, json)

    /** The points file's text, or null when nothing has been kept yet. */
    fun points(): String? = read(POINTS)

    fun savePoints(json: String) = write(POINTS, json)

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
    }
}
