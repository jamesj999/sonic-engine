# S1 `VBlank_Lag` V-int admission

## Status

Approved for implementation, scoped to presentation bridges. Supersedes this
note's first two revisions, which proposed a new recorded stream and asserted
that lag rows run their main loop. **Both were wrong and are corrected below**;
the ROM basis and the disproof of readiness-shaped fixes are unchanged.

The corrected change is small: **consume the already-recorded per-frame lag flag
on presentation-bridge rows, selecting `PlcLifecyclePhase.LAG` for the row's
V-int.** No new stream, no new event kind, no re-record, no contract extension,
and no guard change.

## Scope — read this before estimating the change's value

**This closes the bridge frontier. It does not fix the S1 level reds.**

An earlier revision claimed 33 reachable rows corpus-wide. That figure was never
achievable. Measured, per red segment, whether any lagged row even precedes the
first error:

| segment | first error | lagged rows before it | first lag overall |
|---|---|---|---|
| `mz2_3` (seg 12) | 101 | **none** | 102 |
| `mz3_2` (seg 15) | 102 | 88 | 88 |
| `syz2` bridge | 70 | 60-67 (already suppressed) | 60 |

- **`mz2_3` is unreachable at any fidelity.** Its first lag row is at 102,
  *after* its error at 101. No lag model can explain a divergence that precedes
  every lag frame in the segment.
- **`mz3_2` has the wrong polarity for a service fix.** The engine is already
  behind, and suppressing a service pushes it further behind. (See the caveat
  in "Interaction with the preparation defect" — this argument is about
  *service* and may not survive a *preparation* fix.)
- **`syz2`/`syz3` rows 60-67 are already suppressed** by
  `presentationRowIsCarriedLagClosure`.

So the only new contribution anywhere in the corpus is **row 70 of `syz2` and
`syz3`** — exactly the two rows the tear census named as the sole bridge rows
where the harness fails to suppress a ROM stall.

The correct claim is **"unblocks 16 invisible segments"**, not "fixes the S1
reds". Segments 18-33 have never been replayed because the chain terminates at
this bridge. Expect them to be dirty when they first become visible: new errors
there are *newly visible*, not regressions.

**Success criterion.** `syz2` going green proves nothing on its own. The number
that matters is where the chain stops afterwards.

## ROM basis

`VBlank:` tests the pending routine selector first
(`docs/s1disasm/sonic.asm:656-657`):

```
		tst.b	(v_vblank_routine).w			; was a VBlank routine set?
		beq.s	VBlank_Lag				; if not, this is a lag frame, branch
```

The main loop stores the selector immediately before waiting, and `VBlank`
resets it to `id_VBlank_Lag` once a real handler dispatches (`:675`). So
`v_vblank_routine == 0` at V-int entry means the 68K main loop had not reached
its next wait when this V-int fired.

`VBlank_Lag` (`:712-746`) performs no `ProcessPLC` call. Outside Labyrinth Zone
it branches to `VBlank_Music`; inside LZ it does a CRAM/H-blank palette transfer
first. Both fall through to `VBlank_Exit` (`:684-687`), which still runs
`addq.l #1,(v_vblank_count).w`. **A lag V-int advances the counter while
decompressing zero tiles.**

Corpus evidence (`docs/status/trace-frontier-log.md`): across all 34 segments,
37 stalls, all 37 on lag frames; 3,331 serviced rows, none on a lag frame.

## Why readiness-shaped fixes cannot work

Three independent reasons; any one suffices.

1. **The comparator asserts `remaining_work` per row.** A row that services 9
   tiles where the ROM serviced 0 is wrong *on that row*, whatever its readiness
   state. Readiness-withholding is invisible to the compared field.
2. **The decode counter is not readiness-owned.** `ProcessPLC_9Tiles` (`:1431`)
   arms the per-frame budget; `ProcessPLC`'s inner loop (`:1455-1490`) decrements
   `v_plc_patternsleft` once per tile and nothing else writes it. Readiness
   governs whether a **finished** entry retires; it cannot pause an
   **unfinished** one. `VBlank_Lag` needs rows where `remaining_work` does not
   change mid-entry, and no readiness edge produces one.
3. **The queue chains off internal completion**, so holding readiness leaves the
   downstream schedule permanently one frame early per stall.

A mid-job stall also has **no job identity**. Slicing each quantum into a
pseudo-job to manufacture one invents a granularity the ROM lacks. **An
(a)-shaped fix that goes green has fitted something.**

## CORRECTED: lag rows do not run their main loop

An earlier revision asserted, from review rather than measurement, that these
rows run gameplay with the counter advancing on both sides, and proposed a guard
enforcing it. **Measured, that is false**, and such a guard would have asserted
the opposite of the truth — a failure that presents as a physics bug.

On `lz4`'s listed rows (1, 3, 5, 7, 12, 17, 21, 23, 30, 39, 91, 97, 115, 117,
123): `vblank_counter` +1, **`gameplay_frame_counter` +0**, `player_x`/`player_y`
byte-identical to the previous row. A V-int elapsed and no gameplay pass
happened. The special-stage precedent's `runGameplay = !lagged` has it right.

## CORRECTED: the recorded input already exists

`aux_state.jsonl` already carries per-frame
`{"event":"lag_state","lagged":...,"lagcount":N}`, and `metadata.json` already
declares `lag_state_per_frame` in `aux_schema_extras`. Correlating `lagged`
against frozen `gameplay_frame_counter`:

| segment | transitions | lagged & frozen | clean | disagreements |
|---|---|---|---|---|
| `lz4` | 6,719 | 870 | 5,849 | **0** |
| `lz2` | 8,640 | 162 | 8,478 | **0** |
| `mz3_2` | 11,331 | 33 | 11,298 | **0** |
| `ghz2_2` | 3,605 | 11 | 3,594 | **0** |

30,295 ordinary-level transitions, zero disagreements either way. `syz2` row 70
and `syz3` row 70 are both `lagged=true`.

So no re-record and no new stream. The mechanism is already implemented for one
path: `TraceRunSpecialStageRows.syntheticLagPhase`
(`src/main/java/com/openggf/trace/replay/runs/TraceRunSpecialStageRows.java:326-331`)
maps `trace.getFrame(row).lag()` to `PlcLifecyclePhase.LAG` and sets
`runGameplay = !lagged`. That is contract 1, main-loop admission, working for
special stages and never applied to level and presentation rows. **A coverage
gap, not a new authority.**

### **The trap: `lagged` is the authority, never counter shape**

Frozen `gameplay_frame_counter` coincides with `lagged` only in ordinary level
segments. **Inside presentation bridges it collapses** — `syz2` has **802 of 811
rows frozen while only 9 are lagged**, because gameplay is structurally frozen
there anyway. Anyone deriving lag from counter shape gets a green level suite
and a broken bridge, which is the worst possible signal.

Related trap: the `lag_counter` column in `physics.csv` reads `0000` on every
row sampled. The live value is aux `lagcount`. Do not build on the CSV column.

## Why this does not need a guard change

`TestS1S2PlcComparisonOnlyGuard` matches only the literal pattern
`com\.openggf\.game\.sonic[12]\.resources\.Sonic[12]PlcService`.
`TraceRunSpecialStageRows` sits **inside** the guarded tree
(`com.openggf.trace.replay.runs`) and passes today because it names
`com.openggf.game.resources.PlcLifecyclePhase` — a semantic ROM-loop enum — and
never a PLC service.

**This is a seam, not a loophole, and the distinction is the point:** the guard
forbids trace code from **reaching a PLC service**; it does not forbid trace
code from **declaring which ROM loop a row represents**. Declaring the loop is
comparison-side structural classification. Reaching the service would let a
trace schedule native readiness, which is what the guard exists to prevent.

The level path already has the identical mechanism:
`LevelFrameStep.serviceVBlankOnly` (`src/main/java/com/openggf/LevelFrameStep.java:117-124`)
accepts only `LAG`, `NORMAL_PAUSE`, `SPECIAL_STAGE_PAUSE` and is already invoked
with `LAG` from `GameLoop:1363`. Nothing new touches a PLC service.

Guard baselined **7/0 green**. The registry stays closed: this is not a
`HardwareWorkKind` and must never become one.

## What the port may and may not do

**May**: select `PlcLifecyclePhase.LAG` for a bridge row whose recorded
`lagged` is true; advance the V-int counters `VBlank_Exit` advances; be restored
by rewind with the row cursor.

**May not**: carry or adjust `v_plc_patternsleft`, `v_plc_buffer_dest`, queue
contents, fingerprints or decode progress; create, retire, reorder or prepare a
queue entry; **select a handler** — it may only ever say "no handler ran";
reach physics, aux or any gameplay owner; become a `HardwareWorkKind`; or be
derived from counter shape rather than the recorded flag.

## Interaction with the preparation defect

`ghz2_2` is root-caused separately: `RunPLC` (`sonic.asm:1378-1420`) is
**main-loop code, not VBlank code**, and stores `v_plc_patternsleft` at `:1397`
under `FixBugs=0`. A lag frame does not mean the 68000 was idle — the loop is
mid-iteration and reaches `RunPLC` normally, which is why the ROM arms on a lag
row. `Sonic1PlcService.hasPreparationBoundary` returns `false` for `LAG`, so an
arm released on a lag row cannot become visible until the next non-lag row's
preparation boundary, costing exactly one service.

**These two are not independent.** The scope table's `mz3_2` argument is about
*service* suppression; a *preparation* fix moves the engine the other way. Order
the work so each result is attributable to one change, and re-measure the lag
question against a corrected baseline rather than against today's.

## Telling a legitimate lag release from an illegitimate one

1. **Does it change what the row does, or only which ROM-ported path the V-int
   takes?** Legitimate: the branch is selected and every compared value is still
   produced by the engine's `ProcessPLC` port.
2. **Is the value the ROM's branch input, or a measurement of the engine's
   error?** Legitimate: the recorded `lagged` flag. Illegitimate: a frame list,
   stall count or tile offset obtained by diffing a fixture.
3. **Does it only ever say "no handler ran"?** It may suppress a handler, never
   select one.
4. **Is it reading the flag, or inferring from counter shape?** Counter shape is
   wrong in exactly the bridges this targets.
5. **Would it hold for a BK2 nobody has recorded?** The recording carries the
   emulator's own per-frame lag observation, so a new movie carries its own
   answer.
