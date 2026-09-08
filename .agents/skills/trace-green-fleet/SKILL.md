---
name: trace-green-fleet
description: Coordinate multiple failing OpenGGF trace replay frontiers, including isolated per-trace worktrees and parallel investigation when useful.
---

# Trace green fleet

Use `trace-replay-bug-fixing` for each investigation. This skill adds ownership,
scheduling, and fleet evidence; it does not change ROM-fidelity or integration
rules in root `AGENTS.md`.

## Select the work

Prefer a supplied failing list with test class, fixture/route, first-error
frame/field, and known passing regressions. Otherwise discover the scoped replay
inventory and run its appropriate Maven profile using the measurement procedure
in `trace-replay-bug-fixing`. The ordinary suite is not a trace sweep.

Respect explicit exclusions before discovery or execution. Build a concrete test
allowlist for a scoped request; do not run a broad wildcard and filter afterward.
Account for nested tagged tests and both replay profiles when relevant. Record
infrastructure errors separately from parity failures, without silently ignoring
native extraction or config contamination.

Group likely shared causes before assigning work. Multiple slot, queue, or
sidekick symptoms may be one runtime bug rather than independent zone fixes.
A no-improvement finding with a proven root cause is useful evidence; do not
pressure workers to fit a green fixture.

## Ownership and parallel work

Parallel agents are useful for independent frontiers or a bounded independent
review. Use available capacity and inherit the session's model preferences;
there is no fixed slot count, model ladder, or mandatory paired investigation.
Continue locally when tasks are dependent or agent tools are unavailable.

For concurrent tests/edits, give each worker an isolated worktree branched from
the current main-workspace branch. Follow repository worktree conventions, using
`.worktrees/trace-<game>-<route>` and `bugfix/ai-trace-<game>-<route>` when free.
Each worktree owns its `target/`. Discover ROMs once and pass absolute paths
(`sonic1.rom.path`, `sonic2.rom.path`, `s3k.rom.path`); never create ROM aliases.

A worker brief needs only:

- Exact test/fixture, baseline evidence, objective, and scope exclusions.
- Worktree/base commit and file ownership, including shared surfaces to coordinate.
- ROM/disassembly references and relevant verification commands.
- Delivery boundary: report changes and evidence; leave integration to the owner.

Assign one owner to a shared runtime change or the frontier log. Sequence
conflicting edits instead of racing them. Preserve generated fixtures/captures
outside disposable worktrees until publication, and report every new file,
including untracked artifacts.

## Verification and handoff

For each result record baseline and changed commit, exact commands, pass/fail,
error count, first-error frame/field, ROM citations, regressions, and remaining
uncertainty. Useful states are `green`, `advanced`, `unchanged`, and `unable to
measure`; state regression details independently so advancement cannot hide them.

Compare the whole causal error profile, not only the headline first error.
Review whether the change models actual ROM state, reaches all consumers, and
would hold for another BK2. Queue/dynamic-art failures need capabilities and
admission reasons as described in the diagnostic skill.

Run focused guards for affected games. Shared runtime changes require cross-game
coverage, including the S3K keep-green set in root guidance. Baseline failures
can remain, but introduced regressions block integration. If a correct fix exposes
a compensating defect, investigate it and report that relationship explicitly.

Integrate under root `AGENTS.md`: updated baseline comparison, merge into the
main-workspace branch, post-merge suite comparison, push only that branch, then
verify and clean up fully merged worktrees/branches. Do not substitute a fixed
`develop` target or add a new approval gate. Preserve unresolved work.

Append required frontier evidence to
[trace-frontier-log.md](../../../docs/status/trace-frontier-log.md); keep its
historic prefix unchanged and avoid machine-local absolute paths. The final
fleet report gives each frontier's outcome, significant root causes, remaining
failures, verification evidence, and integrated/pushed commits.
