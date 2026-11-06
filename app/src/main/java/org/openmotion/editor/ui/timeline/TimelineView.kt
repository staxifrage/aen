package org.openmotion.editor.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openmotion.editor.model.Layer
import org.openmotion.editor.model.LayerType
import org.openmotion.editor.model.Project
import org.openmotion.editor.ui.components.TimecodeFormatter
import org.openmotion.editor.ui.theme.AccentKeyframe
import org.openmotion.editor.ui.theme.AccentPlayhead
import org.openmotion.editor.ui.theme.AccentPrimary
import org.openmotion.editor.ui.theme.BgPanel
import org.openmotion.editor.ui.theme.BgPanelElevated
import org.openmotion.editor.ui.theme.BgTimelineTrack
import org.openmotion.editor.ui.theme.BgTimelineTrackActive
import org.openmotion.editor.ui.theme.BorderSubtle
import org.openmotion.editor.ui.theme.LayerAudioColor
import org.openmotion.editor.ui.theme.LayerShapeColor
import org.openmotion.editor.ui.theme.LayerTextColor
import org.openmotion.editor.ui.theme.LayerVideoColor
import org.openmotion.editor.ui.theme.TextPrimary
import org.openmotion.editor.ui.theme.TextSecondary

/**
 * Multi-track Timeline Interface.
 * Handles layer stacking, keyframe manipulation layout, and real-time scrubbing mechanics.
 */
@Composable
fun TimelineView(
    project: Project,
    currentPlayheadMs: Long,
    selectedLayer: Layer?,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSelectLayer: (Layer) -> Unit,
    onAddKeyframe: () -> Unit,
    onSplitLayer: () -> Unit,
    onDeleteLayer: () -> Unit,
    onAddLayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalDurationMs = project.durationMs.coerceAtLeast(1000L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgPanel)
    ) {
        // 1. Playbar and Keyframe Action Controls
        TimelineControlBar(
            currentPlayheadMs = currentPlayheadMs,
            fps = project.frameRate,
            isPlaying = isPlaying,
            hasSelectedLayer = selectedLayer != null,
            onTogglePlayPause = onTogglePlayPause,
            onStepFrame = { frames ->
                val frameDuration = 1000L / project.frameRate
                onSeek((currentPlayheadMs + frames * frameDuration).coerceIn(0L, totalDurationMs))
            },
            onAddKeyframe = onAddKeyframe,
            onSplitLayer = onSplitLayer,
            onDeleteLayer = onDeleteLayer,
            onAddLayer = onAddLayer
        )

        // 2. Timeline Tracks Body & Playhead Scrubber
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Time Ruler (Scrubbable Header)
                TimelineRuler(
                    durationMs = totalDurationMs,
                    onSeek = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                )

                // Vertical Multi-Track Layer List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(project.layers.reversed(), key = { it.id }) { layer ->
                        TimelineTrackRow(
                            layer = layer,
                            projectDurationMs = totalDurationMs,
                            isSelected = layer.id == selectedLayer?.id,
                            onSelect = { onSelectLayer(layer) }
                        )
                    }
                }
            }

            // Scrubbing Playhead Needle Overlay
            TimelinePlayhead(
                currentPlayheadMs = currentPlayheadMs,
                totalDurationMs = totalDurationMs,
                onSeek = onSeek,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun TimelineControlBar(
    currentPlayheadMs: Long,
    fps: Int,
    isPlaying: Boolean,
    hasSelectedLayer: Boolean,
    onTogglePlayPause: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onAddKeyframe: () -> Unit,
    onSplitLayer: () -> Unit,
    onDeleteLayer: () -> Unit,
    onAddLayer: () -> Unit
) {
    Surface(
        color = BgPanelElevated,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(width = 1.dp, color = BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Timecode Readout
            Text(
                text = TimecodeFormatter.format(currentPlayheadMs, fps),
                style = MaterialTheme.typography.labelSmall,
                color = AccentPrimary,
                fontSize = 13.sp
            )

            // Playback Transport Buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onStepFrame(-1) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Step Back", tint = TextPrimary)
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(38.dp)
                        .background(AccentPrimary, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.Black
                    )
                }

                IconButton(onClick = { onStepFrame(1) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.FastForward, contentDescription = "Step Forward", tint = TextPrimary)
                }
            }

            // Layer & Keyframe Actions (Alight-style diamond and edit buttons)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onAddKeyframe,
                    enabled = hasSelectedLayer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Diamond,
                        contentDescription = "Keyframe",
                        tint = if (hasSelectedLayer) AccentKeyframe else TextSecondary.copy(alpha = 0.4f)
                    )
                }

                IconButton(
                    onClick = onSplitLayer,
                    enabled = hasSelectedLayer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = "Split",
                        tint = if (hasSelectedLayer) TextPrimary else TextSecondary.copy(alpha = 0.4f)
                    )
                }

                IconButton(
                    onClick = onDeleteLayer,
                    enabled = hasSelectedLayer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = if (hasSelectedLayer) Color(0xFFFF5252) else TextSecondary.copy(alpha = 0.4f)
                    )
                }

                IconButton(onClick = onAddLayer, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Layer", tint = AccentPrimary)
                }
            }
        }
    }
}

@Composable
private fun TimelineRuler(
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .background(BgTimelineTrack)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek((ratio * durationMs).toLong())
                }
            }
    ) {
        val width = size.width
        val height = size.height

        // Tick marks every 1 second and sub-ticks
        val seconds = (durationMs / 1000).toInt().coerceAtLeast(1)
        val pxPerSec = width / seconds

        for (sec in 0..seconds) {
            val x = sec * pxPerSec
            // Major tick
            drawLine(
                color = TextSecondary,
                start = Offset(x, height - 12f),
                end = Offset(x, height),
                strokeWidth = 2f
            )

            // Sub ticks (half seconds)
            val halfX = x + pxPerSec / 2f
            if (halfX < width) {
                drawLine(
                    color = BorderSubtle,
                    start = Offset(halfX, height - 6f),
                    end = Offset(halfX, height),
                    strokeWidth = 1f
                )
            }
        }
    }
}

@Composable
private fun TimelineTrackRow(
    layer: Layer,
    projectDurationMs: Long,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val trackBg = if (isSelected) BgTimelineTrackActive else BgTimelineTrack
    val layerTypeColor = when (layer.type) {
        LayerType.VIDEO -> LayerVideoColor
        LayerType.SHAPE -> LayerShapeColor
        LayerType.AUDIO -> LayerAudioColor
        LayerType.TEXT, LayerType.IMAGE -> LayerTextColor
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(trackBg)
            .clickable { onSelect() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        // Track Strip Bar positioned by start/end times
        val startFraction = (layer.startTimeMs.toFloat() / projectDurationMs).coerceIn(0f, 1f)
        val endFraction = (layer.endTimeMs.toFloat() / projectDurationMs).coerceIn(0f, 1f)
        val durationFraction = (endFraction - startFraction).coerceAtLeast(0.02f)

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = durationFraction)
        )

        // Custom rendering for layer strip, keyframes, and trim handles
        Canvas(modifier = Modifier.fillMaxSize()) {
            val totalW = size.width
            val startX = startFraction * totalW
            val stripW = durationFraction * totalW
            val h = size.height

            // Layer Body Card
            drawRoundRect(
                color = layerTypeColor,
                topLeft = Offset(startX, 0f),
                size = Size(stripW, h),
                cornerRadius = CornerRadius(6f, 6f)
            )

            // Active Border if Selected
            if (isSelected) {
                drawRoundRect(
                    color = AccentPrimary,
                    topLeft = Offset(startX, 0f),
                    size = Size(stripW, h),
                    cornerRadius = CornerRadius(6f, 6f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Keyframe Diamond Markers
            val allKeyframes = layer.transform.posXKeyframes +
                    layer.transform.posYKeyframes +
                    layer.transform.scaleKeyframes +
                    layer.transform.rotationKeyframes +
                    layer.transform.opacityKeyframes

            val distinctTimes = allKeyframes.map { it.timeMs }.distinct()
            distinctTimes.forEach { kfTime ->
                if (kfTime in layer.startTimeMs..layer.endTimeMs) {
                    val kfX = (kfTime.toFloat() / projectDurationMs) * totalW
                    val kfCenterY = h / 2f
                    val diamondSize = 6.dp.toPx()

                    val diamondPath = Path().apply {
                        moveTo(kfX, kfCenterY - diamondSize)
                        lineTo(kfX + diamondSize, kfCenterY)
                        lineTo(kfX, kfCenterY + diamondSize)
                        lineTo(kfX - diamondSize, kfCenterY)
                        close()
                    }
                    drawPath(diamondPath, color = AccentKeyframe)
                }
            }
        }

        // Layer Name Label
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${layer.name} (${layer.type})",
                style = MaterialTheme.typography.titleSmall,
                fontSize = 11.sp,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun TimelinePlayhead(
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(totalDurationMs) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaRatio = dragAmount.x / size.width
                    val deltaMs = (deltaRatio * totalDurationMs).toLong()
                    onSeek((currentPlayheadMs + deltaMs).coerceIn(0L, totalDurationMs))
                }
            }
    ) {
        val width = size.width
        val height = size.height

        val playheadRatio = (currentPlayheadMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
        val x = playheadRatio * width

        // Playhead Scrubber Needle (Red line)
        drawLine(
            color = AccentPlayhead,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 2.dp.toPx()
        )

        // Scrubber Needle Top Head (Inverted Triangle)
        val headPath = Path().apply {
            moveTo(x - 8.dp.toPx(), 0f)
            lineTo(x + 8.dp.toPx(), 0f)
            lineTo(x, 14.dp.toPx())
            close()
        }
        drawPath(headPath, color = AccentPlayhead)
    }
}
