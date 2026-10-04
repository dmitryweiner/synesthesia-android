package io.github.dmitryweiner.synesthesia.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.dmitryweiner.synesthesia.gl.CpuPictureView
import io.github.dmitryweiner.synesthesia.gl.PictureSource
import io.github.dmitryweiner.synesthesia.gl.PictureView

/**
 * The picture (PLAN.md phase 3): the point's sound as a Gray–Scott field,
 * drawn by the web app's own shaders. A finger paints into it.
 *
 * It runs only while the Activity is visible — the GL thread is parked with
 * the lifecycle — and the sound goes on without it. The LFO clock is the
 * audio clock, so the picture rejoins in step (PLAN.md decision 10).
 */
@Composable
fun Picture(source: PictureSource, modifier: Modifier = Modifier) {
    var missingFloatTargets by remember { mutableStateOf(false) }
    val held = remember { HeldView() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (missingFloatTargets) {
            // No float render targets: the core draws the picture itself and
            // this only shows the pixels (PLAN.md decision 4).
            AndroidView(
                factory = { context: Context -> CpuPictureView(context).also { it.source = source } },
                modifier = Modifier.fillMaxSize().testTag("picture"),
                update = { it.source = source },
            )
        } else {
            AndroidView(
                factory = { context: Context ->
                    PictureView(context).also { view ->
                        view.source = source
                        view.onFloatTargetsMissing = { missingFloatTargets = true }
                        held.view = view
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("picture"),
                update = { it.source = source },
                onRelease = { held.view = null },
            )
        }
    }

    DisposableEffect(held, lifecycle) {
        // A GL surface holds its own thread, and it must stop with the
        // Activity: a picture nobody is looking at still costs a GPU.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> held.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> held.view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            held.view?.onPause()
        }
    }
}

/** The view the lifecycle observer parks and wakes, without making it state. */
private class HeldView {
    var view: PictureView? = null
}
