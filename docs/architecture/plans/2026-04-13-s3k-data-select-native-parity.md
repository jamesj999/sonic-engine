# S3K Data Select Native Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.

**Goal:** Replace the remaining placeholder native S3K Data Select behavior with a disassembly-backed selector, layout, rendering, and movement-SFX implementation that is credible enough to become the later donor presentation.

**Architecture:** Keep the existing save/session backend and host-profile logic, but replace the current synthetic S3K frontend with an authored save-screen scene driven by original object responsibilities from `Obj_SaveScreen_*`. Rendering should use real S3K save-menu assets and mapped objects, not `RECTI` overlays or text-first composition. Cross-game donation work remains paused until this plan is complete.

**Tech Stack:** Java 21, Maven, LWJGL/OpenGL fixed-function rendering, existing `GameServices` and `GraphicsManager`, S3K disassembly under `docs/skdisasm/`. Current and new tests must use JUnit 5 / Jupiter only; any JUnit 4 references below are historical migration context.

---

## File Structure

### New Files

- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectScene.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenObjectState.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenSelectorState.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenLayoutObjects.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveScreenSelectorState.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveScreenLayoutObjects.java`

### Files To Modify

- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java`
- `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSfx.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectManager.java`
- `src/test/java/com/openggf/TestGameLoop.java`
- `docs/S3K_KNOWN_DISCREPANCIES.md`

### Files To Remove From Production Path

- placeholder selector overlay code in `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- any remaining production dependence on synthetic `RECTI` cursor/highlight rendering in native S3K Data Select

### Design Notes

- Do not modify the S1/S2 donated Data Select routing in this plan.
- Do not resume cross-game donation implementation during this parity pass.
- Keep `SaveManager`, `SaveSessionContext`, `DataSelectSessionController`, and `DataSelectHostProfile` intact unless a task below explicitly adjusts their integration boundary.
- Use `docs/skdisasm/sonic3k.asm` and `docs/skdisasm/General/Save Menu/` as the authoritative reference set.

### Task 1: Lock In The Authored Save-Screen Scene Data

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenLayoutObjects.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveScreenLayoutObjects.java`

- [ ] **Step 1: Write the failing layout-object tests**

```java
@Test
void layoutObjects_matchObjDatSaveScreenAuthoredPositions() {
    S3kSaveScreenLayoutObjects objects = S3kSaveScreenLayoutObjects.original();

    assertEquals(0x120, objects.titleText().x());
    assertEquals(0x14C, objects.titleText().y());
    assertEquals(0x120, objects.selector().x());
    assertEquals(0x0E2, objects.selector().y());
    assertEquals(0x0B0, objects.noSave().x());
    assertEquals(0x0C8, objects.noSave().y());
    assertEquals(0x448, objects.delete().x());
    assertEquals(0x0D8, objects.delete().y());
    assertEquals(8, objects.slots().size());
    assertEquals(0x110, objects.slots().get(0).x());
    assertEquals(0x108, objects.slots().get(0).y());
    assertEquals(0x3E8, objects.slots().get(7).x());
    assertEquals(0x108, objects.slots().get(7).y());
}

@Test
void dataLoader_exposesSaveScreenMappingsWithoutSyntheticFrameSelection() throws Exception {
    S3kDataSelectDataLoader loader = realLoader();
    loader.loadData();

    assertFalse(loader.getSaveScreenMappings().isEmpty());
    assertTrue(loader.getSaveScreenMappings().size() >= 36);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kSaveScreenLayoutObjects" test`

Expected: FAIL because `S3kSaveScreenLayoutObjects` does not exist yet.

- [ ] **Step 3: Add a dedicated authored-object layout model**

```java
public record S3kSaveScreenLayoutObjects(
        LayoutEntry titleText,
        LayoutEntry selector,
        LayoutEntry delete,
        LayoutEntry noSave,
        List<LayoutEntry> slots) {

    public static S3kSaveScreenLayoutObjects original() {
        return new S3kSaveScreenLayoutObjects(
                new LayoutEntry(0x120, 0x14C, 3, -1),
                new LayoutEntry(0x120, 0x0E2, 1, -1),
                new LayoutEntry(0x448, 0x0D8, 0x0D, -1),
                new LayoutEntry(0x0B0, 0x0C8, 0, -1),
                List.of(
                        new LayoutEntry(0x110, 0x108, 0, 0),
                        new LayoutEntry(0x178, 0x108, 0, 1),
                        new LayoutEntry(0x1E0, 0x108, 0, 2),
                        new LayoutEntry(0x248, 0x108, 0, 3),
                        new LayoutEntry(0x2B0, 0x108, 0, 4),
                        new LayoutEntry(0x318, 0x108, 0, 5),
                        new LayoutEntry(0x380, 0x108, 0, 6),
                        new LayoutEntry(0x3E8, 0x108, 0, 7)));
    }

    public record LayoutEntry(int x, int y, int mappingFrame, int slotIndex) { }
}
```

- [ ] **Step 4: Use the dedicated layout model from the loader/presentation seam**

```java
public S3kSaveScreenLayoutObjects getLayoutObjects() {
    return S3kSaveScreenLayoutObjects.original();
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kSaveScreenLayoutObjects" test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenLayoutObjects.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveScreenLayoutObjects.java
git commit -m "test: lock in authored s3k save screen layout data"
```

### Task 2: Port The Native Selector State Machine And Movement SFX

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenSelectorState.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSfx.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveScreenSelectorState.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing selector/SFX tests**

```java
@Test
void selector_rightMove_advancesEntryAndQueuesSlotMachineSfx() {
    RecordingSfxBus sfxBus = new RecordingSfxBus();
    S3kSaveScreenSelectorState selector = S3kSaveScreenSelectorState.initial(sfxBus::play);

    selector.moveRight(false);

    assertEquals(1, selector.currentEntry());
    assertEquals(Sonic3kSfx.SLOT_MACHINE.id, sfxBus.lastSfxId());
}

@Test
void selector_moveInAlternateMode_usesSmallBumpersSfx() {
    RecordingSfxBus sfxBus = new RecordingSfxBus();
    S3kSaveScreenSelectorState selector = S3kSaveScreenSelectorState.initial(sfxBus::play);

    selector.moveLeft(true);

    assertEquals(Sonic3kSfx.SMALL_BUMPERS.id, sfxBus.lastSfxId());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kSaveScreenSelectorState,TestS3kDataSelectPresentation" test`

Expected: FAIL because the selector state machine and movement SFX dispatch do not exist yet.

- [ ] **Step 3: Add the selector state machine based on `Obj_SaveScreen_Selector`**

```java
public final class S3kSaveScreenSelectorState {
    private int currentEntry;
    private int selectorX = 0x0A8;
    private int cameraX;
    private int velocityX;
    private int moveFramesRemaining;
    private final IntConsumer sfxPlayer;

    public static S3kSaveScreenSelectorState initial(IntConsumer sfxPlayer) {
        return new S3kSaveScreenSelectorState(sfxPlayer, 0);
    }

    public void moveLeft(boolean alternateMode) {
        if (currentEntry == 0) {
            return;
        }
        currentEntry--;
        velocityX = -8;
        moveFramesRemaining = 0x0D;
        sfxPlayer.accept(alternateMode ? Sonic3kSfx.SMALL_BUMPERS.id : Sonic3kSfx.SLOT_MACHINE.id);
    }

    public void moveRight(boolean alternateMode) {
        if (currentEntry == 9) {
            return;
        }
        currentEntry++;
        velocityX = 8;
        moveFramesRemaining = 0x0D;
        sfxPlayer.accept(alternateMode ? Sonic3kSfx.SMALL_BUMPERS.id : Sonic3kSfx.SLOT_MACHINE.id);
    }
}
```

- [ ] **Step 4: Drive selector input through the new state machine**

```java
if (input.isKeyPressed(leftKey)) {
    selectorState.moveLeft(isAlternateSelectorMode());
    syncControllerSelectionFromSelector();
    return;
}
if (input.isKeyPressed(rightKey)) {
    selectorState.moveRight(isAlternateSelectorMode());
    syncControllerSelectionFromSelector();
    return;
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kSaveScreenSelectorState,TestS3kDataSelectPresentation" test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenSelectorState.java src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSfx.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveScreenSelectorState.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "feat: port s3k data select selector movement and sfx"
```

### Task 3: Replace The Placeholder Cursor With The Real Selector Rendering

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenObjectState.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing renderer test for placeholder removal**

```java
@Test
void renderer_doesNotRegisterRectiSelectionOverlayForNativeS3k() {
    RecordingGraphics graphics = new RecordingGraphics();
    S3kDataSelectRenderer renderer = new S3kDataSelectRenderer(graphics);

    renderer.draw(loadedAssets(), realSceneState());

    assertFalse(graphics.sawRecti(),
            "native S3K selector must render through authored mappings, not RECTI overlays");
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation" test`

Expected: FAIL because the current renderer still registers `GLCommand.RECTI`.

- [ ] **Step 3: Add a scene/object-state model that includes the real selector object**

```java
public record S3kSaveScreenObjectState(
        S3kSaveScreenLayoutObjects layout,
        S3kSaveScreenSelectorState selector,
        int noSavePlayerFrame,
        boolean deleteArmed,
        List<SaveSlotSummary> slotSummaries) {
}
```

- [ ] **Step 4: Render the selector through authored mappings instead of `RECTI`**

```java
private void renderSelector(GraphicsManager graphics, S3kSaveScreenObjectState state) {
    int selectorFrame = state.selector().mappingFrame();
    int x = state.selector().renderX() - 128;
    int y = state.layout().selector().y() - 128;
    drawMappedObject(graphics, saveScreenMappings.get(selectorFrame), x, y, MISC_PATTERN_BASE);
}
```

- [ ] **Step 5: Delete the placeholder overlay path**

```java
// Remove this entire production path:
// graphics.registerCommand(new GLCommand(GLCommand.CommandType.RECTI, ...));
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation" test`

Expected: PASS with no `RECTI` selector usage.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenObjectState.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "feat: render native s3k selector from authored mappings"
```

### Task 4: Fix Background And Authored Object Placement

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing placement tests**

```java
@Test
void renderer_placesBackgroundAtOriginalScreenOrigin() {
    RecordingGraphics graphics = new RecordingGraphics();
    S3kDataSelectRenderer renderer = new S3kDataSelectRenderer(graphics);

    renderer.draw(loadedAssets(), realSceneState());

    assertTrue(graphics.renderedTileAt(0, 0),
            "save-menu background should anchor at screen origin");
}

@Test
void renderer_placesNoSaveDeleteAndSlotsAtObjDatSaveScreenPositions() {
    RecordingGraphics graphics = new RecordingGraphics();
    S3kDataSelectRenderer renderer = new S3kDataSelectRenderer(graphics);

    renderer.draw(loadedAssets(), realSceneState());

    assertTrue(graphics.renderedNear(0x0B0 - 128, 0x0C8 - 128));
    assertTrue(graphics.renderedNear(0x448 - 128, 0x0D8 - 128));
    assertTrue(graphics.renderedNear(0x110 - 128, 0x108 - 128));
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation" test`

Expected: FAIL because the current composition still mixes authored positions with synthetic tilemap placement.

- [ ] **Step 3: Render the background and authored objects from the scene model**

```java
graphics.beginPatternBatch();
renderMenuBackground(graphics, assets);
renderTitleText(graphics, state.layout().titleText());
renderNoSave(graphics, state);
renderDelete(graphics, state);
renderSaveSlots(graphics, state);
renderSelector(graphics, state);
graphics.flushPatternBatch();
```

- [ ] **Step 4: Stop selecting slot visuals through synthetic card tilemaps**

```java
private void renderSaveSlots(GraphicsManager graphics, S3kSaveScreenObjectState state) {
    for (int i = 0; i < state.layout().slots().size(); i++) {
        LayoutEntry slot = state.layout().slots().get(i);
        SaveSlotSummary summary = state.slotSummaries().get(i);
        int frame = resolveAuthoredSlotFrame(summary);
        drawMappedObject(graphics, saveScreenMappings.get(frame), slot.x() - 128, slot.y() - 128, MISC_PATTERN_BASE);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation" test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "fix: place native s3k save screen from authored layout data"
```

### Task 5: Port No-Save, Save-Slot, Clear-Slot, And Delete Visual State

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenObjectState.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectManager.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing parity tests for slot visual state**

```java
@Test
void occupiedSlot_usesAuthoredSavedPresentationInsteadOfNewFrame() {
    S3kSaveScreenObjectState state = sceneStateWithValidSlot();

    assertNotEquals(renderer.resolveAuthoredSlotFrame(emptySummary()),
            renderer.resolveAuthoredSlotFrame(validSummary()));
}

@Test
void clearSlot_usesClearPresentationAndRestrictedRestartSelection() {
    S3kSaveScreenObjectState state = sceneStateWithClearSlot();

    assertTrue(state.selector().isOnClearSlot());
    assertEquals(DataSelectActionType.CLEAR_RESTART, confirmAction(state).type());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectManager,TestS3kDataSelectPresentation" test`

Expected: FAIL because slot visuals are still too synthetic.

- [ ] **Step 3: Move no-save/save-slot/delete visual decisions into the authored scene state**

```java
public S3kSaveScreenObjectState buildSceneState() {
    return new S3kSaveScreenObjectState(
            layoutObjects,
            selectorState,
            resolveNoSavePlayerFrame(),
            menuModel().isDeleteMode(),
            sessionController.slotSummaries());
}
```

- [ ] **Step 4: Render occupied, clear, and empty slots through dedicated authored frame selection**

```java
int resolveAuthoredSlotFrame(SaveSlotSummary summary) {
    if (summary.state() == SaveSlotState.EMPTY) {
        return FRAME_NEW_SLOT;
    }
    if (Boolean.TRUE.equals(summary.payload().get("clear"))) {
        return FRAME_CLEAR_SLOT;
    }
    return FRAME_ACTIVE_SAVE_SLOT;
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectManager,TestS3kDataSelectPresentation" test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveScreenObjectState.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectManager.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "feat: port native s3k save slot visual states"
```

### Task 6: Restore Full Native Interaction Parity And Document Remaining Gaps

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`

- [ ] **Step 1: Write the failing end-to-end parity tests**

```java
@Test
void nativeS3kDataSelect_exitStillDispatchesBackendActionAfterParityRefactor() {
    DataSelectAction action = launchAndConfirmFirstSlot();

    assertEquals(DataSelectActionType.NEW_SLOT_START, action.type());
}

@Test
void nativeS3kDataSelect_cursorMovementPlaysOriginalSfxAndNeverUsesPlaceholderOverlay() {
    NativeS3kParityProbe probe = runNativeS3kDataSelectProbe();

    assertTrue(probe.playedMovementSfx());
    assertFalse(probe.usedPlaceholderOverlay());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestGameLoop,TestS3kDataSelectPresentation,TestS3kDataSelectManager" test`

Expected: FAIL until the full parity path is wired together.

- [ ] **Step 3: Finish integration and remove stale placeholder assumptions**

```java
// Keep backend action flow unchanged:
if (action.type() != DataSelectActionType.NONE) {
    sessionController.queuePendingAction(action);
    state = State.EXITING;
}
```

```java
// Update discrepancy docs to reflect remaining non-parity items only.
```

- [ ] **Step 4: Run the verification suite**

Run: `mvn -Dmse=off "-Dtest=TestS3kSaveScreenLayoutObjects,TestS3kSaveScreenSelectorState,TestS3kDataSelectPresentation,TestS3kDataSelectManager,TestGameLoop" test`

Expected: PASS.

- [ ] **Step 5: Manual validation**

Run:

```bash
run.cmd
```

Expected manual checks:
- background lines up with the original save screen
- selector is no longer a yellow rectangle
- left/right movement plays the original menu SFX
- no-save, occupied slot, clear slot, and delete paths all remain functional

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/test/java/com/openggf/TestGameLoop.java docs/S3K_KNOWN_DISCREPANCIES.md
git commit -m "feat: restore native s3k data select parity"
```

## Self-Review

### Spec Coverage

- Native S3K parity before donation work: covered by Tasks 1-6 and explicitly scoped in the architecture.
- Background/layout correctness: Tasks 1 and 4.
- Real selector behavior and removal of placeholder rectangle: Tasks 2 and 3.
- Movement SFX: Task 2 and end-to-end verification in Task 6.
- Slot/no-save/delete/clear interaction parity: Tasks 5 and 6.
- Manual visual validation requirement: Task 6.

No uncovered spec requirements remain.

### Placeholder Scan

- No `TODO`, `TBD`, or “implement later” placeholders remain.
- All tasks include exact file paths, tests, commands, and concrete code snippets.

### Type Consistency

- New scene types are referenced consistently as:
  - `S3kDataSelectScene`
  - `S3kSaveScreenObjectState`
  - `S3kSaveScreenSelectorState`
  - `S3kSaveScreenLayoutObjects`
- Existing engine/backend seams remain named consistently with current code:
  - `DataSelectSessionController`
  - `SaveManager`
  - `DataSelectActionType`

