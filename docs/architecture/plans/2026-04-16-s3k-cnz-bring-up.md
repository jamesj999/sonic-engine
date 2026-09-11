# S3K CNZ Bring-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Sonic 3 & Knuckles Carnival Night Zone onto the shared runtime stack with ROM-backed parity for events, deform, animated tiles, palette cycling, object-driven sequences, and validation.

**Architecture:** Keep CNZ state explicit in `CnzZoneRuntimeState` and `Sonic3kCNZEvents`, publish the ROM-equivalent deform outputs consumed by `AnimateTiles_CNZ`, and route object-owned side effects through a CNZ event bridge instead of hidden local flags. Reuse the existing S3K runtime frameworks for palette ownership, PLC loading, live layout mutation, water control, and headless testing so CNZ behavior stays traceable to the disassembly while fitting current engine patterns.

**Tech Stack:** Java 21, Maven, JUnit 5, S3K ROM (`Sonic and Knuckles & Sonic 3 (W) [!].gen`), `HeadlessTestFixture`, `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`, `S3kPaletteWriteSupport`, `S3kSeamlessMutationExecutor`, and the S&K-side disassembly in `docs/skdisasm/sonic3k.asm`.

---

## Shared Context

### File Map

- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
  Own the CNZ event instance, runtime installation, and object-event bridge implementation.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
  Expand the collapsed CNZ event model into explicit Act 1 and Act 2 FG/BG routines, published deform outputs, transition flags, and object-consumed side effects.
- **Create:** `src/main/java/com/openggf/game/sonic3k/events/CnzObjectEventBridge.java`
  Define the narrow write surface CNZ objects use to signal arena destruction, water targets, teleporter progress, and boss-route events.
- **Create:** `src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java`
  Provide object-safe bridge helpers mirroring the AIZ and HCZ patterns.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java`
  Expose the CNZ runtime fields used by scroll, animated tile, palette, and object code.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java`
  Publish the deform outputs equivalent to `Events_bg+$10` and `Camera_X_pos_BG_copy`, and route boss scroll from explicit CNZ event state.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
  Restore `AnimateTiles_CNZ` phase derivation and install CNZ animated-tile graph channels.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/S3kAnimatedTileChannels.java`
  Add the CNZ script channels plus the custom direct-DMA channel at tile `$308+`.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
  Finish `AnPal_CNZ`, including underwater mirroring and any event-gated channels.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/S3kPaletteOwners.java`
  Add explicit owners for CNZ teleporter and boss-driven palette writes.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
  Add CNZ art, mapping, palette, and PLC constants verified against the S&K disassembly.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java`
  Add the missing CNZ object IDs (`0x88`, `0x89`, `0xA6`, `0xA7`) as named constants.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`
  Add CNZ teleporter, miniboss, and end-boss art keys.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
  Load any new CNZ sheets or mapping-driven level-art sheets.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
  Register the CNZ-specific object sheets.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
  Replace placeholders with CNZ-specific factories for the missing water, miniboss, teleporter, and end-boss objects.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
  Own the Act 1 boss object, palette load, arena lock, damage rules, and defeat flow.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java`
  Own the top-piece collision, arena block destruction writes, and base-hit signaling.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java`
  Own `Events_bg+$08/$0C`-equivalent scroll acceleration and the two-stage `Events_fg_5` handoff.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzTeleporterInstance.java`
  Own the Knuckles teleporter cutscene, PLC/art load, control lock, and palette writes.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzTeleporterBeamInstance.java`
  Own the teleporter-beam child spawned after art load and landing.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzEggCapsuleInstance.java`
  Own the fixed-position CNZ `Obj_EggCapsule` path unless the HCZ ground capsule can be reused unchanged after verification.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzWaterLevelCorkFloorInstance.java`
  Own the object-driven water target change to `$958`.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzWaterLevelButtonInstance.java`
  Own the armed button path that raises target water to `$A58`.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossInstance.java`
  Own the Act 2 end boss startup, palette/PLC load, capsule release, and post-boss control restoration.
- **Create:** `src/test/java/com/openggf/game/sonic3k/TestCnzZoneRuntimeState.java`
  Unit-test the runtime adapter and published CNZ state surface.
- **Create:** `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPatternAnimation.java`
  Cover `AnimateTiles_CNZ` phase derivation and DMA ownership.
- **Modify:** `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPaletteCycling.java`
  Extend the existing CNZ palette tests with underwater mirroring coverage.
- **Modify:** `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzRuntimeStateRegistration.java`
  Keep the existing runtime-registration canary green while expanding the CNZ runtime surface.
- **Modify:** `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzScroll.java`
  Keep the existing CNZ scroll-parity canary green while adding deform-output publication coverage.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzAct1EventFlow.java`
  Headless coverage for the Act 1 miniboss path, two-stage `Events_fg_5` handoff, and seamless reload request.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java`
  Verify boss-scroll routing and deform-output publication from `SwScrlCnz`.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzMinibossArenaHeadless.java`
  Cover arena destruction, row accumulation, and miniboss lowering.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java`
  Cover the Knuckles teleporter route, PLC load, capsule spawn, control lock, and camera clamp.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzWaterHelpersHeadless.java`
  Cover the two object-driven water target changes.
- **Create:** `docs/architecture/validation/2026-04-16-s3k-cnz-validation.md`
  Record the ROM/disassembly-backed visual validation results for all required CNZ beats.

### ROM Anchors To Cite In Code

- `CNZ1_ScreenEvent`
- `CNZ1_BackgroundEvent`
- `CNZ1_BossLevelScroll`
- `CNZ1_BossLevelScroll2`
- `CNZ1_Deform`
- `CNZ2_ScreenEvent`
- `CNZ2_BackgroundEvent`
- `AnimateTiles_CNZ`
- `AniPLC_CNZ`
- `AnPal_CNZ`
- `Obj_CNZMinibossScrollControl`
- `Obj_CNZMiniboss`
- `Obj_CNZTeleporter`
- `Obj_TeleporterBeam`
- `Obj_CNZWaterLevelCorkFloor`
- `Obj_CNZWaterLevelButton`
- `Obj_CNZEndBoss`
- `PLC_EggCapsule`
- `ArtKosM_CNZTeleport`
- `ShakeScreen_Setup`

### Default Verification Commands

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzZoneRuntimeState,TestS3kCnzPatternAnimation,TestS3kCnzPaletteCycling,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzAct1EventFlow,TestS3kCnzBossScrollHandler,TestS3kCnzMinibossArenaHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzWaterHelpersHeadless"
```

```bash
mvn -q -DskipTests package "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

### Documentation Rule For Every Task

- Add class Javadocs that name the exact CNZ routines or object labels being modeled.
- Add method Javadocs for all non-trivial state transitions, deform formulas, palette writes, PLC loads, and cutscene steps.
- Add block comments for ROM thresholds, byte/word counters, palette line targets, VRAM tile destinations, and any engine adaptation.

### Trace Replay Scope

- Do not block CNZ bring-up on a new trace-replay stack.
- If an existing replay path can be adapted cheaply for one CNZ sequence, record that as an extra validation gain in the final report.
- The required automated gate for this plan remains the JUnit 5 unit and headless coverage listed above.

## Task 1: Slice 0 Runtime Surface And CNZ Object Bridge

**Spec slice:** Slice 0

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/events/CnzObjectEventBridge.java`
- Create: `src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Test: `src/test/java/com/openggf/game/sonic3k/TestCnzZoneRuntimeState.java`

- [ ] **Step 1: Write the failing runtime-surface test**

```java
@Test
void cnzRuntimeStateExposesPublishedFields() {
    Sonic3kCNZEvents events = new Sonic3kCNZEvents();
    events.init(0);
    events.forceForegroundRoutine(0x08);
    events.forceBackgroundRoutine(0x0C);
    events.setPublishedDeformInputs(0x24, 0x30);
    events.setBossScrollState(0x120, 0x40000);
    events.setWallGrabSuppressed(true);
    events.setWaterTargetY(0x0A58);

    CnzZoneRuntimeState state = new CnzZoneRuntimeState(0, PlayerCharacter.KNUCKLES, events);

    assertEquals(0x08, state.foregroundRoutine());
    assertEquals(0x0C, state.backgroundRoutine());
    assertEquals(0x24, state.deformPhaseBgX());
    assertEquals(0x30, state.publishedBgCameraX());
    assertEquals(0x120, state.bossScrollOffsetY());
    assertEquals(0x40000, state.bossScrollVelocityY());
    assertTrue(state.isWallGrabSuppressed());
    assertEquals(0x0A58, state.waterTargetY());
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzZoneRuntimeState"
```

Expected: compile failure for missing CNZ runtime accessors and missing CNZ bridge/write-support types.

- [ ] **Step 3: Add the CNZ bridge, write support, and HCZ-style local FG/BG routines**

Use the `Sonic3kHCZEvents` pattern here: add CNZ-local `fgRoutine` / `bgRoutine` fields plus explicit accessors on `Sonic3kCNZEvents`. Do not try to reach into `AbstractLevelEventManager`'s protected FG/BG counters from the zone event class.

```java
public interface CnzObjectEventBridge {
    void queueArenaChunkDestruction(int chunkWorldX, int chunkWorldY);
    void setBossScrollState(int offsetY, int velocityY);
    void setBossFlag(boolean value);
    void setEventsFg5(boolean value);
    void setWallGrabSuppressed(boolean value);
    void setWaterButtonArmed(boolean value);
    boolean isWaterButtonArmed();
    void setWaterTargetY(int targetY);
    void beginKnucklesTeleporterRoute();
    void markTeleporterBeamSpawned();
}
```

```java
public final class Sonic3kCNZEvents extends Sonic3kZoneEvents {
    private int fgRoutine;
    private int bgRoutine;

    public int getForegroundRoutine() { return fgRoutine; }
    public int getBackgroundRoutine() { return bgRoutine; }
    void forceForegroundRoutine(int routine) { this.fgRoutine = routine; }
    void forceBackgroundRoutine(int routine) { this.bgRoutine = routine; }
    void forceBossBackgroundMode(BossBackgroundMode mode) { this.bossBackgroundMode = mode; }
}
```

```java
public final class S3kCnzEventWriteSupport {
    public static void queueArenaChunkDestruction(ObjectServices services, int chunkWorldX, int chunkWorldY) { }
    public static void setBossScrollState(ObjectServices services, int offsetY, int velocityY) { }
    public static void setBossFlag(ObjectServices services, boolean value) { }
    public static void setEventsFg5(ObjectServices services, boolean value) { }
    public static void setWaterTargetY(ObjectServices services, int targetY) { }
    public static void setWaterButtonArmed(ObjectServices services, boolean value) { }
    public static boolean isWaterButtonArmed(ObjectServices services) { return false; }
    public static void beginKnucklesTeleporterRoute(ObjectServices services) { }
    public static void markTeleporterBeamSpawned(ObjectServices services) { }
}
```

```java
public final class CnzZoneRuntimeState implements S3kZoneRuntimeState {
    public Sonic3kCNZEvents events() { return events; }
    public int foregroundRoutine() { return events.getForegroundRoutine(); }
    public int backgroundRoutine() { return events.getBackgroundRoutine(); }
    public int deformPhaseBgX() { return events.getDeformPhaseBgX(); }
    public int publishedBgCameraX() { return events.getPublishedBgCameraX(); }
    public int bossScrollOffsetY() { return events.getBossScrollOffsetY(); }
    public int bossScrollVelocityY() { return events.getBossScrollVelocityY(); }
    public boolean isWallGrabSuppressed() { return events.isWallGrabSuppressed(); }
    public int waterTargetY() { return events.getWaterTargetY(); }
}
```

- [ ] **Step 4: Run the runtime test and a compile sanity pass**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzZoneRuntimeState"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/CnzObjectEventBridge.java src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java src/test/java/com/openggf/game/sonic3k/TestCnzZoneRuntimeState.java
git commit -m "feat: add cnz runtime state bridge"
```

## Task 2: Act 1 And Act 2 CNZ Event State Machines

**Spec slice:** Slices 1 and 2

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzAct1EventFlow.java`

**Boundary note:** These tests intentionally drive `Sonic3kCNZEvents` through direct test hooks before Task 7 wires the real object -> bridge -> events path.

- [ ] **Step 1: Write the failing Act 1 and Act 2 event-flow tests**

```java
@Test
void firstEventsFg5StartsFgRefresh_notActReload() {
    Sonic3kCNZEvents events = new Sonic3kCNZEvents();
    events.init(0);
    events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_AFTER_BOSS);
    events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_POST_BOSS);
    events.setEventsFg5(true);

    events.update(0, 0);

    assertEquals(Sonic3kCNZEvents.BG_FG_REFRESH, events.getBackgroundRoutine());
    assertFalse(events.isAct2TransitionRequested());
}

@Test
void secondEventsFg5AtTransitionStageRequestsSeamlessActSwap() {
    Sonic3kCNZEvents events = new Sonic3kCNZEvents();
    events.init(0);
    events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_DO_TRANSITION);
    events.setEventsFg5(true);

    events.update(0, 1);

    assertTrue(events.isAct2TransitionRequested());
    assertEquals(0x301, events.getPendingZoneActWord());
}

@Test
void act2KnucklesEntryStartsTeleporterRoute_notModeOnly() {
    Sonic3kCNZEvents events = new Sonic3kCNZEvents();
    events.init(1);
    events.beginKnucklesTeleporterRoute();

    assertEquals(Sonic3kCNZEvents.FG_ACT2_KNUCKLES_ROUTE, events.getForegroundRoutine());
    assertTrue(events.isTeleporterRouteActive());
    assertEquals(0x4750, events.getCameraMinXClamp());
    assertEquals(0x48E0, events.getCameraMaxXClamp());
}
```

- [ ] **Step 2: Run the event-flow tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzAct1EventFlow"
```

Expected: FAIL because the current CNZ event class still collapses the post-boss chain and the Knuckles teleporter route.

- [ ] **Step 3: Implement the ROM-shaped CNZ event stages with heavy Javadocs**

Keep CNZ's `fgRoutine` and `bgRoutine` as local fields on `Sonic3kCNZEvents`, mirroring `Sonic3kHCZEvents`. Add `forceBackgroundRoutine(...)` and `forceBossBackgroundMode(...)` test hooks alongside the normal production getters so the unit tests are explicit about their seam.

```java
public static final int BG_NORMAL = 0x00;
public static final int BG_BOSS_START = 0x04;
public static final int BG_BOSS = 0x08;
public static final int BG_AFTER_BOSS = 0x0C;
public static final int BG_FG_REFRESH = 0x10;
public static final int BG_FG_REFRESH_2 = 0x14;
public static final int BG_DO_TRANSITION = 0x18;

public static final int FG_ACT2_ENTRY = 0x00;
public static final int FG_ACT2_KNUCKLES_ROUTE = 0x04;
public static final int FG_ACT2_NORMAL = 0x08;
```

```java
private void updateAct1Background(int frameCounter) {
    switch (bgRoutine) {
        case BG_NORMAL -> handleAct1NormalEntry();
        case BG_BOSS_START -> handleAct1BossStart();
        case BG_BOSS -> handleAct1BossLoop();
        case BG_AFTER_BOSS -> handleAct1AfterBoss();
        case BG_FG_REFRESH -> handleAct1FgRefresh();
        case BG_FG_REFRESH_2 -> handleAct1FgRefresh2();
        case BG_DO_TRANSITION -> handleAct1DoTransition();
        default -> { }
    }
}
```

```java
private void updateAct2Foreground() {
    switch (fgRoutine) {
        case FG_ACT2_ENTRY -> handleAct2EntryBranch();
        case FG_ACT2_KNUCKLES_ROUTE -> handleAct2KnucklesRoute();
        case FG_ACT2_NORMAL -> handleAct2NormalDraw();
        default -> { }
    }
}
```

```java
private void handleAct1DoTransition() {
    if (!eventsFg5) {
        return;
    }
    eventsFg5 = false;
    applyPlc(0x18);
    applyPlc(0x19);
    act2TransitionRequested = true;
    pendingZoneActWord = 0x0301;
    transitionWorldOffsetX = -0x3000;
    transitionWorldOffsetY = 0x0200;
    wallGrabSuppressed = false;
}
```

- [ ] **Step 4: Run the event-flow tests**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzAct1EventFlow"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java src/test/java/com/openggf/tests/TestS3kCnzAct1EventFlow.java
git commit -m "feat: restore cnz event chains"
```

## Task 3: CNZ Scroll Handler Parity And Deform Output Publication

**Spec slice:** Slice 0

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java`

- [ ] **Step 1: Write the failing scroll-handler tests**

```java
@Test
void normalCnzDeformPublishesBothAnimatedTileInputs() {
    SwScrlCnz handler = new SwScrlCnz();
    int[] hscroll = new int[224];

    handler.update(hscroll, 0x2000, 0x300, 0, 0);

    CnzZoneRuntimeState state = GameServices.zoneRuntimeRegistry().currentAs(CnzZoneRuntimeState.class).orElseThrow();
    assertEquals(0x0A00, state.deformPhaseBgX());
    assertEquals(0x0E00, state.publishedBgCameraX());
}
```

- [ ] **Step 2: Run the scroll tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzBossScrollHandler"
```

Expected: FAIL because `SwScrlCnz` currently computes the BG values locally and does not publish them.

- [ ] **Step 3: Port the CNZ deform math and publish the ROM-equivalent outputs**

```java
private short cnzBgXSevenSixteenths(int cameraX) {
    return negWord((short) (asrWord(cameraX, 1) - asrWord(cameraX, 4)));
}

private int cnzPhaseSourceFiveSixteenths(int cameraX) {
    return (cameraX >> 2) + (cameraX >> 4);
}

private void publishDeformOutputs(int cameraX, CnzZoneRuntimeState state) {
    int phaseSource = cnzPhaseSourceFiveSixteenths(cameraX);
    int bgCameraX = (cameraX >> 2) + (cameraX >> 3) + (cameraX >> 4);
    state.events().setPublishedDeformInputs(phaseSource, bgCameraX);
}
```

- [ ] **Step 4: Run the scroll tests and the runtime test together**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzZoneRuntimeState,TestS3kCnzBossScrollHandler"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java
git commit -m "feat: publish cnz deform outputs"
```

## Task 4: `AnimateTiles_CNZ` Phase Logic, AniPLC Registration, And Direct DMA Ownership

**Spec slice:** Slice 4

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/S3kAnimatedTileChannels.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Test: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPatternAnimation.java`

- [ ] **Step 1: Write the failing CNZ pattern-animation tests**

```java
@Test
void cnzPhaseUsesPublishedDeformInputs() {
    Sonic3kPatternAnimator animator = new Sonic3kPatternAnimator(level, 0x03, 0);
    CnzZoneRuntimeState state = GameServices.zoneRuntimeRegistry().currentAs(CnzZoneRuntimeState.class).orElseThrow();
    state.events().setPublishedDeformInputs(0x1C, 0x08);

    assertEquals(0x14, animator.computeCnzPhase());
}

@Test
void cnzAnimatedTileGraphInstallsCnzCustomChannel() {
    Sonic3kPatternAnimator animator = new Sonic3kPatternAnimator(level, 0x03, 0);
    assertTrue(animator.debugChannelIds().contains("s3k.cnz.scroll"));
}
```

- [ ] **Step 2: Run the CNZ pattern-animation tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzPatternAnimation"
```

Expected: FAIL because CNZ still uses the free-running pachinko animation path instead of the ROM phase formula.

- [ ] **Step 3: Implement CNZ through `S3kAnimatedTileChannels`, not a one-off animator branch**

Follow the existing HCZ/SOZ channel-graph pattern. The CNZ direct-DMA work still lives in `Sonic3kPatternAnimator`, but it should be invoked through a CNZ custom `AnimatedTileChannel` so script ownership and the `$308+` direct-DMA ownership split stay explicit in one graph installation path.

Add a narrow package-private test accessor such as `debugChannelIds()` on `Sonic3kPatternAnimator` if the graph contents are otherwise opaque in `TestS3kCnzPatternAnimation`.

```java
int computeCnzPhase() {
    CnzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
            .currentAs(CnzZoneRuntimeState.class)
            .orElse(null);
    if (state == null) {
        return 0;
    }
    return (state.deformPhaseBgX() - state.publishedBgCameraX()) & 0x3F;
}
```

```java
static List<AnimatedTileChannel> buildCnzChannels(Sonic3kPatternAnimator owner,
                                                  List<AniPlcScriptState> scripts) {
    List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 1);
    for (int i = 0; i < scripts.size(); i++) {
        AniPlcScriptState script = scripts.get(i);
        channels.add(new AnimatedTileChannel(
                "s3k.cnz.script." + i,
                owner::shouldRunScriptChannels,
                ctx -> ctx.frameCounter(),
                scriptDestination(script),
                AnimatedTileCachePolicy.ALWAYS,
                ctx -> owner.tickScript(script)
        ));
    }
    channels.add(new AnimatedTileChannel(
            "s3k.cnz.scroll",
            owner::shouldRunCnzCustomChannels,
            ctx -> owner.computeCnzPhase(),
            new DestinationPlan(0x308, 0x327),
            AnimatedTileCachePolicy.ON_PHASE_CHANGE,
            new SplitTransferApplyStrategy(owner::updateCnzBackgroundTilesForGraph)
    ));
    return channels;
}
```

- [ ] **Step 4: Run the CNZ pattern-animation tests and package build**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzPatternAnimation"
mvn -q -DskipTests package "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: test PASS, package PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java src/main/java/com/openggf/game/sonic3k/S3kAnimatedTileChannels.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/test/java/com/openggf/game/sonic3k/TestS3kCnzPatternAnimation.java
git commit -m "feat: restore cnz animated tile phase"
```

## Task 5: `AnPal_CNZ`, Underwater Mirroring, And CNZ Palette Ownership

**Spec slice:** Slice 4

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/S3kPaletteOwners.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- Test: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPaletteCycling.java`

- [ ] **Step 1: Add the failing underwater-mirroring test to the existing CNZ palette test file**

```java
@Test
void cnzPaletteCycleMirrorsNormalWritesIntoWaterPalette() {
    Palette normal = level.getPalette(2);
    Palette water = level.getWaterPalette(2);
    int beforeNormal = normal.getColor(7).toPackedRgb();
    int beforeWater = water.getColor(7).toPackedRgb();

    tickCnzPalette(8);

    assertNotEquals(beforeNormal, normal.getColor(7).toPackedRgb());
    assertEquals(normal.getColor(7).toPackedRgb(), water.getColor(7).toPackedRgb());
    assertNotEquals(beforeWater, water.getColor(7).toPackedRgb());
}
```

- [ ] **Step 2: Run the palette tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzPaletteCycling"
```

Expected: FAIL because the current CNZ cycle only mutates the normal palette.

- [ ] **Step 3: Port all three CNZ palette channels through ownership-aware writes**

```java
private void applyCnzMirroredWrite(int paletteLine, int colorIndex, byte[] data, int offset) {
    S3kPaletteWriteSupport.applyColorPair(
            registry,
            level,
            GameServices.graphics(),
            S3kPaletteOwners.CNZ_ANPAL,
            S3kPaletteOwners.PRIORITY_ZONE_EVENT,
            paletteLine,
            colorIndex,
            data,
            offset,
            true);
}
```

```java
private void applyCnzTeleporterPalette(byte[] line2Data) {
    S3kPaletteWriteSupport.applyLine(
            registry,
            level,
            GameServices.graphics(),
            S3kPaletteOwners.CNZ_TELEPORTER,
            S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
            1,
            line2Data,
            true);
}
```

- [ ] **Step 4: Run the palette tests and a broader CNZ animation subset**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzPaletteCycling,TestS3kCnzPatternAnimation"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java src/main/java/com/openggf/game/sonic3k/S3kPaletteOwners.java src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java src/test/java/com/openggf/game/sonic3k/TestS3kCnzPaletteCycling.java
git commit -m "feat: finish cnz palette ownership"
```

## Task 6: CNZ Art, Constants, Object IDs, And Factory Registration

**Spec slice:** Infrastructure supporting Slices 1, 3, 4, and 5

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
- Test: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPatternAnimation.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java`

- [ ] **Step 1: Write the failing art-registration assertions**

```java
@Test
void cnzObjectIdsAndArtKeysResolve() {
    assertEquals(0x88, Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR);
    assertEquals(0x89, Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON);
    assertEquals(0xA6, Sonic3kObjectIds.CNZ_MINIBOSS);
    assertEquals(0xA7, Sonic3kObjectIds.CNZ_END_BOSS);
    assertNotNull(provider.getRenderer(Sonic3kObjectArtKeys.CNZ_TELEPORTER));
}
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzPatternAnimation,TestS3kCnzTeleporterRouteHeadless"
```

Expected: compile failure for missing CNZ IDs or runtime failure from missing art registration.

- [ ] **Step 3: Add the verified CNZ constants, art keys, and registry wiring**

```java
public static final int CNZ_WATER_LEVEL_CORK_FLOOR = 0x88;
public static final int CNZ_WATER_LEVEL_BUTTON = 0x89;
public static final int CNZ_MINIBOSS = 0xA6;
public static final int CNZ_END_BOSS = 0xA7;
```

```java
public static final String CNZ_TELEPORTER = "cnz_teleporter";
public static final String CNZ_MINIBOSS = "cnz_miniboss";
public static final String CNZ_END_BOSS = "cnz_end_boss";
```

```java
factories.put(Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR, (spawn, registry) -> new CnzWaterLevelCorkFloorInstance(spawn));
factories.put(Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON, (spawn, registry) -> new CnzWaterLevelButtonInstance(spawn));
factories.put(Sonic3kObjectIds.CNZ_MINIBOSS, (spawn, registry) -> new CnzMinibossInstance(spawn));
factories.put(Sonic3kObjectIds.CNZ_END_BOSS, (spawn, registry) -> new CnzEndBossInstance(spawn));
```

- [ ] **Step 4: Run the targeted tests again**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzPatternAnimation,TestS3kCnzTeleporterRouteHeadless"
```

Expected: compile succeeds and missing-art failures move on to object-behavior gaps only.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat: register cnz object art and ids"
```

## Task 7: Act 1 Miniboss, Scroll-Control, Arena Destruction, And Water Helpers

**Spec slice:** Slices 1 and 5

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzWaterLevelCorkFloorInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzWaterLevelButtonInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzMinibossArenaHeadless.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzWaterHelpersHeadless.java`

- [ ] **Step 1: Write the failing miniboss and water-helper tests**

```java
@Test
void minibossTopHitQueuesArenaChunkRemovalAndLowersBossBase() {
    teleportToCnzMinibossArena();
    CnzMinibossTopInstance top = spawnCnzMinibossTop();

    top.forceArenaCollisionForTest(0x3200, 0x0300);
    fixture.stepIdleFrames(1);

    Sonic3kCNZEvents events = getCnzEvents();
    assertEquals(0x3200, events.getPendingArenaChunkX());
    assertEquals(0x0300, events.getPendingArenaChunkY());
    assertTrue(events.getDestroyedArenaRows() >= 0x20);
}

@Test
void waterHelpersRaiseTargetWaterToRomHeights() {
    spawnAndResolveCorkFloorHelper();
    assertEquals(0x0958, GameServices.water().getTargetWaterLevel());

    armButtonAndPressIt();
    assertEquals(0x0A58, GameServices.water().getTargetWaterLevel());
}

@Test
void scrollControlBridgeSignalAdvancesCnzEventState() {
    teleportToCnzMinibossArena();
    CnzMinibossScrollControlInstance control = spawnCnzScrollControl();

    control.forceBossDefeatSignalForTest();
    control.forceAccumulatedOffsetForTest(0x01C0_0000);
    fixture.stepIdleFrames(1);

    Sonic3kCNZEvents events = getCnzEvents();
    events.update(0, 1);
    assertEquals(Sonic3kCNZEvents.BG_FG_REFRESH, events.getBackgroundRoutine());
}
```

- [ ] **Step 2: Run the miniboss and water-helper tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzMinibossArenaHeadless,TestS3kCnzWaterHelpersHeadless"
```

Expected: FAIL because the CNZ-specific objects do not exist yet.

- [ ] **Step 3: Implement the Act 1 object family and object-driven water writes**

Make the object -> bridge -> events dependency explicit here. The miniboss scroll-control object must be verified as the producer of the same `Events_fg_5` transition that Task 2 first covered with direct test hooks.

```java
public final class CnzMinibossScrollControlInstance extends AbstractObjectInstance {
    private int currentVelocity;
    private int accumulatedOffset;
    private boolean bossDefeatSignalConsumed;

    @Override
    public void update() {
        int nextVelocity = Math.min(currentVelocity + 0x4000, 0x40000);
        accumulatedOffset += nextVelocity;
        S3kCnzEventWriteSupport.setBossScrollState(services(), accumulatedOffset >> 16, nextVelocity);
        if (bossDefeatSignalConsumed && (accumulatedOffset >> 16) >= 0x1C0) {
            S3kCnzEventWriteSupport.setEventsFg5(services(), true);
            destroy();
        }
    }
}
```

```java
public final class CnzWaterLevelButtonInstance extends AbstractObjectInstance {
    @Override
    public void update() {
        if (isPressedByPlayer() && S3kCnzEventWriteSupport.isWaterButtonArmed(services())) {
            S3kCnzEventWriteSupport.setWaterTargetY(services(), 0x0A58);
            services().audioManager().playSfx(Sonic3kSfx.GEYSER.id);
            destroy();
        }
    }
}
```

- [ ] **Step 4: Run the miniboss and water-helper tests**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzMinibossArenaHeadless,TestS3kCnzWaterHelpersHeadless"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzWaterLevelCorkFloorInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzWaterLevelButtonInstance.java src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java src/test/java/com/openggf/tests/TestS3kCnzMinibossArenaHeadless.java src/test/java/com/openggf/tests/TestS3kCnzWaterHelpersHeadless.java
git commit -m "feat: implement cnz miniboss and water helpers"
```

## Task 8: Act 2 Knuckles Teleporter Route And CNZ End Boss

**Spec slice:** Slice 3

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzTeleporterInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzTeleporterBeamInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzEggCapsuleInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java`

- [ ] **Step 1: Write the failing Knuckles-route and end-boss tests**

```java
@Test
void knucklesTeleporterRouteLocksControl_loadsPlc_andSpawnsCapsule() {
    loadCnzAct2AsKnuckles();
    teleportPlayerTo(0x4880, 0x0B00);
    fixture.stepIdleFrames(4);

    assertTrue(sprite.getControlLocked());
    assertTrue(isObjectPresent(CnzTeleporterInstance.class));
    assertTrue(isObjectPresent(CnzEggCapsuleInstance.class));
    assertEquals(0x4750, fixture.camera().getMinX());
    assertEquals(0x48E0, fixture.camera().getMaxX());
}

@Test
void cnzEndBossReleaseSpawnsEggCapsuleAndRestoresControl() {
    CnzEndBossInstance boss = spawnCnzEndBossForTest();
    boss.forceDefeatForTest();
    fixture.stepIdleFrames(1);

    assertTrue(isObjectPresent(CnzEggCapsuleInstance.class));
    assertFalse(sprite.getControlLocked());
}
```

- [ ] **Step 2: Run the teleporter-route test to verify it fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzTeleporterRouteHeadless"
```

Expected: FAIL because the teleporter cutscene and CNZ end boss are not implemented.

- [ ] **Step 3: Implement the teleporter cutscene and end-boss control handoff after verifying capsule parity**

Start by comparing CNZ's fixed-position `Obj_EggCapsule` path against the existing `Aiz2EndEggCapsuleInstance` and `HczEndBossEggCapsuleInstance` implementations. The AIZ2 class is camera-relative routine 8 and should not be assumed equivalent. If HCZ's ground capsule is not a drop-in match for the CNZ route, create `CnzEggCapsuleInstance` with CNZ-local Javadocs and spawn that instead.

```java
public final class CnzTeleporterInstance extends AbstractObjectInstance {
    @Override
    public void update() {
        lockPlayerToTeleporterWindow();
        applyTeleporterPaletteLine();
        ensureTeleportArtLoaded();
        if (artLoaded && playerHasLanded()) {
            spawnChild(() -> new CnzTeleporterBeamInstance(makeBeamSpawn()));
            S3kCnzEventWriteSupport.markTeleporterBeamSpawned(services());
            destroy();
        }
    }
}
```

```java
public final class CnzEndBossInstance extends AbstractObjectInstance {
    private void onDefeated() {
        S3kCnzEventWriteSupport.setBossFlag(services(), false);
        spawnChild(() -> new CnzEggCapsuleInstance(makeCapsuleSpawn()));
        restoreLevelMusicAndControl();
    }
}
```

- [ ] **Step 4: Run the teleporter-route test and the focused package build**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzTeleporterRouteHeadless"
mvn -q -DskipTests package "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzTeleporterInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzTeleporterBeamInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzEggCapsuleInstance.java src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossInstance.java src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java
git commit -m "feat: implement cnz teleporter and end boss"
```

## Task 9: Full CNZ Regression Sweep And Visual Validation Artifact

**Spec slice:** Validation across Slices 0 through 5

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzZoneRuntimeState.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPatternAnimation.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPaletteCycling.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzRuntimeStateRegistration.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzScroll.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzAct1EventFlow.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzMinibossArenaHeadless.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzWaterHelpersHeadless.java`
- Create: `docs/architecture/validation/2026-04-16-s3k-cnz-validation.md`

- [ ] **Step 1: Add the final regression assertions and the validation template**

```markdown
# S3K CNZ Visual Validation

| Beat | ROM anchor | Engine trigger | Result | Notes |
|------|------------|----------------|--------|-------|
| Act 1 miniboss entry shift | `CNZ1BGE_Normal` | Teleport camera to `$3000` threshold |  |  |
| Arena destruction and lowering | `CNZ1_ScreenEvent` + `Obj_CNZMinibossTop` | Force top-piece collisions |  |  |
| FG refresh and collision handoff | `CNZ1BGE_AfterBoss` | First `Events_fg_5` |  |  |
| Seamless Act 1 -> Act 2 reload | `CNZ1BGE_DoTransition` | Second `Events_fg_5` after signpost |  |  |
| Act 2 Knuckles teleporter route | `CNZ2_ScreenEvent` + `Obj_CNZTeleporter` | Teleport Knuckles to `$4880,$0B00` |  |  |
| Pachinko DMA phase | `AnimateTiles_CNZ` | Scroll camera through deform bands |  |  |
| Underwater palette parity | `AnPal_CNZ` | Step idle frames in water |  |  |
| Route-specific PLC art presence | `PLC_EggCapsule` + `ArtKosM_CNZTeleport` | Trigger Knuckles teleporter route |  |  |
| CNZ shake behavior | `ShakeScreen_Setup` | Run Act 2 BG path and boss-end beats |  |  |
```

- [ ] **Step 2: Run the full CNZ regression suite**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzZoneRuntimeState,TestS3kCnzPatternAnimation,TestS3kCnzPaletteCycling,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzAct1EventFlow,TestS3kCnzBossScrollHandler,TestS3kCnzMinibossArenaHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzWaterHelpersHeadless"
```

Expected: PASS.

- [ ] **Step 3: Capture ROM-vs-engine evidence for every required beat**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzMinibossArenaHeadless,TestS3kCnzTeleporterRouteHeadless"
```

Expected: PASS with reproducible headless triggers ready for screenshot capture and manual ROM comparison.

- [ ] **Step 4: Fill in the validation report with the actual ROM comparison results**

Open `docs/architecture/validation/2026-04-16-s3k-cnz-validation.md` and replace the blank `Result` / `Notes` cells with the observed findings from the engine-vs-ROM comparison. Do not prefill expected outcomes.

```markdown
| Beat | ROM anchor | Engine trigger | Result | Notes |
|------|------------|----------------|--------|-------|
| Act 1 miniboss entry shift | `CNZ1BGE_Normal` | Camera X `>= $3000` with Y `>= $54C` |  |  |
| Act 2 Knuckles teleporter route | `Obj_CNZTeleporter` | Knuckles at `$4880,$0B00` |  |  |
| Route-specific PLC art presence | `PLC_EggCapsule` + `ArtKosM_CNZTeleport` | Knuckles route |  |  |
| CNZ shake behavior | `ShakeScreen_Setup` | Act 2 BG steady-state |  |  |
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/openggf/game/sonic3k/TestCnzZoneRuntimeState.java src/test/java/com/openggf/game/sonic3k/TestS3kCnzPatternAnimation.java src/test/java/com/openggf/game/sonic3k/TestS3kCnzPaletteCycling.java src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzRuntimeStateRegistration.java src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzScroll.java src/test/java/com/openggf/tests/TestS3kCnzAct1EventFlow.java src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java src/test/java/com/openggf/tests/TestS3kCnzMinibossArenaHeadless.java src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java src/test/java/com/openggf/tests/TestS3kCnzWaterHelpersHeadless.java docs/architecture/validation/2026-04-16-s3k-cnz-validation.md
git commit -m "test: validate cnz bring-up parity"
```
