# AIZ1 Real Miniboss, Signpost & Defeat Flow Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the end-of-AIZ-Act-1 experience: dynamically-spawned miniboss with Knuckles napalm attack, S3K signpost with bump/hidden-monitor mechanics, reusable defeat-to-signpost flow, and a stubbed results screen.

**Architecture:** Extends the existing `AizMinibossInstance` (0x91) with a Knuckles-only napalm gate. Adds reusable `S3kBossDefeatSignpostFlow` (standalone `AbstractObjectInstance`) to orchestrate the defeat→signpost→results sequence. The signpost (`S3kSignpostInstance`) is a 5-routine state machine with DPLC-based art, bump-from-below, and hidden monitor interaction. Dynamic spawn handled by new AIZ2 resize phase in `Sonic3kAIZEvents`.

**Tech Stack:** Java 21, OpenGL (via existing rendering pipeline), Maven build

**Spec:** `docs/architecture/designs/2026-03-16-aiz1-miniboss-signpost-design.md`

---

## Chunk 1: Foundation (Constants, State Flags, Registry)

### Task 1: Add Game State Flags

**Files:**
- Modify: `src/main/java/com/openggf/game/GameStateManager.java`

- [ ] **Step 1: Add endOfLevelActive and endOfLevelFlag fields**

Add two boolean fields near the existing boss/event state fields (around line 85-90). Add getters, setters, and reset in both `resetSession()` (per-session) and a new `resetForLevel()` method (per-level-load, since these flags must reset between act transitions).

```java
// In field declarations section:
private boolean endOfLevelActive;
private boolean endOfLevelFlag;

// In resetSession() (around line 117-124):
endOfLevelActive = false;
endOfLevelFlag = false;

// New method for per-level reset (call from level load path):
public void resetForLevel() {
    endOfLevelActive = false;
    endOfLevelFlag = false;
}

// Getters/setters (after existing boss getters ~line 408):
public boolean isEndOfLevelActive() { return endOfLevelActive; }
public void setEndOfLevelActive(boolean active) { this.endOfLevelActive = active; }
public boolean isEndOfLevelFlag() { return endOfLevelFlag; }
public void setEndOfLevelFlag(boolean flag) { this.endOfLevelFlag = flag; }
```

Also call `GameServices.gameState().resetForLevel()` from the level-load path (e.g., in `LevelManager.loadLevel()` or `Sonic3k.loadLevel()`).

- [ ] **Step 2: Verify existing tests still pass**

Run: `mvn test -Dtest=TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils -q`
Expected: PASS (no behavioral change)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameStateManager.java
git commit -m "feat(s3k): add endOfLevelActive/endOfLevelFlag state flags for signpost flow"
```

### Task 2: Add ROM Constants

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java`

- [ ] **Step 1: Add signpost and palette constants to Sonic3kConstants.java**

Add near the existing AIZ constants section (after the miniboss constants around line 820):

```java
// Signpost (Obj_EndSign) - End of act signpost
public static final int ART_UNC_END_SIGNS_ADDR = 0x0DCC76;
public static final int ART_UNC_END_SIGNS_SIZE = 3328;
public static final int ART_NEM_SIGNPOST_STUB_ADDR = 0x0DD976;
public static final int MAP_END_SIGNS_ADDR = 0x083B9E;
public static final int DPLC_END_SIGNS_ADDR = 0x083B6C;
public static final int MAP_SIGNPOST_STUB_ADDR = 0x083BFC;
public static final int ART_TILE_END_SIGNS = 0x04AC;
public static final int ART_TILE_SIGNPOST_STUB = 0x069E;
// Note: ART_NEM_MONITORS_ADDR = 0x190F4A already exists at Sonic3kConstants line 208

// Pal_AIZ - Main AIZ palette (for AfterBoss_Cleanup restoring palette line 2)
public static final int PAL_AIZ_ADDR = 0x0A8B7C;
public static final int PAL_AIZ_SIZE = 96;
```

- [ ] **Step 2: Add HIDDEN_MONITOR to Sonic3kObjectIds.java**

```java
public static final int HIDDEN_MONITOR = 0x80;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java
git commit -m "feat(s3k): add signpost ROM addresses and HIDDEN_MONITOR object ID"
```

### Task 3: Register Hidden Monitor in Object Registry

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

- [ ] **Step 1: Add hidden monitor factory registration**

In `registerDefaultFactories()`, add a factory for object ID 0x80. Follow the existing badnik factory pattern (lines 104-126). The hidden monitor should be a placeholder for now — we'll create the real class in Task 7.

```java
// In registerDefaultFactories(), after existing registrations (uses factories.put() pattern):
factories.put(Sonic3kObjectIds.HIDDEN_MONITOR, (spawn, registry) -> new PlaceholderObjectInstance(spawn, "HiddenMonitor"));
```

Note: We register a placeholder now and swap to the real `S3kHiddenMonitorInstance` in Task 7 after the class exists. This keeps the registry compilable at each step.

- [ ] **Step 2: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat(s3k): register placeholder factory for HiddenMonitor (0x80)"
```

### Task 4: Register PLC_EndSignStuff in PLC Art Registry

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java`

- [ ] **Step 1: Add PLC_EndSignStuff entries**

In `addAizEntries()` (or equivalent zone-specific method), add standalone art entries for the signpost stub and monitors. Follow the existing `StandaloneArtEntry` pattern.

The signpost art itself (`ArtUnc_EndSigns`) uses DPLC and will be loaded directly by the signpost object, not via PLC. Only the stub and monitors art go through PLC.

```java
// Add to the AIZ standalone art list:
// PLC_EndSignStuff - loaded when signpost spawns after boss defeat
// StandaloneArtEntry(key, artAddr, compression, artSize, mappingAddr, palette, dplcAddr)
standalone.add(new StandaloneArtEntry(
    "SignpostStub", Sonic3kConstants.ART_NEM_SIGNPOST_STUB_ADDR,
    CompressionType.NEMESIS, -1, Sonic3kConstants.MAP_SIGNPOST_STUB_ADDR,
    0, -1));
// ArtNem_Monitors (0x190F4A) is already registered via ART_NEM_MONITORS_ADDR (Sonic3kConstants line 208)
```

- [ ] **Step 2: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java
git commit -m "feat(s3k): add PLC_EndSignStuff entries for signpost stub art"
```

## Chunk 2: Signpost Core

### Task 5: Create S3kSignpostInstance — Init and Falling States

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java`

- [ ] **Step 1: Create the signpost class with INIT and FALLING states**

The signpost extends `AbstractObjectInstance`. State machine with enum: `INIT, FALLING, LANDED, RESULTS, AFTER`.

Key behaviors for INIT:
- Position at `Camera_Y - 0x20`
- Store reference in a static field `signpostAddr` (so hidden monitors can find it)
- Set collision radii: xRadius=0x18, yRadius=0x1E
- Play `Sonic3kSfx.SIGNPOST`
- Create `S3kSignpostStubChild` at offset (0, 0x18)
- Select animation based on player character

Key behaviors for FALLING:
- Gravity: `yVel += 0x0C` each frame
- Move via velocity
- Sparkle every 4 frames
- Wall bounce: negate xVel when hitting `Camera_X + 0x128` (right) or `Camera_X + 0x18` (left)
- Landing check: `yPos >= Camera_Y + 0x50` AND `yVel > 0` AND floor contact
- On land: set `landed = true`, set 0x40 frame post-land timer

Animation data (ROM-accurate raw sequences):
```java
// AniRaw_EndSigns1 (Sonic/Tails): speed=1
private static final int[] ANIM_SPIN_DEFAULT = {0,4,5,6, 1,4,5,6, 3,4,5,6};
// AniRaw_EndSigns2 (Knuckles): speed=1
private static final int[] ANIM_SPIN_KNUCKLES = {1,4,5,6, 2,4,5,6, 3,4,5,6};
```

Character face frame lookup:
```java
private static final int[] FACE_FRAMES = {0, 0, 1, 2}; // indexed by PlayerCharacter ordinal
```

- [ ] **Step 2: Create S3kSignpostStubChild**

Simple child that follows parent position with Y offset +0x18. Single frame from `Map_SignpostStub`. Pure visual.

- [ ] **Step 3: Create S3kSignpostSparkleChild**

Reuses ring art tiles. Animation: frames 4,5,6,7 at speed 1, then self-destroy. Spawned at signpost position.

- [ ] **Step 4: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostStubChild.java src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostSparkleChild.java
git commit -m "feat(s3k): add S3kSignpostInstance with INIT/FALLING states and children"
```

### Task 6: Add Signpost Bump-From-Below and LANDED/RESULTS States

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java`

- [ ] **Step 1: Implement bump-from-below mechanic**

In the FALLING state, check each frame:
- 0x20-frame cooldown timer (skip if > 0, decrement each frame)
- Range check vs player: `{-0x20, 0x40, -0x18, 0x30}` relative to signpost center
- Player must be jumping (animation == 2) AND moving upward (yVel < 0)
- On bump: `xVel = (signpostX - playerX) * 16` (use 8 if centered), `yVel = -0x200`
- Play `Sonic3kSfx.SIGNPOST`, add 100 points, 0x20 frame cooldown

- [ ] **Step 2: Implement LANDED state**

- Continue spin animation for 0x40 frames
- Each frame: check if `landed` flag was cleared (by hidden monitor) — if so, bounce back up with `yVel = -0x200`, return to FALLING, reset cooldown to 0x20
- When timer expires: show face frame `FACE_FRAMES[playerCharacter.ordinal()]`, set `resultsReady = true`, zero velocity

- [ ] **Step 3: Implement RESULTS state**

- Wait for player to be on ground (not jumping/falling)
- Spawn `S3kLevelResultsInstance` — use a forward reference or lazy instantiation. The class is created in Task 8 (Chunk 3). For now, gate the spawn behind a null check or create a stub `spawnResults()` method that will be completed after Task 8.
- Transition to AFTER state

- [ ] **Step 4: Implement AFTER state**

- Range check: if signpost is off-screen, delete self
- Clear static `signpostAddr` reference

- [ ] **Step 5: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java
git commit -m "feat(s3k): add signpost bump-from-below, LANDED/RESULTS/AFTER states"
```

### Task 7: Create S3kHiddenMonitorInstance

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kHiddenMonitorInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

- [ ] **Step 1: Create hidden monitor class**

Extends `AbstractObjectInstance`. Behavior:
- Init: load `Map_Monitor` mappings, set `collision_flags = 0x46`, back up subtype
- Main loop: poll `S3kSignpostInstance.getSignpostAddr()` each frame
  - Wait for signpost to exist and have `landed == true`
  - Range check: `{-0x0E, 0x1C, -0x80, 0xC0}` relative to hidden monitor position
  - **In range:** play `Sonic3kSfx.BUBBLE_ATTACK`, clear signpost's landed flag, transform self into visible monitor (set `yVel = -0x500`), set subtype from backup
  - **Out of range:** play `Sonic3kSfx.GROUND_SLIDE`, set destroyed

- [ ] **Step 2: Update registry to use real class**

Replace the placeholder from Task 3 (uses `factories.put()` pattern):
```java
factories.put(Sonic3kObjectIds.HIDDEN_MONITOR, (spawn, registry) -> new S3kHiddenMonitorInstance(spawn));
```

- [ ] **Step 3: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kHiddenMonitorInstance.java src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat(s3k): add S3kHiddenMonitorInstance with signpost interaction"
```

## Chunk 3: Defeat Flow and Results Stub

### Task 8: Create S3kLevelResultsInstance (Stub)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kLevelResultsInstance.java`

- [ ] **Step 1: Create stub results screen**

Extends `AbstractObjectInstance`. Minimal behavior:
- Constructor: set a 60-frame timer
- `update()`: decrement timer each frame
- When timer reaches 0:
  - `GameServices.gameState().setEndOfLevelActive(false)`
  - `GameServices.gameState().setEndOfLevelFlag(true)`
  - `setDestroyed(true)`
- `appendRenderCommands()`: no-op (stub has no art)

- [ ] **Step 2: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kLevelResultsInstance.java
git commit -m "feat(s3k): add stub S3kLevelResultsInstance (triggers act transition after 60 frames)"
```

### Task 9: Create S3kBossDefeatSignpostFlow

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kBossDefeatSignpostFlow.java`

- [ ] **Step 1: Create the defeat flow class**

Extends `AbstractObjectInstance`. 4-phase state machine matching ROM's `Obj_EndSignControl`:

```java
private enum Phase { WAIT_FADE, SPAWN_SIGNPOST, AWAIT_RESULTS, AWAIT_ACT_TRANSITION }
```

Constructor takes (in this order):
- `int signpostX` — boss X position (signpost spawns here)
- `Runnable zoneCleanupCallback` — zone-specific palette/art restoration

**WAIT_FADE (Phase 1):**
- Init: set `GameServices.gameState().setEndOfLevelActive(true)`, timer = 0x77 (119 frames)
- Each frame: decrement timer
- When timer reaches 0: transition to SPAWN_SIGNPOST

**SPAWN_SIGNPOST (Phase 2):**
- Clear boss flag via `Sonic3kAIZEvents.setBossFlag(false)` (or equivalent)
- Spawn `S3kSignpostInstance` at boss X position, `Camera_Y - 0x20`
- Load PLC art (signpost stub + monitors)
- Call `zoneCleanupCallback.run()`
- Transition to AWAIT_RESULTS

**AWAIT_RESULTS (Phase 3):**
- Poll `GameServices.gameState().isEndOfLevelActive()` each frame
- When false (results stub cleared it): restore player control, transition to AWAIT_ACT_TRANSITION

**AWAIT_ACT_TRANSITION (Phase 4):**
- Poll `GameServices.gameState().isEndOfLevelFlag()` each frame
- When true: call `LevelManager.getInstance().requestActTransition()` (or equivalent — check existing act transition mechanism used by `SeamlessLevelTransitionRequest` in the S2 signpost), then `setDestroyed(true)`

`appendRenderCommands()`: no-op (invisible orchestrator object)

- [ ] **Step 2: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kBossDefeatSignpostFlow.java
git commit -m "feat(s3k): add S3kBossDefeatSignpostFlow (reusable defeat-to-signpost sequence)"
```

### Task 10: Wire Defeat Flow into AizMinibossInstance

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java`

- [ ] **Step 1: Spawn S3kBossDefeatSignpostFlow on defeat**

In `onDefeatStarted()` (line ~150), after existing logic (setting routine, clearing velocity, starting defeat timer), add:

```java
// Spawn the defeat-to-signpost flow as a dynamic object
// Constructor: S3kBossDefeatSignpostFlow(int signpostX, Runnable zoneCleanupCallback)
S3kBossDefeatSignpostFlow defeatFlow = new S3kBossDefeatSignpostFlow(
    state.x, // signpost X position
    () -> {
        // AIZ1 AfterBoss_Cleanup: restore palette line 2 from Pal_AIZ
        LevelManager level = LevelManager.getInstance();
        byte[] palData = GameServices.rom().readBytes(
            Sonic3kConstants.PAL_AIZ_ADDR, Sonic3kConstants.PAL_AIZ_SIZE);
        // LevelManager.updatePalette(paletteIndex, paletteData) at line 3391
        level.updatePalette(2, palData);
    }
);
// Use inherited static helper from AbstractObjectInstance (line 115)
spawnDynamicObject(defeatFlow);
```

- [ ] **Step 2: Remove self-destruct from updateDefeated()**

In `updateDefeated()` (line ~324-339), replace the block at lines 333-338 that clears boss flag, restores music, and calls `setDestroyed(true)` with a no-op. The defeat flow now handles boss flag clearing, and the boss stays alive (but invisible) while the signpost sequence plays out.

```java
// OLD (remove):
// Sonic3kAIZEvents events = getAizEvents();
// if (events != null) { events.setBossFlag(false); }
// AudioManager.getInstance().getBackend().restoreMusic();
// setDestroyed(true);

// NEW: Boss stays alive but stops updating after explosions finish.
// The S3kBossDefeatSignpostFlow handles the rest of the sequence.
```

- [ ] **Step 3: Verify existing S3K tests still pass**

Run: `mvn test -Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java
git commit -m "feat(s3k): wire S3kBossDefeatSignpostFlow into miniboss defeat, remove self-destruct"
```

## Chunk 4: Knuckles Napalm Attack

### Task 11: Create AizMinibossNapalmController

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossNapalmController.java`

- [ ] **Step 1: Create the napalm controller child**

Extends `AbstractBossChild`. State machine:
- **IDLE:** Poll parent's custom flag bit 1 (`$38`) each frame. Stay idle until bit 1 is set. (For Sonic, this never happens.)
- **DELAY:** Wait for subtype-based delay frames, then transition to FIRE.
- **FIRE:** Spawn visual fire effect child + `AizMinibossNapalmProjectile`. Transition back to IDLE (or stay inactive if parent defeated — base class auto-handles this via `isDestroyed()` check on parent).

The controller is created during boss init but remains in IDLE for Sonic encounters. Only activates for Knuckles when parent sets bit 1.

- [ ] **Step 2: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizMinibossNapalmController.java
git commit -m "feat(s3k): add AizMinibossNapalmController (Knuckles-only napalm child)"
```

### Task 12: Create AizMinibossNapalmProjectile

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossNapalmProjectile.java`

- [ ] **Step 1: Create the napalm projectile**

Extends `AbstractObjectInstance`, implements `TouchResponseProvider`. State machine:
- **LAUNCH:** Set `yVel = -0x400` (upward), play `Sonic3kSfx.PROJECTILE`. After reaching top of screen or delay expires, transition to REPOSITION.
- **REPOSITION:** Place at `Camera_Y - 0x20`, set `yVel` downward toward player area, play `Sonic3kSfx.MISSILE_THROW`. Transition to DROP.
- **DROP:** Apply gravity, check floor collision via terrain probe. Every 4 frames spawn smoke trail child. On floor contact: play `Sonic3kSfx.MISSILE_EXPLODE`, spawn 7 explosion children (reuse `S3kBossExplosionChild` pattern), transition to EXPLODE.
- **EXPLODE:** Play ground fire animation (frames 0x10, 0x11, 0x2D, 0x2E from ROM), then self-destroy.

Collision: `getCollisionFlags()` returns hurt flags during LAUNCH/DROP, 0 during EXPLODE.
`shield_reaction` bit 4 set (fire shield deflection).
Self-deletes after 0x9F frames max lifetime.

- [ ] **Step 2: Verify build**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizMinibossNapalmProjectile.java
git commit -m "feat(s3k): add AizMinibossNapalmProjectile with launch/drop/explode lifecycle"
```

### Task 13: Integrate Napalm into AizMinibossInstance

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java`

- [ ] **Step 1: Add Knuckles napalm gate**

After the swing phase callback (around the `onSwingPrepComplete()` or equivalent post-swing callback), add a character check:

```java
// After swing phase wait callback setup:
PlayerCharacter character = Sonic3kLevelEventManager.getInstance().getPlayerCharacter();
if (character == PlayerCharacter.KNUCKLES) {
    setCustomFlag(FLAG_PARENT_BITS, getCustomFlag(FLAG_PARENT_BITS) | 0x02); // bit 1
}
```

- [ ] **Step 2: Spawn napalm controllers during init**

In the init routine (where body/arm/barrel children are created), add napalm controller children:

```java
// Create napalm controller children (stay idle for Sonic, activate for Knuckles)
AizMinibossNapalmController napalmController = new AizMinibossNapalmController(this, 0);
childComponents.add(napalmController);
```

- [ ] **Step 3: Add character-dependent camera trigger threshold**

In the wait-for-trigger routine, adjust the threshold:

```java
PlayerCharacter character = Sonic3kLevelEventManager.getInstance().getPlayerCharacter();
int triggerX = (character == PlayerCharacter.KNUCKLES) ? 0x10C0 : 0x10E0;
```

- [ ] **Step 4: Verify existing S3K tests still pass (regression check)**

Run: `mvn test -Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils -q`
Expected: PASS (Sonic path unchanged — napalm children stay idle)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java
git commit -m "feat(s3k): add Knuckles-only napalm attack gate to AIZ miniboss"
```

## Chunk 5: Dynamic Spawn and Screen Lock

### Task 14: Add AIZ2 Resize Phase for Boss Spawn

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java`

- [ ] **Step 1: Add AIZ2 miniboss spawn resize phase**

After the fire transition completes (when `FireSequencePhase` reaches `COMPLETE` or equivalent), add a new resize phase that watches camera X position:

```java
// AIZ2 resize: spawn real miniboss when camera reaches threshold
// ROM: AIZ2_SonicResize2 (line 39076) / AIZ2_KnuxResize2 (line 39187)
PlayerCharacter character = Sonic3kLevelEventManager.getInstance().getPlayerCharacter();
int cameraTrigger = (character == PlayerCharacter.KNUCKLES) ? 0x1040 : 0x0F50;
int bossX = (character == PlayerCharacter.KNUCKLES) ? 0x11D0 : 0x11F0;
int bossY = (character == PlayerCharacter.KNUCKLES) ? 0x0420 : 0x0289;

if (Camera.getInstance().getX() >= cameraTrigger && !minibossSpawned) {
    minibossSpawned = true;
    // ObjectSpawn record: (x, y, objectId, subtype, renderFlags, respawnTracked, rawYWord)
    ObjectSpawn bossSpawn = new ObjectSpawn(bossX, bossY, 0x91, 0, 0, false, bossY);
    AizMinibossInstance boss = new AizMinibossInstance(bossSpawn, LevelManager.getInstance());
    // Access ObjectManager via LevelManager (no static getInstance() on ObjectManager)
    LevelManager.getInstance().getObjectManager().addDynamicObject(boss);
}
```

- [ ] **Step 2: Add screen lock when boss triggers**

The boss itself handles screen lock in its wait-for-trigger routine (already in `AizMinibossInstance`). Verify that the existing trigger logic sets `Camera.minX = Camera.maxX` and `setBossFlag(true)`. If not present, add it.

- [ ] **Step 3: Add post-defeat camera unlock**

In the defeat flow or AIZ events, gradually increase `Camera.minX` and `Camera.maxX` by 2/frame after boss flag is cleared, until reaching the signpost area boundary.

- [ ] **Step 4: Add minibossSpawned field and reset**

Add a `boolean minibossSpawned` field to `Sonic3kAIZEvents`, reset it in `init()`.

- [ ] **Step 5: Verify existing S3K tests still pass**

Run: `mvn test -Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java
git commit -m "feat(s3k): add AIZ2 resize phase for dynamic miniboss spawn and screen lock"
```

## Chunk 6: Signpost Art Loading

### Task 15: Load Signpost Art and Mappings

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java` (if needed)

- [ ] **Step 1: Load signpost mappings from ROM**

In the signpost INIT state, parse `Map_EndSigns` from ROM at `0x083B9E`. Use the existing S3K mapping parser (`S3kSpriteDataLoader.loadMappingFrames()` or equivalent) to get 7 mapping frames.

Reference: The signpost uses DPLC-based art. Load `DPLC_EndSigns` from `0x083B6C` and `ArtUnc_EndSigns` from `0x0DCC76`. On each frame change, perform DPLC to load only the needed tiles to VRAM at `ArtTile_EndSigns = 0x04AC`.

- [ ] **Step 2: Load signpost stub mappings**

Parse `Map_SignpostStub` from ROM at `0x083BFC` (1 frame). Art loaded via PLC from `ArtNem_SignpostStub` at `0x0DD976` to `ArtTile_SignpostStub = 0x069E`.

- [ ] **Step 3: Implement Animate_Raw for signpost spin**

The animation system uses the raw frame sequences. Each tick: advance frame pointer, if `0xFC` hit then loop back to start. Speed = 1 (advance every other frame).

- [ ] **Step 4: Implement appendRenderCommands()**

Render the current mapping frame at the signpost's world position. Use the existing `BatchedPatternRenderer` to draw the DPLC-loaded tiles.

- [ ] **Step 5: Verify build and visual test**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS. Visual verification requires running the game and reaching the boss area.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java
git commit -m "feat(s3k): load signpost art/mappings from ROM with DPLC support"
```

## Chunk 7: Integration Testing

### Task 16: Verify End-to-End Flow

**Files:**
- No new files — verification task

- [ ] **Step 1: Run all existing S3K tests**

Run: `mvn test -Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils -q`
Expected: ALL PASS

- [ ] **Step 2: Run full test suite for regression**

Run: `mvn test -q`
Expected: No new failures

- [ ] **Step 3: Manual play test (if ROM available)**

Launch the game, play through AIZ1 to the fire transition, continue to the end of the level. Verify:
- Boss spawns when camera reaches threshold
- Screen locks during fight
- Flame attacks work as before
- Boss takes 6 hits to defeat
- Explosions play, music fades
- Signpost falls from top of screen, spinning
- Signpost can be bumped from below
- Signpost lands and shows character face
- Stub results triggers act transition

- [ ] **Step 4: Final commit (if any cleanup needed)**

Stage only the specific files that were changed, then commit:
```bash
git add <specific-changed-files>
git commit -m "fix(s3k): integration fixes for AIZ1 miniboss signpost flow"
```
