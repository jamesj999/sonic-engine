# Audit remediation validation

## Scope and baseline

The [delivery plan](../plans/2026-09-08-audit-remediation/README.md) defines nine tasks. Three Luna workers at max reasoning implement independent groups in isolated worktrees; the coordinator reviews each task before acceptance. This record describes observed evidence, not estimated coverage.

The updated base is `8392494a0a1f1af966776da1dcbe4698db4bb7df`. Its completed audit runs remain the unchanged-code baseline: ordinary Maven console aggregate 17,049 tests, 0 failures/errors, 44 skips; guards 613 tests, 0 failures/errors/skips. Reports and logs are in the main worktree `target/audit-20260908/`. Nested test discovery causes duplicate identities and different XML/console counts; baseline XML contains 17,044 testcase nodes and 15,920 distinct class/name pairs. Compare identities and full failure messages, not counts alone.

All baseline failures/errors are empty. Forty-three ordinary skips are allowed for absent capabilities or explicit opt-in; the one unclassified allocation probe is task 04. Tests use existing absolute S1/S2/S3K ROM paths and Lua 5.4; no display/device coverage is inferred from headless checks.

## Task acceptance ledger

| Task | Implementer group | Coordinator verdict | Evidence |
|---|---|---|---|
| 01 Save ordering | Persistence | Source/regression review accepted; group suite pending | `0855fdd94`: complete operations share FIFO ordering; no per-instance retained futures; regressions cover cross-manager read/delete/synchronous write and interrupted waits. Coordinator review removed an unbounded test observer |
| 02 SBZ3 rewind ownership | S1 rewind | Pending | Awaiting ObjectManager path evidence |
| 03 Switch RAM rewind | S1 rewind | Pending | Awaiting registered-adapter evidence |
| 04 Release skip policy | Delivery | Focused review accepted; group suite pending | `f9d054f64`: exact opt-in rule inspected; coordinator classifier allows all 44 baseline skips with zero unknown/required/undeclared/stale; policy unit suite 20 tests, one absent historical-report skip |
| 05 Config migration | Persistence | Focused review accepted; group suite pending | `0855fdd94`: coordinator reran the original failed-YAML/restart probe; legacy JSON remains, no backup is created, both startups retain `s1`. Public save API remains compatible |
| 06 Discord presence | Presence | Pending | Awaiting bounded backpressure/lifecycle evidence |
| 07 Current artifact launch | Delivery | Focused review accepted; package/group suite pending | `7dbc6af7e`: coordinator launcher tests 9 passed; manifest selection, stale-only failure, custom names, spaces, missing/invalid metadata and exit codes. Windows contract is statically inspected, not executed |
| 08 Palette teardown | Presentation | Pending | Awaiting palette/session reset evidence |
| 09 Sound-test ownership | Presentation | Pending | Awaiting owner-thread playback and close evidence |

## Integration

No worker changes integrated yet. Focused acceptance above remains subject to completed development-tree verification. Final combined-tree and post-merge verification, policy checks, push and task-worktree cleanup remain pending.

## Verification investigations

The delivery worker ordinary run reported `TestAudioPresentationProducer#warmedProducerAllocatesNoFramePacketOrConsumerArray`: expected 0 allocated bytes, observed 80. Its source already documents historical full-suite-only 80/176-byte measurement artefacts, but this alone does not waive the current failure. The coordinator's focused control on unchanged base `8392494a0` passed one test with no skips using `mvn -Dmse=off -Dsurefire.forkCount=1 '-Dtest=TestAudioPresentationProducer#warmedProducerAllocatesNoFramePacketOrConsumerArray' -Dopenggf.surefire.reports=target/audit-20260908/remediation-baseline-audio-allocation test -B`. Its completed log is `target/audit-20260908/remediation-baseline-audio-allocation.log`. Final-tree reconciliation remains pending.
