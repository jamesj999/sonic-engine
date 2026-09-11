# Refactor SEAMLESS_RELOAD to ROM-Aligned Act Transition System

**Date:** 2026-03-08
**Status:** Design (Approved)
**Focus:** Replace misaligned `SEAMLESS_RELOAD` profile path with a direct `executeActTransition()` method that matches ROM behavior

## Problem

Our `SEAMLESS_RELOAD` system is fundamentally misaligned with how the ROM handles seamless act transitions.

**What the ROM does:** S3K act transitions (AIZ1→2, HCZ1→2, MGZ1→2, etc.) happen entirely within level event background routines. The event handler:
1. Queues art decompression (Kos_modules)
2. Waits for decompression to complete
3. Sets `Current_zone_and_act`
4. Calls `Load_Level()` + `LoadSolids()` (layout + collision only)
5. Applies coordinate offsets via `Offset_ObjectsDuringTransition`
6. Resets object/ring managers
7. Restores camera bounds
8. Reinitializes level events

**What our engine does:** Routes act transitions through `loadLevel()` with `LevelLoadMode.SEAMLESS_RELOAD`, which executes 12 profile steps including game module reinitialization, audio manager reconfiguration, and other heavyweight operations. `Sonic3kLevelInitProfile` has `seamlessReload` gating to skip player/checkpoint steps, but the profile still runs unnecessary steps. Meanwhile, `loadZoneAndActSeamless()` saves and restores checkpoint state around the profile execution — a workaround for the profile clearing state that the ROM never touches.

**Impact:** Architectural smell (wrong abstraction), unnecessary work during transitions, fragile checkpoint save/restore dance, and difficulty extending to other S3K zones.

## ROM Analysis: All S3K Seamless Transitions

All 12+ S3K zones with seamless act transitions follow the same pattern in their BG event handlers. Each does only:

| Zone | Offsets (Player X, Y) | Notes |
|------|----------------------|-------|
| AIZ 1→2 | -0x2F00, -0x80 | Fire transition, art mutation |
| HCZ 1→2 | -0x3600, 0 | |
| MGZ 1→2 | -0x2E00, -0x600 | |
| CNZ 1→2 | -0x2E00, -0x600 | |
| FBZ 1→2 | -0x2E00, 0 | |
| ICZ 1→2 | -0x2E00, -0x600 | |
| LBZ 1→2 | -0x2E00, -0x600 | |
| MHZ 1→2 | -0x2E00, 0 | |
| SOZ 1→2 | explicit position | Resets to specific coords |
| LRZ 1→2 | -0x2E00, -0x600 | |
| SSZ 1→2 | -0x3E00, -0x600 | |
| DEZ 1→2 | -0x2E00, -0x600 | |

None of them reinitialize the game module, reload audio banks, or run the full `Level:` entry point sequence. They all just swap layout data in place.

## Design

### Core Change: `executeActTransition()`

Replace `loadZoneAndActSeamless()` → `loadLevel(SEAMLESS_RELOAD)` with a new `LevelManager.executeActTransition()` that calls `loadLevelData()` directly, bypassing the profile system entirely. This matches the ROM: act transitions are NOT level loads.

```java
// LevelManager.java — new method
public void executeActTransition(SeamlessLevelTransitionRequest request) throws IOException {
    // 1. Set zone/act (ROM: move.b d0, Current_zone_and_act)
    currentZone = request.targetZone();
    currentAct = request.targetAct();

    // 2. Reload layout + collision (ROM: Load_Level + LoadSolids)
    LevelData levelData = levels.get(currentZone).get(currentAct);
    Level loaded = loadLevelData(levelData.getLevelIndex());

    // 3. Apply art mutations if requested (ROM: zone-specific art swaps)
    if (request.mutationKey() != null && !request.mutationKey().isBlank()) {
        applySeamlessMutation(request.mutationKey());
    }

    // 4. Reset managers (ROM: clears Dynamic_object_RAM, Ring_status_table)
    resetManagersForActTransition();

    // 5. Apply coordinate offsets (ROM: Offset_ObjectsDuringTransition)
    applySeamlessOffsets(request);

    // 6. Restore camera bounds from new level (ROM: Camera_min/max from level size array)
    restoreCameraBoundsForCurrentLevel();
    camera.updatePosition(true);

    // 7. Reinitialize level events (ROM: clear event routine counters)
    initLevelEventsForCurrentZoneAct();

    // 8. Music override if specified
    if (request.musicOverrideId() >= 0) {
        AudioManager.getInstance().playMusic(request.musicOverrideId());
    }

    // 9. In-level title card if requested
    if (request.showInLevelTitleCard() && !graphicsManager.isHeadlessMode()) {
        requestInLevelTitleCard(currentZone, currentAct);
    }
}
```

### New Helper: `resetManagersForActTransition()`

```java
private void resetManagersForActTransition() {
    ObjectManager om = getObjectManager();
    if (om != null) {
        om.reset(camera.getX());
    }
    RingManager rm = getRingManager();
    if (rm != null) {
        rm.resetForActTransition();
    }
}
```

### Removals

1. **`LevelLoadMode.SEAMLESS_RELOAD`** — Delete this enum value. `LevelLoadMode` becomes `FULL` only (can be removed entirely later if desired).

2. **`loadZoneAndActSeamless()`** — Delete. The checkpoint save/restore dance is no longer needed because `executeActTransition()` never touches checkpoint state.

3. **`Sonic3kLevelInitProfile` seamless gating** — Remove the `seamlessReload` boolean and all conditional branches. The profile only handles `FULL` loads.

4. **`TestSonic3kLevelInitProfile.seamlessReloadSkipsPlayerAndSidekickSteps()`** — Delete this test.

### Preserved (No Change)

- **`SeamlessLevelTransitionRequest`** — Stays as the async data contract between event handlers and the game loop.
- **Deferred execution pattern** — `requestSeamlessTransition()` → game loop → `consumeSeamlessTransitionRequest()` → execute. This timing safety prevents event handlers from resetting their own state mid-update.
- **`S3kSeamlessMutationExecutor`** — ROM-aligned art overlay system, stays unchanged.
- **`Sonic3kAIZEvents.requestAct2Transition()`** — Builds the request, no change needed.

### Modified: `applySeamlessTransition()`

Simplify the switch to route through `executeActTransition()`:

```java
public void applySeamlessTransition(SeamlessLevelTransitionRequest request) {
    try {
        switch (request.type()) {
            case MUTATE_ONLY -> applySeamlessMutation(request.mutationKey());
            case RELOAD_SAME_LEVEL -> {
                SeamlessLevelTransitionRequest adjusted = SeamlessLevelTransitionRequest
                    .builder(TransitionType.RELOAD_TARGET_LEVEL)
                    .targetZoneAct(currentZone, currentAct)
                    /* copy remaining fields */
                    .build();
                executeActTransition(adjusted);
            }
            case RELOAD_TARGET_LEVEL -> executeActTransition(request);
        }
        // Music suppress handled inside executeActTransition
        if (request.preserveMusic()) {
            setSuppressNextMusicChange(true);
        }
    } catch (IOException e) { ... }
}
```

### Data Flow

```
Event handler (e.g., Sonic3kAIZEvents)
  │
  ├─ applyFireTransitionMutation()     ← MUTATE_ONLY (art swap in-place)
  │
  └─ requestAct2Transition()           ← builds SeamlessLevelTransitionRequest
       │
       ▼
GameLoop.update()
  │
  └─ consumeSeamlessTransitionRequest()
       │
       ▼
LevelManager.applySeamlessTransition()
  │
  ├─ MUTATE_ONLY → applySeamlessMutation()
  │
  └─ RELOAD_TARGET_LEVEL → executeActTransition()
       │
       ├─ Set zone/act
       ├─ loadLevelData()        ← layout + collision only
       ├─ applySeamlessMutation()
       ├─ resetManagersForActTransition()
       ├─ applySeamlessOffsets()
       ├─ restoreCameraBounds()
       ├─ camera.updatePosition(true)
       ├─ initLevelEvents()
       └─ music/title card
```

### Testing Strategy

1. **Unit test `executeActTransition()`** — Verify it calls `loadLevelData()` (not `loadLevel()`), resets managers, applies offsets, and restores camera bounds.
2. **Verify existing S3K headless tests still pass** — `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`.
3. **Verify `SEAMLESS_RELOAD` removal compiles cleanly** — No remaining references.
4. **All 1522 tests pass** — Full regression.

### Why Not Profile Steps?

Act transitions are fundamentally different from level loads. The ROM's `Level:` entry point (our profile system) handles cold starts, checkpoint resumes, and death respawns. Act transitions bypass `Level:` entirely — they're inline data swaps within the game loop. Forcing them through the profile system requires gating logic that obscures the actual behavior and makes both paths harder to reason about.
