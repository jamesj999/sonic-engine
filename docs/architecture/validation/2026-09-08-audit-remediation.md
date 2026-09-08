# Audit remediation validation

## Scope and baseline

The [delivery plan](../plans/2026-09-08-audit-remediation/README.md) defines nine tasks. Three Luna workers at max reasoning implement independent groups in isolated worktrees; the coordinator reviews each task before acceptance. This record describes observed evidence, not estimated coverage.

The updated base is `8392494a0a1f1af966776da1dcbe4698db4bb7df`. Its completed audit runs remain the unchanged-code baseline: ordinary Maven console aggregate 17,049 tests, 0 failures/errors, 44 skips; guards 613 tests, 0 failures/errors/skips. Reports and logs are in the main worktree `target/audit-20260908/`. Nested test discovery causes duplicate identities and different XML/console counts; baseline XML contains 17,044 testcase nodes and 15,920 distinct class/name pairs. Compare identities and full failure messages, not counts alone.

All baseline failures/errors are empty. Forty-three ordinary skips are allowed for absent capabilities or explicit opt-in; the one unclassified allocation probe is task 04. Tests use existing absolute S1/S2/S3K ROM paths and Lua 5.4; no display/device coverage is inferred from headless checks.

## Task acceptance ledger

| Task | Implementer group | Coordinator verdict | Evidence |
|---|---|---|---|
| 01 Save ordering | Persistence | Accepted; full/guards identity checks passed | `0855fdd94`: complete operations share FIFO ordering; no per-instance retained futures; regressions cover cross-manager read/delete/synchronous write and interrupted waits. Coordinator review removed an unbounded test observer |
| 02 SBZ3 rewind ownership | S1 rewind | Accepted; full/guards identity checks passed | `75c9a243d`: real ObjectManager absent/present, forced recreation, reuse, production registration/callback, repeated seeks and ordinary duplicate/offscreen cases |
| 03 Switch RAM rewind | S1 rewind | Accepted; full/guards identity checks passed | `75c9a243d`: production registry restores all 16 bytes; repeated restore, defensive accessor, missing-snapshot reset and consumer-facing pressed gates. Only obsolete switch baseline entry removed |
| 04 Release skip policy | Delivery | Accepted; full/guards identity checks passed | `f9d054f64`: exact opt-in rule inspected; coordinator classifier allows all 44 baseline skips with zero unknown/required/undeclared/stale; policy unit suite 20 tests, one absent historical-report skip |
| 05 Config migration | Persistence | Accepted; full/guards identity checks passed | `0855fdd94`: coordinator reran the original failed-YAML/restart probe; legacy JSON remains, no backup is created, both startups retain `s1`. Public save API remains compatible |
| 06 Discord presence | Presence | Accepted; full/guards identity checks passed | `87b06bf5f`: coordinator and independent Luna reviewer verified coalescing, caller-thread snapshots, nanosecond shutdown deadline and production client lifecycle with injected blocked open/send transports |
| 07 Current artifact launch | Delivery | Accepted; full/guards/classifier passed | `7dbc6af7e`: coordinator launcher tests 9 passed; manifest selection, stale-only failure, custom names, spaces, missing/invalid metadata and exit codes. Windows contract is statically inspected, not executed |
| 08 Palette teardown | Presentation | Accepted; full/guards identity checks passed | `d36375987`: coordinator original probe now reports inactive fade, mask zero and white unchanged after teardown; headless regression also checks cached palette owners |
| 09 Sound-test ownership | Presentation | Accepted; full/guards identity checks passed | `2d2062b59`: coordinator original interactive probe now succeeds; tests cover concurrent close, retained cleanup after timeout, direct-owner retry and daemon owner policy. Strict producer ownership assertion is unchanged |

## Integration and completed verification

All nine tasks are accepted. Worker source commits were merged into the isolated coordination tree without conflicts; overlapping release notes were reconciled into their existing themes, with shutdown wording limited to the actual bounded-wait guarantee.

| Final worker tree | Tested source | Ordinary result | Guards result | Baseline identity comparison |
|---|---|---|---|---|
| Persistence | `0855fdd94` | 17,055 XML nodes; 0 failures/errors; 44 skips | 613; 0 failures/errors/skips | All retained; six added |
| S1 rewind | `75c9a243d` | 17,056 console tests; 0 failures/errors; 44 skips | 613; 0 failures/errors/skips | All retained; seven added |
| Delivery | `8c7c14913` | 17,049 console tests; 0 failures/errors; 44 skips | 613; 0 failures/errors/skips | All retained; none added |
| Presence | `87b06bf5f` | 17,056 XML nodes; 0 failures/errors; 44 skips | 613; 0 failures/errors/skips | All retained; seven added |
| Presentation | `2d2062b59` | 17,054 console tests; 0 failures/errors; 44 skips | 613; 0 failures/errors/skips | All retained; five added |

The coordinator independently compared each final worker XML identity set, full failure/error messages and skips with baseline. Persistence/presence completed console output was retained in the implementing agent's tool session, not saved to a log file; the durable artifacts are their XML archives and agent-attested exact command receipts. The other worker and combined runs have saved completed logs. Do not interpret XML-node counts as console totals.

Combined source `d1f64e1ee` completed the ordinary suite (17,074 console tests, 0 failures/errors, 44 skips; 5m15) and fresh-JVM guards (613 tests, 0 failures/errors/skips; 2m19). Every baseline identity remains, with 25 intended new ordinary regressions. All 44 skips match explicit rules, with zero unclassified, required-skipped, undeclared-input or stale entries. Packaging completed in 18.907s; the generated manifest names `OpenGGF-0.6.prerelease`, and that exact dependency jar exists. No source changed after these runs.

Main integration and post-merge verification remain pending at this record's first verification checkpoint. The final publication and cleanup state is reported separately after execution.

## Commands and retained evidence

Each combined Maven run used Java 21 and this command, with `-Pguards` added for guards and distinct `RUN` values `audit-combined-ordinary` / `audit-combined-guards`:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off \
  -Dsonic1.rom.path=$PROJECT_ROOT/s1.gen \
  -Dsonic2.rom.path=$PROJECT_ROOT/s2.gen \
  -Ds3k.rom.path=$PROJECT_ROOT/s3k.gen \
  -Dopenggf.surefire.reports="$WORKTREE/target/$RUN/reports" \
  -Dopenggf.trace.reports="$WORKTREE/target/$RUN/trace-reports" \
  -Dopenggf.test.diagnostics="$WORKTREE/target/$RUN/diagnostics" test -B
```

Set `PROJECT_ROOT` to the absolute main checkout path and `WORKTREE` to its `.worktrees/ai-audit-remediation` checkout; both must resolve to absolute paths. Package: `LUA_BIN=lua5.4 mvn -Dmse=off -DskipTests package -B`. Tooling: `python3 -m unittest discover -s tools/testing -p 'test_*.py'` (71 tests, one absent historical-fixture skip). Skip classification: `python3 tools/testing/classify_surefire_skips.py --reports target/audit-combined-ordinary/reports --policy tools/testing/release-skip-policy.json --capabilities gl=false,s2_bk2=false,s3k_observations=false,s1_bizhawk_reference=false,audio_reference_files=false --check-evidence`. All 31 tracked Bash scripts passed `bash -n`; agent instructions and skill mirrors match; all 19 local links in the 11 task/validation documents resolve; branch policy checks passed.

`AUDIT_EVIDENCE` denotes the external task evidence directory recorded in the final delivery message. It contains per-worker report archives, command receipts, original/fixed probe logs, and coordinator JSON identity comparisons. Combined `receipt.json` files record exact argv, source commit, timestamps and exit status beside saved logs. This directory is outside Git and must be preserved when task worktrees are removed. These checks do not establish live GL/OpenAL, Windows execution or a live Discord endpoint; the latter uses production client code with injected blocked transports.

## Verification investigations

The delivery worker ordinary run reported `TestAudioPresentationProducer#warmedProducerAllocatesNoFramePacketOrConsumerArray`: expected 0 allocated bytes, observed 80. Its source already documents historical full-suite-only 80/176-byte measurement artefacts, but this alone does not waive the current failure. The coordinator's focused control on unchanged base `8392494a0` passed one test with no skips using `mvn -Dmse=off -Dsurefire.forkCount=1 '-Dtest=TestAudioPresentationProducer#warmedProducerAllocatesNoFramePacketOrConsumerArray' -Dopenggf.surefire.reports=target/audit-20260908/remediation-baseline-audio-allocation test -B`. Its completed log is `target/audit-20260908/remediation-baseline-audio-allocation.log`. The subsequent clean full run on `8c7c14913` completed successfully in 6m11 with 17,049 console tests, 0 failures/errors and 44 skips; all 15,920 baseline class/name identities remain present. The 80-byte failure did not recur. An intervening run used a relative temporary directory and produced path-contract errors; these are preserved separately from the clean run. No audio assertion or source was weakened. Delivery guards completed successfully in 2m24: 613 tests, no failures/errors/skips, no missing baseline identities. Final skip classification permits all 44 ordinary skips with zero unclassified/required/undeclared/stale entries.

The coordinator additionally reran the original palette reset and interactive audio probes against the presentation build: palette teardown reports `active=false mask=0` and white `ffffff`; an executor-driven `presentFrame()` now succeeds. These checks use no display or physical audio device. Combined Python tooling completed 71 tests with one expected absent historical-report skip; all 31 tracked Bash scripts parse, and agent instruction/skill mirrors match.
