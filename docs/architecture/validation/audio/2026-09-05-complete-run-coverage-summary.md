# Complete-run audio coverage summary

`CompleteRunAudioCoverageSummary` reports only captures governed by a
`CompleteRunAudioProfile`. It does not aggregate the narrow S1, S2, or S3K
parity-adapter families.

Each canonical comparison layer has two independent dispositions. Authority is
`UNAVAILABLE`, `DIAGNOSTIC_ONLY`, or `COMPARABLE`, derived from the profile's
producer bindings and observation/comparison inventories. Evidence is
`NOT_RUN`, `REFERENCE_LIMITATION`, `KNOWN_MISMATCH`, or `VERIFIED_MATCH`, derived
from one supplied comparison report. A declaration without a report never
becomes verification.

Fixture, profile, observation, and comparison inventories must match both
capture identities. A full `MATCH` additionally requires both pinned runtime
and observer trust roots. Diagnostic mismatch reports retain an unavailable
authority disposition while pinned sides still undergo runtime validation.
Capture failures throw with the original report attached.

Mismatch attribution uses the comparison location as well as its kind. Frame
lag, frame chip events, and cutoff-boundary chip state have distinct owners.
Frame coordinates, terminal counts, terminal digests, metadata, record shape,
and other whole-report failures remain unassigned instead of being credited to
an arbitrary layer. Consequently, only a correlated `MATCH` with every layer
both comparable and verified can set `fullParity`.

Focused verification is in `TestCompleteRunAudioComparator`. Its controls cover
profile, ROM/BK2/run-manifest fixture and runtime mismatches; declarations with
no evidence; the shipped S1/S2/S3K fixed-profile limitations; capture failures;
typed layer attribution; and deterministic output.

The fixed inventories are regenerable without capture files (exit 3 is expected
because declarations are not evidence):

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
java -cp "target/classes:$(cat target/runtime-classpath.txt)" com.openggf.tools.audio.completerun.CompleteRunAudioTool coverage-text s1_rev01_complete_emeralds.v1
java -cp "target/classes:$(cat target/runtime-classpath.txt)" com.openggf.tools.audio.completerun.CompleteRunAudioTool coverage-text s2_rev01_complete_emeralds.v1
java -cp "target/classes:$(cat target/runtime-classpath.txt)" com.openggf.tools.audio.completerun.CompleteRunAudioTool coverage-text s3k_locked_on_knuckles_superemeralds.v1
```

Supplying absolute reference and engine capture paths runs the strict comparator
first: `coverage-text <profile-id> <reference> <engine>`. Exit 0 requires full
parity, exit 3 means limitation or mismatch, exit 4 preserves a capture-failure
report, and invalid or unknown arguments exit 2.

## Verification record

On merged header-order baseline `8a7dc5f15`, the final candidate preserves the
main baseline and adds eight ordinary tests:

- focused coverage CLI/comparator controls: 9 passed;
- ordinary suite with Java 21 and all three absolute ROM paths: 16,674 tests,
  43 skipped, no failures or errors;
- the first guard run correctly rejected the new reporter as an unclassified
  authenticated capture source: 609 run, 1 failure. The reporter is now the one
  explicitly non-authenticated comparison/reporting source, alongside the
  comparator and report; a guard mutation rejects any authenticated source that
  refers to it. The final guard result is recorded with the delivery evidence.

The declaration-as-match mutation failed its no-evidence assertion. A separate
mismatch-as-full-parity mutation failed both the summary and CLI exit assertions.
Neither mutation is retained.

The candidate is integrated into develop. Combined post-merge verification
preserves every candidate outcome; push and cleanup status are tracked in the
[group ledger](2026-09-05-sol-smps-parity-delivery-group.md).
