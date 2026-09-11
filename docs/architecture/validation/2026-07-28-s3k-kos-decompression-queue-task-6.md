# S3K direct Kosinski queue Task 6 validation

Date: 2026-07-28

## Scope

Task 6 updated the approved cross-game timing contract, S3K timing
inventory, recorder/format documentation, known-discrepancy status,
agent-facing authority rule, changelog, and README release log. It added a
checked compatibility inventory without publishing or modifying trace
payloads.

The final Task 6 file set is:

- `AGENTS.md`, `CLAUDE.md`;
- `CHANGELOG.md`, `README.md`;
- `docs/architecture/audits/2026-07-27-s3k-hardware-timing-inventory.md`;
- `docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md`;
- `docs/architecture/designs/2026-07-28-s3k-kos-decompression-queue.md`;
- `docs/architecture/plans/2026-07-28-s3k-kos-decompression-queue.md`;
- this validation report;
- `docs/status/known-discrepancies.md`;
- `src/test/java/com/openggf/trace/timing/TestCommittedHardwareTimingFixtures.java`;
- `tools/bizhawk/README.md`; and
- `tools/bizhawk-headless/docs/s3k-trace-recorder-behavior.md`; and
- `tools/bizhawk-headless/tests/S3KTraceDifferentialTests.cs`.

Review fix round 1 restored the last file as the normative current
6.38/schema-2 byte contract and corrected both STANDARD and complete-run
parity wording in `tools/bizhawk/README.md`. Historical 6.32/6.33 recorder
derivation remains labelled as history; committed fixtures and frozen Lua are
identified as 6.37/trace-schema-7/hardware-schema-1, distinct from current
native 6.38/trace-schema-7/hardware-schema-2 output.

Review fix round 2 removed the final stale schema-6 summary bullet and made
the STANDARD differential enforce the publication boundary in executable
tests. Current production metadata must be exactly
6.38/trace-schema-7/hardware-schema-2, and the generated timing stream must
contain exact-shape, independently increasing direct and module ledgers at
their ROM-owned boundaries. Committed
6.37/trace-schema-7/hardware-schema-1 fixtures remain explicitly load-only;
the gate cannot normalize their module-only stream into direct-authority
success.

`docs/status/trace-frontier-log.md` was not changed. This documentation task
did not measure a frontier move or regression, and the implementation plan
requires that ledger to change only when one is measured.

## Schema-1 compatibility inventory

`TestCommittedHardwareTimingFixtures` pins the exact committed schema-1
fixtures that reach a gameplay consumer of `Kos_decomp_queue_count`:

- `s3k/aiz1_to_hcz_fullrun` — AIZ intro;
- `s3k/aiz_completerun` — AIZ intro; and
- `s3k/icz_completerun` — ICZ1-to-ICZ2 transition.

All three remain loadable and retain authoritative module-queue edges. Their
direct jobs use the live scheduler under schema 1, so they cannot certify the
direct queue-empty boundary. Schema-2 replacement remains a separately
reviewed, explicitly approved publication action.

The multi-bonus run's first AIZ segment begins at camera X `$1300`, after the
intro consumer, and is intentionally outside this inventory.

## Verification

JDK identity:

```text
mvn -v
Apache Maven 3.9.16
Java version: 21.0.11
```

Focused documentation, authority, and fixture guards:

```text
mvn -Dmse=off "-Dtest=TestCommittedHardwareTimingFixtures,TestHardwareTimingAuthorityGuard,TestTraceFixtureCompressionGuard,TestArchitecturalReviewGuard,TestArchitecturalSourceGuard#hardwareTimingAuthorityExceptionStaysDocumentedAndAgentGuidanceStaysMirrored" test
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The broader guard selection was also run:

```text
mvn -Dmse=off "-Dtest=TestCommittedHardwareTimingFixtures,TestHardwareTimingAuthorityGuard,TestTraceFixtureCompressionGuard,TestArchitecturalSourceGuard,TestArchitecturalReviewGuard" test
Tests run: 93, Failures: 2, Errors: 0, Skipped: 0
```

Both failures were classified:

- the `AGENTS.md` / `CLAUDE.md` mirror failure was repaired in Task 6, then
  the focused documentation method passed;
- `releaseCriticalLargeClassesDoNotGrowWithoutExtraction` still reports
  `GameLoop` 3014 > 3005 and `AbstractPlayableSprite` 3164 > 3159 from the
  pre-task baseline, plus `LevelManager` 2521 > 2500. The `LevelManager`
  overage is attributable to Task 4 commit `bbaa7e857` (+10/-1 from
  pre-Task-4 commit `d230501d4`) and is a Task 7 blocker.

`git diff --name-only -- src/test/resources/traces` produced no output.
`git diff --check` passed. The existing frozen publication-manifest hashes
also passed in `TestCommittedHardwareTimingFixtures`, proving that no
committed fixture payload changed.

Round-2 focused C# contract tests:

```text
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  ./test.sh --filter "S3KTraceDifferential requires" --jobs 1
PASS S3KTraceDifferential requires schema two and rejects schema one as load-only compatibility
PASS S3KTraceDifferential requires direct and module timing ledgers
```

The existing engine-level timing suite also remained green:

```text
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  ./test.sh --filter "HardwareTimingEventEngine" --jobs 1
15 passed
```

The locked-on ROM at
`/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen`
was verified as SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6`. The focused AIZ gate ran through
the frozen physics/aux hash assertions and the new dual-ledger validation,
then produced the required non-success at the publication boundary:

```text
S3K_ROM_PATH=".../Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  BIZHAWK_HOME=".../docs/BizHawk-2.11-linux-x64" \
  ./test.sh \
    --filter "S3KTraceDifferential native capture matches canonical AIZ timing stream" \
    --jobs 1
FAIL ... Fixture metadata is committed schema-one load-only compatibility
and cannot establish schema-two direct-authority differential success.
```

That failure is the asserted release state until explicit schema-2 fixture
publication. It is not treated as a passing differential, and no fixture
payload was changed. After the round-2 changes, the 27 focused Maven guards
were rerun with the command above and again passed with zero failures,
errors, or skips.

Round 3 corrected the differential validator's canonical same-frame boundary
ranks to `vint_service = 0`, `pre_main_loop = 1`, and
`post_objects = 2`. The regression test was observed RED with the old
PRE-before-VINT ranks, then GREEN after the two-rank correction. It proves
that VINT-to-PRE is accepted, PRE-to-VINT is rejected, and the existing
PRE-to-POST order remains accepted.

```text
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  ./test.sh --filter "S3KTraceDifferential" --jobs 1
PASS S3KTraceDifferential requires schema two and rejects schema one as load-only compatibility
PASS S3KTraceDifferential requires direct and module timing ledgers
PASS S3KTraceDifferential orders same-frame VINT before PRE before POST
SKIP three ROM-backed gates: S3K_ROM_PATH is not set

BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  ./test.sh --filter "HardwareTimingEventEngine" --jobs 1
15 passed
```
