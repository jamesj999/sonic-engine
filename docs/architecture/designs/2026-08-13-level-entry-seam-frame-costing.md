# Level-entry seam frame costing

**Status: REOPENED 2026-08-13 by direct measurement. The verdict below ("abandon; no
candidate throughput of any kind") is WRONG, and so is the handover audit that repeats it.**
The 63 residual rows are **`Vint_Lag` frames** — the ROM's own name for them — and the
emulator records them. They are neither incomputable nor a decompression rate: they are
main-loop-admission outcomes, exactly the contract-1 disposition the cross-game timing
contract already assigns to "long synchronous decompression or level initialization".
See [the measurement section](#2026-08-13-measured-the-63-rows-are-vint_lag-frames) at the
foot of this note. Everything between here and there is retained for its ROM citations and
its correctly-ruled-out shortcuts; its *conclusion* is superseded.

**Older status line (superseded):** diagnosed; blocked on a **capture-side** change, not an
engine one. The
original framing of this note (that it needed a suspendable level-load state machine) was
**refuted by the feasibility pass below** — the ordering is already correct and the only
missing quantity is the pre-card span's duration, for which no permitted source exists in
the current fixtures.

**Date:** 2026-08-13

## Summary

The engine's level-entry seam runs its phases in the wrong order relative to the ROM. The
gap between two recorded segments is the correct *length*, but the engine spends it
front-loaded: it runs the entire title card first and then idles. The ROM fades, clears
and decompresses first, and shows the card last.

~~Correcting this requires giving the level load a **frame cost**, which today it cannot
have.~~ **Superseded.** The seam is already split at one boundary and the ordering is
already load-then-card; see the feasibility pass. What is missing is only *how long* the
pre-card phase should take, and that duration has no rule-compliant source today. Closing
it needs the **recorder** to capture the S2 load span — a capture-side change, legitimate
under the project's rules but requiring a fixture regeneration.

## What was measured

For the level→level `level_advance` gap of `TestS2CompleteEmeraldRunChain`:

| | rows spent in the gap |
|---|---|
| recorded | 171 |
| engine | 170 |

The length is already right. `destinationReady`
(`TraceRunPlaybackCoordinator.java:396-399`) refuses admission until the shared BK2 cursor
reaches the destination's `bk2_frame_offset`, which pins it.

The *composition* differs:

| | order inside the gap |
|---|---|
| **engine** | `TITLE_CARD` for 78 rows, then 92 rows idling in `LEVEL` |
| **ROM** | fade → interrupts-off `ClearScreen`/`LoadTitleCard` → `Level_ClrRam` → level art decompression (~93 rows) → *then* `Level_TtlCard` |

ROM path: `Level:` at `s2.asm:4757-4758` (the `bset #GameModeFlag_TitleCard` that ends the
recorder's segment), `Pal_FadeToBlack` call site `:4765` and routine `:3370-3383`
(`move.w #$15,d4` + `dbf` = 22 counted `WaitForVint` iterations), the interrupts-off window
`:4767-4770`, `Level_ClrRam` `:4806`, and `Level_TtlCard` `:4914-4924`.

Consequence: the engine's title-card art edges are stamped **93 rows early**, which is
exactly the `delta=93` on every failing `run_gap.edge[N].movie_logical_frame`. Four of the
five failing axis slots on that test trace to this single defect.

## Why it cannot be fixed cheaply

> **This section's central claim is WRONG and is retained only so the error is not
> repeated.** It asserted that the title card necessarily begins at gap row 0 because
> `InitStep` carries no frame cost, and that ROM ordering therefore requires a suspendable
> cross-frame state machine across all three games. The feasibility pass below measured
> otherwise: the card is raised by a *request flag* consumed on a later frame, so the seam
> is **already** split at one boundary and the ordering is **already** load-then-card.
> Anyone building this should start from `consumeTitleCardRequest()`, not from `InitStep`.

~~The engine executes all 20 level-init steps synchronously in one loop
(`LevelManager.java:385-394`), so the title card necessarily begins at gap row 0.~~
~~Giving the seam ROM ordering therefore means converting the shared level load into a
suspendable cross-frame state machine.~~

The shortcuts below remain correctly ruled out, and are the real obstacle — both are
attempts to supply the missing *duration*:

- **A pre-card hold of 22 rows** (the `Pal_FadeToBlack` count). This is a fitted constant
  in ROM costume. The remaining ~70 rows are interrupts-off screen work plus Kosinski
  level-art decompression, which is *payload-dependent* — so any fixed boundary offset is
  wrong for a different act, and wrong for any BK2 nobody has recorded yet.
- **Retuning `FadeManager.FADE_DURATION`.** Investigated and rejected: fade duration and
  colour ramp are genuinely separate quantities and `FadeManager.java:62-66` already
  documents both correctly (22 ROM V-int iterations vs 21 colour steps). Nothing there
  needs changing.

Note also that S1's currently-green gap edges deliberately absorb 34–40 un-timed rows as
padding (`TraceRunPlaybackCoordinator.java:382-395`). A partial implementation would
disturb them.

## Things ruled out, so they are not retried

- **The gap is too short.** No — 170 against 171. Do not lengthen it.
- **The 78 is an invented duration constant.** No. Both halves are ROM-derived and cited in
  place: 52 rows of PLC drain at `move.w #6,(Plc_FramePatternsLeft).w` (`s2.asm:2202-2213`)
  over `PlrList_Ehz1` + `PlrList_Std2` with the ROM's per-entry `ceil(patterns/6)`
  quantisation, and 26 rows of `LEAVE_*_PASSES` (`TitleCardManager.java:78-84`) cited to the
  leave loop at `s2.asm:5060-5066`.
- **The 169–199 gap variance comes from the results bonus tally.** It cannot — the tally
  runs inside `Level_MainLoop` while `Game_Mode` is still `$0C`, and the capture finalises a
  segment at the first `$8C` frame and re-arms at the first `$0C` (`Level_StartGame`,
  `s2.asm:5084`). The variance is payload-dependent un-timed load cost.
- **Bumping the V-int counter by the gap's movie-row count.** This is the trap, documented
  at length in [docs/status/known-discrepancies.md](../../status/known-discrepancies.md).
  `TraceRunReplayWalker.interLevelVblankBudget` already computes the correct number and is
  gated off for S2; enabling it is one line and produces a byte-identical catastrophe
  (122,139 errors at segment 7 frame 524 on `sidekick_y`) because the counter then disagrees
  with the sidekick position-record buffer — one object on two clocks.

## A smaller shape worth one feasibility pass

Before committing to the full state machine, one variant deserves an honest look because it
threads the existing rules rather than working around them.

The seam's un-timed cost is **art decompression** — and art-decompression readiness is
precisely what the documented hardware-timing sidecar is permitted to delay
([the cross-game timing contract](2026-07-27-cross-game-hardware-timing-trace-contract.md),
hard rule 4). If the seam were split into **two or three coarse ROM-ordered phases** —
pre-card load work, then card — with the card phase gated on readiness of the engine's *own
submitted* load jobs, and that readiness timed by the sidecar, then the **ordering** comes
from ROM structure and the **duration** from the sanctioned timing port. No per-`InitStep`
frame costs and no 20-step state machine: only the seam itself becomes two suspendable
phases.

This may still founder on the same "init runs synchronously in one loop" blocker, in which
case it collapses back to the full job.

### 2026-08-13 feasibility pass: attempted, and it does not thread the rules

The pass was run and landed no code. Three findings, in increasing order of finality.

**The ordering is already correct; only the duration is missing.** The engine does not run
the card before the load. `LevelManager.loadLevel` completes every init step, and the card
is raised by a *request flag* consumed on a later frame update
(`GameLoop.java:1645-1653`, `presentPendingTitleCardDuringSuppressedRunRow`
`GameLoop.java:914-921`, both via `levelManager.consumeTitleCardRequest()`). The seam is
therefore *already* split at exactly one boundary, and the "two coarse ROM-ordered phases"
shape needs no new suspension point at all — holding the request consumption is a few
lines. Nothing here is blocked by `InitStep` lacking a frame-cost concept. The entire
93-row delta is that the load phase costs **zero** frames, so the variant reduces, with no
residue, to "how many frames does the pre-card phase cost" — the one question the fitted
constant is forbidden to answer.

**The sanctioned timing port has no data for S1 or S2, and cannot acquire any within the
existing fixtures.** `hardware_timing.jsonl` is emitted only by the S3K output set
(`tools/bizhawk-headless/src/Program.cs:25-38`: `TraceOutputFileNames` is physics/aux/
metadata, `S3kTraceOutputFileNames` adds the timing stream), and the recorder defines
exactly two kinds, `kos_module_queue` and `kos_decompression_queue`
(`HardwareTimingEventEngine.cs:18,21`). Of the 202 `hardware_timing*` files under
`src/test/resources/traces`, **zero** are under `s1/` or `s2/`. Using the port for the S2
emerald run would require extending the S2 recorder, minting a new event kind, and
republishing the fixture.

**Even with such a stream, the pre-card span fails contract 3's preconditions.** Between
`Level:` and `Level_TtlCard` the ROM performs no `WaitForVint` other than the 22 inside
`Pal_FadeToBlack`; `ClearScreen`/`LoadTitleCard` run under `move #$2700,sr`
(`s2.asm:4767-4770`) and everything through `Level_ClrRam`, the VDP setup, `Level_LoadPal`
and `Level_PlayBgm` (`:4806-4913`) runs inline. The main loop does **not** continue while
this work is pending, and no readiness gate for it is polled. The one readiness gate that
does exist — `tst.l (Plc_Buffer).w`, `s2.asm:4919` — is polled *inside* `Level_TtlCard`,
i.e. after the card object already exists, so it cannot gate card creation; it is the
52-row drain the engine already models. By the contract's own governing principle
("record the smallest scheduling outcome observable to the game… a new completion event is
reserved for work that remains pending while the main loop continues"), this span is
contract 1 (lag) / contract 2 (execution phase) work, not contract 3. Recording it as a
completion event would be recording the hardware cause, which the contract forbids.

Measuring host decompression time instead is closed by the contract's goals: "keep replay
independent of host decompression, rendering, I/O, and CPU speed."

**Verdict:** the smaller variant is not viable. The blocker is not the state machine — the
suspension point already exists — it is that no permitted source can supply the pre-card
duration. The recommendation below stands unchanged, and whoever builds this deliberately
should start from `consumeTitleCardRequest()`, not from `InitStep`.

## Recommendation

**Do not build this to make a trace test green.** The payoff is the tail of one S2 test,
and the cost is a cross-cutting change to shared level loading — the case
[CLAUDE.md](../../../CLAUDE.md) describes as broad architecture migration that must not
displace playable S3K progress.

Build it deliberately, as a designed feature, when it blocks a release gate or an S3K route.
The ROM's load-then-card ordering is **shared structure**, not an S2 nicety, so S3K seam
accuracy is likely to want the same machinery eventually. At that point the S2 emerald chain
goes green as a side effect — which is the correct order of causation: the architecture pulls
the test green, rather than the test pulling the architecture in.

## 2026-08-13, later: the fade is modelled; the residual is post-card, not pre-card

`edc396f5e` landed the ROM-derived half. **Delta 93 → 71.**

**What was wrong.** The chain's boundary wait stepped the engine through
`Pal_FadeToBlack` with the shared movie clock *frozen*. Those iterations are counted
V-blanks in the ROM — `move.w #$15,d4` with `bsr.w WaitForVint` per pass
(`s2.asm:3370-3383`, called from `Level:` at `:4765`) — and the card is not created until
`:4912`, so the fade consumes 22 movie rows before the card exists and the harness
consumed none. Instrumented, not reasoned: a per-frame probe showed 22 consecutive
iterations at frozen cursor 32761 with fade active, pinned by a stack-filtered probe to
the boundary-wait stepper rather than the gap loop. The fix introduces no constant — the
predicate is the fade's own liveness.

**A required gate, worth knowing about.** Without a source-coverage gate the same advance
reports **4** axes instead of 5 and segment 11 vanishes — because the cursor runs past rows
the `seg7_ehz2` source comparator has not consumed (it stops at 3970 of 3997), trading the
walk-failure for a non-atomic-publish error. That is hiding a failure, not fixing one.

**The card animation is NOT where the remaining rows are — this note previously implied it
was.** `Obj34`'s slide is fully derivable: zone name `xstart` = `screen_width+128` = `0x240`,
`xstop` = centred `0x120`, `anim_frame_duration` = `$1B`, and
`Obj34_MoveTowardsTargetPosition` steps `moveq #$10,d0` = 16px/pass — so 1 init pass + 26
`Obj34_Wait` skips + 18 moves = **45 iterations** to reach `titlecard_x_target`
(`s2.asm:27326-27510`). That is *below* EHZ's 52-row PLC drain, so implementing the ROM's
`x == target AND Plc_Buffer empty` exit (`s2.asm:4917-4920`) changes this seam by **zero**
rows. It would only help zones whose drain is under 45 (WFZ 28, ARZ 36, SCZ 40).

**Where the 71 actually are.** The gap's edge ledger shows edges 4–11 are the *new* level's
player art, submitted after `InitPlayers` (`s2.asm:4946`) and stamped across the 25-pass
leave loop (`:5060-5066`). `InitPlayers` sits behind `LoadZoneTiles`, `loadZoneBlockMaps`,
`LoadAnimatedBlocks`, `DrawInitialBG`, `ConvertCollisionArray` and `LoadCollisionIndexes`
(`:4939-4945`). ~~none of which contain a `WaitForVint`~~ — **CORRECTED 2026-08-13:
`LoadZoneTiles` DOES contain one** (`s2.asm:6519`, inside the `dbf` loop), so it costs
`floor(size/$1000)+1` V-blanks. The other five do not. See the derivation section below. So the residual decomposes as
~8 rows of pre-card interrupts-off `ClearScreen`/`LoadTitleCard`/`Level_ClrRam`
(`:4767-4806`) plus ~61 rows of post-card, payload-dependent level-art load.

Neither span has a counted ROM form. They stay an un-timed residual rather than a fitted
number — the same class the S1 path already absorbs as 34–40 rows
(`TraceRunPlaybackCoordinator.java:382-395`). Closing them would need the capture-side
change described above, which measurement shows is practical (recorder builds in 3s,
whole-movie replay 3m20s, fixture 34M) but is not required for any currently failing field
beyond this residual.

## 2026-08-13, final: end-anchoring is the right model and cannot be implemented legally

The remaining residual is a **phase-anchoring** error, confirmed by measurement across all
20 level-destination seams (lengths 156–198):

- title-card art sits at a fixed **33 rows from the seam START**;
- player art sits at a fixed **26–27 rows from the seam END** (len−26 on 17 of 20).

That len−26 is the ROM's 25-pass leave loop (`s2.asm:5060-5066`), which
`TitleCardManager`'s `LEAVE_*` passes already model correctly. Both ends are fixed; only
the payload-dependent middle varies. So anchoring the engine's leave phase to the seam
**end** would absorb the middle automatically, with no duration and no constant.

**It was implemented, and it works — and it is a hard-rule-4 violation.** Measured:
the eight player-art edges went **71 → 1**, segment 11 went **287 → 236**, and the
source-comparator frontier advanced 3970 → 3977, with all five axes still reported.

It is nonetheless **rejected and reverted**, because the anchor it needs is
`next.segment().bk2FrameOffset()` — a recorded BK2 frame index. Piping that into
`TitleCardManager` to drive a state transition breaks rule 4 three ways at once: it calls
a gameplay owner, it drives gameplay state, and it keys on a frame index. The
hardware-timing port does not cover it either — that exception may only release readiness
of a matching production-submitted ROM-backed art job.

**Why there is no legal variant.** A legitimate predicate must read *engine* state, as the
landed fade fix does (`FadeManager.isActive()`). But the seam's end is determined by the
duration of the ROM's level-art load, and **the engine's load is instantaneous** — so no
engine-side quantity corresponds to it. The anchor exists only in the recording.

That closes the question: the last ~71 rows cannot be recovered by anchoring, by capture,
or by derivation. They require the engine's level load to actually take time — the
frame-costed seam this document describes. The earlier escalation was correct, and is now
proven rather than asserted.

**One more rejected fake success.** Anchoring at exactly `LEAVE_PLAYABLE_PASSES` (without
the loop's fall-through pass) puts those edges at delta **0** — the acceptance target hit
exactly — but the run then collapses to **2 axes**, losing segment 11 and both
special-stage gap axes, because the walk dies earlier with "lost production ownership
before source closure". Hitting the target number while hiding three axes is not progress.

## 2026-08-13: blind rate derivation — ABANDON, with one salvage

Every rate below was read from the disassembly and written down before any comparison.
*Disclosure:* the seam lengths were already stated in this note, so the derivation was not
blind in the strict sense. Evidence against fitting: the one new derivable component lands
at **8 rows**, an order of magnitude below the 63 it would have needed to "explain" the
residual. A fitter would not have produced 8.

| component | throughput + citation | frames | bucket |
|---|---|---|---|
| `Pal_FadeToBlack` | `move.w #$15,d4` + `dbf`, one `WaitForVint`/pass (`s2.asm:3370-3383`) | 22 | ROM loop — **landed** `edc396f5e` |
| `Level_TtlCard` PLC drain | `move.w #6,(Plc_FramePatternsLeft).w` (`:2202-2213`), per-entry `ceil(patterns/6)` | 52 (EHZ; zone-keyed 28–81) | ROM immediate — **already exact** |
| **`LoadZoneTiles`** | **counted `dbf` with one `WaitForVint` per `$1000`-byte DMA chunk (`s2.asm:6505-6523`, `bsr.w WaitForVint` at `:6519`); `rol.w #4,d7 / andi.w #$F,d7` ⇒ `floor(size/$1000)`, loop runs `d7+1`** | **EHZ 8, MCZ 8, ARZ 8, MTZ/WFZ/HTZ/CNZ/CPZ/DEZ/SCZ 7, OOZ 6** | **ROM loop — NEW, derivable** |
| leave loop | `s2.asm:5060-5066` | 26 | ROM loop — already modelled |
| `ClearScreen` DMA fill | VDP DMA fill rate (hardware reference, not opened in-repo) | 0.71 | hardware rate — sub-frame |
| `LoadTitleCard` `NemDec`, `Level_ClrRam`, `loadZoneBlockMaps`, `LoadAnimatedBlocks`, `DrawInitialBG`, `ConvertCollisionArray`, `LoadCollisionIndexes` | **68000 cycle-bound; no ROM quantum, no hardware rate, no `WaitForVint`** | **not derivable** | **CPU-bound** |

**Derived total (EHZ): 22 + 52 + 8 + 26 = 108. Recorded seam: 171. Shortfall 63.**

That is not a near miss with a rate to nudge — **the 63 rows have no candidate throughput of
any kind.** They are entirely CPU-cycle-bound decode and array conversion, with no
`WaitForVint` and no polled readiness gate between `Level:` and the leave loop other than
the `Plc_Buffer` test already modelled.

### The sidecar cannot take them either

Verified this round: `hardware_timing.jsonl` is emitted only by the S3K path
(`tools/bizhawk-headless/src/Program.cs:25-38`); kinds are exactly `kos_module_queue` and
`kos_decompression_queue` (`HardwareTimingEventEngine.cs:18,21`); of 202 such files under
`src/test/resources/traces`, **zero** are under `s1/` or `s2/`.

More decisively, these jobs **fail contract 3 on preconditions**: the ROM exposes no
readiness value for `KosDec`/`NemDec` here — each is a synchronous `bsr` inside `Level:`
with no queue and no count word — and the main loop is not running at all during them
(interrupts masked at `s2.asm:4767`). The contract's own inventory pre-decides this twice:
*"Long synchronous decompression or level initialization … Lag … S1/S2 substantially
covered"* and *"Nemesis, Enigma, Saxman, raw map decompression … Lag … **No codec-specific
trace authority**"*.

So this is **not** a capture-side regeneration item — minting an S2 event kind here would
claim authority the contract explicitly denies — and emphatically not a licence to invent a
decompression rate. The authorised regeneration cannot be spent on this.

**Verdict: the frame-costed seam model is abandoned.** One good component may not carry a
bad one. The `LoadZoneTiles` V-int cost is landed on its own merits as ROM accuracy, closing
8 of the 71; the remaining 63 stay an un-timed residual, the same class S1 already absorbs
as 34–40 rows.

## 2026-08-13, measured: the 63 rows are `Vint_Lag` frames

The section above concludes the residual has "no candidate throughput of any kind". That is
false, and the error was reasoning about the seam instead of observing it. A throwaway probe
was added to the native S2 run recorder — logging `(frame, Game_Mode, IGpgxHost.IsLagged)`
for every physical emulator frame — and run against the canonical emerald BK2. It is not
committed; it existed only to answer this question.

### The seam, decomposed

`seg4_ehz1` closes at BK2 row 32760; `seg5_ehz2` opens at 32931. Every frame between reads
`Game_Mode = $8C`. Run-length encoding the probe over that span, and attributing each run to
the ROM:

| BK2 rows | n | lag | ROM span |
|---|---|---|---|
| 32761–32783 | 23 | no | `Pal_FadeToBlack`, `move.w #$15,d4` + `WaitForVint`/pass (`s2.asm:3370-3383`) |
| 32784–32794 | **11** | **YES** | `move #$2700,sr`; `ClearScreen`; `LoadTitleCard` (`s2.asm:4767-4770`) |
| 32795–32847 | 53 | no | `Level_TtlCard` loop, one `WaitForVint`/pass, PLC drain (`s2.asm:4914-4920`) |
| 32848–32861 | **14** | **YES** | `Hud_Base`, `PalLoad_ForFade`, `LevelSizeLoad`, `DeformBgLayer`, `Horiz_Scroll_Buf` clear (`s2.asm:4923-4936`) |
| 32862–32869 | 8 | no | `LoadZoneTiles`, one `WaitForVint` per `$1000`-byte chunk (`s2.asm:6519`) |
| 32870–32906 | **37** | **YES** | `loadZoneBlockMaps`, `LoadAnimatedBlocks`, `DrawInitialBG`, `ConvertCollisionArray`, `LoadCollisionIndexes`, `WaterEffects`, `InitPlayers` (`s2.asm:4938-4946`) |
| 32907–32930 | 24 | no | leave loop, `VintID_TitleCard` + `WaitForVint`/pass (`s2.asm:5060-5066`) |

**Lag frames: 11 + 14 + 37 = 62. The residual this note abandoned is 63.**

Two independent cross-checks fall out of the same table and were not used to build it:

- the 8 non-lag rows at 32862–32869 are exactly the `LoadZoneTiles` count `8695c029e`
  derived for EHZ from `ArtTile_ArtKos_NumTiles_*`, arrived at from the ROM with no
  reference to any recording;
- the 53 non-lag rows at 32795–32847 are the 52-row PLC drain the engine already models.

The failing edges land on the run boundaries, not near them — and this is checkable without
rerunning anything, straight out of the committed
`run_manifest.json` `dynamic_art_gap_transitions`, whose `movie_logical_frame` is in the same
absolute BK2 coordinates as the probe:

| edge | frame | phase/owner | falls on |
|---|---|---|---|
| 0,1 | 32760 | submitted `tails` | last recorded gameplay row |
| 2,3 | **32794** | completed | **last frame of the 11-frame masked `LoadTitleCard` lag run** |
| 4,5 | **32905** | submitted `sonic`/`tails` | **penultimate frame of the 37-frame `InitPlayers` lag run** |
| 6,7 | **32906** | completed | **last frame of that lag run** |
| 8,9 | 32921/32922 | submitted/completed | inside the leave loop (non-lag) |
| 10,11 | 32929/32930 | submitted/completed | inside the leave loop (non-lag) |

`run_gap.edge[2]`/`[3]`
(title-card art) is recorded at **32794** — the last frame of the masked `LoadTitleCard`
run. `edge[4]`–`[7]` (player art, submitted by `InitPlayers`) are recorded at **32905/32906**
— the last frames of the 37-frame lag run that ends at `InitPlayers`. The lag structure does
not merely total 63; it places every edge.

### A second seam, and the cross-check that proves the probe reads ROM structure

The probe was repeated over `seg7_ehz2 -> seg8_cpz1` (61028 → 61206, length 178), a
different zone with a different art payload:

| BK2 rows | n | lag | same ROM span as above |
|---|---|---|---|
| 61029–61051 | 23 | no | `Pal_FadeToBlack` |
| 61052–61062 | **11** | **YES** | masked `ClearScreen` / `LoadTitleCard` |
| 61063–61124 | 62 | no | `Level_TtlCard` PLC drain |
| 61125–61138 | **14** | **YES** | `Hud_Base` … `Horiz_Scroll_Buf` clear |
| 61139–61145 | **7** | no | `LoadZoneTiles` |
| 61146–61181 | **36** | **YES** | `loadZoneBlockMaps` … `InitPlayers` |
| 61182–61205 | 24 | no | leave loop |

Total lag **61** against EHZ's 62. Note what varies and what does not. The two fixed-code
lag runs are **11** and **14** at both seams. The payload-dependent runs move: the PLC drain
53 → 62, the art-conversion lag run 37 → 36.

And `LoadZoneTiles` reads **8 at the EHZ seam and 7 at the CPZ seam** — which is exactly the
per-zone table `8695c029e` derived from `ArtTile_ArtKos_NumTiles_*` and
`floor(size/$1000)+1`, with no reference to any recording (EHZ 8, CPZ 7). Two independent
routes to the same two numbers is the check that the probe is reading the ROM's scheduling
and not an emulator artefact.

The manifest's edges follow the same rule at this seam: card art completes at **61062**, the
last frame of the 11-frame masked run; player art is submitted at **61180** and completes at
**61181**, the last frame of the 36-frame `InitPlayers` run. Both anchors are fixed
(`+34` from the seam start, `len-26` from its end) and the middle is payload-dependent —
**111 rows at the EHZ seam, 118 at the CPZ seam** — which is why no constant can serve and
why the middle must come from the recording as an admission outcome.

### Why the flag discriminates, from the ROM

`V_Int` branches to `Vint_Lag` whenever `Vint_routine` is zero (`s2.asm:481-484`) and writes
`VintID_Lag` back after every dispatch (`s2.asm:501`), so a routine set by `WaitForVint`
runs exactly once. Straight-line 68K code between `WaitForVint` calls therefore takes the
`Vint_Lag` path, and neither `Vint_Lag` nor its in-level branch calls `ReadJoypads`
(`s2.asm:529-583`) — whereas `Vint_TitleCard` does (`s2.asm:1005-1008`). BizHawk's
`IInputPollable.IsLagFrame` is "no controller poll this frame", so the emulator flag and the
ROM's own `Vint_Lag` classification coincide **by construction**. This is not an emulator
artefact standing in for game behaviour; it is the game's own scheduling outcome.

`VintRet` still does `addq.l #1,(Vint_runcount).w` (`s2.asm:505-506`) on the lag path, which
is what replay must advance on such a row — and is the principled form of the
`interLevelVblankBudget` trap, which advances the same counter by a frame-index arithmetic
without spending the rows.

### Which precondition actually fails

Against the contract's five lag-sufficiency preconditions
([the cross-game contract](2026-07-27-cross-game-hardware-timing-trace-contract.md), "S1/S2
lag-frame coverage audit"):

1. **raw capture includes every physical emulator frame — FAILS.** `S2RunCaptureRunner`
   `continue`s without writing a row on every frame where no segment is armed
   (`tools/bizhawk-headless/src/Recording/S2RunCaptureRunner.cs`, the `if (!state.Started)`
   arm gate), so all 170 seam frames are recorded nowhere.
2. **the lag flag distinguishes a serviced interrupt from an executed gameplay loop —
   FAILS, and independently of (1).** `S2TraceCsvWriter` writes the level `lag_counter`
   column as a literal `Hex4(0)` placeholder; measured over `seg4_ehz1`'s 1288 rows the
   column has exactly one distinct value, `0000`. S2 **special-stage** rows carry a real
   `lag` column, so the discriminator exists in the harness and is simply not wired to the
   level writer. Both failures are capture defects, not modelling impossibilities: the
   underlying flag does discriminate, as measured above.
3. **replay advances the required VInt-owned counters and queues on that row** —
   implementable; `Vint_runcount` advances (`s2.asm:505-506`), `Level_frame_counter` does
   not (the level main loop owns it).
4. **input sampling/reuse follows the game's lag path** — holds; `Vint_Lag` performs no
   `ReadJoypads`, so no controller word is republished on those rows.
5. **no ordinary main-loop routine polls a still-pending readiness value across multiple
   non-lag rows** — holds. The one polled gate in the seam is `tst.l (Plc_Buffer).w`
   (`s2.asm:4919`) inside `Level_TtlCard`, and it is already reproduced deterministically as
   the 52-row drain.

So lag **is** sufficient here, and the two failures are both in the recorder.

### What this does and does not license

The consumed quantity is one bit per physical frame: *did the main loop run*. It carries no
position, speed, object state, or any physics/aux comparison value, and it changes only
*when* engine-created work becomes ready — the sanctioned shape. It is categorically not the
reverted end-anchoring attempt, which fed a recorded **frame index**
(`next.segment().bk2FrameOffset()`) into `TitleCardManager`, a gameplay owner. A per-seam
run-length census of that bit is *observation, not authority*, which is precisely what the
superseded S1 note
([2026-08-10-s1-pre-main-loop-load-span-timing-extension.md](2026-08-10-s1-pre-main-loop-load-span-timing-extension.md))
recommended for the same problem class: "Publish the tail rows, or a movie-wide V-int
census, as recorded fixture data."

No completion event, no new hardware-timing kind, and no codec authority is involved, in
line with the contract's inventory rows for synchronous decompression.

### Remaining work, unstarted

1. Populate the S2 level `lag_counter` column from `IGpgxHost.IsLagged` instead of the
   placeholder, and emit the unarmed seam frames' admission outcome (the smallest form is a
   per-transition run-length census in `run_manifest.json`; the fuller form is rows).
2. Regenerate the S2 emerald and halfpipe fixtures, payloads compressed, `trace_schema: 5`.
3. Spend the seam rows in replay as lag rows: advance `Vint_runcount`, service the V-int
   equivalent, run no gameplay, follow the lag input path, and hold the title-card request
   consumption behind the recorded admission outcomes rather than any constant.

Nothing above was implemented this round; only the measurement was taken. Note also that the
handover's "four of five axes trace to one defect" does not reproduce at `8c9adc250`: the
`ss_4 -> seg6_ehz2` axis is a 1-row offset and the `ss_5 -> seg7_ehz2` axis is an edge-count
and content/ordering mismatch (16 vs 18), neither of which is this seam.

## 2026-08-13, implemented: the admission census lands; the seam does not close

The two capture defects above are fixed, the emerald fixture is regenerated, and replay now
spends the recorded lag rows. Measured outcome: the seam moves substantially and does not
close, for a reason the measurement makes precise.

### What landed

1. **Capture (precondition 1).** `S2RunCaptureRunner` now records
   `IGpgxHost.IsLagged` for *every* physical emulator frame, before any arm gate, indexed by
   BK2 movie row. At run end each manifest transition gains
   `gap_admission_runs`: a run-length census, alternating and starting NON-lag, over the rows
   strictly between the source segment's end and the destination segment's first recorded
   row. Only lengths are emitted — never a row index.
2. **Replay (contract 1).** `AbstractRunChainTest.admitLevelWhenReady` expands the census
   (its origin is `destinationOffset - sum`, so no recorded index is consumed) and, on a lag
   row, runs no gameplay lifecycle and only advances the object-visible V-blank counter —
   the ROM's `addq.l #1,(Vint_runcount).w` at `VintRet` (`s2.asm:505-506`), which executes on
   the lag path too, while `Level_frame_counter` (`Level_MainLoop`, `s2.asm:5092`) does not.

The regenerated capture **independently reproduces this note's hand-taken decomposition**,
which is the strongest available check that the census reads ROM scheduling:

| transition | census | lag |
|---|---|---|
| `seg4_ehz1 -> seg5_ehz2` | `[23, 11, 53, 14, 8, 37, 25]` | 62 |
| `seg7_ehz2 -> seg8_cpz1` | `[23, 11, 62, 14, 7, 36, 25]` | 61 |

The two fixed-code lag runs are 11 and 14 at both seams; `LoadZoneTiles` reads 8 at EHZ and
7 at CPZ, matching the per-zone table `8695c029e` derived from `ArtTile_ArtKos_NumTiles_*`
with no reference to any recording.

Every one of the 70 payload files in the regenerated capture is **byte-identical** to the
committed fixture; only `run_manifest.json` (the new field) and `recording_date` differ. The
census is therefore purely additive: no segment offset or row count moved.

### The measured result, both directions

| edge | expected | before | after |
|---|---|---|---|
| `run_gap.edge[2]/[3]` (title-card art) | 32794 | 32804 (**+10**) | 32815 (**+21**) |
| `run_gap.edge[4]`–`[7]` (player art) | 32905/32906 | 32842/32843 (**-63**) | 32867/32868 (**-38**) |

Segment 11 physics errors 252 → **236**; the `seg7_ehz2` walk-failure comparator cursor
3975 → 3977. The axis count stays **5** with the same five fields — no comparator was
starved, which is the failure mode this note warned about twice.

### Why it does not close, precisely

Lag insertion delays the engine's subsequent work by the cumulative lag ahead of it, and
nothing else. That is right for the player-art edges, which sit after all 62 lag frames, and
it recovered 25 of their 63 rows. It is wrong for the title-card edges, because the ROM
submits that art **inside** the 11-frame masked `LoadTitleCard` run — on a frame whose main
loop never ran — whereas the engine's load is instantaneous and its submission lands on the
next row it is allowed to run on, so those 11 rows push it further away rather than closer.

Contract 1 forbids the fix: a lag row runs no gameplay, and the cause of the missed frame is
deliberately absent. Making the level-load pipeline progress *during* the lag rows is exactly
the frame-costing of the level load this note recommended deferring — but the census now
supplies the missing input it would need, which the earlier sections concluded did not exist.

## 2026-08-13, implemented: the player art lands on `InitPlayers`, exactly

The section above ends by saying the remaining fix — making the level-load pipeline
progress *during* the lag rows — is forbidden by contract 1. That reading was too strong,
and this round tested it against the contract text rather than restating it.

Contract 1 says replay "services the ROM-equivalent interrupt work, retains/re-samples
controls according to the game's lag policy, **and does not run gameplay**". It forbids
*gameplay* on a lag row. Publishing a submission the engine already created is not
gameplay: it creates no work, calls no gameplay owner, reads no gameplay value, and changes
only *when* engine-created work becomes visible — the sanctioned shape the contract names.
No object update, physics step, or lifecycle tick runs on the released row.

### What was measured first

A throwaway probe on `DynamicArtLifecycleService.emitGapEdge` (stack-filtered, not
committed) showed the `seg4_ehz1 -> seg5_ehz2` player-art edges being submitted from
`PlayableSpriteAnimation.update` → `DynamicArtDecisionOwner.observe`, inside
`GameLoopTitleCardLifecycle`, at BK2 row **32867** — during the census's 8-row
`LoadZoneTiles` run. The ROM submits at **32905**, the last row of the following 37-row lag
run, because `Level:` reaches `InitPlayers` (`s2.asm:4946`) only after `LoadZoneTiles`,
`loadZoneBlockMaps`, `LoadAnimatedBlocks`, `DrawInitialBG`, `ConvertCollisionArray`,
`LoadCollisionIndexes` and `WaterEffects` (`:4938-4945`). The engine's load has no frame
cost, so its playables exist — and take their first art decision — while the ROM was still
loading.

### Where the run is located, and why it is not a constant

`InitPlayers` ends the census's **last non-admitted run**, and that is a ROM-structural
fact, not a measurement: everything after `InitPlayers` is the leave loop
(`s2.asm:5060-5066`), which does `WaitForVint` every pass and therefore admits the main
loop on every remaining row of the gap. So the load-completion row is
`lastNonAdmittedRow(census)` — no length, no index, no 37 and no 38 anywhere in the code.
A zone with a different payload moves that run's length and the code follows it.

### What landed

- `DynamicArtLifecycleService` gains a level-entry hold. While armed, a playable art
  decision maintains the ROM's per-owner dedup register exactly as before and still hands
  its tiles to the renderer, but stages the ledger edge instead of emitting it. The release
  submits every staged decision, in order, on the row it is called from. Nothing arms it in
  production, where the path is byte-for-byte the old one.
- `AbstractRunChainTest.admitLevelWhenReady` arms the hold when the census has a
  non-admitted run and releases it on that run's last row, after the row's lag V-int
  service. A gap that admits early releases at admission, so nothing leaks forward.

### Measured, base `0bc7cc4be` vs this change

| edge | expected | before | after |
|---|---|---|---|
| `run_gap.edge[2]/[3]` (title-card art) | 32794 | 32794 (**0**) | 32794 (**0**) |
| `run_gap.edge[4]/[5]` (player art submitted) | 32905 | 32867 (**-38**) | 32905 (**0**) |
| `run_gap.edge[6]/[7]` (player art completed) | 32906 | 32868 (**-38**) | 32906 (**0**) |
| `run_gap.edge[8]`–`[11]` (leave loop) | 32921–32930 | **-1** | **-1** |

Nothing else moved: `edge_count` still 12 (no edge vanished — edges 4-7 are compared and
match), segment 11 stays at 236 physics errors, the `seg7_ehz2` source comparator frontier
stays at 3977 of 3997, and the test still reports the same **5** axes. The two full
`-Ptrace-replay` runs, one per tree, ended on **identical 68-test red sets**.

### What is left on this seam

Only `edge[8]`–`[11]`, a uniform **-1** across the leave loop. That is a different defect
from the one this note has chased: both ends are now anchored correctly and the residual
is a single row inside the leave loop, not a payload-dependent span.

## 2026-08-13, final state: one scoped change remains, and it is not a boundary

The level_advance seam is closed except for four fields. `edge[2]`–`[7]` match the recording
exactly; `edge[8]`–`[11]` sit at a uniform **−1**.

### The cause is fully localised

**Defect A (engine, real, confirmed three times).** `updateZoneTileUpload`
(`TitleCardManager.java:693-700`) decrements-then-transitions, arming `leavePass` on the
same row that consumes a `LoadZoneTiles` `WaitForVint` (`s2.asm:6519`). The ROM cannot do
this: the playables do not exist until `InitPlayers` (`s2.asm:4946`), reached only after the
load routines at `:4940-4945`. So the engine runs **two** playable passes inside the load
span where the ROM runs **one**.

**Fixing A alone closes `edge[8]`–`[11]`** — measured, the gap axis disappears. But the
surplus row it removes resurfaces at segment 2 as a permanent +1 clock phase: 3 axes,
segment 2 at 47,639 errors, first non-camera mismatch frame 1132 `sidekick_y`
rom=0x02CB engine=0x02CC, plus a seg3 special-stage DPLC walk failure that hides the later
axes.

### Why segment 2 cannot absorb it today

Segment 2 is entered through the **stage_exit / `BoundaryEntryMode.LEVEL_MODE` interior-return
branch** (`AbstractRunChainTest.java:1171-1466`), which never reads `gapAdmissionRuns`. The
census has exactly one consumer in the tree — `expandGapAdmissionCensus` (`:3680`) via
`admitLevelWhenReady` (`:2742`), reached only from the `LEVEL_LOAD` branch (`:1562`).

Transition 1→2 **does** carry a full census — `[23, 11, 53, 14, 8, 39, 25]`, sum 173, 64 lag
rows, with the same ROM decomposition as the level_advance seam — and replay spends **zero**
movie rows on it: the interior freezes the shared cursor and pre-seeks straight to
`returnOffset` (`:1348-1351`), discarding all 173 rows.

### The remaining change, stated precisely

Make the interior return stop its pre-seek at `returnOffset - sum(census)` and walk the seam
with lag rows and the level-entry art hold, exactly as `admitLevelWhenReady` already does for
`LEVEL_LOAD`. Land it together with defect A, never separately.

**This is scoped work, not a wall.** It collides with two live contracts on that path — the
pre-seek's frame-0 input rationale (`:1322-1330`) and the `framesConsumed == 1`
adopt-opening-row contract (`:1455-1463`) — and it affects every `stage_exit` in the run,
of which the complete-emerald route has seven. That blast radius is why it was not attempted
blind at the end of a long session, not because anything blocks it.

### Regression fingerprints, so a wrong turn is recognised immediately

| what was pulled | signature |
|---|---|
| defect A alone | 3 axes; segment 2 = 47,639; `sidekick_y` frame 1132; seg3 DPLC |
| engine-side release pass | 4 axes; segment 2 = 50,679; frame 0; `dynamic_art.edges rom=[] engine=[0]` |
| leave-loop fall-through altered | 12 axes; segment 11 = 7,104 |
| ownership released early | "lost production ownership before source closure"; 2 axes; segment 11 hidden |
| V-int counter bumped | 122,139 errors; segment 7 frame 524; `sidekick_y` |

## 2026-08-13: the special-stage results tally overran, and it was a summed countdown

The note above defers the interior-return census walk (transition 1->2,
`[23, 11, 53, 14, 8, 39, 25]`, sum 173). A probe over that return measured the
engine spending materially more rows in `SPECIAL_STAGE_RESULTS` than the census
allows. The cause is upstream of the seam entirely and is now closed.

### The ROM's tally length, derived and then cross-checked

`SpecialStage`'s exit loads **two** bonus countdowns from the two players'
separate ring words -- `move.w (Ring_count).w,(Bonus_Countdown_1).w` and
`move.w (Ring_count_2P).w,(Bonus_Countdown_2).w` (`s2.asm:6784-6785`) -- plus
`Total_Bonus_Countdown` = 1000 when an emerald was won (`:6779-6781`).
`Obj6F_TallyScore` tests and decrements all three in a **single pass**
(`:28381-28392`: `subq.w #1` on each ring countdown, `subi.w #10` on the total)
and advances the routine only on the pass where all three are already zero
(`:28395-28400`). So

```
tally passes = max(Ring_count, Ring_count_2P, 1000/10) + 1
```

and the whole results loop is

```
plc drain + 18 Obj34_MoveTowardsTargetPosition steps (:27494)
          + 1 latch pass (:28243-28248, $B4)
          + 180 Obj6F_TimedDisplay passes (:28367-28371)
          + tally passes
          + 120 Obj6F_TimedDisplay passes ($78, :28399-28400)
          + 1 Obj6F_DisplayOnly pass that raises Level_Inactive_flag (:28428-28430)
```

The five special-stage segments of the emerald run confirm this from the
recording, without any of it being used to build the model. Each fixture's
tail run-length-encodes as `[1 lag, 22 non-lag, 16 lag, N non-lag]`; the 22 is
`Pal_FadeToWhite` (`move.w #$15,d4` + `dbf`, `s2.asm:3571-3581`, called at
`:6749`), the 16 is the interrupts-off screen rebuild (`:6752-6795`), and the
final run is the results loop plus the *second* `Pal_FadeToWhite` (`:6806`):

| segment | recorded final run | loop = run - 22 | P1/P2 rings | derived loop |
|---|---|---|---|---|
| `ss`   | 465 | 443 | 91 / 76  | 22 + 18+1+180+101+120+1 = 443 |
| `ss_2` | 527 | 505 | 162 / 3  | 505 |
| `ss_3` | 526 | 504 | 161 / 0  | 504 |
| `ss_4` | 531 | 509 | 166 / 17 | 509 |
| `ss_5` | 560 | 538 | 195 / 6  | 538 |

The PLC drain reads **22 at all five**, and every other term is a ROM immediate
or a ROM-derived step count. `ss` is the only segment where P2's rings are large
enough to distinguish `max` from `sum`, and it lands on `max`.

### The engine's defect

`SpecialStageResultsScreenObjectInstance` carried **one** countdown, seeded from
`Sonic2SpecialStageManager.getRingsCollected()` -- the *combined* total. A single
countdown of `P1 + P2` drained one per frame is their sum, so the tally ran
`min(P1, P2)` frames long wherever the second player had rings: **+67 rows on
`ss`** (167 against 100), +3 on `ss_2`, +17 on `ss_4`, +6 on `ss_5`, 0 on `ss_3`.
That is the overrun, and it is per-stage rather than constant -- which is why no
single number could have absorbed it.

The fix splits the countdown, latching `Ring_count` / `Ring_count_2P` at the
ROM's own copy point (`Sonic2SpecialStageProvider.resetForResults()`, immediately
before the card is created as at `s2.asm:6797`). Total score awarded is
unchanged: both shapes add `(P1 + P2) * 10 + 1000`.

Measured after the change, via a throwaway probe on the tally's exhausted pass
across the emerald run's five stages: 101, 163, 162, 167, 196 passes for
(91,76), (162,3), (161,0), (166,17), (195,6) -- every one exactly
`max(P1, P2, 100) + 1`.

### What it does and does not move

`TestS2CompleteEmeraldRunChain` is **unchanged**: still 5 axes, segment 11 at
236, `seg7_ehz2` comparator cursor 3977 of 3997, the same four `edge[8]`-`[11]`
-1 slots. That is expected, and is the point of recording it here: the
`stage_exit` interior return still freezes the shared cursor and pre-seeks
straight to `returnOffset` (`AbstractRunChainTest.java:1348-1351`), so the length
of the results choreography is not yet compared against anything. The defect was
real and is now closed; the walk that would have exposed it can be attempted
without first having to explain a 44-67 row overrun that had nothing to do with
the seam.

One rendering gap remains and is deliberately left alone: the ROM draws a second
"Miles/Tails rings" row from `Bonus_Countdown_2` (`Obj6F_P2Rings`, `:28306-28318`)
which the engine does not draw. The engine's single rings row now shows
`Bonus_Countdown_1` rather than the combined total, which is the ROM's value for
that row.

## 2026-08-13, measured: the interior-return seam's fade, and the refutation of the walk premise

Three stacked changes were specified for the `stage_exit` interior return. The first
landed on its predicted number exactly; the other two fit the row budget exactly and
still do not move the test, which refutes the reason they were stacked.

### Fix 1 — the interior fade ordering (LANDED)

MEASURED at all five `stage_exit` interiors, by a throwaway probe counting
`stepOneFrame` invocations after the interior's rows are exhausted, labelled by
`GameLoop` mode and `FadeManager.isActive()`:

| | steps | decomposition |
|---|---|---|
| before | **87** | `SPECIAL_STAGE_RESULTS+fade` 1, `TITLE_CARD+fade` 21, `TITLE_CARD` 65 |
| after | **109** | `SPECIAL_STAGE_RESULTS+fade` 23, `TITLE_CARD+fade` 21, `TITLE_CARD` 65 |

The census non-lag total is 109 at every one of the five seams
(`[23,11,53,14,8,39,25]` for `ss`/`ss_2`/`ss_3`, `[23,11,53,14,8,38,25]` for
`ss_4`/`ss_5`; 23 + 53 + 8 + 25 = 109 either way). 87 → 109 is exact.

The defect was ordering, not duration: the engine's 86 title-card rows already equal
the ROM's `53 + 8 + 25`, and its 22-row fade ran *concurrently* with them, where
`Level:` runs `Pal_FadeToBlack` (call site s2.asm:4765, routine :3370-3383,
`move.w #$15,d4` + `dbf`, one `bsr.w WaitForVint` per pass = 22 counted rows)
**entirely before** `Level_TtlCard` (:4914). S1 already models the same span as
`preLevelFadeOutFrames` = 22 for `PaletteFadeOut`; S2's was 0.

No constant was introduced that is not the ROM's own immediate. The change is one
override on an existing per-game profile — no game-name check, no zone, route or
frame predicate.

`TestS2CompleteEmeraldRunChain` is **unchanged** by it: still 5 axes, segment 11 at
236, `seg7_ehz2` comparator cursor 3977 of 3997, the same four `edge[8]`–`[11]` −1
slots. That is expected — the interior return still freezes the shared cursor, so
these rows are not yet compared against anything. Two full `-Ptrace-replay` sweeps,
one per tree at `4d51aa04f`, ended on **identical** results: 842 run / 9 failures /
58 errors / 4 skipped, and the same 66-name red class set.

### Fix 2 — the interior-return census walk (NOT LANDED)

Implemented as specified: pre-seek to `returnOffset - sum(census)` (arithmetic only,
no recorded index), walk the seam with `stepEngineFrameInTransitionGap` using
`expandGapAdmissionCensus` / `lastNonAdmittedRow` / `holdPlayerArtForLevelEntryLoad`.

**The budget fits exactly.** With fix 3 also applied the walk spends **174** steps at
`ss` and `ss_2` — 173 census rows plus the one title-card-exit fall-through frame —
with no `rowsConsumed must be 0 or 1`, no cursor overrun, and `framesConsumed == 1`
preserved. The frame-0 input rationale is preserved by construction, one cursor row
per gap row.

**Without fix 3 it is one non-lag row short** and must not land on its own: the
boundary then latches inside the census and the run fails on
`run_boundary.position.x` expected 4735 actual 4736, which hides segment 11 and both
later special-stage gap axes.

### Fix 3 — defect A (NOT LANDED), and what it refutes

`updateZoneTileUpload` changed to test-then-decrement. It does close
`edge[8]`–`[11]`, and it does supply the 110th engine step the walk needs.

But the premise that "the freed row has nowhere to go until fix 2 lands" is **false**,
measured three ways:

| configuration | post-interior steps | result |
|---|---|---|
| fix 1 only | 109 | 5 axes; segment 11 = 236; cursor 3977 |
| fix 1 + fix 3 | 110 | 3 axes; segment 2 = **47639**; `sidekick_y` f1132 rom=0x02CB engine=0x02CC; seg3 DPLC |
| fix 1 + fix 2 + fix 3 | 174 (173 census + fall-through) | 3 axes; segment 2 = **47639**; `sidekick_y` f1132; seg3 DPLC |

The last two are byte-identical fingerprints. Spending the whole census — 64 lag rows
and all 109 non-lag rows, with the level-entry art hold released at
`lastNonAdmittedRow` — changes segment 2 by **nothing**. Defect A's regression is
therefore not a movie-row phase error that the walk could absorb; the quantity it
perturbs is a **playable-pass count** inside the title-card span, which no amount of
row-spending in the gap can compensate.

Per the skill's "name the lever" rule: **47639 / segment 2 / `sidekick_y` frame 1132 /
seg3 DPLC is defect A's fingerprint, and it is invariant under the census walk.** A
future round that reproduces it has pulled the same lever again. The next question is
not "where do the rows go" but "which pass count does `leavePass` arming actually
change, and what does the ROM run there" — `LEAVE_PRELOOP_PASSES` and the
`s2.asm:5003-5006` leading `RunObjects` pass are the place to start, not the gap.

## 2026-08-13, closed line: the two entry paths run the same number of passes

A long thread assumed the special-stage return needed **one more playable pass** into
`Level:` than an act advance. **The disassembly refutes it. Do not add a pass.**

Entry is identical in object-pass terms. The act advance branches in at `Level_MainLoop`
`:5096` (`bne.w Level`) after that frame's `RunObjects` at `:5095`. The special-stage
return exits its results loop (`:6798-6805`), runs `Pal_FadeToWhite` (`:6809`, routine
`:3570-3582` — 22 V-blanks of `WaitForVint`/`UpdateAllColours`/`RunPLC_RAM` and **zero**
`RunObjects`), sets `Game_Mode` (`:6813`), `rts`, and reaches `Level:` via `MainGameLoop`
`:424-428` → `GameModesArray` `:431`.

- `LEAVE_PRELOOP_PASSES = 1` is correct for both: `:5003-5006` is unconditional
  straight-line code.
- `LEAVE_LOOP_PASSES = 25` is path-independent: the only re-loop test at `:5060-5066` is
  `tst.b (TitleCard_Background+id).w`, with no `Plc_Buffer` term, and `Obj34`'s
  out-routines (`:27518-27604`) are pure step counters.
- `Last_star_pole_hit` — the only entry-provenance-sensitive flag in `Level:`
  (`:4896-4898`, `:4970-4977`) — is pass-neutral.

So **26 passes on both paths**, and the leave fall-through collapse is correct for both.

### What the evidence actually points at

`TailsCPU_Normal_FollowRight`'s `addq.w #1,x_pos(a0)` (`:39332`, mirrored by `subq.w #1`
at `:39318`) is reached via `Obj02_Control` `:38962-38969` → `TailsCPU_Control` `:39070` →
`TailsCPU_Normal` `:39259`, and is **not** gated by `Control_Locked_P2` (`:38963` gates
only the `Ctrl_2` copy) or `Level_started_flag`. So it does fire on the leave passes — but
it is a **ceiling of ±1px per pass, not an identity**: guards at `:39302` (`d2 != 0`),
`:39328` (`inertia != 0`) and `:39330-39331` (`x_flip` clear) can suppress it, and the
**sign** comes from `Sonic_Pos_Record_Buf` read 17 entries back (`:39284-39291`,
`$10<<2 + 4 = $44`) — a buffer cleared by `clearRAM Misc_Variables` at `:4809` and refilled
one entry per pass.

Therefore "engine 2px behind" is **equally consistent with a single pass where the engine
takes `FollowLeft` and the ROM takes `FollowRight`** (−1 against +1 = 2px in one pass).
That reading reconciles everything the measurements show: the walk budget fits exactly,
both paths run 26 passes, the ROM has no provenance branch, and the first-mismatch frame
moves 1 → 0 under the fall-through collapse.

**The next question is follow direction, not pass count.** Instrument which branch
`TailsCPU_Normal` takes on the interior-return seam's passes and compare the delayed target
the engine reads from its position-record buffer against the ROM's.

## 2026-08-13, final: the residual is one compared pass, and four explanations are dead

The `seg4_ehz1 → seg5_ehz2` gap is four fields from exact (`edge[8]`–`[11]` at −1), and the
interior-return seam is one playable pass from agreeing. Four candidate explanations were
each implemented or instrumented and **each is refuted**. Recording them so none is retried.

### Dead ends, with the measurement that killed each

| explanation | how it died |
|---|---|
| **the return needs one more entry pass** | ROM: both paths run 26. `LEAVE_PRELOOP` 1 (`:5003-5006`) is unconditional straight-line code; the leave loop (`:5060-5066`) tests only `TitleCard_Background`'s id; the return's `Pal_FadeToWhite` (`:6809`, `:3570-3582`) has **zero** `RunObjects`. |
| **the sidekick follows the wrong direction** | Per-pass probe: **no `FollowLeft` anywhere** in the seam. Every pass is `Stand` (dx=0, seeded target) or `FollowRight` (+1), `dir` RIGHT throughout, `inertia` 0 for the whole `Stand` run so the `:39328` guard is moot. |
| **the position ring refills without a CPU pass** | Instrumented at the write site across **all seven** level entries: strictly **1:1**, contiguous slots 0–15, including a plain level start with no seam. Census lag rows, fade rows and the fall-through row are all refuted. |
| **the engine's delay is 16 where the ROM's is 17** | **My arithmetic error.** `Sonic_RecordPos` (`:36341-36347`) increments the index *after* the write, so the index is next-free and the `$44` read is last-written **−16**, not −17. Walking `Obj01_Init_Continued` (`:36206-36216`) forward, the ROM reads the seed on passes 1–16 and flips on 17 — exactly what the engine does. `SidekickCpuController.ROM_FOLLOW_DELAY_FRAMES = 16` already documents this in its own comment. |

### What the residual actually is

Post-flip acceleration is one `$C` per pass. The engine reaches `0x84` (11 × `$C`) on pass
28 and `0x90` (12 × `$C`) on pass 29; the recording's segment-2 frame 1 carries `0x90`.
**The comparison lands one pass early** — the destination's first compared row sits one
playable pass before where the recording puts it.

That is a *boundary adoption* question, not a pass-count, follow-direction or ring-delivery
one. The engine runs the right number of passes, in the right directions, with a correctly
delivered follow delay; the seam simply begins comparing one pass too soon. The relevant
machinery is the `framesConsumed == 1` adopt-opening-row contract
(`AbstractRunChainTest.java:1455-1463`) and the pre-seek rationale at `:1322-1330`.

### The census walk is sound and ready

Not landed, because it exposes the above rather than causing it — but the mechanism is
proven and fits to the row:

```
census 173, interiorGapOrigin = destinationOffset − 173, pre-seek to origin + 1,
cursor → returnOffset + 1, framesConsumed == 1, no overrun
```

The `+1` is structural: the census's leading non-lag run begins on the **source** segment's
own last recorded row, which `TraceSessionLauncher.runGapRowContinuesSourceLevelMainLoop`
leaves to the source body. Pre-seeking to the bare origin overshoots by exactly one and
throws `rowsConsumed must be 0 or 1` at `framesConsumed = −1`.

## 2026-08-13, closing: the residual is a sidekick-only simulation deficit

The boundary-adoption hypothesis — engine right, recording right, comparison misaligned by
one row — is **refuted by a discriminating experiment**, and the refutation localises the
defect properly for the first time.

### The experiment

With the interior-return census walk applied (reproducing its stated baseline exactly:
3 axes, segment 2 = 58,355, frame 1, `sidekick_x` rom=0x0DDE engine=0x0DDD), the comparison
for that segment was offset by one row as a **probe only**. If adoption were the cause the
residual had to vanish uniformly across all fields and rows.

| | baseline | one-row shift |
|---|---|---|
| segment 2 errors | 58,355 | **99,105** |
| distinct failing fields | 106 | 134 |
| frames carrying errors | 3,002 | 3,362 |
| player's first error | frame 52 | **frame 15** |

It nearly doubles, and it breaks the one thing that was exact: every player field matched
the recording row-for-row through frame 51, and after the shift the player accumulates
23,346 errors from frame 15. The sidekick's own first error merely moves 1 → 31.

### Why that settles it

**The misalignment is confined to one object.** Read from the committed fixture
(`seg2_ehz1/physics.csv.gz`), the engine's sidekick at compared row N carries the
recording's row N−1 value for frames 1–20 — 20 of 20 exact — **while the player is aligned
row-for-row over the same span**. One comparator cursor serves every field, so an adoption
error must shift them all; it cannot align one playable and misalign the other.

**And the lag is a seed, not a standing offset.** Of 2,847 `sidekick_x_speed` mismatches
across the segment, only 22 satisfy `engine[N] == rom[N-1]`. The one-pass relation holds
over the seam's opening ~20 frames and then the sidekick genuinely diverges.

### What the next round should ask

**Why does the engine's sidekick execute one playable pass fewer than the player across the
interior-return seam?** That is an object-clock question inside the seam walk — not a
boundary, comparison, lookback, follow-direction or ring-delivery question, all of which are
now measured and refuted.

Fingerprint so the lever is recognisable: *census-walk-alone = 58,355 at segment 2, frame 1,
`sidekick_x` 0x0DDE vs 0x0DDD, four sidekick-only fields, player clean to frame 52.*

## Seventh refutation, and a warning about probe output

**Claim:** the engine takes `Stand` on a pass where dx = 32, where the ROM's
`beq` (`s2.asm:39302-39303`) requires dx = 0 — one missing accelerating pass.

**Refuted.** Direct instrumentation of the comparison site shows the engine does *not*
stand on that pass:

```
f=2986  dx=0   targetX=3536              legitimate Stand (seeded target)
f=2987  dx=32  targetX=3568  inR=true    FollowRight taken
f=2988  gv=12                            accelerating
```

The ROM detail that matters, and which the claim got wrong: the delta compared is the
**delayed/seeded target** minus Tails (`:39284-39291`, seed = `Sonic.x − $20` from
`Obj01_Init_Continued` `:36206-36216`), **not** the live leader x. S2's
`sidekickFollowLeadOffset` is 0 (`GameRules.java:282-285`), so the engine's dx is the
ROM's `d2` exactly.

**How the false contradiction arose — worth remembering.** An earlier probe printed
`liveLeaderX` under the `delayedTargetX` column heading, and reported a *pre-pass*
inertia as post-pass. Reading those columns at face value produced an apparent
`Stand`-with-dx=32 that does not exist in the engine.

**A probe's column labels are not measurements.** They are as fallible as any other
code written in a hurry, and a hypothesis derived from mislabelled output is
indistinguishable from one derived from real data until someone re-instruments the
actual site. When a probe seems to show the engine doing something structurally
impossible, suspect the probe first.

## Axis 4 is not closable at frame granularity — and the anchor is luckier than it looks

`ss_4 -> seg6_ehz2` reports `run_gap.edge[0]`/`[1]`.`movie_logical_frame` expected 46347,
actual 46348 — the engine one row **late**, opposite to every other seam defect. Aligned by
transfer id: edge[0] = tid 28084 submitted/sonic/`run_gap`/mapping frame 1/pc `0x1B89A`;
edge[1] = tid 28085 submitted/tails/pc `0x1D1FE`. The matching *completed* pair at 46349
**matches** — only the submission row is wrong.

### The anchor is an approximation that happens to work

`admitLevelWhenReady` releases the held player art on `lastNonAdmittedRow(census)`. For
`ss_4` the census is `[23,11,53,14,8,38,25]`, so the `InitPlayers` lag run is 46311..46348
and the anchor lands on 46348 — exactly the engine's actual.

Computing the tail (`lastLagEnd − submit + 1`) for **all 27 censused transitions**:

| tail | count | meaning |
|---|---|---|
| **1** | 20 seams | the anchor coincides with the ROM |
| **2** | 4 seams | `ss_4`, `ss_5`, `seg15_cnz2→seg16_htz1`, `seg27_wfz1→seg28_dez1` — engine one late |
| ragged | 6 seams | `ss_6` tail 47, `ss_7` tail 8, and four level seams at 20–27 |

**Nothing zone-, act-, route- or path-dependent distinguishes them.**
`seg4_ehz1 → seg5_ehz2` (level_advance) has tail 1 while `ss_4 → seg6_ehz2` (stage_exit) has
tail 2 — *same destination act, opposite tail*. Likewise `seg15_cnz2 → seg16_htz1` is 2 while
`seg16_htz1 → seg17_htz2` is 1.

### Why, from the ROM

`Level:` reaches `InitPlayers` (`:4946`) after the load routines (`:4938-4945`). The player
art is **not** submitted by `InitPlayers` — it is submitted by the leading `jsr (RunObjects)`
at `:5003`, where Obj01/Obj02 display runs the DPLC. Between `:5003` and the leave loop's
first `bsr.w WaitForVint` (`:5060-5061`) the ROM runs **only straight-line 68000 code** —
`BuildSprites` (`:5004`), `AniArt_Load` (`:5005`), `SetLevelEndType` (`:5006`), demo setup
(`:5007-5044`), `PalLoad_Water_ForFade` (`:5045-5056`), the leave-flag writes (`:5057-5059`)
— with no `WaitForVint` and no polled readiness gate.

So the masked-frame count in that tail is a **pure 68000 cycle count**, dominated by
`BuildSprites`/`AniArt_Load`, whose cost depends on the object set `ObjectsManager` loaded at
the entry position (`:5000`). The distance from the submission to the end of the masked span
is **sub-frame CPU position**, which is why the same destination act yields 1 at one seam and
2 at another.

**Therefore axis 4 cannot close at frame granularity** without either a fitted `−1`
(forbidden) or consuming the recorded `movie_logical_frame` that the field itself compares —
round-tripping the answer, forbidden by rule 4. Closing it legitimately needs the capture to
record the submission row.

### Two things this exposes

**`ss_6` and `ss_7` are not passing — they are unreached.** The chain aborts around segment
11, so they are never compared. Their anchor error is 8–47 rows, far larger than axis 4 and
currently latent.

**A ROM-derived anchor improvement, found and deliberately not landed.** The leave loop
performs exactly one `WaitForVint` per pass (`:5060-5061`), so a leave-loop pass can
contribute **at most one** consecutive lag row — therefore any lag run of length ≥ 2 cannot
be inside the leave loop. Anchoring on *the last lag run of length ≥ 2* is ROM-derived rather
than a fitted threshold, and would correct the 8–47-row errors at `ss_6`, `ss_7` and the four
ragged level seams. It changes nothing at `ss_4` and nothing on any seam the test currently
reaches, so it is **unverifiable by measurement today**. Landing an unverifiable change to
this heavily contested harness path was judged the wrong call; it is recommended for a round
that can reach those seams.
