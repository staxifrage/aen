package org.openmotion.editor.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dependency-free JSON project serialization (uses Android's built-in org.json).
 * Projects are stored as JSON files under the filesDir/projects directory
 * so they survive app restarts.
 */
object ProjectSerializer {

    private const val DIR_NAME = "openmotion_projects"
    const val LATEST_FILE = "latest.json"

    fun projectsDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun listSaved(context: Context): List<File> =
        projectsDir(context).listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Saves into a timestamped file and also overwrites latest.json for quick re-open. */
    fun save(context: Context, project: Project): File {
        val dir = projectsDir(context)
        val json = toJson(project).toString(2)
        val safeTitle = project.title.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        val target = File(dir, "${safeTitle}_${System.currentTimeMillis()}.json")
        target.writeText(json)
        File(dir, LATEST_FILE).writeText(json)
        return target
    }

    fun load(context: Context, file: File, into: Project) {
        val loaded = fromJson(JSONObject(file.readText()))
        into.title = loaded.title
        into.width = loaded.width
        into.height = loaded.height
        into.frameRate = loaded.frameRate
        into.durationMs = loaded.durationMs
        into.layers.clear()
        into.layers.addAll(loaded.layers)
    }

    fun toJson(project: Project): JSONObject = JSONObject().apply {
        put("id", project.id)
        put("title", project.title)
        put("width", project.width)
        put("height", project.height)
        put("frameRate", project.frameRate)
        put("durationMs", project.durationMs)
        put("layers", JSONArray(project.layers.map { layerToJson(it) }))
    }

    fun fromJson(root: JSONObject): Project {
        val project = Project(
            id = root.optString("id"),
            title = root.optString("title", "Untitled Project"),
            width = root.optInt("width", 1080),
            height = root.optInt("height", 1920),
            frameRate = root.optInt("frameRate", 30),
            durationMs = root.optLong("durationMs", 10000L)
        )
        val layersArr = root.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until layersArr.length()) {
            project.layers.add(layerFromJson(layersArr.getJSONObject(i)))
        }
        return project
    }

    private fun layerToJson(layer: Layer): JSONObject = JSONObject().apply {
        put("id", layer.id)
        put("name", layer.name)
        put("type", layer.type.name)
        put("startTimeMs", layer.startTimeMs)
        put("endTimeMs", layer.endTimeMs)
        put("mediaOffsetMs", layer.mediaOffsetMs)
        put("mediaSourceUri", layer.mediaSourceUri ?: JSONObject.NULL)
        put("zIndex", layer.zIndex)
        put("isVisible", layer.isVisible)
        put("isLocked", layer.isLocked)
        put("blendMode", layer.blendMode.name)
        put("shapeType", layer.shapeType)
        put("textContent", layer.textContent)
        put("transform", transformToJson(layer.transform))
        put("colorGrading", JSONObject().apply {
            put("brightness", layer.colorGrading.brightness)
            put("contrast", layer.colorGrading.contrast)
            put("saturation", layer.colorGrading.saturation)
            put("tintColorRgb", layer.colorGrading.tintColorRgb)
        })
        put("effects", JSONArray(layer.effects.map { effectToJson(it) }))
    }

    private fun layerFromJson(o: JSONObject): Layer {
        val layer = Layer(
            id = o.optString("id"),
            name = o.optString("name", "Layer"),
            type = runCatching { LayerType.valueOf(o.optString("type")) }.getOrDefault(LayerType.SHAPE),
            startTimeMs = o.optLong("startTimeMs", 0L),
            endTimeMs = o.optLong("endTimeMs", 5000L),
            mediaOffsetMs = o.optLong("mediaOffsetMs", 0L),
            mediaSourceUri = if (o.isNull("mediaSourceUri")) null else o.optString("mediaSourceUri"),
            zIndex = o.optInt("zIndex", 0),
            isVisible = o.optBoolean("isVisible", true),
            isLocked = o.optBoolean("isLocked", false),
            blendMode = runCatching { BlendMode.valueOf(o.optString("blendMode")) }.getOrDefault(BlendMode.NORMAL),
            shapeType = o.optString("shapeType", "RECTANGLE"),
            textContent = o.optString("textContent", "")
        )
        o.optJSONObject("transform")?.let { t ->
            val tr = layer.transform
            tr.posX = (t.optDouble("posX")).toFloat()
            tr.posY = (t.optDouble("posY")).toFloat()
            tr.scaleX = (t.optDouble("scaleX", 1.0)).toFloat()
            tr.scaleY = (t.optDouble("scaleY", 1.0)).toFloat()
            tr.rotationDegrees = (t.optDouble("rotationDegrees")).toFloat()
            tr.anchorX = (t.optDouble("anchorX", 0.5)).toFloat()
            tr.anchorY = (t.optDouble("anchorY", 0.5)).toFloat()
            tr.opacity = (t.optDouble("opacity", 1.0)).toFloat()
            tr.posXKeyframes.addAll(keyframesFromJson(t.optJSONArray("posXKeyframes")))
            tr.posYKeyframes.addAll(keyframesFromJson(t.optJSONArray("posYKeyframes")))
            tr.scaleKeyframes.addAll(keyframesFromJson(t.optJSONArray("scaleKeyframes")))
            tr.rotationKeyframes.addAll(keyframesFromJson(t.optJSONArray("rotationKeyframes")))
            tr.opacityKeyframes.addAll(keyframesFromJson(t.optJSONArray("opacityKeyframes")))
        }
        o.optJSONObject("colorGrading")?.let { c ->
            layer.colorGrading.brightness = (c.optDouble("brightness")).toFloat()
            layer.colorGrading.contrast = (c.optDouble("contrast", 1.0)).toFloat()
            layer.colorGrading.saturation = (c.optDouble("saturation", 1.0)).toFloat()
            layer.colorGrading.tintColorRgb = c.optInt("tintColorRgb", 0xFFFFFF)
        }
        val effectsArr = o.optJSONArray("effects") ?: JSONArray()
        for (i in 0 until effectsArr.length()) {
            layer.effects.add(effectFromJson(effectsArr.getJSONObject(i)))
        }
        return layer
    }

    private fun transformToJson(t: Transform): JSONObject = JSONObject().apply {
        put("posX", t.posX); put("posY", t.posY)
        put("scaleX", t.scaleX); put("scaleY", t.scaleY)
        put("rotationDegrees", t.rotationDegrees)
        put("anchorX", t.anchorX); put("anchorY", t.anchorY)
        put("opacity", t.opacity)
        put("posXKeyframes", keyframesToJson(t.posXKeyframes))
        put("posYKeyframes", keyframesToJson(t.posYKeyframes))
        put("scaleKeyframes", keyframesToJson(t.scaleKeyframes))
        put("rotationKeyframes", keyframesToJson(t.rotationKeyframes))
        put("opacityKeyframes", keyframesToJson(t.opacityKeyframes))
    }

    private fun keyframesToJson(list: List<Keyframe>): JSONArray =
        JSONArray(list.map { JSONObject().apply {
            put("timeMs", it.timeMs)
            put("value", it.value)
            put("easing", JSONObject().apply {
                put("p1x", it.easing.p1x); put("p1y", it.easing.p1y)
                put("p2x", it.easing.p2x); put("p2y", it.easing.p2y)
            })
        } })

    private fun keyframesFromJson(arr: JSONArray?): List<Keyframe> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Keyframe>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val e = o.optJSONObject("easing")
            val easing = if (e != null) EasingCurve(
                (e.optDouble("p1x", 0.25)).toFloat(), (e.optDouble("p1y", 0.1)).toFloat(),
                (e.optDouble("p2x", 0.25)).toFloat(), (e.optDouble("p2y", 1.0)).toFloat()
            ) else EasingCurve.EASE_IN_OUT
            out.add(Keyframe(o.optLong("timeMs"), (o.optDouble("value")).toFloat(), easing))
        }
        return out
    }

    private fun effectToJson(e: Effect): JSONObject = JSONObject().apply {
        put("type", e.type.name)
        put("enabled", e.enabled)
        put("intensity", e.intensity)
        put("param1", e.param1)
        put("param2", e.param2)
        put("param3", e.param3)
    }

    private fun effectFromJson(o: JSONObject): Effect = Effect(
        type = runCatching { EffectType.valueOf(o.optString("type")) }.getOrDefault(EffectType.NONE),
        enabled = o.optBoolean("enabled", true),
        intensity = (o.optDouble("intensity", 0.5)).toFloat(),
        param1 = (o.optDouble("param1")).toFloat(),
        param2 = (o.optDouble("param2")).toFloat(),
        param3 = o.optInt("param3", 0x00FF00)
    )
}
