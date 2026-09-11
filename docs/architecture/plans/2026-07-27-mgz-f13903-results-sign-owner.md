# MGZ f13903 results/sign owner implementation plan

> **For agentic workers:** Execute inline with systematic debugging and strict
> RED/GREEN verification.

**Goal:** Restore the standalone MGZ trace to at least its accepted f23561
frontier while preserving the complete-run f28398 frontier.

**Architecture:** Trace the live results controller, sign child, child
retirement, and reload owner across the standalone and complete-run timelines.
Represent the ROM state that distinguishes those timelines at the owning
object/lifecycle boundary. Do not select a pose from route identity or a
fixed phase.

**Tech stack:** Java 21, Maven, JUnit Jupiter, S3K disassembly, trace replay,
and the bounded BizHawk probe runtime only when engine/fixture evidence cannot
resolve mid-frame ownership.

## Global constraints

- Do not inspect or execute LBZ.
- Do not branch on trace, route, zone, frame, or VBlank phase.
- Do not add the rejected fixed MGZ gate-8 policy.
- Do not use the universal routine-6 Player 1 pose as the final fix.
- Runtime assets remain ROM-backed.
- Remove all throwaway diagnostics before committing.

### Task 1: Identify the earliest wrong owner

- [x] Reproduce standalone f13903 and complete-run f28398.
- [x] Inspect result/sign object slots and lifecycle state around both
      boundaries.
- [x] Compare the change introduced by `ed113599f` with the native
      results/sign disassembly.
- [x] Add bounded engine diagnostics at controller/sign creation, retirement,
      and reload boundaries.
- [x] Use a bounded native probe only if the resulting owner order remains
      ambiguous.

### Task 2: Protect and fix the semantic lifecycle

- [x] Write a focused test whose literal expectations distinguish the two
      live owner timelines.
- [x] Run it against current production code and verify the expected RED.
- [x] Implement the smallest owner-level lifecycle correction.
- [x] Run the focused test and neighboring results/sign suites to GREEN.
- [x] Run rewind coverage if mutable object state changes.

### Task 3: Verify paired routes and document

- [x] Verify standalone MGZ reaches at least f23561.
- [x] Verify MGZ complete-run remains at f28398.
- [x] Verify paired standalone and complete-run AIZ routes.
- [x] Verify relevant CNZ and non-LBZ canaries.
- [x] Update `CHANGELOG.md`, `docs/status/trace-frontier-log.md`, and a
      purpose-classified audit under `docs/architecture/audits/`.
- [x] Run policy/diff checks, stage every artifact, and commit with required
      trailers. Do not push.
