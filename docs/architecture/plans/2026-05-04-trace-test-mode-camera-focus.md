# Trace Test Mode Camera Focus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While paused during a trace test mode session, allow the user to cycle the camera between Default/Sidekick(Eng)/Sidekick(Trace)/Main(Eng)/Main(Trace) using P1 Left/Right, snap back to original camera on unpause, and display the active focus in the top-right HUD.

**Architecture:** A new `TraceCameraFocusController` lives in `com.openggf.testmode`. It is ticked at the very top of `GameLoop.stepInternal()` (only when a trace session is active) and only mutates `Camera.setX/setY`. The available focus list is computed at pause-entry and rebuilt after each frame-step. `TraceHudOverlay` renders the top-right `Camera: <Mode>` and `<- -> Cycle Cameras` lines when the controller is wired and the game is paused.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven; existing `Camera`, `InputHandler`, `LiveTraceComparator`, `SonicConfigurationService`, `PixelFontTextRenderer` classes.

**Spec:** `docs/architecture/designs/2026-05-04-trace-test-mode-camera-focus-design.md`

---

## File structure

| File | Purpose |
|------|---------|
| `src/main/java/com/openggf/testmode/TraceCameraFocusController.java` | New. Owns focus state machine, cycle list, camera mutation. |
| `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java` | New. Headless unit tests for all focus modes, transitions, frame-step path. |
| `src/main/java/com/openggf/testmode/TraceHudOverlay.java` | Modify. Add optional controller dependency; render top-right `Camera:` block when paused. |
| `src/test/java/com/openggf/testmode/TestTraceHudOverlay.java` | Modify. Add tests for top-right strings (paused vs unpaused, controller wired vs not). |
| `src/main/java/com/openggf/GameLoop.java` | Modify. Add setter for the focus controller; tick it at the top of `stepInternal()` when present. |
| `src/main/java/com/openggf/TraceSessionLauncher.java` | Modify. Construct controller in `finishLaunchAfterGameBootstrap`, register on `GameLoop`, pass to `TraceHudOverlay`. Clear on `teardown`. |

---

## Task 1: Define `FocusMode` enum and controller skeleton

**Files:**
- Create: `src/main/java/com/openggf/testmode/TraceCameraFocusController.java`

- [ ] **Step 1: Write the controller skeleton**

```java
package com.openggf.testmode;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.live.LiveTraceComparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Trace Test Mode camera focus controller.
 *
 * <p>While paused during a live trace session, lets the user cycle the camera
 * between up to five focus targets (default, engine/trace sidekick, engine/trace
 * main player) via the P1 LEFT/RIGHT keys, then restores the camera to its
 * pre-pause position on unpause.
 *
 * <p><b>Camera mutation contract:</b> this class must only call
 * {@link Camera#setX(short)} and {@link Camera#setY(short)}. It must not call
 * {@link Camera#updatePosition} or any other camera method, and it must not
 * invoke any sprite/object/manager update path. Calling {@code updatePosition}
 * would clobber the focus value with the focused-sprite-derived position; the
 * pause early-return in {@link com.openggf.GameLoop} keeps every other system
 * frozen, so simply writing {@code x}/{@code y} is sufficient and safe.
 */
public final class TraceCameraFocusController {

    public enum FocusMode {
        DEFAULT("Default"),
        SIDEKICK_ENGINE("Sidekick (Eng)"),
        SIDEKICK_TRACE("Sidekick (Trace)"),
        MAIN_ENGINE("Main (Eng)"),
        MAIN_TRACE("Main (Trace)");

        private final String label;
        FocusMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final LiveTraceComparator comparator;
    private final Supplier<AbstractPlayableSprite> mainSpriteSupplier;
    private final Supplier<AbstractPlayableSprite> firstSidekickSupplier;
    private final Supplier<Camera> cameraSupplier;
    private final SonicConfigurationService configService;
    private final Supplier<Boolean> pausedSupplier;

    private final List<FocusMode> available = new ArrayList<>();
    private int activeIndex;
    private short savedCamX;
    private short savedCamY;
    private boolean wasPaused;
    private FocusMode reapplyAfterStep;

    public TraceCameraFocusController(
            LiveTraceComparator comparator,
            Supplier<AbstractPlayableSprite> mainSpriteSupplier,
            Supplier<AbstractPlayableSprite> firstSidekickSupplier,
            Supplier<Camera> cameraSupplier,
            SonicConfigurationService configService,
            Supplier<Boolean> pausedSupplier) {
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.mainSpriteSupplier = Objects.requireNonNull(mainSpriteSupplier, "mainSpriteSupplier");
        this.firstSidekickSupplier = Objects.requireNonNull(firstSidekickSupplier, "firstSidekickSupplier");
        this.cameraSupplier = Objects.requireNonNull(cameraSupplier, "cameraSupplier");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.pausedSupplier = Objects.requireNonNull(pausedSupplier, "pausedSupplier");
    }

    /** @return current focus label for the HUD, or {@code null} if not paused. */
    public String currentLabel() {
        if (!wasPaused || available.isEmpty()) return null;
        return available.get(activeIndex).label();
    }

    /** Test hook: number of focuses currently available (including DEFAULT). */
    int availableSize() { return available.size(); }

    /** Test hook: currently active focus mode while paused. */
    FocusMode activeMode() { return available.isEmpty() ? null : available.get(activeIndex); }

    public void tick(InputHandler inputHandler) {
        // Implemented in later tasks.
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceCameraFocusController.java
git commit -m "feat(testmode): scaffold TraceCameraFocusController shell

Adds the FocusMode enum and constructor wiring for the Trace Test Mode
pause-time camera cycling feature. tick() is a no-op stub; subsequent
tasks implement the state machine.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 2: Build the available-list construction

**Files:**
- Create: `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java`
- Modify: `src/main/java/com/openggf/testmode/TraceCameraFocusController.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java`:

```java
package com.openggf.testmode;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.testmode.TraceCameraFocusController.FocusMode;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.live.LiveTraceComparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceCameraFocusControllerTest {

    private LiveTraceComparator comparator;
    private SonicConfigurationService config;
    private InputHandler input;
    private Camera camera;
    private AtomicBoolean paused;
    private AtomicReference<AbstractPlayableSprite> mainSprite;
    private AtomicReference<AbstractPlayableSprite> sidekick;

    @BeforeEach
    void setup() {
        comparator = mock(LiveTraceComparator.class);
        config = mock(SonicConfigurationService.class);
        input = mock(InputHandler.class);
        camera = mock(Camera.class);
        paused = new AtomicBoolean(false);
        mainSprite = new AtomicReference<>(null);
        sidekick = new AtomicReference<>(null);

        when(config.getInt(SonicConfiguration.LEFT)).thenReturn(263);   // GLFW_KEY_LEFT
        when(config.getInt(SonicConfiguration.RIGHT)).thenReturn(262);  // GLFW_KEY_RIGHT
        when(config.getInt(SonicConfiguration.FRAME_STEP_KEY)).thenReturn(70); // some key
        when(camera.getX()).thenReturn((short) 100);
        when(camera.getY()).thenReturn((short) 200);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.getMinX()).thenReturn((short) 0);
        when(camera.getMaxX()).thenReturn((short) 10000);
        when(camera.getMinY()).thenReturn((short) 0);
        when(camera.getMaxY()).thenReturn((short) 10000);
    }

    private TraceCameraFocusController newController() {
        return new TraceCameraFocusController(
                comparator, mainSprite::get, sidekick::get,
                () -> camera, config, paused::get);
    }

    private static AbstractPlayableSprite spriteAt(int centreX, int centreY) {
        AbstractPlayableSprite s = mock(AbstractPlayableSprite.class);
        when(s.getCentreX()).thenReturn(centreX);
        when(s.getCentreY()).thenReturn(centreY);
        return s;
    }

    private static TraceFrame frameWith(int mainX, int mainY, TraceCharacterState sidekick) {
        return new TraceFrame(0, 0, (short) mainX, (short) mainY,
                (short) 0, (short) 0, (short) 0, (byte) 0, false, false, 0,
                0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, sidekick);
    }

    @Test
    void availableListIsDefaultOnlyWhenNoMainSpriteOrSidekick() {
        when(comparator.currentVisualFrame()).thenReturn(null);
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        assertEquals(1, controller.availableSize());
        assertEquals(FocusMode.DEFAULT, controller.activeMode());
    }

    @Test
    void availableListIncludesMainEngineWhenSpriteAlive() {
        mainSprite.set(spriteAt(500, 300));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // Main engine added; trace position matches engine, so MAIN_TRACE skipped.
        assertEquals(2, controller.availableSize());
    }

    @Test
    void availableListIncludesMainTraceWhenPositionsDiffer() {
        mainSprite.set(spriteAt(500, 300));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(600, 300, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        assertEquals(3, controller.availableSize());
    }

    @Test
    void availableListIncludesSidekickEngineAndTraceWhenBothPresentAndDiffer() {
        mainSprite.set(spriteAt(500, 300));
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 470, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // DEFAULT, SIDEKICK_ENGINE, SIDEKICK_TRACE, MAIN_ENGINE; MAIN_TRACE skipped (matches).
        assertEquals(4, controller.availableSize());
    }

    @Test
    void availableListSkipsSidekickTraceWhenSidekickAbsentInTrace() {
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = TraceCharacterState.absent();
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // DEFAULT + SIDEKICK_ENGINE only.
        assertEquals(2, controller.availableSize());
    }

    @Test
    void availableListSkipsSidekickWhenEngineHasNoSidekick() {
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 470, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // DEFAULT only — no SIDEKICK_TRACE without SIDEKICK_ENGINE.
        assertEquals(1, controller.availableSize());
    }

    @Test
    void labelIsNullWhenNotPaused() {
        TraceCameraFocusController controller = newController();
        assertNull(controller.currentLabel());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: FAIL — `availableSize()` returns 0 because `tick` is still a stub.

- [ ] **Step 3: Implement `buildAvailable` and pause-edge enter**

Edit `TraceCameraFocusController.java`. Replace the `tick` body and add the helper:

```java
public void tick(InputHandler inputHandler) {
    boolean paused = pausedSupplier.get();
    if (!wasPaused && paused) {
        enterPause();
    }
    wasPaused = paused;
}

private void enterPause() {
    Camera cam = cameraSupplier.get();
    savedCamX = cam.getX();
    savedCamY = cam.getY();
    buildAvailable();
    activeIndex = 0;
    reapplyAfterStep = null;
}

private void buildAvailable() {
    available.clear();
    available.add(FocusMode.DEFAULT);

    AbstractPlayableSprite engineSidekick = firstSidekickSupplier.get();
    AbstractPlayableSprite engineMain = mainSpriteSupplier.get();
    TraceFrame traceFrame = comparator.currentVisualFrame();
    TraceCharacterState traceSidekick = traceFrame != null ? traceFrame.sidekick() : null;

    if (engineSidekick != null) {
        available.add(FocusMode.SIDEKICK_ENGINE);
        if (traceSidekick != null && traceSidekick.present()) {
            int engX = engineSidekick.getCentreX();
            int engY = engineSidekick.getCentreY();
            if (traceSidekick.x() != engX || traceSidekick.y() != engY) {
                available.add(FocusMode.SIDEKICK_TRACE);
            }
        }
    }

    if (engineMain != null) {
        available.add(FocusMode.MAIN_ENGINE);
        if (traceFrame != null) {
            int engX = engineMain.getCentreX();
            int engY = engineMain.getCentreY();
            if (traceFrame.x() != engX || traceFrame.y() != engY) {
                available.add(FocusMode.MAIN_TRACE);
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceCameraFocusController.java \
        src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java
git commit -m "feat(testmode): build focus cycle list at pause-edge entry

Constructs the available focus modes (DEFAULT/SIDEKICK_ENGINE/
SIDEKICK_TRACE/MAIN_ENGINE/MAIN_TRACE) by inspecting engine sprite
state and the live trace frame. Skips trace variants when positions
match engine, skips sidekick entries when engine has no sidekick.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 3: Pause-edge exit restores camera

**Files:**
- Modify: `src/main/java/com/openggf/testmode/TraceCameraFocusController.java`
- Modify: `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `TraceCameraFocusControllerTest.java`:

```java
@Test
void unpauseRestoresCameraToSavedPosition() {
    when(comparator.currentVisualFrame()).thenReturn(null);
    TraceCameraFocusController controller = newController();

    paused.set(true);
    when(camera.getX()).thenReturn((short) 1234);
    when(camera.getY()).thenReturn((short) 567);
    controller.tick(input);  // pause-edge enter, snapshots 1234/567

    paused.set(false);
    controller.tick(input);  // pause-edge exit, must restore

    org.mockito.Mockito.verify(camera).setX((short) 1234);
    org.mockito.Mockito.verify(camera).setY((short) 567);
}

@Test
void labelClearedAfterUnpause() {
    when(comparator.currentVisualFrame()).thenReturn(null);
    TraceCameraFocusController controller = newController();
    paused.set(true);
    controller.tick(input);
    assertEquals("Default", controller.currentLabel());
    paused.set(false);
    controller.tick(input);
    assertNull(controller.currentLabel());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: FAIL — `unpauseRestoresCameraToSavedPosition` because no `setX/setY` happens; `labelClearedAfterUnpause` because `wasPaused` flips off but `available` is still populated.

- [ ] **Step 3: Implement pause-edge exit**

Edit `tick`:

```java
public void tick(InputHandler inputHandler) {
    boolean paused = pausedSupplier.get();
    if (!wasPaused && paused) {
        enterPause();
    } else if (wasPaused && !paused) {
        exitPause();
    }
    wasPaused = paused;
}

private void exitPause() {
    Camera cam = cameraSupplier.get();
    cam.setX(savedCamX);
    cam.setY(savedCamY);
    available.clear();
    activeIndex = 0;
    reapplyAfterStep = null;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceCameraFocusController.java \
        src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java
git commit -m "feat(testmode): restore camera on unpause-edge

When the pause is released, the controller writes the snapshotted X/Y
back to the camera so normal gameplay resumes from the same viewpoint
the user paused at, with no leak of the focus camera into live play.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 4: Cycling input applies camera focus

**Files:**
- Modify: `src/main/java/com/openggf/testmode/TraceCameraFocusController.java`
- Modify: `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `TraceCameraFocusControllerTest.java`:

```java
@Test
void rightArrowAdvancesFocusAndAppliesCameraCentredOnTarget() {
    mainSprite.set(spriteAt(1000, 500));
    when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
    TraceCameraFocusController controller = newController();
    paused.set(true);
    controller.tick(input);
    // Available: DEFAULT, MAIN_ENGINE.

    when(input.isKeyPressed(262)).thenReturn(true);  // RIGHT
    controller.tick(input);
    when(input.isKeyPressed(262)).thenReturn(false);

    assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
    // Centred: camX = 1000 - 320/2 = 840; camY = 500 - 224/2 = 388.
    org.mockito.Mockito.verify(camera).setX((short) 840);
    org.mockito.Mockito.verify(camera).setY((short) 388);
}

@Test
void leftArrowFromDefaultWrapsToLastAvailable() {
    mainSprite.set(spriteAt(1000, 500));
    when(comparator.currentVisualFrame()).thenReturn(frameWith(2000, 600, null));
    TraceCameraFocusController controller = newController();
    paused.set(true);
    controller.tick(input);
    // Available: DEFAULT, MAIN_ENGINE, MAIN_TRACE (3 items).

    when(input.isKeyPressed(263)).thenReturn(true);  // LEFT
    controller.tick(input);
    when(input.isKeyPressed(263)).thenReturn(false);

    assertEquals(FocusMode.MAIN_TRACE, controller.activeMode());
    // MAIN_TRACE centres on (2000, 600): camX = 2000-160 = 1840, camY = 600-112 = 488.
    org.mockito.Mockito.verify(camera).setX((short) 1840);
    org.mockito.Mockito.verify(camera).setY((short) 488);
}

@Test
void cycleClampsToCameraBounds() {
    mainSprite.set(spriteAt(50, 50));  // near top-left of level
    when(comparator.currentVisualFrame()).thenReturn(frameWith(50, 50, null));
    when(camera.getMinX()).thenReturn((short) 0);
    when(camera.getMaxX()).thenReturn((short) 10000);
    when(camera.getMinY()).thenReturn((short) 0);
    when(camera.getMaxY()).thenReturn((short) 10000);
    TraceCameraFocusController controller = newController();
    paused.set(true);
    controller.tick(input);

    when(input.isKeyPressed(262)).thenReturn(true);  // RIGHT to MAIN_ENGINE
    controller.tick(input);

    // 50 - 160 = -110, clamped to 0.
    org.mockito.Mockito.verify(camera).setX((short) 0);
    org.mockito.Mockito.verify(camera).setY((short) 0);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: FAIL — controller doesn't read input or apply focus yet.

- [ ] **Step 3: Implement cycle handling and applyFocus**

Edit `tick` to add the cycling branch (only when paused-and-still-paused), and add helpers:

```java
public void tick(InputHandler inputHandler) {
    boolean paused = pausedSupplier.get();
    if (!wasPaused && paused) {
        enterPause();
    } else if (wasPaused && !paused) {
        exitPause();
    } else if (paused) {
        handleCycleInput(inputHandler);
    }
    wasPaused = paused;
}

private void handleCycleInput(InputHandler inputHandler) {
    if (available.size() <= 1) return;
    int leftKey = configService.getInt(SonicConfiguration.LEFT);
    int rightKey = configService.getInt(SonicConfiguration.RIGHT);
    int n = available.size();
    boolean changed = false;
    if (inputHandler.isKeyPressed(leftKey)) {
        activeIndex = (activeIndex - 1 + n) % n;
        changed = true;
    } else if (inputHandler.isKeyPressed(rightKey)) {
        activeIndex = (activeIndex + 1) % n;
        changed = true;
    }
    if (changed) {
        applyFocus(available.get(activeIndex));
    }
}

private void applyFocus(FocusMode mode) {
    Camera cam = cameraSupplier.get();
    if (mode == FocusMode.DEFAULT) {
        cam.setX(savedCamX);
        cam.setY(savedCamY);
        return;
    }
    int targetX;
    int targetY;
    switch (mode) {
        case SIDEKICK_ENGINE -> {
            AbstractPlayableSprite s = firstSidekickSupplier.get();
            if (s == null) return;
            targetX = s.getCentreX();
            targetY = s.getCentreY();
        }
        case SIDEKICK_TRACE -> {
            TraceFrame f = comparator.currentVisualFrame();
            if (f == null || f.sidekick() == null || !f.sidekick().present()) return;
            targetX = f.sidekick().x();
            targetY = f.sidekick().y();
        }
        case MAIN_ENGINE -> {
            AbstractPlayableSprite s = mainSpriteSupplier.get();
            if (s == null) return;
            targetX = s.getCentreX();
            targetY = s.getCentreY();
        }
        case MAIN_TRACE -> {
            TraceFrame f = comparator.currentVisualFrame();
            if (f == null) return;
            targetX = f.x();
            targetY = f.y();
        }
        default -> { return; }
    }
    int halfW = cam.getWidth() / 2;
    int halfH = cam.getHeight() / 2;
    short camX = clampShort(targetX - halfW, cam.getMinX(), cam.getMaxX());
    short camY = clampShort(targetY - halfH, cam.getMinY(), cam.getMaxY());
    cam.setX(camX);
    cam.setY(camY);
}

private static short clampShort(int value, short min, short max) {
    if (max < min) {
        // Transient camera-bound state; fall through with no clamp.
        return (short) value;
    }
    if (value < min) return min;
    if (value > max) return max;
    return (short) value;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceCameraFocusController.java \
        src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java
git commit -m "feat(testmode): cycle focus on P1 LEFT/RIGHT and centre camera

Reads the configured P1 LEFT/RIGHT key bindings while paused, advances
or rewinds activeIndex with wraparound, and writes the centring math
to camera.setX/setY (clamped to current camera bounds).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 5: Frame-step path restores camera before the step and re-applies after

**Files:**
- Modify: `src/main/java/com/openggf/testmode/TraceCameraFocusController.java`
- Modify: `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `TraceCameraFocusControllerTest.java`:

```java
@Test
void frameStepRestoresCameraBeforeStepAndReappliesFocusAfter() {
    mainSprite.set(spriteAt(1000, 500));
    when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
    TraceCameraFocusController controller = newController();

    when(camera.getX()).thenReturn((short) 100);
    when(camera.getY()).thenReturn((short) 200);
    paused.set(true);
    controller.tick(input);  // pause-edge enter

    when(input.isKeyPressed(262)).thenReturn(true);
    controller.tick(input);  // RIGHT -> MAIN_ENGINE
    when(input.isKeyPressed(262)).thenReturn(false);
    org.mockito.Mockito.clearInvocations(camera);

    // Frame-step pressed: paused stays true, controller should restore savedCam,
    // remember the focus, and let the step happen.
    when(input.isKeyPressed(70)).thenReturn(true);  // FRAME_STEP_KEY
    controller.tick(input);
    when(input.isKeyPressed(70)).thenReturn(false);

    org.mockito.Mockito.verify(camera).setX((short) 100);
    org.mockito.Mockito.verify(camera).setY((short) 200);
    org.mockito.Mockito.clearInvocations(camera);

    // Next tick after step: re-apply MAIN_ENGINE focus.
    controller.tick(input);
    org.mockito.Mockito.verify(camera).setX((short) 840);
    org.mockito.Mockito.verify(camera).setY((short) 388);
    assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
}

@Test
void frameStepFallsBackToDefaultWhenPreviousFocusGone() {
    mainSprite.set(spriteAt(1000, 500));
    sidekick.set(spriteAt(950, 500));
    when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
    TraceCameraFocusController controller = newController();
    paused.set(true);
    controller.tick(input);
    // Available: DEFAULT, SIDEKICK_ENGINE, MAIN_ENGINE.

    // Move to SIDEKICK_ENGINE.
    when(input.isKeyPressed(262)).thenReturn(true);
    controller.tick(input);
    when(input.isKeyPressed(262)).thenReturn(false);
    assertEquals(FocusMode.SIDEKICK_ENGINE, controller.activeMode());

    // Frame-step: sidekick despawns mid-step.
    when(input.isKeyPressed(70)).thenReturn(true);
    controller.tick(input);
    when(input.isKeyPressed(70)).thenReturn(false);
    sidekick.set(null);

    // Next tick rebuilds available list; SIDEKICK_ENGINE is gone -> fall back to DEFAULT.
    controller.tick(input);
    assertEquals(FocusMode.DEFAULT, controller.activeMode());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: FAIL — frame-step branch not implemented.

- [ ] **Step 3: Implement frame-step branch**

Replace the `else if (paused)` branch in `tick`:

```java
} else if (paused) {
    int frameStepKey = configService.getInt(SonicConfiguration.FRAME_STEP_KEY);
    boolean frameStep = inputHandler.isKeyPressed(frameStepKey);
    if (reapplyAfterStep != null) {
        // The previous tick was a frame-step; gameplay has now advanced one step.
        // Rebuild available list (spawn state may have changed) and re-apply the
        // focus the user had selected, falling back to DEFAULT if it's gone.
        FocusMode prev = reapplyAfterStep;
        reapplyAfterStep = null;
        buildAvailable();
        int idx = available.indexOf(prev);
        activeIndex = idx >= 0 ? idx : 0;
        applyFocus(available.get(activeIndex));
    } else if (frameStep) {
        reapplyAfterStep = available.get(activeIndex);
        Camera cam = cameraSupplier.get();
        cam.setX(savedCamX);
        cam.setY(savedCamY);
    } else {
        handleCycleInput(inputHandler);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceCameraFocusController.java \
        src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java
git commit -m "feat(testmode): preserve gameplay determinism across frame-step

When the user frame-steps while a focus is active, restore camera to
the snapshotted position before the step runs (so object placement
windows etc. see the original camera) and re-apply the focus on the
next tick. Falls back to DEFAULT if the previous focus is no longer
available after the step.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 6: Camera mutation contract regression guard

**Files:**
- Modify: `src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java`

- [ ] **Step 1: Write the test**

Append to `TraceCameraFocusControllerTest.java`:

```java
@Test
void controllerOnlyMutatesSetXAndSetYOnCamera() {
    mainSprite.set(spriteAt(1000, 500));
    when(comparator.currentVisualFrame()).thenReturn(frameWith(2000, 600, null));
    TraceCameraFocusController controller = newController();
    paused.set(true);
    controller.tick(input);
    when(input.isKeyPressed(262)).thenReturn(true);
    controller.tick(input);
    when(input.isKeyPressed(262)).thenReturn(false);
    controller.tick(input);
    paused.set(false);
    controller.tick(input);

    // updatePosition must NEVER be called.
    org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).updatePosition();
    org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).updatePosition(
            org.mockito.ArgumentMatchers.anyBoolean());
    org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).setShakeOffsets(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
}
```

- [ ] **Step 2: Run tests to verify it passes**

Run: `mvn -q test -Dtest=TraceCameraFocusControllerTest`
Expected: PASS — the production code already respects the contract; this guards against regression.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/testmode/TraceCameraFocusControllerTest.java
git commit -m "test(testmode): guard against TraceCameraFocusController side effects

Asserts the controller never calls Camera.updatePosition or
setShakeOffsets, only setX/setY. Documents the safety contract
mechanically so future edits can't silently break it.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 7: TraceHudOverlay renders top-right `Camera:` block

**Files:**
- Modify: `src/main/java/com/openggf/testmode/TraceHudOverlay.java`
- Modify: `src/test/java/com/openggf/testmode/TestTraceHudOverlay.java`

- [ ] **Step 1: Write the failing tests**

Replace the existing constructor calls in `TestTraceHudOverlay.java` to take an optional focus controller via a `Supplier<String>` (the `currentLabel()` accessor). Add new tests at the end of the class:

```java
@Test
void renderShowsCameraFocusBlockWhenPausedAndLabelSupplied() {
    LiveTraceComparator comparator = mock(LiveTraceComparator.class);
    when(comparator.recentMismatches()).thenReturn(List.of());
    PixelFontTextRenderer textRenderer = mock(PixelFontTextRenderer.class);
    when(textRenderer.measureWidth(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyFloat())).thenReturn(40);

    TraceHudOverlay overlay = new TraceHudOverlay(
            comparator, () -> "ENTER", () -> true, () -> "Sidekick (Eng)");

    overlay.render(textRenderer);

    verify(textRenderer).drawShadowedText(
            org.mockito.ArgumentMatchers.eq("Camera: Sidekick (Eng)"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyFloat());
    verify(textRenderer).drawShadowedText(
            org.mockito.ArgumentMatchers.eq("<- -> Cycle Cameras"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyFloat());
}

@Test
void renderHidesCameraFocusBlockWhenNotPaused() {
    LiveTraceComparator comparator = mock(LiveTraceComparator.class);
    when(comparator.recentMismatches()).thenReturn(List.of());
    PixelFontTextRenderer textRenderer = mock(PixelFontTextRenderer.class);
    when(textRenderer.measureWidth(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyFloat())).thenReturn(40);

    TraceHudOverlay overlay = new TraceHudOverlay(
            comparator, () -> "ENTER", () -> false, () -> "Default");

    overlay.render(textRenderer);

    verify(textRenderer, never()).drawShadowedText(
            org.mockito.ArgumentMatchers.startsWith("Camera:"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyFloat());
    verify(textRenderer, never()).drawShadowedText(
            org.mockito.ArgumentMatchers.eq("<- -> Cycle Cameras"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyFloat());
}

@Test
void renderHidesCameraFocusBlockWhenLabelSupplierReturnsNull() {
    LiveTraceComparator comparator = mock(LiveTraceComparator.class);
    when(comparator.recentMismatches()).thenReturn(List.of());
    PixelFontTextRenderer textRenderer = mock(PixelFontTextRenderer.class);

    TraceHudOverlay overlay = new TraceHudOverlay(
            comparator, () -> "ENTER", () -> true, () -> null);

    overlay.render(textRenderer);

    verify(textRenderer, never()).drawShadowedText(
            org.mockito.ArgumentMatchers.startsWith("Camera:"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyFloat());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=TestTraceHudOverlay`
Expected: FAIL — the new constructor with the label supplier doesn't exist; the new strings aren't rendered.

- [ ] **Step 3: Add label supplier to `TraceHudOverlay`**

Edit `TraceHudOverlay.java`. Add a fourth constructor parameter (a `Supplier<String>` for the active focus label) and a top-right rendering block. Specific changes:

1. Add field `private final Supplier<String> focusLabelSupplier;` and a constant for top-right margin (`private static final int RIGHT_MARGIN = 4;`) and screen width (`private static final int SCREEN_WIDTH = 320;`).

2. Add the new constructor (keep the existing two-arg public constructor for back-compat by delegating with a `() -> null` supplier):

```java
public TraceHudOverlay(LiveTraceComparator comparator) {
    this(comparator, TraceHudOverlay::configuredPauseKeyLabel,
            TraceHudOverlay::isGameLoopPaused, () -> null);
}

public TraceHudOverlay(LiveTraceComparator comparator, Supplier<String> focusLabelSupplier) {
    this(comparator, TraceHudOverlay::configuredPauseKeyLabel,
            TraceHudOverlay::isGameLoopPaused, focusLabelSupplier);
}

TraceHudOverlay(LiveTraceComparator comparator,
                Supplier<String> pauseKeyLabelSupplier,
                BooleanSupplier pausedSupplier,
                Supplier<String> focusLabelSupplier) {
    this.comparator = comparator;
    this.pauseKeyLabelSupplier = pauseKeyLabelSupplier;
    this.pausedSupplier = pausedSupplier;
    this.focusLabelSupplier = focusLabelSupplier;
}
```

3. At the end of `render(PixelFontTextRenderer text)`, inside the existing `try { ... } finally { text.endBatch(); }` block, append:

```java
if (paused) {
    String focusLabel = focusLabelSupplier.get();
    if (focusLabel != null) {
        String main = "Camera: " + focusLabel;
        String hint = "<- -> Cycle Cameras";
        int mainW = text.measureWidth(main, SCALE);
        int hintW = text.measureWidth(hint, SCALE);
        int mainX = SCREEN_WIDTH - RIGHT_MARGIN - mainW;
        int hintX = SCREEN_WIDTH - RIGHT_MARGIN - hintW;
        text.drawShadowedText(main, mainX, TOP_Y, DebugColor.LIGHT_GRAY, SCALE);
        text.drawShadowedText(hint, hintX, TOP_Y + LINE_HEIGHT, DebugColor.GRAY, SCALE);
    }
}
```

4. Update existing test constructions in `TestTraceHudOverlay` that pass the three-arg package-private constructor: append a fourth `() -> null` argument so they still compile.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=TestTraceHudOverlay`
Expected: PASS (all original + 3 new tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceHudOverlay.java \
        src/test/java/com/openggf/testmode/TestTraceHudOverlay.java
git commit -m "feat(testmode): render top-right Camera: focus block while paused

Adds a focus-label supplier to TraceHudOverlay; when paused and the
supplier returns a non-null label, the overlay renders a right-aligned
'Camera: <Mode>' line plus a '<- -> Cycle Cameras' hint at the top of
the screen.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 8: Wire controller into GameLoop

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [ ] **Step 1: Add field, setter, and tick call**

Edit `GameLoop.java`:

1. Add the import: `import com.openggf.testmode.TraceCameraFocusController;`

2. Add a private field next to other test-mode fields (search for `playbackDebugManager` to find the section):

```java
private TraceCameraFocusController traceCameraFocusController;
```

3. Add a setter (place near `setGameModeChangeListener`):

```java
public void setTraceCameraFocusController(TraceCameraFocusController controller) {
    this.traceCameraFocusController = controller;
}
```

4. At the very top of `stepInternal()`, immediately after `refreshRuntimeBindings();` and the `paletteRegistry.beginFrame();` block, before `playbackDebugManager.handleInput(inputHandler);`, insert:

```java
if (traceCameraFocusController != null) {
    traceCameraFocusController.tick(inputHandler);
}
```

This guarantees:
- Pause-edge enter/exit fire reliably regardless of any later early-returns.
- On a frame-step (paused stays true, frame-step key pressed), the camera is restored to savedCam BEFORE the existing pause early-return decision and BEFORE any audio/timer/sprite/object update. The actual frame-step block lower in `stepInternal` then runs gameplay updates against the restored camera.
- On the unpause edge, camera is restored before any other system samples it.

- [ ] **Step 2: Verify compilation**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run all GameLoop tests**

Run: `mvn -q test -Dtest=TestGameLoop`
Expected: PASS — controller is null in existing tests so the new code is inert.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit -m "feat(testmode): tick TraceCameraFocusController at top of stepInternal

Adds a setter for the optional focus controller and ticks it as the
very first action in stepInternal so pause-edge transitions and
frame-step camera restoration always fire before any other system
samples the camera position.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 9: Wire controller into TraceSessionLauncher

**Files:**
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`

- [ ] **Step 1: Construct, register, and clear the controller**

Edit `TraceSessionLauncher.java`:

1. Add import: `import com.openggf.testmode.TraceCameraFocusController;`

2. Add field next to `comparator` and `overlay`:

```java
private TraceCameraFocusController cameraFocusController;
```

3. Inside `finishLaunchAfterGameBootstrap()`, replace the existing block that constructs `comparator` and `overlay` with one that also constructs the focus controller. After:

```java
this.comparator = new LiveTraceComparator(
        trace,
        ToleranceConfig.DEFAULT,
        initialCursor,
        loop::getMainPlayableSprite,
        loop::toggleUserPause);
```

insert:

```java
this.cameraFocusController = new TraceCameraFocusController(
        comparator,
        loop::getMainPlayableSprite,
        () -> {
            var sprites = GameServices.spritesOrNull();
            if (sprites == null) return null;
            var sks = sprites.getSidekicks();
            return sks.isEmpty() ? null : sks.get(0);
        },
        GameServices::camera,
        GameServices.configuration(),
        loop::isPaused);
loop.setTraceCameraFocusController(cameraFocusController);
this.overlay = new TraceHudOverlay(comparator,
        () -> cameraFocusController.currentLabel());
```

(Replace the existing `this.overlay = new TraceHudOverlay(comparator);` line.)

4. In the `catch (Exception e)` block of `finishLaunchAfterGameBootstrap` (after `playback.endSession();`), add:

```java
if (loop != null) {
    loop.setTraceCameraFocusController(null);
}
this.cameraFocusController = null;
```

5. In `teardown()`, after `activeSession = null;` add:

```java
GameLoop loop = Engine.currentGameLoop();
if (loop != null) {
    loop.setTraceCameraFocusController(null);
}
this.cameraFocusController = null;
```

(Note: there's already a `GameLoop loop = ...; if (loop != null)` block lower; the second `loop` declaration must be moved or the new statements inlined into the existing block. Place the new statements inside the existing `if (loop != null)` near the bottom of `teardown()` instead, before `loop.returnToMasterTitle();`.)

- [ ] **Step 2: Re-run trace launcher tests if any cover this path**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the full testmode suite**

Run: `mvn -q test -Dtest='com.openggf.testmode.*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/TraceSessionLauncher.java
git commit -m "feat(testmode): construct TraceCameraFocusController on session start

Wires the focus controller into TraceSessionLauncher: built alongside
the LiveTraceComparator after gameplay bootstrap, registered on
GameLoop, and passed to TraceHudOverlay as the focus-label supplier.
Cleared on teardown and on partial-bootstrap failure.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 10: Manual smoke test and full test run

**Files:** none

- [ ] **Step 1: Run full test suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 2: Manual smoke test**

Build and run the engine with `TEST_MODE_ENABLED=true` and a populated `TRACE_CATALOG_DIR`. Pick any S2 trace from the picker.

Verify:
1. Press the configured pause key during gameplay → bottom-left HUD shows existing trace info; top-right shows `Camera: Default` and `<- -> Cycle Cameras`.
2. Press P1 RIGHT → top-right reads `Camera: Sidekick (Eng)` (or `Main (Eng)` if running solo); camera snaps to centre on the engine sidekick/main.
3. Press P1 RIGHT again → cycles to the next available focus; `Sidekick (Trace)` and `Main (Trace)` only appear when their position differs from the engine.
4. Press P1 LEFT from `Default` → wraps to the last available focus.
5. Press the frame-step key while focus = `Main (Eng)` → game advances exactly one step; the focus camera reappears immediately (no flicker of the original camera position visible to the user beyond one frame); object placement etc. behaves as if camera was at default during the step.
6. Unpause → camera snaps back to its pre-pause position; normal camera-follow behaviour resumes.
7. Press Esc to exit the trace session → returns to picker without errors.

- [ ] **Step 3: Update CHANGELOG.md**

Add an entry under the next unreleased version describing the new pause-time camera focus feature in trace test mode.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog entry for trace test mode camera focus

Trace Test Mode pause now allows cycling the camera between Default,
Sidekick (Eng/Trace), and Main (Eng/Trace) using P1 LEFT/RIGHT.
Camera state is restored on unpause; gameplay updates remain
deterministic across frame-step.

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Self-review

**Spec coverage:**
- Five focus modes — Task 2 (cycle list), Task 4 (centring) ✓
- Skip rules (positions match, sidekick absent, main despawned) — Task 2 ✓
- P1 LEFT/RIGHT cycling with wraparound — Task 4 ✓
- Camera mutation contract (only setX/setY) — Task 6 (regression guard) ✓
- Pause-edge snapshot/restore — Tasks 3, 8 ✓
- Frame-step path — Task 5 ✓
- Top-right HUD `Camera: <Mode>` + cycle hint — Task 7 ✓
- Activation gate (only during trace session) — Task 9 (controller only constructed/registered when session starts) ✓
- TraceHudOverlay backwards-compatible default — Task 7 (existing public constructor delegates with `() -> null`) ✓

**Placeholder scan:** every step has full code or full commands; no TBD/TODO; commit messages are concrete.

**Type consistency:** `currentLabel()`, `availableSize()`, `activeMode()`, `tick(InputHandler)` are consistent across tasks; `FocusMode.label()` referenced in HUD matches enum definition; `setTraceCameraFocusController` setter name reused identically in tasks 8 and 9.
