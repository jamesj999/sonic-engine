# First Sol SMPS parity delivery cycle

Status: delivered on develop through `7f73667b8`; post-merge verification, push
and completed-worktree cleanup succeeded.
Integration baseline: `adcd0a3fa` (includes the independently delivered Continue
screens). Initial investigation baseline was `eb324f5c6`.

## Scope and evidence

The goal is per-game retail SMPS behavior, not matching only current recordings.
This cycle delivers bounded S3K repairs and production observation proof while
the larger [roadmap](../../plans/audio/2026-09-05-audio-trace-coverage-roadmap.md)
remains active. No chip replacement or waveform comparison is involved.

| Contribution | Source worker commit | Evidence and limits |
|---|---|---|
| Remove duplicate PSG note volume | `c9a79620e`, hardened by `6ba9d0a25` | Collapse note regression fails with two writes before correction; prior service prefix retained |
| Preserve PSG frequency transaction | `e5622b96a` | Source-track gate admits both exact bytes; AF FF retains physical FF latch semantics; denied/invalid pairs cannot leak writes or mutate latch state |
| Observe production admission | `3a32b78ba`, `648b864e5`, `dd4661be0` | Jump/ring suppression mutation causes both tests to fail; explicit blocked ring event precedes selection; accepted jump writes attributed to its service |
| Restore FM3 mode before voice | `5f87e16ae` | Normal/special mode tests fail without YM27 and assert the exact write before voice reload; non-FM3 and other release profiles excluded |
| Compare driver fade counters | `0c7523b1f` | Independent fade-in/out mutations fail at service 37; songless driver counters are projected rather than omitted |

The hard S3K oracle advances from service 1570 event 43 to service 1592.
Services 0 through 1591 match. The next mismatch is `MUS_PSG3.volEnv`, reference
0 versus engine 1; it is not a full-window pass. DAC content/timing
limitations remain separate. See `docs/status/audio-frontier-log.md` for the
source-level diagnostic record and selected focused commands.

Review challenged the inference that a note's volume tail was already emitted;
the guard was restricted to the actual supported modulation/channel conditions.
Review also rejected reinterpreting FF as frequency data: the hardware still
decodes it as a latch, regardless of the original driver's intended transaction.
The implementation changes logical ownership arbitration, not physical bytes.
Contention diagnostics now report one decision per frequency transaction rather
than two per-byte decisions; chip observers still receive both physical writes.

Production observer tests are independent controlled stimuli through AudioManager,
not a native differential comparison or reference-fed gameplay replay. The tests
assert request/decision/ownership ordering, no SFX admission on discard, ring
phase preservation, and accepted jump service/write attribution after restore.
Required-event lookup fails on missing events rather than allowing index -1 to
accidentally satisfy an ordering assertion.

## Verification ledger

All Maven runs use JDK 21 and absolute discovered ROM paths supplied through
`sonic1.rom.path`, `sonic2.rom.path` and `s3k.rom.path`. Reproducible commands:

```bash
mvn -Dmse=off "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" test -B
mvn -Dmse=off -Pguards "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" test -B
```

Set those three variables to absolute ROM paths before invocation. Main-workspace
baseline output is preserved below `target/audio-trace-coverage-updated-baseline-evidence/`;
candidate output stays below its own worktree's `target/`.

| Run | Outcome |
|---|---|
| Updated baseline ordinary, `adcd0a3fa` | 16,626 executions, 0 failures/errors, 43 skips |
| Updated baseline separate guards, `adcd0a3fa` | 609 executions, 0 failures/errors/skips |
| Lead's first assembled focused run, before pair integration | 34 tests, 0 failures/errors/skips |
| First candidate ordinary, before fade gates and FM3 restore | 16,634 executions, 0 failures/errors, 23 skips; execution permissions differed from baseline, so final comparison reruns under matching conditions |
| Final assembled candidate ordinary | 16,641 executions, 0 failures/errors, 43 skips; every baseline outcome retained after the explicitly reviewed rename, plus 15 new passing outcomes |
| Final assembled candidate separate guards | 609 executions, 0 failures/errors/skips; exact baseline identity/outcome equality |
| Final focused candidate selection | 65 executions, 0 failures/errors/skips |
| Post-merge ordinary, `a1e00c643` | 16,641 executions, 0 failures/errors, 43 skips; exact identity/outcome equality with candidate |
| Post-merge separate guards, `a1e00c643` | 609 executions, 0 failures/errors/skips; exact candidate identity/outcome equality |

Compare exact distinct test identity/status/message outcomes as well as summed
per-class and terminal log totals. Surefire nested suites duplicate XML entries;
raw XML testcase count is not the execution count. One intentional test rename
is `collapseFirstWalkMatchesItsFirst43OrderedServiceWrites` to
`collapseFirstWalkMatchesItsFirst48OrderedServiceWrites`; inspect the strengthened
assertion separately instead of treating its old identity as a lost test.

The first ordinary comparison also lost six ICZ identities to overwritten nested
reports. A separate baseline ICZ run passed nine executions. The final candidate
full run contains all baseline ICZ identities, so no substitute evidence was
needed for the final ordinary comparison. The raw comparator reports exactly one
removed passing identity and its renamed replacement; normalizing only that
explicit pair yields zero removed/changed outcomes and 15 added passing outcomes.

Final focused command:

```bash
mvn -Dmse=off "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" -Dtest=TestS3kProductionAdmissionObservation,TestS3kOneUpRestoreRom,TestAudioManagerDiagnosticObserverLease,TestS1SfxTakeoverOrder,TestS3kSfxNoiseTailWriteStream,TestSonic3kFm3SpecialMode,TestS3kFadeCounterParity,TestS3kOracleRequestSidecarWiring test -B
```

## Separate reference-tooling lane

TraceChaser worktree commits `d9cd72a` and `96a9dc2` add fixed S3K external tempo
observation and an executable diagnostic selector. They are local and unpublished:
no OpenGGF submodule pointer update or remote delivery is claimed here.

The lead independently checked both duplicate raw capture hashes:

```text
acfb164ae1444bb34bc8f46022e59257e8701be3d29bce3163e823c1f8beb43f
```

Each contains 5,400 observed movie rows. The worker identified tempo value 08 at
row 3072 and 00 at row 4269 with native begin/end ordinals and service identities.
These remain diagnostic evidence, not authenticated production fixtures; Java
service-phase correlation and extracted-input integration are still in progress.
The native worker reports the same 186 failures on pinned and modified standalone
trees, with two additional passing tests. This is not a full native-suite pass;
layout/environment attribution must remain separate from focused capability proof.

The compiled extractor produced `observations.json` (14 requests, two tempo
controls), SHA-256
`fcf309027da96b811b50db3b47f9acbec2061f75baeab975e7908b25990080c5`.
Reversing the duplicate inputs produces identical extracted bytes. The worker's
actual old-versus-new capture comparison reports no request transcript changes.
Repeated tempo values are negative/unit-test coverage, not observed occurrences
in this particular prefix. Service-phase replay still needs explicit validation.

## Integration and cleanup

The candidate includes develop's intervening Continue-screen work without
conflicts. Recording the already-integrated worker histories produced one
documentation-only conflict: the stale "No full-service progress" sentence was
discarded in favor of the reviewed matching-prefix limitation. The candidate
tree hash was identical before and after those history merges.

Develop merge `a1e00c643` completed without conflicts. Candidate and post-merge
ordinary and separate-guard outcomes match exactly. Develop was pushed through
`7f73667b8`. The three completed worktrees (`audio-trace-coverage`,
`sol-s3k-psg-parity`, `sol-s3k-admission-proof`) and their fully merged local
branches were removed, then worktree metadata was pruned. Verification artifacts
and local configuration copies were archived before cleanup. The generated
rewind probe report was identified by its test writer and archived as well;
original ROMs and user-modified reference submodules were preserved.
Subsequent envelope and S2 cadence work uses new isolated
worktrees and is not part of this delivery; unpublished TraceChaser work remains
separate. No unmerged work may be discarded for cleanup.
