# VHS Picture-Search Rewind Shader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render an authentic VCR picture-search effect (scrolling tear bands, scanline jitter, chroma bleed, tape dropouts, head-switch strip, vertical wobble) over the frame while live held-key rewind is active.

**Architecture:** A second, engine-owned `DisplayShaderPipeline` instance runs a built-in single-pass VHS fragment shader, applied in `Engine.display()` after the fade pass and before the user's PRESENTATION-phase display shader. A `RewindEffectEnvelope` in `LiveRewindManager` supplies a 4-frame-attack / 10-frame-release intensity plus a latched tape speed. The pipeline is prewarmed at GL init (no first-rewind compile hitch) only when `LIVE_REWIND_ENABLED && LIVE_REWIND_VHS_EFFECT`.

**Tech Stack:** Java 21, LWJGL/OpenGL 4.1 core, RetroArch-compat GLSL (legacy-style fragment via `RetroArchGlslCompat`), JUnit 5, Maven.

**Spec:** `docs/architecture/designs/2026-07-02-vhs-rewind-shader-design.md` — read it before starting.

## Global Constraints

- Branch: `feature/ai-vhs-rewind-shader` off `develop` (repo branch-naming policy `feature/ai-*`).
- JUnit 5 / Jupiter ONLY. No JUnit 4 imports, rules, or runners.
- Every commit needs the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`), each `updated` or `n/a`. A `feat:` commit touching `src/main/` must either stage `CHANGELOG.md` with `Changelog: updated` or justify with `Changelog: n/a: <reason>`. This plan updates `CHANGELOG.md` in the final task; intermediate `feat:` commits use `Changelog: n/a: incremental step; changelog entry lands with final Engine wiring commit on this branch`.
- If hooks are not installed (fresh worktree without a Maven run), run once: `git config core.hooksPath .githooks`.
- PowerShell: quote Maven `-D` properties, e.g. `mvn "-Dtest=com.openggf.game.rewind.TestRewindEffectEnvelope" test`.
- GL smoke tests use `Assumptions` and skip automatically on headless machines; locally with a GPU they must PASS, not skip.
- Source files end with a newline. Keep logic out of `Engine.java` where a manager/pass class can own it.

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/com/openggf/game/rewind/RewindEffectEnvelope.java` | Create | Attack/release intensity + latched-speed state machine (no GL, no deps) |
| `src/test/java/com/openggf/game/rewind/TestRewindEffectEnvelope.java` | Create | Envelope unit tests |
| `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPipeline.java` | Modify | Add per-frame dynamic-uniform `apply` overload |
| `src/main/resources/shaders/shader_vhs_rewind.glsl` | Create | The VHS picture-search fragment shader |
| `src/main/java/com/openggf/graphics/shaderlib/RewindVhsEffectPass.java` | Create | Owns the private pipeline; prewarm, per-frame apply, failure latch |
| `src/test/java/com/openggf/graphics/shaderlib/TestRewindVhsEffectPass.java` | Create | No-GL preset/resource sanity tests |
| `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java` | Modify | GL smoke tests: dynamic-uniform overload + VHS preset activate/apply |
| `src/main/java/com/openggf/configuration/SonicConfiguration.java` | Modify | `LIVE_REWIND_VHS_EFFECT` enum key |
| `src/main/java/com/openggf/configuration/ConfigCatalog.java` | Modify | `rewind.vhsEffect` catalog entry |
| `src/main/java/com/openggf/configuration/SonicConfigurationService.java` | Modify | Default `true` |
| `CONFIGURATION.md` | Modify | Document the new key (table row + YAML sample) |
| `src/main/java/com/openggf/game/rewind/LiveRewindManager.java` | Modify | Own/tick the envelope; expose `effectIntensity()`/`effectSpeed()` |
| `src/main/java/com/openggf/GameLoop.java` | Modify | Surface intensity/speed to Engine |
| `src/main/java/com/openggf/Engine.java` | Modify | Prewarm at GL init; apply hook in `display()` |
| `CHANGELOG.md` | Modify | Unreleased entry (final task) |

---

### Task 1: RewindEffectEnvelope

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/RewindEffectEnvelope.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindEffectEnvelope.java`

**Interfaces:**
- Consumes: nothing (pure state machine).
- Produces: `RewindEffectEnvelope` with `void frameActive(double currentSpeed)`, `void frameInactive()`, `void reset()`, `float intensity()` (0..1), `float speed()` (0.25..4.0, default 1.0). Task 5 calls these from `LiveRewindManager`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewindEffectEnvelope {

    @Test
    public void attackReachesFullIntensityInFourFrames() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        for (int i = 0; i < 3; i++) {
            envelope.frameActive(1.0);
            assertTrue(envelope.intensity() < 1.0f, "should still be ramping at frame " + i);
        }
        envelope.frameActive(1.0);
        assertEquals(1.0f, envelope.intensity(), 1e-6f);
        envelope.frameActive(1.0);
        assertEquals(1.0f, envelope.intensity(), 1e-6f, "must clamp at 1.0");
    }

    @Test
    public void releaseReachesZeroInTenFrames() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        for (int i = 0; i < 4; i++) {
            envelope.frameActive(1.0);
        }
        for (int i = 0; i < 9; i++) {
            envelope.frameInactive();
            assertTrue(envelope.intensity() > 0.0f, "should still be releasing at frame " + i);
        }
        envelope.frameInactive();
        assertEquals(0.0f, envelope.intensity(), 1e-6f);
        envelope.frameInactive();
        assertEquals(0.0f, envelope.intensity(), 1e-6f, "must clamp at 0.0");
    }

    @Test
    public void defaultSpeedIsOne() {
        assertEquals(1.0f, new RewindEffectEnvelope().speed(), 1e-6f);
    }

    @Test
    public void latchedSpeedHeldThroughRelease() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(2.5);
        for (int i = 0; i < 5; i++) {
            envelope.frameInactive();
        }
        assertEquals(2.5f, envelope.speed(), 1e-6f,
                "release tail must keep tape motion at the last held speed");
    }

    @Test
    public void zeroSpeedDuringActiveFrameDoesNotClearLatch() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(2.0);
        envelope.frameActive(0.0);
        assertEquals(2.0f, envelope.speed(), 1e-6f);
    }

    @Test
    public void speedIsClampedToTapeRange() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(9.0);
        assertEquals(4.0f, envelope.speed(), 1e-6f);
        envelope.frameActive(0.01);
        assertEquals(0.25f, envelope.speed(), 1e-6f);
    }

    @Test
    public void resetZeroesIntensityAndRestoresDefaultSpeed() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(3.0);
        envelope.frameActive(3.0);
        envelope.reset();
        assertEquals(0.0f, envelope.intensity(), 1e-6f);
        assertEquals(1.0f, envelope.speed(), 1e-6f);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.rewind.TestRewindEffectEnvelope" test`
Expected: COMPILE ERROR — `RewindEffectEnvelope` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.openggf.game.rewind;

/**
 * Intensity envelope for the VHS picture-search rewind presentation.
 * <p>
 * Attack is fast (a VCR degrades the picture almost immediately when the head
 * speed changes); release is slower and covers the coast-after-release window.
 * The tape speed is latched from the last active frame so the release tail
 * keeps its scroll motion even though {@code RewindSpeedController} resets to
 * zero on the first non-held frame when tape coast is disabled.
 */
public final class RewindEffectEnvelope {

    private static final float ATTACK_PER_FRAME = 1.0f / 4.0f;
    private static final float RELEASE_PER_FRAME = 1.0f / 10.0f;
    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 4.0f;
    private static final float DEFAULT_SPEED = 1.0f;

    private float intensity;
    private float latchedSpeed = DEFAULT_SPEED;

    /** Tick one frame in which rewind is actively stepping (held or coasting). */
    public void frameActive(double currentSpeed) {
        intensity = Math.min(1.0f, intensity + ATTACK_PER_FRAME);
        if (currentSpeed > 0.0) {
            latchedSpeed = (float) Math.max(MIN_SPEED, Math.min(MAX_SPEED, currentSpeed));
        }
    }

    /** Tick one frame in which rewind is not stepping. */
    public void frameInactive() {
        intensity = Math.max(0.0f, intensity - RELEASE_PER_FRAME);
    }

    /** Instantly kill the effect (boundaries, mode exits, teardown). */
    public void reset() {
        intensity = 0.0f;
        latchedSpeed = DEFAULT_SPEED;
    }

    public float intensity() {
        return intensity;
    }

    public float speed() {
        return latchedSpeed;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.rewind.TestRewindEffectEnvelope" test`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindEffectEnvelope.java src/test/java/com/openggf/game/rewind/TestRewindEffectEnvelope.java
git commit -m "feat: add rewind effect intensity envelope

Changelog: n/a: incremental step; changelog entry lands with final Engine wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: DisplayShaderPipeline dynamic-uniform overload

**Files:**
- Modify: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPipeline.java` (methods `apply` ~line 153, `renderPass` ~line 228, `setUniforms` ~line 284)
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java` (add one test method)

**Interfaces:**
- Consumes: existing `DisplayShaderPipeline` internals.
- Produces: `public void apply(int vpX, int vpY, int vpW, int vpH, int frameCount, Map<String, Float> dynamicUniforms)`. Existing 5-arg `apply` behavior unchanged (delegates with `Map.of()`). Task 3 calls the 6-arg form.

- [ ] **Step 1: Write the failing GL smoke test**

Add to `TestDisplayShaderPipelineSmoke.java` (inside the class, near `retroArchParameterUniformSourceActivatesAndBindsPresetValue`):

```java
    @Test
    public void dynamicUniformOverloadBindsPerFrameValues() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(16, 16, 16, 16);

            DisplayShaderPreset preset = new DisplayShaderPreset("dynamic-uniform", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            uniform float RewindIntensity;
                            void main() {
                                gl_FragColor = RewindIntensity == 0.75
                                    ? vec4(0.0, 1.0, 0.0, 1.0)
                                    : vec4(1.0, 0.0, 0.0, 1.0);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 16, 16);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 16, 16, 1, Map.of("RewindIntensity", 0.75f));

            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            assertTrue(green > 200, "expected dynamic uniform to render green, red=" + red + " green=" + green);
            assertTrue(red < 50, "expected low red success pixel, red=" + red + " green=" + green);
            pipeline.dispose();
        }
    }
```

(`List`, `Map`, `ByteBuffer`, and the GL statics are already imported by this test class.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPipelineSmoke#dynamicUniformOverloadBindsPerFrameValues" test`
Expected: COMPILE ERROR — no 6-arg `apply` overload exists.

- [ ] **Step 3: Implement the overload**

In `DisplayShaderPipeline.java`, replace the existing `apply` method signature and body wiring:

```java
    public void apply(int vpX, int vpY, int vpW, int vpH, int frameCount) {
        apply(vpX, vpY, vpW, vpH, frameCount, Map.of());
    }

    /**
     * Apply the pass chain with additional per-frame float uniforms, set after
     * the preset's static parameter values (so dynamic values win on collision).
     */
    public void apply(int vpX, int vpY, int vpW, int vpH, int frameCount, Map<String, Float> dynamicUniforms) {
```

The 6-arg body is the existing `apply` body unchanged except the `renderPass(...)` call inside the pass loop gains the extra argument:

```java
                renderPass(i, pass, target, inputTexture, inputWidth, inputHeight, frameCount, dynamicUniforms);
```

Update `renderPass` to accept and forward it:

```java
    private void renderPass(int passIndex, CompiledPass pass, PassTarget target, int inputTexture,
                            int inputWidth, int inputHeight, int frameCount, Map<String, Float> dynamicUniforms) {
```

and inside it change the `setUniforms(...)` call to:

```java
        setUniforms(pass.programId(), sourceWidth, sourceHeight,
                inputWidth, inputHeight, target.width(), target.height(), frameCount,
                pass.parameterValues(), dynamicUniforms);
```

Update `setUniforms` to accept the extra map and set it AFTER the static parameter loop:

```java
    private void setUniforms(int programId, int videoWidth, int videoHeight, int inputWidth, int inputHeight,
                             int outputWidth, int outputHeight, int frameCount,
                             Map<String, Float> parameterValues, Map<String, Float> dynamicUniforms) {
```

with this added immediately after the existing `parameterValues` loop:

```java
        if (dynamicUniforms != null) {
            for (Map.Entry<String, Float> entry : dynamicUniforms.entrySet()) {
                setFloat(programId, entry.getKey(), entry.getValue());
            }
        }
```

- [ ] **Step 4: Run the smoke tests to verify they pass**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPipelineSmoke" test`
Expected: all tests PASS (or SKIP with the GLFW assumption message only on a headless machine — on a dev machine with a GPU they must pass, including the new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPipeline.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java
git commit -m "feat: add per-frame dynamic uniform overload to display shader pipeline

Changelog: n/a: incremental step; changelog entry lands with final Engine wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: VHS shader + RewindVhsEffectPass

**Files:**
- Create: `src/main/resources/shaders/shader_vhs_rewind.glsl`
- Create: `src/main/java/com/openggf/graphics/shaderlib/RewindVhsEffectPass.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestRewindVhsEffectPass.java` (no-GL)
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java` (one GL test)

**Interfaces:**
- Consumes: Task 2's `apply(vpX, vpY, vpW, vpH, frameCount, Map<String, Float>)`.
- Produces:
  - `public static DisplayShaderPreset builtInPreset() throws IOException` (no GL needed — loads the classpath resource and builds the preset record).
  - `public void prewarm(int sourceW, int sourceH, int viewportW, int viewportH)`
  - `public void apply(float intensity, float speed, int sourceW, int sourceH, int vpX, int vpY, int vpW, int vpH)`
  - `public boolean isFailed()`, `public void dispose()`
  Task 6 (Engine) calls `prewarm` and `apply`.

- [ ] **Step 1: Write the failing no-GL sanity test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewindVhsEffectPass {

    @Test
    public void builtInPresetLoadsAndDeclaresRequiredUniforms() throws IOException {
        DisplayShaderPreset preset = RewindVhsEffectPass.builtInPreset();

        assertEquals(1, preset.passes().size(), "VHS effect is a single fragment-only pass");
        DisplayShaderPass pass = preset.passes().get(0);
        assertEquals(GlslShape.FRAGMENT_ONLY, pass.shape());
        assertEquals(ScaleType.VIEWPORT, pass.scaleTypeX(), "must not downsample the captured frame");

        String fragment = pass.fragmentSource();
        assertTrue(fragment.contains("uniform sampler2D Texture"), "missing Texture uniform");
        assertTrue(fragment.contains("uniform vec2 TextureSize"), "missing TextureSize uniform");
        assertTrue(fragment.contains("uniform vec2 OutputSize"), "missing OutputSize uniform");
        assertTrue(fragment.contains("uniform int FrameCount"), "missing FrameCount uniform");
        assertTrue(fragment.contains("uniform float RewindIntensity"), "missing RewindIntensity uniform");
        assertTrue(fragment.contains("uniform float RewindSpeed"), "missing RewindSpeed uniform");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestRewindVhsEffectPass" test`
Expected: COMPILE ERROR — `RewindVhsEffectPass` does not exist.

- [ ] **Step 3: Write the shader**

Create `src/main/resources/shaders/shader_vhs_rewind.glsl`. Written in RetroArch-compat legacy style (`gl_FragColor`, `texture2D`, no `#version` — `RetroArchGlslCompat` adds the preamble), matching the proven fragment-only smoke-test presets. Pixel-scale math uses `TextureSize` (logical source size, 320x224) so artifact sizes are authentic regardless of window size. `uv.y == 0` is the BOTTOM of the frame (capture and `gl_FragCoord` are both GL bottom-up — see `combinedTextureCoordinatesPreserveXAxisAndYAxis`).

```glsl
// VHS picture-search (rewind/review) post-process.
// Applied by RewindVhsEffectPass while live rewind is active; runs before any
// user display shader so a CRT preset displays the damaged "signal".
// Simulates the tape, not the TV: no scanlines, no curvature.

uniform sampler2D Texture;
uniform vec2 TextureSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform float RewindIntensity; // 0..1 envelope
uniform float RewindSpeed;     // 0.25..4.0, 1.0 = base tape speed

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// shortest distance between two points on the 0..1 wrap-around ring
float ringDist(float a, float b) {
    float d = abs(a - b);
    return min(d, 1.0 - d);
}

void main() {
    vec2 uv = gl_FragCoord.xy / OutputSize;
    float t = float(FrameCount);
    float k = clamp(RewindIntensity, 0.0, 1.0);
    float speed = clamp(RewindSpeed, 0.25, 4.0);

    // vertical wobble: slow sub-pixel sway
    uv.y += k * (0.5 / TextureSize.y) * sin(t * 0.11 + uv.x * 3.0);

    float line = floor(uv.y * TextureSize.y);

    // picture-search tear bands: 2 (3 at high speed) wide noise bands
    // scrolling upward at a rate proportional to tape speed
    float scroll = fract(t * 0.006 * speed);
    float bandCount = speed > 2.0 ? 3.0 : 2.0;
    float band = 0.0;
    for (int i = 0; i < 3; i++) {
        if (float(i) >= bandCount) {
            break;
        }
        float center = fract(float(i) / bandCount + scroll);
        float d = ringDist(uv.y, center);
        band = max(band, 1.0 - smoothstep(0.045, 0.06, d));
    }
    band *= k;

    // ragged per-line horizontal displacement inside the bands (up to ~25 src px)
    float rag = hash21(vec2(line, t)) * 2.0 - 1.0;
    float x = uv.x + band * rag * (25.0 / TextureSize.x);

    // global per-scanline jitter (+/- 1.5 src px)
    float jitter = hash21(vec2(line * 1.37, t * 0.7)) * 2.0 - 1.0;
    x += k * jitter * (1.5 / TextureSize.x);

    // head-switching strip: bottom ~2.5% of the frame
    float strip = 1.0 - smoothstep(0.02, 0.03, uv.y);
    x += strip * k * (hash21(vec2(line, t * 1.3)) - 0.3) * (18.0 / TextureSize.x);

    vec2 suv = vec2(x, uv.y);

    // chroma fringing: R and B sampled with opposite ~1.5 px offsets
    float fringe = k * 1.5 / TextureSize.x;
    vec3 col;
    col.r = texture2D(Texture, suv + vec2(fringe, 0.0)).r;
    col.g = texture2D(Texture, suv).g;
    col.b = texture2D(Texture, suv - vec2(fringe, 0.0)).b;

    // global tape desaturation (~12%)
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(luma), 0.12 * k);

    // inside bands / head-switch strip: kill chroma, mix in luma static
    float noiseZone = max(band, strip * k);
    float staticN = hash21(vec2(floor(suv.x * TextureSize.x), line + t * 13.0));
    col = mix(col, vec3(luma), noiseZone);
    col = mix(col, vec3(staticN), noiseZone * 0.4);

    // bright tear line at band edges (band field transitions 0 -> 1)
    float tear = smoothstep(0.15, 0.25, band * (1.0 - band)) * k;
    col += tear * vec3(0.35);

    // tape dropouts: sparse bright horizontal dashes
    float rowRoll = hash21(vec2(line, floor(t * 0.5)));
    if (rowRoll > 0.997) {
        float segStart = hash21(vec2(line * 3.1, floor(t * 0.5)));
        float segLen = 0.04 + 0.08 * hash21(vec2(line * 7.7, t));
        if (uv.x > segStart && uv.x < segStart + segLen) {
            col = mix(col, vec3(0.95), k * 0.85);
        }
    }

    gl_FragColor = vec4(col, 1.0);
}
```

- [ ] **Step 4: Write RewindVhsEffectPass**

```java
package com.openggf.graphics.shaderlib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Engine-owned VHS picture-search post-process for live rewind.
 * <p>
 * Owns a private {@link DisplayShaderPipeline} independent of the user's
 * display-shader selection. Prewarmed once at GL init (when live rewind and
 * the VHS effect are both enabled in config) so the first rewind press never
 * pays a shader-compile hitch; applied per frame only while the rewind effect
 * envelope intensity is above zero. On any activation or apply failure the
 * pass latches failed and never retries for the session — the pipeline's own
 * logging covers the details, this class adds a single summary warning.
 */
public final class RewindVhsEffectPass {

    private static final Logger LOG = Logger.getLogger(RewindVhsEffectPass.class.getName());
    static final String SHADER_RESOURCE = "/shaders/shader_vhs_rewind.glsl";

    private final DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
    private boolean activated;
    private boolean failed;
    private int frameCounter;

    /** Builds the built-in preset from the classpath. No GL required. */
    public static DisplayShaderPreset builtInPreset() throws IOException {
        String fragment = loadResource(SHADER_RESOURCE);
        return new DisplayShaderPreset("vhs-rewind", ShaderPhase.PRESENTATION, List.of(
                new DisplayShaderPass(null, fragment, GlslShape.FRAGMENT_ONLY,
                        1.0, 1.0, ScaleType.VIEWPORT, ScaleType.VIEWPORT,
                        false, WrapMode.CLAMP_TO_EDGE)));
    }

    /** Compile and activate the pipeline. Call once during engine GL init. */
    public void prewarm(int sourceW, int sourceH, int viewportW, int viewportH) {
        if (activated || failed) {
            return;
        }
        try {
            pipeline.resize(sourceW, sourceH, viewportW, viewportH);
            if (!pipeline.activate(builtInPreset())) {
                markFailed("activation failed: " + pipeline.lastActivationFailure());
                return;
            }
            activated = true;
        } catch (Exception e) {
            markFailed("load failed: " + e.getMessage());
        }
    }

    /**
     * Apply the effect over the current default-framebuffer viewport contents.
     * No-op unless prewarmed, healthy, and intensity is above zero.
     */
    public void apply(float intensity, float speed,
                      int sourceW, int sourceH,
                      int vpX, int vpY, int vpW, int vpH) {
        if (!activated || failed || intensity <= 0.0f || vpW <= 0 || vpH <= 0) {
            return;
        }
        pipeline.resize(sourceW, sourceH, vpW, vpH);
        pipeline.apply(vpX, vpY, vpW, vpH, frameCounter++,
                Map.of("RewindIntensity", intensity, "RewindSpeed", speed));
        if (!pipeline.isActive()) {
            // apply() disposes the pipeline internally on failure
            activated = false;
            markFailed("apply failed; see DisplayShaderPipeline log");
        }
    }

    public boolean isFailed() {
        return failed;
    }

    public void dispose() {
        pipeline.dispose();
        activated = false;
    }

    private void markFailed(String reason) {
        failed = true;
        LOG.warning("VHS rewind effect disabled for this session: " + reason);
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = RewindVhsEffectPass.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing shader resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 5: Run the no-GL test to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestRewindVhsEffectPass" test`
Expected: PASS.

- [ ] **Step 6: Add the GL smoke test for the real shader**

Add to `TestDisplayShaderPipelineSmoke.java`:

```java
    @Test
    public void vhsRewindBuiltInPresetActivatesAndApplies() throws Exception {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);

            assertTrue(pipeline.activate(RewindVhsEffectPass.builtInPreset()),
                    pipeline.lastActivationFailure());

            glViewport(0, 0, 32, 32);
            glClearColor(0.2f, 0.4f, 0.6f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 32, 32, 5, Map.of("RewindIntensity", 1.0f, "RewindSpeed", 1.0f));

            assertTrue(pipeline.isActive(), "VHS preset apply must not disable the pipeline");
            pipeline.dispose();
        }
    }
```

- [ ] **Step 7: Run the GL smoke tests**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPipelineSmoke" test`
Expected: all PASS on a GPU machine (the new test catches any GLSL syntax/link error in the shader — if it fails, fix `shader_vhs_rewind.glsl`, not the test).

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/shaders/shader_vhs_rewind.glsl src/main/java/com/openggf/graphics/shaderlib/RewindVhsEffectPass.java src/test/java/com/openggf/graphics/shaderlib/TestRewindVhsEffectPass.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java
git commit -m "feat: add VHS picture-search shader and rewind effect pass

Changelog: n/a: incremental step; changelog entry lands with final Engine wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Config key `LIVE_REWIND_VHS_EFFECT`

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java` (after the `LIVE_REWIND_TAPE_COAST_*` constants, ~line 371+)
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java` (rewind block, after `LIVE_REWIND_TAPE_COAST_MIN_STEPS` ~line 153)
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java` (defaults block, after `LIVE_REWIND_TAPE_COAST_MIN_STEPS` ~line 647)
- Modify: `CONFIGURATION.md` (rewind table after the `LIVE_REWIND_TAPE_COAST_MAX_STEPS` row ~line 318; YAML sample after `tapeCoastMinSteps` ~line 552)

**Interfaces:**
- Produces: `SonicConfiguration.LIVE_REWIND_VHS_EFFECT` (bool, YAML `rewind.vhsEffect`, default `true`). Task 6 reads it via `configService.getBoolean(...)`.

- [ ] **Step 1: Add the enum constant**

In `SonicConfiguration.java`, after the last `LIVE_REWIND_TAPE_COAST_*` constant:

```java
	/**
	 * Whether the VHS picture-search shader effect renders while live rewind is active.
	 */
	LIVE_REWIND_VHS_EFFECT,
```

- [ ] **Step 2: Add the catalog entry**

In `ConfigCatalog.java`, in the `// rewind (player-facing live rewind)` block after the `LIVE_REWIND_TAPE_COAST_MIN_STEPS` entry:

```java
        put(LIVE_REWIND_VHS_EFFECT, of("rewind", "vhsEffect", BOOL,
                "Render a VHS picture-search effect while live rewind is active"));
```

- [ ] **Step 3: Add the default**

In `SonicConfigurationService.java`, after `putDefault(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MIN_STEPS, 0.25);`:

```java
		putDefault(SonicConfiguration.LIVE_REWIND_VHS_EFFECT, true);
```

- [ ] **Step 4: Document in CONFIGURATION.md**

Table row (after the `LIVE_REWIND_TAPE_COAST_MAX_STEPS` row):

```markdown
| `LIVE_REWIND_VHS_EFFECT` | `rewind.vhsEffect` | bool | `true` | Render an authentic VHS picture-search effect (scrolling noise bars, scanline jitter, chroma bleed, tape dropouts, head-switch strip) while live rewind is active, fading out over ~10 frames after release. Applied after the fade pass and before any user display shader. Only meaningful when `LIVE_REWIND_ENABLED` is true. |
```

YAML sample (after the `tapeCoastMinSteps` line):

```yaml
  vhsEffect: true   # VHS picture-search effect while live rewind is active
```

- [ ] **Step 5: Run the configuration test suite**

Run: `mvn "-Dtest=com.openggf.configuration.*" test`
Expected: PASS (catalog-completeness style tests must see the new key in both the enum and the catalog).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/ConfigCatalog.java src/main/java/com/openggf/configuration/SonicConfigurationService.java CONFIGURATION.md
git commit -m "feat: add rewind.vhsEffect config key

Changelog: n/a: incremental step; changelog entry lands with final Engine wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: LiveRewindManager envelope wiring + GameLoop accessors

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Modify: `src/main/java/com/openggf/GameLoop.java` (after `renderLiveRewindHud`, ~line 418)

**Interfaces:**
- Consumes: Task 1's `RewindEffectEnvelope`.
- Produces: `LiveRewindManager.effectIntensity()` / `effectSpeed()` (both `float`), `GameLoop.liveRewindEffectIntensity()` / `liveRewindEffectSpeed()` (both `float`). Task 6 (Engine) calls the GameLoop pair.

Tick contract (why this is single-tick-per-frame): in LEVEL mode without a trace session, `GameLoop.step()` calls `liveRewindManager.handleRealtimeRewindInput(...)` exactly once per frame (GameLoop.java:703-708) — when it returns true the step returns early and `recordExternalFrame` never runs that frame. So the envelope is ticked ONLY inside `handleRealtimeRewindInput`; `recordExternalFrame` does not touch it. When a trace session is active neither runs and the envelope stays at zero (live rewind and trace sessions are mutually exclusive presentation paths).

- [ ] **Step 1: Wire the envelope into LiveRewindManager**

Add the field (after `private boolean rewinding;`):

```java
    private final RewindEffectEnvelope effectEnvelope = new RewindEffectEnvelope();
```

Replace `handleRealtimeRewindInput` with (three envelope ticks added — held branch, coast branch, not-rewinding fallthrough — everything else byte-identical):

```java
    public boolean handleRealtimeRewindInput(GameMode mode, InputHandler input) {
        if (mode != GameMode.LEVEL || input == null || !enabled()) {
            activeInputHandler = null;
            clear();
            return false;
        }
        activeInputHandler = input;
        if (!ensureInstalled()) {
            return false;
        }
        int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
        if (input.isKeyDown(rewindKey)) {
            if (!rewinding) {
                GameServices.audio().beginReverseAudioPresentation();
                beginReverseFadePresentation();
            }
            rewinding = true;
            int steps = speedController.stepsWhileHeld();
            GameServices.audio().setReversePlaybackRate(speedController.currentSpeed());
            stepBackward(steps);
            effectEnvelope.frameActive(speedController.currentSpeed());
            GameServices.audio().update();
            return true;
        }
        int coastSteps = speedController.stepsAfterRelease();
        if (rewinding && coastSteps > 0) {
            if (stepBackward(coastSteps) > 0) {
                GameServices.audio().setReversePlaybackRate(speedController.currentSpeed());
                effectEnvelope.frameActive(speedController.currentSpeed());
                GameServices.audio().update();
                return true;
            }
            speedController.reset();
        }
        if (rewinding) {
            cleanupPresentationAfterRealtimeRewind(AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
        }
        rewinding = false;
        effectEnvelope.frameInactive();
        return false;
    }
```

Add instant-zero resets. In `clear()`, add as the LAST line:

```java
        effectEnvelope.reset();
```

In `handleLevelLoadBoundary()` and `handleSeamlessLevelTransitionBoundary()`, add immediately after the existing `rewinding = false;` line in each:

```java
        effectEnvelope.reset();
```

Add the accessors (after `renderHud`):

```java
    /** Current VHS rewind presentation intensity, 0..1. */
    public float effectIntensity() {
        return effectEnvelope.intensity();
    }

    /** Latched tape speed for the VHS rewind presentation, 0.25..4.0. */
    public float effectSpeed() {
        return effectEnvelope.speed();
    }
```

- [ ] **Step 2: Add the GameLoop accessors**

In `GameLoop.java`, after `renderLiveRewindHud(...)`:

```java
    public float liveRewindEffectIntensity() {
        return liveRewindManager.effectIntensity();
    }

    public float liveRewindEffectSpeed() {
        return liveRewindManager.effectSpeed();
    }
```

- [ ] **Step 3: Compile and run the neighboring test suites**

Run: `mvn "-Dtest=com.openggf.game.rewind.*" test`
Expected: PASS (including `TestRewindEffectEnvelope` from Task 1 and all pre-existing rewind tests).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/LiveRewindManager.java src/main/java/com/openggf/GameLoop.java
git commit -m "feat: tick VHS rewind effect envelope from live rewind manager

Changelog: n/a: incremental step; changelog entry lands with final Engine wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Engine wiring, CHANGELOG, verification

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java` (field ~line 131; `initializeDisplayShaders()` ~line 487-503; `display()` fade/PRESENTATION block ~lines 1582-1588; `cleanup()` ~line 2310)
- Modify: `CHANGELOG.md` (top of `## Unreleased`)

**Interfaces:**
- Consumes: `RewindVhsEffectPass.prewarm(...)` / `.apply(...)` (Task 3), `gameLoop.liveRewindEffectIntensity()` / `.liveRewindEffectSpeed()` (Task 5), `SonicConfiguration.LIVE_REWIND_VHS_EFFECT` (Task 4).
- Produces: the user-visible feature.

- [ ] **Step 1: Add the field and import**

`RewindVhsEffectPass` is in `com.openggf.graphics.shaderlib` — add the import next to the existing `com.openggf.graphics.shaderlib.*` imports (Engine.java lines 55-62). Add the field near the other display-shader fields (~line 131):

```java
	private RewindVhsEffectPass rewindVhsEffectPass;
```

- [ ] **Step 2: Prewarm at GL init**

At the END of `initializeDisplayShaders()` (after `displayShaderController.applySavedSelectionSilently();`):

```java
		if (configService.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED)
				&& configService.getBoolean(SonicConfiguration.LIVE_REWIND_VHS_EFFECT)) {
			rewindVhsEffectPass = new RewindVhsEffectPass();
			rewindVhsEffectPass.prewarm(
					configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
					configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS),
					Math.max(1, viewportWidth),
					Math.max(1, viewportHeight));
		}
```

If either flag is false, `rewindVhsEffectPass` stays null — no GL resources, matching the spec's "never activated" rule.

- [ ] **Step 2b: Dispose at shutdown**

The pass owns a private `DisplayShaderPipeline` that GraphicsManager does not know about, so it needs its own cleanup step. In `Engine.cleanup()` (~line 2310), add a step immediately BEFORE the `cleanupStep("graphics manager", graphicsManager::cleanup);` line (GL context is still current there, matching how the user display pipeline is disposed inside `GraphicsManager.cleanup()`):

```java
		cleanupStep("VHS rewind effect", () -> {
			if (rewindVhsEffectPass != null) {
				rewindVhsEffectPass.dispose();
				rewindVhsEffectPass = null;
			}
		});
```

- [ ] **Step 3: Apply in display() between fade pass and PRESENTATION phase**

In `Engine.display()`, the current code reads (~lines 1582-1588):

```java
		// Render screen fade overlay via unified UI render pipeline
		if (uiPipeline != null && !userRecordingSceneSuppressed) {
			uiPipeline.renderFadePass();
		}
		if (!userRecordingSceneSuppressed) {
			applyDisplayShaderPhase(ShaderPhase.PRESENTATION);
		}
```

Insert the VHS pass between them:

```java
		// Render screen fade overlay via unified UI render pipeline
		if (uiPipeline != null && !userRecordingSceneSuppressed) {
			uiPipeline.renderFadePass();
		}
		// VHS picture-search effect while live rewind is active. Runs BEFORE the
		// user's PRESENTATION-phase display shader so a CRT preset displays the
		// damaged "signal" (tape artifacts precede the TV in the real chain).
		if (!userRecordingSceneSuppressed && rewindVhsEffectPass != null && gameLoop != null) {
			rewindVhsEffectPass.apply(
					gameLoop.liveRewindEffectIntensity(),
					gameLoop.liveRewindEffectSpeed(),
					configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
					configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS),
					viewportX, viewportY, viewportWidth, viewportHeight);
		}
		if (!userRecordingSceneSuppressed) {
			applyDisplayShaderPhase(ShaderPhase.PRESENTATION);
		}
```

- [ ] **Step 4: Add the CHANGELOG entry**

At the top of the `## Unreleased` section in `CHANGELOG.md`:

```markdown
- **Live rewind now presents an authentic VHS picture-search effect:** while the rewind key is held (and through a ~10-frame release tail), a new engine-owned post-process pass (`RewindVhsEffectPass` + `shaders/shader_vhs_rewind.glsl`) renders scrolling tear/noise bands (a third band appears at high tape-coast speed), per-scanline jitter, chroma fringing with mild desaturation, sparse tape dropouts, a bottom head-switching strip, and sub-pixel vertical wobble. The pass runs after the fade pass and before any user display shader, so CRT presets display the damaged "signal"; the post-fade `REWIND <frame>` HUD label stays crisp. Intensity follows a 4-frame-attack/10-frame-release envelope (`RewindEffectEnvelope`) that latches the last tape speed so bands keep scrolling through the release tail. The shader pipeline is prewarmed at GL init (no first-rewind compile hitch) only when both `rewind.liveEnabled` and the new `rewind.vhsEffect` (default `true`) are set; compile/apply failure logs one warning and disables the effect for the session without touching gameplay or the user's shader selection. `DisplayShaderPipeline` gains a per-frame dynamic-uniform `apply` overload.
```

- [ ] **Step 5: Full build and test sweep**

Run: `mvn test`
Expected: BUILD SUCCESS, no new failures relative to the branch point (pre-existing unrelated failures documented in the trace frontier log are acceptable; anything in `com.openggf.graphics.shaderlib`, `com.openggf.game.rewind`, or `com.openggf.configuration` must be green).

- [ ] **Step 6: Manual verification**

1. In `config.yaml`, set `rewind.liveEnabled: true` (leave `rewind.vhsEffect` at its default `true`).
2. `mvn package` then `java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar`.
3. Start a level, play a few seconds, hold R: expect the picture-search effect to snap in over ~4 frames — two scrolling noise bands, jitter, chroma bleed, bottom noise strip — while the `REWIND <frame>` label stays crisp; release R: the effect fades over ~10 frames with the bands still scrolling.
4. With tape coast enabled (`rewind.tapeCoastEnabled: true`), hold R longer: bands scroll faster and a third band appears above 2.0x speed.
5. Cycle a user display shader (shader next/previous keys): the CRT preset applies on top of the VHS damage during rewind and continues to work when not rewinding.
6. Set `rewind.vhsEffect: false`, relaunch, hold R: rewind works with no visual effect.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/Engine.java CHANGELOG.md
git commit -m "feat: wire VHS picture-search effect into live rewind presentation

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Completion

After Task 6, use superpowers:finishing-a-development-branch. Note the repo rule: when merging this branch into `develop`, stage a `README.md` update summarizing the change in the release/change log section.
