# S3K PSG admission silence validation — 2026-09-04

## Scope and source

Measured in `.worktrees/audio-round-s3k`, branch
`bugfix/ai-audio-round-s3k`, based on `develop` commit `4296bc291`.
Implementation is the commit containing this report. Maven ran on OpenJDK
21.0.11 with `-Dmse=off`; all build output and logs stayed in this worktree's
`target/`. No Maven invocations overlapped in this worktree.

The discovered locked-on ROM, `Sonic and Knuckles & Sonic 3 (W) [!].gen`,
was verified as SHA-1 `cfbf98c36c776677290a872547ac47c53d2761d6`.
Existing modified disassembly submodules were read only and preserved.

Commands below replace the executed machine-local paths with `SONIC1_ROM`,
`SONIC2_ROM`, and `S3K_ROM`. Export these to the discovered absolute ROM paths
before running from a worktree; a missing or relative path can silently skip
ROM tests. S1 REV01 SHA-1 is `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`;
S2 REV01 is `8bca5dcef1af3e00098666fd892dc1c2a76333f9`. Literal executed
commands remain in `target/gates/s3k-executed-commands.txt`.

`Sound/Z80 Sound Driver.asm:2131-2136`,
`zGetSFXChannelPointers.is_psg`, emits an unconditional PSG `FF` after calling
`zSilencePSGChannel` on the shipped `fix_sndbugs=0` branch. The fixed branch
relies on corrected channel silence instead. This guarantee applies to all
PSG header entries, not just PSG3. `Sound/SFX/59 - Collapse.asm:7-10` declares
FM3, FM4, FM5, PSG3; therefore the noise silence follows the three FM
initialization groups and precedes the music walk.

The semantic `psgSfxAdmissionSilencesNoise` setting defaults false, is enabled
by the S3K profile, and survives the presentation configuration copy. The
driver emits raw `FF` in declared header order, independently of incoming
track override bits and hardware ownership. Shared code tests no game name,
zone, request ID, or comparison service number.

## Red-first evidence

Fresh focused baseline command:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B \
  "-Ds3k.rom.path=$S3K_ROM" \
  -Dtest=TestS3kOracleRequestSidecarWiring,TestS3kSfxLifecycleRom,TestSmpsSequencerConfigCopyCoverageGuard test
```

Result: **14 tests, zero failures/errors/skips**, exit 0. Log:
`target/gates/s3k-baseline.log`. The pre-existing oracle test pins service
1569 and the DAC test pins run 338, byte 0; this green result is not an
all-matching oracle claim.

The red command added `TestPsgSfxAdmissionNoiseSilence` to that selection.
The corrected red run (`target/gates/s3k-red-corrected.log`) was **18 tests,
3 failures, zero errors/skips**, exit 1:

- Runtime Collapse admission: write 15 should be `psg[FF]`, but was the next
  music write `ym0[A4]=1A`.
- PSG1/PSG2 synthetic admission: expected two guaranteed `FF` writes, got
  none. The final test separates these into independent parameterized
  admissions, avoiding any implication that consecutive-header stale-IX
  behavior is implemented.
- New hard matching-prefix gate: service 1569, event 15,
  `EVENT_VALUE_DIFFERENT`; reference PSG 255, engine YM2612 port 0 register
  164 value 34. Expected `MATCH`, so the new gate was demonstrated active
  before the production change.

An earlier test attempt (`target/gates/s3k-red.log`) had two synthetic setup
errors from passing null DAC data. Those were corrected to empty `DacData`
before the successful red reproduction; they are not parity results.

## Final focused verification

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B \
  "-Dsonic1.rom.path=$SONIC1_ROM" \
  "-Dsonic2.rom.path=$SONIC2_ROM" \
  "-Ds3k.rom.path=$S3K_ROM" \
  -Dtest=TestS3kOracleRequestSidecarWiring,TestS3kSfxLifecycleRom,TestPsgSfxAdmissionNoiseSilence,TestSmpsSequencerConfigCopyCoverageGuard,TestS3kSfxNoiseTailWriteStream,TestS3kSfxRuntimePathWithMusic,TestS3kNoiseFormEffectWriteStream,TestPreparedSfxAdmission,TestSfxAdmissionMutationJournal,TestS2SfxAdmissionChannelMask test
```

Result: **54 tests, zero failures/errors/skips**, exit 0. Log:
`target/gates/s3k-focused-green-final.log`. Coverage includes:

- Exact runtime Collapse admission sequence: 15 FM initialization writes,
  then `FF`, before the music walk.
- Independent PSG1 and PSG2 admissions with the S3K profile and the disabled
  default profile; configuration-copy survival.
- Hard `MATCH` plus literal count **1570 services**, covering ordinals
  0–1569; the next full-window frontier remains pinned separately.
- SFX noise tails, runtime paths, prepared admission and rollback journal,
  and S2 admission ownership.

Two existing test assumptions needed correction. Insta-shield admission now
expects exactly `[FF]`, retaining the assertion that its track has not yet
walked. The noise-form helper used the first PSG write as evidence the track
had run; admission now supplies that write, so it clears admission writes
before observing the first track pass. Its ROM-derived `DF`-before-`E7`
assertion remains. These changes do not remove a behavioral assertion to
hide a regression.

## Oracle measurement

After the production build, the command was (absolute ROM path normalized):

```bash
mvn -Dmse=off -q dependency:build-classpath -Dmdep.outputFile=target/gates/cp.txt
java -cp "target/classes:$(cat target/gates/cp.txt)" \
  com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
  --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
  --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json \
  --rom "$S3K_ROM"
```

Result: exit **3**, intentionally still a full-window mismatch. Log:
`target/gates/s3k-frontier-after.log`.

| Axis | Before | After |
|---|---|---|
| Service state/write comparator | 1569, event 15, reference PSG `FF`, engine YM0 `A4=22` | 1570, event 39, reference PSG `E7`, engine PSG `FF` |
| DAC byte stream | run 338, byte 0, reference `88`, engine `7F` | unchanged: run 338, byte 0, reference `88`, engine `7F` |

The comparator reports one first divergence, not an error count. The next
service exposes the already documented lazy noise-channel takeover write;
this patch does not remove it. No register ordering, tolerance, DAC filtering,
or reference contents were changed to move the frontier.

## Limits and integration

The preceding `zSilencePSGChannel` call reads stale IX. For Collapse that
register names its preceding FM5 header and contributes no PSG write. Other
header and service arrangements can contribute additional writes; this
change implements the guaranteed `FF` only, not full stale-IX serialization.
No DAC behavior or synth backend changed. No listening-parity or complete-run
oracle claim is made.

The lead owns ordinary/guard baseline, combined-development and merged-tree
regression comparison, release notes, integration, push and worktree cleanup.
Those delivery checks are not claimed by this focused lane report.
