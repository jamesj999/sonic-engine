# S2 dynamic-art discard — findings and handover

**Date:** 2026-08-14
**Status:** Investigation complete. One engine defect identified and **not** landed, with
its scoping attached. One recorder defect identified, requiring a fixture regeneration that
has **not** been requested or authorised.

All numbers here are stamped with the commit they were measured at. A measured fact that is
load-bearing and older than current `HEAD` must be re-measured before it is relied on.

## The engine defect: art is applied at submission, not at drain

**MEASURED at `fb01e231b`..`5c80dcedc`.** `DynamicArtDecisionOwner.observe()` calls
`lifecycle.observePlayerDplc(...)` and then, *synchronously in the same call*,
`renderer.applyRuntimeArtUpdate(...)` → `patternBank.consumeRuntimeArtState(...)`. The tiles
are written there and then.

The ROM writes nothing at submission. `LoadSonicDynPLC` only calls `QueueDMATransfer`
(`s2.asm:38858`); the VRAM write happens later, inside `ProcessDMAQueue`
(`s2.asm:1770-1791`), which reads `move.w (a1)+,d0 / beq.s .done` and issues nothing on a
zero first word.

**Consequence:** the ROM *discards* queued work at several points by zeroing the queue head.
Because the engine already applied the art at submission, it displays art the hardware never
transferred. Two instances measured under instrumentation in
`TestS2EhzHalfpipeRoundTripChain`:

| transfer | owner | submitted | retired across | ROM discards at |
|---|---|---|---|---|
| 8078 | tails-tails, mf 13 | movie row 12604 (`LEVEL`) | `LEVEL → SPECIAL_STAGE` | `s2.asm:6599` overrun |
| 5495, 5496 | ss-tails | movie row 9701 | `SPECIAL_STAGE → SPECIAL_STAGE_RESULTS` | `s2.asm:6759-6760` |

### The discard sites, guard-checked

The instruction pair `clr.w (VDP_Command_Buffer).w` / `move.l #VDP_Command_Buffer,
(VDP_Command_Buffer_Slot).w` appears at six sites. **Two never execute.**

| `s2.asm` | guard | shipped ROM (`fixBugs = 0`) runs it? |
|---|---|---|
| 4857 | none (preceding `endif` at 4817) | **yes** |
| **6609** | **`if fixBugs` at 6604** | **NO — do not model** |
| 6759 | none | **yes** |
| **10342** | **`if fixBugs` at 10332** | **NO — do not model** |
| 10766 | none | **yes** |
| 11737 | none | **yes** |

Plus a **fifth, different mechanism** at special-stage entry: the shipped `else` branch runs
`clearRAM SS_Shared_RAM,SS_Shared_RAM_End+4` (`s2.asm:6599`), which **overruns into**
`VDP_Command_Buffer` — declared immediately after `SS_Shared_RAM_End`
(`s2.constants.asm:1183-1186`). The disassembly annotates this itself at `:6604-6608`.
Modelling the entry discard must model the overrun (and whatever else that clear tramples),
**not** the guarded explicit pair — that would be taking the FixBugs branch.

**The entry discard is structurally different from the other four, and this is easy to get
wrong.** The `+4` overrun zeroes only the **first word** of `VDP_Command_Buffer`. The paired
`move.l #VDP_Command_Buffer,(VDP_Command_Buffer_Slot).w` that would reset the *write pointer*
sits inside the `if fixBugs` block at `:6604-6611` and **does not run**. So entry is "head
zeroed, slot pointer left where it was" — not a full queue reset. The four unguarded sites do
both. Modelling entry as a full reset would be taking the FixBugs branch by the back door.

Site `:10342` documents the bug its fix would have prevented: after a Game Over in HTZ,
`Dynamic_HTZ`'s queued cloud art transfers late over Tails' Continue art and corrupts it. At
`fixBugs = 0` **that corruption is live shipped behaviour** and something the engine should
reproduce, not avoid.

### The submission-time path exists ONLY during a trace run

**MEASURED at `5c80dcedc`, and it reframes the whole fix.** `DynamicArtDecisionOwner.observe()`
early-returns on `!lifecycle.isRunActive()` (`:32-34`), and every lifecycle entry point calls
`requireRunActive()`. So the submission-time application described above **happens only under
the comparator**.

In ordinary windowed play the art lands somewhere else entirely: `PlayerSpriteRenderer.drawFrame`
→ `applyDplc` (`:60-76`, `:168-181`), lazily, on first draw of a changed mapping frame, called
from `Sonic.java:38`, `Tails.java:38`, `Knuckles.java:35`.

Two consequences:

- **A deferral built only on the `observe()` seam would change engine behaviour only under the
  comparator** — precisely the shape the project forbids.
- There are **five** S2-reachable art-application sites, only one of which passes through the
  queue-aware object: `applyRuntimeArtUpdate` (run-only); `drawFrame`→`applyDplc` (production);
  `forceInitialDplc` (`:184-196`); `Sonic2SpecialStageManager.publishSpecialStageOwner`
  (`:2087-2096`, which submits to the same S2 pending list but applies no art — its art comes
  from `drawFrame`); and `Sonic2EndingCutsceneManager:1764,:1773`, calling
  `consumeRuntimeArtState` directly with no ledger at all.

There are also **two independent dedup registers for one ROM contract** — the renderer's
`lastFrame` and the lifecycle's `lastMappingFrames`. Today `applyRuntimeArtUpdate` sets
`lastFrame`, which is what stops `drawFrame` re-applying. **Defer the write without unifying
these and `drawFrame` simply applies the art anyway at render time, so the accuracy defect
survives the fix.** This is the "two paths that should agree, but don't" pattern.

### Scope: the fix has two halves that must land together

1. Application moves from submission to drain.
2. The cited reset sites **remove entries from the drainable queue**.

Landing only (1) does not fix the defect — it relocates it. The entry would survive the mode
boundary and apply at the *next* drain, so art the hardware never transferred appears in the
following mode instead of the current one.

Only the third piece — what edge the ledger emits for a removed entry, and how the
comparator treats it — is deferrable.

**Anticipated cost.** Apply-at-drain changes *when every* S2 dynamic-art write lands, not
only the discarded ones: every submission-to-drain interval where the engine showed new art
while the ROM still showed old art becomes different. That implies broad S2 trace
re-measurement, not just the emerald chain.

### Verdict: larger than one round. Recommended decomposition

Scoped and **not attempted** — the engine has no drain-time art application anywhere, and
introducing a queue-backed VRAM model that exists outside a trace run is not a call-site move.
Four rounds, strictly ordered:

- **A. A production-owned S2 DMA queue that exists outside a run.** Entries carry
  `{target DynamicPatternBank, source Pattern[], TileLoadRequest list}`; submitted at the
  `QueueDMATransfer` counterparts, drained at the V-int counterpart. Reuse
  `DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE.services(phase)` **unchanged** — it
  already computes exactly "does this V-blank run `ProcessDMAQueue`" and is verified correct.
  The existing lifecycle ledger becomes an *observer* of this queue rather than the owner of
  the concept.
- **B. Make submission the sole art entry point** for queue-backed S2 owners: stop
  `drawFrame`/`forceInitialDplc` writing the bank for them, and unify the two dedup registers
  against `SPLC_ReadEntry`'s per-frame compare. This is the round that makes the accuracy
  effect real, and the one most likely to move rendered output in ordinary play — it needs its
  own before/after visual check. **Not optional polish.**
- **C. Model the discards**: full reset at `:4857`, `:6759`, `:10766`, `:11737`; head-only zero
  with slot pointer retained at `:6599`. Two sites (continue screen, level select) may have no
  exercised engine counterpart yet — budget discovery. **C is not landable without A.**
- **D. Rewind coverage** (a pending-unapplied queue is cross-frame mutable state →
  `RewindSnapshottable`, both rewind guards) plus full blast-radius re-measurement.

**Comparator prediction to brief forward:** B changes when `lastFrame` advances, which changes
which mapping frame the ledger's dedup sees, which moves `dynamic_art.edge[N].mapping_frame` —
the same field that produced the ledger-only options' comparator starvation. Any round
attempting A or B must **diff the chain's axis list by name, not the failure count.**

### Why no ledger-only fix works — measured, do not re-run

Three edge-semantics options were prototyped from one build, arm-selected at runtime
(baseline at `fb4ce0b53`: **843 run / 9F / 56E / 4S, 65 red**):

- **Option 3** (stop performing, keep the `completed` stamp) — run as a **control**:
  byte-identical to baseline, red set identical by name, all 5 chain axes verbatim. This is
  what proves the scaffolding inert. It is *separately* rejected as illegal: an engine
  stamping `completed` on work it did not do is a lie in its own telemetry, placed there
  only to match a recorder we know is wrong, and after regeneration the engine would become
  the one manufacturing lifecycles.
- **Option 1** (silent removal) and **Option 2** (distinct `discarded` edge kind) — both
  **843 / 10F / 56E / 4S, 66 red**, red-set diff `+ TestS2EhzHalfpipeRoundTripChain` only;
  none of the other 29 blast-radius classes moved.
- **Both starve the comparator.** `TestS2CompleteEmeraldRunChain` stops reporting its 5 axes
  and aborts earlier on a DPLC divergence in special-stage segment 1, so segment 11 = 236,
  the cursor walk failure and all three gap axes go **unmeasured**. Same failure shape the
  DO-NOT-RE-RUN list records for the interior-return census walk. **Fewer axes is not
  better.**

All three leave the accuracy defect untouched, because they change only what is *recorded*.

## The recorder defect — needs an authorised regeneration

`S2DynamicArtObserver.OnProcessDmaQueue`
(`tools/bizhawk-headless/src/Recording/S2DynamicArtObserver.cs:599-623`) does
`while (ledger.Count != 0) { ... AddRawEdge(..., Completed, ...) }` with **no read of
`VDP_Command_Buffer`**. `profile.Ram.DmaCommandBuffer` (`0xDC00`) is declared in
`DynamicArtRomProfile.cs:106` and never read anywhere; only `DmaCommandBufferSlot`
(`0xDCFC`) is read.

So at the four live reset sites the recorder **manufactures a completed lifecycle for work
the hardware discarded**. It should read `$FFDC00` at entry and, on a zero first word, record
the pending ledger as *discarded* rather than *completed*.

**This is a recorder change requiring fixture regeneration, which is separately authorised
and has not been requested.** It is recorded here so the request can be made as one package
rather than piecemeal.

## What was confirmed, and is not a defect

- **KD 28 / chain axis 4 stands, now on positive evidence** rather than the pattern-match it
  was written on. Re-examined against the recorder-fiction pattern: no ledger-invalidating
  write lies inside the span (every queue-zeroing write is upstream of the submission at
  `s2.asm:5007`), and the transfer is genuinely *performed* — transfers 28084/28085 submit
  at row 46347 and complete at 46349, with the next two submitting and completing on
  consecutive rows. A queue draining next-row is live, not stranded. Its span citations were
  four lines low and are corrected in `known-discrepancies.md`.
- **The S3K `PALETTE_FADE`-wins-the-token behaviour is ROM-correct**, not a silent
  regression. Both S3K blocking fades rewrite `V_int_routine = $12` every loop iteration
  before `Wait_VSync` (`sonic3k.asm:5045-5050`, `:4906-4911`), so a V-blank inside an S3K
  fade always dispatches `VInt_12` (`:849-852`) and can never reach `VInt_0_Main` — the lag
  path (`:519-520`) and sole bump of `Lag_frame_count` (`:570`). Recorded on
  `LevelFrameStep.serviceVBlankOnly`.
- **The v5 hardware-timing port cannot cover any of this**, and must not: it would model
  recorder bookkeeping rather than hardware. It also fails its own eligibility gate — no
  polled readiness value, no loop in the span, VDP transfer fences named explicitly
  non-authoritative, and rule 4 permits **DPLC** for S2, which never calls
  `QueueDMATransfer`.

## Corrections landed to existing documentation

Four committed comments stated the ROM backwards, all the same species — claims about what a
V-int "retires" written from the call graph without checking whether the queue still held
anything:

- `GameLoop.java` ×2 (special-stage results tail; `s2.asm:6759` zeroes first)
- `ResultsScreenObjectInstance.java` (level entry; `s2.asm:4857` zeroes first)

Checked and found **correct**, so deliberately untouched: `Sonic2SpecialStageManager`
:1285-1288 — `Vint_CtrlDMA` really does call `ProcessDMAQueue` (`s2.asm:998-1001`), and that
pass queues fresh art after the entry clear, so there is genuinely something to retire.

The manifest's own frontier claim was also stale and is corrected:
`TestS2CompleteEmeraldVisualRun` does **not** stall at row 5200. It is pinned to
`stopAfterSegmentBody(0)`, asserts `sharedCursor == 4479`, and is **green**.

## Recommendation

The emerald chain's only honest blocker (KD 28) is now confirmed unclosable at frame
granularity. The apply-at-drain fix is genuine accuracy work that stands without any trace —
the test being *"would you land it if the trace did not exist?"*, and for modelling the ROM's
discard the answer is yes — but it is a cross-cutting change to the S2 art pipeline on a game
that is not the stated priority.

Land it if its scoping proves contained, under the opportunistic-uplift clause. If it
sprawls, park it as a documented defect with this scoping attached: a well-scoped unlanded
fix is an asset; a half-landed cross-cutting one is a liability.

## Decisions needing your authority

Both concern the same authority — what gets recorded — so they are put together rather than
piecemeal.

### 1. Fixture regeneration for the recorder discard defect

As above: `OnProcessDmaQueue` manufactures completed lifecycles for work the ROM discarded.
Fixing it means a recorder change plus regenerated S2 fixtures. **Not requested, not
started.** Note the sequencing trap: regenerating *before* the engine's apply-at-drain fix
lands would make the engine and the corrected stream disagree in the other direction.

### 2. Whether to pull the S3K captures forward in the recorder-migration order

> **CORRECTED 2026-08-14, after re-measurement at `17fd2596a`.** The premise below — that the
> S3K replays are green and the priority game therefore has no measured frontier — is
> **FALSE**. A fresh full sweep with the report directory cleared gives 843 / 9F / 56E / 4S,
> 65 red, and **64 of those 65 red classes are S3K**; the only non-S3K red is
> `TestS2CompleteEmeraldRunChain`. That 65-red figure had been quoted all session as a
> stable S2-flavoured baseline.
>
> S3K has a large, well-witnessed engine frontier: the shared `KOS_DECOMPRESSION_QUEUE`,
> where the recording expects a completion the engine never submitted (`engine pending:
> <none>` across 31 segments, all three run chains and the MHZ complete-run), plus 10
> segments submitting at the right ordinal under the wrong fingerprint. Unchanged in shape
> since 2026-08-12, so no S3K frontier has moved since. Separately, 11 segments cannot load
> at all (DEZ act 2, HPZ act 2, DDZ act 2 have no engine level-list entries).
>
> **So the capture-reordering question is real but SECOND.** Fix the frontier that exists
> before recording more of what is not yet compared. The coverage analysis below stands and
> is still worth acting on — it is the reason the green part is worth less than it looks —
> but it is no longer the primary ask.

This one is a genuine scheduling question and it is not ours to settle.

The current recorder-migration order puts the two S3K captures last, behind the S2 workflow
and the S1 complete-run. Meanwhile:

- S3K playable parity is the **stated top priority** (`CLAUDE.md`, "Current priority").
- ~~The S3K complete-run trace replays are, as far as we know, **green**~~ — **false, see the
  correction above.** Only six standalone act-1 single-zone complete-runs are green; 64 red
  S3K classes were being counted as part of a baseline nobody had decomposed. Even the green
  six cover no act transition, no special or bonus detour, and no second act of any zone — so
  the coverage point still holds, it just is not the top of the list.
- The priority list itself directs that zones beyond AIZ→HCZ advance "by current route
  blockers and **complete-run trace frontiers**". There *is* such a frontier — the
  `KOS_DECOMPRESSION_QUEUE` one above — and it should be worked before more is recorded.
- The S1 complete-run capture, ahead of the S3K ones in the queue, serves a game that is not
  the priority.

So the priority game has thin coverage beyond act 1, and closing that gap is
a recording task before it is a fixing task. Pulling the S3K captures forward — new BK2
coverage through the route segments existing traces do not exercise, CNZ onward — would
convert "no known open S3K regression" from a statement about ignorance into one about
evidence.

Recommended, pending your call: (a) fresh full S3K `*TraceReplay` sweep under the
measurement protocol first, since it is cheap and decides everything downstream — a stale
surefire report counts as passes and a truncated run reports *fewer* red, so the existing
"green" claim needs re-measuring before it is relied on; (b) if genuinely green, reorder the
captures; (c) meanwhile, audit the S3K Kosinski-queue path for the *same* apply-at-submission
defect documented above, which is a bounded carry-over of this investigation and bears on the
standing `maxChunkPatternIndex > patternCount` limitation.
