# Trace v5 pre-capture freeze

## Verdict and frozen identity

Task 8 passed independent review with `SPEC PASS` and `QUALITY PASS`; the
reviewer declared the implementation safe to freeze. This artifact authorizes
Task 9 candidate capture, not fixture installation by itself.

| Identity | Frozen value |
| --- | --- |
| Reviewed source commit | `763415cd4f4cdd141c98a2850d7b80e01035e2be` |
| Reconciled `origin/develop` | `8211d923f80eaffa5add99d66761c0a25619e2bb` |
| Reviewed diff SHA-256 | `0ed806a8c14315c0b971ebcda0874c34c1f55be2ce6299f867a8b07ea6ae4eb7` |
| Diff definition | `git diff --full-index --binary origin/develop..763415cd4 \| sha256sum` |
| Native harness artifact | `tools/bizhawk-headless/bin/Release/BizHawk.Headless.Gpgx.exe` |
| Native harness size | 349,696 bytes |
| Native harness SHA-256 | `ecd9aa1389d6acdab5a476122f3ad5d2165d26bb0d76ae4aba5f10d88b78b514` |
| Native test artifact SHA-256 | `12e0ff67e928d35bdbc6ccba69ba386b04aa48873106f692ab0b7ad9b58a3446` |
| Fixture inventory | 913 files |
| Fixture aggregate SHA-256 | `52ea19afea7250121c35a94927e3a4b950c6b00b8fac9570284401db3f0615bd` |

The post-review tracked tree was clean at the reviewed source commit. The
standard build command was invoked exactly once after the clean review and
returned exit 0:

```bash
BIZHAWK_HOME=/abs/path/to/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  tools/bizhawk-headless/build.sh
```

No build or native test wrapper was invoked after that command. Post-build
work was limited to file/assembly inspection, hashing, inventory verification,
and this documentation.

## Tool and ROM identity

- Maven 3.9.16; Arch Linux OpenJDK 21.0.11.
- Mono JIT 6.12.0; xbuild 14.0 / Mono 6.12.0.0.
- Git 2.55.0.
- BizHawk 2.11 Linux x64 at
  `/abs/path/to/OpenGGF/docs/BizHawk-2.11-linux-x64`.
  `EmuHawk.exe` SHA-256:
  `b2d4be5e2a766a5161cc26f3af2a90753c39d64c91c54a9884171aed09e21df3`.
- S1 World REV01: CRC32 `AFE05EEE`, SHA-1
  `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`.
- S2 World REV01: CRC32 `7B905383`, SHA-1
  `8bca5dcef1af3e00098666fd892dc1c2a76333f9`.
- S3&K locked-on: CRC32 `63522553`, SHA-1
  `cfbf98c36c776677290a872547ac47c53d2761d6`.

The ROM paths used by the native gates were the three verified root files:

```text
/abs/path/to/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen
/abs/path/to/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen
/abs/path/to/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen
```

## Pre-capture verification

All native commands set the three `S1_ROM_PATH`, `S2_ROM_PATH`, and
`S3K_ROM_PATH` values above plus the pinned absolute `BIZHAWK_HOME`.

```bash
S1_ROM_PATH='/abs/path/to/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen' \
S2_ROM_PATH='/abs/path/to/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen' \
S3K_ROM_PATH='/abs/path/to/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' \
BIZHAWK_HOME='/abs/path/to/OpenGGF/docs/BizHawk-2.11-linux-x64' \
  tools/bizhawk-headless/test.sh --no-gates --jobs 1 --slowest 5
```

Result: 500 passed, 0 failed, 0 skipped in 8.0 seconds. This includes the
credits predecessor-inventory and raw-host/hash-binding units plus the
registry contract that selects exactly the safe pre-capture tiers.

```bash
S1_ROM_PATH='/abs/path/to/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen' \
S2_ROM_PATH='/abs/path/to/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen' \
S3K_ROM_PATH='/abs/path/to/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' \
BIZHAWK_HOME='/abs/path/to/OpenGGF/docs/BizHawk-2.11-linux-x64' \
  tools/bizhawk-headless/test.sh --gates-only --jobs 1 --slowest 6
```

Result: 6 passed, 0 failed, 0 skipped in 121.5 seconds, exit 0. The selected
set contains both S1 retail lifecycle gates, both S2 retail owner probes, the
S1 all-eight credits double-capture/raw-host evidence gate, and the S1
deterministic end-to-end scratch gate. The bounded S2 Tails pilot probe found
its path at BK2 frame 2740. No candidate-root-dependent test was registered or
skipped.

The focused Java authority/parser/timing/manifest/generated-v5 command was:

```bash
mvn -q -Dmse=off -Dsurefire.forkCount=1 \
  -Dtest='com.openggf.trace.TestTraceV5LoadingContract,
com.openggf.trace.TestTraceDataHardwareTiming,
com.openggf.trace.timing.TestHardwareTimingStreamLoader,
com.openggf.trace.timing.TestHardwareTimingReplayPort,
com.openggf.trace.timing.TestHardwareTimingAuthorityGuard,
com.openggf.trace.replay.TestTraceHardwareTimingScheduleCompiler,
com.openggf.TestLevelFrameHardwareTimingBoundaries,
com.openggf.TestSpecialStageHardwareTimingLifecycle,
com.openggf.tests.trace.TestTraceRunManifest,
com.openggf.tests.trace.TestTraceRunSyntheticFixture,
com.openggf.tests.trace.TestS2SyntheticRunFixture,
com.openggf.tests.trace.runs.TestTraceRunDynamicArtGapComparator,
com.openggf.tests.trace.runs.TestTraceRunReplayWalkerControlFlow,
com.openggf.tests.trace.runs.TestTraceRunPlaybackCoordinator,
com.openggf.tests.trace.runs.TestTraceRunPlaybackTranscriptParity,
com.openggf.trace.catalog.TestTraceCatalogRunDiscovery,
com.openggf.trace.catalog.TestTraceRunLaunchValidation,
com.openggf.tests.trace.TestTraceFixtureRootOverride,
com.openggf.tests.TestArchitecturalSourceGuard,
com.openggf.trace.TestTraceFixtureCompressionGuard,
com.openggf.trace.SpecialStageTraceFrameTest,
com.openggf.tests.trace.TestS1SpecialStageTraceParsing,
com.openggf.tests.trace.TestS3kSpecialStageTraceParsing,
com.openggf.TestTraceSessionLauncherRunBranch' test
```

Result: 309 tests, 0 failures, 0 errors, 0 skipped.

```bash
python3 -m unittest -v \
  tools.testing.test_validate_trace_v5 \
  tools.testing.test_compare_trace_v5_candidates
```

Result: 36 tests passed.

The immutable baseline was verified from both the worktree and the Git index:

```bash
python3 tools/traces/trace_fixture_inventory.py verify \
  src/test/resources/traces \
  docs/architecture/validation/trace/2026-08-03-trace-v5-baseline-inventory.json
python3 tools/traces/trace_fixture_inventory.py verify --git-index \
  src/test/resources/traces \
  docs/architecture/validation/trace/2026-08-03-trace-v5-baseline-inventory.json
```

Both passed before and after the final build. `git diff --check`, mirrored
agent/Claude skills, and the `AGENTS.md`/`CLAUDE.md` pair also passed.

## Fixture preservation and upstream attribution

The original Task 1 inventory contains 809 files with aggregate SHA-256
`6885fbd0b0c5f02adf95ca33841c9755d2c81b46220f918d810394108b25da00`.
Reconciled upstream commit `8211d923f` added exactly the 104-file legacy run
`s1/runs/s1-sonic-complete-withemeralds/`: 34 metadata files, 34 physics
payloads, 34 auxiliary payloads, one BK2, and one run manifest. Every added
byte matches `origin/develop`; no original path changed or disappeared.

The combined 913-file inventory and aggregate hash in the table above preserve
both sets. All eight S1 credits-demo fixtures remain present and unchanged.
No installed fixture was generated, rewritten, deleted, normalized, or
otherwise mutated during Task 8.

## Non-self-reference and invalidation rule

This freeze document cannot include its own commit in the reviewed-source hash
without making that hash self-referential. The executable source and native
artifact are therefore deliberately bound to reviewed commit `763415cd4`.
The commit that adds this document is documentation-only and does not alter
either the reviewed executable source or the hashed native assembly.

Task 9 capture-matrix and validation-report documentation may be added after
this freeze without changing the executable-source or artifact identity. Each
capture invocation must verify the reviewed commit boundary, deterministic
diff hash, and native artifact SHA-256 recorded here. A change to recorder or
runtime source, native recorder tests/gates, Java trace parsing/timing/replay
authority, or any other executable test-authority source invalidates this
freeze and every capture made from it. Such a change requires reconciliation,
review, one new post-review build, a replacement freeze, and fresh captures.

This artifact does not authorize legacy compatibility, installed-fixture
mutation before Task 9, or any expansion of hardware-timing authority.
