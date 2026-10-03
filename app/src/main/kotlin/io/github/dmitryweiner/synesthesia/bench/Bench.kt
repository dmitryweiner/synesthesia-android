package io.github.dmitryweiner.synesthesia.bench

import android.os.Build
import io.github.dmitryweiner.synesthesia.core.presetStateJson
import io.github.dmitryweiner.synesthesia.core.presets
import io.github.dmitryweiner.synesthesia.core.renderStats
import kotlin.math.log10

/**
 * How fast this phone renders each built-in point (PLAN.md decision 12): the
 * core renders [SECONDS] of it offline, on the calling thread, at the
 * device's rate, and the wall time says how many times faster than real
 * time that is — the live player needs > 1×, and its share of one core is
 * the inverse.
 */
object Bench {
    const val SECONDS = 10.0

    data class Row(val index: Int, val name: String, val realtime: Double, val rmsDb: Double) {
        /** Share of one core the live sound of this point costs. */
        val coreShare: Double get() = 1.0 / realtime
    }

    val count: Int get() = presets().size

    /** Measures built-in point [index]. Blocks for a second or more; not on the main thread. */
    fun measure(index: Int, sampleRate: Int): Row {
        val json = checkNotNull(presetStateJson(index.toUInt()))
        val t0 = System.nanoTime()
        val stats = renderStats(json, SECONDS, sampleRate.toUInt())
        val wall = (System.nanoTime() - t0) / 1e9
        return Row(index, presets()[index].name, SECONDS / wall, 20 * log10(maxOf(stats.rms, 1e-9)))
    }

    /** Which phone, for the report. */
    fun device(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL}$soc · Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.first()}"
    }

    /** The results as plain text, to paste into an issue or a chat. */
    fun report(sampleRate: Int, rows: List<Row>): String = buildString {
        appendLine("Synesthesia bench — ${device()}")
        appendLine("${SECONDS.toInt()} s per point at $sampleRate Hz, one thread")
        for (r in rows) {
            appendLine("%2d  %-16s %6.1f×  %5.1f%% of a core  %6.1f dB".format(r.index, r.name, r.realtime, 100 * r.coreShare, r.rmsDb))
        }
        rows.minByOrNull { it.realtime }?.let { appendLine("slowest: ${it.name}, %.1f×".format(it.realtime)) }
    }
}
