# Special Stage Rewind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add held-key live rewind inside supported `SPECIAL_STAGE` sessions, with Sonic 1 special stage as the first rewind-capable provider.

**Architecture:** Reuse the existing keyframe-plus-input re-simulation engine. Add a special-stage stepper and mode/provider-aware install path, register a single `"special-stage-runtime"` adapter while an eligible special stage is active, and keep unsupported S2/S3K special stages inert via `SpecialStageProvider.supportsRewind()`.

**Tech Stack:** Java 21, Maven, JUnit 5/Jupiter, existing OpenGGF rewind framework (`RewindController`, `LiveRewindManager`, `RewindRegistry`, `GameplayModeContext`).

---

## File Structure

- Modify `src/main/java/com/openggf/debug/playback/Bk2FrameInput.java`: document that Start booleans carry current held state despite historical accessor names.
- Modify `src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java`: record `startHeld()` instead of `startPressed()`.
- Create `src/main/java/com/openggf/game/SpecialStageInputMapper.java`: one shared logical-input to Mega Drive special-stage bitmask mapper.
- Modify `src/main/java/com/openggf/GameLoop.java`: use mapper, wire special-stage rewind mode checks, register/deregister adapter, record special-stage frames.
- Modify `src/main/java/com/openggf/game/SpecialStageProvider.java`: add `supportsRewind()` default false, the generic special-stage rewind key, and a default-empty provider-owned adapter factory.
- Modify `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java`: return true and supply the S1 adapter from the provider-owned factory.
- Modify `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`: add snapshot capture/restore and render re-establishment.
- Create `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageSnapshot.java`: opaque cloned snapshot payload.
- Create `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java`: replay one recorded input row through active special-stage provider.
- Create `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageRewindAdapter.java`: `RewindSnapshottable` wrapper keyed as `"special-stage-runtime"`, colocated with the S1 manager so snapshot methods stay package-private.
- Modify `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`: add mode/provider suppliers, stepper-kind tracking, special-stage capability checks.
- Modify `src/main/java/com/openggf/game/session/GameplayModeContext.java`: add special-stage adapter registration pair that uses only provider capability and the provider-owned optional adapter.
- Add tests under `src/test/java/com/openggf/game`, `src/test/java/com/openggf/game/rewind`, and `src/test/java/com/openggf/game/sonic1/specialstage`.
- Modify `CHANGELOG.md`, `docs/status/known-discrepancies.md`, and `README.md`.

---

## Prerequisite: Branch Setup

Before implementation commits, start from `develop` on the feature branch named by the spec:

```powershell
git switch develop
git pull --ff-only
git switch -c feature/ai-special-stage-rewind
```

If `feature/ai-special-stage-rewind` already exists locally for this session, use `git switch feature/ai-special-stage-rewind` instead of creating a second branch. Keep every commit in this plan on that same branch.

---

### Task 1: Fix Start Held Semantics For Live Rewind Rows

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java`
- Modify: `src/main/java/com/openggf/debug/playback/Bk2FrameInput.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindInputSourceStartHeld.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/rewind/TestLiveRewindInputSourceStartHeld.java`:

```java
package com.openggf.game.rewind;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestLiveRewindInputSourceStartHeld {

    @Test
    void heldStartReplaysAsHeldAfterThePressEdgeFrame() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.handleKeyEvent(config.getInt(SonicConfiguration.START), GLFW_PRESS);

        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        input.update();

        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);

        Bk2FrameInput first = source.read(1);
        Bk2FrameInput second = source.read(2);

        var firstReplay = RecordedInputSnapshots.fromBk2(first, source.read(0));
        var secondReplay = RecordedInputSnapshots.fromBk2(second, first);

        assertTrue(firstReplay.player1().startHeld());
        assertTrue(firstReplay.player1().startPressed());
        assertTrue(secondReplay.player1().startHeld(),
                "live rewind input rows must retain Start held state after the edge frame");
        assertFalse(secondReplay.player1().startPressed(),
                "pressed edge should still be derived from current held vs previous held");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindInputSourceStartHeld" test
```

Expected: FAIL on `secondReplay.player1().startHeld()` because current `LiveRewindInputSource.appendFrame()` records `startPressed()`.

- [ ] **Step 3: Implement the semantic fix**

Change `LiveRewindInputSource.appendFrame(...)` to store held Start:

```java
frames.add(new Bk2FrameInput(
        frameIndex,
        p1.heldMask(),
        p1.actionHeldMask(),
        p1.startHeld(),
        p2.heldMask(),
        p2.actionHeldMask(),
        p2.startHeld(),
        input.isKeyPressed(config.getInt(SonicConfiguration.DEBUG_MODE_KEY)),
        input.isShiftDown(),
        input.isControlDown(),
        "live:" + frameIndex));
```

Update `Bk2FrameInput` Javadoc without renaming record components:

```java
 * @param p1StartPressed historical name; stores whether P1 Start is held on this frame
 * @param p2StartPressed historical name; stores whether P2 Start is held on this frame
```

- [ ] **Step 4: Run targeted tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindInputSourceStartHeld,com.openggf.game.rewind.TestLiveRewindLogicalInput,com.openggf.game.rewind.TestLiveRewindInputSource" test
```

Expected: PASS. If old tests describe "start press edge", update assertion text only when the asserted behavior still matches held-state storage.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java src/main/java/com/openggf/debug/playback/Bk2FrameInput.java src/test/java/com/openggf/game/rewind/TestLiveRewindInputSourceStartHeld.java
git commit -m "fix: preserve Start held state in live rewind input rows" -m "Changelog: n/a: input-row semantic prerequisite only`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 2: Add Special Stage Rewind Capability Gate

**Files:**
- Modify: `src/main/java/com/openggf/game/SpecialStageProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java`
- Test: `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`

- [ ] **Step 1: Write the failing capability test**

Create `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`:

```java
package com.openggf.game;

import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSpecialStageRewindCapability {

    @Test
    void defaultAndNoOpProvidersDoNotSupportRewindOrProvideAdapters() {
        MinimalSpecialStageProvider defaultProvider = new MinimalSpecialStageProvider();
        assertFalse(defaultProvider.supportsRewind());
        assertTrue(defaultProvider.rewindAdapter().isEmpty());
        assertFalse(NoOpSpecialStageProvider.INSTANCE.supportsRewind());
        assertTrue(NoOpSpecialStageProvider.INSTANCE.rewindAdapter().isEmpty());
        assertEquals("special-stage-runtime", SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY);
    }

    @Test
    void onlySonic1ProviderSupportsRewindInThisRollout() {
        assertTrue(new Sonic1SpecialStageProvider().supportsRewind());
        assertFalse(new Sonic2SpecialStageProvider().supportsRewind());
        assertFalse(new Sonic3kSpecialStageProvider().supportsRewind());
    }

    private static final class MinimalSpecialStageProvider implements SpecialStageProvider {
        @Override public void initialize() throws IOException { }
        @Override public void update() { }
        @Override public void draw() { }
        @Override public void handleInput(int heldButtons, int pressedButtons) { }
        @Override public boolean isFinished() { return false; }
        @Override public void reset() { }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean hasSpecialStages() { return true; }
        @Override public SpecialStageAccessType getAccessType() { return SpecialStageAccessType.GIANT_RING; }
        @Override public void initializeStage(int stageIndex) throws IOException { }
        @Override public int getCurrentStage() { return 0; }
        @Override public boolean isEmeraldCollected() { return false; }
        @Override public int getEmeraldIndex() { return -1; }
        @Override public int getRingsCollected() { return 0; }
        @Override public void setEmeraldCollected(boolean collected) { }
        @Override public boolean isSpriteDebugMode() { return false; }
        @Override public void toggleSpriteDebugMode() { }
        @Override public void cyclePlaneDebugMode() { }
        @Override public SpecialStageDebugProvider getDebugProvider() { return null; }
        @Override public boolean isAlignmentTestMode() { return false; }
        @Override public void toggleAlignmentTestMode() { }
        @Override public void adjustAlignmentOffset(int delta) { }
        @Override public void adjustAlignmentSpeed(double delta) { }
        @Override public void toggleAlignmentStepMode() { }
        @Override public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) { }
        @Override public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) { }
        @Override public void setLagCompensation(double factor) { }
        @Override public ResultsScreen createResultsScreen(
                int ringsCollected, boolean gotEmerald, int stageIndex, int totalEmeraldCount) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability" test
```

Expected: FAIL because `supportsRewind()`, `SPECIAL_STAGE_REWIND_KEY`, and `rewindAdapter()` do not exist yet.

- [ ] **Step 3: Add the capability and adapter contract**

In `SpecialStageProvider` add imports and defaults:

```java
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Optional;

String SPECIAL_STAGE_REWIND_KEY = "special-stage-runtime";

default boolean supportsRewind() {
    return false;
}

default Optional<RewindSnapshottable<?>> rewindAdapter() {
    return Optional.empty();
}
```

In `Sonic1SpecialStageProvider` add:

```java
@Override
public boolean supportsRewind() {
    return true;
}
```

- [ ] **Step 4: Run the test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/game/SpecialStageProvider.java src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java
git commit -m "feat: gate rewind-capable special stages" -m "Changelog: n/a: capability gate only, surfaced by later feature entry`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 3: Extract Special Stage Input Mapper

**Files:**
- Create: `src/main/java/com/openggf/game/SpecialStageInputMapper.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Test: `src/test/java/com/openggf/game/TestSpecialStageInputMapper.java`

- [ ] **Step 1: Write mapper tests**

Create `src/test/java/com/openggf/game/TestSpecialStageInputMapper.java`:

```java
package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSpecialStageInputMapper {

    @Test
    void mapsHeldAndPressedBitsFromLogicalSnapshot() {
        LogicalInputSnapshot logical = LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_UP | AbstractPlayableSprite.INPUT_LEFT,
                        AbstractPlayableSprite.INPUT_LEFT,
                        InputActionMasks.ACTION_A | InputActionMasks.ACTION_C,
                        InputActionMasks.ACTION_C,
                        true,
                        true),
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_DOWN | AbstractPlayableSprite.INPUT_RIGHT,
                        0,
                        InputActionMasks.ACTION_B,
                        0,
                        true,
                        false));

        SpecialStageInputMapper.MappedInput mapped = SpecialStageInputMapper.map(logical);

        assertEquals(0x01 | 0x04 | 0x40 | 0x20 | 0x80, mapped.p1Held());
        assertEquals(0x04 | 0x20 | 0x80, mapped.p1Pressed());
        assertEquals(0x02 | 0x08 | 0x10 | 0x80, mapped.p2Held());
        assertEquals(mapped.p2Held(), mapped.p2Logical());
    }

    @Test
    void replayedRowsProduceSameStartHeldAndPressedSemantics() {
        Bk2FrameInput previous = new Bk2FrameInput(0, 0, 0, false, 0, 0, false, "previous");
        Bk2FrameInput current = new Bk2FrameInput(
                1,
                AbstractPlayableSprite.INPUT_RIGHT,
                InputActionMasks.ACTION_A,
                true,
                0,
                0,
                false,
                "current");

        SpecialStageInputMapper.MappedInput mapped =
                SpecialStageInputMapper.map(RecordedInputSnapshots.fromBk2(current, previous));

        assertEquals(0x08 | 0x40 | 0x80, mapped.p1Held());
        assertEquals(0x08 | 0x40 | 0x80, mapped.p1Pressed());
    }
}
```

- [ ] **Step 2: Run failing mapper tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageInputMapper" test
```

Expected: FAIL because `SpecialStageInputMapper` does not exist.

- [ ] **Step 3: Create the mapper**

Create `src/main/java/com/openggf/game/SpecialStageInputMapper.java`:

```java
package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Objects;

public final class SpecialStageInputMapper {

    private SpecialStageInputMapper() {
    }

    public record MappedInput(int p1Held, int p1Pressed, int p2Held, int p2Logical) {
    }

    public static MappedInput map(LogicalInputSnapshot logical) {
        Objects.requireNonNull(logical, "logical");
        PlayerInputState p1 = logical.player1();
        PlayerInputState p2 = logical.player2();

        int p1Held = directionBits(p1.heldMask())
                | InputActionMasks.toMegaDriveButtonBits(p1.actionHeldMask())
                | (p1.startHeld() ? 0x80 : 0);
        int p1Pressed = directionBits(p1.pressedMask())
                | InputActionMasks.toMegaDriveButtonBits(p1.actionPressedMask())
                | (p1.startPressed() ? 0x80 : 0);
        int p2Held = directionBits(p2.heldMask())
                | InputActionMasks.toMegaDriveButtonBits(p2.actionHeldMask())
                | (p2.startHeld() ? 0x80 : 0);

        return new MappedInput(p1Held, p1Pressed, p2Held, p2Held);
    }

    private static int directionBits(int logicalMask) {
        int bits = 0;
        if ((logicalMask & AbstractPlayableSprite.INPUT_UP) != 0) bits |= 0x01;
        if ((logicalMask & AbstractPlayableSprite.INPUT_DOWN) != 0) bits |= 0x02;
        if ((logicalMask & AbstractPlayableSprite.INPUT_LEFT) != 0) bits |= 0x04;
        if ((logicalMask & AbstractPlayableSprite.INPUT_RIGHT) != 0) bits |= 0x08;
        return bits;
    }
}
```

- [ ] **Step 4: Refactor `GameLoop.updateSpecialStageInput()`**

Replace the inline held/pressed construction with:

```java
SpecialStageInputMapper.MappedInput mapped =
        SpecialStageInputMapper.map(inputHandler.logical());
ssProvider.handleInput(mapped.p1Held(), mapped.p1Pressed());
ssProvider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
```

Remove `GameLoop.directionBits(...)` if no other code uses it, and add:

```java
import com.openggf.game.SpecialStageInputMapper;
```

- [ ] **Step 5: Run mapper and existing special-stage gate tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageInputMapper,com.openggf.TestGameLoopSpecialStageRewindGate" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/game/SpecialStageInputMapper.java src/main/java/com/openggf/GameLoop.java src/test/java/com/openggf/game/TestSpecialStageInputMapper.java
git commit -m "refactor: extract special stage input mapping" -m "Changelog: n/a: behavior-preserving input extraction`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 4: Add SpecialStageStepper

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java`
- Test: `src/test/java/com/openggf/game/rewind/TestSpecialStageStepperReplay.java`

- [ ] **Step 1: Write stepper replay tests**

Create a package-private fake provider in `TestSpecialStageStepperReplay` that records call order:

```java
package com.openggf.game.rewind;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSpecialStageStepperReplay {

    @Test
    void stepMapsInputAndUpdatesProviderInOrder() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        RecordingProvider provider = new RecordingProvider();
        SpecialStageStepper stepper = new SpecialStageStepper(inputs, () -> input, () -> provider);

        stepper.step(new Bk2FrameInput(1, 0x0C, 0x03, true, 0x01, 0x04, false, "row"));

        assertEquals(List.of("handleInput:220:220", "handlePlayer2Input:33:33", "update"),
                provider.calls);
        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void clearsLogicalOverrideWhenProviderThrows() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        RecordingProvider provider = new RecordingProvider();
        provider.throwOnUpdate = true;
        SpecialStageStepper stepper = new SpecialStageStepper(inputs, () -> input, () -> provider);

        assertThrows(IllegalStateException.class,
                () -> stepper.step(new Bk2FrameInput(1, 0, 0, false, 0, 0, false, "row")));
        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void providerIsResolvedEveryStep() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        RecordingProvider first = new RecordingProvider();
        RecordingProvider second = new RecordingProvider();
        AtomicReference<SpecialStageProvider> active = new AtomicReference<>(first);
        SpecialStageStepper stepper = new SpecialStageStepper(inputs, () -> input, active::get);

        stepper.step(new Bk2FrameInput(1, 0, 0, false, 0, 0, false, "one"));
        active.set(second);
        stepper.step(new Bk2FrameInput(1, 0, 0, false, 0, 0, false, "two"));

        assertEquals(List.of("handleInput:0:0", "handlePlayer2Input:0:0", "update"), first.calls);
        assertEquals(List.of("handleInput:0:0", "handlePlayer2Input:0:0", "update"), second.calls);
    }

    @Test
    void finishingDuringReplayDoesNotDispatchResults() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        RecordingProvider provider = new RecordingProvider();
        provider.finishOnUpdate = true;
        SpecialStageStepper stepper = new SpecialStageStepper(inputs, () -> input, () -> provider);

        stepper.step(new Bk2FrameInput(1, 0, 0, false, 0, 0, false, "finish"));

        assertEquals(List.of("handleInput:0:0", "handlePlayer2Input:0:0", "update"), provider.calls);
        assertEquals(0, provider.resultsCreated);
    }

    private static final class RecordingProvider extends MinimalSpecialStageProvider {
        final List<String> calls = new ArrayList<>();
        boolean throwOnUpdate;
        boolean finishOnUpdate;
        boolean finished;
        int resultsCreated;

        @Override public void handleInput(int heldButtons, int pressedButtons) {
            calls.add("handleInput:" + heldButtons + ":" + pressedButtons);
        }

        @Override public void handlePlayer2Input(int heldButtons, int logicalButtons) {
            calls.add("handlePlayer2Input:" + heldButtons + ":" + logicalButtons);
        }

        @Override public void update() {
            calls.add("update");
            if (throwOnUpdate) {
                throw new IllegalStateException("boom");
            }
            finished = finishOnUpdate;
        }

        @Override public boolean isFinished() {
            return finished;
        }

        @Override public ResultsScreen createResultsScreen(
                int ringsCollected, boolean gotEmerald, int stageIndex, int totalEmeraldCount) {
            resultsCreated++;
            return null;
        }
    }

    private static class MinimalSpecialStageProvider implements SpecialStageProvider {
        @Override public void initialize() throws IOException { }
        @Override public void update() { }
        @Override public void draw() { }
        @Override public void handleInput(int heldButtons, int pressedButtons) { }
        @Override public boolean isFinished() { return false; }
        @Override public void reset() { }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean hasSpecialStages() { return true; }
        @Override public SpecialStageAccessType getAccessType() { return SpecialStageAccessType.GIANT_RING; }
        @Override public void initializeStage(int stageIndex) throws IOException { }
        @Override public int getCurrentStage() { return 0; }
        @Override public boolean isEmeraldCollected() { return false; }
        @Override public int getEmeraldIndex() { return -1; }
        @Override public int getRingsCollected() { return 0; }
        @Override public void setEmeraldCollected(boolean collected) { }
        @Override public boolean isSpriteDebugMode() { return false; }
        @Override public void toggleSpriteDebugMode() { }
        @Override public void cyclePlaneDebugMode() { }
        @Override public SpecialStageDebugProvider getDebugProvider() { return null; }
        @Override public boolean isAlignmentTestMode() { return false; }
        @Override public void toggleAlignmentTestMode() { }
        @Override public void adjustAlignmentOffset(int delta) { }
        @Override public void adjustAlignmentSpeed(double delta) { }
        @Override public void toggleAlignmentStepMode() { }
        @Override public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) { }
        @Override public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) { }
        @Override public void setLagCompensation(double factor) { }
        @Override public ResultsScreen createResultsScreen(
                int ringsCollected, boolean gotEmerald, int stageIndex, int totalEmeraldCount) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Run failing tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSpecialStageStepperReplay" test
```

Expected: FAIL because `SpecialStageStepper` does not exist.

- [ ] **Step 3: Implement the stepper**

Create `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java`:

```java
package com.openggf.game.rewind;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.SpecialStageInputMapper;
import com.openggf.game.SpecialStageProvider;

import java.util.Objects;
import java.util.function.Supplier;

final class SpecialStageStepper implements RewindSeekAwareEngineStepper {

    private final LiveRewindInputSource inputs;
    private final Supplier<InputHandler> inputHandlerSupplier;
    private final Supplier<SpecialStageProvider> providerSupplier;

    SpecialStageStepper(LiveRewindInputSource inputs,
                        Supplier<InputHandler> inputHandlerSupplier,
                        Supplier<SpecialStageProvider> providerSupplier) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.inputHandlerSupplier = Objects.requireNonNull(inputHandlerSupplier, "inputHandlerSupplier");
        this.providerSupplier = Objects.requireNonNull(providerSupplier, "providerSupplier");
    }

    @Override
    public void step(Bk2FrameInput input) {
        InputHandler liveInput = inputHandlerSupplier.get();
        SpecialStageProvider provider = providerSupplier.get();
        if (liveInput == null || provider == null) {
            return;
        }
        Bk2FrameInput previous = inputs.read(Math.max(inputs.earliestFrame(), input.frameIndex() - 1));
        liveInput.setLogicalOverride(RecordedInputSnapshots.fromBk2(input, previous));
        try {
            SpecialStageInputMapper.MappedInput mapped =
                    SpecialStageInputMapper.map(liveInput.logical());
            provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
            provider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
            provider.update();
        } finally {
            liveInput.clearLogicalOverride();
        }
    }

    @Override
    public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSpecialStageStepperReplay" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/game/rewind/SpecialStageStepper.java src/test/java/com/openggf/game/rewind/TestSpecialStageStepperReplay.java
git commit -m "feat: add special stage rewind stepper" -m "Changelog: n/a: internal stepper for special-stage rewind feature`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 5: Teach LiveRewindManager About Stepper Kind And Provider Capability

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerSpecialStageMode.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerBonusStageMode.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java`
- Test: `src/test/java/com/openggf/game/rewind/TestHeldRewindAudioRestoreDeferral.java`
- Test: `src/test/java/com/openggf/TestGameLoopSpecialStageRewindGate.java`

- [ ] **Step 1: Write manager tests**

Create `TestLiveRewindManagerSpecialStageMode` with cases for:

```java
@Test
void specialStageModeIsRejectedWhenProviderDoesNotSupportRewind() {
    var manager = new LiveRewindManager(config, () -> GameMode.SPECIAL_STAGE,
            () -> NoOpSpecialStageProvider.INSTANCE);
    manager.recordExternalFrame(GameMode.SPECIAL_STAGE, false, new InputHandler());
    assertNull(SessionManager.getCurrentGameplayMode().getRewindController());
}

@Test
void sameContextChangedStepperKindReinstallsController() {
    AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.LEVEL);
    AtomicReference<SpecialStageProvider> provider = new AtomicReference<>(new RewindableProvider());
    var manager = new LiveRewindManager(config, mode::get, provider::get);
    InputHandler input = new InputHandler();

    manager.recordExternalFrame(GameMode.LEVEL, false, input);
    RewindController levelController = SessionManager.getCurrentGameplayMode().getRewindController();
    assertNotNull(levelController);

    mode.set(GameMode.SPECIAL_STAGE);
    manager.markBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);
    RewindController specialController = SessionManager.getCurrentGameplayMode().getRewindController();

    assertNotNull(specialController);
    assertNotSame(levelController, specialController);
}
```

Use the existing `TestLiveRewindManagerBonusStageMode` fixture style for config/session setup helpers. Also update `TestLiveRewindManagerBonusStageMode.nonGameplayModesAreNotRewindable()` so `SPECIAL_STAGE` is no longer asserted false; add a separate assertion that `SPECIAL_STAGE_RESULTS` remains false and `SPECIAL_STAGE` is a structurally rewindable mode whose actual activation is filtered by provider capability in `supportsCurrentRewindContext(...)`.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindManagerSpecialStageMode" test
```

Expected: FAIL because constructor overloads, special-stage mode, capability filtering, and stepper-kind tracking do not exist.

- [ ] **Step 3: Add constructor dependencies while preserving existing constructor**

In `LiveRewindManager` add fields:

```java
private final Supplier<GameMode> modeSupplier;
private final Supplier<SpecialStageProvider> specialStageProviderSupplier;
private StepperKind installedStepperKind;

enum StepperKind {
    LEVEL_FRAME,
    SPECIAL_STAGE
}
```

Keep old constructor:

```java
public LiveRewindManager(SonicConfigurationService config) {
    this(config, () -> GameMode.LEVEL, () -> NoOpSpecialStageProvider.INSTANCE);
}

public LiveRewindManager(SonicConfigurationService config,
                         Supplier<GameMode> modeSupplier,
                         Supplier<SpecialStageProvider> specialStageProviderSupplier) {
    this.config = Objects.requireNonNull(config, "config");
    this.modeSupplier = Objects.requireNonNull(modeSupplier, "modeSupplier");
    this.specialStageProviderSupplier = Objects.requireNonNull(
            specialStageProviderSupplier, "specialStageProviderSupplier");
    this.hudOverlay = new LiveRewindHudOverlay(this::statusLabel);
}
```

- [ ] **Step 4: Add required-kind derivation and guard checks**

Implement:

```java
private boolean supportsCurrentRewindContext(GameMode mode) {
    if (mode == GameMode.SPECIAL_STAGE) {
        SpecialStageProvider provider = specialStageProviderSupplier.get();
        return provider != null && provider.supportsRewind();
    }
    return isRewindableMode(mode);
}

private StepperKind requiredStepperKind() {
    GameMode mode = modeSupplier.get();
    if (mode == GameMode.SPECIAL_STAGE) {
        SpecialStageProvider provider = specialStageProviderSupplier.get();
        if (provider != null && provider.supportsRewind()) {
            return StepperKind.SPECIAL_STAGE;
        }
    }
    return StepperKind.LEVEL_FRAME;
}
```

Update entry guards:

```java
if (!supportsCurrentRewindContext(mode) || rewindBlocked || input == null || !enabled()) {
    activeInputHandler = null;
    clear();
    return false;
}
```

Do the same for `recordExternalFrame`, `resetBufferAtCurrentFrame`, and `renderHud` where capability matters. `isRewindableMode(GameMode.SPECIAL_STAGE)` should return true as a structural mode gate, but `supportsCurrentRewindContext(GameMode.SPECIAL_STAGE)` must return false unless the active provider reports `supportsRewind()`.

- [ ] **Step 5: Reinstall when stepper kind changes**

Change `ensureInstalled()` guard:

```java
StepperKind requiredKind = requiredStepperKind();
if (gameplayMode == installedGameplayMode
        && installedStepperKind == requiredKind
        && rewindController != null
        && inputSource != null) {
    rewindController.setRewindHistoryArmed(true);
    return true;
}
```

Create stepper by kind:

```java
EngineStepper stepper = requiredKind == StepperKind.SPECIAL_STAGE
        ? new SpecialStageStepper(inputSource, () -> activeInputHandler, specialStageProviderSupplier)
        : new LiveRewindStepper(inputSource, () -> activeInputHandler, () -> LevelFrameContext.from(gameplayMode));
gameplayMode.installPlaybackController(inputSource, stepper, KEYFRAME_INTERVAL);
installedStepperKind = requiredKind;
```

Clear it in `clear()`:

```java
installedStepperKind = null;
```

Update reflection-based test helpers that pre-install a `LiveRewindManager` controller so they also set the new private field:

```java
setInstalledStepperKind(manager, "LEVEL_FRAME");
```

Use a helper that resolves the private nested enum reflectively so tests outside `com.openggf.game.rewind`, such as `com.openggf.TestGameLoopSpecialStageRewindGate`, do not need to name `StepperKind` directly:

```java
@SuppressWarnings({"unchecked", "rawtypes"})
private static void setInstalledStepperKind(LiveRewindManager manager, String kindName) throws Exception {
    Class<?> kindClass = Class.forName("com.openggf.game.rewind.LiveRewindManager$StepperKind");
    Object kind = Enum.valueOf((Class<? extends Enum>) kindClass.asSubclass(Enum.class), kindName);
    setField(manager, "installedStepperKind", kind);
}
```

Apply that to:

- `TestLiveRewindManagerAudioCleanup#installTestController(...)`
- `TestHeldRewindAudioRestoreDeferral#installTestController(...)`
- `TestGameLoopSpecialStageRewindGate#installTestController(...)`

If keeping `StepperKind` private makes those helpers awkward, add a package-private test helper on `LiveRewindManager` that installs a test controller with an explicit kind, and use it from all three tests. Do not leave the field unset; otherwise `ensureInstalled()` will reinstall and discard the seeded controller.

- [ ] **Step 6: Run manager tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindManagerSpecialStageMode,com.openggf.game.rewind.TestLiveRewindManagerBonusStageMode,com.openggf.game.rewind.TestLiveRewindManagerAudioCleanup,com.openggf.game.rewind.TestHeldRewindAudioRestoreDeferral,com.openggf.game.rewind.TestLiveRewindBoundaryPolicy,com.openggf.TestGameLoopSpecialStageRewindGate" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/openggf/game/rewind/LiveRewindManager.java src/test/java/com/openggf/game/rewind/TestLiveRewindManagerSpecialStageMode.java src/test/java/com/openggf/game/rewind/TestLiveRewindManagerBonusStageMode.java src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java src/test/java/com/openggf/game/rewind/TestHeldRewindAudioRestoreDeferral.java src/test/java/com/openggf/TestGameLoopSpecialStageRewindGate.java
git commit -m "feat: install rewind by mode stepper kind" -m "Changelog: n/a: internal rewind installer plumbing for the special-stage feature`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 6: Add Sonic 1 Special Stage Snapshot Capture And Restore

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`
- Test: `src/test/java/com/openggf/game/sonic1/specialstage/TestSonic1SpecialStageRewindSnapshot.java`

- [ ] **Step 1: Write the snapshot round-trip test**

Create a same-package test so package-private snapshot methods can stay out of the public API. Use reflection helpers to set private fields:

```java
@Test
void captureRestoreDeepCopiesMutableArraysAndDoesNotAdvanceBgAnimation() throws Exception {
    Sonic1SpecialStageManager manager = new Sonic1SpecialStageManager();
    byte[] layout = new byte[0x4000];
    layout[0x1020] = 0x3A;
    int[] sineBuffer = new int[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20
    };
    int[] bandBuffer = new int[] {
            10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23
    };
    int[] expectedSineBuffer = sineBuffer.clone();
    int[] expectedBandBuffer = bandBuffer.clone();
    Palette[] palettes = new Palette[] {new Palette(), new Palette(), new Palette(), new Palette()};
    palettes[0].setColor(3, new Palette.Color((byte) 12, (byte) 34, (byte) 56));
    set(manager, "initialized", true);
    set(manager, "layout", layout);
    set(manager, "ssAngle", 0x1200);
    set(manager, "ssRotate", 0x40);
    set(manager, "sonicPosX", 0x1234_0000L);
    set(manager, "sonicPosY", 0x0567_0000L);
    set(manager, "bgAnimState", 6);
    set(manager, "bgExtraScrollX", 7);
    set(manager, "bgYScroll", 9);
    set(manager, "bgSineBuffer", sineBuffer);
    set(manager, "bgBandBuffer", bandBuffer);
    set(manager, "bgHScrollData", new int[224]);
    set(manager, "ssAnimBuffer", new int[][] {
            {1, 2, 3, 4}, {5, 6, 7, 8}, {0, 0, 0, 0}, {0, 0, 0, 0},
            {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}
    });
    set(manager, "ssAnimGlassFinalBlock", new int[] {1, 2, 3, 4, 5, 6, 7, 8});
    set(manager, "ssPalettes", palettes);

    Sonic1SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
    Sonic1SpecialStageSnapshot repeatedSnapshot = manager.captureRewindSnapshot();
    assertNotSame(snapshot.layout, repeatedSnapshot.layout);
    assertNotSame(snapshot.ssAnimBuffer, repeatedSnapshot.ssAnimBuffer);
    assertNotSame(snapshot.ssAnimBuffer[0], repeatedSnapshot.ssAnimBuffer[0]);
    assertNotSame(snapshot.ssAnimGlassFinalBlock, repeatedSnapshot.ssAnimGlassFinalBlock);
    assertNotSame(snapshot.bgSineBuffer, repeatedSnapshot.bgSineBuffer);
    assertNotSame(snapshot.bgBandBuffer, repeatedSnapshot.bgBandBuffer);
    assertNotSame(snapshot.ssPalettes, repeatedSnapshot.ssPalettes);
    assertNotSame(snapshot.ssPalettes[0], repeatedSnapshot.ssPalettes[0]);
    assertNotSame(snapshot.ssPalettes[0].getColor(3), repeatedSnapshot.ssPalettes[0].getColor(3));

    layout[0x1020] = 0;
    ((int[][]) get(manager, "ssAnimBuffer"))[0][0] = 99;
    ((int[]) get(manager, "ssAnimGlassFinalBlock"))[0] = 99;
    ((int[]) get(manager, "bgSineBuffer"))[0] = 99;
    ((int[]) get(manager, "bgBandBuffer"))[0] = 99;
    ((Palette[]) get(manager, "ssPalettes"))[0].setColor(
            3, new Palette.Color((byte) 99, (byte) 98, (byte) 97));
    set(manager, "bgExtraScrollX", 100);
    set(manager, "bgYScroll", 100);

    manager.restoreRewindSnapshot(snapshot);

    assertNotSame(snapshot.layout, get(manager, "layout"));
    assertNotSame(snapshot.ssAnimBuffer, get(manager, "ssAnimBuffer"));
    assertNotSame(snapshot.ssAnimBuffer[0], ((int[][]) get(manager, "ssAnimBuffer"))[0]);
    assertNotSame(snapshot.ssAnimGlassFinalBlock, get(manager, "ssAnimGlassFinalBlock"));
    assertNotSame(snapshot.bgSineBuffer, get(manager, "bgSineBuffer"));
    assertNotSame(snapshot.bgBandBuffer, get(manager, "bgBandBuffer"));
    assertNotSame(snapshot.ssPalettes, get(manager, "ssPalettes"));
    assertNotSame(snapshot.ssPalettes[0], ((Palette[]) get(manager, "ssPalettes"))[0]);
    assertNotSame(snapshot.ssPalettes[0].getColor(3),
            ((Palette[]) get(manager, "ssPalettes"))[0].getColor(3));
    assertEquals(0x3A, ((byte[]) get(manager, "layout"))[0x1020] & 0xFF);
    assertEquals(1, ((int[][]) get(manager, "ssAnimBuffer"))[0][0]);
    assertEquals(1, ((int[]) get(manager, "ssAnimGlassFinalBlock"))[0]);
    assertArrayEquals(expectedSineBuffer, (int[]) get(manager, "bgSineBuffer"));
    assertArrayEquals(expectedBandBuffer, (int[]) get(manager, "bgBandBuffer"));
    Palette.Color restoredColor = ((Palette[]) get(manager, "ssPalettes"))[0].getColor(3);
    assertEquals(12, restoredColor.r & 0xFF);
    assertEquals(34, restoredColor.g & 0xFF);
    assertEquals(56, restoredColor.b & 0xFF);
    assertEquals(7, get(manager, "bgExtraScrollX"));
    assertEquals(9, get(manager, "bgYScroll"));

    ((byte[]) get(manager, "layout"))[0x1020] = 0x7F;
    ((int[][]) get(manager, "ssAnimBuffer"))[0][0] = 77;
    ((int[]) get(manager, "ssAnimGlassFinalBlock"))[0] = 77;
    ((int[]) get(manager, "bgSineBuffer"))[0] = 77;
    ((int[]) get(manager, "bgBandBuffer"))[0] = 77;
    ((Palette[]) get(manager, "ssPalettes"))[0].setColor(
            3, new Palette.Color((byte) 77, (byte) 76, (byte) 75));

    manager.restoreRewindSnapshot(snapshot);

    assertEquals(0x3A, ((byte[]) get(manager, "layout"))[0x1020] & 0xFF);
    assertEquals(1, ((int[][]) get(manager, "ssAnimBuffer"))[0][0]);
    assertEquals(1, ((int[]) get(manager, "ssAnimGlassFinalBlock"))[0]);
    assertArrayEquals(expectedSineBuffer, (int[]) get(manager, "bgSineBuffer"));
    assertArrayEquals(expectedBandBuffer, (int[]) get(manager, "bgBandBuffer"));
    Palette.Color restoredAgain = ((Palette[]) get(manager, "ssPalettes"))[0].getColor(3);
    assertEquals(12, restoredAgain.r & 0xFF);
    assertEquals(34, restoredAgain.g & 0xFF);
    assertEquals(56, restoredAgain.b & 0xFF);
}
```

The final test must also seed, mutate, and assert every primitive Category-A field listed in the snapshot constructor, not only the representative fields shown above: `finished`, `emeraldCollected`, `debugMode`, `currentStage`, `ringsCollected`, `ssAngle`, `ssRotate`, `debugSavedAngle`, `debugSavedRotate`, `sonicPosX`, `sonicPosY`, `sonicVelX`, `sonicVelY`, `sonicInertia`, `sonicAirborne`, `sonicFacingLeft`, `cameraX`, `cameraY`, `ghostState`, `upDownCooldown`, `reverseCooldown`, all ring/wall/Sonic/palette animation counters, `exitTriggered`, `exitPhase`, `exitTimer`, `exitFadeStarted`, `exitFadeTimer`, `heldButtons`, `pressedButtons`, `bgAnimState`, `bgUsingPlane6`, `fgAnimPlaneIndex`, and `fgYScroll`/`bgYScroll`/`bgExtraScrollX`. Also assert `bgHScrollData` is rebuilt from the restored band source without advancing any accumulator.

Add helpers:

```java
private static Object get(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
}

private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
}
```

- [ ] **Step 2: Run failing test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic1.specialstage.TestSonic1SpecialStageRewindSnapshot" test
```

Expected: FAIL because snapshot type and methods do not exist.

- [ ] **Step 3: Add snapshot payload**

Create `Sonic1SpecialStageSnapshot` as package-private final class with fields for every Category A value in the spec. Constructor must clone arrays:

```java
final class Sonic1SpecialStageSnapshot {
    final boolean initialized;
    final boolean finished;
    final boolean emeraldCollected;
    final boolean debugMode;
    final int currentStage;
    final int ringsCollected;
    final byte[] layout;
    final int ssAngle;
    final int ssRotate;
    final int debugSavedAngle;
    final int debugSavedRotate;
    final long sonicPosX;
    final long sonicPosY;
    final int sonicVelX;
    final int sonicVelY;
    final int sonicInertia;
    final boolean sonicAirborne;
    final boolean sonicFacingLeft;
    final int cameraX;
    final int cameraY;
    final int ghostState;
    final int upDownCooldown;
    final int reverseCooldown;
    final int ringAnimFrame;
    final int ringAnimTimer;
    final int wallVramAnimFrame;
    final int wallVramAnimTimer;
    final int[][] ssAnimBuffer;
    final int[] ssAnimGlassFinalBlock;
    final int sonicAnimId;
    final int sonicAnimFrameIndex;
    final int sonicAnimFrameTimer;
    final int palSsTime;
    final int palSsNum;
    final int palSsIndex;
    final int ani2Frame;
    final int ani2Timer;
    final int ani3Frame;
    final int ani3Timer;
    final int sonicSpriteFrame;
    final boolean exitTriggered;
    final int exitPhase;
    final int exitTimer;
    final boolean exitFadeStarted;
    final int exitFadeTimer;
    final int heldButtons;
    final int pressedButtons;
    final int bgAnimState;
    final boolean bgUsingPlane6;
    final int fgAnimPlaneIndex;
    final int fgYScroll;
    final int bgYScroll;
    final int bgExtraScrollX;
    final int[] bgSineBuffer;
    final int[] bgBandBuffer;
    final Palette[] ssPalettes;

    Sonic1SpecialStageSnapshot(/* all fields */) {
        this.layout = layout != null ? layout.clone() : null;
        this.ssAnimBuffer = clone2d(ssAnimBuffer);
        this.ssAnimGlassFinalBlock = cloneArray(ssAnimGlassFinalBlock);
        this.bgSineBuffer = cloneArray(bgSineBuffer);
        this.bgBandBuffer = cloneArray(bgBandBuffer);
        this.ssPalettes = clonePalettes(ssPalettes);
        // assign primitive fields directly
    }
}
```

Use private clone helpers in the snapshot class; deep-copy `Palette` by creating a new `Palette` and copying color values using existing `Palette` APIs.

- [ ] **Step 4: Add manager capture/restore**

Add package-private methods:

```java
Sonic1SpecialStageSnapshot captureRewindSnapshot() {
    return new Sonic1SpecialStageSnapshot(
            initialized, finished, emeraldCollected, debugMode, currentStage, ringsCollected,
            layout, ssAngle, ssRotate, debugSavedAngle, debugSavedRotate,
            sonicPosX, sonicPosY, sonicVelX, sonicVelY, sonicInertia,
            sonicAirborne, sonicFacingLeft, cameraX, cameraY,
            ghostState, upDownCooldown, reverseCooldown,
            ringAnimFrame, ringAnimTimer, wallVramAnimFrame, wallVramAnimTimer,
            ssAnimBuffer, ssAnimGlassFinalBlock, sonicAnimId, sonicAnimFrameIndex,
            sonicAnimFrameTimer, palSsTime, palSsNum, palSsIndex, ani2Frame,
            ani2Timer, ani3Frame, ani3Timer, sonicSpriteFrame,
            exitTriggered, exitPhase, exitTimer, exitFadeStarted, exitFadeTimer,
            heldButtons, pressedButtons, bgAnimState, bgUsingPlane6, fgAnimPlaneIndex,
            fgYScroll, bgYScroll, bgExtraScrollX, bgSineBuffer, bgBandBuffer, ssPalettes);
}

void restoreRewindSnapshot(Sonic1SpecialStageSnapshot snapshot) {
    this.initialized = snapshot.initialized;
    this.finished = snapshot.finished;
    // assign every primitive field directly
    this.layout = snapshot.layout != null ? snapshot.layout.clone() : null;
    this.ssAnimBuffer = clone2d(snapshot.ssAnimBuffer);
    this.ssAnimGlassFinalBlock = cloneArray(snapshot.ssAnimGlassFinalBlock);
    this.bgSineBuffer = cloneArray(snapshot.bgSineBuffer);
    this.bgBandBuffer = cloneArray(snapshot.bgBandBuffer);
    this.ssPalettes = clonePalettes(snapshot.ssPalettes);
    reestablishRewindRenderState();
}
```

In `reestablishRewindRenderState()` null-guard graphics collaborators, recache palettes, set tilemaps, mark layers dirty, recompute `wallRotFrame`, and rebuild `bgHScrollData` using only `fillHScrollFromBands(...)`.

- [ ] **Step 5: Run snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic1.specialstage.TestSonic1SpecialStageRewindSnapshot" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageSnapshot.java src/test/java/com/openggf/game/sonic1/specialstage/TestSonic1SpecialStageRewindSnapshot.java
git commit -m "feat: snapshot Sonic 1 special stage runtime" -m "Changelog: n/a: internal snapshot coverage for special-stage rewind feature`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 7: Register Special Stage Runtime Adapter

**Files:**
- Modify: `src/main/java/com/openggf/game/SpecialStageProvider.java`
- Create: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageRewindAdapter.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java`
- Test: `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`

- [ ] **Step 1: Write registration tests**

Create a test that registers the S1 provider, captures a registry snapshot, and asserts the generic key exists. Also assert adapter identity behavior:

```java
@Test
void registersProviderOwnedSpecialStageRuntimeOnlyWhenProviderSupportsRewind() {
    GameplayModeContext context = SessionManager.getCurrentGameplayMode();
    Sonic1SpecialStageProvider provider = new Sonic1SpecialStageProvider();

    context.registerSpecialStageAdapter(provider);

    CompositeSnapshot snapshot = context.getRewindRegistry().capture();
    assertTrue(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));

    context.deregisterSpecialStageAdapter();

    CompositeSnapshot after = context.getRewindRegistry().capture();
    assertFalse(after.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
}

@Test
void sonic1AdapterUsesGenericKeyAndKeepsThrowingMissingSnapshotDefault() {
    Sonic1SpecialStageProvider provider = new Sonic1SpecialStageProvider();
    RewindSnapshottable<?> adapter = provider.rewindAdapter().orElseThrow();

    assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
    assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
}
```

- [ ] **Step 2: Run failing test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
```

Expected: FAIL because registration methods, the S1 adapter class, and the S1 provider adapter override do not exist yet.

- [ ] **Step 3: Add the provider-owned adapter override**

In `Sonic1SpecialStageProvider` add imports and override:

```java
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Optional;

@Override
public Optional<RewindSnapshottable<?>> rewindAdapter() {
    return Optional.of(new Sonic1SpecialStageRewindAdapter(manager));
}
```

- [ ] **Step 4: Add adapter**

Create `Sonic1SpecialStageRewindAdapter`:

```java
package com.openggf.game.sonic1.specialstage;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Objects;

final class Sonic1SpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic1SpecialStageSnapshot> {
    private final Sonic1SpecialStageManager manager;

    public Sonic1SpecialStageRewindAdapter(Sonic1SpecialStageManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override public String key() {
        return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
    }

    @Override public Sonic1SpecialStageSnapshot capture() {
        return manager.captureRewindSnapshot();
    }

    @Override public void restore(Sonic1SpecialStageSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot);
    }
}
```

- [ ] **Step 5: Add context registration pair**

In `GameplayModeContext`:

```java
public void registerSpecialStageAdapter(SpecialStageProvider provider) {
    if (rewindRegistry == null) {
        return;
    }
    rewindRegistry.deregister(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY);
    if (provider != null && provider.supportsRewind()) {
        provider.rewindAdapter().ifPresent(rewindRegistry::register);
    }
}

public void deregisterSpecialStageAdapter() {
    if (rewindRegistry != null) {
        rewindRegistry.deregister(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY);
    }
}
```

- [ ] **Step 6: Run tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter,com.openggf.game.session.TestGameplayModeContextRewindRegistry" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/openggf/game/SpecialStageProvider.java src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageRewindAdapter.java src/main/java/com/openggf/game/session/GameplayModeContext.java src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java
git commit -m "feat: register Sonic 1 special stage rewind adapter" -m "Changelog: n/a: internal adapter registration for the special-stage rewind feature`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 8: Wire GameLoop Special Stage Rewind Entry, Record, And Teardown

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Test: `src/test/java/com/openggf/TestGameLoopSpecialStageRewindGate.java`
- Test: `src/test/java/com/openggf/TestGameLoopSpecialStageRewindBoundary.java`
- Test: `src/test/java/com/openggf/TestGameLoopSpecialStageRewindDebugBoundary.java`

- [ ] **Step 1: Add boundary, frame-zero, and record tests**

Create a boundary test that tees boundary events into the real `LiveRewindManager` and invokes the actual special-stage entry helper with a lightweight rewindable provider. This proves `doEnterSpecialStage(...)` registers the provider-owned adapter before the boundary installs the controller and captures frame 0:

```java
@Test
void supportedSpecialStageEntrySeversLevelThenEntersFreshRewindSessionWithFrameZeroAdapter() throws Exception {
    List<RewindBoundary> boundaries = new ArrayList<>();
    LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
    loop.installLiveRewindBoundaryReporter(boundary -> {
        boundaries.add(boundary);
        liveRewindManager.markBoundary(boundary);
    });
    RewindableProvider provider = new RewindableProvider();
    GameplayModeContext context = SessionManager.getCurrentGameplayMode();

    invokeDoEnterSpecialStage(loop, provider, 0, false);

    assertEquals(List.of(
            RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE,
            RewindBoundary.MODE_ENTER_REWINDABLE), boundaries);
    assertEquals(GameMode.SPECIAL_STAGE, getField(loop, "currentGameMode"));
    RewindController controller = context.getRewindController();
    assertNotNull(controller);
    CompositeSnapshot frameZero = frameZeroSnapshot(controller);
    assertTrue(frameZero.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
            "adapter must be registered before MODE_ENTER_REWINDABLE installs the controller");
}

@Test
void supportedSpecialStageExitClearsSpecialStageRewindSession() throws Exception {
    List<RewindBoundary> boundaries = new ArrayList<>();
    LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
    loop.installLiveRewindBoundaryReporter(boundary -> {
        boundaries.add(boundary);
        liveRewindManager.markBoundary(boundary);
    });
    RewindableProvider provider = new RewindableProvider();
    setField(loop, "activeSpecialStageProvider", provider);
    invokeDoEnterSpecialStage(loop, provider, 0, false);
    assertNotNull(SessionManager.getCurrentGameplayMode().getRewindController());
    assertNotNull(getField(liveRewindManager, "rewindController"));
    boundaries.clear();

    GameMode oldMode = loop.changeGameModeForBoundary(GameMode.SPECIAL_STAGE_RESULTS);

    assertEquals(GameMode.SPECIAL_STAGE, oldMode);
    assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
    assertNull(getField(liveRewindManager, "rewindController"));
    assertNull(getField(liveRewindManager, "inputSource"));
    assertNull(getField(liveRewindManager, "installedGameplayMode"));
}

@Test
void directSupportedSpecialStageToLevelClearsSpecialStageBeforeEnteringLevelRewind() throws Exception {
    List<RewindBoundary> boundaries = new ArrayList<>();
    LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
    loop.installLiveRewindBoundaryReporter(boundary -> {
        boundaries.add(boundary);
        liveRewindManager.markBoundary(boundary);
    });
    RewindableProvider provider = new RewindableProvider();
    invokeDoEnterSpecialStage(loop, provider, 0, false);
    boundaries.clear();

    GameMode oldMode = loop.changeGameModeForBoundary(GameMode.LEVEL);

    assertEquals(GameMode.SPECIAL_STAGE, oldMode);
    assertEquals(List.of(
            RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE,
            RewindBoundary.MODE_ENTER_REWINDABLE), boundaries);
    CompositeSnapshot frameZero = frameZeroSnapshot(SessionManager.getCurrentGameplayMode().getRewindController());
    assertFalse(frameZero.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
            "level rewind session must not capture stale special-stage adapter state");
}

@Test
void sharedResultsExitCleanupIsIdempotentAfterResultsBoundaryAlreadyDeregisteredAdapter() throws Exception {
    List<RewindBoundary> boundaries = new ArrayList<>();
    LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
    loop.installLiveRewindBoundaryReporter(boundary -> {
        boundaries.add(boundary);
        liveRewindManager.markBoundary(boundary);
    });
    RewindableProvider provider = new RewindableProvider();
    invokeDoEnterSpecialStage(loop, provider, 0, false);
    loop.changeGameModeForBoundary(GameMode.SPECIAL_STAGE_RESULTS);
    invokeDeregisterSpecialStageAdapter(loop);
    boundaries.clear();

    loop.changeGameModeForBoundary(GameMode.LEVEL);

    assertEquals(List.of(RewindBoundary.MODE_ENTER_REWINDABLE), boundaries);
    CompositeSnapshot frameZero = frameZeroSnapshot(SessionManager.getCurrentGameplayMode().getRewindController());
    assertFalse(frameZero.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
            "normal results exit must not recapture stale special-stage adapter state");
}

private static CompositeSnapshot frameZeroSnapshot(RewindController controller) throws Exception {
    KeyframeStore store = (KeyframeStore) getField(controller, "keyframes");
    return store.latestAtOrBefore(0).orElseThrow().snapshot();
}

private static void invokeDoEnterSpecialStage(
        GameLoop loop, SpecialStageProvider provider, int stageIndex, boolean fadeFromBlack) throws Exception {
    Method method = GameLoop.class.getDeclaredMethod(
            "doEnterSpecialStage", SpecialStageProvider.class, int.class, boolean.class);
    method.setAccessible(true);
    method.invoke(loop, provider, stageIndex, fadeFromBlack);
}

private static void invokeDeregisterSpecialStageAdapter(GameLoop loop) throws Exception {
    Method method = GameLoop.class.getDeclaredMethod("deregisterSpecialStageAdapter");
    method.setAccessible(true);
    method.invoke(loop);
}

private static final class RewindableProvider extends MinimalSpecialStageProvider {
    private final RewindSnapshottable<Integer> adapter = new RewindSnapshottable<>() {
        private int value;

        @Override public String key() {
            return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
        }

        @Override public Integer capture() {
            return value;
        }

        @Override public void restore(Integer snapshot) {
            value = snapshot;
        }
    };

    @Override public boolean supportsRewind() {
        return true;
    }

    @Override public Optional<RewindSnapshottable<?>> rewindAdapter() {
        return Optional.of(adapter);
    }
}

private static class MinimalSpecialStageProvider implements SpecialStageProvider {
    @Override public void initialize() throws IOException { }
    @Override public void update() { }
    @Override public void draw() { }
    @Override public void handleInput(int heldButtons, int pressedButtons) { }
    @Override public boolean isFinished() { return false; }
    @Override public void reset() { }
    @Override public boolean isInitialized() { return true; }
    @Override public boolean hasSpecialStages() { return true; }
    @Override public SpecialStageAccessType getAccessType() { return SpecialStageAccessType.GIANT_RING; }
    @Override public void initializeStage(int stageIndex) throws IOException { }
    @Override public int getCurrentStage() { return 0; }
    @Override public boolean isEmeraldCollected() { return false; }
    @Override public int getEmeraldIndex() { return -1; }
    @Override public int getRingsCollected() { return 0; }
    @Override public void setEmeraldCollected(boolean collected) { }
    @Override public boolean isSpriteDebugMode() { return false; }
    @Override public void toggleSpriteDebugMode() { }
    @Override public void cyclePlaneDebugMode() { }
    @Override public SpecialStageDebugProvider getDebugProvider() { return null; }
    @Override public boolean isAlignmentTestMode() { return false; }
    @Override public void toggleAlignmentTestMode() { }
    @Override public void adjustAlignmentOffset(int delta) { }
    @Override public void adjustAlignmentSpeed(double delta) { }
    @Override public void toggleAlignmentStepMode() { }
    @Override public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) { }
    @Override public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) { }
    @Override public void setLagCompensation(double factor) { }
    @Override public ResultsScreen createResultsScreen(
            int ringsCollected, boolean gotEmerald, int stageIndex, int totalEmeraldCount) {
        return null;
    }
}
```

Add a false-provider case that only emits `MODE_EXIT_TO_NON_REWINDABLE`.

Create `TestGameLoopSpecialStageRewindDebugBoundary` to cover live-only debug/alignment controls that are not stored in `Bk2FrameInput`:

```java
@Test
void gameplayDebugToggleClearsSpecialStageRewindAndSkipsSameFrameRecord() throws Exception {
    List<RewindBoundary> boundaries = new ArrayList<>();
    LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
    loop.installLiveRewindBoundaryReporter(boundary -> {
        boundaries.add(boundary);
        liveRewindManager.markBoundary(boundary);
    });
    RewindableProvider provider = new RewindableProvider();
    invokeDoEnterSpecialStage(loop, provider, 0, false);
    boundaries.clear();
    pressUnmodifiedDebugModeKey(inputHandler);

    invokeUpdateSpecialStageMode(loop);

    assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
    assertNull(getField(liveRewindManager, "rewindController"));
    assertTrue(provider.debugToggled);
}

@Test
void alignmentAdjustmentClearsSpecialStageRewindAndSkipsSameFrameRecord() throws Exception {
    List<RewindBoundary> boundaries = new ArrayList<>();
    LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
    loop.installLiveRewindBoundaryReporter(boundary -> {
        boundaries.add(boundary);
        liveRewindManager.markBoundary(boundary);
    });
    RewindableProvider provider = new RewindableProvider();
    provider.alignmentTestMode = true;
    invokeDoEnterSpecialStage(loop, provider, 0, false);
    boundaries.clear();
    pressUnmodifiedKey(inputHandler, configService.getInt(SonicConfiguration.LEFT));

    invokeUpdateSpecialStageMode(loop);

    assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
    assertNull(getField(liveRewindManager, "rewindController"));
    assertEquals(-1, provider.alignmentOffset);
}
```

Also cover `SPECIAL_STAGE_KEY`, X/Z stage/layout debug, complete/fail keys, sprite-debug toggle, plane-debug toggle, sprite-debug navigation, F4 alignment toggle, the F1 lag-model display, and alignment adjustment controls. F6/F7 do not mutate the deterministic lag model and therefore are not special-stage rewind boundaries. The `SPECIAL_STAGE_KEY` path is handled by the global debug shortcut branch before `updateSpecialStageMode()`, so that case must drive `stepInternal()`/`loop.step()` or directly invoke the global shortcut path before asserting the boundary. The mode-local shortcut cases can use `invokeUpdateSpecialStageMode(loop)` when that is the path under test. Assert `isSpecialStageRewindable()`/top-level engagement returns false on those frames when the rewind key is also held. Use the existing test input helpers where possible; if the current helper surface cannot synthesize unmodified debug keys, add a focused package-private test helper rather than weakening the assertions.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
mvn "-Dtest=com.openggf.TestGameLoopSpecialStageRewindGate,com.openggf.TestGameLoopSpecialStageRewindBoundary,com.openggf.TestGameLoopSpecialStageRewindDebugBoundary" test
```

Expected: FAIL until GameLoop wiring exists.

- [ ] **Step 3: Construct LiveRewindManager with suppliers**

In the `GameLoop` constructor, replace:

```java
this.liveRewindManager = new LiveRewindManager(configService);
```

with:

```java
this.liveRewindManager = new LiveRewindManager(
        configService,
        () -> currentGameMode,
        this::getActiveSpecialStageProvider);
```

- [ ] **Step 4: Add special-stage predicate and engagement guard**

Add near `isBonusStageRewindable()`:

```java
private boolean specialStageRewindBoundaryThisFrame;

private boolean isSpecialStageRewindable() {
    return currentGameMode == GameMode.SPECIAL_STAGE
            && activeSpecialStageProvider != null
            && activeSpecialStageProvider.supportsRewind()
            && !specialStageTransitionPending
            && !specialStageRewindBoundaryThisFrame
            && !hasSpecialStageLiveOnlyShortcutPressed();
}

private boolean isSpecialStageRewindableBase() {
    return currentGameMode == GameMode.SPECIAL_STAGE
            && activeSpecialStageProvider != null
            && activeSpecialStageProvider.supportsRewind()
            && !specialStageTransitionPending;
}

private boolean hasSpecialStageLiveOnlyShortcutPressed() {
    int leftKey = configService.getInt(SonicConfiguration.LEFT);
    int rightKey = configService.getInt(SonicConfiguration.RIGHT);
    int upKey = configService.getInt(SonicConfiguration.UP);
    int downKey = configService.getInt(SonicConfiguration.DOWN);
    int debugModeKey = configService.getInt(SonicConfiguration.DEBUG_MODE_KEY);
    SpecialStageProvider provider = getActiveSpecialStageProvider();

    if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_X)
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_Z)
            || isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY))
            || isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_FAIL_KEY))
            || isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY))
            || isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY))
            || isUnmodifiedDebugKeyPressed(debugModeKey)
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_F4)
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_F1)
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_F6)
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_F7)) {
        return true;
    }
    boolean spriteDebugNavigation = provider != null && provider.isSpriteDebugMode()
            && (isUnmodifiedDebugKeyPressed(leftKey)
            || isUnmodifiedDebugKeyPressed(rightKey)
            || isUnmodifiedDebugKeyPressed(upKey)
            || isUnmodifiedDebugKeyPressed(downKey));
    boolean alignmentAdjustment = provider != null && provider.isAlignmentTestMode()
            && (isUnmodifiedDebugKeyPressed(leftKey)
            || isUnmodifiedDebugKeyPressed(rightKey)
            || isUnmodifiedDebugKeyPressed(upKey)
            || isUnmodifiedDebugKeyPressed(downKey)
            || isUnmodifiedDebugKeyPressed(GLFW_KEY_SPACE));
    return spriteDebugNavigation || alignmentAdjustment;
}

private void severSpecialStageRewindForLiveOnlyShortcut() {
    if (specialStageRewindBoundaryThisFrame) {
        return;
    }
    specialStageRewindBoundaryThisFrame = true;
    if (isSpecialStageRewindableBase()) {
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
        } else {
            liveRewindManager.markBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
        }
    }
}
```

Change the top-level guard:

```java
if ((currentGameMode == GameMode.LEVEL || isBonusStageRewindable() || isSpecialStageRewindable())
        && TraceSessionLauncher.active() == null
        && liveRewindManager.handleRealtimeRewindInput(
                currentGameMode, rewindBlocked, inputHandler)) {
    inputHandler.update();
    return;
}
```

- [ ] **Step 5: Add special-stage debug-boundary and record hook**

At the start of each `stepInternal()` frame, after input state has been refreshed but before the top-level rewind engagement guard, reset the same-frame suppression flag:

```java
if (currentGameMode == GameMode.SPECIAL_STAGE) {
    specialStageRewindBoundaryThisFrame = false;
}
```

Reshape the existing global special-stage shortcut path that handles `SPECIAL_STAGE_KEY` (the path that calls `handleSpecialStageDebugKey()` and can enter the results screen without reaching `updateSpecialStageMode()`) as a single branch. Do not add a second branch before the existing one. If the key is pressed, sever only when the current mode is `SPECIAL_STAGE`, then call `handleSpecialStageDebugKey()` exactly once:

```java
if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))) {
    if (currentGameMode == GameMode.SPECIAL_STAGE) {
        severSpecialStageRewindForLiveOnlyShortcut();
    }
    handleSpecialStageDebugKey();
}
```

If the existing branch cannot be shaped exactly this way, keep the same ordering and single-call invariant: `severSpecialStageRewindForLiveOnlyShortcut()` must run before `handleSpecialStageDebugKey()`, `enterResultsScreen(false)`, or any other state/mode mutation from `SPECIAL_STAGE_KEY`, and `handleSpecialStageDebugKey()` must still run exactly once for the key press in every mode that previously handled it. Preserve the existing `stepInternal()` tail after the branch; do not add an early return that skips input/profiler cleanup or `inputHandler.update()`. This is separate from the `updateSpecialStageMode()` hook because the global branch runs earlier and may skip the mode update entirely. The helper's frame-idempotence prevents a second boundary if the same key frame later reaches the `updateSpecialStageMode()` live-only shortcut check.

At the top of `updateSpecialStageMode()`, before any X/Z, complete/fail, sprite/plane debug, sprite-debug navigation, or `updateSpecialStageInput()` mutation runs, sever the active special-stage rewind session if the current frame contains a live-only shortcut:

```java
if (!specialStageRewindBoundaryThisFrame && hasSpecialStageLiveOnlyShortcutPressed()) {
    severSpecialStageRewindForLiveOnlyShortcut();
}
```

In `updateSpecialStageMode()`, immediately after `ssProvider.update();` and before `ssProvider.isFinished()`:

```java
if (isSpecialStageRewindable()
        && !specialStageRewindBoundaryThisFrame
        && TraceSessionLauncher.active() == null) {
    liveRewindManager.recordExternalFrame(
            currentGameMode, specialStageTransitionPending, inputHandler);
}
```

This intentionally records after simulation and before results dispatch. A finishing in-stage frame may be recorded, but no `SPECIAL_STAGE_RESULTS` frame is recorded because the mode boundary clears the session. During rewind replay the `SpecialStageStepper` does not call `isFinished()` or create results; after rewind release, the normal live `GameLoop` finish check owns any transition from the restored state.

- [ ] **Step 6: Add ordered boundary enter and exit events**

In `reportRewindModeBoundary(oldMode, newMode)`, compute transition booleans first and emit boundaries in this order. Do not add independent snippets around the existing logic in a way that could enter `LEVEL` before clearing an active special-stage session:

```java
boolean leavingLevel = oldMode == GameMode.LEVEL && newMode != GameMode.LEVEL;
boolean enteringLevel = oldMode != GameMode.LEVEL && newMode == GameMode.LEVEL;
boolean enteringSupportedSpecialStage = newMode == GameMode.SPECIAL_STAGE
        && activeSpecialStageProvider != null
        && activeSpecialStageProvider.supportsRewind();
boolean leavingSupportedSpecialStage = oldMode == GameMode.SPECIAL_STAGE
        && newMode != GameMode.SPECIAL_STAGE
        && activeSpecialStageProvider != null
        && activeSpecialStageProvider.supportsRewind();

if (leavingLevel) {
    context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
}
if (leavingSupportedSpecialStage) {
    deregisterSpecialStageAdapter();
    context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
}
if (enteringSupportedSpecialStage) {
    context.markRewindBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);
}
if (enteringLevel) {
    context.markRewindBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);
}
```

This preserves the existing `LEVEL -> non-LEVEL` and `non-LEVEL -> LEVEL` behavior, adds `LEVEL -> SPECIAL_STAGE` as exit-then-enter, adds `SPECIAL_STAGE -> SPECIAL_STAGE_RESULTS` as exit-only, and makes a direct `SPECIAL_STAGE -> LEVEL` deregister-then-exit-then-enter instead of installing level rewind before the special-stage session is cleared.

- [ ] **Step 7: Register and deregister adapter on mode edges**

Add a private helper used by every special-stage adapter teardown path:

```java
private void deregisterSpecialStageAdapter() {
    GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
    if (gameplayMode != null) {
        gameplayMode.deregisterSpecialStageAdapter();
    }
}
```

In `doEnterSpecialStage(...)`, declare `GameplayModeContext gameplayMode` before the `try`, then register after `activeSpecialStageProvider = ssProvider;` and before `changeGameModeForBoundary(GameMode.SPECIAL_STAGE);`:

```java
GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
try {
    ssProvider.reset();
    ssProvider.initializeStage(stageIndex);
    activeSpecialStageProvider = ssProvider;

    if (gameplayMode != null) {
        gameplayMode.registerSpecialStageAdapter(ssProvider);
    }

    GameMode oldMode = changeGameModeForBoundary(GameMode.SPECIAL_STAGE);
    // existing camera/music/reveal code follows
} catch (IOException e) {
    if (gameplayMode != null) {
        gameplayMode.deregisterSpecialStageAdapter();
    }
    throw new RuntimeException("Failed to initialize special stage", e);
} catch (RuntimeException e) {
    if (gameplayMode != null) {
        gameplayMode.deregisterSpecialStageAdapter();
    }
    throw e;
}
```

If the method keeps its existing broader catch shape, preserve that behavior; the load-bearing points are that `gameplayMode` is in scope for all failure paths and `deregisterSpecialStageAdapter()` runs after any failed entry that may have registered. The `RuntimeException` catch is intentional because boundary/controller install, camera/music setup, listener callbacks, or reveal fade setup can fail after adapter registration.

At the top of `doExitResultsScreen()`, before `activeSpecialStageProvider = NoOpSpecialStageProvider.INSTANCE;`, keep an idempotent deregistration cleanup for the normal results path:

```java
deregisterSpecialStageAdapter();
```

- [ ] **Step 8: Run GameLoop tests**

Run:

```powershell
mvn "-Dtest=com.openggf.TestGameLoopSpecialStageRewindGate,com.openggf.TestGameLoopSpecialStageRewindBoundary,com.openggf.TestGameLoopSpecialStageRewindDebugBoundary" test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/openggf/GameLoop.java src/test/java/com/openggf/TestGameLoopSpecialStageRewindGate.java src/test/java/com/openggf/TestGameLoopSpecialStageRewindBoundary.java src/test/java/com/openggf/TestGameLoopSpecialStageRewindDebugBoundary.java
git commit -m "feat: wire live rewind into supported special stages" -m "Changelog: n/a: feature changelog is staged in final documentation task`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

### Task 9: Documentation And Verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/status/known-discrepancies.md`
- Modify: `README.md`
- Already modified: `docs/architecture/designs/2026-07-07-special-stage-slots-rewind-design.md`

- [ ] **Step 1: Add changelog entry**

Add an entry under the unreleased/current section:

```markdown
- **feat(rewind): add held live rewind for Sonic 1 special stages.** Special-stage rewind now uses the existing keyframe-plus-input re-simulation controller with a special-stage stepper, provider capability gate, and a Sonic 1 runtime snapshot adapter. The scope is within-stage only; entering/exiting special stages still severs the level rewind timeline.
```

- [ ] **Step 2: Add known-discrepancies scope note**

Add a concise note:

```markdown
### Special-stage live rewind scope

Held live rewind is supported only inside rewind-capable special-stage providers. It does not rewind across the LEVEL -> SPECIAL_STAGE or SPECIAL_STAGE -> results/LEVEL boundaries; those transitions intentionally start fresh rewind timelines.
```

- [ ] **Step 3: Add README merge summary**

Under the README release/change-log section required by the branch merge policy, add a concise branch summary:

```markdown
- Added held live rewind support for Sonic 1 special stages, scoped to the active special-stage session. Level entry/exit boundaries intentionally remain rewind timeline boundaries.
```

- [ ] **Step 4: Run focused verification**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindInputSourceStartHeld,com.openggf.game.TestSpecialStageInputMapper,com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.rewind.TestSpecialStageStepperReplay,com.openggf.game.rewind.TestLiveRewindManagerSpecialStageMode,com.openggf.game.sonic1.specialstage.TestSonic1SpecialStageRewindSnapshot,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter,com.openggf.TestGameLoopSpecialStageRewindGate,com.openggf.TestGameLoopSpecialStageRewindBoundary,com.openggf.TestGameLoopSpecialStageRewindDebugBoundary" test
```

Expected: PASS.

- [ ] **Step 5: Run required regression set**

Run:

```powershell
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestBonusStageRewindCapability,TestBonusStageCoordinatorRewindAdapter,TestLiveRewindManagerBonusStageMode" test
```

Expected: PASS.

- [ ] **Step 6: Run the full test suite**

Run:

```powershell
mvn test
```

Expected: PASS. When using a constrained execution window, record the focused commands that passed and explicitly state that the full suite was not run.

- [ ] **Step 7: Manual gate**

With `s1.gen` in the repo root, run:

```powershell
mvn package
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

Manually verify:

- Held rewind works inside an S1 special stage.
- Maze rotation, Sonic movement, collected rings/items, palette cycling, and background animation restore cleanly.
- Repeated short backward steps within a cached segment render correctly.
- No double-triggered SFX or music occurs during replay or release.
- Rewind across a keyframe boundary of more than 60 frames is seamless.
- Rewind disengages when the mode flips to `SPECIAL_STAGE_RESULTS`.
- Level rewind works after returning to the level.
- S2/S3K special stages remain inert.

- [ ] **Step 8: Commit docs**

```powershell
git add CHANGELOG.md docs/status/known-discrepancies.md README.md docs/architecture/designs/2026-07-07-special-stage-slots-rewind-design.md docs/architecture/plans/2026-07-08-special-stage-rewind.md
git commit -m "docs: plan special stage live rewind" -m "Changelog: updated`nGuide: n/a`nKnown-Discrepancies: updated`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

The second `-m` argument is the required contiguous trailer block; update any trailer value if additional mapped docs were staged.

---

## Self-Review

- Spec coverage: the plan covers the Start-held input correction, capability gate, mapper, special-stage stepper, manager install/reinstall, S1 snapshot adapter, GameLoop mode-edge registration, docs, focused tests, regression tests, and manual gate.
- Placeholder scan: no unresolved placeholder markers remain.
- Type consistency: `SpecialStageInputMapper.MappedInput`, `SpecialStageStepper`, `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`, `SpecialStageProvider.rewindAdapter()`, and `GameplayModeContext.registerSpecialStageAdapter(...)` are introduced before downstream tasks depend on them.
