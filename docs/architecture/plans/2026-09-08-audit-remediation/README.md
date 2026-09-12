# Audit remediation delivery plan

The user authorized implementation, Luna agents at max reasoning, parallel work where independent, and coordinator validation of each completed result. This plan addresses the nine ranked fixes from the 2026-09-08 audit. The initial base is `8392494a0a1f1af966776da1dcbe4698db4bb7df` on `develop`; fetch/fast-forward pull found no update.

## Scope and invariants

- Preserve shipped-ROM behavior, comparison-only trace authority, injected object services, and rewind identity rules.
- Preserve the intentional S1/S2 omission of saved continues. Do not implement the rejected FFmpeg controller-timeout claim.
- Secondary audit suggestions (ROM revision gates, duplicate test discovery, auxiliary trace diagnostics) are follow-up work, not part of these nine fixes.
- Main workspace stays on its current branch. Worker branches stay local. The coordinator owns integration, final verification, push, and removal of only fully accounted-for task worktrees.
- Do not weaken assertions, broaden skip policy, or add baseline exceptions to make failures pass. For fixed rewind gaps, remove the exact obsolete baseline entry.
- Capture meaningful failing regressions before runtime fixes, using deterministic latches/fault injection where relevant. Avoid sleeps as race proofs and avoid source-text tests when behavior can be exercised.
- All production assets stay ROM-backed. No ROM copies or manual links; use existing absolute ROM paths supplied by the coordinator. Never share Maven build output between worktrees.
- Each worker installs repository hooks, follows the documentation obligation checklist, commits with seven accurate trailers, and reports exact commands/results/commit. Never push worker branches.

## Parallel workstreams

| Workstream | Tasks | Shared ownership | Scheduling |
|---|---|---|---|
| Persistence | [01 save ordering](01-save-ordering.md), [05 config migration](05-config-migration.md) | Save/config lifecycle and their tests | First wave |
| S1 rewind | [02 SBZ3 ownership](02-sbz3-rewind-ownership.md), [03 switch RAM](03-s1-switch-rewind.md) | S1 module adapters and rewind guards | First wave, same worker |
| Delivery | [04 release skip](04-release-skip-policy.md), [07 launchers](07-current-artifact-launch.md) | Build-tooling tests and scripts | First wave |
| Presentation | [08 palette teardown](08-palette-teardown.md), [09 sound-test shutdown](09-sound-test-shutdown.md) | Teardown contracts; avoid persistence files | Next free worker |
| Presence | [06 nonblocking Discord](06-discord-presence.md) | Presence service, game-loop wiring and tests | Next free worker |

Tasks have independent acceptance criteria and should use separate commits where practical. Changelog edits may overlap; preserve both themes during integration. The coordinator authors a validation record after reviewing each change. No shared source edits across worktrees.

## Validation and delivery

The unchanged, updated base already has completed audit runs: ordinary Maven console aggregate 17,049 / failures 0 / errors 0 / skips 44; separate guards 613 / 0 / 0 / 0; package successful. Receipts are in the main workspace `target/audit-20260908/`. These exact-commit runs are the baseline and need not be repeated without a source/base change. Nested-test reporting makes console totals different from XML totals; compare failure identities/messages and inspect skips, not totals alone. The skip classifier has one known failure: the allocation probe fixed by task 04.

Each worker runs relevant focused tests, then the full ordinary suite and guards in its own final development tree, with explicit S1/S2/S3K paths and `LUA_BIN=lua5.4`. Use `-Dmse=off` and fresh named report directories under that worktree's `target/`. Coordinate concurrency with the lead. The lead reads diffs and executable evidence on completion, resolves review findings, and records task-level verdicts before accepting integration.

After all accepted changes are combined: run full ordinary suite, guards, skip classification with honest capabilities, and packaging. Merge into the main workspace's unchanged current branch, run the full suite and guards after integration, compare to baseline by identity/message, run policy checks, push only that branch, inspect and remove only clean/fully merged task worktrees and branches. Preserve pre-existing worktrees and dirty disassemblies.
