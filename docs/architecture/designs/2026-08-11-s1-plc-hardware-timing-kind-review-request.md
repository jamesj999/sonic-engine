# Design review request: an S1 PLC hardware-timing completion kind

> **SUPERSEDED 2026-08-11 — WITHDRAWN. Do not act on this request.**
>
> [`../audits/2026-08-11-s1-plc-hardware-timing-audit.md`](../audits/2026-08-11-s1-plc-hardware-timing-audit.md)
> produced the missing audit output and **refuted this document's premise.**
> `Level_TtlCardLoop`'s drain rate *is* ROM-derivable: `VBlank_TitleCards` calls
> `ProcessPLC_9Tiles`, which decodes exactly 9 patterns per frame
> (`docs/s1disasm/sonic.asm:946,1431-1440,1472-1478`). For this load the ROM runs
> `1 + Σ ceil(Nᵢ/9) = 1 + 150 = 151` iterations, and the engine already runs
> exactly 151. The gate is correct; the 34-row deficit is the un-modelled
> blocking level-load span (`sonic.asm:2857-2900`), which is pure 68000 cycle
> cost and is not a polled readiness gate at all.
>
> Consequently: **no S1 PLC hardware-timing kind is warranted**, and
> `TestS1S2PlcComparisonOnlyGuard` should be left exactly as it is — this audit
> is evidence *for* the guard, not against it. The sections below remain accurate
> as a description of what *would* block such a change, and the "What blocks it
> today (MEASURED)" section is still correct. The claim in "Why this was raised"
> that the gate is "decompression-rate bound" with "no wait constant to derive"
> is **false** and is retained only so the record shows what was corrected.

Status: **request, not a design. Withdrawn.** No code landed. This document exists because the
gate described in
[2026-07-27-cross-game-hardware-timing-trace-contract.md](2026-07-27-cross-game-hardware-timing-trace-contract.md)
requires a review *before* an S1 PLC completion kind is implemented, and that review
has not happened.

## Why this was raised

`TestS1GhzMazeRoundTripChain`'s terminal tail lands 34 movie rows early
(`run_tail.edge[*].movie_logical_frame` expected 9071, actual 9037). The deficit sits
entirely inside `Level_TtlCardLoop`, whose exit is gated on
`tst.l (v_plc_buffer).w` (`docs/s1disasm/sonic.asm:2839-2840`). That gate is
decompression-rate bound: there is no `dbf` count and no wait constant to derive, so
no honest fix exists at frame granularity (hard rule 3). The only rule-4-legal route is
recorded hardware timing releasing real, engine-submitted S1 PLC work.

## What blocks it today (MEASURED)

1. `HardwareWorkKind` contains exactly `KOS_MODULE_QUEUE, KOS_DECOMPRESSION_QUEUE`.
   `HardwareWorkSubmission` is referenced outside `game/timing/` in exactly two files,
   both S3K (`S3kKosDecompressionQueue.java:111`, `S3kKosModuleQueue.java:233`).
   `Sonic1PlcService` has zero hardware-timing references; its decompression is
   synchronous.
2. `TestS1S2PlcComparisonOnlyGuard.timingKindRegistryAdmitsOnlyKosinskiWork`
   (`src/test/java/com/openggf/trace/TestS1S2PlcComparisonOnlyGuard.java`) asserts both
   that no `HardwareWorkKind` name contains `PLC` — "PLC readiness is native
   deterministic service, not timing-stream authority" — and that the value set equals
   exactly the two Kos kinds. Adding any S1 PLC kind turns this guard red. Renaming
   around the `PLC` substring does not help; the exact-set assertion still fails.
3. The contract document defers this explicitly:
   - "Normal S1/S2 PLC queues ... are initially classified as native deterministic
     service queues, not as automatically authoritative trace inputs." (l.253-258)
   - "`PLC_QUEUE` ... remain non-authoritative inventory candidates; each requires
     separate ROM evidence and design review." (l.328-330)
   - The kind registry is selected by trace metadata schema (schema 1: module queue;
     schema 2: both Kos kinds). A third kind needs a schema 3 and a fixture
     republication, which is behind an explicit user-approval gate.
   - Acceptance criterion 1 and follow-up audit output 1 both require an S1 timing
     inventory separating synchronous lag work from polled PLC queues, *before*
     implementation.

CLAUDE.md hard rule 4's one-line summary does name "S1 PLC" as in scope for the
contract. That is the contract's eventual scope, not a standing authorisation: the
document it points to, and a committed guard test, both say an S1 PLC kind is gated on
this review. Treating the summary line as approval would silently reverse an
architectural decision the repository states in a test-failure message.

## The evidence the review would need, and which parts already exist

The doc's bar is: "Any proposed S1/S2 authoritative completion kind must ... demonstrate
a polled, gameplay-visible readiness gate whose timing is not already reproduced by lag,
execution phase, and deterministic queue service."

MEASURED and satisfied: `Level_TtlCardLoop` (`sonic.asm:2813-2841`) is exactly such a
gate. The loop runs `ExecuteObjects`, `BuildSprites`, and `RunPLC` every iteration and
re-tests `v_plc_buffer`; ordinary main-loop work continues across many non-lag rows
while the queue drains. Note `FixBugs = 0` is the modelled path, so the loop's *first*
condition is the single `v_ttlcardact` position test, and the `v_plc_buffer` test is
what actually holds the loop at the end.

NOT YET PRODUCED, and required before any code:
1. The S1 timing inventory (follow-up audit output 1) — every S1 site that polls a
   hardware readiness value, separating synchronous lag work from the PLC queue.
2. Proof that the 34-row deficit is *not* already explainable by lag rows or execution
   phase in the existing replay. This has not been shown; it is currently inferred from
   the absence of a derivable constant, which is weaker.
3. A `HardwareWorkKind` schema-3 registry proposal, with the guard test's assertion
   rewritten deliberately rather than as collateral.

## Fingerprint contract sketch (for a stage 2 recorder, if approved)

Recorded only so a later recorder change has a written target. Not implemented.

- Kind wire name: `s1_plc_queue` (one kind; the S1 queue is a single FIFO, unlike
  S3K's module/direct split).
- One submission per `AddPLC`/`LoadPLC` descriptor the ROM enqueues — i.e. per Nemesis
  art entry in the PLC list at `docs/s1disasm` label `ArtLoadCues`, resolved through the
  ROM loading pipeline, never from `docs/` bytes (hard rule 1).
- Fingerprint components, all ROM-derived: Nemesis source ROM address, VRAM
  destination, and decoded pattern count. These are already available inside
  `Sonic1PlcService.Submission` / `PlcDefinition`.
- Ordinal: a monotonic per-session counter incremented in the engine's own submission
  order, which is `RunPLC` FIFO order and therefore the ROM's. It must never be
  assigned to match a recorded value.
- Boundary: `vint_service` — `RunPLC` is called from the main loop after
  `WaitForVBlank`, so readiness must become visible before the post-VInt consumer.
- Stage 2 must emit these edges into a `hardware_timing.jsonl` stream for S1 fixtures
  (there are currently zero; all 139 such files are under
  `src/test/resources/traces/s3k/`), and this is a full S1 fixture regeneration.

## Recommendation

Do not implement stage 1 as a fait accompli. Produce audit output 1, then take the
schema-3 registry change and the guard-test reversal to the user as one explicit
decision, then build the routing. The routing change is small; the architectural
reversal underneath it is not.
