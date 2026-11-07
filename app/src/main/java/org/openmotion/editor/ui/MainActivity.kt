package org.openmotion.editor.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openmotion.editor.engine.pipeline.CompositorEngine
import org.openmotion.editor.engine.pipeline.ExportEngine
import org.openmotion.editor.model.Keyframe
import org.openmotion.editor.model.Layer
import org.openmotion.editor.model.LayerType
import org.openmotion.editor.model.Project
import org.openmotion.editor.ui.preview.PreviewMonitor
import org.openmotion.editor.ui.properties.PropertiesPanel
import org.openmotion.editor.ui.theme.AccentPrimary
import org.openmotion.editor.ui.theme.BgPanel
import org.openmotion.editor.ui.theme.BgWorkspace
import org.openmotion.editor.ui.theme.OpenMotionTheme
import org.openmotion.editor.ui.theme.TextPrimary
import org.openmotion.editor.ui.theme.TextSecondary
import org.openmotion.editor.ui.timeline.TimelineView
import java.io.File
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import org.openmotion.editor.model.ProjectSerializer

class MainActivity : ComponentActivity() {

    private lateinit var compositorEngine: CompositorEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed multi-track sample composition
        val project = Project(
            title = "Alight Alternative Demo",
            width = 1080,
            height = 1920,
            frameRate = 60,
            durationMs = 12000L
        ).apply {
            layers.add(
                Layer(
                    name = "Background Video",
                    type = LayerType.VIDEO,
                    startTimeMs = 0L,
                    endTimeMs = 10000L,
                    zIndex = 0
                )
            )
            layers.add(
                Layer(
                    name = "Animated Shape",
                    type = LayerType.SHAPE,
                    startTimeMs = 1000L,
                    endTimeMs = 8000L,
                    zIndex = 1
                ).apply {
                    transform.posXKeyframes.add(Keyframe(1000L, -200f))
                    transform.posXKeyframes.add(Keyframe(4500L, 200f))
                }
            )
            layers.add(
                Layer(
                    name = "Title Text",
                    type = LayerType.TEXT,
                    startTimeMs = 500L,
                    endTimeMs = 6000L,
                    textContent = "OpenMotion 4K",
                    zIndex = 2
                ).apply {
                    transform.posY = -300f
                }
            )
        }

        compositorEngine = CompositorEngine(applicationContext, project)

        setContent {
            OpenMotionTheme {
                EditorScreen(
                    project = project,
                    engine = compositorEngine
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        compositorEngine.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    project: Project,
    engine: CompositorEngine
) {
    val context = LocalContext.current
    var currentPlayheadMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var selectedLayer by remember { mutableStateOf<Layer?>(project.layers.firstOrNull()) }
    val layerList = remember { mutableStateListOf<Layer>().apply { addAll(project.layers) } }

    // Undo / Redo History
    val undoStack = remember { mutableStateListOf<List<Layer>>() }
    val redoStack = remember { mutableStateListOf<List<Layer>>() }

    fun pushUndoState() {
        undoStack.add(project.layers.map { it.deepCopy() })
        redoStack.clear()
    }

    // Export State
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportEngineRef by remember { mutableStateOf<ExportEngine?>(null) }
    var showLoadDialog by remember { mutableStateOf(false) }
    val savedProjects = remember(showLoadDialog) { if (showLoadDialog) ProjectSerializer.listSaved(context) else emptyList() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { /* temporary grant is enough for this session */ }
            val mime = context.contentResolver.getType(uri)
            val type = if (mime?.startsWith("video/") == true) LayerType.VIDEO else LayerType.IMAGE
            pushUndoState()
            val newLayer = Layer(
                name = if (type == LayerType.VIDEO) "Video Clip" else "Image",
                type = type,
                mediaSourceUri = uri.toString(),
                startTimeMs = currentPlayheadMs,
                endTimeMs = (currentPlayheadMs + 5000L).coerceAtMost(project.durationMs),
                zIndex = project.layers.size
            )
            project.layers.add(newLayer)
            layerList.add(newLayer)
            selectedLayer = newLayer
            engine.seekTo(currentPlayheadMs)
        }
    }

    DisposableEffect(engine) {
        engine.onPlayheadUpdated = { time ->
            currentPlayheadMs = time
        }
        onDispose {
            engine.onPlayheadUpdated = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgWorkspace
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Navigation & Action Bar
            TopAppBar(
                title = {
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                redoStack.add(project.layers.map { it.deepCopy() })
                                val prevLayers = undoStack.removeAt(undoStack.lastIndex)
                                project.layers.clear()
                                project.layers.addAll(prevLayers)
                                layerList.clear()
                                layerList.addAll(prevLayers)
                                selectedLayer = project.layers.firstOrNull()
                                engine.seekTo(currentPlayheadMs)
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Undo,
                            contentDescription = "Undo",
                            tint = if (undoStack.isNotEmpty()) TextPrimary else TextSecondary.copy(alpha = 0.4f)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                undoStack.add(project.layers.map { it.deepCopy() })
                                val nextLayers = redoStack.removeAt(redoStack.lastIndex)
                                project.layers.clear()
                                project.layers.addAll(nextLayers)
                                layerList.clear()
                                layerList.addAll(nextLayers)
                                selectedLayer = project.layers.firstOrNull()
                                engine.seekTo(currentPlayheadMs)
                            }
                        },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Redo,
                            contentDescription = "Redo",
                            tint = if (redoStack.isNotEmpty()) TextPrimary else TextSecondary.copy(alpha = 0.4f)
                        )
                    }

                    IconButton(onClick = { importLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import media", tint = TextPrimary)
                    }
                    IconButton(onClick = {
                        val saved = ProjectSerializer.save(context, project)
                        Toast.makeText(context, "Saved: ${saved.name}", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save project", tint = TextPrimary)
                    }
                    IconButton(onClick = { showLoadDialog = true }) {
                        Icon(Icons.Default.Folder, contentDescription = "Load project", tint = TextPrimary)
                    }
                    IconButton(onClick = { /* Project Settings dialog */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                    }

                    Button(
                        onClick = {
                            val exportFile = File(context.cacheDir, "OpenMotion_${System.currentTimeMillis()}.mp4")
                            val exporter = ExportEngine(context, project)
                            exportEngineRef = exporter
                            isExporting = true
                            exportProgress = 0f

                            exporter.startExport(
                                outputFile = exportFile,
                                onProgress = { progress -> exportProgress = progress },
                                onComplete = { file ->
                                    isExporting = false
                                    Toast.makeText(context, "Exported to: ${file.name}", Toast.LENGTH_LONG).show()
                                },
                                onError = { error ->
                                    isExporting = false
                                    Toast.makeText(context, "Export error: ${error.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Export",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("Export", color = Color.Black, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPanel)
            )

            // Top Viewport: Aspect-Ratio Preserving Preview Monitor
            PreviewMonitor(
                engine = engine,
                selectedLayer = selectedLayer,
                aspectRatio = project.aspectRatio,
                onTransformChange = { dx, dy ->
                    selectedLayer?.let { layer ->
                        layer.transform.posX += dx
                        layer.transform.posY += dy
                        engine.seekTo(currentPlayheadMs)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f)
            )

            // Middle: Multi-Track Scrubbable Timeline
            TimelineView(
                project = project,
                currentPlayheadMs = currentPlayheadMs,
                selectedLayer = selectedLayer,
                isPlaying = isPlaying,
                onSeek = { targetMs ->
                    currentPlayheadMs = targetMs
                    engine.seekTo(targetMs)
                },
                onTogglePlayPause = {
                    isPlaying = !isPlaying
                    engine.togglePlayPause()
                },
                onSelectLayer = { layer ->
                    selectedLayer = layer
                },
                onAddKeyframe = {
                    selectedLayer?.let { layer ->
                        pushUndoState()
                        val evaluated = layer.transform.evaluateAt(currentPlayheadMs)
                        layer.transform.posXKeyframes.add(Keyframe(currentPlayheadMs, evaluated.posX))
                        layer.transform.posYKeyframes.add(Keyframe(currentPlayheadMs, evaluated.posY))
                        engine.seekTo(currentPlayheadMs)
                    }
                },
                onSplitLayer = {
                    selectedLayer?.let { layer ->
                        if (currentPlayheadMs in (layer.startTimeMs + 100)..(layer.endTimeMs - 100)) {
                            pushUndoState()
                            val newLayer = layer.deepCopy().copy(
                                id = java.util.UUID.randomUUID().toString(),
                                name = "${layer.name} (Split)",
                                startTimeMs = currentPlayheadMs,
                                endTimeMs = layer.endTimeMs
                            )
                            layer.endTimeMs = currentPlayheadMs
                            project.layers.add(newLayer)
                            layerList.add(newLayer)
                            selectedLayer = newLayer
                            engine.seekTo(currentPlayheadMs)
                        }
                    }
                },
                onDeleteLayer = {
                    selectedLayer?.let { layer ->
                        pushUndoState()
                        project.layers.remove(layer)
                        layerList.remove(layer)
                        selectedLayer = project.layers.firstOrNull()
                        engine.seekTo(currentPlayheadMs)
                    }
                },
                onAddLayer = {
                    pushUndoState()
                    val newLayer = Layer(
                        name = "Layer ${project.layers.size + 1}",
                        type = LayerType.SHAPE,
                        startTimeMs = currentPlayheadMs,
                        endTimeMs = (currentPlayheadMs + 5000L).coerceAtMost(project.durationMs),
                        zIndex = project.layers.size
                    )
                    project.layers.add(newLayer)
                    layerList.add(newLayer)
                    selectedLayer = newLayer
                    engine.seekTo(currentPlayheadMs)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f)
            )

            // Bottom: Properties Inspector (Move & Transform, Blending, Color)
            PropertiesPanel(
                layer = selectedLayer,
                currentPlayheadMs = currentPlayheadMs,
                onPropertyUpdated = {
                    engine.seekTo(currentPlayheadMs)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Exporting Progress Dialog Overlay
        if (isExporting) {
            AlertDialog(
                onDismissRequest = { /* Cannot dismiss without cancel */ },
                confirmButton = {
                    TextButton(onClick = {
                        exportEngineRef?.cancelExport()
                        isExporting = false
                    }) {
                        Text("Cancel", color = Color(0xFFFF5252))
                    }
                },
                title = { Text("Exporting Video", color = TextPrimary) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentPrimary
                        )
                        Text(
                            text = "Rendering frames: ${(exportProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                },
                containerColor = BgPanel
            )
        }
        if (showLoadDialog) {
            AlertDialog(
                onDismissRequest = { showLoadDialog = false },
                confirmButton = {
                    TextButton(onClick = { showLoadDialog = false }) { Text("Close", color = TextPrimary) }
                },
                title = { Text("Load Project", color = TextPrimary) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (savedProjects.isEmpty()) {
                            Text("No saved projects yet.", color = TextSecondary)
                        } else {
                            savedProjects.take(8).forEach { file ->
                                Text(
                                    file.nameWithoutExtension,
                                    color = AccentPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clickable {
                                            ProjectSerializer.load(context, file, project)
                                            layerList.clear()
                                            layerList.addAll(project.layers)
                                            selectedLayer = project.layers.firstOrNull()
                                            currentPlayheadMs = 0L
                                            engine.seekTo(0L)
                                            showLoadDialog = false
                                            Toast.makeText(context, "Loaded: ${file.name}", Toast.LENGTH_SHORT).show()
                                        }
                                )
                            }
                        }
                    }
                },
                containerColor = BgPanel
            )
        }
    }
}
