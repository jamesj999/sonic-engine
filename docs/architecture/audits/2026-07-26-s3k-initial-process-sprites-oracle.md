# S3K initial `Process_Sprites` ROM oracle

## Capture identity

The attended capture used the locked-on World ROM with SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6` and BizHawk 2.11 GPGX. ROM bytes
at `$00647E` are `4E B9 00 01 AA DA`, an absolute-long `jsr $0001AADA`;
`Process_Sprites` is assembled at `$1AADA`, and the return PC is therefore
`$006484`. This agrees with `loc_6468` and the `Process_Sprites` source
(`docs/skdisasm/sonic3k.asm:7848-7856,35965-36008`).

The local raw artifact is
`target/initial-process-sprites-oracle/aiz1.jsonl`. References below use its
one-based lines: line 1 is `ADJACENT_MINUS_ONE_PRE_SETUP`, line 2 is
`POST_INITIAL_PROCESS_SPRITES`, and line 3 is
`FIRST_LEVEL_LOOP_PLAYER_ENTRY`. The artifact is deliberately untracked.

## Stable observations

| State | Pre-call (line 1) | Return (line 2) | First `LevelLoop` P1 entry (line 3) |
|---|---:|---:|---:|
| PC | `$647E` | `$6484` | `$10A94` |
| emulator frame | 799 | 799 | 800 |
| `Level_frame_counter` | 0 | 0 | 1 |
| `V_int_run_count` | 762 | 762 | 763 |
| oscillation control word | `$007D` | `$007D` | `$007D` |
| `Water_flag` byte | 1 | 1 | 1 |
| raw/logical P1 and P2 controls | all zero | all zero | all zero |
| collision-list byte count | 0 | 0 | 0 |
| absolute dynamic slot 3 pointer | 0 | 0 | 0 |

Thus the initial pass does not advance the level, VInt, emulator-frame, or
observed oscillation epochs. The ordinary boundary advances level and VInt
once. Control remains neutral throughout setup, consistent with the locked
control writes before `loc_6468` (`sonic3k.asm:7765-7774`).
`Water_flag` is the byte at `$F730`
(`sonic3k.constants.asm:618-633`; byte reads at
`sonic3k.asm:7777,8474-8478`) and remains 1 at all three boundaries: AIZ1 has
water enabled, and the initial `Process_Sprites` pass does not change that
level water state.

`Collision_response_list` starts at `$E380` and is declared as a byte array,
but its first two bytes are a native big-endian **word** byte count:
`Touch_Process` reads it with `move.w (a4)+,d6`,
`Add_SpriteToCollisionResponseList` compares/increments it by 2, and slot 2
clears it with `move.w #0`
(`sonic3k.constants.asm:330`;
`sonic3k.asm:8467-8469,20655-20667,21200-21209`). The probe therefore keeps a
`u16` read and names the field `collision_list_byte_count`; the value is zero
at all three captured boundaries.

P1 stays at centre `$0040,$0420`, and P2 at `$0020,$0424`; all position
fractions and x/y/ground velocities remain zero. Both player routines change
from 0 to 2. P1 `object_control` changes `$00->$53`; P2 remains `$00`.
Both `air_left` bytes change `0->30`, and both `flip_speed` bytes change
`0->4`, matching the two native init routines
(`sonic3k.asm:21931-21940,26139-26155`). Status, secondary status,
double-jump flag, flips remaining, move lock, animation
id/previous/frame/timer, collision flags/property, and the actual
invulnerability, invincibility, and speed-shoes timers remain zero (raw lines
1-2). The probe uses the player-specific offsets at
`sonic3k.constants.asm:50-65`; in particular `$30/$31/$35` are not mislabeled
as power-up timers or `air_left`.

## History and sidekick CPU

`Pos_table_index` remains zero and all captured Tails CPU globals remain zero.
The preceding history entry `$FC` changes from zero to P2's centre
`$0020,$0424`, but P2 does not write it. `Sonic_Init` temporarily moves P1
from `$0040,$0420` to `$0020,$0424`, calls
`Reset_Player_Position_Array` to fill the shared history at that adjusted
position, then restores P1 before returning
(`sonic3k.asm:21931-21941,22166-22193`). The later non-competition
`Tails_Init` initializes Tails CPU state and installs `Tails_tails`, but does
not call the history reset (`sonic3k.asm:26101-26156`).

This is initialization behavior, not an ordinary `Sonic_RecordPos` increment.
The source order still places P1 before P2 because `Process_Sprites` walks 110
`$4A`-byte SST records from `Object_RAM` in ascending order
(`sonic3k.asm:35965-35986`; `sonic3k.constants.asm:303-323`). No normal
delayed-follow CPU read occurs in this setup pass: CPU
routine/targets/timers remain zero. The first ordinary P1 entry is `$10A94`;
its later `Sonic_RecordPos` write precedes the subsequent P2 slot's delayed
CPU read by the same ascending SST order
(`sonic3k.asm:22119-22136,26683-26705`).

This qualifies an important implementation expectation: Task 2 must pin the
setup's player-init/history-array behavior, not assert that the setup pass
itself performs an ordinary history-index increment or delayed CPU target
selection.

## Fixed slots

The fixed SST layout is the 17 slots 93-109 documented at
`sonic3k.constants.asm:309-323`.

- Slots 93-97 and 101-109 are initially null. During the pass, slot 97
  (`Tails_tails`) activates as `$000160D2`, frame 1, timer 32.
- Slots 98 and 99 (`Dust`, `Dust_P2`) start at `$00018B3E`, routine/frame/timer
  0, and return with routine 2, frame 1, timer 31.
- Slot 100 (`Shield`) changes code `$000194CE->$0001952A` and returns at frame
  1, timer 31. This is a real fixed-slot dispatch/mutation, not an empty slot.
- Slots 101-109 remain null. Absolute dynamic slot 3 remains null before and
  after the pass.

These values answer the two evidence-dependent inventory questions: native
Tails-tails activates, and fresh dust/shield mappings advance during the
initial pass.

## Reproduction and safety

The probe is `tools/bizhawk/s3k_initial_process_sprites_probe.lua`. It uses
only `mainmemory.read_*`, `emu.getregister`, execution callbacks, logging, and
normal emulator frame advancement. It contains no memory-write, input-drive,
savestate, rewind, fixture-read, or state-hydration API. Lua syntax validation
used `luac -p`.

The probe follows `tools/bizhawk/diag_template_fast.lua`: it disables the
framerate limit, selects 6400% speed, suppresses audio, and uses invisible
emulation. Before AIZ1 it polls only `Game_Mode`, `Current_zone`, and
`Current_act` once per frame. It registers the execution callbacks only after
the ROM enters the level-mode family for AIZ1, then unregisters all three
callbacks immediately after the first ordinary player-entry snapshot. This
avoids paying BizHawk's Lua/C# callback cost during boot, title, level select,
or unrelated stages.

The attended executable was
`docs/BizHawk-2.11-linux-x64/EmuHawkMono.sh`; the ROM argument was the
discovered `s3k.gen`, and `OGGF_OUT` named the target JSONL. The first
sandboxed launch was denied X display access; the same command was then run
against the existing display with GUI permission and produced exactly three
records.
