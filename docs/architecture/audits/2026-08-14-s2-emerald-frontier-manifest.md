# S2 complete-emerald chain — frontier manifest

**Date:** 2026-08-14
**HEAD:** `aef2ae90c`
**Status:** `TestS2EhzHalfpipeRoundTripChain` GREEN. `TestS2CompleteEmeraldRunChain` RED on
5 axes.
**Suite at HEAD, all fixes stacked:** trace profile 842 run / 9 failures / 56 errors /
4 skipped, 65 red classes — identical by name to the session baseline, no interaction
regression.

---

## What this document is, and who it is for

This is the complete state of one long investigation, written so that a reader with **no
prior OpenGGF context** can engage with it. The engine (OpenGGF) is a Java re-implementation
of the Sonic 2 game logic that must reproduce the original ROM's behaviour
**pixel-for-pixel and frame-for-frame**, verified by replaying recorded runs of the real
game and comparing state every frame. One such run — the complete-emerald run — is five
defects (axes) away from matching. This document describes those five axes, the rules any
fix must satisfy, everything already tried and refuted, and the decision that is currently
open.

**A useful contribution is:** a mechanism for one of the open axes that survives the
[hard rules](#the-rules-any-fix-must-satisfy) and is not in the
[refuted list](#explanations-measured-and-refuted-do-not-re-run); a flaw in the
[axis 4 impossibility argument](#axis-4--dynamic-art-gap-ss_4--seg6_ehz2-2-fields); or a
third option for the [open decision](#open-decision). **A re-tread is:** proposing a tuned
offset or tolerance, re-proposing anything in the refuted list, or suggesting the
comparison be weakened — all of these have been tried, measured, and rejected, with the
measurements recorded below.

## Vocabulary

Read this first; every term below is used without further definition.

- **BK2 / movie** — a BizHawk emulator input recording of a real play-through of the
  original Sonic 2 ROM. Deterministic: replaying it in the emulator reproduces the run
  exactly. A **movie row** (or `movie_logical_frame`) is one frame of it, indexed
  absolutely from the movie's start.
- **Trace / fixture** — per-frame dumps of emulator state (positions, speeds, queue
  states, art-transfer events) captured while the emulator replays the BK2. Committed to
  the repo; **comparison-only** — the engine may be compared against a trace but never
  driven by one (with one narrow documented exception, rule 4 below).
- **Segment** — a contiguous span of recorded gameplay. The complete-emerald run is
  28 segments (EHZ1 → Death Egg, 7 special stages, ~245,000 movie rows). Segment names
  like `seg7_ehz2` mean "7th segment, Emerald Hill Zone act 2"; `ss_4` is the 4th
  special stage.
- **Gap / seam** — the movie rows *between* two segments: fades, title cards, level
  loading. Gameplay state is not compared row-by-row there, but art-transfer events
  crossing the gap are (see *ledger edge*). "Seam" emphasises the code path that spends
  those rows; "gap" the rows themselves. 27 gap transitions exist; 24 compare clean.
- **Chain test** — `TestS2CompleteEmeraldRunChain` replays the whole run end to end:
  every segment compared frame-by-frame, every gap's transfer ledger compared
  edge-by-edge.
- **Axis** — one independent family of failures in the chain's report. Distinct axes have
  distinct causes; a fix for one must not silently change another's error count.
- **Playable pass** — one execution of the player (Sonic) or sidekick (Tails) object's
  per-frame update. The ROM runs these from `RunObjects`; counting them is how several
  hypotheses below were tested.
- **Ledger edge / transfer** — one dynamic-art event: a *submission* (the game asks for a
  character's art tiles to be transferred to video RAM) or its *completion*. Each carries
  a **transfer id** (tid), owner (sonic/tails/title card), the submitting ROM program
  counter, and the movie row it happened on. Gap edges must be **aligned by transfer id,
  never by list index** — index alignment makes one surplus transfer look like many
  shifted ones.
- **Lag row vs admitted row** — on some frames the ROM's main loop does not run (the CPU
  is busy in straight-line load code); the V-blank interrupt takes the ROM's own
  `Vint_Lag` path instead. The emulator's lag flag coincides with this by construction.
  A gap row where the main loop ran is *admitted*; one where it did not is a *lag row*.
- **Admission census** (`gap_admission_runs`) — per gap transition, a run-length encoding
  of admitted/lag over the gap's rows (alternating, starting non-lag; lengths only, never
  a row index). E.g. `[23, 11, 53, 14, 8, 37, 25]` = 23 admitted, 11 lag, 53 admitted …
  Recorded in `run_manifest.json`; replay may spend these rows as the ROM did.
- **Census walk** — replaying a gap by actually stepping the engine through its census
  rows (advancing the V-blank counter on lag rows, running no gameplay there), instead of
  skipping the gap. Implemented for `level_advance` gaps; **not** for `stage_exit`
  interior returns, which still discard their gap rows (this matters — see Grouping).
- **`level_advance` vs `stage_exit`** — the two gap kinds. `level_advance`: act ends,
  next act loads. `stage_exit`: a special stage ends and control *returns* to the level —
  handled by a different branch ("interior return") in the test harness
  (`AbstractRunChainTest`), which currently pre-seeks past the gap rather than walking it.
- **Fitted constant** — a number chosen because it makes this fixture pass, rather than
  derived from the ROM code that owns it. Forbidden (rule 3): it will desync the first
  recording nobody has made yet.
- **`s2.asm`** — the annotated Sonic 2 disassembly; all ROM citations are line references
  into it. `Vint_runcount` is the ROM's V-blank counter; DPLC = dynamic pattern load cue,
  the per-frame character-art transfer mechanism.

---

## What syncs

The run is 28 segments, EHZ1 → Death Egg, 7 special stages, ~245,000 movie rows.

- **27 of 28 segments compare clean** — of the segments the chain reaches. The chain
  aborts around segment 11, so later material (`ss_6`, `ss_7`) is **unreached, not
  passing**; known latent anchor error there is 8–47 rows (see axis 4).
- **Player physics matches the recording row-for-row on every compared frame.** The
  sidekick does too outside the failing axes below.
- **24 of 27 gap transitions compare entirely clean.**
- On the failing gaps, transfer ids, edge ordinals, owners, submission origins and (except
  where noted) request lists and ledger fingerprints all match.

## What does not

### Axis 1 — `[walk-failure]` seg7_ehz2

```
comparator cursor 3977 of 3997, mode path=[TITLE_CARD], level ownership changed=true
```

20 recorded rows unconsumed: the engine leaves the level before the recording does.
**Not an independent defect** — a consequence of axis 2, since the engine's end-of-act
sequence fires early.

### Axis 2 — `[segment-physics]` segment 11 (`seg7_ehz2`), 236 errors

```
first non-camera mismatch: frame 3525, queue.s2_nemesis_plc.busy  rom=false engine=true
```

**No physical field diverges.** At that frame every position, speed and sub-pixel value
matches. The divergence is an art-decompression queue being busy while the ROM's is idle.

The queue burst itself is byte-identical to the ROM's — same `remaining_work` sequence
(94, 91 … 1 → 68, 65 … 2 → 12, 9, 6, 3), same 3-patterns-per-frame service, same 64-row
length. Only the **start row** differs, by 28 rows at session start and less now.

**Root cause.** This segment is the end-of-act animal-release sequence. `loc_3F3A8`
(`s2.asm:84935-84942`) gates each random animal spawn on
`move.b (Vint_runcount+3).w,d0 / andi.b #7,d0 / bne` — i.e. an animal spawns only when the
V-blank counter is ≡ 0 (mod 8). The engine's counter arrives at segment entry 6 rows
(−2 mod 8) out of phase, firing **one extra spawn**. That consumes an extra
`nextEggPrisonAnimalXOffset` + `nextAnimalArtVariant` draw pair, so every later animal gets
a different position, type and travel direction (`btst #4,(Vint_runcount+3)`,
`s2.asm:24661-24665`). A different animal survives last, so the end-of-act scan at
`loc_3F406` (`:85001-85012`) reaches `Load_EndOfAct` early — which is axis 1.

*Not* the animal deletion predicate — that already models the ROM's despawn box
(`f9da6563e`), and per-animal lifetimes match the recording (engine 96–144 rows mean ~119;
ROM 95–144 mean ~119). The defect is purely the counter's *phase* at segment entry — and
segment 11 is entered through a `stage_exit` interior return, which discards its gap rows
instead of spending them (see Grouping).

### Axis 3 — `[dynamic-art-gap]` seg4_ehz1 → seg5_ehz2, 4 fields

```
edge[8]   expected 32921   actual 32920
edge[9]   expected 32922   actual 32921
edge[10]  expected 32929   actual 32928
edge[11]  expected 32930   actual 32929
```

All `movie_logical_frame`, all exactly **one row early**. These are the title-card
leave-loop transfers — two *submitted*, two *completed*, moving as a pair (completion
trails submission by one row in both ROM and engine).

Everything else on this gap is exact, including `edge_count` and `edge[2]`–`[7]`.
**This gap reported ten mismatching fields at −93 rows at session start.**

**Root cause is known and closable**, but its fix regresses the first interior return:
the engine arms its leave phase one row early (`updateZoneTileUpload`
decrements-then-transitions), running two playable passes inside the load span where the
ROM runs one. Fixing that alone moves the surplus pass to segment 2 as a permanent +1
clock phase. Full analysis with fingerprints:
[the seam design note](../designs/2026-08-13-level-entry-seam-frame-costing.md).

### Axis 4 — `[dynamic-art-gap]` ss_4 → seg6_ehz2, 2 fields

```
edge[0]  expected 46347   actual 46348
edge[1]  expected 46347   actual 46348
```

One row **late** — the only defect in the run pointing that direction. These are the
*submitted* pair for the returning level's player art (`pc 0x1B89A` = `LoadSonicDynPLC`
exit, `0x1D1FE` = `LoadTailsDynPLC` exit, tids 28084/28085). **Their completions at 46349
match exactly.**

**Not closable at frame granularity by any rule-compliant route** — recorded as
[known-discrepancy 28](../../status/known-discrepancies.md). The argument, in full so it
can be attacked:

1. The ROM submits this art at the leading `jsr (RunObjects)` (`s2.asm:5003`). Between
   there and the leave loop's first `bsr.w WaitForVint` (`:5060-5061`) it runs **only
   straight-line 68000 code** — `BuildSprites`, `AniArt_Load`, demo setup, palette load —
   with no `WaitForVint` and no polled readiness gate.
2. So which movie row the submission lands on is a **pure 68000 cycle count**, dominated
   by `BuildSprites`/`AniArt_Load`, whose cost depends on the object set loaded at the
   entry position. It is sub-frame CPU position; frame-granularity data cannot locate it.
3. Measured across all 27 censused transitions: the harness's release anchor
   (`lastNonAdmittedRow` of the census) coincides with the ROM on 20 — **by
   coincidence** — is one row late on 4 (`ss_4`, `ss_5`, `seg15_cnz2→seg16_htz1`,
   `seg27_wfz1→seg28_dez1`), and is 8–47 rows out on 6 more.
4. Nothing zone-, act-, route- or path-dependent distinguishes them: two seams sharing a
   destination act have opposite tails, so no structural predicate exists.
5. The remaining routes are each forbidden or impractical: a tuned `−1` is a fitted
   constant (and wrong on the 20 seams currently right); recording the submission row and
   consuming it **round-trips the answer** — the recorded quantity would determine the
   very field the comparison checks, so the test would prove nothing; cycle-level
   modelling of the span is rule-compliant but is a partial 68000 cycle emulator.

To refute this you must break one of those five links — most plausibly by finding a
frame-granularity observable the ROM itself exposes for this span (none was found), or by
showing the cycle-costing job is smaller than assessed.

### Axis 5 — `[dynamic-art-gap]` ss_5 → seg7_ehz2, ~20 fields

```
edge_count              expected 16     actual 18      ← two SURPLUS transfers
edge[9].mapping_frame   expected 1      actual 105
edge[12].gap_edge_index expected 0      actual 2
edge[16].present        expected false  actual true
edge[17].present        expected false  actual true
```

**Mapping frame 105 = `0x69` = `TailsAni_Balance` frame 0** (`s2.asm:41570`). The engine
emits a Tails balancing-on-a-ledge animation frame the ROM never produces and submits art
for it. That inserts two extra ledger edges, shifting `gap_edge_index` by 2 and pushing
every later edge's mapping frame, row, requests and fingerprints out of alignment.
**The entire cascade is downstream of one surplus transfer.**

**Root cause.** On level init the ROM seeds all 64 entries of the leader's position-record
ring with `Sonic.x − $20` (`Obj01_Init_Continued`, `s2.asm:36206-36216`); the sidekick CPU
reads 16 entries back (`TailsCPU_Normal`, `:39284-39291`), so Tails stands still for ~17
passes after any level init. The ROM spends that window **before** the recording resumes;
the engine spends it **inside** the gap (the `stage_exit` interior return discards the gap
rows), so when comparison starts the engine's Tails is still at x=10064 where the ROM's is
at 10097 — and `Tails_Balance`'s ledge test (`ChkFloorEdge` / `cmpi.w #$C,d1`,
`:39726-39730`) resolves differently at those two positions.

---

## Grouping

| axis | fields | shape |
|---|---|---|
| 1 | — | consequence of axis 2 |
| 2 | 236 | V-blank counter phase wrong at a `stage_exit` entry → extra mod-8 spawn → early end-of-act |
| 3 | 4 | one row early; closable, fix regresses elsewhere |
| 4 | 2 | one row late; not closable at frame granularity |
| 5 | ~20 | sidekick init window spent inside a discarded `stage_exit` gap → one surplus transfer, cascading |

Axes 1 and 2 are one defect. **Axes 2 and 5 share a mechanism** — INFERRED, not
demonstrated: each axis's own root-cause measurement traces to state arriving at a
`stage_exit` segment entry out of phase — the V-blank counter in axis 2, the sidekick's
position within its init window in axis 5 — and both entries go through the
interior-return branch that discards its gap rows instead of walking them. No single
change has yet been shown to move both, and the one candidate that would have (below)
aborted before comparing either axis, so it neither confirms nor refutes the grouping.

**The obvious fix for that shared mechanism has been built and measured, and it is a net
regression. Do not propose it.** Extending the **census walk** to interior returns is
budget-exact (census 173, `framesConsumed` 1, no overrun, both path contracts preserved) and
would in principle set both phases from recorded ROM scheduling. Measured, it does **not**
close axes 2 or 5 — **it makes them unobservable**: the chain aborts on a `seg3`
special-stage DPLC walk-failure and produces trace reports for `seg0`–`seg3` only, so
segments 4–11 and three gap transitions are never compared. Both axes vanish from the report
by comparator starvation, with no field proven fixed.

What it opens instead: segment 2 at **58,355** errors (frame 1, `sidekick_x` 0x0DDE vs
0x0DDD) plus a new gap axis at `seg2_ehz1 → ss_2`. A per-field probe shows the divergence is
**not** sidekick-confined — of 106 distinct failing fields, the sidekick carries 28,229
errors and the dynamic-art ledger ~29,000, but genuine player and global physics diverge too:
`y` (141 errors, first frame 1159), `rings` (618, f2759), `camera_y` (169, f1159). An earlier
claim that the player stayed "clean to frame 52" was a misreading — frame 52 is a
`queue.s2_nemesis_plc` field, not player physics.

Pairing the walk with the title-card pass-placement change does not rescue it: segment 2
stays at exactly 58,355, the `seg3` abort persists, and it additionally breaks a
currently-clean gap (`ss → seg2_ehz1`, `edge[0].movie_logical_frame` expected 10308 actual
10269), taking the run from 3 axes to 4.

**Consequence for anyone attacking axes 2 and 5:** the one-pass sidekick deficit is a
*prerequisite* for the walk, not a consequence of it. It must be closed with the walk
unapplied. And note what the per-field probe implies: the divergence under the walk is not
confined to the state the two axes name, so the discarded gap is currently protecting
*more* engine state than the two known phases — spending those rows correctly requires
more than fixing the counter and the init window.

Axis 3 is a different family: a pass-count defect inside the title-card span. Axis 4 is
the only axis with no rule-compliant route at all.

---

## The rules any fix must satisfy

These are non-negotiable project constraints, enforced by guards and review. Several were
earned by violating them this session.

1. **Model ROM state, never the trace.** No branching on zone id, route, movie-frame
   number or "known failing trace". *"ROM-default except at the return"* is still a
   carve-out.
2. **The bar is any BK2, not this BK2.** A fix must hold for a recording nobody has made
   yet; a green fixture proves only the fixture.
3. **A constant measured off a fixture is a fitted model even when every test passes.**
   Measuring is a legitimate *starting point*; the landed value must be traceable to the
   ROM routine that owns it and cited there. A value close-but-not-equal to the ROM's is
   usually absorbing an error elsewhere — chase that, don't keep the constant.
4. **Trace data is comparison-only.** The sole exception (the hardware-timing port) may
   only release readiness of a matching, already-submitted, engine-created art job. It may
   not decide *what* happens, carry gameplay values, or key on a frame index. (The
   admission census threads this: it records only *whether the ROM's main loop ran*, one
   bit per row, lengths only.)
5. **No game-name or zone carve-outs in shared runtime code** — per-game differences live
   in typed per-game rule records or existing provider/profile registries.

### Verification discipline

- **Never weaken, relax, widen, add tolerance to, exclude a field from, make advisory,
  delete or disable any assertion.** If a test pins old wrong behaviour, invert it with
  equal strength and a ROM citation.
- **Fewer axes is not better.** If an axis disappears, name the field that now matches
  and prove it was *fixed*, not *skipped*. Two false greens this session came from the
  comparator being starved of rows.
- **Align gap edges by transfer id, not list index.**
- **Run both build profiles; diff red test sets BY NAME**, not by count. A test count
  below the baseline is truncation, not improvement.
- **Serialise Maven runs** — concurrent builds share a native-library path and
  manufacture ~172 spurious link errors.
- **Label every statement MEASURED / DERIVED / INFERRED.**
- **Adversarial verification defaults to REFUTED**, and reproduces control *and* after.
- **Refuting the brief's premise counts as success.** Across ~39 rounds every brief's
  central premise was refuted, and every refutation was correct.

### Failure modes discovered (the expensive ones)

- **Tautological checks.** A ring-write-vs-CPU-pass count returned 1:1 — but the path
  emits exactly one write per pass *by construction*, so it could not have disagreed.
  **Ask what result would have falsified a check before trusting it.**
- **Published arithmetic errors.** "Engine follow delay 16 vs ROM 17" was written up as a
  defect; the ROM's ring index increments *after* the write, so the read is −16 and the
  engine was already correct.
- **Mislabelled probe output.** A probe printed a live value under a delayed-value column
  heading, producing an apparent impossible state. **A probe's column labels are not
  measurements** — when a probe shows the engine doing something structurally impossible,
  suspect the probe first.
- **Approximations that agree often enough to look like models.** The census release
  anchor is right on 20 of 27 seams by coincidence (axis 4, point 3).
- **A reviewer inherits the write-up's errors at full strength.** This document's own
  "player clean to frame 52" summary line was a misreading — frame 52 is a
  `queue.s2_nemesis_plc` field, not player physics — and a reviewer applying the
  MEASURED/DERIVED/INFERRED discipline rigorously to their *own* reasoning cited it without
  questioning the label, and built a recommendation on it. **A cited number is as fallible
  as a probe column.** Re-measure at the source before reasoning from a claim you inherited,
  including from this file.
- **Unreached ≠ passing.** `ss_6`/`ss_7` report no failures only because the chain aborts
  before comparing them.

### Regression fingerprints

Each wrong lever pulled this session is identifiable from its error profile alone, so a
repeat is recognised immediately:

| what was pulled | signature |
|---|---|
| V-int counter bumped arithmetically | 122,139 errors; segment 7 frame 524; `sidekick_y` |
| playable pass deleted not moved | 3 axes; segment 2 = 47,639; `sidekick_y` frame 1132 |
| engine-side art release pass | 4 axes; segment 2 = 50,679/50,811; frame 0; `dynamic_art.edges rom=[] engine=[0]` |
| leave-loop fall-through alone | 12 axes; segment 11 = 7,104 |
| production ownership released early | "lost production ownership before source closure"; cursor 38806; 2 axes |
| interior census walk alone | 3 axes (2 hidden by a `seg3` abort); segment 2 = 58,355; frame 1; `sidekick_x` 0x0DDE vs 0x0DDD; reports `seg0`–`seg3` only |
| interior census walk + pass placement | 4 axes; segment 2 = 58,355 unchanged; additionally breaks `ss → seg2_ehz1` at −39 rows |
| walk + fall-through | segment 2 = 47,802; frame 0; `sidekick_x` 0x0DDD vs 0x0DDB |
| walk budget wrong | `IllegalArgument rowsConsumed must be 0 or 1` |
| comparison shifted one row | segment 2 = 99,105; player breaks at frame 15 |

## Explanations measured and refuted — DO NOT RE-RUN

**Check any new idea against this list first.** Each entry was implemented or instrumented
and killed by measurement; the measurements live in
[the seam design note](../designs/2026-08-13-level-entry-seam-frame-costing.md).

For the interior-return residual (the one-pass sidekick deficit):

- **an extra entry pass on the special-stage return path** — the disassembly shows both
  entry paths run exactly 26 playable passes;
- **a follow-direction flip** — per-pass probe: no `FollowLeft` occurs anywhere in the
  seam;
- **position-ring refill parity** — instrumented at the write site across all seven level
  entries: strictly 1:1 with CPU passes;
- **a 16-vs-17 lookback** — arithmetic error in the write-up; the engine was already
  correct;
- **boundary adoption (comparison misaligned by one row)** — a one-row shift nearly
  doubles the residual (58,355 → 99,105) and breaks the previously row-exact player;
  the misalignment is confined to one object, which a comparison offset cannot produce;
- **the sidekick running fewer total passes** — 4235:4235 exactly (an earlier count
  suggesting otherwise was a tautology);
- **`Stand` with non-zero dx** — probe artifact (mislabelled columns).

For the V-blank clock (axis 2's phase):

- the act-advance code-path hypothesis; event-count factoring of the special-stage
  deficit; the "invented 78-frame constant" reading (both halves are ROM-derived and
  cited); the results-bonus-tally explanation of seam variance (real defect, fixed
  separately, changed nothing here);
- **bumping the counter by the gap's row count arithmetically** — produces the 122,139
  fingerprint above: the counter then disagrees with the sidekick position buffer, one
  object on two clocks. Rows must be *spent*, not added.

For axes 2 and 5 jointly:

- **extending the census walk to `stage_exit` interior returns** — the most
  attractive-looking idea in this file, and a measured net regression. It is budget-exact
  and rule-compliant, and it still aborts the chain at `seg3`, hides both axes by
  comparator starvation, opens segment 2 at 58,355 errors across 106 fields (player and
  global physics included, not just the sidekick), and adds a new gap axis. Pairing it
  with the title-card pass placement changes nothing and breaks a currently-clean gap.
  Full measurements in [Grouping](#grouping). Do not re-propose it as-is; the one-pass
  sidekick deficit (and whatever else the per-field probe shows the discarded gap is
  protecting) must be closed first, with the walk unapplied.

For axis 4: a pre-card hold constant; retuning fade duration; end-anchoring the leave
phase to the recorded segment offset (works perfectly — and breaks rule 4 three ways;
reverted); recording the submission row (round-trips the compared field).

---

## Fixes landed this session

All ROM-cited, each independently verified, none introducing a fitted constant.

| commit | fix |
|---|---|
| `edc396f5e` | `Pal_FadeToBlack`'s 22 counted V-blanks spent at the boundary |
| `8695c029e` | `LoadZoneTiles` spends one V-blank per `$1000`-byte DMA chunk |
| `a04774b31` | admission census + lag-row replay |
| `0bc7cc4be` | removed a 21-frame reveal fade the ROM never performs |
| `cd0d893a1` | player art submitted at `InitPlayers` |
| `4d51aa04f` | bonus tally drains two countdowns, finishing with the longer |
| `609361da8` | pre-level fade ordered before the title card (87 → 109 rows) |
| `2a1f9a270` | `TailsCPU_Init` returns instead of falling through to steering |
| `bad614ae6` | corrected `SS_Ctrl_Record_Buf` word expectation |
| `7c7aa5901` | corrected a wrong ROM claim in the leave-loop javadoc |

**Net:** the dominant gap went from ten mismatching fields at −93 rows to four at −1.
Segment 11: 287 → 236 errors.

---

## 2026-08-14, later — the "untimed span" framing is REFUTED for the visual path

An earlier revision of this document, and a consult built on it, framed the production
visual path's row-5200 stall as a second instance of the same *untimed straight-line span*
class as axis 4: the ROM spending real 68000 time in code with no `WaitForVint` while the
engine does it instantly, therefore the engine retiring a queued art transfer ~17 rows
early. **That is wrong, and external readers should not spend a round on it.**

The results-tail setup block **resets the DMA queue**, unconditionally, with no `fixBugs`
guard (`s2.asm`, inside the block, immediately after `Hud_Base`):

```
    clr.w   (VDP_Command_Buffer).w
    move.l  #VDP_Command_Buffer,(VDP_Command_Buffer_Slot).w
```

`Vint_Fade` (`s2.asm:1068-1071` — `Do_ControllerPal` / `Hint_counter` / `ProcessDPLC`) does
**not** call `ProcessDMAQueue`, so a transfer queued before `Pal_FadeToWhite` survives all
22 fade rows untouched and is then **discarded** by that clear. `ProcessDMAQueue`
(`s2.asm:1770+`) reads `move.w (a1)+,d0 / beq.s .done` — on a zero first word it issues
nothing at all.

**So the ROM never completes that transfer.** The recorded "ROM still outstanding at row
5200" is the *recorder's own ledger* holding a transfer the hardware never performed. This
is the recorder-fiction pattern the `trace-replay-bug-fixing` skill already documents for
the special-stage **entry**. The results site is the same defect at the other end of the
special stage — but **the two sites reach it by different mechanisms, and conflating them
will misdirect a fix:**

- **Results tail: the pair is unguarded.** No `if`/`else`/`endif` appears anywhere in the
  surrounding block; the shipped ROM executes it. Safe to model directly.
- **Entry: the explicit pair is inside `if fixBugs`.** Since all three disassemblies build
  at `fixBugs = 0`, **the shipped ROM never executes those two instructions at entry.** The
  queue still ends up zeroed, but by the *excessive `SS_Shared_RAM` clearRAM overrunning
  into* `VDP_Command_Buffer` — exactly what the disassembly's own annotation says ("the
  excessive `SS_Shared_RAM` clear sets `VDP_Command_Buffer` to 0, just like the below
  code"). Modelling the explicit clear at the entry site would be **taking the FixBugs
  branch**, which the hard rules forbid; the accurate model is the overrunning clear, and
  whatever else that clear tramples.

The pair appears at six sites in `s2.asm`. Guard-checked, all six (MEASURED — each
`clr.w (VDP_Command_Buffer).w` line located, then its nearest preceding
`if`/`else`/`endif` read):

| `s2.asm` line | guard | shipped ROM (`fixBugs = 0`) executes it? |
|---|---|---|
| 4857 | none (preceding `endif` at 4817) | **yes** |
| **6609** | **`if fixBugs` at 6604** | **NO** |
| 6759 (results tail) | none | **yes** |
| **10342** | **`if fixBugs` at 10332** | **NO** |
| 10766 | none (preceding `endif` at 10765) | **yes** |
| 11737 | none | **yes** |

**Two of six are `fixBugs`-guarded**, so "six sites" overstates the shipped ROM's discards
by a third. Only 4857, 6759, 10766 and 11737 are real.

Site 10342 is worth reading for its own sake: the disassembly documents the bug the fix
*would* have prevented — after a Game Over in HTZ, `Dynamic_HTZ`'s queued cloud art
transfers late over Tails' Continue art and corrupts it. **At `fixBugs = 0` that corruption
is live shipped behaviour**, so it is something the engine should reproduce, not avoid.

**Consequences, and they are the useful part of this finding:**

- **The v5 hardware-timing port cannot and must not cover this.** Delaying engine readiness
  by ~17 rows would model recorder bookkeeping, not hardware, and would go green against a
  lifecycle that never happened. Independently, the port's eligibility gate fails on its own
  terms: the design note requires "a readiness value polled by ordinary main-loop code" and
  a main loop that "can continue while that value remains pending" — the setup block
  contains no loop, and nothing in S2 polls `VDP_Command_Buffer`. The design note further
  names VDP transfer fences as explicitly **non-authoritative** inventory candidates, and
  rule 4 names S2's permitted pipeline as **DPLC** — `ProcessDPLC` writes patterns straight
  to `VDP_control_port` and never calls `QueueDMATransfer`, so this is not even the same
  mechanism.
- **Cycle-level span costing is not justified by this frontier.** The second instance that
  was supposed to move it from "defer" to "do" is not an instance.
- **Axis 4 should be re-examined against this pattern before any span-costing work.** Its
  "masked span" framing was the model this one was built from, and KD 28 rests on a
  pattern-match that may now fall. Whichever way it goes, the finding must be a *positive*
  disassembly citation — either the masked span is real elapsed work (cite the path and the
  **absence** of a discard) or the submission is discarded (cite the discard site **and its
  guard status**). "Looks like the same pattern" is not a finding.
- **There is a legal engine-side fix here, and it is not comparator work.** If the ROM
  discards queued work at these sites and the engine *performs* it, the engine is wrong on
  its own terms — it transfers something the hardware never did. Modelling the ROM's queue
  reset, cited to those lines, is an ordinary accuracy fix.
  **The test for legality: would you land it if the trace did not exist?** For the discard,
  yes. For anything whose only justification is reconciling with a known-bad stream, no.
- **"Leave it red" has an unpriced cost.** The stall at row 5200 means rows 5201–5681 are
  never compared at all: a documented red carries one known defect *and silences 481 rows
  of real signal*, behind which any genuine regression hides. If a suppression is used
  instead, it must have teeth — scoped to the mechanism (the specific ledger entry for a
  transfer the engine can prove is discarded per the cited ROM reset, not a row range and
  not a field blanket), documented as a **recorder** defect with the regeneration
  obligation, and **asserting that the defect is present** so a regenerated fixture fails
  loudly and forces the suppression's removal rather than silently eating a corrected
  stream. Green-by-projection is dishonest only when it is silent and unbounded.
- **The durable fix is recorder-side:** its ledger must model queue discard. Fixture
  regeneration is separately authorised and has not been requested; the recorder defect,
  the six-site guard sweep, and the regeneration request should go up as one package.

### 2026-08-14, later still — the discard is not a ledger problem at all

Three semantics options for modelling the ROM's discard were prototyped and measured
(one build, arm-selected at runtime, so the scaffolding is identical across arms). The
measurement invalidated the question.

**The engine applies dynamic art at SUBMISSION, not at queue drain.** MEASURED:
`DynamicArtDecisionOwner.observe()` calls `lifecycle.observePlayerDplc(...)` and then,
*synchronously in the same call*, `renderer.applyRuntimeArtUpdate(...)` →
`patternBank.consumeRuntimeArtState(...)`. The tiles are written there and then. The ROM
writes nothing at submission — `LoadSonicDynPLC` only calls `QueueDMATransfer`
(`s2.asm:38858`); the VRAM write happens later, inside `ProcessDMAQueue`
(`s2.asm:1770-1791`).

So **no ledger-side option can stop the engine performing a transfer the ROM discards.**
All three options change only what is *recorded*; the visible accuracy defect — art
appearing that the hardware never transferred — survives every one of them untouched.
The real fix is to defer art application to the drain, which is a materially larger change
than any edge-kind decision. Any future round must start there, not at the ledger.

**Measured blast radius, for the record** (baseline 843 / 9F / 56E / 4S, 65 red):

- **Option 3** (stop performing, keep the stamp) run as a **control**: byte-identical to
  baseline in every respect, red set identical by name, all 5 chain axes verbatim. That is
  what proves the scaffolding inert and options 1/2's deltas attributable to the discard.
  It is also, independently, the option rejected as illegal — an engine stamping
  `completed` on work it did not do is a lie in its own telemetry.
- **Options 1 and 2** (silent removal; distinct `discarded` kind): both **843 / 10F / 56E /
  4S, 66 red**, red-set diff `+ TestS2EhzHalfpipeRoundTripChain` and nothing else. None of
  the other 29 blast-radius classes moved.
- **Both cause comparator starvation on the chain.** `TestS2CompleteEmeraldRunChain` stops
  reporting 5 axes and aborts earlier on a DPLC divergence in special-stage segment 1.
  Segment 11's 236, the cursor walk failure, and all three gap axes stop being measured —
  the same failure shape the DO-NOT-RE-RUN list records for the census walk. **Fewer axes
  is not better.**

**Correction to this document's own frontier claim.** Earlier revisions state that
`TestS2CompleteEmeraldVisualRun` "stalls at segment 0/1 row 5200". **That is stale.** The
class is pinned to `VisualRunReplayHarness.stopAfterSegmentBody(0)` and asserts
`sharedCursor == 4479`, `currentSegmentIndex == 0`. It is **GREEN**, at baseline and under
all three options. The row-5200 stall was a state of an earlier commit; the visual path is
not currently a live frontier, and no round should be briefed as though it were.

Minor citation fix: `ProcessDMAQueue` is `s2.asm:1770-1791`, not `:1772-1790`.

`known-discrepancies` entry 28 remains accurate as written for axis 4; nothing landed.

## Open decision

The chain cannot go green while axis 4 is honestly reported. Options on the table, each
requiring explicit authorisation:

1. **Excuse `movie_logical_frame`** at seams where the submission row falls inside a
   masked span. Chain goes green; adds a second instance of the papering-over this
   session found in `TestS1GhzMazeRoundTripChain` (:29-70), which is probably hiding the
   same class of defect.
2. **Cycle-level modelling** of `BuildSprites`/`AniArt_Load`. Closes it properly; is a
   partial 68000 cycle emulator, and collides with the project's current S3K priority.
3. **Defer with the axis honestly red.** KD 28 already records the limitation; the chain
   stays a truthful ledger of a characterised, bounded defect (2 fields, ±1 row) until
   the level-load frame-costing machinery is built for its own sake — which the seam
   design note argues S3K seam accuracy will eventually want anyway. Prerequisite worth
   landing first either way: the ROM-derived anchor improvement (a lag run of length ≥ 2
   cannot be inside the leave loop), which corrects the latent 8–47-row errors at
   `ss_6`/`ss_7` once the chain can reach them.
