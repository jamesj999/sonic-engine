# S3K queue lifecycle recovery Wave 2 validation

Date: 2026-08-02

## Context

- Worktree: `.worktrees/s3k-queue-lifecycle-recovery`
- Branch: `bugfix/ai-s3k-queue-lifecycle-recovery`
- Verified HEAD: `f05ac8eae539ffeffc90a5492bc7262c2eb2a2a7`
  (`refactor(level): extract seamless transition
  orchestration`), including the cadence correction at `f4651e8f1`
- Runtime: Maven 3.9.16 on JDK 21.0.11
- ROMs: S1 REV01 SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`,
  S2 REV01 SHA-1 `8bca5dcef1af3e00098666fd892dc1c2a76333f9`, and
  locked-on S3K SHA-1 `cfbf98c36c776677290a872547ac47c53d2761d6`
- Trace fixtures changed: none

Wave 2 moved S3K enemy-art admission to exact production-owned leases, made
carried results the sole CNZ/MGZ/LBZ title publisher, made ICZ resource
publication transactional, and repaired the generic late-placement lifecycle
that owns CNZ's later special-stage entry ring.

## Commands and outer results

Focused seven-route gate:

```bash
mvn -q -Dmse=off \
  -Dtest='TestS3kAizTraceReplay,TestS3kAizCompleteRunTraceReplay,TestS3kCnzTraceReplay,TestS3kCnzCompleteRunTraceReplay,TestS3kIczCompleteRunTraceReplay,TestS3kLbzCompleteRunTraceReplay,TestS3kMgzCompleteRunTraceReplay' \
  -Ds3k.rom.path=<repo>/s3k.gen test
```

Outer Maven exit: 1. Surefire reported 48 methods, 4 failures, 33 errors,
0 skipped, and 11 passes. The four failures are the existing focused AIZ
camera/sidekick assertions; the 33 errors comprise seven canonical hardware
admission terminals plus the CNZ class's follow-on context/metadata methods.

Exact authority/queue matrix:

```bash
mvn -q -Dmse=off \
  -Dtest='TestS3kKosDecompressionQueue,TestS3kKosDecompressionQueueLifecycle,TestS3kKosModuleQueue,TestS3kKosModuleReadiness,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard,TestHardwareTimingService,TestLevelIterationHardwareTimingAdmissionOrder,TestSpecialStageHardwareTimingLifecycle,TestTraceRunHardwareTimingCoordinator,TestTraceSuppressedRowClosure,TestLoadQueueTraceComparison,TestQueueDiagnosticSnapshot' \
  -Ds3k.rom.path=<repo>/s3k.gen test
```

Outer Maven exit: 0. The selected XML reports total 142 tests, 0 failures,
0 errors, and 0 skipped.

Complete trace fleet:

```bash
mvn -q -Dmse=off -Dtest='*TraceReplay' \
  -Dsonic1.rom.path=<repo>/s1.gen \
  -Dsonic2.rom.path=<repo>/s2.gen \
  -Ds3k.rom.path=<repo>/s3k.gen test
```

Outer Maven exit: 1. The exact wildcard run discovered 64 concrete classes
and 108 methods: 67 passed, 4 failed, 37 errored, and none skipped. S1 is
30/30 classes green, S2 is 20/20 green, and S3K is 4/14 green. The inventory
matches the expected 30 + 20 + 14 split exactly.

The method reconciliation is S1 30 + S2 21 + S3K 57 = 108. S2's special-stage
class contributes its second method; S3K comprises 16 passing, 4 failing, and
37 errored methods. Thus 30 + 21 + 16 = 67 passes, with the same 4 failures
and 37 errors reported by the outer run.

The standard and complete-run S3K classes for AIZ, CNZ, and MGZ publish to the
same zone-named comparator report paths. After preserving the wildcard totals,
two focused supplemental commands selected the three complete-run classes and
then the three standard canonical methods. Each exited 1 with 3 errors and no
failures. This disambiguates all six colliding reports without changing
production code or fixtures.

```bash
mvn -q -Dmse=off \
  -Dtest='TestS3kAizCompleteRunTraceReplay,TestS3kCnzCompleteRunTraceReplay,TestS3kMgzCompleteRunTraceReplay' \
  -Ds3k.rom.path=<repo>/s3k.gen test
mvn -q -Dmse=off \
  -Dtest='TestS3kAizTraceReplay#replayMatchesTrace,TestS3kCnzTraceReplay#replayMatchesTrace,TestS3kMgzTraceReplay#replayMatchesTrace' \
  -Ds3k.rom.path=<repo>/s3k.gen test
```

## Authority/queue matrix line by line

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

## Complete trace fleet line by line

Every ordinary route class below runs `replayMatchesTrace`; the XML-method
column calls out classes that also contain focused methods.

### Sonic 1 — 30/30 classes green

| Concrete class / canonical test | XML methods | Result | Comparator errors / first frontier |
|---|---:|---|---|
| `TestS1Credits00Ghz1TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits01Mz2TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits02Syz3TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits03Lz3TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits04Slz3TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits05Sbz1TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits06Sbz2TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Credits07Ghz1bTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1FzCompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Ghz1CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Ghz1TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Ghz2CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Ghz3CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Lz1CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Lz2CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Lz3CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Mz1CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Mz1TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Mz2CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Mz3CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Sbz1CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Sbz2CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Sbz3CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Slz1CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Slz2CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Slz3CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1SpecialStageTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Syz1CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Syz2CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS1Syz3CompleteRunTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |

All S1 comparator reports contain 0 errors. There is no terminal queue
admission exception.

### Sonic 2 — 20/20 classes green

| Concrete class / canonical test | XML methods | Result | Comparator errors / first frontier |
|---|---:|---|---|
| `TestS2Arz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2ArzLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Cnz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2CnzLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Cpz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2CpzLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2DezEndingLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Ehz1TraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Htz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2HtzLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Mcz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2MczLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Mtz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Mtz3LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2MtzLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2Ooz2LevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2OozLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2SczLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |
| `TestS2SpecialStageTraceReplay` | 2 | PASS | 0 / none |
| `TestS2WfzLevelSelectTraceReplay#replayMatchesTrace` | 1 | PASS | 0 / none |

All S2 comparator reports contain 0 errors. There is no terminal queue
admission exception.

### Sonic 3 & Knuckles — 4/14 classes green

The comparator count is the report's zero-tolerance error-group count. The
terminal column is the independent fail-closed hardware-timing state; a class
can have both comparator errors and one XML error.

| Concrete class / canonical test | XML methods | Result | Comparator frontier | Terminal raw hardware state |
|---|---:|---|---|---|
| `TestS3kAizCompleteRunTraceReplay#replayMatchesTrace` | 1 | ERROR | 56 errors; first f1237 `queue.s3k_kos_direct.busy`, expected false / actual true | raw 6346, direct `#35`, `c3e8ddd34bf587540ca7d131fc68d371538d1a746da64c4eee3ec01f524948b7`, pending `<none>` |
| `TestS3kAizTraceReplay#replayMatchesTrace` | 16 (11 pass, 4 fail, 1 error) | FAIL+ERROR | 58 errors; first f1527 `queue.s3k_kos_direct.busy`, expected false / actual true | raw 5543, direct `#36`, `c3e8ddd34bf587540ca7d131fc68d371538d1a746da64c4eee3ec01f524948b7`, pending `<none>` |
| `TestS3kCnzCompleteRunTraceReplay#replayMatchesTrace` | 1 | ERROR | 419 errors; first f12024 `g_speed`, expected `0x0018` / actual `-0018` | raw 13962, direct `#205`, `589a478d29f5c788ad304520acc86172ea220a4a68b5a74ac25ee62e80d5899c`, pending `<none>` |
| `TestS3kCnzTraceReplay#replayMatchesTrace` | 27 errors | ERROR | 95 errors; first f16661 `player_animation_id`, expected `0x0005` / actual `0x0013` | raw 17421, direct `#24`, `c2b0befca6c881f069f36f7bf5955eda3974e620af2f01172124ba808eeb4650`, pending `<none>` |
| `TestS3kGumballBonusTraceReplay#replayMatchesTrace` | 1 | PASS | 0 errors through structural boundary raw 1276 | No in-scope terminal timing exception; the outer transition tail is intentionally out of standalone scope |
| `TestS3kHczCompleteRunTraceReplay#replayMatchesTrace` | 2 errors | ERROR | 28 errors; first f3253 `tails_x_speed`, expected `0x0000` / actual `0x005B` | raw 3341, direct `#90`, `66961069e564ef707173bbad733f75e3ab034e29e3f4833a02e2e26af452d8fd`, pending `<none>` |
| `TestS3kIczCompleteRunTraceReplay#replayMatchesTrace` | 1 | ERROR | 9 errors; first f12320 `queue.s3k_kos_module.remaining_work`, expected `5` / actual `4` | raw 12380, direct `#245`, expected `66961069e564ef707173bbad733f75e3ab034e29e3f4833a02e2e26af452d8fd`, pending direct `#245` `403b1d33b7d7af9a32e45aca194d548b6c96a8fc718daca7e19aed05d14a14c8` |
| `TestS3kLbzCompleteRunTraceReplay#replayMatchesTrace` | 1 | ERROR | 7 errors; first f19870 `queue.s3k_kos_direct.busy`, expected true / actual false | raw 19871, direct `#282`, `45546caa5f444bff6604fc52442a5cb91754c273b5a016194c2e10ee92315ea5`, pending `<none>` |
| `TestS3kMgzCompleteRunTraceReplay#replayMatchesTrace` | 1 | ERROR | 17 errors; first f16512 `queue.s3k_kos_direct.busy`, expected true / actual false | raw 16655, direct `#147`, `e045a53989c11fc9a7d8e36f7f7418f40bd13144de810424122515cd609b9cca`, pending `<none>` |
| `TestS3kMgzTraceReplay#replayMatchesTrace` | 1 | ERROR | 79 errors; first f13903 `player_animation_id`, expected `0x0013` / actual `0x0005` | raw 14386, direct `#24`, `fbfc78d499717cfec6df27fdd04fa4b5293a7147ec7ff7a7a18004e9db801e78`, pending `<none>` |
| `TestS3kMhzCompleteRunTraceReplay#replayMatchesTrace` | 1 | ERROR | 865 errors; first f3420 `rings`, expected `3` / actual `4` | raw 7221, direct `#335`, `3c96d8b9573e86f26814cb8a605459c8fef23cc1ca5425db2fd1cc250d408d91`, pending `<none>` |
| `TestS3kPachinkoBonusTraceReplay#replayMatchesTrace` | 1 | PASS | 0 errors through structural boundary raw 2902 | No in-scope terminal timing exception; the outer transition tail is intentionally out of standalone scope |
| `TestS3kSlotsBonusTraceReplay#replayMatchesTrace` | 1 | PASS | 0 errors through structural boundary raw 1028 | No in-scope terminal timing exception; the outer transition tail is intentionally out of standalone scope |
| `TestS3kSpecialStageTraceReplay` | 2 | PASS | 0 errors | No terminal timing exception |

`TestS3kCnzTraceReplay`'s other 26 XML errors and
`TestS3kHczCompleteRunTraceReplay`'s second XML error are follow-on
bootstrap/context or metadata-variant errors after the canonical replay has
already terminated. They are retained in the 108-method totals but are not
separate trace frontiers.

## Frame-33 queue lane correction

The pre-correction fleet found the same eight f33 groups in CNZ standard and
complete, HCZ, ICZ, LBZ, MGZ standard and complete, and MHZ: direct
busy/prepared/source/destination plus module
busy/prepared/remaining/fingerprints. The sibling regression audit attributed
the common edge to `633e06cec`, where the skipped-title model let its lower-slot
owner observe the last higher-slot child retirement in the same dispatch.

Commit `f4651e8f1` restores ROM SST order: trace frame 33 drains the last child,
and frame 34 is the first subsequent owner dispatch that can release its exact
lease. The final fleet contains none of those f33 groups. Every affected class
loses exactly eight comparator groups and restores its prior first-error lane:
CNZ standard 95/f16661, CNZ complete 419/f12024, HCZ 28/f3253, ICZ 9/f12320,
LBZ 7/f19870, MGZ standard 79/f13903, MGZ complete 17/f16512, and MHZ
865/f3420. No fixture or hardware-timing authority changed.

## Frontier movement and attribution boundaries

- CNZ standard advances its terminal from raw 17278/direct `#20` fingerprint
  mismatch to raw 17421/direct `#24` absent after carried-title admission.
- CNZ complete consumes direct `#203` and `#204` and advances from raw
  9712/direct `#203` absent to raw 13962/direct `#205` absent after the generic
  late-placement lifecycle correction.
- AIZ standard and complete retain the Task 2 `PRESERVE_CURRENT` outcomes at
  raw 5543/direct `#36` and raw 6346/direct `#35`. The prior raw 6351/module
  `#16` service-row observation remains a separate native-recorder attribution
  lane; the campaign does not authorize fixture replacement.
- ICZ and LBZ terminal identities remain raw 12380/direct `#245` fingerprint
  mismatch and raw 19871/direct `#282` absent. MGZ complete now terminates at
  raw 16655/direct `#147` absent rather than Wave 1 raw 17952/direct `#149`;
  this backwards terminal is the corrected carried-results/title-owner
  producer sequence, while the separate common f33 regression is closed.
- MGZ standard's first gameplay divergence at f13903 `player_animation_id`,
  HCZ's at f3253 `tails_x_speed`, and MHZ's at f3420 `rings` remain separate
  gameplay lanes and are visible again after the common f33 correction.
- All S1 and S2 trace classes remain green. The four standalone S3K bonus and
  special-stage classes remain green. The 142-test strict timing/authority
  matrix remains fully green, so the observed red frontiers were not hidden by
  weakening production-job, fingerprint, preparation, boundary, or rewind
  checks.
