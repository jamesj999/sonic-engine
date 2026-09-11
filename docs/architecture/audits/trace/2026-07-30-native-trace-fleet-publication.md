# Native trace fleet publication audit — 2026-07-30

## Outcome

The reviewed headless BizHawk build regenerated every reproducible S1, S2, and
S3K trace target in the capture matrix. The capture fleet was installed only
after two explicit approvals:

1. approval of the 447 native capture outputs; and
2. approval of a storage-only revision that replaced 70 plain payload paths
   with deterministic `gzip -9 -n` files.

The final installed inventory contains 447 files and 311,578,257 bytes. Its
portable path/length/SHA-256 manifest has SHA-256
`167f6fd885ebadea619013531eda0438b0ee323f7fe5f00f3cac2f32aef8ee63`.
All 377 unaffected files are byte-identical to the first approved inventory.
Decompressing each of the 70 revised files reproduces its first-approved
payload byte-for-byte.

## Recorder identity and invocation result

- Source commit before publication: `122ed4095caca9218b591cb135930ec071865a5a`
- Native harness SHA-256:
  `ffccbaeb899a06561a979507ff77a5c8dec7ab7b81fcc1749fc0a3652b05fc35`
- Capture invocations: 32 passed, 0 failed
- Trace directories: 152
- Trace rows: 1,447,450
- Unique logical publication targets: 447
- Duplicate logical targets: 0
- Conflicting target bytes: 0

The all-ROM pre-capture harness gate ran 525 tests: 509 passed and 16 failed.
Every failure was an enumerated old-canonical audit/schema/hash migration
refusal. There were no recorder runtime failures, semantic-unit failures,
unclassified failures, or skips.

## Audit coverage

| Game | Directories | Stored rows | PLC states | DPLC envelopes | Direct queue states | Module queue states | Timing events |
|---|---:|---:|---:|---:|---:|---:|---:|
| S1 | 24 | 209,145 | 206,054 | 209,145 | 0 | 0 | 0 |
| S2 | 60 | 455,424 | 388,155 | 455,424 | 0 | 0 | 0 |
| S3K | 68 | 782,881 | 0 | 0 | 782,881 | 782,881 | 2,428 |

Every supported S1/S2 stored row has one complete player-DPLC lifecycle
envelope. Every level row also has the advertised game-specific PLC state.
Special-stage rows intentionally omit level PLC state while retaining the
player-DPLC audit.

Every S3K stored row has exactly two physical queue-state records in stable
order: direct Kosinski first, module Kosinski second. All S3K metadata uses
trace schema 7 and hardware-timing schema 2. This includes the six named-run
special-stage segments (`ss`, `ss_2`, and `ss_3` in both identities), whose
pre-fix captures had empty auxiliary streams.

Validation checked gzip integrity, UTF-8 JSON/JSONL parsing, CSV row counts,
manifest ordering, capability markers, queue membership and preparation
shapes, signed direct destinations, stable fingerprints, DPLC edge pairing,
terminal ledgers, and timing-event shapes. An independent rerun of the
aggregate validator completed successfully.

## Compression revision

The first approved native inventory contained 70 uncompressed
`physics.csv`/`aux_state.jsonl` publication paths. Repository policy forbids
new uncompressed trace payloads. No content was recaptured or edited:

- each plain payload was compressed independently with `gzip -9 -n`;
- each resulting stream was decompressed and compared with its approved source;
- all 70 comparisons were byte-identical; and
- the user approved the revised exact-byte inventory before it replaced the
  installed plain paths.

`TestTraceFixtureCompressionGuard` passes with the revised tree.

Target closure removed three superseded tracked plain payloads:
`s1/ghz1_fullrun/aux_state.jsonl`,
`s1/ghz1_fullrun/physics.csv`, and
`s2/ehz1_fullrun/physics.csv`. The S1 auxiliary file initially shadowed its
approved `.gz`, because the trace loader deliberately prefers plain files.
A closure check found no remaining approved-target sibling overlap.

## Validator reconciliation

The first isolated replay diagnostic exposed a stale S1 DPLC submission-PC
whitelist. The approved traces consistently report the four retail submission
sites `$0D20`, `$0E34`, `$0F24`, and `$1030`; completion sites remain `$0D50`,
`$0E64`, `$0F54`, and `$1060`. A focused regression failed while the
submitted-edge whitelist still used `$1436A`; that whitelist was corrected to
the four submission sites while preserving `$1436A` as the recorder's valid
decision/arming return callback. All 17 dynamic-art lifecycle tests then
passed. S1 replay consequently reaches its real comparison frontier instead of
failing fixture validation.

## Focused Java evidence

On JDK 21,
`mvn -Dmse=off -Dtest=TestLoadQueueTraceComparison test` ran 8 tests with
0 failures, 0 errors, and 0 skips. This includes parsed-JSON signed S3K direct
destinations and zero-tolerance queue comparison.

The exhaustive replay result and per-class frontiers are recorded separately
in the matching validation report and trace frontier log.

## Final native publication gate

The fresh all-ROM command `tools/bizhawk-headless/test.sh` completed with exit
0: 525 passed, 0 failed, and 0 skipped in 1,462.3 seconds using eight jobs.
This includes byte-differential reproduction of the S1 19-segment complete
run, S2 35-segment complete-emeralds run, S3K 15-segment complete run, both
S3K multibonus identities, standalone/level-select traces, metadata, manifests,
compression shapes, and queue-audit invocation flags.

The clean JDK-21 trace/hardware publication-guard selection completed with
155 passed, 0 failed, 0 errors, and 0 skips. The separately discovered
`TestTraceDataAuxSchemaPerformance` benchmark is intentionally opt-in and was
excluded from the zero-skip guard allowlist rather than misreported as a guard.
