# S3K `Game_mode` Constants

Reference artefact for the CNZ trace recorder (`tools/bizhawk/s3k_trace_recorder.lua`)
and related tooling. The recorder needs to distinguish "user soft-reset back to
title" from "CNZ gameplay running" so it can discard a spurious AIZ recording and
re-arm for the real CNZ capture. This document pins the exact byte values the
S3KL game loop writes to `Game_mode` on title, level-select, and level.

All values, labels, and approximate ROM addresses below were read directly from
`docs/skdisasm/sonic3k.asm` (S&K side / S3KL path — the only path the engine
runs). Nothing in this file is inferred from `docs/skdisasm/s3.asm`.

## Dispatcher

The game-mode jump table is called `GameModes` in the S3K disassembly (not
`GameModeArray` — that's the S1/S2 spelling). It lives at
`sonic3k.asm:430` and is dispatched from `GameLoop` at `sonic3k.asm:423`.

```asm
GameLoop:
        move.b  (Game_mode).w,d0
        andi.w  #$7C,d0                   ; mask: keep only bits 2..6
        movea.l GameModes(pc,d0.w),a0
        jsr     (a0)
        bra.s   GameLoop
```

The mask `$7C` strips bits 0-1 (so the low two bits are effectively ignored) and
bit 7 (used transiently as "level loading in progress" — see `sonic3k.asm:7505`
where `Level` does `bset #7,(Game_mode).w` and `sonic3k.asm:7882` where it clears
it again). Game-mode values are therefore **multiples of 4**, in the range
`0x00` through `0x7C`, and bit 7 is an orthogonal "loading" flag.

The very first thing `SonicAndKnucklesStartup` does before entering `GameLoop`
is `move.b #0,(Game_mode).w` (`sonic3k.asm:421`), so the boot-time mode is `0x00`
(Sega screen).

## `GameModes` jump table

Read straight from `sonic3k.asm:430-451`. Each entry is a 4-byte longword, so
the byte offset stored in `Game_mode` equals `index * 4`.

| Hex   | Label                       | Approx. line | Notes                                                            |
|-------|-----------------------------|--------------|------------------------------------------------------------------|
| `0x00`| `Sega_Screen`               | 431          | Boot-time value; routine just writes `#4` and RTSs (see below).  |
| `0x04`| `Title_Screen`              | 432          | **Title screen.** The stable "idle" value when the user is on title. |
| `0x08`| `Level`                     | 433          | Level routine, used for **level demo** (`Demo_mode_flag` set).   |
| `0x0C`| `Level`                     | 434          | **Level — normal gameplay.** (Already defined in the recorder.)  |
| `0x10`| `JumpToSegaScreen`          | 435          | Writes `#0` to `Game_mode` and RTSs (back to Sega_Screen).       |
| `0x14`| `ContinueScreen`            | 436          | Continue screen.                                                 |
| `0x18`| `JumpToSegaScreen`          | 437          | Same shim as `0x10`.                                             |
| `0x1C`| `LevelSelect_S2Options`     | 438          | Level-select variant (S2-style options).                         |
| `0x20`| `S3Credits`                 | 439          | Sonic 3 credits sequence.                                        |
| `0x24`| `LevelSelect_S2Options`     | 440          | Level-select variant.                                            |
| `0x28`| `LevelSelect_S2Options`     | 441          | **Level select** reached from the S3K title screen — see below.  |
| `0x2C`| `BlueSpheresTitle`          | 442          | Blue Spheres title.                                              |
| `0x30`| `BlueSpheresResults`        | 443          | Blue Spheres results.                                            |
| `0x34`| `SpecialStage`              | 444          | Special stage.                                                   |
| `0x38`| `Competition_Menu`          | 445          | 2P competition menu.                                             |
| `0x3C`| `Competition_PlayerSelect`  | 446          | 2P competition player select.                                    |
| `0x40`| `Competition_LevelSelect`   | 447          | 2P competition level select.                                     |
| `0x44`| `Competition_Results`       | 448          | 2P competition results.                                          |
| `0x48`| `SpecialStage_Results`      | 449          | Special stage results.                                           |
| `0x4C`| `SaveScreen`                | 450          | Save / data select.                                              |
| `0x50`| `TimeAttack_Records`        | 451          | Time attack records.                                             |

`Sega_Screen` at `sonic3k.asm:5387`:

```asm
Sega_Screen:
        move.b  #4,(Game_mode).w        ; set to title screen
        rts
```

So in practice `0x00` only ever appears for one `GameLoop` iteration before the
mode becomes `0x04`. The recorder should treat both as "at or heading to title".

**Important correction to the task-plan hypothesis:** the plan suggested
`0x08 -> Level select` and `0x1C -> Ending / credits`. Neither is correct for
S3K. Level select from the title screen is `0x28` (`sonic3k.asm:5657-5658`,
reached when `Title_screen_option == 2`), and credits is `0x20` (`S3Credits`).
Game mode `0x08` is the level-demo variant of the `Level` routine, dispatched
from the same `Level:` label as `0x0C` but with `Demo_mode_flag` set.

## "Pause + A" soft-reset path

`Pause_Game` at `sonic3k.asm:1528` is the in-level pause handler. After Start is
pressed and `Game_paused` is set, the routine enters `Pause_Loop`
(`sonic3k.asm:1550`). Per-frame while paused:

```asm
Pause_Loop:
        move.b  #$10,(V_int_routine).w
        bsr.w   Wait_VSync
        tst.b   (Slow_motion_flag).w            ; only when slow-mo cheat is armed
        beq.s   Pause_NoSlowMo
        btst    #button_A,(Ctrl_1_pressed).w
        beq.s   Pause_ChkFrameAdvance           ; branch if A isn't pressed
        move.b  #4,(Game_mode).w                ; set to title screen  <-- THIS LINE
        nop
        bra.s   Pause_ResumeMusic
```

So the soft-reset transition is:

- **Trigger:** while paused, press **A** on controller 1, with `Slow_motion_flag`
  set (the flag is armed alongside `Level_select_flag` by the title-screen cheat
  at `sonic3k.asm:46573-46574`, so in practice this means "the player has entered
  the debug / level-select cheat").
- **Effect:** `Game_mode` is written to **`0x04`** — i.e. the title-screen entry.
- **Next `GameLoop` iteration:** the level tears down (the `Level` routine's tail
  at `sonic3k.asm:7917-7922` already exits whenever `Game_mode` is neither `0x08`
  nor `0x0C`), and the dispatcher enters `Title_Screen`.

There is also an adjacent 2P time-attack escape at `sonic3k.asm:1577`
(`move.b #$40+$80,(Game_mode).w` — i.e. `0xC0` before masking, `0x40` after, the
Competition level-select) but that is gated on `Current_zone` being in the ALZ-EMZ
range and is not the path the CNZ recorder cares about.

## Recorder invariant

Captured here as the authoritative statement (Task 3 references this):

> **When recording, if `Game_mode` transitions from `0x0C` (level gameplay) to
> `0x04` (title screen) without passing through `0x28` (level select),
> discard the current recording.** This corresponds to the user soft-resetting
> out of the level with "pause + A" (on an already-armed slow-motion / level-select
> cheat), aborting the playthrough.

Sub-cases worth noting for the recorder's detection logic:

1. **Direct soft reset from `Pause_Game`:** `0x0C -> 0x04`. This is the canonical
   case above.
2. **Demo time-out:** when a demo ends, `DemoMode` at `sonic3k.asm:7925` writes
   `Game_mode` to `0x00` (`sonic3k.asm:7932, 7939`). Next frame `Sega_Screen`
   rewrites it to `0x04`. So a demo timeout manifests as
   `0x08 -> 0x00 -> 0x04`. The recorder should normally never record during
   `Game_mode == 0x08`, but this transition is a useful sanity check.
3. **Legitimate level-select return:** from the title, pressing Start + A with
   the cheat armed takes you to level select (`sonic3k.asm:6617`,
   `move.b #$28,(Game_mode).w`). So the pattern `0x0C -> 0x04 -> 0x28` is a
   "go back to title and pick a different level" flow — still a discard, but
   distinguishable from a hard abort.

## Lua block (copy-paste for Task 2, Step 2)

The following block matches the style of the existing `GAMEMODE_LEVEL = 0x0C`
constant in `tools/bizhawk/s3k_trace_recorder.lua:100`. Hex values are verified
against `sonic3k.asm:430-451` above.

```lua
local GAMEMODE_SEGA       = 0x00  -- verified from GameModes entry 0 label <Sega_Screen>       (sonic3k.asm:431)
local GAMEMODE_TITLE      = 0x04  -- verified from GameModes entry 1 label <Title_Screen>      (sonic3k.asm:432)
local GAMEMODE_LEVEL_SEL  = 0x28  -- verified from GameModes entry 10 label <LevelSelect_S2Options> (sonic3k.asm:441; reached from title via sonic3k.asm:6617)
local GAMEMODE_LEVEL      = 0x0C  -- already defined in recorder; re-stated here for doc cross-ref (sonic3k.asm:434)
```
