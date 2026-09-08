# S3K PSG takeover validation — 2026-09-05

## Scope and source

Worktree `.worktrees/audio-next-psg`, branch `bugfix/ai-audio-next-psg`,
base `bbf28b7dc`. Implementation is the commit containing this report.
Maven 3.9.16 ran on JDK 21.0.11 with `-Dmse=off` and `LUA_BIN=lua5.4`.
All build outputs and logs stayed in this worktree's `target/`; no Maven
invocations overlapped within the worktree. Other worktrees were running
independently, so these results make no performance claim.

Absolute ROM paths pointed to the main workspace's `s1.gen`, `s2.gen` and
`s3k.gen`. Commands below normalize the machine-local prefix to
`PSG_ROM_ROOT`; set that variable to the main workspace's absolute path
before running. SHA-1 verification returned
`69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`,
`8bca5dcef1af3e00098666fd892dc1c2a76333f9` and
`cfbf98c36c776677290a872547ac47c53d2761d6`, respectively. Source research
read the main workspace's existing disassembly without modifying it.

Retail `fix_sndbugs=0` in `Sound/Z80 Sound Driver.asm:16` selects
`cfSetPSGNoise`'s branch at :3559-3572. It sends `DF`, then the operand
(or `FF` for operand zero), without a subroutine call between writes.
Admission has already sent the guaranteed `FF` through
`zGetSFXChannelPointers.is_psg` (:2131-2136). The engine claimed PSG3's
declared tone slot during admission, then independently claimed the noise
slot on the command's first noise latch. Its default `FORCE_SILENCE`
policy inserted another `FF` at that second acquisition.

The sole production change selects the existing `REGISTER_SEQUENCE` policy
in the S3K provider. Locks, latch state, override bits, priority, admission
ordering, release, rollback and other game profiles are unchanged. No
comparator, reference data, tolerance, timing, or DAC behavior changed.

## Red-first checks

The initial command was:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B \
  "-Ds3k.rom.path=$PSG_ROM_ROOT/s3k.gen" \
  '-Dtest=TestS3kNoiseFormEffectWriteStream,TestPsgSfxAdmissionNoiseSilence,TestS3kOracleRequestSidecarWiring#theServiceStreamMatchesThroughCollapseFirstWalk' test
```

`target/gates/psg-red.log`: 9 tests, 5 failures, zero errors/skips,
exit 1. The three ROM effects showed `DF FF E7`; the extended 1571-service
`MATCH` gate failed at service 1570, event 39, reference `E7`, engine `FF`.
The zero-operand observer initially saw no writes because admission defers
the first walk. Its bounded wait was corrected before treating it as evidence.

The same selection in `psg-red-corrected.log` again ran 9 tests with 5
failures, zero errors/skips. The zero test now observed
`DF FF FF DF FF`: the command's extra `FF`, the following rest's `DF`, and
existing inactive-noise-slot reconciliation at the end. The final zero
assertion therefore checks the command through the next rest (`DF FF DF`),
without conflating reconciliation with the command being fixed.

That final zero assertion was separately observed red with
`-Dtest=TestPsgSfxAdmissionNoiseSilence#zeroNoiseOperandWritesOneNoiseSilenceAfterAdmission`:
1 test, 1 failure, zero errors/skips, exit 1 (`psg-zero-red.log`),
expected `DF FF DF`, actual `DF FF FF`. Admission still asserted exactly `FF`.

## Evidence-driven prefix correction

After the profile change, the initial selection passed all eight admission
and noise-pair checks but the extended service gate failed later in the
same service: event 43, reference YM0 `A4=22`, engine PSG `F0`
(`psg-green-prefix.log`: 9 tests, 1 failure, zero errors/skips, exit 1).
The lead approved keeping the full-service `MATCH` bound at 1570 services
and adding a hard equality check of service 1570's first 43 ordered service
writes. This is progress within a service, not a matching 1571-service claim.

The ordered-prefix helper applies the existing service-axis definition:
exclude DAC sample bytes (`2A`) and sample-end disable (`2B=0`), preserve
every other write in order including `2B=80`. The full-window DAC gate and
production comparator are untouched. Removing the production setting
made the new assertion fail at the original inserted `FF`:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B \
  "-Ds3k.rom.path=$PSG_ROM_ROOT/s3k.gen" \
  '-Dtest=TestS3kOracleRequestSidecarWiring#collapseFirstWalkMatchesItsFirst43OrderedServiceWrites' test
```

`psg-ordered-prefix-red.log`: 1 test, 1 failure, zero errors/skips,
exit 1. The profile change was then restored for final verification.

## Focused verification

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B \
  "-Dsonic1.rom.path=$PSG_ROM_ROOT/s1.gen" \
  "-Dsonic2.rom.path=$PSG_ROM_ROOT/s2.gen" \
  "-Ds3k.rom.path=$PSG_ROM_ROOT/s3k.gen" \
  '-Dtest=TestS3kOracleRequestSidecarWiring,TestS3kNoiseFormEffectWriteStream,TestPsgSfxAdmissionNoiseSilence,TestS3kSfxLifecycleRom,TestS3kSfxNoiseTailWriteStream,TestS3kSfxRuntimePathWithMusic,TestS1SfxTakeoverOrder,TestS2SfxAdmissionChannelMask,TestPreparedSfxAdmission,TestSfxAdmissionMutationJournal,TestSmpsAssetCatalog,TestSmpsSequencerConfigCopyCoverageGuard' test
```

Result: **74 tests, zero failures/errors/skips**, exit 0,
`target/gates/psg-focused-final.log`.
The selection checks the changed pair, retained admission silence, default
and S1/S2 policies, configuration copying, replacement, release, noise tails
over music, prepared admissions and rollback.

## Independent oracle measurement and limits

After compiling the changed production source:

```bash
mvn -Dmse=off -q dependency:build-classpath -Dmdep.outputFile=target/gates/cp.txt
java -cp "target/classes:$(cat target/gates/cp.txt)" \
  com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
  --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
  --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json \
  --rom "$PSG_ROM_ROOT/s3k.gen"
```

`psg-frontier-after.log`: exit 3, `EVENT_VALUE_DIFFERENT`, service 1570,
event 43, reference YM2612 port 0 register `A4` value `22`, engine PSG `F0`.
Independent DAC comparison remains `BYTE_DIFFERENT`, run 338, byte 0,
reference `88`, engine `7F`. There is one first divergence per comparator,
not a total mismatch count.

The next volume-write candidate is `playNote`'s modulation send
(`SmpsSequencer.java:2916`) reaching `writeTrackFrequency`'s volume tail
(:3733-3762), followed by its separate attacked-note volume send (:2938).
Collapse's script starts modulation and a fresh `nB3` with no volume envelope;
retail `zUpdatePSGTrack` (:4054-4137) converges new-note and note-going paths
onto one volume tail. This is source-based attribution for a separate fix,
not a dynamic stack proof or implementation in this change.

The stale-IX admission silence remains unmodelled. The zero-operand test
also exposed an existing final reconciliation silence, outside the command
prefix asserted here. No listening parity or full-window parity is claimed.
Full ordinary/guard regression comparisons, integration and push belong to
the lead and are not claimed by this focused lane.
