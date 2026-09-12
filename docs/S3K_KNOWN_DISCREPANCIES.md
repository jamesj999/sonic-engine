# Known Discrepancies from Original S3K ROM

This document tracks **intentional deviations** from the original Sonic 3 & Knuckles ROM. Entries here are architectural choices we've made (cleaner code, added features, deliberate corrections of known ROM bugs) that we accept and do not plan to revert. Runtime gameplay behavior is preserved unless a rationale explicitly justifies a visible change (e.g., the "Save System" entry adds JSON persistence that replaces SRAM).

**What does NOT belong here:**
- Bugs, incomplete implementations, and parity gaps that we *intend to fix* → [s3k-known-bugs.md](status/s3k-known-bugs.md)
- General (cross-game) engine-level issues → [known-bugs.md](status/known-bugs.md)
- General (cross-game) intentional discrepancies → [known-discrepancies.md](status/known-discrepancies.md)

Each entry describes what the ROM does, what we do, and why — focusing on *why* the divergence is acceptable.

**TraceChaser extraction note (2026-08-29):** the optional pinned trace-tool
submodule changes recorder ownership, not Sonic 3 & Knuckles runtime behaviour.
No S3K discrepancy was added or reclassified by the cutover.

## Table of Contents

1. [AIZ Intro Object Spawn Source](#aiz-intro-object-spawn-source)
2. [Obj_Wait Timer Pattern](#obj_wait-timer-pattern)
3. [Host-side KosM Preparation with Native Readiness](#host-side-kosm-preparation-with-native-readiness)
4. [Knuckles DPLC Pre-Loading](#knuckles-dplc-pre-loading)
5. [Save System](#save-system)
6. [Tails Flying-With-Cargo Physics](#tails-flying-with-cargo-physics)
7. [HCZ Object Mappings: Removal of `docs/` Runtime Reads](#hcz-object-mappings-removal-of-docs-runtime-reads)
8. [AIZ2 Battleship Ship-Loop Display Compensation](#aiz2-battleship-ship-loop-display-compensation)
9. [LBZ1 Miniboss Box Pieces: PLC VRAM Restore Skipped](#lbz1-miniboss-box-pieces-plc-vram-restore-skipped)
10. [LBZ2 Launch Pad Collapse: Mutation Pipeline Offset](#lbz2-launch-pad-collapse-mutation-pipeline-offset)
11. [LBZ2 End Boss Smoke Puffs: Immortal-Object Quirk Not Replicated](#lbz2-end-boss-smoke-puffs-immortal-object-quirk-not-replicated)
12. [LBZ2 Finale Player Scripts: Engine Animation IDs Instead of Raw Mapping Frames](#lbz2-finale-player-scripts-engine-animation-ids-instead-of-raw-mapping-frames)
13. [AIZ2 Boss Rewind: Transient Combat/Cosmetic Children Restored](#aiz2-boss-rewind-transient-combatcosmetic-children-restored)
14. [MHZ StickyVine Pull: Heuristic Replaced with ROM `sub_3EC66` Vector Math](#mhz-stickyvine-pull-heuristic-replaced-with-rom-sub_3ec66-vector-math)
15. [Madmole Cap/Body: Single Merged Object Instead of Parent+Child Split](#madmole-capbody-single-merged-object-instead-of-parentchild-split)
16. [MHZ Dragonfly Tail Ripple: Explicit One-Frame Gate Instead of Object-List Reordering](#mhz-dragonfly-tail-ripple-explicit-one-frame-gate-instead-of-object-list-reordering)
17. [MHZ Swing Vine / Vertical Swing Bar Forced Camera Scroll (Resolved)](#mhz-swing-vine--vertical-swing-bar-forced-camera-scroll-resolved)
18. [MHZ2 End-Boss Background Vertical Deform (`sub_554B8`)](#mhz2-end-boss-background-vertical-deform-sub_554b8)
19. [MHZ Deferred Items: Out-of-Scope Divergences Confirmed During the Parity-Fix Wave](#mhz-deferred-items-out-of-scope-divergences-confirmed-during-the-parity-fix-wave)
20. [Super Emerald Special Stage Results: Sanctuary Reveal Replaced by an Immediate Exit](#super-emerald-special-stage-results-sanctuary-reveal-replaced-by-an-immediate-exit)
21. [Air Countdown Digits: Rebuilt Mapping Frames Instead of VRAM DMA](#air-countdown-digits-rebuilt-mapping-frames-instead-of-vram-dma)
22. [YM Service Timing: Source-Relative Timeline Without Absolute VInt Phase](#ym-service-timing-source-relative-timeline-without-absolute-vint-phase)

---

## YM Service Timing: Source-Relative Timeline Without Absolute VInt Phase

**Location:** `YmServiceTimingProfile`, `YmWriteTimeline`, `SmpsDriver`, and
`VirtualSynthesizer`.

### Original implementation

The retail Z80 sound driver runs asynchronously and writes the YM2612 through
the hardware bus. The observed Blue Sphere FM5 upload has stable relative bus
spacing, while its absolute placement within a VInt depends on scheduling state
outside the retained bounded capture.

### Our implementation

OpenGGF starts the S3K sound service at its engine-owned service boundary and
schedules every FM write by the native source-relative master-cycle vector.
The YM2612 advances and drains the writes at internal-sample boundaries. The
engine does not claim or synthesize an absolute native VInt phase, and Sonic 1
and Sonic 2 retain untimed profiles under their separately audited drivers.

### Rationale

The relative vector is reproducible across twelve native upload groups and is
enough to preserve chip evolution during the write sequence. Inventing an
absolute phase from a bounded movie would fit the fixture rather than model the
ROM. The retained evidence also contains no DMA-contended upload, so no special
DMA timing is inferred. These limits are explicit in the validation report and
do not introduce trace-fed runtime state.

### Verification

The native lab, compact oracle, transactional service tests, rewind/observer
guards, bounded playback trace, and three-ROM audio suites are recorded in
`docs/architecture/validation/audio/2026-08-22-s3k-blue-sphere-audio-validation.md`.
The replacement trace requires a closed-world drained prefix: its exact
13-event committed Spring tail is followed immediately by the exact Blue Sphere
attack stream, with no intervening event. The pending service contains only the
reviewed 34 source/segment-tagged writes and rejects every music or
completion-restore prefix. The rollback guard structurally requires its capture as the first
unconditional transaction-helper statement and rejects any additional work.
Human listening remains required before integration.

---

## AIZ Intro Object Spawn Source

**Location:** `Sonic3kAIZEvents.java`  
**ROM Reference:** `sonic3k.asm` line 8111+ (`SpawnLevelMainSprites`)

### Original Implementation

The ROM creates `Obj_AIZPlaneIntro` inside `SpawnLevelMainSprites`, which runs during the main level initialization sprite pass:

```asm
    cmpi.w  #0,(Current_zone_and_act).w     ; AIZ Act 1?
    bne.s   loc_6834
    cmpi.w  #2,(Player_mode).w              ; Not 2-player?
    bhs.s   locret_6832
    move.l  #Obj_AIZPlaneIntro,(Dynamic_object_RAM+(object_size*2)).w
    clr.b   (Level_started_flag).w
```

### Our Implementation

We spawn the intro object from `Sonic3kAIZEvents`, the zone-specific level event handler. `init(act)` calls
`spawnIntroObject()` when `shouldSpawnIntro(act)` holds (act 1 and the bootstrap is not skipping the intro), and
`updateAct1()` re-runs it as a one-shot fallback if the object is missing before the camera reaches the start of
the tracked range. The spawn reuses the ROM's fixed dynamic-object slot rather than allocating a fresh one:

```java
private boolean spawnIntroObject() {
    AizPlaneIntroInstance existing = findLiveIntroObject();
    if (existing != null) {
        // ROM SpawnLevelMainSprites installs Obj_AIZPlaneIntro in a fixed
        // dynamic-object slot before the first Process_Sprites call
        // (sonic3k.asm:7849-7853, 8111-8126). A duplicate engine event init
        // must re-adopt that live object, not allocate a second parent.
        AizPlaneIntroInstance.adoptActiveIntroInstance(existing);
        return true;
    }
    // ...
    ObjectSpawn spawn = new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0);
    AizPlaneIntroInstance intro = lm.getObjectManager().createDynamicObjectAtSlot(
            () -> new AizPlaneIntroInstance(spawn), AIZ_PLANE_INTRO_SST_SLOT);
    // ...
}
```

`init()` also clears the camera's level-started flag before the spawn, matching the `clr.b (Level_started_flag).w`
in `SpawnLevelMainSprites`.

### Rationale

1. **Consistent with engine architecture** - All dynamic object spawning for cutscenes goes through level event handlers (for example `Sonic2CNZEvents` spawning the CNZ boss). No separate `SpawnLevelMainSprites` equivalent exists.
2. **Object exists from frame 1 either way** - Both paths create the object before the first `update()` call.
3. **Cleaner init flow** - Zone-specific behavior belongs in zone event handlers, not in a monolithic sprite spawning routine.

### Verification

The intro object is active on the first frame of level execution, identical to the ROM's timing.

---

## Obj_Wait Timer Pattern

**Location:** `AizPlaneIntroInstance.java`, `CutsceneKnucklesAiz1Instance.java`  
**ROM Reference:** `sonic3k.asm` `Obj_Wait` subroutine, SST offsets `$2E`/`$34`

### Original Implementation

The ROM uses a convention where SST offset `$2E` is a countdown timer and `$34` is a 32-bit pointer to a callback routine. `Obj_Wait` decrements `$2E` each frame and calls the routine at `$34` when it reaches zero:

```asm
Obj_Wait:
    subq.w  #1,$2E(a0)
    bpl.s   locret
    movea.l $34(a0),a1
    jmp     (a1)
```

### Our Implementation

We use explicit named fields (`waitTimer`, `waitCallback`) or inline timer logic within each routine method, rather than raw SST offset conventions:

```java
if (--waitTimer < 0) {
    onWaitExpired();  // or direct routine advance
}
```

### Rationale

1. **Named fields are self-documenting** - `waitTimer` is clearer than `$2E(a0)` when reading Java code.
2. **No function pointer indirection needed** - Java's method dispatch and routine switch make callbacks unnecessary; the expired handler is just the next case in the state machine.
3. **Same timing behavior** - The countdown interval and frame-exact trigger points are identical.

### Verification

Timer-driven routine transitions fire on the exact same frame as the ROM's `Obj_Wait` pattern.

---

## Host-side KosM Preparation with Native Readiness

**Location:** `S3kKosModuleQueue.java`, `AizPlaneIntroInstance.java`, `Sonic3kAIZEvents.java`
**ROM Reference:** `sonic3k.asm` `Queue_Kos_Module` calls at `loc_6777A`, `Kos_decomp_queue_count` gate in `AIZ1_Resize`

### Original Implementation

The ROM queues KosinskiM-compressed art for deferred DMA transfer during V-blank:

```asm
    lea     (ArtKosM_AIZIntroPlane).l,a1
    move.w  #tiles_to_bytes(ArtTile_AIZIntroPlane),d2
    jsr     (Queue_Kos_Module).l
    lea     (ArtKosM_AIZIntroEmeralds).l,a1
    move.w  #tiles_to_bytes(ArtTile_AIZIntroEmeralds),d2
    jsr     (Queue_Kos_Module).l
```

This queues decompression work across hardware service intervals. Downstream,
`AIZ1_Resize` keeps the intro deformation active until the queue drains.

### Our Implementation

The engine now submits the real ROM-backed archives to
`S3kKosModuleQueue`. Its resumable decoder advances at the native
pre-main-loop and post-object boundaries, and consumers retain typed handles
until the queue admits readiness. Replay may hold an already-prepared matching
job until its independently recorded completion edge, but it cannot supply
payload or decoder progress. AIZ publishes its terrain overlays and changes
phase only after claiming that prepared queue output.

### Rationale

The remaining intentional difference is below the ROM-visible readiness
boundary: the host does not emulate instruction-by-instruction decoder
pre-emption or VDP bus bandwidth. It advances deterministic whole work units
at the correct service points. Queue order, ROM archive identity, module
count, prepared payload, readiness polling, and the consumer's downstream
mutation remain native-owned and rewind-safe.

### Verification

`TestS3kKosModuleQueue`, `TestSonic3kTitleCardKosQueue`,
and `TestS3kKosStructuralSequence` cover decoder parity, FIFO ownership,
and consumer polling. Recorded-identity and composed before/on/after
completion rewind acceptance remain pending publication of the corrected
6.35 timing fixtures; candidate-only integration tests are intentionally not
part of the green prerequisite bundle.

---

## Knuckles DPLC Pre-Loading

**Location:** `CutsceneKnucklesAiz1Instance.java`  
**ROM Reference:** `sonic3k.asm` `Perform_DPLC` calls in `CutsceneKnux_AIZ1`

### Original Implementation

The ROM uses Dynamic Pattern Loading Cues (DPLC) to transfer only the patterns needed for the current animation frame into VRAM each frame:

```asm
CutsceneKnux_AIZ1:
    ...
    lea     DPLCPtr_CutsceneKnux(pc),a2
    jsr     (Perform_DPLC).l
    jmp     (Draw_Sprite).l
```

This minimizes VRAM usage by loading only the active frame's tiles, reusing the same VRAM region as the frame changes.

### Our Implementation

We pre-load all Knuckles cutscene frames at init time, assigning each frame's patterns to distinct tile indices:

```java
// Load all DPLC frames at init
for (int frame = 0; frame < frameCount; frame++) {
    loadDplcFrame(frame, baseArtTile + frameOffset);
}
```

### Rationale

1. **No VRAM scarcity** - Modern systems have abundant texture memory; the VDP's limit does not apply directly.
2. **Eliminates per-frame pattern transfer** - No need to track which frame was last loaded or detect frame changes.
3. **Simpler rendering** - Each mapping frame references stable tile indices, making the draw path straightforward.

### Verification

Every Knuckles animation frame displays the correct patterns at the correct positions, matching the ROM's per-frame DPLC result.

---

## Save System

**Location:** `com.openggf.game.save`, `com.openggf.game.dataselect`, `com.openggf.game.sonic3k.dataselect`  
**ROM Reference:** `sonic3k.asm` SRAM routines (`ReadSaveGame`, `WriteSaveGame`), save-screen objects (`ObjDat_SaveScreen`, `Obj_SaveScreen_*`)

### Original Implementation

The ROM stores save data directly in battery-backed SRAM at fixed offsets. Each of the 8 slots occupies a contiguous region with zone/act, character, emerald, and clear flags packed into specific byte positions. The save screen itself is object-driven, with authored selector/card objects and mappings rather than a debug-style overlay.

### Our Implementation

OpenGGF now keeps the native S3K save-screen flow but stores saves as JSON envelopes instead of raw SRAM. Key differences:

- **Per-slot JSON files** stored at `saves/s3k/slotN.json` wrapped in a `SaveEnvelope` with version, game code, slot number, payload, and hash.
- **SHA-256 integrity** rather than the ROM checksum routine. Hash mismatches log warnings during Data Select scan but do not block otherwise valid saves.
- **Corrupt quarantine** - malformed, unreadable, wrong-game, or structurally invalid save files are renamed to `.corrupt` and treated as empty slots.
- **No-op unsaved sessions** - save requests route through `SaveSessionContext`; when no slot is active, they silently no-op.
- **Snapshot providers** - game-specific payload capture is handled by `SaveSnapshotProvider` implementations rather than direct SRAM-style writes.
- **Session-owned launch metadata** - active slot ownership, selected team, and launch zone/act are carried by `WorldSession` and `SaveSessionContext` rather than being inferred from config during gameplay.
- **Restricted clear restart modeling** - clear slots use Java-side restart tables reconstructed from the disassembly, including Knuckles-specific restrictions, rather than exposing unrestricted level selection.
- **Native S3K save-screen parity** - the native `S3K` `1 PLAYER` route now renders from the authored object layout and mapping frames; the old RECTI/text-placeholder selector path is gone on that production path. Cross-game donation remains separate work, and the temporary S1/S2 placeholder managers are not part of this parity claim.

### Rationale

1. **Platform independence** - JSON files work on any OS without SRAM hardware emulation.
2. **Human-readable** - save files can be inspected and manually edited for debugging.
3. **Extensible** - the envelope format supports versioning and per-game payload schemas.
4. **Parity with the original menu flow** - the S3K save screen now follows the original authored layout and selector behavior, while the backend storage remains engine-owned.

### Verification

`TestSaveManager` verifies round-trip write/read, hash validation, corrupt quarantine, wrong-game detection, replacement of stale `.corrupt` artifacts, and no-op unsaved sessions. `TestS3kSaveSnapshotProvider` verifies payload capture includes team, zone, act, lives, emerald count, and clear-restart metadata. `TestS3kDataSelectPresentation` verifies the native save-screen renderer uses authored layout objects and mapping frames instead of the old RECTI overlay path. `TestGameLoop` verifies active-slot saves are written on bonus-stage and special-stage returns, that `S3K` `ONE_PLAYER` routes into native Data Select, and that `TWO_PLAYER`/overlay bypasses do not.

### Manual Validation

- `2026-04-13`: native S3K parity pass captured via `com.openggf.game.sonic3k.dataselect.S3kDataSelectVisualCapture`, which renders the live native S3K Data Select frontend with real ROM assets into `target/s3k-dataselect-visual/native_s3k_dataselect_slot1.png` for inspection.

---

## Tails Flying-With-Cargo Physics

**Location:** Tails flight physics (`TailsFlightController`, `PlayableSpriteMovement.applyGravity`); CNZ1 carry and CPU flight routines (`SidekickCpuController`)
**ROM Reference:** `sonic3k.asm:27592` `Tails_Move_FlySwim` (+0x08 flight gravity), `sonic3k.asm:27553` `Tails_Stand_Freespace` (branch on `double_jump_flag`)

### Original Implementation

ROM `Tails_Stand_Freespace` at `sonic3k.asm:27553-27555` branches to `Tails_FlyingSwimming` whenever `double_jump_flag(a0)` is non-zero, swapping the normal `+0x38` air gravity for `+0x08` flight gravity from `Tails_Move_FlySwim` (sonic3k.asm:27633 `loc_1488C`). The flag is set when Tails picks up Sonic for the CNZ1 carry intro (`loc_13FC2` at sonic3k.asm:26904 writes `double_jump_flag=1`) and is NOT cleared by the ground-release path at `loc_14016` — Tails continues under flight physics until he actually touches the floor.

### Our Implementation

The engine reproduces this behavior with a feature-scoped gate rather than a flat bit check:

1. `SidekickCpuController.updateCarryInit()` sets `sidekick.setDoubleJumpFlag(1)` at the same point ROM `loc_13FC2` writes the flag.
2. The ground-release branch in `updateCarrying()` zeros Tails's `x_vel/y_vel/ground_vel` and keeps the air bit set (matching ROM `loc_14016` at sonic3k.asm:26923-26946). Crucially, it does NOT clear `double_jump_flag` — the ROM leaves it set so Tails continues in flight physics for at least one more tick while the carry-release impulse propagates to Sonic.
3. `PlayableSpriteMovement.applyGravity()` and `doObjectMoveAndFall()` gate flight gravity on `sprite.getSecondaryAbility() == FLY && sprite.getDoubleJumpFlag() != 0` (mirrors `Tails_Stand_Freespace` → `Tails_FlyingSwimming` branch).
4. Tails's CPU flight AI — `Tails_Catch_Up_Flying` (routine 0x02 at `sonic3k.asm:26474`) and `Tails_FlySwim_Unknown` (routine 0x04 at `sonic3k.asm:26534`) — is ported into `SidekickCpuController.CATCH_UP_FLIGHT` / `FLIGHT_AUTO_RECOVERY`, plus the NORMAL → `FLIGHT_AUTO_RECOVERY` transition on a dead leader.

### Rationale

1. **Feature-scoped gate over raw flag** — `double_jump_flag` is overloaded in the ROM: Sonic's insta-shield uses it (values 1-$20 during shield timing), Knuckles's glide uses it (1=gliding, 2=stopped, 3=sliding), and Tails's flight uses it (non-zero = flight-gravity). Gating the flight-gravity substitution on `SecondaryAbility.FLY` prevents Sonic's insta-shield and Knuckles's glide from accidentally acquiring the `+0x08` gravity. The ROM achieves the same scoping naturally because only Tails's code path hits `Tails_Stand_Freespace`.
2. **Plan reference** — See `docs/architecture/plans/2026-04-24-s3k-tails-cpu-flight-ai.md` for the full breakdown of the carry-release and flight-AI ports.

### Verification

`TestSidekickCpuControllerCarry`, `TestSidekickCpuControllerCatchUpFlight`, and `TestSidekickCpuControllerFlightAutoRecovery` cover the state-machine transitions. `TestS3kCnzCarryHeadless` verifies the CNZ1 intro carry-release frame window.

---

## HCZ Object Mappings: Removal of `docs/` Runtime Reads

**Location:** `Sonic3kObjectArtProvider.java`, `Sonic3kConstants.java`
**ROM Reference:** `Lockon S3/LockOn Data.asm:838` (`Map_HCZMiniboss`), `:856` (`Map_HCZEndBoss`), `:192` (`Map_HCZWaterWall`)

### Original Implementation (engine, pre-fix)

`Sonic3kObjectArtProvider` previously parsed three HCZ object mapping tables (`Map_HCZMiniboss`, `Map_HCZEndBoss`, `Map_HCZWaterWall`) by reading `.asm` source files from `docs/skdisasm/Levels/HCZ/Misc Object Data/` at runtime via `Files.readAllLines`, falling back to an empty mapping list (and therefore invisible sprites) whenever the disassembly tree was absent. This violated the project's "ROM only for runtime assets" hard rule documented in `CLAUDE.md`.

### Fixed Implementation

All three call sites now read mapping bytes from the user-supplied ROM via `S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr)` using the new constants:
- `MAP_HCZ_MINIBOSS_ADDR = 0x3629E0` (was incorrectly `0x362A28`, which pointed at the first frame body rather than the offset table base)
- `MAP_HCZ_END_BOSS_ADDR = 0x3634D4`
- `MAP_HCZ_WATERWALL_ADDR = 0x22EE10`

Each address was derived from the disassembly's absolute `Frame_<addr>` labels (since the lock-on data is anchored at `org $200000` in the `Sonic3_Complete` build) and verified by reading the ROM at the computed offset and confirming the first word equals the expected offset-table size and the first frame's piece count matches the source.

The duplicate-frame workaround for shared `Frame_362BB0` labels in HCZ miniboss is no longer needed, because ROM-based reading of duplicate offsets yields duplicate frame entries naturally.

### Rationale

This is not a behavioral discrepancy from the ROM — sprite output is identical to before, when the disassembly tree was present. It is recorded here only because the previous implementation deviated from the project's ROM-only sourcing rule and silently degraded under the (CI / fresh clone) configurations where `docs/skdisasm/` is absent.

### Verification

`TestSonic3kLevelLoading` and `TestSonic3kBootstrapResolver` continue to pass. The `loadMappingsFromAsmInclude` helper and the three `Path` constants pointing under `docs/` have been removed from `Sonic3kObjectArtProvider`.

---

## MGZ2 Quake Chunk Source Address

**Location:** `Sonic3kMGZEvents.MGZ_QUAKE_CHUNK_ROM_ADDR`
**ROM Reference:** `0x3CBBB4`, S3-half `MGZ2_QuakeChunks`

The MGZ2 earthquake chunk replacement table is currently read from the S3-half
address `0x3CBBB4`. The project normally prefers S&K-side addresses for locked-on
S3K runtime data, but this quake table is recorded as a reviewed exception until an
equivalent S&K-side source is verified.

`TestArchitecturalSourceGuard.mgz2QuakeChunkS3HalfAddressIsReviewedAndDocumented`
pins both the runtime address and this documentation so the exception cannot drift
silently.

---

## AIZ2 Battleship Ship-Loop Display Compensation

Gameplay state follows the S&K disassembly: `AIZ2_DoShipLoop` writes
`Level_repeat_offset=$200` and subtracts `$200` from camera/player state when
the post-bombing ship loop reaches `$46C0`
(`docs/skdisasm/sonic3k.asm:105200-105221`). Do not change
`BATTLESHIP_WRAP_DIST_POST_BOMBING` away from `$200`.

**Display validated seamless (2026-06-16).** Once the AIZ trace frontier advanced
past the battleship (`TestS3kAizTraceReplay` first error f19089, into the AIZ2
end-boss arena), the ship-loop wrap was visually validated via a trace-faithful
`TraceCaptureTool` capture: at the `$200` wrap (trace f16507 cam `$443C` → f16508
cam `$4240`) the burning-forest background renders continuously with **no seam,
no repeated columns, and no empty `$200`–`$400` filler scrolling into view** — the
current non-wrapping `SwScrlAiz.battleshipSmoothScrollX` BG deform handles the loop
seamlessly. The earlier-deferred forest-loop BG fix
(`docs/architecture/plans/2026-05-29-aiz2-battleship-wrap-seam.md`) is **not
warranted**: its premise (empty filler scrolls into view) does not manifest with
the current smooth-scroll renderer. No remaining display gap.

---

## MHZ Swing Vine / Vertical Swing Bar Forced Camera Scroll (Resolved)

**Location:** `Camera.requestForcedScroll(int, int)`, `MhzSwingVineObjectInstance`,
`MhzSwingBarVerticalObjectInstance`
**ROM Reference:** consumer `loc_1BFB8` (`sonic3k.asm:38296-38300`), setters
`loc_226F2` (swing vine, `sonic3k.asm:47072-47074`) and `loc_3F0CC` (vertical bar,
`sonic3k.asm:83575-83577`)

Previously neither MHZ traversal object modeled the ROM `Scroll_force_positions`
flag, so while a player hung on the vine or climbed the vertical bar the camera kept
tracking the *player* with its normal deadzone/delay instead of the forced object
coordinates. Now resolved: `Camera` models the ROM flag as a frame-scoped,
coordinate-carrying request (`requestForcedScroll(forcedX, forcedY)`) that, for that
frame, zeroes the horizontal scroll frame offset (`H_scroll_frame_offset`) and points
the horizontal/vertical camera math at the forced coordinates instead of the focused
sprite (mirroring the existing `requestFastVerticalScroll()` `Fast_V_scroll_flag`
pattern). The request auto-clears each `updatePosition`.

The two consumers pass different coordinate pairs, matching the ROM setters:
- **Swing vine** — forced X and Y both from the vine object (`x_pos(a0)` / `y_pos(a0)`).
  The setter lives in the swing routine `loc_226B0`, so it fires only while the vine is
  swinging and Player 1 is grabbed (not during a stationary slow-grab hang).
- **Vertical bar** — forced X from the bar (`x_pos(a0)`), forced Y from
  `Player_1+y_pos`, fired every frame Player 1 is hanging.

The abstraction is game-agnostic; the AIZ ride-vine consumers (out of scope here) can
adopt it later. Covered by `TestCameraForcedScroll`,
`TestMhzSwingVineObjectInstance`, and `TestMhzSwingBarVerticalObjectInstance`.

---

## LBZ1 Miniboss Box Pieces: PLC VRAM Restore Skipped

**Location:** `LbzMinibossBoxRig.java` (`Phase.LINGER` removal), `Lbz1RobotnikEventController.java`, `LbzMinibossBoxInstance.java`
**ROM Reference:** `sonic3k.asm` `loc_8CF1E` (`PLC_LBZRobotnikAfter`, `PLC_MonitorsSpikesSprings`)

### Original Implementation

When the last LBZ1 miniboss box piece scrolls off screen, `loc_8CF1E` reloads
`PLC_MonitorsSpikesSprings` (subtype `$C` only) and `PLC_LBZRobotnikAfter`
(bubbles + LBZ misc art) before deleting itself, because the box/boss art had
overwritten those VRAM tile ranges on real hardware.

### Our Implementation

The engine loads the box, boss, and Robotnik ship art as standalone
`Pattern[]` sheets outside the level's shared pattern buffer, so no VRAM tiles
are overwritten and there is nothing to restore. The pieces' off-screen
removal range (`$280` coarse) and lingering drift behaviour are replicated;
only the PLC reloads are omitted.

### Rationale

Standalone PLC decompression is the project's preferred boss-art strategy
("Why standalone" in the s3k-implement-boss skill): it avoids the VRAM overlap
conflict entirely instead of emulating the overwrite-and-restore cycle.

### Verification

`TestS3kLbz1KnucklesSequenceHeadless#lbz1RobotnikFoldsAwayBurstPanelsAndKeepsDriftersUntilOffscreen`
covers the piece lifecycle including the off-screen cull;
`TestSonic3kPlcArtRegistry` guards the standalone sheets.

---

## LBZ2 Launch Pad Collapse: Mutation Pipeline Offset

**Location:** `Sonic3kLBZEvents.java`, `LbzZoneRuntimeState.java`

**ROM Reference:** LBZ2 launch finale dynamic events and foreground scroll state

### Original Implementation

The ROM drives the LBZ2 launch-pad collapse through its launch event RAM and
foreground scroll mechanics. The visible effect is a small foreground terrain
clear while the Death Egg launch scroll state is active.

### Our Implementation

The engine keeps `Events_fg_5` reserved for the LBZ1 -> LBZ2 transition path and
uses semantic launch state in `LbzZoneRuntimeState` instead. The pad-collapse
request is consumed by `Sonic3kLBZEvents`, which routes the terrain clear through
`ZoneLayoutMutationPipeline` / `LevelMutationSurface` and combines it with the
launch foreground-scroll offset. The copied 64-by-28-cell VDP window replaces
Plane A from screen Y=40 downward, without scrolling, in both visible tile
passes and the sprite-priority mask. Its final two VBlanks clear the remaining
upper Plane A strip, restore the six hidden rows, and disable the window.
Only the exposed upper strip follows the detach scroll; applying that scroll
to the lower window lifts the platform into grounded Sonic.

### Rationale

This is an intentional engine-equivalent divergence. Routing the collapse through
the shared mutation pipeline keeps gameplay tile edits rewind-safe, redraw-aware,
and covered by the no-direct-map-mutation policy while preserving the visible
launch-pad collapse behavior.

### Verification

`TestSonic3kLbzLaunchSignals` covers the copy/clear VBlanks, fixed window,
semantic pad-collapse signal, and mutation routing.
`TestForegroundWindowRendering` checks visible pixels, transparency, and the
priority mask when a GL context is available; `TestSonic3kLbzRewindRoundTrip`
covers the copied nametable and pending clear. `TestLbzLaunchRuntimeState`
covers rewind capture/restore for the launch state, and `TestNoDirectMapMutationsInGameplay` guards against direct
gameplay map writes.

---

## LBZ2 End Boss Smoke Puffs: Immortal-Object Quirk Not Replicated

**Location:** `LbzEndBossInstance.LbzEndBossSmokePuffChild`

**ROM Reference:** `sonic3k.asm` `loc_73BA0`/`loc_73BDC`, anim script `byte_741F8`

### Original Implementation

The LBZ2 end-boss smoke puff is meant to delay `-2*(subtype-$10)` frames, play
frames `7,7,8,9` at delay 5, then delete via the `$F4` anim command, whose
handler (`AnimateRaw_CustomCode`) aliases `Obj_Wait` with `$34 =
Go_Delete_Sprite`. However, the puff's main loop `loc_73BDC` executes
`addq.w #1,$2E(a0)` every frame, while the `$F4` command only runs
`subq.w #1,$2E(a0)` once per anim pass (~19 frames). `$2E` therefore grows
monotonically and never goes negative, so `Go_Delete_Sprite` never fires: the
ROM object loops its smoke frames in a leaked object slot until the level
unloads (invisible for the rising subtype-0 puffs once off-screen, and masked
by the short fight duration for the explosion-spray puffs).

### Our Implementation

The engine implements the clearly-intended behaviour: delay, play `7,7,8,9` at
the ROM cadence, then expire the child.

### Rationale

Replicating the slot leak would accumulate stale objects with no gameplay or
visual value the original authors intended. All other smoke parameters (delays,
frame cadence, subtype-0 rise velocity) match the ROM.

### Verification

`TestLbzEndBossInstance` covers the spike-ball spray spawning the four delayed
smoke puffs and the rolling-smoke speed gate.

---

## LBZ2 Finale Player Scripts: Engine Animation IDs Instead of Raw Mapping Frames

**Status:** Resolved for the post-boss player scripts. The heading remains for existing links.

**Location:** `LbzFinalBoss1Instance.java`

**ROM Reference:** `sonic3k.asm` `loc_72C68`/`loc_72C9E`,
`Animate_ExternalPlayerSprite`, `byte_7386A`/`byte_73874`.

The finale uses raw mapping frames `$55`, `$59`, `$5A` at six-dispatch
intervals. The external animator advances past the table's initial `$C4` entry.
Sonic's terminal zero on dispatch 19 changes the boss routine to `loc_72CC6`;
P2 still receives its animation call on that dispatch, then both mapping frames
remain held through the final fall. P2's longer table is consequently not run
to completion. Render flipping does not change the player's status-facing bit.
The shipped `FixBugs=0` branch preserves P2's animation byte during setup and
clears it on the following external-animation dispatch.

`TestLbzFinalBoss1Instance` verifies the setup, six-dispatch cadence, terminal
callback, retained mappings, and final-fall transition threshold. See the
[ending audit](architecture/audits/2026-09-09-lbz2-ending-sequence.md) for the
review scope and validation limits. These checks do not establish pixel-perfect
parity for the complete scene.

---

## AIZ2 Boss Rewind: Transient Combat/Cosmetic Children Restored

**Location:** `Sonic3kObjectRegistry.java` (`DYNAMIC_REWIND_CODECS`), `ObjectManager.recreateDynamicObject`

### Behaviour

The held-rewind system recreates dynamic objects on a backward seek via per-class
rewind codecs. Held rewind restores the nearest keyframe and then re-simulates forward
to the displayed frame every frame, so an object reverses cleanly only if it is captured
in the keyframe and recreated on restore. The AIZ2 ship-loop driver objects
(`AizBattleshipInstance`, `AizBossSmallInstance`, `AizBgTreeSpawnerInstance`,
`AizBgTreeInstance`), the boss-endgame `Aiz2BossEndSequenceController`, and the
**structural** miniboss/end-boss children (body, arm, ship, flame column, napalm
controller, flame barrel) have codecs.

In addition, all of the short-lived **combat and cosmetic** children now have codecs and
are restored across a rewind boundary:

`AizShipBombInstance`, `AizBombExplosionInstance`, `AizMinibossBarrelShotChild`,
`AizMinibossBarrelShotFlareChild`, `AizMinibossImpactFlameChild`,
`AizMinibossFlameChild`, `AizMinibossDebrisChild`, `AizEndBossPropellerChild`,
`AizEndBossFlameChild`, `AizEndBossBombChild`, `AizEndBossSmokeChild`,
`AizEndBossDebrisChild`.

There are no longer any intentionally-dropped AIZ2 battleship/boss transient children.

### Rationale

Previously these children were given no codec, so a rewind restore dropped them and the
forward re-simulation re-emitted them from scratch — bombs and boss attacks visibly
played *forward* and re-triggered/stacked instead of reversing. Restoring them makes the
whole scene rewind cleanly.

Each codec only builds a structurally-correct instance and relies on the generic field
capturer to reapply the captured non-final scalar state afterward:

- **Parent relink.** Codecs that need the live boss/ship find it in
  `getActiveObjects()`. Dynamic entries are captured and restored in spawn order, and a
  parent always spawns before its children, so the parent is already recreated when the
  child's codec runs. `AizShipBombInstance` relinks the live `AizBattleshipInstance`;
  the end-boss bomb/smoke and the miniboss flame relink their boss.
- **Sibling relink.** Some children also need a live sibling, not just the boss:
  `AizEndBossPropellerChild` needs its `AizEndBossArmChild`, `AizEndBossFlameChild` needs
  its `AizEndBossPropellerChild`, `AizMinibossBarrelShotChild` needs its
  `AizMinibossFlameBarrelChild`, and `AizMinibossBarrelShotFlareChild` needs its
  anchoring barrel shot. Siblings are recreated earlier in spawn order, so they are
  present. When several live siblings of the same type exist, the child is relinked to
  the one nearest its captured spawn position; if no live sibling/boss is present the
  child is dropped (codec returns null) rather than recreated with a dangling reference.
- **Self-contained.** `AizBombExplosionInstance`, `AizEndBossDebrisChild`,
  `AizMinibossImpactFlameChild`, and `AizMinibossDebrisChild` carry their world position
  in their spawn and need no relink.

To make the differentiating constructor arguments survive recreate, the fields that were
derived from non-spawn constructor args were made non-final so the generic field capturer
captures and reapplies them (the codec passes placeholders). Object-reference fields
(`sourceShip`, `boss`, `parent`, `arm`, `propeller`, `barrel`, `anchor`) remain final and
are relinked by the codec.

The bosses themselves (`AizMinibossInstance` id `0x91`, `AizEndBossInstance` id `0x92`)
are layout-spawned and recreated by the object registry, and they re-spawn their children
from a routine (not the constructor), so no double-spawn occurs.

### Verification

`TestAiz2ObjectRewindCodecs` asserts a codec exists for each restored class (including
the formerly-dropped transients). `TestAiz2TransientChildRewind` boots AIZ act 2, drops a
battleship bomb, captures, removes it, restores, and asserts the bomb is recreated with
its mid-flight scalar state (not reset to spawn defaults) and relinked to the live ship.
## Batch-2 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

`MgzEndBossDefeatDebrisChild` is intentionally **not** captured/recreated across a
held-rewind boundary (no rewind codec; its `#recreate` and `#finalScalar` keys stay in
`src/test/resources/rewind/coverage-baseline.txt`). It is short-lived cosmetic debris:
three MGZ end-boss fragments emitted at defeat that drift at constant velocity, render a
static frame, and self-destruct the moment they pass the offscreen margin. They hold no
player, score, terrain, or arena state, so a one-frame catch-up on forward re-simulation
is invisible. This mirrors the AIZ2 transient-children precedent above: capture is only
worthwhile when a dropped object would otherwise visibly re-emit and play forward; a
sub-lifetime cosmetic fragment that re-emits in-frame does not qualify.

All other batch-2 S3K transient/relink children (`AizRockFragmentChild`,
`CnzMinibossDebrisChild`, `S3kBossExplosionChild`, `S3kSignpostSparkleChild`,
`MhzPollenParticleInstance`, `MhzMinibossFlameInstance`,
`Sonic3kStarPostBonusStarChild`, `IczEndBossEggCapsuleInstance`,
`CaterkillerJrBodyInstance`, `BuggernautBabyInstance`) now have rewind codecs in
`Sonic3kObjectRegistry` and are restored on a backward seek. `Sonic3kSSEntryFlashObjectInstance`
now restores through graph-tested `RewindRecreatable`; compact restore resolves the exact
captured parent ring by `ObjectRefId` and reapplies the flash scalars.
`MhzMinibossEscapeShardInstance` likewise restores through graph-tested `RewindRecreatable`
generic recreate with exact/compact parent relink instead of a dynamic codec.

## Batch-4 Rewind: AIZ Intro Plane Children

`AizIntroEmeraldGlowChild` now models the two animated AIZ1 intro-plane pieces as real,
slot-owning dynamic SST objects, matching `CreateChild1_Normal` and preserving later
allocation pressure. Each child implements `RewindRecreatable`, relinks to the restored
`AizIntroPlaneChild`, and exposes its variant and offsets to generic compact scalar capture.
The three former coverage gaps therefore require neither a baseline exception nor a
zone-specific rewind codec. The children also render through the shared intro-plane art,
so their animation and slot identities survive backward seeks together with the parent
graph.

The remaining AIZ2/Batch-2 transient-children precedent above is narrower: capture is only
worthwhile when a dropped object would otherwise visibly re-emit and play forward.

The other batch-4 HCZ end-boss scene objects (`HczEndBossInstance`,
`HczEndBossEggCapsuleInstance`, `HczEndBossGeyserCutscene`, `HczEndBossRobotnikShip`,
`HczEndBossTurbine`, `HczEndBossBlade`, `HczEndBossBladeSplash`,
`HczEndBossBladeWaterChute`, `HczEndBossWaterColumn`) plus the AIZ boss/intro objects
(`AizEndBossInstance`, `Aiz2EndEggCapsuleInstance`, `AizIntroPlaneChild`,
`AizIntroWaveChild`) now have rewind codecs in `Sonic3kObjectRegistry` and are restored on
a backward seek.

---

## MHZ StickyVine Pull: Heuristic Replaced with ROM `sub_3EC66` Vector Math

**Location:** `MhzStickyVineObjectInstance.applyStickyPull`
**ROM Reference:** `sub_3EC66` (`sonic3k.asm` ~83210-83258), called from `loc_3EB26` after `sub_3EC2A`

### Original Implementation (engine, pre-fix)

`applyStickyPull` used a `sign(d)*clamp(|d|/2,1,4)` heuristic: it pulled the player 1-4
whole pixels per frame directly toward the anchor along each axis independently, and
derived the ground `ground_vel`-halving threshold from `abs(dx)<<6` rather than the ROM's
actual pull magnitude. This reproduced the ROM's air/ground branch *structure* (position
pull, air `x_vel` halving, ground `ground_vel` halving) but not its displacement magnitude
or true diagonal direction.

### Fixed Implementation

`applyStickyPull` now ports `sub_3EC66` directly: it computes the full 32-bit
(pixel:subpixel) delta between the player and the vine anchor in the same Q16.16
representation as the ROM's `x_pos`/`y_pos` longword (the engine's `xPixel`/`xSubpixel`
split already mirrors this exactly), takes the integer pixel component (`swap d1`/`swap
d2`) as the `GetArcTan(dxPixel, dyPixel)` input, derives magnitude `d3 =
(|dxPixel|+|dyPixel|)*2`, and looks up `(sin, cos) = GetSineCosine(angle)` via the
engine's shared `TrigLookupTable.calcAngle`/`sinHex`/`cosHex` (the same tables other S3K
objects such as `MGZDashTriggerObjectInstance` and `GumballItemObjectInstance` already use
for `GetArcTan`/`GetSineCosine` parity). The pull is a full 32-bit sub-pixel position
subtract (`x_pos -= cos(angle)*d3*4`; `y_pos -= sin(angle)*d3*2` while airborne only), and
the ground `ground_vel`-halving gate now compares against the ROM's actual `(cos(angle)*d3*4)
>> 8` pull magnitude instead of the heuristic's `abs(dx)<<6` proxy.

### Rationale

This was a parity bug, not an intentional divergence: the heuristic's per-frame pull was an
order of magnitude too large (whole pixels vs. the ROM's sub-pixel drift) and ignored pull
direction on the non-dominant axis, so the vine's "sticky" feel diverged visibly from the
ROM. It is recorded here per this document's fixed-bug precedent (see "HCZ Object Mappings"
above) so the before/after behavior change is traceable.

### Verification

`TestMhzStickyVineObjectInstance#activeAirPullMatchesRomArcTanSineCosineVector` asserts the
per-frame position delta for a known `(dx, dy)` matches `GetArcTan`/`GetSineCosine` computed
from the same production `TrigLookupTable`.
`TestMhzStickyVineObjectInstance#groundedStickyPullHalvesGroundSpeedWhenRomThresholdIsExceeded`
exercises the corrected ground `ground_vel`-halving gate with a drift large enough to clear
the ROM `$200`/`$10` threshold. The full `TestMhzStickyVineObjectInstance` suite passes.

---

## Madmole Cap/Body: Single Merged Object Instead of Parent+Child Split

**Location:** `MadmoleBadnikInstance.java` (MHZ, S3K SKL slot `$8C`)
**ROM Reference:** `Obj_Madmole`, `sonic3k.asm:193075-193526`

### Original Implementation

The ROM models Madmole as two separate objects sharing one placement-table
spawn: the **parent** (the visible ground cap) keeps its own SST slot and
calls `sub_8D876` (`SolidObjectFull`, `d1=$1F,d2=4,d3=5`) unconditionally
every frame — this call never depends on the body's state. The parent spawns
a **child** body object via `CreateChild1_Normal` (`ChildObjDat_8D9C0`) when a
player enters `$A0` range; the child runs its own rise/pause/drill/sink
routine on its own slot and has `collision_flags $0B` (a normal ENEMY hitbox)
while active. The parent sets `$38(a0)` bit 1 when it spawns the child and
waits (`loc_8D5D4`) for that bit to clear before re-arming. The **only** place
that clears it is the child's own normal sink-completion path
(`loc_8D6D6: bclr #1,$38(a1) / jmp Go_Delete_Sprite`). If the player instead
kills the child via the generic enemy-touch/`EnemyDefeated`-style path, the
child's own code never reaches `loc_8D6D6`, so the parent's bit 1 never
clears — the parent parks at `loc_8D5D4` forever, `sub_8D876` keeps running
every frame regardless, and the cap becomes a permanent solid stump that
never spawns a new child.

### Our Implementation

`MadmoleBadnikInstance` models the cap and body as **one merged engine
object** (single SST slot, single `AbstractBadnikInstance`) rather than a
ROM-faithful parent+child pair — the body's rise/pause/drill/sink cycle is a
`state` field on the same instance, and `getCollisionFlags()`/
`getSolidParams()` switch between the cap's zero-collision solid shape and
the body's `$0B` enemy shape based on that state. To reproduce the ROM
outcome above without a second SST slot, `destroyBadnik(PlayableEntity)` is
overridden: on defeat it snaps the state back to `BURIED` (cap-only,
`offsetY=0`, matching `sub_8D876` always being called against the parent's
unmoved position), sets a `bodyDefeated` latch that permanently short-circuits
`updateBuried()`'s range check (the engine equivalent of `$38(a0)` bit 1 never
clearing), and spawns the explosion/animal/points sequence at the body's
last position with a **freshly allocated slot** (`badnikSlot=-1`, `spawn=null`)
instead of transferring the parent's own slot — the merged object is never
marked `isDestroyed()`, so it keeps its own slot and keeps reporting solid
contact every frame, exactly like the ROM parent.

### Why This Is Acceptable

Runtime gameplay behavior is preserved: the cap remains a solid stump after
the body is defeated, and it never re-emerges — matching the ROM outcome
described above. Only the *object model* differs (one merged instance with an
internal latch vs. two SST-slot objects linked by a busy bit); this keeps the
existing single-object rewind/render/collision wiring for Madmole intact
rather than requiring a parent+dynamically-spawned-child split purely to
mirror ROM's slot layout.

---

## MHZ Dragonfly Tail Ripple: Explicit One-Frame Gate Instead of Object-List Reordering

**Location:** `DragonflyBadnikInstance.java` (`LinkedBodyChild.updateVerticalPhase`,
`DragonflyHoverReturnGate`)

**ROM Reference:** `sonic3k.asm` `loc_8DE8A`/`loc_8DEA8`/`loc_8DF24` (segment return-gate
check/set/clear), `CreateChild4_LinkListRepeated`/`AllocateObjectAfterCurrent`
(`sonic3k.asm:177038-177061,37917`)

### Original Implementation

Each Dragonfly linked-body tail segment gates its FOLLOW-to-RETURN transition on
`btst #2,$38` of its `parent3` anchor (the previous segment, or the Dragonfly itself for
segment 0). The observable one-frame ripple down the chain (segment *n* enters return
exactly one frame after segment *n*-1) is not itself an explicit delay in `loc_8DE8A`; it
falls out of `CreateChild4_LinkListRepeated` calling `AllocateObjectAfterCurrent` for every
segment, which always inserts the new child immediately after the *Dragonfly's own* object-list
slot. Because every segment is inserted at that same point, each later-created segment ends up
processed **before** the earlier ones each frame -- segment *n*-1 always runs after segment *n*
within a frame, so segment *n* only ever observes segment *n*-1's bit as it stood at the end of
the *previous* frame. The Dragonfly-to-segment-0 hop has no such delay, since the Dragonfly
itself always runs first.

### Our Implementation

The engine's object manager does not replicate ROM object-list slot insertion order, so
`LinkedBodyChild` models the *resulting* one-frame ripple directly instead: each segment tracks
its own `returnGateBit` (mirrors `$38` bit 2, set entering return, cleared completing the wait
phase) and a `returnGateVisibleToFollower` value that is promoted from `returnGateBit` once at
the start of the segment's own `update()`. A segment's FOLLOW-phase check reads its
`followAnchor`'s gate via the shared `DragonflyHoverReturnGate` interface, which the Dragonfly
implements directly (live/immediate, matching the same-frame Dragonfly-to-segment-0 visibility)
and each `LinkedBodyChild` implements via its delayed `returnGateVisibleToFollower`. This
reproduces the exact one-frame-per-link cascade regardless of the order the engine happens to
call `update()` on the chain each frame.

### Rationale

Replicating the ROM's actual object-list slot-reversal quirk would require reworking how
`ObjectManager` orders dynamic-object updates for an unrelated, incidental allocation-order
side effect, at high regression risk to every other spawned-child object. Modeling the
*observable* one-frame delay directly, keyed off each segment's own return-gate state rather
than object-list position, produces byte-for-byte identical animation timing without that risk.

### Verification

`TestDragonflyBadnikInstance#sevenSegmentTailEntersReturnOneFrameApartDownTheChainNotAllAtOnce`
spawns a real 7-segment tail and asserts each segment enters its return phase exactly one frame
after the previous segment. `TestDragonflyBadnikInstance#linkedChildStartsVerticalReturnWhenParentEntersHoverWait`
continues to cover the same-frame Dragonfly-to-segment-0 hop.

---

## MHZ2 End-Boss Background Vertical Deform (`sub_554B8`)

**Location:** `SwScrlMhz.java` (`computeMhzDeform`, `computeBgY`), `Sonic3kMHZEvents.java`
(`isBossAreaBackgroundDeformActive`), `MhzZoneRuntimeState.java`
**ROM Reference:** `sub_554B8` (asm ~113118-113151), dispatched from `MHZ2_BackgroundEvent_Index`
per the exact `Events_routine_bg` table below (asm 112861-113104)

### Original Implementation (engine, pre-fix)

`SwScrlMhz.computeMhzDeform` unconditionally computed the BG vertical scroll factor with
the standard `MHZ_Deform` formula, `Camera_Y_pos_copy * 5/32 + $76`, for every frame of
act 2. During the end-boss arena the ROM instead routes through `sub_554B8`, which uses
`(Camera_Y_pos_copy - $280) * 5/32 + $180` — a different offset and base. The engine never
modeled this branch, so the boss-arena background scrolled at the wrong vertical rate/bias
the whole encounter.

### Fixed Implementation

`Sonic3kMHZEvents.isBossAreaBackgroundDeformActive()` exposes the exact ROM routine-to-deform
mapping through `MhzZoneRuntimeState`, not a flat `Events_routine_bg >= $8` cutoff (the first
version of this fix used that cutoff and was corrected in review — see below). `SwScrlMhz`'s
`computeMhzDeform` branches on that predicate and calls the existing generalized
`computeBgY(cameraY, yOffset, baseY)` helper with `(0x280, 0x180)` when it is true, `(0, 0x76)`
otherwise.

The predicate is `act2BackgroundRoutine != 0 && act2BackgroundRoutine != $C`, which is every
routine value the field can hold except those two:

- Routine `0` (loc_551EE, asm 112883-112899): ROM is conditional on `P1.x >= $3700 && P1.y <
  $500`, but that is the exact condition `updateAct2InitialBackgroundEvent()` already tests to
  decide whether to advance to routine `4` within the same frame — so by the time this
  predicate is read, `act2BackgroundRoutine` only remains `0` when the ROM condition was
  false. Standard deform.
- Routine `4` (loc_55236, asm 112910-112911): ROM-unconditional `sub_554B8`. Numerically `< $8`,
  so the original `>= $8` cutoff wrongly used standard deform here.
- Routine `8` (loc_55250, asm 112922-112966): ROM three-way branches on `P1.y`/status bit 1, but
  `updateAct2EndBossCustomLayoutEvent()`'s three exits mean `act2BackgroundRoutine` only remains
  `8` across the frame boundary on the one ROM exit (loc_552E0) that calls `sub_554B8`; its other
  two exits transition to `$C` (standard) or `$10` (which itself funnels to `sub_554B8`) within
  the same frame.
- Routine `$C` (loc_552F8, asm 112977-112986): ROM-unconditional standard `MHZ_Deform`.
  Numerically `>= $8`, so the original `>= $8` cutoff wrongly used `sub_554B8` here.
- Routines `>= $10` (loc_55312 onward): all funnel to `sub_554B8` via loc_55486 (asm 113099).

`computeBgY`'s existing shift-based math (`asr.l #3` / `asr.l #2` + add, ported as Java `>>`,
not `/`) already floors negative deltas correctly per ROM semantics; a regression test pins
that so a future refactor toward `* 5 / 32` truncating division would be caught.

### Rationale

This is a parity fix, not an intentional deviation — recorded here per the MHZ parity-fix
plan's per-commit documentation rule so the resolved defect is traceable. Horizontal shake
handling (`Screen_shake_offset`) was already correct and untouched.

### Verification

`SwScrlMhzTest`:
- `endBossVerticalDeformUsesSub554B8BaseWhenRoutineBgIsInBossAreaRange` — routine `8` (the
  loc_552E0 exit) uses `sub_554B8`.
- `routineBg4UsesSub554B8Unconditionally` — routine `4` uses `sub_554B8` even though it is
  numerically below `$8`.
- `routineBgCUsesStandardMhzDeformUnconditionally` — routine `$C` uses standard `MHZ_Deform`
  even though it is numerically `>= $8`.
- `sub554B8UsesAsrFlooringNotTruncatingDivisionForNegativeDelta` — a `cameraY < $280` case
  asserts the ROM-exact floored result, which a truncating-division oracle would get wrong.

---

## MHZ Madmole deferred body deletion (corrected)

`loc_8D6D6` clears the cap's busy bit but `Go_Delete_Sprite` only installs
`Delete_Current_Sprite` for the next pass. The body still returns through
`Child_DrawTouch_Sprite` at its final submerged position. The consolidated
engine object previously snapped that still-collidable body onto the cap.
Cleanup now waits for the deferred-delete pass, preserving final movement,
collision, mapping and slot occupancy. The focused boundary test passes;
the MHZ zone replay advances from row 2,830 to row 6,958. Further route and
checkpoint parity remains unresolved (see the trace frontier log).

## MHZ Deferred Items: Out-of-Scope Divergences Confirmed During the Parity-Fix Wave

**Location:** various (see per-item notes below)
**ROM Reference:** various (see per-item notes below)

The 2026-07-01 MHZ parity-fix wave's audit identified several additional divergences that
were deliberately left unfixed, either because the engine's behavior is preferable to the ROM's,
because the item is a scope-limited follow-up, or because fixing it needs a dedicated
investigation this wave's task briefs explicitly excluded. Recorded here per the wave's Task 15
note and Task V1 (Step 5) reconciliation so they are tracked rather than silently dropped.

- **Standalone S&K entry remains outside the supported locked-on ROM scope.**
  `sub_54B80` tests `SK_alone_flag`, not the player character. The Act 1
  camera now follows the locked-on height rule for every character: min X
  `$C0` above Y `$580`, otherwise zero. The existing Knuckles boundary test
  covers this correction; further Knuckles-route work is excluded from the
  current Sonic/Tails completion scope.
- **Act 1 → Act 2 title-card handoff (implemented).**
  The old deferred note is obsolete. `updateAct1BackgroundEvent` requests
  the target-level reload with `TITLE_OWNER` art admission, preserved results
  globals, the in-level title card and its event-state reset. Existing
  `TestSonic3kMHZEvents` transition tests cover this contract.
- **MHZ1 miniboss offscreen lifetime (corrected, 2026-09-12).**
  The boss and its attached flames were subject to ordinary object-manager
  distance unloading. `Obj_MHZMiniboss` / `loc_751E2` ends in
  `Draw_And_Touch_Sprite`, and `loc_757D6` follows the parent's lifetime
  through `Child_DrawTouch_Sprite`; neither uses a distance-delete helper.
  Both now remain registered offscreen until explicit encounter cleanup.
  The first dash still intentionally leaves the screen and waits for the
  camera in `loc_75392`. A real-manager regression test checks offscreen
  retention, resumed movement, attached flames and explicit deletion.
- **Mushmeanie shell-direction pointer (verified, 2026-09-12).**
  The earlier stale-pointer diagnosis was incorrect: `Check_PlayerCollision`
  explicitly stores the selected player pointer into the parent's `$44`.
  `loc_8DC42` reads that pointer after `loc_8DC9C` reverses the player's
  velocity. The engine's saved attacking-player velocity follows this path;
  existing tests cover P1 and P2. No stale ROM-header read needs implementing.
- **PulleyLift level-select cheat.** The ROM's MHZ Pulley Lift object has an undocumented
  level-select easter-egg trigger; the engine does not reproduce it. Intentionally out of scope
  (`MhzPulleyLiftObjectInstance`).
- **Mushmeanie left-wall bounce sign (verified correct, 2026-09-12).**
  `loc_8DB94` selects `ObjCheckRightWallDist` or `ObjCheckLeftWallDist`, then both
  branches enter `loc_8DBB4`, which explicitly executes `add.w d1,x_pos(a0)`.
  The current `currentX += wall.distance()` therefore matches the shipped ROM,
  including its counterintuitive left-wall penetration correction. The earlier
  proposed subtraction was based on a different object's routine and is rejected.
  `TestMushmeanieBadnikInstance#leftWallBouncePreservesRomAddDistanceQuirk`
  covers the left branch. The separately recorded ICZ path-follow wall question
  still requires its own owning-routine investigation.
- **MHZ1 door latch ownership (corrected, 2026-09-12).**
  `_unkFAA9` now belongs to `MhzZoneRuntimeState` and is included in its rewind
  payload, so button/door streaming does not reset the lowered position.
  Contrary to the old note, full level reload does clear this byte:
  `loc_60DE` clears `_unkFA80` through `DMA_queue`. A newly installed MHZ
  runtime therefore resets it, including checkpoint reload. The focused
  recreation/rewind test covers both lifetimes.
- **`MhzEndBossDefeatFragmentChild` velocity table and end-boss body collision category
  (Task V1 Step 4 spot-checks).** The velocity table was verified against raw ROM bytes and found
  to be genuinely wrong (using `Obj_VelocityIndex` rows 4-9 instead of the ROM-correct rows 2-7,
  once `CreateChild6_Simple`'s subtype pre-scaling by 2 is accounted for) and has been fixed in this
  pass; see the class javadoc and `TestMhzBossObjects#mhzEndBossFadeWaitUnderflowSpawnsRomDefeatFragments`.
  The end-boss body's `BOSS`-category `getCollisionFlags()` (`0xCF`, inherited from
  `AbstractBossInstance`) alongside the dedicated `MhzEndBossHitProxyChild`'s `ENEMY`-category
  `0x25` was verified **correct as designed**: ROM's `Obj_MHZEndBoss` (`loc_76004`) sets the main
  body's own `collision_flags(a0)=$0F` and runs it through `Draw_And_Touch_Sprite` every frame,
  while the separately-allocated `ChildObjDat_76982 -> loc_764A0` hit-proxy child independently
  registers its own `word_76964`-sourced `collision_flags=$25` via `Child_AddToTouchList` -- ROM's
  own architecture genuinely scans both as independent touch surfaces with different roles (general
  body contact vs. the attackable weak point), predating this wave and already covered by
  `TestMhzBossObjects#mhzBossesExposeRomCollisionHitCounts` and the `MHZEndBossHitProxy*` tests.
  No fix needed for the collision-category half of this spot-check.
- **MHZ end-boss fragment parent-flip inheritance (corrected, 2026-09-12).**
  `CreateChild6_Simple` copies mappings/art_tile but not render flags;
  `loc_766CA` calls `SetUp_ObjAttributes`, which sets only bit 2. Consequently
  `Set_IndexedVelocity` sees the child's bit 0 clear regardless of the parent.
  Fragment construction now uses the unmirrored ROM velocity table for both
  parent orientations. `loc_766CA`'s first pass only initializes/draws; subsequent
  `Obj_FlickerMove` passes alternate hidden/visible starting hidden. Boundary
  deletion retains its slot until the next pass, matching `Go_Delete_Sprite_3`.
  The defeat-spawn test exercises both parent orientations and the first three passes.

### Verification

The defeat-fragment velocity fix is covered by
`TestMhzBossObjects#mhzEndBossFadeWaitUnderflowSpawnsRomDefeatFragments`. The parent-flip and Mushmeanie wall checks described above extend that coverage.
The native pulley menu/debug unlock and standalone S&K entry remain excluded.
Direct Sonic/Tails implementation coverage and validation are recorded in
[the completion audit](architecture/research/s3k-zones/mhz-completion-audit.md);
this section does not establish frame-perfect zone parity.

## S3K Bonus Stage Rewind: Gumball/Pachinko Live, Slot Machine Deferred

**Location:** `S3kSlotBonusStageRuntime`, `S3kSlotStageState`, `GameLoop.isBonusStageRewindable()`
**ROM Reference:** n/a (engine-only feature)

Live rewind was extended to the Gumball and Pachinko bonus stages via
`BonusStageProvider.supportsRewind()`, but the Slot Machine bonus stage intentionally continues to
report `supportsRewind()==false` and is excluded from rewind entirely (held rewind input is ignored
while it is active; no keyframes are recorded for it).

Slot Machine rewind is deferred rather than fixed here because `S3kSlotBonusStageRuntime` holds
live cross-references and bespoke mutable state that the standard object/level rewind adapters do
not capture:

- `S3kSlotStageState` — roughly 35 scalar fields, six `int[3]` arrays, and two `Deque<int[]>`
  reward queues.
- Runtime bookkeeping outside that state object: `continueAwarded`, `exitFadeStarted`,
  `exitTriggered`, `lastFrameCounter`, and the coordinator's own `slotFrameCounter`.
- A swapped-in player sprite (`slotPlayer`) driven by a custom `slotPlayerRuntime` (fixed-point
  `slotOriginX/Y`, exit-sequence phase), with CPU sidekicks suppressed for the duration.
- Parallel `List`s (`slotRingRewards` / `slotSpikeRewards`) of `ObjectManager`-tracked dynamic
  reward objects that would need to be reconciled back to their rewind-recreated instances
  (extending the `S3kSlotRewindSupport` re-resolution pattern already used elsewhere).
- Mutable render buffers (`S3kSlotRenderBuffers`); `pointGrid`/`visibleCells` are derived and
  could be rebuilt, but the animation state feeding them is not currently captured.

Because the Slot Machine's `updateDuringLevelFrame()` is `true`, the held-rewind re-simulation
stepper *would* drive `slotRuntime.update(...)` if rewind were enabled for it, so a faithful
snapshot/restore of all of the above is mandatory for deterministic re-simulation — this is
materially more work than the Gumball/Pachinko adapter (a single `BonusStageAccumulatorSnapshot`
covering rings/lives/shield) and was scoped out as its own follow-up.

**Decision:** Slot Machine rewind will be tackled together with Sonic 1's Special Stage in a
dedicated follow-up, because both need the same shape of fix — snapshotting a self-contained
minigame runtime with its own mutable state that bypasses the standard object/level snapshot model
(S1's special stage is a monolithic manager with a mutated layout array, custom player
physics/camera, and its own scroll accumulators; Slots has its runtime plus `S3kSlotStageState`). A
shared "self-contained runtime snapshot" approach should be designed once and applied to both,
after an investigation spike.

### Impact

None on the shipped Gumball/Pachinko rewind support. The Slot Machine bonus stage keeps its
pre-existing (non-rewindable) behavior; holding rewind while it is active is a no-op, matching its
behavior before this change.

## Gumball Machine Frame-0 RNG Reseed: Preserve Run-History Entropy, Do Not Clobber With a Session Counter

ROM `Obj_GumballMachine` seeds `RNG_seed` from `V_int_run_count` on init:

```
move.l (V_int_run_count).w,(RNG_seed).w   ; sonic3k.asm:127412
```

`V_int_run_count` is a persistent counter incremented every VBlank interrupt since power-on. The
purpose of this write is to fold *run-history entropy* (menu time, prior acts, etc.) into the
bonus-stage RNG so the ball-subtype roll (`sub_612A8`, `sonic3k.asm:127988-128008`) varies
run-to-run.

### Resolution (no longer a divergence)

`GumballMachineObjectInstance` previously re-derived that seed from its `update` `frameCounter`
argument — `ObjectManager.vblaCounter`, a per-gameplay-session object-dispatch counter that resets
on every session rebuild and lives in a materially smaller range than the hardware run counter
(observed `0x0400` locally vs. `0x1598` recorded for the same frame-0 tick). That was the actual
bug: `vblaCounter` is *not* `V_int_run_count`, so seeding from it clobbered a correct seed with a
wrong per-session count and flipped the ball subtype (e.g. awarding 10 rings for a ball the recorded
run never dispensed as a reward).

The engine's shared RNG **already carries** run-history entropy when the machine spawns: it has been
advanced by all prior gameplay in live play, and in trace replay
`TraceReplaySessionBootstrap.applyInitialRngSeedForReplay` has already primed it to the recorded
run's exact seed (that recording's `V_int_run_count`) — uniformly for every trace carrying
`metadata.rng_seed`, as ordinary initial-state reconstruction, not per-frame hydration. So the ROM
invariant `RNG_seed == V_int_run_count` is already satisfied by the RNG's own established state on
this tick. Modeling the reseed is therefore a read of that same value — a no-op — and the object no
longer performs the erroneous `setSeed`. This is applied **uniformly** to live play and trace
replay (no trace-identity gating, no simulation-time trace read), so there is no live/replay
behavioral split.

An earlier iteration instead had `TraceReplaySessionBootstrap` suppress the reseed *only during
trace replay*; that trace-gated split was reverted. The current fix removes the wrong reseed for
every caller, which both closes the trace divergence and keeps live play on the RNG's genuine
run-dependent entropy rather than the acknowledged-wrong session counter.

### Impact

The Gumball bonus stage's frame-0 ball-subtype roll now consumes the RNG's established
run-history seed on every path. Trace replay reproduces the recorded run's subtypes (the frontier
past the spurious early ring award); live play keeps run-dependent variety without a reference
recording to diverge from. No other Gumball, Pachinko, or Slots behavior is affected; the RNG stream
itself (post-seed advancement) is unchanged and remains ROM-faithful.

Slots is *also* affected by a `vblaCounter`-vs-`V_int_run_count` gap (the counter-provenance mismatch the Gumball roll previously had):
`S3kSlotBonusStageRuntime.globalVIntRunCounter()` feeds `ObjectManager.vblaCounter` into
`S3kSlotOptionCycleSystem.tick(...)` as the ROM-faithful *shape* of `Slots_CycleOptions`'s several
`V_int_run_count` reads (reel-word seeds, per-reel velocity offsets, the fixed-row scan seed, the
random-target draw, and the post-decelerate countdown extension — sonic3k.asm:99614-99946). Because
`vblaCounter` is a per-gameplay-session approximation with the same reset-cadence and smaller-range
mismatch described above, all of those Slots computations can select a different (and
differently-timed) reel target/prize than a specific recorded run whenever local session timing
differs from the original hardware's power-on-relative VBlank count, exactly as with the Gumball
frame-0 roll. This is the same underlying counter gap surfacing at a second, independent call site,
not a new discrepancy; the correct fix is the same deferred persistent VBlank-driven global counter
described above, which would resolve both sites at once.

### Resolution (trace replay only): metadata-primed `V_int_run_count` base

Unlike the Gumball reseed above (a single frame-0 read, satisfied once the shared RNG is primed),
`Slots_CycleOptions` reads `V_int_run_count` on an ongoing basis across the whole slots bonus stage,
so priming the shared RNG once at bootstrap cannot cover it. Recorder v6.32-s3k+ instead captures the
ROM `V_int_run_count` longword (sonic3k.constants.asm:790, `CrossResetRAM+$0C`) once at bonus-segment
arm time and emits it as decimal `metadata.v_int_run_count`, for gumball/pachinko/slots segments
(zone ids `0x13`-`0x15`; harmless no-op field for gumball/pachinko, which do not consume it).
`TraceReplaySessionBootstrap.applyBonusStageEntry` reads `TraceMetadata#recordedVIntRunCount()` and,
when present and the segment is `SLOT_MACHINE`, calls
`S3kSlotBonusStageRuntime.primeVIntRunCountForReplay(recordedBase)` immediately after
`onDeferredSetupComplete()` (same comparison-bootstrap "load a save state" pattern as
`applyInitialRngSeedForReplay`/`metadata.rng_seed`, and gated the same data-driven way — no
zone/route/frame carve-out). `globalVIntRunCounter()` then returns `recordedBase +
ticks-elapsed-since-priming` (ticks measured off the same `ObjectManager.vblaCounter` used before,
so the *cadence* is unchanged — only the base value moves) instead of the raw per-session
`vblaCounter` value.

**Measured effect (`TestS3kSlotsBonusTraceReplay`, `s3-knux-multibonus-ss`/`slots`):** this closes the
specific divergence this section originally cited (rings 75→76 at the trace's first reel resolution,
previously observed at frame ~269-271): with the base primed, that same reel resolves correctly and
the first divergence moves to frame 301 (still a `rings` off-by-one, now on a *later* reel cycle — the
machine cycles through multiple spins across the 1200-frame segment, each an independent
`Slots_CycleOptions` pass). Total report error-group count moved from 179 to 182 — a later frontier
that *unmasks* further reel-cycle divergences the original frame-271 cascade had been hiding, not a
regression in the fix itself: three separate `+1`/`-1`-scale `rings` mismatches remain (frames 301,
849, and a larger 971-1029 cluster consistent with a subsequently-diverged reward/exit path). A small
sweep of the tick-alignment constant (base+0 vs base+1 vs base+2 ticks-since-priming) confirmed
base+1 — the natural, no-fudge-factor result of priming once before the trace's frame 0 and reading
back after that frame's own `ObjectManager.update()` VBlank tick — is the local optimum; shifting
either direction strictly worsens both the error count and the frontier, so the residual is not a
further constant-offset bug. It is most likely either (a) a second, independent
`Slots_CycleOptions`-consumption timing wrinkle across multi-spin cycles, or (b) a lag/pause-frame
VBla-counter parity gap specific to this trace's later frames, and is left as an open, still-tracked
frontier item rather than force-fit with an unjustified per-cycle correction.

**Residual gap (unchanged):** live play and legacy traces recorded before v6.32-s3k still fall back to
raw `ObjectManager.vblaCounter` with no base correction — the underlying gap described above (a
per-gameplay-session counter standing in for hardware's power-on-relative `V_int_run_count`) is
**not** closed for live play; only trace replay's reproducibility of a specific recorded run's *first*
reel cycle is fixed, with a further multi-cycle/lag-parity gap left open per the measured effect above.
The correct live-play fix remains the same deferred persistent VBlank-driven global counter mentioned
above, which would resolve both the Gumball and Slots call sites (and this replay-only workaround) at
once.

---

## Gumball Bonus-Stage Exit Choreography: ~152-Frame ROM Sequence Not Reproduced

### Original Implementation

When the player descends to the gumball machine's exit trigger, the ROM plays a
~152-frame exit choreography, all inside `game_mode=0x8C` (the level-restart
variant of `Level`, `V_int` still ticking every frame). Decoded from the recorded
run `s3-knux-multibonus-ss/gumball` tail (interior frames ~1277-1429), the
sequence is three phases:

1. **~23 frames — player held at the machine bottom.** The player reaches the exit
   trigger and is held frozen at the chute (`y=0x35C`, `y_speed=0x0F70` clamped,
   `present=1`, `routine=02`) while the fade-to-black plays. The exit trigger
   (`loc_61050`: `subq.w #1,($FF2020).l` then `Check_PlayerInRange word_610AE`
   `x[-$100,$200] y[-$10,$40]`, sonic3k.asm:127741) fires `loc_61076` on the
   in-range frame, which sets `Restart_level_flag=1` and copies
   `Ring_count -> Saved_ring_count` (sonic3k.asm:127754-127765).
2. **~80 frames — `clearRAM Object_RAM` + level reload.** The player object is
   zeroed (`air 1->0`, `routine 0`, all fields 0) while the returning level
   decompresses/loads behind the black screen.
3. **~50 frames — return-level fade-in.** The player is repositioned to the star
   post (`y=0x25C`), the camera jumps to the return position, and the level fades
   in, still `routine=0`, before gameplay proper resumes.

### Our Implementation

The engine's live bonus exit is a ~21-frame `FadeManager` fade-to-black
(`GameLoop.exitBonusStage` -> `startFadeToBlack` -> `doExitBonusStage`) followed by
a synchronous `loadZoneAndAct` (~1 frame) and the normal return title-card path.
The ~152-frame ROM choreography is **not** reproduced; the engine returns to the
level roughly 130 frames sooner.

In the multi-stage chain replay, the return comparator is re-anchored to the
return segment's recorded offset after `stage_exit` is observed
(`AbstractRunChainTest.handoffIntoInterior` / the bonus branch of the return-attach
in `assertChainReplay`) rather than relying on the cursor arriving there
organically. `GameLoop.updateBonusStageMode`'s exit-fade freeze branch still
advances the shared playback cursor + VBla counter once per frozen frame — that is
retained as **correct `V_int` modeling** (the ROM's exit frames are `game_mode=0x8C`
with `V_int` ticking), and it narrows the drift, but it cannot close it because of
the reload phase below.

### Rationale

The choreography's frame count cannot be reproduced organically and reproducing it
would buy nothing that is validated:

- **~80 of the frames have no natural engine equivalent.** The ROM's phase-2 is the
  `clearRAM Object_RAM` + level decompression that spans ~80 black-screen frames;
  the engine performs the equivalent `loadZoneAndAct` synchronously in a single
  frame. There is no cursor-advancing engine work to fill those frames without
  artificial, zone-specific padding.
- **No gameplay-visible state depends on the duration.** The choreography is a
  fade + reload + fade-in; the player is either frozen or cleared throughout. The
  chain validates the *boundary state* instead — ring carry-over (asserted, and
  fixed to the ROM `Ring_count -> Saved_ring_count` copy above), checkpoint/star-post
  restore, and the return position — all of which are independent of how many frames
  the fade took.
- **The exit tail has no comparator coverage anyway.** Both the standalone
  `TestS3kGumballBonusTraceReplay` and the chain interior comparator stop diverging
  only at the stage exit (their first error is at the descent's last frame, ~f1276);
  neither compares the post-catch tail, so a duration-matching implementation would
  be unverifiable.

### Verification

Recorded `s3-knux-multibonus-ss/gumball/physics.csv.gz` + `aux_state.jsonl.gz` tail
decode (three phases above; `game_mode=0x8C` throughout via the frame-1277
`zone_act_state` event). `TestS3kGumballBonusTraceReplay` stays green (interior
comparator stops at the exit). `TestS3kMegaRunChain` clears the gumball round trip;
the chain's remaining seg2 (aiz_2) blocker is a separate landing/animation-state
fidelity slip at f186/f192, tracked in docs/status/trace-frontier-log.md, not this
divergence.

---

## HPZ Sanctuary Background: Screen Shake and Two-Band Plane Fill Not Modelled

The giant-ring destination `$1701` now selects the same sanctuary resource,
object, palette, camera, and scroll paths as the legacy engine alias `$1601`.
A routing regression had left the real destination on ordinary level defaults,
without its controller or seven emerald pedestals. ROM-backed lifecycle tests
exercise both destinations through the ceremony and pedestal traversal.
The rendering limitations below remain separate from that restored routing.

`SwScrlHpz` ports `HPZ_BackgroundInit` / `HPZ_BackgroundEvent` and their shared
scroll math at `loc_5A33C` (sonic3k.asm:120069-120280). Two parts of the ROM
routine are deliberately not carried over.

### Original Implementation

1. **`Screen_shake_offset` fold-in.** `sub_5A32C` / `sub_5A334` subtract
   `Screen_shake_offset` from `Camera_Y_pos_copy` before scaling by 3/16 and add
   it back to the result, and `loc_5A262` / `loc_5A2E4` tail-call
   `ShakeScreen_Setup` (sonic3k.asm:104219) to advance the offset. The sanctuary's
   only shake source is the falling-crystal ceremony, which writes a timed
   `Screen_shake_flag` that indexes `ScreenShakeArray`.
2. **Two-band plane fill.** `HPZ_BackgroundInit` copies `HScroll_table` word 0 to
   word 1 and word 2 to word 3, masks both with `$FFF0`, and passes
   `HPZ_BGDrawArray = {$200, $7FFF}` to `Refresh_PlaneTileDeform`, so the BG
   nametable is filled from the layout at two different X positions split at BG
   plane Y `$200`.

### Our Implementation

`SwScrlHpz.backgroundY` keeps the shake term as a single named local pinned to
zero at both ROM points, and the handler builds one background X base rather than
a banded plane fill. The 3/16 scroll rates, the `$EC0` seam offsets
(`$348`/`$000` and `$E00`/`$700`), the `loc_5A388` 3/4-to-1/4 gradient, and
`HPZ_BGDeformArray` are all modelled.

### Rationale

- **The shake has no amplitude to fold in yet.** `HPZSanctuaryFallingCrystalObjectInstance`
  publishes `GameStateManager.setScreenShakeActive(boolean)`, not the ROM's
  `Screen_shake_flag` countdown, so there is no per-frame offset for the handler to
  read. HPZ produced no screen shake before this handler existed either; modelling
  the counter is a separate change with its own owner, and the fold-in points are
  documented in place so it lands in one edit.
- **The plane-fill split is unobservable in the sanctuary.** `Sonic3kLevelResourceProfile`
  pins the hub camera at `($15A0, $0320)`, which puts `Camera_Y_pos_BG_copy` at
  `$01E6` — entirely inside band 0 of `HPZ_BGDrawArray`. The second band would only
  be reachable if the engine ever framed HPZ below BG plane Y `$200`, which no
  current route does.

### Verification

`SwScrlHpzTest` pins the provider route, both seam offset pairs,
`Camera_Y_pos_BG_copy = $01E6` for the special-stage return framing, and the
resulting deform bands (26 lines at `-$053E`, then `-$05B8` to the bottom of the
display). Headless `HeadlessGameBoot` capture of zone `$16` act 1 shows the
sanctuary crystal wall spanning the full display instead of surviving only in the
leftmost columns, which was the visible symptom of the previous generic-fallback
parallax.

---

## Super Emerald Special Stage Results: Sanctuary Reveal Replaced by an Immediate Exit

### Original Implementation

`Obj_SpecialStage_Results` routine 6 (`loc_2E512`, sonic3k.asm) sees a cleared Super
Emerald stage — S3 locked on, `SK_special_stage_flag` set, `Special_stage_spheres_left`
zero — and jumps to routine `$E` (`loc_2E616`) instead of falling through to the Chaos
Emerald check at `loc_2E540`. `SpecialStage_Results` has already rebuilt HPZ as the
backdrop for that routine (Layout_HPZ, `HPZ_128x128_*`, `PLCID_48`, camera at the shrine),
so routine `$E` pans `Camera_Y_pos` down to `$320` to reveal the shrine, spawns the Super
Emerald pedestal sprites and the invincibility-star orbit, and — once
`Super_emerald_count` reaches 7 — pans across and spawns ObjDat2_2E984,
"NOW &lt;name&gt; CAN / BE HYPER &lt;name&gt;". The seven small Chaos Emerald indicators at
`loc_2EAA6` test `cmpi.b #1` against `Collected_emeralds_array`, so on this screen — where
every collected emerald is state 2 or 3 — none of them draw.

### Our Implementation

The engine's Super Emerald results screen keeps the `Pal_Results` backdrop, so it has no
shrine to pan to. The Chaos Emerald reveal is correctly suppressed and the screen exits
after the bonus tally; the sanctuary itself is then entered through the ordinary Big Ring
route. The small emerald indicators remain gated on "collected at all" rather than the
ROM's exact `state == 1`, so they stay visible on the Super Emerald screen.

### Rationale

The shrine reveal needs the results screen to host a live HPZ level render, which is a
separate piece of work from the message-selection fix; suppressing the wrong message does
not depend on it. Tightening the indicator gate to `state == 1` in the meantime would leave
the Super Emerald results screen with no emeralds shown at all — worse than the current
approximation, because the big shrine emeralds that replace them are not drawn yet. Both
halves land together when the shrine backdrop does.

### Verification

`TestS3kSpecialStageResultsReveal` pins the suppressed reveal, the Super_emerald_count
sourcing (`sub_2ECA8`), the SUPER EMERALD word (`loc_2EB88`), and the S3-side versus
S&K-side reveal selection at `loc_2E540`.

---

## Air Countdown Digits: Rebuilt Mapping Frames Instead of VRAM DMA

**Location:** `Sonic3kObjectArtProvider.loadAirCountdownDigitArt()`, `S3kAirCountdownObjectInstance.java`
**ROM Reference:** `sonic3k.asm:33320-33327` (`AirCountdown_Init`), `sonic3k.asm:33489-33516` (`AirCountdown_Load_Art`)

### Original Implementation

`Obj_AirCountdown` points at a single mapping table for its whole life:

```asm
        move.l  #Map_Bubbler,mappings(a0)
        tst.b   parent+1(a0)
        beq.s   loc_1819E
        move.l  #Map_Bubbler2,mappings(a0)
loc_1819E:
        move.w  #make_art_tile(ArtTile_Bubbles,0,0),art_tile(a0)
```

Mapping frames `$00`-`$08` are bubbles drawn from `ArtNem_Bubbles`. Frames
`$09`-`$12` all resolve to the *same* one-piece frame (`word_2FD7A`) whose 2x3
piece sits at tile `$384` past `ArtTile_Bubbles` — i.e. `ArtTile_DashDust`.
The frame therefore selects nothing on its own; `AirCountdown_Load_Art` DMAs six
tiles from `ArtUnc_AirCountdown + (frame - 9) * 6` into that fixed VRAM window
every time the mapping frame changes. P2 uses `Map_Bubbler2` and
`ArtTile_DashDust_P2` so the two players don't overwrite each other's digit.

### Our Implementation

We have no VRAM window to overwrite, so the provider builds a separate
`AIR_COUNTDOWN_DIGITS` sheet: the ten ROM mapping frames are re-emitted with the
ROM's own geometry (offsets, size, flip, palette, priority) and each frame's tile
index moved onto its own six-tile slice of `ArtUnc_AirCountdown`. The object
picks that sheet for frames `>= $09` and the shared `BUBBLER` sheet below it.

### Why This Is Acceptable

The mapping geometry and every art byte still come from the ROM through the
normal ROM-loading pipeline; only the indirection changes — a per-frame tile
base replaces a per-frame DMA into a shared window. The rendered result is
identical, and because both players read from the same immutable source art
rather than a mutable VRAM window, the P1/P2 `Map_Bubbler`/`Map_Bubbler2` split
collapses to a single shared sheet with no visible difference. Emulating the DMA
would mean modelling `ArtTile_DashDust` as mutable VRAM shared with the dash
dust, which buys nothing the tile rebase doesn't already give us.

## AIZ2 mid-level enemy-art admission (RESOLVED 2026-08-10)

**Status:** RESOLVED. This entry is retained because its original text stated a ROM claim
that was WRONG, and the correction matters more than the entry did.

### What this entry originally claimed, and why it was wrong

It said the recorded ROM had the direct decompression queue already working on a module
inside `ArtKosM_AIZ_Bloominator` (0x367DCA) on the admission frame, implying an older
parent owned that iteration's module step and that the engine failed to model parent
ownership across a mid-level admission.

**None of that is in the fixtures.** Decoding the recorded `load_queue_state` rows
directly gives `active_source = 0x36800E` on both admission frames (5542 in
`aiz1_to_hcz_fullrun`, 6345 in `aiz_completerun`). That is `ArtKosM_AIZ_MonkeyDude + 2`
— module 0 of the FIRST `PLCKosM_AIZ` entry, in plain PLC order
(`skdisasm/sonic3k.asm:64349-64351`). The module FIFO was **empty** when `LoadEnemyArt`
ran; there was no older parent. Bloominator's module 0 is 0x367DCC and it is published
four rows LATER (5546 / 6349), after MonkeyDude retires. The value 0x367DCE appears
nowhere in either fixture.

### The actual defect, now fixed

The module queue's held-loop-tail deferral was suppressing the **submission** of a head
archive's first module, not merely its readiness. Instrumented at the admission row: the
AIZ2 `LoadEnemyArt` batch is submitted correctly during the event pass (3 parents,
capacity available), then the same row's POST_OBJECTS step ran with the held-tail flag
set, took the `activeChild == null && deferFirstChild` early return, and published
nothing. The next POST_OBJECTS — the held-tail closure — submitted 0x36800E. So the child
arrived one loop late and the row's snapshot showed an idle direct FIFO.

That deferral has no ROM basis for a level-loop producer. The two producers that set the
flag are now split: the generic held-loop-tail arm defers only readiness and visibility,
as its own documentation always said it should, while the locked title-card owner — whose
`LoadEnemyArt` genuinely runs after that iteration's module step — declares its late
ordering explicitly. The fix removes a suppression rather than adding a compensator, so
it holds for any recording.

### Residual, still open

`AIZ2_WAIT_FIRE_REDRAW_FRAMES = 38` and `AIZ2_FIRE_REDRAW_FRAMES = 8` remain documented
in-code as coming from a fixture regen, i.e. fixture-measured rather than ROM-derived.
That is a hard rule 3 exposure inherited with the branch and is what makes the admission
frame fixture-relative in the first place.

## `s3k_kos_direct.prepared`: a sub-frame ROM bit compared against a boundary-granular model

**Status:** OPEN, bounded. One asymmetric comparison-side excusal, added 2026-08-10 in
`LoadQueueComparisonProjection`. It fires exactly once across the current fixture set
(AIZ complete run, frame 6349, direct job ordinal 36) and nowhere in CNZ or MHZ.

### The compared field is not "prepared"

The recorder projects this field from bit 15 of `Kos_decomp_queue_count`
(`tools/tracechaser/bizhawk-headless/src/Recording/LoadQueueStateProjector.cs`:
`bool prepared = (rawCount & 0x8000) != 0;`). In the ROM that bit means
**decompression is in progress right now**, not "prepared":

- `Process_Kos_Queue_Main` (`docs/skdisasm/sonic3k.asm:2845-2846`) sets it with
  `ori.w #$8000,(Kos_decomp_queue_count).w` — commented in the disassembly as
  "set sign bit to signify decompression in progress" — immediately before entering
  `Process_Kos_Queue_Loop`.
- `Process_Kos_Queue_EndReached` (`docs/skdisasm/sonic3k.asm:2938-2941`) clears it with
  `andi.w #$7FFF,(Kos_decomp_queue_count).w` when the stream ends.
- `Set_Kos_Bookmark` (`docs/skdisasm/sonic3k.asm:2819`) only **reads** the bit, to decide
  whether to redirect a preempting V-int's `rte` to `Backup_Kos_Registers`. It is not a
  work quantum and it neither sets nor clears the bit.

A recorded row therefore reads the bit set only when that frame's V-int happened to land
**inside** `Process_Kos_Queue_Loop` — a sub-frame 68000 cycle position. Frame-granularity
state cannot reconstruct it, in this engine or in principle.

### The engine's model, and why it can only err in one direction

`S3kKosDecompressionQueue.afterTimingService` arms the serviced FIFO head at each
`PRE_MAIN_LOOP` boundary and disarms it only when the recorded completion edge fires. The
engine's flag therefore means "the head survived a service boundary before its recorded
completion", which is a **boundary-granular over-approximation** of the ROM bit: it can
read true one or more boundaries before the ROM's V-int first landed in the loop, but it
cannot read false while the ROM is mid-loop. (The flag is comparison-only: `preparedEntries`
is read solely by `QueueDiagnosticSnapshot` capture and by rewind `capture()`/`restore()`;
no gameplay, art, or scheduling path consumes it.)

The excusal mirrors that asymmetry exactly. Only `actual=true / expected=false` is excused,
and only while the head's own recorded completion edge (matched by kind, ordinal and
submission fingerprint) still lies strictly in the future. `actual=false / expected=true`
stays a hard error forever — the engine claiming decompression was not in progress while
the ROM was mid-loop means a completion landed early, which is a real timing defect.
`busy`, `active_source`, `active_destination`, the waiting fingerprints, and the module
queue's `prepared` are all still fully compared.

The asymmetry is structural, not merely gated. The excusal's only effect is to raise the
*expected* row's `prepared` to true, which by construction cannot conceal an actual value
of false. Widening the polarity gate alone is therefore a semantic no-op — verified by
mutation: forcing the branch to accept both polarities left all of
`TestLoadQueueTraceComparison` green. The mutation that *would* be dangerous is changing
the rewrite to mirror the actual value instead of forcing true, and
`keepsReverseInProgressPolarityAsError` fails on exactly that.
`keepsInProgressBitComparedWithoutFutureRecordedCompletion` likewise fails when the
`rawFrame > frame` guard is removed. Both were confirmed by running the mutations.

### Refuted alternatives — do not rerun these

Measured against the 1-error baseline at `b5975c195` (frame 6349,
`queue.s3k_kos_direct.prepared` expected=false actual=true):

1. **Reordering the module and direct service passes** — breaks direct job ordinal 38 with
   a hard `IllegalStateException`, not a comparison error.
2. **Never setting the engine bit** — 37 errors, first at frame 64. 36 rows genuinely
   require it TRUE.
3. **A provenance rule** (distinguishing the false row by where the entry came from) — 29
   errors, first at frame 64. The TRUE rows and the FALSE row share routine, size class
   (`$800` words) and provenance, so no provenance predicate separates them.

### Rejected: a recorded decompression-start edge

Extending the v5 `hardware_timing.jsonl` stream with a decompression-**start** edge would
reproduce the bit exactly, and was rejected as a hard-rule-4 violation. A start edge
releases no engine work: nothing waits on it, no prepared ROM-backed job becomes ready
because of it. Its only observable effect would be to set the value of a compared field,
which makes the comparison a checksum of the sidecar against itself. Rule 4's test is
whether a recorded input only changes *when* real engine work becomes ready; a start edge
decides *what* a compared row says, so it is outside the exception however well the ROM
behaviour is cited.

## MGZ swinging platform endpoint: the inherited `d1` high word is not modelled

`MGZSwingingPlatformObjectInstance.hasLaterSlotRiderCosineResidue` is a fitted
angle/slot table and is knowingly incorrect. It is recorded here rather than removed
because removing it trades one measured red for another.

**Mechanism (ROM-derived).** `GetSineCosine` returns the cosine with
`move.w SineTable(pc,d0.w),d1` (`sonic3k.asm:3025`), a word write, so the high word of
`d1` on entry to `sub_34074` is inherited from whatever ran before. `sub_34074` then does
`swap d1 / asr.l #4` (`sonic3k.asm:70487-70490`), which pushes that inherited word `H`
into the low twelve bits of the per-link X step, and accumulates five steps. With `C` the
cosine word:

```
platformX = pivotX + ((20480*C + 5*(H >> 4)) >> 16)
```

The engine computes the `H == 0` case. The extra term is at most `5*$FFF = $4FFB`, so it
can carry at most one pixel, and only when both of

```
k = (5 * (C & $F)) & $F >= 11        (H >> 4) >= ($10000 - k*$1000) / 5
```

hold. The first condition is fully ROM-derived; measured against `Levels/Misc/sine.bin`,
all thirteen angles in the fitted table satisfy it with `k` in 12..15, which is
independent confirmation that the table is approximating this mechanism and not something
else. The second condition needs `H`, and the engine has no model of inter-object register
carry through `Process_Sprites`, so the table stands in for it.

The sine (Y) side needs no term: `Process_Sprites`' `sub_1AAFC` does `move.l (a0),d0`
before `jsr (a1)` (`sonic3k.asm:35983-35988`), so `d0`'s high word is the high word of
`loc_3403A`'s own address, `$0003`, and `asr.l #4` of `$0003` is zero.

**Measured consequence.** At `e9d5eb610`:

| change | `TestS3kSonicTailsMgzSegmentTraceReplay` | `TestS3kMgzTraceReplay` |
|---|---|---|
| table kept (current) | 3950 errors, first frame 10709 (`x` $23DB/$23DC) | green |
| table removed | 3921 errors, first frame 12932 | 14 errors, first frame 25770 (`x` $2A78/$2A77) |

At MGZ segment frame 10709 the player is riding slot 6 at byte angle `$62`; the table adds
the carry and the ROM does not, so the platform sits at `$23D5` instead of `$23D4` and the
rider is carried one pixel too far right. At `TestS3kMgzTraceReplay` frame 25770 the ridden
platform is slot 7 of a different pivot group and the ROM *does* take the carry.

**Removal condition.** Model `d1`'s inherited high word across the object execution order
(the value left by the previously executed SST slot), then compute the carry from the
formula above and delete both the table and this entry. Do not extend the table with more
angles: it cannot be right, because the carry is not a function of the angle alone.

## Segment trace replays start with an empty save-game inventory

**What diverges.** Any per-zone *segment* fixture cut from a complete run replays
with zero Chaos Emeralds, zero Super Emeralds and an empty
`Collected_special_ring_array`, regardless of what the recording's player had
at that point in the movie. Object branches that read those globals therefore
take the low-inventory arm in the engine and the high-inventory arm in the ROM.

**Measured case.** `TestS3kSonicTailsMgzSegmentTraceReplay`, frame 17383 --
3410 of the class's 3446 errors start there, and frames 13864-15531 and
15562-17382 are clean. `Obj_SSEntryRing`'s collision arm `loc_6170A`
(`docs/skdisasm/sonic3k.asm:128283-128291`) branches on
`cmpi.b #7,(Chaos_emerald_count).w`. MGZ is `Current_zone` 2, so
`SSEntry_CheckLevel` (`:128433-128443`) reports an S3 level and the ROM takes
`loc_61794` (`:128318-128327`): `moveq #50,d0 / jmp (AddRings).l`, ring
self-retires, player keeps rolling. The trace shows `rings` `0x28 -> 0x5A` on
that single frame with `anim` still `$02`.

The engine takes `loc_6173A` (`:128290-128295`) instead, which writes
`mapping_frame = 0`, `anim = $1C`, `object_control = $53` -- and those are
exactly the engine's compared values at 17383. The object is **not** at fault:
`Sonic3kSSEntryRingObjectInstance.awardsFiftyRingsInsteadOfCapture` already
models the full ROM branch and `isSonic3HalfLevel()` already models
`SSEntry_CheckLevel`. A probe at the touch reported
`zone=2 hasAllEmeralds=false hasAllSuper=false isS3Half=true award=false`;
only the inventory term is wrong.

**Why it is not closed.** The fixture's own `metadata.json`, `physics.csv` and
frame `-1` bootstrap aux events carry no inventory field, and
`AbstractTraceReplayTest` does not read the parent run manifest. The value does
exist in committed data -- `runs/s3k-sonic-tails-complete-emeralds/run_manifest.json`
records `emeralds_after: 7` into segment 15 and `emeralds_before: 7` from
segment 18, and MGZ is `segment_index: 16` -- but seeding an inventory counter
from it is hydration of gameplay state into the engine, which hard rule 4
permits only for the hardware-timing port. The pre-trace bootstrap carve-out
covers position, RNG seed, oscillator pre-advance and frame counters, not
save-game progress.

**Removal condition.** Take an explicit decision on whether starting save-game
inventory belongs in the frame-0 bootstrap contract. If it does, extend the
segment bootstrap to derive it from the parent run manifest's recorded
progression -- for every game, since S1 and S2 segment fixtures have the same
shape -- and measure the blast radius across every segment class before
landing. Do **not** close it by keying on the run id, the fixture name, the
zone or a frame index. Delete this entry when the bootstrap carries the value.

## SEGA Screen: an engine addition the ROM does not have

**What the ROM does.** Nothing. There is no SEGA screen sequence in either half of the
locked-on cartridge.

- `Sega_Screen` — game mode 0's handler — is
  `move.b #4,(Game_mode).w` and falls straight into `Title_Screen`
  (`docs/skdisasm/sonic3k.asm:5387-5388`); the S3 half is the same with an explicit `rts`
  (`docs/skdisasm/s3.asm:4768-4770`).
- `JumpToSegaScreen`, the handler for game modes `$10` and `$18`, only sets game mode 0
  (`sonic3k.asm:454-456`), which then advances the same way.
- A case-insensitive search for "sega" across both disassemblies returns **only** the
  cartridge header strings, the TMSS security write, those advancing routines, and one
  `SegaScr_VInt` reference in `s3.asm:830` that is vestigial — game mode 0 advances on its
  first main-loop pass, so that V-int handler can run at most once.

There is no hold, no timer, and no SEGA sound command anywhere in either file.

**What we do.** `Sonic3kTitleScreenManager` presents a SEGA screen: it plays the SEGA sound,
holds for `SEGA_HOLD_DURATION = 180` frames, stops the sound and moves to the palette
transition.

The chant itself is no longer a presentation addition: since 2026-09-03 the sound it plays is the
ROM's own `zPlaySEGAPCM` transport (`Sound/Z80 Sound Driver.asm:4372-4424`), run by the driver
through the emulated YM2612 DAC at the ROM's own loop cadence. Only the screen and its hold
remain engine additions.

**Why this is acceptable.** It is a presentation addition, not a parity gap — there is no ROM
behaviour being approximated, so there is nothing for the 180 to be wrong against. It affects
only the pre-title sequence and no gameplay state, and it is skippable by input like the rest of
the intro.

**Why it is written down.** Found during the 2026-08-21 timing-constant provenance sweep, where
`SEGA_HOLD_DURATION` was the only constant of six checked with no ROM counterpart — the other
five were ROM-exact. Without this entry the next sweep re-flags it as an uncited constant and
someone goes looking for the ROM value that does not exist. **Whether to keep the screen at all
is a product decision, not a parity one.**

Full context:
[docs/architecture/audits/2026-08-21-timing-constant-provenance-sweep.md](architecture/audits/2026-08-21-timing-constant-provenance-sweep.md).

---

## PSG admission stale-IX silence remains incomplete

**What the ROM does.** The S3K sound driver is built with `fix_sndbugs = 0`
(`Sound/Z80 Sound Driver.asm:16`), so `cfSetPSGNoise` takes the `else` branch at
`:3559-3572`. That branch puts exactly two bytes on the PSG bus, back to back and
with nothing between them: `0DFh` to silence PSG3, then the effect's own noise
operand. The routine has no calls between the two writes, so their adjacency is
structural rather than incidental.

**Resolved ordering gap (2026-09-05).** The engine previously emitted
`DF FF E7`: noise-channel ownership injected a second silence after the loader
had already emitted the guaranteed admission `FF`. The S3K profile now selects
`REGISTER_SEQUENCE`, so admission retains its `FF` in header order and the
command emits adjacent `DF E7`. Ownership, release and rollback are unchanged.

**Scope of the resolved gap.** Every S3K effect that carries a PSG form was affected, because all 36 of
them declare `$E7`. Among them are three behind reported AIZ1 audio faults:
`sfx_Splash` (`$39`), `sfx_InstaAttack` (`$42`) and `sfx_Collapse` (`$59`).

**Remaining admission gap.** `zGetSFXChannelPointers.is_psg` calls
`zSilencePSGChannel` before replacing IX (:2131-2154). That stale pointer can
contribute additional PSG writes before the guaranteed `FF`; the engine still
models only the guaranteed write. Collapse's preceding FM5 header contributes
none, so this comparison does not establish general stale-IX parity.

**Coverage and next frontier.** `TestS3kNoiseFormEffectWriteStream` asserts that
`DF` immediately precedes the ROM-derived noise operand for all three effects.
The synthetic zero-operand test also checks adjacent `DF FF` without a duplicate.
Observation starts after admission
so a setup silence cannot be mistaken for the track's first pass.
The independent committed AIZ1 intro oracle now advances within service
**1570** from event **43** to **48**. The event-43 duplicate was an engine
sequencer defect: note-start modulation sent Collapse's frequency pair and the
single volume tail reached by the ROM's `zUpdatePSGTrack` fall-through
(`Sound/Z80 Sound Driver.asm:4059-4135`), then the generic attacked-note
epilogue sent the same `F0` again. The engine now suppresses that epilogue only
when the configured S3K frequency tail already performed the write. The new
frontier is reference PSG `FF` against an exhausted engine service stream.
That `FF` is the second byte of the ordered `AF FF` source-driver frequency
transaction and, because bit 7 is set, is also physically decoded as a PSG3
volume latch. The engine formerly made a new ownership decision for that
second bus byte and rejected it. The ROM checks track override once before
writing the pair and performs no ownership decision between its bytes. The
driver now admits the source transaction once and forwards both bytes verbatim,
preserving the chip's final latch and noise attenuation. The next FM3 release
gap is also resolved: the covered music track's retained normal/special mode
is written to register `27` before its voice, matching `cfStopTrack`
(:3443-3518). The music PSG3 envelope gap is also resolved: an attacked note
still resets `VolEnv`, but while overridden it no longer consumes the first
envelope byte before `zUpdatePSGTrack`'s override return (:4058-4115). The
S3K's `cfChangePSGVolume` envelope rewind is also now byte-accurate: its `DEC`
wraps `VolEnv` from `00` to `FF`; the prior Java guard incorrectly left zero
unchanged (`Sound/Z80 Sound Driver.asm:3263-3285`). The oracle advances from
service **1594** to **1652**. That ordered-write gap is also resolved: retail
`zUpdateSFXTracks` walks fixed FM3..FM6 then PSG1..PSG3 RAM slots, independent
of sound admission/header order (`Sound/Z80 Sound Driver.asm:727-759`). The
S3K PSG track stop also preserves the shipped pointer helper's unconditional
`FF` after its channel/noise silence and before ownership restoration
(`:2115-2142, :3443-3469, :4226-4249`). The covered music track's signed raw
noise byte is now restored without changing its playing/rest state
(`:3521-3533`). The oracle advances to service **2012** event 1: reference PSG
`BF` versus engine PSG `FF`.
`TestS3kOracleRequestSidecarWiring` pins that mismatch and separately asserts a
matching 1690-service prefix through ordinal 1689. Service 1690 and the full
window are not claimed to match. The immediate `FF` RAM state is proven; a
later envelope walk from `FF` remains unverified because retail `zDoVolEnv`
uses it as an unsigned `envelope + 255` table offset while Java bounds the
cursor to the loaded envelope array. The DAC stream
remains independently red at run 338, byte 0 (`88` versus `7F`). See the
[2026-09-05 validation report](architecture/validation/audio/2026-09-05-s3k-psg-takeover.md)
for commands and red-first evidence; this is not full-window audio parity.

The later SFX-header correction moved the frontier to service 2357. That
state mismatch was diagnostic input selection: retail raw ring request `33h`
toggles boot-zeroed `zRingSpeaker` at dispatch and selects Sound34/Sound33
(`Sound/Z80 Sound Driver.asm:547,1919-1928`), while the capture loaded Sound33
directly. The capture now applies the alternating transform at its pending
driver callback and advances to service **2409**, `MUS_PSG1.overridden`
(`true` versus `false`). This does not close live request parity: S3K still has
no production three-slot mailbox consumer, AudioManager selects rings before
retail's consume point and resets its fallback side on stop paths. The
capture's 1-up/fade mailbox-clearing behavior remains unverified against
`zUpdateMusic` (:658-701).
