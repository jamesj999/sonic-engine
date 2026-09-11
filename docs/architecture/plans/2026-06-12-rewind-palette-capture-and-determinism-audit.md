# Rewind Palette Capture & Determinism Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make live palette colors part of the rewind snapshot set (fixes "palette changes don't rewind" in the AIZ intro), and add a config-gated determinism audit that reports the first state divergence between live play and segment re-simulation (the root mechanism behind object snapping at keyframes), plus convert the hand-rolled AIZ zone-event byte sidecar to schema-driven capture with a CI guard.

**Architecture:** Held rewind stores a full `CompositeSnapshot` keyframe every 60 frames and re-simulates intermediate frames on demand (`RewindController` + `SegmentCache` + `LiveRewindStepper`). Anything outside the snapshot set therefore (a) never rewinds and (b) makes re-simulation diverge from live play, snapping at keyframe boundaries. This plan (1) adds a `PaletteColorStateAdapter` `RewindSnapshottable` capturing normal + underwater palette color bytes and re-caching GPU palette textures on restore; (2) promotes the existing test-scope `RewindSnapshotDiff` to `src/main` and hooks a `RewindDeterminismAuditor` into `RewindController.recordExternalStep()` behind a new `debug.rewind.determinismAudit` config flag; (3) replaces `Sonic3kLevelEventManager.writeAizState/readAizState` with schema-driven capture (`RewindSchemaRegistry.schemaFor` + `CompactFieldCapturer`) guarded by a coverage-parity test.

**Tech Stack:** Java 21, Maven, JUnit 5 (Jupiter only — no JUnit 4), existing `com.openggf.game.rewind` framework.

**Verified background facts (do not re-derive):**
- Keyframe interval is 60 (`LiveRewindManager.java:22`). Keyframes capture in `RewindController.recordExternalStep()` when `currentFrame % keyframeInterval == 0`.
- Palette colors are in NO snapshot today. `PaletteOwnershipSnapshot` = owner metadata strings only. `LevelSnapshot` has no palette content.
- Normal palettes live on `Level` (`level.getPalette(i)`, `level.getPaletteCount()`, typically 4 lines × 16 colors). Underwater palettes come from `WaterSystem.getUnderwaterPalette(level.getZoneIndex(), levelManager.getCurrentAct())` (see usage at `S3kPaletteWriteSupport.java:223`) and are mutated in place by `PaletteOwnershipRegistry.resolveInto`.
- GPU palette textures are pushed via `GraphicsManager.cachePaletteTexture(palette, line)` and `cacheUnderwaterPaletteTexture(underwater, normalLine0)`, guarded by `graphics.isGlInitialized()` (see `PaletteOwnershipRegistry.resolveInto`).
- `RewindSnapshotDiff` already exists at `src/test/java/com/openggf/game/rewind/RewindSnapshotDiff.java` (510 lines) — recursive path-based diff with object-manager slot-identity bucketing and cosmetic-divergence filtering. Promote, don't rewrite.
- `RewindSchemaRegistry.schemaFor(Class)` is public and works for arbitrary classes; `CompactFieldCapturer.capture(Object)/restore(Object, blob)` are public; `CompactFieldCapturer.validateSupported` throws if the schema has UNSUPPORTED fields. `RewindObjectStateBlob(schemaId, type, byte[] scalarData, Object[] opaqueValues)`.
- `Sonic3kLevelEventManager.captureExtra()` starts ~line 1269; `writeAizState`/`readAizState` ~line 1449; the AIZ block is 84 bytes (20 booleans + 15 ints + 1 enum ordinal) accessed via getters/setters on `Sonic3kAIZEvents`.
- `Bk2FrameInput` has convenience constructor `(int frameIndex, int p1InputMask, int p1ActionMask, boolean p1StartPressed, String rawLine)`.
- `CompositeSnapshot` exposes `entries()`, `get(key)`, `containsKey(key)`.

**Repo policies that apply to every commit in this plan:**
- Base the branch on `develop`, NOT `master` (master is thousands of commits behind). Branch name: `bugfix/ai-rewind-palette-and-audit`.
- This working tree is shared by concurrent agent sessions: work in an isolated worktree, never `git add -A` / `git add .` — stage exact files only.
- Run `mvn test-compile` (or any mvn goal) once before the first commit so the `.githooks` hooks install; or run `git config core.hooksPath .githooks` manually.
- Every commit message gets the trailer block (auto-appended by prepare-commit-msg — fill it in, don't delete it). `feat`/`fix` commits touching `src/main/` MUST set `Changelog: updated` and stage `CHANGELOG.md`.
- JUnit 5 / Jupiter only. Quote `-Dtest=` selectors in PowerShell. Note `-Dtest` runs may print a misleading project-wide MSE `total=` — read the per-class results.

---

## Task 0: Branch setup (existing Codex worktree)

This thread already runs in a linked worktree at `C:/Users/farre/.codex/worktrees/066a/sonic-engine`, detached at the same commit as `develop`. Do NOT create a new worktree. The working tree has pre-existing dirty doc changes — stage exact files only, never `git add -A` / `git add .`.

- [ ] **Step 1: Create the branch in this worktree**

```bash
cd /c/Users/farre/.codex/worktrees/066a/sonic-engine
git switch -c bugfix/ai-rewind-palette-and-audit
git config core.hooksPath .githooks
```

- [ ] **Step 2: Copy ROMs (gitignored, needed by some tests) and sanity-build**

```bash
cp /c/Users/farre/IdeaProjects/sonic-engine/*.gen . 2>/dev/null
mvn -q test-compile
```
Expected: BUILD SUCCESS.

---

## Task 1: `PaletteColorSnapshot` + `PaletteColorStateAdapter`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/PaletteColorSnapshot.java`
- Create: `src/main/java/com/openggf/game/palette/PaletteColorStateAdapter.java`
- Test: `src/test/java/com/openggf/game/palette/TestPaletteColorStateAdapter.java`

The adapter is deliberately decoupled from `Level`/`WaterSystem` — it takes `Supplier<Palette[]>` for both surfaces so unit tests need no level scaffolding. Registration (Task 2) supplies the real arrays.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.palette;

import com.openggf.game.rewind.snapshot.PaletteColorSnapshot;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestPaletteColorStateAdapter {

    private static Palette[] makePalettes(int lines, byte seed) {
        Palette[] palettes = new Palette[lines];
        for (int l = 0; l < lines; l++) {
            palettes[l] = new Palette();
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                palettes[l].colors[c].r = (byte) (seed + l * 16 + c);
                palettes[l].colors[c].g = (byte) (seed + l * 16 + c + 1);
                palettes[l].colors[c].b = (byte) (seed + l * 16 + c + 2);
            }
        }
        return palettes;
    }

    private static byte[] rgbOf(Palette[] palettes) {
        byte[] out = new byte[palettes.length * Palette.PALETTE_SIZE * 3];
        int i = 0;
        for (Palette p : palettes) {
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                out[i++] = p.colors[c].r;
                out[i++] = p.colors[c].g;
                out[i++] = p.colors[c].b;
            }
        }
        return out;
    }

    @Test
    public void captureRestoreRoundTripsBothSurfaces() {
        Palette[] normal = makePalettes(4, (byte) 0x10);
        Palette[] underwater = makePalettes(4, (byte) 0x60);
        byte[] normalBefore = rgbOf(normal);
        byte[] underwaterBefore = rgbOf(underwater);

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> underwater, () -> null);
        PaletteColorSnapshot snap = adapter.capture();

        // Mutate every color after capture (simulates AIZ intro palette writes).
        for (Palette p : normal) {
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                p.colors[c].r = (byte) 0x7F;
            }
        }
        underwater[2].colors[15].g = (byte) 0x01;

        adapter.restore(snap);

        assertArrayEquals(normalBefore, rgbOf(normal), "normal colors must revert");
        assertArrayEquals(underwaterBefore, rgbOf(underwater), "underwater colors must revert");
    }

    @Test
    public void restoreWritesInPlaceWithoutReplacingColorObjects() {
        Palette[] normal = makePalettes(1, (byte) 0x20);
        Palette.Color aliased = normal[0].colors[5];

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> null, () -> null);
        PaletteColorSnapshot snap = adapter.capture();
        normal[0].colors[5].r = (byte) 0x44;
        adapter.restore(snap);

        assertSame(aliased, normal[0].colors[5],
                "restore must write color fields in place — other code aliases Color refs");
        assertEquals((byte) 0x25, aliased.r);
    }

    @Test
    public void nullSurfacesCaptureEmptyAndRestoreNoops() {
        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> null, () -> null, () -> null);
        PaletteColorSnapshot snap = adapter.capture();
        assertEquals(0, snap.normalRgb().length);
        assertEquals(0, snap.underwaterRgb().length);
        adapter.restore(snap); // must not throw
    }

    @Test
    public void keyIsStable() {
        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> null, () -> null, () -> null);
        assertEquals("palette-colors", adapter.key());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -q "-Dtest=com.openggf.game.palette.TestPaletteColorStateAdapter" test
```
Expected: COMPILATION FAILURE ("cannot find symbol: PaletteColorStateAdapter").

- [ ] **Step 3: Write the snapshot record**

```java
package com.openggf.game.rewind.snapshot;

/**
 * Snapshot of live palette COLOR data for both palette surfaces.
 *
 * <p>Layout: 3 bytes (r, g, b) per color, 16 colors per line, lines in
 * index order. {@code normalRgb} covers the level's normal palette lines;
 * {@code underwaterRgb} covers the underwater mirror set and is empty when
 * the current level has no underwater palettes. Complements
 * {@link PaletteOwnershipSnapshot}, which captures ownership metadata only.
 *
 * <p>Arrays are owned by the snapshot once constructed — treat as immutable
 * (consistent with the other snapshot records in this package).
 */
public record PaletteColorSnapshot(byte[] normalRgb, byte[] underwaterRgb) {
}
```

- [ ] **Step 4: Write the adapter**

```java
package com.openggf.game.palette;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.PaletteColorSnapshot;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Captures and restores live palette COLOR words for the normal and
 * underwater surfaces, and re-caches GPU palette textures on restore.
 *
 * <p>{@link PaletteOwnershipRegistry} snapshots ownership metadata only;
 * the colors themselves are mutated in place on {@code Level} palettes and
 * the water system's underwater set (via {@code resolveInto} and direct
 * writes such as the AIZ intro's palette mutations). Without this adapter
 * those writes are invisible to rewind: flags revert, colors don't.
 *
 * <p>Suppliers are re-evaluated on every capture/restore so the adapter
 * follows level/act swaps without re-registration.
 */
public final class PaletteColorStateAdapter implements RewindSnapshottable<PaletteColorSnapshot> {

    private static final int BYTES_PER_COLOR = 3;
    private static final int BYTES_PER_LINE = Palette.PALETTE_SIZE * BYTES_PER_COLOR;

    private final Supplier<Palette[]> normalPalettes;
    private final Supplier<Palette[]> underwaterPalettes;
    private final Supplier<GraphicsManager> graphics;

    public PaletteColorStateAdapter(Supplier<Palette[]> normalPalettes,
                                    Supplier<Palette[]> underwaterPalettes,
                                    Supplier<GraphicsManager> graphics) {
        this.normalPalettes = Objects.requireNonNull(normalPalettes, "normalPalettes");
        this.underwaterPalettes = Objects.requireNonNull(underwaterPalettes, "underwaterPalettes");
        this.graphics = Objects.requireNonNull(graphics, "graphics");
    }

    @Override
    public String key() {
        return "palette-colors";
    }

    @Override
    public PaletteColorSnapshot capture() {
        return new PaletteColorSnapshot(pack(normalPalettes.get()), pack(underwaterPalettes.get()));
    }

    @Override
    public void restore(PaletteColorSnapshot snapshot) {
        Palette[] normal = normalPalettes.get();
        Palette[] underwater = underwaterPalettes.get();
        unpack(normal, snapshot.normalRgb());
        unpack(underwater, snapshot.underwaterRgb());
        recacheTextures(normal, underwater);
    }

    @Override
    public void resetForMissingSnapshot() {
        // Colors simply stay live; nothing to reset.
    }

    private static byte[] pack(Palette[] palettes) {
        if (palettes == null) {
            return new byte[0];
        }
        byte[] out = new byte[palettes.length * BYTES_PER_LINE];
        int i = 0;
        for (Palette palette : palettes) {
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                Palette.Color color = palette.colors[c];
                out[i++] = color.r;
                out[i++] = color.g;
                out[i++] = color.b;
            }
        }
        return out;
    }

    private static void unpack(Palette[] palettes, byte[] rgb) {
        if (palettes == null || rgb.length == 0) {
            return;
        }
        int lines = Math.min(palettes.length, rgb.length / BYTES_PER_LINE);
        int i = 0;
        for (int l = 0; l < lines; l++) {
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                // Write fields in place: other code aliases Color references.
                Palette.Color color = palettes[l].colors[c];
                color.r = rgb[i++];
                color.g = rgb[i++];
                color.b = rgb[i++];
            }
        }
    }

    private void recacheTextures(Palette[] normal, Palette[] underwater) {
        GraphicsManager g = graphics.get();
        if (g == null || !g.isGlInitialized() || normal == null) {
            return;
        }
        for (int line = 0; line < normal.length; line++) {
            g.cachePaletteTexture(normal[line], line);
        }
        if (underwater != null && normal.length > 0) {
            g.cacheUnderwaterPaletteTexture(underwater, normal[0]);
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn -q "-Dtest=com.openggf.game.palette.TestPaletteColorStateAdapter" test
```
Expected: 4 tests PASS. (Note: `cacheUnderwaterPaletteTexture(Palette[], Palette)` — verify the exact parameter types against `GraphicsManager` before compiling; `PaletteOwnershipRegistry.resolveInto` lines 80-88 show the call shape.)

- [ ] **Step 6: Update CHANGELOG.md** — add under the unreleased/current section:

```markdown
- Rewind now captures and restores live palette colors (normal + underwater surfaces), so palette mutations (e.g. AIZ intro fire sequence) rewind correctly instead of persisting through a rewind.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/snapshot/PaletteColorSnapshot.java \
        src/main/java/com/openggf/game/palette/PaletteColorStateAdapter.java \
        src/test/java/com/openggf/game/palette/TestPaletteColorStateAdapter.java \
        CHANGELOG.md
git commit -m "feat: add palette color rewind snapshot adapter"
```
Fill the auto-appended trailers: `Changelog: updated`, all others `n/a`.

---

## Task 2: Register the adapter in `GameplayModeContext`

**Files:**
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java` (in `attachLevelManagers`, after the `spriteManager.rewindSnapshottable()` registration around line 233)
- Test: `src/test/java/com/openggf/game/rewind/TestPaletteColorRewindRegistration.java`

- [ ] **Step 1: Write the failing test** — registry-level round trip proving a composite capture/restore reverts palette colors:

```java
package com.openggf.game.rewind;

import com.openggf.game.palette.PaletteColorStateAdapter;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPaletteColorRewindRegistration {

    @Test
    public void compositeCaptureRestoreRevertsPaletteColors() {
        Palette[] normal = new Palette[] { new Palette(), new Palette() };
        normal[1].colors[15].r = (byte) 0x33;

        RewindRegistry registry = new RewindRegistry(null);
        registry.register(new PaletteColorStateAdapter(() -> normal, () -> null, () -> null));

        CompositeSnapshot snap = registry.capture();
        assertTrue(snap.containsKey("palette-colors"));

        normal[1].colors[15].r = (byte) 0x77; // post-capture mutation
        registry.restore(snap);

        assertEquals((byte) 0x33, normal[1].colors[15].r);
    }
}
```

- [ ] **Step 2: Run test to verify it fails or passes**

```bash
mvn -q "-Dtest=com.openggf.game.rewind.TestPaletteColorRewindRegistration" test
```
This test exercises adapter+registry wiring and should PASS already (Task 1 built both pieces); it exists to lock the composite key contract. If it passes, continue — the production registration below is the actual change under test at integration level.

- [ ] **Step 3: Add the production registration** in `GameplayModeContext.attachLevelManagers`, inside the existing `if (rewindRegistry != null)` block, after `rewindRegistry.register(spriteManager.rewindSnapshottable());`:

```java
rewindRegistry.deregister("palette-colors");
rewindRegistry.register(new PaletteColorStateAdapter(
        () -> levelPalettesOrNull(levelManager),
        () -> underwaterPalettesOrNull(waterSystem, levelManager),
        GameServices::graphics));
```

(`GameServices.graphicsOrNull()` does NOT exist — `graphics()` at `GameServices.java:310` is the accessor; `EngineServices.current()` self-bootstraps so it doesn't throw, and the adapter already null-checks the returned `GraphicsManager` and gates on `isGlInitialized()`.)

And add the two private static helpers to `GameplayModeContext`:

```java
private static Palette[] levelPalettesOrNull(LevelManager levelManager) {
    Level level = levelManager.getCurrentLevel();
    if (level == null) {
        return null;
    }
    Palette[] palettes = new Palette[level.getPaletteCount()];
    for (int i = 0; i < palettes.length; i++) {
        palettes[i] = level.getPalette(i);
    }
    return palettes;
}

private static Palette[] underwaterPalettesOrNull(WaterSystem waterSystem, LevelManager levelManager) {
    Level level = levelManager.getCurrentLevel();
    if (level == null) {
        return null;
    }
    return waterSystem.getUnderwaterPalette(level.getZoneIndex(), levelManager.getCurrentAct());
}
```

Add imports: `com.openggf.game.palette.PaletteColorStateAdapter`, `com.openggf.level.Level`, `com.openggf.level.Palette`, plus `com.openggf.game.GameServices` if not present.

**Verify before compiling:** the exact signature of `WaterSystem.getUnderwaterPalette` — mirror the call at `S3kPaletteWriteSupport.java:223` (`water.getUnderwaterPalette(level.getZoneIndex(), levelManager.getCurrentAct())`).

- [ ] **Step 4: Run the rewind + session test packages**

```bash
mvn -q "-Dtest=com.openggf.game.rewind.*Test*,com.openggf.game.rewind.Test*" test
```
Expected: PASS (existing guard tests like `TestCompositeSnapshotOwnership`, `TestRewindArchitectureGuard` must stay green; if a guard enumerates registered keys, add `palette-colors` to its expectation).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/session/GameplayModeContext.java \
        src/test/java/com/openggf/game/rewind/TestPaletteColorRewindRegistration.java
git commit -m "feat: register palette color rewind capture in gameplay session"
```
Trailers: `Changelog: n/a: covered by previous commit's CHANGELOG entry for the palette rewind feature`, others `n/a`. (If the hook rejects that justification form, fold a one-line CHANGELOG clarification into this commit and use `Changelog: updated`.)

---

## Task 3: Promote `RewindSnapshotDiff` to `src/main`

**Files:**
- Move: `src/test/java/com/openggf/game/rewind/RewindSnapshotDiff.java` → `src/main/java/com/openggf/game/rewind/RewindSnapshotDiff.java`

Same package, so no test imports change. The class must not reference any test-only types — verify its imports after the move (it imports only `snapshot.*` and `schema.RewindObjectStateBlob`, all main-scope).

- [ ] **Step 1: Move the file**

```bash
git mv src/test/java/com/openggf/game/rewind/RewindSnapshotDiff.java \
       src/main/java/com/openggf/game/rewind/RewindSnapshotDiff.java
```

- [ ] **Step 1b: Fix the class Javadoc** — `RewindBenchmark` stays test-scope, so the `{@link RewindBenchmark}` reference in the moved class's Javadoc now dangles. Replace it with plain text, e.g. change "See {@link RewindBenchmark} for the original implementation" to "Originally extracted from the test-scope {@code RewindBenchmark} helpers".

- [ ] **Step 2: Compile + run the rewind tests that use it**

```bash
mvn -q "-Dtest=com.openggf.game.rewind.TestRewindTorture,com.openggf.game.rewind.RewindBenchmark" test-compile
mvn -q "-Dtest=com.openggf.game.rewind.TestRewindTorturePatternBounds" test
```
Expected: compile clean, tests PASS. If `RewindBenchmark` or torture tests declared package-private access to helpers that the move breaks, widen the helper visibility on `RewindSnapshotDiff` (it's already `public final` with public statics — likely no change).

- [ ] **Step 3: Commit**

```bash
git add -A src/test/java/com/openggf/game/rewind/RewindSnapshotDiff.java \
           src/main/java/com/openggf/game/rewind/RewindSnapshotDiff.java
git commit -m "refactor: promote RewindSnapshotDiff to main for runtime audit use"
```
Trailers: `Changelog: n/a` is fine (refactor prefix is not gated), others `n/a`.

---

## Task 4: Determinism audit (config flag + auditor + controller hook)

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java` (new constant)
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java` (meta entry — must live with the `debug.*` entries, after all normal sections)
- Modify: wherever `LIVE_REWIND_ENABLED`'s default value is declared (grep: `grep -rn "LIVE_REWIND_ENABLED" src/main/java/com/openggf/configuration/`) — add default `false` for the new flag in the same structure
- Create: `src/main/java/com/openggf/game/rewind/RewindDeterminismAuditor.java`
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java` (wire flag in `ensureInstalled`)
- Test: `src/test/java/com/openggf/game/rewind/TestRewindDeterminismAuditor.java`
- Test: existing `TestConfigCatalog` must stay green (it validates every constant has catalog meta)

- [ ] **Step 1: Write the failing auditor test**

```java
package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewindDeterminismAuditor {

    /** Fully captured counter subsystem: replay is deterministic. */
    private static final class CapturedCounter implements RewindSnapshottable<Integer> {
        int value;
        @Override public String key() { return "counter"; }
        @Override public Integer capture() { return value; }
        @Override public void restore(Integer snapshot) { value = snapshot; }
    }

    /** Subsystem with a hidden field the snapshot misses: replay diverges. */
    private static final class LeakyCounter implements RewindSnapshottable<Integer> {
        int value;
        int hidden; // NOT captured — models out-of-snapshot state
        @Override public String key() { return "leaky"; }
        @Override public Integer capture() { return value; }
        @Override public void restore(Integer snapshot) { value = snapshot; }
    }

    private static final class ScriptedInputs implements InputSource {
        private final int frames;
        ScriptedInputs(int frames) { this.frames = frames; }
        @Override public int frameCount() { return frames; }
        @Override public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "");
        }
    }

    private RewindController build(RewindRegistry registry, EngineStepper stepper, int interval) {
        return new RewindController(registry, new InMemoryKeyframeStore(),
                new ScriptedInputs(1000), stepper, interval);
    }

    @Test
    public void deterministicSubsystemProducesNoDivergence() {
        CapturedCounter counter = new CapturedCounter();
        RewindRegistry registry = new RewindRegistry(null);
        registry.register(counter);
        EngineStepper stepper = in -> counter.value = counter.value * 31 + in.frameIndex();

        List<String> reports = new ArrayList<>();
        RewindDeterminismAuditor auditor = new RewindDeterminismAuditor(reports::add);
        RewindController controller = build(registry, stepper, 4);
        controller.setDeterminismAuditor(auditor);

        for (int f = 1; f <= 12; f++) {           // mimic the live loop:
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));  // host steps engine
            controller.recordExternalStep();       // then records the frame
        }
        assertEquals(0, auditor.divergentSegmentCount());
        assertTrue(reports.isEmpty());
    }

    @Test
    public void hiddenStateProducesDivergenceReport() {
        LeakyCounter leaky = new LeakyCounter();
        RewindRegistry registry = new RewindRegistry(null);
        registry.register(leaky);
        EngineStepper stepper = in -> { leaky.value += leaky.hidden; leaky.hidden++; };

        List<String> reports = new ArrayList<>();
        RewindDeterminismAuditor auditor = new RewindDeterminismAuditor(reports::add);
        RewindController controller = build(registry, stepper, 4);
        controller.setDeterminismAuditor(auditor);

        for (int f = 1; f <= 8; f++) {
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));
            controller.recordExternalStep();
        }
        // 8 frames at interval 4 = 2 segments, but the controller disarms the
        // auditor after the first divergent segment (post-divergence state may
        // be perturbed), so exactly one divergence is recorded.
        assertEquals(1, auditor.divergentSegmentCount(),
                "first divergent segment must be detected, then the auditor disarmed");
        assertTrue(reports.stream().anyMatch(r -> r.contains("leaky")),
                "report should name the divergent subsystem key");
    }
}
```

**Note for the implementer:** check `EngineStepper`'s functional shape and `InMemoryKeyframeStore`'s constructor in `src/main/java/com/openggf/game/rewind/` before compiling; `TestRewindController.java` in the same test package shows the canonical fake wiring — mirror it if the lambda shapes above don't match. The audit hook fires inside `recordExternalStep()` at the keyframe boundary, so the test drives that exact entry point. The auditor replays via the controller's `engineStepper` — the same stepper lambda — which works because all replay state lives in the fake subsystems.

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -q "-Dtest=com.openggf.game.rewind.TestRewindDeterminismAuditor" test
```
Expected: COMPILATION FAILURE (`RewindDeterminismAuditor`, `setDeterminismAuditor` not found).

- [ ] **Step 3: Write `RewindDeterminismAuditor`**

```java
package com.openggf.game.rewind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Debug-mode auditor: after each live keyframe capture, the previous segment
 * is re-simulated from its keyframe and the result diffed against the live
 * keyframe. Any diff means state outside the rewind snapshot set (or a
 * replay-pipeline mismatch) — exactly the class of bug that makes objects
 * "snap" at keyframe boundaries during held rewind.
 *
 * <p>Enabled via {@code debug.rewind.determinismAudit}. Costs one extra full
 * segment simulation per keyframe interval (~2x frame CPU) — debug only.
 *
 * <p><b>Safety caveat:</b> the audit restores the live keyframe after
 * replaying, but {@code RewindRegistry.restore} can only restore registered
 * snapshot entries. If the replay mutated state OUTSIDE the snapshot set —
 * which is precisely what a divergence means — that mutation cannot be
 * undone and live play is perturbed from that point on. The auditor
 * therefore disarms itself after the first divergent segment: the first
 * divergence is the actionable signal, and continuing would both add noise
 * and compound the perturbation.
 */
public final class RewindDeterminismAuditor {

    private static final Logger LOG = Logger.getLogger(RewindDeterminismAuditor.class.getName());
    private static final int MAX_LINES_PER_SEGMENT = 20;

    private final Consumer<String> reporter;
    private int divergentSegments;

    public RewindDeterminismAuditor() {
        this(LOG::warning);
    }

    public RewindDeterminismAuditor(Consumer<String> reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    public int divergentSegmentCount() {
        return divergentSegments;
    }

    /**
     * Diffs the live keyframe against the re-simulated segment end state.
     *
     * @return true when the segment diverged — the caller must disarm the
     *         auditor, since post-divergence engine state may be perturbed
     */
    public boolean report(int fromFrame, int toFrame, CompositeSnapshot live, CompositeSnapshot replayed) {
        List<String> diffs = new ArrayList<>();
        for (String key : live.entries().keySet()) {
            diffs.addAll(RewindSnapshotDiff.diffKey(key, live.get(key), replayed.get(key)));
            if (diffs.size() >= MAX_LINES_PER_SEGMENT) {
                break;
            }
        }
        if (diffs.isEmpty()) {
            return false;
        }
        divergentSegments++;
        reporter.accept("rewind-audit: segment " + fromFrame + "->" + toFrame
                + " DIVERGED (" + diffs.size() + "+ diffs). First: " + diffs.get(0));
        for (int i = 1; i < Math.min(diffs.size(), MAX_LINES_PER_SEGMENT); i++) {
            reporter.accept("rewind-audit:   " + diffs.get(i));
        }
        reporter.accept("rewind-audit: disarming after first divergence — "
                + "replayed out-of-snapshot state cannot be rolled back; "
                + "live state may be perturbed from here");
        return true;
    }
}
```

- [ ] **Step 4: Hook into `RewindController`** — add field + setter, and call from `recordExternalStep()`:

```java
private RewindDeterminismAuditor determinismAuditor; // null = disabled

public void setDeterminismAuditor(RewindDeterminismAuditor auditor) {
    this.determinismAuditor = auditor;
}
```

In `recordExternalStep()`, extend the keyframe branch:

```java
if (currentFrame % keyframeInterval == 0) {
    keyframes.put(currentFrame, registry.capture());
    captureAudioKeyframe(currentFrame);
    if (determinismAuditor != null) {
        auditLastSegment();
    }
}
```

And add the private method (mirrors `seekTo`'s restore/replay/audio bookkeeping; ends by restoring the live keyframe so the engine resumes in the exact pre-audit state):

```java
/**
 * Re-simulates the segment ending at the keyframe just captured and diffs
 * the result against that keyframe, then restores the live keyframe.
 *
 * <p>NOT side-effect-free on divergence: registry.restore can only roll
 * back registered snapshot entries, so out-of-snapshot mutations made by
 * the replay persist. The auditor is disarmed after the first divergent
 * segment for exactly this reason (see RewindDeterminismAuditor docs).
 */
private void auditLastSegment() {
    var prevOpt = keyframes.latestAtOrBefore(currentFrame - 1);
    var liveOpt = keyframes.latestAtOrBefore(currentFrame);
    if (prevOpt.isEmpty() || liveOpt.isEmpty() || liveOpt.get().frame() != currentFrame) {
        return;
    }
    var prev = prevOpt.get();
    CompositeSnapshot live = liveOpt.get().snapshot();
    boolean diverged;
    try (AudioReplayScope ignored = beginAudioReplay(
            currentFrame, currentFrame, AudioReplayReason.SEEK)) {
        registry.restore(prev.snapshot());
        primeStepperAtFrame(prev.frame());
        int pos = prev.frame();
        while (pos < currentFrame) {
            engineStepper.step(inputs.read(pos + 1));
            pos++;
        }
        diverged = determinismAuditor.report(prev.frame(), currentFrame, live, registry.capture());
        registry.restore(live);
        primeStepperAtFrame(currentFrame);
        restoreAudioLogicalState(currentFrame);
        beginAudioFrame(currentFrame);
        afterAudioRestore(AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
    }
    if (diverged) {
        determinismAuditor = null; // disarm: post-divergence state may be perturbed
    }
}
```

- [ ] **Step 5: Run the auditor test**

```bash
mvn -q "-Dtest=com.openggf.game.rewind.TestRewindDeterminismAuditor" test
```
Expected: 2 tests PASS. Also run `mvn -q "-Dtest=com.openggf.game.rewind.TestRewindController" test` — must stay green (auditor is null by default; zero behavior change).

- [ ] **Step 6: Add the config flag.** In `SonicConfiguration`, add constant `LIVE_REWIND_DETERMINISM_AUDIT` near the other rewind/debug constants. In `ConfigCatalog`, add (placed with the `debug.*` entries — normal sections must precede the debug block):

```java
put(LIVE_REWIND_DETERMINISM_AUDIT, of("debug.rewind", "determinismAudit", BOOL,
        "Audit live-rewind determinism: re-simulate each completed keyframe segment "
        + "and log the first state divergence (debug; ~2x frame cost)"));
```

Find the default-value declaration site (`grep -rn "LIVE_REWIND_ENABLED" src/main/java/com/openggf/configuration/`) and register default `false` in the same structure. Run:

```bash
mvn -q "-Dtest=com.openggf.configuration.TestConfigCatalog,com.openggf.configuration.TestSonicConfigurationFileBootstrap" test
```
Expected: PASS. `TestConfigCatalog` only checks metadata/section ordering; `TestSonicConfigurationFileBootstrap` exercises the persisted-file bootstrap — if it asserts the full key/default set, add the new flag with default `false` there. (Memory note: config tests boot `SonicConfigurationService` under `forkCount=4` — any new test touching it must use `@TempDir` + `user.dir` override.)

- [ ] **Step 7: Wire in `LiveRewindManager.ensureInstalled()`**, after `rewindController = gameplayMode.getRewindController();`:

```java
if (rewindController != null
        && config.getBoolean(SonicConfiguration.LIVE_REWIND_DETERMINISM_AUDIT)) {
    rewindController.setDeterminismAuditor(new RewindDeterminismAuditor());
}
```

- [ ] **Step 8: Update CHANGELOG.md and CONFIGURATION.md (both mandatory — this adds a persisted config flag).** CHANGELOG.md:

```markdown
- New debug flag `debug.rewind.determinismAudit`: re-simulates each completed rewind keyframe segment during live play and logs the first state divergence, pinpointing state that is missing from rewind capture. Disarms after the first divergence (replayed out-of-snapshot state cannot be rolled back).
```

CONFIGURATION.md: add `debug.rewind.determinismAudit` to the debug flags section with the same description, noting default `false` and the disarm-after-first-divergence behavior.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java \
        src/main/java/com/openggf/configuration/ConfigCatalog.java \
        src/main/java/com/openggf/game/rewind/RewindDeterminismAuditor.java \
        src/main/java/com/openggf/game/rewind/RewindController.java \
        src/main/java/com/openggf/game/rewind/LiveRewindManager.java \
        src/test/java/com/openggf/game/rewind/TestRewindDeterminismAuditor.java \
        CHANGELOG.md CONFIGURATION.md
git commit -m "feat: add config-gated rewind determinism audit"
```
(Also stage the defaults file found in Step 6 if it's separate.) Trailers: `Changelog: updated`, `Configuration-Docs: updated`, others `n/a`.

---

## Task 5: Schema-driven sidecar for the AIZ zone-event handler

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/schema/ZoneEventSchemaSidecar.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java` (add `@RewindTransient` to structural fields; no logic changes)
- Modify: its superclass chain (`Sonic3kZoneEvents`, and any base with fields) — same annotation treatment
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java` (`captureExtra`/`restoreExtra`: replace the hand-written AIZ block with the sidecar; other zones unchanged for now)
- Test: `src/test/java/com/openggf/game/sonic3k/TestZoneEventRewindSchemaGuard.java`

**Why:** the hand-counted byte layout in `captureExtra()` silently misses any newly added handler field, breaking replay determinism. Schema capture derives the field list from the class itself; the only manual act is marking structural (non-state) fields `@RewindTransient`, and the guard test fails loudly when a field is neither captured nor annotated.

- [ ] **Step 1: Write `ZoneEventSchemaSidecar`**

```java
package com.openggf.game.rewind.schema;

import java.util.Objects;

/**
 * Serializes a zone-event handler's mutable state via the rewind schema
 * system, replacing hand-counted byte layouts in
 * {@code Sonic3kLevelEventManager.captureExtra()}.
 *
 * <p>Handlers must annotate structural fields (services, managers, level
 * references, immutable tables) with {@code @RewindTransient}; every
 * remaining field must have a codec or the schema rejects the class at
 * first capture. Scalar-only by design: a handler whose schema produces
 * opaque values (e.g. String fields) needs explicit codec/policy work
 * before conversion.
 */
public final class ZoneEventSchemaSidecar {

    private ZoneEventSchemaSidecar() {
    }

    public static byte[] capture(Object handler) {
        Objects.requireNonNull(handler, "handler");
        RewindObjectStateBlob blob = CompactFieldCapturer.capture(handler);
        if (blob.opaqueValues().length != 0) {
            throw new IllegalStateException("Zone-event sidecar for "
                    + handler.getClass().getName()
                    + " produced opaque values; scalar-only fields required");
        }
        return blob.scalarData();
    }

    public static void restore(Object handler, byte[] bytes) {
        Objects.requireNonNull(handler, "handler");
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(handler.getClass());
        CompactFieldCapturer.restore(handler,
                new RewindObjectStateBlob(schema.schemaId(), handler.getClass(), bytes, new Object[0]),
                RewindCaptureContext.none());
    }
}
```

(Check `CompactFieldCapturer.restore` overloads — the two-arg `restore(Object, RewindObjectStateBlob)` exists; use it if the context overload isn't public for arbitrary objects.)

- [ ] **Step 2: Discover unsupported fields on the AIZ handler.** Run the jshell probe:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cat > target/Probe.jsh << 'EOF'
import com.openggf.game.rewind.schema.*;
var schema = RewindSchemaRegistry.schemaFor(Class.forName("com.openggf.game.sonic3k.events.Sonic3kAIZEvents"));
System.out.println("UNSUPPORTED fields (need @RewindTransient or a codec):");
schema.unsupportedFields().forEach(f -> System.out.println("  " + f));
System.out.println("CAPTURED fields:");
schema.capturedFields().forEach(f -> System.out.println("  " + f.field().getName() + " : " + f.field().getType().getSimpleName()));
/exit
EOF
mvn -q test-compile && jshell --class-path "target/classes;$(cat target/cp.txt)" target/Probe.jsh
```

(If `unsupportedFields()`/`capturedFields()` accessors differ, read `RewindClassSchema.java` and adjust the probe.)

- [ ] **Step 3: Annotate.** For every UNSUPPORTED field, decide:
  - References to services/managers/registries/`Level`/renderers/art/static-style tables → add `@RewindTransient` (import `com.openggf.game.rewind.RewindTransient`).
  - Genuine mutable gameplay state with no codec → STOP and add a codec/policy entry instead of annotating (annotating real state hides it from rewind — the exact bug class we're killing).
  Re-run the probe until UNSUPPORTED is empty. **Then verify coverage parity:** every field serialized by the old `writeAizState` (`Sonic3kLevelEventManager.java:1449-1488`) MUST appear in the CAPTURED list. **Use the FIELD names declared in `Sonic3kAIZEvents`, not the getter names the sidecar calls** — the accessors rename some fields (e.g. `isPostFireHazeActiveRaw()` reads field `postFireHazeActive`). Build the list by mapping each `writeAizState` getter to its backing field declaration: `paletteSwapped` (line 219), `fireMinXLockReached` (221), `levelRepeatOffset` (265), `act2TransitionRequested` (292), `fireTransitionMutationRequested` (294), `postFireHazeActive` (296), `fireOverlayTilesLoaded` (298), `fireBgCopyFixed` (300), `fireRiseSpeed` (302), `fireWavePhase` (304), `fireTransitionFrames` (306), `firePhaseFrames` (308), `act2WaitFireDrawActive` (310), `fireSequencePhase` (312), `fireOverlayTileCount` (377), plus the remaining entries of the 20-boolean/15-int block.

- [ ] **Step 4: Write the failing guard test**

```java
package com.openggf.game.sonic3k;

import com.openggf.game.rewind.schema.RewindClassSchema;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards schema-converted zone-event handlers: every field is either
 * captured or explicitly @RewindTransient, and the fields the legacy
 * hand-written sidecar serialized remain covered.
 */
public class TestZoneEventRewindSchemaGuard {

    private static final List<Class<?>> CONVERTED_HANDLERS = List.of(Sonic3kAIZEvents.class);

    /**
     * FIELD names (not getter names) the legacy writeAizState byte layout
     * serialized — see Task 5 Step 3 for the getter-to-field mapping.
     */
    private static final Set<String> AIZ_LEGACY_FIELDS = Set.of(
            "paletteSwapped", "fireMinXLockReached", "levelRepeatOffset",
            "act2TransitionRequested", "fireTransitionMutationRequested",
            "postFireHazeActive", "fireOverlayTilesLoaded", "act2WaitFireDrawActive",
            "fireBgCopyFixed", "fireRiseSpeed", "fireWavePhase",
            "fireTransitionFrames", "firePhaseFrames", "fireOverlayTileCount",
            "fireSequencePhase");
            // Extend with the full list extracted in Task 5 Step 3.

    @Test
    public void convertedHandlersHaveNoUnsupportedFields() {
        for (Class<?> handler : CONVERTED_HANDLERS) {
            RewindClassSchema schema = RewindSchemaRegistry.schemaFor(handler);
            assertTrue(schema.unsupportedFields().isEmpty(),
                    handler.getSimpleName() + " has unsupported rewind fields: "
                            + schema.unsupportedFields());
        }
    }

    @Test
    public void aizSchemaCoversAllLegacySidecarFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kAIZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = AIZ_LEGACY_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy sidecar fields: " + missing);
    }
}
```

(Adjust accessor names against `RewindClassSchema.java`; complete `AIZ_LEGACY_FIELDS` from the actual `writeAizState` body — every getter it serializes maps to a field name.)

- [ ] **Step 5: Run guard test until green**

```bash
mvn -q "-Dtest=com.openggf.game.sonic3k.TestZoneEventRewindSchemaGuard" test
```
Iterate on annotations until PASS.

- [ ] **Step 6: Swap the AIZ block in `captureExtra`/`restoreExtra`.** In `Sonic3kLevelEventManager.captureExtra()`, replace the fixed-size AIZ section with a length-prefixed sidecar. Since the schema payload size is no longer a compile-time constant, switch the AIZ section to: `1 byte presence flag + 4-byte int length + payload`:

```java
// AIZ — schema-driven sidecar (replaces writeAizState fixed layout)
byte[] aizBytes = aizEvents != null ? ZoneEventSchemaSidecar.capture(aizEvents) : null;
```
…include `(aizBytes != null ? 5 + aizBytes.length : 1)` in the `size` computation instead of `1 + aizSize`, then:
```java
if (aizBytes != null) {
    buf.put((byte) 1);
    buf.putInt(aizBytes.length);
    buf.put(aizBytes);
} else {
    buf.put((byte) 0);
}
```
Mirror in `restoreExtra` (read flag, read length, read bytes, `ZoneEventSchemaSidecar.restore(aizEvents, bytes)`), and update the layout comment block at the top of `captureExtra`. Delete `writeAizState`/`readAizState` and any now-unused getters/setters on `Sonic3kAIZEvents` that existed solely for the sidecar (keep ones used by tests/other code — grep each before deleting).

- [ ] **Step 7: Run the S3K event + rewind test surface**

```bash
mvn -q "-Dtest=com.openggf.game.sonic3k.*" test
mvn -q "-Dtest=com.openggf.game.rewind.TestRewindAcrossActBoundary" test
```
Expected: PASS. Watch for `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading` (must stay green per CLAUDE.md).

- [ ] **Step 8: Update CHANGELOG.md**

```markdown
- S3K AIZ zone-event rewind state is now schema-captured (auto-derived from handler fields) instead of a hand-counted byte layout; a guard test fails when a handler field is neither captured nor explicitly rewind-transient.
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/schema/ZoneEventSchemaSidecar.java \
        src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java \
        src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java \
        src/test/java/com/openggf/game/sonic3k/TestZoneEventRewindSchemaGuard.java \
        CHANGELOG.md
git commit -m "fix: schema-driven rewind sidecar for AIZ zone events"
```
(Also stage any superclass files annotated in Step 3.) Trailers: `Changelog: updated`, others `n/a`.

---

## Task 6: Convert remaining S3K zone handlers (HCZ, CNZ, MGZ, MHZ, ICZ)

Repeat Task 5 Steps 2–9 per handler, one commit each, in this order: HCZ, CNZ, MGZ, MHZ, ICZ. For each:
1. jshell probe → annotate `@RewindTransient` until UNSUPPORTED empty.
2. Add the class to `CONVERTED_HANDLERS` and a `<ZONE>_LEGACY_FIELDS` set + parity test mirroring `aizSchemaCoversAllLegacySidecarFields` (field names from the corresponding `write<Zone>State` body; MHZ/ICZ use `rewindStateBytes()`-driven blocks — read those methods for the field lists).
3. Swap the manager block to flag + length + sidecar bytes; delete the legacy write/read pair.
4. Run `mvn -q "-Dtest=com.openggf.game.sonic3k.*" test`.
5. Commit `fix: schema-driven rewind sidecar for <ZONE> zone events` with `Changelog: updated` (one cumulative CHANGELOG line covering remaining zones is fine on the first of these commits; later commits may justify `Changelog: n/a: covered by <sha> changelog entry for schema sidecar conversion`).

ICZ note (from memory): ICZ registry tests flake under `forkCount=4` if a prior fork-mate leaks an SKL-zone session — if `TestSonic3kICZ*` fails unexpectedly, re-run it isolated before assuming the conversion broke it.

---

## Task 7: Fix S3K elemental shield graphics corruption after rewind restore

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` (`refreshPowerUpObjectsAfterRewindRestore()`, lines 1190-1211)
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/FireShieldObjectInstance.java`, `LightningShieldObjectInstance.java`, `BubbleShieldObjectInstance.java` (shared art-refresh hook)
- Test: `src/test/java/com/openggf/sprites/playable/TestShieldRewindRestore.java`

**Verified mechanism:** the post-restore callback `refreshPowerUpObjectsAfterRewindRestore()` unconditionally destroys the shield object — whose rewind state (animation frame/timers) was just restored by `ObjectManager` — and respawns a fresh one with constructor defaults. Separately, the shield DPLC renderer's `lastFrame` is rewind-transient (`RewindPolicyRegistry`) and `DynamicPatternBank`'s CPU-side `patterns[]` is never snapshotted, so after restore the bank can hold stale tiles for a frame index the DPLC won't re-upload (empty-request frames reuse "previous" tiles). Net effect: animation desync + garbage tiles.

**Important:** Task 4's determinism audit will NOT flag this — pattern banks and renderer caches are presentation state outside the snapshot set, and the shield's *logical* state replays fine. This task is the explicit fix.

- [ ] **Step 1: Determine the restore identity model.** Read `ObjectManagerSnapshot` restore (`src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java`) and the `ObjectManager` restore path to answer one question: after a composite restore, is the shield object (a) the same live instance with state restored in place, or (b) a recreated instance (making `player.shieldObject` a stale reference)? This decides Step 3's branch.

- [ ] **Step 2: Write the failing test.** Mirror the `TestSpindashGating` TestableSprite pattern (inner subclass, no ROM/OpenGL). Core assertion:

```java
package com.openggf.sprites.playable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards shield continuity across rewind restore: the post-restore refresh
 * must NOT discard a live, type-matching shield object (its rewind state was
 * just restored — respawning resets animation state and desyncs shield art),
 * and must request a full DPLC/art re-upload on whatever object it keeps.
 */
public class TestShieldRewindRestore {

    @Test
    public void refreshKeepsRestoredShieldObjectWhenTypeMatches() {
        // TestableSprite with shield=true, shieldType set, shieldObject = recording fake.
        // Call refreshPowerUpObjectsAfterRewindRestore().
        // assertSame(fake, sprite.getShieldObject()) — object retained, not respawned;
        // assertTrue(fake.artRefreshRequested) — DPLC cache invalidated for re-upload.
    }

    @Test
    public void refreshRespawnsWhenShieldObjectMissingOrDestroyed() {
        // shieldObject = null (or destroyed fake) -> spawner invoked exactly once,
        // and the spawned object gets the art-refresh request too.
    }
}
```

Fill in the fake/spawner wiring against the real field and spawner types on `AbstractPlayableSprite` (read lines 1150-1215 first; the shield object field type and `powerUpSpawner.spawnShield(this, shieldType)` shape are there). If Step 1 found model (b) — recreated instances — the first test instead asserts the callback *relinks* `shieldObject` to the restored instance found via the ObjectManager rather than respawning; keep the art-refresh assertion either way.

- [ ] **Step 3: Implement.** Two pieces:

(1) In `refreshPowerUpObjectsAfterRewindRestore()`, stop unconditionally destroying. Model (a) — in-place restore:

```java
if (shieldObject != null && !shieldObject.isDestroyed()
        && shieldObjectMatches(shieldObject, shieldType)) {
    shieldObject.refreshArtAfterRewindRestore();
    if (invincibleFrames > 0) {
        shieldObject.setVisible(false);
    }
    return;
}
```
…falling through to the existing destroy+respawn only when the object is missing, destroyed, or the wrong type — and call `refreshArtAfterRewindRestore()` on the respawned object too. Model (b): replace the retention check with an ObjectManager lookup for the restored shield instance of the matching class, relink `shieldObject`, destroy true duplicates only.

(2) Add `refreshArtAfterRewindRestore()` to the shield instances (via their common base/interface — check what type `shieldObject` is declared as): it must call `dplcRenderer.invalidateDplcCache()` and clear any "art loaded" latch so `ensureShieldArtLoaded()` re-binds, guaranteeing the next `drawFrame` runs the `forceInitialDplc()` path and re-uploads pattern bytes into the `DynamicPatternBank` instead of trusting stale tiles.

- [ ] **Step 4: Run the test + the shield/object test surface**

```bash
mvn -q "-Dtest=com.openggf.sprites.playable.TestShieldRewindRestore" test
mvn -q "-Dtest=com.openggf.game.sonic3k.objects.*Shield*" test
```
Expected: PASS.

- [ ] **Step 5: Manual verification (ROM + display).** S3K AIZ/HCZ: grab a fire shield, hold rewind across the pickup and through several shield animation cycles, release, resume. Expected: shield art stays correct throughout — no garbage tiles, no frozen/reset animation.

- [ ] **Step 6: Update CHANGELOG.md**

```markdown
- Fixed S3K elemental shield graphics corrupting after a rewind: the post-restore refresh no longer discards the restored shield object's animation state, and shield DPLC art is force re-uploaded after restore.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java \
        src/main/java/com/openggf/game/sonic3k/objects/FireShieldObjectInstance.java \
        src/main/java/com/openggf/game/sonic3k/objects/LightningShieldObjectInstance.java \
        src/main/java/com/openggf/game/sonic3k/objects/BubbleShieldObjectInstance.java \
        src/test/java/com/openggf/sprites/playable/TestShieldRewindRestore.java \
        CHANGELOG.md
git commit -m "fix: preserve shield state and force art re-upload across rewind restore"
```
(Stage the shared base/interface file too if Step 3 added the hook there.) Trailers: `Changelog: updated`, others `n/a`.

---

## Task 8: End-to-end verification

- [ ] **Step 1: Full build + test sweep in the worktree**

```bash
mvn -q test 2>&1 | tail -40
```
Expected: BUILD SUCCESS. Known acceptable flakes (from memory): lwjgl/glfw `UnsatisfiedLinkError` noise and `TestBundledConfigResource` — re-run those isolated before treating as regressions.

- [ ] **Step 2: Manual palette-rewind verification (requires ROM + display).** Enable `rewind.liveEnabled: true` in `config.yaml`, start S3K AIZ1, play until the intro fire sequence palette change, hold the rewind key back across it. Expected: palette colors visually revert with the rewind instead of staying mutated. If running headless-only, substitute: a focused test that drives `RewindController` through a scripted stepper which mutates a `Palette` registered via the Task 2 adapter, asserting `stepBackward()` restores pre-mutation colors.

- [ ] **Step 3: Determinism audit smoke.** Set `debug.rewind.determinismAudit: true`, play ~30 seconds of AIZ1 (or run an existing ROM-gated rewind integration test with the flag forced on), and collect the `rewind-audit:` log lines. **These findings are the input for the next round of capture fixes (vine/object snapping)** — file them in `docs/` or the issue tracker; do not fix them ad hoc in this branch.

- [ ] **Step 4: Push and open PR into develop**

```bash
git push -u origin bugfix/ai-rewind-palette-and-audit
gh pr create --base develop --title "Rewind: palette color capture + determinism audit + schema event sidecars" --body "..."
```
PR body: summarize the three changes, link the audit findings, end with the standard generated-with footer.

---

## Self-review notes

- **Spec coverage:** Option A = Tasks 1–2; Option C(audit) = Tasks 3–4; Option C(sidecars+guard) = Tasks 5–6; shield rewind corruption = Task 7; verification = Task 8. Option D intentionally out of scope (future feature; when built, gate on a semantic "scripted sequence" predicate, never zone id, per CLAUDE.md trace-fix rules).
- **Known unknowns flagged inline** (exact `GraphicsManager` underwater-cache signature, `EngineStepper` lambda shape, `RewindClassSchema` accessor names, config default-value site) — each has a concrete look-up instruction and a canonical usage site to mirror.
- **Type consistency:** `PaletteColorStateAdapter(Supplier<Palette[]>, Supplier<Palette[]>, Supplier<GraphicsManager>)` used identically in Tasks 1–2; `setDeterminismAuditor(RewindDeterminismAuditor)` consistent across Task 4 steps; `ZoneEventSchemaSidecar.capture/restore` consistent across Tasks 5–6.
