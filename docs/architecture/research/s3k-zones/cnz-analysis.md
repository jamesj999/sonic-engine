# S3K CNZ Zone Analysis

## Summary

- **Zone:** Carnival Night Zone (CNZ)
- **Zone Index:** 0x03
- **Zone Set:** S3KL
- **Acts:** 1 and 2
- **Water:** Yes, but only on the Act 2 Sonic/Tails route. Knuckles uses a separate late-act teleporter / capsule route with different event pressure.
- **Palette Cycling:** Yes. `AnPal_CNZ` drives 3 always-on channels and mirrors each write into both normal and water palettes.
- **Animated Tiles:** Yes. `AniPLC_CNZ` provides 7 standard scripts, and `AnimateTiles_CNZ` adds a direct DMA reload whose phase depends on `Events_bg+$10` from `CNZ1_Deform`.
- **Character Branching:** Yes. The major branch is Act 2 Knuckles, but Act 1 also has Knuckles-specific camera/Y adjustment and wall-grab suppression during the miniboss sequence.
- **Seamless Transition:** Yes. Act 1 to Act 2 happens inside `CNZ1_BackgroundEvent`, not in `_Resize`.
- **Screen Shake:** Yes. `CNZ2_BackgroundEvent` ends in `ShakeScreen_Setup`, and the miniboss arena object path triggers crash/shake beats.
- **Layout Modification:** Yes. Act 1 dynamically removes miniboss-arena chunks from the live layout and then copies BG chunks into FG for collision handoff after the miniboss.

## Events

### Dynamic Resize Stubs

**Disassembly location:** `sonic3k.asm` lines 39409-39410

- `CNZ1_Resize` is a bare `rts`.
- `CNZ2_Resize` is a bare `rts`.
- Real CNZ event logic lives in the `ScreenInit` / `ScreenEvent` / `BackgroundInit` / `BackgroundEvent` pair tables at `sonic3k.asm` lines 102266-102269.

### Act 1 Screen Events (`CNZ1_ScreenInit` / `CNZ1_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` lines 107326-107420 and 107331-107420

**`CNZ1_ScreenInit`:**
1. Calls `Reset_TileOffsetPositionActual`.
2. Refreshes the full plane.
3. Does not set up a custom event routine itself; the heavy logic is deferred to `CNZ1_ScreenEvent` and `CNZ1_BackgroundEvent`.

**`CNZ1_ScreenEvent`:**

This is not a camera-lock state machine. It is mostly a tile refresh path plus the arena-destruction consumer for data written by the miniboss object layer.

| Behavior | Trigger | Action | Notes |
|----------|---------|--------|-------|
| Direct screen refresh | `Events_bg+$06 != 0` | Clear `Events_bg+$06`, call `Refresh_PlaneScreenDirect` | One-shot full refresh request from another CNZ path |
| Normal tile drawing | otherwise | `DrawTilesAsYouMove` | Standard FG streaming |
| Arena chunk removal | `Events_bg+$00/$02 != 0` | Clear layout chunk data at the requested position, queue VRAM clears, scan the miniboss arena rows, and accumulate `Events_bg+$04` | This is the key object -> event dependency in CNZ |

**Arena-destruction flow:**

- `Obj_CNZMinibossTop` writes a chunk world position into `Events_bg+$00/$02` when it hits a wall, floor, or arena block.
- `CNZ1_ScreenEvent` consumes those coordinates, clears the corresponding chunk data from the live layout, and queues VRAM writes to blank the visual tiles.
- It then scans the 5x5 arena block area around level-layout position Y `$300`, X `$3200`.
- Every fully destroyed row increases `Events_bg+$04` by `$20`.
- `Obj_CNZMiniboss` reads `Events_bg+$04` in `CNZMiniboss_MoveDown`, temporarily switching to routine `$E` and lowering the base to match the destroyed arena height.

This is why CNZ cannot be modeled as a pure camera-threshold zone. The screen event is consuming boss-object side effects every frame.

### Act 1 Background Events (`CNZ1_BackgroundInit` / `CNZ1_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` lines 107421-107659

**`CNZ1_BackgroundInit`:**
1. Runs `CNZ1_Deform`.
2. Calls `Reset_TileOffsetPositionEff`.
3. Refreshes the full BG plane.
4. Applies `CNZ1_BGDeformArray`.

**`CNZ1_BackgroundEvent` state machine (`Events_routine_bg`):**

| Stage | Label | Trigger | Action | Notes |
|-------|-------|---------|--------|-------|
| 0 | `CNZ1BGE_Normal` | Camera X `< $3000` | Normal deform, tile-row draw, and BG deformation | Standard Act 1 play |
| 0 | `CNZ1BGE_Normal` | Camera X `>= $3000` | Enter miniboss path | If Camera Y `>= $54C`, subtract `$700` from both players and camera before continuing |
| 4 | `CNZ1BGE_BossStart` | automatic | Clamp `Camera_min_Y_pos` toward `$1C0`, run `CNZ1_BossLevelScroll`, draw BG as you move | Miniboss-path setup |
| 8 | `CNZ1BGE_Boss` | after `CNZ1_BossLevelScroll` lifts BG enough | Continue boss scroll, draw a BG column at X `$200`, plain deformation | Shared miniboss arena camera path |
| 12 | `CNZ1BGE_AfterBoss` | `Events_fg_5 == 0` | Continue boss scroll, draw BG, plain deformation, update BG diffs | Waiting for scroll-control object to finish |
| 12 | `CNZ1BGE_AfterBoss` | `Events_fg_5 != 0` | Clear `Events_fg_5`, set delayed FG refresh position `$2F0`, rowcount `$F`, advance | This is only the first post-boss handoff, not the act reload |
| 16 | `CNZ1BGE_FGRefresh` | until bottom-up draw completes | Refresh Plane A from BG chunks while continuing boss scroll | Converts the arena from BG-backed gimmick space into FG collision space |
| 16 -> 20 | refresh complete | Copy a 5x5 BG region into FG layout, clear `Background_collision_flag`, clear `Events_bg+$08`, move players/camera down by `$1C0`, reset tile offsets, set new delayed refresh state | This is the real collision/layout handoff |
| 20 | `CNZ1BGE_FGRefresh2` | until second refresh completes | Refresh Plane A again using FG chunks, continue BG draw/deformation | Finalizes the post-miniboss foreground |
| 20 -> 24 | refresh complete | Spawn `Obj_EndSign` at X `$32C0`, advance | Signpost phase |
| 24 | `CNZ1BGE_DoTransition` | `Events_fg_5 != 0` after sign lands | Load PLCs `$18/$19`, set zone/act to `$301`, reload level/solids/water/palette, offset world and camera by `-$3000,+$200`, clear routines | Seamless Act 1 -> Act 2 transition |

**Important correction versus the earlier analysis:**

- The first `Events_fg_5` signal does **not** immediately request the Act 1 -> Act 2 reload.
- It only starts the FG refresh / collision handoff.
- The actual seamless reload waits until `CNZ1BGE_DoTransition`, after the signpost phase and a second `Events_fg_5`.

**Act 1 miniboss-entry side effects in `CNZ1BGE_Normal`:**

1. If Camera Y is already low enough (`>= $54C`), player and camera Y are shifted upward by `$700`.
2. `CNZ1_BossLevelScroll` is started.
3. `Pal_CNZMiniboss` is loaded into palette line 1.
4. `Camera_min_Y_pos` is forced to `$1C0`.
5. Bit 7 of `Disable_wall_grab` is set to disable Knuckles wall grab during the arena path.

**Helper routines:**

- `CNZ1_ScrollToYStart` adjusts `Camera_min_Y_pos` toward a target Y floor.
- `CNZ1_BossLevelScroll` transitions from entry scroll into the steady boss scroll once BG Y reaches `$1E0`.
- `CNZ1_BossLevelScroll2` uses `(Camera_Y_pos_copy - $100 + Events_bg+$08)` for BG Y and `(Camera_X_pos_copy - $2F80)` for BG X.

### Act 2 Screen Events (`CNZ2_ScreenInit` / `CNZ2_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` lines 107876-107964

**`CNZ2_ScreenInit`:**
1. Calls `Reset_TileOffsetPositionActual`.
2. Refreshes the full plane.

**Act 2 pre-branch camera behavior:**

Before dispatching the stage machine, `CNZ2_ScreenEvent` does two pieces of always-on setup:

1. Adds `Screen_shake_offset` into `Camera_Y_pos_copy`.
2. If Player X is still left of `$4600`, adjusts `Camera_min_Y_pos`:
   - Player X `< $940` -> min Y becomes `$580`
   - otherwise -> min Y becomes `0`

This keeps the old Act 1 boss arena / lower route constraints active until the player is far enough right.

**`CNZ2_ScreenEvent` state machine (`Events_routine_fg`):**

| Stage | Label | Trigger | Action | Notes |
|-------|-------|---------|--------|-------|
| 0 | unnamed entry branch | Sonic or Tails | Set FG routine to 8 and fall into normal draw path | Sonic/Tails skip the special route entirely |
| 0 | unnamed entry branch | Knuckles X `>= $4880` and Y `>= $0B00` | Spawn `Obj_CNZTeleporter`, spawn `Obj_EggCapsule` at `$4980,$0A20`, load `PLC_EggCapsule`, clamp camera X to `$4750..$48E0`, clear `End_of_level_flag`, advance to stage 4 | This is much more than a flag toggle |
| 4 | second stage | `End_of_level_flag != 0` | Spawn `Obj_IncLevEndXGradual`, set `Camera_stored_max_X_pos=$49A0`, restore level music and player control, advance to stage 8 | Completion path for Knuckles teleporter ending |
| 8 | `CNZ2SE_Normal` | default | `DrawTilesAsYouMove` | Normal Act 2 FG streaming |

**Teleporter object notes (`Obj_CNZTeleporter`, line 108031):**

- Prevents Knuckles from overshooting the teleporter while airborne.
- Clears player X velocity and ground velocity.
- Locks controls and clears logical input.
- Queues `ArtKosM_CNZTeleport`.
- Writes a small set of teleporter colors directly into palette line 2.
- After art finishes loading and Knuckles lands, spawns `Obj_TeleporterBeam` and plays `sfx_Charging`.

The earlier CNZ analysis treated this route as a background-mode flag. In ROM it is a full cutscene / control / art / camera side-effect chain.

### Act 2 Background Events (`CNZ2_BackgroundInit` / `CNZ2_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` lines 107965-108030

**`CNZ2_BackgroundInit`:**
1. Sets `Events_routine_bg = 8`.
2. Runs `CNZ1_Deform`.
3. Calls `Reset_TileOffsetPositionEff`.
4. Refreshes the full BG plane.
5. Applies `CNZ1_BGDeformArray`.

**`CNZ2_BackgroundEvent` state machine (`Events_routine_bg`):**

| Stage | Label | Trigger | Action | Notes |
|-------|-------|---------|--------|-------|
| 0 | `loc_52238` | only if BG routine is manually reset to 0 | Bottom-up plane refresh using Y `$200`; when complete, rerun deform, reset effective offsets, seed delayed refresh position, advance to stage 4 | Transitional refresh path |
| 4 | `loc_52266` | transitional | Continue bottom-up refresh from `Camera_Y_pos_BG_copy`; advance to stage 8 when done | Transitional refresh continuation |
| 8 | `loc_5227C` / shared tail | normal Act 2 entry from init | Run `CNZ1_Deform`, draw BG row, apply `CNZ1_BGDeformArray`, call `ShakeScreen_Setup` | Standard Act 2 BG path |

**Important nuance:**

- `CNZ2_BackgroundInit` starts the act at BG routine 8.
- So stages 0 and 4 exist, but the stock act entry does **not** traverse them.
- Any engine model that assumes routine 0 is the default Act 2 steady state is already off.

## Boss Sequences

### Act 1 Miniboss

**Core objects:**

- `Obj_CNZMinibossScrollControl` at `sonic3k.asm` line 107734
- `Obj_CNZMiniboss` at `sonic3k.asm` line 144823
- `Obj_CNZMinibossTop`, `Obj_CNZMinibossCoil`, timed sparks, sparks, bounce effect, debris in the same object section

**Arena setup and lock:**

1. `Obj_CNZMiniboss` waits until Camera X reaches `$31E0`.
2. It stores the old camera max X, clamps the arena to X `$31E0..$3260`, sets min Y `$1C0`, max Y / target max Y `$2B8`.
3. It fades out music, sets `Boss_flag`, loads PLC `$5D`, and loads `Pal_CNZMiniboss`.
4. After the wait, it starts miniboss music and spawns `Obj_CNZMinibossScrollControl`.

**Why the scroll-control object matters:**

`Obj_CNZMinibossScrollControl` is the real owner of the miniboss-path BG scroll speed:

- It accelerates `Events_bg+$0C` up to `$40000`.
- It accumulates that into `Events_bg+$08`.
- On boss completion it clears the first `Events_fg_5`, then transitions through wait / slow / wait stages until the BG offset lands on exact `$100` boundaries.
- It restores `Camera_target_max_Y_pos = $1000`.
- It re-enables BG collision and advances `Events_routine_bg`.
- It clears a FG strip in the layout.
- Finally, when `Events_bg+$08 >= $1C0`, it sets `Events_fg_5` again and deletes itself.

That second `Events_fg_5` is the one `CNZ1BGE_AfterBoss` uses to begin the FG refresh path.

**Miniboss combat logic:**

- The base starts with 6 collision hits but internally uses `$45(a0)` as the real top-hit count, initialized to 4.
- The boss has a closed state, an opening state, a coil-open exposed state, and a closing state.
- Hitting the closed body does not damage the boss; it opens the coil and starts palette-rotation spark behavior.
- The moving top piece (`Obj_CNZMinibossTop`) is the real attack vector and also destroys arena blocks on collision.
- `CNZMinibossTop_CheckHitBase` only sets the parent hit flag when the coil was already open.
- On defeat, `CNZMiniboss_BossDefeated` sets `Events_fg_5`, starts the boss explosion flow, and stops the timer.

**Post-defeat flow:**

- `Obj_CNZMinibossEnd` sets `_unkFAA8`, spawns debris, and transitions to `Obj_EndSignControlAwaitStart`.
- `Obj_CNZMinibossEndGo` clears `Boss_flag`, runs `AfterBoss_Cleanup`, and loads `PLC_EndSignStuff`.
- The BG event sequence then performs the FG/BG handoff and seamless transition described above.

### Act 2 End Boss

**Disassembly location:** `Obj_CNZEndBoss` at `sonic3k.asm` line 145796

This object surface was missing from the earlier CNZ analysis almost entirely.

**Startup:**

1. Uses `Check_CameraInRange` against a CNZ-specific arena range.
2. Loads PLC `$6E`.
3. Loads `Pal_CNZEndBoss`.
4. Stores `mus_EndBoss` as the boss-saved music.

**State shape:**

- Routines at `off_6E4E2` cover at least 8 states.
- The boss oscillates with `Swing_UpAndDown`, does timed waits, horizontal facing / tracking, child-object spawning, and a later capsule-release sequence.
- `loc_6E6E4` clears `Boss_flag`, sets `_unkFAA8`, and spawns `Obj_EggCapsule` at `$4990,$2E0`.
- `loc_6E724` later restores player control and level music, adjusts camera stored bounds, and starts follow-up child motion controllers.

This is relevant to CNZ analysis even if the branch implementation has not touched the boss yet, because the Act 2 route is not just "spawn end boss." It has its own PLC, palette, child objects, and post-boss control restoration.

## Parallax

### Shared Deform (`CNZ1_Deform`)

**Disassembly location:** `sonic3k.asm` lines 107660-107710

CNZ uses one deform core for both acts.

**BG Y formula:**

- Starts from `Camera_Y_pos_copy`.
- Subtracts `Screen_shake_offset`, computes a fractional scaled value, then adds shake back.
- Effective BG Y is approximately `13/128` of camera Y, with shake folded into the calculation.

**BG X / HScroll pipeline:**

1. Start from camera X in fixed-point form.
2. Compute a base half-speed term.
3. Derive `d1` as one eighth of that half-speed term, which is effectively `1/16` of camera X.
4. Write five HScroll values corresponding to approximately:
   - `1/2`
   - `7/16` (also stored as `Camera_X_pos_BG_copy`)
   - `1/4`
   - `1/8`
   - `1/32`
5. Store the intermediate `5/16` term in `Events_bg+$10` for animated-tile phase control.

**Key dependency:**

- `Camera_X_pos_BG_copy` is the 7/16-speed CNZ BG X.
- `Events_bg+$10` is the 5/16-speed CNZ dynamic-art phase source.
- `AnimateTiles_CNZ` consumes both.

So the CNZ custom art phase is **not** a free-running counter and **not** equal to BG X. It is the difference between two distinct deform outputs.

### Deformation Bands

**BG deform array:** `CNZ1_BGDeformArray` at line 107874

| Band | Height | Notes |
|------|--------|-------|
| 0 | `$80` | Upper band |
| 1 | `$30` | Second band |
| 2 | `$60` | Third band |
| 3 | `$C0` | Large lower band |
| end | `$7FFF` | Terminator |

Both acts use this same deform array. There is no separate `CNZ2_Deform`.

## Animated Tiles

### Custom Wrapper (`AnimateTiles_CNZ`)

**Disassembly location:** `sonic3k.asm` lines 54368-54410

`AnimateTiles_CNZ` does direct DMA before the generic AniPLC path.

**Phase formula:**

`(Events_bg+$10 - Camera_X_pos_BG_copy) & $3F`

Given the deform outputs above, this is effectively:

- `(5/16-speed term - 7/16-speed term) & $3F`
- which produces a 64-step phase used to select two DMA slices from `ArtUnc_AniCNZ__6`

**Behavior:**

1. Read the 6-bit phase.
2. If unchanged from the prior frame, skip the DMA work.
3. Split the phase into:
   - low 3 bits for bank/offset selection
   - upper bits for word-count pair selection through `word_27C9C`
4. DMA the first chunk to VRAM tile `$308`.
5. Optionally DMA a second chunk to the next tile range.
6. Advance `Anim_Counters` and then fall through to the generic AniPLC runner.

This is a direct parallax -> animated-tiles dependency. It is the most important shared-state edge in CNZ outside the miniboss event system.

### `AniPLC_CNZ`

**Disassembly location:** `sonic3k.asm` lines 55716 onward

CNZ uses 7 standard AniPLC scripts for both acts.

| Script | Source | Dest Tile | Decl |
|--------|--------|-----------|------|
| 0 | `ArtUnc_AniCNZ__0` | `$2B2` | `zoneanimdecl 3, ..., $10, 9` |
| 1 | `ArtUnc_AniCNZ__0` | `$2BB` | Same art, phase-shifted source list |
| 2 | `ArtUnc_AniCNZ__1` | `$2C4` | `zoneanimdecl 3, ..., $10, $10` |
| 3 | `ArtUnc_AniCNZ__2` | `$2D4` | `zoneanimdecl 3, ..., 8, $20` |
| 4 | `ArtUnc_AniCNZ__3` | `$2F4` | `zoneanimdecl 3, ..., 8, $10` |
| 5 | `ArtUnc_AniCNZ__4` | `$304` | Later in the script table |
| 6 | `ArtUnc_AniCNZ__5` | `$328` | Later in the script table |

**Ownership note:**

- `AniPLC_CNZ` owns `$2B2-$307` and `$328+`.
- `AnimateTiles_CNZ` owns `$308+` via direct DMA.
- That handoff is clean only if the engine preserves the ROM phase calculation.

## Palette Cycling

### Shared Routine (`AnPal_CNZ`)

**Disassembly location:** `sonic3k.asm` lines 3319-3366

`AnPal_CNZ` always mirrors writes into both the normal and water palettes. The earlier CNZ analysis understated this by treating underwater parity as optional.

| Channel | Delay / Timer | Counter | Step | Limit | Normal Target | Water Target | Table |
|---------|---------------|---------|------|-------|---------------|--------------|-------|
| 1 | `Palette_cycle_counter1`, reload 3 | `Palette_cycle_counter0` | `+6` | `$60` | `Normal_palette_line_4+$12/$16` -> palette line 4 colors 9-11 | `Water_palette_line_4+$12/$16` | `AnPal_PalCNZ_1` / `_2` |
| 2 | no extra delay | `Palette_cycle_counters+$02` | `+6` | `$B4` | `Normal_palette_line_3+$12/$16` -> palette line 3 colors 9-11 | `Water_palette_line_3+$12/$16` | `AnPal_PalCNZ_3` / `_4` |
| 3 | `Palette_cycle_counters+$08`, reload 2 | `Palette_cycle_counters+$04` | `+4` | `$40` | `Normal_palette_line_3+$0E` -> palette line 3 colors 7-8 | `Water_palette_line_3+$0E` | `AnPal_PalCNZ_5` |

**Table locations:**

- `AnPal_PalCNZ_1`: line 4093
- `AnPal_PalCNZ_3`: line 4111
- `AnPal_PalCNZ_5`: line 4143
- `AnPal_PalCNZ_2`: line 4161
- `AnPal_PalCNZ_4`: line 4179

**Conflict note:**

- `Pal_CNZMiniboss` and `Pal_CNZEndBoss` both target palette line 1.
- `AnPal_CNZ` targets palette lines 3 and 4.
- So the major palette conflict risk is not boss overlap; it is forgetting the mirrored underwater writes.

## Water System

### CNZ Act 2 Water Helpers

**Disassembly location:**

- `Obj_CNZWaterLevelCorkFloor` at `sonic3k.asm` line 134025
- `Obj_CNZWaterLevelButton` at `sonic3k.asm` line 134053

These objects were missing from the first CNZ pass but are part of the zone's actual behavior surface.

### `Obj_CNZWaterLevelCorkFloor`

Behavior:

1. Waits offscreen.
2. Spawns `Obj_CorkFloor` as a child at its own position with subtype 1.
3. Stores the child object pointer at `$44(a0)`.
4. Watches for the child to stop being `loc_2A5F8` (the live cork-floor object state).
5. If `_unkFAA2` is still clear, sets `_unkFAA2` and writes `Target_water_level = $958`.
6. Deletes itself.

Interpretation:

- Breaking or exhausting the cork-floor object is what kicks the water target upward to `$958`.
- This is not part of the zone event layer; it is object-driven water control.

### `Obj_CNZWaterLevelButton`

Behavior:

1. Uses cutscene-button art / PLC.
2. Adds 4 pixels to its Y position on init.
3. Every frame, runs solid collision and checks whether the player is standing on it.
4. If pressed and `_unkFAA3` is set:
   - clears `_unkFAA3`
   - sets `Target_water_level = $A58`
   - plays `sfx_Geyser`
   - spawns another helper object at `loc_62480` with subtype set

Interpretation:

- The button only changes water when another CNZ object path has armed `_unkFAA3`.
- So CNZ water parity is not just "Act 2 has water." It is an object-gated water sequence with explicit target heights and SFX.

## Notable Objects

The earlier CNZ analysis had the broad inventory, but the key point is that many of CNZ's real gameplay mechanics are object-driven rather than event-routine-driven.

| Object ID | Name | Role |
|-----------|------|------|
| `$41-$4E` | CNZ gimmick range | Balloons, cannon, rising platform, trap door, light bulb, hover fan, cylinder, vacuum tube, giant wheel, bumper set, spiral tube, wire cage, and related gimmicks |
| `$88` | `CNZWaterLevelCorkFloor` | Object-driven water target change |
| `$89` | `CNZWaterLevelButton` | Object-gated water target change |
| `$A3` | `Clamer` | CNZ badnik |
| `$A4` | `Sparkle` | CNZ badnik |
| `$A5` | `Batbot` | CNZ badnik |
| `$A6` | `CNZMiniboss` | Act 1 miniboss |
| `$A7` | `CNZEndBoss` | Act 2 boss |

## Dependency Map

### Objects -> Screen Events

- `Obj_CNZMinibossTop` writes `Events_bg+$00/$02` -> `CNZ1_ScreenEvent` destroys arena chunks and queues VRAM clears.
- `CNZ1_ScreenEvent` scans destroyed rows and accumulates `Events_bg+$04` -> `Obj_CNZMiniboss` reads it and lowers the boss base.

### Objects -> Background Events

- `Obj_CNZMinibossScrollControl` owns `Events_bg+$08/$0C` -> `CNZ1_BossLevelScroll2` uses `Events_bg+$08` in the BG Y formula.
- `Obj_CNZMinibossScrollControl` sets `Events_fg_5` at the end of its wait chain -> `CNZ1BGE_AfterBoss` begins the FG refresh sequence.
- `CNZMiniboss_BossDefeated` also sets `Events_fg_5` earlier -> scroll-control consumes that first signal and begins its slowdown sequence.

### Parallax -> Animated Tiles

- `CNZ1_Deform` writes `Events_bg+$10` and `Camera_X_pos_BG_copy`.
- `AnimateTiles_CNZ` reads both and DMA-loads tile `$308`.
- This is the critical parity dependency currently under-modeled in the engine branch.

### Events -> Character Control

- `CNZ1BGE_Normal` sets bit 7 of `Disable_wall_grab` during the miniboss path.
- `CNZ1BGE_DoTransition` clears that bit after the seamless reload.
- `CNZ2_ScreenEvent` teleporter branch locks controls, then later restores music/control after the route completes.

### Objects -> Water

- `Obj_CNZWaterLevelCorkFloor` writes `Target_water_level = $958`.
- `Obj_CNZWaterLevelButton` writes `Target_water_level = $A58` when `_unkFAA3` is armed.

### Palette Ownership

| Target | Owner | Condition |
|--------|-------|-----------|
| Palette line 1 | `Pal_CNZMiniboss`, `Pal_CNZEndBoss` | Boss entry |
| Palette line 2 teleporter colors | `Obj_CNZTeleporter` | Knuckles teleporter route |
| Palette lines 3-4 | `AnPal_CNZ` | Always-on cycling, mirrored to water palette too |

## Current Branch Parity Gaps

These are the important mismatches in `feature/ai-s3k-cnz-bring-up` after the first-wave implementation.

1. **Act 1 BG state machine is still collapsed too aggressively.**
   - `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
   - Current code turns the first `eventsFg5` into an immediate foreground refresh + act transition request.
   - ROM uses a longer chain: boss defeat signal -> scroll-control slowdown / wait path -> `CNZ1BGE_AfterBoss` -> FG refresh -> FG copy / collision handoff -> second refresh -> end sign -> `CNZ1BGE_DoTransition`.

2. **Knuckles Act 2 teleporter path is reduced to a mode flag.**
   - `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
   - ROM spawns `Obj_CNZTeleporter`, spawns `Obj_EggCapsule`, loads `PLC_EggCapsule`, clamps camera X, clears `End_of_level_flag`, later restores music/control, and uses teleporter-beam object flow.
   - Current branch records the mode but does not model those gameplay side effects.

3. **`AnimateTiles_CNZ` phase is not parity-correct yet.**
   - `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
   - `computeCnzPhase()` currently hardcodes `eventsBg10 = 0`.
   - ROM requires `(Events_bg+$10 - Camera_X_pos_BG_copy) & $3F`, where `Events_bg+$10` is the distinct 5/16-speed deform output.

4. **CNZ palette cycling still omits underwater palette mirroring.**
   - `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
   - ROM writes every CNZ channel to both normal and water palette targets.
   - Current branch keeps TODOs for underwater sync, so underwater CNZ palette parity is still incomplete.

## Implementation Notes

### Why CNZ Looked Shallower Than LBZ

CNZ is not meaningfully "simpler" than LBZ in the way the earlier write-up implied. Its complexity is just distributed differently:

- `_Resize` is trivial, so a resize-first read misses most of the zone.
- Act 1 orchestration is split across `ScreenEvent`, `BackgroundEvent`, `Obj_CNZMiniboss`, and `Obj_CNZMinibossScrollControl`.
- The most important animated-tile path depends on parallax state rather than on a local tile timer.
- Act 2 character branching is concentrated in a teleporter / capsule object chain rather than a large `_Resize` routine.
- Water behavior is partly owned by dedicated objects instead of zone-event code.

### Recommended Next Pass

1. Fix `Sonic3kCNZEvents` to model the real post-miniboss BG chain before changing more CNZ systems.
2. Expose or reconstruct the true `Events_bg+$10` deform output for `AnimateTiles_CNZ`.
3. Mirror `AnPal_CNZ` into the water palette to match ROM.
4. Treat the Knuckles teleporter path and CNZ water helpers as object bring-up work, not as "event flags."
