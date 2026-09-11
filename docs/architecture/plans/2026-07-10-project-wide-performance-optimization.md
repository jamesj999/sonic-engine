# Project-Wide Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver every evidence-backed performance candidate from the July 2026 audit with deterministic, audio, visual, rewind, trace, and full-suite proof.

**Architecture:** Three subsystem plans own disjoint behavior and tests. Work proceeds measurement-first, then exact-equivalence changes, bounded caches, and finally evidence-gated structural/GPU changes. Each task is independently committed and receives specification and code-quality review before the next task.

**Tech Stack:** Java 21, Maven, JUnit 5, LWJGL/OpenGL, JFR/ThreadMXBean, existing trace/rewind/visual harnesses.

---

## Component Plans

- `docs/architecture/plans/2026-07-10-audio-performance-optimization.md`
- `docs/architecture/plans/2026-07-10-render-special-stage-performance-optimization.md`
- `docs/architecture/plans/2026-07-10-rewind-trace-test-performance-optimization.md`

### Task 1: Establish And Record The Clean Baseline

- [ ] Verify the worktree branch is `feature/ai-performance-optimization` and `git status --short` contains only approved plan/spec files.
- [ ] Run `mvn test` as the only Maven lifecycle using this worktree's `target/`.
- [ ] Require `Tests run: 11211, Failures: 0, Errors: 0, Skipped: 12` or explain any legitimate test-count change introduced after the recorded baseline.
- [ ] Restore generated `docs/status/rewind-round-trip-gaps.md` output unless an implementation intentionally changes coverage and the task owns that update.
- [ ] Record baseline wall time, allocation probes, rewind benchmark output, special-stage draw/upload counters, and representative GPU timings in `docs/architecture/validation/performance/2026-07-integration-report.md`.

### Task 2: Execute Audio Tasks 1-7

- [ ] Execute every checkbox in `2026-07-10-audio-performance-optimization.md` in order using TDD.
- [ ] After each task, dispatch an independent specification reviewer; fix and re-review all gaps.
- [ ] After specification approval, dispatch an independent code-quality reviewer against the task's base/head SHAs; fix all Critical/Important findings and re-review.
- [ ] Do not begin fused FIR work until lazy-history lifecycle and pending-command work are green, because their measurements define the remaining audio cost.

### Task 3: Execute Low-Risk Render Tasks 1-4 And 6

- [ ] Execute palette upload, primitive visibility, static special-stage data, frame-owned command state, and small zone-allocation tasks using the render plan's red/green commands.
- [ ] Review each task separately. Pixel ordering, palette version invalidation, and queued-buffer ownership are blocking review criteria.
- [ ] Capture before/after reference frames for every affected special stage and water/overlay scene.

### Task 4: Execute Low-Risk Rewind/Trace Tasks 1-2 And 6-9

- [ ] Repair the benchmark before using its numbers.
- [ ] Execute metadata caches, lazy diagnostics, dense event indexing, source indexing, and pruning/lifecycle cleanup using the rewind plan's red/green commands.
- [ ] Review each task separately. Comparator output, event order, coverage results, snapshot bytes, and reset ordering are blocking criteria.

### Task 5: Execute Structural Rewind Tasks 3-5

- [ ] Implement immutable scalar slabs and one-pass object snapshots only after byte-equivalence tests exist.
- [ ] Implement bounded multi-segment caching only after poison rollback and alternating-boundary tests fail on the current implementation.
- [ ] Run the mutation benchmark before paged COW. If it disproves the bottleneck, document the result rather than adding ownership complexity; this satisfies the candidate investigation but not an unmeasured rewrite.
- [ ] Run rewind torture, graph identity, coverage, COW, and trace-seek tests after every structural commit.

### Task 6: Execute GPU/Driver-Sensitive Render Tasks 5 And 7

- [ ] Implement virtual-ID-safe overflow batching with the direct fallback retained until atlas/page/order parity is proven.
- [ ] Implement tilemap ring upload only after upload/GPU measurements and forward/backward/wrap tests establish the current cost and correctness oracle.
- [ ] Run repeated GL destroy/reinit tests and visual captures before removing fallbacks.

### Task 7: Cross-Subsystem Determinism And Performance Verification

- [ ] Run audio bit-exact and rewind-audio tests from the audio plan.
- [ ] Run render/special-stage focused and S3K safety tests from the render plan.
- [ ] Run rewind, coverage, trace, and benchmark gates from the rewind plan.
- [ ] Run `mvn -Ptrace-replay "-Dtest=*TraceReplay" "-DfailIfNoTests=false" test` and update `docs/status/trace-frontier-log.md` only when a full sweep or frontier movement requires it.
- [ ] Run `mvn test`, then `mvn package`; require successful exit codes.
- [ ] Re-run the same warmed allocation, retained-memory, frame-time, rewind latency, draw/upload, and GPU measurements as baseline.

### Task 8: Integration Report And End-To-End Review

**Files:**
- Create: `docs/architecture/validation/performance/2026-07-integration-report.md`
- Modify: `CHANGELOG.md`

- [ ] Record every changed file/commit, red-green evidence, focused/full commands, baseline/after measurements, disproved candidates, and residual risks.
- [ ] Map every design acceptance criterion to evidence. No criterion may be marked complete solely from an agent report.
- [ ] Dispatch an independent end-to-end reviewer with the design, all three plans, base SHA, and head SHA.
- [ ] Fix and re-review every blocker and Important finding.
- [ ] Run fresh final `mvn test` and `mvn package` after review fixes.
- [ ] Stop for human review; do not merge into `develop` without explicit confirmation.

## Self-Review

- Scope coverage: all candidates from audio, special stages, render pipeline, memory, rewind, trace, and test audits map to a subsystem task.
- Determinism coverage: audio arithmetic, render tie order, palette mutation, virtual IDs, object/RNG identity, trace comparison, COW isolation, and single-owner Maven execution are explicit gates.
- Rollback: evidence-gated structural changes retain current fallbacks or produce a documented disproval result.
- Placeholder scan: the plan contains no `TODO`, `TBD`, or unspecified implementation steps.
