# S3K LBZ Zone Analysis

## Summary

- **Zone:** Launch Base Zone (LBZ)
- **Zone Index:** 0x06
- **Zone Set:** S3KL
- **Acts:** 1 and 2
- **Water:** Yes. Act 1 uses normal level water; Act 2 uses water and dynamic waterline art during the Death Egg sequence.
- **Palette Cycling:** Yes. `AnPal_LBZ1` and `AnPal_LBZ2` cycle three colors on palette line 3.
- **Animated Tiles:** Yes. LBZ has declarative AniPLC scripts plus custom direct-DMA background/waterline channels.
- **Character Branching:** Yes. Knuckles has altered LBZ1 background setup, immediate LBZ2 corridor opening after transition, and a different LBZ2 boss/cutscene path.
- **Current engine status:** The engine now implements the main LBZ1 layout/event path, LBZ1->LBZ2 seamless reload, LBZ2 entry layout, Death Egg terrain/art swap, Death Egg launch motion, LBZ1/LBZ2 parallax, LBZ2 Death Egg deform, LBZ animated-tile dependencies, LBZ palette cycling, and the major LBZ2 boss/object hooks. Remaining gaps are mostly special VInt/window-plane presentation and some lower-impact object polish.

## Disassembly Anchors

Prefer `docs/skdisasm/sonic3k.asm` labels and S&K-side addresses for all runtime work.

| Category | Label | Location |
|----------|-------|----------|
| Dynamic resize | `LBZ1_Resize` | `sonic3k.asm:39481` |
| Dynamic resize | `LBZ2_Resize` | `sonic3k.asm:39485` |
| Palette cycling | `AnPal_LBZ1` / `AnPal_LBZ2` | `sonic3k.asm:3440` / `3445` |
| Animated tiles | `AnimateTiles_LBZ1` / `AnimateTiles_LBZ2` | `sonic3k.asm:54539` / `54634` |
| AniPLC data | `AniPLC_LBZ1`, `AniPLC_LBZSpec`, `AniPLC_LBZ2` | `sonic3k.asm:55900`, `55909`, `55926` |
| Screen/background events | `LBZ1_ScreenInit` / `LBZ1_ScreenEvent` | `sonic3k.asm:110833` / `110880` |
| Screen/background events | `LBZ1_BackgroundInit` / `LBZ1_BackgroundEvent` | `sonic3k.asm:111163` / `111190` |
| Screen/background events | `LBZ2_ScreenInit` / `LBZ2_ScreenEvent` | `sonic3k.asm:111290` / `111302` |
| Screen/background events | `LBZ2_BackgroundInit` / `LBZ2_BackgroundEvent` | `sonic3k.asm:111383` / `111405` |
| Parallax | `LBZ2_Deform` / `LBZ2_DeathEggDeform` | `sonic3k.asm:111581` / `111847` |
| Death Egg motion | `LBZ2_DeathEggMoveScreen` | `sonic3k.asm:112060` |
| Boss/object code | `Obj_LBZFinalBoss1`, `Obj_LBZFinalBossKnux`, `Obj_LBZFinalBoss2` | `sonic3k.asm:151927`, `152493`, `154226` |
| Robotnik ride ship | `Obj_LBZ2RobotnikShip` | `sonic3k.asm:192827` |

## Current Engine Routing

| ROM area | Engine owner | Notes |
|----------|--------------|-------|
| LBZ runtime flags and event globals | `LbzZoneRuntimeState` | Mirrors launch, layout, Death Egg deform, ride gate, and animated-tile phase state. Rewind serialized. |
| Screen/background events | `Sonic3kLBZEvents` | Owns layout mods, transition reload, Death Egg resource swap, launch/fall state, screen shake, and terrain clears. |
| Scroll/deform | `SwScrlLbz` | Ports LBZ1, LBZ2, and LBZ2 Death Egg deform through `ScrollEffectComposer` and publishes the ROM globals consumed by animated tiles. |
| Animated tiles | `Sonic3kPatternAnimator` + `S3kAnimatedTileChannels` | Custom LBZ channels are graph-owned. LBZ2 phase inputs come from `LbzZoneRuntimeState`, not camera recomputation. |
| Palette cycling | `Sonic3kPaletteCycler` | `LbzCycle` reads the correct act table and writes through `PaletteOwnershipRegistry`. |
| Layout mutation | `ZoneLayoutMutationPipeline` + `LevelMutationSurface` | All gameplay layout edits route through the mutation pipeline. |
| Death Egg art/chunk swap | `Sonic3kLBZEvents.applyDeathEggTerrainSwapHook` | Loads ROM-backed Kos/KosM resources and refreshes the Knuckles pillar sheet from ROM. |

## Events

### Act 1: `LBZ1_Resize`

`LBZ1_Resize` is a no-op in the disassembly. Act 1 behavior is hosted by `LBZ1_ScreenEvent` and `LBZ1_BackgroundEvent`, and the Java implementation follows that split through `Sonic3kLBZEvents`.

#### Act 1 Screen Event

| Feature | ROM behavior | Current engine behavior |
|---------|--------------|-------------------------|
| Four interior layout mods | Player rectangles copy hidden FG staging rows into visible rows; exit ranges restore the covered rows. | Implemented via `LBZ1_LAYOUT_MODS`, `applyLayoutCopy`, and `LbzZoneRuntimeState.activeInteriorLayoutMod`. |
| Layout mod 3 disable | `LBZ1_ModEndingLayout` sets the flag that prevents the boss-area reveal from re-entering after the building collapse. | Implemented as `interiorLayoutMod3Disabled`. |
| Boss-area opened box | `Events_fg_4=$55` changes Sonic/Tails FG block `($7D,2)` to `$DA`; Knuckles copies a three-column staging row. | Implemented by `applyMinibossBoxOpenedChunkSwap`. |
| Building collapse | `LBZ1_EventVScroll` drops ten foreground VScroll columns to `-$300`, plays rumble, then applies ending layout and crash SFX. | Implemented with fixed-point column state, per-column VScroll override, `BIG_RUMBLE`, `CRASH`, and invisible barrier spawn. |
| Restart past boss area | Non-Knuckles restart at camera X >= `$3B60` applies the ending layout and spawns the invisible barrier. | Implemented in `applyRestartFromBossAreaInitIfNeeded`. |

#### Act 1 Background Event and Transition

| Stage | ROM behavior | Current engine behavior |
|-------|--------------|-------------------------|
| Normal | Runs `LBZ1_Deform`, draws BG rows, applies foreground VScroll, and shake. | Deform is handled by `SwScrlLbz`; collapse VScroll is supplied by `Sonic3kLBZEvents`. |
| Transition prep | `Events_fg_5` queues LBZ2 secondary resources. | Engine level reload already loads LBZ2 resources, so this is collapsed into the transition execution. |
| Transition reload | Changes zone/act to `$0601`, reloads level, applies `-0x3A00` X offset to players/objects/camera, seeds `Events_bg+$10`, and starts LBZ2 tile animation. | Implemented by `handleSeamlessReloadStage`, `SeamlessLevelTransitionRequest`, `applyLbz2TransitionLayout`, and LBZ2 animated-tile bootstrap. |

### Act 2: `LBZ2_Resize`

`LBZ2_Resize` is a two-state dynamic terrain/art hook.

| Stage | Trigger | ROM action | Current engine behavior |
|-------|---------|------------|-------------------------|
| 0 | Camera X >= `$3BC0` and camera Y >= `$0500` | Queue Death Egg 16x16, 128x128, 8x8, and Death Egg 2 art resources, then advance. | `updateAct2DeathEggTerrainSwap` loads ROM-backed resources and patches chunks, blocks, patterns, and the Knuckles pillar sheet. |
| 2 | Terminal | No further action. | `deathEggTerrainSwapApplied` prevents repeat application. |

#### Act 2 Screen Event

| Feature | ROM behavior | Current engine behavior |
|---------|--------------|-------------------------|
| `Adjust_LBZ2Layout` | Moves seam chunks, writes chunk `$DB`, rotates chunk `$DB` by 24 words, copies row data, and writes chunks `$58/$55`. | Implemented by `applyLbz2TransitionLayout` and `rotateBlockDefinition`. |
| `LBZ2_LayoutMod` | Copies six rows from FG staging columns `$94..` into columns `6..`, opening the entry corridor. | Implemented by `applyLbz2EntryCorridorMod`. |
| Transition gate | Sonic/Tails wait until Player 1 X >= `$60A`; Knuckles applies immediately. Direct act-2 starts apply immediately. | Implemented by `updateAct2EntryLayout`. |

#### Act 2 Death Egg Launch Event

The ROM's background event owns the launch state. In Java, `Obj_LBZ2RobotnikShip` requests it, and `Sonic3kLBZEvents` owns the shared state.

| ROM state | Current engine state | Notes |
|----------|----------------------|-------|
| `Events_bg+$0E = $3C` | `preLaunchDelay = 0x3C` | Decremented after launch initialization. |
| `_unkEEA2 = $1E00` | `fgLaunchSpeed = 0x1E00` | Decelerated by `LBZ2_EndFallingAccel`. |
| `_unkEE9C = $6200` | `bgLaunchSpeed = 0x6200` | Eventually becomes negative, starting detach/water disable behavior. |
| `Events_bg+$02/$06` | `fgAccum` / `bgAccum` | High-word deltas drive camera/water/ride coupling. |
| `Events_bg+$16` | `detachScroll` | Applied only to FG VScroll during platform detach. |
| `Scroll_lock` | `camera.setFrozen(true)` | Used while ship/camera are locked to the launch sequence. |
| `Event_LBZ2_DeathEgg` | `deathEggRumble` | Drives screen shake and `DEATH_EGG_RISE_LOUD` cadence. |

Current engine launch parity:

- Starts launch on `requestLaunchStart`.
- Locks max camera to X `$4390`, Y `$0668`.
- Applies the small-background reframe equivalent: copy BG row 2 to row 0 and BG row 3 to row 1.
- Moves camera right toward the rider, then releases the camera lock at the ROM bounds.
- While locked, applies FG high-word delta to both camera Y and the registered ride ship anchor.
- Moves target water level by combined FG+BG high-word delta, clamped to `$0F80`.
- Runs `LBZ2_EndFallingAccel` once pad collapse is requested.
- Disables water when BG launch speed turns negative.
- Advances detach scroll every four frames until `$28`, then clears the 3x2 platform terrain patch at FG (`$87..$89`, `$0B..$0C`).
- Final fall keeps `launchActive` set and scrolls camera Y by `-2` per frame.

Known presentation gap:

- The ROM special VInt window-plane routines `$10/$14/$18/$1C` are not fully modeled as VDP window-plane effects. The terrain, scroll, water, and event state are represented, but the exact window-copy presentation remains future render-system work.

## Parallax

### Act 1: LBZ1 Deform

Engine owner: `SwScrlLbz.updateAct1`.

| Feature | Current implementation |
|---------|------------------------|
| BG vertical scroll | `Camera_Y >> 4`, with screen shake added after normal computation. |
| BG X base | `Camera_X >> 4`, plus ROM offset `$0A` for the copied BG camera X. |
| Band table | `{ 0xD0, 0x18, 8, 8, 0x7FFF }`, applied through `DeformationPlan.applyTableBands`. |
| Custom HScroll entries | Builds the same small LBZ1 table and applies the ROM offsets `+4`, `-2`, `+7`. |
| Animated tile phase dependency | `AnimateTiles_LBZ1` consumes `Events_bg+$10 - Camera_X_pos_BG_copy`; Java computes this through `computeLbz1ScrollPhase`. |

Confidence: HIGH. The engine follows the disassembly formula and uses the shared scroll-composition framework.

### Act 2: Normal LBZ2 Deform

Engine owner: `SwScrlLbz.updateAct2`.

| Feature | Current implementation |
|---------|------------------------|
| Waterline equilibrium | Relative Y = `Camera_Y - $5F0`; BG Y fixed = half speed minus `1/8` and `1/32`; `Events_bg+$10` equivalent is `bgYWithoutBase - relativeY`. |
| BG vertical base | `bgYWithoutBase + $2C0`. |
| Waterline gradient | Builds dynamic transition bands and applies `WaterlineBlendComposer` with `LBZ_WaterlineScroll_Data`. |
| Underwater bands | Builds half-speed X bands, publishes `Camera_X_pos_BG_copy`, and publishes the `Events_bg+$12` source used by animated tiles. |
| Cloud bands | Uses the scatter offsets `{ $16, $0E, $0A, $14, $0C, $06, $18, $10, $12, $02, $08, $04, $00 }` with `cloudAccumulator += $E00`. |
| Lower BG bands | Builds the foreground/lower background fills around the waterline delta. |
| Water waves | Applies the ROM wave table to the visible underwater span. |
| Deform table | `{ $C0, $40, $38, $18, $28, $10, $10, $10, $18, $40, $20, $10, $20, $70, $30, $80E0, $20, $7FFF }`. |

Critical dependency:

- `SwScrlLbz` publishes `Events_bg+$10`, `Events_bg+$12`, and `Camera_X_pos_BG_copy` into `LbzZoneRuntimeState`. `Sonic3kPatternAnimator` reads those values for LBZ2 waterline and scroll art. Do not recompute those phases from camera position in the animator; the Death Egg path intentionally diverges.

Confidence: HIGH.

### Act 2: Death Egg Deform

Engine owner: `SwScrlLbz.updateAct2DeathEgg`.

| Feature | Current implementation |
|---------|------------------------|
| Adjusted camera Y | Subtracts screen shake, FG launch accumulator high word, and BG launch accumulator high word. |
| BG wrap latch | Persists the `$100` wrapping behavior in `deathEggDeformWrapLatch`. |
| Deform table | `{ $38, $18, $28, $10, $10, $10, $18, $40, $38, $18, $28, $10, $10, $10, $18, $40, $20, $10, $20, $70, $60, $10, $805F, $7FFF }`. |
| Waterline art phase | Publishes normal equilibrium while positive; publishes `$7FFF` once BG launch speed is negative or the computed waterline delta goes negative. |
| Scroll art phase | Preserves the previously published `Events_bg+$12` and BG camera X during Death Egg deform. |
| Cloud/table shape | Uses Death Egg-specific upper gradient, underwater range, cloud scatter, lower background, and water waves. |

Confidence: HIGH for current behavior. Remaining uncertainty is only visual parity for the VDP window-plane handoff, not the scroll math.

## Animated Tiles

Engine owner: `Sonic3kPatternAnimator` plus `S3kAnimatedTileChannels`.

### Shared LBZ Channel

- ROM: `AnimateTiles_LBZ1` / `AnimateTiles_LBZ2` first block, `ArtUnc_AniLBZ__0`.
- Engine channel: `s3k.lbz.shared`.
- Destination: VRAM tiles `$160-$16F`.
- Phase: `(channelFrameCounter >>> 2) & $0F`, with ROM `ror.w #7` source selection.
- Act 2 gating: disabled while `Anim_Counters+$F` equivalent is active.

### Act 1 Scroll Channel

- ROM: `AnimateTiles_LBZ1`, camera/BG phase block.
- Engine channel: `s3k.lbz1.scroll`.
- Destinations: starts at `$350`; follow-up cap art is appended after the variable transfer.
- Phase: `(Events_bg+$10 - Camera_X_pos_BG_copy) & $1F`.
- Source art: `ArtUnc_AniLBZ1_1` and `ArtUnc_AniLBZ1_2`.
- Confidence: HIGH.

### Act 1 Alarm/Spec Scripts

- ROM: `AniPLC_LBZ1` plus `AniPLC_LBZSpec`.
- `AniPLC_LBZ1`: `zoneanimdecl 2, ArtUnc_AniLBZ1_0, $365, 4, 8`.
- `AniPLC_LBZSpec`: shared two-script set using `ArtUnc_AniLBZ2_0` to `$170` and `ArtUnc_AniLBZ2_1` to `$175`.
- Engine gates the alarm script through `LbzZoneRuntimeState.alarmAnimationActive`, matching the ROM's alarm animation behavior.

### Act 2 Scroll Channel

- ROM: `AnimateTiles_LBZ2`, `Events_bg+$12 - Camera_X_pos_BG_copy`, mask `$0F`.
- Engine channel: `s3k.lbz2.scroll`.
- Destination: VRAM tile `$2E3`.
- Transfer size: `$20` words / `$40` bytes.
- Source art: `ArtUnc_AniLBZ2_2`.
- Phase source: `LbzZoneRuntimeState.lbz2ScrollArtPhase()`.
- Act 2 gating: disabled while persistent `Anim_Counters+$F` equivalent is active.
- Confidence: HIGH.

### Act 2 Waterline Channel

- ROM: `sub_27F66`.
- Engine channel: `s3k.lbz2.waterline`.
- Inputs: `LbzZoneRuntimeState.lbz2WaterlinePhase()` from scroll/deform.
- Delta `0`: reloads static lower BG to `$2C3` and static upper BG to `$2D3`.
- Negative delta `(-$3F..-1)`: composes `ArtUnc_AniLBZ2_WaterlineBelow` through `LBZ_WaterlineScroll_Data` into `$2C3`; deltas `<= -$40` load full below-water art.
- Positive delta `(1..$3F)`: composes `ArtUnc_AniLBZ2_WaterlineAbove` into `$2D3`; deltas `>= $40` load full above-water art.
- Death Egg behavior: once the launch path publishes `$7FFF`, the above-water static art path is selected.
- Confidence: HIGH.

### Act 2 Ride Gate

- ROM: `Obj_LBZ2RobotnikShip` executes `st (Anim_Counters+$F).w`.
- Engine state: `LbzZoneRuntimeState.lbz2RideAnimatedTileGateActive`.
- Behavior: persistent gate, not a one-frame trigger. The LBZ2 shared and scroll channels remain suppressed until explicitly cleared.
- Confidence: HIGH.

## Palette Cycling

Engine owner: `Sonic3kPaletteCycler.loadLbzCycles`.

| Act | ROM routine | Table | Engine constants | Destination | Timing |
|-----|-------------|-------|------------------|-------------|--------|
| 1 | `AnPal_LBZ1` | `AnPal_PalLBZ1` | `ANPAL_LBZ1_ADDR=0x0030B6`, size `18` | `Normal_palette_line_3+$10..+$14` | Timer reload 3; counter advances by 6 bytes and wraps at `$12`. |
| 2 | `AnPal_LBZ2` | `AnPal_PalLBZ2` | `ANPAL_LBZ2_ADDR=0x0030C8`, size `18` | `Normal_palette_line_3+$10..+$14` | Same timing and destination as act 1. |

The Java cycle writes through `PaletteOwnershipRegistry`, so underwater mirroring and competing owners are resolved by the shared palette framework.

Confidence: HIGH.

## Notable Objects

| Object | Current engine status | Route impact |
|--------|-----------------------|--------------|
| `Obj_LBZMiniboss` | Implemented by `LbzMinibossInstance` when zone set is S3KL and current zone is LBZ. | HIGH |
| `Obj_LBZMinibossBox` / Knuckles variant | Implemented by LBZ object classes and event hooks around boss-area box/layout changes. | HIGH |
| `Obj_LBZ1Robotnik` | Implemented enough to drive miniboss intro/runtime flags. | HIGH |
| `Obj_LBZ2RobotnikShip` | Implemented by `Lbz2RobotnikShipInstance`; owns player grab, camera opening, launch signal, persistent animated-tile gate, exhaust child, and rider delta consumption. | BLOCKER |
| `Obj_LBZEndBoss` | Implemented by `LbzEndBossInstance`, including art/palette loading, child platforms, spike balls, and defeat flow. | HIGH |
| `Obj_LBZFinalBoss1` | Implemented by `LbzFinalBoss1Instance`; includes ship, turret, laser children, Death Egg small art, ending palette hooks, explosion/debris children, and Tails/P2 helpers. | BLOCKER |
| `Obj_LBZFinalBossKnux` / `Obj_LBZFinalBoss2` | The handoff remains intentionally inert, invisible, and persistent because two candidate ports were rejected/unintegrated. The first (`98d968d7f`) invented phases, mapping ownership, and defeat flow; an uncommitted v2 proved articulated anchors/tables (`$AD`/`$9A`), grab, and debris and passed focused/graph/rewind guards, but root choreography, post-capsule continuation, and a Knuckles LBZ route trace remain unproven. | BLOCKER |
| `Obj_LBZ1InvisibleBarrier` | Implemented and spawned during collapse/restart paths. | HIGH |
| LBZ flame thrower, cup elevator, tunnel, bridge, launcher, grapple, pipe objects | Many are implemented in dedicated object classes or shared S3K object utilities. | MIXED |

## Cross-Cutting Concerns

- **Water:** Act 2 waterline art depends on scroll-published `Events_bg+$10`. The launch event moves target water level and disables water when BG launch speed turns negative.
- **Screen shake:** `Sonic3kLBZEvents` requests offsets from the ROM 64-byte shake table and `SwScrlLbz` consumes them during scroll composition. Death Egg rumble plays `DEATH_EGG_RISE_LOUD`; building collapse plays `BIG_RUMBLE` and `CRASH`.
- **Act transition:** `Events_fg_5` from results flow drives an immediate Java seamless reload to LBZ2 with `-0x3A00` X offset and no title-card fade.
- **Character paths:** Knuckles immediately applies LBZ2 corridor layout after transition and has alternate boss/cutscene content. Sonic/Tails wait for X `$60A` after transition before applying the corridor mod.
- **Dynamic tilemap changes:** LBZ1 interior mods, ending collapse layout, miniboss box swap, LBZ2 transition layout, entry corridor mod, Death Egg small-background reframe, Death Egg terrain/art swap, and launch-pad clear all route through `ZoneLayoutMutationPipeline` / `LevelMutationSurface`.
- **PLC/art loading:** Runtime art uses ROM-backed `ResourceLoader` and object art providers. No gameplay asset bytes are read from `docs/`.
- **Special render modes:** The Death Egg window-plane special VInt sequence is the primary remaining render-mode gap.

## Dependency Map

### Events -> Animated Tiles

- `Obj_LBZ2RobotnikShip` sets the persistent `Anim_Counters+$F` mirror, causing LBZ2 shared and scroll channels to stop updating.
- `SwScrlLbz` publishes `Events_bg+$10`, `Events_bg+$12`, and `Camera_X_pos_BG_copy` into `LbzZoneRuntimeState`; `Sonic3kPatternAnimator` reads those values for waterline and scroll art.
- LBZ1 alarm runtime state gates the LBZ1 alarm script, matching the ROM's conditional use of the spec animation.

### Events -> Parallax

- Launch state switches `SwScrlLbz` from normal LBZ2 deform to Death Egg deform.
- `fgAccum`, `bgAccum`, `bgLaunchSpeed`, `deathEggDeformWrapLatch`, `detachScroll`, and screen shake all affect the Death Egg scroll path.
- Final fall bypasses launch motion and scrolls camera upward while launch state remains active for consumers.

### Events -> Palette

- LBZ cyclic palette writes are independent table cycles. Boss objects request dedicated palette lines through object/palette owners, so the runtime does not hard-code palette writes in LBZ events.

### VRAM Ownership Table

| VRAM tile range | Owner | Condition |
|-----------------|-------|-----------|
| `$160-$16F` | LBZ shared animated art | All LBZ, except LBZ2 ride gate suppresses it. |
| `$350+` | LBZ1 scroll/cap animated art | LBZ1 only, phase from LBZ1 BG scroll. |
| `$170-$17A+` | LBZ spec/AniPLC scripts | LBZ1 spec and LBZ2 normal scripts. |
| `$2E3-$2E4` | LBZ2 scroll art | LBZ2 only, suppressed by ride gate. |
| `$2C3-$2D2` / `$2D3-$2E2` | LBZ2 waterline/lower/upper BG art | LBZ2 waterline delta driven. |
| `$000+` | Death Egg terrain art | Runtime Death Egg terrain swap. |
| `$05A0+` | Death Egg 2 / Knuckles pillar art | Runtime Death Egg terrain swap. |

### Palette Ownership Table

| Palette entry | Owner | Condition |
|---------------|-------|-----------|
| Line 3 colors 8-10 | `AnPal_LBZ1` / `AnPal_LBZ2` | Timer-driven every 4 frames. |
| Boss/object palette lines | Boss/object palette owners | Loaded/requested by object implementations. |

## Implementation Notes

### Priority Order

1. Keep LBZ2 Death Egg launch and animated-tile phase parity stable. This is traversal- and spectacle-critical.
2. Verify the VDP window-plane special VInt sequence for platform detach. Current gameplay state is modeled, but exact window-plane presentation is not.
3. Implement Knuckles' Big Arm from `Obj_LBZFinalBoss2`; the current inert handoff is a concrete route blocker, not a verification-only item. Reuse only v2 behavior that survives ROM/source review, then prove root choreography, post-capsule continuation, and a Knuckles LBZ trace.
4. Use trace/screenshot validation for the LBZ2 ride, terrain swap, and final boss route before broadening polish work.

### Framework Routing

- **Runtime state:** Keep LBZ globals in `LbzZoneRuntimeState`; do not reintroduce event-manager private state for cross-system values.
- **Palette ownership:** Keep all color changes routed through `PaletteOwnershipRegistry` or object palette owners.
- **Animated tiles:** Keep custom LBZ transfers in `AnimatedTileChannelGraph`; LBZ2 must consume scroll-published phases.
- **Layout mutations:** Keep all gameplay map/chunk/pattern changes in `ZoneLayoutMutationPipeline` / `LevelMutationSurface`.
- **Scroll composition:** Keep LBZ parallax in `SwScrlLbz` using `ScrollEffectComposer`, `DeformationPlan`, and `WaterlineBlendComposer`.
- **Render registries:** Model the remaining Death Egg VInt/window behavior through render-mode/special-render registries rather than ad hoc rendering in event code.

### Known Risks

- Special VInt/window-plane presentation is not fully represented yet.
- Knuckles final boss / Big Arm remains an inert handoff after two rejected/unintegrated attempts; port and validate `Obj_LBZFinalBoss2` against the disassembly, post-capsule continuation, and a Knuckles LBZ trace.
- Act 1 collapse and LBZ2 launch should stay under focused regression tests because they combine layout mutation, scroll effects, object anchoring, palette/art ownership, and rewind-visible runtime state.
