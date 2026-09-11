# Sonic 3K Trace Recorder RAM Addresses

This note resolves the Sonic 3 & Knuckles RAM labels the v3 trace recorder needs beyond the frozen counter matrix. All addresses below are 68K absolute RAM addresses from `docs/skdisasm/`; if the recorder reads BizHawk Genesis `mainmemory`, subtract `$FF0000` before indexing.

## Core RAM / OST Values

| Recorder field | S3K label(s) | Value | Evidence | Notes |
| --- | --- | --- | --- | --- |
| Game mode byte | `Game_mode` | abs `$FFFFF600` / mainmemory `$F600` | `docs/skdisasm/sonic3k.constants.asm:525-536`, `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md:90-104` | `Game_mode` is the first byte before the controller block that ends at `_tempF608`, so the mainmemory offset is `$F600`. |
| Player 1 SST base | `Player_1` | abs `$FFFFB000` / mainmemory `$B000` | `docs/skdisasm/sonic3k.constants.asm:283-304`, `tools/bizhawk/trace_output/s3k_player_base_compare.txt` | The earlier `$B400` note was a bad sum. `Chunk_table` `$8000` + layout/header `$1000` + `Block_table` `$1800` + `HScroll_table` `$0200` + `Nem_code_table` `$0200` + `Sprite_table_input` `$0400` lands on `$B000`, and the live BizHawk scan shows Sonic/Tails moving in slots `$B000/$B04A` while `$B400` stays dead. |
| Player 2 SST base | `Player_2` | abs `$FFFFB04A` / mainmemory `$B04A` | `docs/skdisasm/sonic3k.constants.asm:303-305`, `tools/bizhawk/trace_output/s3k_player_base_compare.txt` | `object_size` is `$4A`, so `Player_2 = Player_1 + $4A`. |
| SST slot size | `object_size` | `$4A` bytes | `docs/skdisasm/sonic3k.constants.asm:303-323` | The object pool comment fixes the per-slot size. |
| Player 1 control-lock timer | `Player_1 + move_lock` | abs `$FFFFB032` / mainmemory `$B032` | `docs/skdisasm/sonic3k.constants.asm:61`, `docs/skdisasm/sonic3k.constants.asm:303-304`, `tools/bizhawk/trace_output/s3k_player_base_compare.txt` | `move_lock` is an OST field offset, not a standalone RAM label. Recorder reads `Player_1` base plus `$32`. |
| Player 2 control-lock timer | `Player_2 + move_lock` | abs `$FFFFB07C` / mainmemory `$B07C` | `docs/skdisasm/sonic3k.constants.asm:61`, `docs/skdisasm/sonic3k.constants.asm:304-305`, `tools/bizhawk/trace_output/s3k_player_base_compare.txt` | Useful for Sonic/Tails movies when verifying sidekick lock state during cutscenes. |
| Level-start flag | `Level_started_flag` | abs `$FFFFF711` / mainmemory `$F711` | `docs/skdisasm/sonic3k.constants.asm:627-629` | `Rings_manager_routine` is the preceding byte at `$F710`; `_unkF712` immediately follows, so `Level_started_flag` is `$F711`. |
| P1 input-lock byte | `Ctrl_1_locked` | abs `$FFFFF7CA` / mainmemory `$F7CA` | `docs/skdisasm/sonic3k.constants.asm:683-689` | This is the global cutscene/control lock byte, distinct from the per-player `move_lock` timer in the SST. |
| Apparent zone/act word | `Apparent_zone_and_act` | abs `$FFFFEE4E` / mainmemory `$EE4E` | `docs/skdisasm/sonic3k.constants.asm:386-388`, `docs/skdisasm/sonic3k.constants.asm:421`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt` | The `_unkEE8E` anchor is reliable: with `Camera_X_pos_BG_copy` at `$EE8C` and `_unkEE8E` itself at `$EE8E`, counting back places `Apparent_zone_and_act` at `$EE4E`, not `$EE50`. |
| Apparent zone byte | `Apparent_zone` | abs `$FFFFEE4E` / mainmemory `$EE4E` | `docs/skdisasm/sonic3k.constants.asm:386-388`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt` | Always matches the actual zone in AIZ/HCZ. |
| Apparent act byte | `Apparent_act` | abs `$FFFFEE4F` / mainmemory `$EE4F` | `docs/skdisasm/sonic3k.constants.asm:386-388`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt` | This is the ROM-backed act presentation byte the spec requires. The original `$EE51` derivation was off by two bytes. |
| AIZ fire-transition signal | `Events_fg_5` | abs `$FFFFEEC6` / mainmemory `$EEC6` | `docs/skdisasm/sonic3k.constants.asm:438-448`, `docs/skdisasm/sonic3k.constants.asm:421`, `docs/skdisasm/sonic3k.asm:104613-104639` | `_unkEE8E` again provides the anchor. `AIZ1BGE_Normal` branches into `AIZ1_AIZ2_Transition` when this word becomes non-zero. |
| Background-event scratch block | `Events_bg` | abs `$FFFFEED2` / mainmemory `$EED2` | `docs/skdisasm/sonic3k.constants.asm:449-459`, `docs/skdisasm/sonic3k.constants.asm:421` | Included because AIZ fire/handoff code writes several phase values into `Events_bg+$00/$02/$04`. |
| Current zone/act word | `Current_zone_and_act` | abs `$FFFFFE10` / mainmemory `$FE10` | `docs/skdisasm/sonic3k.constants.asm:790-793`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt` | The original derivation assumed `CrossResetRAM` started at `$FE04`. The live dump shows `Level_frame_counter` incrementing at `$FE04`, which shifts `V_int_run_count` to `$FE0C` and `Current_zone_and_act` to `$FE10`. |
| Current zone byte | `Current_zone` | abs `$FFFFFE10` / mainmemory `$FE10` | `docs/skdisasm/sonic3k.constants.asm:791-793`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt` | Recorder should treat this as the authoritative zone byte. |
| Current act byte | `Current_act` | abs `$FFFFFE11` / mainmemory `$FE11` | `docs/skdisasm/sonic3k.constants.asm:791-793`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt` | Recorder should treat this as the authoritative act byte. |
| Restart flag | `Restart_level_flag` | abs `$FFFFFE02` / mainmemory `$FE02` | `docs/skdisasm/sonic3k.constants.asm:780-782`, `tools/bizhawk/trace_output/s3k_handoff_diag.txt`, `docs/skdisasm/sonic3k.asm:180637-180643` | `StartNewLevel` sets `Current_zone_and_act`, `Apparent_zone_and_act`, and `Restart_level_flag` together. The prior `$FE06` note was four bytes too high. |
| End-of-level active flag | `_unkFAA8` | abs `$FFFFFAA8` / mainmemory `$FAA8` | `docs/skdisasm/sonic3k.constants.asm:723-726`, `docs/skdisasm/sonic3k.asm:138248-138277`, `docs/skdisasm/sonic3k.asm:62702-62712` | This flag is reused by generic end-of-level/title-card flows. It is useful as a secondary diagnostic, but not as a unique `hcz_handoff_begin` checkpoint source because it also clears during non-HCZ transitions. |
| End-of-level completion flag | `End_of_level_flag` | abs `$FFFFFAAA` / mainmemory `$FAAA` | `docs/skdisasm/sonic3k.constants.asm:724-727`, `docs/skdisasm/sonic3k.asm:62702-62705` | Set when the act-2 results object finishes; useful as a secondary diagnostic field while validating the HCZ load boundary. |

## Required Checkpoints

| Checkpoint | Recorder-side signal | BizHawk offset(s) | Replay-side engine mirror | Evidence |
| --- | --- | --- | --- | --- |
| `intro_begin` | emit on recorded frame `0` unconditionally | n/a | replay bootstrap frame `0` | spec anchor |
| `gameplay_start` | `Level_started_flag != 0 && Game_mode == $0C && Player_1 + move_lock == 0 && Ctrl_1_locked == 0` | `$F711`, `$F600`, `$B032`, `$F7CA` | `GameServices.level().getCurrentZone() == 0 && fixture.sprite().getMoveLockTimer() == 0 && !fixture.sprite().isControlLocked()` | `2026-04-21-s3k-trace-addresses.md`, `AbstractPlayableSprite#getMoveLockTimer`, `isControlLocked` |
| `aiz1_fire_transition_begin` | rising edge of `Events_fg_5 != 0` while `Current_zone_and_act == $0000` | `$EEC6`, `$FE10` | `captureS3kProbe(replayFrame, fixture.sprite()).fireTransitionActive()` backed by `GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager && manager.isFireTransitionActive()` | `sonic3k.asm:104613-104639`, `Sonic3kLevelEventManager#isFireTransitionActive`, `Sonic3kAIZEvents#isFireTransitionActive` |
| `aiz2_reload_resume` | `Current_zone_and_act == $0001 && Apparent_act == $00` | `$FE10`, `$EE4F` | `GameServices.level().getCurrentZone() == 0 && GameServices.level().getCurrentAct() == 1 && GameServices.level().getApparentAct() == 0` | `sonic3k.asm:104722-104770`, `LevelManager#getApparentAct` |
| `aiz2_main_gameplay` | `Current_zone_and_act == $0001 && Player_1 + move_lock == 0 && Ctrl_1_locked == 0` | `$FE10`, `$B032`, `$F7CA` | `GameServices.level().getCurrentZone() == 0 && GameServices.level().getCurrentAct() == 1 && fixture.sprite().getMoveLockTimer() == 0 && !fixture.sprite().isControlLocked()` | disasm + `AbstractPlayableSprite` mirrors |
| `hcz_handoff_complete` | `Current_zone_and_act == $0100 && Player_1 + move_lock == 0 && Ctrl_1_locked == 0` | `$FE10`, `$B032`, `$F7CA` | `GameServices.level().getCurrentZone() == 1 && GameServices.level().getCurrentAct() == 0 && fixture.sprite().getMoveLockTimer() == 0 && !fixture.sprite().isControlLocked()` | `sonic3k.asm:180637-180643`, engine zone/act mirrors |

## Emission And Detection Order

Within a single frame, recorder emission order is:

1. `zone_act_state`
2. `checkpoint`
3. existing object / routine / mode diagnostics in fixed detector order

Optional checkpoints (`aiz2_signpost_begin`, `aiz2_results_begin`) are diagnostics-only.
They may appear in recorder output and replay diagnostics, but they never drive elastic-window entry, exit, or pass/fail decisions.

## AIZ Fire / HCZ Handoff Notes

- `Apparent_act` is ROM RAM, not an engine-only mirror. The disassembly explicitly documents the AIZ fire-transition case: actual act has already become act 2 while `Apparent_act` still reports act 1.
- `move_lock` is also not a standalone RAM label. It is an SST field offset (`$32`) that must be read from the player object base. For this movie, recorder diagnostics should at minimum sample `Player_1 + move_lock`; sampling `Player_2 + move_lock` is useful because the fixture is a Sonic/Tails run.
- The AIZ2 post-boss route has one recorder-safe required seam:
  - actual level jump: `StartNewLevel($0100)` writes HCZ1 into `Current_zone_and_act` / `Apparent_zone_and_act` and raises `Restart_level_flag`.
- `_unkFAA8` still helps for diagnostics, but it is not unique to the HCZ handoff. The generic title-card/end-sign code clears it in multiple flows, including non-HCZ transitions (`sonic3k.asm:62703-62712`).

## Emulator Pin

The recorder should treat this fixture as pinned to the emulator/core combination stored in the movie itself:

| Field | Value | Evidence | Notes |
| --- | --- | --- | --- |
| BizHawk version | `2.11` | `docs/BizHawk-2.11-win-x64/Movies/s3-aiz1&2-sonictails.bk2` -> `BizVersion.txt` = `Version 2.11` | Matches the local BizHawk install directory name. |
| Genesis core | `Genplus-gx` | BK2 `Header.txt` -> `Core Genplus-gx`; `SyncSettings.json` uses `BizHawk.Emulation.Cores.Consoles.Sega.gpgx.GPGX+GPGXSyncSettings` | Use this exact core for reproducible rerecording. |
| Platform | `GEN` | BK2 `Header.txt` | Confirms Genesis movie format. |
| ROM checksum string | `C5B1C655C19F462ADE0AC4E17A844D10` | BK2 `Header.txt` field named `SHA1` | Carry this exact non-empty header value into fixture metadata as `rom_checksum`. |

The BK2 header therefore already contains the pin the fixture needs:

```text
Core Genplus-gx
Platform GEN
emuVersion Version 2.11
GameName Sonic and Knuckles & Sonic 3 (W) [!]
SHA1 C5B1C655C19F462ADE0AC4E17A844D10
```
