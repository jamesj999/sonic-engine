# Is the S1 Nemesis PLC arming row observable to the recorder?

Date: 2026-08-06

## Question

`37ada49ad` measured that the recorded row shape *"a PLC entry completes on row
`f`, and recorded row `f+1` is a lag row"* occurs 15 times across every fixture
advertising `load_queue_state_per_frame`, and that in 14 of them the ROM's row-`f`
sample already shows the next queue head armed while in exactly one (`ghz2_2`
row 107) it does not. `99746ffa9`'s `TraceExecutionModel.isIterationHeldIntoNextRow`
models the single case and is therefore wrong on the other 14; the discriminator
is sub-frame 68000 cycle position, which the v5 aux stream does not carry.

Hard rule 4 permits a recorded hardware-timing stream to delay readiness in the
**S1 PLC** pipeline. Before building that, one thing has to be true:

> Can a recorder actually observe the discriminator, or do both cases look
> identical to it too?

This document answers that by measurement, not by argument. **The answer is yes,
and unambiguously so.**

## ROM sites

Sonic 1 (World) REV01, CRC32 `AFE05EEE`, SHA-1
`69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`. Every address below was located by
byte search in the ROM image, not read off a `loc_` comment — the `sub_`/`loc_`
comments in `docs/s1disasm/sonic.asm` are REV00 addresses and run 8 bytes ahead
of REV01 through this whole region.

| Site | `sonic.asm` | REV01 PC | Bytes |
|---|---|---|---|
| `RunPLC` entry (`tst.l (v_plc_buffer).w`) | 1379-1380 | `0x0015E4` | `4A B8 F6 80` |
| `tst.w (v_plc_patternsleft).w` | 1382 | `0x0015EA` | `4A 78 F6 F8` |
| **arming path taken** (`movea.l (v_plc_buffer).w,a0`) | 1385 | `0x0015F0` | `20 78 F6 80` |
| **arming write** (`move.w d2,(v_plc_patternsleft).w`) | 1397 | `0x00160A` | `31 C2 F6 F8` |
| `ProcessPLC_9Tiles` entry | 1431 | `0x00163A` | `4A 78 F6 F8` |
| `ProcessPLC_3Tiles` entry | 1443 | `0x001656` | `4A 78 F6 F8` |
| `subq.w #1,(v_plc_patternsleft).w` | 1476 | `0x0016AA` | `53 78 F6 F8` |
| **`ProcessPLC_ShiftCue`** (head retirement) | 1494 | `0x0016D4` | `41 F8 F6 80` |

`0x0015F0`, `0x00160A` and `0x0016D4` are the same three PCs already reviewed and
byte-checked as `prepare begin`, `early PatternsLeft publish` and `pop pre` in
[`2026-07-28-s1-s2-plc-readiness-evidence.md`](2026-07-28-s1-s2-plc-readiness-evidence.md).
This measurement reuses that reviewed address set rather than introducing a new
one.

RAM: `v_plc_buffer` `0xF680` (16 × 6-byte slots), `v_plc_patternsleft` `0xF6F8`,
`v_framecount` `0xFE04` — the addresses already pinned in
`tools/bizhawk-headless/src/Recording/S1Ram.cs`.

Retail REV01 assembles with `FixBugs=0`, so `move.w d2,(v_plc_patternsleft).w`
sits at `0x00160A`, **before** `NemDec_BuildCodeTable`. Arming is therefore the
first observable effect of the arming path, not something that lands after the
Huffman table build.

## Method

A throwaway native probe (`tools/bizhawk-headless/.scratch/PlcProbe.cs` — not
part of the harness build, and untracked because `.gitignore` ignores `tools/*`,
so rebuild it from the addresses above if it is gone) replays a BK2 through the
same `GpgxHost` the recorder uses, registers one address-filtered `M68K BUS`
execute callback per site, and
writes, for every raw frame: the ordered sequence of sites that executed inside
that frame, plus the frame-end RAM sample of `v_plc_patternsleft`,
`v_plc_buffer[0]`, `v_plc_buffer[1]` and `v_framecount`.

Legend for the order column: `9`/`3` = `ProcessPLC_9Tiles`/`_3Tiles` **entered**
from the VInt handler — the hook is the routine entry, so it also fires on the
`beq ProcessPLC_Return` no-op path; `S` = `ProcessPLC_ShiftCue` (head retired);
`R` = `RunPLC` entered; `A` = arming path taken; `W` = `v_plc_patternsleft`
written. An absent `9`/`3` therefore means the VInt handler did not call
`ProcessPLC` at all, which is what a lag V-blank looks like.

The frame-end sample is taken at exactly the point the production recorder takes
its row sample, so the trace row index maps to the raw frame as
`raw = bk2_frame_offset + row`. Alignment was confirmed independently on every
case below by matching the probe's `v_framecount` and `v_plc_patternsleft`
columns against the committed fixture's `gameplay_frame_counter` and
`queue.s1_nemesis_plc.remaining_work`.

## Result: the two cases are completely disjoint

`sonic1-complete-withemeralds.bk2`. `ghz2_2` is the one case; `mz2_3` 101 is the
frontier; `mz3_2` 102 is a control from a segment whose standalone sibling
fixture is green.

### `ghz2_2` row 107 — the one (`bk2_frame_offset` 9741)

```
raw    order    patsleft  plc[0]    plc[1]    v_framecount   row
9847   3R       3         0003B9A8  0003BF06  107            106
9848   3S       0         0003BF06  00000000  108            107   <- entry completes
9849   RAW      14        0003BF4A  00000000  108            108   <- lag row, ARMS HERE
9850   3R       11        0003BF7F  00000000  109            109
```

### `mz2_3` row 101 — one of the 14 (`bk2_frame_offset` 47034)

```
raw    order    patsleft  plc[0]    plc[1]    v_framecount   row
47134  3R       3         0003A58E  0003C040  101            100
47135  3SRAW    18        0003C040  0003BCB4  102            101   <- completes AND ARMS HERE
47136  (none)   18        0003C07F  0003BCB4  102            102   <- lag row, nothing runs
47137  3R       15        0003C0B3  0003BCB4  103            103
```

### `mz3_2` rows 102 and 109 — two more of the 14 (`bk2_frame_offset` 66604)

```
raw    order    patsleft  plc[0]    plc[1]    v_framecount   row
66705  3R       3         0003A58E  0003C040  101            101
66706  3SRAW    18        0003C040  0003BCB4  102            102   <- completes AND ARMS HERE
66707  (none)   18        0003C07F  0003BCB4  102            103   <- lag row, nothing runs
...
66712  3R       3         0003C165  0003BCB4  107            108
66713  3SRAW    14        0003BCB4  00000000  108            109   <- completes AND ARMS HERE
66714  (none)   14        0003BCE9  00000000  108            110   <- lag row, nothing runs
```

### The discriminator

| | completion row `f` | lag row `f+1` |
|---|---|---|
| the 14 (`mz2_3` 101, `mz3_2` 102/109, …) | `3SRAW` — service, retire, **arm** | *(empty)* — nothing executes |
| the 1 (`ghz2_2` 107) | `3S` — service, retire, **no `R` at all** | `RAW` — **arm** |

Both `f` rows retire the head. Both `f+1` rows are lag rows by the recorded
counters (`v_framecount` held, V-blank counter advanced). The committed row shape
is identical. The **execution** is not even close: in the 14 the whole of
`RunPLC` runs inside row `f`; in the one, `RunPLC` is not entered at all on row
`f` and the entire arm — entry, path, write — happens inside row `f+1`.

This is not a sub-instruction race that a hook might straddle. The frame boundary
falls between `ProcessPLC_ShiftCue` returning and `RunPLC` being called at all.

### Every one of the 15 cases, classified by hook

Two probe passes: `sonic1-complete-withemeralds.bk2` to raw frame 202,300 and
`s1-complete-run.bk2` to raw frame 181,200. Row → raw mapping is the fixture's
own `bk2_frame_offset`.

| fixture | row `f` | raw `f` | order on `f` | order on `f+1` | arms on |
|---|---:|---:|---|---|---|
| `ghz2_2` | 107 | 9848 | `3S` | `RAW` | **`f+1`** |
| `mz2_3` | 101 | 47135 | `3SRAW` | *(empty)* | `f` |
| `mz3_2` | 102 | 66706 | `3SRAW` | *(empty)* | `f` |
| `mz3_2` | 109 | 66713 | `3SRAW` | *(empty)* | `f` |
| `lz3` | 10185 | 158595 | `3SRAW` | *(empty)* | `f` |
| `lz4` | 11 | 202143 | `3SRAW` | *(empty)* | `f` |
| `lz4` | 16 | 202148 | `3SRAW` | *(empty)* | `f` |
| `lz4` | 114 | 202246 | `3SRAW` | *(empty)* | `f` |
| `lz4` | 122 | 202254 | `3SRAW` | *(empty)* | `f` |
| `mz3_completerun` | 102 | 43545 | `3SRAW` | *(empty)* | `f` |
| `fz_completerun` | 11 | 181015 | `3SRAW` | *(empty)* | `f` |
| `fz_completerun` | 16 | 181020 | `3SRAW` | *(empty)* | `f` |
| `fz_completerun` | 29 | 181033 | `3SRAW` | *(empty)* | `f` |
| `fz_completerun` | 118 | 181122 | `3SRAW` | *(empty)* | `f` |
| `fz_completerun` | 125 | 181129 | `3SRAW` | *(empty)* | `f` |

14 identical `3SRAW` / *(empty)* pairs and one `3S` / `RAW` pair — exactly the
14-to-1 split `37ada49ad` derived from the recorded queue columns, now
reproduced from ROM execution rather than from the aux stream, and with the
mechanism visible rather than inferred.

## Census over both probed spans

202,301 raw frames of `sonic1-complete-withemeralds.bk2` and 181,201 of
`s1-complete-run.bk2`:

| order string | emeralds | complete-run | meaning |
|---|---:|---:|---|
| `3R` | 168,309 | 174,002 | level VInt service, `RunPLC` declines (head still busy) |
| *(empty)* | 23,412 | 1,348 | no PLC activity |
| `9R` | 7,979 | 3,055 | 9-pattern handler service, `RunPLC` declines |
| `3` | 875 | 1,093 | service only; `RunPLC` not reached this frame |
| `R` | 808 | 1,025 | `RunPLC` entered, declines; no service this frame |
| `9SRAW` | 407 | 319 | retire **and** arm in one frame (9-pattern handler) |
| `R3` | 140 | — | `RunPLC` declines before the frame's VInt service |
| `3SRAW` | 129 | 119 | retire **and** arm in one frame (3-pattern handler) |
| `3SR` | 62 | 59 | retire; `RunPLC` declines (queue now empty) |
| `3RAW` | 56 | — | arm with no retirement this frame |
| `9RAW` | 49 | — | arm with no retirement this frame |
| `9SR` | 42 | — | retire; `RunPLC` declines (queue now empty) |
| `9` | 30 | — | service only |
| **`3S`** | **2** | **0** | retire with `RunPLC` never reached |
| **`RAW`** | **1** | **0** | arm on a row with no service |

642 armings and 642 retirements on the emerald movie; 528 and 528 on the
complete run. Invariants that hold over both spans and that a recorder may rely
on:

- never more than one arming, one retirement or one `RunPLC` entry per raw frame;
- the arming never precedes the frame's VInt service — `A` follows `3`/`9` in
  every frame containing both, **0 exceptions in 383,502 frames**. (Bare `R`
  *can* precede it: `R3` occurs 140 times. It is the arming, not the `RunPLC`
  call, whose boundary is pinned.) The edge boundary is therefore strictly later
  than `vint_service`;
- `RAW` occurs **once in 383,502 frames**, at `ghz2_2` 9849;
- of the two `3S` frames, only 9848 leaves a pending entry behind
  (`v_plc_buffer[0]` = `0x0003BF06`). The other, 202260, retires the last entry
  to an empty queue, so `RunPLC` has nothing to arm and declines on the next
  frame. `ghz2_2` 9848 is the sole "retirement whose arming fell off the end of
  the frame" in either movie.

## Frame-end RAM sampling alone is *not* sufficient

The naive recorder rule "`v_plc_patternsleft` went `0` → nonzero, therefore an
arm happened" disagrees with the hook on **536** emerald frames and **438**
complete-run frames. The reason is the `9SRAW`/`3SRAW` shape: retirement and
arming happen inside one frame, so `v_plc_patternsleft` is never sampled at zero
and the arm is invisible to a naive sampler.

A recorder can still recover it without a hook by mirroring the 16-entry FIFO and
detecting the `ProcessPLC_ShiftCue` six-byte shift structurally (the S3K
`HardwareTimingEventEngine` mirrors its FIFOs the same way), but that inference
is strictly weaker than the exact `0x0015F0` observation, and the arming write at
`0x00160A` rewrites slot 0 so the head's identity has to be retained across an
advancing pointer. The reviewed, address-filtered execute callback is the
cheaper and more defensible option, and it is the same shape as the one existing
sanctioned exception (`HardwareTimingEventEngine.ModuleChildSubmissionPc`
`0x001B46`, S3K).

## Verdict

**PHASE A PASSES.** The recorder can observe the discriminator exactly. The
plan to give the S1 Nemesis PLC pipeline a recorded hardware-timing readiness
stream is not blocked by observability.

Two further findings that shape the build:

1. **No new boundary semantics are needed.** The arm is the ROM's loop tail —
   `RunPLC` (`sonic.asm`:3032) sits after `ExecuteObjects`, `DeformLayers`,
   `BuildSprites`, `ObjPosLoad` and `PaletteCycle`, and before the loop-top
   re-arm. That is `pre_main_loop`. The `ghz2_2` case needs its edge on a
   held-counter lag row, which is exactly the suppressed-row `pre_main_loop`
   admission the cross-game contract already defines and
   `HardwareTimingReplayPort.applySuppressedRowCompletion` already implements.
2. **Only one fixture actually needs the stream.** `LiveTraceComparator` is the
   sole caller of `99746ffa9`'s deferral, so the standalone `*TraceReplay`
   fixtures containing the other 14 cases are unaffected by reverting it, and
   `ghz2_2` — the single case the deferral is right about — occurs only in
   `s1-sonic-complete-withemeralds`.

The remaining work, its hook points and the two policy gates it must clear are in
[`../../plans/trace/2026-08-06-s1-nemesis-plc-timing-stream-plan.md`](../../plans/trace/2026-08-06-s1-nemesis-plc-timing-stream-plan.md).
