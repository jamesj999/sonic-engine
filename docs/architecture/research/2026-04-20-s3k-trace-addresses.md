# Sonic 3K Trace Recorder RAM Addresses

This note resolves the Sonic 3 & Knuckles RAM labels the v3 trace recorder needs beyond the
frozen counter matrix. All addresses below are 68K absolute RAM addresses from
`docs/skdisasm/`; BizHawk Genesis `mainmemory` strips the `$FF0000` base, so the recorder uses
the low 16-bit offsets shown in the notes column.

## Core RAM / OST Values

| Recorder field | S3K label(s) | Value | Evidence | Notes |
| --- | --- | --- | --- | --- |
| Game mode byte | `Game_mode` | `$FFFFF600` | `docs/skdisasm/sonic3k.constants.asm:525-531` | BizHawk `mainmemory` offset `$F600`. |
| In-level game-mode value | level mode sentinel | `$0C` | `docs/skdisasm/sonic3k.asm:5628`, `6578`, `16778` | S3K writes `#$0C` to `(Game_mode).w` when entering a normal level. |
| Held input P1 | `Ctrl_1_held` | `$FFFFF604` | `docs/skdisasm/sonic3k.constants.asm:528-531` | `Ctrl_1_held_logical` is the logical copy at `$FFFFF602`; raw held input remains at `$FFFFF604`. |
| Held-logical input P1 | `Ctrl_1_held_logical` | `$FFFFF602` | `docs/skdisasm/sonic3k.constants.asm:528-531` | BizHawk offset `$F602`. |
| Player 1 OST base | `Player_1` | `$FFFFB400` | `docs/skdisasm/sonic3k.constants.asm:303-307` | First entry in `Object_RAM`. BizHawk offset `$B400`. |
| Player 2 OST base | `Player_2` | `$FFFFB44A` | `docs/skdisasm/sonic3k.constants.asm:303-305` | `Player_2 = Player_1 + object_size`. |
| OST slot size | `object_size` | `$4A` bytes | `docs/skdisasm/sonic3k.constants.asm:113`, `303-307` | Critical S3K divergence from S1/S2 `$40`. |
| Total OST slots | `Object_RAM` pool | `110` | `docs/skdisasm/sonic3k.constants.asm:303-323` | 3 fixed entries + 90 dynamic + 17 fixed in-level/support entries. |
| First dynamic OST slot index | `Dynamic_object_RAM` | `3` | `docs/skdisasm/sonic3k.constants.asm:304-307` | `(Dynamic_object_RAM - Player_1) / $4A = 3`. |
| Dynamic OST slot count | `Dynamic_object_RAM ... Dynamic_object_RAM_end` | `90` | `docs/skdisasm/sonic3k.constants.asm:307-308` | Dynamic scan window is slots `3..92`. |
| Camera X | `Camera_X_pos` | `$FFFFEE7A` | `docs/skdisasm/sonic3k.constants.asm:386-415`, `docs/architecture/research/2026-04-21-s3k-trace-addresses.md` | `Apparent_zone_and_act` anchors `$EE50`; counting the intervening fields places `Camera_X_pos` at `$EE7A` (`mainmemory $EE7A`). |
| Camera Y | `Camera_Y_pos` | `$FFFFEE7E` | `docs/skdisasm/sonic3k.constants.asm:386-415`, `docs/architecture/research/2026-04-21-s3k-trace-addresses.md` | Immediately follows `Camera_X_pos`. |
| Current zone/act word | `Current_zone_and_act` | `$FFFFFE14` | `docs/skdisasm/sonic3k.constants.asm:790-793`, `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md` | Frozen by the lag-model matrix. |
| Current zone byte | `Current_zone` | `$FFFFFE14` | `docs/skdisasm/sonic3k.constants.asm:791-793` | BizHawk offset `$FE14`. |
| Current act byte | `Current_act` | `$FFFFFE15` | `docs/skdisasm/sonic3k.constants.asm:791-793` | BizHawk offset `$FE15`. |
| Ring count | `Ring_count` | `$FFFFFE24` | `docs/skdisasm/sonic3k.constants.asm:800-806`, `790-793` | Starting from frozen `Current_zone_and_act = $FE14`, the intervening HUD bytes place `Ring_count` at `$FE24`. |
| Player mode | `Player_mode` | `$FFFFFF08` | `docs/skdisasm/sonic3k.constants.asm:870-892` | `Perfect_rings_flag` ends at `$FF07`; `Player_mode` is the next word at BizHawk offset `$FF08`. |
| Player routine offset | `routine` | `$05` | `docs/skdisasm/sonic3k.constants.asm:20`, `docs/skdisasm/sonic3k.asm:21883-21891` | S3K player dispatch reads `routine(a0)` at offset `$05`, not `$24`. |
| Player primary status offset | `status` | `$2A` | `docs/skdisasm/sonic3k.constants.asm:30` | Primary status byte used for the CSV booleans. |
| Player secondary status offset | `status_secondary` | `$2B` | `docs/skdisasm/sonic3k.constants.asm:54` | Shield / invincibility / speed-shoes flags live here; not emitted into v3 CSV. |
| Player tertiary status offset | `status_tertiary` | `$37` | `docs/skdisasm/sonic3k.constants.asm:65` | Character-specific tertiary flags; not emitted into v3 CSV. |
| Control-lock timer offset | `move_lock` | `$32` | `docs/skdisasm/sonic3k.constants.asm:61` | Word countdown inside the player OST. |
| Global control-lock byte | `Ctrl_1_locked` | `$FFFFF7CA` | `docs/skdisasm/sonic3k.constants.asm:683-689`, `docs/architecture/research/2026-04-21-s3k-trace-addresses.md` | Useful extra guard for intro/cutscene recording. |
| Stand-on-object tracker offset | `interact` | `$42` | `docs/skdisasm/sonic3k.constants.asm:74` | Stores the RAM address of the ridden object, not an S1/S2-style slot byte. |
| Player X position | `x_pos` | `$10` | `docs/skdisasm/sonic3k.constants.asm:11`, `51-75` | Player positions are 32-bit: pixel word at `$10`, subpixel word at `$12`. |
| Player Y position | `y_pos` | `$14` | `docs/skdisasm/sonic3k.constants.asm:12`, `51-75` | Pixel word at `$14`, subpixel word at `$16`. |
| Player X velocity | `x_vel` | `$18` | `docs/skdisasm/sonic3k.constants.asm:22` | Signed word. |
| Player Y velocity | `y_vel` | `$1A` | `docs/skdisasm/sonic3k.constants.asm:23` | Signed word. |
| Player ground speed | `ground_vel` | `$1C` | `docs/skdisasm/sonic3k.constants.asm:51` | Signed word. |
| Player angle | `angle` | `$26` | `docs/skdisasm/sonic3k.constants.asm:29` | Same semantic use as S1/S2. |
| Player hurt routine value | `routine = 4` | `$04` | `docs/skdisasm/sonic3k.asm:21080-21111`, `21889-21891`, `26087-26094`, `30337-30344` | `HurtCharacter` writes `#4,routine(a0)` and all three character index tables map entry `4` to the hurt state. |
| Player death routine value | `routine = 6` | `$06` | `docs/skdisasm/sonic3k.asm:21111-21144`, `21889-21892`, `26087-26095`, `30337-30345` | `Kill_Character` transitions into routine `6`; all three character tables map entry `6` to death. |

## Player_mode Routing

Rule: the v3 physics row always records `Player_1`. `Player_mode` is diagnostic only.

| Player_mode | Value | Trace source | Notes |
| --- | ---: | --- | --- |
| Sonic + Tails | `0` | `Player_1` | Sonic is in `Player_1`; Tails remains in `Player_2`. |
| Sonic alone | `1` | `Player_1` | `Player_2` is inert. |
| Tails alone | `2` | `Player_1` | Tails occupies `Player_1` in solo mode. |
| Knuckles alone | `3` | `Player_1` | Knuckles occupies `Player_1` in solo mode. |

## CSV Status Bit Mapping

The v3 CSV keeps the S1-compatible semantic set. S3K-specific shield and super-form flags remain
diagnostic only and stay out of `physics.csv`.

| CSV column | Source | Bit number | Bit mask | Evidence | Notes |
| --- | --- | ---: | ---: | --- | --- |
| `facing_left` | `status` | `0` | `$01` | `docs/skdisasm/sonic3k.constants.asm:173-180` | `Status_Facing = 0`. |
| `in_air` | `status` | `1` | `$02` | `docs/skdisasm/sonic3k.constants.asm:175` | `Status_InAir = 1`. |
| `rolling` | `status` | `2` | `$04` | `docs/skdisasm/sonic3k.constants.asm:176` | `Status_Roll = 2`. |
| `on_object` | `status` | `3` | `$08` | `docs/skdisasm/sonic3k.constants.asm:177` | `Status_OnObj = 3`; object identity still comes from `interact`. |
| `roll_jump` | `status` | `4` | `$10` | `docs/skdisasm/sonic3k.constants.asm:178` | `Status_RollJump = 4`. |
| `pushing` | `status` | `5` | `$20` | `docs/skdisasm/sonic3k.constants.asm:179` | `Status_Push = 5`. |
| `underwater` | `status` | `6` | `$40` | `docs/skdisasm/sonic3k.constants.asm:180` | `Status_Underwater = 6`. |

S3K-only flags intentionally excluded from the v3 CSV:

- `status_secondary` bits `Status_Shield`, `Status_Invincible`, `Status_SpeedShoes`,
  `Status_FireShield`, `Status_LtngShield`, `Status_BublShield`
- `status_tertiary` character-specific flags such as super / hyper / insta-shield state

## Zone / Act Naming for `ZONE_NAMES`

The recorder should use the canonical short names already used by the engine and trace harness.

| Zone ID | Zone label | Canonical short name | Evidence |
| --- | --- | --- | --- |
| `$00` | Angel Island Zone | `aiz` | `docs/skdisasm/sonic3k.constants.asm`, `s3k-disasm-guide` zone table |
| `$01` | Hydrocity Zone | `hcz` | same |
| `$02` | Marble Garden Zone | `mgz` | same |
| `$03` | Carnival Night Zone | `cnz` | same |
| `$04` | Flying Battery Zone | `fbz` | same |
| `$05` | IceCap Zone | `icz` | same |
| `$06` | Launch Base Zone | `lbz` | same |
| `$07` | Mushroom Hill Zone | `mhz` | same |
| `$08` | Sandopolis Zone | `soz` | same |
| `$09` | Lava Reef Zone | `lrz` | same |
| `$0A` | Sky Sanctuary Zone | `ssz` | same |
| `$0B` | Death Egg Zone | `dez` | same |
| `$0C` | Doomsday Zone | `ddz` | same |
| `$0D` | Hidden Palace Zone | `hpz` | same |

## Lag Counter Semantics

`Lag_frame_count` is diagnostic only.

- `docs/skdisasm/sonic3k.asm:570` increments `(Lag_frame_count).w` from V-int routine 0 during lag
  VBlanks.
- `docs/skdisasm/sonic3k.asm:786` clears `(Lag_frame_count).w` at the end of a normal frame.
- The counter therefore reads `0` on non-lag frames and a small positive value when gameplay did
  not advance but VBlank still ran.

Replay phase selection must continue to use the recorded `gameplay_frame_counter` and
`vblank_counter` deltas rather than branching directly on `lag_counter`.
