# CNZ f6680 status owner implementation plan

> **For agentic workers:** Execute inline with systematic debugging and strict
> RED/GREEN verification.

**Goal:** Advance the standalone CNZ trace beyond frame 6680 by modelling the
native state owner that produces the expected player status.

**Architecture:** Reproduce and trace the first divergent frame across the
player, cylinder, and solid-contact phases. Use existing trace evidence first,
then a bounded native probe only if the final-frame evidence cannot identify the
mid-frame owner. Implement the smallest semantic owner correction and cover it
with a focused regression test.

**Tech stack:** Java 21, Maven, JUnit Jupiter, S3K disassembly, trace replay,
and the BizHawk headless probe runtime when necessary.

## Global constraints

- Do not inspect or execute LBZ.
- Do not branch on frame, zone, route, trace identity, or expected trace state.
- Do not patch the final status byte; model the ROM routine/state owner.
- Runtime assets remain ROM-backed.
- Throwaway diagnostic instrumentation and probes do not ship.

### Task 1: Establish the first wrong native state

**Files:**
- Inspect: `target/trace-reports/s3k_cnz1_report.json`
- Inspect: `target/trace-reports/s3k_cnz1_context.txt`
- Inspect: `docs/skdisasm/sonic3k.asm`

- [ ] Reproduce frontier-only CNZ and record expected/actual f6680 status.
- [ ] Compare the preceding trace rows and object diagnostics.
- [ ] Trace the relevant ROM player/cylinder/solid routines in execution order.
- [ ] Add temporary engine diagnostics, or a bounded native probe only when
      existing evidence cannot distinguish the candidate owners.
- [ ] Remove all temporary diagnostics after identifying the earliest wrong
      state.

### Task 2: Protect and correct the semantic owner

**Files:**
- Modify: the production owner identified by Task 1.
- Test: the closest existing focused JUnit test class for that owner.

- [ ] Write one focused behavioral test with literal native expectations.
- [ ] Run the test against the current implementation and verify the intended
      assertion fails.
- [ ] Implement the smallest owner-level correction supported by disassembly.
- [ ] Run the focused test and its neighboring suite to GREEN.
- [ ] Run `TestRewindCoverageGuard` when mutable runtime state changes.

### Task 3: Verify the frontier and release evidence

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/status/trace-frontier-log.md`
- Create: `docs/architecture/audits/2026-07-27-cnz-f6680-cylinder-inclusive-edge.md`

- [ ] Run CNZ frontier-only and canonical replay comparisons.
- [ ] Run all focused CNZ scenarios affected by the owner.
- [ ] Run standalone AIZ and MGZ as explicit non-LBZ canaries.
- [ ] Document the native evidence, RED/GREEN result, commands, and new
      frontier.
- [ ] Run formatting/policy checks, review the staged diff, and commit with all
      required trailers. Do not push.
