# SMPS parity cycle 5: S3K SFX header-order admission

Status: delivered. Merged as develop `8a7dc5f15`; both post-merge suites passed
with exact candidate outcome equality, and develop was pushed through `9d153cf6a`.
The completed worktree and local branch were removed. Full-game audio parity
and authenticity remain open.

## Source and representation

Retail `zSFXTrackInitLoop` calls `zGetSFXChannelPointers` before copying the
current header, then finishes the pass with IX naming that newly initialized
track (`docs/skdisasm/Sound/Z80 Sound Driver.asm:1997-2103`). On the next PSG
header, shipped `fix_sndbugs=0` calls `zSilencePSGChannel` through that stale IX
and then emits unconditional `FF` (:2109-2165). Thus PSG2 to PSG1 is
`FF BF FF`, and the reverse is `FF 9F FF`. An intervening FM/DAC header replaces
IX and prevents a stale PSG latch.

S3K still services SFX in fixed channel-RAM order. A constructor-derived,
immutable index projection retains ROM header order for admission only and
resolves to the same live Track identities after snapshot restoration. It adds
no production mutable temporal state. The public test/manual `addTrack` seam
appends to that projection so its existing behavior is not silently lost. The
shipped-header reachability test proves every S3K SFX PlaybackControl header is
exactly `80`; arbitrary synthetic flag forms are outside this bounded repair.

## Red, corrections, and mutations

- Initial red: consecutive PSG headers produced `FF FF` instead of
  `FF BF FF` (`target/sfx-header-order/red.log`).
- The first implementation walked `getTracks()`. S3K sorting reversed the
  controlled PSG pair and erased an FM separator; those failures are retained
  in `target/sfx-header-order/green-attempt.log`.
- Omitting the previous-header silence fails with `FF FF`
  (`target/sfx-header-order/mutation-omit-previous.log`). Using the current
  header instead fails with `FF 9F FF`
  (`target/sfx-header-order/mutation-current-header.log`).

## Focused evidence

- Synthetic ordering, raw `80/A0/C0` mapping, and snapshot identity: 9 tests,
  no failures/errors/skips (`target/sfx-header-order/final-unit.log`). The test
  also pins fixed channel-RAM runtime order and parsed-SFX `addTrack` behavior.
- ROM-backed Skid header order: 1 test, no failures/errors/skips
  (`target/sfx-header-order/skid-rom.log`).
- Combined restored hard-oracle run: 11 tests, no failures/errors/skips
  (`target/sfx-header-order/restored-focused.log`). The frontier advances from service
  2012 event 1 to service 2357 `MUS_FM4.overridden` (`false` vs `true`).

## Final candidate verification

All commands ran after merging verified develop `cc75320b0`, with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk` and absolute S1 REV01, S2 REV01, and
locked-on S3K ROM properties.

| Run | Command scope | Result | Evidence |
|---|---|---|---|
| Expanded focused | `mvn -Dmse=off -Dtest=TestPsgSfxAdmissionNoiseSilence,TestS3kSfxLifecycleRom,TestS3kOracleRequestSidecarWiring,TestS3kNoiseFormEffectWriteStream,TestS3kSfxNoiseTailWriteStream,TestS3kSfxRuntimePathWithMusic,TestPreparedSfxAdmission,TestSfxAdmissionMutationJournal,TestSmpsDriver,TestSmpsSequencerSnapshot,TestSmpsSequencerConfigCopyCoverageGuard,TestS2SfxAdmissionChannelMask,TestS1SfxTakeoverOrder test -B` | 92 tests, 0 failures/errors/skips | `target/sfx-header-order/final-focused.log`; XML in `final-focused-reports/` |
| Ordinary | `mvn -Dmse=off test -B` | 16,666 tests, 0 failures/errors, 43 skips | `target/sfx-header-order/final-ordinary.log`; XML in `final-ordinary-reports/` |
| Structural guards, separate JVM | `mvn -Dmse=off -Pguards test -B` | 609 tests, 0 failures/errors/skips | `target/sfx-header-order/final-guards.log`; XML in `final-guards-reports/` |

The cycle-4 baseline is develop `cc75320b0` (source merge `1e33747f1`): 16,661
ordinary tests with 43 skips and 609 guard tests. Root comparison preserves all
15,712 distinct ordinary baseline identity/status/message outcomes, adds five
passing identities, and preserves all 609 guard outcomes exactly. No rename or
removal allowance was needed. The logs are main
`target/audio-parity-cycle5-candidate-{ordinary,guards}-comparison.log`.

## Integration and archived evidence

Fetch and fast-forward pull left main develop unchanged at `cc75320b0`.
Candidate `3818dca75` already contained that baseline. Merge `8a7dc5f15`
was conflict-free and preserves the user's three modified disassembly
submodules. Main post-merge commands use the same JDK and absolute ROM inputs,
with logs/XML below `target/audio-parity-cycle5-postmerge-evidence/`.

Candidate focused/full/guard logs and XML, initial failures, negative controls,
and generated local configuration/rewind reports were archived outside the
repository at `${EVIDENCE_ROOT}/cycle5-candidate-3818dca75.tar.gz`, SHA-256
`24ff0edff76b50a322c96ae0dae89b27407ae820d25f82667de6fb23ab8e5ffb`.
This archive preserves reports, not a shared Maven build tree.

Post-merge `mvn -Dmse=off test -B` passes 16,666 tests, zero failures/errors,
43 skips. The separate `mvn -Dmse=off -Pguards test -B` passes 609 tests,
zero failures/errors/skips. Both include the same three absolute ROM properties.
Exact candidate comparison preserves all 15,717 distinct ordinary outcomes and
609 guard outcomes, with no additions, removals or changed statuses/messages.
Post-merge evidence and all four comparison logs are archived at
`${EVIDENCE_ROOT}/cycle5-postmerge-8a7dc5f15.tar.gz`, SHA-256
`b9c69f048e8bbf0b7257655009d8d3b5bd7b88fab69e2d8bec17ce74ba7f3075`.
Develop was pushed through evidence commit `9d153cf6a`. After confirming clean
tracked/untracked state and merged ancestry, the completed
`.worktrees/sol-s3k-sfx-header-order` worktree and
`bugfix/ai-sol-s3k-sfx-header-order` local branch were removed and metadata pruned.
Only inspected generated build/cache/config/report files and hook-created links
were discarded; original ROMs, user disassembly modifications and other
worktrees were preserved. Archived evidence remains recoverable outside the repo.
