# Third Sol SMPS parity cycle

Status: delivered; merged, verified, pushed and completed worktrees removed.
Baseline: develop `32522d7cb`, including the intervening audio-performance
delivery. Baseline and candidate each have a separate worktree and `target/`
tree to avoid sharing build output with that concurrent delivery.

## Scope and review

The first reviewed contribution, `b39a47844`, selects the existing fixed-channel
SFX walker for S3K. Retail `zUpdateSFXTracks` iterates its seven physical slots:
FM3 through FM6, then PSG1 through PSG3. The engine's header/arrival-order
default instead serviced older Collapse PSG before newly admitted Flying FM4.
This is a driver configuration rule, not a movie-specific runtime exception.

Independent review checked the shared ordering keys, request/walk timing and
comparison authority. Requests enter the established driver-isolation path;
tracks, ownership and expected writes are not hydrated from the reference.
The full service comparator matches the prefix through service 1689; the
additional service-1652 check does not replace that complete-prefix assertion.
The next mismatch is service 1690 event 6, reference PSG FF versus engine C0.

The reviewed follow-on preserves the retail stop-path silence transaction.
The shipped `fix_sndbugs=0` pointer helper emits a tone mute, a conditional
noise mute and an unconditional noise mute, before ownership release. Its
driver-owned transaction retains source attribution and does not change locks.
Only the S3K handler selects this behavior. An initial test manufactured intended
bytes inside a spy and was rejected; its replacement records calls at the actual
injected physical target, including ordinary-write suppression and invalid-input
no-write behavior. Commit `284e8b3b8` passed independent source/test review and
was merged as `09f8ae434`; its shared-driver addition merged cleanly with the
intervening performance query change.

Both deliberate mutations fail the physical observation test: deleting the
final FF produces `DF FF` instead of `DF FF FF`; admitting an ordinary overridden
music write fails the empty-bus assertion. The worker reports exit zero after
restoring production; its quiet success log is empty and is not independent
proof of that outcome. The lead's nonquiet 57-test run establishes the restored
test result. Mutation commands use JDK 21:

```bash
mvn -q -Dmse=off '-Dtest=TestSmpsDriver#driverSilenceWritesWhileMusicIsOverriddenWithoutChangingOwnership' test
```

Worker logs `mutation-driver-silence-delete-final-ff.log`,
`mutation-driver-silence-admit-ordinary-overridden.log` and
`mutation-driver-silence-restored-green.log` were copied into the candidate's
`target/audio-parity-cycle3-candidate-evidence/mutations/`. The lead's nonquiet focused
run below independently verifies the restored implementation and surrounding
S3K cases.

The worker reports the next mismatch at service 1690 event 7, reference PSG E7
versus engine C0. This is a separate music-noise restoration discrepancy, not
proof that the whole stop/release path matches. No restoration repair is included
in this batch.

### Next bounded repair (not implemented)

Two independent source reviews agree on retail `cfStopTrack` /
`zStopPSGTrack`: only SFX stops restore music; the PSG branch does not require
the covered music track to be active or change its resting state. With the
music noise bit set, it writes the stored raw noise byte unchanged only when
its sign bit is set. Tone mode and nonnegative noise bytes produce no restore
write. The Java legacy release policy instead refreshes frequency/volume.

The ownership boundary must be reviewed with that policy: a noise-form PSG3
effect can own both tone slot 2 and noise slot 3. Both matching locks and the
ended track's channel-2 admission claim must be released before the music callback,
without releasing another effect's ownership. Required regression cases include
physical `DF FF FF E7` order, ownership observed during E7, inactive music,
both resting states, exact negative raw values, nonnegative/noise-disabled
no-write behavior, music-stop helper only, and unchanged S1/S2 policies.

## Verification plan and evidence

Use JDK 21 and absolute paths for all three discovered ROMs:

```bash
mvn -Dmse=off "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" test -B
mvn -Dmse=off -Pguards "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" test -B
mvn -Dmse=off "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" -Dtest=TestS3kOracleRequestSidecarWiring,TestS3kSfxLifecycleRom,TestS3kSfxNoiseTailWriteStream test -B
```

| Run | Outcome |
|---|---|
| Baseline ordinary, `32522d7cb` | 16,651 tests, 0 failures/errors, 43 skips |
| Baseline guards, `32522d7cb` | 609 tests, 0 failures/errors/skips |
| Initial slot-order candidate focused run | 20 tests, 0 failures/errors/skips |
| Final candidate focused, `09f8ae434` | 57 tests, 0 failures/errors/skips |
| Initial candidate ordinary, production `09f8ae434` | 16,654 tests, 4 failures, 0 errors, 43 skips; not accepted |
| Candidate guards, `091669935` | 609 tests, 0 failures/errors/skips; exact baseline outcome equality |
| Corrected candidate focused, `8306b9a3a` | 75 tests, 0 failures/errors/skips |
| Corrected candidate ordinary, source/tests `8306b9a3a` | 16,654 tests, 0 failures/errors, 43 skips; every baseline outcome preserved, three passing additions |
| Corrected candidate guards, `f241de4e0` | 609 tests, 0 failures/errors/skips; exact baseline outcome equality |
| Post-merge ordinary, `ebb024201` | 16,654 tests, 0 failures/errors, 43 skips; exact candidate outcome equality |
| Post-merge guards, `ebb024201` | 609 tests, 0 failures/errors/skips; exact candidate outcome equality |

Compare exact distinct test identities/statuses/messages as well as log totals.
Preserve initial failed executions and explicitly review any test rename or
coverage supplement. Fetch and reconcile intervening develop changes before
integration; do not overwrite the other delivery's reports or user changes.

The corrected ordinary comparison preserves all 15,702 distinct baseline
identity/status/message outcomes and adds three passing tests: physical driver
silence, tone/noise F2 stop writes, and the service-1652 fixed slot-order case.
No identity normalization or removed-test allowance was needed. The four
corrected existing assertions still run under their original identities.

## Integration

Develop merge `ebb024201` was clean. The intervening `cea8ad3d5` update only
recorded the already-included performance delivery's verification; it was
reconciled without source or test changes. Ordinary and separate guard runs
on the merged main workspace exactly match the corrected candidate. A direct
baseline-to-merged ordinary comparison also preserves every baseline outcome
and adds only the same three passing identities. No worktree branch was pushed.

Evidence archives under `${EVIDENCE_ROOT}/smps-parity-cycle-20260905/` include
`cycle3-baseline-32522d7cb.tar.gz` and `cycle3-candidate-33663e4c0.tar.gz`;
the latter retains the initial failed suite and all correction evidence. Main
post-merge logs and XML remain in `target/audio-parity-cycle3-postmerge-evidence/`
with exact comparisons beside it. Public release copy now reports this bounded
F2/slot-order result, not full-game parity or completed human listening QA.

Develop was pushed through `74a7c838b` after both post-merge comparisons passed.
The completed `audio-parity-cycle3`, `audio-parity-cycle3-baseline`,
`sol-s3k-sfx-slot-order` and `sol-s3k-runtime-tail-harness` worktrees were removed
after confirming clean tracked state and merged ancestry. Their three named
local branches were deleted and worktree metadata pruned. Ignored artifacts were
classified as generated outputs/configuration, test-produced reports/caches or
hook-created ROM/reference links; evidence and local configurations were archived
before removal. Originals, the user's disassembly changes and unfinished
noise-restoration/native work remain intact. Additional archives are
`cycle3-postmerge-ebb024201.tar.gz` and
`cycle3-generated-local-configs-and-reports.tar.gz`.

Native tempo-read work and full-movie S1 diagnostic captures remain separate,
unpublished work. Their capture progress is not a sealed reference, production
authentication or Java parity pass.

### Initial full-suite failures

The first candidate ordinary run completed with four assertion failures, all
reproduced in a fresh two-class run (18 tests, four failures, no errors/skips).
`TestSonic3kFm3SpecialMode` has two F3 raw-operand cases whose terminal F2
expectations omit the retail unconditional FF; source review confirms the
expected full sequences need that byte in both noise and reset cases. Their
raw-operand state assertions remain unchanged.

Correction `091669935` passes a 52-test focused run. Deleting the default leaf
transaction's unconditional FF deliberately makes both corrected F3 cases fail;
restoring production passes the same 52-test selection again. The nonquiet
mutation and restored logs plus XML are retained under
`target/audio-parity-cycle3-candidate-evidence/f2-correction/`. No production
change from that mutation remains.

`TestS3kSfxRuntimePathWithMusic` also failed Collapse and spindash tail assertions:
observed reduced noise levels contain a final 4 or 6 after silence. Its observer
collected the whole physical bus until after the final presentation frame,
mixing the ending effect with same-service covered-track restoration and music.
Reviewed test-only `ed6167919`, merged as `8306b9a3a`, follows the requested
base-game SFX identity through an observed alive-to-dead transition and requires
a unique physical `DF FF FF` stop transaction in that frame. It separately
retains and characterizes the complete suffix; that known-gap restoration is
explicitly not asserted as retail parity and must change with the next repair.

Collapse has five 24-tick notes, not six timed repeats; its six attenuation
levels include silence. Literal ROM durations plus deferred admission and the
following F2 frame give 122 presented frames in this NTSC fixture; Dash's
6+79 ticks give 87. A temporary production duration decrement of two instead of
one makes the tests fail at 62 and 45 respectively. Restored focused verification
passes all six live-path and isolated-tail tests. No runtime mutation remains.
Named nonquiet logs are retained in
`target/audio-parity-cycle3-candidate-evidence/runtime-tail/`. Initial failed logs
and XML remain in `target/audio-parity-cycle3-initial-failure-evidence/`.
