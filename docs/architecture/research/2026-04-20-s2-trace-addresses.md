# Sonic 2 Trace Recorder RAM Addresses

This note resolves the Sonic 2 REV01 RAM labels the v3 trace recorder needs beyond the frozen counter matrix. All addresses below are 68K absolute RAM addresses from `docs/s2disasm/`; if Task 2 reads stable-retro work RAM directly, subtract `$FF0000` before indexing.

## Core RAM / SST Values

| Recorder field | REV01 label(s) | Value | Evidence | Notes |
| --- | --- | --- | --- | --- |
| Game mode byte | `Game_Mode` | `$FFFFF600` | `docs/s2disasm/s2.constants.asm:1379-1393` | The preceding unused block ends at `$FFFFF5FF`, so `Game_Mode` is the next byte at `$FFFFF600`. |
| In-level game-mode value | `GameModeID_Level` | `$0C` | `docs/s2disasm/s2.constants.asm:461-469`, `docs/s2disasm/s2.asm:4509` | REV01 uses `GameModeID_Level`; older names like `id_Level` / `IDs_Level` are not the active label in this disassembly. |
| Player SST base | `MainCharacter` | `$FFFFB000` | `docs/s2disasm/s2.constants.asm:1096-1102`, `docs/s2disasm/s2.asm:4267` | `Object_RAM` starts at `$FFFFB000`, and `MainCharacter` is the first slot in that pool. |
| SST slot size | `object_size` | `$40` bytes | `docs/s2disasm/s2.constants.asm:191-193` | `object_size_bits = 6`, so `object_size = 1 << 6 = $40`. |
| Total SST slots from `Object_RAM_End - Object_RAM` | `Object_RAM_End - Object_RAM` | `$80` slots (`128`) | `docs/s2disasm/s2.constants.asm:1096-1145`, `docs/s2disasm/s2.constants.asm:191-193`, `docs/s2disasm/s2.asm:61736-61737` | The normal SST pool is the label-backed range from `Object_RAM` through `Object_RAM_End`, not the later level-only extension. |
| Camera X | `Camera_X_pos` | `$FFFFEE00` | `docs/s2disasm/s2.constants.asm:1215-1224`, `docs/s2disasm/s2.constants.asm:1243-1251` | `Camera_Positions` plus `Camera_Positions_P2` occupy `$40` bytes immediately before the next block reaches `$FFFFEE45`, so `Camera_X_pos` starts at `$FFFFEE00`. |
| Camera Y | `Camera_Y_pos` | `$FFFFEE04` | `docs/s2disasm/s2.constants.asm:1215-1224`, `docs/s2disasm/s2.constants.asm:1243-1251` | Second longword in the same camera block. |
| Current zone byte | `Current_Zone` | `$FFFFFE10` | `docs/s2disasm/s2.constants.asm:1674-1678` | `Current_ZoneAndAct` is two bytes; the trailing comment fixes the following unused range at `$FFFFFE13-$FFFFFE15`. |
| Current act byte | `Current_Act` | `$FFFFFE11` | `docs/s2disasm/s2.constants.asm:1674-1678` | Byte immediately after `Current_Zone`. |
| Ring count | `Ring_count` | `$FFFFFE20` | `docs/s2disasm/s2.constants.asm:1688-1703` | The comment after `Score` fixes the later unused range at `$FFFFFE2A-$FFFFFE2F`, which places `Ring_count` at `$FFFFFE20`. |
| Held input P1 | `Ctrl_1_Held` | `$FFFFF604` | `docs/s2disasm/s2.constants.asm:1382-1393` | `Ctrl_1` is the 2-byte pair at `$FFFFF604-$FFFFF605`; the held byte is the first byte of that pair. |
| Held-logical input P1 | `Ctrl_1_Logical` / `Ctrl_1_Held_Logical` | `$FFFFF602` | `docs/s2disasm/s2.constants.asm:1384-1386` | `Ctrl_1_Logical` is the 2-byte base pair spanning `$FFFFF602-$FFFFF603`; the actual recorded byte for this field is `Ctrl_1_Held_Logical`, the first byte of that pair at `$FFFFF602`. |
| Player primary status offset | `status` | `$22` | `docs/s2disasm/s2.constants.asm:36` | Offset inside `MainCharacter`. |
| Player secondary status offset | `status_secondary` | `$2B` | `docs/s2disasm/s2.constants.asm:53-54` | Offset inside `MainCharacter`. |
| Player routine offset | `routine` | `$24` | `docs/s2disasm/s2.constants.asm:37` | Offset inside `MainCharacter`. |
| Control-lock timer offset | `move_lock` | `$2E` | `docs/s2disasm/s2.constants.asm:57` | Two-byte horizontal control lock timer at `$2E-$2F`. |
| Stand-on-object tracker offset | `interact` | `$3D` | `docs/s2disasm/s2.constants.asm:68-70`, `docs/s2disasm/s2.constants.asm:187` | Use `interact`, not `top_solid_bit`: `interact` stores the last object stood on, while `top_solid_bit` only selects solidity bits. |
| Player hurt routine value | `Obj01_Hurt` | `$04` | `docs/s2disasm/s2.asm:35867-35872`, `docs/s2disasm/s2.asm:64553` | The player routine jump table assigns hurt to `4`; several checks treat routine `4` as hurt. |
| Player death routine value | `Obj01_Dead` | `$06` | `docs/s2disasm/s2.asm:35867-35872`, `docs/s2disasm/s2.asm:5265` | The player routine jump table assigns dead to `6`; gameplay checks test for routine `6` as dead. |
| First dynamic SST slot index | `Dynamic_Object_RAM` | `$10` (`16`) | `docs/s2disasm/s2.constants.asm:1101-1140`, `docs/s2disasm/s2.constants.asm:191-193`, `docs/s2disasm/s2.asm:33481-33506` | There are 16 fixed `object_size` slots before `Dynamic_Object_RAM`, so `(Dynamic_Object_RAM - Object_RAM) / object_size = $10`. This also puts `Dynamic_Object_RAM` at `$FFFFB400`. |
| Last SST slot exclusive as slot index | `Object_RAM_End` | `$80` (`128`) | `docs/s2disasm/s2.constants.asm:1096-1145`, `docs/s2disasm/s2.constants.asm:191-193`, `docs/s2disasm/s2.asm:61736-61737` | The recorder should stop before slot index `128`. |

### Object Pool Boundary Note

Use `Object_RAM_End` for the recorder's normal SST scan window, not `LevelOnly_Object_RAM_End`.

- `Object_RAM_End` is the bound used by generic object-pool loops such as `moveq #(Object_RAM_End-Object_RAM)/object_size-1,d1` (`docs/s2disasm/s2.asm:61736-61737`).
- Level code separately acknowledges a wider level-only range with `move.w #(LevelOnly_Object_RAM_End-Object_RAM)/object_size-1,d7 ; run the first $90 objects in levels` (`docs/s2disasm/s2.asm:29616`).
- `AllocateObjectAfterCurrent` also hard-codes `Dynamic_Object_RAM_End` as `$D000`, which matches `Object_RAM = $B000`, first dynamic slot `$10`, and `0x70` dynamic slots (`docs/s2disasm/s2.asm:33481-33506`).
- `docs/s2disasm/s2.asm:4267` still comments `Object_RAM` as `$B000-$D5FF`; treat that comment as stale. The label-backed SST bounds in the constants file and object-loop code are the values Task 2 should follow.

## CSV Status Bit Mapping

All seven CSV booleans come from the primary `status` byte at offset `$22`. None of these seven semantics live in `status_secondary`; `status_secondary` is used for shield, invincibility, speed shoes, and sliding instead (`docs/s2disasm/s2.constants.asm:234-252`).

| CSV column | Source | Bit number | Bit mask | Evidence | Notes |
| --- | --- | --- | --- | --- | --- |
| `facing_left` | `status` | `0` | `$01` | `docs/s2disasm/s2.constants.asm:198-210` | `status.player.x_flip = render_flags.x_flip`, and `render_flags.x_flip = 0`. |
| `in_air` | `status` | `1` | `$02` | `docs/s2disasm/s2.constants.asm:210-216`, `docs/s2disasm/s2.asm:5496` | Primary airborne flag. |
| `rolling` | `status` | `2` | `$04` | `docs/s2disasm/s2.constants.asm:210-216`, `docs/s2disasm/s2.asm:50549` | REV01 explicitly defines this as spinning, covering rolling and jump ball state. |
| `on_object` | `status` | `3` | `$08` | `docs/s2disasm/s2.constants.asm:210-216`, `docs/s2disasm/s2.asm:35760-35761` | Boolean "stood on an object" flag. The specific ridden object identity lives separately in `interact` at offset `$3D`. |
| `roll_jump` | `status` | `4` | `$10` | `docs/s2disasm/s2.constants.asm:214`, `docs/s2disasm/s2.asm:59446` | REV01 name is `status.player.rolljumping`. |
| `pushing` | `status` | `5` | `$20` | `docs/s2disasm/s2.constants.asm:215`, `docs/s2disasm/s2.asm:35244` | Pressing against an object. |
| `underwater` | `status` | `6` | `$40` | `docs/s2disasm/s2.constants.asm:216`, `docs/s2disasm/s2.asm:36076` | Water-state flag used by player logic. |

## Zone / Act Naming for `ZONE_NAMES`

REV01's zone IDs are the values in `Current_Zone`; acts are the values in `Current_Act`. Acts are zero-based in RAM. The title-card table also confirms the canonical three-letter abbreviations used throughout the disassembly (`TC_EHZ`, `TC_CPZ`, `TC_ARZ`, and so on). (`docs/s2disasm/s2.constants.asm:381-429`, `docs/s2disasm/s2.asm:27274-27285`, `docs/s2disasm/s2.asm:28358-28375`)

Recommended `ZONE_NAMES` mapping for Task 2:

| Zone ID | REV01 zone label | Canonical short name | Act interpretation | Evidence |
| --- | --- | --- | --- | --- |
| `$00` | `emerald_hill_zone` | `ehz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:381`, `docs/s2disasm/s2.constants.asm:405-406`, `docs/s2disasm/s2.asm:28359` |
| `$01` | `zone_1` | `unknown_01` | Unused; keep fallback naming | `docs/s2disasm/s2.constants.asm:382`, `docs/s2disasm/s2.asm:28360` |
| `$02` | `wood_zone` | `wz` | Prototype; Acts 1/2 at constants level | `docs/s2disasm/s2.constants.asm:383`, `docs/s2disasm/s2.constants.asm:426-427` |
| `$03` | `zone_3` | `unknown_03` | Unused; keep fallback naming | `docs/s2disasm/s2.constants.asm:384` |
| `$04` | `metropolis_zone` | `mtz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:385`, `docs/s2disasm/s2.constants.asm:419-420`, `docs/s2disasm/s2.asm:28363` |
| `$05` | `metropolis_zone_2` | `mtz` | `Current_Act = 0` is Act 3 | `docs/s2disasm/s2.constants.asm:386`, `docs/s2disasm/s2.constants.asm:421`, `docs/s2disasm/s2.asm:27281-27285`, `docs/s2disasm/s2.asm:28364` |
| `$06` | `wing_fortress_zone` | `wfz` | Single act, `Current_Act = 0` | `docs/s2disasm/s2.constants.asm:387`, `docs/s2disasm/s2.constants.asm:423`, `docs/s2disasm/s2.asm:28365` |
| `$07` | `hill_top_zone` | `htz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:388`, `docs/s2disasm/s2.constants.asm:413-414`, `docs/s2disasm/s2.asm:28366` |
| `$08` | `hidden_palace_zone` | `hpz` | Prototype; Acts 1/2 at constants level | `docs/s2disasm/s2.constants.asm:389`, `docs/s2disasm/s2.constants.asm:428-429`, `docs/s2disasm/s2.asm:28367` |
| `$09` | `zone_9` | `unknown_09` | Unused; keep fallback naming | `docs/s2disasm/s2.constants.asm:390`, `docs/s2disasm/s2.asm:28368` |
| `$0A` | `oil_ocean_zone` | `ooz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:391`, `docs/s2disasm/s2.constants.asm:417-418`, `docs/s2disasm/s2.asm:28369` |
| `$0B` | `mystic_cave_zone` | `mcz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:392`, `docs/s2disasm/s2.constants.asm:415-416`, `docs/s2disasm/s2.asm:28370` |
| `$0C` | `casino_night_zone` | `cnz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:393`, `docs/s2disasm/s2.constants.asm:411-412`, `docs/s2disasm/s2.asm:28371` |
| `$0D` | `chemical_plant_zone` | `cpz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:394`, `docs/s2disasm/s2.constants.asm:407-408`, `docs/s2disasm/s2.asm:28372` |
| `$0E` | `death_egg_zone` | `dez` | Single act, `Current_Act = 0` | `docs/s2disasm/s2.constants.asm:395`, `docs/s2disasm/s2.constants.asm:424`, `docs/s2disasm/s2.asm:28373` |
| `$0F` | `aquatic_ruin_zone` | `arz` | `Current_Act` `0/1` -> Acts 1/2 | `docs/s2disasm/s2.constants.asm:396`, `docs/s2disasm/s2.constants.asm:409-410`, `docs/s2disasm/s2.asm:28374` |
| `$10` | `sky_chase_zone` | `scz` | Single act, `Current_Act = 0` | `docs/s2disasm/s2.constants.asm:397`, `docs/s2disasm/s2.constants.asm:422`, `docs/s2disasm/s2.asm:27275-27280`, `docs/s2disasm/s2.asm:28375` |

For the standard Sonic 2 trace fixtures, the canonical short names are therefore:

`ehz`, `cpz`, `arz`, `cnz`, `htz`, `mcz`, `ooz`, `mtz`, `scz`, `wfz`, `dez`

`hpz` is also defined by REV01 if prototype coverage is ever needed. Unused IDs `$01`, `$03`, and `$09` should keep fallback `unknown_%02x` naming instead of inventing fixture names.
