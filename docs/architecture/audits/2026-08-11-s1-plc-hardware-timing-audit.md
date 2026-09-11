# Audit: does the S1 GHZ round-trip tail need a PLC hardware-timing kind?

Date: 2026-08-11
Base commit: `746d3de04` (branch `develop`)
Status: **point-in-time assessment. No production code changed. No guard changed.**

Supersedes the premise of
[`../designs/2026-08-11-s1-plc-hardware-timing-kind-review-request.md`](../designs/2026-08-11-s1-plc-hardware-timing-kind-review-request.md),
which has been annotated as superseded. Extends
[`2026-07-27-s1-hardware-timing-inventory.md`](2026-07-27-s1-hardware-timing-inventory.md)
rather than replacing it.

---

## Headline: the brief's central claim is REFUTED

The task brief stated that the residual 34-row deficit in
`TestS1GhzMazeRoundTripChain` "sits inside `Level_TtlCardLoop`: engine rows
8884-9034 = 151 vs the ROM's ~185", and that the gate is "decompression-rate
bound" with no derivable constant.

**MEASURED: the ROM's `Level_TtlCardLoop` for this load is exactly 151
iterations, not ~185. The engine's 151 is exactly right. The deficit is not in
that loop, and no PLC timing kind can close it.**

Three independent measurements below establish this. The practical consequence:
routing S1 PLC through the hardware-timing pipeline would take a model that is
currently *exactly ROM-correct* and lengthen it to absorb an error that lives in
a different routine. That is precisely the "constant absorbing an error
elsewhere" failure mode named in CLAUDE.md hard rule 3, and it would be
architecturally irreversible (schema 3 + fixture republication) for a fix that
is wrong on the merits.

**Recommendation: do not pursue an S1 PLC hardware-timing kind. Close the review
request. Leave `TestS1S2PlcComparisonOnlyGuard` exactly as it is.**

---

## Question 1 — lag and execution phase

### 1a. The disputed rows do not exist in the recording (MEASURED)

`run_manifest.json` segment coverage for `s1-ghz-maze-roundtrip`:

| segment | bk2 offset | rows | movie frames covered |
|---|---|---|---|
| `ghz1` | 774 | 4182 | 774 – 4955 |
| `ss` | 4957 | 3091 | 4957 – 8047 |
| `ghz2` | 8049 | 812 | 8049 – 8860 |

Recorded physics coverage **ends at movie frame 8860.** The span the brief asks
me to classify — movie rows 8884..9068 — contains **zero recorded rows**. There
is no `gameplay_frame_counter`, no `vblank_counter` and no `lag_counter` for any
frame in it. The only recorded artefact in the terminal tail is the single
`dynamic_art_gap_transitions` tail edge at `movie_logical_frame 9071`
(`edge_ordinal 4698/4699`, `transfer_id 2349`, `rom_callback_pc 3360`).

So question 1 as posed cannot be answered by measuring recorded rows, and any
past claim about lag "in that span" — in either direction — was never
measurable. This is worth stating plainly because it also bounds what any future
recorder change could prove without a new capture.

### 1b. The recording contains no lag frames anywhere (MEASURED)

Across every recorded row of the run:

| segment | `lag_counter` | distinct values | `vblank_counter` per row |
|---|---|---|---|
| `ghz1` | 0 → 0 | 1 | 4181 ticks over 4182 rows, never held |
| `ghz2` | 0 → 0 | 1 | 804 ticks over 812 rows, held on 8 rows |

`lag_counter` is identically zero for the entire movie. **Lag explains none of
the 34 rows.** This is measured, not inferred, and it holds for the whole
recording rather than only the unrecorded tail.

Two incidental observations, recorded for accuracy rather than because they bear
on the 34:

- `ghz2` has 8 rows on which `vblank_counter` did not advance (812 rows, 804
  ticks). That is the opposite sign to lag — surplus rows, not dropped frames —
  and is unexplained. It does not enter the tail accounting because it is inside
  recorded coverage that already compares green.
- `ghz2`'s `gameplay_frame_counter` is frozen at `0x1053` for all 812 rows,
  whereas `ghz1`'s increments once per row. Whatever the recorder samples for
  that field, S1 stops advancing it in the post-special-stage act. Flagged as a
  possible recorder-fidelity issue for a separate investigation; it does not
  affect this audit's conclusion.

### 1c. Execution phase accounts for zero of the 34 (MEASURED + INFERRED)

Engine phase lengths in the terminal tail, from the instrumentation recorded in
`TestS1GhzMazeRoundTripChain`'s class comment and confirmed by the current run:

- `SPECIAL_STAGE_RESULTS` — 22 rows
- `TITLE_CARD` — **151 rows** (MEASURED)
- then ~3 rows to the dynamic-art edge at engine `movie_logical_frame 9037`

Current failure, re-run at `746d3de04`:

```
run_tail.edge[0].movie_logical_frame  expected=9071  actual=9037  delta=34
run_tail.edge[1].movie_logical_frame  expected=9071  actual=9037  delta=34
```

The engine's phase machine has a phase for every ROM loop that waits on frames.
It has **no** phase at all for the ROM's blocking, non-frame-quantised level-load
work. That is where the 34 sit — see question 2.

### Clock naming

Every row number in this document is `movie_logical_frame` (the BK2 movie
clock), not a segment-local row index and not `Level_frame_counter`. The 151 is
a count of engine `TITLE_CARD` phase iterations, which correspond 1:1 to
`Level_TtlCardLoop` iterations and therefore to VBlanks.

---

## Question 2 — YES, the decode quantum is derivable, and already implemented

### The ROM has a hard per-frame budget (MEASURED, ROM-cited)

S1 is **not** like S3K's `Process_Kos_Queue`. Where the S3K routine decompresses
a whole blob in one uninterruptible call — no quantum, sub-frame in-progress
bit, underivable — S1 splits Nemesis decode across frames with an explicit
counter:

- `ProcessPLC_9Tiles` (`docs/s1disasm/sonic.asm:1431-1440`) sets
  `move.w #9,(v_plc_framepatternsleft).w` — **exactly 9 patterns per VBlank.**
- `ProcessPLC_3Tiles` (`docs/s1disasm/sonic.asm:1443-1451`) sets 3 per VBlank.
- `ProcessPLC`'s inner loop (`sonic.asm:1472-1478`) decodes one tile
  (`movea.w #8,a5` / `NemPCD_NewRow`), decrements `v_plc_patternsleft`, and
  decrements `v_plc_framepatternsleft`, exiting the frame when the frame budget
  is spent.
- `VBlank_TitleCards` (routine `$0C`, `sonic.asm:912`, dispatch at
  `sonic.asm:697`) calls `ProcessPLC_9Tiles` at `sonic.asm:946`. The title-card
  loop sets `id_VBlank_TitleCards` every iteration (`sonic.asm:2815`), so the
  title-card drain runs at **9 patterns/frame**.
- `RunPLC` (`sonic.asm:1379-1420`) arms the FIFO head only when
  `v_plc_patternsleft` is zero, and is called from the loop body *after*
  `WaitForVBlank` (`sonic.asm:2819`). `ProcessPLC_ShiftCue` (`sonic.asm:1494`)
  pops the finished entry in the same VBlank that consumed its last pattern, so
  the next entry is armed on that same iteration — there is no inter-entry
  bubble.

Therefore the loop length is fully determined by the ROM and the level's own PLC
list: **iterations = 1 (initial arming) + Σ ceil(Nᵢ / 9)**.

### The count for the GHZ title-card load (MEASURED against `s1.gen`)

Derived through the engine's own ROM-loading pipeline
(`PlcParser` + `NemesisPlcPatternCounts`, ROM `AFE05EEE`; the measurement
harness was a throwaway JUnit class, deleted, not staged). GHZ's level header
gives primary `plcid 4` (`PLC_GHZ`); `Sonic1LevelInitProfile` also appends
`plcid 1` (`PLC_Main2`), matching `sonic.asm:2733-2737`.

| PLC | entry | source | patterns | `ceil(n/9)` |
|---|---|---|---|---|
| `PLC_GHZ` | 0 | `0x03CB3C` | 461 | 52 |
| | 1 | `0x03E19C` | 369 | 41 |
| | 2 | `0x02F8C8` | 4 | 1 |
| | 3 | `0x0300BA` | 24 | 3 |
| | 4 | `0x035EB0` | 68 | 8 |
| | 5 | `0x03639E` | 55 | 7 |
| | 6 | `0x037016` | 32 | 4 |
| | 7 | `0x037CB6` | 85 | 10 |
| | 8 | `0x037A2C` | 29 | 4 |
| | 9 | `0x02FCFE` | 8 | 1 |
| | 10 | `0x03A80A` | 16 | 2 |
| | 11 | `0x03A90C` | 14 | 2 |
| `PLC_Main2` | 0 | `0x039B02` | 64 | 8 |
| | 1 | `0x02C730` | 27 | 3 |
| | 2 | `0x02C8C6` | 36 | 4 |
| **total** | **15 entries** | | **1292** | **150** |

**150 service frames + 1 arming iteration = 151 loop iterations.**

The engine's measured `TITLE_CARD` phase is **151**. Exact match, from an
independent ROM derivation. The engine already implements this correctly:
`Sonic1PlcService.serviceFastVBlank()` calls `queue.servicePatterns(9)`
(`Sonic1PlcService.java:101-103`), `prepare()` calls `queue.prepareHead()`
(`:91-93`), and `Sonic1LevelInitProfile.completeInitialPresentationPlcs` drains
`PlcLifecyclePhase.LEVEL_TITLE_CARD` against `plcService::isBusy`
(`Sonic1LevelInitProfile.java:169-171`).

The brief's "~185" has no ROM basis I can find. The `v_plc_buffer` test at
`sonic.asm:2839-2840` does hold the loop, exactly as the review request said —
but what it holds it for is a fully derivable 151 frames, not an underivable
decompression rate.

### Where the 34 actually are (MEASURED bound + INFERRED attribution)

Between the loop's exit (`sonic.asm:2841`) and `Level_Delay`
(`sonic.asm:2954`) the ROM executes a long, entirely **blocking** span with no
`WaitForVBlank` and no `dbf` frame loop: `Hud_Base`, `PalLoad_Fade`,
`LevelSizeLoad`, `DeformLayers`, `LevelDataLoad`, `LoadTilesFromStart`,
`ColIndexLoad`, `ObjPosLoad`, `ExecuteObjects`, `BuildSprites`
(`sonic.asm:2857-2900`). An equivalent blocking span precedes the loop
(`ClearScreen`, the direct `NemDec` of `Nem_TitleCard` at `sonic.asm:2720-2723`).
VBlanks keep firing and `V_int_run_count` keeps incrementing throughout, but the
code never waits on them, so the number of frames consumed is **68000 cycle
cost** — the one thing that genuinely cannot be derived from frame-granularity
state.

Accounting (INFERRED from arithmetic, because the tail has no recorded rows):
the ROM's edge at 9071 sits 26 rows before its first main-loop row
(`Level_Delay` 4 + `PalFadeIn_Alt` 22 — the same 26 that already explained the
run's *first* gap edge at 748 = 774 − 26). With the title-card loop ending at
9034 in both, the ROM spends ~37 frames in that blocking span; the engine spends
~3. Deficit 34.

This is the **level-load-span** strand of
`docs/architecture/plans/trace/2026-08-06-trace-validation-roadmap.md` §4, whose
stated prerequisite is a *recorded* level-load span segment plus an
engine-counted load model. It is the S3K-shaped problem — no quantum, pure cycle
cost — and it is a different problem from S1 PLC, which does have a quantum and
is already solved.

---

## Question 3 — S1 timing inventory

The contract's acceptance criterion 1 is **already satisfied** by
[`2026-07-27-s1-hardware-timing-inventory.md`](2026-07-27-s1-hardware-timing-inventory.md),
which enumerates every S1 hardware-readiness site, separates `LAG` / `PHASE` /
`NATIVE_SERVICE_QUEUE` / `INITIAL_BASE` / `DIAGNOSTIC_ONLY`, and already records
the 3-and-9-pattern budgets at `sonic.asm:775-784,860-870,909-967`. It should be
read as the inventory; this section only adds what 2026-08-11 measurement
changes.

Candidate art-loading paths, restated against the brief's three criteria —
(a) polled by a gameplay-visible loop, (b) synchronous in the engine today,
(c) a timing-port candidate:

| ROM path | routine | gate that observes it | (a) polled? | (b) synchronous in engine? | rate ROM-derivable? | fixture data that would distinguish it | timing-port candidate? |
|---|---|---|---|---|---|---|---|
| PLC queue, title-card drain | `RunPLC` + `ProcessPLC_9Tiles`, `sonic.asm:1379-1478`, `946` | `tst.l (v_plc_buffer)`, `sonic.asm:2839` | yes | **no** — modelled at 9/frame | **YES, 9/frame; verified exact (151)** | none needed | **NO — already exact** |
| PLC queue, ordinary level drain | `ProcessPLC_3Tiles`, `sonic.asm:1443-1451` (VBlank `$08`, `:867`) | object routines polling `v_plc_buffer` (results card, game-over, FZ boss) | yes | no — modelled at 3/frame | **YES, 3/frame** | none needed | **NO** |
| PLC queue, special-stage results drain | `sonic.asm:3400-3410` | `tst.l (v_plc_buffer)` | yes | no | YES, 9/frame | none needed | **NO** |
| PLC queue, level-select / credits drains | `sonic.asm:2195-2203`, `3877-3886` | `tst.l (v_plc_buffer)` | yes | not exercised by current traces | YES | none currently | **NO** (speculative; no route reaches it) |
| **Pre/post-title-card blocking level load** | `sonic.asm:2700-2740`, `2857-2900` | none — no poll, no wait | **no** | yes, at ~0 cost | **NO — pure cycle cost** | **a recorded level-load span segment; none exists today** | **the real gap**, but not a *readiness* kind — see below |
| `QuickPLC` | `sonic.asm:1519-1543` | return from call | no | yes | n/a (blocking) | none | NO — classified `LAG` in the 2026-07-27 inventory |
| Nemesis / Kosinski / Enigma direct decoders | `sonic.asm:1828-1981` and `_inc/Decompression/*` | return from call | no | yes | NO — cycle cost | as above | NO — `LAG` |

Honest labelling: rows 1-3 are measured this session. Row 4 is **speculative** —
those loops are real in the ROM but no committed trace routes through them. Row 5
is the one real deficiency, and it fails criterion (a): the ROM never *polls*
anything there, so it is not a completion-readiness gate at all. A
`HardwareWorkKind` is the wrong shape for it; what it needs is a recorded
load-span duration, which is the roadmap §4 item and a different contract
question.

---

## Question 4 — what schema 3 would actually cost

Scoped concretely so the number is real, even though the recommendation is not
to spend it.

**Registry and schema selection**

- `HardwareWorkKind` (`src/main/java/com/openggf/game/timing/HardwareWorkKind.java`)
  is a 17-line enum with two constants and a hand-written `fromWireName`. Adding
  a constant and a wire name is a ~4-line edit. This is the trivially small part
  and is not where the cost is.
- `HardwareWorkKind` is referenced from **81 files** (33 under `src/main`, 48
  under `src/test`). Of the `src/main` references, all game-side producers are
  S3K; `HardwareWorkSubmission` is referenced outside `game/timing/` in exactly
  two files (`S3kKosDecompressionQueue.java`, `S3kKosModuleQueue.java`). An S1
  producer would be a third, in `Sonic1PlcService`, plus its boundary
  registration.
- Schema selection: `trace_schema: 5` is the sole live contract and owns
  metadata, rows, timing and run manifests. The kind-set-per-schema mapping is
  what a third kind changes; there is no single `traceSchema` switch inside
  `trace/timing/` to edit, so the change is distributed across
  `HardwareTimingStreamLoader`, `TraceHardwareTimingScheduleCompiler`, and the
  fixture-validation tests.

**Guard reversal (the actual architectural cost)**

`TestS1S2PlcComparisonOnlyGuard.timingKindRegistryAdmitsOnlyKosinskiWork`
asserts both that no kind name contains `PLC` and that the value set is exactly
`{KOS_MODULE_QUEUE, KOS_DECOMPRESSION_QUEUE}`. Renaming around the substring
does not help — the exact-set assertion still fails. The guard's five other
tests (`nativePlcServicesDoNotDependOnTracePackages`,
`traceProductionSourcesDoNotDependOnNativePlcServices`,
`replayAndBootstrapSourcesDoNotReferenceNativePlcServices`, and the dynamic-art
mutation guards) would all need re-scoping, because an S1 PLC producer by
construction makes a native PLC service depend on `game/timing/` and makes trace
replay reach it. That is not an edit; it is deleting the isolation the guard
exists to enforce.

**Recorder and fixtures**

- The recorder must emit `hardware_timing.jsonl` rows for S1 with a stable
  submission fingerprint. The fingerprint components are available
  (`Sonic1PlcService.Submission` / `PlcDefinition`: Nemesis source ROM address,
  VRAM destination, decoded pattern count).
- **There are currently 139 `hardware_timing*` fixtures and all 139 are under
  `src/test/resources/traces/s3k/`. Zero exist for S1.** Every S1 fixture that
  should carry timing would need regeneration from its BK2 — a full S1 fixture
  republication, which is behind an explicit user-approval gate.

**What breaks in the interim**

Between adding the kind and republishing fixtures, every S1 trace runs with a
declared-but-absent timing stream. Under the contract a job may only be released
by a matching recorded edge, so S1 PLC readiness would either stall (all S1
traces red) or require a fallback path that re-introduces the deterministic
service — i.e. the change would be inert until republication completes, and red
in between.

**Rough total:** ~4 lines of enum, ~2 new producer integration points, a
distributed schema-selection change across 3 trace-timing classes, a deliberate
rewrite of 1 guard class (6 tests), a recorder feature, and regeneration of the
entire S1 fixture set — with all S1 traces red or inert in the interim.

And it would not fix the 34 rows.

---

## Recommendation

1. **Do not implement an S1 PLC hardware-timing kind.** The gate it would target
   is already exactly ROM-correct at 151 frames. Adding recorded authority there
   could only make a correct model wrong.
2. **Leave `TestS1S2PlcComparisonOnlyGuard` untouched.** Its stated rationale —
   "PLC readiness is native deterministic service, not timing-stream authority" —
   is now positively confirmed by measurement rather than merely asserted. This
   audit is evidence *for* the guard.
3. **Close the review request** at
   `docs/architecture/designs/2026-08-11-s1-plc-hardware-timing-kind-review-request.md`
   (annotated superseded). Its blocking analysis was accurate; its premise was
   not.
4. **Re-point the residual 34 rows** at the level-load-span strand
   (`docs/architecture/plans/trace/2026-08-06-trace-validation-roadmap.md` §4).
   Its prerequisite is unchanged and is a *recording* problem, not a contract
   problem: no fixture currently records a level-load span, and this run's
   coverage stops 210 frames before the disputed edge.
5. **Consider leaving `TestS1GhzMazeRoundTripChain` red** with its accurate
   comment rather than closing it. A 34-frame constant fitted here would be a
   fitted model under hard rule 3 and would desync the first different
   recording — the deficit is 68000 cycle cost, and it varies with zone, act and
   PLC list.
6. Separately, investigate `ghz2`'s frozen `gameplay_frame_counter` and its 8
   vblank-held rows as a possible recorder-fidelity issue. Unrelated to the 34,
   but it is unexplained recorded data.

### What would change this recommendation

- **A recorded level-load span** showing that the ROM's title-card loop for this
  load was *not* 151 frames. That would falsify the core measurement directly.
  It is the single most valuable capture anyone could add, and it would settle
  both this question and roadmap §4.
- Evidence that `Level_TtlCardLoop` for this specific load queues a PLC list
  other than `PLC_GHZ` + `PLC_Main2` — e.g. a lamppost/checkpoint path that
  alters the queue. I checked the level-header path
  (`Sonic1LevelInitProfile.queueInitialPlcs`) and `sonic.asm:2726-2737`, and
  found none, but the terminal load is a next-act advance out of a special
  stage, which is the least-travelled entry into `GM_Level`.
- A demonstration that the ~37-frame blocking span is itself frame-quantised
  somewhere I did not read — I read `sonic.asm:2841-2960` and found no
  `WaitForVBlank` before `Level_Delay`, but `LevelDataLoad` and
  `LoadTilesFromStart` were not read to their leaves.

## Measurement provenance

- Worktree `<scratch-worktree>` at `746d3de04`, JDK 21
  (`mvn -v` → 21.0.11). Left clean; the throwaway measurement class was deleted.
- Failure reproduction:
  `mvn -Ptrace-replay -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical "-Dsurefire.argLine=-Xmx4g" -Dsonic1.rom.path=… -Dsonic2.rom.path=… -Ds3k.rom.path=… "-Dtest=TestS1GhzMazeRoundTripChain" test`
  → `Tests run: 2, Failures: 1`; `run_tail.edge[0..1].movie_logical_frame
  expected=9071 actual=9037 delta=34`.
- Pattern counts derived through `PlcParser` / `NemesisPlcPatternCounts` against
  the user-supplied ROM (CRC32 `AFE05EEE`) — ROM-loading pipeline only, no bytes
  read from `docs/` (hard rule 1).
- Counter statistics read from
  `src/test/resources/traces/s1/runs/s1-ghz-maze-roundtrip/{ghz1,ghz2}/physics.csv.gz`
  (comparison-only; nothing hydrated — hard rule 4).
