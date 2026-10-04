package io.github.dmitryweiner.synesthesia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame

/**
 * What the sound is doing, as a strip under the picture. Phase 1 drew every
 * feature as its own bar because there was no picture yet to show them in;
 * the picture does that now (loudness breathes the exposure, an onset flares
 * and seeds, the bands tint the tones), so what is left here is the spectrum,
 * which the picture does not show, and the numbers.
 */
@Composable
fun Meters(frame: AudioFrame?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Spectrum(frame?.spectrum, Modifier.fillMaxWidth().height(40.dp))
        val f = frame
        Text(
            if (f == null) "—" else "hits ${f.hits} · peak %.3f · limiter %.1f dB · t %.1f s".format(f.peak, f.limiterDb, f.time),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun StatsLine(stats: OutputStats?) {
    Text(
        if (stats == null) {
            "—"
        } else {
            "%d Hz · buffer %d ms · core %.1f%% (peak %.0f%%) · underruns %d".format(
                stats.sampleRate, stats.bufferMs, 100 * stats.load, 100 * stats.loadPeak, stats.underruns,
            )
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Spectrum(bands: ByteArray?, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        drawRect(track)
        val b = bands ?: return@Canvas
        val w = size.width / b.size
        for (i in b.indices) {
            val v = (b[i].toInt() and 0xFF) / 255f
            drawRect(
                Color(color.red, color.green, color.blue, 0.4f + 0.6f * v),
                topLeft = Offset(i * w, size.height * (1 - v)),
                size = Size(w * 0.8f, size.height * v),
            )
        }
    }
}
