package io.github.dmitryweiner.synesthesia.ui

import android.graphics.Bitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame

/**
 * What the sound is doing, in the picture's place — the console's spectrum
 * view (`VizMode::Spectrum`). The two share the space because together they
 * fight: the bars read as part of the image.
 *
 * A spectrogram, not a row of bars: time runs left to right, frequency bottom
 * to top (the core's 64 log-spaced bands), and loudness is colour, the way a
 * spectrogram is usually drawn. The history lives in a bitmap one pixel wide
 * per frame, written one column at a time and drawn in a single call — a
 * rectangle per cell would be eight thousand draws a frame.
 *
 * The features keep their bars here and nowhere else: when the picture is on,
 * it shows them better than a bar does (loudness breathes the exposure, an
 * onset flares and seeds, the bands tint the tones).
 */
@Composable
fun Spectrogram(frame: AudioFrame?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Waterfall(frame, Modifier.fillMaxWidth().weight(1f))
        Bar(stringResource(R.string.meter_loud), frame?.loudness ?: 0.0)
        Bar(stringResource(R.string.meter_bright), frame?.brightness ?: 0.0)
        Bar(stringResource(R.string.meter_low), frame?.low ?: 0.0)
        Bar(stringResource(R.string.meter_mid), frame?.mid ?: 0.0)
        Bar(stringResource(R.string.meter_high), frame?.high ?: 0.0)
        Bar(stringResource(R.string.meter_onset), frame?.onset ?: 0.0)
        CenteredBar(stringResource(R.string.meter_swell), frame?.swell ?: 0.0)
        val f = frame
        Text(
            if (f == null) {
                stringResource(R.string.no_numbers)
            } else {
                stringResource(R.string.frame_stats, f.hits.toInt(), f.peak, f.limiterDb, f.time)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** How many frames of history the spectrogram keeps — about four seconds. */
private const val HISTORY = 240

@Composable
private fun Waterfall(frame: AudioFrame?, modifier: Modifier) {
    val bands = frame?.spectrum?.size ?: 64
    val history = remember(bands) { Bitmap.createBitmap(HISTORY, bands, Bitmap.Config.ARGB_8888) }
    val column = remember(bands) { IntArray(bands) }
    // Where the newest column sits; the bitmap is a ring, drawn in two pieces.
    var head by remember(bands) { mutableIntStateOf(0) }
    val image = remember(history) { history.asImageBitmap() }

    if (frame != null) {
        val spectrum = frame.spectrum
        for (i in column.indices) {
            // Row 0 is the top of the bitmap, and the lowest band belongs at
            // the bottom.
            val level = (spectrum[column.size - 1 - i].toInt() and 0xFF) / 255f
            column[i] = heat(level)
        }
        history.setPixels(column, 0, 1, head, 0, 1, column.size)
        head = (head + 1) % HISTORY
    }

    Canvas(modifier) {
        drawRect(Color(0xFF07070C))
        val older = HISTORY - head
        val scale = size.width / HISTORY
        // The ring, oldest first: the part after the head, then the part
        // before it.
        drawImage(
            image = image,
            srcOffset = IntOffset(head, 0),
            srcSize = IntSize(older, bands),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize((older * scale).roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        if (head > 0) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(head, bands),
                dstOffset = IntOffset((older * scale).roundToInt(), 0),
                dstSize = IntSize((head * scale).roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

/**
 * Loudness as colour, the ramp a spectrogram is usually drawn with: near
 * black where there is nothing, through blue and red, to yellow and white
 * where it is loudest.
 */
private fun heat(level: Float): Int {
    val stops = HEAT_STOPS
    val t = level.coerceIn(0f, 1f) * (stops.size - 1)
    val i = t.toInt().coerceAtMost(stops.size - 2)
    val f = t - i
    val (ar, ag, ab) = stops[i]
    val (br, bg, bb) = stops[i + 1]
    val r = ((ar + (br - ar) * f) * 255).roundToInt()
    val g = ((ag + (bg - ag) * f) * 255).roundToInt()
    val b = ((ab + (bb - ab) * f) * 255).roundToInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private val HEAT_STOPS = listOf(
    Triple(0.03f, 0.03f, 0.06f), // silence
    Triple(0.13f, 0.07f, 0.35f), // deep blue
    Triple(0.47f, 0.11f, 0.42f), // violet
    Triple(0.78f, 0.22f, 0.27f), // red
    Triple(0.95f, 0.53f, 0.10f), // orange
    Triple(0.99f, 0.85f, 0.35f), // yellow
    Triple(1.00f, 1.00f, 0.92f), // the loudest
)

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
fun StatsLine(stats: OutputStats?) {
    Text(
        if (stats == null) {
            stringResource(R.string.no_numbers)
        } else {
            stringResource(
                R.string.output_stats,
                stats.sampleRate, stats.bufferMs, 100 * stats.load, 100 * stats.loadPeak, stats.underruns,
            )
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

