# OpenMotion Editor (aen)

Open-source, hardware-accelerated motion / video editor for Android. Think of it as a
community-driven alternative to proprietary motion-design apps — built from the ground up
around a zero-copy OpenGL ES compositor, MediaCodec hardware decode/encode, and a
keyframed layer model.

> Status: **early technical preview**. The render pipeline, keyframe engine, timeline UI,
> and export path work; audio mixing and the full effects library are in progress.

## Tech Stack

| Component | Choice | Notes |
|---|---|---|
| UI | Jetpack Compose (Material 3) | Timeline, properties inspector, preview overlay |
| Main rendering | OpenGL ES 3.0 | EGL14 context on a dedicated GL thread; FBO-free back-to-front compositing |
| Compatibility renderer | OpenGL ES 3.0+ | Single renderer; `uses-feature glEsVersion=0x00030000` required |
| Shader language | GLSL ES 3.00 | Per-layer color grading + 2-slot effect stack (blur, chroma key, glow, …) |
| Video decoding | MediaCodec → SurfaceTexture (OES texture) | Zero-copy, per-layer decoders |
| Video encoding | MediaCodec (input Surface) + MediaMuxer | Offscreen EGL surface, H.264 |
| Playback | Custom compositor clock (Media3 included for future audio) | `media3-exoplayer`/`transformer`/`effect` deps present |
| Performance-critical logic | Kotlin on GL/export handler threads | NDK/C++ reserved for future (e.g. native image decode) |
| Project engine | Kotlin data model | `Project` → `Layer` → `Transform`/`ColorGrading`/`Effect` + cubic-bezier keyframes |

### On Vulkan / SPIR-V
A Vulkan backend is feasible (API 24+ devices are Vulkan-capable, and AGP ships the required
NDK tooling), but it is **not** the right first step. The current bottleneck is not draw
throughput — it is decoder scheduling, effect coverage, and audio. A Vulkan port would add
~10k lines of render-pass/descriptor-set boilerplate for a marginal preview-frame gain.
Keep GLES 3 as the renderer; add a C++/NDK module first (native image decode, fast cubic
solver) and revisit Vulkan once the compositor is FBO/ping-pong based for multi-pass effects.

## Features

- **Multi-track timeline** — video / image / shape / text / audio layers, z-ordered compositing
- **Keyframed animation** — position, scale, rotation, opacity with cubic-bezier easing (linear, ease in/out)
- **Layer effects** — Gaussian blur, chroma key, vignette, exposure, soft glow, RGB split, camera shake
- **Color grading** — brightness, contrast, saturation, tint per layer
- **Blend modes** — normal, add, multiply, screen (plus 8 more in the picker, shader-based implementation pending)
- **Hardware export** — offscreen render to MP4 (H.264) with progress + cancel
- **Project save/load** — JSON projects persisted to app storage
- **Media import** — system photo picker for video clips and images
- **Undo / redo** — full layer-stack history (deep-copied, so split layers are independent)

## Build

Requires JDK 17 + Android SDK (compileSdk 35, minSdk 24).

```bash
./gradlew assembleDebug    # APK at app/build/outputs/apk/debug/
./gradlew assembleRelease  # signed with debug key (CI-friendly); swap in your keystore for release
./gradlew testDebugUnitTest
```

CI (`.github/workflows/android-build.yml`) runs lint, unit tests, and builds both APKs on
every push/PR and attaches them as artifacts.

## Architecture

```
ui/            Compose: MainActivity, TimelineView, PreviewMonitor (SurfaceView + overlay), PropertiesPanel
engine/
  decoder/     TrackMediaDecoder — MediaCodec → SurfaceTexture → OES texture
  gl/          EglCore, LayerRenderer (OES / 2D / color programs), GlShader, Image/Text texture helpers
  pipeline/    CompositorEngine (preview GL thread), ExportEngine (offscreen encode), FrameQueueManager
model/         Project, Layer, Transform, Keyframe, EasingCurve, BlendMode, Effect, ProjectSerializer
```

Render loop: `CompositorEngine` posts at ~60 fps on its own GL thread; for each frame it
evaluates keyframes → applies shake → draws active layers bottom-to-top through
`LayerRenderer`, which uploads transform/grading/effect uniforms and blends with the
selected blend equation. Export uses the same draw logic on an encoder input surface.

## Roadmap

- [ ] Audio track mixing (Media3) and muxing into export
- [ ] Shader-based blend modes (all 12) via offscreen FBO ping-pong
- [ ] More effects: distortion, liquify, particle emitters, masking
- [ ] Vector shape drawing (paths, gradients) instead of solid quads
- [ ] NDK module for native image decode + keyframe solver
- [ ] Project format versioning + cloud sync hooks

## License

MPL-2.0 (inherited from upstream). Contributions welcome.
