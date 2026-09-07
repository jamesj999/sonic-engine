# Known Discrepancies from Original ROMs

This document tracks **intentional deviations** from the original Sonic 1 / 2 / 3&K ROMs that apply engine-wide (cross-game) or to Sonic 1 / Sonic 2 specifically. Entries here are architectural choices we've made (cleaner code, added features, deliberate corrections of known ROM bugs) that we accept and do not plan to revert. Runtime gameplay behavior is preserved unless a rationale explicitly justifies a visible change (e.g., the multi-sidekick entry adds gameplay that the ROM never supported).

**What does NOT belong here:**
- Bugs, incomplete implementations, and parity gaps that we *intend to fix* → [known-bugs.md](known-bugs.md)
- Sonic 3 & Knuckles-specific intentional discrepancies →
  [S3K known discrepancies](../S3K_KNOWN_DISCREPANCIES.md)
- Sonic 3 & Knuckles-specific bugs → [s3k-known-bugs.md](s3k-known-bugs.md)

Each entry describes what the ROM does, what we do, and why — focusing on *why* the divergence is acceptable.

**TraceChaser extraction note (2026-08-29):** moving recorder and analysis
utilities to the optional pinned `tools/tracechaser` submodule changes source
ownership only. It introduces no cross-game or ROM-behaviour discrepancy, so
no discrepancy entry was added or reclassified by the cutover.

## Table of Contents

1. [Gloop Sound Toggle](#gloop-sound-toggle)
2. [Spindash Release Transpose Fix](#spindash-release-transpose-fix)
3. [Pattern ID Ranges](#pattern-id-ranges-for-guiresults-screen)
4. [HTZ Cloud Scroll Precision Fix](#htz-cloud-scroll-precision-fix)
5. [MCZ Rotating Platforms Child Cleanup](#mcz-rotating-platforms-child-cleanup)
6. [Multi-Sidekick Daisy Chain](#multi-sidekick-daisy-chain)
7. [Sonic 1 Monitor Sidekick Guard](#sonic-1-monitor-sidekick-guard)
8. [Bonus Stage Game Mode](#bonus-stage-game-mode)
9. [HCZ Conveyor Belt Rolling State Clear (Resolved)](#hcz-conveyor-belt-rolling-state-clear-resolved)
10. [Right-Wall Penetration Timer Heuristic](#right-wall-penetration-timer-heuristic)
11. [S2 CPZ Visual Water Surface Oscillation](#s2-cpz-visual-water-surface-oscillation)
12. [S2 Music Offsets Resolved from Hardcoded REV01 Table](#s2-music-offsets-resolved-from-hardcoded-rev01-table)
13. [Right-Boundary Is Viewport-Independent (Level Edge)](#right-boundary-is-viewport-independent-level-edge)
14. [Object Despawn and Visibility Windows](#object-despawn-and-visibility-windows)
15. [Legacy Pre-Level Intro Prefix Trace Bootstrap Contract](#legacy-pre-level-intro-prefix-trace-bootstrap-contract)
16. [S2 Tornado Ride-Start Trace Bootstrap Contract](#s2-tornado-ride-start-trace-bootstrap-contract)
17. [S2 CNZ Slot-Machine Trace Bootstrap Contract](#s2-cnz-slot-machine-trace-bootstrap-contract)
18. [Whole-Run Level-Restart Admission Row](#whole-run-level-restart-admission-row)
19. [S3K Production Lifecycle and Structural Trace Replay Scheduling](#s3k-production-lifecycle-and-structural-trace-replay-scheduling)
20. [Hardware-Timing Replay Input Exception](#hardware-timing-replay-input-exception)
21. [S3K Complete-Run Segment Start-Position Bootstrap Debt](#s3k-complete-run-segment-start-position-bootstrap-debt)
22. [Frame-0 Trace Bootstrap Snapshot Coverage Debt](#frame-0-trace-bootstrap-snapshot-coverage-debt)
23. [Sonic 1 Embedded Runtime Data Ratchet](#sonic-1-embedded-runtime-data-ratchet)
24. [Special-stage Live Rewind Scope](#special-stage-live-rewind-scope)
25. [S2 CPZ Debug Placement Capability Boundary](#s2-cpz-debug-placement-capability-boundary)
26. [S2 Native Human-P2 Monitor Branch Unavailable](#s2-native-human-p2-monitor-branch-unavailable)
27. [S2 Whole-Run V-int Clock Cannot Be Made Exact](#s2-whole-run-v-int-clock-cannot-be-made-exact)
28. [S2 Push-Release Animation Restart Fires Once Extra At A Contact-End Frame](#s2-push-release-animation-restart-fires-once-extra-at-a-contact-end-frame)
29. [S2 CPZ Boss Truncates The Object Pass (`fixBugs = 0`)](#s2-cpz-boss-truncates-the-object-pass-fixbugs--0)
30. [S2 Interactive Special-Stage Hardware Lag Is Approximated](#s2-interactive-special-stage-hardware-lag-is-approximated)
31. [YM2612 Output Scale, Resting Level and DAC Presentation](#ym2612-output-scale-resting-level-and-dac-presentation)
32. [PSG Tone-2-Linked Noise at Period 0/1 Follows the Reference Cores](#psg-tone-2-linked-noise-at-period-01-follows-the-reference-cores)
33. [FM:PSG Mix Balance Is Pre-Rewrite Parity, Not a Hardware Calibration](#fmpsg-mix-balance-is-pre-rewrite-parity-not-a-hardware-calibration)
34. [S2 Compressed-Music Load Timing Omits Sub-Frame Bus Contention](#s2-compressed-music-load-timing-omits-sub-frame-bus-contention)
35. [S2 stopMusic Bypasses the Mailbox While a Compressed Load Blocks It](#s2-stopmusic-bypasses-the-mailbox-while-a-compressed-load-blocks-it)
36. [Fast FM Register-Level Timing and Output](#fast-fm-register-level-timing-and-output)

---

## Special-stage Live Rewind Scope

Held live rewind is supported only inside rewind-capable special-stage providers. It does not rewind across the LEVEL -> SPECIAL_STAGE or SPECIAL_STAGE -> results/LEVEL boundaries; those transitions intentionally start fresh rewind timelines.

---

## S2 Interactive Special-Stage Hardware Lag Is Approximated

**Location:** `Sonic2SpecialStageProvider.java`, `GameLoop.java`
**ROM Reference:** `docs/s2disasm/s2.asm` `V_Int`, `Vint_Lag`, `Vint_S2SS`, and `SS_MainLoop`

The shipped game takes `Vint_Lag` when the 68K main loop has not re-armed
`Vint_routine` before the next VBlank. In a Sonic 2 special stage, that result
depends on the variable cost of the complete `SS_MainLoop`, the substantial
`Vint_S2SS` service path, DMA/DPLC work, and hardware contention. OpenGGF does
not yet model those costs at cycle granularity. Ordinary interactive play
therefore uses the existing stateless segment/speed approximation to retain the
special stage's expected slowdown and speed simulation. Its ratios were fitted
from one Stage 1 recording, not derived from the ROM routine, so it is an interim
playability model rather than accuracy evidence for arbitrary movies.

Trace replay does not use that approximation. Externally paced sessions disable
it, and a recorded `lag_state.lagged` outcome admits the already-existing
`VBlank_Lag` loop through the separate scheduling-only contract. That outcome
supplies no gameplay value or work.

---

## Gloop Sound Toggle

**Location:** `BlueBallsObjectInstance.java`
**ROM Reference:** `s2.sounddriver.asm` lines 2142-2149

### Original Implementation

The ROM implements the Gloop sound toggle in the Z80 sound driver itself:

```asm
zPlaySound_CheckGloop:
    cp    SndID_Gloop           ; Is this the gloop sound?
    jr    nz,zPlaySound_CheckSpindash
    ld    a,(zGloopFlag)
    cpl                         ; Toggle the flag
    ld    (zGloopFlag),a
    or    a
    ret   z                     ; Return WITHOUT playing if flag is 0
    jp    zPlaySound            ; Only play every other call
```

This hardcodes a specific sound ID check into the driver, causing the Gloop sound to only play every other time it's requested.

### Our Implementation

We implement the toggle in `BlueBallsObjectInstance.playGloopSound()` instead:

```java
private static boolean gloopToggle = false;

private void playGloopSound() {
    if (!isOnScreen()) {
        return;
    }
    // Toggle flag - only play every other call (ROM: zGloopFlag)
    gloopToggle = !gloopToggle;
    if (!gloopToggle) {
        return;
    }
    AudioManager.getInstance().playSfx(SND_ID_GLOOP);
}
```

### Rationale

1. **Gloop is exclusively used by BlueBalls** - A search of the disassembly confirms `SndID_Gloop` (0xDA) is only referenced in `Obj1D` (BlueBalls). No other object uses this sound.

2. **Keeps SMPS driver generic** - Hardcoding sound-specific behavior in the driver would make it less reusable and harder to maintain. The driver should ideally just play what it's told.

3. **Encapsulates behavior** - The toggle is really a BlueBalls-specific feature to prevent sound spam when multiple balls are active. Keeping it in the object makes the relationship explicit.

4. **Identical runtime behavior** - The end result is the same: Gloop plays every other call, preventing audio spam from staggered sibling balls.

### Verification

Both implementations result in the Gloop sound playing at 50% frequency, which prevents overwhelming audio when multiple BlueBalls objects are bouncing with staggered timers.

---

## Spindash Release Transpose Fix

**Location:** `Sonic2SfxData.java`
**ROM Reference:** `docs/s2disasm/sound/sfx/BC - Spin Dash Release.asm`

### Original Implementation

The ROM SFX header for Spindash Release (0xBC) uses an invalid transpose value for FM5:

```asm
    smpsHeaderSFXChannel cFM5, Sound3C_SpindashRelease_FM5, $90, $00
```

This value is called out in the disasm as a bug. Some SMPS drivers interpret `$90` as a large negative transpose, which can underflow the note calculation and skip the initial FM burst.

### Our Implementation

We patch only this invalid FM transpose value when parsing SFX headers:

```java
int transpose = (byte) data[pos + 4];
if ((channelId & 0x80) == 0 && transpose == (byte) 0x90) {
    transpose = 0x10;
}
```

### Rationale

1. **Targets a known bad data value** - The disasm explicitly documents the `$90` transpose as invalid for this SFX.
2. **Preserves other SFX behavior** - We do not mask or normalize all transposes, only this exact FM case.
3. **Improves fidelity** - Restores the missing initial FM burst for 0xBC that is audible in hardware/driver-correct playback.

### Verification

Spindash Release now includes the initial FM5 hit before the delayed PSG noise, matching expected playback.

---

## Pattern ID Ranges

**Location:** `LevelManager.java`, `ObjectRenderManager.java`, `PatternAtlas.java`
**ROM Reference:** VDP VRAM tile management

### Original Implementation

The Mega Drive VDP has limited VRAM (~64KB), so the original game dynamically loads and overwrites pattern data. When displaying the results screen after completing an act, the game overwrites level tile patterns that are no longer needed with results screen graphics (score tallies, continue icons, etc.). Pattern indices directly correspond to VRAM tile addresses (0x0000-0x07FF typical range).

From `s2.asm`, results screen art is loaded into VRAM locations previously used by level tiles:
```asm
; Load results screen patterns, overwriting level data
lea     (ArtNem_TitleCard).l,a0
lea     (vdp_control_port).l,a4
move.w  #tiles_to_bytes(ArtTile_Title_Card),d0
```

### Our Implementation

We use **extended pattern ID ranges** with fixed bases that don't overlap:

| Range | `PatternAtlasRange` | Notes |
|------|----------|-------|
| `0x00000`–`0x01FFF` | `LEVEL_TILES` | Corresponds to VRAM tile indices (0-~2047); also the S2 special-stage playfield |
| `0x03000`–`0x0FFFF` | `SPECIAL_STAGE_PLAYFIELD` | S3K special-stage track, objects and HUD (`Sonic3kSpecialStageManager`) |
| `0x10000`–`0x17FFF` | `SONIC1_SPECIAL_STAGE` | S1 special-stage art (`Sonic1SpecialStageManager`) |
| `0x20000`–`0x27FFF` | `OBJECTS` | Monitors, springs, badniks, zone-specific objects (`LevelManager`, the S2/S3K object art providers, MGZ boss and LBZ1 Knuckles cutscene art) |
| `0x28000`–`0x2FFFF` | `HUD` | Score, time, rings display (fixed base) |
| `0x30000`–`0x37FFF` | `WATER_SURFACE` | Underwater palette transition patterns; `Sonic3kDustArt` suballocates spindash/skid dust at `base() + 0x4000` (`0x34000`) |
| `0x38000`–`0x3FFFF` | `SIDEKICK_BANKS` | Extra DPLC banks for duplicate-character sidekicks and Tails tail sprites (global running offset from `LevelManager`) |
| `0x40000`–`0x47FFF` | `TITLE_CARDS` | S2 title card, S1/S2 special-stage results, S3K AIZ intro art; shared base in mutually-exclusive contexts (see below) |
| `0x48000`–`0x4FFFF` | `TRANSIENT_EFFECTS` | Short-lived S3K effect art: ICZ snowboard (`IczSnowboardArtLoader`), lightning spark, `Sonic3kObjectArtProvider` transient sheets |
| `0x50000`–`0x57FFF` | `MENU_AND_DATA_SELECT` | S1/S3K title cards, S1/S2/S3K level select, S3K data select; shared base in mutually-exclusive contexts (see below) |
| `0x60000`–`0x67FFF` | `RESULTS_SCREENS` | S1 Try Again Eggman (`TryAgainEndManager`, emerald art at `base() + 0x1000`), S2 title-screen background, S3K level results |
| `0x70000`–`0x77FFF` | `SPECIAL_STAGE_RESULTS` | S3K special-stage results and S2 title sprites; shared base in mutually-exclusive contexts |
| `0x80000`–`0x87FFF` | `SONIC2_TITLE_CREDIT_TEXT` | S2 title-screen credit text |
| `0x90000`–`0x97FFF` | `SONIC1_TITLE_FOREGROUND` | S1 title-screen foreground (`Sonic1TitleScreenDataLoader`) |
| `0xA0000`–`0xA7FFF` | `SONIC1_TITLE_SPRITES` | S1 title-screen sprites |
| `0xB0000`–`0xB7FFF` | `SONIC1_CREDIT_TEXT` | S1 credit text |
| `0xC0000`–`0xC7FFF` | `SONIC1_TITLE_TM` | S1 title TM mark |
| `0xD0000`–`0xD7FFF` | `SONIC1_TITLE_GHZ_BACKGROUND` | S1 title GHZ background |
| `0xE0000`–`0xE7FFF` | `S3K_TITLE_SCREEN_ANIMATION` | S3K title animation and S2 credits text; shared base in mutually-exclusive contexts |
| `0xE8000`–`0xEFFFF` | `S3K_TITLE_SCREEN_SPRITES` | S3K title sprites |
| `0xF0000`–`0xF0FFF` | `SONIC2_ENDING_CHARACTER` | S2 ending character art (`Sonic2EndingArt`) |
| `0xF1000`–`0xF1FFF` | `SONIC2_ENDING_FINAL_TORNADO` | S2 ending final Tornado |
| `0xF2000`–`0xF2FFF` | `SONIC2_ENDING_PICS` | S2 ending photo frame |
| `0xF3000`–`0xF3FFF` | `SONIC2_ENDING_MINI_TORNADO` | S2 ending mini Tornado |
| `0xF4000`–`0xF4FFF` | `SONIC2_ENDING_CLOUDS` | S2 ending clouds |
| `0xF5000`–`0xF5FFF` | `SONIC2_ENDING_ANIMAL` | S2 ending animal |
| `0xF6000`–`0xF7FFF` | `SONIC2_CREDITS_LOGO` | S2 credits logo (`Sonic2LogoFlashManager`) |
| `0xF8000`–`0xFFFFF` | `SONIC2_ENDING_VRAM` | S2 ending VRAM-relative art |
| `0x100000`–`0x107FFF` | `SEGA_BOOT_LOGOS` | S1/S2 SEGA logo tiles and S2 giant-Sonic boot-screen art |
| `0x108000`–`0x187FFF` | `MGZ_ZOOM_CUES` | 4096 non-overlapping 128-pattern banks for live/rewound MGZ end-boss scaled-art cues. Allocation is monotonic for process lifetime and fails loudly if exhausted, preventing a later cue from overwriting an earlier queued draw. |

The table is transcribed from `src/main/java/com/openggf/graphics/PatternAtlasRange.java`
(base and size per constant); regenerate it from that enum rather than editing rows by hand.

**Shared-base contexts** (`0x40000`):
- S2 Title Card (`TitleCardManager.PATTERN_BASE`) — gameplay scope, not active during cutscenes
- S2 Special Stage Results (`SpecialStageResultsScreenObjectInstance.PATTERN_BASE`)
- S1 Special Stage Results Screen (`Sonic1SpecialStageResultsScreen.PATTERN_BASE`)
- S3K AIZ Intro Art Loader (`AizIntroArtLoader.INTRO_PATTERN_BASE`) — only active during AIZ1 intro
- S2 Title Screen (`TitleScreenDataLoader`) — only active before any gameplay

**Shared-base contexts** (`0x50000`):
- S1 Title Card (`Sonic1TitleCardManager.PATTERN_BASE`)
- S3K Title Card (`Sonic3kTitleCardManager.PATTERN_BASE`)
- S1/S2/S3K Level Select (`Sonic1LevelSelectConstants`, `LevelSelectConstants`, `Sonic3kLevelSelectConstants`)
- S3K Data Select (`S3kDataSelectRenderer.DATA_SELECT_PATTERN_BASE`)

These bases are reused across game-context-mutually-exclusive subsystems (e.g., title card vs. level select vs. data select are never active in the same frame). `PatternAtlas.registerRange(...)` provides a diagnostic collision detector but is not yet enforced at every call site — adding bootstrap-time `registerRange` calls in each owning subsystem is a follow-up.

The source-level ownership for documented ranges is now guarded by
`TestArchUnitRules.virtual_pattern_base_fields_are_backed_by_pattern_atlas_range`
and the S3K frontend range signals in `TestArchitecturalSourceGuard`, so new
`*_PATTERN_BASE` constants inside a registered range must reference
`PatternAtlasRange` instead of hard-coding the numeric base.

```java
// LevelManager.java
private static final int OBJECT_PATTERN_BASE = 0x20000;
private static final int HUD_PATTERN_BASE = 0x28000;
```

The `PatternAtlas` stores all patterns in a HashMap keyed by pattern ID. Each category has a fixed base to prevent collisions when new sheets are dynamically registered (e.g., zone-specific objects like SmashableGround in HTZ).

### Rationale

1. **Level patterns remain cached** - No need to reload level tiles after results screen, enabling instant transitions.

2. **Simpler state management** - No need to track which tiles were overwritten or restore them later.

3. **Easier debugging** - Level and UI patterns coexist without interference; inspecting the atlas shows all patterns.

4. **No VRAM constraints** - Modern systems have abundant texture memory; emulating the 64KB limit adds complexity with no benefit.

### Verification

The rendered output is identical to the original - the same graphics appear at the same screen positions. Only the internal storage differs.

---

## HTZ Cloud Scroll — resolved to the shipped `fixBugs = 0` path

**Location:** `SwScrlHtz.java`
**ROM Reference:** `s2.asm:15851-15866` and `:15881-15891` (two `if fixBugs`
conditionals inside `SwScrl_HTZ`)

This entry previously documented a deliberate deviation: the engine implemented
the **corrected** (`fixBugs = 1`) path so the clouds scrolled smoothly instead of
with the ROM's periodic 2-frame stutter. That is no longer the case, and the
deviation is gone.

The disassemblies are assembled with the bug-fix conditional OFF, the traces
record shipped-ROM behaviour, and the engine models the un-fixed branch even where
it is plainly a bug — see the `FixBugs` gotcha in CLAUDE.md / AGENTS.md. The
stutter is what the ROM does.

Two conditionals were involved, and both are now on the shipped path:

1. **The divide.** Shipped: `asr.w #4,d1`, a word shift that discards the
   remainder. Fixed: `swap d1 / asr.l #4,d1 / swap d1`, widening the divide so the
   remainder survives in the low half.
2. **The accumulator init.** Shipped: `moveq #0,d3 / move.w d1,d3`, zero-extending
   only the low word so the fixed-point accumulator starts with **no fractional
   part** — this is the actual source of the jerkiness, and the disassembly's own
   comment says so. Fixed: `move.l d1,d3`, carrying the preserved fraction.

Both `fixBugs = 1` variants are written out in full in the source comments, so the
site is self-describing if the bug-fixed revisions are ever supported.

Note on why this was invisible: HTZ trace replays were green before and after the
correction, so no compared column currently observes that accumulator. The
deviation was **latent, not harmless** — it would have desynced the first fixture
that compared cloud scroll.

---

## MCZ Rotating Platforms Child Cleanup

**Location:** `MCZRotPformsObjectInstance.java`
**ROM Reference:** `s2.asm` lines 53707-53726 (child spawn), lines 53801-53803 / 53826-53828 (`MarkObjGone2` calls)

### Original Implementation

In the ROM, all three objects (parent + 2 children) live in the same flat object RAM table. Children are allocated via `AllocateObjectAfterCurrent` into adjacent SST slots. Each object independently calls `MarkObjGone2` using `objoff_32` (base X = the parent's original spawn X):

```asm
; routine 2 exit (MTZ path / parent):
loc_27C5E:
    move.w  objoff_32(a0),d0
    jmpto   JmpTo3_MarkObjGone2

; routine 4 exit (MCZ path / children):
loc_27C9A:
    move.w  objoff_32(a0),d0
    jmpto   JmpTo3_MarkObjGone2
```

`Obj6A_InitSubObject` copies the parent's `x_pos` to each child's `objoff_32`:

```asm
    move.w  x_pos(a0),objoff_32(a1)
```

So all three share the same base X and self-destruct at the same camera threshold.

### Our Implementation

Our engine uses a two-tier object system: placement-windowed objects (`activeObjects`) and unwindowed dynamic objects (`dynamicObjects`). The parent is placement-managed, but children are dynamic and have no off-screen removal.

Instead of independent self-cleanup, the parent's `onUnload()` explicitly destroys its children:

```java
@Override
public void onUnload() {
    for (MCZRotPformsObjectInstance child : children) {
        child.setDestroyed(true);
    }
    children.clear();
}
```

### Rationale

1. **Architectural mismatch** - The ROM's flat object table lets every object run `MarkObjGone2` against a base X. Our dynamic objects have no equivalent windowing mechanism.

2. **Parent-driven cleanup is idiomatic** - This matches the pattern used by other parent-child objects in the engine (`AizGiantRideVineObjectInstance`, `Sonic1CaterkillerBadnikInstance`, `SolBadnikInstance`).

3. **Same trigger point** - The parent's Placement window check uses the same spawn X that the ROM's `MarkObjGone2` checks via `objoff_32`, so cleanup occurs at the same camera position.

### Verification

When the camera leaves the MCZ crate area, all three objects are removed. On return, the parent is re-spawned by Placement and creates fresh children — no accumulation.

---

## Multi-Sidekick Daisy Chain

**Location:** `Engine.java`, `SidekickCpuController.java`, `SpriteManager.java`, `LevelManager.java`
**ROM Reference:** Sonic 2 supports exactly one CPU-controlled Tails at `$FFFFB040` (Sidekick). S3K adds Knuckles as a playable character but still has at most one CPU follower.

### Original Implementation

The ROM allocates a single fixed RAM slot for the sidekick character. `Tails_CPU` routines follow Sonic with a 17-frame position/input history delay. There is no support for multiple sidekicks, sidekick chains, duplicate characters, or the main player character also appearing as a sidekick.

### Our Implementation

The engine supports an arbitrary number of CPU-controlled sidekicks configured via comma-separated `SIDEKICK_CHARACTER_CODE` (e.g. `"tails,knuckles,sonic,sonic"`). Key divergences:

**Daisy chain following.** Each sidekick follows the one in front of it rather than all following Sonic. The chain uses the same 17-frame history delay per link. A direct CPU leader already in NORMAL is used immediately; the settled-leader threshold is only for healing past broken or not-yet-normal links via `getEffectiveLeader()`.

**Duplicate characters.** The same character can appear multiple times (e.g. five Sonics). Each duplicate gets a separate DPLC pattern bank in the virtual `0x38000+` range to prevent atlas corruption. The `PlayerSpriteRenderer` uses `renderPatternWithId()` to bypass the VDP's 11-bit pattern index limit.

**Same character as player.** The main player's character can also be used as a sidekick (e.g. Sonic main + Sonic sidekick). VRAM bank slot allocation ensures each instance has independent DPLC storage.

**Per-character respawn strategies.** The ROM's Tails CPU respawn is a single fly-in-from-above behavior. The engine generalizes this via `SidekickRespawnStrategy`:
- **Tails**: flies in from above (ROM-accurate)
- **Knuckles**: glides in from the screen edge opposite to the leader's movement direction
- **Sonic**: walks or spindashes in from the nearest floor at the screen edge

**Parallel respawn.** When multiple sidekicks despawn simultaneously, they respawn in parallel using chain healing rather than cascading one-by-one.

**P2 input routing.** Only the first sidekick in the chain receives Player 2 controller input, matching the ROM's single-sidekick model.

### VRAM Bank Limits

Duplicate-character sidekick DPLC banks occupy pattern ID ranges above the VDP's native 11-bit (2048 tile) limit:

| Range | Purpose | Capacity |
|-------|---------|----------|
| `0x38000+` | Sidekick body DPLC banks | ~512 Sonic-type or ~64 before hitting tail range |
| `0x39000+` | Tails tail appendage (Obj05) banks | ~1,170 tail appendages |
| `0x40000` | Title Card (next range) | Collision boundary |

Practical limits before title card pattern corruption:
- **All-Sonic sidekicks** (no tail appendages): ~512
- **All-Tails sidekicks** (body + tail): ~65
- **Mixed configurations**: proportional; typical setups (≤10 sidekicks) are well within budget

### Rationale

1. **Fun/novelty** — Multiple characters running together in a chain looks great and extends the ROM's single-sidekick concept naturally.
2. **Architecture exercise** — The `SidekickRespawnStrategy` interface and chain healing demonstrate clean extension of ROM-accurate systems without if/else chains.
3. **No impact on accuracy** — Single-sidekick configurations behave identically to the ROM. Multi-sidekick behavior only activates when explicitly configured.

### Configuration

```json
{
  "SIDEKICK_CHARACTER_CODE": "tails,knuckles,sonic"
}
```

Empty string disables sidekicks (default). Single value preserves ROM-accurate single-sidekick behavior.

---

## Sonic 1 Monitor Sidekick Guard

**Location:** `Sonic1MonitorObjectInstance.java`
**ROM Reference:** `docs/s1disasm/_incObj/26 Monitor.asm`

### Original Implementation

Sonic 1 has no CPU sidekick actor. `Touch_Monitor` only ever runs with the single main player object in `a1`, so the ROM does not need a sidekick-specific monitor-break guard.

### Our Implementation

When cross-game sidekicks are donated into Sonic 1, `Sonic1MonitorObjectInstance.onTouchResponse(...)` now returns early for `player.isCpuControlled()`. This matches the engine's shared monitor rule already used by the Sonic 2 and Sonic 3K monitor implementations.

### Rationale

1. **Cross-game donation introduces a new actor class** - Sonic 1 content can now run with AI sidekicks that the ROM never had to consider.
2. **Protects intended ownership rules** - Sidekicks should not be able to break monitors or claim their rewards in donated-content scenarios.
3. **Keeps behavior aligned across games** - Sonic 2 and S3K already block CPU sidekicks from monitor breaks, so the donated S1 path should not be the odd exception.

### Verification

`TestSonic1MonitorObjectInstance.cpuSidekickCannotBreakSonic1Monitor` covers the donated-sidekick path. The local S1 disassembly also confirms that the static monitor icon mapping (`Map_Monitor` frame `2`) is real art, so no icon-suppression discrepancy entry is needed.

---

## S2 CPZ Debug Placement Capability Boundary

**Location:** `CPZSpinTubeObjectInstance.java`
**ROM Reference:** `docs/s2disasm/s2.asm:36224-36230,48526-48527`

### Original Implementation

Obj1E checks the ROM-global `Debug_placement_mode` byte before testing the
player's tube-entry bounds. S2's debug object-placement path is entered from
`Debug_mode_flag` plus the B button and also owns level-wide ring/item placement
and related object, signpost, and scroll gates.

### Engine Implementation

OpenGGF does not expose that native level-wide placement mode. Its supported
level debug capability is the engine free-fly mode toggled by `D`; CPZ's entry
test now rejects a player in that mode, matching the shared touch/solid debug
boundary. Normal tube gameplay remains ROM-driven; the existing engine
free-fly mid-traversal reset is unchanged.

### Rationale and Verification

Mapping free-fly movement to the complete native placement mode would invent
the missing global mode owner and its ring/item/object lifecycle. The bounded
guard prevents a visible interaction leak without claiming native placement
parity. `TestCPZSpinTubeObjectInstance.engineDebugMovementCannotBeCapturedByTube`
and `TestSonic2SpecialStageModuleGraph.moduleDoesNotAdvertiseNativeLevelDebugPlacement`
cover the boundary; native ring/item placement remains unavailable until an
engine-wide placement capability is designed and sourced from the ROM.

---

## S2 Native Human-P2 Monitor Branch Unavailable

**Location:** `MonitorObjectInstance.java`, S2 level-mode ownership
**ROM Reference:** `docs/s2disasm/s2.asm:85337-85340`

### Original Implementation

`Touch_Monitor` allows a monitor break from above for `MainCharacter`, or for a
second player only when the ROM-global `Two_player_mode` is nonzero; the roll
animation check follows at `s2.asm:85342-85343`. CPU Tails
is therefore blocked from that branch in ordinary one-player gameplay, while a
human P2 can use it in the native competition mode.

### Engine Implementation

S2 has no competition-mode owner or human-P2 playable slot. Its level-event
owner keeps the ROM `Two_player_mode` gate explicitly false, and Player 2
bindings feed the existing CPU-sidekick/manual-input path.
`MonitorObjectInstance` consequently retains the ROM-faithful
lead-player/CPU-sidekick behavior; no object-local human-P2 branch is
advertised or fabricated.

### Rationale and Verification

The missing behavior depends on a complete mode (player slots, initialization,
physics, art, scoring, camera, and competition-zone lifecycle), not a monitor
condition. A dedicated S2 competition-mode design must own that state before
the native branch can be implemented and validated. Existing monitor sidekick
tests prove the supported path; no title-provider assertion is used as evidence
of mode absence. Human-P2 monitor parity is deferred as an explicit product-level
capability gap.

---

## Bonus Stage Game Mode

**Location:** `GameLoop.java`, `GameMode.java`
**ROM Reference:** `sonic3k.asm` Level: routine (line 7504)

### Original Implementation

S3K bonus stages (Gumball, Pachinko, Slots) are loaded through the normal `Level()` routine. The zone ID changes to a bonus zone ($1300/$1400/$1500), the level loads, and the game loop runs identically to any other level. No separate game mode exists.

### Engine Implementation

Bonus stages use a distinct `GameMode.BONUS_STAGE` that runs the same level rendering/physics/object pipeline as `LEVEL` mode, but with an explicit `AbstractBonusStageCoordinator` managing entry/exit lifecycle, state persistence, and ring gains.

### Reason

The engine's `GameLoop` dispatches behavior based on `GameMode`. Overloading `LEVEL` with conditional bonus stage logic would scatter bonus-specific checks across the codebase (timer suppression, death plane disable, exit detection, state save/restore). A dedicated mode keeps the lifecycle explicit and contained in the coordinator.

### Impact

None on gameplay. The level pipeline (rendering, physics, objects, collision) is identical between `LEVEL` and `BONUS_STAGE` modes. The only difference is the coordinator managing transitions.

---

## Bonus Stage Rewind Scope (Within-Stage Only)

**Location:** `LiveRewindManager`, `GameLoop.isBonusStageRewindable()`, `BonusStageCoordinatorRewindAdapter`
**ROM Reference:** n/a (engine-only feature; bonus stages have no ROM-native rewind concept)

### Original Implementation

N/A — held rewind is an engine-only feature with no ROM equivalent.

### Engine Implementation

Live rewind now works *within* the Gumball and Pachinko bonus stages (`BonusStageProvider.supportsRewind()==true`): held rewind restores in-stage object/reward/accumulator state exactly as it does in `GameMode.LEVEL`. The timeline is intentionally severed at the bonus-stage boundary in both directions. The `GameplayModeContext` (and its `rewindRegistry`) survives bonus entry — that is precisely why the coordinator accumulator adapter can be registered/deregistered against it. What resets the rewind timeline is the `LEVEL_LOAD`-class boundary that `loadZoneAndAct` emits on the bonus-zone load: it drives `LiveRewindManager.handleLevelLoadBoundary()` → `resetToFrameZero`, and the entry transition also marks `MODE_EXIT_TO_NON_REWINDABLE` (buffer clear). So rewind cannot walk backward across that boundary into the parent level's pre-entry history; likewise the exit frame (`bonusStageTransitionPending`) is excluded from capture, so rewind never walks forward past the bonus stage back into the level. Held rewind is confined to frames strictly inside the active bonus stage.

The Slot Machine bonus stage is excluded: `BonusStageProvider.supportsRewind()` reports `false` for it, so `LiveRewindManager`/`GameLoop` never engage rewind while it is active (see `docs/S3K_KNOWN_DISCREPANCIES.md` for the reason and the deferred follow-up plan).

**Cosmetic caveat:** during backward re-simulation frames (replaying forward from a keyframe to reach a rewind target), the player sprite's art-tile high-priority bit is not re-forced each frame the way normal forward gameplay does. This is a minor rendering-order artifact only visible mid-scrub, and it self-corrects once rewind releases and normal per-frame updates resume; it has no effect on collision, physics, or reward-accumulator correctness.

### Reason

Bonus stages load through the same `LEVEL_LOAD`-class transition as ordinary level entry/exit, so severing the rewind timeline at that boundary reuses the already-correct `LEVEL_LOAD` behavior instead of teaching the rewind system to snapshot/restore across a full mode swap. The player art-tile priority bit gap is accepted as a cosmetic-only artifact rather than adding a per-frame re-force to the re-simulation stepper for a bit that only matters visually and only during the scrub itself.

### Impact

None on gameplay determinism or reward correctness (ring/item totals match what was actually collected at the point rewind is released). The only visible effect is the momentary art-tile priority artifact during backward scrubbing described above.

---

## HCZ Conveyor Belt Rolling State Clear (Resolved)

**Location:** `HCZConveyorBeltObjectInstance.java` (`capturePlayer()`)
**ROM Reference:** `sonic3k.asm` lines 66490-66511 (standing capture), 66528-66547 (hanging capture)

### Original Implementation

The ROM's capture sequences for the HCZ conveyor belt (Obj 0x3E) do not contain an explicit `bclr #Status_Roll,status(a1)`. The capture code clears velocities, sets `object_control` to 3, snaps the player's Y position, and sets the mapping frame, but never directly modifies `Status_Roll`:

```asm
; Standing capture (sonic3k.asm:66490-66503):
    clr.w   x_vel(a1)
    clr.w   y_vel(a1)
    clr.w   ground_vel(a1)
    andi.b  #$FC,render_flags(a1)   ; clears render flip bits, NOT status
    move.w  d0,y_pos(a1)
    move.b  #0,anim(a1)
    move.b  #3,object_control(a1)
    move.b  #$63,mapping_frame(a1)
    ; ... state init, DPLC call
```

The release path unconditionally sets `Status_Roll` via `bset #Status_Roll,status(a1)` (sonic3k.asm:66454).

### Our Implementation

An earlier engine build added an explicit `setRolling(false)` to `capturePlayer()` that the ROM does not
perform, so that a player captured mid-roll would not count as attacking. That divergence has been removed:
`capturePlayer()` now mirrors the ROM sequence instruction for instruction — velocity clears, render-flip clear,
Y snap through `NativePositionOps.writeYPosPreserveSubpixel`, `anim = 0`, `object_control = 3` via
`ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed()`, and the initial mapping frame — with no roll
write. The release path still sets the roll bit as the ROM does (`sonic3k.asm:66454`).

### Rationale

The roll bit is left exactly as the ROM leaves it. Enemy touch responses consume the native attack state the
same way the ROM does, so the earlier engine-side clear is no longer needed to keep the player vulnerable on the
belt.

### Verification

Retained as a record of the removed divergence. `TestHCZConveyorBeltObjectInstance` covers the capture/release sequence.

---

## Right-Wall Penetration Timer Heuristic

**Location:** `AbstractPlayableSprite.rightWallPenetrationTimer`
**ROM Reference:** `AnglePos`/right-wall sensor selection paths in the Sonic 1, Sonic 2, and Sonic 3K disassemblies.

### Original Implementation

The ROM resolves the active wall sensor and floor angle from the current frame's sensor probes. Odd/flagged angle values are snapped from the same frame's result; there is no cross-frame map of prior alternate-sensor angles and no grace timer for right-wall penetration.

### Our Implementation

The engine carries one narrow right-wall stability heuristic: `AbstractPlayableSprite.rightWallPenetrationTimer` gives a short grace period while resolving right-wall penetration and is captured in playable-sprite rewind snapshots.

A second heuristic, `CollisionSystem.pendingOddSensorFallbackAngles` (a cross-frame cache of the alternate sensor angle applied when the selected sensor reported distance 0 with an odd angle), has been **removed**: ROM `Sonic_Angle` (`docs/s1disasm/_incObj/Sonic AnglePos.asm:186-208`) snaps an odd selected angle straight to `(angle+0x20)&0xC0` from the current frame, and the cache was resurrecting a stale angle one frame early on the S1 LZ3 and S3K CNZ right-wall paths (see the `CHANGELOG.0.6.md` entry "S1 right-wall odd-angle snap (LZ3/CNZ)").

### Rationale

The remaining timer prevents visible ground-mode oscillation at right-wall transitions in the Java collision model while the broader collision pipeline still differs structurally from the ROM's exact object RAM and terrain probe sequencing.

### Verification

`TestAbstractPlayableSpriteRewindCapture` covers the captured `rightWallPenetrationTimer` field.

---

## S2 CPZ Visual Water Surface Oscillation

**Location:** `Sonic2WaterDataProvider.getVisualWaterLevelOffset` (CPZ branch), `WaterSystem.getOscillatedWaterLevel`
**ROM Reference:** `docs/s2disasm/s2.asm:5273-5282` (`MoveWater`)

### Original Implementation

For non-ARZ S2 water zones (CPZ Act 2, plus the unused HPZ), `MoveWater` reads the first word of `Oscillating_Data`, shifts it right by 1, and adds the result to `Water_Level_2` to produce the visible water surface position:

```asm
MoveWater:
    clr.b   (Water_fullscreen_flag).w
    moveq   #0,d0
    cmpi.b  #aquatic_ruin_zone,(Current_Zone).w
    beq.s   +
    move.b  (Oscillating_Data).w,d0
    lsr.w   #1,d0
+
    add.w   (Water_Level_2).w,d0
    move.w  d0,(Water_Level_1).w
```

`Oscillating_Data` is the value-word of oscillator 0 (initial value `$0080`, limit `$10`), so the high byte read by `move.b` ranges 0..16 and the resulting `lsr.w #1` offset is 0..8 — a one-sided positive bob added on top of `Water_Level_2`.

### Engine Implementation

`Sonic2WaterDataProvider.getVisualWaterLevelOffset(ZONE_CPZ, 1)` returns `OscillationManager.getByte(0) - 8`, producing a signed offset in the range `-8..+7` centred around zero. `WaterSystem.getOscillatedWaterLevel` then adds this to the base water level. The shift-by-1 from the ROM is replaced by a subtract-by-8 recentring.

### Rationale

The engine uses this offset purely as a *visual* bob applied on top of the gameplay water level (`baseLevel`) returned by the water system. Centring around zero (`oscillation - 8` in place of the ROM's `oscillation >> 1`) keeps the absolute water gameplay surface anchored at the value owned by `WaterSystem` / the dynamic water handler, while the visible surface oscillates symmetrically `±8` pixels rather than only ever sitting 0..8 pixels *below* its nominal level. The original 0..+8 one-sided bob would otherwise need every base water level returned from `WaterDataProvider.getStartingWaterLevel` (and every dynamic target written by event managers) to be biased down by 4 pixels to hit the same visible mean — an invasive change for a purely cosmetic axis.

The `-8` recentring has been the engine's behaviour since `WaterSystem` was first authored; the `dfbc610c9` test commit / `7cad4c068` provider refactor only moved the existing logic out of `WaterSystem` and onto the per-game `WaterDataProvider`. No commit on this branch introduced the divergence.

### Verification

`TestSonic2WaterDataProvider.testCpz2VisualOffsetAtResetIsMinusEight` pins the post-`OscillationManager.reset()` value at literal `-8`, and `testCpz2VisualOffsetTracksOscillatorAfterStepping` asserts the formula `getByte(0) - 8` after stepping the oscillator (with an explicit `byte0 != 0` precondition so the test would fail if the oscillator never advanced). Trace replay fixtures are unaffected because the comparator covers camera/player position only, not water-level pixel offsets.

### Removal Condition

Remove this entry if the engine is ever re-aligned to the ROM's `lsr.w #1` formula. That would require either biasing every base water level returned from `WaterDataProvider.getStartingWaterLevel` (and every `DynamicWaterHandler` target write) down by 4 pixels, or changing the visual contract so callers expect a one-sided 0..+8 bob layered on top of a slightly higher mean.

---

## S2 Music Offsets Resolved from Hardcoded REV01 Table

**Location:** `Sonic2SmpsLoader.findMusicOffset` / `Sonic2SmpsLoader.musicMap`
**ROM Reference:** `docs/s2disasm/sound/_smps2asm_inc.asm` (`zMasterPlaylist` flag table + per-bank `MusicPoint` pointer tables, inside the Saxman-compressed Z80 driver blob)

### Original Implementation

The ROM resolves a music ID to its SMPS data through the Z80 sound driver's `zMasterPlaylist` flag table and the per-bank pointer tables (`MusicPoint` entries) it references. That structure only exists in readable form *after* the Saxman-compressed Z80 driver blob has been decompressed into Z80 RAM at runtime — the bytes sitting in 68K ROM are still compressed. The driver indexes `zMasterPlaylist` by the requested song ID to pick a bank and pointer.

### Our Implementation

`Sonic2SmpsLoader.findMusicOffset(musicId)` resolves song offsets from a hardcoded `Sonic2Music`-ID → REV01-ROM-offset map (`musicMap`) instead of reading `zMasterPlaylist` / `MusicPoint` from ROM:

```java
public int findMusicOffset(int musicId) {
    Integer mapped = musicMap.get(musicId);
    if (mapped != null) {
        return mapped;
    }
    // ...
}
```

The offsets in `musicMap` were discovered empirically and verified against REV01.

### Rationale

1. **The ROM table is compressed at rest.** `zMasterPlaylist` and the per-bank `MusicPoint` pointer tables live inside the Saxman-compressed Z80 driver blob in 68K ROM. The previous ROM-driven implementation parsed those compressed bytes directly and could not yield correct offsets; the table is only readable after a runtime Z80 decompression.

2. **Engine IDs are intentionally shifted.** The engine's `Sonic2Music` IDs are systematically shifted relative to the disassembly's `zMasterPlaylist` entry order (e.g. `EMERALD_HILL.id == 0x81` loads the EHZ track, but `zMasterPlaylist[0]` / disasm id `0x81` is `Mus_2PResult`). Even a properly Z80-decompressed lookup would disagree with the engine's intended track for most IDs.

3. **No audible difference.** Both paths reference the same underlying SMPS music data — only the lookup source differs. The hardcoded REV01 map is authoritative until both problems above are solved.

### Verification

The hardcoded `musicMap` covers every `Sonic2Music` entry, and the offsets are the empirically-verified REV01 ROM addresses used by the engine and the sound-test debug tool (`SoundTestApp`). Playback matches the original game's track-to-ID assignments.

### Removal Condition

Remove this entry once the S2 driver's `zMasterPlaylist` / `MusicPoint` tables are read through a runtime Z80 decompression path **and** the `Sonic2Music` ID shift is reconciled with the disassembly entry order, so `findMusicOffset` can resolve offsets from ROM data rather than the hardcoded REV01 map.

---

## Sonic 1 credits demo trace replay divergences (post-frame-0-hydration removal)

**Source:** Removed in commit following `6ea9554`. Prior commit `6ea9554` removed `TraceEvent.StateSnapshot` hydration from `AbstractCreditsDemoTraceReplayTest` so the replay tests now exercise the engine without per-frame trace correction. The follow-up commit additionally removed the trace-derived per-demo `STARTING_ANIMATION_ID` / `setDirection(RIGHT)` overrides from `Sonic1CreditsDemoBootstrap.applyStartingPose` (now deleted) and let the engine's natural `Sonic_Animate` pass and post-spawn defaults drive the frame-zero pose. Removing those overrides did not change which credits demos pass or fail.

### Status

All 8 S1 credits-demo trace replay tests pass in the 2026-05-19 targeted S1
regression sweep. The historical failures below were exposed after removing
trace-state hydration and trace-derived pose overrides; they were pre-existing
engine bugs that the prior hydration was masking, not regressions caused by
removing the trace-derived overrides. See `docs/status/trace-frontier-log.md` for the
latest frontier snapshot.

| Test | First divergence frame | First divergence | Total errors |
|------|------------------------|------------------|--------------|
| `TestS1Credits01Mz2TraceReplay` | resolved 2026-05-19 | MZ push-block lava-geyser slot/launch phase | 0 in targeted replay |
| `TestS1Credits02Syz3TraceReplay` | resolved before 2026-05-19 sweep | full targeted replay passes | 0 in targeted sweep |
| `TestS1Credits03Lz3TraceReplay` | resolved 2026-05-18 | REV01 LZ wind-tunnel d0-clobber/vblank-phase emulation | 0 in targeted replay |
| `TestS1Credits04Slz3TraceReplay` | resolved before 2026-05-19 sweep | full targeted replay passes | 0 in targeted sweep |
| `TestS1Credits05Sbz1TraceReplay` | resolved before 2026-05-19 sweep | full targeted replay passes | 0 in targeted sweep |

### Rationale for not patching from traces

Per CLAUDE.md "Trace Replay Tests" the comparison-only invariant forbids hydrating engine state from `TraceEvent.StateSnapshot` events; trace data is read-only diagnostic input. Any per-credit override to mask these failures would be a spec violation. The bugs need to be diagnosed and fixed in the engine (likely physics, ring/object collision, or zone-specific systems such as LZ water/SBZ junction objects) and the failing tests turned green by ROM-accurate code paths, not bootstrap papering.

### Resolved MZ2 (`TestS1Credits01Mz2TraceReplay`) push-block/geyser root cause (2026-05-19)

The MZ2 credits trace diverged when the pushed block reached the
`PushB_LoadLava` positions and spawned a geyser maker from a later SST slot.
`GMake_Wait` (`docs/s1disasm/_incObj/4C & 4D Lava Geyser Maker.asm`) first
spends one live tick after `FindFreeObj`, then its bubble animation advances
to `GMake_MakeLava`. At that point the maker sets the parent block airborne
and writes `#-$580` to `obVelY`.

The engine now mirrors both phases: parent-spawned geyser makers start with
one live tick, and `Sonic1PushBlockObjectInstance.applyLavaGeyserLaunch`
preserves the first airborne frame's launch phase so the initial `#-$580`
displacement is visible before `loc_C056`'s `+$18` gravity affects the next
velocity. This removes the frame-493 maker timing drift and the frame-499
one-pixel vertical mismatch without reading trace state back into the engine.

### Resolved LZ3 (`TestS1Credits03Lz3TraceReplay`) y-bump root cause (2026-05-18)

The 2px Y drift starting at frame 221 is caused by a documented bug in the
ORIGINAL Sonic 1 REV01 ROM. `LZWindTunnels` (`docs/s1disasm/_inc/LZWaterFeatures.asm`)
overwrites the low byte of `d0` with `v_vbla_byte` (line 313) but later uses
`d0` as if it still held the saved player X for the curve check (line 329).
Disassembly comment at line 309: `d0 is overwritten but later used as if it
wasn't!`. The `if FixBugs` branch wraps `move.w d1,d0` to restore the saved
X.

The bug fires the curve adjustment (`+2`/`-2` to `obY`) on frames where it
would otherwise be skipped — most notably every 64 frames when the rushing
water sound branch reloads `d0` with `sfx_Waterfall = 0x00D0`. The recorded
trace, captured on REV01 hardware, contains those occasional `+2` bumps.

`Sonic1LZWaterEvents` now emulates the REV01 non-FixBugs path by preserving
the high byte of the player X check while replacing the low byte with
`v_vblank_byte & 0x3F`, and by using the waterfall SFX id on sound-gate
frames.

**Corrected 2026-08-21.** This paragraph previously said that
`Sonic1CreditsDemoBootstrap` "also seeds the LZ credits-demo vblank phase when
applying the lamppost state". **It does not, and no longer should.** The
bootstrap carries an explicit note in its place: the ROM's `v_vblank_count` is
incremented once per V-blank at `VBlank_Exit` (`sonic.asm:685`) and written
nowhere else in the entire ROM, so nothing resets it on level load and
`EndDemo_LampVar` (`sonic.asm:3879`) carries no vblank field. Its value entering
a credits demo is a free-running count since console reset, not level-derived
state, and cannot be reconstructed from the lamppost table -- **seeding a
measured phase there would be a fitted model**. The counter is instead
established once at frame 0 by the replay entry path, as every other
trace-replay entry does.

The resolution above survives that removal: `TestS1Credits03Lz3TraceReplay` was
re-run on 2026-08-21 and passes green, so the fix is carried by the
`d0`-clobber emulation and the frame-0 establishment alone. The stale sentence
had been read as describing a live mechanism by two people in one session, one
of whom spent part of a round looking for code that had been deleted.

The `Sonic1LZWaterEvents` X-push and Y-input nudges have been migrated from
`setCentreX`/`setCentreY` (which zero sub-pixels) to
`setCentreXPreserveSubpixel`/`setCentreYPreserveSubpixel` so that ROM-accurate
word-only writes (`addq.w #4,obX`, `subq.w #1,obY`, `addq.w #1,obY`,
`add.w d0,obY`) preserve `obSubpixelX`/`obSubpixelY`. This brings the trace's
`sub_x` line into agreement (was a persistent `0x6400` desync) while the
REV01 wind-tunnel bug emulation removes the remaining LZ3 y divergence.

### Removal Condition

Remove this entry once each listed test has been diagnosed (root-cause identified in the engine), fixed at the source, and is consistently green against the recorded ROM trace.

---

## Right-Boundary Is Viewport-Independent (Level Edge)

**Location:** `RightBoundary.java`, `PlayableSpriteMovement.doLevelBoundary()`
**ROM Reference:** `sonic3k.asm:23183-23186` (`Player_Boundary_Sides`, strict path: `Camera_max_X_pos + $128`); `s2.asm:36907-36909` (normal path: `Camera_max_X_pos + $128 + $40`)

### Behavior

The player's right level-boundary clamp is the level's design edge: `Camera_max_X_pos` plus a fixed offset relative to the **native** 320px screen width — NOT the render viewport.

- **Strict path** (S3K `Player_Boundary_Sides`, boss fight, end-of-level): `Camera_max_X_pos + $128` (= `maxX + 320 - 24`)
- **Normal path** (S1/S2/S3K non-strict ground/air): `Camera_max_X_pos + $128 + $40` (= `maxX + 320 - 24 + 64`)

`camera.getMaxX()` holds the native ROM `Camera_Max_X_pos` (the level's right scroll limit, e.g. EHZ `0x2940`), so `maxX + 320` is the level's right wall. `RightBoundary.compute` is called with the fixed `LEVEL_DESIGN_WIDTH = 320`, so the clamp lands at the wall at **every** `DISPLAY_ASPECT` — fully reproducing the ROM `+$128` / `+$128 + $40` values. This is NOT a divergence: native and widescreen produce identical boundaries.

### Why it must not widen

An earlier widescreen pass computed the boundary with `camera.getWidth()` instead of the native width, so at a wider viewport the clamp moved right with the screen (e.g. ULTRA_21_9 → `maxX + 528 - 24` = level edge + 184). Because the level geometry only exists up to `maxX + 320`, this let the player **walk past the level's right wall into the void beyond a camera lock and fall to their death** where no level exists. The boundary tracks the level's wall, not the screen, so it stays native regardless of viewport.

A known cosmetic consequence at widescreen: when the camera is locked at `maxX`, the wider screen renders past the level edge (`maxX + 320 .. maxX + viewportWidth`) as empty space, and the player stops before reaching the visible right edge. That is the safe trade-off.

**Do not "fix" this by clamping the camera's reachable X.** Capping the camera's right edge at the level edge (effective top-left max `maxX − (viewportWidth − 320)`) was tried and reverted: level events trigger on `camera.getX() >= threshold` where the threshold sits near `maxX` (e.g. `Sonic2EHZEvents` spawns the boss at `camera.getX() >= 0x28F0` with `maxX = 0x2940`). Reducing the camera's reachable X by the widescreen inset (208px at ULTRA) stops the camera short of those thresholds, so bosses never spawn and arena locks never engage. A real fix would have to make the event thresholds themselves viewport-aware, which is a larger, ROM-state-sensitive change deferred for now.

### Verification

`TestRightBoundary` pins the pure-function math; `TestPlayableSpriteMovement.rightLevelBoundaryIsViewportIndependentAtWidescreen` drives `doLevelBoundary` with a 528px camera and asserts the clamp still lands at the native level edge.

---

## Object Despawn and Visibility Windows

**Location:** `AbstractObjectInstance.isInRange()`, `AbstractObjectInstance.isChkObjectVisible()`
**ROM Reference:** `Macros.asm` (`out_of_range` macro: `cmpi.w #128+320+192,d0`); `docs/s1disasm/_incObj/sub ChkObjectVisible.asm`

### Original Implementation

**`out_of_range` despawn:** The ROM chunk-aligns the object X and the camera-left-minus-128 position, subtracts them (16-bit unsigned wrap), and compares against a single compile-time constant: `128 + 320 + 192 = 640` (`$280`). Any object whose distance exceeds 640 pixel-widths is deleted.

**`ChkObjectVisible` visibility:** The ROM subtracts the camera X from the object X (`move.w $10(a0),d0` / `sub.w (v_screenposx).w,d0`) and rejects the object if `dx` is outside `[0, 320)`, and similarly for Y / `[0, 224)`.

Both constants encode the native Mega Drive screen dimensions: 320 × 224 pixels.

### Our Implementation

Both limits are derived from the cached `cameraBounds` rectangle rather than hardcoded constants:

```java
// isInRange — despawn window scales with viewport width
int viewportWidth = cameraBounds.right() - cameraBounds.left();
return dist <= (128 + viewportWidth + 192);

// isChkObjectVisible — visibility rectangle scales with viewport
int viewportWidth  = cameraBounds.right()  - cameraBounds.left();
int viewportHeight = cameraBounds.bottom() - cameraBounds.top();
if (dx < 0 || dx >= viewportWidth)  return false;
return dy >= 0 && dy < viewportHeight;
```

`cameraBounds` is updated once per frame by `ObjectManager.updateCameraBounds()` from `camera.getX() / getY() / getWidth() / getHeight()`, so the window always matches the configured viewport.

**Load-ahead window is capped (intentionally narrower than the despawn window).** The despawn/visibility windows above scale by the *full* viewport width so on-screen objects at the wider right edge are never culled. The *spawn load-ahead* window (`AbstractPlacementManager.loadAheadFor`), however, grows only by the minimum pre-load lead — `max(320 + extraAhead, viewportWidth + 128)` — NOT by `viewportWidth + extraAhead`. The object slot pool is a fixed ROM-sized table (`ObjectSlotLayout`: S1=96, S2=112, S3K=89); a window that grew by the full extra width overran the pool in dense areas, so `allocateSlot()` returned −1 and spawns were silently dropped (objects intermittently failing to load when scrolling right at widescreen, in all games). Capping the load-ahead keeps the live-object count close to native (≈+2% at ULTRA_21_9 vs +27% before) so the pool no longer overruns, while the wider despawn/visibility windows still prevent right-edge culling. This narrower load window is deliberate — do not widen it to match the despawn window. Native (320) is byte-identical (load-ahead = `0x280`).

### Parity at Native Width

At `DISPLAY_ASPECT = NATIVE_4_3` (viewport width 320, height 224):

| Check | Engine limit | ROM literal | Match |
|-------|-------------|-------------|-------|
| `isInRange` | 128 + 320 + 192 = 640 | `$280` = 640 | exact |
| `isChkObjectVisible` X | `[0, 320)` | `[0, 320)` | exact |
| `isChkObjectVisible` Y | `[0, 224)` | `[0, 224)` | exact |

`TestObjectViewportWindowWidth` pins this parity with dedicated native-width test cases.

### Rationale

At widescreen viewport widths (e.g. `ULTRA_21_9` = 528 px) the ROM's hardcoded 640 despawn distance is smaller than the distance from the camera to the visible right edge of the widened viewport (~656 px), causing objects near the right edge to be incorrectly deleted mid-screen. Similarly, `ChkObjectVisible` with `dx >= 320` would report objects past the native right edge as invisible even though they are fully in view. Scaling both checks with `viewportWidth` is the correct design decision for the widescreen extension — an object genuinely in view on the wider screen must not be culled or despawned. Note this is the opposite of the [Right-Boundary Is Viewport-Independent (Level Edge)](#right-boundary-is-viewport-independent-level-edge) entry above: despawn/visibility track what is *on screen* (so they widen), whereas the right level boundary tracks the *level's wall* (so it stays native).

### Verification

`TestObjectViewportWindowWidth` covers all four cases: native `isInRange` at 640 and just past, widescreen `isInRange` at 768 (in range) and 896 (out of range); native `isChkObjectVisible` at dx/dy just inside and at exclusive bounds; widescreen `isChkObjectVisible` at dx=321/400 (visible past old 320 limit) and dx=528 (invisible at exclusive widescreen bound).

### Removal Condition

This entry should remain as long as widescreen `DISPLAY_ASPECT` presets are supported. It would only be removed if the engine reverted to a fixed 320 × 224 viewport assumption.
---

## Legacy Pre-Level Intro Prefix Trace Bootstrap Contract

**Location:** `TraceReplayBootstrap`, legacy `TraceMetadata` capability parsing
**Scope:** S1/S2 fixture compatibility only; not live gameplay.

### Current Boundary

Some older non-S3K replay fixtures intentionally begin before their first
comparable LEVEL-mode row. Their legacy capability remains parser-compatible
while those fixtures are retained.

S3K no longer consumes this metadata. It recognizes a pre-level prefix from
the recorded `zone_act_state` mode transition, so it does not infer a phase
from a fixture name, start position, velocity, animation, or oscillator value.

### Rationale

The engine must execute its own production lifecycle. Trace rows and auxiliary
events are comparison-only evidence; they never hydrate player, sidekick,
object, CPU, or oscillator state.

### Removal Condition

Remove this compatibility boundary when the remaining S1/S2 legacy fixtures no
longer advertise it.

---

## S2 Tornado Ride-Start Trace Bootstrap Contract

**Location:** `TraceReplayBootstrap.usesS2TornadoRideStartForTraceReplay`,
`TraceReplaySessionBootstrap.applyS2TornadoRideStart`
**Scope:** Sonic 2 SCZ/WFZ trace replay comparison setup.

### Contract

The S2 Tornado route bootstrap is a deterministic native prelude contract for
SCZ/WFZ ride-start traces. It is not trace-row hydration: replay setup discovers
the live ObjB2 Tornado shape and applies the same title-card/object prelude
needed to reach the first comparable gameplay frame. Non-Tornado S2 traces fall
back to the generic title-card object ticks and must not receive the ride-start
state.

### Rationale

The ROM runs route-specific title-card and Tornado setup before normal gameplay
comparison begins. The replay fixture needs that deterministic prelude so frame
0 compares engine state to the same ROM phase. The contract is acceptable only
because it is route/object-state driven and covered by tests proving ordinary S2
routes do not get the Tornado prelude.

### Verification

`TestTraceReplayStartPositionPolicy`, `TestSonic2TornadoRidePrelude`, and
`TestPreludeFramesKnobsZero` cover the policy boundaries: SCZ/WFZ ride-start
fixtures use the Tornado prelude, ordinary S2 traces do not, and metadata-only
knobs remain zero unless the live ObjB2 shape selects the object prelude.

---

## S2 CNZ Slot-Machine Trace Bootstrap Contract

**Location:** `TraceReplayBootstrap.zoneFeatureTitleCardPreludeFramesForTraceReplay`
**Scope:** Sonic 2 trace replay fixture compatibility only; not live gameplay.

### Contract

S2 CNZ replay fixtures that advertise per-frame slot-machine state need a short
native slot-machine init prelude before comparison begins. The bootstrap now
consumes this through generic `TraceMetadata.hasPerFrameSlotMachineState()`
capability metadata; the old CNZ-named metadata helper remains only as a
deprecated alias for the current recorder schema string. The prelude executes
only ROM `SlotMachine_Routine1`; recorded row 0 owns the following Routine2
reel draw (`s2.asm:59320-59355`).

### Rationale

This is accepted release debt because it is a fixture capability boundary, not
a gameplay rule. The live game should model the ROM slot-machine state directly;
the trace replay layer only bridges old fixture data that did not record enough
state to compare from frame 0 without the prelude.

### Removal Condition

Regenerate the affected fixtures with a recorder schema name that is no longer
CNZ-specific, or replace the fixture-capability predicate with explicit runtime
feature-state phase metadata.

---

## Whole-Run Level-Restart Admission Row

**Location:** `TraceRunPlaybackCoordinator.destinationReady`
**Scope:** Multi-segment whole-run replay comparison ownership only; not
gameplay.

### Contract

A whole-run destination level segment is admitted only once the shared movie
cursor has reached that segment's own recorded first row. Every other condition
stays engine-derived: the destination is still unreachable without a real
production level load with the matching cause, the recorded boundary signal, a
matching level identity, and a released initial title card. The recorded offset
can therefore only defer an admission the semantics already allowed; rows past
it remain bounded by the receipt's zero-or-one consumed-row contract. The
presentation-bridge branch immediately above it already worked this way.

### Rationale

A game's `Level:` routine reaches its first main-loop row after a fixed set of
counted `WaitForVBlank` loops plus un-timed load steps whose elapsed cost the
engine cannot count. For Sonic 1 the counted part is `PaletteFadeOut` (22
rows), the locked `Level_TtlCardLoop` (the queued art's own drain — 146 rows
for MZ, 150 for GHZ at nine patterns per title-card V-int), `Level_Delay` (4)
and `PalFadeIn_Alt` (22) — docs/s1disasm/sonic.asm:2711, 2814-2842, 2957-2966,
1431-1441. The remainder is `NemDec`, the `clearRAM` block, `ClearScreen`,
`Hud_Base`, `LevelSizeLoad`, `LevelDataLoad`, `LoadTilesFromStart`,
`ObjPosLoad`, `ExecuteObjects` and `BuildSprites`, none of which is a counted
loop. Measured against every ordinary boundary of the
`s1-sonic-complete-withemeralds` run, that un-timed remainder is 34 rows for
MZ, 36-37 for LZ and SLZ, 38 for SYZ and 39-40 for SBZ, and it moves by a row
between acts of the same zone — the signature of payload-dependent hardware
cost, not of a loop with a frame count.

### Removal Condition

Remove this boundary if the engine ever gains a derivation for a level load's
elapsed hardware cost, or if the recorder begins to record the restart span so
the counted and un-timed parts can be compared separately.

---

## S3K Production Lifecycle and Structural Trace Replay Scheduling

**Location:** `TraceReplayBootstrap`, `TraceReplaySessionBootstrap`, and the
S3K trace recorder.
**Scope:** Replay scheduling only; live gameplay remains the source of runtime
state.

### Production Lifecycle

S3K replay starts through the same fresh-main-playable lifecycle used by
production. The recorder does not request a sidekick setup tick, an oscillator
pre-advance, or a fixture-specific intro action. Legacy phase metadata remains
parsable for old files, but new S3K recordings do not emit it and S3K scheduling
does not consult it.

### Structural Scheduling

When the first recorded `zone_act_state` is outside LEVEL mode and a later
event enters LEVEL mode, replay has a structural pre-level prefix. The actual
LEVEL-transition row is VBlank-only; later rows execute as full LEVEL frames by
default. Direct lag evidence remains VBlank-only, and unchanged-state input
latches are advance-only only within the bounded prefix. No zone, route, frame,
or first-row outcome predicate chooses the phase.

### Comparison-Only Contract

Physics CSV and auxiliary events are expected values for the comparator and
diagnostic reports only. They never write player, sidekick, object, CPU, RNG,
or oscillator state into the engine during replay. New recorder metadata keeps
the trace profile, BK2 input-alignment offset, and CSV/aux schema versions; its
diagnostic hooks remain explicitly opt-in and quiet mode remains supported.

### Removal Condition

Keep this entry while trace replay exists. It documents the invariant that
production lifecycle execution, rather than trace outcome data, establishes
runtime state.

---

## Sonic 1/2 Native PLC Readiness

**Location:** Session-owned `Sonic1PlcService` / `Sonic2PlcService` and their
game-owned frame lifecycle and producer owners.
**Scope:** Sonic 1/2 Pattern Load Cue completion timing.

S1 and S2 PLC readiness is deterministic ROM-derived production state. The
engine submits each represented ROM cue to its native-shape FIFO, services it
at the owning VBlank lifecycle boundary, and lets results, Final Zone, ARZ, and
other implemented consumers poll that queue. Runtime rendering remains eager
so a submitted cue's prepared art can be displayed without tying host decode
cost to Mega Drive timing.

Skipped and visible initial title-card paths converge on the same idempotent
production boundary. S1 drains its locked primary queue, publishes the
ROM-header secondary cue, and performs the native fixed palette-fade service
iterations; S2 drains its locked primary queue and then publishes the
ROM-header secondary cue. A trace may select the ordinary production
presentation-omitted transition, but it cannot submit, mutate, service, or
release either queue.

The hardware-timing replay exception below does not apply to S2 DPLCs at
all, and applies to S1 PLCs only at the `RunPLC` FIFO-head arming edge
(`NEMESIS_PLC_QUEUE`). Physics and auxiliary trace data remain
comparison-only for both games, no recorded edge may carry decoded art or any
other payload, and no S2 capture can supply an edge of any kind. Everything
this entry describes — the native service queue, its cadence, and every
pattern it produces — stays natively owned in both games; the S1 exception
moves only *when* an engine-submitted arm becomes visible. No committed S1 or
S2 fixture carries a timing stream today, so in practice no recorded edge
reaches either game's PLC service: see "Coverage" under that exception.
Remove or amend this entry only if a future cycle-accuracy finding proves the
modeled native service budget insufficient and the hardware-timing contract is
deliberately expanded further.

---

## Dynamic-Art Row Stamps Are Not Compared In Unrepresented, Unclosed Spans

**Location:** `TraceRunDynamicArtGapComparator` —
`recordedCoverageLeavesSpanUnrepresentedAndUnclosed` and `putRowStamp`.
**Scope:** the `movie_logical_frame` field of `run_gap.edge[N]` /
`run_tail.edge[N]`, and nothing else.

A run fixture's recorded coverage is the union of its segments'
`[bk2_frame_offset, bk2_frame_offset + trace_frame_count)` ranges. Where a
comparison span contains no recorded row **and** no recorded coverage follows
it, the fixture itself declares that span unrepresented and leaves the movie
clock unanchored at its far end. In such a span a dynamic-art edge's movie row
stamp is downgraded from `ERROR` to `WARNING`; it is still reported, and every
other property of the edge — presence, count, ordinal, transfer id, phase,
owner, submission origin, mapping frame, gap edge index, requests, forwarded
completion, and the before/after ledger fingerprints — remains a hard error.
Outside such a span, including every ordinary segment-to-segment gap, the row
stamp is a hard error exactly as before.

**Why the row stamp is not engine evidence there.** Finding 1 of
`docs/architecture/plans/trace/2026-08-06-trace-validation-roadmap.md`
("Why the current green is not yet proof") establishes that
`TraceRunPlaybackCoordinator.destinationReady` gates on
`sharedBk2Cursor() >= destination.bk2FrameOffset()`, and that while the
coordinator sits in `TRANSITION_GAP`, `GameLoop.suppressesRunNativeLevelBody()`
stops the level body running at all: *"The engine's real load duration is never
observed, in either direction."* How many movie rows the engine spends inside
such a span is therefore harness choreography, not engine behaviour. Closing a
row-stamp divergence by inserting harness delays, or by importing a recorded
span duration, would fit the measurement instrument to its own reference —
worse than a fitted constant, because a fitted constant at least models
something.

**What this costs, stated plainly.** After this change the S1 GHZ round-trip
chain verifies load-window **work** and **order** — which transfers exist, for
which owner and mapping frame, in which order, with which requests and which
resulting ledger — but **not** load-window **timing**. Load-window timing
remains unobserved, and cannot be observed, until `destinationReady` /
`suppressesRunNativeLevelBody()` are reworked by the roadmap's level-load-span
strand (section 4 of the same document).

**Guards.** `TestTraceRunDynamicArtGapComparator` pins both halves of the
asymmetry:
`excusesOnlyTheRowStampInsideAnUnrepresentedUnclosedSpan`,
`stillFailsOnEdgeIdentityInsideAnUnrepresentedUnclosedSpan`, and
`comparesTheRowStampByRowWhereRecordedCoverageFollowsTheSpan`. Both mutations
were run and observed red: widening the coverage predicate to accept any span
fails the third; extending the excusal to an identity field fails the second.

**Removal condition.** When the level-load-span strand makes the engine's own
load duration observable, delete the excusal and restore the row stamp to a
hard error in every span.

---

## Hardware-Timing Replay Input Exception

**Location:** Dedicated hardware-timing fixture stream and the bounded
hardware-timing replay port described by
`docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md`.
**Scope:** Readiness timing for production-submitted ROM-backed hardware jobs
only.

### Accepted Boundary

Physics CSV and auxiliary events remain comparison-only. They cannot drive the
engine. A separate hardware-timing input stream may release the observable
readiness of a job only when the engine independently submitted and prepared
the same job and its kind, ordinal, stable submission fingerprint, and service
boundary match the recording.

The live v5 contract grants this authority to `KOS_MODULE_QUEUE`,
`KOS_DECOMPRESSION_QUEUE` and `NEMESIS_PLC_QUEUE` whenever the dedicated
`hardware_timing.jsonl` stream is present. `NEMESIS_PLC_QUEUE` carries only
S1's `RunPLC` FIFO-head *arming* edge; every pattern the armed entry
decompresses is still produced natively by the production PLC pipeline. The stream has one complete registry; policy is never
inferred from which kinds happen to have rows. Direct Kosinski edges can
release only a prepared FIFO head at `pre_main_loop`.
Schema-2 direct edges can release only a prepared head that the shared
four-entry production FIFO submitted, and only at `pre_main_loop`. KosM child
streams are real direct submissions with their own direct ordinals; the trace
cannot create or suppress them.

This is a narrow external-timing input, analogous to replaying the time at
which emulated hardware completed work. It is not permission to copy rings,
positions, routines, object state, queue descriptors, archive addresses, or
any other gameplay/work payload from the fixture. The timing port cannot call
gameplay owners or create missing work. A mismatch fails structurally.

### Rationale

Lag rows already reproduce work whose only observable effect is a missed main
loop. Some hardware queues remain pending while normal main-loop code continues
and polls a completion gate. Host execution time cannot represent Mega Drive
completion timing, while a fully cycle-accurate machine is outside the engine's
scope. The dedicated stream supplies only that otherwise-external completion
edge; the ROM-modeled consumer still owns every downstream mutation.

An absent timing file means no recorded timing port and leaves the production
scheduler live. A present empty file is an explicit v5 recorded stream with
the complete registry and no edges. Legacy schema-1/schema-2 fixtures and
their metadata selectors are not supported runtime inputs.

### Coverage: implemented for S1 and S3K, fixtures S3K-only

Three separate questions; do not collapse them.

**Contract scope is cross-game.** Recorded timing *may* delay S1 Nemesis PLC,
S2 DPLC, and S3K Kosinski readiness.

**Implementation covers S1 and S3K.** The live registry is
`KOS_MODULE_QUEUE`, `KOS_DECOMPRESSION_QUEUE`, and `NEMESIS_PLC_QUEUE`
(`HardwareWorkKind`), pinned by
`TestS1S2PlcComparisonOnlyGuard.timingKindRegistryIsClosedToUndeclaredWork`.
On the engine side `Sonic1PlcArmTiming` submits the S1 `RunPLC` arming edge and
`Sonic1PlcService.ownsTimedLoopTailArm()` gates on `isRecordedAuthority()`. On
the recorder side `HardwareTimingEventEngine` is constructed by
`S3KCompleteRunCaptureRunner`, `S3KTraceCaptureRunner`, `S1TraceCaptureRunner`
and `S1RunCaptureRunner`; `S1PlcHardwareTimingObserver` supplies the S1 edges,
`StagedRunSegmentSink` defines the run-mode sink, and
`CommandLineOptions.S1ConditionalTraceOutputFileNames` publishes the S1 stream
only when the capture observed an edge. **S2 is not implemented at either end**
— no source under `game/sonic2/` references `HardwareWorkKind`, and no S2
recorder constructs `HardwareTimingEventEngine`.

**Fixture coverage is S3K-only.** Every committed `hardware_timing.jsonl` under
`src/test/resources/traces` is beneath `s3k/` (272, plus 2
`hardware_timing_interstitial.jsonl`). No committed S1 or S2 fixture carries
one.

So the S1 path is **implemented but dormant**, which is not the same as absent.
Recorded admission is installed only by
`GameplayModeContext.activateRecordedHardwareAdmission()`; with no stream every
kind stays at `HardwareReadinessAdmissionPolicy.LIVE`,
`isRecordedAuthority()` is false, and the arm is released by the boundary that
prepared it — the pre-timing-port behaviour. An S1 trace divergence today must
be reasoned about against the native service model, because no committed
fixture can supply an edge.

"Re-record it with the hardware-timing stream" is now available for S1 and
still unavailable for S2. It is not the first resort for S1 in any case: a
divergence that looks like elapsed hardware cost is usually a counted ROM wait
loop in the wrong place (see the `plc-system` skill's S1 `segment_start - 26`
load-pair invariant).

### Historical pre-v5 evidence (not live)

Earlier schema-1/module-only and schema-2/direct-queue descriptions below this
point document the migration evidence that led to v5. They are retained for
audit provenance only and must not be implemented as compatibility branches.

### Guard and Removal Conditions

Keep this entry while authoritative hardware-timing input exists. Guard tests
must prove that only matching prepared jobs can be released and that
physics/aux data has no path into the timing port. Remove the exception if all
eligible hardware queues become sufficiently cycle-accurate that recorded
completion edges are no longer needed.

---

## S3K Complete-Run Segment Start-Position Bootstrap Debt

**Location:** `TraceReplayBootstrap.isS3kCompleteRunSegment`,
`TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap`
**Scope:** Sonic 3 and Knuckles complete-run trace replay setup only.

### Current Boundary

S3K complete-run per-zone segments no longer seed frame-zero player, sidekick,
camera, object, or CPU state from trace rows. They drive and compare from trace
frame 0 with `ReplayStartState.DEFAULT`; `shouldSeedFrameZeroForTraceReplay`
and `shouldSeedReplayStartStateForTraceReplay` both remain false. The remaining
fixture dependency is narrower: replay setup applies the metadata start centre
coordinates once, then runs the same ground-snap/camera/event initialization
path used by ordinary trace fixtures.

### Rationale

These segments arm at a zone handoff or first controllable frame from a longer
complete-run movie, so the fixture metadata supplies the save-state entry
position for the segment. That is accepted frame-zero bootstrap debt, not
per-frame trace hydration, and it must not expand into copying recorded trace
rows or sidekick/camera state back into the engine.

### Known Consequence: Run-Level Progression Is Absent

Position is not the only run-level state a mid-run segment lacks. Chaos and
Super Emerald counts also start at zero, because the segment did not replay the
special stages that earned them. This is deliberate -- seeding them from the run
manifest's `emeralds_before` would be trace hydration under hard rule 4 -- but it
makes some ROM branches unreachable in a standalone segment replay.

`loc_6170A` (skdisasm/sonic3k.asm:128276-128293) sends a special-stage entry ring
to the 50-ring award at `loc_61794` (:128325-128333) when `Chaos_emerald_count`
is 7 **and** either `SK_alone_flag` is set, or `SSEntry_CheckLevel` says the
level is an S3-half one (:128433-128443), or the Super Emeralds are also
complete. Otherwise it runs the capture sequence at `loc_6173A` (:128295-128301),
which writes `move.b #$1C,anim(a1)`, `move.b #0,mapping_frame(a1)` and
`move.b #$53,object_control(a1)` -- the player is locked and frozen. The engine's
own branch (`Sonic3kSSEntryRingObjectInstance.awardsFiftyRingsInsteadOfCapture`)
is ROM-correct; only the emerald count differs.

**Measured instances, re-stamped at the ICZ segment-column balance-width fix.**
The earlier Tails-animation frontier that masked ICZ at 1983 (`tails_animation_id`
`0x0005` vs `0x0006`) is closed, so ICZ reports this boundary directly at 2336
again -- the citation that was stale at `2bab075aa` is accurate once more:

| class | errors | first error |
|---|---:|---|
| `TestS3kSonicTailsLbzSegmentTraceReplay` | 7174 | 958, `player_animation_id` `0x0002` vs `0x001C` |
| `TestS3kSonicTailsCnzSegmentTraceReplay` | 6300 | 5754, `player_animation_id` `0x0000` vs `0x001C` |
| `TestS3kSonicTailsIczSegmentTraceReplay` | 2860 | 2336, `player_animation_id` `0x000D` vs `0x001C` |

These are the three largest release-6 S3K reds and they are **one cause**, not three.
At each frontier exactly two fields differ -- `player_animation_id` and
`player_mapping_frame` -- with position, speed, sub-pixels, rings and camera all
matching, and no error group starts before it. On the *next* frame the ROM's
`rings` steps by exactly +50 (16 -> 66 in LBZ, 94 -> 144 in CNZ, 30 -> 80 in ICZ).
The recorded run held 7 emeralds from segment 15 onward, and CNZ (zone 3),
LBZ (zone 6) and ICZ (zone 5) are all S3-half, so the ROM awards while the replay
captures.

A temporary probe seeding 7 emeralds at bootstrap -- **not landed, and not
landable: seeding from the manifest is trace hydration under hard rule 4** --
moved LBZ to 5028 errors with every field at frame 958 matching except `rings`,
confirming the diagnosis positively rather than by elimination.

Behind this sits a **second, independent and still-open defect**: with emeralds
present, the 50-ring award publishes exactly one frame early (one-frame,
self-correcting `rings` spans at LBZ 958 and CNZ 5754). Whether that is an engine
phase error or the recorder sampling `rings` before the object pass is not
established; ordinary ring pickups show no such skew, which argues it is real. It
is only observable with emeralds seeded, so no committed test can pin it today.
Nothing was fitted to close it.

Such frontiers are closed by the ordered run chain, not by the standalone segment
harness.

### Verification

`TestTraceReplayStartPositionPolicy.s3kCompleteRunSegmentsDoNotSeedFrameZeroTraceState`
checks current complete-run fixtures use metadata start position only, keep an
unseeded replay start, and do not receive the S3K sidekick seed-row prelude.
`TestBuildToolingGuard.traceReplayLegacyExceptionsShouldBeDocumentedAndBounded`
keeps this release-debt entry present.

### Measured Scope: Every Continuation Segment, Not Only Emerald Counts

The consequence above is written for Chaos and Super Emerald counts, but it
applies to every quantity a mid-run segment inherits rather than earns. Measured
across the whole `-Ptrace-segments` profile (70 tests: 52 physics failures, 7
`unmatched recorded hardware completions`, 1 FIFO-full):

| group | first error at trace frame 0 | first error later |
|---|---:|---:|
| continuation segments | **29** | **0** |
| first-of-zone / other | 5 | 18 |

**Every continuation segment in the profile diverges at its first compared row,
and none diverges later.** The correlation is total, and the fields are the
carried run state a standalone segment cannot reconstruct:

| field | classes | | field | classes |
|---|---:|---|---|---:|
| `rings` | 12 | | `y_speed` | 2 |
| `tails_x` | 4 | | `x_sub` | 2 |
| `camera_y` | 4 | | `x_speed` | 1 |
| `tails_y` | 3 | | `tails_y_speed` | 1 |

The twelve `rings` cases are identical in shape — trace frame 0, engine `0`, ROM
carrying an accumulated count (8, 75, 77, 85, 96, 117, 126, 150, 156, 177, 180,
201). Ring count is the same class of state as the emerald counts named above:
run-level progression the segment never played. Seeding it — or the sidekick
position, camera, or player sub-pixel and speed columns — from `physics.csv` row
0 or from run-manifest metadata would be per-frame trace hydration under hard
rule 4, which this debt exists to refuse. The fixtures carry no such fields:
`aiz_3/metadata.json` supplies `start_x`/`start_y` and nothing else.

**So these 29 red classes are this debt showing up as failures, not defects.**
They must not be driven green by seeding entry state, and a report that counts
them as regressions is overstating. A mid-run segment's frame-0 divergence on a
carried field is expected until the Removal Condition below is met.

### Removal Condition

Replace per-segment metadata start positions with a native ROM-state handoff
model or regenerate complete-run fixtures so the replay can enter each segment
from the real preceding state without fixture-provided start coordinates.

---

## Frame-0 Trace Bootstrap Snapshot Coverage Debt

**Location:** `AbstractTraceReplayTest.captureEngineSnapshot`,
`TraceBinder.compareBootstrapFrame0`
**Scope:** Trace replay verification coverage only; not live gameplay.

### Current Boundary

The frame-0 bootstrap comparator is comparison-only: it never hydrates engine
state from `player_history_snapshot`, `cpu_state_snapshot`, or
`object_state_snapshot` events. However, the engine snapshot currently supplies
player history only. Sidekick CPU views and per-slot SST snapshots are left
empty because the needed live accessors are outside the release-review patch
scope. When a trace records those views and the engine cannot provide them, the
comparator emits bootstrap warnings rather than strict errors.

### Release Meaning

This is not a full sidekick/SST parity proof. A trace run with zero per-frame
errors but bootstrap warnings proves only the currently compared fields. Release
validation may treat warnings as blocking, but until engine-side sidekick CPU
and object-slot snapshot extraction is implemented, warning-free frame-0
sidekick/SST coverage is not guaranteed by this comparator.

### Removal Condition

Add native engine snapshot accessors for the sidekick CPU fields and cardinal
per-slot object SST fields, wire them into `AbstractTraceReplayTest`, then make
missing engine views for recorded native-prelude traces strict failures.

---

## Sonic 1 Embedded Runtime Data Ratchet

**Location:** `Sonic1ObjectArtProvider`, `Sonic1BossMappings`
**Scope:** Sonic 1 runtime data source debt.

### Current State

The runtime does not read gameplay asset bytes from `docs/` disassembly trees.
The former palette-cycle rows, conveyor waypoint and child spawner tables, GHZ
bridge bend tables, and the small `Map_Seesaw` / `Map_SSawBall` / `Map_Fan` /
`Map_Pylon` / `Map_Scen` / `Map_ExplodeItem` support-object mapping slice, the
LZ `Map_Jaws` / `Map_Burro` / `Map_Flap` / `Map_WFall` / `Map_Splash` tables,
the MZ/SLZ `Map_Fire` table, the MZ `Map_Bas` / `Map_Glass` / `Map_CStom` / `Map_Geyser` / `Map_LWall` tables, the LZ `Map_LConv` / `Map_Bub` tables, the GHZ `Map_Hel` / `Map_Swing_GHZ` tables, the SLZ `Map_Swing_SLZ` table, the SYZ
`Map_Bump` / `Map_Spring` / `Map_Roll` / `Map_Yad` tables, the GHZ `Map_Crab` / `Map_Moto` / `Map_Newt` / `Map_Buzz` / `Map_Missile` tables, the GHZ/SLZ `Map_Smash` table, the
LZ/SLZ/SBZ `Map_Orb` table, the LZ `Map_Harp` table, the MZ/SBZ `Map_Cat` table, the SBZ `Map_Hog` table, the SLZ/SBZ `Map_Bomb` table, the SBZ `Map_Flame` / `Map_Saw` / `Map_Elec` / `Map_ADoor` / `Map_Gird` /
`Map_Trap` / `Map_Spin` / `Map_Stomp` tables, the shared button `Map_But` table, the shared
animal `Map_Animal1` / `Map_Animal2` / `Map_Animal3` tables, the special-stage
result-card `Map_Got` / `Map_SSR` tables, the special-stage result emerald
`Map_SSRC` table, the Prison Capsule `Map_Pri` table, the Giant
Ring `Map_GRing` / Ring Flash `Map_Flash` tables, the GHZ giant ball
`Map_GBall` table, the SYZ/LZ spikeball chain `Map_SBall` / `Map_SBall2`
tables, the Big Spiked Ball `Map_BBall` table, the LZ Gargoyle `Map_Gar`
table, the LZ Block `Map_LBlock` table, the SYZ Boss Block `Map_BossBlock`
table, the SBZ rotating junction `Map_Jun` table, the SBZ Running Disc `Map_Disc` table, the LZ Breakable Pole
`Map_Pole` table, the MZ/LZ Push Block `Map_Push` table, the MZ Brick `Map_Brick` table,
the SYZ Spinning Light `Map_Light` table, the MZ Smashable Green Block
`Map_Smab` table, the MZ/SLZ/SBZ Collapsing Floor `Map_CFlo` table, the
MZ/SBZ Moving Block `Map_MBlock` table, the LZ Moving Block `Map_MBlockLZ`
table, the GHZ/SYZ/SLZ Basic Platform `Map_Plat_GHZ` / `Map_Plat_SYZ` /
`Map_Plat_SLZ` tables, the SLZ Elevator/Circling Platform/Staircase
`Map_Elev` / `Map_Circ` / `Map_Stair` tables, the unused small explosion
`Map_UnkExplode` table, the GHZ Collapsing Ledge `Map_Ledge` table, the MZ
large grassy platform `Map_LGrass` table, the SBZ
vanishing platform `Map_VanP` table, the SYZ/SLZ/LZ floating block and door
`Map_FBlock` table, the SBZ2
`Map_FFloor` table, the shared boss `Map_Eggman` / `Map_BossItems` tables, the SBZ2/FZ `Map_SEgg` table, the ending
`Map_ESon` / `Map_ECha` / `Map_ESth` tables, plus the Final Zone
`Map_EggCyl` / `Map_PLaunch` / `Map_Plasma` / `Map_FZLegs` / `Map_FZDamaged`
boss mapping slice, now load from the user-supplied ROM and their guard budgets
have been ratcheted down. The Sonic 1 object-provider and boss mapping budgets
are zero; remaining tile-word transformations use the shared
`SpriteMappingPieces` helper over ROM-loaded frames rather than provider-local
mapping literals.

### Release Boundary

New gameplay runtime asset data must still be ROM-backed.
`TestArchitecturalSourceGuard` locks the current zero-exception counts for
these files so this debt cannot reappear silently under the release branch.

### Removal Condition

Replace each embedded table with ROM-backed loaders or generated mappings
through the normal user-supplied ROM pipeline, then reduce or remove the
`sonic1EmbeddedRuntimeDataExceptionsStayDocumentedAndBounded` ratchet.

## Batch-2 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

`Sonic1MotobugSmokeInstance` is intentionally **not** captured/recreated across a
held-rewind boundary (no rewind codec; its `#recreate` and `#finalScalar` keys stay in
`src/test/resources/rewind/coverage-baseline.txt`). It is the Motobug exhaust puff:
an animation-only object with no collision and no player/score/terrain state that plays a
short smoke script then self-deletes. The parent `Sonic1MotobugBadnikInstance` already has
a rewind `#recreate` path and continuously re-emits a fresh puff within ~1 frame on
forward re-simulation, so a dropped in-flight puff is visually undetectable. An
`exactSpawnCodec` is also the wrong tool here because the captured `ObjectSpawn` does not
carry the puff's facing bit. This mirrors the AIZ2 transient-children precedent (and the
S3K `MgzEndBossDefeatDebrisChild` case in `docs/S3K_known-discrepancies.md`): capture is
only worthwhile when a dropped object would otherwise visibly re-emit and play forward; a
sub-lifetime cosmetic that re-emits in-frame does not qualify.

All other batch-2 S1 transient/relink children (`Sonic1BombFuseInstance`,
`Sonic1BombShrapnelInstance`, `Sonic1BuzzBomberMissileInstance`,
`Sonic1BuzzBomberMissileDissolveInstance`, `Sonic1CannonballInstance`,
`Sonic1CaterkillerBodyInstance`, `Sonic1CrabmeatProjectileInstance`,
`Sonic1NewtronMissileInstance`, `GHZBossWreckingBall`, `Sonic1SLZBossSpikeball`)
now have rewind codecs in `Sonic1ObjectRegistry` and are restored on a backward seek.
`SYZBossSpike` intentionally has no codec — see "Construction-Spawned Boss/Object Children"
below.

## Batch-3 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

`Sonic1SplashObjectInstance` (LZ water splash, object `0x08`) is intentionally **not**
captured/recreated across a held-rewind boundary (no rewind codec; its `#recreate` and
`#finalScalar` keys stay in `src/test/resources/rewind/coverage-baseline.txt`). It is a
short-lived, purely cosmetic water splash with no collision and no player/score/terrain
state: a 3-frame animation (~12 game ticks) that self-deletes, spawned on water
entry/exit. The water-entry/exit code path naturally re-emits it within ~1 frame on
forward re-simulation, so a dropped in-flight splash is visually undetectable. This
mirrors the AIZ2 transient-children precedent and the batch-2 `Sonic1MotobugSmokeInstance`
case above: capture is only worthwhile when a dropped object would otherwise visibly
re-emit and play forward; a sub-lifetime cosmetic that re-emits in-frame does not qualify.

All other batch-3 S1 objects that were previously dropped now have rewind codecs in
`Sonic1ObjectRegistry` and are restored on a backward seek: `FZCylinder`,
`FZPlasmaLauncher`, `FZPlasmaBall`, `Sonic1BossBlockInstance` (boss + fragment forms),
`Sonic1CollapsingFloorObjectInstance`, `Sonic1EggPrisonObjectInstance`,
`Sonic1ExplosionItemObjectInstance`, `Sonic1FloatingBlockObjectInstance`,
`Sonic1GrassFireObjectInstance`, `Sonic1LamppostTwirlInstance`,
`Sonic1MonitorPowerUpObjectInstance`, `Sonic1RingFlashObjectInstance`,
`Sonic1RingInstance` (collected/animating child rings),
`Sonic1SeesawBallObjectInstance`, `Sonic1SpikedBallChainObjectInstance`,
`Sonic1StomperDoorObjectInstance`, and `Sonic1TeleporterObjectInstance`.

## Batch-4 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

`CPZBossSmokePuff` (CPZ boss retreat smoke) is intentionally **not** captured/recreated
across a held-rewind boundary (no rewind codec; its `#recreate` key stays in
`src/test/resources/rewind/coverage-baseline.txt`). It is a purely cosmetic smoke effect
with no collision and no player/score/terrain state: it re-derives its X/Y from the live
boss every frame (`x = mainBoss.getX() - 0x28`, `y = mainBoss.getY() + 4`) and
self-destructs when the boss is destroyed. It is also currently dead code — nothing in
`src/main` or the tests ever constructs it (the CPZ boss only spawns Robotnik/Flame/Pump/
Container/Pipe), so it can never enter a rewind snapshot at runtime. This mirrors the AIZ2
transient-children precedent and the batch-2/3 cosmetic cases above. All other batch-4 S2
CPZ-boss components and hazards that were previously dropped now have rewind codecs in
`Sonic2ObjectRegistry` and are restored on a backward seek: `CPZBossContainer`,
`CPZBossContainerFloor`, `CPZBossFallingPart`, `CPZBossFlame`, `CPZBossGunk`,
`CPZBossPipe`, `CPZBossPipePump`, `CPZBossPump`, `CPZBossRobotnik`,
`LavaBubbleObjectInstance`, `MCZFallingDebrisInstance`, `BubbleObjectInstance`, and
`OOZBurnerFlameObjectInstance`.

## Batch-5 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

`Sonic1TryAgainEmeraldsObjectInstance` (S1 Object-8C "TRY AGAIN" chaos-emerald orbit
display) is intentionally **not** captured/recreated across a held-rewind boundary (no
rewind codec; its `#recreate` key stays in `src/test/resources/rewind/coverage-baseline.txt`).
It is a `GameMode.TRY_AGAIN_END` end-screen display object that is never instantiated in
production gameplay (no `new Sonic1TryAgainEmeraldsObjectInstance` outside its own file and
no registry/ObjectId binding; the live Try-Again screen is rendered by
`com.openggf.game.sonic1.credits.TryAgainEndManager`, which re-implements the emerald orbit
standalone). Rewind capture is gameplay-mode-scoped (`RewindRegistry`/`RewindController` live
on `GameplayModeContext`), so this object can never appear in a held-rewind snapshot and the
"dropped on restore -> vanishes" failure mode cannot occur. It also has no `ObjectSpawn`
(`super(null, "TryChaos")`) and derives all per-emerald state lazily from
`GameStateManager` emerald data, so `exactSpawnCodec` is structurally inapplicable. It holds
no player/score/terrain state. This mirrors the AIZ2 transient-children precedent and the
batch-2/3/4 cosmetic cases above. All other batch-5 S1 objects that were previously dropped
now have rewind codecs in `Sonic1ObjectRegistry` and are restored on a backward seek:
`Sonic1EndingEmeraldsObjectInstance`, `Sonic1EndingSonicObjectInstance`,
`Sonic1GlassReflectionInstance`, and `Sonic1ResultsScreenObjectInstance`.

## Batch-6 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

One batch-6 cosmetic transient child is intentionally **not** captured/recreated across
a held-rewind boundary (no rewind codec; its `#recreate` key stays in
`src/test/resources/rewind/coverage-baseline.txt`). It self-regenerates, holds no
player/score/terrain state, and is structurally awkward to codec because its only
constructor takes the live player rather than an `ObjectSpawn`. This mirrors the AIZ2
transient-children precedent and the batch-2/3/4/5 cosmetic cases above.

- `com.openggf.game.sonic2.objects.SuperSonicStarsObjectInstance` (S2 Super Sonic sparkle/trail,
  ROM Obj7E): every scalar field (`animActive`, `freezeFlag`, `mappingFrame`, `frameTimer`,
  `visible`, `snapX`, `snapY`) is re-derived each frame from the live player's speed and centre
  position, and a full 6-frame cycle re-emits continuously while `|gSpeed| >= 0x800`. Its only
  ctor is `(AbstractPlayableSprite player)` (`super(null, ...)`, no `ObjectSpawn`), so
  `exactSpawnCodec` cannot supply the arg; it is owned and re-spawned by
  `Sonic2SuperStateController` (not the power-up spawner), so a deferred player-bound codec
  would orphan the pending entry. Dropping it causes at most a brief cosmetic absence that
  naturally re-emits.

`com.openggf.level.objects.SplashObjectInstance` was originally classified here, but as of
2026-06-25 it restores through generic recreate with `facingLeft` encoded in `ObjectSpawn`
render flags and a transient renderer lookup from the focused player's dust controller.

All other batch-6 S2 objects that were previously dropped now have rewind codecs in
`Sonic2ObjectRegistry` and are restored on a backward seek: `RingPrizeObjectInstance` (CNZ
slot-machine ring prize), `SteamPuffObjectInstance` (MTZ steam puff), `SeesawBallObjectInstance`
(HTZ seesaw ball, parent-relink), and `CPZBossContainerExtend` (CPZ-boss container extend,
boss+container relink).

## Batch-7 Rewind: Transient Cosmetic Children Not Rewound (Re-emit In-Frame)

Several batch-7 cosmetic objects are intentionally **not** captured/recreated across a held-rewind
boundary (no rewind codec; their `#recreate` / `#finalScalar` keys stay in
`src/test/resources/rewind/coverage-baseline.txt`). This mirrors the AIZ2 transient-children
precedent and the batch-2/3/4/5/6 cosmetic cases above.

- `com.openggf.level.objects.BoxObjectInstance` (debug-box base class; renders only a coloured
  outline + crosshair, holds no player/score/terrain state): it is **never** registered as a
  factory in any `*ObjectRegistry` and is never spawned as its own concrete type in gameplay —
  all real instances are subclasses (checkpoints, springs, bridges, CNZ blocks, elevators, etc.),
  each with its own object ID and its own codec. Its baseline keys
  (`#recreate` + `#finalScalar#{b,g,halfHeight,halfWidth,highPriority,r}`) are a
  `RewindCoverageAnalyzer` static over-approximation of a base class that no live carry path can
  produce as itself, so it can never actually be dropped on a held rewind. Accept-drop-as-baseline
  rather than registering a production codec for an abstract-role base class with no spawn factory.
- `com.openggf.game.sonic1.objects.Sonic1TryAgainEggmanObjectInstance` (S1 TRY AGAIN / END ending
  Eggman, object 0x8B): the class is **never** instantiated in `src/main` — no `new`, no factory
  registration, no `spawnChild`. The live TRY AGAIN / END ending-screen Eggman state machine and
  rendering are fully reimplemented inline inside `com.openggf.game.sonic1.credits.TryAgainEndManager`,
  so the instance class is orphaned/dead code and never enters the rewindable dynamic-object list — it
  can never actually be dropped on a held rewind. Its baseline `#recreate` key is a
  `RewindCoverageAnalyzer` static over-approximation ("No probe-compatible constructor found"). It is
  not spawn-constructible anyway (super ctor passes `null` spawn, fixed screen-space coords, and it
  holds a live sibling `emeralds` ref + a renderer), so even if wired up it would need a sibling-relink
  codec, not an `exactSpawnCodec`. Accept-drop-as-baseline rather than adding a codec for dead code.

`com.openggf.level.objects.BreathingBubbleInstance` was originally classified here, but as of
2026-06-25 it restores through generic recreate. Its spawn now encodes the one-shot constructor
state needed for faithful reconstruction: facing direction, countdown number, S1/S2 art profile,
and rise velocity.

All other batch-7 objects now have rewind codecs and are restored on a backward seek:
`com.openggf.level.objects.boss.BossExplosionObjectInstance` (shared boss-defeat explosion,
registered per-game in `Sonic1ObjectRegistry`/`Sonic2ObjectRegistry`) and
`com.openggf.level.objects.SignpostSparkleObjectInstance` (shared S1+S2 signpost ring sparkle,
in `ObjectRewindDynamicCodecs.sharedCodecs()`; its non-final `worldX`/`worldY` are reapplied after
recreate). S3K's signpost sparkle (`S3kSignpostSparkleChild`) is already codec'd separately.

## Batch-inner1 Rewind: Inner-Class Children

Batch-inner1 S1 inner-class children now use generic recreate or parent-relink rewind coverage and
are restored on a backward seek:
`Sonic1JunctionObjectInstance$Sonic1JunctionChildInstance` (SBZ rotating-junction display child;
spawn-based generic recreate),
`Sonic1FalseFloorInstance$FalseFloorBlock` (SBZ2 boss collapsing-floor tile; un-finaled
`currentX`/`currentY`/`blockIndex`, re-registered into the master's `childBlocks`),
`Sonic1OrbinautBadnikInstance$OrbSpikeObjectInstance` (Orbinaut HURT satellite/projectile;
reflection-constructed, parent relinked).
`Sonic1ScrapEggmanInstance$ScrapEggmanButton` intentionally has no codec — see
"Construction-Spawned Boss/Object Children" below.

## Construction-Spawned Boss/Object Children: Adopted In Place At Exact State, No Codec

When a boss or object is a **placed/active object** (in the level spawn list), rewind restore
reconstructs it by calling `registry.create(spawn)` → constructor → `initializeBossState()`.
If that constructor/initializer also spawns permanent child objects, those children are
**adopted in place** by the restore: the reconstructed parent wires its back-references
(`childComponents`, named child fields) to the constructed children, and the step-4
dynamic-object reconciliation loop in `ObjectManager.restore()` then registers each one at its
captured slot and applies its **exact captured state** on top.

To make this work, `AbstractObjectInstance.spawnChild`/`spawnFreeChild` route child spawns
through `ObjectManager.registerRewindReconstructionChild(...)` (instead of `addDynamicObject*`)
whenever `ObjectConstructionContext.isRewindActiveRestore()` is true. The reconciliation loop
matches each captured `DynamicObjectEntry` to the pending reconstruction child of the same class
in first-in (spawn) order — construction order is deterministic and equals the capture order —
and adopts it, falling back to a codec recreate only for routine-spawned children that have no
construction counterpart. This gives **exact-state fidelity** for construction children with
`target == keyframe` (zero re-simulation), keeps the parent's child references valid (they point
at the very instances that get the captured state), and avoids the double-spawn a codec recreate
would cause.

**Principle:** Construction-spawned children do **not** need (and must not have) a
`DYNAMIC_REWIND_CODECS` entry — adoption restores them. Children spawned from
**update/attack routines** (no construction counterpart to adopt) need an explicit restore path,
preferably graph-tested `RewindRecreatable` generic recreate; use a codec only when the graph path
cannot model the runtime owner. Construction children remain absent from `DYNAMIC_REWIND_CODECS`; their `#recreate` keys stay in the coverage
baseline because the static `RewindCoverageAnalyzer` is codec-based and cannot see the adoption
path (it documents such no-codec-but-correctly-restored classes as acceptable `#recreate`
over-approximations). Verified by `TestBossChildNoDoubleSpawnParity` (count parity) and
`TestBossChildExactStateRewind` (exact non-init state + reference integrity).

**Affected children (no codec, construction-spawned, adopted in place at exact state):**

- `com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$ArticulatedChild`
  (×6 spawned in `initializeBossState()` → `spawnChildren()`; DEZ Death Egg Robot body parts)
- `com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$HeadChild`
  (×1 spawned in `initializeBossState()` → `spawnChildren()`; DEZ hittable head)
- `com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$JetChild`
  (×1 spawned in `initializeBossState()` → `spawnChildren()`; DEZ jet exhaust)
- `com.openggf.game.sonic1.objects.bosses.SYZBossSpike`
  (×1 spawned in `Sonic1SYZBossInstance.initializeBossState()` → `spawnSpikeChild()`)
- `com.openggf.game.sonic1.objects.bosses.Sonic1ScrapEggmanInstance$ScrapEggmanButton`
  (×1 spawned directly in `Sonic1ScrapEggmanInstance` constructor via `spawnDynamicObject()`)
- `com.openggf.game.sonic2.objects.bosses.EHZBossSpike`,
  `EHZBossWheel` (×3), `EHZBossGroundVehicle`, `EHZBossPropeller`, `EHZBossVehicleTop`
  (all spawned in `Sonic2EHZBossInstance.initializeBossState()` → `spawnChildComponents()`;
  7 construction children total. The Propeller is additionally reloaded from a routine
  (`reloadPropeller()` during the fly-off phase) but that is the same singleton child, so the
  construction instance is still adopted in place at exact state. All five codecs were removed
  in the EHZ pass; their `#recreate` keys are in the coverage baseline.)

**Construction children that were never codec'd (correct, verified, no change needed):**

- `Sonic2MTZBossInstance$MTZBossOrb` (×7) and `Sonic2MTZBossInstance$MTZLaserShooter` (×1) —
  both spawned in `initializeBossState()` (`spawnOrbs()` / direct). Neither has ever had a codec,
  so MTZ never double-spawned. `MTZBossLaser` is fired from a routine (`fireLaser()`) and KEEPS
  its codec. (MTZ is event-spawned with no registry factory, so it is not reconstructed via
  `registry.create()` during restore the way EHZ/DEZ are.)
- `Sonic2MechaSonicInstance$MechaSonicLEDWindow`, `$MechaSonicTargetingSensor`,
  `$MechaSonicDEZWindow` — all spawned in `initializeBossState()` → `spawnChildObjects()`.
  None has ever had a codec, so Mecha Sonic never double-spawned. `MechaSonicSpikeball` is
  routine-spawned (`fireSpikeballs()`).

The MTZ/Mecha cases are guarded statically by `TestBossChildNoDoubleSpawnParity`
(`mtzBossConstructionChildrenHaveNoCodecs`, `mechaSonicConstructionChildrenHaveNoCodecs`):
those construction children must never gain a codec.

**Routine-spawned children with explicit restore decisions (NOT construction):**

- `Sonic2DeathEggRobotInstance$BombChild` — spawned from attack routine `fireBombs()`; restores
  through graph-tested `RewindRecreatable` generic recreate with nearest live Death Egg Robot relink
- `Sonic2DeathEggRobotInstance$ForearmChild` — no codec (construction-spawned, same as above;
  plus `isFront` is a final field; baseline carries `#finalScalar#isFront` and `#recreate`)
- `Sonic2DeathEggRobotInstance$SensorChild` — no codec (spawned from update targeting routine;
  transient, re-emitted by parent; accepted as drop)
- All WFZ boss children (`WFZFloatingPlatform`, `WFZLaserWall`, `WFZPlatformHurt`) — spawned
  from `updateSpawnChildren()` (update routine `ROUTINE_SPAWN_CHILDREN`, not construction)

## V-Int Run Counter Does Not Tick On Pause Or Seamless-Boundary Lag Rows

The engine's `ObjectManager.vblaCounter` models the ROM V-int run counter --
`Vint_runcount` (`docs/s2disasm/s2.asm:508`), `v_vblank_count`
(`docs/s1disasm/sonic.asm:682`) and `V_int_run_count`
(`docs/skdisasm/sonic3k.asm:543`) -- which the ROM increments once at V-int
exit regardless of which V-int routine the mode jump table dispatched.

The engine holds the invariant **exactly one tick per serviced V-blank**: the
gameplay row ticks inside `ObjectManager.update(...)`, and every row where the
level loop did not run but the V-int was still serviced (lag skip, bonus-stage
lag, bonus-exit fade hold, title-card overlay, seamless-reload transition,
trace `VBLANK_ONLY` / `PLAYABLE_ANIMATION_ONLY`) calls
`ObjectManager.advanceVblaCounter()` exactly once. **No guard test enforces this.**
Earlier revisions of this note cited a `TestVblaCounterVBlankInvariant` pinning the
single mutation statement and the per-row tick counts; no such class exists anywhere
in `src/`, and the invariant is carried by the field javadoc alone. Anyone changing
tick ownership should write the guard rather than assume it caught a missed site.

Three row kinds diverge:

- **PAUSE rows** (deliberate). The ROM's pause loop still runs the V-int, so the
  ROM counter advances while paused; the engine's does not.
- **Seamless-boundary LAG rows** (deliberate). These service
  `LevelFrameStep.serviceVBlankOnly` without ticking the counter.
- **S1 and S2 title-card locked-phase rows** (deliberate, cross-game work
  pending). `Level_TtlCard` runs its scroll-in loop with a `WaitForVint` on
  every pass (`docs/s2disasm/s2.asm:4914-4916`; Sonic 1 has the identical
  property), so each of those passes is a real serviced V-blank and the ROM
  counter advances through the card. `Sonic2TitleCardManager` and
  `Sonic1TitleCardManager` both leave
  `shouldAdvanceVblankClockDuringLockedPhase()` at the `TitleCardProvider`
  default of `false`, so the engine takes the camera-only branch and does not
  tick. **`Sonic3kTitleCardManager` already returns `true`
  (`Sonic3kTitleCardManager.java:827`), so S3K models this correctly and S1/S2
  are the outliers** -- the correct end state is for all three to return `true`.
  This was surfaced, not introduced, by `bugfix/ai-s2-seg15-r1`: before that
  change S2 dispatched objects during the locked phase, which incidentally
  advanced the clock, and freezing the objects to match `ObjectsManager`'s ROM
  position at `s2.asm:5003` left the clock behaviour visible on its own. It is
  not fixed there because S1 has the identical gap, making this a cross-game
  change whose trace movement would be unattributable if folded into an
  object-lifetime fix. It is a plausible cause of the current
  `TestS2CompleteEmeraldRunChain` frontier at segment 15 frame 409.

- **Special-stage results-screen presentation rows** (modelled around, not
  fixed). A trace run's stage-exit presentation bridge plays every recorded row
  of `SS_Finish`'s fade-out and the results screen, but no level loop runs on
  any of them, so the production counter is frozen for the whole segment while
  the ROM's keeps ticking. This one is **not** unobservable: measured on the
  `s1-sonic-complete-withemeralds` route, the engine reached GHZ3 act 2 with a
  1,587-tick deficit, which is 3 mod 8 and 19 mod 32 and so de-phased both the
  prison capsule's `Pri_Animals` spawn gate and `Anml_ChkFloor`'s `btst #4`
  escape-direction flip. `TraceRunVblankClock` now seeds the bridge from the
  level that entered the stage and derives the bridge's own tail from
  `TracePlaybackProfile.stageResultsEntryNonAdvancingMovieRows` (S1: the seven
  V-ints `SS_Finish` builds the results screen through with interrupts
  disabled, docs/s1disasm/sonic.asm:3369-3383), so every gameplay segment after
  a bridge is back on the recorded clock. The bridge's own rows remain frozen;
  nothing observes the counter there.

The first two are unobservable against the currently recorded traces.
Closing either divergence is a phase change, not a refactor: the counter is the
`vblaCounter` argument handed to every object instance each frame, so a one-tick
shift moves spilled-ring floor-probe cadence in all three games, the S3K slot
bonus-stage RNG seed, LBZ/MGZ/ICZ object phasing, and every rewind snapshot's
stored scalar. Any such change needs a full S1/S2/S3K trace fleet plus rewind
determinism measurement of its own.

---

## S2 Whole-Run V-int Clock Cannot Be Made Exact

`TestS2CompleteEmeraldRunChain`'s final physics axis is blocked on this, after eleven
rounds of investigation. Recording it so the next attempt starts from the evidence
rather than repeating the sequence.

### Original Implementation

`Vint_runcount` is free-running and absolute: `VintRet: addq.l #1,(Vint_runcount).w`
(docs/s2disasm/s2.asm:508) is unconditional and every mode path reaches it. ROM objects
gate on its parity and modulo — `Obj4B_ChkPlayers` `btst #0` (s2.asm:60989-60998,
period 2, "target Sidekick on uneven frames"), `Obj2C_Leaf` `& $1F`
(s2.asm:52200-52208, period 32), and `Obj28_Main` `btst #4` for animal bounce
direction (s2.asm:24660-24665, verified against the recording on all 31 floor-contact
events).

A tick is lost only inside an interrupts-off window, and only when that window spans
**two or more** V-blanks: `disable_ints` masks the CPU but not the VDP, which holds its
interrupt asserted until acknowledged, so a single V-int raised inside a window is
still taken the moment `enable_ints` runs.

### Our Implementation

**S2 applies no clock alignment at all.** `TracePlaybackProfile` defines exactly two
constants (`TracePlaybackProfile.java:18-21`): `DISABLED` and `SONIC_1`. There is no
`SONIC_2`, `Sonic2GameModule` does not override `getTracePlaybackProfile()`
(`Sonic2GameModule.java:81`), and the `GameModule` default returns `DISABLED`
(`GameModule.java:334-335`). The whole S2 package contains zero references to the type.
S3K is in the same position.

Every alignment path is gated on that profile and therefore never runs for S2:
`alignsInterLevelVblank()` is `interLevelNonAdvancingMovieRows >= 0` → false, so
`TraceRunVblankClock.levelDestinationTarget` returns empty and
`AbstractRunChainTest.completeInterLevelVblankBudget` returns immediately;
`alignsStageResultsPresentationVblank()` and `alignUncomparedInteriorReturnVblank` are
likewise false; every `ifPresent(objects::initVblaCounter)` in `TraceSessionLauncher`
receives an empty Optional. The S2 object clock is seeded once at bootstrap and
free-runs — **no seam mask, no 9, no 11, no interior alignment.**

The chain never drives uncompared special-stage interiors, so the engine advances its
object-visible counter by a uniform **78 ticks** (measured live, all five interiors
reached before the walk stops: `ss`, `ss_2`, `ss_3`, `ss_4`, `ss_5` — each exactly 78)
across an interior the ROM spends 5,800–8,500 V-blanks in. S2 special-stage rows never
tick the clock: `TraceRunSpecialStageRows.S2Rows.admission` hardcodes
`advancePreservedVblankIfUnchanged = false` (`TraceRunSpecialStageRows.java:205-211`) and
no level loop runs on those rows, so `advanceVblaCounter()` is never called. The 78 is
entirely boundary choreography, and it is uniform because the choreography is. The deficit
accumulates to roughly 33,500 by the run's final segment.

### Rationale

Two of the three inputs are derivable and were derived. The S2 special-stage return
masks 11 rows (10 for the level-entry block at s2.asm:4766-4770, whose two Nemesis
streams are 94 and 92 tiles by their 0x805E/0x805C headers; 1 for the results block at
s2.asm:6751-6761, which carries no Nemesis stream). S1's interior return masks **0** —
`GM_Special` has one interrupts-off block (s1disasm/sonic.asm:3231-3239) that runs
`disable_display` before `ClearScreen`, about a quarter of a frame.

Two are not.

- **The ordinary level seam is not derivable, and the obvious alternative is refuted.**
  Measured across the **19** `level_advance` boundaries of the complete-emerald run, it
  masks 9 rows at 18 of them and 8 at **OOZ1→OOZ2**. (The halfpipe chain contributes no
  `level_advance` boundaries at all — its four transitions are starpost_special and
  stage_exit — so an earlier "21 of 22" in this entry was wrong.)

  The tempting hypothesis was that an act advance within a zone takes a structurally
  different ROM path from a full zone load, making "act-advance masks 8, full-load masks
  9" a derivable code-path predicate rather than phase variance. **Measured and refuted:**
  the run has 9 act advances and 10 full loads, and 8 of the 9 act advances mask 9 exactly
  like every full load. The classes do not separate; the single deviation is *inside* the
  act-advance class. Do not re-run this hypothesis.

  Positive evidence for the phase reading: the recorded `vblank_counter` is not
  one-per-row even *inside* segments — seg15_cnz2 and seg27_wfz1 both contain internal
  row-to-row deltas of 0 and 2. The OOZ outlier is the counter gaining one extra tick
  across a gap, the same shape as those mid-segment slips. A fixed ROM window whose
  whole-V-blank count varies with its sub-frame opening phase cannot be modelled at
  frame granularity.
- **A 32-tick loss was recorded here against `ss_3`. It is not in the recording.**
  Re-measured directly from the fixture, **all seven special-stage crossings reconcile at
  exactly 11** — the derived value (10 for the level-entry block at `s2.asm:4766-4770`
  plus 1 for the results block at `s2.asm:6751-6761`). There is no `ss_3` outlier and no
  unattributed internal loss:

  | interior | crossing | ss rows | movie rows | vbl delta | deficit |
  |---|---|---|---|---|---|
  | `ss`   | seg1_ehz1 → seg2_ehz1   | 5681 | 5856 | 5845 | 11 |
  | `ss_2` | seg2_ehz1 → seg3_ehz1   | 6361 | 6536 | 6525 | 11 |
  | `ss_3` | seg3_ehz1 → seg4_ehz1   | 7092 | 7267 | 7256 | 11 |
  | `ss_4` | seg5_ehz2 → seg6_ehz2   | 7224 | 7398 | 7387 | 11 |
  | `ss_5` | seg6_ehz2 → seg7_ehz2   | 6690 | 6864 | 6853 | 11 |
  | `ss_6` | seg9_cpz2 → seg10_cpz2  | 8310 | 8510 | 8499 | 11 |
  | `ss_7` | seg11_arz1 → seg12_arz1 | 8498 | 8672 | 8661 | 11 |

  The interiors differ wildly on every event axis available — 5681 to 8498 rows, 41 to 60
  segment changes, 55 to 140 rings-to-go transitions, 6 to 14 orientation changes — while
  the deficit stays pinned at 11. The deficit is therefore **independent of every
  measurable event axis**, which also disposes of the "does 32 factor as a per-event
  constant times an event delta" hypothesis: there is no per-interior variation for an
  event count to be proportional to. Do not re-run that test either.

  If a 32-tick discrepancy is real it is **engine-side**, not a property of the ROM or the
  recording, and this entry previously mis-attributed it. No prior note recording how the
  32 was measured exists anywhere in `docs/` or `src/`. Whoever picks this up should start
  by re-deriving it against the engine rather than trusting the number.

### Retracted: there is no cancellation mechanism

Earlier revisions of this entry described the baseline as surviving by "cancellation, not
correctness" — an odd deficit at one special-stage crossing cancelling an odd deficit at
an act seam, leaving whole-run parity correct by luck. **That mechanism is wrong and is
retracted.** Three independent reasons, any one sufficient:

1. It reasons about deficits relative to a uniform-9 *engine* seam model. S2 has no such
   model — the profile is `DISABLED` and no seam-alignment code runs. The 9/8 split is a
   property of the **recording only**; nothing in the S2 engine path consumes it.
2. Its per-interior constant was 79. Measured, it is **78** — and the parity of that
   constant was the entire argument. 78 is even, which inverts the odd/even split the
   section described.
3. Its prediction is empirically false. Engine clock at segment start against that
   segment's recorded first-row `vblank_counter`:

   | segment | engine | recorded | delta | parity |
   |---|---|---|---|---|
   | seg1_ehz1 | 553 | 554 | −1 | in phase |
   | seg2_ehz1 | 4342 | 10108 | −5766 | **inverted** |
   | seg3_ehz1 | 7797 | 20009 | −12212 | **inverted** |
   | seg4_ehz1 | 11835 | 31224 | −19389 | in phase |
   | seg5_ehz2 | 13200 | 32673 | −19473 | in phase |
   | seg6_ehz2 | 19325 | 46105 | −26780 | **inverted** |
   | seg7_ehz2 | 23197 | 56751 | −33554 | **inverted** |

   Parity is inverted in **four of the seven** level segments reached, and it flips back
   and forth rather than inverting once and cancelling once. Segments 2 and 3 are
   parity-inverted **and compare clean**.

**Parity inversion does not by itself produce compared divergence.** `Obj4B` is
implemented and genuinely parity-gated — `BuzzerBadnikInstance.selectTargetPlayer`
(`BuzzerBadnikInstance.java:166-172`) is a faithful port of the ROM's
`btst #0,(Vint_runcount+3).w` (`s2.asm:60989-60998`; `+3` is the low byte of the
longword, so this is the parity of the full counter). But `Obj4B` is the Buzzer, an EHZ
badnik (`s2.asm:29991`, body at `:60850`), and its parity branch sits behind
`Obj4B_shooting_flag` and a subsequent narrow x-window test. The gate is reached rarely
and usually picks a player on the same side, so it is not a sensitive detector of clock
phase.

### Decision: `DISABLED` is retained deliberately

Nobody chose `DISABLED` for S2 — it is an inherited default. It is retained, but the
justification below is **narrower than an earlier revision of this entry claimed**:

- **Parity (mod 2) phase is invisible.** Engine-vs-recorded parity inverts four times
  across the seven level segments reached, and those segments compare clean. The only
  implemented parity-gated object, `Obj4B`, is ported faithfully but is a poor detector
  (shooting flag plus a narrow x-window).
- **Mod-8 phase is NOT invisible — the tripwire below has fired.** An earlier revision
  said "the comparison set does not consume clock phase." That is **wrong**. The Egg
  Prison random-animal spawn gate (`loc_3F3A8`, `s2.asm:84935-84942`,
  `move.b (Vint_runcount+3).w,d0 / andi.b #7,d0 / bne`) consumes mod-8 phase directly.
  The engine's counter is 6 rows (−2 mod 8) early at segment entry, which fires one extra
  random spawn, consumes an extra RNG draw pair, and shifts every later animal's position,
  type and travel direction. That is the entire 287-error segment-11 divergence.

**The fix is not a `SONIC_2` profile.** The mis-phase is *inherited at segment entry* from
an under-replayed transition gap, with no drift within the segment. The engine is faithful
everywhere it was checked — the deletion predicate already models the ROM BuildSprites box
(commit `f9da6563e`), and per-animal lifetimes match the recording (engine 96–144 rows mean
~119; ROM 95–144 mean ~119). What is wrong is how many movie rows the harness consumes
crossing the gap; see the gap-accounting note below.

Building a `SONIC_2` profile now would be speculative machinery validated against nothing
— the shape of work the fitted-constant rule exists to prevent. S1's profile is
legitimate because its alignment is derived from the ROM's own frame accounting.

**Tripwire status: FIRED, and it resolved to gap accounting, not to a clock model.** The
end-of-act PLC divergence turned out to be exactly the modulo-gated case this tripwire was
written for — but the owner is the harness, not the engine. The engine's object clock is
correct *given* the rows it was advanced; it was advanced too few.

### Gap accounting — the actual defect

The harness under-replays uncompared transition gaps, and this is now the dominant
remaining cause across multiple independent axes:

| boundary | recorded rows | engine rows |
|---|---|---|
| EHZ1→EHZ2 act seam | 172 | **78** |
| special-stage interior | 5,845–8,661 | **78** |

At the act seam every transfer id, edge ordinal, owner, submission origin, request set,
`gap_edge_index` and **both** ledger fingerprints match the recording — only
`movie_logical_frame` diverges. The art pipeline is faithful and emits correct edges
stamped from a short cursor.

**Seam length is data-driven and cannot be a constant.** Recorded `level_advance` seam
lengths range 158–200 rows, with within-zone spread ≤3 across acts but between-zone spread
up to 42, correlating with the destination zone's PLC1 entry count at Spearman 0.886. The
ROM predicts exactly that act-independence: `LevelArtPointers` gives both acts of a zone
the same PLC1 (`s2.asm:89135-89151`) and `Level:` selects it by `Current_Zone`, not by act
(`s2.asm:4783-4795`). A single global constant would be off by up to 42 rows.

Note the engine's *decompression* is correct — a live probe shows `remain` decrementing by
exactly 6 per frame, matching `move.w #6,(Plc_FramePatternsLeft).w` (`s2.asm:2203`), and it
processes the ROM's own `PlrList_Ehz1`+`PlrList_Std2` payload in 52 rows. `Level_TtlCard`
(`s2.asm:4914-4924`) exits only when the slide has finished **and** `tst.l (Plc_Buffer).w`
is empty, so seam length is `max(slide, backlog)`; for EHZ the backlog is 52 and the slide
dominates. The missing rows are in slide/leave choreography, not the queue.

**The defect is seam ORDER, not gap length.** Measured directly: the gap length is already
correct — for the level→level `level_advance` gap the recording spans 171 rows and the
engine drives 170, because `destinationReady`
(`TraceRunPlaybackCoordinator.java:396-399`) refuses admission until the shared cursor
reaches the destination's `bk2_frame_offset`. What differs is the composition:

| | order inside the gap |
|---|---|
| **engine** | `TITLE_CARD` for 78 rows, then 92 rows idling in `LEVEL` |
| **ROM** | fade → interrupts-off `ClearScreen`/`LoadTitleCard` → `Level_ClrRam` → level art decompression (**~93 rows of pre-card work**) → *then* `Level_TtlCard` |

So the engine's title-card art edges land **93 rows early** — exactly the `delta=93` seen on
every failing `run_gap.edge[N].movie_logical_frame`.

**The 78 is not invented and must not be "fixed".** Both halves are already ROM-derived and
cited in place: 52 rows of genuine PLC drain at `move.w #6,(Plc_FramePatternsLeft).w`
(`s2.asm:2202-2213`) over `PlrList_Ehz1` + `PlrList_Std2` with the ROM's per-entry
`ceil(patterns/6)` quantisation, and 26 rows of `LEAVE_*_PASSES` in
`TitleCardManager.java:78-84`, each cited to the leave loop at `s2.asm:5060-5066`.

**A retracted theory, recorded so it is not retried.** An earlier revision claimed the
169–199 variance came from the results bonus tally counting down at a fixed rate. It cannot:
the tally runs inside `Level_MainLoop` while `Game_Mode` is still `$0C`, and the capture
finalises a segment at the first `$8C` frame (set by `Level:`'s first instruction) and
re-arms at the first `$0C` (cleared at `Level_StartGame`, `s2.asm:5084`). The variance is
payload-dependent **un-timed load cost** — the same class already documented for S1 at
`TraceRunPlaybackCoordinator.java:382-395` (34–40 rows there).

**Fixing it needs a frame-costed level load, which does not exist.** The engine runs all 20
level-init steps synchronously in one loop (`LevelManager.java:385-394`) and `InitStep` is
`record(String, String, Runnable)` with no frame-cost concept, so the title card necessarily
begins at gap row 0. ROM ordering requires a suspendable cross-frame level load shared by all
three games, every test, the editor and level select.

That is deliberately **not built**. Full diagnosis, the ruled-out shortcuts, a smaller
sidecar-based variant worth one feasibility pass, and the priority argument are in
[docs/architecture/designs/2026-08-13-level-entry-seam-frame-costing.md](../architecture/designs/2026-08-13-level-entry-seam-frame-costing.md).
Do not attempt a 22-row pre-card hold: the remaining ~70 rows are payload-dependent, so any
fixed boundary offset is a fitted constant, and it would disturb S1's currently-green gap
edges, which absorb 34–40 un-timed rows as padding
(`TraceRunPlaybackCoordinator.java:382-395`).

### The fingerprint is real; its explanation was not

> **122,139 errors at segment 7, frame 524, field `sidekick_y` (rom=0x0271
> engine=0x0272).**

This has been produced **five times under four different descriptions** — boolean-only
alignment, a `-1`-only budget, a `-1-11` budget, and the no-`-1` masked form. The "fix
both seams or neither" advice attached to it in earlier revisions followed from the
retracted mechanism and should be ignored.

**Explained: it is the signature of a correct number applied by the wrong mechanism.**
Every one of the five attempts ultimately bumped the V-int counter by the gap's true
movie-row count. That makes the counter say ~190 frames passed while every other piece of
engine state says 78 — ring timers, animation phases, and critically the sidekick's
position-record buffer. The Tails CPU has counter-gated logic sitting immediately next to
that buffer, so the object runs on **two different clocks at once**, which is a
deterministic wreck rather than a random one. Same number, same mechanism, same failure —
which is exactly why five differently-described attempts produced an identical error count
and first-error location. The fingerprint was never mysterious; it is the machinery's
signature.

This also dissolves the apparent contradiction with the parity finding above. The counter
value is inert **to what the clean segments happen to contain** — it was never a general
claim. Segment 7's sidekick is counter-sensitive.

**Do not "fix" this by enabling the budget machinery.**
`TraceRunReplayWalker.interLevelVblankBudget` (`:731-746`) already computes the correct
movie-row count, and every call site is gated on the profile that returns `DISABLED` for
S2. Enabling it is a one-line change whose arithmetic is right and whose mechanism is the
one described above. Being one already-tested line makes it more dangerous, not less.

Consequences visible today: leaf particles are mis-phased in every S2 run (period 32
against a deficit of 6 mod 32), and the emerald run's end-of-act PLC submission lands 28
rows early because a differently-chosen animal survives last.

**What the next agent should actually know:** S2 has no clock model, not a delicate one.
Before treating any of this as a constraint, decide whether S2 *should* carry a
`TracePlaybackProfile` at all — the S1 machinery exists and is inert here, which is a
very different starting position from "the model is fitted and fragile".

### Routes considered and rejected, so the wall is not re-derived

- **Write down the measured deficit.** Excluded: a fitted model. It would go green here
  and desync the first differently-timed recording — the failure this project's rule 3
  exists to prevent.
- **Model the level-entry window at 68000 cycle level** to predict the 9-or-8 split.
  Rule-compliant and would also predict the art-volume spread seen elsewhere, but
  frame-exactness needs plane draws, VDP wait states and V-blank interleaving — a
  partial cycle emulator. Impractical, not forbidden.
- **The rule-4 hardware-timing sidecar.** Does not apply: it may only delay readiness of
  engine-submitted art jobs, and nothing here polls a queue.

What would actually close it: per-row lag data for the undriven interiors, which is a
recorder and fixture change rather than a trace fix.

### Verification

`TestS2EhzHalfpipeRoundTripChain` is fully green and unaffected. Emerald segments 0–10
report zero errors and player physics matches the recording on every row of the run.
Segment 11 carries 287 errors, all of them the Nemesis PLC queue, downstream of this.

## 28. S2 Run-Gap Art Submission Rows Are Sub-Frame CPU Position

**Status:** intentional limitation. Not closable at frame granularity by any rule-compliant
route, including fixture regeneration.

### Symptom

`TestS2CompleteEmeraldRunChain` axis 4: `ss_4 -> seg6_ehz2` reports
`run_gap.edge[0]`/`[1]`.`movie_logical_frame` expected 46347, actual 46348 — the engine one
row late. Aligned by transfer id these are tids 28084/28085, the *submitted* pair; the
matching *completed* pair at 46349 matches.

### Original Implementation

The ROM submits the returning level's player art at the leading `jsr (RunObjects)`
(`s2.asm:5007`), where Obj01/Obj02 display runs the DPLC. Between that call and the leave
loop's first `bsr.w WaitForVint` (`:5060-5061`) it executes **only straight-line 68000 code**
— `BuildSprites` (`:5008`), `AniArt_Load` (`:5009`), `SetLevelEndType` (`:5010`), demo-script
setup (`:5011-5048`), `PalLoad_Water_ForFade` (`:5049-5054`), the leave-flag writes
(`:5056-5058`) — with no `WaitForVint` and no polled readiness gate.

**Re-verified 2026-08-14 against the recorder-fiction hypothesis, and it holds.** After a
sibling frontier turned out to be recorder fiction — the ROM discarding queued work via an
unguarded `clr.w (VDP_Command_Buffer).w` while the recorder's ledger reported it completed
— this span was re-examined for the same pattern, since it was the model that framing came
from. It is not the same:

- **No ledger-invalidating write lies inside the span.** Every write that can zero
  `VDP_Command_Buffer` is upstream of the submission: `:4857-4858` sits in Level init's VDP
  setup ~150 lines *before* `:5007`; the annotated `SS_Shared_RAM` overshoot (`:6599`) and
  the results-tail pair (`:6759-6760`) are both thousands of rows earlier; `:10766` and
  `:11737` are not on this path. (`:6609` and `:10342` are inside `if fixBugs` and so never
  execute at all — see the manifest's guard table.)
- **The transfer is performed, not discarded.** In the fixture's own ledger, transfers
  28084/28085 submit at row 46347 and complete at 46349, and the *next* two transfers
  (28086, 28087) submit and complete on consecutive rows. A queue that drains next-row on
  the following transfers is live, not stranded — the opposite of the results-tail
  signature, where one transfer stayed outstanding across all 22 fade rows and was then
  discarded. The recorded completion at `ProcessDMAQueue` is explained by the ROM's own
  control flow: `Vint_TitleCard` calls it at `:1046`, raised by the very `WaitForVint` at
  `:5061` that terminates this span.

So the ±1 row is genuine elapsed 68000 time — how far before the span's end the submission
falls is a pure cycle count with no frame-granularity observable. This entry stands as
written. **Caveat recorded for whoever regenerates fixtures:** the recorder's
`OnProcessDmaQueue` drains its ledger unconditionally and never reads `VDP_Command_Buffer`,
so it *is* capable of manufacturing lifecycles at the four live reset sites. It simply is
not doing so here, because the precondition — a zeroing write between submission and drain
— is absent.

The number of masked V-int frames between the DPLC write and the leave loop's first V-int is
therefore a **pure 68000 cycle count**, dominated by `BuildSprites`/`AniArt_Load`, whose cost
depends on the object set `ObjectsManager` loaded at the entry position (`:5000`).

### Our Implementation

The harness releases held player art on `lastNonAdmittedRow(census)` — the end of the masked
load-span run. Measured across **all 27 censused transitions** of the complete-emerald run,
that anchor coincides with the ROM on 20, is one row late on 4 (`ss_4`, `ss_5`,
`seg15_cnz2→seg16_htz1`, `seg27_wfz1→seg28_dez1`), and is 8–47 rows out on 6 more.

**Nothing zone-, act-, route- or path-dependent distinguishes them.**
`seg4_ehz1 → seg5_ehz2` and `ss_4 → seg6_ehz2` share a destination act and have opposite
tails; so do `seg15_cnz2 → seg16_htz1` and `seg16_htz1 → seg17_htz2`.

### Why it cannot be closed

- **A tuned offset** is a fitted constant, forbidden by rule 3, and would be wrong on the
  20 seams the anchor currently gets right.
- **Recording the submission row** and consuming it round-trips the answer: the recorded
  quantity would determine the very field the comparison checks, so the test would prove
  nothing. This is why the authorised fixture regeneration cannot close it.
- **Cycle-level modelling** of `BuildSprites`/`AniArt_Load` would close it and is
  rule-compliant, but needs a partial 68000 cycle emulator — assessed as impractical.

### Verification

Axis 4 is left reported rather than excused. Excusing `movie_logical_frame` here would
suppress the evidence that located the cause, and would mirror the S1 precedent
(`TestS1GhzMazeRoundTripChain`:29-70) that this session found is probably papering over the
same class of defect.

### Related, and latent

`ss_6` and `ss_7` are **not passing — they are unreached**: the chain aborts around segment 11
and never compares them, hiding anchor errors of 8–47 rows.

A ROM-derived anchor improvement exists and is **not landed**: the leave loop performs exactly
one `WaitForVint` per pass (`:5060-5061`), so a lag run of length ≥ 2 cannot be inside it,
making *"the last lag run of length ≥ 2"* a derived anchor rather than a fitted threshold. It
would correct the 8–47-row cases but changes nothing at `ss_4` and nothing on any seam the
test currently reaches, so it is unverifiable today. See
[the design note](../architecture/designs/2026-08-13-level-entry-seam-frame-costing.md).

## Object-slot occupancy is not compared, and diverges from ROM in all three games

**Retitled 2026-08-21.** This entry was headed *"S3K object-slot occupancy…"*. Arming
`SlotOccupancyProbe` across the full `-Ptrace-replay` sweep shows the defect is **not
S3K-specific**: on presence/absence alone, S1 diverges on 45 of 48 fixtures and S2 on 33 of
36, alongside S3K's 10 of 10 — 88 of 94 fixtures and 40.1% of 29,832 compared frames. The
S3K-specific material below stands; its scope does not. Measurements and the divergence
breakdown: the 2026-08-21 occupancy entry in
[trace-frontier-log.md](trace-frontier-log.md).

**Do not wire occupancy in as a comparison yet** — it would red 88 fixtures across three games
to report an already-known fact. Fix the dominant "engine has fewer occupants" bucket (39% of
divergent frames) first, re-measure with the probe, and land the comparison when the red set is
reviewable.


**Measured 2026-08-15.** `AbstractTraceReplayTest.compareObjectNearEvents()` defaults to `false`
and `TestS3kHczCompleteRunTraceReplay` does not override it; `SlotOccupancyProbe` is
`OGGF_SLOT_PROBE`-gated off. **So that test compares no object identity, slot or position at
all** — despite its fixture carrying 394,205 `object_state`, 342,381 `object_near` and 2,388
`slot_dump` events.

With the probe armed, engine-vs-ROM occupancy diverges on **2387 of 2387 sampled frames**.

**Corrected 2026-08-15 (same day): the magnitude first published here overstated by ~3x.** The
60,274 figure counted a comparison artefact. S3K keeps a **32-bit ROM code pointer** in the first
SST long, not an id byte — `Process_Sprites` does `move.l (a0),d0 / movea.l d0,a1 / jsr (a1)`
(`sonic3k.asm:35985-35988`) — so the recorder's `slot_dump` carries values like `"0x0002D95C"`.
Both `SlotOccupancyProbe.parseId` and the **committed** `TraceBinder.compareObjectNear`
(via `parseHexByte`) truncate that to its low byte and compare it against the engine's *layout*
object id. Different number spaces; the tell is that every "ROM id" printed is even, because
addresses are. That accounts for **40,755 of the 60,274 entries (67.6%)**. The genuine
presence/absence divergence is **19,519 entries across 2025 frames**. The 2387/2387 frame headline
survives; the magnitude does not.

**Consequence: enabling `compareObjectNearEvents()` for an S3K class today would not measure
occupancy at all** — it would compare an engine object id against the low byte of a ROM code
address. S1's `object_near` carries a real one-byte `"type":"0x25"`, which is why the S1
complete-run classes legitimately enable it. Fixing the identity mapping is the prerequisite for
any occupancy comparison work.

**Amended 2026-08-15: there is no identity mapping to fix, and truncation is not the real
defect.** The follow-up audit
[*S3K trace object identity: the object pointer tables cannot supply it*](../architecture/audits/2026-08-15-s3k-object-code-pointer-identity.md)
read both S3K object pointer tables out of the ROM — `Sprite_Listing3 = 0x00094EA2` (256 entries)
and `Sprite_ListingK = 0x000952A2` (185), both taken from the `203C` immediates the object loader
itself uses at ROM `0x01B6A8` / `0x01B6C4` (`sonic3k.asm:37411-37430`) — and inverted them.
**Only 7 of the 189 distinct code pointers in the HCZ `slot_dump` stream are table entries at
all: 2,428 of 56,993 entries, 4.26%** (2.6% across the full event stream). The S3K SST has no id
field at any offset (`sonic3k.constants.asm:9,20`), and objects overwrite their own dispatch
pointer with internal sub-routine addresses to advance state — **1,758 `move.l #<label>,(a0)`
sites in `sonic3k.asm`** — so the recorded value is a live program counter, not a type at any
width. Containment (nearest preceding table entry) is not a sound repair: it maps `0x00085AD2`
into `Obj_HiddenMonitor`'s `0x2BCE` gap when the previous audit's co-occurrence map called it
`Obj_Poindexter` (`$98 → 0x0008827C`), and it produces `+0x2520`-and-larger offsets that no
single object body spans.

**Consequences.** The 19,519 genuine presence/absence divergence and the 2387/2387 frame headline
stand, unchanged — they never consulted `type`. The **41.3/23.3/23.8/11.6
permutation-vs-population mixture is withdrawn**, not refined: it rested on the inferred map, and
the ROM supplies a sound replacement for 4.26% of entries and nothing for the rest, so that split
is **not currently measurable**. `compareObjectNearEvents()` must stay off for S3K for a firmer
reason than before — the field carries no object identity at any width. The comparison S3K could
support is *occupancy alone* (presence/absence and slot index over `Dynamic_object_RAM`, slots
4-90, no identity), which is already sound today; identity would instead require annotating every
ported S3K object with its current ROM routine address.

**The complete-run has been green for its entire life while its object graph disagreed with the
ROM everywhere.** Its green is evidence about player physics, not about object layout.

**Why this is not cosmetic.** Several ROM behaviours are keyed on an object's own SST slot index.
`Obj_Bouncing_Ring` gates its floor probe on it —
`move.b (V_int_run_count+3).w,d0 / add.b d7,d0 / andi.b #7,d0 / bne`
(`docs/skdisasm/sonic3k.asm:35629-35632`), where `d7` is `Process_Sprites`' live slot countdown.
A ring occupying a different slot therefore bounces on different frames. A measured instance:
after the HCZ boss hurt both trees scatter 32 rings at pixel-identical positions, but ring 0 lands
in slot 38 on one tree and slot 4 on the other (34 apart, two apart in the `&7` cycle), so it is
collected four frames early and prints as a `rings` mismatch 27,600 frames after the change that
moved the layout.

**Consequence for reading results.** A green S3K complete-run does not imply the object graph
matches. It implies the compared fields match. Occupancy should be brought into the compared
surface before slot-sensitive behaviour is judged by these tests.

**Corrected 2026-08-15 by
[the occupancy scoping audit](../architecture/audits/2026-08-15-s3k-object-slot-occupancy-scoping.md).**
The 2387/2387 frame figure holds, but **60,274 overstates the real divergence about threefold**.
S3K stores a 32-bit code pointer in the first long of an SST slot, not an id byte
(`docs/skdisasm/sonic3k.asm:35985-35988`), so the recorder's `slot_dump` and `object_near` carry
addresses such as `"0x0001365C"`. Both `SlotOccupancyProbe.parseId` and `TraceBinder`'s
`parseHexByte` truncate that to its low byte and compare it against the engine's layout object id.
**40,755 of the 60,274 entries (67.6%) are that artefact**, not a divergence; the genuine
presence/absence divergence is 19,519 entries on 2025 frames. S1's `object_near` `type` is a real
one-byte id, which is why the three S1 classes can enable the comparison — **an S3K class must not
enable `compareObjectNearEvents()` until the identity key is fixed**, or the resulting red is
uninterpretable. With identity resolved the divergence is a near-even mixture of permutation
(23.3% of sampled instances) and population difference (23.8% short, 11.6% excess); the ROM's
`AllocateObject` search order is modelled correctly and is **not** the cause.

## HCZ complete-run is deliberately red: a slot-phase consequence of a ROM-correct fix

**Since 2026-08-15.** `TestS3kHczCompleteRunTraceReplay` fails with exactly two errors —
`rings` 1 vs 2 at frame 29095 and 0 vs 1 at 29262. This is **expected and documented**, not an
unhandled regression, and the class carries the same marker in its own javadoc.

Commit `b31069c3f` corrected two ROM-cited MegaChopper defects (the `Obj_WaitOffscreen` gate and
the `EnemyDefeated` bounce the `Touch_Special` path owes the player). At frame 1481 the badnik
then sits at x=**3840** where it previously sat at 3839 — and this run's own aux records
`object_x 0x0F00` = 3840, so **the fix is the ROM-correct side and the previous behaviour was
wrong.**

That one pixel changes object-slot occupancy on 16,289 of 31,482 rows. 27,600 frames later the
boss-hurt ring scatter places ring 0 in slot 4 rather than 38, and `Obj_Bouncing_Ring` gates its
floor probe on its own SST slot (`move.b (V_int_run_count+3).w,d0 / add.b d7,d0 / andi.b #7,d0 /
bne`, `sonic3k.asm:35629-35632`, `d7` being `Process_Sprites`' live slot countdown). Those slots
are two apart in the `&7` cycle, so the ring bounces on different frames and is collected four
frames early.

**The previous green was accidental.** Occupancy diverges from ROM on 2387 of 2387 sampled frames
on *both* trees, and this test compares no object identity, slot or position — so the old green
was a slot-phase lottery that happened to land compatible phases, not a baseline that the fix
broke.

**Removal condition:** slot-occupancy parity. That is currently unmeasurable from the recorded
stream — see the SST no-identity finding above — so it needs a different recording surface before
it can be worked. **Do not close this by adjusting ring timing, slot allocation or the bounce.**

## 30. S3K standalone Super-Emerald special stages cannot choose their layout set

Seven Sonic+Tails special-stage segments (`ss_8`..`ss_14`) diverge at frame ~135 with
large positional error (e.g. expected `x=5376`, actual `512`). The engine loads the
**Chaos** layout set for stages the ROM ran with the **Super** set.

### The ROM

`sub_85B0` masks the index -- `andi.w #7,d0` / `move.b d0,(Current_special_stage).w`
(`docs/skdisasm/sonic3k.asm:10858`) -- so **the stage index is 0-7 in both halves**. The
layout set is chosen entirely by `SK_special_stage_flag` at `loc_85E4` (`:10824-10830`):
set means `a2 = SSLayoutOffs_RAM` with `d3 = Super_emerald_count`, clear means
`SStageLayoutPtrs` with `d3 = Chaos_emerald_count`, and the `adda.l #LockOnROM_Start,a2`
S3-half rebase (`:10863-10868`) applies only when it is clear.

Consequence: **`special_stage_index` is not a segment identity.** Index 0 names both `ss`
(Chaos) and `ss_8` (Super). Seven test-class javadocs claimed the directory ordinal as the
index (`ss_8` = "index 7" and so on); all seven were wrong and have been corrected.

### The engine's invented index space

`Sonic3kSpecialStageManager` branches on `currentStage < 8` for layouts and
`superEmeraldMode || currentStage >= 8` for palettes (`:511-514`), an **0-15 index space
the ROM does not have**. It is reachable only from `debugToggleLayoutSet()` (`:1444`),
which initializes stage 8 and lets `debugNextStage()` cycle 8-15. Only the emerald-art
archive site (`:1035`) consumes the flag cleanly.

### Why the obvious predicate is also wrong

`superEmeraldMode` = `hasAllEmeralds() && !hasAllSuperEmeralds()` is a proxy for the normal
route and is **not ROM-equivalent**:

- the special-stage demo path sets `move.b #1,(SK_special_stage_flag).w` and then
  `clr.w (Emerald_counts).w` (`sonic3k.asm:5702-5706`) -- flag set, counts zero;
- level select picks the flag independently (`:10102`).

So an unseen demo or level-select recording defeats the inference. Replacing the invented
index space with this proxy would swap one non-ROM predicate for another.

### What is blocked, and the decision required

No committed fixture records the flag. The recorder samples only `$FFB0` (Chaos count,
`tools/tracechaser/bizhawk-headless/src/Recording/S3KRam.cs:247`); SS `aux_state.jsonl.gz` is
byte-empty by design (`S3KCompleteRunSegmenter.cs:24`). Note `run_manifest.json` **does**
carry `emeralds_before`/`emeralds_after` -- an earlier claim here that it did not was
wrong -- but that is the Chaos count, not the flag.

Deriving the set from frame-0 position/spheres/rings, or by trying both layouts and keeping
the match, is a trace oracle. Treating `ss_8`+ or the segment ordinal as "Super" is a route
predicate that fails the any-BK2 bar.

**Escalation, awaiting a decision that is not the engine's to make:**

1. Recorder exports `sk_special_stage_flag` (`u8`, 0 or 1, ROM RAM `$FFBB`, sampled at the
   special-stage arm beside `Current_special_stage` `$FE16`) into every S3K special-stage
   `metadata.json` and the matching `run_manifest.json` entry. This makes the fixture
   self-describing. **It does not by itself authorise feeding the value into gameplay.**
2. Then one of:
   - **bless** `{special_stage_index, sk_special_stage_flag}` as immutable segment-entry
     identity -- analogous to choosing which test case to initialise, not to hydrating
     state; or
   - keep it **comparison-only**, accept that standalone Super-stage segments stay red, and
     close them through continuous run-chain or predecessor replay instead.

Reconstructing the selector natively instead would need `chaos_emerald_count` (`$FFB0`),
`super_emerald_count` (`$FFB1`), the seven-byte `collected_emeralds_array`
(`$FFB2..$FFB8`) -- necessary because the selector inspects individual entries when
skipping collected stages (`sonic3k.asm:10841`) -- and optionally `emeralds_converted_flag`
(`$FFBA`).

### Landable today, independently

Removing the 0-15 index space is verifiable **without any fixture**: keep `currentStage`
strictly 0-7, represent the layout set as an explicit flag on the production stage-entry
request, consume it at every site (layout, palette, emerald art, award inventory, rewind,
debug), make `debugToggleLayoutSet()` toggle the flag rather than the stage number, and
test `(stage 0, flag 0)` against `(stage 0, flag 1)` for distinct ROM-backed layout data.
The ROM-faithful owner of the flag is the entry object -- `Obj_HPZSuperEmerald` writes it
at `sonic3k.asm:197730` -- which is not implemented (registry name only).

---

## S2 Push-Release Animation Restart Fires Once Extra At A Contact-End Frame

**Status:** open, deliberately not fixed. **Cost:** 58 comparator errors in the
CPZ2 standalone segment, and one red class if the push-gate pair is landed.

### Original Implementation

`SolidObject_TestClearPush` gates its `move.w #(Walk<<8)|Run,anim(a1)` restart
write on `btst d4,status(a0)` -- the object's own pushing bit
(`docs/s2disasm/s2.asm:35462-35466`). `SolidObject_LeftRight` branches to
`SolidObject_SideAir` **before** the `bset d4,status(a0)` (`:35413-35461`), so an
object's pushing bit is only ever set for a grounded character, and the airborne
side path returns through `Solid_NotPushing` without reaching the write.

### Our Implementation

The engine licenses that write on `sprite.getPushing()` -- the player's global
flag -- and gates its player-side clear on a per-instance latch. Landing the
ROM-faithful pair (ungated player clear plus object-bit gate) plus the MGZ
spiked-platform site leaves exactly **one** extra Walk/Run write over the S2
prefix chain, 10 against the ROM's 9, and `TestS2CompleteEmeraldRunPrefix` red on
a shifted `dynamic_art` edge ordinal.

### Rationale

The cause has been **excluded from three places and localised to none**:

- **Entry classification.** The engine reaches the same entries the ROM does:
  all 224 object-bit sets fire grounded, and six monitors take the airborne
  `SideAir` clear as the ROM does.
- **The write-site predicate.** The ROM itself performs the write while airborne
  (f377, f3408 in `seg5_ehz2`, out of box with the bit set), so an
  airborne-at-the-write-site suppression would suppress the case the ROM
  performs. Verified by reconstructing `SolidObject_cont`'s box arithmetic
  (`:35344-35369`) from the object's own `d1`/`d2` and the recorded positions --
  a reconstruction that validates itself, since `d0` is exactly `0` on every
  pushing frame, which is `SolidObject_AtEdge`'s `sub.w d0,x_pos(a1)`.
- **Object population.** The engine pushes exactly the same seven monitor
  placements the ROM does, coordinate for coordinate, across the nine segments
  the prefix chain replays. An earlier "three ROM slots against eleven engine
  instances" was a counting error -- different denominator (one segment against
  the chain) and different unit (ROM slots against Java identities, which the
  chain re-creates per segment reload).

What remains is a single contact-end frame where the engine reports no contact
and the ROM reports a side collision. Every candidate fix lives in collision code
shared by all three games, so the blast radius is every solid contact in Sonic 1,
2 and 3&K -- against a 58-error win in one segment. Excluding is not localising,
and acting on three exclusions rather than one localisation is how a narrow
symptom acquires a wide fix.

The push-gate pair is therefore **not landed**, and this entry exists so the next
attempt starts from the three exclusions rather than repeating them.

### Verification

`(1)+(2)+MGZ site` measures 790/5 against a 790/4 control at the same base with
`TestS2CompleteEmeraldRunPrefix` the only new red; the CPZ2 standalone segment
improves 2491 -> 2433 and every `player_mapping_frame` divergence in it closes;
the three complete-emerald chains are red in control and candidate alike with
identical failure modes; and none of the ten regressions retired by the
object-side port audit have returned.

---

## S2 CPZ Boss Truncates The Object Pass (`fixBugs = 0`)

**Status:** reproduced (closed). Kept as the citation record for a `fixBugs` site.

### Original Implementation

`Obj5D_Pipe_Retract_ChkID`, on the shipped `fixBugs = 0` path, carries the
disassembly authors' own warning:

```
; 'd7' should not be used here. This causes the 'RunObjects' routine to
; either run too few objects or too many objects, causing all sorts of errors.
    moveq   #0,d7
    move.b  #ObjID_CPZBoss,d7
    cmp.b   id(a1),d7
```

`ObjID_CPZBoss` is `$5D`, so the boss **writes `$5D` into `RunObjects`' live loop
counter**. The iteration's `dbf` takes it to `$5C`, giving 92 further iterations
from slot 30 and ending the pass at slot 122 — six slots short of
`LevelOnly_Object_RAM` at `$FFD000`. `Tails_Tails`, `SuperSonicStars` and
`Sonic_BreathingBubbles` are therefore all skipped for as long as the boss holds
routine `$08`. Measured: `d7` goes `0072` -> `005C` after slot 29 on every
affected row, 123 iterations instead of 144.

The `fixBugs = 1` path uses `cmpi.b #ObjID_CPZBoss,id(a1)` and leaves `d7` alone.
**The engine models `fixBugs = 0`**, so the truncation is the accurate behaviour.

### Our Implementation

`CPZBossPipe.updateRetract()` (`docs/s2disasm/s2.asm:62244-62253` cited inline) now
calls `ObjectManager.overrideRemainingObjectLoopSlots(...)` with the boss's own object
id while the retract search runs, so the object pass ends where the ROM's does. The
value is `ObjID_CPZBoss` read out of the disassembly, not a tuned duration, and the
write stops once `Obj5D_flag` is set, exactly as the ROM's entry test at
`docs/s2disasm/s2.asm:62220-62221` skips the search on later frames.

### Rationale

An earlier revision recorded this as "deliberately not reproduced" because it required
a mutable pass budget in the dispatch loop shared by all three games. That budget now
exists as `overrideRemainingObjectLoopSlots`; see the `CHANGELOG.0.6.md` entry "The CPZ
act 2 boss's retracting pipe now cuts the object pass short, as the shipped ROM does"
for the measured effect (Tails' tails no longer animate DPLC frames the ROM never queues).

### Verification

Probe `tools/bizhawk/probes/s2_cpz2_d7_clobber_probe.lua` over rows 5535-5600
logs `d7` per `RunObject` iteration; rows 5554-5564 show 123 iterations ending at
`$FFCE80`, their neighbours 144 ending at `$FFD3C0`. The inline `FixBugs` comment in
`CPZBossPipe.updateRetract()` is the ported-site marker required by the project rule.

## S2 tornado (ObjB2): recorded single-instance SST rows are exact

**Status:** all five divergence spans in rows where the comparator identifies one
Tornado are resolved. Comparison landed at WARNING on 2026-08-21, the two SCZ
spans resolved on 2026-08-23, and the three WFZ spans resolved on the 2026-08-25
candidate over `bad36d0b2`.

`aux_state.jsonl` carries ObjB2's SST (`s2_tornado_state`) on every row of the SCZ and WFZ
fixtures. It was parsed as an untyped generic event and never compared. Comparing it exposed
five pre-existing divergence spans across 24,056 rows. The three formerly open WFZ spans were:

| fixture | field | rows | ROM | engine |
|---|---|---|---|---|
| `wfz` | `tornado.status_byte` | 0–196 | `0x08` | `0x00` |
| `wfz` | `tornado.routine` | 10419 only | `0x00` | `0x06` |
| `wfz` | `tornado.objoff_2f` | 13378–13440 | `0x01` | `0x00` |

Unlike the CNZ slot block these were discrete and individually nameable rather than a cascade.
The SCZ targets were resolved by
initialising the native expired vertical timer sentinel and by preserving the ROM's
stationary airborne release path through `Move_below_player`; the focused SCZ replay now
reports zero errors and zero warnings.

- **`objoff_31` at level start.** The ROM's vertical-move countdown is decremented with
  `subq.b #1` / `bpl` (`s2.asm:79388`), so its expired resting value is `-1` = `0xFF`. The
  engine now exposes that sentinel from the native ride-start prelude. This closes the
  SCZ 0–198 span.
- **`status_byte` at WFZ level start.** ObjB2's inline `SolidObject` call now retains the
  returned standing state in the object-owned status latch. This follows
  `RideObject_SetRide` setting `p1_standing` and `SolidObject` clearing it on release
  (`s2.asm:35014-35044, 35986-36045`).
- **`routine` at WFZ row 10419.** A newly allocated ObjB2 snapshot now exposes routine 0
  until its pending `ObjB2_Init` update installs the subtype-derived routine
  (`s2.asm:78777-78813`).
- **`objoff_2f` at WFZ rows 13378-13440.** The WFZ-end snapshot now encodes the low byte of
  the word incremented by `ObjB2_Wait_Leader_position`, then the jump countdown installed
  by `ObjB2_Prepare_to_jump` (`s2.asm:78972-78979, 79042-79052`). The Java model already
  owned those values as `leaderWaitCounter` and `jumpTimer`; no trace value supplies them.

The resolved SCZ `x` span at row 7103 was caused by the engine treating an airborne,
stationary player as still riding a different solid while ObjB2 performed its pre-contact
`Move_with_player` read. The fix is state-based: non-zero horizontal launch velocity keeps
the live-object path, while the stationary airborne release seeds and consumes ObjB2's
decaying horizontal relation. It does not inspect trace rows, frame numbers, or zone names.

**The routine-aware encoding.** The SCZ scratch bytes are re-encoded from the engine's
semantic fields using ROM idioms read off the **SCZ** arm:
`objoff_2E` is the `p1_standing` transition (`status(a0)` saved, then
`(saved ^ current) & p1_standing`, `s2.asm:78827, 78834-78839`), and `objoff_2F`/`objoff_30`
are `st.b`/`clr.b` flags, hence `0xFF`/`0x00` (`s2.asm:79382-79383, 79390`). The WFZ-end arm
instead owns `objoff_2E` as a word, so `objoff_2F` is its low byte. The mapping is therefore
per routine, matching the ROM's reuse of the SST scratch region.

**Coverage boundary.** `TraceBinder` compares `s2_tornado_state` only while exactly one live
Tornado instance can be identified by content. Once WFZ-end creates its Tornado children,
the comparison stops instead of inventing a ROM-slot-to-engine-slot mapping. The committed
WFZ fixture therefore proves the recorded single-instance rows through the `$0040` leader
wait, but does not prove the parent plane's state-8-and-later `$38`/`$28` decrement and
landing-reset tail. Focused ObjB2 tests cover those ROM-owned transitions directly; the
trace comparator remains deliberately unchanged in this patch.

`y_sub` is exact rather than truncating: the ROM's sub-pixel word has a zero low byte on all
7629 SCZ rows, so the engine's 8-bit sub-pixel is a faithful model of it.

**Removal condition.** Give the parent Tornado an exact identity through the child-presence
tail without slot fitting, compare and close the state-8-and-later rows, then promote the
Tornado-only warning helper to `Severity.ERROR`.

## S2 Compressed-Music Load Timing Omits Sub-Frame Bus Contention

**Location:** `Sonic2SaxmanLoadReadiness`, `SmpsDriverSession`

**ROM reference:** `docs/s2disasm/s2.sounddriver.asm` `zBGMLoad`,
`zSaxmanDec`, `zInitMusicPlayback`, and `zUpdateMusic`

Sonic 2 decompresses compressed music on the Z80 inside its interrupt service,
so no music update runs until the decompressor and post-load initialization
return. OpenGGF derives that readiness from the actual compressed ROM stream
and the shipped unoptimised Z80 instruction path. For EHZ this is 363,255
T-states on NTSC: the model predicts the six fully masked rows observed by the
native driver oracle without using a movie row, song id, or fitted frame count.

The model intentionally omits the sub-frame perturbation from exact 68K
BUSREQ phase, VDP-DMA contention on bank-window reads, and YM2612 busy-poll
retries. Those effects require a shared cycle scheduler that OpenGGF does not
have. Their bounded residual does not cross EHZ's frame-granularity readiness
boundary, but another compressed stream sufficiently close to a boundary may
become ready one presentation earlier or later than hardware.

Remove this entry when the engine models the shared 68K/Z80/VDP/YM bus phase
closely enough for compressed-load readiness to include those stalls directly.

## S2 stopMusic Bypasses the Mailbox While a Compressed Load Blocks It

**Location:** `AudioManager.playMusic`/`stopMusic`, `AudioPresentationProducer`,
`Sonic2SoundRequestService`

`AudioManager.playMusic` enters Sonic 2's real mailbox
(`Sonic2SoundRequestService`, modelling `PlayMusic` at `s2.asm:1520-1528`), but
`stopMusic` still enqueues directly into the plain presentation command queue.
While a compressed load blocks mailbox consumption
(`SmpsDriverSession#blocksForwardRequestConsumption`), the producer holds both
queues shut and, on release, applies held plain commands before held mailbox
requests — so a play and a stop issued in the same blocked span are not
issue-ordered. A faithful fix routes the stop through the mailbox as the
shipped `MusID_Stop` command (`CmdPtr_Stop` → `zStopSoundAndMusic`,
`s2.sounddriver.asm:1598,2545`), but that command shares `Music1` with the
`fixBugs = 0` slot-3 `VoiceTblPtr` alias bug (the unfixed four-iteration SFX
scan reads `SoundQueue.SFX0+3`, which is `Music1`'s struct offset,
`s2.asm:1270-1332`, `s2.constants.asm:1879-1885`) — on real hardware a second
same-frame mailbox write can itself be silently lost this way, so the fix must
accept that loss rather than paper over it. Deferred; not fixed by this entry.

## YM2612 Output Scale, Resting Level and DAC Presentation

`Ym2612Chip` is a facade over the Nuked-OPN2 port (`audio.synth.nuked`), which is
clocked one internal cycle at a time and leaves time-multiplexed pin values on
MOL/MOR. The facade's output sample is the sum of the 24 pin values of a frame
(the RC-averaged pin of the real part), shifted left by 3.

### Original Implementation

The discrete YM2612's analogue output is what it is; the previous engine core
(a Genesis Plus GX derivative) presented one FM channel at a nominal full scale
of 8191 and rested, when silenced, at +768 at chip scale (+384 after the
mixer's `MASTER_GAIN_SHIFT`), a resting level that was inherent to that model of
the discrete DAC.

### Our Implementation

- **Scale.** In the 24-cycle pin sum a full-scale channel contributes
  `3 * 256 = 768` in both output stages (`chOutput`, `ym3438.c:947`). The facade
  applies one shift (`<< 3`), giving 6144 per channel: the largest power-of-two
  scaling that keeps six full-scale channels inside 16 bits after the master
  gain. Relative to the previous core's 8191 the FM level is about 2.5 dB lower;
  the mixer's PSG preamp restores the previous FM:PSG balance (see "FM:PSG Mix
  Balance Is Pre-Rewrite Parity, Not a Hardware Calibration"). No other gain is
  applied in the chip.
- **Resting level.** In YM2612 mode a silent channel's four pin cycles each carry
  the amplified sign of a non-negative output (+3), so a silenced chip rests at
  `72 << 3 = 576` per side at chip scale, +288 LSB after the master gain
  (previously +384). Types 1 and 2 (YM3438 output stage) rest at 0. Muted
  channels keep this resting contribution, as a keyed-off channel does on
  silicon. The offset is the model's own and is not adjusted.
- **DAC cadence.** `playDac` streams PCM as `0x2A` writes every
  `(baseCycles + 26 * (rate - 1)) / 2` Z80 cycles, from the Z80 playback loops
  (`s1disasm sound/z80.asm zPlayPCMLoop`, `s2disasm s2.sounddriver.asm
  zWriteToDAC`, `skdisasm Sound/Z80 Sound Driver.asm zPlayDigitalAudio`): the
  fixed instruction path per byte plus the taken `djnz` iterations. The two
  halves of each byte are not equally long on the Z80 (S1: 124 and 177 cycles
  before the pitch loop) but `DacData` carries only the per-byte total, so the
  facade spaces the two samples evenly; the Z80 loop also stalls while the
  chip bus is busy with sequencer writes, which the facade models by pausing
  the cadence rather than dropping samples. The S2 loader now uses the retail
  loop's 295-cycle byte budget; ROM-backed coverage pins both that value and
  the resulting rate-1 and kick-rate-23 cadence inputs. S1 and S3K retain their
  separately owned constants.
- **DAC interpolation** (`audio.dacInterpolate`, default off since
  2026-09-02) is a presentation option with no hardware counterpart: between
  two PCM samples the facade writes a linearly interpolated `0x2A` value once
  per output frame, only when the chip bus is idle and the real Z80 cadence
  cannot reach its next sample before the synthetic write completes; otherwise
  the frame's interpolation is dropped. It never pauses the sample cadence (an
  earlier implementation did, stretching samples ~1.7x). The previous core's
  engine-side DAC high-pass option was removed with the switch-over.

### Rationale

The facade must not add constants to reproduce the previous core's numbers:
the port is a derivative of one pinned source and its output is pinned
sample-for-sample against the C build (`TestYm2612ChipNukedParity`). The scale
shift is the single explicit adjustment the port contract permits.

### Verification

`TestYm2612ChipNukedParity` (68 scripts against the C harness under
`tools/audio/nuked-opn2/harness/`), `TestYm2612ChipSnapshot`,
`TestChipWriteObserver`, `TestSfxAdmissionMutationJournal`.

---

## S3K Music DAC Byte Stream Partition (Oracle Comparison)

The S3K driver oracle compares the music DAC byte stream over the whole capture
window rather than per service window, and does not assert how far a DAC run
got before it was cut short, nor whether a run ended by exhausting rather than
being superseded. The `2Bh = 80h` enable and everything else in a tick stay
strictly partitioned and compared; the sample-end `2Bh = 0` disable moved into
the DAC byte stream with the bytes.

### Original Implementation

`zPlayDigitalAudio` streams every decoded sample byte to `2Ah` for the whole
of the interval between two V-ints (Sound/Z80 Sound Driver.asm:4296-4351),
so the number of bytes that land in a given service window is whatever the
Z80 had time for after that frame's `zUpdateEverything` returned.

### Engine Behaviour

The engine models the driver's semantics, not the Z80's instruction timing.
`Ym2612Chip` streams the same bytes at the same per-byte cadence, derived from
`DacData.baseCycles` and the sample's rate byte, but it has no cost for the
service itself, so it spends the whole interval streaming.

### Why

The split is a property of a CPU this engine does not emulate, and measuring
it from a fixture would be a fitted model. Two measurements settle it. The
aggregate cadence is right: every contiguous run of `2Ah` writes in the
reference sums to a ROM sample's own decoded sample count (1,438 for
`DAC_86`, 3,836 for `DAC_81`, 9,294 for `DAC_88`), and pairing each tick's
`zDACIndex` with that sample's rate byte leaves a residual per frame that is
consistent across fifteen samples spanning rates 3 to 27:

| Sample | Rate | Median bytes per tick | Z80 cycles unaccounted per frame |
|---|---|---|---|
| `DAC_90` | 3 | 265 | 13,494 |
| `DAC_86` | 4 | 239 | 14,924 |
| `DAC_88` | 6 | 220 | 12,766 |
| `DAC_8D` | 11 | 165 | 13,784 |
| `DAC_93` | 14 | 151 | 11,794 |
| `DAC_97` | 18 | 129 | 12,070 |
| `DAC_B2` | 22 | 114 | 11,685 |
| `DAC_8B` | 27 | 98 | 12,059 |

A wrong per-byte cost would skew that residual with the rate; it does not.
The residual is the service's own execution cost, and it is not constant:
within one uninterrupted play of one sample at one fixed rate the reference's
per-tick counts swing by 15 to 86 bytes, and four separate plays of the same
sample index peak at 268, 258, 257 and 244.

Run length inherits the same quantity. A run ends either because the sample
was exhausted or because a later play cut it short
(`jp p, .dac_idle_loop`, :4343-4345), and how far the stream got before the
cut is Z80 duration: the engine carries the first music sample 1,438 bytes
where the reference's own play was cut at 1,364.

The sample-end `2Bh = 0` inherits it in turn, and that is why it left the
service stream. Only an exhausted sample reaches `zPlayDigitalAudio`'s entry,
which is the sole writer of the disable (:4258-4262); a superseded one jumps
straight to `.dac_idle_loop` and writes nothing. So the disable's presence
encodes exactly which of the two endings occurred, which is the quantity
already excused above. Measured on the committed reference: the first music
play is superseded at service 145 with no disable at all, while the engine,
streaming for the whole interval, exhausts the same sample and writes one at
service 143.

The `2Bh = 80h` enable did not move and must not. The idle loop writes it
immediately on finding `zDACIndex` non-zero (:4269-4276), and that store
happens at a service boundary, so every run's start stays pinned to an exact
tick by strictly compared data.

### What Is Still Compared

The comparison keeps everything the ROM decides rather than the Z80's clock.
The run count must match exactly. Every byte the two sides share in a run
must be equal, in order, which is what proves the sample selection, the DPCM
decode and the cadence. Run starts are the `2Bh = 80h` enables, which are
still compared per tick, so run structure is pinned by compared data. No
`2Ah` byte may stream while the DAC is disabled, on either side, because the
idle loop streams nothing (:4262-4270); that stays a hard error.

Two quantities are reported rather than asserted, so a regression in either
stays visible on the DAC stream's result line: `run-length delta` for the
cumulative byte difference, and `sample-end delta` for the cumulative
difference in trailing disables.

How many disables fall between two runs is not asserted either, and that was
measured rather than assumed. Run 0 carries three on both sides, because
`zPlaySEGAPCM` returns through `zPlayDigitalAudio`'s entry and re-writes the
disable each time the idle path is re-entered. An earlier draft of this
excusal asserted "at most one" and was refuted by the reference itself.

### Removal Condition

A Z80 cycle account for the driver's own service. Nothing short of that makes
the partition derivable, and no fixture measurement may stand in for it.

---

## S2 Music DAC Byte Stream Partition (Oracle Comparison)

The S2 driver-state oracle compares the music DAC byte stream over the whole
capture window rather than per service window, and does not assert how far a
DAC run got before it ended. Everything else in a service, the `2Bh` enable
and disable writes included, stays strictly partitioned and compared.

### Original Implementation

`zWriteToDAC` streams a sample's decoded bytes to `2Ah` from outside the
interrupt, bracketing each write with `di` / `ei` and spending the rest of its
time in two `djnz $` busy waits (s2.sounddriver.asm:682-726). Those waits are
the only window a V-int can land in, so the number of bytes that fall in a
given service window is whatever the Z80 had time for after that frame's
`zUpdateEverything` returned with interrupts masked.

### Engine Behaviour

The engine models the driver's semantics, not the Z80's instruction timing. It
streams the same bytes at the same per-byte cadence but charges nothing for the
service itself, so it spends the whole inter-V-int interval streaming. Measured
on the committed `s2-driver-state-w10150-12400` reference, the engine's music
activation service carried 108 sample bytes that the ROM's carried none of, and
those 108 bytes were byte-for-byte the head of the reference's *next* service.

### Why

This is the same unmodelled quantity already excused for S3K, one section
above: the split is a property of a CPU this engine does not emulate, and
measuring it from a fixture would be a fitted model.

### What Is Still Compared

The run count must match exactly, and every byte the two sides share within a
run must be equal, in order. That is what proves the sample selection, the
`jman2050` nybble decode and the cadence.

Every run boundary is the ROM's own and is derived from driver state rather
than assumed; the next section states the rule in full. Sonic 2's playback loop
writes nothing at all between samples, because `zWaitLoop` spins on the
remaining length `de` being zero and touches no register until a sample is
queued (:647-650), so a completed service carrying no `2Ah` byte is a real gap.
The sample's own decoded length and the driver's current sample selector supply
the other two.

Unlike S3K, no `2Bh` write moved into this stream. Sonic 2's playback loop
never writes the DAC enable register per sample: every `2Bh` write in this
driver belongs to a song load, a fade or the SEGA chant (:1613, :1662, :1936,
:2555, :3158), so all of them stay in the per-service partition.

Two quantities are reported rather than asserted, so a regression in either
stays visible on the DAC stream's result line. `run-length delta` is the
cumulative byte-count difference across shared runs. `resync` is where the
reference's remaining bytes pick up again in the engine's run after a byte
difference, and it is what separates the two very different situations a byte
difference can mean.

### The Run Rule, And The One Thing It Cannot Split

A run is at most one decoded sample long. That bound is the one
`zWriteToDAC` itself enforces: it decrements `de`, loaded from `zDACLenTbl`
by the same self-modified entry lookup that rebases `zCurDAC`
(s2.sounddriver.asm:505-518, :528-529), once per source byte, and returns to
`zWaitLoop` when it reaches zero. So a run ends at its sample's decoded
length, at a service that carried no sample byte, or at a change of the
current sample selector. Two consecutive plays of one sample are two runs of
that length even with nothing between them. Each side reads the bound from the
ROM's own table through its own selector, and the two selectors are never
compared to each other.

A run shorter than its bound was cut off by another play rather than
exhausting its sample, and how far each side got before the cut is the
service-duration quantity above. The join between two such runs is the one
place the comparison cannot line up, because the two producers phase a queued
sample differently: the ROM arms it at the end of `zUpdateDAC` and cannot
stream a byte until the service returns and re-enables interrupts (:492-536,
:682-726), while the engine's pump, charging nothing for the service, begins
inside it.

It is measured, not assumed. On the committed `s2-driver-state-w10150-12400`
reference the first such join is in run 3, whose sample is 1,320 decoded bytes
long. The ROM played 709 of them before another play superseded it and the
engine played 1,206. The reference's 709 is exactly the sum of its own
services 150-155, and its next byte is `0x80`, the value `zWriteToDAC`'s
accumulator starts every sample at (`ld a,80h` / `ex af,af'` in `zVInt`'s
`.dacqueued`, :502-517); the step from the preceding `0x89` is minus nine,
which no entry of `zDACDecodeTbl` can produce, so a second sample begins
there.

The data itself is not in question, and the result line says so. The three
runs before the join are full-length plays of 1,320 bytes and agree byte for
byte on both sides, which is what `complete runs agreed` counts. Aligning each
side's superseding sample by its own start, 3,612 bytes agree with zero
mismatches. A byte difference inside a full-length run would be sample data
and would be a defect; a byte difference after a pair of short runs is this
join.

The engine's DAC interpolation is not involved and cannot be. `Ym2612Chip`'s
interpolated write deliberately does not call the write observer, because it
has no ROM counterpart, so no synthetic byte reaches the compared stream.

Three quantities are reported rather than asserted, so a regression in any of
them stays visible on the result line: `run-length delta`, the cumulative
byte-count difference across shared runs; `resync`, where the reference's
remaining bytes resume in the engine's run after a difference; and
`previous run superseded`, the two short lengths and the bound they fell short
of.

### Verification

`TestS2DriverStateOracle`, which reports the DAC stream as its own result line
beside the per-service one, and whose
`aCorruptedDacSampleByteBreaksTheStreamComparison` flips one reference sample
byte and requires the verdict to move.

### Removal Condition

A Z80 cycle account for the driver's own service. Nothing short of that makes
the partition derivable, and no fixture measurement may stand in for it.

---

## S2 Headless Oracle Capture Starts Mid-Run (Driver Variables Outside The Music Load's Clear)

The S2 driver-state oracles drive the engine from a song load, not from
power-on. Driver variables that a song load does not clear therefore enter the
comparison with whatever the recording accumulated before the window, and the
engine capture has no way to know that value. The first one this has cost is
`zRingSpeaker`.

### Original Implementation

`zPlaySound_CheckRing` alternates the ring sound between two speakers. While
`zRingSpeaker` is zero it resolves the raw `B5h` request to `CEh`, ring left,
and either way it complements the flag afterwards, so consecutive rings
alternate (s2.sounddriver.asm:2124-2135). The flag lives among the driver's own
byte variables at low Z80 addresses, outside the `zAbsVar`-to-`zTracksSongEnd`
region `zInitMusicPlayback` clears (:2580-2612), so a song load leaves it
untouched.

### Engine Behaviour

`S2OracleEngineCapture` starts its alternation at the power-on value, zero, so
its first ring resolves to `CEh`. That is the correct default and it is
asserted by
`TestS2AudioOracleComparator#explicitDriverRequestsResolveAndAdmitTheFirstRingBeforeTheTargetUpdate`,
which requires `zRingSpeaker = 0` to produce `CEh`.

### Why

A capture that begins at a mid-run song load cannot derive a variable the load
does not clear. Seeding it from the reference would hydrate driver state that
decides *which* sound plays, which is outside the hardware-timing exception in
hard rule 4: that exception may only affect when engine-created work becomes
ready or which of two existing ROM loops a row takes, never what happens.

### What It Costs, Measured

On the committed `s2-driver-state-cpz-w2700-3450` reference the window's
`zRingSpeaker` is `FFh` from the anchor through service 261 and flips to `00h`
at service 262, which is the window's first ring. So the ROM kept `B5h`, ring
right, where the engine resolved `CEh`, ring left. The two sounds occupy
different FM channels, so the whole voice load appears one channel across:
the reference on FM5 at `B1h`, `31h`, `35h`, `39h`, `3Dh`, the engine on FM4 at
`B0h`, `30h`, `34h`, `38h`, `3Ch`, every value equal.

The attribution was proved by experiment rather than argued. Starting the
harness alternation at the opposite phase moves the first write divergence from
service 237, movie row 2968, to service 494, movie row 3225, and halves the
divergent services from 36 to 18. That change was not kept, because it breaks
the power-on assertion above; the phase is a property of the window, not a
constant to pick.

### What Is Still Compared

Everything else. The CPZ window's driver state matches on all 720 compared
services, and the write stream matches up to the first ring. Nothing is
excluded from the comparison by this entry; it records why a mid-run window's
first ring can differ and what that difference looks like, so the next lane
does not read it as a channel-assignment defect. The slot rule itself is not
involved: `zPlaySound` derives an SFX slot from the header's own channel byte
through `zMusicTrackOffs` (:2210-2251, :747-757) and both sides agree on it.

### Resolved For The Ring Flag

Met on 2026-09-04 by the second route rather than the first. The flag is the
parity of the rings played since power-on, and the recording's own ring
requests are stimuli, so the count of them before a window derives it without
reading any compared value. `S2OracleEngineCapture` takes that count and the
CPZ oracle reads it from the committed
`s2-request-stimuli-cpz-w120-3450.json`, which carries one ring request at
movie row 1432, before the window. The write frontier moved from service 237,
movie row 2968, to service 494, movie row 3225.

The entry stays because the general limitation stands: any other driver
variable outside the load's clear is still inherited and still undetermined,
and the ring flag is only the first one to have cost anything.

### Removal Condition

A capture that starts from power-on and an engine run driven across it. Two
producer limitations block that today and are recorded in the audio frontier
log: the request-window capture mode cannot begin a publication epoch at movie
row 0, and the request producer records no music requests at all, so a
power-on engine run cannot reproduce the songs a recording loads before the
window.

---

## Audio Harness Startup and Compatibility Fade Boundaries

The September 5 [live-boundary audit](../architecture/audits/audio/2026-09-05-live-parity-boundary-audit.md)
found coverage gaps beyond the corrected native S1/S3K fade commands:

- S1 captures supply startup register writes that are not yet a verified
  ownership-sensitive production song-load implementation. Comparator reports
  explicitly exclude production startup from their coverage. Correct startup
  must respect normal/special SFX ownership; an unconditional harness reset is
  not a safe production replacement.
- A S3K donor song on an S1 host retains existing mixed source/host fade
  semantics, including a possible PSG halt without the source driver's silence
  burst. The terminal-stop capability check preserves existing donor key-off
  behavior; it does not certify donor fade parity.
- The legacy direct-read adapter has no driver-level sample-clock service for
  a songless fade. Forward presentation and service-based capture advance its
  counters, but direct-read compatibility calls advance sequencers only.

These are open implementation/coverage limitations, not permission to omit
differences from an oracle. Closing them requires source-owned initialization
and host/source command semantics, plus a shared cadence model for songless
direct reads; no comparison-state hydration or fitted timing is permitted.

---

## PSG Tone-2-Linked Noise at Period 0/1 Follows the Reference Cores

**Location:** `PsgChip.tickNoise()` / `PsgChip.linkedNoiseReload()`
**Hardware Reference:** `docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md` §4.1, §10.5

### Original Implementation

On the Sega-integrated SN76489 a tone period of 0 or 1 holds the channel's output
constantly high. The TI datasheet wires "tone generator #3 output" into the noise
clock when the noise rate bits are `11`, and a constant output has no edges, so
the clean-room specification's first revision concluded the LFSR stops in that
state. No public hardware capture of the Sega part in this state is known.

### Our Implementation

The noise clock is driven by tone 2's *counter expiry*, not its held output
latch: with `N₂ ≤ 1` the noise counter reloads with `max(N₂, 1)`, so it flips every
tick and the LFSR shifts every second tick — the highest noise pitch the chip
produces. Tone 2's own output stays constantly high as before.

### Rationale

The pinned Genesis Plus GX reference (and MAME, at twice the rate) both clock the
noise in this state, and the SMPS drivers use it deliberately: writing the top
note-table entry (`0x000`) into tone 2 under a linked noise track is their idiom
for the fastest noise. Twenty-two SFX across the three games sit in that state —
S1 `A2`, `AA` Splash, `AB`, `AE` Fireball, `B6` Spikes Move; S2 `A2`, `AA` Splash,
`AB` Swish, `AE` Lava Ball, `B6`, `D4`; S3K `42`, `47`, `4E`, `66`, `70`, `7E`,
`8D`, `97`, `A0`, `D1`, `DB` — the S1/S2 Splash for its whole 1.7 s body. A stopped
LFSR silences them. Whether real silicon shifts once per tick (MAME) or once per
two ticks (GPGX) remains unmeasured; the engine follows GPGX because the engine
contract's tolerances are stated against it. Only the linked-noise clock changed;
the tone channel's constant-high rule and every other noise rate are untouched.

### Verification

`TestPsgChipHardwareBehaviour.toneTwoLinkedNoiseFollowsToneTwoPeriodAndKeepsClockingAtTheTopOfTheTable`
asserts one shift per two ticks at `N₂ = 1` and `N₂ = 0` with tone 2 held high.
The capture re-run in
`docs/architecture/validation/2026-08-29-psg-clean-room-capture-comparison.md` §9
matches the reference on the S1 `B6` tail to within 0.02 dB per attenuation step,
identical transition counts, and cross-correlation 0.990–0.9999 at a constant lag.

---

## FM:PSG Mix Balance Is Pre-Rewrite Parity, Not a Hardware Calibration

**Location:** `VirtualSynthesizer.PSG_PREAMP_PERCENT` (applied via `PsgChip.configure(38, 0xFF)`)
**Reference:** `docs/architecture/validation/2026-08-29-audio-mix-calibration.md`

### Original Implementation

The console mixes the YM2612 and SN76489 at a fixed analogue ratio. The engine
never measured it: before the two core rewrites the balance was whatever the
two Genesis Plus GX-derived cores produced through a plain sum — one full-scale
FM channel 8191 against one full-scale PSG channel 2800 x 150 % = 4200, an
FM:PSG ratio of 1.950 (+5.80 dB), with no preamp call anywhere in the mixer.

### Our Implementation

The Nuked-OPN2 facade emits 6144 per full-scale FM channel and the clean-room
PSG 8191 per full-scale channel, so an unadjusted sum would have moved the
balance by 8.30 dB toward the PSG. The mixer now sets the PSG output-stage
preamp to 38 % (`6144 x 4200 / 8191^2 = 38.46 %`, rounded to the whole percent
`configure` accepts), giving 6144 / 3112 = 1.974 (+5.90 dB), 0.10 dB from the
pre-rewrite ratio. At the 16-bit output after `MASTER_GAIN_SHIFT` one full-scale
FM channel is 3072, one full-scale PSG channel 1556, and silence rests at +288.
Neither chip's own scale is adjusted.

### Rationale

No capture with both FM music and PSG sound exists in the repository (every
`*-reference.wav` under `docs/architecture/research/audio/` is FM-only, the
trace fixtures carry no PCM, and the BizHawk observer records chip writes, not
audio), so the balance is **pre-rewrite parity, uncalibrated against hardware —
it needs a two-chip capture**. Restoring the old ratio numerically keeps the
audible balance players had rather than inventing a new one.

### Verification

`TestVirtualSynthesizerMix` asserts the documented per-chip levels, the ratio,
and the resting level from the constants above. Removal condition: a hardware
or trusted-emulator capture of one passage containing FM music and a PSG SFX,
rendered through `FmSfxRenderTool` / `PsgSfxRenderTool` and matched on the
relative RMS of the FM- and PSG-dominated segments; replace the 38 % with the
measured value and retire this entry.

---


## Fast FM Register-Level Timing and Output

The selectable `audio.fmCore=fast` core advances synthesis at one internal FM
frame (144 input clocks), while retaining measured register and DAC output
sampling boundaries within that frame. It does not emulate the full bus
pipeline, busy flag, LSI test registers, or analogue ladder-effect output stage.
Its facade preserves stereo panning and the mixer's nominal 6144 full-scale
channel level, with writes paced and diagnostics timestamped at frame
boundaries. These deliberate approximations reduce synthesis CPU cost.

`audio.fmCore=accurate` retains the cycle-exact Nuked implementation and is
required for physical reference captures. New configurations select fast;
existing explicit accurate selections are preserved. Pitch-transition phase and per-path modulation history now have independent
all-channel coverage. Carrier/channel output history and key-on phase now
also account for the multiplexed sampling boundary, with all 24 write offsets
covered on every channel. Frequency sampling has all-operator/all-offset
coverage and brings S3K effect 3C within tolerance. FM/DAC relative timing
and all DAC data/enable write offsets have independent output coverage.
Scheduled frequency, level and key admission now closes the S2 BC and pan/TL
failures, with independent sample-exact high-feedback transition coverage.
The LFO prescaler and PM arithmetic are now oracle-measured (bit-containment
terminals, half-step truncated partial sums, twelve-bit wrap). AM preserves
discrete depths and operator output history, with independent all-channel/
all-carrier measurements and rewind coverage. All 178 supported scripts now
meet the unchanged waveform/level bounds without deferrals. Independent
high-FNUM checks cover global PM sampling across every channel and carrier.
Final integrated verification and human listening sign-off remain separate
release gates; this tolerance result does not establish full SMPS parity. See the [fast FM validation record](../architecture/validation/audio/2026-09-06-fast-fm-release.md).

## S1/S2 Extra-Life SFX Block: Residual Flag Edges

**Location:** `AudioVoiceRegistry` (`sfxBlocked`, `sfxBlockHeldThroughFadeIn`),
`Sonic1StatefulCommandPolicy`, `Sonic2StatefulCommandPolicy`,
`Sonic2SoundRequestPipeline`

The engine now models the two flags that refuse SFX around a Sonic 1 / Sonic 2
extra life: the 1-up flag from the jingle's load to `cfFadeInToPrevious`
(`s1.sounddriver.asm:784, :2222`; `s2.sounddriver.asm:1712, :3155`) and the
fade-in flag from there until the fade-in stepper's counter runs down
(`s1:2220, :1650`; `s2:3151, :2740`), with `Sound_PlaySFX` /
`zPlaySound_CheckRing` refusing while either is set (`s1:978-982`,
`s2:2118-2120`). Three edges of the same mechanism are not modelled:

- **The refused request's priority clear.** Both refusals also zero the SFX
  priority latch (`.clear_sndprio`, `s1:1085-1086`; `zKillSFXPrio`,
  `s2:2334-2336`). Sonic 2's mailbox model
  (`Sonic2SoundRequestPipeline.onSfxSuppressedDuringOneUpOrFadeIn`) has the
  operation but the presentation's refusal is not reported back to the request
  service, so the latch keeps the value `zCycleQueue` stored for the refused
  request. Sonic 1's presentation admission is permissive and carries no latch.
  Audible only as a lower-priority effect being turned away just after the
  fade in, in the shipped-bug case where `cfFadeInToPrevious` restores a
  non-zero latch from the RAM copy (`s2:1714-1722`).
- **A jingle over silence.** With no song to save, the ROM still raises the
  fade-in flag at `cfFadeInToPrevious` and holds SFX for the `28h`-step fade of
  nothing. The engine has no song to fade, so it releases when the jingle ends.
- **Sonic 1 `StopAllSound` keeps `f_1up_playing`.** The global stop saves and
  restores that byte across its RAM clear (`s1:1490, :1506`), so a fade-out
  that completes during the jingle leaves SFX refused until the next song
  loads. The engine releases the block on the global stop, as it does for the
  other two games.
