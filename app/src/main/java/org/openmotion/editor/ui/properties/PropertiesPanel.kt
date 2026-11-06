package org.openmotion.editor.ui.properties

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openmotion.editor.model.BlendMode
import org.openmotion.editor.model.Layer
import org.openmotion.editor.ui.theme.AccentKeyframe
import org.openmotion.editor.ui.theme.AccentPrimary
import org.openmotion.editor.ui.theme.BgPanel
import org.openmotion.editor.ui.theme.BgPanelElevated
import org.openmotion.editor.ui.theme.BorderSubtle
import org.openmotion.editor.ui.theme.TextPrimary
import org.openmotion.editor.ui.theme.TextSecondary
import java.util.Locale

enum class PropertyTab(val title: String, val icon: ImageVector) {
    TRANSFORM("Transform", Icons.Default.OpenWith),
    BLENDING("Blending", Icons.Default.Layers),
    COLOR("Color & Fill", Icons.Default.ColorLens)
}

/**
 * Properties Panel (Inspector).
 * Provides interactive manipulation for transformations, opacity/blending, and color grading.
 */
@Composable
fun PropertiesPanel(
    layer: Layer?,
    currentPlayheadMs: Long,
    onPropertyUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (layer == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(BgPanel),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Select a layer on the timeline to edit properties",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        return
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgPanel)
            .border(width = 1.dp, color = BorderSubtle)
    ) {
        // Tab Bar
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = BgPanelElevated,
            contentColor = AccentPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = AccentPrimary
                )
            }
        ) {
            PropertyTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tab.title, fontSize = 12.sp)
                        }
                    }
                )
            }
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            when (PropertyTab.entries[selectedTabIndex]) {
                PropertyTab.TRANSFORM -> TransformTabContent(layer, onPropertyUpdated)
                PropertyTab.BLENDING -> BlendingTabContent(layer, onPropertyUpdated)
                PropertyTab.COLOR -> ColorTabContent(layer, onPropertyUpdated)
            }
        }
    }
}

@Composable
private fun TransformTabContent(
    layer: Layer,
    onUpdated: () -> Unit
) {
    val transform = layer.transform

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Virtual Joystick / Control Pad (Alight-style pan controller)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text("Position Pad", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgPanelElevated)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            transform.posX += dragAmount.x
                            transform.posY += dragAmount.y
                            onUpdated()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(AccentPrimary, CircleShape)
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "X: ${transform.posX.toInt()}  Y: ${transform.posY.toInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
        }

        // Sliders for Scale & Rotation
        Column(
            modifier = Modifier.weight(1.5f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Scale Slider
            Text(
                "Scale: ${String.format(Locale.US, "%.2f", transform.scaleX)}x",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Slider(
                value = transform.scaleX,
                onValueChange = {
                    transform.scaleX = it
                    transform.scaleY = it
                    onUpdated()
                },
                valueRange = 0.1f..4.0f,
                colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
            )

            // Rotation Slider / Angle
            Text(
                "Rotation: ${transform.rotationDegrees.toInt()}°",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Slider(
                value = transform.rotationDegrees,
                onValueChange = {
                    transform.rotationDegrees = it
                    onUpdated()
                },
                valueRange = -180f..180f,
                colors = SliderDefaults.colors(thumbColor = AccentKeyframe, activeTrackColor = AccentKeyframe)
            )
        }
    }
}

@Composable
private fun BlendingTabContent(
    layer: Layer,
    onUpdated: () -> Unit
) {
    val transform = layer.transform

    Column(modifier = Modifier.fillMaxWidth()) {
        // Opacity Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Opacity", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text("${(transform.opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = AccentPrimary)
        }
        Slider(
            value = transform.opacity,
            onValueChange = {
                transform.opacity = it
                onUpdated()
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
        )

        Spacer(Modifier.height(12.dp))

        // Blend Mode Picker
        Text("Blend Mode", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(6.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(BlendMode.entries) { mode ->
                val isSelected = layer.blendMode == mode
                Surface(
                    color = if (isSelected) AccentPrimary else BgPanelElevated,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isSelected) AccentPrimary else BorderSubtle),
                    modifier = Modifier
                        .clickable {
                            layer.blendMode = mode
                            onUpdated()
                        }
                ) {
                    Text(
                        text = mode.displayName,
                        color = if (isSelected) Color.Black else TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorTabContent(
    layer: Layer,
    onUpdated: () -> Unit
) {
    val grading = layer.colorGrading

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Brightness Slider
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Brightness", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(String.format(Locale.US, "%.2f", grading.brightness), style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        }
        Slider(
            value = grading.brightness,
            onValueChange = {
                grading.brightness = it
                onUpdated()
            },
            valueRange = -1.0f..1.0f,
            colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
        )

        // Contrast Slider
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Contrast", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(String.format(Locale.US, "%.2f", grading.contrast), style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        }
        Slider(
            value = grading.contrast,
            onValueChange = {
                grading.contrast = it
                onUpdated()
            },
            valueRange = 0.0f..2.0f,
            colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
        )

        // Saturation Slider
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Saturation", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(String.format(Locale.US, "%.2f", grading.saturation), style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        }
        Slider(
            value = grading.saturation,
            onValueChange = {
                grading.saturation = it
                onUpdated()
            },
            valueRange = 0.0f..2.0f,
            colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
        )
    }
}
