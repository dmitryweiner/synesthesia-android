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

/** The features the picture will listen to, drawn plainly (phase 1's stand-in for the picture). */
@Composable
fun Meters(frame: AudioFrame?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Spectrum(frame?.spectrum, Modifier.fillMaxWidth().height(72.dp))
        Bar("loud", frame?.loudness ?: 0.0)
        Bar("bright", frame?.brightness ?: 0.0)
        Bar("low", frame?.low ?: 0.0)
        Bar("mid", frame?.mid ?: 0.0)
        Bar("high", frame?.high ?: 0.0)
        Bar("onset", frame?.onset ?: 0.0)
        CenteredBar("swell", frame?.swell ?: 0.0)
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
private fun Bar(label: String, value: Double) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(track)
            drawRect(color, size = Size(size.width * value.coerceIn(0.0, 1.0).toFloat(), size.height))
        }
    }
}

@Composable
private fun CenteredBar(label: String, value: Double) {
    val color = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(track)
            val mid = size.width / 2
            val w = mid * value.coerceIn(-1.0, 1.0).toFloat()
            drawRect(color, topLeft = Offset(minOf(mid, mid + w), 0f), size = Size(kotlin.math.abs(w), size.height))
        }
    }
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
