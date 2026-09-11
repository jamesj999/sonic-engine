# Coordinated audio milestone: review and integration evidence

## Scope

This report accompanies the [implementation plan](../../plans/audio/2026-09-04-audio-correctness-and-evidence.md).
The delivery base is `develop` at `4296bc291`. Only this round's four isolated
worktrees are owned by this workflow; pre-existing worktrees and modified
disassembly submodules are preserved.

## Independent task reviews

### S3K admission correction

Reviewer: independent `review_s3k` agent, gpt-5.6-sol/high. Range:
`4296bc291..5aca88c31`. Verdict: approved, no actionable findings.

The review checked the retail `fix_sndbugs=0` routine, unconditional PSG noise
silence for every PSG header, declared header order, default-disabled profile,
S3K profile enablement, presentation-copy survival, and unchanged S1/S2 paths.
It verified that the adjusted first-track-pass test still asserts the
ROM-derived `DF`-before-`E7` ordering, and that the new matching prefix is a
hard assertion rather than a measurement-only report. The reference and
comparator were unchanged. Stale-IX behavior and the following lazy takeover
write remain explicit limitations.

The reviewer and lead inspected the fresh 54-test focused result and full
oracle output. The prefix matches 1,570 services (ordinals 0–1569); the next
first difference is service 1570/event 39, PSG `E7` versus `FF`. DAC remains
run 338/byte 0, `88` versus `7F`. These are separate comparison axes.
Integrated into the coordination branch at `f34bb06b0`; the frontier-log
addition merged automatically with the historical attribution correction.

### Physical capture

Early independent review: `review_capture`, gpt-6-astra/high. Final approval
is pending the completed implementation. Lead design review required distinct
per-chip clock domains, preservation of legacy callbacks, a surviving
transaction-rollback discontinuity, and an unknown provenance marker for a
DAC data strobe resumed from a snapshot without diagnostic origin.

The early review found missing non-bus/admission-restore boundaries, missed
initialization in the CLI's late attachment, insufficient live-chip replay
and physical-opt-in non-interference tests, and suppressed export I/O errors.
These were returned to the worker before integration. Strobe placement,
per-chip clock advancement, disabled-path allocation and full-session rollback
ordering were sound by inspection; that is not a final test or merge verdict.

The committed implementation `aefd59738` resolved those findings. Follow-up
`75c10b85a` adds fail-closed, test-only replay-segment checks and corrects reset
provenance: reset preserves configuration and cannot make an unknown state
known. Explicit YM mode 3 identifies the constructor configuration. Final
independent verdict: approved, no blocking findings. The reviewer verified
48 targeted XML test results with zero failures/errors/skips. No general
production replayer or whole-mixer parity claim was added.

### Benchmark and evidence tooling

Independent review: `review_evidence`, gpt-5.6-sol/high, range
`4296bc291..856f91776`. Changes requested: the retained snapshot check used an
amplitude sum rather than the historical experiment's sample-wise comparison;
the result assembler accepted malformed types/shapes; and the validation prose
conflated checkout identity verification with the runner's content-hash check.
These findings were returned to the owner before integration.

The reviewer independently passed the standalone boundary tests, verified
upstream checkout identities and file locks, and checked compiler dependency
output against all locked ymfm inputs. Lead pre-commit review also required
validation of both Java executables, physical path containment before directory
creation, and content hashes for the compiled local core/harness inputs as well
as pinned upstream sources. Smoke timings are not publishable.

Follow-up `856f91776..7ef9e44d8`: approved, all three findings resolved. The
reviewer reran boundary/syntax checks and verified the smoke's input hashes
against the committed sources. Snapshots compare complete stereo sequences;
Java/C output uses ordered signed-sample FNV-1a (a diagnostic compact hash,
not proof against collisions). The strict record validator rejects the tested
malformed shapes, booleans, invalid timing values and dimension mismatches.
Integrated at `2235ea7c0`.

A fresh integration checkout exposed unrecorded executable bits on the three
shell entry points (`core.filemode=false` hid the worker's local chmod).
The lead explicitly staged their `100755` modes and reran the standalone
boundary test successfully in the integration worktree.

## Baseline

At `4296bc291`, Maven used OpenJDK 21.0.11 and all three verified absolute ROM
paths. The commands are recorded in the implementation plan.

| Invocation | Reported executions | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| Ordinary `mvn -Dmse=off -B` with three ROM properties, `test` | 16,465 | 0 | 0 | 40 |
| Separate `mvn -Dmse=off -B -Pguards test` | 609 | 0 | 0 | 0 |

Ordinary reports were archived before guards reused the report directory.
The console contains repeated class-result lines: 2,027 lines and 1,983 final
XML files, whose case total is 16,369. Both sources have empty failure/error
sets. Preserve execution and final-XML counts separately; regression
comparison uses archived per-test outcomes, not an assumed historic total.

## Combined and merged verification

Whole-branch independent review: `review_s3k`, gpt-5.6-sol/high, range
`4296bc291..ed9fd1e1b`. Verdict: approved with no actionable findings, subject
to the required combined and post-merge verification. The review checked
cross-lane ownership, observer compatibility, diagnostic authority, script
modes, release/handover consistency and explicit deferrals. It found no native
runtime dependency, backend selection, trace-schema change or submodule edit.

Combined ordinary tests at `ed9fd1e1b`: 16,482 reported executions, zero
failures/errors, 40 skips (5:04). Archived per-test comparison found no new
failures or newly skipped tests. The sole absent name is the intentionally
renamed frontier assertion (`theOracleReachesTheTitleMusicLoadsTrackCadence`
became `theFullOraclePinsTheNextSfxWriteFrontier`); 17 net new cases execute.

The first combined guard run completed with 609 tests, one failure, zero
errors/skips: `TestArchUnitRules.core_runtime_cycle_cluster_does_not_gain_top_level_edges`
reported the new `tools -> version` edge. Lead and independent `review_capture`
reviewed both version classes: they depend only on JDK/internal version types,
with no runtime back-edge. The approved correction explicitly admits only this
leaf build-provenance dependency with a rationale comment. Cycle detection and
all other ratchets remain unchanged. This is an intentional architecture
decision, not a pre-existing baseline failure. The initial red reports are
archived; the full guard suite must pass again before integration.

The full guard rerun at `1da078e31` passed: 609 tests, zero failures/errors/
skips (2:04). Archived per-test comparison matches the baseline with no added,
missing, newly skipped or failing guard cases. Production code is unchanged
from the ordinary-tested `ed9fd1e1b`; intervening changes are the reviewed
guard declaration and verification prose.

The union of the S3K lane and physical-capture lane's focused selections was
then run together in the coordination worktree with all three absolute ROM
properties: 102 tests, zero failures/errors/skips (31.599 seconds). The exact
individual selections are in the linked lane reports; the combined run is
`target/audio-round-development-focused.log`. The standalone benchmark
boundary test also passes in this worktree. No performance timing from the
contended integration host is promoted to benchmark evidence.

## Final delivery — 2026-09-05

After explicit user approval, the unchanged staged milestone merged into
`develop` as `078e5df6f`, without conflicts or branch switching. A fresh fetch
confirmed `origin/develop` was still `4296bc291`; unrelated updates to `next`
were not integrated. The three pre-existing modified disassembly submodules
were preserved.

Post-merge commands used Maven's JDK 21 and the same verified absolute ROM
paths as the baseline (export `SONIC1_ROM`, `SONIC2_ROM`, `S3K_ROM` first):

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Dsonic1.rom.path=$SONIC1_ROM" "-Dsonic2.rom.path=$SONIC2_ROM" "-Ds3k.rom.path=$S3K_ROM" test
LUA_BIN=lua5.4 mvn -Dmse=off -B -Pguards test
tools/audio/fm-core-benchmark/tests/test-tool.sh
```

| Check at `078e5df6f` | Executions | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| Ordinary suite (5:30) | 16,482 | 0 | 0 | 40 |
| Separate guards (2:13) | 609 | 0 | 0 | 0 |
| Standalone benchmark boundary checks | pass | — | — | — |

Archived per-test comparisons show no new failures or newly skipped tests
against the original baseline. Ordinary cases exactly match the combined
development result; guards have no added, missing or changed outcome. The
intentional frontier-test rename is the only absent baseline ordinary name,
as documented above. Final logs, JSON comparisons and compressed reports live
under `target/audio-round-merged-*`. Development and focused evidence was
also archived outside the checkout in the explicit task directory
`audio-coordination-20260904` before cleanup. Generated build/test outputs
discarded during cleanup are reproducible; no unmerged work was discarded.

`078e5df6f` was pushed to `origin/develop`. Only this round's four worktrees
(`audio-coordination`, `audio-round-s3k`, `audio-round-capture`,
`audio-round-evidence`) and their fully merged local branches were removed,
then worktree metadata was pruned. Older worktrees were left intact. This
final delivery-record update is documentation-only; the tested production,
tooling and test sources are unchanged.

## Remaining product decisions

Java Nuked remains the production FM core. Complete full-game audio parity,
the independent DAC discrepancy, low-end/platform performance budgets, and
release listening validation are not closed by this milestone. The optional
benchmark harness does not make native integration or a fast backend a
release dependency.
