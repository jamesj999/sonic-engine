# Repeated SMPS Playback Performance Audit

**Date:** 2026-08-11
**Status:** Accepted
**Baseline:** `d958fc681c4e272f7ba7072a4b344f533ca614d8`
**Feature evidence HEAD:** `27011b350ed119b888a1a855f68ed7e114e52c98`
**Final implementation HEAD:** `19183e2b3`

## Decision

The repeated-playback allocation gate passes. Repeated SMPS SFX starts now
allocate a constant 1,344 bytes per trigger in the historical public-API
fixture, independent of a 64-byte versus 1 MiB program, a 64-byte versus 4 MiB
DAC bank, or one versus ten unrelated music tracks. Music also improves by
5.148% in the 256-operation comparison. Every feature fixture performs exactly
one loader call and one program materialization per key/generation.

Allocation is normative in this audit. Warming and elapsed nanoseconds are
reported to make the run reproducible, but timing is descriptive because it is
host/JIT sensitive.

## Authenticated benchmark contract

The byte-identical benchmark source on both revisions is
`src/test/java/com/openggf/audio/TestSmpsRepeatedPlaybackBenchmark.java`.
Each executing test resolves its own worktree from the loaded
`target/test-classes` location and hashes that source. The baseline run,
feature run, and independently archived manifests all authenticate as:

`07a3aa73e7ff7845b10ead5a67c3660bbd2e02b5c2ddedf86f84c5d4412c1450`

The benchmark uses only public APIs present on both commits:
`AudioManager.playMusic(int)`, `AudioManager.playSfx(int)`, and
`presentFrame(SILENT)`. The feature-only executable comparator separately
hashes both archived source roots and rejects manifest, environment, operation,
counter, median, slope, or topology mismatches.

## Environment and fixture topology

- Maven 3.9.16; OpenJDK 21.0.11+10, 64-bit Server VM, Linux amd64.
- Surefire `forkCount=1`, `reuseForks=true`, `-Xshare:off`, `-Xmx1g`.
- Thread allocation accounting supported and enabled.
- 64 setup operations, 10,000 discarded calls through the actual allocation
  wrapper on the tiny fixture, then one discarded complete scenario/count pass.
- Recorded counts: 64, 128, and 256 operations; five alternating repetitions.
- Program sizes: 64 bytes and 1,048,576 bytes.
- DAC sizes: 64 bytes and 4,194,304 bytes.
- Unrelated music topology: one or ten tracks.
- Stable post-operation topology: one live presentation voice; music has one
  driver sequencer and SFX has two.
- VM margin: 128 bytes/op. A fixture tolerance is the larger paired control
  spread plus that margin.

## Exact commands

Baseline, from a detached worktree at the binding commit:

```bash
mvn -Dmse=off -Dsurefire.forkCount=1 \
  -Dtest=TestSmpsRepeatedPlaybackBenchmark \
  -Dopenggf.audio.repeatedPlaybackBenchmark=true \
  -Dopenggf.audio.benchmark.reuseForks=true test
```

The identical command was run in the feature worktree. The executable gate was:

```bash
java -cp target/test-classes \
  com.openggf.audio.SmpsRepeatedPlaybackBenchmarkComparator \
  <baseline-raw> <feature-raw> \
  <baseline-manifest-root> <feature-manifest-root>
```

It exited 0 and printed `SMPS_COMPARATOR_RESULT PASS`.

## 256-operation result

| Scenario | Baseline B/op | Feature B/op | Delta | Baseline ns/op | Feature ns/op | Result |
|---|---:|---:|---:|---:|---:|---|
| Music repeat | 239,944 | 227,592 | -5.148% | 44,494 | 20,994 | PASS |
| SFX, 64-byte program | 27,328 | 1,344 | -95.082% | 3,116 | 224 | PASS |
| SFX, 1 MiB program | 7,366,912 | 1,344 | -99.982% | 1,860,821 | 225 | PASS |
| SFX, 64-byte DAC | 27,328 | 1,344 | -95.082% | 3,211 | 236 | PASS |
| SFX, 4 MiB DAC | 8,415,808 | 1,344 | -99.984% | 348,720 | 219 | PASS |
| SFX, one unrelated music track | 27,328 | 1,344 | -95.082% | 6,449 | 216 | PASS |
| SFX, ten unrelated music tracks | 31,696 | 1,344 | -95.760% | 7,477 | 218 | PASS |

## Normative slope gates

| Control | Baseline slope B/op | Feature slope B/op | Tolerance | Large-case improvement B/op | Result |
|---|---:|---:|---:|---:|---|
| Program size | 7,339,584 | 0 | 152 | 7,365,568 | PASS |
| DAC size | 8,388,480 | 0 | 152 | 8,414,464 | PASS |
| Unrelated music | 4,368 | 0 | 128 | 30,352 | PASS |

For every scenario/count pair, the feature median is no greater than the
baseline median plus the paired tolerance. Each targeted feature slope is
within zero/control tolerance and materially below its baseline slope. Every
large target improves by more than its tolerance. Feature loader and
materialization counts are exactly `1/1` for every repetition.

Catalog identity tests separately prove that repeated live and rewind-restored
music/SFX sequencers share the registered immutable program/view,
voice/envelope, DAC, configuration, and descriptor identities while retaining
distinct mutable sequencers, tracks, and cursors.

## Raw five-repetition summaries

The following lines are copied verbatim from the authenticated raw outputs.
`bytesPerOp` and `nanosPerOp` contain every recorded repetition. GC deltas
remain in the full test output; they are not part of the acceptance rule.

### Baseline

```text
SMPS_BENCHMARK_SUMMARY scenario=music-repeat operations=64 bytesPerOp=[239882, 239944, 239882, 239944, 239882] medianBytesPerOp=239882 controlSpread=62 nanosPerOp=[50764, 24425, 25507, 22559, 46844] medianNanosPerOp=25507 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=music-repeat operations=128 bytesPerOp=[239976, 240008, 239976, 240008, 239914] medianBytesPerOp=239976 controlSpread=94 nanosPerOp=[50874, 24346, 24816, 23394, 47120] medianNanosPerOp=24816 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=music-repeat operations=256 bytesPerOp=[239976, 239944, 239976, 239944, 239912] medianBytesPerOp=239944 controlSpread=64 nanosPerOp=[49230, 44494, 23887, 42441, 46879] medianNanosPerOp=44494 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-tiny operations=64 bytesPerOp=[27352, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=24 nanosPerOp=[6150, 3075, 3160, 2984, 5525] medianNanosPerOp=3160 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-tiny operations=128 bytesPerOp=[27352, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=24 nanosPerOp=[6932, 3037, 3194, 2953, 5672] medianNanosPerOp=3194 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-tiny operations=256 bytesPerOp=[27352, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=24 nanosPerOp=[5967, 3020, 3116, 5379, 3081] medianNanosPerOp=3116 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-large operations=64 bytesPerOp=[7366936, 7366912, 7366912, 7366912, 7366912] medianBytesPerOp=7366912 controlSpread=24 nanosPerOp=[1837681, 1896815, 1847150, 1856022, 1902017] medianNanosPerOp=1856022 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-large operations=128 bytesPerOp=[7366936, 7366912, 7366912, 7366912, 7366912] medianBytesPerOp=7366912 controlSpread=24 nanosPerOp=[1853610, 1837447, 1863626, 1858658, 1865353] medianNanosPerOp=1858658 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-large operations=256 bytesPerOp=[7366936, 7366912, 7366912, 7366912, 7366912] medianBytesPerOp=7366912 controlSpread=24 nanosPerOp=[1840847, 1846084, 1860821, 1922550, 1872869] medianNanosPerOp=1860821 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-tiny operations=64 bytesPerOp=[27352, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=24 nanosPerOp=[3170, 5908, 2996, 5258, 2921] medianNanosPerOp=3170 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-tiny operations=128 bytesPerOp=[27352, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=24 nanosPerOp=[3015, 5974, 2982, 6961, 2887] medianNanosPerOp=3015 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-tiny operations=256 bytesPerOp=[27352, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=24 nanosPerOp=[3211, 6706, 2949, 6580, 2886] medianNanosPerOp=3211 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-large operations=64 bytesPerOp=[8415832, 8415808, 8415808, 8415808, 8415808] medianBytesPerOp=8415808 controlSpread=24 nanosPerOp=[350164, 335328, 370210, 337848, 374830] medianNanosPerOp=350164 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-large operations=128 bytesPerOp=[8415832, 8415808, 8415808, 8415808, 8415808] medianBytesPerOp=8415808 controlSpread=24 nanosPerOp=[353487, 351265, 349825, 344152, 376625] medianNanosPerOp=351265 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-large operations=256 bytesPerOp=[8415809, 8415808, 8415808, 8415808, 8415808] medianBytesPerOp=8415808 controlSpread=1 nanosPerOp=[334760, 355981, 348261, 348720, 351633] medianNanosPerOp=348720 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-min operations=64 bytesPerOp=[27328, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=0 nanosPerOp=[4725, 2936, 6811, 8072, 6369] medianNanosPerOp=6369 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-min operations=128 bytesPerOp=[27328, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=0 nanosPerOp=[6734, 2964, 7082, 5506, 6223] medianNanosPerOp=6223 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-min operations=256 bytesPerOp=[27328, 27328, 27328, 27328, 27328] medianBytesPerOp=27328 controlSpread=0 nanosPerOp=[6513, 3038, 6507, 6449, 6242] medianNanosPerOp=6449 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-max operations=64 bytesPerOp=[31696, 31696, 31696, 31696, 31696] medianBytesPerOp=31696 controlSpread=0 nanosPerOp=[7772, 3576, 9388, 7127, 6765] medianNanosPerOp=7127 loaderCalls=[256, 1024, 256, 1024, 256] programMaterializations=[128, 512, 128, 512, 128]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-max operations=128 bytesPerOp=[31696, 31696, 31696, 31696, 31696] medianBytesPerOp=31696 controlSpread=0 nanosPerOp=[7451, 3822, 6848, 9060, 8042] medianNanosPerOp=7451 loaderCalls=[512, 896, 512, 896, 512] programMaterializations=[256, 448, 256, 448, 256]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-max operations=256 bytesPerOp=[31696, 31696, 31696, 31696, 31696] medianBytesPerOp=31696 controlSpread=0 nanosPerOp=[7209, 7695, 7219, 7973, 7477] medianNanosPerOp=7477 loaderCalls=[1024, 640, 1024, 640, 1024] programMaterializations=[512, 320, 512, 320, 512]
```

### Feature

```text
SMPS_BENCHMARK_SUMMARY scenario=music-repeat operations=64 bytesPerOp=[227530, 227592, 227530, 227592, 227535] medianBytesPerOp=227535 controlSpread=62 nanosPerOp=[23734, 21697, 21901, 16573, 18758] medianNanosPerOp=21697 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=music-repeat operations=128 bytesPerOp=[227625, 227656, 227631, 227656, 227576] medianBytesPerOp=227631 controlSpread=80 nanosPerOp=[35033, 22817, 29808, 17762, 27369] medianNanosPerOp=27369 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=music-repeat operations=256 bytesPerOp=[227624, 227592, 227624, 227592, 227560] medianBytesPerOp=227592 controlSpread=64 nanosPerOp=[22762, 25126, 20994, 18009, 17582] medianNanosPerOp=20994 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-tiny operations=64 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[389, 358, 262, 233, 201] medianNanosPerOp=262 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-tiny operations=128 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[413, 368, 206, 246, 212] medianNanosPerOp=246 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-tiny operations=256 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[402, 340, 210, 224, 203] medianNanosPerOp=224 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-large operations=64 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[429, 351, 221, 214, 212] medianNanosPerOp=221 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-large operations=128 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[457, 562, 200, 218, 207] medianNanosPerOp=218 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-program-large operations=256 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[397, 678, 205, 225, 206] medianNanosPerOp=225 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-tiny operations=64 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[396, 498, 234, 233, 214] medianNanosPerOp=234 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-tiny operations=128 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[495, 347, 218, 232, 211] medianNanosPerOp=232 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-tiny operations=256 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[403, 353, 236, 227, 219] medianNanosPerOp=236 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-large operations=64 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[575, 384, 215, 243, 240] medianNanosPerOp=243 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-large operations=128 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[405, 362, 221, 214, 203] medianNanosPerOp=221 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-dac-large operations=256 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[402, 358, 212, 214, 219] medianNanosPerOp=219 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-min operations=64 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[400, 363, 222, 212, 220] medianNanosPerOp=222 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-min operations=128 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[378, 377, 217, 220, 209] medianNanosPerOp=220 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-min operations=256 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[409, 363, 216, 214, 211] medianNanosPerOp=216 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-max operations=64 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[382, 386, 218, 205, 227] medianNanosPerOp=227 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-max operations=128 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[364, 454, 203, 220, 204] medianNanosPerOp=220 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
SMPS_BENCHMARK_SUMMARY scenario=sfx-unrelated-music-max operations=256 bytesPerOp=[1344, 1344, 1344, 1344, 1344] medianBytesPerOp=1344 controlSpread=0 nanosPerOp=[442, 387, 211, 206, 218] medianNanosPerOp=218 loaderCalls=[1, 1, 1, 1, 1] programMaterializations=[1, 1, 1, 1, 1]
```

## Verification and limitations

The final round-four corrections did not change the benchmark manifest. They
restore atomic ROM/source publication and harden architecture analysis.
Post-correction verification completed 175 runtime source tests, 39
guard/comparator tests, 56 opt-in benchmark tests, 131 Task 7/8 tests, and 635
broad audio tests without a focused failure or error. Baseline and feature
ArchUnit runs each report the same 29 tests, one pre-existing
`trace -> graphics` failure, and zero errors.

The round-four no-property package comparison was 14,714 tests with 55
failures, 19 errors and 41 skips before its seven test additions, versus
14,721 tests with the same 55 failures, 19 errors and 41 skips afterward. A
fresh documentation-boundary `mvn -Dmse=off test` again ran 14,721 tests and
reported 56 failures, 19 errors and 41 skips: the sole additional failure was
the parallel-sensitive `TestPreparedSfxAdmission` allocation probe. That exact
test had passed minutes earlier in the 165-test focused gate with flat measured
slopes, and it passes in the dedicated Task 7/8 gates. Repository-wide failures
are pre-existing or independently reproduced parallel-order noise; no focused
audio regression is attributable to this change.

After reconciling the completed work with updated `develop` at
`3c50b09a4f231cd081b6a72d58ea714e2c6d0c32`, the official detached baseline
ran 14,579 tests with 51 failures, 16 errors, and 40 skips. The reconciled
feature ran 14,724 tests with 55 failures, 19 errors, and 41 skips. Every one
of the baseline's 67 failing or erroring testcase identities remained
unchanged. The seven feature-only failing/erroring cases (three special-stage
presentation cases, three Madmole cases, and the prepared-admission allocation
probe) all passed together in an immediate 59-test isolated rerun, confirming
the aggregate delta as suite-order noise rather than an attributable audio
regression. The reconciled 165-test focused audio/ownership/allocation gate
also passed in full.

The exact full-package commands and totals, both sorted identity lists, their
seven-entry `comm` delta, and the literal 59-test rerun command/outcome are
preserved in [the updated-develop package comparison evidence](2026-08-11-smps-updated-develop-package-comparison.md).

## Pre-merge review corrections

`2ed13b923` retains the catalog entry's frozen SFX policy during queued
admission and completes a config-less donor-music source coherently when its
base owner arrives. Policy mutation after manager registration therefore
cannot change priority, special, continuous, or extension behavior, while a
donor registered before its base remains deferred without changing generation,
fallback, or null-result semantics.

`19183e2b3` makes warmed-materialization checks traverse annotated helpers and
makes lookup-before-load analysis merge branch state across every exclusive
path. The new mutations cover helper descriptor creation, data/hash/copy
materialization, and lookup/load split across opposite branches.

Post-correction verification completed **177/177** runtime tests, **105/105**
source transaction/reentrancy tests, **41/41** guard/comparator tests, **58**
ordinary Task 9 tests with one expected opt-in skip, **58/58** opt-in Task 9
tests, **131/131** Task 7/8 tests, and **639** broad audio tests with eight
skips and no failures/errors. Isolated ArchUnit remained exactly **29 run, one
known `trace -> graphics` failure, zero errors**. The authenticated benchmark
manifest source did not change, so the paired historical benchmark was not
rerun.

## S1 frontier reconciliation

The observer-heavy S1 audio frontier was reconciled locally at
`05ac8e6dce1c59681bbfb927ab78aff2b1015140`. Its affected-channel mutation
journal preserves post-mutation diagnostic timing and queued-command retry
without retaining whole synth snapshots; continuous extension uses scalar-only
journal state. The actual dirty frontier worktree passed a 139-test focused
observer/journal/allocation/onset gate. Measured slopes remained independent of
program, DAC, and unrelated-music size (1,368/1,368, 1,344/1,344, and
1,344/1,344 allocated bytes per trigger respectively).

The actual frontier full run executed 14,902 tests with 36 failures, 14 errors,
and 42 skips while concurrent unrelated complete-run work added tests. Its 50
failing/erroring identities were all present in the preserved 56-identity
pre-port baseline: zero new identities and six absent. The frontier commit is
intentionally local and unpushed.
