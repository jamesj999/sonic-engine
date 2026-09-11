---
name: s1-trace-replay
description: Use when running or adding Sonic 1 replay fixtures. For capture use bizhawk-headless-trace; for a replay failure use trace-replay-bug-fixing.
---

# Sonic 1 trace replay

Choose the requested existing fixture/test before recording anything. Test-only
and report-interpretation tasks do not need a new capture. Discover fixture and
class names from current files rather than a fixed GHZ/MZ inventory:

```bash
rg --files src/test | rg 'TestS1.*TraceReplay|traces/s1/.*/metadata.json'
```

## Run an existing fixture

Use JDK 21 and the discovered absolute Sonic 1 REV01 ROM path:

```bash
mvn "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dtest=<TestClass>" test
```

Check skips and the newly written report under the current worktree's
`target/trace-reports/`. Inspect the first failing frame/field and error count;
read `../trace-replay-bug-fixing/SKILL.md` before diagnosing or changing behavior.
Current baselines live in `docs/status/trace-frontier-log.md`, not this skill.

## Capture or fixture changes

Use `../bizhawk-headless-trace/SKILL.md` for native capture prerequisites,
commands, output validation, and complete-run publication. TraceChaser lives in
`tools/tracechaser/` and owns the current producer contract. Keep durable captures
in an explicit task directory outside the repository and give the recorder a
new output path so it cannot overwrite evidence.

Before adopting a capture, verify ROM identity, movie/profile, schema, frame
coverage, and successful producer validation. Preserve provenance and manifests
required by the current contract. Commit physics/aux payloads compressed as
`.csv.gz` / `.jsonl.gz`; do not add uncompressed large payloads.

The live contract is v5. Use the fixture metadata, current parser/producer, and
hardware-timing design contract to interpret fields; recorder version strings
are provenance, not replay-mode selectors. Do not resurrect old numbered CSV
column tables or infer a field's meaning from its name alone.

## Evidence boundary

Physics and auxiliary trace data compare behavior; they do not hydrate gameplay
state. Timing admission has only the dedicated contract's permitted shapes.
Consult that contract before modifying queue/lag inputs rather than extrapolating
from another game's recorded coverage.

When the frontier moves or a fix lands, update the trace frontier log with the
command, commit/worktree context, result, error count, and first error frame/field.
For video evidence use `../trace-capture/SKILL.md` after the replay is selected.
