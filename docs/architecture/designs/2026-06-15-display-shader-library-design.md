# Design: Runtime Display Shader Library

**Date:** 2026-06-15
**Status:** Draft design for review
**Topic:** Add a user-facing display shader library rooted at `shaders/`, discovered at runtime and cycled with configurable keys.

## Problem

OpenGGF can render accurate game pixels, but it has no user-facing post-processing layer for emulator-style presentation effects. BizHawk ships a small set of useful display shaders under `docs/BizHawk-2.11-win-x64/Shaders/BizHawk`, including scanlines, gamma, bicubic filters, and `hq2x`. RetroArch shader distributions also include a large GLSL preset set under `shaders_glsl/`, with `.glslp` presets referencing one or more `.glsl` pass files. The current engine shader system is oriented around internal tile/sprite/fade rendering, not user-selectable final-frame effects.

Users need a way to drop compatible shader presets into a root `shaders/` folder, optionally download the RetroArch GLSL shader pack into that root from inside the application, have the engine discover compatible entries at runtime, and select shaders without forcing users to step through hundreds of presets one at a time.

## Goals

- Add a runtime-scanned shader library rooted at a configurable folder, defaulting to `shaders`.
- Support an explicit `Off` entry plus discovered shader entries.
- Let users cycle backward and forward through the library with configurable keys, defaulting to `[` and `]`.
- Persist the selected shader by stable relative path, not by fragile list index.
- Compile/load only the selected shader, so a broken shader does not prevent startup.
- Support BizHawk-style GLSL presets well enough for the local `BizHawk/*.cgp` files that have GLSL siblings.
- Support RetroArch GLSL presets (`.glslp` plus referenced `.glsl` pass files) well enough for ordinary single- and multipass `shaders_glsl` presets that are compatible with, or can be normalized into, the engine's OpenGL 4.1 forward-compatible core profile and do not require external textures, includes, history, or Slang.
- Support standalone GLSL post-process shaders that follow the engine compatibility contract.
- Provide an application-callable utility for downloading the libretro RetroArch GLSL shader pack from GitHub's zip archive into `shaders/libretro-glsl`, without requiring `git` or a RetroArch installation.
- Track the downloaded pack version with `shaders/libretro-glsl/.openggf-libretro-glsl.properties` so the application can cheaply check whether a newer pack is available.
- Provide a scalable selection path for large libraries, so `[`/`]` cycling remains a quick shortcut but not the only practical way to find a shader once `libretro-glsl` is installed.
- Keep shader effects display-only; they must not affect physics, traces, ROM palette state, or gameplay timing.
- Show a short on-screen confirmation when the active shader changes.

## Non-Goals

- No HLSL/Cg compilation in the first implementation. `.hlsl` and raw `.cg` files are discoverable only as unsupported companions unless a GLSL-compatible source is available.
- No Slang compilation or translation in this implementation. `.slangp` and `.slang` files are out of scope.
- No promise that every BizHawk/RetroArch shader pack will work unchanged.
- No RetroArch LUT textures, external image resources, `#include` expansion, feedback/history samplers, wildcard preset inheritance, previous-frame buffers, or full RetroArch runtime emulation in this pass.
- No in-engine parameter editing UI in this pass. `#pragma parameter` declarations may be tolerated, but the shader uses its built-in default constants unless a later feature exposes parameter uniforms.
- No marketplace integration or third-party shader redistribution from this repository. The app may download the upstream libretro GLSL pack at user request, but committed sample shader content remains out of scope unless licensing/attribution is reviewed.
- No shader hot-reload watcher in this pass. Runtime rescan can happen on startup and optionally when cycling if the library is dirty, but file watching is future work.
- No shader effects during headless trace replay or tests unless a test explicitly initializes GL presentation.

## User Workflow

Users place shaders in a root folder:

```text
shaders/
  BizHawk/
    BizScanlines.cgp
    BizScanlines.glsl
    bsnes-gamma.cgp
    bsnes-gamma.glsl
    hq2x.cgp
    hq2x.glsl
    bicubic-fast.cgp
    bicubic-fast.hlsl
    bicubic-normal.cgp
    bicubic-normal.hlsl
  Custom/
    warm-crt.glsl
  RetroArch/
    shaders_glsl/
      scanlines/
        scanline.glslp
        shaders/
          scanline.glsl
      crt/
        crt-easymode.glslp
        shaders/
          crt-easymode.glsl
  libretro-glsl/
    .openggf-libretro-glsl.properties
    crt/
      crt-easymode.glslp
      shaders/
        crt-easymode.glsl
    scanlines/
      scanline.glslp
      shaders/
        scanline.glsl
```

At startup, the engine scans `shaders/` recursively and builds a sorted list:

```text
Off
BizHawk/BizScanlines.cgp
BizHawk/bicubic-fast.cgp
BizHawk/bicubic-normal.cgp
BizHawk/bsnes-gamma.cgp
BizHawk/hq2x.cgp
Custom/warm-crt.glsl
RetroArch/shaders_glsl/crt/crt-easymode.glslp
RetroArch/shaders_glsl/scanlines/scanline.glslp
libretro-glsl/crt/crt-easymode.glslp
libretro-glsl/scanlines/scanline.glslp
```

Pressing `]` advances to the next entry. Pressing `[` moves to the previous entry. Selecting `Off` disables the post-process pipeline. The current selection is saved, and the screen shows a brief confirmation such as:

```text
Shader: BizHawk/BizScanlines
Shader: Off
Shader failed: Custom/warm-crt
```

Linear cycling is not sufficient once the libretro pack is installed. `[` and `]` remain quick previous/next shortcuts, but the user-facing selection model must also support a scalable picker path before the downloaded pack is treated as first-class UX. The picker can be a simple in-engine list or overlay in this implementation, but it must at least support root-relative text filtering and category-aware display using path segments such as `crt`, `scanlines`, `xbr`, and `scalehq`. A future favorites/recent list can build on the same library metadata, but the first implementation should avoid making users cycle through hundreds of entries to reach a known preset.

## Libretro GLSL Pack Download

The application should expose a user-triggered action to install or update the RetroArch GLSL shader pack without requiring RetroArch or `git` on the user's machine. In this implementation, the shader picker owns that action: open the picker with `BACKSLASH`, then press `F5` to install or update the libretro GLSL pack. The download utility itself should remain self-contained and UI-agnostic so it can also be called from a future menu, command palette, or startup prompt.

Download source:

```text
https://github.com/libretro/glsl-shaders/archive/refs/heads/master.zip
```

Install target:

```text
<DISPLAY_SHADER_LIBRARY_ROOT>/libretro-glsl/
```

Extraction rules:

- Create the configured shader root if needed.
- Download into a temporary `.part` file under the shader root or `libretro-glsl` staging area.
- Extract into a staging directory, not directly over the active install.
- Strip the zip's top-level folder, e.g. `glsl-shaders-master/`, so categories such as `crt/`, `scanlines/`, `xbr/`, and `scalehq/` sit directly under `libretro-glsl/`.
- Reject zip entries that escape the staging directory after normalization.
- Replace the existing `libretro-glsl` directory only after the download and extraction both succeed.
- Write update metadata to `libretro-glsl/.openggf-libretro-glsl.properties`.

Progress reporting:

- Expose a callback interface that reports stage, completed units, total units when known, and a short detail string.
- Stages should cover at least update check, download, extraction, and completion.
- Download progress should use response `Content-Length` when available, otherwise report an unknown total.
- Extraction progress may use entry count or uncompressed byte totals from the zip central directory.

Update check:

- Use a lightweight HTTP check before re-downloading when metadata exists.
- The simplest acceptable mechanism is `HEAD` or conditional request against the GitHub zip archive using the stored `ETag` / `Last-Modified`.
- A GitHub commit API check is also acceptable, but the utility must not require authentication.
- If the remote version matches the stored metadata, report "up to date" and do not download.
- If metadata is missing, unreadable, or the remote identity differs, treat the pack as installable/updateable.
- After a successful install/update, rescan the display shader library so the new `libretro-glsl` presets become selectable without requiring a restart.

Error handling:

- Network failures, non-2xx HTTP statuses, interrupted downloads, partial downloads, invalid archives, zip-slip paths, and disk/write failures must surface as checked, categorized exceptions.
- A failed download or extraction must leave the previous `libretro-glsl` install intact.
- The utility must not write outside the configured shader root and its `libretro-glsl` child.

## Configuration

Add display configuration keys:

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `DISPLAY_SHADER_LIBRARY_ROOT` | string | `shaders` | Root directory scanned for user display shaders |
| `DISPLAY_SHADER_SELECTION` | string | `OFF` | Last selected shader, stored as `OFF` or a root-relative path |
| `DISPLAY_SHADER_NEXT_KEY` | key | `RIGHT_BRACKET` | Runtime key for next shader |
| `DISPLAY_SHADER_PREVIOUS_KEY` | key | `LEFT_BRACKET` | Runtime key for previous shader |
| `DISPLAY_SHADER_PICKER_KEY` | key | `BACKSLASH` | Runtime key to open the searchable/category-aware shader picker |
| `DISPLAY_SHADER_DEFAULT_PHASE` | enum | `PRESENTATION` | Fallback render phase for standalone shaders |

`DISPLAY_SHADER_SELECTION` should be path-normalized with forward slashes for stable config files across Windows/macOS/Linux. If the saved selection is missing at startup, fall back to `Off` and log a warning.

Playback is not currently a user-facing default flow, so shader cycling owns `LEFT_BRACKET`/`RIGHT_BRACKET` by default. To prevent double firing and keep dormant playback tooling from consuming ordinary presentation keys, implementation must change all playback-only key defaults to unbound. Users who actively use playback can rebind those controls in config.

The existing YAML config emitter should place these under `display`:

```yaml
display:
  shaderLibraryRoot: shaders
  shaderSelection: OFF
  shaderNextKey: RIGHT_BRACKET
  shaderPreviousKey: LEFT_BRACKET
  shaderPickerKey: BACKSLASH
  shaderDefaultPhase: PRESENTATION
```

`ConfigCatalog` implementation requirements:

- Insert the new keys in the existing normal `display` section, before every `debug.*` key, so debug sections remain contiguous and last.
- Register `DISPLAY_SHADER_DEFAULT_PHASE` with `ofEnum(...)` and allowed values `SCENE`, `PRESENTATION`, and `FINAL`.
- Register `DISPLAY_SHADER_SELECTION` and `DISPLAY_SHADER_LIBRARY_ROOT` as free-form strings.
- Register `DISPLAY_SHADER_NEXT_KEY`, `DISPLAY_SHADER_PREVIOUS_KEY`, and `DISPLAY_SHADER_PICKER_KEY` as key values.

## Discovery Rules

`DisplayShaderLibrary` scans the configured root recursively. If the root is missing, the library contains only `Off`.

Selectable entries:

1. `.cgp` preset files.
2. `.glslp` preset files.
3. Standalone `.glsl` files that are not already paired with or referenced by a discovered preset.

Ignored as standalone entries:

- `.hlsl`
- `.cg`
- `.slangp`
- `.slang`
- `.txt`
- `.glsl` pass files referenced by any discovered `.cgp` or `.glslp` preset
- `.glsl` files under path segments named `shaders` or `resources`, because RetroArch uses those directories for implementation pass files rather than user-facing presets
- files in hidden directories
- files outside the configured root after path normalization

Sort order is stable, case-insensitive, and root-relative. `Off` is always index `0`.

The `shaders`/`resources` segment filter is evaluated only on the path relative to the configured shader root. The root directory itself defaults to a folder named `shaders`, and that root name must not cause every file to be filtered out.

The scan should produce metadata only. It must not compile every shader during startup. Compilation happens when an entry is selected. Discovery may perform a cheap text parse of discovered preset files to collect `shaderN` references for standalone `.glsl` exclusion, but it must not initialize GL or compile shader source. This means `.cgp` files that only have HLSL/Cg sources, such as the local bicubic presets, still appear in the library and fail gracefully when selected until a GLSL source is added. `.glslp` presets also remain metadata-only during scanning; unsupported features such as includes, external textures, Slang references, or history buffers are detected when the preset is loaded or compiled.

The root `shaders/` folder is user-supplied runtime content. The implementation should add `/shaders/` to `.gitignore` by default. Sample shaders must not be committed at the repo root unless their license, attribution, and redistribution terms have been reviewed and documented.

## Preset Loading

`DisplayShaderPresetLoader` accepts three sources:

### `.cgp`

Parse a focused subset first:

| Field | Behavior |
|-------|----------|
| `shaders` | Number of passes |
| `shaderN` | Source path for pass `N` |
| `scaleN` | Integer output scale relative to previous pass/source |
| `scale_typeN` | Supports `source` and `viewport` initially; missing value defaults to `source` |
| `filter_linearN` | Optional texture filtering override |

The local BizHawk `.cgp` files reference `.cg` names, e.g. `shader0 = BizScanlines.cg`, while GLSL siblings exist beside them. The loader should resolve in this order:

1. Exact referenced path if it is `.glsl`.
2. Same basename with `.glsl` beside the `.cgp`.
3. Unsupported, with a clear warning that HLSL/Cg is not compiled.

Missing `scale_typeN` must default to `source`, matching the Cg/RetroArch preset behavior used by `BizScanlines.cgp` and `hq2x.cgp` (`scale0 = 2`). Treating a missing `scale_typeN` as `viewport` would change scanline/scaler density and is not acceptable. `scale_typeN = viewport` is supported for presets such as the local bicubic `.cgp` files, although those specific files remain unsupported until GLSL sources exist. `absolute` scaling is out of scope for the first pass and should produce a clear unsupported-preset error rather than silently falling back.

### `.glslp`

Parse the same core preset subset as `.cgp`, but resolve `shaderN` paths exactly as RetroArch GLSL presets normally use them:

| Field | Behavior |
|-------|----------|
| `shaders` | Number of passes |
| `shaderN` | Relative `.glsl` source path for pass `N` |
| `scaleN` | Integer output scale relative to previous pass/source |
| `scale_typeN` | Supports `source` and `viewport`; missing value defaults to `source` |
| `filter_linearN` | Optional texture filtering override |
| `wrap_modeN` | Optional texture wrap hint; `clamp_to_border`, `clamp_to_edge`, and `repeat` may be mapped where available, unknown values warn and fall back to clamp-to-edge |

`.glslp` source resolution is relative to the preset file's directory. For example, `scanlines/scanline.glslp` with `shader0 = shaders/scanline.glsl` resolves to `scanlines/shaders/scanline.glsl`.

The loader should reject `.glslp` passes that reference `.slang`, `.cg`, `.hlsl`, missing files, external textures/LUTs, or preset inheritance. These failures must remain selection failures, not startup failures.

`#pragma parameter` lines are allowed in source files, but v1 does not expose user-editable parameter uniforms. The pipeline should not define `PARAMETER_UNIFORM`; RetroArch GLSL files that include fallback `#define` values will use those defaults. Shaders that require parameter uniforms without fallback values may fail selection.

### Standalone `.glsl`

Treat as a single-pass preset. It uses `DISPLAY_SHADER_DEFAULT_PHASE` unless the file later grows an engine-specific metadata comment.

Supported GLSL shapes:

- BizHawk combined files with `#ifdef VERTEX` and `#ifdef FRAGMENT` sections.
- RetroArch combined files with `#if defined(VERTEX)` and `#elif defined(FRAGMENT)` sections.
- Fragment-only files that sample `s_p`, `SceneTexture`, or RetroArch's `Texture`; these use the engine's fullscreen textured quad vertex shader.

The compiler wrapper must emit `#version 410 core` as the first directive in the generated stage source, then insert stage defines such as `#define VERTEX` and `#define FRAGMENT`. Existing lower `#version` directives are normalized to 410 rather than preserved, because the engine runs a forward-compatible core profile.

## Render Phases

The pipeline supports explicit insertion phases:

| Phase | Placement | Use |
|-------|-----------|-----|
| `SCENE` | After the engine's normal scene render flush, before fade | Pixel scalers such as `hq2x`; in the current render path this includes HUD pixels because HUD is drawn inside the scene path |
| `PRESENTATION` | After HUD/fade, before post-fade diagnostics/debug overlays | Default for scanlines, gamma, CRT effects |
| `FINAL` | After diagnostics/debug overlays, before screenshot/present | True monitor simulation, opt-in |

Default behavior should be `PRESENTATION`. This keeps trace/debug diagnostics readable while applying display effects to the normal game presentation. Presets can override this later through metadata. For the first pass, `.cgp` and `.glslp` presets use the configured default unless a hardcoded compatibility table marks a scaler preset as `SCENE`; examples include `hq2x` and RetroArch scaler category directories such as `scalenx`, `scalehq`, `xbr`, `xbrz`, `xsal`, and `xsoft`. Category matching should prefer the preset's root-relative directory segments over broad basename substring checks to avoid mis-tagging non-scalers whose names merely contain scaler terms.

## Rendering Architecture

Introduce:

- `DisplayShaderLibrary`
- `DisplayShaderPresetRef`
- `DisplayShaderPresetLoader`
- `DisplayShaderPreset`
- `DisplayShaderPass`
- `DisplayShaderPipeline`
- `DisplayShaderController`

`GraphicsManager` owns the pipeline and initializes it when GL is available. `Engine.display()` asks the pipeline to begin/end capture at the configured phase and composite the final texture back to the viewport.

The pipeline uses ping-pong FBOs:

```text
viewport render -> capture FBO -> pass 0 FBO -> pass 1 FBO -> default framebuffer
```

FBO textures should use `GL_NEAREST` by default to preserve pixel edges. `filter_linearN` may request `GL_LINEAR` per pass for bicubic-style shaders when needed. `wrap_modeN` from `.glslp` should map to GL texture wrap modes when the value is known and otherwise fall back to clamp-to-edge with a warning.

Reuse the existing `FboHelper` and `QuadRenderer` patterns instead of duplicating FBO lifecycle or full-screen draw setup. The pipeline may need a new textured-quad renderer for BizHawk combined vertex/fragment shaders, but FBO creation, cleanup, and viewport save/restore should stay on the shared helper path.

The pipeline should resize FBOs when the viewport changes. FBO dimensions are derived from:

- source logical size: `SCREEN_WIDTH_PIXELS x SCREEN_HEIGHT_PIXELS`
- current viewport size: `viewportWidth x viewportHeight`
- preset `scaleN` and `scale_typeN`

The pipeline also receives a display-only frame counter each time it applies a shader. This counter is used for RetroArch `FrameCount`; it must be sourced from presentation frames, not gameplay simulation or rewind state, so shader animation cannot perturb timing, traces, or deterministic replay.

## Shader Compatibility Contract

The engine should provide BizHawk-style and RetroArch GLSL aliases so compatible shaders need minimal edits. These names define the compatibility contract that shaders may declare and the engine will populate by uniform/attribute location when present. The engine must not inject uniform declarations into user shaders, because RetroArch and BizHawk shaders normally declare these uniforms themselves and duplicate declarations would fail compilation. The only source injected by the compatibility shim is stage defines, bounded legacy macro/output support, and other declarations that are known not to duplicate shader-owned uniforms.

```glsl
uniform sampler2D s_p;
uniform sampler2D SceneTexture;
uniform sampler2D Texture;

uniform mat4 MVPMatrix;

uniform struct {
    vec2 video_size;
    vec2 texture_size;
    vec2 output_size;
} IN;

uniform vec2 InputSize;
uniform vec2 TextureSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform int FrameDirection;
```

For combined BizHawk and RetroArch GLSL, the post-process quad must provide:

```glsl
in vec4 VertexCoord;
in vec4 TexCoord; // shaders that declare vec2 TexCoord are also accepted by name
in vec4 COLOR;
```

This is required for shaders like `hq2x.glsl`, which compute neighboring sample coordinates in the vertex stage. The existing `shader_fullscreen.vert` that relies only on `gl_VertexID` is still useful for fragment-only engine shaders, but BizHawk-compatible combined shaders need a textured quad vertex format with attributes.

Uniform mapping:

| Uniform | Value |
|---------|-------|
| `s_p` | previous pass texture |
| `SceneTexture` | same texture as `s_p` |
| `Texture` | same texture as `s_p` |
| `MVPMatrix` | identity/fullscreen transform unless a shader requires otherwise |
| `IN.video_size` | logical source game size |
| `IN.texture_size` | previous pass texture size |
| `IN.output_size` | current pass output size |
| `InputSize` | logical source game size for the first pass; previous pass output size for later passes, i.e. the dimensions of this pass's input texture |
| `TextureSize` | previous pass texture size |
| `OutputSize` | current pass output size |
| `FrameCount` | monotonically increasing presentation frame counter |
| `FrameDirection` | `1` |

Attribute mapping:

| Attribute | Value |
|-----------|-------|
| `VertexCoord` | fullscreen quad position |
| `TexCoord` | fullscreen quad UV, supplied as a `vec4`-compatible attribute so RetroArch declarations can read `.xy` |
| `COLOR` | constant white vertex color |

The RetroArch compatibility layer deliberately does not provide `OriginalHistory`, `Prev`, named lookup textures, or feedback buffers in this pass.

The engine runs OpenGL 4.1 core profile with forward compatibility enabled, so the shader wrapper cannot blindly preserve legacy GLSL versions or removed symbols. Before compiling user GLSL, normalize each generated stage source through a `RetroArchGlslCompat`-style step:

1. Parse the first `#version` directive if present.
2. Emit `#version 410 core` for source with no version or with a version less than or equal to 410.
3. Reject source that explicitly requires a version greater than 410 with a clear unsupported-version error.
4. Insert the stage define (`VERTEX` or `FRAGMENT`) immediately after the emitted `#version`.
5. Preserve RetroArch's `COMPAT_*` macro headers; they should select the modern `in`/`out`/`texture` path under GLSL 410.
6. For legacy-only GLSL that uses removed symbols directly, inject a small stage-aware compatibility prelude when needed: `texture2D` maps to `texture`; vertex-stage `attribute` maps to `in`; vertex-stage `varying` maps to `out`; fragment-stage `varying` maps to `in`; and fragment-stage `gl_FragColor` maps to a declared `out vec4 FragColor` when the source does not already declare its own fragment output.

This compatibility shim is intentionally bounded. If the source relies on fixed-function state, `gl_FragData`, external samplers, includes, or other RetroArch runtime features outside the table above, selection fails gracefully instead of silently rendering incorrectly.

## Error Handling

Shader errors must be recoverable:

- Missing root folder: library is `Off` only.
- Missing saved selection: warn, select `Off`.
- Unsupported preset source: keep entry in the list but fail selection with a notification.
- Unsupported preset features, such as `.glslp` includes, external textures, history buffers, or Slang references: fail selection with a notification.
- Compile/link failure: log shader info log, select `Off`, and continue rendering normally.
- Runtime FBO failure: disable pipeline and continue rendering normally.

Cycling should skip entries that are known unsupported in the current process only after they fail once. A later rescan/startup can try them again.

## Notifications

Follow the `DisplayColorProfileController` pattern for notification state: a short-lived `notificationText` with a frame countdown, rendered from `Engine` through the existing pixel-font path. Shader and color-profile notifications must not occupy the exact same slot without stacking. If both are visible, stack them vertically or route them through a tiny shared display-notification renderer.

## Screenshots and Capture

F12 screenshots currently happen after normal rendering. By default, screenshots should capture the display shader output because it is what the user sees. Visual-regression/image-comparison tests remain stable because the default selection is `Off`.

Trace/video capture should keep its current parity behavior unless the capture mode explicitly asks for presentation shaders. This avoids accidentally changing comparison-oriented trace artifacts.

## Testing

Add JUnit 5 coverage for non-GL logic:

- Library scan returns `Off` plus sorted `.cgp`, `.glslp`, and unpaired/unreferenced `.glsl` entries.
- Hidden/outside-root files are ignored.
- RetroArch implementation pass files under `shaders/` or `resources/` are not surfaced as standalone cycle entries.
- `.cgp` parser reads `shaders`, `shaderN`, `scaleN`, `scale_typeN`, and `filter_linearN`.
- `.glslp` parser reads `shaders`, `shaderN`, `scaleN`, `scale_typeN`, `filter_linearN`, and known `wrap_modeN` values.
- Missing `scale_typeN` defaults to `source`, while explicit `viewport` is honored.
- `.cgp` source resolution maps `BizScanlines.cg` to sibling `BizScanlines.glsl`.
- `.cgp` presets without a GLSL source remain selectable metadata entries but fail selection gracefully.
- `.glslp` source resolution maps `shader0 = shaders/scanline.glsl` relative to the preset directory.
- `.glslp` presets that reference Slang, missing files, includes, external textures, or history fail gracefully when selected.
- Shape detection recognizes both BizHawk `#ifdef VERTEX` / `#ifdef FRAGMENT` and RetroArch `#if defined(VERTEX)` / `#elif defined(FRAGMENT)`.
- GLSL normalization emits `#version 410 core`, upgrades low existing versions, rejects versions above 410, inserts stage defines after the version line, and injects the bounded legacy compatibility prelude only when needed.
- Saved selection resolves by relative path and falls back to `Off` when missing.
- Controller cycles forward/backward, wraps around, persists selection, and shows notification text.
- Controller ignores unbound previous/next keys.
- Selection picker/filter logic can locate entries by root-relative text and category path segment, so large downloaded libraries do not depend only on linear cycling.
- All playback-only key defaults are unbound so shader cycling does not double-fire with dormant playback tooling.
- `DISPLAY_SHADER_DEFAULT_PHASE` is catalogued as an enum with the exact allowed values `SCENE`, `PRESENTATION`, and `FINAL`.
- `display.*` shader keys emit before the debug block.
- Shader compatibility metadata computes `video_size`, `texture_size`, and `output_size`.
- Shader compatibility metadata also computes RetroArch `InputSize`, `TextureSize`, `OutputSize`, `FrameCount`, and `FrameDirection`.
- Libretro downloader builds the install path as `<shaderRoot>/libretro-glsl`, strips the archive's top-level directory, writes `.openggf-libretro-glsl.properties`, reports download/extract progress, skips download when stored ETag/Last-Modified is current, and preserves an existing install on failure.
- Libretro downloader rejects zip-slip entries and reports HTTP, network, interrupted, partial-download, invalid-archive, and disk/write failures with categorized checked exceptions.

Add focused GL smoke coverage where practical:

- Compile one fragment-only test shader.
- Compile one combined BizHawk-style test shader with `VERTEX`/`FRAGMENT` sections.
- Compile one combined RetroArch-style GLSL test shader with `#if defined(VERTEX)` / `#elif defined(FRAGMENT)` sections, `Texture`, `TextureSize`, `InputSize`, and `OutputSize`.
- Compile one low-version or legacy-symbol RetroArch-style test shader after normalization to prove the forward-compatible core context path is exercised.
- Verify a selected bad shader does not abort startup and leaves rendering disabled.

These GL smoke tests cannot use the standard headless harness. They should create an explicit GLFW/OpenGL 4.1 core, forward-compatible context matching `Engine.init()` and skip when unavailable, or remain as manual/dev-only checks until the project has a reliable GL test fixture. `.glslp` tests should synthesize fixtures in `@TempDir`; the in-repo shader resources are engine shaders, and local RetroArch sample content may not include GLSL presets.

Render-order tests should assert the pipeline is inserted before or after fade/diagnostics according to phase, similar to the existing post-fade diagnostic guard.

## Documentation

Update `CONFIGURATION.md` with:

- `display.shaderLibraryRoot`
- `display.shaderSelection`
- `display.shaderNextKey`
- `display.shaderPreviousKey`
- `display.shaderDefaultPhase`
- The default `shaders/` directory layout.
- The GLSL compatibility contract.
- The HLSL/Cg and Slang limitations.
- The supported RetroArch GLSL subset: `.glslp` presets, no external textures/LUTs, no includes, no history/feedback buffers, no parameter UI.
- How to install/update the upstream libretro GLSL pack into `shaders/libretro-glsl`, including the fact that the app downloads GitHub's zip archive and strips the top-level folder.
- The scalable selection model for large packs: `[`/`]` remain shortcuts, while search/filter/category selection is the practical path for the full libretro library.
- The fact that root-level `shaders/` is user-supplied and gitignored unless sample shader licensing is explicitly handled.
- The recovery behavior for broken shaders.

Add a short note that repository-internal shaders under `src/main/resources/shaders` are engine shaders, while root-level `shaders/` is the user display shader library.

Because this change makes all playback-only key defaults unbound, update the existing checked-in config artifacts so the committed reference does not contradict the new code defaults:

- `src/main/resources/config.yaml` — set the `debug.playback.*Key` entries (`toggleKey`, `loadKey`, `playPauseKey`, `stepBackKey`, `stepForwardKey`, `jumpBackKey`, `jumpForwardKey`, `fastRateKey`, `resetToStartKey`) to the unbound representation, and add the new `display.shader*` keys.
- `CONFIGURATION.md` — the embedded sample `config.yaml` repeats the same playback key defaults; update those entries to match, and note that playback controls are unbound by default and must be rebound to use playback.

## Implementation Notes

- Use root-relative paths in config and notifications.
- Do not copy shader source into `src/main/resources`; user shader files are external runtime content.
- Keep the first implementation conservative: `.cgp`, `.glslp`, and GLSL, single/multipass FBO chain, no file watcher, no Slang runtime.
- Avoid branching shader behavior on game module; this is a display layer and should be game-agnostic.
