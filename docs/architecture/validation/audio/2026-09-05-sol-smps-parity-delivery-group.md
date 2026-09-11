# SMPS evidence delivery group: coverage, S1 probe and S3K ring adapter

Status: delivered. Merged and post-merge verified, pushed through `be4428602`,
and completed worktrees/local branches removed.
This group improves evidence and a diagnostic adapter, not full SMPS parity.

## Reviewed candidates and baseline

| Candidate | Frozen commit | Focused | Ordinary tests / skips | Separate guards |
|---|---|---|---|---|
| Complete-run coverage report | `652c3b4a5` | 9 | 16,674 / 43 | 609 |
| S1 restore prerequisite diagnostic | `856bba03b` | 11 | 16,676 / 43 | 609 |
| S3K diagnostic ring selection | `3e893708c` | 4 | 16,669 / 43 | 609 |

All final candidate runs have zero failures/errors. Focused and guard runs have
zero skips. Main develop was fetched and fast-forward pulled at `a0b63f61d`;
there were no upstream changes. Fresh baseline ordinary and guard runs pass
16,666 tests with 43 skips and 609 tests without skips respectively. Their
15,717 distinct ordinary outcomes and 609 guard outcomes exactly match the
previous verified source baseline. Candidate comparisons preserve every
baseline identity/status/message, adding eight, ten and three passing ordinary
identities respectively. No rename or removal allowance is used.

All ordinary/guard commands use JDK 21 and these absolute, verified inputs:

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path="${ROM_ROOT}/s1.gen" \
  -Dsonic2.rom.path="${ROM_ROOT}/s2.gen" \
  -Ds3k.rom.path="${ROM_ROOT}/s3k.gen" test -B
mvn -Dmse=off -Pguards \
  -Dsonic1.rom.path="${ROM_ROOT}/s1.gen" \
  -Dsonic2.rom.path="${ROM_ROOT}/s2.gen" \
  -Ds3k.rom.path="${ROM_ROOT}/s3k.gen" test -B
```

`${ROM_ROOT}` must resolve to the main checkout's ROM directory, not a worktree
relative path. Baseline logs/XML are under main
`target/audio-parity-delivery-baseline-evidence/`; the six candidate comparisons
are `target/audio-delivery-*-candidate-*-comparison.log`.

## What the evidence establishes

- The complete-run report distinguishes declared producer/layer authority from
  executed evidence. All three shipped fixed profiles still report
  `full_parity=false`; narrow parity adapters are explicitly outside its scope.
  Capture failures retain their diagnostics and exit 4, wrong selected-profile
  correlation exits 2, and only a fully comparable correlated match exits 0.
- The S1 probe runs canonical production BK2 input independently of reference
  state. Its final runtime output stops at row 972: native request `B5`, engine
  no request. Rows 860–971 match, but pre-860 reference request history is absent.
  No one-up/restore boundary or exact restore-write comparison is reached.
- S3K's capture-local ring transform resolves consumed raw `33` to alternating
  `34/33`. It moves the non-DAC/state oracle frontier from service 2357 to 2409,
  `MUS_PSG1.overridden` (`true` versus `false`). It does not change production
  request scheduling, and does not establish DAC timing parity.

The [coverage record](2026-09-05-complete-run-coverage-summary.md),
[S1 diagnostic record](2026-09-05-s1-restore-diagnostic.md) and
[ring record](2026-09-05-sol-smps-parity-cycle6.md) retain focused commands,
negative controls and limitations. Independent Sol review found no blocking
issue in the final S1 and ring source; root reviewed all three candidates.

## Corrections retained in the audit

Coverage's first guard run failed 609/1 because the reporter was classified as
an authenticated producer. Only that reporting class was classified alongside
Comparator/Report, and an adversarial guard now rejects authenticated producers
using it. Declaration-as-match and mismatch-as-full-parity mutations fail.

S1's first guard run failed 609/2 on direct singleton access. Configuration now
uses the context established by the hidden GL lifetime; a new real probe
reproduced the row-972 result. The first ordinary run lacked a preserved XML
snapshot and is not accepted as final evidence; the fresh final run has its own
reports. Reversed admission/request and unrelated lifecycle/service controls
reject premature restoration claims.

The ring bypass mutation restores the old service-2357 failure. An attempted
test that merely skipped a service call was rejected: it did not exercise retail
one-up discards. That behavior remains explicitly unverified here. A proposed
partial one-request S3K service was also rejected because retail consumes three
queue slots and distinguishes one-up clearing from restore deferral.

## Integration and archives

The three merges are `e9a0c0850`, `28f48613f` and `ad152601b`. They were
conflict-free; Git combined the README and 0.6 release entries without losing
either candidate's notes. Coverage's earlier worktree baseline merge had a
release-note conflict, resolved by preserving both header-order and coverage
entries. The user's three modified disassembly submodules remain untouched.

Candidate archives under `${EVIDENCE_ROOT}` preserve logs/XML, mutations and
generated local configuration/rewind reports, not reusable Maven build trees:

| Archive | SHA-256 |
|---|---|
| `coverage-candidate-652c3b4a5.tar.gz` | `ec8c29b5acda0a9e5a2e2025b195763c1cd6b640b162d7a7c35ae6d402edd415` |
| `s1-diagnostic-candidate-856bba03b.tar.gz` | `ae5d18fddf5dab072fe2531bd73deae0949932ecd0cfcac0f0c7120bbd22d178` |
| `ring-candidate-3e893708c.tar.gz` | `a43061d3f6663542a54124bec718f1cba93b284af494133707cf9385d72bb2df` |

The final external S1 `-i.json` probe has SHA-256
`2a3166b3f9e5cd0bde99cb2825fc9772b8c1a65fe2fcb6ff48e13987811d6daa`,
independently checked by root. Sealed native A, the prior probe and the initial
GL-abort transcript remain preserved externally. No native publication,
TraceChaser pin change or authenticated fixture installation is part of this
group.

## Combined post-merge verification

Develop `f56d4fae1`, including the integrated handover, passes the ordinary
suite with 16,687 tests, zero failures/errors and 43 unchanged skips. Its
separate structural run passes all 609 guards. A final focused run of the
coverage, S1 diagnostic/unavailable-oracle and S3K ring/frontier controls passes
24 tests with zero failures/errors/skips.

Exact comparison preserves all 15,717 baseline ordinary outcomes and adds
precisely the union of the candidates' 21 passing identities. Comparisons
against each candidate independently find no removed or changed outcome: the
coverage candidate gains only the other 13 tests, S1 gains 11 and ring gains 18.
All four guard comparisons preserve the same 609 outcomes exactly. There are
no rename, removal or skip allowances.

Main `target/audio-parity-delivery-postmerge-evidence/` contains separate
ordinary, guard and focused logs/XML plus exact commands. The baseline,
post-merge evidence, all comparison logs and comparison helper are archived at
`${EVIDENCE_ROOT}/delivery-postmerge-f56d4fae1.tar.gz`, SHA-256
`00c94f54b210f4984b4956d92ce09c72b5261cf47bbedda6508ae9eb0ba6359c`.

## Delivery and cleanup

Only develop was pushed, through verification commit `be4428602`. After
confirming clean tracked/untracked state and merged ancestry, these completed
worktrees and their local branches were removed:

- `sol-audio-coverage-truth` / `bugfix/ai-sol-audio-coverage-truth`;
- `openggf-sol-s1-restore-diagnostic` / `bugfix/ai-sol-s1-restore-diagnostic`;
- `sol-s3k-ring-dispatch` / `bugfix/ai-sol-s3k-ring-dispatch`.

Stale worktree metadata was pruned. Ignored files were inspected: build output,
generated configuration/rewind reports and image caches, plus hook-created ROM
and reference links. Evidence/configuration reports were archived before removal;
source remains in merged Git history. Original ROMs, user disassembly changes,
other worktrees, sealed native capture A and its unpublished TraceChaser work
were preserved. No worktree branch was pushed.
