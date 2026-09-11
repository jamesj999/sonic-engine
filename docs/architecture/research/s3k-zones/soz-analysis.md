# S3K SOZ Zone Analysis

## Summary

- **Zone:** Sandopolis Zone (SOZ)
- **Zone Index:** 0x08 (S&K set)
- **Zone Set:** SKL
- **Acts:** 1 (desert exterior) and 2 (pyramid interior with seamless transition from Act 1)
- **Water:** No (quicksand objects only, not the water subsystem)
- **Palette Cycling:** Yes (1 channel Act 1; Act 2 uses a 900-frame darkness timer plus 4-frame fade steps driven by `SOZ_darkness_level` and `Palette_cycle_counters`)
- **Animated Tiles:** Yes (1 custom handler Act 1 -- parallax-driven BG tile cycling; 1 custom handler Act 2 -- darkness-aware torch animation; SOZ still shares `AniPLC_LRZ1` in the generic animation slot)
- **Character Branching:** Minimal -- Knuckles uses different start locations; ghost capsule behavior branches on character. No chunk adjustments or separate resize paths.
- **Dynamic Resize:** `No_Resize` in `LevelResizeArray` -- all camera boundary management and event logic is handled through `SOZ1_BackgroundEvent` / `SOZ2_BackgroundEvent` instead of the standard `Dynamic_resize_routine`.
- **Extended Y Wrap (Act 2):** Act 2 uses `Screen_Y_wrap_value=$7FF`, `Camera_Y_pos_mask=$7F0`, `Layout_row_index_mask=$3C` for double-height level layout (2048px Y wrap vs standard 1024px).

## Events

### Act 1 (SOZ1_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 113582

SOZ1 does NOT use `Dynamic_resize_routine`. All event logic is in the background event handler. The foreground `SOZ1_ScreenEvent` (line 113565) only applies `Screen_shake_offset` to `Camera_Y_pos_copy` and calls `DrawTilesAsYouMove`.

**State machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | `Events_fg_5 != 0` | Clears `Events_fg_5`. Sets `_unkEE9C=-8` (sand rise offset). Sets `Screen_shake_flag=$FF`. Resets tile offset, queues delayed row draw ($F rows). Advances to $04. Falls through to $04 handler. | Triggered when player reaches boss arena trigger zone (from `sub_55E96`). |
| 0 | $00 | `Events_fg_5 == 0` (normal) | Dynamic Y boundary: if Player X < $4000 then `Camera_max_Y_pos=$B20`, else `Camera_max_Y_pos=$960`. Calls `sub_55E96` (boss arena trigger check). Updates BG scroll via `sub_55D56`. Draws BG tile row. Applies FG/BG deformation via `loc_55DF2`. | Default running state. Max Y switches at the indoor/outdoor boundary. |
| 1 | $04 | BG plane draw complete (bpl) | Queues `SOZ1_16x16_Custom_Kos` to `Block_table+$EA0`. Queues `ArtKosM_SOZ1_Custom` at VRAM tile $315. Spawns 3 objects: `loc_55F48` (miniboss spawner), `loc_55F98` (Act 1 end door sprite mask), `loc_55FDA` (Act 1 end door solid). Advances to $08. | Art swap for boss arena. The boss spawner waits for shake to finish then spawns `Obj_SOZMiniboss`. |
| 2 | $08 | `Events_fg_5 == $55` AND Player1 X >= $4378 AND Player1 Y < $9A8 AND (Player_mode != 0 OR Player2 also meets coords) | Initiates Act 1->2 transition: sets `Palette_fade_info=$F`, `Events_bg+$04=$15` (fade counter), `Events_bg+$06=$10` (delay), `Palette_fade_timer=$FF`. Advances to $0C. | The $55 value in `Events_fg_5` is set by the end door sequence completing. Sonic+Tails path requires both players to be past the door. |
| 2 | $08 | Conditions not met | Calls `sub_55D94` (sand shake update). Draws BG, applies deformation, calls `ShakeScreen_Setup`. | Normal frame while waiting for transition trigger. |
| 3 | $0C | `Events_bg+$06` countdown | Phase 1 fade-to-black: decrements `Events_bg+$06`. When it reaches 0, sets `Events_bg+$02=$FF` (flag). While `Events_bg+$02==0`, calls `Pal_ToBlack` every other frame, decrementing `Events_bg+$04` ($15 steps). When `Events_bg+$04 < 0`, advances to $10. | First $10 frames are delay, then 21 fade steps at half-speed (every other frame). |
| 4 | $10 | `Events_bg+$06` countdown | Phase 2 fade-to-black: sets `Palette_fade_info=$401F` (palette lines 2-3), `Events_bg+$04=$15`, `Events_bg+$06=8`. Same Pal_ToBlack logic. When complete, advances to $14. | Fades the additional palette lines to black. |
| 5 | $14 | `Kos_modules_left == 0` | Act transition: sets `Current_zone_and_act=$801` (SOZ Act 2). Clears `Dynamic_resize_routine`, `Object_load_routine`, `Rings_manager_routine`, `Boss_flag`, `Respawn_table_keep`. Calls `Clear_Switches`, `Load_Level`, `LoadSolids`. Loads palette 3 (Sonic/Tails) or 5 (Knuckles). Loads palette $1B (SOZ2 zone palette) twice (normal + immediate). Calls `sub_55EFC` (darkness init). Clears palette lines 3-4 to black. Repositions players to ($140, $3AC). Sets camera to ($A0, $34C) and locks all boundaries. Clears event state. | Queues `SOZ2_16x16_Secondary_Kos`, `ArtKosM_SOZ2_Secondary` at VRAM $315, and PLC $2C before this stage. Seamless transition -- no title card. |

**Boss arena trigger (`sub_55E96`, line 113975):**
- Only when `Camera_max_Y_pos == $960` AND Camera Y >= $960
- Sets `Camera_min_Y_pos = $960`
- When `Camera_X_pos >= $4310`: sets `Camera_min_X_pos = $4180`, sets `Events_fg_5 = $FF`
- This triggers the boss arena lock and sand rise sequence

**Sand rise / screen shake (`sub_55D94`, line 113866):**
- Active when `Screen_shake_flag < 0` (i.e., $FF / set by stage 0)
- Every frame where `Level_frame_counter & 3 != 0`: increments `_unkEE9C` by 1
- When `_unkEE9C >= $280`: sets `Screen_shake_flag = 8` (disables further shake)
- Plays `sfx_Rumble2` every 16 frames during shake

**BG scroll computation (`sub_55DB6`, line 113882):**
- `Camera_Y_pos_BG_copy = Camera_Y_pos_copy - $900 + _unkEE9C`
- `Camera_X_pos_BG_copy = Camera_X_pos_copy - $3CD0`
- Also writes `Camera_X_pos_BG_copy` to `Events_bg+$10`
- Plays `sfx_Rumble2` every 16 frames when `Screen_shake_flag < 0`

**BG initial scroll computation (`sub_55D56`, line 113833):**
- BG Y = Camera Y / 16
- BG X = Camera X / 16 (with fixed-point precision)
- Writes `Events_bg+$10` = Camera X / 32
- Fills 7 entries in `HScroll_table` with incremental BG X accumulation (halving each tier)

**Confidence:** HIGH

### Act 2 (SOZ2_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 114320

SOZ2 uses BOTH `SOZ2_ScreenEvent` (foreground, line 114230) and `SOZ2_BackgroundEvent` (background) with independent state machines (`Events_routine_fg` and `Events_routine_bg`).

**SOZ2_ScreenInit** (line 114216) sets:
- `Screen_Y_wrap_value = $7FF` (2048px Y wrap)
- `Camera_Y_pos_mask = $7F0`
- `Layout_row_index_mask = $3C`
- `Events_routine_fg = 8` (start in normal draw state)
- If Player X >= $4E80: calls `sub_5622C` (chunk data copy for end-area tilemap)

**SOZ2_ScreenEvent state machine (Events_routine_fg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | (set externally) | Re-initializes Y wrap values. Resets tile offset. Queues delayed row draw. Advances to $04. | Used when switching BG modes mid-level. |
| 1 | $04 | Plane draw complete | Advances to $08. | Transition state. |
| 2 | $08 | `Events_fg_4 != 0` | Clears `Events_fg_4`. Calls `sub_5622C` (tilemap chunk copy). | Responds to tilemap change trigger from boss area. |
| 2 | $08 | Always | Calls `DrawTilesAsYouMove`. | Normal FG tile drawing. |

**SOZ2_BackgroundInit** (line 114304):
- Calls `sub_5697E` (clears chunk overlay bytes)
- Sets `Events_routine_bg` to $10 (normal outdoor) or $20 (post-pyramid) based on Player X >= $2980
- Calls `sub_566D2` (BG = Camera/2), resets tile offsets, refreshes plane

**SOZ2_BackgroundEvent state machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | `Events_routine_fg >= 8` | Re-initializes BG. Calls `sub_5697E`, `sub_566D2`, resets tile offsets. Queues delayed rows. Advances to $04. | Used after FG has finished its transition draw. |
| 1 | $04 | BG plane draw complete | Sets `Palette_fade_info=$F`, `Events_bg+$00=$15`. Advances to $08. | Start fade-from-black (Act 2 entry). |
| 2 | $08 | Fade progress | Phase 1 fade-from-black via `Pal_FromBlack`. At step 5, spawns `Obj_TitleCard` with `$3E=FF`. When `Events_bg+$00 < 0`: sets `Palette_fade_info=$401F`, `Events_bg+$00=$15`. Advances to $0C. | Title card spawns mid-fade. |
| 3 | $0C | Fade progress | Phase 2 fade-from-black via `Pal_FromBlack`. When complete: unlocks camera (`Camera_min_X_pos=$6000`, `Camera_min_Y_pos=-$FFF800`), clears `Palette_fade_timer` and `Ctrl_1_locked`. Advances to $10. | Full camera unlock after fade completes. |
| 4 | $10 | Normal outdoor | Checks `sub_5699A` (pyramid entrance check). If triggered, jumps to $18 (rising sand). Checks `Events_fg_5` for sand rise trigger. Otherwise calls `sub_566D2`, draws BG tiles, applies `PlainDeformation`. | Main outdoor gameplay state. |
| -- | $10 | `Events_fg_5 != 0` | Sand rise trigger: clears `Events_fg_5`, advances +4 or +8 depending on Player Y (< $400: $14, >= $400: $18). Sets `_unkEE9C` to $80 or $3E0. Calls `sub_566E8`, resets tile offsets. Sets `Special_events_routine=$10` (activates `loc_569BA` sand rise). | Two variants depending on player position. |
| 5 | $14 | Rising sand #1 | Checks `sub_5699A` (pyramid entrance). Checks `Events_fg_5` for another trigger. When `_unkEE9C >= $400`: clears `Special_events_routine`. Draws BG, deforms, calls `Go_CheckPlayerRelease`. | Low-position rising sand, slower accumulation threshold. |
| 6 | $18 | Rising sand #2 | Same as $14 but threshold is `_unkEE9C >= $A00`. When `Events_fg_5` set: advances to next sand stage. | High-position rising sand, higher threshold before stopping. |
| 7 | $1C | Pyramid entrance | Saves BG position to `Events_bg+$02/$04`. Calls `sub_566D2`. Resets tile offsets. Sets delayed draw. Clears `Events_fg_5`, `Special_events_routine`, `Background_collision_flag`. Advances to $20. | Transitions to indoor pyramid BG. |
| 8 | $20 | Normal indoor | Checks Player X >= $5000 AND Camera Y >= $500 for boss trigger. Otherwise calls `sub_566D2`, draws BG tiles. Restores saved BG position if `Events_bg+$02 != 0`. Applies `PlainDeformation`. | Main indoor pyramid state. |
| -- | $20 | Player X >= $5000 AND Camera Y >= $500 | Boss arena setup: sets `Events_bg+$0C=$5250`, `Events_bg+$0E=$6D8` (boss position). Copies tilemap data for boss background. Clears HScroll table. Calls `sub_56706` (boss BG scroll). Sets camera boundaries: min Y=$500, max Y=$680, max X=$5140. Queues `SOZ2_16x16_Custom_Kos` and `ArtKosM_SOZ2_Custom` at VRAM $315. Stops darkness timer (`Palette_cycle_counter1=$7FFF`), resets `SOZ_darkness_level=0`. Reverses palette darkening. Disables tile animation (`Anim_Counters=$7F`). Spawns 8 solid sand wall objects via `sub_56A12`. Advances to $24. | Major boss arena initialization. |
| 9 | $24 | Camera locked | Locks `Camera_min_Y_pos` to current Camera Y. When Camera Y == Camera_max_Y_pos: advances to $28. Stops darkness timer. Disables tile animation. Checks `Events_fg_5` for boss death trigger (negative). Calls boss BG scroll `sub_56706`. Draws BG via `Draw_BG` + `ApplyDeformation` with `SOZ2_BGDrawArray`. Calls `ShakeScreen_Setup`. | Boss arena vertical camera tracking. |
| -- | $24 | `Events_fg_5 < 0` (boss death) | Saves BG position. Calls `sub_56964` (offset BG scroll). Resets tile offsets. Queues delayed rows. Queues `SOZ2_16x16_Secondary_Kos` and `ArtKosM_SOZ2_Secondary`. Loads palette $1B immediate. Advances to $2C. | Restores normal tileset after boss defeat. |
| 10 | $28 | Normal boss state | Same as $24 but camera fully locked. Checks `Events_fg_5 < 0` for boss death. | Terminal boss state until defeat. |
| 11 | $2C | `Kos_modules_left == 0` | Calls `sub_56964`. Draws BG plane. When plane draw complete: clears `Events_bg+$02`, sets `Camera_max_X_pos=$52C0`, clears `Anim_Counters`. Advances to $30. | Art reload after boss defeat. |
| 12 | $30 | Normal post-boss | Calls `sub_56964`. Draws BG tiles. Restores saved BG X if needed. Applies `PlainDeformation`. Calls `ShakeScreen_Setup`. | Final state -- level continues to exit. |

**Pyramid entrance check (`sub_5699A`, line 115044):**
- Returns true (d0=-1) when: Player X >= $2A00 AND $140 <= Player Y <= $180
- This triggers the BG transition from outdoor to indoor pyramid

**Rising sand special event (`loc_569BA`, line 115063):**
- Called via `SpecialEvents_Index` entry 4 when `Special_events_routine=$10`
- Sets `Background_collision_flag = $FF`
- Increments `_unkEE9C` by $A000 (fixed-point, so high word increments by ~$A per frame)
- Computes `Camera_Y_diff = -(($2E0 + _unkEE9C.w))`, `Camera_X_diff = $1930`
- This creates an upward BG scroll effect simulating rising sand

**BG scroll modes:**
- **Outdoor (sub_566D2):** BG = Camera / 2 (both X and Y)
- **Sand rise (sub_566E8):** BG Y = Camera Y + $2E0 + _unkEE9C; BG X = Camera X - $1930
- **Boss area (sub_56706):** Complex -- BG Y based on Camera Y - $250 + delta from $6D8; BG X based on Camera X - $4010 + delta from $5250. Then runs sub-state machine for sand wall collapse animation.
- **Post-boss (sub_56964):** BG Y = Camera Y / 2; BG X = Camera X / 2 + $200

**Sand wall collapse animation (inside `sub_56706`):**

The boss arena has a 9-segment sand wall that collapses progressively. This sub-state machine at `Events_bg+$08` controls the animation:

| Sub-State | Offset | Action |
|-----------|--------|--------|
| 0 | $00 | Checks `Events_bg+$0A` (triggered by boss). Sets up initial HScroll offsets (-1 through -9) at `HScroll_table+$134`. Plays `sfx_BossHit`. Sets delay timer. |
| 1 | $04 | Waits for delay. Expands HScroll offsets (-1 through -18). Sets longer delay. |
| 2 | $08 | Computes fall distance from camera/BG offset (clamped 0-$10). Sets velocity table from `word_56AE2`. Creates `$80000` entries in HScroll for 9 segments. Plays `sfx_Collapse`. |
| 3 | $0C | Physics loop: each of 9 segments has position+velocity+done-flag. Applies gravity ($2800/frame deceleration). Segments settle when velocity reverses. Plays `sfx_BossRecovery` when first segment settles. |
| 4 | $10 | Reverse: segments rise back up with -$2800 acceleration. When all segments at 0, clears positions and sets reset delay. |
| 5 | $14 | Special wind mode: 12 HScroll entries slide leftward by 5px/frame. When all reach -$100: sets `Events_fg_5=$FF` (signals completion). |

**Confidence:** HIGH

### Act 1 Background Sub-Events (inline objects)

Three objects are spawned by SOZ1_BackgroundEvent stage 1:

1. **`loc_55F48` -- Miniboss spawner** (line 114052): Waits for screen shake to stop (`Screen_shake_flag == 0`), waits 60 frames, sets `Events_bg+$02=$FF`, then when `Events_bg+$02` clears, spawns `Obj_SOZMiniboss` and deletes self.

2. **`loc_55F98` -- Act 1 End Door (sprite mask)** (line 114093): Rendered with priority $200. Uses `Map_SOZ1EndDoor`. Stays at Camera X position. Sets `Spritemask_flag`. Deleted when `Current_act != 0` (i.e., after transition to Act 2).

3. **`loc_55FDA` -- Act 1 End Door (solid/animated)** (line 114113): Uses `Map_SOZ1EndDoor` frame 1. Art tile $029, palette 2. Initial position at ($439C, $9D4). Opens (moves down) when `Events_bg+$02 != $00` -- plays `sfx_DoorOpen`, sets `Events_bg+$00=$F6`. Closes back up on second trigger. Movement via `sub_560A2` -- increments position every frame (accelerated every 4th frame), with horizontal jitter from `ScreenShakeArray2`.

## Boss Sequences

### Act 1 Miniboss (SOZ Guardian / Stone Golem)

- **Object:** `Obj_SOZMiniboss` at line 157796
- **Spawn position:** ($439D, $9F7) hardcoded
- **Spawn trigger:** Stage 1 of SOZ1_BackgroundEvent -> `loc_55F48` spawner -> waits for shake stop -> 60 frame delay -> `Events_bg+$02` flag cycle -> spawns boss
- **Pre-fight:** Fades palette line 1 from `Pal_SOZMinibossFade` to `Pal_SOZMinibossMain`. Fades out current music (`cmd_FadeOut`), then plays `mus_Miniboss` with Pal_FromBlack effect via `Obj_FadeSelectedFromBlack`.
- **Art loading:** `ArtKosM_SOZMiniboss` at `ArtTile_SOZMiniboss`, `ArtKosM_SOZSand` at `ArtTile_SOZSand`, plus `PLC_BossExplosion`
- **Arena boundaries:** Locked by `sub_55E96`: Camera_min_X_pos=$4180, Camera_min_Y_pos=$960, Camera_max_Y_pos=$960
- **Routine table:** 13 states (loc_76AC8 through locret_76E08) -- init, wait, descend, attack patterns, damage, defeat
- **Defeat behavior:** Triggers door opening sequence which eventually sets `Events_fg_5=$55`, enabling the Act 1->2 transition
- **Confidence:** HIGH

### Act 2 End Boss (Eggman Golem)

- **Object:** `Obj_SOZEndBoss` at line 158771
- **Spawn trigger:** SOZ2_BackgroundEvent stage $20 triggers boss arena when Player X >= $5000 AND Camera Y >= $500
- **Pre-fight:** Boss arena initialized in SOZ2_BackgroundEvent. Tilemap modified, HScroll cleared, camera locked (min Y=$500, max Y=$680, max X=$5140). Darkness system disabled. 8 solid sand wall objects spawned.
- **Art loading:** `ArtKosM_SOZEndBoss` at `ArtTile_SOZEndBoss`, PLC $6D, `Pal_SOZEndBoss1` (palette line 1), `Pal_SOZEndBoss2` (palette line 4)
- **Position tracking:** Each frame writes `x_pos` and `y_pos` to `Events_bg+$0C` and `Events_bg+$0E` (used by `sub_56706` for boss BG scroll tracking)
- **Routine table:** 7 states (line 158780-158787)
- **Child objects:** Creates body parts via `ChildObjDat_782FA`, `ChildObjDat_78320`, `ChildObjDat_78326`
- **Hit count:** 8 hits (`collision_property = 8`)
- **Boss BG integration:** The sand wall collapse animation in `sub_56706` is triggered by `Events_bg+$0A` (set by boss hit events), creating the visual effect of the arena walls crumbling with each phase
- **Defeat behavior:** Sets `Events_fg_5` to negative value, triggering SOZ2_BackgroundEvent to restore normal tileset and transition to post-boss state
- **Confidence:** HIGH

## Parallax

### Act 1 (SOZ1 Deformation)

**Disassembly location:** `sonic3k.asm` line 113906 (`loc_55DF2`)

**Description:** SOZ Act 1 uses per-line FG and BG deformation via the shared `AIZ2_SOZ1_LRZ3_FGDeformDelta` sine table (line 105635). This creates a heat-shimmer/mirage effect on the desert background. The BG scroll uses a multi-tier accumulation system via `sub_55D56`.

**FG deformation (`sub_55E4C`, line 113938):**
- 112 scanlines ($70) of per-line deformation
- Each line gets a delta from `AIZ2_SOZ1_LRZ3_FGDeformDelta` indexed by `(Camera_Y * 2 + Level_frame_counter / 2) & $3E`
- FG scroll = Camera X (negated) + delta
- BG scroll = Camera BG X (negated) + delta (same delta applied to both planes)

**BG scroll bands (`word_560DC` draw array, line 114206):**

| Band | Height (px) | Description |
|------|-------------|-------------|
| 0 | $110 (272) | Large sky/dune area |
| 1 | $08 (8) | Far dune detail 1 |
| 2 | $08 (8) | Far dune detail 2 |
| 3 | $08 (8) | Far dune detail 3 |
| 4 | $08 (8) | Far dune detail 4 |
| 5 | $08 (8) | Far dune detail 5 |
| End | $7FFF | Terminator |

**BG X accumulation (`sub_55D56`, line 113833):**
- Base BG X = Camera X / 16 (with 16.16 fixed-point precision)
- `Events_bg+$10` = Camera X / 32
- 7 entries filled in `HScroll_table` with halving accumulation from the BG X position
- This creates 7 speed tiers for the background layers, slower further from camera

**BG Y scroll:** Camera Y / 16

**Boss arena BG (`sub_55DB6`, line 113882):**
- During boss fight: BG Y = Camera Y - $900 + `_unkEE9C` (sand rise offset)
- BG X = Camera X - $3CD0
- Rumble SFX every 16 frames while `Screen_shake_flag < 0`

**Confidence:** HIGH

### Act 2 -- Outdoor (sub_566D2)

**Disassembly location:** `sonic3k.asm` line 114705

**Description:** Simple half-speed parallax for the outdoor desert portion of Act 2.

- BG X = Camera X / 2
- BG Y = Camera Y / 2
- Uses `PlainDeformation` (no per-line deformation in outdoor section)

**Confidence:** HIGH

### Act 2 -- Rising Sand (sub_566E8)

**Disassembly location:** `sonic3k.asm` line 114719

**Description:** During rising sand sequences, the BG position is offset to show the sand level rising.

- BG Y = Camera Y + $2E0 + `_unkEE9C` (rising sand accumulator)
- BG X = Camera X - $1930
- The rising-sand path is entered from `SOZ2_BackgroundEvent` stage $10 when `Events_fg_5` is set and the player is on the lower route; the stage writes either `$14` or `$18` and seeds `_unkEE9C` to `$80` or `$3E0`
- `Special_events_routine=$10` routes through `loc_569BA`, which sets `Background_collision_flag`, increments `_unkEE9C` by `$A000` per frame, and writes `Camera_Y_diff` / `Camera_X_diff` for the upward-scroll illusion
- `Go_CheckPlayerRelease` runs while the sand is moving so the player is detached from any solid object before the next frame
- Uses `PlainDeformation`

**Confidence:** HIGH

### Act 2 -- Boss Area (sub_56706 + SOZ2_BGDrawArray)

**Disassembly location:** `sonic3k.asm` line 114734

**Description:** Complex multi-band BG with boss position tracking and sand wall collapse physics. The boss arena background tracks the boss object position.

- BG Y = Camera Y - $250 + ($6D8 - Events_bg+$0E)
- BG X = Camera X - $4010 + ($5250 - Events_bg+$0C)
- `Events_bg+$08` is the boss-arena sub-state machine index; `Events_bg+$0A` is the one-shot boss-hit latch that kicks the first collapse phase
- `sub_56A12` spawns 8 physical wall solids from `word_56AF4`; they are camera-relative collision shells, separate from the HScroll collapse
- Player death check: if `Player_1+routine >= 6`, skip sand wall physics

**BG draw bands (`SOZ2_BGDrawArray`, line 115158):**

| Band | Height (px) | Description |
|------|-------------|-------------|
| 0 | $440 (1088) | Large main background area |
| 1-11 | $10 (16) each | 11 bands of 16px each (segmented sand wall area) |
| End | $7FFF | Terminator |

**HScroll band mappings (second part of SOZ2_BGDrawArray):**

The 8 entries after the height bands define the HScroll table offsets for each BG band:

| Entry | Value | Meaning (high byte = dest HScroll offset, low byte = src HScroll offset) |
|-------|-------|---------|
| 0 | $1800 | |
| 1 | $1502 | |
| 2 | $1204 | |
| 3 | $0F06 | |
| 4 | $0C08 | |
| 5 | $090A | |
| 6 | $060C | |
| 7 | $030E | |

**Sand wall velocity table (`word_56AE2`, line 115168):**

9 entries used as initial fall velocities for the sand wall segments:
$0010, $030E, $060C, $090A, $0C08, $0F06, $1204, $1502, $1800

**Solid sand wall X positions (`word_56AF4`, line 115178):**

8 entries for the solid object X positions spawned by `sub_56A12`:
$1268, $1260, $1260, $125C, $1254, $1248, $1248, $1248

**BG HScroll computation (line 114986):**
- 13 bands computed from 3 HScroll sub-tables at offsets $100, $120, $140
- Each band: `scroll = HScroll[$120+i] + HScroll[$140+i] + Camera_X_pos_BG_copy`
- Written to both `HScroll_table[i*4]` (draw) and `HScroll_table[$100+i]` (deformation)

**Confidence:** MEDIUM (sand wall physics sub-state machine is complex with multiple velocity/acceleration phases)

### Act 2 -- Post-Boss (sub_56964)

**Disassembly location:** `sonic3k.asm` line 115011

- BG X = Camera X / 2 + $200
- BG Y = Camera Y / 2
- Uses `PlainDeformation` + `ShakeScreen_Setup`

**Confidence:** HIGH

## Animated Tiles

### AnimateTiles_SOZ1 (BG Parallax Tile Cycling)

**Disassembly location:** `sonic3k.asm` line 54939

**Description:** Driven by BG parallax scroll position, not a frame timer. Updates BG tiles when the scroll offset (modulo 32) changes, creating seamless scrolling background art.

**Mechanism:**
1. Computes `d0 = (Events_bg+$10 - Camera_X_pos_BG_copy) & $1F` -- this is the pixel offset within a 32-pixel tile group
2. Only triggers DMA when this offset differs from the cached value in `Anim_Counters+1`
3. Splits offset into two parts:
   - Low 3 bits (`d0 & 7`): selects within a column group, multiplied by $180 (384 bytes = 12 tiles * 32 bytes/tile)
   - Bits 3-4 (`d0 & $18`): selects the tile split point, using `word_281B8` table

**DMA transfers:**
- **Transfer 1:** From `ArtUnc_AniSOZ1_BG` + computed offset, to VRAM tile $330, size from `word_281B8[split*2]`
- **Transfer 2:** From `ArtUnc_AniSOZ1_BG` + base offset, to VRAM tile $330+transfer1_size, size from `word_281B8[split*2+1]`
- **Transfer 3:** From `ArtUnc_AniSOZ1_BG2` + `(offset * $C0)`, to VRAM tile $33C, size $60 (3 tiles)

**Split table (`word_281B8`, line 54928):**

| Split Index | First Size | Second Size |
|-------------|-----------|------------|
| 0 | $C0 (6 tiles) | $00 (0 tiles) |
| 1 | $90 (4.5 tiles) | $30 (1.5 tiles) |
| 2 | $60 (3 tiles) | $60 (3 tiles) |
| 3 | $30 (1.5 tiles) | $90 (4.5 tiles) |

**Trigger:** Always active (position-driven, not timer-driven)
**Source art:** `ArtUnc_AniSOZ1_BG` (main BG patterns), `ArtUnc_AniSOZ1_BG2` (secondary BG patterns)
**Destination VRAM:** Tiles $330-$33B (main), $33C-$33E (secondary)

**Confidence:** HIGH

### AnimateTiles_SOZ2 (Torch/Light Animation)

**Disassembly location:** `sonic3k.asm` line 54997

**Description:** Timer-driven animation for Act 2 torch/light patterns. Frame selection incorporates the darkness level to show different torch intensities.

**Mechanism:**
1. Decrements `Anim_Counters`. When < 0, resets to 7 (8-frame period)
2. Increments frame counter `Anim_Counters+1`, wraps at 2 (so 2 frames of animation)
3. Combines frame with darkness level: `index = (Palette_cycle_counters+$06 & 6)` -> maps to 0, 3, 6 via `d1 + d1/2` computation. When `index == 6` (maximum darkness), frame offset is not added
4. Final DMA index = `computed_index * $C0` ($C0 = 192 bytes = 6 tiles * 32 bytes)

**DMA transfer:**
- From `ArtUnc_AniSOZ2_BG` + computed offset
- To VRAM $6600
- Size $60 (3 tiles)

**Frame count:** 2 base frames x up to 4 darkness variants = 7 total visual states; the max-darkness case uses a single frame because the computed index is pinned
**Timer period:** 8 frames
**Trigger:** Always active, but can be gated by `Anim_Counters = $7F` (set during boss arena to disable)
**Source art:** `ArtUnc_AniSOZ2_BG`
**Destination VRAM:** $6600

**Confidence:** HIGH

### AniPLC Scripts (shared with LRZ1)

SOZ does not define its own zone-specific AniPLC routine here. In the `Offs_AniFunc` table, both SOZ entries point at `AniPLC_LRZ1` (`sonic3k.asm` lines 53869 and 53871), and the same shared script is also used by LRZ1.

`AniPLC_LRZ1` is a plain `zoneanimstart` block with two declarations:
- `ArtUnc_AniLRZ1_0` -> VRAM tile `$354`
- `ArtUnc_AniLRZ1_1` -> VRAM tile `$350`

That makes it a shared art-loading dependency for the SOZ/LRZ1 animation slot, not a SOZ-specific animation system. The visible SOZ-specific animated behavior still comes from `AnimateTiles_SOZ1` and `AnimateTiles_SOZ2`.

**Confidence:** HIGH

## Palette Cycling

### Act 1 (AnPal_SOZ1)

**Disassembly location:** `sonic3k.asm` line 3466

**Description:** Single channel cycling 4 colors across palette line 3 (sand shimmer effect).

#### Channel 0: Sand shimmer

- **Counter:** `Palette_cycle_counters+$04`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $20 (wraps at $20, so 4 frames: offsets 0, 8, $10, $18)
- **Timer:** `Palette_cycle_counters+$0A`, period = 6 frames (reset to 5)
- **Table:** `AnPal_PalSOZ1` at line 4302 (16 words = 4 frames x 4 colors)
- **Destination:** `Normal_palette_line_3+$18` (4 words) and `Normal_palette_line_3+$1C` (4 words) = Palette line 2 (index 3), colors 12-15
- **Conditional:** Always active
- **Color values (4 frames):**

| Frame | Color 12 | Color 13 | Color 14 | Color 15 |
|-------|----------|----------|----------|----------|
| 0 | $02CE | $06CE | $00AE | $006C |
| 1 | $006C | $02CE | $06CE | $00AE |
| 2 | $00AE | $006C | $02CE | $06CE |
| 3 | $06CE | $00AE | $006C | $02CE |

**Note:** This is a simple 4-entry rotation of the same 4 colors, creating a shimmering wave effect.

**Confidence:** HIGH

### Act 2 (AnPal_SOZ2)

**Disassembly location:** `sonic3k.asm` line 3492

**Description:** Multi-layered system with a progressive darkness timer and integrated sand shimmer. The state byte `SOZ_darkness_level` is the long-period level counter; `Palette_cycle_counters+$06` is the active fade-step accumulator used by both the palette sweep and `AnimateTiles_SOZ2`.

#### Darkness Timer System

- **Master timer:** `Palette_cycle_counter1`, period = 900 frames (15 seconds), resets to 899
- **Darkness level:** `SOZ_darkness_level`, byte, range 0-5
- When the timer expires and the level is still below 5, the level increments by 1
- Only even-numbered levels start a fade pulse: `Palette_cycle_counters+$00 = 2` and `Palette_cycle_counters+$08 = 0`
- `Palette_cycle_counters+$06` is the active step counter. `sub_55EFC` seeds it to 4; `AnPal_SOZ2` increments it while darkening and decrements it while brightening, then masks it with `& 6` to pick the 26-color slice from `AnPal_PalSOZ2_Light`
- Darkness level 0 = full brightness, 5 = terminal darkness used after the Act 1 -> Act 2 transition and while the boss arena is darkened back down

#### Channel 0: Darkness palette transition

- **Active when:** `Palette_cycle_counters+$00 != 0`
- **Fade timer:** `Palette_cycle_counters+$08`, period = 4 frames (reset to 3)
- **Direction:** Positive value = darkening (decrement counter, add $34 to offset). Negative value = brightening (increment counter, subtract $34 from offset).
- **Step size:** $34 bytes per tick (26 colors = 11 line-3 + 15 line-4)
- **Table:** `AnPal_PalSOZ2_Light` at line 4328
- **Destination:** 
  - 11 words to `Normal_palette_line_3+$02` (palette line 2, colors 1-11)
  - 15 words to `Normal_palette_line_4+$02` (palette line 3, colors 1-15)
- **Table layout:** The table stores one 26-color slice per brightness step. `Palette_cycle_counters+$02` is the byte offset into that table, while `Palette_cycle_counters+$06` is the state used to advance or rewind the slice.

**`AnPal_PalSOZ2_Light` data structure (line 4328-4339):**

| Level | Palette Line 3 (colors 1-11) | Palette Line 4 (colors 1-15) |
|-------|------------------------------|------------------------------|
| 0 (bright) | $EEE,$8EE,$4EE,$0CE,$24C,$006,$000,$CEA,$886,$424,$0E0 | $EEE,$AEE,$6EE,$4AC,$068,$046,$022,$8AA,$468,$224,$002,$28E,$4EE,$08C,$00C |
| 1 | $8CE,$6CC,$4AC,$08A,$248,$004,$000,$8A8,$664,$422,$080 | $CEE,$ACC,$6CC,$48A,$266,$046,$022,$ACC,$468,$224,$002,$28E,$6EE,$28C,$22C |
| 2 | $2CE,$28A,$268,$246,$224,$202,$000,$464,$222,$200,$040 | $ACE,$8AA,$688,$468,$244,$024,$022,$CCC,$468,$224,$002,$26C,$6EE,$28C,$24C |
| 3 | $88A,$668,$646,$424,$224,$202,$000,$444,$222,$200,$040 | $8CE,$888,$666,$446,$222,$022,$022,$EEE,$468,$224,$002,$26A,$6EE,$48C,$26C |
| 4 | $C46,$824,$804,$402,$202,$200,$000,$422,$402,$200,$040 | $6AE,$664,$422,$402,$200,$000,$000,$EEE,$466,$224,$000,$046,$6EE,$48C,$26A |

**Note:** Level 0 is the initial `AnPal_PalSOZ2_Light` entry. `AnPal_PalSOZ2_Light_2` (line 4330) starts at level 0's line-4 data. The total table spans from the initial bright colors through 5 darkness steps.

#### Channel 1: Sand shimmer (darkness-aware)

- **Counter:** `Palette_cycle_counters+$04`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $20 (4 frames, same as Act 1)
- **Timer:** `Palette_cycle_counters+$0A`, period = 6 frames (reset to 5)
- **Table:** `AnPal_PalSOZ1` at line 4302, BUT indexed by darkness level:
  - Base table offset = `(Palette_cycle_counters+$06) << 5` -- each darkness step shifts the base by 32 bytes (one full rotation set)
  - The `AnPal_PalSOZ1` table has 5 sets of 16 words (one set per darkness level 0-4)
- **Destination:** Same as Act 1 -- `Normal_palette_line_3+$18` (colors 12-15)
- **Conditional:** Always active (runs even during darkness fade)

**Sand shimmer color sets per darkness level (from `AnPal_PalSOZ1`):**

| Level | Colors (4-frame rotation) |
|-------|--------------------------|
| 0 | $02CE, $06CE, $00AE, $006C |
| 1 | $028A, $06AC, $008A, $0048 |
| 2 | $0468, $0668, $0248, $0026 |
| 3 | $0424, $0846, $0224, $0222 |
| 4 | $0602, $0824, $0402, $0200 |

**Confidence:** HIGH

#### Light Switch Reset

When `Obj_SOZLightSwitch` is activated (line 86011):
- Sets `Palette_cycle_counter1 = (15*60)-1` (restart 15-second darkness timer)
- Sets `SOZ_darkness_level = 0` (fully bright)
- Reverses current fade direction by negating `Palette_cycle_counters+$06` into `Palette_cycle_counters+$00` (negative = brightening direction)
- Resets fade timer: `Palette_cycle_counters+$08 = 0`

The same reset is performed during:
- Boss arena initialization (SOZ2_BackgroundEvent stage $20, line 114604-114609)
- Act 2 darkness init (`sub_55EFC`, line 114042-114047) -- but sets darkness to level 5 (starts dark)

**`sub_55EFC` Act 2 init palette setup (line 114023):**
- Copies `word_560EA` (11 words) to `Normal_palette_line_3+$02` and `Target_palette_line_3+$02` -- these are the level 4 darkness palette for line 3
- Copies `word_56100` (15 words) to `Normal_palette_line_4+$02` and `Target_palette_line_4+$02` -- level 4 darkness palette for line 4
- Sets `SOZ_darkness_level = 5` (maximum darkness)
- Sets `Palette_cycle_counter1 = (30*60)-1` (1800 frames = 30 seconds before first brightening step)
- Sets `Palette_cycle_counters+$00 = 0` (no active fade)
- Sets `Palette_cycle_counters+$06 = 4` (at darkening step 4)
- Sets `Palette_cycle_counters+$02 = $D0` (palette table offset for level 4)

**This means Act 2 begins at near-total darkness and the first light switch activates a brightening sequence by reusing the same fade accumulator in reverse.**

**Confidence:** HIGH

## Notable Objects

| Object ID (SKL) | Name | Description | Notes |
|------------------|------|-------------|-------|
| 56 | `Obj_SOZQuicksand` | Quicksand pit (4 subtypes: normal, slide, waterfall, deep) | Subtype bits 6-7 select variant: $00=normal sinking, $40=sand slide, $80=sand waterfall, $C0=deep quicksand. Low bits = size. |
| 57 | `Obj_SOZSpawningSandBlocks` | Sand blocks that spawn periodically | Uses `Oscillating_table+$16` for bob. Spawns child blocks every $7F frames. |
| 58 | `Obj_SOZPathSwap` | SOZ-specific path switcher | At line 40023. |
| 59 | `Obj_SOZLoopFallthrough` | Loop fallthrough trigger | Catches player falling at y_vel >= $800, puts into spin state, applies gravity until past target Y. |
| 62 | `Obj_SOZPushableRock` | Pushable rock | Moved by player contact. |
| 63 | `Obj_SOZSpringVine` | Spring-loaded vine | Bounces player like a spring but with vine visual. |
| 64 | `Obj_SOZRisingSandWall` | Rising sand wall | Sand wall that rises up. |
| 65 | `Obj_SOZLightSwitch` | Light switch (Act 2) | Grabable switch -- player hangs from handle, pulls it down. Resets `SOZ_darkness_level` to 0 and restarts 15-second timer. Uses multi-sprite (main body + handle). |
| 66 | `Obj_SOZFloatingPillar` | Floating sand pillar | Solid platform. Art tile $001, palette 2. |
| 67 | `Obj_SOZSwingingPlatform` | Swinging platform | Pendulum-motion solid platform. |
| 68 | `Obj_SOZBreakableSandRock` | Breakable sand rock | Destroyed by spin attack. |
| 69 | `Obj_SOZPushSwitch` | Push switch | Floor-mounted switch activated by player weight. |
| 70 | `Obj_SOZDoor` | Door | Activated by switches. |
| 71 | `Obj_SOZSandCork` | Sand cork | Blocks sand flow; can be removed. |
| 72 | `Obj_SOZRapelWire` | Rappel wire | Swing wire with multiple chain segments. Player grabs and swings. |
| 73 | `Obj_SOZSolidSprites` | Solid sprite decorations | Static solid objects using shared sprite data. |
| -- | `Hyudoro` | Ghost controller (Act 2) | Loaded at level start for SOZ2 (line 62265). Spawns ghost bodies when `SOZ_darkness_level > 0`. Max ghosts: level 1=1, level 2-3=2, level 4-5=3. Spawn interval: $3F frames. |
| -- | `Hyudoro_body` | Ghost body (Act 2) | DPLC-driven sprite. Moves horizontally, attacks player. Only damages when `SOZ_darkness_level > 0`. Immune to attack -- player attacks cause ghost to fade out (plays `sfx_Bouncy`). Decrements `Hyudoro_count` on offscreen/fadeout. |
| -- | `SOZ_capsule` | Ghost capsule (Act 2) | SOZ-specific capsule variant. Knuckles or checkpoint-past players get it pre-opened. When opened, spawns `SOZ_capsule_Hyudoro` ghosts that fly outward. Calls `set_Hyudoro` to save checkpoint. |
| -- | `Obj_SOZMiniboss` | Act 1 miniboss (Stone Golem) | See Boss Sequences section. |
| -- | `Obj_SOZEndBoss` | Act 2 end boss (Eggman Golem) | See Boss Sequences section. |

**Badnik PLCs (PLCKosM_SOZ, line 64412):**
- `ArtTile_Skorp` / `ArtKosM_Skorp` -- Skorp (scorpion badnik)
- `ArtTile_Sandworm` / `ArtKosM_Sandworm` -- Sandworm badnik
- `ArtTile_Rockn` / `ArtKosM_Rockn` -- Rock'n (rock badnik)

## Cross-Cutting Concerns

### Water / Quicksand
SOZ does NOT use the engine's water subsystem (`DynamicWaterHeight` / `WaterTransition`). There are no water palette transitions or underwater physics. Instead, quicksand is implemented entirely through `Obj_SOZQuicksand` (object ID 56) which handles player sinking, speed reduction, and escape mechanics through direct manipulation of player position and velocity. The four subtypes create different sand behaviors:
- **$00 (normal):** Player sinks slowly, can jump out
- **$40 (slide):** Sand slide -- player moves diagonally
- **$80 (waterfall):** Sand pours downward
- **$C0 (deep):** Deep quicksand with faster sinking

### Screen Shake
Present in both acts:
- **Act 1:** `Screen_shake_flag` set to $FF by SOZ1_BackgroundEvent stage 0 when boss arena triggers. `sub_55D94` manages shake accumulation -- `_unkEE9C` rises from -8 toward $280 at ~0.75 per frame (3 out of every 4 frames). `ShakeScreen_Setup` called in the background event loop. Shake stops when `_unkEE9C >= $280`.
- **Act 2:** `ShakeScreen_Setup` called during boss arena states ($24, $28, $30). SOZ2_ScreenEvent applies `Screen_shake_offset` to `Camera_Y_pos_copy`.

### Character Branching
Minimal compared to other zones:
- **Start locations:** Separate Sonic and Knuckles start position binaries for both acts
- **Ghost capsule:** Knuckles (`character_id == 2`) gets pre-opened capsule and ghosts are active from level start without needing a checkpoint. For Sonic/Tails, ghosts only spawn after hitting a star post (`Last_star_post_hit != 0`).
- **Act 2 palette:** Palette 3 (Sonic/Tails) vs palette 5 (Knuckles) loaded during Act 1->2 transition
- **No chunk adjustments:** Unlike AIZ, SOZ has no `Adjust_SOZChunks` routines
- **No separate resize paths:** Both characters use the same background event state machines

### Dynamic Tilemap
- **Act 1 boss arena:** Queues `SOZ1_16x16_Custom_Kos` blocks and `ArtKosM_SOZ1_Custom` patterns at VRAM $315 when boss arena activates
- **Act 1->2 transition:** Queues `SOZ2_16x16_Secondary_Kos` blocks and `ArtKosM_SOZ2_Secondary` patterns at VRAM $315, plus PLC $2C
- **Act 2 boss arena:** Modifies the first 4 bytes of 16 tilemap rows to create the boss background window. Queues `SOZ2_16x16_Custom_Kos` and `ArtKosM_SOZ2_Custom` at VRAM $315
- **Act 2 post-boss:** Restores `SOZ2_16x16_Secondary_Kos` and `ArtKosM_SOZ2_Secondary`, loads palette $1B immediate
- **Act 2 `sub_5622C`:** Copies chunk data from offset $AB to offset $95/$8C within the tilemap structure -- used for the end-area tilemap update when Player X >= $4E80

### PLC Loading
- **SOZ1 level PLCs:** PLC $2A/$2B (= `PLC_2A_2B`): `ArtNem_SOZMisc` at `ArtTile_SOZMisc`, `ArtNem_SOZTile` at `ArtTile_SOZTile`
- **SOZ2 level PLCs:** PLC $2C/$2D (= `PLC_2C_2D`): `ArtNem_SOZMisc` at `ArtTile_SOZMisc`, `ArtNem_SOZ2Extra` at `ArtTile_SOZ2Extra`
- **Enemy PLCs:** `PLCKosM_SOZ`: Skorp, Sandworm, Rock'n
- **Boss PLCs:**
  - Act 1 miniboss: `ArtKosM_SOZMiniboss`, `ArtKosM_SOZSand`, `PLC_BossExplosion`
  - Act 2 end boss: `ArtKosM_SOZEndBoss`, PLC $6D, `Pal_SOZEndBoss1`/`Pal_SOZEndBoss2`
- **Ghost capsule:** `PLC_SOZGhostCapsule` (= `ArtNem_EggCapsule` at `ArtTile_SOZGhostCapsule`)
- **Act transition:** PLC $2C loaded during fade-to-black stage
- **Animals:** `PLC_Animals_SOZ` (line 180947)

### Time-of-Day / Darkness System (Act 2)

This is SOZ's signature mechanic. The system is not a one-shot fade; it is a two-layer state machine with a long-period darkness counter and a short-period palette sweep:

**Architecture:**
1. `SOZ_darkness_level` (byte, 0-5): current darkness intensity
2. `Palette_cycle_counter1`: 900-frame master timer
3. `Palette_cycle_counters+$06`: fade-step accumulator used for table selection and torch art
4. `Palette_cycle_counters+$00`: fade direction (+2 = darkening, negative = brightening, 0 = idle)
5. `Palette_cycle_counters+$02`: palette table byte offset (incremented or decremented by $34 per fade tick)
6. `Palette_cycle_counters+$08`: per-step fade timer (4-frame period)

**Flow:**
1. `sub_55EFC` seeds Act 2 with the dark baseline, sets `SOZ_darkness_level=5`, copies the darkest palette slice into `Normal_palette_line_3/4` and `Target_palette_line_3/4`, sets `Palette_cycle_counter1=(30*60)-1`, and initializes `Palette_cycle_counters+$06=4`, `+$02=$D0`, `+$00=0`
2. Every 900 frames, `AnPal_SOZ2` increments `SOZ_darkness_level` if it is still below 5
3. Only even-numbered levels start a fade pulse: `Palette_cycle_counters+$00=2` and `Palette_cycle_counters+$08=0`
4. Each fade tick (every 4 frames) advances or rewinds the table slice by $34 bytes and copies 26 colors from `AnPal_PalSOZ2_Light` into palette lines 3 and 4
5. `Obj_SOZLightSwitch` resets `SOZ_darkness_level=0`, restarts the 15-second timer, and negates `Palette_cycle_counters+$06` into `Palette_cycle_counters+$00` so the same accumulator can run in reverse
6. The boss arena forces `SOZ_darkness_level=0`, sets `Palette_cycle_counter1=$7FFF`, and disables `AnimateTiles_SOZ2` by holding `Anim_Counters=$7F`

**Visual progression:**
- Level 0: full desert daylight colors
- Level 1: first dim state
- Level 2: first fade pulse
- Level 3: steady dim state
- Level 4: second fade pulse
- Level 5: terminal darkness used by the Act 2 intro and the boss arena before the fight is lit back up

**Interaction with ghosts:** `Hyudoro_ctr` checks `SOZ_darkness_level`; ghosts only spawn when the level is above 0, and the max ghost count increases with darkness. Ghosts only deal damage when `SOZ_darkness_level > 0`.

**Interaction with animated tiles:** `AnimateTiles_SOZ2` uses `Palette_cycle_counters+$06 & 6` to select brightness-appropriate torch art frames. That means torch animation brightness follows the same sweep state as the palette fade instead of reading `SOZ_darkness_level` directly.

### Ghost Mechanics (Act 2)

**Spawner object (`Hyudoro`, line 195446):**
- Loaded for SOZ2 at level start (line 62265)
- Fixed screen position ($120, $A0) -- always present
- Only active when: Knuckles, OR `Last_star_post_hit != 0` (past a checkpoint)
- Spawn gating by `SOZ_darkness_level`:
  - Level 0: no spawning, clears `Hyudoro_count`
  - Level 1: max 1 ghost
  - Level 2-3: max 2 ghosts
  - Level 4-5: max 3 ghosts
- Spawn interval: $3F frames (63 frames) between ghosts

**Ghost bodies (`Hyudoro_body`, line 195494):**
- 9-state routine: init, wait, move, change direction (3 stages), attack (3 stages)
- Uses DPLC for sprite art (`DPLC_SOZGhosts`)
- Movement speed varies by darkness level: `Hyudoro_init_spd_tbl` = {-$100, -$100, -$100, -$180, -$180, -$200}
- Appearance animation uses 3 sets of fade-in patterns based on darkness level
- Collision: checks `Check_PlayerCollision` then `Check_PlayerAttack`. If player attacks: ghost fades out (not destroyed). If player doesn't attack: `HurtCharacter_Directly`.
- Bounces off screen edges (X < $A0 or X > $1A0)
- Automatically fades out when `SOZ_darkness_level == 0` (light restored)

**Ghost capsule (`SOZ_capsule`, line 195806):**
- SOZ-specific capsule variant
- Knuckles or post-checkpoint: capsule shown as pre-opened (frame 1, bit 5 set)
- When broken, spawns 6 `SOZ_capsule_Hyudoro` children with varied velocities and trajectories
- Calls `set_Hyudoro` to save progress (sets `Last_star_post_hit=1`, saves SOZ2 start position)

### Act 1 to Act 2 Seamless Transition

The transition is a multi-phase process spanning SOZ1_BackgroundEvent stages 2-5:

1. **Stage $08 (trigger):** Requires `Events_fg_5 == $55` (set by door completion) AND both players past coordinates
2. **Stage $0C (fade phase 1):** 16-frame delay, then 21 `Pal_ToBlack` steps on palette lines 0-1 (every other frame)
3. **Stage $10 (fade phase 2):** 8-frame delay, then 21 `Pal_ToBlack` steps on palette lines 2-3
4. **Stage $14 (reload):** Waits for the Kos queue to clear, sets `Current_zone_and_act=$801`, reloads level data/solids/palettes, applies `sub_55EFC`, clears palette lines 3-4 to black, moves both players to ($140, $3AC), and locks the camera to ($A0, $34C)

Between stages $10 and $14, the code queues `SOZ2_16x16_Secondary_Kos`, `ArtKosM_SOZ2_Secondary`, and PLC $2C for the Act 2 art.

The Act 2 `SOZ2_BackgroundEvent` then starts at stage $00, waits for the foreground draw to settle, and performs its own fade-from-black (stages $04-$0C) with a title card spawn at step 5 of the first fade phase.

### Extended Y Wrap (Act 2)

SOZ Act 2 uses a non-standard level height configuration:
- `Screen_Y_wrap_value = $7FF` (vs standard $3FF)
- `Camera_Y_pos_mask = $7F0` (vs standard $3F0)
- `Layout_row_index_mask = $3C`
- Level bounds: Y start = -$100, Y end = $800

This allows the pyramid interior to extend vertically beyond the normal 1024px limit. The `SOZ2_ScreenEvent` stage $00 handler re-applies these values when re-initializing the FG plane.

## Implementation Notes

### Priority Order
1. **Events (BackgroundEvent + ScreenEvent)** -- SOZ is unusual in using `No_Resize` with all logic in Background/Screen events. Implement the Act 1 trigger/fade/reload chain first, then the Act 2 rising-sand path (`Special_events_routine=$10`), then the boss arena state machine (`Events_bg+$08/$0A`, `sub_56706`, `sub_56A12`).
2. **Palette Cycling (AnPal)** -- The darkness system is foundational to Act 2's identity. Implement the `SOZ_darkness_level` timer, the `Palette_cycle_counters` fade accumulator, `AnPal_PalSOZ2_Light` table sweeps, and the light-switch reversal logic together so torch art and palette fades stay in sync.
3. **Parallax (Deform)** -- Act 1 uses per-line FG/BG deformation via `AIZ2_SOZ1_LRZ3_FGDeformDelta` (shared with AIZ2). Act 2 has multiple BG scroll modes that switch as the player progresses through outdoor, rising sand, indoor, and boss areas.
4. **Animated Tiles** -- Custom handlers for both acts, driven by scroll position (Act 1) or timer+darkness (Act 2).
5. **Ghost System** -- The `Hyudoro` spawner, ghost bodies, and capsule variant are tightly coupled with the darkness system.

### Dependencies
- Palette cycling requires `SOZ_darkness_level` to be managed by the darkness timer (AnPal_SOZ2) and reset by light switches
- Ghost spawning depends on `SOZ_darkness_level > 0` and `Last_star_post_hit` (or Knuckles)
- AnimateTiles_SOZ2 torch brightness depends on `Palette_cycle_counters+$06 & 6` and therefore on the same sweep state as `AnPal_SOZ2`
- Boss arena disables darkness timer and animated tiles
- Act 1->2 transition requires `Events_fg_5=$55` from door sequence AND both players past coordinates
- Extended Y wrap must be set before Act 2 tile drawing begins
- Sand wall collapse physics in boss area require `Events_bg+$08`/`$0A`, `sub_56A12` solid objects, and the HScroll table collapse sequence in `sub_56706`

### Known Risks
- **Act 1->2 seamless transition:** Complex multi-phase fade and level reload while maintaining player positions. The `sub_55EFC` initialization sets darkness level 5 and specific palette counters -- any mismatch will produce wrong initial colors. Confidence: MEDIUM.
- **Sand wall collapse physics:** The 6-state sub-machine in `sub_56706` with per-segment velocity, gravity, settling detection, and reverse animation is the most complex BG animation in the zone. Confidence: MEDIUM.
- **Darkness palette system:** The bidirectional fade with variable-length table indexing (`$34` bytes per step, driven by `Palette_cycle_counters+$06` which can go negative during brightening) requires careful counter management. Confidence: MEDIUM.
- **Extended Y wrap:** Non-standard `Screen_Y_wrap_value=$7FF` may expose edge cases in tile drawing routines that assume $3FF. Confidence: MEDIUM.
- **Rising sand special event:** Uses `Special_events_routine=$10` to hook into the global `SpecialEvents` system, creating per-frame BG collision with `Background_collision_flag` and camera Y offset. Interactions with normal collision system need testing. Confidence: LOW-MEDIUM.
