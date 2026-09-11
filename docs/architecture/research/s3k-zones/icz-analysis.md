# S3K ICZ Zone Analysis

## Summary

- **Zone:** IceCap Zone (ICZ)
- **Zone Index:** 0x05
- **Zone Set:** S3KL
- **Acts:** 1 and 2
- **Water:** Yes, Act 2 only. The Act 1 -> Act 2 transition explicitly runs `CheckLevelForWater` and enables H-Interrupt for the water path.
- **Palette Cycling:** Yes, 4 channels shared by both acts (`AnPal_ICZ`)
- **Animated Tiles:** Yes. ICZ uses both a generic AniPLC script (`AniPLC_ICZ`) and a custom `AnimateTiles_ICZ` DMA updater.
- **Character Branching:** Yes. Knuckles gets a teleporter intro in Act 1 and skips the Big Snow Fall setup path once the first ICZ1 trigger trips.
- **Unique Mechanics:** Snowboarding intro/skip path, Big Snow Fall + quake sequence, looping Act 1 camera setup, indoor/outdoor palette + background swaps in Act 2
- **Seamless Transition:** Yes. Act 1 to Act 2 is a BG-event-driven reload with object/camera offsets `-$6880 X`, `+$0100 Y`
- **Screen Shake:** Yes. Act 1 intro avalanche uses `Screen_shake_flag`, `Screen_shake_offset`, control lock, and periodic rumble SFX
- **Looping Geometry:** Yes. Act 1 explicitly sets `Screen_Y_wrap_value = $07FF`, `Camera_Y_pos_mask = $07F0`, and `Layout_row_index_mask = $003C`

## Current Engine Status

- **Events:** Not implemented. `Sonic3kLevelEventManager` only wires AIZ and HCZ handlers.
- **Parallax / scroll handler:** Not implemented. `Sonic3kScrollHandlerProvider` has no ICZ case.
- **Animated tiles:** Not implemented. `Sonic3kPatternAnimator` does not resolve `AniPLC_ICZ` and has no `AnimateTiles_ICZ` custom updater.
- **Palette cycling:** Implemented and tested in `Sonic3kPaletteCycler` / `TestS3kIczPaletteCycling`.

## Level Start / Bootstrap

### Spawn and Intro Routing

ICZ has multiple distinct entry paths before the resize routine does anything:

| Path | Trigger | Effect |
|------|---------|--------|
| Sonic/Tails normal Act 1 | Default `Current_zone_and_act == $0500` | Starts in the snowboard intro flow / outdoor intro background |
| Tails-alone Act 1 skip | `Current_zone_and_act == $0500` and `Player_mode == 2` | Repositions player/camera to skip the snowboard sequence entirely |
| Knuckles Act 1 teleporter | `ICZ1_ScreenInit`, no lamppost restart, `Player_mode == 3` | Spawns `Obj_ICZTeleporter`, sets player/camera into the teleporter intro setup |
| Lamppost restart | `Last_star_post_hit != 0` | Skips the teleporter bootstrap branch in `ICZ1_ScreenInit` |

### Tails-alone Snowboard Skip

**Disassembly location:** `sonic3k.asm` lines 38184-38206

If `Current_zone_and_act == $0500` and `Player_mode == 2`, the ROM bypasses the snowboard intro:

- `Camera_min_X_pos = Camera_target_min_X_pos = Camera_min_X_pos_P2 = $35A0`
- `Player_1+x_pos = $3780`
- `Camera_X_pos = Camera_X_pos_P2 = $36F0`
- `Player_1+y_pos = $01E0`
- `Camera_min_Y_pos = Camera_target_min_Y_pos = Camera_min_Y_pos_P2 = $0200`
- `Camera_Y_pos = Camera_Y_pos_P2 = $0200`

This is a significant cross-cutting constraint for validation because it means ICZ1 does not have a single canonical opening state across all character modes.

## Events

### Act 1 Screen Events (`ICZ1_ScreenInit` / `ICZ1_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` lines 110046-110111

#### `ICZ1_ScreenInit`

When the player is Knuckles, not restarting from a lamppost:

1. Allocates `Obj_ICZTeleporter`
2. Sets `Player_1` position to `($3640, $0660)`
3. Sets camera position to `($35A0, $05FB)`

Regardless of character after that branch:

1. `Screen_Y_wrap_value = $07FF`
2. `Camera_Y_pos_mask = $07F0`
3. `Layout_row_index_mask = $003C`
4. `Reset_TileOffsetPositionActual`
5. `Refresh_PlaneFull`

This confirms ICZ1 is treated as a looping level from the start.

#### `ICZ1_ScreenEvent`

`ICZ1_ScreenEvent` dispatches from `Events_routine_bg`, not a separate foreground counter:

| Stage | Label | Action | Notes |
|-------|-------|--------|-------|
| 0 | `ICZ1SE_Init` | If `Screen_shake_flag != 0` and controls are not already locked: set `Ctrl_1_locked`, clear `Ctrl_1_logical`; add `Screen_shake_offset` to `Camera_X_pos_copy` | Horizontal shake / input suppression during avalanche setup |
| 4 | `ICZ1SE_WaitQuake` | Add `Screen_shake_offset` to `Camera_Y_pos_copy` | Vertical shake stage |
| 8+ | `ICZ1SE_Normal` | `DrawTilesAsYouMove` | All later screen-event states fall through to the normal draw path |

**Implementation note:** The screen-event layer and the background-event layer share `Events_routine_bg`, so an engine port needs to preserve both the camera-copy mutations and the background-state dispatch from the same effective routine value.

### Act 1 Foreground (`ICZ1_Resize`)

**Disassembly location:** `sonic3k.asm` lines 39416-39452

**State machine (`Dynamic_resize_routine`):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 | `Camera_X >= $3700` and `Camera_Y >= $68C` | Set `Events_fg_5`, advance | First trigger that moves BG logic out of the intro flow |
| 2 | `Camera_X >= $3940` | Set `Events_fg_5`, advance | Triggers the indoor conversion / BG refresh path |
| 4 | None | `rts` | Terminal |

**Notes:**
- This FG routine is small; the meaningful ICZ1 sequencing lives in the BG event routine.
- `Events_fg_5` is a one-shot signal consumed by `ICZ1_BackgroundEvent`.

**Confidence:** HIGH

### Act 1 Background (`ICZ1_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` lines 110150-110321

**State machine (`Events_routine_bg`, stride 4):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 `ICZ1BGE_Intro` | `Events_fg_5` set | Clears flag. Sonic/Tails: spawns `Obj_ICZ1BigSnowPile`, clears `Events_bg+$00/$04`, starts `ICZ1_BigSnowFall`, advances to stage 4. Knuckles: skips directly toward the next phase. | This is the avalanche / quake intro handoff. |
| 0 `ICZ1BGE_Intro` | Otherwise | Runs `ICZ1_IntroDeform`, draws intro BG, applies `ICZ1_IntroBGDeformArray`, runs shake setup | Normal intro background |
| 4 `ICZ1BGE_SnowFall` | `Events_fg_5` set | Clears flag, sets delayed BG refresh position/row count, advances to stage 8 | Second trigger into the indoor transition |
| 4 `ICZ1BGE_SnowFall` | Otherwise | Sonic/Tails keep running `ICZ1_BigSnowFall`; Knuckles skips the snow logic | Still in avalanche section |
| 8 `ICZ1BGE_Refresh` | Until vertical refresh completes | Draw bottom-up plane refresh; once complete, runs `ICZ1_Deform`, resets effective tile offsets, seeds another delayed refresh, advances to stage 12 | First part of indoor BG rebuild |
| 12 `ICZ1BGE_Refresh2` | Until vertical refresh completes | Runs `ICZ1_Deform`; once complete, sets `Events_bg+$16`, loads indoor palette, advances to stage 16 | `Events_bg+$16` is the indoor flag later reused by `AnPal_ICZ` |
| 16 `ICZ1BGE_Normal` | `Camera_X >= $6900` | Queues ICZ2 secondary chunks/blocks/art, loads PLC `$20`, advances to stage 20 | Seamless Act 1 -> Act 2 prep |
| 16 `ICZ1BGE_Normal` | Otherwise | Runs `ICZ1_Deform`, `DrawBGAsYouMove`, `PlainDeformation` | Normal indoor ICZ1 scrolling |
| 20 `ICZ1BGE_Transition` | Kos queue empty | Switches to `Current_zone_and_act = $0501`, reloads ICZ2, checks water, loads palette `$15`, offsets players/objects/camera by `-$6880 X`, `+$100 Y`, updates wrap masks and bounds, resets BG routine | Seamless Act 1 -> Act 2 transition |

#### `ICZ1BGE_Intro` details

- `Events_fg_5` is consumed and cleared here
- Sonic/Tails path:
  - Allocates `Obj_ICZ1BigSnowPile`
  - Clears `Events_bg+$00` and `Events_bg+$04`
  - Calls `ICZ1_BigSnowFall`
  - Resets tile offsets and advances the routine
- Knuckles path:
  - Skips the snowpile object allocation and jumps toward the next state

#### `ICZ1_BigSnowFall`

**Disassembly location:** `sonic3k.asm` lines 110379-110410

- Uses `Events_bg+$00` as the accumulated vertical snow offset
- Uses `Events_bg+$04` as an acceleration term, increasing by `$2400` per frame
- While `Events_bg+$00 > -$12E`:
  - Sets `Screen_shake_flag`
  - Advances the snow movement
  - Plays `sfx_Rumble2` every 16th frame
- Once the lower bound is reached:
  - Converts the shake from constant/negative to a timed shake of `4`
  - Clamps `Events_bg+$00 = -$12E`
- Then projects a snow-specific background camera:
  - `Camera_Y_pos_BG_copy = Camera_Y_pos_copy - $460 + Events_bg+$00`
  - `Camera_X_pos_BG_copy = Camera_X_pos_copy - $1D40`

#### `Obj_ICZ1BigSnowPile`

**Disassembly location:** `sonic3k.asm` lines 110433-110477

- Fixed object position: `x = $3880`
- Render/collision profile:
  - `height_pixels = $80`
  - sloped solid with width parameter `$94`
  - slope data from `ICZ1_SnowpileSlopeDef`
- Deletes itself once `Events_routine_bg >= 8`
- When its Y reaches `$70E` and player control is locked:
  - waits for the player to stand grounded
  - waits for jump input
  - then forcibly unlocks control and injects a jump:
    - `y_vel = -$600`
    - sets `Status_InAir`
    - `jumping = 1`
    - `anim = 2`
    - sets roll status and plays `sfx_Jump`

**Cross-cutting details:**
- `Events_bg+$16` is the indoor/outdoor mode flag for both Act 1 indoor state and `AnPal_ICZ`'s conditional channels.
- The transition path is BG-driven; ICZ is explicitly excluded from the standard Act 1 results-screen `Events_fg_5` transition path in engine code.
- The BG routine is also the source of the screen-event shake stages, so stage numbers affect both draw logic and parallax/event behavior.

**Confidence:** HIGH

### Act 2 Foreground (`ICZ2_Resize`)

**Disassembly location:** `sonic3k.asm` lines 39454-39476

**State machine (`Dynamic_resize_routine`):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 | `Camera_X >= $740` and `Camera_Y < $400` | Set `Camera_min_X = $740`, advance | Early forward camera lock in Act 2 |
| 2 | None | `rts` | Terminal |

This is much smaller than ICZ1 and exists mainly to prevent backtracking once the player has climbed high enough early in the act.

**Confidence:** HIGH

### Act 2 Screen Events (`ICZ2_ScreenInit` / `ICZ2_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` lines 110650-110658

#### `ICZ2_ScreenInit`

- `Reset_TileOffsetPositionActual`
- `Refresh_PlaneFull`

#### `ICZ2_ScreenEvent`

- Directly jumps to `DrawTilesAsYouMove`
- No separate screen-event state machine beyond the background-event-driven indoor/outdoor switching

### Act 2 Background (`ICZ2_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` lines 110703-110822, deform helpers in `Lockon S3/Screen Events.asm`

#### `ICZ2_BackgroundInit`

**Disassembly location:** `sonic3k.asm` lines 110659-110702

Initialization starts with `Events_routine_bg = 4`, then picks one of two starting modes:

**Outdoor start if either condition is true:**
- `Camera_X >= $3600`
- or `Camera_X < $1000` and `Camera_Y < $580`
- or `Camera_X < $720` after the later palette split

**Indoor start if the player begins in the central cave region:**
- `Camera_X < $3600`
- `Camera_X >= $1000` or `Camera_Y >= $580`
- and `Camera_Y >= $720` for the direct indoor branch

Outdoor init:

1. Clears `Events_bg+$16`
2. If `Camera_X < $720`, loads `ICZ2_SetICZ1Pal`; otherwise loads `ICZ2_SetOutdoorsPal`
3. Runs `ICZ2_OutDeform`
4. Refreshes the full plane
5. Applies `ICZ2_OutBGDeformArray`

Indoor init:

1. Sets `Events_bg+$16`
2. Loads `ICZ2_SetIndoorsPal`
3. Runs `ICZ2_InDeform`
4. Resets effective tile offsets
5. Refreshes the full plane
6. Applies `ICZ2_InBGDeformArray`

**State machine (`Events_routine_bg`, stride 4):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 `ICZ2BGE_FromICZ1` | First frame after Act 1 transition | Advances to stage 4, then decides whether the opening background state should be indoors or outdoors based on camera Y | Only meaningful when entering from ICZ1 |
| 4 `ICZ2BGE_Normal` outdoors | `1000 <= Camera_X < 3600` and `Camera_Y >= $720` | Sets `Events_bg+$16`, loads indoor palette, runs `ICZ2_InDeform`, schedules BG refresh, advances to stage 8 | Outdoor -> indoor switch |
| 4 `ICZ2BGE_Normal` indoors | Leaving the indoor region, or dropping below the indoor thresholds | Clears `Events_bg+$16`, loads either ICZ1 or outdoor palette based on `Camera_X < $720`, runs `ICZ2_OutDeform`, schedules BG refresh, advances to stage 8 | Indoor -> outdoor switch |
| 4 `ICZ2BGE_Normal` indoors | `Camera_X` in `$1900-$1B80` | Stay indoors regardless of the broader check | Special interior span |
| 8 `ICZ2BGE_Refresh` | Until refresh completes | Uses the active indoor/outdoor deform path, draws bottom-up refresh, then drops back to stage 4 | Refresh stage after mode change |

#### Indoor / outdoor threshold logic

The act uses three distinct palette/background states:

| State | Condition | Palette routine | Deform routine |
|-------|-----------|-----------------|----------------|
| ICZ1-like outdoor | `Camera_X < $720` and outdoors | `ICZ2_SetICZ1Pal` | `ICZ2_OutDeform` |
| Standard outdoor | outdoors and `Camera_X >= $720` | `ICZ2_SetOutdoorsPal` | `ICZ2_OutDeform` |
| Indoor cave | `Events_bg+$16 != 0` or central indoor thresholds matched | `ICZ2_SetIndoorsPal` | `ICZ2_InDeform` |

The “central indoor thresholds” are not a single rectangle:

- Normal outdoors -> indoors trigger:
  - `Camera_X >= $1000`
  - `Camera_X < $3600`
  - `Camera_Y >= $720`
- Indoors -> outdoors trigger:
  - if `Camera_X < $1000`
  - or `Camera_X >= $3600`
  - or `Camera_Y < $720`, except the forced-indoor span `$1900 <= Camera_X < $1B80`

**Notes:**
- ICZ2 does not have a complex boss/event state machine here; the core job is swapping indoor/outdoor deformation and palette state.
- `Events_bg+$16` remains the authoritative indoor flag and also gates two `AnPal_ICZ` channels.
- The stage-8 refresh path is symmetric: it runs the current deform routine, performs `Draw_PlaneVertBottomUp`, and then subtracts 4 from the routine to return to stage 4 once the redraw completes.

**Confidence:** HIGH

## Boss Sequences

### Act 1 Miniboss

- **Object:** `Obj_ICZMiniboss`
- **Disassembly location:** `sonic3k.asm` lines 149641-150492
- **Spawn style:** Object-driven via `Check_CameraInRange`
- **Trigger tables:** `word_71142` / `word_71152`
  - Default: trigger `X=$0000-$0378`, `Y=$05F0-$07F0`; arena lock `Y=$02B8`, `X=$06F0`
  - Alternate subtype: trigger `X=$07C8-$09C8`, `Y=$05F0-$07F0`; arena lock `Y=$08C8`, `X=$06F0`
- **PLC load:** `$5F`
- **Palette load:** `Pal_ICZMiniboss`
- **Act restriction:** Deletes itself if `Apparent_zone_and_act == $0501`, so this is not used in Act 2

**Confidence:** MEDIUM

### Act 2 End Boss

- **Object:** `Obj_ICZEndBoss`
- **Disassembly location:** `sonic3k.asm` lines 150573-151352
- **Spawn style:** Object-driven via `Check_CameraInRange`
- **Trigger table:** `word_71BBE` = trigger `X=$02F8-$06F8`, `Y=$4340-$4490`
- **Arena lock table:** `word_71BC6` = `Y=$05F8`, `X=$4390`
- **PLC load:** `$70`
- **Palette load:** `Pal_ICZEndBoss`

**Confidence:** MEDIUM

## Parallax

### Act 1 Intro (`ICZ1_IntroDeform`)

**Disassembly location:** `sonic3k.asm` lines 110328-110358

- Uses `Adjust_BGDuringLoop` with loop bounds `$400/$800`
- BG Y comes from `Events_fg_1`, shifted down after loop adjustment
- BG X is based on `Camera_X_pos_copy - Screen_shake_offset`, then split into a set of constant-speed and auto-moving layers
- `ICZ1_IntroBGDeformArray`: `$44, $0C, $0B, $0D, $18, $50, $02, $06, $08, $10, $18, $20, $28, $7FFF`

More exact behavior:

1. `Adjust_BGDuringLoop` updates `Events_fg_0/1` relative to loop bounds `$400/$800`
2. `Camera_Y_pos_BG_copy = Events_fg_1 >> 7`
3. `Camera_X_pos_copy - Screen_shake_offset` is converted into a 16.16 value, shifted by 5
4. First 5 deformation bands use constant speeds
5. Last 9 bands accumulate extra auto-motion via a running delta starting at `$800`

**Implementation note:** This is more complex than the normal ICZ1 deform, but still materially simpler than HCZ. The array and the two-phase HScroll write are both explicit.

**Confidence:** MEDIUM

### Act 1 Normal (`ICZ1_Deform`)

**Disassembly location:** `sonic3k.asm` lines 110416-110429

- `Camera_Y_pos_BG_copy = Camera_Y / 2`
- `Events_bg+$12 = Camera_Y / 4`
- `Camera_X_pos_BG_copy = Camera_X / 2 - $1D80`
- `Events_bg+$10 = Camera_X / 4 - $0EC0`

This routine is simple enough that it should be treated as the reference producer for the custom `AnimateTiles_ICZ` inputs in Act 1.

**Cross-category dependency:**
- `AnimateTiles_ICZ` uses `Events_bg+$10` and `Events_bg+$12` as its scroll-phase inputs, so the custom animated tiles and the scroll handler must agree on these derived values.

**Confidence:** HIGH

### Act 2 Outdoors (`ICZ2_OutDeform`)

**Disassembly location:** `Lockon S3/Screen Events.asm` lines 1257-1307

- BG Y is fixed at `0`
- Main outdoor HScroll is built from `Camera_X + (Level_frame_counter >> 1)` and back-filled across 40 entries, creating slow auto-moving outdoor layers
- The top entries are then overwritten with camera-relative values plus `AIZ2_ALZ_BGDeformDelta` wobble data
- `ICZ2_OutBGDeformArray`: `$5A, $26, $8030, $7FFF`
  - The `$8030` entry indicates a per-line segment

More exact behavior:

1. Writes 40 entries starting at `HScroll_table+$64` using a descending longword accumulator
2. Fills the top part of the table from a slower camera-derived value
3. Uses `Level_frame_counter >> 2` to index `AIZ2_ALZ_BGDeformDelta`
4. Applies 8 wobble values on top of the slower outdoor layer

This is a true auto-scrolling outdoor sky path, not just a static ratioed parallax.

**Confidence:** MEDIUM

### Act 2 Indoors (`ICZ2_InDeform`)

**Disassembly location:** `Lockon S3/Screen Events.asm` lines 1310-1359

- `Camera_Y_pos_BG_copy = (Camera_Y - $700) / 4 + $118`
- HScroll builds a mirrored stepped set of bands from `Camera_X / 2`, subtracting `Camera_X / 16` each step
- `Camera_X_pos_BG_copy` is written from the center band
- `Events_bg+$10` is written from the next slower band for use by `AnimateTiles_ICZ`
- `ICZ2_InBGDeformArray`: `$1A0, $40, $20, $18, $40, $08, $08, $18, $7FFF`

The indoor HScroll table is explicitly mirrored:

- `HScroll[0]` and `HScroll[8]` share the fastest value
- `HScroll[1]` and `HScroll[7]` share the next value
- `HScroll[2]` and `HScroll[6]` share the next value
- `HScroll[3]` and `HScroll[5]` share the next value
- center band is written to `HScroll[4]` and to `Camera_X_pos_BG_copy`

Then one further subtraction step is stored in `Events_bg+$10`, making the animated-tile phase intentionally lag one band behind the visual center layer.

**Confidence:** HIGH

## Animated Tiles

### Generic AniPLC (`AniPLC_ICZ`)

**Disassembly location:** `sonic3k.asm` lines 55887-55896

- One script
- Trigger byte: `3`
- Source art: `ArtUnc_AniICZ__0`
- Destination VRAM tile: `$11E`
- Frame count: `8`
- Tiles/bytes-per-frame field: `4`
- Frame data: `0, 4, 8, C, 10, 14, 18, 1C`

**Confidence:** HIGH

### Custom DMA updater (`AnimateTiles_ICZ`)

**Disassembly location:** `sonic3k.asm` lines 54434-54538

**Shared path (both acts):**
- Computes phase from `(Events_bg+$10 - Camera_X_pos_BG_copy) & $1F`
- DMA source base: `ArtUnc_AniICZ__1`
- Destination starts at VRAM tile `$10E`
- Segment sizes come from `word_27CBC`

The shared path maintains its own cached phase in `Anim_Counters+1`. It only queues DMA when the derived phase changes, so a faithful engine port should avoid re-uploading the ICZ strip art every frame when the phase is unchanged.

**Act 1-only extra channels:**
- Channel A: `ArtUnc_AniICZ__2` -> VRAM `$122`, size `$80`
- Channel B: `ArtUnc_AniICZ__3` -> VRAM `$12A`, size `$40`
- Channel C: `ArtUnc_AniICZ__4` -> VRAM `$12E`, size `$20`
- Channel D: `ArtUnc_AniICZ__5` -> VRAM `$130`, size `$10`
- These are driven from `Camera_Y_pos_BG_copy` and `Events_bg+$12` with progressively slower divisors

More exact Act 1 channel formulas:

- Channel A phase: `(-(Camera_Y_pos_BG_copy - Events_bg+$12)) & $3F`
- Channel B phase: `(-(Camera_Y_pos_BG_copy - (Events_bg+$12 >> 1) - (Events_bg+$12 >> 2))) & $1F`
- Channel C phase: `(-(Camera_Y_pos_BG_copy - (Events_bg+$12 >> 1))) & $0F`
- Channel D phase: `(-(Camera_Y_pos_BG_copy - (Events_bg+$12 >> 2))) & $07`

Each phase is stored in a separate `Anim_Counters` slot and only triggers DMA when its cached value changes.

**Implementation note:**
- ICZ animated tiles are not just AniPLC registration. The custom updater is coupled to the deform outputs, so the parallax and animated-tile implementations need to agree on the same derived scroll state.
- The ICZ entry in `Offs_AniFunc` uses `AnimateTiles_ICZ` for both acts and pairs it with `AniPLC_ICZ` for both acts, so the full runtime behavior is “custom DMA updater plus generic AniPLC,” not one or the other.

**Confidence:** HIGH

## Palette Cycling

### `AnPal_ICZ`

**Disassembly location:** `sonic3k.asm` lines 3379-3436

| Channel | Timer reload | Counter | Step | Limit | Destination | Condition |
|---------|--------------|---------|------|-------|-------------|-----------|
| 1 | `5` | `Palette_cycle_counter0` | `+4` | `$40` | `Normal_palette_line_3+$1C` (palette 2, colors 14-15) | Always |
| 2 | `9` | `Palette_cycle_counters+$02` | `+4` | `$48` | `Normal_palette_line_4+$1C` (palette 3, colors 14-15) | Only when `Events_bg+$16 != 0` |
| 3 | `7` | `Palette_cycle_counters+$04` | `+4` | `$18` | `Normal_palette_line_4+$18` (palette 3, colors 12-13) | Only when `Events_bg+$16 != 0` |
| 4 | Shared with channel 3 | `Palette_cycle_counters+$06` | `+4` | `$40` | `Normal_palette_line_3+$18` (palette 2, colors 12-13) | Always |

**Tables:**
- `AnPal_PalICZ_1`
- `AnPal_PalICZ_2`
- `AnPal_PalICZ_3`
- `AnPal_PalICZ_4`

**Engine status:** Already implemented in `Sonic3kPaletteCycler` with tests.

**Confidence:** HIGH

## Notable Objects / Zone-Specific Mechanics

- `Obj_ICZTeleporter`: Knuckles-only Act 1 intro object spawned from `ICZ1_ScreenInit`
- `Obj_ICZ1BigSnowPile`: spawned by the Act 1 BG intro trigger; controls the quake intro and the forced jump release
- `Obj_ICZMiniboss`
- `Obj_ICZEndBoss`
- `Obj_ICZPathFollowPlatform`
- `Obj_ICZBreakableWall`
- `Obj_ICZIceBlock`
- `Obj_ICZCrushingColumn`
- `Obj_ICZFreezer`
- `Obj_ICZSegmentColumn`
- `Obj_ICZSwingingPlatform`
- `Obj_ICZStalagtite`
- `Obj_ICZIceSpikes`
- `Obj_ICZIceCube`
- `Obj_ICZHarmfulIce`
- `Obj_ICZSnowPile`
- `Obj_ICZTensionPlatform`
- `Obj_ICZSnowdust`

### `Obj_ICZTeleporter`

**Disassembly location:** `sonic3k.asm` lines 110478-110568

Responsibilities:

1. Creates the teleporter platform parent plus a child beam object
2. Applies temporary palette changes to `Target_palette_line_2+$10`
3. Forces Knuckles under object control, with roll/jump animation state
4. Uses `Gradual_SwingOffset` each frame to swing the player vertically until the teleport arc completes
5. Restores control afterward
6. Deletes itself once `Camera_X_pos_copy >= $3780`, restoring the mutated palette line

This is a substantial ICZ-specific intro path and should be considered part of ICZ validation, even if not part of the first event-handler bring-up slice.

## Cross-Cutting Concerns

- **Water:** Present in Act 2. The Act 1 -> 2 transition explicitly re-runs level water setup and enables H-Interrupt for water rendering.
- **Screen shake:** Act 1 Big Snow Fall uses `Screen_shake_flag` and periodic `sfx_Rumble2`. `ICZ1_ScreenEvent` also temporarily locks controls while the quake is active.
- **Act transition:** ICZ1 transitions seamlessly into ICZ2 through the BG event routine, not through the generic Act 1 results-screen trigger path.
- **Character branching:** Knuckles gets a teleporter intro and avoids the Sonic/Tails Big Snow Fall setup path. Tails-alone gets a snowboard-skip start state.
- **PLC loading:** PLC `$20` during ICZ1 -> ICZ2 transition, `$5F` for the miniboss, `$70` for the end boss.
- **Animated-tile dependency:** `AnimateTiles_ICZ` depends on deform-derived values in `Events_bg+$10/$12`, so parallax and animated tiles should be implemented together or validated together.
- **Snowboarding intro:** The zone includes a snowboard-specific path outside the resize routine. It is relevant for validation because the ROM has at least three materially different opening paths: snowboard intro, Tails skip, and Knuckles teleporter.
- **Shared BG routine counter:** ICZ1 uses `Events_routine_bg` to drive both `ScreenEvent` and `BackgroundEvent`, so ports need to preserve the coupling rather than treating them as independent state machines.

## Scope Decision

Feature scope for ICZ:

- **[DISPATCH] Events**: Required. No ICZ event handler exists in the engine.
- **[DISPATCH] Parallax**: Required. No ICZ scroll handler exists, and animated tiles depend on its derived scroll state.
- **[DISPATCH] Animated Tiles**: Required. `AniPLC_ICZ` and `AnimateTiles_ICZ` are both currently missing from `Sonic3kPatternAnimator`.
- **[VALIDATE] Palette Cycling**: Already implemented. Keep this in validate-only mode.
