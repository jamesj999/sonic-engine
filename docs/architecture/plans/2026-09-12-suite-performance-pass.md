# Systematic test-suite performance pass

Base: `09f442379` on develop. Worktree: `.worktrees/ai-suite-performance-pass`.

The approved scope is the remaining measured hotspots: complete-run audio
comparison, structural guards, FBZ compatibility, rewind torture, trace-session
launching, FM bit-exact scripts, and direct-connect integration. Execute locally
in this order, using the current repository's bounded validation workflow.

## Coverage and measurement contract

- Preserve every test identity, assertion domain, ROM route/configuration,
  seed, chip type, digest pin, and real network interaction unless an explicit
  equivalence check demonstrates the replacement tests cover the same behavior.
- Retain the comparator's 434,417-frame high-entropy input, compressed/expanded
  size thresholds, and separate 32 MiB JVM. Retain all 500,000 semantic requests.
- Retain all four FM chip types and every pinned cycle/side-log checksum.
- Keep mutable engine state and adversarial fixtures isolated. Share only data
  proven immutable; do not reuse gameplay state to skip traversal coverage.
- Record class/method timings, completed counts/skips, exact known failures,
  and the tested revision. Never infer elapsed savings from fewer selected tests.
- Use one focused baseline for the six named ordinary hotspot classes and one
  structural baseline. After each bounded change, run its relevant focused
  checks; run the required change-based selection once after the set is finished.
- Investigate disputed failures with matched focused checks. Do not fix unrelated
  gameplay issues or rerun broad suites just because known failures remain.
- Delete consumed diagnostics. Durable evidence is the concise ledger below,
  not an archive of Maven logs, fixtures, or failure payloads.

## Work sequence

- [x] Measure the six ordinary classes and the structural guards on the pinned base.
- [x] Audio comparator: measure generation/hash/write/compare phases; remove
  repeated immutable fixture preparation while preserving all large-input checks.
  Owning test: `TestCompleteRunAudioComparator`.
- [x] Structural guards: rank classes and methods, then eliminate repeated imports
  or source analysis only where input and import scope are identical. Keep negative
  fixture imports separate and confirm mutations still fail. Inspect
  `TestAudioPresentationArchitectureGuard`, `TestSmpsSessionArchitectureGuard`,
  and `TestActiveSegmentPayloadAuthorityGuard` first.
- [x] FBZ matrix: distinguish fixture/preflight cost from required route simulation.
  Eliminate redundant preparation without dropping configurations, route frames,
  sidekick audits, or the standalone preflight checks. Owning test:
  `TestFbzCompatibilityMatrix` and its traversal helper.
- [x] Rewind torture: measure reference generation, replay, and snapshot comparison.
  Preserve seeds, checkpoint schedule, keyframe boundaries, and same-fixture object
  identity. Owning test: `TestRewindTorture`.
- [x] Trace launcher: avoid canonicalizing the installed S1 run in every test when
  most tests use only synthetic S2/S3K data. Preserve per-test mutable state and
  every production-launcher assertion. Owning test:
  `TestTraceSessionLauncherRunBranch`.
- [x] FM scripts: measure parsing versus chip cycles. Reuse script preparation or
  simplify execution overhead without removing a cycle, type, script, or checksum.
  Owners: `TestNukedOpn2BitExactScripts`, `NukedOpn2ScriptRunner`.
- [x] Direct connect: inspect host-clock and scheduler boundaries; replace wall-clock
  waiting with controlled host time where possible while retaining real sockets,
  latency proxy, event order, round/vote flow, and teardown. Owner:
  `TestDirectConnectEndToEnd`, with host changes only if required by a reusable seam.
- [x] Review every target's measured disposition, finish release/validation prose,
  preflight, and run the single required selection against the actual pinned base.
- [ ] Integrate into develop without switching its branch; reconcile upstream by
  intent, perform relevant focused integration checks, validate push policy, push
  develop, acknowledge diagnostics, and remove accounted-for worktrees/branches.

## Disposition ledger

Completed focused measurements are recorded in the
[validation ledger](../validation/2026-09-12-suite-performance-pass.md).

| Target | Baseline | Final focused | Disposition |
|---|---:|---:|---|
| Complete-run audio comparator | 132.859 s | 78.641 s | Bulk bounded reads, canonical byte reuse, shared root preparation; 105 cases retained |
| Structural guards | 215.251 s total | 173.141 s total | Exact substring prefilter, source/graph-scoped memo, identical-scope imports; 658 passing checks in completed lane |
| FBZ compatibility matrix | 64.512 s | 64.033 s | Duplicate preflight removed; all 13 full failure messages identical, 26 cases retained |
| Rewind torture | 46.179 s | 46.289 s | Unchanged; replay audio dominates; keep seeds/checkpoints/identity |
| Trace-session launcher | 43.923 s | 10.580 s | Lazy read-only S1 reference reuse; fresh mutable consumers; all 42 cases retained |
| FM bit-exact scripts | 43.537 s | Unchanged | Loop experiment 43.897 s discarded; chip cycles dominate, all 732 pins retained |
| Direct-connect integration | 33.670 s | 2.614 s | Controlled room deadlines with real sockets, latency proxy, and full round/vote flow |
