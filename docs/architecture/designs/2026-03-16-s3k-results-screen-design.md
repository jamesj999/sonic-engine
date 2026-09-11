# S3K Results Screen Design

## Overview

Implement the S3K act-completion results screen that displays after the signpost lands. Shows "<CHARACTER> GOT THROUGH ACT <N>" with time bonus and ring bonus tally, then signals completion for the level event system to handle zone-specific transitions.

## Key Difference from S1/S2

S3K results never trigger a fade-to-black or level load. The results screen is a pure display/tally state machine that signals completion via flags. Zone-specific transitions (act 2 title card, zone-to-zone events) are handled by the existing `S3kBossDefeatSignpostFlow` and level event system.

## Class Structure

### S3kResultsScreenObjectInstance (new, extends AbstractResultsScreen)

Inherits the 5-state machine: SLIDE_IN → PRE_TALLY_DELAY → TALLY → WAIT → EXIT.

**Overrides:**
- `getSlideDuration()` — returns 0. S3K has no parent slide-in phase; children handle their own sliding during the pre-tally delay. Skipping SLIDE_IN keeps the total pre-tally time at exactly 360 frames.
- `getPreTallyDelay()` — returns 360 (ROM: `6*60` frames, not S2's 180).
- `getWaitDuration()` — returns 90 (ROM: `move.w #90,$2E(a0)`).
- `updatePreTallyDelay()` — overrides to trigger `mus_GotThroughAct` (0x29) at frame 71 of the 360-frame countdown (when `stateTimer == 71`, matching ROM's check for 289 remaining at line 62626). Also resets air to 30 for both players (Hydrocity compatibility). Calls `super.updatePreTallyDelay()` for state transition logic.
- `updateTally()` — overrides to use global frame counter modulo 4 for tick sound timing (ROM: `Level_frame_counter & 3`), instead of the base class's `stateTimer % interval` approach. Tally decrement logic remains the same.
- `performTallyStep()` — decrements time bonus and ring bonus (no perfect bonus in S3K). Tracks `totalBonusCountUp` for the running total display.
- `playTickSound()` — plays `sfx_Switch` (0x5B).
- `playTallyEndSound()` — plays `sfx_Register` (0xB0).
- `onExitReady()` — act-dependent exit behavior (see Exit Behavior section). No fade, no advance.

**Init behavior:** Constructor fades out current music (`cmd_FadeOut`) immediately, matching ROM line 62513-62514. Calculates time bonus and ring bonus from singletons. Triggers art loading.

**Constructor parameters:** `PlayerCharacter character, int act` (act is 0-indexed: 0=Act 1, 1=Act 2). Elapsed time and ring count are read from singletons (`LevelGamestate` timer, player ring count) during init, matching how the ROM reads `Timer_minute`/`Timer_second`/`Ring_count` from RAM.

### Art Loading (Sonic3kObjectArt.loadResultsArt)

Called once during results init (not at level load time — matches ROM behavior).

| Asset | Destination (Act 1) | Destination (Act 2) | Purpose |
|-------|---------------------|---------------------|---------|
| `ArtKosM_ResultsGeneral` | Tiles 0x520 | Tiles 0x520 | "GOT THROUGH", "TIME BONUS", "RING BONUS", labels |
| `ArtKosM_TitleCardNum1` | Tiles 0x568 | Tiles 0x568 | Act 1 number tiles (default) |
| `ArtKosM_TitleCardNum2` | Tiles 0x568 | Tiles 0x568 | Act 2 number tiles (used when act != 0 OR zone == $16/LRZ boss) |
| Character name art | Tiles 0x578 | Tiles 0x5A0 | "SONIC", "TAILS", or "KNUCKLES" |
| `Pal_Results` | Full palette ($80 bytes) | Full palette ($80 bytes) | Replaces all 4 palette lines (64 colors) |

**Num1 vs Num2 selection:** Default to `ArtKosM_TitleCardNum2`. Use `ArtKosM_TitleCardNum1` only when act == 0 AND zone != $16 (LRZ boss). This matches ROM logic where `subtype` is 0 for act 1 and -1 for act 2/LRZ.

Character name art selected by `PlayerCharacter`:
- SONIC_AND_TAILS / SONIC_ALONE → `ArtKosM_ResultsSONIC` (frame 0x13)
- TAILS_ALONE → `ArtKosM_ResultsTAILS` (frame 0x13 + 2, with Tails variant if `Graphics_flags` set)
- KNUCKLES → `ArtKosM_ResultsKNUCKLES` (frame 0x13 + 3, shifted left 0x30px, width increased by 0x30)

### Mapping Frames (ROM-parsed)

`Map_Results` parsed via `S3kSpriteDataLoader` — 59 mapping frames defining exact tile placement for every element. This matches the approach used by the S3K title card.

## Element Layout

12 elements from ROM's `ObjArray_LevResults`. All start sliding simultaneously at 16px/frame — the visual stagger comes from different start distances, not different start times.

| # | Type | Target X | Start X | Y | Frame | Width | Exit Queue |
|---|------|----------|---------|---|-------|-------|------------|
| 1 | CharName | $E0 | -$220 | $B8 | $13 | $48 | 1 |
| 2 | General | $130 | -$1D0 | $B8 | $11 | $30 | 1 |
| 3 | General | $E8 | $468 | $CC | $10 | $70 | 3 |
| 4 | General | $160 | $4E0 | $BC | $F | $38 | 3 |
| 5 | General | $C0 | $4C0 | $F0 | $E | $20 | 5 |
| 6 | General | $E8 | $4E8 | $F0 | $C | $30 | 5 |
| 7 | TimeBonus | $178 | $578 | $F0 | 1 | $40 | 5 |
| 8 | General | $C0 | $500 | $100 | $D | $20 | 7 |
| 9 | General | $E8 | $528 | $100 | $C | $30 | 7 |
| 10 | RingBonus | $178 | $5B8 | $100 | 1 | $40 | 7 |
| 11 | General | $D4 | $554 | $11C | $B | $30 | 9 |
| 12 | Total | $178 | $5F8 | $11C | 1 | $40 | 9 |

**Slide-in:** Elements with negative start X slide right; positive start X slide left. Speed: 16px/frame (`moveq #$10,d1`). Element stops when `x_pos == targetX`.

**Slide-out:** After the 90-frame post-tally wait, the parent increments an exit queue counter each frame. Each child starts sliding out (at 32px/frame, `move.w #-$20,d0`) when the queue counter reaches its exit queue priority. Elements slide back the direction they came from. When off-screen, they decrement the parent's child counter and self-destruct. When all children are gone, the parent signals completion.

## Tally Mechanics

**Time Bonus table** (indexed by `elapsedSeconds / 30`, capped at index 7):
```
{5000, 5000, 1000, 500, 400, 300, 100, 10}
```

**Special case:** If timer is exactly 9:59 (599 seconds), time bonus is 10000 (tallies to 100,000 points). This overrides the table lookup.

**Ring Bonus:** `ringCount * 10`

**Total Bonus:** Running count-up displayed by element 12, increases by the sum of time+ring decrements each frame.

**No Perfect Bonus** — S3K does not have this.

**Countdown:** Decrement each bonus by 10 per frame. Add total decrement to player score each frame via `HUD_AddToScore`. Play tick SFX every 4 frames (using global `Level_frame_counter & 3 == 0`). Play cash register SFX when all bonuses reach zero.

**Digit display:** TimeBonus/RingBonus/Total elements use child sprites (7 digits each) positioned relative to the element's X. Binary-to-BCD conversion via the ROM's double-dabble approach, with leading zero suppression.

## Music

**On init:** Fade out current music (`cmd_FadeOut`), matching ROM line 62513-62514.

**At frame 71 of pre-tally delay:** Play `mus_GotThroughAct` (0x29). The ROM sets a 360-frame countdown at line 62580, then checks for 289 remaining (360 - 71 = 289) at line 62626. Also resets air to 30 for both players (Hydrocity compatibility) at lines 62628-62629.

## Exit Behavior

The ROM has three exit paths based on zone/act:

| Condition | Action |
|-----------|--------|
| Act 2, or Sky Sanctuary (zone $A), or LRZ boss (zone $16) | Clear `_unkFAA8`, set `End_of_level_flag`, delete self |
| Act 1 (most zones) | Set `Apparent_act = 1`, clear checkpoint, clear `_unkFAA8`, transform into Title Card object |
| Act 1 (Sandopolis zone $8 or Death Egg zone $B) | Same as above but no title card — just delete self |

Additionally, for Act 1 (except AIZ zone $0 and ICZ zone $5), `Events_fg_5` is set at results creation time (line 62616) to trigger the background level event for the act transition. **Engine mapping:** Call `Sonic3kLevelEventManager.getInstance().setEventsFg5(true)` (or equivalent flag setter) during element creation.

**Engine mapping for `onExitReady()`:**
- **Act 2 / SSZ / LRZ boss:** Signal `endOfLevelActive = false`, `endOfLevelFlag = true`, self-destruct.
- **Act 1 (most zones):** Signal `endOfLevelActive = false`, advance `Apparent_act` to 1, clear checkpoint, trigger `Sonic3kTitleCardManager.initializeInLevel()` for Act 2, self-destruct.
- **Act 1 (SOZ/DEZ):** Signal `endOfLevelActive = false`, advance `Apparent_act` to 1, clear checkpoint, self-destruct (no title card).

## Integration

### Spawn Point

`S3kSignpostInstance` RESULTS state changes from spawning `S3kLevelResultsInstance` (stub) to spawning `S3kResultsScreenObjectInstance`. Must pass `PlayerCharacter` and `act` to constructor.

### Flag Signaling

For Act 2: `endOfLevelActive = false`, `endOfLevelFlag = true`. The existing `S3kBossDefeatSignpostFlow` picks up from AWAIT_RESULTS → AWAIT_ACT_TRANSITION.

For Act 1: `endOfLevelActive = false` only. No `endOfLevelFlag` — the act 2 transition is handled inline by showing the title card and advancing `Apparent_act`.

### Character Support

Three display names via PlayerCharacter enum:
- SONIC (for SONIC_AND_TAILS and SONIC_ALONE)
- TAILS (for TAILS_ALONE)
- KNUCKLES

## ROM Addresses Needed

To be found via `RomOffsetFinder --game s3k`:
- `ArtKosM_ResultsGeneral`
- `ArtKosM_ResultsSONIC`
- `ArtKosM_ResultsTAILS`
- `ArtKosM_ResultsKNUCKLES`
- `ArtKosM_TitleCardNum1`
- `ArtKosM_TitleCardNum2`
- `Map_Results`
- `Pal_Results`

## Files Changed

| File | Change |
|------|--------|
| `S3kResultsScreenObjectInstance.java` | New — full results screen implementation |
| `Sonic3kObjectArt.java` | Add `loadResultsArt(PlayerCharacter, int act)` |
| `Sonic3kConstants.java` | Add ROM addresses for results art/mappings/palette |
| `S3kSignpostInstance.java` | Spawn new results class instead of stub, pass character + act |
| `S3kLevelResultsInstance.java` | Delete (replaced) |

## Files Untouched

- `S3kBossDefeatSignpostFlow.java` — works as-is, polls existing flags
- `Sonic3kLevelEventManager.java` — no changes needed (Events_fg_5 setter may already exist)
- `AbstractResultsScreen.java` — inherited without modification
- `GameStateManager.java` — flags already exist
