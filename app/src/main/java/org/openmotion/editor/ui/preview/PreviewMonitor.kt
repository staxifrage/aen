package org.openmotion.editor.ui.preview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.openmotion.editor.engine.pipeline.CompositorEngine
import org.openmotion.editor.model.Layer
import org.openmotion.editor.ui.theme.AccentPrimary
import org.openmotion.editor.ui.theme.BgViewport

/**
 * Preview Monitor Viewport.
 * Hosts the hardware-accelerated OpenGL SurfaceView while enforcing aspect ratio framing
 * and drawing interactive canvas manipulation boundaries.
 */
@Composable
fun PreviewMonitor(
    engine: CompositorEngine,
    selectedLayer: Layer?,
    aspectRatio: Float = 9f / 16f,
    onTransformChange: (deltaX: Float, deltaY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgViewport),
        contentAlignment = Alignment.Center
    ) {
        // Aspect Ratio Framed Canvas Viewport
        Box(
            modifier = Modifier
                .padding(12.dp)
                .aspectRatio(aspectRatio)
                .background(Color.Black)
        ) {
            // Native OpenGL ES SurfaceView
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                engine.attachSurface(holder.surface, width, height)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                engine.attachSurface(holder.surface, width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                engine.detachSurface()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Direct Manipulation Overlay (Bounding Box & Touch Gizmos)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(selectedLayer) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onTransformChange(dragAmount.x, dragAmount.y)
                        }
                    }
            ) {
                if (selectedLayer != null) {
                    val evaluated = selectedLayer.transform.evaluateAt(0L)
                    val centerX = size.width / 2f + evaluated.posX
                    val centerY = size.height / 2f + evaluated.posY

                    val boxW = 200f * evaluated.scaleX
                    val boxH = 200f * evaluated.scaleY
                    val left = centerX - boxW / 2f
                    val top = centerY - boxH / 2f

                    // Draw selection bounding rectangle
                    drawRect(
                        color = AccentPrimary,
                        topLeft = Offset(left, top),
                        size = Size(boxW, boxH),
                        style = Stroke(width = 3f)
                    )

                    // Draw corner control handles
                    val handleRadius = 10f
                    val corners = listOf(
                        Offset(left, top),
                        Offset(left + boxW, top),
                        Offset(left, top + boxH),
                        Offset(left + boxW, top + boxH)
                    )
                    corners.forEach { corner ->
                        drawCircle(
                            color = Color.White,
                            radius = handleRadius,
                            center = corner
                        )
                        drawCircle(
                            color = AccentPrimary,
                            radius = handleRadius,
                            center = corner,
                            style = Stroke(width = 3f)
                        )
                    }

                    // Draw anchor pivot point
                    drawCircle(
                        color = AccentPrimary,
                        radius = 6f,
                        center = Offset(centerX, centerY)
                    )
                }
            }
        }
    }
}
