---
name: trace-replay-bug-fixing
description: Diagnose or fix OpenGGF trace replay divergences, bootstrap mismatches, and run-boundary failures using ROM evidence.
---

# Trace replay bug fixing

Find the earliest causal disagreement between the engine, recorder, and shipped
ROM. A passing fixture proves only the fields and rows it actually compares.

## Establish a trustworthy measurement

Use JDK 21 and absolute ROM paths with the properties in root `AGENTS.md`.
Before reporting suite numbers, read the measurement-hazard table in
[briefing-trace-rounds.md](../../../docs/agent-workflow/briefing-trace-rounds.md).
Confirm the requested concrete test exists and which fixture/segments it drives.

Example focused run (set the variables to discovered paths and a real class):

```bash
mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 \
  -Dsurefire.runOrder=alphabetical "-Dtest=$TRACE_TEST" \
  "-Dsonic1.rom.path=$S1_ROM" "-Dsonic2.rom.path=$S2_ROM" \
  "-Ds3k.rom.path=$S3K_ROM" test
```

The ordinary suite excludes replay tests. `trace-replay` covers the release-6
scope; `trace-replay-r7` covers the deferred scope. Check
[trace-scope-release-6.md](../../../docs/status/trace-scope-release-6.md)
and `pom.xml` for the test's lane; run both separately when shared behavior
reaches both. Do not hide an empty selection with `failIfNoTests=false`.
Use fresh reports below this worktree's `target/`; save prior evidence before
clearing generated reports. The pom binds fork count to `surefire.forkCount`
and reports to `openggf.surefire.reports`, not the similarly named bare flags.

Read the class's Surefire result and the corresponding JSON/context files in
`target/trace-reports/`. Check errors, skips, compared rows, and process completion;
stale XML, a skipped ROM test, or a crashed fork is not a pass. Reproduce suspected
order/native/config contamination in isolation and report it until resolved.

## Diagnose and implement

1. Parse error spans and cascades, including predecessor segment reports. An early
   self-healing blip may be separate from the later cascade. Record both.
2. Inspect existing physics and aux evidence before adding probes. Trace report
   and aux frames are row indices, not the CSV `frame` column. Numeric physics
   columns are generally hexadecimal; boolean/enum columns are exceptions. Use
   `TraceFrame`'s parser as the format authority.
3. Align sampling boundaries and name each clock: trace row, manager execution
   count, ROM `V_int_run_count`, and `Level_frame_counter` can differ. Compare a
   shared event/value before calling anything “N frames late.”
4. Locate the owning ROM routine using the relevant disassembly skill. Search
   the engine for that symbol too: the behavior may already exist. Prove the
   suspected branch executes, then compare ordering, widths, state visibility,
   and the shipped `FixBugs=0` path.
5. Change the smallest accurate owner. Cite the ROM routine for each behavioral
   constant or predicate. Shared changes require checking affected games and
   sibling implementations of the same contract.
6. Re-run the target and affected regression tests against a matched baseline.
   Follow root integration/test policy. Append command, commit/worktree context,
   error count, and before/after frame/field to
   [trace-frontier-log.md](../../../docs/status/trace-frontier-log.md) when required.
   Preserve its historic prefix and use repository-relative paths in added entries.

A fix must hold for another BK2: no fixture-fitted constants, tolerances,
zone/route/frame exceptions, or trace-to-engine state hydration. Bootstrap
snapshots are comparison data too. A local reseeding probe must be removed before
committing. Do not force a green result when the causal evidence is incomplete.

## Hardware timing boundary

`trace_schema: 5` is the sole live contract; recorder names/versions are opaque
provenance and never behavior selectors. Read the
[hardware-timing contract](../../../docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md)
when touching timing authority. It permits two scheduling inputs:

- Readiness release delays matching prepared, production-submitted ROM work;
  kind, ordinal, fingerprint, and service boundary must match.
- Per-row lag admission chooses an already-implemented ROM loop, including
  `VBlank_Lag`; it supplies no gameplay values and creates no work.

Neither shape may use physics/aux comparison values, key on a route/game/frame,
or decide what work exists. S1 PLC, S2 DPLC, and S3K Kosinski timing are in contract
scope; implementation and fixture coverage differ. Consult the contract's
coverage status rather than inferring support. Keep `TestHardwareTimingAuthorityGuard`
green. Derive frame-counted service from the ROM before introducing timing input.

## Read only for the matching failure

- Clocks, slot occupancy, queues, recorder defects, or visual run boundaries:
  [diagnostic reference](references/diagnostics.md).
- Missing recorder evidence or fixture regeneration: use `bizhawk-headless-trace`
  and TraceChaser's capture/behavior docs. Capabilities must describe real captured
  evidence; adding metadata flags cannot create missing observations.
- Video reproduction: use `trace-capture`; a completed video continues through
  desyncs and is not parity evidence.
- Multiple independent trace frontiers: use `trace-green-fleet`.
