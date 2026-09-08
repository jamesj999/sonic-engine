# Audit remediation validation

## Scope and baseline

The [delivery plan](../plans/2026-09-08-audit-remediation/README.md) defines nine tasks. Three Luna workers at max reasoning implement independent groups in isolated worktrees; the coordinator reviews each task before acceptance. This record describes observed evidence, not estimated coverage.

The updated base is `8392494a0a1f1af966776da1dcbe4698db4bb7df`. Its completed audit runs remain the unchanged-code baseline: ordinary Maven console aggregate 17,049 tests, 0 failures/errors, 44 skips; guards 613 tests, 0 failures/errors/skips. Reports and logs are in the main worktree `target/audit-20260908/`. Nested test discovery causes duplicate identities and different XML/console counts; baseline XML contains 17,044 testcase nodes and 15,920 distinct class/name pairs. Compare identities and full failure messages, not counts alone.

All baseline failures/errors are empty. Forty-three ordinary skips are allowed for absent capabilities or explicit opt-in; the one unclassified allocation probe is task 04. Tests use existing absolute S1/S2/S3K ROM paths and Lua 5.4; no display/device coverage is inferred from headless checks.

## Task acceptance ledger

| Task | Implementer group | Coordinator verdict | Evidence |
|---|---|---|---|
| 01 Save ordering | Persistence | Pending | Awaiting regression and change |
| 02 SBZ3 rewind ownership | S1 rewind | Pending | Awaiting ObjectManager path evidence |
| 03 Switch RAM rewind | S1 rewind | Pending | Awaiting registered-adapter evidence |
| 04 Release skip policy | Delivery | Pending | Awaiting exact rule/classifier |
| 05 Config migration | Persistence | Pending | Awaiting failed-write/restart regression |
| 06 Discord presence | Presence | Pending | Awaiting bounded backpressure/lifecycle evidence |
| 07 Current artifact launch | Delivery | Pending | Awaiting executable launcher checks |
| 08 Palette teardown | Presentation | Pending | Awaiting palette/session reset evidence |
| 09 Sound-test ownership | Presentation | Pending | Awaiting owner-thread playback and close evidence |

## Integration

No runtime changes accepted or integrated yet. Final combined-tree and post-merge verification, policy checks, push and task-worktree cleanup remain pending.
