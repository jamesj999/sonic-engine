# S3K Collapse and Dash SFX validation

Status: implementation and automated verification green; listening and
integration are pending.

## Change

- Z80-family PSG note starts now publish only the post-modulation frequency,
  matching the single upload in `zUpdatePSGTrack`.
- Noise-latch/volume writes from a live PSG3/noise SFX use that logical track's
  PSG3 lock, avoiding a non-native mid-note noise silence.
- Each retiring FM track now releases its hardware lock at its own
  `cfStopTrack` boundary. FM5's terminal key-off and interrupted music-voice
  restore remain one audited transaction even while a longer PSG sibling is
  active; whole-SFX cleanup does not repeat them.
- The diagnostic host gained bounded Z80 RAM writes so isolated native SFX
  lifecycles can be regenerated without Lua Z80 hooks or production changes.

No SFX ID, game name, zone, route, or measured waveform constant enters
production code. S1/S2 retain their existing modulation algorithms.

## Evidence

- Native injected lifecycle: Collapse active through frame 121; Dash active
  through frame 86; two quiet terminal frames each.
- Native `cfStopTrack` key-offs FM5 on Collapse frame 18 and Dash frame 16,
  while their PSG3 siblings remain active. Before the correction the engine
  emitted neither terminal FM5 key-off and kept the music channel overridden
  until whole-SFX cleanup (Collapse frame 121 / Dash frame 86).
- Native Collapse rendered stereo RMS is 2566.75 left / 2398.23 right
  (ratio 1.070). The engine moved from 5734.37 / 3118.86 (ratio 1.839) to
  3116.01 / 3139.11 (ratio 1.007) after restoring the FM5 terminal key-off.
- Java ROM-backed lifecycle: 122 and 87 request-through-terminal updates.
- Effective native PSG state SHA-256:
  - Collapse: `d85bbd997725b5804d5990cb222f13a1c367ce2e76b628ab5ec61c515d81c584`
  - Dash: `0b7d78978c85bc7c021789c333594b96f905bbf2e64f1b2b3921751f2af1e093`

### Final-speaker correction after the listening gate

The first listening package still ended Collapse too abruptly. A fresh native
A/B capture added final presented PCM after GPGX's ordinary audio drain. The
two 125-frame captures were byte-identical at SHA-256
`5c6bfe3382749fa31137128a3bfd87d191da17d6ad33ece8c39d360e428ce7a3`
with zero overflow/fault. Raw native PSG has RMS `44.695811` on frame 121
and is exactly silent from frame 122. Final native PCM nevertheless decays with
RMS `88.917`, `21.669`, `5.142`, `1.273` on frames 121-124. GPGX owns that
tail through its globally selected `hq_psg=1` band-limited delta path; the
global post-filter is `None`.

OpenGGF already contained the matching HQ `PsgChip` path but no SMPS runtime
selected it. The correction enables it in `SmpsDriver`, so S1, S2, and S3K all
follow the same console-level GPGX setting. Sound_59 still executes five
24-tick bursts and completes after the same 122 request-through-terminal
updates. The aligned OpenGGF post-mute RMS is `76.086`, `18.124`, `4.291`,
`1.064`; its decay ratios match the native response. Disabling HQ mode makes
the final-PCM comparison fail before the mute, while the semantic lifetime
test remains unchanged. The compact comparison-only evidence is
`docs/architecture/research/audio/s3k-collapse-final-pcm-hq-psg-v1.json`.

Fresh JDK 21 verification for this correction:

```bash
mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
  -Dtest=TestS3kCollapseDashSfxParity,TestPsgChipGpgxParity test
# 27 tests, 0 failures/errors/skips

mvn -Dmse=off -Dsonic1.rom.path="$S1_ROM" \
  -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" \
  -Dtest=TestS3kCollapseDashSfxParity,TestPsgChipGpgxParity,\
TestPsgChipSnapshot,TestVirtualSynthesizerSnapshot,TestSmpsDriverSnapshot,\
TestSmpsDriverYmWriteTimeline,TestAudioPresentationSnapshotParity,\
TestSonic1UnifiedAudioPresentationRomIntegration,\
TestSonic2UnifiedAudioPresentationRomIntegration,\
TestSonic3kUnifiedAudioPresentationRomIntegration,\
TestAudioPresentationAllocationBudget,TestSmpsSfxAdmissionAllocation test
# 114 tests, 0 failures/errors/skips
```

All three ROM SHA-1s matched the project pins. As mutation evidence, changing
the three `SmpsDriver` constructors back to fast mode made
`collapseFinalPcmRetainsTheReferencePostMuteRingDown` fail on the first aligned
native frame (`122.835` expected, `127.764` observed); restoring HQ returned the
focused suite to green.

## Commands run

All Maven commands used Maven 3.9.16 on JDK 21.0.11.

```bash
mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
  -Dtest=TestS3kCollapseDashSfxParity,TestS3kBlueSphereSfxParity,\
TestSonic3kCoordFlagParity,TestPreparedSfxAdmission,\
TestSmpsSequencerSnapshot,TestPsgChipSnapshot,TestVirtualSynthesizerSnapshot test
# 97 tests, 0 failures/errors/skips

mvn -Dmse=off -Dsonic1.rom.path="$S1_ROM" \
  -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" \
  -Dtest=TestSonic1UnifiedAudioPresentationRomIntegration,\
TestSonic2UnifiedAudioPresentationRomIntegration,\
TestSonic3kUnifiedAudioPresentationRomIntegration,TestS1SfxTakeoverOrder,\
TestSfxContentionObserver,TestSmpsGlobalSfxPriority,\
TestSmpsSfxConstructionPurity,TestSmpsSfxAdmissionAllocation test
# 28 tests, 0 failures/errors/skips

tools/bizhawk-headless/test.sh --filter GpgxHost --jobs 1
# bounded Z80 write test PASS

OPENGGF_GPGX_Z80_CAPABILITY=1 \
OPENGGF_GPGX_S3K_SFX_LIFECYCLE=1 \
tools/bizhawk-headless/test.sh \
  --filter 'capture injected S3K SFX lifecycles' --jobs 1
# PASS
```

The identical full JDK 21/all-three-ROM suite was run against exact parent
`e0c94ea181a03a87cc42d4317cd6eb4452106ef3` and implementation commit
`9a5e242a1`. The parent ran 15,500 tests with 56 failures, 56 errors, and 19
skips; the implementation ran 15,506 tests with 53 failures, 56 errors, and 19
skips. Its six additional tests were the initial Collapse/Dash parity cases;
the per-track terminal correction adds three more.

The sorted failure/error identity comparison contained no candidate-only red
test. The parent ledger has 112 identities (SHA-256
`86177f94f9f56c23a1d2da447b21de0c67800c6a4fd0e5825446f7a3eef8aa2b`);
the candidate ledger has 109 (SHA-256
`f1284d5aa7ed356866a508271609c6e36c8e64176efe489af4596038b55d81a0`).
The complete Maven log hashes are
`9289b3fe08e141021a8f3bff8839e8db36d35ada22dfc1577ad7037136192706`
for the parent and
`20841cbbc91aa65f9b01a090e8190e3860f140d664a80b9c54944b586f4ca2d1`
for the candidate. The three baseline-only failures are the AIZ initial-player
state, test-mode trace-picker loading screen, and Corkey registry cases; no
baseline-passing test regressed.

The broad capability-class selector also exposes an existing combined service
manifest mismatch (`90cf...` expected, `0b96...` generated) on the integration
base. The isolated lifecycle selector is green and this change does not modify
the service manifest or native observer patch.

The focused terminal/timeline regression after this correction used:

```bash
mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
  -Dtest=TestS3kCollapseDashSfxParity,TestS3kBlueSphereSfxParity,\
TestSmpsDriverYmWriteTimeline,TestSmpsFmVoiceWriteProfiles test
# 77 tests, 0 failures/errors/skips
```

The combined S3K snapshot/admission and cross-game control selector ran
125 tests with zero failures, errors, or skips.

After the per-track terminal correction and structural call-site guard, the
fresh all-three-ROM suite ran 15,510 tests with 53 failures, 56 errors, and 19
skips. Its 109-entry sorted failure/error ledger is byte-identical to the
implementation ledger above (SHA-256
`f1284d5aa7ed356866a508271609c6e36c8e64176efe489af4596038b55d81a0`),
so the four additional terminal/guard tests introduced no new red identity.
The full log SHA-256 is
`f1c637ff6258dd097528d08bfc3f565631ef3297d3e5faa3200b1bb96f33201c`.

## Remaining gate

### 2026-08-24 final-PCM / HQ PSG correction

The later listening report that Collapse still ended abruptly was not a second
track-lifetime defect.  A native final-speaker capture proved that the raw S3K
PSG stream ends after frame 121 in both implementations, while GPGX's
band-limited PSG output decays across the following output frames.  GPGX selects
its HQ PSG delta path (`config.hq_psg = 1`); OpenGGF had a compatible path but
left it disabled for every SMPS driver.  The correction enables that existing
reference rendering mode at the shared SMPS-driver boundary for S1, S2, and
S3K.  It does not extend Sound_59, synthesize reverb, or branch on a game or
sound identifier.

Two independent native captures produced byte-identical 125-frame final-PCM
evidence (SHA-256
`5c6bfe3382749fa31137128a3bfd87d191da17d6ad33ece8c39d360e428ce7a3`).
Native frame RMS at 121-124 was `88.917, 21.669, 5.142, 1.273`; the retained
comparison fixture checks the active waveform and post-mute decay ratios rather
than inventing a longer semantic lifetime.  With HQ mode deliberately disabled,
the final-PCM parity test fails on the active waveform; restoring it returns the
focused and broad selectors to green.

Fresh JDK 21 verification at `a0d3352a7`:

```text
TestS3kCollapseDashSfxParity,TestPsgChipGpgxParity: 27/27 green
cross-game chip/driver/presentation snapshot and allocation selector: 114/114 green
TestMadmoleBadnikInstance isolated order-flake check: 32/32 green
```

An exact detached parent baseline at `0612aab314dafcba1cdc50e6bff7a43978644b5e`
ran the identical all-three-ROM suite: 15,550 tests, 56 failures, 56 errors,
19 skips.  Its 112-entry red ledger SHA-256 is
`731a77a49c8a5362c2fc556ee4e6713e617a97c5ddd460ae5bde6a6b7006e3b3`;
the complete log SHA-256 is
`0c18d731c7f3a18016ce18b69f0be4f0040aeacb0d3a0f9c32a8ba3ce83b048b`.

The clean candidate suite encountered one native-loader JVM startup crash, so
Surefire omitted the complete 13-test `TestS3kCnzLocalTraversalHeadless` class.
The remaining run reported 15,541 tests, 56 failures, 56 errors, and 19 skips;
the missing class then passed 13/13 in isolation.  The completed candidate red
ledger also has 112 identities (SHA-256
`3a6fc61e04a14cceb8cde0529b88e855171cc37813a1371e623088f8c6d0f745`).
Its three candidate-only Madmole identities all pass in the isolated 32-test
class and are suite-order contamination; the candidate resolves three other
baseline reds.  No red identity is attributable to the PSG correction.

Listen to Collapse through all five PSG bursts and terminal silence, plus
low/high-charge Dash release and a replay after another SFX. The exact package
identity is reported in the handoff after building clean HEAD so the report
does not create a self-referential rebuild. Do not merge or push before the
listening result.

### 2026-08-24 real-game repeated-request correction

The HQ result above was correct for one isolated Sound_59 instance, but the
subsequent listening result proved that it did not explain the audible AIZ
texture. A fresh BK2-driven native capture kept music and gameplay active. It
showed Collapse requests at relative frames 228, 289, and 351: each new request
arrives 61–62 frames after the previous one, well before Sound_59's 121-frame
PSG track terminal. The final request then runs through relative frame 472.
The capture SHA-256 is
`75e58682964c5b9263a849af32cc6e4676509e2df44ff4c9b31a0adf5d34b155`.

That overlap exposed a separate driver defect. OpenGGF's same-ID removal path
called `releaseLocks` before admitting the replacement. On PSG3 it published
`DF C0 00 E7`: silence the old SFX, restore the interrupted music tone, and
then reclaim the channel. The shipped S3K `zPlaySound` overwrites the one
shared SFX RAM track while the music override remains set, publishing `DF FF`
for its `fix_sndbugs=0` PSG3/noise silence. A strict regression first failed on
the transient restore, then passed after the old PSG lock was handed directly
to the replacement. The same test reproduces the native 61/62-frame request
spacing and proves that the final replacement remains alive for its complete
121-frame decay.

Fresh JDK 21 focused verification:

```text
TestS3kCollapseDashSfxParity, TestPreparedSfxAdmission,
TestSfxContentionObserver: 51/51 green
S1/S2/S3K unified presentation, Blue Sphere, Collapse/Dash,
prepared admission and contention controls: 75/75 green
expanded architecture, timeline, rewind/snapshot, admission, and all-game
presentation selector: 179/179 green
```

### 2026-08-24 single-request FM track-stop correction

The later report that a single Collapse request still lost its final decay was
reproduced without the earlier three-block route. The headless regression boots
AIZ1 with Tails as the main character and the intro skipped, places Tails at
debug-HUD top-left position `6517,933` in an air roll, and lets him fall without
input. The ROM-backed `AIZLRZEMZRock` at `$1980,$0424` breaks at headless frame
27; the spring below launches Tails later at frame 63.

This isolated the remaining fault to FM track termination, not PSG noise. A
native locked-on trace showed that `cfStopTrack` enters at Z80 PC `$0D87`, then
executes 181 Z80 T-states before its YM2612 key-off data write: 2715 master
cycles at the established 15:1 ratio. OpenGGF had performed the key-off at the
driver-service boundary, prematurely advancing the YM envelope release. The
source calculation is now retained in
`s3k-ym-write-timing-calculation-v1.json`; it covers the exact shipped
`fix_sndbugs=0` path and both YM ports.

The implementation adds one generic locked-on `TRACK_STOP_KEY_OFF` timing
segment. It is selected by the S3K timing profile for eligible FM track stops;
there is no sound ID, AIZ, object, position, or route predicate. FM5 music
restoration remains a distinct, subsequently ordered `COMPLETION_RESTORE`
segment, so the source key-off is neither duplicated nor folded into the voice
upload. S1 and S2 retain their existing immediate/none-profile behaviour.

The real headless test observes Collapse's three terminal key-offs on frames
45, 46, and 47 (`break + 18/+19/+20`), verifies non-zero presented PCM through
four subsequent packets, and independently proves that the later spring still
launches Tails. Focused JDK 21 verification with the authenticated S3K ROM ran
33 tests with zero failures, errors, or skips. The expanded all-three-ROM
timing, rewind, architecture, presentation, and real-headless selector ran 150
tests with zero failures, errors, or skips.
