# Trace Lag Model Matrix

## Scope

This document defines the ROM-side contract the trace replay harness should follow when a frame reaches VBlank but the level main loop does not fully complete.

The key distinction is:

- `VBlank-only lag frame`: interrupt-side work ran, but the gameplay loop did not advance.
- `Full level frame`: the gameplay loop advanced after the wait-for-VBlank barrier.

The replay system should derive phase from recorded ROM counters, not from unchanged player physics.

There are currently no checked-in Sonic 2 or Sonic 3K trace fixtures under `src/test/resources/traces/` that need migration. Existing checked-in fixtures are Sonic 1 and synthetic parser fixtures.

## Sonic 1

### Execution Matrix

| Subsystem | Full level frame | VBlank-only lag frame | Evidence |
|---|---|---|---|
| `v_framecount` | yes | no | `docs/s1disasm/sonic.asm:3198-3203` |
| `v_vbla_count` / `v_vbla_byte` | yes | yes | `docs/s1disasm/sonic.asm:615-643`, `docs/s1disasm/Variables.asm:332-334` |
| zone pre-physics (`LZWaterFeatures`) | yes | no | `docs/s1disasm/sonic.asm:3203-3205` |
| object execution (`ExecuteObjects`) | yes | no | `docs/s1disasm/sonic.asm:3205` |
| deform / scroll preparation | yes | no | `docs/s1disasm/sonic.asm:3215-3218` |
| sprite build / object placement | yes | no | `docs/s1disasm/sonic.asm:3219-3220` |
| palette cycle / PLC / oscillation / ring animation | yes | no | `docs/s1disasm/sonic.asm:3221-3225` |
| LZ interrupt palette / H-int setup | level-zone specific | yes | `docs/s1disasm/sonic.asm:665-700` |
| music update | yes | yes | `docs/s1disasm/sonic.asm:637-643` |

### Counter Address Table

| Name | Label | Address | Evidence |
|---|---|---:|---|
| gameplay frame counter | `v_framecount` | `0xFE04` | `docs/s1disasm/Variables.asm:325-335` with `v_zone = 0xFE10` and `v_vbla_count = 0xFE0C` anchoring the preceding layout |
| VBlank counter low word | `v_vbla_word` | `0xFE0E` | `docs/s1disasm/Variables.asm:332-333` |
| VBlank counter low byte | `v_vbla_byte` | `0xFE0F` | `docs/s1disasm/Variables.asm:333-334` |
| lag counter | n/a | n/a | Sonic 1 does not expose an explicit lag-frame counter in the same way as S3/S3K |

## Sonic 2

### Execution Matrix

| Subsystem | Full level frame | VBlank-only lag frame | Evidence |
|---|---|---|---|
| `Level_frame_counter` | yes | no | `docs/s2disasm/s2.asm:5084-5089`, `docs/s2disasm/s2.constants.asm:1665` |
| `Vint_runcount` | yes | yes | `docs/s2disasm/s2.constants.asm:1672` |
| water effects / object run / deform / rings / ani art / oscillation | yes | no | `docs/s2disasm/s2.asm:5089-5108` |
| lag V-int palette / H-int setup | no | yes | `docs/s2disasm/s2.asm:529-583` |
| lag V-int sprite-table DMA | two-player / build-dependent | yes | `docs/s2disasm/s2.asm:607-639` |
| sound-driver input | yes | yes | `docs/s2disasm/s2.asm:539-543`, `docs/s2disasm/s2.asm:639-642` |

### Counter Address Table

`CrossResetRAM` starts at `$FFFFFE00` in Sonic 2, as shown by `SS_2p_Flag` occupying `$FFFFFE00-$FFFFFE01` in `docs/s2disasm/s2.constants.asm:1663`.

| Name | Label | Address | Evidence |
|---|---|---:|---|
| gameplay frame counter | `Level_frame_counter` | `0xFE04` | `docs/s2disasm/s2.constants.asm:1663-1665` |
| VBlank counter low word | `Vint_runcount+2` | `0xFE0E` | `docs/s2disasm/s2.constants.asm:1663-1672` (byte-wise layout: `SS_2p_Flag` word at FE00, `Level_Inactive_flag` word at FE02, `Level_frame_counter` word at FE04, `Debug_object` byte FE06, pad byte FE07, `Debug_placement_mode` byte FE08, pad byte FE09, `Debug_Accel_Timer` byte FE0A, `Debug_Speed` byte FE0B, `Vint_runcount` longword FE0C-FE0F → low word at FE0E) |
| lag counter | n/a | n/a | Sonic 2 does not expose an explicit lag-frame counter like S3/S3K |

## Sonic 3K

### Execution Matrix

| Subsystem | Full level frame | VBlank-only lag frame | Evidence |
|---|---|---|---|
| `Level_frame_counter` | yes | no | `docs/skdisasm/sonic3k.asm:7884-7890`, `docs/skdisasm/sonic3k.constants.asm:782` |
| `V_int_run_count` | yes | yes | `docs/skdisasm/sonic3k.constants.asm:790`, `docs/skdisasm/sonic3k.asm:566-581` |
| `Lag_frame_count` | reset during normal frame | increments on lag V-int 0 | `docs/skdisasm/sonic3k.asm:566-581`, `docs/skdisasm/sonic3k.asm:784-786` |
| special events / sprite load / process sprites / deform / screen events / rings / animated tiles / oscillation | yes | no | `docs/skdisasm/sonic3k.asm:7889-7911` |
| lag V-int palette / H-int setup | no | yes | `docs/skdisasm/sonic3k.asm:584-648` |
| lag V-int sprite-table DMA | competition mode only | yes | `docs/skdisasm/sonic3k.asm:623-647` |
| HUD / DMA queue / demo timer housekeeping | yes | V-int-side when normal frame path reaches `Do_Updates` | `docs/skdisasm/sonic3k.asm:764-793` |

### Counter Address Table

The Sonic 3K constants file phases RAM at `$FFFF0000` in `docs/skdisasm/sonic3k.constants.asm:283-285`. The named temporary labels in the same file confirm the low-word addresses in the `$F600`, `$FAA0`, and `$FE00` regions, allowing the exact addresses below to be computed from the layout.

| Name | Label | Address | Evidence |
|---|---|---:|---|
| gameplay frame counter | `Level_frame_counter` | `0xFE08` | `docs/skdisasm/sonic3k.constants.asm:776-782` with `CrossResetRAM` at `0xFE04` from the preceding layout ending at `System_stack = 0xFE04` |
| VBlank counter low word | `V_int_run_count+2` | `0xFE12` | `docs/skdisasm/sonic3k.constants.asm:790` and the same `CrossResetRAM` layout |
| lag counter | `Lag_frame_count` | `0xF628` | `docs/skdisasm/sonic3k.constants.asm:536`, `docs/skdisasm/sonic3k.constants.asm:550-555` |

### S3K Address Derivation Notes

- `Game_mode` sits at `0xF600`:
  - `_tempF608` begins at `0xF608` in `docs/skdisasm/sonic3k.constants.asm:536`, so the preceding controls and mode bytes anchor this block.
- `Lag_frame_count` follows:
  - `H_int_counter_command` at `0xF624`
  - `Palette_fade_info` at `0xF626`
  - `Lag_frame_count` at `0xF628`
- `CrossResetRAM` begins at `0xFE04`:
  - `Stack_contents` is `0x100` bytes starting at `0xFD04`
  - therefore `System_stack = 0xFE04`
  - `CrossResetRAM` begins immediately after in `docs/skdisasm/sonic3k.constants.asm:776-780`
- From there:
  - unused word at `0xFE04`
  - `Restart_level_flag` at `0xFE06`
  - `Level_frame_counter` at `0xFE08`
  - `V_int_run_count` at `0xFE10`

## Replay Rule

Use the following rule for phase derivation:

1. If there is no previous frame, treat the current frame as `FULL_LEVEL_FRAME`.
2. If `gameplayFrameCounter` changed, the frame is `FULL_LEVEL_FRAME`.
3. If `gameplayFrameCounter` did not change but `vblankCounter` did, the frame is `VBLANK_ONLY`.
4. `lagCounter` is diagnostic only. Do not use it as the primary selector.

## Recorder Implications

- Sonic 1 recorder should emit:
  - `gameplay_frame_counter`
  - `vblank_counter`
  - `lag_counter = 0`
- Sonic 2 recorder should emit:
  - `gameplay_frame_counter`
  - `vblank_counter`
  - `lag_counter = 0`
- Sonic 3K recorder should emit:
  - `gameplay_frame_counter`
  - `vblank_counter`
  - `lag_counter`
