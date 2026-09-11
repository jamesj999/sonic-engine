# S2 Special Stage Trace-Green Campaign — Design

Date: 2026-07-09
Status: Approved for planning
Predecessor: `2026-07-09-s2-special-stage-trace-design.md` (pipeline, merged to develop `72794884b`)

## Goal

Drive the S2 special-stage trace (`TestS2SpecialStageTraceReplay`, trace
`src/test/resources/traces/s2/special_stage/`) to **fully green with the ratchet
on**, thereby fixing the four reported issue areas:

1. Control locked/unlocked at the wrong times.
2. Tails CPU sidekick behavior (team now spawns — post two-key fix — but CPU
   input semantics are unverified against ROM).
3. Playback speed driven by an imprecise flat-0.35 lag compensator (normal play).
4. Timing of text/graphics (start banner, "GET n RINGS", rings-to-go HUD) and
   ring/object appearance.

Success criteria:

- Zero Tier-1 errors on the trace; `assertNoReleaseBlockingDivergences()` flipped
  on in `AbstractS2SpecialStageTraceReplayTest` so it stays green.
- Each Tier-2 warning group ratcheted to error as its area is fixed; by campaign
  end all Tier-2 groups are errors and green (requires the engine/snapshot
  additions below).
- Normal-play lag compensator replaced by a trace-derived deterministic lag
  model (statistical validation, defined below).
- `docs/status/trace-frontier-log.md` updated at every frontier move per repo policy.

## Ground truth: the divergence chain (report of 2026-07-09)

`target/trace-reports/s2_special_stage_0_report.json` — 15,313 errors / 1,877
warnings over 3,249 stepped frames — reduces to an ordered root chain:

Frame numbers below are trace-frame indices from SS entry (they count every
recorded frame including lag rows); the comparator *steps* 3,249 of them and the
report's `total_frames` of 3,250 includes one extra finished-transition
bookkeeping row.

| First frame | Field | Root reading |
|---|---|---|
| f0–f22 | `*_present`, all player fields | ROM's `SpecialStage:` entry runs `Pal_FadeToWhite` first (`s2.asm:6546`), a 22-iteration `WaitForVint` loop (`s2.asm:3570-3582`) executed with `Game_Mode` already `0x10` and the player object slots still empty; the engine spawns both players immediately |
| f0 | `speed_factor` exp 0 act 12 | ROM sets player object ids only at `s2.asm:6628-6634` and `SS_New_Speed_Factor=$C0000` at `s2.asm:6640`, all after the fade; engine initializes 12 at construction. (The PLC/track wait loops at `s2.asm:6644-6658` run *after* object creation — they are not the source of this window) |
| f4 | `track_anim_frame` +1 | track animation starts on the wrong phase relative to mode entry |
| f195 | `current_segment` 0 vs 1 | engine track playback runs ahead (accumulated phase shift) |
| f791 | `combined_rings` 1 vs 0 | first ring-collection timing (downstream of the shift) |
| f2683 | `sonic_hurt` | bomb-hit response mismatch (downstream) |
| f5073 | `finished` (146 frames early) | accumulated time shift reaches course end |

The intro/init misalignment is the dominant root; most of the error mass is its
cascade. Issues 1 and 4 are largely THIS; issues 2 and 3 sit behind it.

### 2026-07-10 atomic comparison correction

The original table treated each VBlank CSV row as though it were the completed
state of the ROM logic pass. A PC-execute probe disproved that assumption:
`WaitForVint` can return before a VBlank sample and the following
`SSObjectsManager -> RunObjects` work can finish after it, including across a
lag row. At f799 the CSV therefore reports one ring while the exact
`RunObjects_End` state for that same logical step reports three. Conversely,
the first-ring f791 pass end remains one. Recorder v1.3-s2ss uses exactly two
REV01 execute hooks: the shared `ReadJoypads` RTS at `$1156`, filtered to
`A0=$F608` and stack return `$88E` so it represents the completed P1/P2 poll
inside `Vint_S2SS`, and `RunObjects_End` at `$15FE4`
(`s2.asm:837-840,1361-1387,29805-29849`). Every completed recurring pass records
the full manager/player surface atomically, a contiguous `pass_sequence`, and
the exact physical poll identity/raw held/previous-polled held bytes it consumed.
The pass queues to the first forward non-lag observation after completion;
replay then executes zero, one, or multiple engine updates in that order. The
pass whose input sample still saw `SpecialStage_Started=0` is retained on the
verified startup VBlank path rather than replayed twice, and pass capture stops
at the final-checkpoint `stage_finished` boundary. Obj6F is retained later as
`results_started` in the uncompared tail. This is physical input plus pacing
only, never trace-to-engine state hydration. Missing fields, sequence/sample gaps, held-chain breaks, BK2
identity mismatches, invalid bounds, or a post-finish pass fail validation.

The semantic gate deliberately leaves the distinct startup/fade loops on their
VBlank CSV observations; there is no frame-number exception. Completed-pass
pacing removes the f916 one-pass-ahead mismatch. Replay binds each captured
sample to the already-pending owning pass before executing it, rather than
letting `update()` consume the preceding sample. The Stage-1 report now has
3,694 errors / 0 warnings (75.88% below the 15,313-error baseline), first
divergence f1019 `sonic_ss_x` expected 65530, actual -6 (signed mapping).

### 2026-07-10 f890 player-control correction

A bounded PC-execute probe resolved f890 as two partly compensating engine
errors. In the active recurring loop, `Vint_S2SS` reads the current joypad word
during `WaitForVint` and the main thread copies that fresh raw word to
`Ctrl_1_Logical` immediately before `RunObjects` (`s2.asm:6694-6721`). Obj5F
latches `SpecialStage_Started` when WAIT2 creates the ring-requirement message
(`s2.asm:9734-9746`), so MESSAGE_FLYOUT and GAMEPLAY select this source while
pre-start DROP/WAIT1/WAIT2 retain the preceding raw word copied before their wait
(`s2.asm:6674-6688`). The engine snapshots this semantic latch so later
checkpoint-message reuse cannot re-lock the control-copy path. Separately, Obj09's
`SSObjectMove` negates negative inertia and logically shifts the magnitude
before subtraction (`s2.asm:69718-69735`), truncating `-$60` and `-$C0` to zero
where Java's signed shift had produced `-1`. Modeling both rules moves the
frontier to f896 and reduces the report from 12,271 to 11,049 errors. The f896
residual is a separate pass-observation/cadence question around lag-labelled
VBlank rows, not justification for another input latch or movement offset.
The raw press byte stored at each executed VInt is likewise computed once from
current held versus the last executed VInt held sample (`s2.asm:1361-1387`);
BK2 mapper edges whose intervening release occurred entirely on a skipped update
are neither ORed into the active pass nor retained for the following pre-start
prior-word copy.

### 2026-07-10 f1331 ring-angle boundary correction

After signed coordinate comparison exposed f1331, atomic pass snapshots and an
engine active-object dump showed paired animation-8 rings at angles `$48` and
`$38`. Sonic was at `$42`; the engine collected both in pass 570, while ROM
collected the `$48` ring in pass 570 and the `$38` ring only in pass 571 after
Sonic moved to `$41`. `Obj61_TestCollision` implements a half-open byte interval:
`objectAngle-d6 <= playerAngle < objectAngle+d6`, including wraparound
(`s2.asm:70846-70877`). Obj60 supplies `d6=$A`; Obj61 supplies `d6=8`
(`:70674-70676,70767-70769`). The engine had made the positive endpoint inclusive
and shared Obj60's width with bombs. Correcting both properties in the shared
predicate moves the report from 1,311 errors / 0 warnings at f1331 to 606 / 0 at
f4845; no collision timing or trace-specific offset is involved.

## Method (binding rules)

- **Fix loop:** per the `trace-replay-bug-fixing` skill — take the FIRST
  divergence, root-cause against the disassembly, fix the engine, rerun the
  trace, bank the win (commit + frontier-log entry), re-triage. Never chase a
  divergence downstream of an unfixed earlier root.
- **ROM-modeled fixes only:** every retiming fix must model the ROM mechanism
  (init routine order, object routine counters, `SpecialStage_Started`,
  banner/flyout object timers — cite `s2.asm` lines), never a magic constant
  sniffed from the trace. The trace is evidence, not source. No zone/route/
  frame carve-outs; the comparison-only invariant continues to hold.
- **No compensating errors:** a fix that improves the error count but models no
  ROM mechanism is rejected even if it "works".
- **Regeneration:** if a root needs trace data the recorder doesn't capture,
  extend the lua (bump `LUA_SCRIPT_VERSION` — it owes a bump already), re-record
  via the ps1 route, and re-commit artifacts; parser/columns changes follow the
  pipeline spec's contracts (48-column header is versioned via `ss_csv_version`).

## Campaign stages

### Stage 1 — Init/intro sequence alignment (the dominant root)

Port the ROM's special-stage startup order faithfully, per the disasm's
`SpecialStage:` routine (`s2.asm:6537-6672`): the dominant pre-roll consumer is
the 22-frame `Pal_FadeToWhite` loop (`s2.asm:3570-3582`, invoked at `:6546`),
followed by init work, player object creation (`:6628-6634`),
`SS_New_Speed_Factor` set (`:6640`), then the PLC/track wait loops
(`:6644-6658`), track animation start, `SS_player_anim_frame_timer` seeding,
and input enable. Rework `Sonic2SpecialStageIntro`'s phase timings
(DROP/WAIT/MESSAGE_FLYOUT/GAMEPLAY) and
`Sonic2SpecialStageManager.initialize()`/`update()` ordering to match — driven
by the ROM mechanism, not by trace frame numbers.

**Two gates, not one.** The pre-roll has a player half and a global half, each
needing an owning mechanism:

**(a) Pre-roll phase (global half):** add a leading phase to
`Sonic2SpecialStageIntro`'s machine (before DROP — e.g. `PRE_ROLL`) modeling the
fade window. While active it suppresses everything the ROM hasn't started yet:
`trackAnimator.update()` and the `drawingIndex` increment (both currently
unconditional every frame — `Sonic2SpecialStageManager.java:1030,1043-1044`,
commented "Track animation always runs (even during intro)"), the speed-factor
set (engine currently constructs at 12; ROM sets it at `s2.asm:6640` —
`captureComparisonState` reads it via `trackAnimator.getSpeedFactor()`, so the
animator needs a pre-roll value of 0), and the banner DROP start. Folding the
pre-roll into the intro `Phase` enum gives rewind-snapshot coverage for free
(the intro phase machine is already captured, `Sonic2SpecialStageManager.java:2149`).
**Boundary pinned:** the observable empty-slot window is the 23 recorded frames
f0–f22 (the 22-iteration fade loop plus init work); the spawned flags and
speed-factor set flip ON f23 — implement the phase length from the ROM
mechanism, then verify against this window. The 23rd frame (beyond the 22 fade
iterations) must be attributed to a specific ROM VInt between the fade call's
return (`s2.asm:6547`, immediately after the `bsr.w Pal_FadeToWhite` at
`:6546`) and object creation (`s2.asm:6628`) during implementation — never
padded to make the count fit.
**This retiming intentionally applies to normal (non-trace) play** — that is
the substance of issue 4 — and the plan must verify it composes with the
engine's existing SS-entry fade presentation rather than double-counting a
pause. **Replay frame-0 anchor moves:** the predecessor spec's "frame 0 anchors
to intro DROP phase start" is superseded — frame 0 now anchors to the pre-roll
phase start immediately after `initializeStage()`.

**(b) Gating, not deferral (player half; rewind invariant):** players stay constructed at
`initialize()` — `restorePlayerTopologyForRewind`
(`Sonic2SpecialStageManager.java:2263-2264`) throws when the player count
changes across a rewind restore, and `setupIntro()`'s team detection reads the
constructed players (`:395`), so deferring construction is off the table.
Instead add a `spawned`/`active` flag on `Sonic2SpecialStagePlayer` that (a)
gates participation (movement/collision/render) during the pre-roll exactly as
ROM's empty slots do, (b) makes `toComparisonPlayerState`
(`Sonic2SpecialStageManager.java:2442`) return null while unspawned so the
comparator's `present` goes false — a small comparison-accessor change this
stage owns; the Tier-1 comparator must treat a null player comparison-state as
`present=false` and skip that player's positional/routine fields for the frame
(never NPE or compare against stale values — the recorder still dumps slot
bytes when the ROM slot is empty) — and (c) is captured in
`Sonic2SpecialStageSnapshot` so rewinding into the pre-roll restores the
unspawned state.

Expected outcome: the f0–f22 cluster, `speed_factor` f0, `track_anim_frame` f4
and most position/segment/ring/finish cascade collapse. Rerun, re-triage,
record the new first root.

### Stage 2 — Iterative frontier fixes to Tier-1 green

Repeat the fix loop on whatever the report surfaces next. Anticipated (from the
chain + known code): track phase residuals, ring-collection windows
(`SS_Perfect_rings_left` / object touch), control-lock edges (jump/hurt input
windows; `intro.isInputEnabled()` gating vs ROM's control flags), Tails CPU.
**Control-lock adjudication:** no recorded field marks ROM control enablement
directly — today it is inference-only through position cascade. The ROM's
authoritative flag is `SpecialStage_Started` (RAM `0xDB23`; cleared
`s2.asm:6585`, gates the gameplay loop `s2.asm:6689`, set when the
ring-requirement message resolves `s2.asm:9745`). Since the recorder already
owes a `LUA_SCRIPT_VERSION` bump, add `SpecialStage_Started` to the recorded
stream (per-frame or aux transition events) during the first regen this stage
needs, so control-unlock timing is compared directly. Continue with Tails CPU
`SS_Ctrl_Record_Buf` semantics (shift order, delayed-tap index, P2-override
branch conditions, `s2.asm:70411-70449`), bomb/hurt response, and the finish
frame. Tier-1 green = flip the ratchet on (releases-blocking divergences fail
the test from then on).

### Stage 3 — Tier-2 ratchet enablers (engine/snapshot additions)

- **Per-player rings:** add per-player ring tracking to the engine mirroring the
  ROM's per-object `ss_rings_*` fields (`s2.asm:70771-70789`, summed for HUD
  `s2.asm:9938-9943`). The collision path already knows the touching player
  (`handleObjectCollision(obj, player)`,
  `Sonic2SpecialStageManager.java:1234,1243`) — route collection through it,
  AND convert bomb-hit ring loss (`loseRingsFromBombHit()`, `:1257`) to a
  per-player debit on the hit player, matching ROM. Keep the combined total
  consistent. Then compare per-player rings as errors.
- **Snapshot extension:** add rings-to-go state, swap-positions flag (reconciled
  to a single ROM-faithful global instead of per-player copies), and hurt/slide/
  flip timers to `Sonic2SpecialStageComparisonState`; wire each into the
  comparator and ratchet to error when green.
- **`getPlayerAnimFrameTimer()`:** replace the constant with a real decrementing
  counter (ROM `SSRun_Animation_Timers`, `s2.asm:960-982`) and START comparing
  the recorded `player_anim_frame_timer` column (currently recorded-not-compared).
- **`rings_togo_bcd` mapping (defined):** the engine stores no rings-to-go
  value — it is computed at render time as
  `currentRingRequirement - getRingsCollected()`
  (`Sonic2SpecialStageManager.java:1735`) — while the ROM's `SS_RingsToGoBCD`
  is a BCD cell refreshed only on ring-check events. Comparison therefore uses
  the same explicit-mapping treatment as `routine`/`track_duration_timer`:
  decode BCD, and compare only at/after refresh points (gated on the recorded
  `check_rings_flag`/`SS_TriggerRingsToGo` transitions), never raw per-frame
  equality against the live subtraction.
- Intro/message timing fields (message lifecycle aux events) ratchet to errors
  once Stage 1's retiming lands.

### Stage 4 — Normal-play lag model (issue 3, non-replay half)

Replace the flat `lagCompensation = 0.35` skip with a deterministic,
trace-derived state-keyed model:

- **Derivation (offline, one-off tool or test-tree analysis):** bucket the
  recorded per-frame lag flags by observable engine state (track segment type,
  `speed_factor`, drawing index phase, live object count) and extract per-bucket
  lag ratios and burst-length patterns from the 5,299-frame schedule.
- **Runtime:** a small `Sonic2SpecialStageLagModel` consulted once per frame in
  the same place the accumulator lives today. **Rewind constraint:** the model
  must be a PURE FUNCTION of already-rewind-snapshotted inputs (`frameCounter`,
  track animator segment/speed state, `drawingIndex`, live object count) with
  NO carried per-frame state — `Sonic2SpecialStageSnapshot` currently restores
  `lagCompensation`/`lagAccumulator` and `frameCounter`
  (`Sonic2SpecialStageManager.java:2160,2195-2196`); the accumulator is removed
  (its snapshot fields retired/repurposed in the same change), and burst
  patterns must be expressed as arithmetic on `frameCounter` + state, never as
  a new mutable counter — otherwise that counter must be added to the snapshot,
  which this spec chooses to avoid. Trace replay continues to force the model
  off (trace pacing governs there).
- **Validation (defined):** for every (segment-type × speed-factor) bucket
  present in the trace, the model's lag ratio must be within ±5 percentage
  points of the recorded ratio, and overall ratio within ±2 points of the
  recorded overall ratio (1971 lag rows / 5299 total rows ≈ 37.2%, computed
  from the committed `physics.csv.gz` `lag` column — note this spans the full
  recording INCLUDING the uncompared results tail; the validation test computes
  both sides from the same artifact rather than hardcoding these counts);
  asserted by a JUnit 5 (Jupiter) test against the committed trace artifacts.
  Perceived-speed eyeball via the visual SS session (jar test mode) at the end.
- Keep the F1 lag overlay as a read-only view of the current model bucket and
  ratio. The derived model has no live enable/disable or tuning control;
  `setLagCompensation(0)` remains an internal trace-pacing bypass only.

### Stage 5 — Green gate & closeout

Ratchet fully on (Tier-1 + all ratcheted Tier-2), full-suite regression sweep,
frontier log final entry, CHANGELOG/README per policy on merge. Add the SS
trace test to the tracked keep-green set used by trace sweeps.

## Out of scope (follow-ups)

- Results-screen replay/timing (the recorded post-`stage_finished` tail,
  including `results_started`, stays uncompared).
- Additional traces (solo Sonic, solo Tails, human-P2 override, failed-stage,
  stages 2–7) — recommended immediately after green to lock the fixes broadly,
  using the existing recorder unchanged.
- S1 special-stage trace generalization.

## Risks

- **Cascade churn:** most measured error mass may collapse after Stage 1 — or
  reveal deeper roots currently masked. Mitigation: strict first-divergence
  ordering; no parallel fix streams on this single trace.
- **Init sequence depth:** ROM SS init interleaves PLC loading across frames;
  the engine loads synchronously. The fix models the *observable* sequencing
  (object creation, flags, timers), not the PLC scheduler itself — if a
  divergence turns out to require PLC-cycle emulation, stop and re-scope with
  the user rather than emulating the loader.
- **Lag-model overfit:** one trace = one route. The ±tolerance validation and
  bucket granularity guard against memorizing the schedule; broader-corpus
  recalibration is listed as follow-up work.

## Testing

- Oracle: the SS trace test after every fix (focused run) + determinism test.
- Each engine fix carries its own focused unit test where the mechanism is
  isolable (intro phase machine, ctrl-record buffer, lag-model buckets).
- Existing level-trace suites and SS package tests stay green throughout.
- Final: full-suite sweep + visual session eyeball.
