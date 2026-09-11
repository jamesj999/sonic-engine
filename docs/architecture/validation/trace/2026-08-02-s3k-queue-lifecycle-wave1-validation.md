# S3K queue lifecycle recovery Wave 1 validation

Date: 2026-08-02

## Context

- Worktree: `.worktrees/s3k-queue-lifecycle-recovery`
- Branch: `bugfix/ai-s3k-queue-lifecycle-recovery`
- Verified HEAD: `59686c9e5421cdb7be597edd1fd4c2be4af53967`
- Base: `develop` at `176e5bee40a9`
- Runtime: JDK 21
- ROM properties: verified S1 REV01, S2 REV01, and locked-on S3K images supplied
  through the three documented test properties

Wave 1 made no trace-fixture changes. It added structural prefix closure for the
three standalone bonus fixtures and modelled the two ROM-owned LBZ miniboss-box
KosM parent submissions, including rewind rebind/claim ownership.

## Focused timing/authority matrix

Reproducible command template:

```bash
mvn -q -Dmse=off \
  -Dtest='TestS3kKosDecompressionQueue,TestS3kKosDecompressionQueueLifecycle,TestS3kKosModuleQueue,TestS3kKosModuleReadiness,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard,TestHardwareTimingService,TestLevelIterationHardwareTimingAdmissionOrder,TestSpecialStageHardwareTimingLifecycle,TestTraceRunHardwareTimingCoordinator,TestTraceSuppressedRowClosure,TestLoadQueueTraceComparison,TestQueueDiagnosticSnapshot' \
  -Ds3k.rom.path=<verified-s3k> test
```

The executed invocation used the same options and selectors, substituting the
machine-local verified S3K ROM path for `<verified-s3k>`.

Result: exit 0, 142 tests, 0 failures, 0 errors, 0 skipped. The
pre-Wave-1 matrix was 138/138; the four additional passing methods are the new
prefix-close authority cases in `TestHardwareTimingReplayPort`. The selected
Surefire XML reports contain 11.197 seconds of aggregate class time; the shell
wall time was not retained.

| Class | Tests | Result |
|---|---:|---|
| `TestS3kKosDecompressionQueue` | 10 | PASS |
| `TestS3kKosDecompressionQueueLifecycle` | 3 | PASS |
| `TestS3kKosModuleQueue` | 14 | PASS |
| `TestS3kKosModuleReadiness` | 1 | PASS |
| `TestS3kKosStructuralSequence` | 8 | PASS |
| `TestS3kHardwareTimingReplay` | 4 | PASS |
| `TestHardwareTimingReplayPort` | 30 | PASS |
| `TestHardwareTimingAuthorityGuard` | 22 | PASS |
| `TestHardwareTimingService` | 18 | PASS |
| `TestLevelIterationHardwareTimingAdmissionOrder` | 3 | PASS |
| `TestSpecialStageHardwareTimingLifecycle` | 4 | PASS |
| `TestTraceRunHardwareTimingCoordinator` | 8 | PASS |
| `TestTraceSuppressedRowClosure` | 5 | PASS |
| `TestLoadQueueTraceComparison` | 8 | PASS |
| `TestQueueDiagnosticSnapshot` | 4 | PASS |

## Complete trace fleet

Reproducible command template:

```bash
mvn -q -Dmse=off -Dtest='*TraceReplay' \
  -Dsonic1.rom.path=<verified-s1> \
  -Dsonic2.rom.path=<verified-s2> \
  -Ds3k.rom.path=<verified-s3k> test
```

The executed invocation used the same options and selector, substituting the
three machine-local verified ROM paths for `<verified-s1>`, `<verified-s2>`,
and `<verified-s3k>`.

Result: exit 1 across 64 concrete classes and 108 JUnit methods: 67 methods
passed, 4 failed, and 37 errored. S1 is 30/30 classes green, S2 is 20/20
classes green, and S3K is 4/14 classes green. Surefire report publication spans
412.560 seconds and the reports contain 1,599.658 seconds of aggregate class
time; the shell wall time was not retained.

Class totals and `replayMatchesTrace` are deliberately reported separately.
`TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, and
`TestS3kHczCompleteRunTraceReplay` contain additional focused methods. In
particular, CNZ's canonical replay error prevents clean context construction in
20 later methods and six metadata variants lack a copied hardware-timing file;
HCZ has one analogous follow-on context error. Those are broad-run secondary
errors, not moved canonical frontiers.

### Sonic 1 — 30/30 classes green

| Concrete class | XML methods | Result |
|---|---:|---|
| `TestS1Credits00Ghz1TraceReplay` | 1 | PASS |
| `TestS1Credits01Mz2TraceReplay` | 1 | PASS |
| `TestS1Credits02Syz3TraceReplay` | 1 | PASS |
| `TestS1Credits03Lz3TraceReplay` | 1 | PASS |
| `TestS1Credits04Slz3TraceReplay` | 1 | PASS |
| `TestS1Credits05Sbz1TraceReplay` | 1 | PASS |
| `TestS1Credits06Sbz2TraceReplay` | 1 | PASS |
| `TestS1Credits07Ghz1bTraceReplay` | 1 | PASS |
| `TestS1FzCompleteRunTraceReplay` | 1 | PASS |
| `TestS1Ghz1CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Ghz1TraceReplay` | 1 | PASS |
| `TestS1Ghz2CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Ghz3CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Lz1CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Lz2CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Lz3CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Mz1CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Mz1TraceReplay` | 1 | PASS |
| `TestS1Mz2CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Mz3CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Sbz1CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Sbz2CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Sbz3CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Slz1CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Slz2CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Slz3CompleteRunTraceReplay` | 1 | PASS |
| `TestS1SpecialStageTraceReplay` | 1 | PASS |
| `TestS1Syz1CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Syz2CompleteRunTraceReplay` | 1 | PASS |
| `TestS1Syz3CompleteRunTraceReplay` | 1 | PASS |

### Sonic 2 — 20/20 classes green

| Concrete class | XML methods | Result |
|---|---:|---|
| `TestS2Arz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2ArzLevelSelectTraceReplay` | 1 | PASS |
| `TestS2Cnz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2CnzLevelSelectTraceReplay` | 1 | PASS |
| `TestS2Cpz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2CpzLevelSelectTraceReplay` | 1 | PASS |
| `TestS2DezEndingLevelSelectTraceReplay` | 1 | PASS |
| `TestS2Ehz1TraceReplay` | 1 | PASS |
| `TestS2Htz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2HtzLevelSelectTraceReplay` | 1 | PASS |
| `TestS2Mcz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2MczLevelSelectTraceReplay` | 1 | PASS |
| `TestS2Mtz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2Mtz3LevelSelectTraceReplay` | 1 | PASS |
| `TestS2MtzLevelSelectTraceReplay` | 1 | PASS |
| `TestS2Ooz2LevelSelectTraceReplay` | 1 | PASS |
| `TestS2OozLevelSelectTraceReplay` | 1 | PASS |
| `TestS2SczLevelSelectTraceReplay` | 1 | PASS |
| `TestS2SpecialStageTraceReplay` | 2 | PASS |
| `TestS2WfzLevelSelectTraceReplay` | 1 | PASS |

### Sonic 3 & Knuckles — 4/14 classes green

The comparison frontier column records the first canonical comparator error
where one exists. The terminal column records the fail-closed timing exception
that ended this sweep after accumulated comparator errors. Hence a class may
show both comparator errors and one XML error.

| Concrete class | XML class total | Canonical `replayMatchesTrace` frontier | Terminal timing state |
|---|---|---|---|
| `TestS3kAizCompleteRunTraceReplay` | 1 error | 60 comparator errors; first f1106 `queue.s3k_kos_direct.busy` false/true, unchanged | raw 6351 module `#16`, exact engine parent not prepared; recorder-attribution audit lane |
| `TestS3kAizTraceReplay` | 16 methods: 11 pass, 4 fail, 1 error | 77 comparator errors; first f1396 `queue.s3k_kos_direct.busy` false/true, unchanged | raw 8942 direct `#47`, engine pending none |
| `TestS3kCnzCompleteRunTraceReplay` | 1 error | 7 comparator errors; first f9710 `queue.s3k_kos_direct.busy` true/false, unchanged | raw 9712 direct `#203`, engine pending none |
| `TestS3kCnzTraceReplay` | 27 errors | 7 comparator errors; first f9710 `queue.s3k_kos_direct.busy` true/false, unchanged | raw 17278 direct `#20`, fingerprint expected `fbfc78d4...`, actual `c2b0befc...`; 26 secondary method errors described above |
| `TestS3kGumballBonusTraceReplay` | 1 pass | PASS through structural bonus boundary raw 1276 | Future outer-transition timing tail correctly remains out of standalone scope |
| `TestS3kHczCompleteRunTraceReplay` | 2 errors | 28 comparator errors; first f3253 `tails_x_speed` expected 0, actual `0x005B`, unchanged | raw 3341 direct `#90`, engine pending none; second XML error is follow-on bootstrap context |
| `TestS3kIczCompleteRunTraceReplay` | 1 error | 10 comparator errors; first f12320 module queued/fingerprint state, unchanged | raw 12380 direct `#245`, fingerprint expected `66961069...`, actual `403b1d33...` |
| `TestS3kLbzCompleteRunTraceReplay` | 1 error | 7 comparator errors; first f17599 `queue.s3k_kos_direct.busy` true/false, unchanged | **advanced** from raw 17604 direct `#279` to raw 19871 direct `#282` (`45546caa...`), engine pending none |
| `TestS3kMgzCompleteRunTraceReplay` | 1 error | 23 comparator errors; first f16512 `queue.s3k_kos_direct.busy` true/false, unchanged | raw 17952 direct `#149`, engine pending none |
| `TestS3kMgzTraceReplay` | 1 error | 79 comparator errors; first f13903 `animation` expected `0x13`, actual `0x05`, unchanged | raw 14386 direct `#24`, engine pending none |
| `TestS3kMhzCompleteRunTraceReplay` | 1 error | 865 comparator errors; first f3420 `rings` expected 3, actual 4, unchanged | raw 7221 direct `#335`, engine pending none |
| `TestS3kPachinkoBonusTraceReplay` | 1 pass | PASS through structural bonus boundary raw 2902 | Future outer-transition timing tail correctly remains out of standalone scope |
| `TestS3kSlotsBonusTraceReplay` | 1 pass | PASS through structural bonus boundary raw 1028 | Future outer-transition timing tail correctly remains out of standalone scope |
| `TestS3kSpecialStageTraceReplay` | 2 pass | PASS | No timing exception |

## Wave 1 frontier conclusions

- Gumball, Pachinko, and Slots move from terminal unconsumed timing-tail errors
  to green without consuming or mutating the outer transition's future edges.
- LBZ now production-submits and owns both miniboss-box KosM parents. Recorded
  direct children `#279` at raw 17604 and `#280` at raw 19709 are consumed; the
  next missing producer is direct `#282` at raw 19871 (`45546caa...`).
- Every other canonical S3K comparison frontier is unchanged. The focused
  authority matrix grew and remains entirely green, so Wave 1 did not weaken
  kind, ordinal, fingerprint, preparation, service-boundary, or rewind checks.
- No S1 or S2 class regressed.

## Wave 2 owner order

The next implementation design should rank production owners in this order:

1. AIZ standard title-retirement ownership for direct `#47`.
2. The shared deferred-transition admission/order owner indicated by CNZ
   standard direct `#20` and ICZ direct `#245` fingerprint mismatches.
3. CNZ complete placement/producer ownership for direct `#203`.
4. MGZ complete results/title handoff for direct `#149`.

AIZ complete raw 6351/module `#16` remains a separate native-recorder
observation-row/service-row attribution audit; it does not authorize fixture
replacement or engine preparation. MGZ standard (animation), HCZ
(`tails_x_speed`), and MHZ (rings) remain gameplay-first exclusions because
their canonical divergences precede the terminal queue diagnostics.
