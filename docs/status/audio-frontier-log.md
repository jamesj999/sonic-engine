# Audio frontier log

The audio counterpart of [trace-frontier-log.md](trace-frontier-log.md): the
running record of driver-oracle comparisons between the engine's SMPS driver
and reference captures recorded from the real driver running in the pinned
emulator (BizHawk 2.11 / Genesis Plus GX through TraceChaser).

Each entry records, newest first:

- **Date / commit / worktree** the comparison ran at.
- **Fixture** — the committed reference capture (and its BK2 movie) compared
  against, by file name under `src/test/resources/audio/parity/`.
- **Command** — the exact re-runnable invocation.
- **Result** — `MATCH` or the comparator's first divergence: tick ordinal,
  role/field (or event index), reference vs engine value. The comparator is
  validation-first and no-realignment, so one entry has exactly one first
  divergence; there is no error count beyond it. A capture failure is recorded
  as such, never as a parity result.
- **Notes** — what moved, or what the divergence is suspected to be. Fixing
  driver behaviour belongs to implementation lanes; this log only measures.

Comparisons at this tier are per driver invocation: driver-RAM-shaped track
state plus the ordered YM/PSG write stream of that invocation ("ticks"), as
defined by `com.openggf.tools.audio.parity`.

---

<!-- entries are prepended below, newest first -->

## 2026-09-07 - S1/S2: SFX are refused through the 1-up jingle and its fade in

- **Worktree/branch:** `.worktrees/s1s2-1up-restore`,
  `bugfix/ai-s1s2-1up-restore`, following the FM voice resend fix
  (`212a71ec1`). Closes the gap that entry left open.
- **What the ROM does.** `Sound_PlaySFX` / `Sound_PlaySpecial` and
  `zPlaySound_CheckRing` refuse every SFX while the 1-up flag or the fade-in
  flag is set (s1.sounddriver.asm:978-982, :1118-1122;
  s2.sounddriver.asm:2118-2120). The extra-life load raises the first
  (s1:784, s2:1712); `cfFadeInToPrevious` clears it and raises the second
  (s1:2220-2222, s2:3150-3155); the fade-in stepper clears that one when the
  counter reaches zero (s1:1650, s2:2740). An ordinary song load clears both
  (`.bgmnot1up` s1:790, s2:1730; the RAM clear in `InitMusicPlayback` /
  `zInitMusicPlayback`, s1:1498-1510, s2:2597-2623). S1's extra-life branch
  also clears the playing bit on all six SFX tracks before the backup
  (s1:769-774); S2 stops them through `zStopSoundEffects` on every song
  (s2:1667-1672), which the request path already issued.
- **What the engine did.** Only `Sonic3kStatefulCommandPolicy` suppressed
  SFX during an override, and the registry lifted the block at the restore.
  S2 had no stateful policy at all. Measured headlessly through
  `presentFrame` before the fix: a jump requested five frames into the jingle
  was admitted in both games, and again ten frames into the fade in; in S1 the
  jump playing before the 1-up carried on under the jingle.
- **The fix.** `SmpsStatefulCommandPolicy` gains
  `releasesSfxSuppressionAtRestore` (S3K true, S1/S2 false) and
  `stopsSfxWhenOverrideStarts` (S1 true). New `Sonic2StatefulCommandPolicy`
  (`sonic2-commands-v1`). The registry keeps a second bit,
  `sfxBlockHeldThroughFadeIn`, in the presentation snapshot: set at the
  restore when the policy holds, cleared when the restored song's fade in is
  no longer running. The clear is evaluated at each frame boundary and at
  every SFX admission, because the ROM clears its flag at the top of the
  service after the last step, ahead of that service's request cycle, and the
  engine's sequencer fade completion lands after the registry's end-of-frame
  hook.
- **Tests.** Three cases added to `OneUpRestoreLivePathSupport`, run under
  both ROMs: the pre-jingle SFX is stopped and requests during the jingle and
  the fade in are refused, then admitted once the fade completes; a snapshot
  round trip mid fade still releases at the end; an ordinary song during the
  jingle releases at once. Ablating `suppressesSfxDuringOverride` in both
  policies fails all three per game on the first block assertion.
  `TestS3kOneUpRestoreRom` (release at restore) unchanged and green.
- **Not modelled, recorded in known-discrepancies.md.** The refused request's
  priority-latch clear (`zKillSFXPrio`, `.clear_sndprio`); the fade-in hold
  over a jingle that had no song to save; S1 `StopAllSound` preserving
  `f_1up_playing` across its RAM clear.


## 2026-09-07 - S1/S2: the live 1-up restore never re-sent the FM voices

- **Worktree/branch:** `.worktrees/s1s2-1up-restore`,
  `bugfix/ai-s1s2-1up-restore`, from `develop` at `77c244548`. Reported in
  game on both Sonic 1 and Sonic 2: the music does not come back properly
  after an extra life.
- **What was actually wrong.** `cfFadeInToPrevious` restores the backed-up
  driver RAM and then, per playing FM track that no SFX owns, re-sends the
  whole voice with the attenuated volume in its TL bytes: `SetVoice`
  (s1.sounddriver.asm:2196-2200) and `zSetVoiceMusic`
  (s2.sounddriver.asm:3111-3118). The chip is still holding the jingle's
  instrument on every FM channel at that point. The engine's `REST_TRACKS`
  branch of `SmpsSequencer.triggerFadeIn` rested and attenuated the tracks
  but wrote only TL; the voice reload it relied on was
  `refreshAllVoices` in `AbstractSmpsAudioBackend.doRestoreMusic`, which the
  live path does not reach (`AudioManager.sendLiveBackendCommands` is false).
  The S3K branch already resent its voices from the 2026-09-04 fix, which is
  why S3K was not reported.
- **Measured before the fix, headlessly through `presentFrame`.** The level
  music came back with its positions and tempo intact, rested, attenuated by
  28h, and faded in over 120 frames, so the restore itself was sound. The
  chip writes on the restore frame were TL and PSG note-off only: no 30h-3Fh,
  50h-9Fh or B0h-B2h register for any channel. The song resumed on the
  jingle's algorithms, multipliers and envelopes with the level music's TLs
  laid over them, until each track's own next voice change.
- **The fix.** The `REST_TRACKS` branch now follows the routine's own order:
  playing tracks only, rest, add the fade depth, then `refreshInstrument` on
  FM tracks that are not overridden and a note-off on PSG tracks, with no
  separate TL write. The depth is `28h` less the fade-in counter the restored
  copy carries (s1:2186, s2:3099), so a second extra life during a running
  restore fade no longer stacks a full attenuation on the first.
- **Tests.** New `TestS1OneUpRestoreRom` and `TestS2OneUpRestoreRom` drive
  the live path and assert the song resumes rather than restarts, that the
  restore frame carries a B0h+ch write for every playing FM track and a
  note-off for every PSG track, and that the fade runs back to the song's
  own volume. Ablating the resend (TL write in its place) fails both on
  "the restore must re-send FM0's voice". `TestS3kOneUpRestoreRom` and
  `TestSmpsFadeInRestoreDriverModes` stay green.
- **Oracle unchanged.** `TestS2OneUpRestoreDriverStateOracle` still reports
  the window measurement-only at the same points as when it was committed:
  state at tick 2880 (`PSG1.volFlutter`), writes at tick 0. The 1-up in that
  window is at 2875, so neither frontier reaches the restore; it cannot see
  this fix either way, and the harness behind it runs the legacy backend.
- **Still open, deliberately not done here.** On the live path S1 and S2
  admit new SFX during the jingle and during the fade in. The ROM refuses
  them and clears the priority latch while `f_1up_playing` or
  `f_fadein_flag` is set (`Sound_PlaySFX`, s1.sounddriver.asm:978-982;
  `zPlaySound_CheckRing`, s2.sounddriver.asm:2118-2120). Only the S3K
  session policy suppresses SFX during an override, and nothing on the live
  path releases a block at fade completion. That is a separate gap from the
  reported one and needs its own release hook.


## 2026-09-05 - S3K diagnostic ring dispatch advances service 2357 to 2409

- **Before:** service 2357, `MUS_FM4.overridden`, reference `false`, engine
  `true`.
- **After:** service 2409, `MUS_PSG1.overridden`, reference `true`, engine
  `false`.
- **ROM owner:** raw request `33h` reaches `zPlaySound_CheckRing`, toggles the
  boot-zeroed `zRingSpeaker`, and selects Sound34/Sound33 in turn
  (`Sound/Z80 Sound Driver.asm:547,1919-1928`, retail `fix_sndbugs=0`). The
  diagnostic capture now performs that transform inside its pending driver
  callback, so submission itself cannot advance the side.
- **Scope:** this is capture-adapter accuracy, not proof of live request
  parity. The live S3K presentation still lacks the three-slot mailbox cycle;
  retail's 1-up/fade mailbox clearing remains unverified here.
- **Evidence:** candidate `0bbd98884`; focused JDK 21 invocation used all
  three absolute ROM properties and
  `-Dtest=TestS3kCaptureRingDispatch,TestS3kOracleRequestSidecarWiring#theFullOraclePinsTheNextSfxWriteFrontier+firstRawRingSelectsFm5WithExactReferenceWrites`
  (4 passed, no failures/errors/skips). The ring-write assertion compares the
  exact non-DAC service stream under the existing DAC timing exclusion; it is
  not a claim that PCM window partitioning matches.

## 2026-09-05 - S3K SFX header order advances service 2012 to 2357

- **Before:** service 2012 event 1, reference PSG `BF`, engine PSG `FF`.
- **After:** service 2357, `MUS_FM4.overridden`, reference `false`, engine
  `true`.
- **ROM owner:** `zSFXTrackInitLoop` leaves IX naming the preceding newly
  initialized header when the next `zGetSFXChannelPointers` call silences it
  (`Sound/Z80 Sound Driver.asm:1997-2103, 2109-2165`). Skid's shipped PSG2 then
  PSG1 headers emit `FF BF FF`. Runtime service remains in fixed channel-RAM
  order; only admission uses immutable ROM header order.
- **Evidence:** the hard oracle matches through service 2012. The next state
  mismatch is independent; full-game audio parity remains open.

## 2026-09-05 - S3K covered-noise restore advances service 1690 to 2012

- **Before:** service 1690 event 7, reference PSG `E7`, engine PSG `C0`.
- **After:** service 2012 event 1, reference PSG `BF`, engine PSG `FF`.
- **ROM owner:** `zStopPSGTrack` preserves the covered music track's state and
  writes its exact stored `PSGNoise` byte only for noise mode and a negative
  raw byte (driver:3521-3533). Tone and positive-byte cases write nothing.
- **Ownership:** the ended noise-form PSG3 SFX releases only its own tone/noise
  locks and channel-2 admission claim before the one music callback.

## 2026-09-05 - S3K PSG stop helper advances service 1690 event 6 to event 7

- **Before:** service 1690 event 6, reference PSG `FF`, engine PSG `C0`.
- **After:** service 1690 event 7, reference PSG `E7`, engine PSG `C0`; the
  first 1,690 services and first seven writes of that service match.
- **ROM owner:** retail `fix_sndbugs=0` `zGetSFXChannelPointers` calls
  `zSilencePSGChannel` and then emits an unconditional compensating `FF`
  before `cfStopTrack` restores music ownership (driver:2115-2142,
  3443-3469, 4226-4249). OpenGGF previously emitted only the channel/noise
  silence pair.
- **Evidence:** tone/noise stop tests pin `mute+FF` and `mute+FF+FF`; FM and
  earlier-game handlers remain untouched. The next `E7` restore gap is
  separate. The independent DAC frontier is unchanged.

## 2026-09-05 - Fixed SFX RAM walk: service 1652 to service 1690

- **Before:** service 1652 event 0, reference Flying FM4 voice release
  (`80=FF`), engine older Collapse PSG `C8`.
- **After:** service 1690 event 6, reference PSG `FF`, engine PSG `C0`; the
  first 1,690 services match.
- **ROM owner:** `zUpdateSFXTracks` linearly walks `zTracksSFXStart` through
  FM3..FM6 then PSG1..PSG3 (`Sound/Z80 Sound Driver.asm:727-759`). OpenGGF now
  uses its existing cross-program fixed-channel walker for S3K as for S1/S2.
- **Evidence:** the ROM-backed concurrent test admits Flying after Collapse
  and pins the complete service-1652 transaction, proving FM4 precedes the
  older PSG slots without synthetic occupancy. The independent DAC frontier
  is unchanged.

## 2026-09-05 - PSG-volume envelope rewind: service 1594 to service 1652

- **Before:** service 1594 SFX PSG3 `volEnv`, reference `FF`, engine `00`.
- **After:** `EVENT_VALUE_DIFFERENT` at service 1652 event 0, reference YM2612
  port I `80=FF`, engine PSG `C8`; the first 1,652 services match.
- **ROM owner:** S3K `cfChangePSGVolume` clears rest and performs a byte-sized
  `DEC` on `VolEnv`, so zero wraps to `FF` before its wrapped volume add and
  unsigned `0Fh` clamp (`Sound/Z80 Sound Driver.asm:3263-3285`). The engine's
  prior `envPos > 0` guard incorrectly suppressed only that zero wrap.
- **Evidence:** red-first unit coverage pins `00 -> FF`, signed underflow
  clamping, the PSG-only gate, and absence of an immediate PSG write. The
  committed ROM oracle pins the new event-order frontier and its 1,652-service
  matching prefix. A later envelope walk from unsigned offset `FF` is not yet
  claimed because Java bounds cursors to the loaded envelope array. The
  independent DAC frontier is unchanged.

## 2026-09-05 - Overridden PSG attack envelope: service 1592 to service 1594

- **Before:** service 1592 music PSG3 `volEnv`, reference `0`, engine `1`.
- **After:** `TRACK_STATE_MISMATCH` at service 1594, SFX PSG3 `volEnv`,
  reference `FF`, engine `00`; the first 1,594 services match.
- **ROM owner:** `zFinishTrackUpdate` resets `VolEnv` on an attacked note, but
  `zUpdatePSGTrack` returns on the music track's override bit before calling
  `zDoVolEnv` (`Sound/Z80 Sound Driver.asm:1055-1069, :4058-4115`). The first
  envelope byte is consumed only on the first pass after release.
- **Evidence:** a focused red test observed cursor `1` under override; it now
  pins reset cursor `0`, no volume latch before release, and one first-step
  volume latch after release. Tied notes still preserve then step their cursor,
  attacked rests reset without stepping, and S1/S2 keep their existing eager
  attacked-note step. The independent DAC frontier is unchanged.

## 2026-09-05 - FM3 release mode: service 1588 event 1 to service 1592 PSG3 state

- **Before:** service 1588 event 1, reference YM2612 port 0 `27=0F`, engine
  port 0 `B6=C0`.
- **After:** `TRACK_STATE_MISMATCH` at service 1592, music PSG3 `volEnv`,
  reference `0`, engine `1`; the first 1,592 services match.
- **ROM owner:** shipped `fix_sndbugs=0` `cfStopTrack` clears the music override
  and writes FM3 mode `0F` or `4F` before uploading the restored music voice
  (`Sound/Z80 Sound Driver.asm:3443-3518`). The shipped branch also stores the
  setting in driver RAM; the fixed branch omits that store, but both write
  register `27` physically.
- **Evidence:** focused normal/special-mode tests failed red with `B6` as the
  first restore write and now pin `27` before `B6`; non-FM3 and S1/S2 release
  modes remain excluded. The ROM-backed oracle pins the new PSG-envelope
  frontier. The independent DAC frontier is unchanged.

## 2026-09-05 - PSG frequency transaction: service 1570 event 48 to service 1588 event 1

- **Before:** `EVENT_MISSING` at service 1570 event 48, reference PSG `FF`.
- **After:** `EVENT_VALUE_DIFFERENT` at service 1588 event 1, reference YM2612
  port 0 `27=0F`, engine port 0 `B6=C0`; the first 1,588 services match.
- **ROM and hardware owner:** `zUpdatePSGTrack` gates the source track before
  writing both frequency bytes (`Sound/Z80 Sound Driver.asm:4085-4095`). The
  second byte remains physically literal: `FF` is decoded by the SN76489 as a
  PSG3-volume latch and silences noise. Only the driver's second ownership
  decision was extraneous.
- **Evidence:** the driver-level transaction tests pin exact `AF FF` observer
  order, final physical latch 7/noise attenuation 15, atomic rejection when
  the source tone channel is owned, and continued rejection of an unrelated
  single `FF` latch. Contention diagnostics now report one decision per ROM
  frequency transaction instead of one per physical byte; source-byte and
  physical-write observers still receive both bytes in order. The ROM-backed
  oracle pins the new frontier and preserves the full prior prefix. The
  independent DAC frontier remains unchanged.

## 2026-09-05 - S3K first-note volume tail: service 1570 event 43 to event 48

- **Context:** `bugfix/ai-sol-s3k-psg-parity`,
  `.worktrees/sol-s3k-psg-parity`, base `develop` `be5a9e596`; JDK 21.0.11,
  isolated build output and the absolute verified locked-on ROM path.
- **Fixture:** `s3k/s3k-aiz1-intro-reference-v2.jsonl.gz` with
  `s3k-aiz1-intro-requests-v1.json`.
- **Before:** service 1570 event 43, reference YM2612 port 0 `A4=22`, engine
  PSG `F0`.
- **After:** `EVENT_MISSING` at service 1570 event 48, reference PSG `FF`,
  engine stream exhausted. The first 1,570 services and first 48 ordered writes
  of service 1570 match. The independent DAC frontier is unchanged at run 338,
  byte 0, reference `88` versus engine `7F`.
- **ROM owner:** `zUpdatePSGTrack` has one shared volume tail after the new-note
  and continuing-note frequency paths (`Sound/Z80 Sound Driver.asm:4059-4135`).
  Collapse's note-start modulation already reached that tail; the engine then
  added a second generic attacked-note volume write.
- **Evidence:** `collapseFirstNoteWritesItsNoiseVolumeOnce` failed red with two
  writes in the first sounding service and passes with one after the fix. The
  exact comparison command remains the `S3kAudioParityTool compare` command in
  the preceding entry, run from this worktree with the same fixture, request
  sidecar and absolute ROM path.
- **Event-48 diagnosis:** reference `AF FF` is one PSG tone-frequency pair, not
  a final noise silence. The sequencer submits both bytes; `SmpsDriver.writePsg`
  reclassifies the high-bit `FF` as a PSG3 latch and drops it because Collapse
  owns noise. The ROM performs its override check before the pair and has no
  per-byte ownership arbitration, so the next repair belongs at the driver PSG
  transaction boundary rather than in `SmpsSequencer`. This diagnosis does not
  advance the pinned frontier.

## 2026-09-05 - PSG command takeover: service 1570 event 39 to event 43

- **Context:** `bugfix/ai-audio-next-psg`, `.worktrees/audio-next-psg`,
  base `develop` `bbf28b7dc`; implementation is the commit containing this
  entry. JDK 21.0.11, isolated build output, absolute verified ROM paths.
- **Fixture:** `s3k/s3k-aiz1-intro-reference-v2.jsonl.gz` with
  `s3k-aiz1-intro-requests-v1.json`.
- **Command:** after `mvn -Dmse=off -q dependency:build-classpath
  -Dmdep.outputFile=target/gates/cp.txt`, with `S3K_ROM` denoting the discovered
  absolute locked-on ROM path (SHA-1
  `cfbf98c36c776677290a872547ac47c53d2761d6`):

  ```bash
  java -cp "target/classes:$(cat target/gates/cp.txt)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
    --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json \
    --rom "$S3K_ROM"
  ```

- **Before:** `EVENT_VALUE_DIFFERENT`, service 1570, event 39,
  reference PSG `E7`, engine PSG `FF`.
- **After:** same service, event 43, reference YM2612 port 0 `A4=22`,
  engine PSG `F0`; full comparison exits 3. Independent DAC remains
  `BYTE_DIFFERENT`, run 338, byte 0, reference `88`, engine `7F`.
- **ROM owner:** retail `fix_sndbugs=0` `cfSetPSGNoise`
  (`Sound/Z80 Sound Driver.asm:3559-3572`) emits adjacent `DF` and its
  operand. The S3K provider now selects existing `REGISTER_SEQUENCE`, so
  noise ownership acquisition no longer inserts another silence after the
  loader's guaranteed `FF`. Locks, admission, release and rollback are unchanged.
- **Evidence:** all three ROM-backed effect adjacency checks and the
  zero-operand check were red before the setting. The 1571-service `MATCH`
  attempt exposed the next volume write, so the approved final gates retain
  1570 whole matching services and add exact equality for all 43 ordered
  service writes preceding the new frontier. That ordered-prefix gate was
  also demonstrated red with the setting removed. Final focused suite:
  74 tests, zero failures/errors/skips, including cross-game policies,
  configuration copy, admission rollback, release and noise lifetime.
- **Limits:** no additional whole matching service, listening-parity or
  all-green claim. Stale-IX admission writes remain incomplete. The second
  fresh-note `F0` is the next source-backed investigation candidate; DAC
  remains separate. Exact red/green commands, observer corrections and
  attribution are in [the validation report](../architecture/validation/audio/2026-09-05-s3k-psg-takeover.md).

## 2026-09-04 - PSG SFX admission noise silence: 1569 to 1570

- **Context:** `bugfix/ai-audio-round-s3k`, `.worktrees/audio-round-s3k`,
  based on `develop` `4296bc291`; implementation is the commit containing
  this entry. JDK 21.0.11, fresh isolated build, absolute verified ROM path.
- **Fixture:** `s3k/s3k-aiz1-intro-reference-v2.jsonl.gz` with
  `s3k-aiz1-intro-requests-v1.json`.
- **Command:** after compilation and `mvn -Dmse=off -q
  dependency:build-classpath -Dmdep.outputFile=target/gates/cp.txt`.
  Export `S3K_ROM` to the discovered absolute locked-on ROM path (SHA-1
  `cfbf98c36c776677290a872547ac47c53d2761d6`); the placeholder replaces the
  executed machine-local path:

  ```bash
  java -cp "target/classes:$(cat target/gates/cp.txt)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
    --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json \
    --rom "$S3K_ROM"
  ```

- **Before:** service 1569, `EVENT_VALUE_DIFFERENT`, event 15: reference
  PSG `FF`, engine YM2612 port 0 register `A4` value `22`.
- **After:** service 1570, `EVENT_VALUE_DIFFERENT`, event 39: reference
  PSG `E7`, engine PSG `FF`. Full comparison exits 3; no all-green claim.
  The DAC stream stays at `BYTE_DIFFERENT`, run 338, byte 0, reference `88`
  versus engine `7F`.
- **ROM owner:** retail `fix_sndbugs=0`,
  `zGetSFXChannelPointers.is_psg` (`Sound/Z80 Sound Driver.asm:2131-2136`),
  unconditionally emits `FF` for every PSG header. Collapse loads FM3,
  FM4, FM5, PSG3, so the write follows 15 FM initialization writes. A
  profile-owned setting now emits that guaranteed write in header order.
  The preceding stale-IX silence call is not fully modelled; the next
  service's lazy noise takeover remains open.
- **Evidence:** new prefix test failed at the exact missing write before
  implementation and now asserts `MATCH` for all 1570 services through
  1569. Final focused suite: 54 tests, zero failures/errors/skips, including
  admission order, PSG1/PSG2/default profiles, configuration copy, runtime
  noise tails and admission rollback. Ordinary/guards remain the integration
  lead's checks. Exact red/green commands and test-observer corrections are
  in [the validation report](../architecture/validation/audio/2026-09-04-s3k-admission-silence.md).

## 2026-09-04 - HANDOVER: S3K AIZ1 intro oracle, state at branch head

- **Historical branch:** `bugfix/ai-s3k-oracle-freq-resend`; the measured code
  head was `3c552b92a`, followed by handover documentation at `ebc90caa3`.
  This work entered develop through `a306c0351`, not the earlier merge
  `37c3f0c0b` (which predates `3c552b92a`). The originating worktree was
  `.worktrees/audio-s3k-freq`, reported clean at handover. Counts and frontiers
  below describe that historical measurement, not the current branch.
- **Resume command.** From the worktree, after `mvn -q -Dmse=off clean package
  -DskipTests` and `mvn -q -Dmse=off dependency:build-classpath
  -Dmdep.outputFile=target/gates/cp.txt`:

  ```
  java -cp "target/classes:$(cat target/gates/cp.txt)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
    --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json \
    --rom "$PWD/s3k.gen"
  ```

  Build clean before every measurement: a stale `target/` reports the previous
  engine. Run only one build or suite in this worktree at a time; two
  overlapping runs share its `target/` and its Maven temp directory and produce
  failures that look like regressions and are not.
- **Current first divergence.** Service stream `EVENT_VALUE_DIFFERENT` at tick
  1569: the reference emits `psg 0FFh` where the engine emits
  `ym2612 port 0 register 164 value 34`. That service is where an SFX takes the
  third PSG channel, so the takeover's own writes are the place to start. DAC
  byte stream `BYTE_DIFFERENT` at run 338, byte 0, reference `88h` against
  engine `7Fh`, unchanged for many cycles.
- **Frontier history this session.** 751, 566, 760, 1490, 1569. The two
  backwards moves were honest: each uncovered a defect the previous ordering
  had hidden, and both are described in their own entries.
- **Open items, carried forward.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG silence shape;
  S1's and S2's track-end stop. Added this session: the driver-level backup of
  tempo, tempo speedup, voice table pointer and song bank that `zPlayMusic`'s
  extra-life branch saves (Sound/Z80 Sound Driver.asm:1739-1781), which has no
  engine equivalent because the presentation override stack preserves the
  sequencer instead.
- **Comparator coverage.** Six fields were promoted from diagnostic to gated
  this session: `volEnv`, `noteFillTimeout`, `noteFillMaster`,
  `palDoubleUpdateCounter`, `fadeDelay` and `fadeDelayTimeout`. All were free.
  `volEnv` has since caught two separate defects. The fields still diagnostic
  are so for cause, each named in `S3kAudioFieldRegistry`: they name a ROM byte
  the engine has no equivalent for, a Z80 address, presentation-side state, or
  a fade shape that genuinely differs.
- **A habit worth keeping.** Every new or promoted comparison this session was
  proven to execute by corrupting the engine's value for it and checking the
  oracle names that field. Twice on this branch a comparison turned out not to
  be running; a green gate and an absent gate look identical from outside.
- **Gates at this head, all green, on a clean build and a quiet machine.**
  Audio, per-game audio and parity packages with all three ROM paths: 2,761
  tests, 0 failures, 16 skips. Ordinary suite 16,477 tests, 0 failures, 22
  skips. `-Pguards` 609 tests, 0 failures.


## 2026-09-04 - An overridden PSG track kept running its envelope; 1569 state -> 1569 write

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, merged with develop at `37c3f0c0b`,
  which did not move the frontier.
- **What 1569 was.** `TRACK_STATE_MISMATCH` on `MUS_PSG3`'s `volEnv`, reference
  79 against engine 80. At that service an SFX takes the third PSG channel: the
  reference's track goes `ovr=true` and its envelope position stops dead at 79
  and stays there, while the engine kept advancing it.
- **The ROM.** `zUpdatePSGTrack` tests the SFX-overriding bit immediately after
  `zDoModulation` and returns on it, before both the frequency latch and
  `zDoVolEnv` (Sound/Z80 Sound Driver.asm:4079-4083). An overridden PSG track
  therefore advances nothing at all. The engine ran the envelope underneath the
  SFX, so when the channel came back the envelope was somewhere else in its
  data.
- **The gated field earned its place again.** `volEnv` was promoted from
  diagnostic three commits ago and has now caught two separate defects, this
  one within a single service of the takeover that caused it. Before the
  promotion this would have surfaced, if at all, as some unrelated write
  hundreds of services later.
- **Frontier stays at 1569 and changes kind,** from a state mismatch to a write
  one: the reference emits `psg 0FFh` where the engine emits
  `ym2612 port 0 register 164 value 34`. That is the next thing to chase. The
  DAC byte stream is unchanged at run 338, byte 0.
- **Gates at this commit, all green, on a clean build and a quiet machine.**
  Audio, per-game audio and parity packages with all three ROM paths: 2,761
  tests, 0 failures, 16 skips. Ordinary suite 16,477 tests, 0 failures, 22
  skips. `-Pguards` 609 tests, 0 failures.


## 2026-09-04 - Two regressions in my own fade work, both found by audit not by me

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`. Both reported against develop
  `5dd1b8122`, both reproducible through the live presentation path, and
  neither visible to the intro oracle, which reaches no extra life.
- **First: S3K lost its PSG channels after the 1-up fade in.** The restore
  marks the PSG tracks overridden, which is right and is what mutes them
  through the fade. `zDoMusicFadeIn` clears that bit again on every PSG track
  and on FM6/DAC once its timeout reaches zero (Sound/Z80 Sound
  Driver.asm:2440-2452); the engine's completion branch released only the DAC,
  so the PSG tracks stayed muted for the rest of the song. The engine was also
  attenuating them on the way: neither S3K stepper walks the PSG tracks at all,
  since `zDoMusicFadeOut` loops the tracks before PSG1 and `zDoMusicFadeIn`
  loops FM1 onwards (:2364, :2416-2430). Measured, 400 frames after the
  restore, all three PSG tracks were still overridden with their volume offsets
  driven from 4 to -60.
- **My test could not have caught it.** It stopped at the first attenuation
  decrease, and the release only happens at completion. It now runs the fade
  out and asserts the PSG tracks are released, that their volume never moved,
  and that the FM tracks arrive back at the volume they had before the jingle.
- **Second: a rewind round trip zeroed the driver's fade counters.**
  `SmpsDriverSession`'s snapshot copy lists its arguments positionally and was
  still calling the pre-fade-pair constructor, so a capture and restore dropped
  all four counters and the driver-owned-fade flag. A round trip during the
  fade left the attenuation frozen where the capture landed: 69 instead of the
  settled 15.
- **The compatibility constructor I added is what allowed it.** I widened the
  snapshot record and kept an overload at the old arity so the nine existing
  construction sites would keep compiling. That overload silently defaulted the
  new fields for every caller that had not been updated, including the one that
  mattered. Convenient, and exactly the wrong trade.
- **A guard for the shape, not just the instance.**
  `TestSmpsDriverSnapshotCopyCoverageGuard` builds a snapshot whose every
  component differs from its zero value, runs the session copy, and reflects
  over the record naming any component that did not survive. Against the
  unfixed copier it names all five. It is the same guard shape as
  `TestSmpsSequencerConfigCopyCoverageGuard`, which caught my
  `driverOwnedFadeDelay` flag two commits ago; a positional copy of a growing
  record needs one.
- **Frontier unchanged** at tick 1569, `MUS_PSG3` `volEnv`, and the DAC stream
  at run 338, byte 0.
- **Gates at this commit, all green, on a clean build and a quiet machine.**
  Audio, per-game audio and parity packages with all three ROM paths: 2,761
  tests, 0 failures, 16 skips. Ordinary suite 16,477 tests, 0 failures, 22
  skips. `-Pguards` 609 tests, 0 failures.


## 2026-09-04 - A music change was eating that service's fade step; 1490 -> 1569

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, merged with develop at `5dd1b8122`
  (a fast-forward that did not move the frontier).
- **What 1490 was.** The reference opens the service with twenty total level
  writes, four operators across five FM channels, and only then silences FM6
  and loads the next song. The engine went straight to the silence. Those
  twenty writes are one fade step: the level music was fading out with 26
  steps left and its delay counting 4, 3, 2, 1 across services 1486 to 1489, so
  1490 is the service the step falls on.
- **The cause.** `zUpdateMusic` runs `TempoWait`, `zDoMusicFadeOut` and
  `zDoMusicFadeIn` before it reaches `zFillSoundQueue` (Sound/Z80 Sound
  Driver.asm:659-701). The song playing when a request arrives therefore takes
  its fade step first, and a music change consumed by that service replaces it
  afterwards. The engine ran the step inside the song's own walk, which the
  earlier ordering work had already placed after the request, so a service that
  changed the music lost the step: at 1490 the engine's music slot is already
  the new song and the old one never stepped.
- **The fix.** The driver runs the playing song's fade step before it consumes
  the queue, and the song's own walk skips the step it has already had. Only a
  step that actually ran claims the service, which matters because a fade armed
  by the request that follows must still be left alone by its own arming
  service.
- **One wrong turn, recorded.** Latching the service unconditionally, including
  when no fade was active, swallowed a later real step and put the frontier back
  at 422 with the delay counter one behind. The oracle caught it immediately.
- **Frontier 1490 to 1569,** and the field that catches it is one promoted two
  commits ago: `TRACK_STATE_MISMATCH` on `MUS_PSG3`'s `volEnv`, reference 79
  against engine 80. Gating that field is already paying for itself. The DAC
  byte stream is unchanged at run 338, byte 0.
- **Gates at this commit, all green, on a clean build and a quiet machine.**
  Audio, per-game audio and parity packages with all three ROM paths: 2,759
  tests, 0 failures, 16 skips. Ordinary suite 16,476 tests, 0 failures, 22
  skips. `-Pguards` 608 tests, 0 failures.


## 2026-09-04 - The 1-up resume never ran its restore body on the live path

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`. Reported in game: after the extra-life
  jingle the music has no fade in and the instruments sound briefly wrong.
- **A premise worth correcting before the fix.** The brief said the engine
  re-issues the song rather than restoring track RAM. It does not. The previous
  song's sequencer is kept alive on the presentation override stack and
  reactivated, which is the engine's equivalent of the ROM's saved track
  region: the state survives. What was missing was everything
  `zFadeInToPrevious` does *after* the restore, and on the live path it was
  missing entirely.
- **What was actually wrong.** `zFadeInToPrevious` restores the saved tracks
  and then, per track, marks it playing, leaves the PSG tracks overridden,
  clears the overriding bit on the FM ones, lowers their volume by 40h,
  resends their instrument, and arms a fade in of 40h steps with a delay of 2
  (Sound/Z80 Sound Driver.asm:2725-2789). `SmpsSequencer.triggerFadeIn` already
  implements that body, and the S3K config already carries 40h and 2 from the
  same routine. Nothing on the live path called it. The only production caller
  was `AbstractSmpsAudioBackend.doRestoreMusic`, which is not reached, because
  `AudioManager.sendLiveBackendCommands` returns false; and even there the call
  was conditional on SFX being blocked.
- **Two lines of behaviour.** The presentation restore now asks the session to
  run the body on the song that just came back. The body's S3K branch also
  resends each FM track's instrument, which it had not done, in the ROM's own
  order: clear the bit, attenuate by 40h, then send the voice (:2766-2772). The
  resend after the attenuation is what stops the voice reaching the chip at the
  level the song had before the jingle interrupted it.
- **The test reproduces the report.** `TestS3kOneUpRestoreRom` gained a case
  that plays the level music, plays the jingle, and follows the restore through
  `AudioManager.presentFrame`. It asserts the song comes back attenuated
  against its settled volume, that the restore resends FM voices, that the PSG
  tracks stay overridden through the fade, and that the attenuation then steps
  back down. Ablating the new call fails it with "was 15 against a settled 15":
  the song returning at full volume, which is the reported symptom exactly.
- **Frontier unchanged** at tick 1490, event 0, and the DAC stream at run 338,
  byte 0. The intro capture reaches no extra life.
- **Gates at this commit, all green, on a clean build and a quiet machine.**
  Audio, per-game audio and parity packages with all three ROM paths: 2,759
  tests, 0 failures, 16 skips. Ordinary suite 16,476 tests, 0 failures, 22
  skips. `-Pguards` 608 tests, 0 failures.
- **Still open, and deliberately not done here.** The driver-level backup of
  the tempo, tempo speedup, voice table pointer and song bank that
  `zPlayMusic`'s extra-life branch saves (:1739-1781) has no engine equivalent;
  the presentation stack preserves the sequencer instead. That is a real
  structural difference, but it is not what the owner heard, and folding it in
  would have hidden the two-line fix that was.


## 2026-09-04 - The whole S3K fade-out machine moves to the driver, and both counters gate

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`.
- **What moved.** `zFadeOutTimeout` (1C0Dh) and `zFadeInTimeout` (1C29h) join
  the delay pair as driver state, so the fade-out machine is driver-owned end
  to end. `zDoMusicFadeOut` runs from `zUpdateMusic` every service and tests
  only `zFadeOutTimeout` (Sound/Z80 Sound Driver.asm:2331-2346), so the driver
  now steps it with no song loaded, which is the case a song-owned stepper
  could not reach at all. A song still drives its own fade through
  `SmpsSequencer.processFade`.
- **Ordering, learned from the oracle.** The songless step must run before the
  service consumes its request. `zUpdateMusic` runs `TempoWait` and both fade
  handlers before it reaches `zFillSoundQueue` (:659-701), so a fade armed by
  this service's own request is not stepped until the next one. Stepping it
  first put the engine one ahead at service 1, reading 5 where the reference
  read 6.
- **Both counters now gate.** `fadeDelay` and `fadeDelayTimeout` are compared
  every service, and each was proven to run by corrupting the engine's value
  for it and checking the oracle names that field. S1 and S2 keep their
  song-owned shape under `driverOwnedFadeDelay`.
- **Frontier unchanged** at tick 1490, event 0, and the DAC stream at run 338,
  byte 0. Two more gated fields at no cost.
- **A measurement hazard I created and then had to unpick.** Two of my own
  background gate runs overlapped in one worktree, sharing its `target/` and
  its Maven temp directory. That produced a `CAPTURE_FAILURE` in a
  memory-constrained child JVM on one run and twenty "failed to create default
  temp directory" guard errors on the next, with different victims each time
  and neither reproducible alone. Disk had 326 GB free, so space was never the
  issue. Killing the stray run by PID and running the chain once on a quiet
  machine gave a clean result. Read a red from a worktree that had two runs in
  it as a measurement fault first, not a regression.
- **Gates at this commit, all green, on a clean build and an undisturbed
  machine.** Audio, per-game audio and parity packages with all three ROM
  paths: 2,758 tests, 0 failures, 16 skips. Ordinary suite 16,475 tests, 0
  failures, 22 skips. `-Pguards` 608 tests, 0 failures.


## 2026-09-04 - The fade delay pair moves to the driver, and the ROM swaps its roles

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`.
- **The gap this closes.** `zFadeDelay` and `zFadeDelayTimeout` are driver
  variables. Both `zFadeOutMusic` and `zFadeInToPrevious` write them before
  looking at anything else, and neither asks whether a song is loaded
  (Sound/Z80 Sound Driver.asm:2306-2312, :2784-2789). The engine kept the pair
  on the music sequencer's fade state, so a fade armed with no song recorded
  nothing at all. The driver now owns both bytes, arms them on a fade request
  either way, snapshots them and restores them.
- **S1 and S2 keep their own shape, under a config flag.** They hold a single
  delay byte per direction and reload it from an immediate rather than from a
  stored timeout: 3 in both (`s1.sounddriver.asm:1363`, :1381;
  `s2.sounddriver.asm:2425-2429`) against S3K's 6. Only S3K sets
  `driverOwnedFadeDelay`.
- **The ROM swaps the pair's roles between its two steppers, which I had
  backwards at first.** `zDoMusicFadeOut` decrements `zFadeDelayTimeout` and
  reloads it from `zFadeDelay` (:2337-2346). `zDoMusicFadeIn` decrements
  `zFadeDelay` and reloads it from `zFadeDelayTimeout` (:2405-2414). Both
  arming routines write the same value to both halves, so the swap is
  invisible until a fade actually runs; the oracle found it at service 2. The
  engine's accessors are therefore direction-aware rather than fixed.
- **The gate is not landed yet, and here is exactly why.** With the pair
  driver-owned, the oracle reaches service 2 and stops: the reference has
  stepped the counter to 5 and the engine still reads 6. The ROM's
  `zDoMusicFadeOut` runs from `zUpdateMusic` every service and only tests
  `zFadeOutTimeout`, so it steps the delay with no song loaded. The engine's
  fade-out timeout is still song state, so with no song there is no stepper to
  run. That byte is the next piece to move, and the two counters are gated the
  moment it does. They stay diagnostic until then, with that reason recorded in
  the registry rather than left blank.
- **Caught by a guard, worth noting.** The new config flag failed
  `TestSmpsSequencerConfigCopyCoverageGuard` until it was added to
  `SmpsAssetCatalog.copyBuilder`. Without that the presentation layer would
  have copied the config and silently dropped the flag, so every sound played
  through `AudioManager` would have used the S1/S2 shape.
- **Frontier unchanged** at tick 1490, event 0, and the DAC stream at run 338,
  byte 0.
- **Gates at this commit, all green, on a clean build.** Audio, per-game audio
  and parity packages with all three ROM paths: 2,758 tests, 0 failures, 16
  skips. Ordinary suite 16,475 tests, 0 failures, 22 skips. `-Pguards` 608
  tests, 0 failures.


## 2026-09-04 - Four comparator fields promoted from diagnostic to gated, at no cost

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`.
- **Why.** The previous entry's envelope drift ran eight entries deep for four
  hundred services before any gated field noticed. The field that would have
  caught it on the first service was registered as diagnostic.
- **What was actually wrong with `volEnv`.** Not a shape mismatch, as its
  registry note claimed. The engine sent `null`: three track fields were
  hard-coded null in `S3kAudioStateNormalizer`, so there was nothing to
  compare even if the field had been gated. The ROM's byte is the envelope
  *position*, which `zDoVolEnvAdvance` increments and `zFinishTrackUpdate`
  clears (Sound/Z80 Sound Driver.asm:1055-1069, :4211-4213), and the engine
  has carried it in `SmpsTrackSnapshot.envPos` all along.
- **Promoted, all four free.** The frontier is 1490 before and after.

  | Field | ROM byte | Engine source |
  |---|---|---|
  | `volEnv` | VolEnv 17h | `SmpsTrackSnapshot.envPos` |
  | `noteFillTimeout` | NoteFillTimeout 1Eh | `fillCounter` |
  | `noteFillMaster` | NoteFillMaster 1Fh | `fill` |
  | `palDoubleUpdateCounter` | zPalDblUpdCounter 1C04 | `SmpsDriverSnapshot.palUpdateCounter` |

- **Each proven to run.** Corrupting the engine's value for a field, one at a
  time, makes the oracle report that field by name. A gate that has never
  failed and a gate that never executes look identical from the outside.
- **One I did not promote, and the gap it found.** `fadeDelay` and
  `fadeDelayTimeout` have settled ROM meanings, but wiring them puts the
  frontier at tick 1: the reference reads 6 where the engine reads 0, because
  the ROM seeds `zFadeDelay` at driver init and the engine carries no such
  byte until a fade is armed. That is a real engine gap, not comparator
  timidity, and gating it now would hide everything after tick 1. Recorded here
  so it is a known item rather than a silent one; the fix is to give the driver
  the seeded byte, after which the gate costs nothing.
- **The rest stay diagnostic for cause, not for comfort.** `dacIndex`,
  `fadeInTimeout`, `pauseFlag`, `soundQueue`, `nextSound`, `modulationPtr` and
  `dataPointer` each name a ROM byte the engine has no equivalent for, or a
  Z80 address, or presentation-side state that is not a driver flag.
  `fadeOutTimeout` maps to a fade shape that differs. None of those is a
  settled mapping being withheld.
- **Frontier unchanged** at tick 1490, event 0, and the DAC stream at run 338,
  byte 0. No re-pin was needed.
- **Gates at this commit, all green, on a clean build.** Audio, per-game audio
  and parity packages with all three ROM paths: 2,758 tests, 0 failures, 16
  skips. Ordinary suite 16,475 tests, 0 failures, 22 skips. `-Pguards` 608
  tests, 0 failures.


## 2026-09-04 - Every driver restarts the volume envelope at a note; frontier 760 -> 1490

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, merged with develop at `725d4cfd5`,
  which did not move the frontier.
- **What 760 was.** The reference writes the music PSG1 tone and moves on to
  the next channel; the engine wrote the tone and then a latched silence,
  `09Fh`, so its event 20 was a silence where the reference had the second
  channel's tone latch.
- **Not an ordering fault, which is worth recording.** `zUpdatePSGTrack` sends
  the frequency first and reads the volume envelope after, and an `83h`
  terminator then jumps to `zRestTrack`, which falls through into
  `zSilencePSGChannel` (Sound/Z80 Sound Driver.asm:4058-4130, :4189-4194,
  :4220-4245). Frequency-then-silence is exactly what the ROM would emit. The
  engine's order was right; it simply reached the terminator and the ROM did
  not.
- **The cause.** Comparing envelope positions service by service, the
  reference restarts the music PSG1 envelope at service 559 while the engine
  is eight entries in and still climbing. Every driver resets that index
  beside the note fill, in the same routine and under the same do-not-attack
  guard: `zFinishTrackUpdate` (:1055-1069), S2 at
  `s2.sounddriver.asm:948-957`, and S1 `FinishTrackUpdate` at
  `s1.sounddriver.asm:436-442`, whose own comment notes it happens even on FM
  tracks. The engine reset the note fill and the aliased modulation bytes
  there but never the envelope index, so a PSG envelope ran past the note that
  should have restarted it, hit its terminator early and parked. A parked `83h`
  re-silences the channel on every later pass, which is what event 20 was.
- **Why it stayed hidden.** `volEnv` is registered as a diagnostic field, not
  a gated one, its own note saying it is "not yet pinned". The comparator
  therefore never checked the position, and the first visible symptom was a
  write four hundred services later. Worth revisiting whether it can be gated
  now, which would move the frontier but stop hiding this class of drift.
- **The fix is shared, and the other two oracles say so.** The reset is
  unconditional in all three drivers, so it is applied for all three. The S1
  gameplay oracles, both S2 driver-state oracles, the S2 published request
  windows and the S2 request-aware raw stream all stayed green.
- **Frontier 760 to 1490.** `EVENT_VALUE_DIFFERENT` at tick 1490, event 0:
  reference `ym2612 port 0 register 64 value 33`, a total-level write, against
  engine `port 1 register 130 value 255`. The DAC byte stream is unchanged at
  run 338, byte 0.
- **Gates at this commit, all green, on a clean build.** Audio, per-game audio
  and parity packages with all three ROM paths: 2,758 tests, 0 failures, 16
  skips. Ordinary suite 16,475 tests, 0 failures, 22 skips. `-Pguards` 608
  tests, 0 failures.


## 2026-09-04 - The S3K 1-up restore was lost to a rejected presentation command

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`. Reported in game on develop: after the
  extra-life jingle the music is wrong.
- **What happened.** `cfFadeInToPrevious` stores `zFadeToPrevFlag` and the
  driver's main loop acts on it (`Sound/Z80 Sound Driver.asm:3079-3082`, read
  at :659-666), so the restore belongs inside the driver's service. The engine
  splits it: the coordination flag asks, and the presentation layer brings the
  backed-up song back. The S3K handler asked through the global
  `AudioManager` instead of the sequencer's injected restore sink. A flag runs
  inside the service, which for this path runs inside an active presentation
  command batch, and the batch refuses a command submitted into it. The refusal
  is logged rather than raised, so the restore was lost in silence and the
  level music never came back.
- **The other door was already right.** The sequencer holds a
  `MusicRestoreSink`, and for presentation-owned sequencers that sink is
  `AudioManager.restoreShadowMusic`, which sets a flag `presentFrame` drains
  after the batch closes. S1 and S2 reach the restore through it in
  `SmpsSequencer.handleFadeIn`, so they were never affected. Confirmed at
  runtime as well as by reading: an S2 extra-life through the same
  `presentFrame` path restores Emerald Hill with no warning logged.
- **The fix.** `CoordFlagContext` now carries `restorePreviousMusic`, the S3K
  handler calls it, and `SmpsSequencer` implements it against its injected
  sink. Two lines of behaviour, and the handler no longer reaches a singleton
  from inside a service.
- **A design I did not force.** My first test asserted the restore reaches the
  command timeline. It does not, for any of the three games: the presentation
  sink raises a presentation command, not an `AudioCommand`, and a rewind
  replays the jingle itself, whose flag produces the restore again. I dropped
  the assertion rather than add a timeline entry that would double-apply on
  replay.
- **Test.** `TestS3kOneUpRestoreRom` drives `AudioManager.presentFrame` with
  the S3K profile, plays the level music, plays the jingle, and runs to the
  flag. It asserts the level music comes back, that no shadow command mirror
  warning was logged, and that the jingle's own sequencer is gone. Broken on
  purpose against the old handler, it fails on the first of those: the music
  never returns, which is the owner's report exactly.
- **Frontier unchanged** at tick 760, event 20, and the DAC stream at run 338,
  byte 0. The intro capture never reaches an extra life.
- **Gates at this commit, all green, on a clean build.** Audio, per-game audio
  and parity packages with all three ROM paths: 2,727 tests, 0 failures, 16
  skips. Ordinary suite 16,442 tests, 0 failures, 22 skips. `-Pguards` 608
  tests, 0 failures.


## 2026-09-04 - S3K 566 was my own double skip; frontier 566 -> 760

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, merged with develop at `633ee400f`.
  The merge did not move the frontier: it was 566 before and after.
- **What 566 was.** `TRACK_STATE_MISMATCH` on `SFX_FM4`'s `resting`, reference
  true against engine false. Sound effect B7h is admitted at service 565 with
  two tracks, on ROM FM5 and FM6. The reference walks both at 566: one takes a
  rest of two, the other a note of twenty-two, and the resting track plays the
  same note at 568. The engine did not walk either until 567, one service late.
- **The cause was the previous entry's fix, not a new defect.** Consuming a
  request at the ROM's point means a sound effect created there has genuinely
  missed its admitting service's SFX walk, because that walk ran earlier in the
  same service. `SmpsSequencer.primeFirstService` also skips a walk, modelling
  the same ROM ordering for a sound admitted outside a service, which is how
  live playback admits one. Both skips fired, so the sound lost two services
  instead of one.
- **The fix.** The driver now tells a sound effect created during request
  consumption that its admitting service's walk has already gone by, and
  `primeFirstService` no longer skips a second one for it. The skip stays
  intact for every sound admitted outside a service, so live playback is
  unchanged.
- **A phantom I nearly chased.** The engine's probe prints linear channel
  indices and the reference's roles are one-based, so engine FM3 and FM4 are
  ROM FM4 and FM5. There was never a channel-mapping fault; recorded so the
  next reader does not spend the same reading.
- **Frontier moved forward past its previous best.** From 566 to 760, where
  the previous best was 751. `EVENT_VALUE_DIFFERENT` at tick 760, event 20:
  the reference writes PSG `0A3h`, a latched volume of 3 on the second channel,
  where the engine writes `09Fh`, a latched silence on the first. The DAC byte
  stream is unchanged at run 338, byte 0.
- **Gates at this commit, all green, on a clean build.** Audio, per-game audio
  and parity packages with all three ROM paths: 2,726 tests, 0 failures, 16
  skips. Ordinary suite 16,441 tests, 0 failures, 22 skips. `-Pguards` 608
  tests, 0 failures.


## 2026-09-04 - S3K: a request no longer pre-empts the service that consumes it

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`.
- **The fix.** `zUpdateEverything` runs `zPauseUnpause` and
  `zUpdateSFXTracks`, then `zUpdateMusic`'s `TempoWait` and both fade
  handlers, and only then loads `zMusicNumber` and calls `zFillSoundQueue`
  and `zCycleSoundQueue` (`Sound/Z80 Sound Driver.asm:653-701`). The tracks
  playing when a request arrives are therefore walked by the very service that
  consumes it, before it is consumed. The capture host applied the request
  ahead of `driver.serviceOuterFrame()`, so a music change tore those tracks
  down first and the frequency their last update owed the chip was never sent.
  The host now hands the request to the driver, which runs it between the SFX
  walk and the music walk in `serviceSfxThenMusic`.
- **Three slots, not one.** The first attempt asserted on a second pending
  request and the oracle stopped on it. That was the ROM telling me something:
  `zCycleSoundQueue` is called three times, playing `zMusicNumber`,
  `zSFXNumber0` and `zSFXNumber1` in order (:698-701), so one service can
  consume a song and two sound effects. The driver now runs them in submission
  order and refuses a fourth.
- **The load service still walks the song it loads.** Placing the consume
  point after the music walk broke the title load at service 138, where the
  reference's `durationTimeout` is 6 and the engine's stayed at its seed of 1.
  `.update_music` follows `zCycleSoundQueue` at :702, so the new song is
  walked by its own load service, and `SmpsSequencer.primeFirstService` owns
  what that walk does. The consume point sits before the music walk in both
  service orders.
- **Evidence at 751.** The reference opens that service with `A5h` then `A1h`,
  the frequency pair for linear FM4, and then begins the six-channel silence
  loop at `82h`. The engine used to open at `82h`; it now opens with the same
  pair. Non-DAC writes for the service went from 258 to 260 against the
  reference's 274, and the event-zero divergence there is gone.
- **The frontier moved backwards, from 751 to 566, and that is the honest
  result.** Correcting the ordering also changes what the music change at
  service 495 leaves behind, which uncovers a `resting` difference at 566 that
  the old ordering had been hiding: reference true, engine false. The old
  number was not the better engine. The pin in
  `TestS3kOracleRequestSidecarWiring` follows the correction and says so in its
  own comment rather than moving quietly.
- **The sidecar was audited and is correct.** All fourteen observations were
  checked against the reference's own non-DAC write counts. Every request that
  loads music resolves to the service whose writes show the load. The branch I
  had suspected never fires for any of them.
- **Gates at this commit, all green, on a clean build.** Audio, per-game audio
  and parity packages with all three ROM paths: 2,696 tests, 0 failures, 16
  skips. Ordinary suite 16,408 tests, 0 failures, 22 skips. `-Pguards` 607
  tests, 0 failures. S1 and S2 are untouched by construction: only the S3K
  capture host submits a service request, so `runPendingServiceRequest` is a
  no-op for every other driver, and both oracle-backed S1 classes and all five
  S2 ones stayed green.
- **Next.** Service 566, `TRACK_STATE_MISMATCH` on `resting`, reference true
  against engine false. The DAC byte stream is unchanged at run 338, byte 0,
  reference `88h` against engine `7Fh`.


## 2026-09-04 - S3K 751: the sidecar was right, my probe was not, and the real fault is request ordering

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, engine at `4c43929a9`.
- **Correction, and it retracts the previous entry's main claim.** I reported a
  one-service phase error, with the engine spending 89 writes in service 750
  and 177 in 751 against the reference's 8 and 274. That was a measurement
  artefact of my own making. The probe set its ordinal immediately before
  `driver.serviceOuterFrame()`, but the capture host dispatches the service's
  request *before* that call, so every write the request itself produced was
  stamped with the previous service's ordinal. Moving the assignment above the
  dispatch loop gives 8 writes in 750 and 258 in 751. There is no phase error.
  This is the mislabelled-probe-column hazard exactly as the briefing describes
  it, and it cost an entry.
- **The sidecar attribution is correct, and was never the problem.** Audited
  against all fourteen observations, using the reference's own non-DAC write
  counts as the arbiter. Every request that loads music resolves to the service
  whose write count shows the load, and in each of those cases the consuming
  service is simply the first one whose frame is greater than the observation
  row, which is `firstServiceAfter` with no adjustment:

  | Row | Request | Consuming service frame | Its non-DAC writes |
  |---|---|---|---|
  | 251 | 25h | 253 | 259 |
  | 618 | 2Fh | 620 | 164 |
  | 876 | 01h | 879 | 274 |
  | 1618 | 1Fh | 1620 | 218 |
  | 2144 | 01h | 2147 | 296 |
  | 3968 | 2Ch | 3970 | 196 |
  | 5165 | 01h | 5167 | 276 |

  In every one of these the row after the observation completes no service at
  all, so the rule's third branch fires and takes that service unchanged. The
  branch I suspected, the one that steps back a service, never runs for them.
  The remaining seven observations are fades and stops whose services carry the
  request in their own frame-entry mailbox, so the sidecar only cross-checks
  them.
- **The real fault is that a request pre-empts its own service's track walk.**
  Reference service 751 opens with `A5h` then `A1h`, the frequency pair for
  linear FM4, and only then begins the six-channel silence loop at `82h`. The
  engine's 751 opens directly at `82h`. Its service 750 shows the ordinary
  per-service frequency sends, so the walk works normally right up to the
  service that takes the request.
- **Why, in ROM terms.** `zUpdateEverything` runs `zPauseUnpause` and
  `zUpdateSFXTracks`, then `zUpdateMusic`'s `TempoWait` and both fade
  handlers, and only then loads `zMusicNumber` and calls `zFillSoundQueue`
  and `zCycleSoundQueue` (`Sound/Z80 Sound Driver.asm:653-701`). So the tracks
  of the service that consumes a request are walked *before* the request is
  consumed. The capture host dispatches the request before
  `driver.serviceOuterFrame()`, so the stop-all runs first and there is no walk
  left to do. This is the same ordering the branch already models for the first
  service through `sfxWalkPrecedesRequest`; it is simply not applied to
  mid-stream requests.
- **Size of the gap.** Reference 274 non-DAC writes against the engine's 258 at
  service 751. The two leading frequency writes are part of that difference; the
  rest should be accounted for by the fix rather than assumed.
- **Gates.** Not re-run; no engine behaviour changed since `0da7b645a`.

## 2026-09-04 - The unobserved transfer is sndDriverInput's music store at PC $10C0, and the hook is managed

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, at `54d3d3adb`. Analysis only; nothing
  changed and no line moved.
- **The answer, cited.** `sndDriverInput` makes two distinct stores into Z80
  RAM. The music one is `move.b d0,zVar.QueueToPlay(a1)` at
  `.isNotPauseCommand`, which the disassembly labels `loc_10C0`
  (s2.asm:1302-1304). The SFX one is `move.b d0,zVar.Queue0(a1,d1.w)` inside
  `.loop` (:1317-1326). The observer's manifest hooks a single instruction,
  `pc 4310` with opcode `13801009`, and 4310 is `$10D6`.
- **Verified against the cartridge, not only the listing.** The ROM holds
  `13400008` at `$10C0` and `13801009` at `$10D6`, and `72031030` at `$10C4`,
  which is the `moveq #3,d1` the `fixBugs = 0` path assembles at `.doSFX`. So
  `$10D6` is the SFX store the manifest already names and `$10C0` is the music
  store it does not.
- **That single omission explains both gaps.** The nine music loads reach the
  driver through `$10C0`, and so does the ring at movie row 3225, because the
  ring-milestone check jumps to `PlayMusic` (s2.asm:25913-25914) which writes
  `Sound_Queue.Music0` or `Music1` (:1520-1527) rather than the SFX slots.
- **A structural finding that changes the shape of the fix.** The hook is not a
  native observer action. `S2PreconsumptionRequestObserver` arms it with
  `host.RegisterExecuteCallback(Pc, OnTransfer)`, a managed execute callback,
  and reads `M68K D0` and `D1` in the callback; the manifest's
  `native_action: 7` and patch digest are validated as identity, not used to
  arm this hook. So adding the second site needs no patch edit, no core rebuild
  and no toolchain: it is a manifest schema change plus managed code and tests.
- **One detail the second site must not copy.** At `$10D6` the callback reads
  `D1` as the SFX queue slot, which the `.loop` index makes correct. At `$10C0`
  `D1` holds the pause-check residue of `move.b d0,d1` / `subi.b #MusID_Pause,d1`
  (:1294-1295), so it is not a slot and must not be recorded as one; the music
  transfer needs its own fixed slot identity.
- **Next.** Implement the second site in the TraceChaser submodule under the
  same rules, add the harness tests the existing site has, then recapture and
  re-measure. The three published request windows and their transfer counts of
  25, 52 and 27 are a gate: a widened observer that changes them must explain
  each new transfer by ROM routine, and every one of them should be a music
  request that `$10C0` alone carries.

## 2026-09-04 - The CPZ writes line is producer-blocked: one ring is played through the music mailbox

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, at `aa3b74a4b`.
- **Measurement, not a comparison run.** Nothing changed. CPZ state only
  `MATCH (720 ticks)`; state with writes DIVERGENCE at tick 494 (movie row
  3225), `writes.count`, reference 34 against the engine's 3, 18 of 719
  divergent. All 18 are at or after that row, so there is one cause.
- **What the reference plays there.** The FM5 voice-load block, byte for byte
  the same one the ring plays at rows 2968, 2974, 3004 and 3069. The engine
  emits only the three music writes of that service, because it was never told
  to play it.
- **Only the ring path can produce it, and that is checked rather than
  assumed.** `zRingSpeaker` has exactly one writer in the whole driver, the
  `cpl` in `zPlaySound_CheckRing` (s2.sounddriver.asm:2127-2134, declared at
  :4091), and the flag flips at this row. So a ring was played.
- **Two producers, nine rings, and the same one missing from both.** The
  power-on driver-state capture records nine flips of the flag, at movie rows
  1436, 2968, 2974, 3004, 3069, 3225, 3437, 3440 and 3448. Its own
  pre-consumption markers appear at seven of those, missing 1436 and 3225. The
  request capture records eight transfers, at 1432, 2968, 2974, 3004, 3069,
  3437, 3440 and 3448, missing 3225. So row 3225 is invisible to both, and the
  49-transfer reduction is not at fault: the extracted payload contains 49
  request values in total and none of them is near that row.
- **The ROM says why. The 68k plays the ring from seven sites and one of them
  uses the other mailbox.** Six reach `PlaySound` or `PlaySound2`, but the
  ring-milestone check at s2.asm:25913-25914 does `move.w #SndID_Ring,d0` then
  `jmp (PlayMusic).l`, and `PlayMusic` writes `Sound_Queue.Music0` or
  `Music1` (:1520-1527) rather than the SFX slots. The request manifest hooks a
  single instruction, `pc 4310` with opcode `13801009`, described as the
  accepted M68K-to-Z80 transfer, so a request that arrives through the music
  mailbox is not observed. This is the same mailbox split an earlier lane
  recorded for the ten-ring and shield monitors.
- **So the writes line cannot reach MATCH from the committed captures.** The
  missing stimulus is a request the recording genuinely made; supplying it by
  reading the reference's flag flip would be hydrating driver state to decide
  what the engine plays, which hard rule 4 forbids, and narrowing the window to
  end before row 3225 would be fitting the measurement to the gap.
- **What would unblock it.** A producer change: record the music-mailbox
  transfer as well as the SFX one. The manifest is already data-driven, with
  `request_transfer` carrying cpu, pc, opcode, native action and marker tokens,
  so the shape of the change is a second transfer site rather than new
  analysis. It needs a TraceChaser submodule change and a recapture, which is
  why it is proposed here rather than started.

## 2026-09-04 - The ring phase is derived from requests; CPZ tick 237 -> 494, and two producer limits found

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, over `develop` at `874906bb7`.
- **Before:** CPZ state with writes DIVERGENCE at tick 237 (movie row 2968),
  `writes[4]`, reference `ym1[0xb1]=0x4` against the engine's
  `ym1[0xb0]=0x4`; 36 of 719 divergent.
- **After:** DIVERGENCE at tick 494 (movie row 3225), field `writes.count`,
  reference 34 against the engine's 3; 18 of 719 divergent. State only stays
  `MATCH (720 ticks)`.
- **The removal condition is met by derivation, not by a power-on engine run.**
  The ring flag is the parity of the rings played since power-on, because
  `zPlaySound_CheckRing` complements it on every ring
  (s2.sounddriver.asm:2124-2135). Ring requests are stimuli under the accepted
  contract, so counting the ones before a window derives the phase without
  reading a compared value. `S2OracleEngineCapture` now takes that count and
  the CPZ oracle reads it from the new committed stimuli file, which carries
  exactly one ring request, at movie row 1432. The driver-state capture agrees:
  `zRingSpeaker` is `00h` from power-on and flips once, at movie row 1436, the
  service that consumed it.
- **Two new fixtures.** `s2-driver-state-cpz-w0-3450.reference-v2.jsonl.gz`,
  3,264 ticks from the driver's first serviced frame to row 3449, duplicate
  capture met byte-identical; and `s2-request-stimuli-cpz-w120-3450.json`, the
  49 request transfers of the extracted request window reduced to row, order,
  request and slot, with the source payload's digest recorded.
- **First producer limitation: the request capture cannot start at row 0.**
  `--request-window-mode capture --first-row 0` fails with "GPGX audio observer
  begin publication epoch failed with status -2". The same command at
  `--first-row 120`, the driver's first serviced frame, succeeds, and at
  `--first-row 2700` succeeds. The driver-state mode accepts row 0 and simply
  reports its first tick at row 120.
- **Second producer limitation, and it is the larger one: no music requests are
  recorded.** All 49 transfers over rows 120-3448 are `A0h` or above. The
  driver-state capture shows the run loading nine songs in that span, with
  `zCurSong` moving to `99h`, `91h`, `99h`, `89h`, `97h`, `82h`, `99h`, `91h`
  and finally `8Eh` at row 2725, and not one of those appears in the request
  stream. So an engine run driven from power-on by recorded requests cannot
  reproduce the songs the recording plays, which is what a true power-on
  comparison would need.
- **A third, smaller gap in the same producer.** The stimuli carry eight ring
  requests while the driver-state capture records nine flips of `zRingSpeaker`,
  and only the ROM's ring path complements that byte (:2133). The missing one is
  at movie row 3225, which is exactly the new first divergence: the reference
  emits 34 writes there and the engine 3, because it was never told to play
  that sound.
- **What did not change.** No engine behaviour. The capture's power-on default
  is still the ROM's zero flag; the count simply advances it.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the EHZ v2 oracle
  unchanged at `MATCH (2198 ticks)` on both lines; the three request windows
  `MATCH` at 25, 52 and 27 transfers; audio packages plus the four extra
  classes with three ROM paths: 2,063 tests, 0 failures, 0 errors, 10 skips.

## 2026-09-04 - The miniboss fade is real, and the drowning restore had no overrides

- **Worktree/branch:** `.worktrees/s3k-issue-coverage`,
  `feature/ai-s3k-aiz1-audio-issue-coverage`, over `develop` at `633ee400f`.
- **The fade-rate claim stands, and the run that seemed to contradict it was
  measuring elsewhere.** A source reading said the AIZ miniboss and fire-curtain
  fades ran at the S1/S2 delay of 3; a later engine-driven run of seven AIZ1
  music events recorded no fade at all and appeared to retract it. Both were
  right. That run drove the fire-curtain restore through `setEventsFg5`, which
  enters after the cutscene's own trigger has already run, so it never reached
  either fade site.
- **Settled by execution.** Driving `updateWaitTrigger` with a real
  `AudioManager` carrying the S3K profile records exactly one `cmd_FadeOut`
  carrying `28h/6`, the values `zFadeOutMusic` loads
  (Sound/Z80 Sound Driver.asm:2306-2311). Before the routing fix it carried
  `28h/3`. Pinned by `TestAizMinibossCutsceneInstance`, which fails on the
  parameters if the profile supplies another game's.
- **The drowning restore ignored all three ROM overrides, confirmed by
  running it.** `Player_ResetAirTimer` (sonic3k.asm:33663-33686) loads
  `Current_music` and substitutes `mus_Invincibility` for an invincible player,
  the same track again for Super or Hyper, and `mus_MinibossK` during a boss.
  On a real AIZ1 fixture the engine requested the zone track in every case, so
  surfacing while invincible or Super cut the theme dead.
- **All three games' rules are filled from their own routines, not defaulted.**
  The shapes differ and the differences matter: Sonic 1's overrides are
  conditional on `Revision<>0`, which holds for the REV01 ROM the engine models
  (sonic.asm:14), and it has no Super form; Sonic 2 gives Super its own track
  rather than reusing the invincibility theme (s2.asm:42296-42318); S3K reuses
  it. In all three the boss test is taken last, so a boss wins.
- **Coverage.** `TestAirResetMusicSelection` pins each game's choice and the
  precedence, `TestS3kAirResetMusicRestore` drives a real AIZ1 fixture and pins
  that the restore asks for it. The second was red on the invincible and Super
  cases before the fix and is green after.

## 2026-09-04 - The subset is published: 20 per-song windows, all 15 songs, six matching

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` merged at `633ee400f`,
  clean-built before measuring.
- **Command:** `LUA_BIN=lua5.4 mvn -Dmse=off -B -Dsonic1.rom.path=...
  -Dsonic2.rom.path=... -Ds3k.rom.path=... -Ds2.request.bk2.path=...
  "-Dtest=com/openggf/tools/audio/parity/**/*,com/openggf/audio/**/*,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" test`

**Published:** 20 windows from `s1-complete-run.bk2`, 5.94 MB gzipped, covering
**all 15 songs the movie plays**. Four whole-movie capture passes, two per
window, every published window verified `cmp`-identical between its pair before
publication.

**Eight windows match end to end, across six distinct songs** -- `$81` Green
Hill (three windows, one of 5,726 ticks), `$82` Labyrinth, `$84` Spring Yard at
5,535 ticks, `$8A` title screen, `$8C` special stage, and `$91` credits. Before
today only GHZ had ever matched.

**The twelve red windows, by cause.**

| Cause | Windows | Frontier |
|---|---|---|
| SFX override held from before the window's epoch | 15, 16, 58, 77, 80 | tick 0 (58 at tick 34) |
| Act-clear double driver pass | 62, 72 | tick 360, `tempo_timeout` |
| 1-up restore arms a fade the engine has no save slot for | 59 | tick 0, `fade_active` |
| `StopAllSound` write ordering inside the silence | 81 | tick 4,646 of 4,671 |
| Re-entered invocation | 82 | tick 0 |
| First chip write | 76 | tick 0 |
| PSG base frequency | 30 | tick 0 |

**The restore window confirms the other lane's prediction exactly.** Window 59,
music `$84`, opens at the 1-up restore and diverges at tick 0 on `fade_active`:
`cfFadeInToPrevious` arms a fade-in at counter `$28`, and the engine has no
driver-level 1-up save slot, so it has nothing to restore into. That is 0.7
authenticity work, not a driver defect, and it is now pinned rather than
predicted.

**Two housekeeping corrections.** A stale `w002` fixture from the earlier
partial publish was removed; the full capture's `$8E` windows are 62 and 72.
And window 81's pinned frontier moved between the pre-merge and post-merge
measurement, from the engine emitting a pan byte to emitting a key-off with the
wrong channel -- closer, and re-pinned from a clean build rather than carried.

**Gates.** Audio parity and the wider audio packages plus both rewind coverage
guards, with three ROM paths and the S2 request movie: **2,109 tests, 0
failures, 0 errors, 10 skips.** Both S1 gameplay oracles `MATCH` at 2,562 and
5,257 ticks; S2 v1 `MATCH (698 ticks)`; v2 both lines `MATCH (2198 ticks)`; CPZ
state-only `MATCH (720 ticks)`; request windows `MATCH` at 25, 52 and 27.


## 2026-09-04 - StopAllSound routed against the window that contains one: $8D from a crash to tick 4,646 of 4,671

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` merged at `1456a4a51`.
- **Fixture:** window 81 of the completed pass over `s1-complete-run.bk2`,
  music `$8D`, 4,671 ticks.
- **Result before:** the capture host threw, `S1 driver flag command 0xe4 is not
  modelled by the parity host yet`. Nothing was compared.
- **Result after:** `EVENT_VALUE_DIFFERENT` tick 4,646, reference
  `ym2612 port 0 register 28h = 2` against engine `port 1 register B1h = 61`.
  **Every compared field agrees through tick 4,645, and the first two chip
  writes of the stop agree.**

**Routed against the real window, not a guess.** `$E4` is the fourth sound id
`PlaySoundID` sends through `Sound_E0toE4` (s1.sounddriver.asm:715, :726),
reaching `StopAllSound` (:1461-1482). It is a different mechanism from the `E4`
*coordination flag* in track data, which ends a 1-up jingle; the engine's
`handleFadeIn` is that one. Conflating them is easy and this entry records the
distinction because a reader of the earlier `Sound_E0toE4` commit could make it.

**What the ROM does, and what now happens.** Enable the DAC (`2Bh = 80h`), put
FM3 and FM6 back in normal mode with the timers off (`27h = 00h`), clear the
driver's variable and track RAM, set `v_sound_id` to `$80`, then `FMSilenceAll`
and `PSGSilenceAll`. The host emits the two mode writes, stops the SFX
sequencers, applies the RAM clear to the music sequencer's own tracks, and
silences. The clear zeroes the master tempo and its timeout as well as the track
RAM, which is what took the frontier past its first landing at tick 4,646 on
`tempo_reload`, reference `0` against engine `6`.

**Where it stops.** The remaining difference is inside the silence itself: the
ROM's next write is the first `28h` key-off and the engine emits a leftover
`B1h` pan byte first. That is a write-ordering question inside the stop, with
the driver state on both sides already agreeing, and it is recorded rather than
chased.

**Placement, for the same reason as the fade's stops.** The stop runs before the
frame service rather than at the dispatch point, because it releases sequencers
and the driver iterates its list by index over a size captured before the pass.
The ROM likewise clears before it walks any track.

**Gates.** Audio parity and the wider audio packages plus both rewind coverage
guards: 2,072 tests, 0 failures, 0 errors, 10 skips. All three published pins
unchanged; both S1 gameplay oracles `MATCH` at 2,562 and 5,257.


## 2026-09-04 - The tick-zero override class is fixable, not a discrepancy: withdrawing my own entry

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` merged at `1456a4a51`.
- **No measurement changed.** This records a corrected judgement.

**What I wrote, and why it was wrong.** Five of the eighteen captured windows
diverge at tick 0 on a music track's `overridden` bit, because the window's
epoch lands while an SFX admitted *before* that epoch still holds the track. I
recorded it in `known-discrepancies.md` as an accepted divergence with a
selection-based removal condition, reasoning that the overriding SFX's identity
is state predating the window and so undreivable without hydration.

**That reasoning had a hole.** The SFX was requested inside the *previous*
window, and a recorded request is the accepted stimulus for this contract --
it is what the host already replays. So the host can start at the predecessor
window's epoch, replay that window's song and its recorded dispatches, and only
begin *comparing* at the target window's epoch. Nothing is hydrated: the driver
carries itself across the boundary exactly as the ROM does, from requests
alone. Where a predecessor is itself contaminated the chain extends further
back, and it terminates, because a run's first window starts from power-on and
is always clean.

**So the entry is withdrawn.** A known-discrepancy entry asserts that a
divergence is intentional and accepted, and this one is neither. Leaving it
would have told the next lane not to fix something fixable, which is worse than
having no entry at all. The five windows keep their pinned frontiers until the
chained replay lands.

**What lands with it.** The survey now records, per window, how many music
tracks hold the SFX-overriding bit at that window's epoch. That stays useful
either way: it tells a chained replay how far back it must start, rather than
only telling a selector which windows to avoid.


## 2026-09-04 - All 18 captured windows measured: six songs green, and one dominant red class

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` merged at `02f3950df`.
- **Source:** the completed pass A over `s1-complete-run.bk2`, 84 windows walked,
  18 written, 33,354 ticks, no capture errors. Publication awaits pass B.

**The extra-life boundary works.** Window 58 is music `$88` alone, 210
invocations from frame 138,135 to 138,346. Window 59 opens at the restore
carrying music `$84`, the zone song the jingle interrupted, for 4,223
invocations. The same capture previously aborted at frame 138,347.

**Results, all 18.**

| Window | Music | Ticks | Result |
|---|---|---|---|
| 0 | `$8A` | 72 | `MATCH` |
| 1 | `$81` | 5,257 | `MATCH` |
| 3 | `$81` | 1 | `MATCH` |
| 8 | `$81` | 5,726 | `MATCH` |
| 9 | `$8C` | 1,426 | `MATCH` |
| 38 | `$82` | 981 | `MATCH` |
| 61 | `$84` | 5,535 | `MATCH` |
| 15 | `$87` | 1,202 | `TRACK_STATE_MISMATCH` tick 0, `overridden`, `true` vs `false` |
| 16 | `$83` | 443 | same, tick 0 |
| 77 | `$92` | 189 | same, tick 0 |
| 80 | `$86` | 1,074 | same, tick 0 |
| 30 | `$85` | 742 | `TRACK_STATE_MISMATCH` tick 0, `base_frequency`, `922` vs `854` |
| 58 | `$88` | 210 | `TRACK_STATE_MISMATCH` tick 34, `overridden`, `false` vs `true` |
| 62, 72 | `$8E` | 569, 524 | `GLOBAL_STATE_MISMATCH` tick 360, `tempo_timeout` |
| 76 | `$86` | 1,132 | `EVENT_VALUE_DIFFERENT` tick 0 |
| 82 | `$8B` | 1,223 | `EVENT_VALUE_DIFFERENT` tick 0 |
| 81 | `$8D` | 4,671 | capture host throws on driver command `$E4` |

**Six distinct songs agree end to end:** `$81`, `$82`, `$84`, `$8A`, `$8C`, and
`$81` again at 5,726 ticks, a longer GHZ window than any committed fixture.
Before today only GHZ had ever matched.

**The dominant red class is one thing, and it is not driver accuracy.** Five
windows diverge at **tick 0** on `overridden`, reference `true` against engine
`false`: the window's epoch lands while an SFX is still holding a music track,
and the replay host starts with no SFX admitted because a window's dispatch
record begins at its own epoch. This is the same shape as the recorded S2
`zRingSpeaker` discrepancy -- driver state that predates the window and cannot
be derived from what the window contains. Worth attacking as one item rather
than five.

**Two defects found by measuring rather than by reasoning.** Window 80 crashed
the capture host with `Index 1 out of bounds for length 1`: the dispatch-point
seam called `stopAllSfx()` from inside the music sequencer's own service, and
the driver iterates its sequencer list by index over a size captured before the
pass, so removing sequencers there walks off the shortened list. `FadeOutMusic`
stops the SFX tracks before it walks any track
(s1.sounddriver.asm:1361-1362), and nothing between the pre-service point and
the dispatch point reads SFX state, so the stops moved back to pre-service and
only the fade arming -- which is what the fade step's position actually decides
-- stays at the dispatch point. Window 81 is the first real occurrence of a
driver command other than `$E0`: `$E4`, `StopAllSound`. The host throws rather
than ignoring it, which is why it is visible; routing it is now evidence-backed
work rather than speculation.

**Gates.** 192 tests, 0 failures, 0 errors, 2 skips, against the merged develop.
All three published pins unchanged.


## 2026-09-04 - Tick 360 is two VBlank calls a frame apart, and the obvious contract fix is wrong

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `2ea323bbd`.
- **Result unchanged:** `$8E` still `GLOBAL_STATE_MISMATCH` tick 360,
  `tempo_timeout`, reference `1` against engine `2`. **A candidate fix was
  built, measured, and reverted.**

**What tick 360 actually is.** Logging every `UpdateMusic` entry inside the
recorded invocation, with the caller's return address read off the stack, gives
two entries: frames 6,203 and 6,204, both from `VBlank_Music`
(caller return `$B64`, sonic.asm:682), both at stack `$FFFDB2`. So it is one
call site on two consecutive frames, not the HBlank path, and not two entries
inside one frame. The first invocation's return was never observed, and the
shared lifecycle folds a same-stack entry into the one already open, so two
frames of driver work became one recorded tick with two tempo decrements.

**The candidate, and why it looked right.** The lifecycle's same-stack fold
exists for the DAC-busy retry, where `bra.s UpdateMusic`
(s1.sounddriver.asm:165) genuinely re-executes the entry address at the same
stack. That retry always happens within the frame it started in, so the
emulator frame looked like a clean discriminator: same stack *and* same frame is
a retry, same stack a frame later is a new invocation.

**It is wrong, and the measurement said so immediately.** Treating the later
entry as a new invocation abandons the earlier one, which drops driver work the
ROM really did. Two things broke at once. `$8E` went from diverging at tick 360
to diverging at **tick 0**, on the first chip write. And the GHZ window stopped
being byte-identical to the committed
`s1-gameplay-ghz1-run2-reference.v1` fixture, which is a reproducibility
property worth more than this frontier. Reverted.

**Why neither folding nor dropping is right.** The ROM ran its driver twice and
the record has one tick. Folding attributes both passes' effects to one tick,
which is what the reference does today and is at least faithful about the
resulting RAM. Dropping discards a pass outright. Representing it properly needs
two ticks, which needs the first invocation's end to be observable, and it is
not. The remaining option is the one already recorded: carry the per-tick driver
pass count and have the host service that many times, which is the hard rule 4
question, not a lane's to settle.

**Reproducibility re-verified after the revert.** A third independent capture
reproduces all three published windows byte for byte, and the GHZ window's
identity with the committed gameplay fixture. Three captures now agree.


## 2026-09-04 - The every-toggle noise clock doubles the shift rate, and is still not the fault

- **Worktree/branch:** `.worktrees/s3k-issue-coverage`,
  `feature/ai-s3k-aiz1-audio-issue-coverage`.
- **Why.** Three of the four reported AIZ1 audio faults are noise-form effects.
  Every S3K effect that carries a PSG form declares `$E7`, white noise clocked
  from PSG3's tone period, and the one effect nobody has complained about, the
  jump (`$62`), is a plain tone on PSG1 with no form byte at all.
- **Measurement.** Each effect's real per-frame PSG writes were captured from
  the driver and replayed into `PsgChip` clocked at the tick rate, counting
  LFSR shifts, against an independently written SN76489 noise generator
  following the libvgm rule of one shift per rising edge.

  | Effect | Independent oracle | `everyToggle=false` | `everyToggle=true` | Ratio |
  |---|---|---|---|---|
  | Splash `$39` | 4,522 | 4,523 | 9,044 | 2.000 |
  | Insta-shield `$42` | 11,408 | 11,408 | 22,815 | 2.000 |
  | Collapse `$59` | 2,165 | 2,166 | 4,330 | 1.999 |

  The hardware-rule mode matches the independent oracle, exactly for the
  insta-shield and within a single shift for the other two, which is a
  start-of-run counter phase and not a rule difference. The every-toggle mode
  shifts twice as often for all three. The comparison can disagree and does,
  by a factor of two, so it is not measuring itself.
- **It is not the reported fault, though.** A field test on 2026-09-04 set
  `audio.psgNoiseShiftEveryToggle=false` for the reporter, who had `true`, and
  none of the four reported faults changed. So the shipped default is a real
  divergence from the hardware rule and worth removing on its own merits, but
  it is not what anyone is hearing. The pattern fit was strong and wrong, which
  is why the field test and not the pattern is what settles it.
- **Config.** `src/main/resources/config.yaml` ships
  `audio.psgNoiseShiftEveryToggle: true`; the repository-root `config.yaml`
  sets it `false`. `PsgChip`'s own default is the hardware rule, and
  `TestPsgChipHardwareBehaviour.everyToggleModeShiftsExactlyTwiceAsOften`
  already pins the 2x relationship at the chip level. Audit CHIP-02 in
  `docs/architecture/audits/audio/2026-08-30-smps-behaviour-claims-digest.md`
  records this residual default. Removal is owned by the `psg-noise-toggle`
  lane; nothing in this lane depends on the key.
- **Separately, a driver-side ordering defect.** `cfSetPSGNoise` puts `0DFh`
  and the noise operand on the bus back to back (`Sound/Z80 Sound
  Driver.asm:3558-3571`, `fix_sndbugs = 0`). The engine emits `DF FF E7`,
  because `SmpsDriver.silencePsgChannel` runs the SFX channel takeover lazily
  on the first latch of the stolen channel and the `0E7h` write is that latch.
  Recorded in `docs/S3K_KNOWN_DISCREPANCIES.md` and covered by
  `TestS3kNoiseFormEffectWriteStream`, which is `@Disabled` against that entry.
  Not fixed here: the reorder is in shared cross-game driver code and the
  hardware oracle cannot yet arbitrate it, since its S3K frontier is service
  565 and the first splash request in the captured window is service 4,087.

## 2026-09-04 - A 16,000-frame AIZ1 window captures splash and collapse, but the frontier is 565

- **Worktree/branch:** `.worktrees/s3k-issue-coverage`,
  `feature/ai-s3k-aiz1-audio-issue-coverage`, over `develop` at `319d777de`.
- **Purpose.** Four user-reported S3K AIZ1 audio faults need an arbiter: the
  insta-shield silent, the water splash wrong, the collapsing bridge wrong, and
  a note held too long after the AIZ1 Knuckles cutscene.
- **Capture.** Same producer, movie and power-on epoch as the committed intro
  reference, with the window extended from 5,400 to 16,000 movie frames:

  ```
  OGGF_BIZHAWK_STOCK=<stock 2.11> \
  OGGF_OBSERVER_CORE=<lock-matching gpgx.wbx.zst> \
  OGGF_WORKDIR=<scratch> OGGF_OUT=<out.jsonl> OGGF_FRAMES=16000 \
    tools/audio/run_s3k_audio_oracle_reference_v2.sh
  ```

  15,832 completed services over 16,000 frames, 68,573,797 bytes raw and
  4,183,143 gzipped. Two serial captures to separate scratch roots were
  byte-identical at sha256
  `7747071cbbf8dfaf877e8c537e9c9d97bd3d29ba630ff952523002f8c3f2c00a`.
- **Observer core.** The lock-matching build, verified against
  `artifact-lock.json` on all five recorded values: patch `2e1d1e59...`,
  decompressed core `177fb4b0...`, compressed core `25ee305d...`, build id
  `9ded8f477abe193d`, observer identity `95188e3c...`. The separate ABI-5
  install carrying patch `77ba1eab...` is a different build and was not used.
- **Overlap check: byte-identical.** All 5,263 tick rows of the committed
  `s3k-aiz1-intro-reference-v2.jsonl.gz` appear verbatim as the first 5,263
  tick rows of the wider capture. The first differing line is the trailer, and
  the metadata differs only in its `frames` field.
- **Result, no sidecar:** `EVENT_MISSING`, tick 128, field `decoded_write`,
  event 0, reference `ym2612[port 1, register 0x82] = 255` against
  `<missing>`. This is the known frame-entry mailbox limitation, not a new one.
- **Result, committed sidecar supplied as `--requests`:**
  `EVENT_VALUE_DIFFERENT`, tick 565, field `decoded_write`, event 0, reference
  `ym2612[port 0, register 0x28] = 4` against engine
  `ym2612[port 0, register 0xA4] = 18`. DAC stream `BYTE_DIFFERENT` run 338
  byte 0, reference `0x88` against `0x7F`. That reproduces the
  `bugfix/ai-s3k-oracle-freq-resend` frontier exactly, from a different
  worktree and a different reference file.
- **Why the window does not yet arbitrate the four faults.** The frontier is
  service 565 and the first splash request in this movie is service 4,087. The
  effects sit far beyond where the comparison currently reaches.

  | Effect | Request | Occurrences in window | First service |
  |---|---|---|---|
  | Splash | `sfx_Splash` 0x39 | 37 | 4,087 |
  | Collapse | `sfx_Collapse` 0x59 | 24 | 1,569 |
  | Insta-shield | `sfx_InstaAttack` 0x42 | 0 | never |

- **Two structural gaps, not frontier distance.** The insta-shield is never
  requested anywhere in `s3k-complete-sonic-tails.bk2`, so no window over this
  movie can cover it; that fault needs a recording in which Sonic double-jumps
  without a shield. And no music request appears in the mailbox stream at all,
  only the `E1h` fades and one `FFh`, because `mailbox_sampling` is
  `frame_entry`. The AIZ1 Knuckles cutscene is identified by its `mus_Knuckles`
  (`$1F`) request, so locating and covering that fault needs the
  request-observation producer re-run over the wider window.
- **Not published.** The capture is validated and duplicate-identical, but its
  only content beyond the committed intro reference is services 5,264-15,832,
  which the comparison cannot reach while the frontier is 565. Holding the
  4.2 MB fixture out of git history until the frontier is close enough to use
  it.

## 2026-09-04 - S3K service 751 is a music change, and the engine goes quiet just as the ROM gets busy

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, engine at `0da7b645a`.
- **Measurement, not a comparison run.** No engine behaviour changed. This
  records what tick 751 actually is, because the single-event frontier line
  had me looking at the wrong thing for a cycle.
- **What the frontier line says, and why it misleads.** The comparator reports
  `EVENT_VALUE_DIFFERENT` at tick 751 event 0: reference
  `ym2612 port 1 register 165 value 80` against engine
  `port 1 register 130 value 255`. That reads like one wrong write. It is not.
  Tick 751 carries 274 reference writes, and the two sides are not comparing
  the same moment at all.
- **Tick 751 is a music change.** The reference's writes there are
  unmistakably the six-channel loop of `zStopAllSound`
  (`Sound/Z80 Sound Driver.asm:2476-2498`) on the `fix_sndbugs = 0` branch:
  per channel, D1L/RR of `FFh` on all four operators, total level of `7Fh` on
  all four, a key off on register 28h, then the four SSG-EG registers cleared
  to zero. Register 165 and 161 are the frequency high and low bytes for
  linear FM4, sent immediately before that loop begins.
- **The engine runs it one service early, and much shorter.** Writes per
  service, engine against reference:

  | Service | 747 | 748 | 749 | 750 | 751 | 752 | 753 | 754 |
  |---|---|---|---|---|---|---|---|---|
  | Reference | 28 | 8 | 8 | 8 | 274 | 297 | 270 | 263 |
  | Engine | 28 | 8 | 8 | 89 | 177 | 10 | 10 | 10 |

  The two agree exactly through 749. The engine then begins its stop at 750,
  a whole service before the ROM does, and its own tick 751 opens with the
  *tail* of that stop - a key off of channel 6 and register 27h set to zero,
  which is `zFM3NormalMode` at the very end of `zStopAllSound` (:2506-2513).
- **Correction to the paragraph this replaces.** I first wrote that the engine
  was missing almost all of the new song's servicing, on the strength of the
  reference sustaining 250 to 300 writes per service from 751 onward against
  the engine's ten. That was wrong, and wrong in the way the known-discrepancy
  note about the DAC partition warns about: those reference counts are almost
  entirely 2Ah DAC bytes. The previous song had no DAC track playing, the new
  one does, so the DAC pump starts at 751 and inflates every count after it.
  Excluding 2Ah, the reference services 20, 19, 19, 23 and 13 writes across
  752 to 756 against the engine's 10, 10, 10, 14 and 9. Comparable, and no
  collapse. The engine also loads the song correctly: a probe of the music
  sequencer at 751 shows music id 01h with all nine tracks present, active and
  counting their durations down normally.
- **So the whole finding is the one-service phase error.** On non-DAC writes
  the two sides agree exactly through 749. The reference then spends 274
  writes on the stop in service 751 alone. The engine spends 89 in 750 and 177
  in 751, which is the same work started a service early and split across two.
- **Where the service is chosen.** The music change is not mailbox-driven
  here: the reference's frame-entry mailbox is empty at every service from 746
  to 754. It comes from the request sidecar, whose row 876 carries music id
  01h, and `S3kAudioReferenceReader.resolveSidecarRequests` decides which
  service consumed it. That method has three branches; the one that fires when
  the following service's entry sample is clear attributes the request to the
  *previous* service, on the reasoning that the service running in the store's
  own row must already have consumed and cleared the byte. For this request
  that lands on service 750, which the reference shows doing nothing unusual
  at all, only its ordinary eight writes.
- **What the next cycle should do.** Settle that branch against the ROM before
  changing anything. This is fixture attribution rather than engine code, so a
  change to it moves the oracle's input and needs the reference's own writes as
  the arbiter: whichever service the ROM spends 274 writes in is the service
  that consumed the request, and here that is unambiguously 751. Check the
  branch against the other thirteen observations in the sidecar before
  concluding it is wrong in general rather than wrong here.
- **Ruled out this cycle.** `forceSilence` is not the source of the engine's
  `82h = FFh`: a probe on that method across services 748 to 754 never fired.
  The write comes from the engine's own stop-all loop. The engine emits no 2Ah
  bytes at all in this window, so its non-DAC counts above are also its total
  counts; that silence belongs to the separate DAC-stream frontier at run 338.
- **Gates.** Not re-run; no engine behaviour changed since `0da7b645a`, whose
  gates are recorded in the entry below.


## 2026-09-04 - S3K SFX lifecycle: two missing key offs the intro oracle cannot see

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`.
- **Why this is not an oracle cycle.** The AIZ1 intro capture is music only,
  so no SFX is ever admitted in it. Three conditions the branch's own SFX work
  changed are therefore invisible to it, and the lead made them merge
  blockers. New ROM-gated class `TestS3kSfxLifecycleRom` drives them through
  the runtime request path (`AudioManager.playSfx` / `presentFrame`) and
  asserts the chip writes, with the owning ROM routine cited on each test.
- **Defect 1: an SFX taking an FM channel from another SFX sent no key off.**
  `zSFXTrackInitLoop` keys off each incoming SFX track and clears its SSG-EG
  operators while the sound loads (`Sound/Z80 Sound Driver.asm:2092-2103`);
  nothing in that loop asks who holds the channel. The engine emitted those
  writes as the driver rather than as a track, so `SmpsDriver.writeFm` dropped
  them whenever the channel was already locked - exactly the takeover case
  where the key off is audible. Measured with SPLASH (PSG2 + FM4) followed by
  GRAB (FM4): the takeover service produced `psg[C5], psg[01]` and no FM write
  at all. They now go out through a new `writeRawFm`, matching the ROM, which
  writes through `zWriteFMI` with no ownership test.
- **Defect 2: an SFX torn down wholesale never keyed off its FM note.**
  `cfStopTrack` clears the playing flag and then sends `zKeyOffIfActive`
  before any voice restore (`:3443-3449`). A sequencer removed by
  `stopAllSfx` runs no coordination flag, and S3K's `ROM_VOICE_RESTORE` release
  mode had replaced the old force-silence with nothing at all, so the note
  kept sounding on the channel just handed back. `releaseLocks` now issues the
  key off the flag would have sent, guarded by the same `overridden` test
  `zKeyOffIfActive` applies. Measured: `stopAllSfx` on SPLASH produced only
  `psg[DF]`; it now produces `ym0[28]=05, psg[DF]`.
- **Both fixes proven load-bearing.** Reverting `SmpsDriver.java` alone turns
  the two new tests red with the pre-fix write streams quoted in the failure
  messages. The walk-before-judged-finished test is load-bearing on
  `sfxWalkPrecedesRequest`: flipping that flag false makes the admitting
  service reach the chip with the insta-shield's opening PSG writes, which the
  ROM ordering forbids.
- **The per-track handback test is a regression guard, not an ablated one.**
  It asserts SPLASH's FM4 track hands its channel back to the music while the
  PSG2 track keeps playing, which discriminates against whole-sequencer
  handback by construction. Two ablations aimed at its mechanism did not move
  it: neither disabling the F2 `releaseChannelToMusic` call nor short-circuiting
  `reconcileInactiveSfxTracks` changed the result, so the clear comes from a
  third path I did not identify. Recorded here so the next round does not
  re-spend those two readings.
- **Frontier unchanged.** The S3K service stream is still
  `EVENT_VALUE_DIFFERENT` at tick 751, event 0, reference
  `ym2612 port 1 register 165 value 80` against engine
  `port 1 register 130 value 255`. The DAC byte stream is still
  `BYTE_DIFFERENT` at run 338, byte 0, reference `88h` against engine `7Fh`.
  Both measured after `mvn clean package`, so the build was not stale.
- **Gates, all green.** Audio, per-game audio and parity packages with all
  three ROM paths: 2,696 tests, 0 failures, 16 skips. That run includes the
  oracle-backed classes, which all passed: `TestS1GameplayAudioDriverOracle`,
  `TestS1GameplayRun2AudioDriverOracle`, `TestS2DriverStateOracle`,
  `TestS2CpzDriverStateOracle`, `TestS2PublishedRequestWindows`,
  `TestS2RequestAwareOracleRawStream`, `TestS3kOracleRequestSidecarWiring` and
  both S3K fixture contracts.
- **What was not measured.** I could not reconstruct the standalone
  `S1AudioParityTool` and `S2AudioOracleTool` command lines in this worktree:
  the S1 tool rejected every reference path as not a direct child of its
  validated run root, and the S2 tool reported a changed fixture digest for
  each committed stream. Those are failures of my invocation, not results; the
  JUnit classes above cover the same comparisons and did run.


## 2026-09-04 - Live-regression risk audit of this branch, and one defect fixed

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, unmerged. Clean build before every
  measurement.
- **Why this entry exists.** The owner reproduced in-game S3K breakage on
  develop and a bisect lane asked which of this branch's changes could stop a
  music track or cut an SFX under live `AudioManager` conditions that the
  stimulus-replay oracle never exercises. The oracle drives 5,263 services of
  one intro window with a fixed request list; it sees no malformed track data,
  no sequencer reaping, no SFX replacement and no live fade interaction.
- **Confirmed defect, introduced by this branch and fixed here.**
  `trackEndFlagOwnsTheStop` removed the read loop's blanket
  `if (!t.active) stopNote(t)` for S3K, on the correct ground that the two
  track-end flags stop the note themselves. But five *other* S3K sites
  deactivate a track without stopping it, and all five lost their key-off:
  `0F9h` return with an empty return stack; an unreadable jump pointer; an
  unreadable loop-exit pointer; a gosub with an unreadable pointer or a full
  return stack; and a loop with no jump target. With the stop gone, any of
  them leaves the channel sounding indefinitely, which presents as a note held
  for ever. None occurs in the oracle window. All five now stop the note
  through one helper that says why: the shipped driver never reaches these
  states, so there is no ROM behaviour to model, only a channel that must not
  keep sounding.
- **Remaining risks on this branch, ranked, with the condition that would
  expose each.** None is a known defect; each is a place where the oracle
  cannot see and the live path differs.
  1. *`FmSfxReleaseMode.ROM_VOICE_RESTORE`* removed the force-silence from the
     driver's release sweep. The key-off now comes from the track-end flag, so
     a release that happens **without** that flag — a sequencer reaped, an SFX
     replaced by a new request, a stop-all — leaves no silence at all on that
     channel. Live SFX interruption is exactly that path.
  2. *`releaseChannelToMusic` from the `0F2h` flag* drops the channel lock as
     soon as one SFX track ends. Faithful to `cfStopTrack`, which is per
     track, but the engine's lock is per channel and per sequencer: a
     multi-track SFX that ends one track early now returns that channel to
     music while its other tracks still play. Could cut an SFX short.
  3. *`sfxWalkPrecedesRequest`* gives an admitted SFX no walk in its own
     service. Anything that services once and then tests completion sees an
     unstarted SFX; a very short one could be judged finished before it plays.
  4. *`trackEndFlagOwnsTheStop`* itself, beyond the five sites fixed above:
     any future path that clears `active` without stopping inherits the same
     hole.
  5. *`restTrack`'s fall-through into the PSG silence* fires on every pass
     while a track is parked on an envelope rest command. It is guarded on the
     override bit, so it should not touch a channel an SFX owns, but it does
     make the music silence its own PSG channel far more often than before.
  The fade work is the one to check against "abrupt un-faded music changes":
  this branch made the fade real rather than a no-op, so a request that
  previously produced no fade now produces one, and the reverse is not
  possible.
- **Frontier unchanged** at `EVENT_VALUE_DIFFERENT`, tick 751, event 0, and
  the DAC stream at run 338 byte 0, since none of the five sites is reached in
  the oracle window.
- **Gates at this commit, all green.** 2,159 tests, 0 failures, 10 skips. S1
  sound test `MATCH (14690 ticks)` and `MATCH (1967 ticks)`; all S2 lines
  unchanged including the CPZ state-and-writes count of 36 of 719.


## 2026-09-04 - The track-end flag hands the channel back itself; tick 590 -> tick 751

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, unmerged by design. Clean build before
  every measurement.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 590, event 1, reference
  `ym2612 port 1 register 0B4h = 128` against engine
  `port 0 register 0A4h = 19`. DAC stream `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 751, event 0, reference
  `ym2612 port 1 register 0A5h = 50` against engine
  `port 1 register 82h = 255`. DAC stream unchanged. **A hundred and
  sixty-one more services agree.**
- **The routine, read to its end this time.** `cfStopTrack` does not finish at
  the key-off. It clears the overridden music track's bit, and then, for an FM
  track, writes the FM3 settings if the channel is FM3 and sends that music
  track's instrument through `zSendFMInstrument`, followed by its SSG-EG
  (Sound/Z80 Sound Driver.asm:3059-3086). The whole tail is gated on
  `zUpdatingSFX`, so only an SFX track's end restores anything (:3050-3054).
  All of it happens inside the flag, during the SFX update, which
  `zUpdateEverything` runs before the music update (:650-701) — so the restore
  precedes that service's music writes.
- **The engine had the right writes in the wrong place.** They came from the
  driver's post-service release sweep, through `updateOverrides` and
  `refreshInstrument`, which lands them after the music tracks have already
  written. The flag now hands the channel back directly:
  `CoordFlagContext.releaseChannelToMusic` reaches the driver, which drops the
  lock and clears the override for that one channel, and the S3K `0F2h`
  handler calls it right after its key-off. The driver's own SFX test mirrors
  the ROM's `zUpdatingSFX` gate.
- **This is the fix the previous cycle looked for and missed.** That cycle
  routed the same intent through `reconcileInactiveSfxTracks`, whose guard is
  about whether the sequencer still holds an active track on the channel, and
  measured no change. The guard was the wrong question: the ROM does not ask
  it, it simply releases the channel the ending track owned.
- **S1 and S2 untouched by construction and by measurement.** The new hook is
  a default no-op on both interfaces and is called only from the S3K flag
  handler. S1 sound test `MATCH (14690 ticks)` and `MATCH (1967 ticks)`; S2 v1
  `MATCH (698 ticks)`, v2 state-and-writes and state-only `MATCH (2198
  ticks)`, CPZ state-only `MATCH (720 ticks)`, request windows `MATCH` at 25,
  52 and 27, and the CPZ state-and-writes line unchanged at 36 of 719.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG silence shape;
  and S1's and S2's track-end stop.
- **Gates at this commit, all green.** 2,159 tests, 0 failures, 10 skips.


## 2026-09-04 - Service 590 attributed: the music voice restore lands after the music

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, unmerged by design. **Clean build before
  every measurement in this entry**, after the stale-build error two cycles
  ago.
- **Command:** unchanged, both result lines.
- **Result, unchanged by this cycle:** `EVENT_VALUE_DIFFERENT`, tick 590,
  event 1, reference `ym2612 port 1 register 0B4h = 128` against engine
  `port 0 register 0A4h = 19`. DAC stream `BYTE_DIFFERENT` run 338 byte 0. No
  engine change landed; what this cycle produced is the attribution and one
  rejected fix.
- **What service 590 is.** The reference emits the SFX FM4 track's key-off and
  then, immediately, the music's voice restore for that channel: `0B4h`,
  `0B0h`, then the whole operator block. The engine emits the same key-off,
  then the music tracks' own frequency and PSG writes, and only afterwards the
  voice restore. Same writes, wrong order.
- **Both writes attributed with the observer probe.** The key-off comes from
  the `0F2h` track-end handler through `stopNote`, which is correct and
  matches. The `0B4h` comes from `updateOverrides` calling
  `setChannelOverridden`, then `refreshInstrument` and `applyFmPanAmsFms` —
  the driver's post-service release sweep. The ROM does that work inline:
  `cfStopTrack` clears the overridden music track's bit and restores its voice
  as part of the flag itself (Sound/Z80 Sound Driver.asm:3059-3070), inside
  the SFX update, which `zUpdateEverything` runs before the music update
  (:650-701). So in the ROM the restore precedes that service's music writes.
- **A fix was tried and rejected because it does nothing.** The host already
  exposes `reconcileInactiveSfxTracks`, so the obvious move is to call it from
  the track-end flag through a new `CoordFlagContext` hook. Implemented and
  measured: the write order at 590 is byte-for-byte unchanged, so the
  reconcile does not release the channel at that point. Its per-channel guard
  requires the sequencer to hold no active track on that channel, and
  something still satisfies that test when the flag runs. Reverted rather than
  left in as an inert hook with a citation implying it works.
- **Next step, stated concretely.** Find why
  `reconcileInactiveSfxTracks` declines the release immediately after the
  track-end flag clears the track's active bit — most likely `hasActiveTrack`
  or the lock ownership test — and make the release happen at the flag rather
  than in the post-service sweep. The write content already matches; only its
  position in the service is wrong.
- **S1 and S2 untouched this cycle:** no source change landed.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG silence shape;
  and S1's and S2's track-end stop.


## 2026-09-04 - One track end, one key-off; tick 588 -> tick 590

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, unmerged by design.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 588, event 3, reference
  `ym2612 port 1 register 0B5h = 64` against engine `port 0 register 28h = 5`.
  DAC stream `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 590, event 1, reference
  `ym2612 port 1 register 0B4h = 128` against engine
  `port 0 register 0A4h = 19`. DAC stream unchanged.
- **The attribution took three probes and corrected a stale measurement.**
  A stack probe on `forceSilence` never fired, and one on the driver's own
  `writeFm` never fired either, because those writes reach the chip through
  the session's synthesizer rather than the driver method. A third probe, on
  the capture's own chip-write observer, caught the byte at service **566**,
  not 588 — and re-dumping both services showed why: the release-rate and
  total-level block the previous entry chased was already gone, removed by
  that entry's own two config corrections. Its claim that they "changed
  nothing" was read off a stale build, and the previous entry is corrected in
  place. Service 566 carries that block in the reference too, and matches.
- **What was actually left at 588 was a duplicated key-off.** The observer
  probe, pointed at `28h = 5`, caught two writes from two different callers:
  the `0F2h` track-end flag handler, and the blanket
  `if (!t.active) stopNote(t)` after the stream-read loop. The ROM stops once:
  `cfStopTrack` calls `zKeyOffIfActive` as it clears the playing bit
  (Sound/Z80 Sound Driver.asm:3040-3046) and nothing follows it.
- **Scoped to S3K after S2 said no.** Removing the blanket stop for every
  driver took the S2 driver-state oracle from `MATCH (2198 ticks)` to a
  missing write at tick 207, and pushed the CPZ divergent count from 36 to 45.
  S1 and S2 therefore keep it, and the new `trackEndFlagOwnsTheStop` names why:
  their handlers do not all stop the note themselves, which makes their
  track-end paths unaudited rather than known-equal. With the flag applied to
  S3K alone, every S2 line returns to its previous value including the CPZ
  count of 36.
- **The data-exhausted safety stop moved rather than vanished.** Running off
  the end of a stream has no ROM counterpart, since every stream ends with a
  track-end flag, so that stop now sits in the exhaustion branch itself where
  it cannot double up.
- **Two write-list snapshots re-pinned.** `TestSonic3kFm3SpecialMode`'s two
  `F3` tests assert the PSG write list as incidental context around
  raw-operand retention, and each carried one cleanup pair too many. They now
  assert the single cleanup, with the `cfStopTrack` citation.
- **S1 and S2 read by content.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; S2 v1 `MATCH (698 ticks)`, v2 state-and-writes and
  state-only `MATCH (2198 ticks)`, CPZ state-only `MATCH (720 ticks)`,
  request windows `MATCH` at 25, 52 and 27.
- **Open items.** S2's `zNoteFillUpdate` countdown; S1 and S2's post-note
  do-not-attack clear; the `.dac_playback_loop` cycle total of 303 against
  `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG silence shape;
  and now S1's and S2's track-end stop, which this cycle showed the engine
  depends on but no listing has been read for.
- **Gates at this commit, all green.** 2,159 tests, 0 failures, 10 skips.


## 2026-09-04 - The PSG SSG-EG clear writes nothing, and 588 is a force-silence with no ROM caller

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, unmerged by design.
- **Command:** unchanged, both result lines.
- **Result before and after:** `EVENT_VALUE_DIFFERENT`, tick 588, event 3,
  reference `ym2612 port 1 register 0B5h = 64` against engine
  `port 0 register 28h = 5`; DAC stream `BYTE_DIFFERENT` run 338 byte 0. The
  frontier did not move. What landed is a correction to the previous entry's
  reasoning and two cited config corrections that are inert on this fixture.
- **The previous entry was wrong to call the PSG case unmodelled, and the
  `FixBugs` rule is what settles it.** `fix_sndbugs = 0` is the shipped branch
  (skdisasm/sonic3k.asm:38) and it does call `zFMClearSSGEGOps` for PSG tracks,
  which the listing flags with its own "(even on PSG tracks!!!)" note
  (:2099). But every one of that loop's writes goes through
  `zWriteFMIorII`, which opens with `bit 7,(ix+zTrack.VoiceControl) / ret nz`
  and returns before touching the chip for any PSG track (:2549-2551). So the
  PSG call is a wasted call, not a write, and the fixed branch merely tests
  that bit at the call site to skip it. The observable stream is identical on
  both branches. The FM-only implementation is therefore complete rather than
  partial, and the comment now says so with the flag named, the branch taken
  and what the fixed branch would do.
- **Two further guards from the same routines are now modelled.**
  `zWriteFMIorII` also returns on `PlaybackControl` bit 2, the SFX-overriding
  bit (:2552-2553), and `zKeyOffIfActive` returns on that bit or the
  do-not-attack bit (:3338-3341). Both are inert for a freshly loaded SFX
  track, which is why the frontier is unchanged, and both are cited.
- **Two config corrections, cited and inert here.** S3K now uses
  `FmSfxTakeoverMode.REGISTER_SEQUENCE`, because `zSFXTrackInitLoop`'s only
  chip writes are the key-off and the SSG-EG clear (:2092-2103) and the
  engine's legacy takeover additionally forced RR and TL on the channel. And
  `FmSfxReleaseMode.ROM_VOICE_RESTORE`, because `cfStopTrack` releases a
  channel by keying it off, clearing the music track's override bit and
  restoring its voice (:3040-3070), never force-silencing;
  `zFMSilenceChannel` is reached only from `zInitAudioDriver`'s boot loop
  (:2475-2495) and from the track's own `0F2h` flag, `cfSilenceStopTrack`
  (:3082-3096). **Correction, made the next cycle:** the "neither changed a
  byte" claim in this entry was wrong. It came from a `WriteDump` run against
  a stale build. Both modes together removed the whole
  `ym1[81h] [89h] [85h] [8Dh] = 0FFh` and `ym1[41h] [49h] [45h] [4Dh] = 07Fh`
  block from service 588; what they did not do is move the *first* divergence
  index, which is why the frontier line looked unchanged.
- **Service 588, decoded but not closed.** The reference is
  `ym1[0A4h] [0A0h]`, one `ym0[28h] = 5`, then straight into the voice load
  at `ym1[0B5h]`. The engine inserts, between the key-off and the voice load,
  a second `ym0[28h] = 5` followed by `ym1[81h] [89h] [85h] [8Dh] = 0FFh` and
  `ym1[41h] [49h] [45h] [4Dh] = 07Fh`. That is
  `SmpsSequencer.forceSilence`'s release-rate and total-level block. It is not
  reached through the takeover or release modes corrected above, both of which
  I changed and re-measured without effect, so the caller is one of the two
  remaining `forceSilence` sites in `SmpsDriver` around lines 2555 and 2565,
  which are a different release path. S3K has no ROM counterpart for it at
  this point: the two routines the engine's comment cites,
  `zSetMaxRelRate` and `zFMSilenceChannel`, are called only at boot and from
  the `0F2h` flag. Tracing which of those two sites fires here is the next
  step.
- **S1 and S2 unchanged, read by content.** S1 sound test `MATCH (14690
  ticks)` and `MATCH (1967 ticks)`; S2 v1 `MATCH (698 ticks)`, v2
  state-and-writes and state-only `MATCH (2198 ticks)`, CPZ state-only
  `MATCH (720 ticks)`, request windows `MATCH` at 25, 52 and 27. The CPZ
  state-and-writes line sits unchanged at its own frontier.
- **Open items.** S2's `zNoteFillUpdate` countdown; S1 and S2's post-note
  do-not-attack clear; the `.dac_playback_loop` cycle total of 303 against
  `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape. The SSG-EG-on-PSG item leaves the list: there is nothing to model.
- **Gates at this commit, all green.** 2,159 tests, 0 failures, 10 skips
  across the audio packages and the keep-green set.


## 2026-09-04 - The SFX load keys off and clears SSG-EG; tick 565 -> tick 588

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `981aece96`. From this
  cycle the branch stays unmerged until the AIZ1 oracle is MATCH end to end,
  so the frontier log lives here.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 565, event 0, reference
  `ym2612 port 0 register 28h = 4` against engine `port 0 register 0A4h = 18`.
  DAC stream `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 588, event 3, reference
  `ym2612 port 1 register 0B5h = 64` against engine `port 0 register 28h = 5`.
  DAC stream unchanged. Twenty-three more services agree.
- **The routine.** `zSFXTrackInitLoop` calls `zKeyOffIfActive` and then
  `zFMClearSSGEGOps` for every SFX track it initialises, while the SFX is
  still being loaded (Sound/Z80 Sound Driver.asm:2092-2103). The clear writes
  `90h` and the three operator registers above it with zero (:2528-2536). The
  reference's service 565 opens with exactly that, twice: key off FM4 then
  `ym1[90h] [94h] [98h] [9Ch] = 0`, key off FM5 then
  `ym1[91h] [95h] [99h] [9Dh] = 0`, and only afterwards the music's own
  writes. The engine emitted none of it.
- **One half deliberately not modelled, and it is the ROM's own bug.** Under
  `fix_sndbugs = 0` the SSG-EG clear runs for PSG tracks too, which the
  listing flags with its own "(even on PSG tracks!!!)" note (:2099). A PSG
  track's `VoiceControl` selects a nonsense YM channel there. No committed
  fixture exercises it, so modelling it would be a guess; the FM case is
  implemented and the PSG case is written down in the config's own comment.
- **S1 and S2 unchanged, read by content.** S1 sound test `MATCH (14690
  ticks)` and `MATCH (1967 ticks)`. S2 v1 `MATCH (698 ticks)`, v2
  state-and-writes and state-only `MATCH (2198 ticks)`, CPZ state-only
  `MATCH (720 ticks)`, request windows `MATCH` at 25, 52 and 27. The CPZ
  state-and-writes line is unchanged at its own frontier, tick 237.
- **Open items.** S2's `zNoteFillUpdate` countdown; S1 and S2's post-note
  do-not-attack clear; the `.dac_playback_loop` cycle total of 303 against
  `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG silence shape;
  and now the SSG-EG clear on PSG tracks described above.
- **Gates at this commit, all green.** The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,159
  tests, 0 failures, 10 skips.

## 2026-09-04 - First per-song windows published: $8A green, $8E red with its frontier pinned

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `d617f1e1f`.
- **Command:** `LUA_BIN=lua5.4 mvn -Dmse=off -B -Dsonic1.rom.path=... -Dsonic2.rom.path=...
  -Ds3k.rom.path=... -Ds2.request.bk2.path=... "-Dtest=com/openggf/tools/audio/parity/**/*" test`

**Published, from `s1-complete-run.bk2`.**

| Fixture | Music | Ticks | Result |
|---|---|---|---|
| `runs/s1-complete-run/w000-id8A.jsonl.gz` | `$8A` title screen | 72 | `MATCH` |
| `runs/s1-complete-run/w002-id8E.jsonl.gz` | `$8E` act clear | 567 | `GLOBAL_STATE_MISMATCH` tick 360, `tempo_timeout`, `1` against `2` |
| `runs/s1-complete-run/w003-id81.jsonl.gz` | `$81` GHZ | 1 | `MATCH` |

79,903 gzipped bytes for all three. Two serial captures, `cmp`-identical per
window before publication.

**Window 1 is deliberately not published.** Its ticks are byte-identical to the
committed `s1-gameplay-ghz1-run2-reference.v1` fixture, which
`TestS1GameplayRun2AudioDriverOracle` already covers at 5,257 ticks, so
committing it again would add about 870 KB for no new coverage.

**The red one is published on purpose.** A window with its frontier recorded
pins where the engine stops agreeing, so the next change either moves that point
or shows up as a regression. `$8E` agrees for 359 ticks before the tick the ROM
serviced its driver twice.

**Pins, not bare assertions.** `S1RunWindowPins` holds each window's expected
outcome as the one-line summary the oracle prints, so a window that moves in
*either* direction fails until both the table and this log are updated. A green
window cannot regress unnoticed and a frontier cannot move silently.
`$8A` additionally has its own named test asserting `Kind.MATCH` and the literal
72, because the first whole song outside GHZ to agree should name itself when it
breaks. A companion test corrupts a byte of a committed reference and requires
the comparator to report it at the corrupted tick, so a comparison that silently
stopped running is visible as such.

**The manifest carries the whole plan, not just what was published.** Both
movies' complete window lists are recorded -- 83 windows and 194,667
invocations for `s1-complete-run.bk2`, 101 and 224,123 for
`sonic1-complete-withemeralds.bk2` -- each with its music id, epoch frame, close
frame, invocation count, SFX count and whether it contains a re-entered
invocation, together with the exact command that regenerates any one of them.

**Gates.** 188 tests, 0 failures, 0 errors, 2 skips. Both S1 gameplay oracles
`MATCH` at 2,562 and 5,257 ticks; S2 v1 `MATCH (698 ticks)`; v2 both lines
`MATCH (2198 ticks)`; CPZ state-only `MATCH (720 ticks)`; request windows
`MATCH` at 25, 52 and 27.


## 2026-09-04 - Music $8E tick 360 measured, not argued: the ROM ran its driver twice in that frame

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `193561c6f`.
- **Result unchanged:** `GLOBAL_STATE_MISMATCH` tick 360, `tempo_timeout`,
  reference `1` against engine `2`. **Nothing landed. What changed is that the
  cause is now measured rather than reasoned about.**

**The measurement.** A debug-only hook on `UpdateMusic`'s `.driverinput`
(s1.sounddriver.asm:169, `loc_71B82`, verified by opcode `4df900fff000`,
`lea (v_snddriver_ram).l,a6`) counts how many times a recorded invocation gets
past the Z80/DAC wait loop to the point where the tempo is decremented and the
tracks are walked. Over the first four windows of `s1-complete-run.bk2`, 6,419
frames and several thousand invocations, **exactly one** invocation has a count
other than 1: window 2, ordinal 360, emulator frame 6,204, with **2 passes**.
That is the divergent tick, and it accounts for the whole discrepancy: two
passes decrement the tempo twice, which is the 3-to-1 step the previous entry
recorded, and everything after it is a constant one-step phase offset.

**It corrects my own earlier reasoning, which is why it was worth measuring.**
The previous entry ruled out the `.updateloop` DAC-busy retry on the grounds
that `bra.s UpdateMusic` returns above the tempo decrement. That much is right
-- a retry never reaches `.driverinput`, so retries cannot decrement twice --
but I had then treated "not the retry" as "not a second driver pass", and it is
a second driver pass. Two passes inside one entry-to-return pair means a second
`UpdateMusic` service ran within the invocation the probe recorded, at a stack
the lifecycle folded rather than flagged, since window 2 records no abandoned
invocations.

**What this means for the contract.** The replay host services the driver once
per recorded tick, so it cannot express a tick in which the ROM serviced twice.
This is the same shape as the re-entered invocation: a scheduling outcome of the
real hardware that frame-granularity state does not carry, and no constant can
absorb it honestly.

**The one option worth putting to a decision rather than taking.** The probe
could record the per-tick driver-pass count and the host could service that many
times. That carries no value and creates no work the engine did not already
have; it selects between two ROM loops that both exist, which is close to hard
rule 4's sanctioned per-row scheduling admission shape. But rule 4's exception
is written for the art-loading timing contract and the lag contract, and this
is neither, so extending it is a design decision and not a lane's to take.
**Recorded as an open question, deliberately not implemented.**


## 2026-09-04 - The dispatch point lands, and music $8A goes green: the first whole new song to match

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `1bc78c8ea`.
- **Fixture:** scratch capture of window 0 of `s1-complete-run.bk2`, music
  `$8A`, the title-screen song, 72 ticks.
- **Result before:** `GLOBAL_STATE_MISMATCH` tick 48, `fade_delay`, reference
  `3` against engine `2`.
- **Result after:** **`MATCH (72 ticks)`.** A song the oracle had never covered
  now agrees end to end.

**The ordering, from the ROM.** `UpdateMusic` steps a fade in progress
(s1.sounddriver.asm:179-186) and only then cycles the sound queue and calls
`PlaySoundID` (:197-202), before walking any track (:205 onward). So a fade
armed by a request in invocation N is not stepped until N+1: N's fade step has
already gone by. The parity host submitted every recorded request before the
whole frame service, and the engine's fade step and track walk both live inside
that service, so the engine stepped the fade in the invocation that armed it,
one step early.

**The seam.** `SmpsSequencer` now exposes a dispatch point that runs past the
fade step and before the track walk, and the host submits driver commands
there.

**Only the driver commands moved, and the reason matters.** Moving SFX
admission to the same point would have dropped a newly admitted SFX from the
frame's own walk, because the driver captures its sequencer-list size before
iterating, where the ROM walks an SFX admitted by `PlaySoundID` in that very
invocation. Nothing between the pre-service point and the dispatch point touches
SFX admission -- the fade step neither reads nor writes it -- so for an SFX the
two positions are observationally identical and the pre-service one is the
faithful one. The fade commands are the only requests the fade step interacts
with, so they are the only ones that had to move. **No compensating constant
was introduced**, which was the alternative and would have been a fitted one.

**Gates, all green and read by content.** The audio parity package, the wider
audio packages and both rewind coverage guards with three ROM paths and the S2
request BK2: 2,063 tests, 0 failures, 0 errors, 10 skips. Both S1 gameplay
oracles `MATCH` at 2,562 and 5,257 ticks; S2 v1 `MATCH (698 ticks)`; v2 both
lines `MATCH (2198 ticks)`; CPZ state-only `MATCH (720 ticks)` with its
state-and-writes companion unchanged at tick 237; request windows `MATCH` at
25, 52 and 27.

**Open.** Music `$8E` is unchanged at tick 360, the two-tempo-decrement
question recorded in the previous entry.


## 2026-09-04 - The tempo frontier was not tempo seeding: a finished S1 song stopped being serviced

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `d26d6a083`.
- **Fixture:** scratch capture of window 2 of `s1-complete-run.bk2`, music
  `$8E`, 567 ticks.
- **Result:** unchanged first divergence, `GLOBAL_STATE_MISMATCH` tick 360,
  `tempo_timeout`, reference `1` against engine `2`. **The frontier did not
  move, and what landed is still worth having:** the engine's tempo state after
  tick 360 was frozen and now runs.

**The hypothesis handed to this round was tempo-mode seeding. It was wrong, and
the trajectory said so.** Dumping both sides across the divergence shows every
track going inactive at tick 360 on *both* sides: `$8E` is the act-clear
jingle and it simply ends there. After that the reference's tempo timeout keeps
cycling 3, 2, 1 forever while the engine's sat at 2 for all 206 remaining ticks.
A value that stops changing is not a seeding error.

**The ROM reason.** `UpdateMusic` decrements `v_main_tempo_timeout` and reloads
it through `TempoWait` before it looks at any track, and unconditionally
(s1.sounddriver.asm:174-176, :1549-1560). The driver has no notion of a song
being over: its RAM persists and its tempo keeps running until a new song loads
or `StopAllSound` clears it. The engine's `SmpsSequencer.isComplete()` reports
a sequencer finished once every track is inactive, and both the service loop
and `reapCompletedSequencers` then remove it, after which nothing services it
at all. Guarding both removal paths for the direct-68k music sequencer makes the
tempo run again; SFX sequencers are untouched, because those genuinely are
released when they end.

**The residual, stated precisely so the next round does not re-derive it.**
With the tempo running, the two sides now cycle with the same period and differ
by a constant one-step phase. The reference's tick 360 carries **two** tempo
decrements: 359 reads 3, 360 reads 1, and there is no reload between them
(`tempo_reload` is 3 throughout and no tempo command is dispatched -- the only
dispatch at 360 is SFX `$CD`). So one recorded invocation at 360 contains two
`.driverinput` passes. It is **not** the `.updateloop` DAC-busy retry: that
`bra.s UpdateMusic` returns to the top of the routine, *before* the tempo
decrement (:147-165), so a retry cannot decrement twice. The open question is
which two calls those are, and answering it needs a probe that counts
`.driverinput` passes per recorded invocation rather than another round of
reading the listing.

**Gates, all green and read by content.** Audio parity package with three ROM
paths: 183 tests, 0 failures, 0 errors, 2 skips. The wider audio packages plus
the rewind coverage guards, `TestSmpsFadeAudioThroughput`,
`TestYm2612DacTiming` and the S3K audio classes: 1,921 tests, 0 failures, 0
errors, 8 skips -- run because a sequencer-lifetime change is exactly the kind
that breaks rewind coverage. Both S1 gameplay oracles `MATCH` at 2,562 and
5,257 ticks; S2 v1 `MATCH (698 ticks)`; v2 both lines `MATCH (2198 ticks)`; CPZ
state-only `MATCH (720 ticks)`; request windows `MATCH` at 25, 52 and 27.


## 2026-09-04 - The 1-up restore is a second window epoch; the first whole-run capture found it by aborting

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `a7e2dc866`.
- **Capture failure, recorded as such and not as a parity result.** The first
  full pass over `s1-complete-run.bk2` aborted at emulator frame 138,347, 212
  frames into window 58, with `ROM sequence pointer is outside the GHZ asset
  range` from the shared contract (`s1_audio_parity_contract.lua:318`). Windows
  0 through 57 completed normally.

**Why the window rule was incomplete.** Window 58's song is `$88`,
`bgm_ExtraLife`. The jingle ends at the `E4` coordination flag,
`cfFadeInToPrevious` (s1.sounddriver.asm:2166-2223), which restores the whole
of `v_1up_ram` -- every variable and every music track -- from the copy
`Sound_PlayBGM`'s 1-up branch made before it loaded the jingle (:776-784). It
issues **no** `Sound_PlayBGM` of its own. The window rule closed a window only
at a BGM dispatch, so the `$88` window ran through the restore and on into the
*previous* song's playback, whose sequence pointers lie outside `$88`'s asset
range. The contract asserts that pointer is in range, correctly, and the
capture stopped.

**The rule, corrected.** A window now closes at either ROM epoch that replaces
the music track RAM wholesale: a `Sound_PlayBGM` dispatch, or a
`cfFadeInToPrevious` restore. The window opening at a restore is normalized
against the song being restored, which is the one the jingle interrupted, so
the probe carries the interrupted song's id forward. Each window records which
boundary opened it. The address is `$72B14`, verified by opcode rather than by
label, since labels in this area have drifted: `204e` (`movea.l a6,a0`),
`43ee03a0` (`lea v_1up_ram_copy(a6),a1`), `303c0087` (`move.w #$87,d0`, the
`$220/4-1` longword count the listing names).

**What this does not change.** A window containing no 1-up is unaffected, so
the ordinals of windows before a run's first restore are stable, and the GHZ
window's byte-identity with the committed gameplay fixture is untouched.
Ordinals *after* a run's first restore shift by one per restore, so anything
citing a window should quote its epoch frame alongside its ordinal.

**How it was found is the point.** A single-song contract that asserts its own
asset range turned an unmodelled ROM epoch into a loud capture failure at the
exact frame, rather than into plausible-looking parity data normalized against
the wrong song. The assertion earned its keep.


## 2026-09-04 - Music $8E's new frontier at tick 360: the engine's tempo timer stops, it does not merely shift

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `817d96e96`.
- **Fixture:** scratch capture of window 2 of `s1-complete-run.bk2`, music
  `$8E`, 567 ticks.
- **Result:** `GLOBAL_STATE_MISMATCH` tick 360, field `tempo_timeout`,
  reference `1` against engine `2`. Recorded, not fixed.

**The trajectory, which says more than the first divergence does.** The tempo
timeout cycles 3, 2, 1 on both sides through tick 359. At 360 the reference
goes straight from 3 to 1, skipping the 2, and then resumes a clean 3, 2, 1
cycle from 361 onward. The engine reaches 2 at 360 and then **stays at 2 for
every remaining tick**. So the two sides fail differently: the reference takes
one extra decrement and carries on, while the engine's tempo timer stops
advancing altogether.

**What each side's shape suggests.** A single frame in which the tempo timer
decrements twice is what two `UpdateMusic` invocations folded into one captured
tick would look like: `UpdateMusic` decrements
`v_main_tempo_timeout` once per invocation (s1.sounddriver.asm:174-176), and
the shared lifecycle folds a same-stack re-entry into the invocation already
open, so a second call at the same depth contributes its decrement without
producing a second record. The engine's frozen state is not a phase shift and
not a fold; a state that stops changing is a sequencer that stopped being
serviced. `$8E` is the act-clear jingle, so a song reaching its end around tick
360 while the ROM's driver keeps walking the finished song's track RAM is the
shape to check first.

**Deliberately not fixed here.** These are two different subsystems from the
PSG envelope work that moved this frontier, and the engine-side half is a
sequencer-lifetime question rather than a driver-accuracy one. Recorded so the
next cycle starts from the trajectory rather than from the first divergent
value, which on its own would have suggested an off-by-one.


## 2026-09-04 - A tied note steps the PSG envelope: music $8E moves from tick 186 to tick 360

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `981aece96`.
- **Fixture:** scratch capture of window 2 of `s1-complete-run.bk2`, music
  `$8E`, 567 ticks.
- **Result before:** `TRACK_STATE_MISMATCH` tick 186, role `PSG1`, field
  `envelope_cursor`, reference `7` against engine `6`.
- **Result after:** `GLOBAL_STATE_MISMATCH` tick 360, field `tempo_timeout`,
  reference `1` against engine `2`.

**Two hypotheses died before the right one, and both are worth recording.**
First: S1's shipped `FixBugs = 0` path treats only an exact `$80` as the
envelope terminator and uses any other high byte as a signed volume addend,
where the engine treats every byte at or above `$80` as a command
(s1.sounddriver.asm:1938-1952). That is a real divergence in the code and it
is unreachable in practice: all nine S1 PSG envelopes were dumped from the
pinned ROM and every one terminates with `$80` and contains no other high
byte. Second: the envelope-index reset is gated on the do-not-attack bit
(`FinishTrackUpdate`, :438-442), so a tied note keeps its cursor. The engine
already gates its reset the same way. Neither hypothesis survived contact with
the data.

**What the data said.** Dumping `PSG1` across ticks 170-195 on both sides shows
them identical up to 185 and identical again from 189, with the reference one
step ahead across 186-188 exactly. Tick 186 is where the track reads a new note
(sequence position 63 to 66) that is *tied*: `doNotAttack` is true. The
reference's cursor goes 6 to 7; the engine's stays at 6.

**The ROM reason.** `PSGUpdateTrack`'s new-note path ends `bra.w PSGDoVolFX`
(:1813-1819), taken whatever the do-not-attack bit says, and `PSGDoVolFX`
advances the envelope index. Only the *reset* is conditional, and it lives
elsewhere, in `FinishTrackUpdate` (:438-442). So an attacked note clears the
cursor and then steps it, and a tied note keeps the cursor and then steps it.
The engine did the reset and the step together inside its
attacked-note branch, so a tied note got neither. S2 reaches its vol-FX the
same unconditional way, `call zPSGDoVolFX` on the note-on path
(s2.sounddriver.asm:1129). S3K's `zUpdatePSGTrack` is structured differently
(skdisasm Sound/Z80 Sound Driver.asm:4058-4069), so the change stays on the
existing `isDirect68kDriver` branch that already carried this citation.

**One write per pass, which is what the first attempt got wrong.** Stepping the
envelope on the tied path made the track state agree and then reported an extra
PSG write, because the engine's envelope step sends the volume as it goes and
the branch already sent one afterwards. Removing the second send instead broke
both committed gameplay oracles with `event_missing`, at ticks 1,861 and 2,810,
because a step that ends on a hold emits nothing while the ROM still sends. The
ROM splits the work: `PSGDoVolFX` computes and falls into `SetPSGVolume`, which
decides and sends (:1960-1969). The engine now mirrors that split, stepping
with the write withheld and then sending exactly once.

**Gates, all green and read by content.** Audio parity package with three ROM
paths: 183 tests, 0 failures, 0 errors, 2 skips. Both S1 gameplay oracles pass
their `MATCH` assertions at 2,562 and 5,257 ticks, which is the check that
caught the wrong version of this fix. S2 v1 `MATCH (698 ticks)`; v2 both lines
`MATCH (2198 ticks)`; CPZ state-only `MATCH (720 ticks)` with its
state-and-writes companion unchanged at tick 237; request windows `MATCH` at
25, 52 and 27.


## 2026-09-04 - The re-entered invocation measured: both call sites identified, and the cause is not preemption

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `981aece96`.
- **Method:** the survey probe logs every `UpdateMusic` entry and return within
  two frames of each known re-entry, with the caller's return address read off
  the stack. That address is what distinguishes the two call sites.

**Both call sites confirmed by their following opcodes.** `$B64` is the return
address of `VBlank_Music`'s `jsr` (sonic.asm:682): the bytes before it are
`4eb900071b4c`, `jsr (UpdateMusic).l`, and the bytes after are `52b8fe0c`,
`addq.l #1,(v_vblank_count).w`, which is `VBlank_Exit` (:684). `$11B0` is the
HBlank delayed-transfer call (:1062): same `jsr` before it, and after it
`4cdf7fff` `4e73`, `movem.l (sp)+,d0-a6` then `rte` (:1063-1064).

**The measured events, all three from `s1-complete-run.bk2`.**

| Abandoned entry | Caller | Re-entry | Caller | Stack move |
|---|---|---|---|---|
| frame 107,740 | HBlank (:1062) | 107,741 | HBlank (:1062) | 4 bytes deeper |
| frame 187,448 | VBlank (:682) | 187,449 | VBlank (:682) | 4 bytes shallower |
| frame 194,164 | VBlank (:682) | 194,165 | VBlank (:682) | 4 bytes shallower |

**This refutes the obvious explanation, which is worth stating because it was
mine too.** It is not one call site preempting the other: in two of the three
events both the abandoned invocation and the re-entry come from the *same* site,
`VBlank_Music`. The stack moves in both directions across the three events, and
in every case the re-entry lands on the *following* frame rather than nested
inside the same one. So the abandoned invocation is not a partially-executed
inner call that an outer one interrupted.

**What the ROM does offer.** `UpdateMusic`'s `.updateloop` re-executes the
routine's own entry address through `bra.s UpdateMusic` whenever the Z80 has
the DAC busy (s1.sounddriver.asm:147-165), so a single logical invocation can
strike the entry hook many times. The shared parity contract already models
that as a same-stack `retry`. What these three events add is a retry arriving
at a *different* stack depth a frame later, meaning the earlier invocation never
reached `DoStartZ80`'s `rts`. How long the Z80 holds the DAC busy is hardware
timing, and it is not derivable from the frame-granularity state the contract
records.

**Consequence for the contract, and the decision taken.** Modelling this would
require knowing where in the track walk the abandoned invocation stopped, which
no frame-granularity record contains. Hard rule 3 is explicit that a value
measured from a fixture's own rows rather than derived from the ROM is a fitted
model even when the test passes, and rule 4's timing exception does not cover
it: this decides *what* work happens, not when engine-created work becomes
ready. **So windows containing a re-entered invocation are excluded from the
committed subset rather than modelled.** That is 3 of 83 windows in
`s1-complete-run.bk2` (ordinals 39, 78, 81) and 3 of 101 in
`sonic1-complete-withemeralds.bk2` (ordinals 3, 63, 98). Both probes continue to
drop such an invocation and count it in the window's metadata, so a window that
contains one is identifiable from the record alone.

**One song is blocked by this.** Music `$8B` has exactly one window in each
movie and in both it is a re-entry window, so `$8B` cannot be published clean
from either recording. It is included in the subset and expected to diverge at
its re-entry; the alternative is no `$8B` coverage at all. **Removal
condition:** a recording whose `$8B` window contains no re-entry, or a contract
that can represent a partial track walk.


## 2026-09-04 - The fade dispatch class is hooked, and the residual is an ordering fact about UpdateMusic

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `981aece96`.
- **Fixtures:** still none published; scratch captures of the first four
  windows of `s1-complete-run.bk2`.

**What landed.** The per-song window probe now hooks `Sound_E0toE4`, and the
engine host routes the fade-out command. Music `$8A`, the 72-tick
title-screen window, moves from `GLOBAL.fade_active` `true` against `false` to
`GLOBAL.fade_delay` `3` against `2`, both at tick 48. The fade now happens; only
its first step is early.

**The address, verified by opcode rather than by a stale label.** `$71F8E`
reads `040700e0` (`subi.b #$E0,d7`, `flg__First` = `$E0`), then `e54f`
(`lsl.w #2,d7`), then `4efb7002` (`jmp Sound_ExIndex(pc,d7.w)`), and the two
bytes before it are the `4e75` `rts` the disassembly labels `locret_71F8C`.
That is `Sound_E0toE4` (s1.sounddriver.asm:715), `PlaySoundID`'s fourth
dispatch branch, reaching `FadeOutMusic` (`$E0`, :1360), `PlaySegaSound`
(`$E1`), `SpeedUpMusic` (`$E2`, :1568), `SlowDownMusic` (`$E3`, :1587) and
`StopAllSound` (`$E4`) through `Sound_ExIndex` (:722-726).

**Only `$E0` is modelled, and the rest fail loudly.** The host throws on
`$E1-$E4` rather than ignoring them, so a window containing one cannot be
compared as though the request never happened. Across the first four windows
only `$E0` occurs, once in the title window and once in the act-clear window.

**The GHZ window is still byte-identical.** Adding a dispatch hook changes the
`dispatches` array only where such a command occurs, and GHZ's window has none,
so its tick body remains `cmp`-identical to the committed run 2 fixture at
44,775,538 bytes. That check was re-run after the hook landed.

**The residual is an ordering fact, not a constant to tune.** Inside one
`UpdateMusic` invocation the ROM runs `DoFadeOut` at :179-181, *before* it
cycles the sound queue and calls `PlaySoundID` at :197-202. So a fade armed by
a dispatch in invocation N is not stepped until invocation N+1: `DoFadeOut`
has already passed for N. The parity host submits dispatches before the whole
frame service, and the engine's fade update and track walk both live inside
that service, so the engine steps the fade in the same invocation that armed
it, one step early. `FadeOutMusic`'s other effects -- `StopSFX`,
`StopSpecialSFX`, the DAC stop and the `f_speedup` clear -- *do* belong to
invocation N, before its track walk, so the answer is not to move the whole
submit later. What the ROM needs is a dispatch point between the fade update
and the track walk, which `SmpsDriver.serviceOuterFrame` does not currently
expose. **Left open deliberately, and not absorbed by a compensating step
count:** a fade armed one delay unit short would make this window agree and
would be a fitted constant, which is exactly what hard rule 3 forbids.

**The other frontier is unchanged.** Music `$8E`, the 567-tick act-clear
window, diverges at tick 186, role `PSG1`, field `envelope_cursor`, reference
`7` against engine `6`.

**Gates, all green and read by content.** Audio parity package with three ROM
paths: 183 tests, 0 failures, 0 errors, 2 skips. Both S1 gameplay oracles pass
their `MATCH` assertions at 2,562 and 5,257 ticks. S2 v1 `MATCH (698 ticks)`;
v2 both lines `MATCH (2198 ticks)`; CPZ state-only `MATCH (720 ticks)` with its
state-and-writes companion unchanged at tick 237; request windows `MATCH` at
25, 52 and 27.


## 2026-09-04 - The loop-counter set becomes song-derived, and the fade dispatch class turns out to be unhooked

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `981aece96`.
- **Fixtures:** still none published. Measurements below are against scratch
  captures of the first four windows of `s1-complete-run.bk2`.

**What moved.** The per-song window probe now derives each window's reachable
`F7` loop-counter slots by walking the song's own track bytecode, mirroring
`S1OpenGgfAudioCapture.parseReachableF7LoopIndices`, instead of the fixed
`{0, 1}` the single-window probes carry. Music `$8E` moves from
`TRACK_STATE_MISMATCH` at **tick 0** to **tick 186**, role `PSG1`, field
`envelope_cursor`, reference `7` against engine `6`.

**The derivation is checked against every song, not just the failing one.** Run
offline over the pinned ROM for all nineteen music ids: `$81` gives `{0, 1}`,
which is exactly the constant it replaces, so GHZ is unchanged and the probe's
window 1 tick body stays `cmp`-identical to the committed run 2 fixture at
44,775,538 bytes. `$8E` and `$88` reach no `F7` at all and give `{}`, which is
what the engine already derived and what the tick-0 divergence was reporting.
`$91`, the credits song, needs `{0, 1, 2}`: the old constant would have
under-reported it. Ten songs give `{0}`. The constant was right for GHZ and
wrong for the other eighteen.

**A new frontier, and it names a missing input rather than an engine fault.**
Music `$8A`, the 72-tick title-screen window, diverges at tick 48 on
`GLOBAL.fade_active`, reference `true` against engine `false`. The engine was
never told to fade. `PlaySoundID` dispatches ids `$E0-$E4` through a fourth
branch, `Sound_E0toE4` (s1.sounddriver.asm:715, at `$71F8E` from the
disassembly's own `locret_71F8C` address comment two bytes earlier), which
reaches `FadeOutMusic` (:1360), `SpeedUpMusic` (:1568) and `SlowDownMusic`
(:1587) through `Sound_ExIndex` (:722-725). The gameplay probe hooks
`Sound_PlaySFX` and `Sound_PlaySpecial` but not this branch, so a fade request
is invisible to the capture and the engine host has nothing to replay. These
are driver commands the game issues exactly as it issues SFX, so they belong in
the same per-invocation dispatch array; the ids already disambiguate
themselves, as `$D0-$DF` do for special SFX. Landing that hook and its host
routing is the next step, and it is required for any window that spans a fade,
which over a whole run is most act transitions.

**Measurements at this commit,** scratch captures of the first four windows:

| Window | Music | Ticks | Result |
|---|---|---|---|
| 0 | `$8A` | 72 | first divergence tick 48, `GLOBAL.fade_active`, `true` vs `false` |
| 1 | `$81` | 5,257 | tick body byte-identical to the committed run 2 fixture |
| 2 | `$8E` | 567 | first divergence tick 186, `PSG1.envelope_cursor`, `7` vs `6` |
| 3 | `$81` | 1 | `MATCH (1 ticks)` |

**Gates, all green and read by content.** Audio parity package with three ROM
paths: 183 tests, 0 failures, 0 errors, 2 skips. Both S1 gameplay oracles pass
their `MATCH` assertions at 2,562 and 5,257 ticks. S2 v1 `MATCH (698 ticks)`;
v2 state-and-writes and state-only `MATCH (2198 ticks)`; CPZ state-only
`MATCH (720 ticks)`, its state-and-writes line unchanged at tick 237,
`writes[4]`, 36 of 719 divergent; request windows `MATCH` at 25, 52 and 27.


## 2026-09-04 - The S1 whole-run window plan, and a per-song oracle contract that reproduces the committed fixture byte for byte

- **Worktree/branch:** `.worktrees/s1-audio-complete`,
  `feature/ai-s1-audio-complete-runs`, over `develop` at `981aece96`.
- **Fixtures:** none published yet. This entry records the measured capture
  plan for both pinned complete runs, the contract that will carry it, and the
  first divergence the contract exposes.

**The window rule, and why it is per song rather than per act.** A window opens
at a `Sound_PlayBGM` dispatch and closes at the next one. That boundary is the
ROM's: `Sound_PlayBGM` reloads the driver's music track RAM through
`InitMusicPlayback` (s1.sounddriver.asm:1498-1502), so the song, and with it
the ROM asset range every sequence position is normalized against, changes
exactly there. Per act does not work, because an act contains several BGM
loads -- invincibility, extra life, boss, act clear -- and crossing one is
precisely what the existing contract cannot describe: the reference normalizes
against one song's range and the engine host drives one music sequencer. Per
song is the smallest ROM-defined epoch that keeps both sides' contracts intact,
and it strictly refines per act. Chained windows tile a movie from its first
BGM request to its end, so the coverage is the whole run.

**The plan, measured rather than estimated.** A new reconnaissance probe,
`tools/audio/probes/s1_bgm_window_survey_probe.lua`, hooks only `Sound_PlayBGM`
and the `UpdateMusic` entry and return pair, so it walks a whole movie in about
ten minutes and emits a capture plan instead of parity data.

| Movie | Input rows | Windows | Invocations | SFX dispatches | Abandoned |
|---|---|---|---|---|---|
| `s1-complete-run.bk2` | 195,493 | 83 | 194,667 | 4,988 | 3 |
| `sonic1-complete-withemeralds.bk2` | 225,101 | 101 | 224,123 | 6,244 | 3 |

Fifteen distinct music ids appear across the shorter run, and every id resolves
in the ROM music pointer table, so no window needs a hard-coded address.

**The survey reproduces the committed fixture independently.** Its window 1 for
`s1-complete-run.bk2` is music `$81` opening at emulator frame 584 with 5,257
invocations -- the published `s1-gameplay-ghz1-run2-reference.v1` window's
epoch frame and tick count, derived by a different code path.

**The capture probe's window 1 is byte-identical to that fixture.**
`tools/audio/probes/s1_run_window_driver_parity_probe.lua` chains windows and
writes one file per window. Its capture of window 1 has a tick body of
44,775,538 bytes, `cmp`-identical to the committed fixture's 44,775,538, so the
per-tick record shape and every bus-capture path are preserved and a window
this probe records is comparable with one the single-window probe records over
the same span. Only the metadata line differs, by design.

**The contract is additive, so no committed fixture's validation moved.** A new
capture kind, `s1_run_song_window_driver_reference` and its OpenGGF
counterpart, carries `music_id` and a `window` object instead of
`launch_update_music_invocations`. That field is a per-movie property the
gameplay kind pins, and it is meaningless for windows after the first, which
have no dormant prefix at all -- the driver is already running when they open.
Widening the gameplay kind would have disturbed its pinned counts, so the new
shape sits beside it. The engine host now loads the window's own epoch song and
derives its asset range from the music pointer table the same way
`Sonic1SmpsLoader.calculateMusicDataSize` sizes the blob, replacing a
hard-coded GHZ id and range.

**First divergence on a non-GHZ song, and it is a probe constant rather than an
engine fault.** Measuring music `$8E` (567 ticks, window 2 of the shorter run)
gives `TRACK_STATE_MISMATCH` at tick 0, role `DAC`, field `loop_counters`,
reference `[0, 0]` against engine `[]`. The reference side normalizes loop
counters at a fixed index set, `ACTIVE_LOOP_COUNTERS = {0, 1}`, which is GHZ's
reachable set and was correct for every window captured until now. The engine
derives the set per song by walking the song's own bytecode
(`parseReachableF7LoopIndices`), and finds `$8E` reaches no `F7` loop at all.
The accurate normalization is the song-derived one, so the probe needs the same
derivation rather than the constant. Left open deliberately: landing the
song-derived set in Lua is the next step, and it is a ROM-data derivation on
both sides, not a fitted constant.

**Two UpdateMusic call sites, and the abandoned invocations they cause.**
`UpdateMusic` is called from the VBlank handler (sonic.asm:682) and from the
HBlank handler's delayed-transfer path (sonic.asm:1062), which runs when
`f_doupdatesinhblank` is set. The two run at different stack depths, so over a
whole run an invocation can be re-entered before its return is seen. The shared
parity contract's invocation lifecycle asserts on exactly that, which is why
the existing single windows, all of which stop early, never meet one. Three
occur in each pinned movie. Both new probes drop such an invocation rather than
recording a track walk that did not complete, and count it. `PauseMusic` is not
involved: all of its exits reach `DoStartZ80`'s `rts`
(s1.sounddriver.asm:581, :629), which is the hooked return.

**Payload cost, measured.** At the existing fixtures' 165 gzipped bytes per
tick, full coverage is about 32 MB for the shorter movie and about 69 MB for
the pair. Individual per-song files stay between roughly 0.2 and 2 MB, so
nothing approaches GitHub's per-file limit, but the repository total is a real
cost and the committed subset is a decision recorded before any fixture lands.

**Gates at this commit, all green.** The audio parity package with three ROM
paths: 183 tests, 0 failures, 0 errors, 2 skips. Both S1 gameplay oracles pass
their `MATCH` assertions at 2,562 and 5,257 ticks. S2 v1 `MATCH (698 ticks)`;
v2 state-and-writes and state-only `MATCH (2198 ticks)`; CPZ state-only
`MATCH (720 ticks)` with its state-and-writes line unchanged at tick 237,
`writes[4]`, 36 of 719 divergent; request windows `MATCH` at 25, 52 and 27.


## 2026-09-04 - S2: the 1-up restore never rested the tracks, in all three drivers

**Date / commit / worktree** 2026-09-04, from `373b4376c` on
`bugfix/ai-s2-1up-music-restore` in `.worktrees/s2-1up-restore`.

**Fixture** New: `src/test/resources/audio/parity/s2/s2-driver-state-w20107-23600.reference-v2.jsonl.gz`
(gzip sha256 `1fa3d0bd...5bf786`, uncompressed `725fcd97...f1e875`), cut from
`sonic-2-sonic-tails-complete-emeralds.bk2`. The first committed S2 window that
contains a 1-up jingle and the restore that follows it. 3484 ticks over 3493
frames, 9 zero-service frames, 0 multi-service, 388486 writes. Duplicate capture
byte-identical.

**Window and epoch** Rows 20107..23600. Tick 0 is a ROM epoch, not a chosen
frame: the service on which `zCurSong` becomes `82h`, the level music load,
with `1upPlaying` already cleared by `zPlayMusic`'s ordinary-load branch
(`s2.sounddriver.asm:1728-1731`). `MusID_ExtraLife` (`98h`,
`s2.constants.asm:856`) is requested on tick 2875 / row 22991, where
`zPlayMusic` backs up `zAbsVar` and every track and sets `1upPlaying` to `80h`
(`s2.sounddriver.asm:1675-1724`). `cfFadeInToPrevious` runs on tick 3085 / row
23201 (`s2.sounddriver.asm:3083-3163`). 399 further services carry the whole
`28h`-step fade-in. `zCurSong` stays `98h` across the restore because it lives
at `1300h`, outside the backed-up region, so `1upPlaying` is the evidence, not
the song byte.

**Command**

```
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Dsonic2.rom.path=<s2 REV01>" \
  "-Dtest=TestS2OneUpRestoreDriverStateOracle" test
```

**Result** First divergence after the jingle ends, at the restore service
itself: every playing FM and PSG music track. The reference has all eight at
rest; the engine had all eight not resting.

```
FM1..FM5, PSG1..PSG3: engine resting=false against the ROM's true
```

**Mechanism** `SmpsSequencer.triggerFadeIn` is the engine's port of
`cfFadeInToPrevious`, and its only production caller is
`AbstractSmpsAudioBackend.doRestoreMusic`. It applied the `28h` attenuation and
re-uploaded voices but never marked the playing tracks at rest, and never
issued the PSG note-off. The resumed song therefore carried whatever note each
channel was holding when the jingle interrupted it, with the restored
instrument reloaded underneath it, until each track reached its own next note.
That is the reported "funky remix".

S1 and S2 rest the tracks: `bset #1` on every playing FM and PSG track with
`PSGNoteOff` on the PSG ones (`s1disasm/s1.sounddriver.asm:2193, :2211-2212`),
and `set 1` with `zPSGNoteOff` (`s2.sounddriver.asm:3107, :3131-3132`).

**S3K does not, and the disassembly says it does.** `zFadeInToPrevious` ORs
`84h` over every track and then clears bit 2 again on the FM ones
(`skdisasm/Sound/Z80 Sound Driver.asm:2761-2770`). Its inline comment on both
`or 84h` sites reads "Set 'track is playing' and 'track is resting' flags", but
in this driver's own layout bit 2 is "SFX is overriding this track" and bit 4 is
"track is resting" (`Driver.asm:25, :27`), and `zRestTrack` at `:4220-4223` sets
bit 4 and then tests bit 2. `84h` is bits 7 and 2, so it is playing plus
overriding, and it sets no resting bit at all. The comment names the wrong bit.
The `res 2` at `:2767` makes the intent unambiguous: every track is marked
overridden, the FM tracks are released again and re-voiced, and the PSG tracks
keep the overriding bit, which is what mutes them through the fade. S3K also
attenuates only the FM tracks, by `40h` (`:2769`), leaving the PSG volumes
untouched.

So the restore has two shapes, not one, and they are now a driver config mode:
`SmpsSequencerConfig.FadeInRestore.REST_TRACKS` for S1/S2 and `OVERRIDE_PSG` for
S3K. Taking the S3K comment at face value would have rested its tracks and
attenuated its PSG channels, neither of which that driver does.

**Why the existing oracles could not see it** `PlaybackControl` bit 1 is
excluded twice over. `S2OracleComparison.MappedTrack.CONTROL_MASK` is `0x98`,
which omits it on the reference side, and `MappedTrack.fromEngine` composes the
engine's control byte from active/tieNext/modEnabled only, so the rest bit is
never mapped from the engine at all. `NOT_COMPARED` records the exclusion. The
new test compares the field directly rather than widening the mask, so the
existing windows keep their pinned control-bit semantics.

**Notes** The attenuation half was already correct: the engine's
`volumeOffset += steps` matches the ROM's `Volume += 28h - FadeInCounter` with
no fade in flight, and the reference's next service confirms the first fade
step is immediate. Existing oracles stay green; the 1-up restore is not inside
any of their windows, so the new PSG note-off writes reach no committed write
stream.

---

## 2026-09-04 - The duration seed lands; both red assertions were the suspects

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `230e88fcc`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 565, role `SFX_FM4`, field
  `durationTimeout`, reference `1` against engine `0`. DAC stream
  `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 565, event 0, reference
  `ym2612 port 0 register 28h = 4` against engine `port 0 register 0A4h = 18`.
  DAC stream unchanged. Every compared field at service 565 agrees.
- **The oracles arbitrated first, and they cleared the seed.** With the seed
  in place all four S1 comparisons still MATCH: the sound-test captures at
  14,690 and 1,967 ticks, and both gameplay oracles at 2,562 and 5,257. That
  is real hardware behaviour agreeing with the change, against two synthetic
  fixtures disagreeing with it.
- **Hypothesis 1, the S1 note-fill assertion: the fixture was degenerate, not
  the assertion.** Counting the ROM with the seed of 1 and the fill-after-not-
  expired ordering gives cursor 2, exactly what the test asserts: the opening
  walk reads `E8 02`, `F5 01` and the note and takes the first envelope step;
  the next frame decrements the fill from 2 to 1 and steps again; the frame
  after that decrements it to zero and `NoteTimeoutUpdate` exits before the
  envelope. So the ROM says 2 and the assertion was right. A frame-by-frame
  probe showed the engine's track never reading its stream at all: the fixture
  declares `tempo = 1`, and S1's `UpdateMusic` adds 1 to every slot's
  `DurationTimeout` whenever the tempo timeout expires
  (s1.sounddriver.asm:1549-1561), which at tempo 1 is every frame and exactly
  cancels the per-frame decrement. A track seeded at the ROM's own 1 can then
  never reach zero. The fixture now declares `tempo = 2` and the assertion
  passes at its original value.
- **Hypothesis 2, the tempo-delay walk: the guard was measuring the wrong
  frame.** Its `events.size() > 2` required at least one chip write on a
  tempo-delay frame. That only held while the track had never started, because
  the write it counted was the opening note read. Once a track is playing, S1's
  tempo delay adds 1 and the walk's decrement cancels it, so the note does not
  advance and the frame emits nothing. The guard now asserts what the frame
  genuinely proves: the walk ran, evidenced by the duration being net
  unchanged across it, and the frame's event list is exactly the service
  begin/end pair. The scoping property the test exists for is untouched, and
  the observer still fails any unscoped write.
- **No per-driver difference to model.** All three ROMs seed 1: S1 loads
  `d5 = 1` for both music loops (s1.sounddriver.asm:823, :836, used at :847
  and :897) and writes 1 for SFX (:1062, :1171); S2 stores 1 with the comment
  "should expire next update, play first note, etc."
  (s2.sounddriver.asm:1857); S3K's `zZeroFillTrackRAM` seeds it in the
  track-RAM fill (skdisasm Sound/Z80 Sound Driver.asm:2168-2184).
- **The CPZ state-and-writes line is still the S2 lane's own frontier.**
  `DIVERGENCE at tick 237, field writes[4], expected ym1[0B1h] against
  ym1[0B0h], 36 of 719 ticks divergent`, unchanged by this branch and
  previously reproduced with this branch's source change reverted. Its
  `state only` companion is `MATCH (720 ticks)`.
- **Open items.** S2's `zNoteFillUpdate` countdown; S1 and S2's post-note
  do-not-attack clear; the `.dac_playback_loop` cycle total of 303 against
  `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape. The duration seed leaves the list.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 v1 `MATCH (698 ticks)`, v2 state-and-writes and state-only
  `MATCH (2198 ticks)`, CPZ state-only `MATCH (720 ticks)`, request windows
  `MATCH` at 25, 52 and 27. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,159
  tests, 0 failures, 10 skips.


## 2026-09-04 - CPZ tick 237 attributed: the ring speaker's phase, inherited from before the window

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, over `develop` at `d3f3f4212`.
- **Result lines unchanged.** CPZ state only `MATCH (720 ticks)`; state with
  writes DIVERGENCE at tick 237 (movie row 2968), `writes[4]`, reference
  `ym1[0xb1]=0x4` against the engine's `ym1[0xb0]=0x4`, 36 of 719 divergent.
  Nothing landed in the engine.
- **The attribution.** The window's request at that row is `B5h`, the ring
  sound. `zPlaySound_CheckRing` resolves a raw `B5h` to `CEh`, ring left, only
  while `zRingSpeaker` is zero, and complements the flag either way
  (s2.sounddriver.asm:2124-2135). The reference's `zRingSpeaker` is `FFh` from
  the anchor through service 261 and flips to `00h` at service 262, which is
  that row, so the ROM kept `B5h`, ring right. The engine's headless capture
  starts the alternation at the power-on value zero and resolved `CEh`, ring
  left. The two sounds sit on different FM channels, which is why the whole
  voice load appeared one channel across rather than as a value difference.
- **Proved by experiment, not argued.** Starting the harness alternation at the
  opposite phase moves the write frontier from tick 237, row 2968, to tick 494,
  row 3225, and halves the divergent services from 36 to 18.
- **Not kept, and the default is not arbitrary.** The opposite phase breaks
  `TestS2AudioOracleComparator#explicitDriverRequestsResolveAndAdmitTheFirstRingBeforeTheTargetUpdate`,
  which requires `zRingSpeaker = 0` to resolve the first raw `B5h` to `CEh`.
  Zero is the power-on value, so the harness default is right and the CPZ
  window simply starts with the flag already flipped.
- **Why the engine cannot derive it.** `zRingSpeaker` lives among the driver's
  own byte variables at low Z80 addresses, outside the region
  `zInitMusicPlayback` clears (:2580-2612), so a song load leaves it untouched
  and a capture that begins at that load inherits nothing. Seeding it from the
  reference would hydrate driver state that decides *which* sound plays, which
  hard rule 4's exception does not cover.
- **The slot rule is not involved and that is now settled from both sides.**
  `zPlaySound` derives an SFX slot from the header's own channel byte through
  `zMusicTrackOffs` (:2210-2251, :747-757), and the engine's
  `SmpsSequencer.mapFmChannel` agrees with it. Neither side searches for a free
  channel.
- **Recorded** in docs/status/known-discrepancies.md, "S2 Headless Oracle
  Capture Starts Mid-Run (Driver Variables Outside The Music Load's Clear)",
  with a removal condition: a capture that starts from power-on, or a
  derivation from something the window does contain.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the EHZ v2 oracle
  unchanged at `MATCH (2198 ticks)` on both lines; the three request windows
  `MATCH` at 25, 52 and 27 transfers; audio packages plus the four extra
  classes with three ROM paths: 2,063 tests, 0 failures, 0 errors, 10 skips.

## 2026-09-04 - The note fill moves into the continuing-note branch; the duration seed does not land

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `1292b5a51`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before and after:** `TRACK_STATE_MISMATCH`, tick 565, role
  `SFX_FM4`, field `durationTimeout`, reference `1` against engine `0`; DAC
  stream `BYTE_DIFFERENT` run 338 byte 0. **The frontier did not move.** What
  landed is a cited correction at a site no committed fixture exercises, and
  the finding that the seed needs more than the restructure it was paired
  with.
- **Every driver seeds the first duration timeout with 1, and each is cited.**
  S1 loads `d5 = 1` for both of its music loops, commenting "note duration for
  first note" (s1.sounddriver.asm:823, :836), and uses it at :847 and :897;
  its SFX loops write 1 directly (:1062, :1171). S2 stores 1 with the comment
  "should expire next update, play first note, etc." (s2.sounddriver.asm:1857).
  S3K's `zZeroFillTrackRAM` seeds it in the track-RAM fill itself (skdisasm
  Sound/Z80 Sound Driver.asm:2168-2184). There is no per-driver difference to
  model: the value is 1 everywhere.
- **What landed: the note fill belongs to the continuing-note branch.** All
  three drivers call it only from there, never from the pass whose duration
  timer expired: S3K from `zUpdateFMorPSGTrack`'s `.note_going`
  (:781-790), S2 from `zFMUpdateTrack`'s `.notegoing`
  (s2.sounddriver.asm:832-834) and S1 from `FMUpdateTrack`'s `.notegoing`
  (s1.sounddriver.asm:358-361). The engine ran it after the decrement whatever
  the outcome. Moving it changes nothing measurable today, because no
  committed fixture has a fill expire on the same pass its timer runs out, and
  it is landed cited rather than left wrong.
- **What did not land, and the honest reason.** Seeding the constructor with 1
  makes the frontier byte agree and takes the S3K oracle to service 565's
  first write. It also turns two assertions red, and they stay red with the
  note-fill relocation in place, so the relocation was necessary-but-not-
  sufficient rather than the whole story:
  `TestS1AudioStateNormalizer#productionS1NoteFillExpirySkipsTheRemainingPsgUpdate`
  reads envelope cursor 3 where it asserts 2, and
  `TestAudioDiagnosticObservers#fadeOnlyFrameAndTempoDelayTrackWalkHaveTypedServices`
  loses its tempo-delay track walk. The first names a ROM property rather than
  pinning a snapshot. The residual is an interaction with S1's `TIMEOUT` tempo
  mode, whose per-frame branch adds 1 to every track's duration, and I did not
  isolate it. Landing a seed I cannot explain against an assertion that names
  a ROM property is the mistake this log exists to prevent, so the seed is
  reverted and stays on the open list with these two names attached.
- **The CPZ line is not mine, and that was measured rather than assumed.**
  `s2-driver-state-cpz-w2700-3450 state and writes` reports `DIVERGENCE at
  tick 237, field writes[4], expected ym1[0B1h] against ym1[0B0h], 36 of 719
  ticks divergent`. Reverting this branch's only source change and re-running
  the identical command reproduces that line character for character, so it is
  the S2 lane's own frontier. Its `state only` companion is `MATCH (720
  ticks)`, as reported.
- **All other S1 and S2 lines read by content.** S1 sound test `MATCH (14690
  ticks)` and `MATCH (1967 ticks)`; S2 v1 driver oracle `MATCH (698 ticks)`;
  v2 state-and-writes and state-only `MATCH (2198 ticks)` each; CPZ state-only
  `MATCH (720 ticks)`; request windows `MATCH` at 25, 52 and 27 transfers.
- **Open items.** The track-init `DurationTimeout` seed, now with its two
  blocking assertions named; S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape.
- **Gates at this commit, all green.** The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,158
  tests, 0 failures, 10 skips.


## 2026-09-04 - CPZ tick 237 is a whole voice load one FM channel across, and neither slot rule explains it

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, at `fad986e21`.
- **Measurement, not a comparison run.** Nothing landed. Lines unchanged: CPZ
  state only `MATCH (720 ticks)`, state with writes DIVERGENCE at tick 237.
- **What the two sides actually emit.** Both write the same voice load, to
  different channels of port 1, register for register:

  | side | registers |
  |---|---|
  | reference | `B1 04`, `31 37`, `35 77`, `39 72`, `3D 49`, `51`-`8D`, `B5 C0`, `41 23` |
  | engine | `B0 04`, `30 37`, `34 77`, `38 72`, `3C 49`, `50`-`8C`, `B4 C0`, `40 23` |

  Every value agrees and every register is exactly one FM channel lower. The
  reference loads the voice on FM5, the engine on FM4. The surrounding services
  agree in full, including the four `A4`/`A0`/`A6`/`A2` frequency writes that
  open this very service.
- **The ROM's slot rule says FM5, and it is not a search.** `zPlaySound` takes
  the SFX header's own channel byte, does `sub 2` then `add a,a`, and indexes
  `zMusicTrackOffs`, whose entries are `zSongFM3, 0000h, zSongFM4, zSongFM5`
  and then the three PSG tracks (s2.sounddriver.asm:2210-2251, :747-757). The
  request at this row is `B5h`, its header declares channel byte `05h`, and
  `(5 - 2) * 2` is offset 6, which is `zSongFM5`.
- **The engine's own mapping also says FM5.** `SmpsSequencer.mapFmChannel`
  sends `5` to linear channel 4, and `writeTrackFrequency`'s port split sends
  linear 4 to port 1 channel 1, which is `B1h`. Loading the SFX directly
  confirms the header byte the engine reads is `05h`.
- **So the emitter of this block is not the SFX header path**, and the obvious
  hypothesis is dead on both sides of the comparison. The next step is the
  per-write attribution probe that settled the earlier EHZ ordering
  divergences: print the music sequencer's track walk with an end marker per
  track and every chip write inline with its call stack, and find which track
  produces the block. It is most likely the same override or release path as
  the PSG channel handback fixed earlier, since the block is a voice load
  rather than a note.
- **Not attempted here.** Guessing a channel adjustment without that
  attribution would be exactly the fitted change the earlier rounds avoided.

## 2026-09-04 - The CPZ oracle's music id now comes from zMasterPlaylist, and the tick-237 mechanism narrows

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, over `develop` at `23e15d8f1`.
- **Result lines unchanged.** CPZ state only `MATCH (720 ticks)`; state with
  writes DIVERGENCE at tick 237 (movie row 2968), `writes[4]`, reference
  `ym1[0xb1]=0x4` against the engine's `ym1[0xb0]=0x4`, 36 of 719 divergent.
  The EHZ v2 oracle is unchanged at `MATCH (2198 ticks)` on both lines.
- **The music id is now cited rather than inferred.** `zPlayMusic` strips the
  flag bits from the request byte and indexes `zMasterPlaylist` with what is
  left (s2.sounddriver.asm:1748, :1766-1773), and the table's own order is
  `2PResult, EHZ, MCZ_2P, OOZ, MTZ, HTZ, ARZ, CNZ_2P, CNZ, DEZ, MCZ, EHZ_2P,
  SCZ, CPZ, WFZ, HPZ, Options, SpecStage` from request `81h` (:3823-3841). So
  request `8Eh` is CPZ, and request `91h`, which this window's previous song
  was, is Options, which is the level-select screen music the recording was
  playing. The oracle now reads that table.
- **The two id spaces are not a shift, which is why the arithmetic had to go.**
  Request `82h` is EHZ, which the engine calls `81h`; request `8Eh` is CPZ,
  which the engine calls `8Ch`. A new test keeps the citation honest by
  comparing the playlist entry's own header tempo against the `TempoMod` the
  recording stored at load (:1817-1826); it is `EEh` on both sides.
- **A runtime ROM read is not available for this, and that is already
  documented.** `Sonic2SmpsLoader.findMusicOffset` records that the driver's
  playlist and its pointer tables live inside the Saxman-compressed Z80 blob,
  so they exist in readable form only after the driver decompresses itself into
  Z80 RAM. The citation is therefore to the disassembly, checked against the
  recording, rather than to bytes read from the cartridge.
- **Tick 237's mechanism narrows, and the obvious hypothesis is dead.** The ROM
  does not search for a free channel. `zPlaySound` derives the slot from the
  SFX header's own channel byte, `sub 2` then `add a,a`, and indexes
  `zMusicTrackOffs`, whose entries are `zSongFM3, 0000h, zSongFM4, zSongFM5`
  then the three PSG tracks (s2.sounddriver.asm:2210-2251, :747-757). The
  window's second request, `B5h`, declares channel byte `05h`, which is
  offset 6, which is `zSongFM5`. The engine's own
  `SmpsSequencer.mapFmChannel` maps `5` to linear channel 4, which is port 1
  channel 1, which is `B1h`. So both sides put that SFX on FM5 and the
  assignment is not the difference.
- **What is left.** The two sides write the same value to FM4 and FM5 in a
  different order within one service, which makes this an ordering or
  suppression question between a music track and the SFX that took its
  channel, not a channel choice. That is the same family as the PSG release
  ordering fixed earlier on the EHZ span, and it needs the per-write
  attribution probe rather than another reading of the header path.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the three request windows
  `MATCH` at 25, 52 and 27 transfers; audio packages plus the four extra
  classes with three ROM paths: 2,063 tests, 0 failures, 0 errors, 10 skips.

## 2026-09-04 - An S3K SFX owns its channel at admission but walks a service later

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `472f32b17`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 565, role `SFX_FM4`, field
  `resting`, reference `false` against engine `true`. DAC stream
  `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 565, role `SFX_FM4`, field
  `durationTimeout`, reference `1` against engine `0`. DAC stream unchanged.
- **The engine's SFX track ran a whole service early, and the probe showed it
  as a clean shift.** Across services 565 to 570 the engine's `SFX_FM4` state
  equalled the reference's state one service later, field for field: duration
  2 against 1, then 1 against 2, then 22 against 1, and so on.
- **Two halves of one ROM ordering, and they pull in opposite directions.**
  `zUpdateEverything` calls `zUpdateSFXTracks` and only then falls into
  `zUpdateMusic`, whose `zFillSoundQueue` consumes the request (Sound/Z80
  Sound Driver.asm:650-701). So the admitting service has already walked the
  SFX tracks and gives the new one no update at all; its first walk is the
  next service. But `zSFXTrackInitLoop` sets bit 2 on the overridden music
  track while the SFX is still being *loaded* (:1997-2003), so ownership does
  exist from the admitting service. Deferring the walk alone moved the
  divergence straight onto `MUS_FM4.overridden`, which is how the second half
  was found. New `sfxWalkPrecedesRequest` for the deferral, and S3K now
  selects the existing `SfxChannelOwnershipMode.ADMISSION` that S1 and S2
  already used.
- **A third change was tried, measured and dropped.** `zZeroFillTrackRAM`
  seeds `DurationTimeout` with 1 rather than 0 (:2168-2184), which is exactly
  the remaining divergence. Seeding it in the engine's track constructor makes
  that byte agree but changes the first walk's shape for every driver: it
  turned `TestS1AudioStateNormalizer`'s note-fill assertion red, and that test
  asserts a named ROM property rather than a snapshot. The engine reaches the
  same first-unit read from 0 that the ROM reaches from 1, so the seed needs
  the first walk restructured alongside it. Left for its own change, and it is
  the frontier.
- **Three tests re-based, and why that is not laundering.**
  `TestSonic3kCoordFlagParity`'s three spindash-counter tests admit an SFX and
  then mix once, expecting its coordination flags to have run. With the ROM's
  ordering they run on the following service, so each now mixes twice. The
  property under test, that two `E9` flags increment the persistent counter
  and a normal SFX start resets it, is untouched; only the amount of driver
  work needed to reach it changed.
- **S1 and S2 read by content.** Both S1 sound-test captures MATCH at 14,690
  and 1,967 ticks. All four S2 oracles MATCH: v1 at 698 ticks, v2
  state-and-writes and state-only at 2,198 each, and the request windows at
  25, 52 and 27 transfers.
- **Open items, unchanged, plus one.** S2's `zNoteFillUpdate` countdown; S1
  and S2's post-note do-not-attack clear; the `.dac_playback_loop` cycle total
  of 303 against `baseCycles` of 297; S2's `zFadeOutMusic` clearing
  `SpeedUpFlag` (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG
  silence shape; and now the track-init `DurationTimeout` seed described
  above.
- **Gates at this commit, all green.** The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,154
  tests, 0 failures, 10 skips.


## 2026-09-04 - A second S2 driver-state recording: CPZ state MATCHes, writes stop at an SFX channel choice

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, over `develop` at `9f8f21058`.
- **Fixture:** `s2-driver-state-cpz-w2700-3450.reference-v2.jsonl.gz`, new,
  captured from `s2-lvl-select-CPZ.bk2` over movie rows [2700,3450). 744 ticks
  across 750 frames, 6 of them run past by an overrunning service, 94,990
  writes. A different movie, a different zone and a different song from the
  widened EHZ span.
- **Command:** `run-s2-request-window.sh --request-window-mode driver-state`
  with the CPZ movie and its sha256, `--first-row 2700 --exclusive-end 3450`,
  recorded in full in the fixture metadata.
- **The core was not rebuilt.** The build the widen lane left under its scratch
  root matches `artifact-lock.json` on all four values, compressed digest,
  decompressed digest, build id and observer identity, so it was installed
  beside the stock distribution and used as is. A previous entry said no such
  core existed anywhere; that was a fact about a search capped at six
  directory levels, not about the machine.
- **Duplicate-capture gate met:** two serial captures to distinct absent
  external paths, byte-identical at
  `af5ec3e65137d3cc1670433b368247dcf25440cc8ea2a7ebf7478c6ddc680bd7`.
- **Result, state only:** `MATCH (720 ticks)`. The engine's driver state agrees
  with a second recording across the whole span after the load.
- **Result, state and writes:** DIVERGENCE at tick 237 (movie row 2968), field
  `writes[4]`, reference `ym1[0xb1]=0x4` against the engine's
  `ym1[0xb0]=0x4`; 36 of 719 ticks divergent.
- **Mechanism candidate for that first divergence: SFX FM channel choice.** The
  two sides write the same value to two different channels of port 1, `B1`
  against `B0`, which is FM5 against FM4. The row is the window's second sound
  request, `B5h`, arriving while the first, `A0h` at row 2959, is still
  sounding, so the two sides disagree about which FM slot the second SFX takes
  when one is already in use. Not fixed here: the write comparison had never
  reached a second overlapping SFX before, and the ROM evidence for the
  allocation order needs reading `zPlaySound`'s SFX slot search rather than
  inferring it from one row.
- **The engine music id was measured, not assumed.** ROM request `8Eh` is
  engine id `8Ch`. The two are not a fixed distance apart, since EHZ is driver
  `82h` against engine `81h`, so the id was settled by matching the song's
  stored header tempo `EEh` against every loadable id
  (s2.sounddriver.asm:1817-1826).
- **Requests come from the committed sidecar.** The driver-state payload's own
  pre-consumption markers carry no sound id, so the already-published
  `s2-request-window-cpz-w2700-3450` supplies the 33 request transfers as
  engine stimuli. Feeding them moved the write line from tick 228, the window's
  first unheard SFX, to tick 237, and cut divergent ticks from 306 to 36.
- **One documented offset.** The writes are compared from the first wholly
  post-load service onwards, one later than the state. This window's load spans
  two services because the Saxman decompression overruns its frame, so the
  anchored service still carries the tail of `zBGMLoad`'s writes while the
  engine capture emits its load burst as one block and drains it.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the EHZ v2 oracle
  unchanged at `MATCH (2198 ticks)` on both lines; the three request windows
  `MATCH` at 25, 52 and 27 transfers; audio packages plus the four extra
  classes with three ROM paths: 2,062 tests, 0 failures, 0 errors, 10 skips.

## 2026-09-04 - Roadmap step 4: all three routes to a second S2 span are blocked, and the third is the engine's own chain

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, over `develop` at `9f8f21058`.
- **Measurement and survey, not a comparison run.** Nothing landed and no
  fixture was published. One experiment was run and reverted.
- **Route one, the committed CPZ level-select recording.** Already published as
  `s2-request-window-cpz-w2700-3450`, with its own pinned digests and the CPZ
  music load inside it at movie row 2724, where `zCurSong` goes `91h` to `8Eh`.
  The engine cannot be driven through it: `S2PublishedRequestWindows` keeps it
  out of `COMPLETE_RUN_WINDOWS`, documented as the windows the run-chain
  harness replays, and the CPZ trace under `traces/s2/cpz` starts at
  `bk2_frame_offset: 2868`, which is 144 rows after that load.
- **Route two, a fresh CPZ window of the complete-emeralds movie.** Needs a
  capture, and the capture needs the patch-0001 GPGX observer core. No core
  matching `artifact-lock.json`'s
  `25ee305d…` exists on this machine; every `gpgx.wbx.zst` under the repository
  and the scratch roots is the stock `c4231296…`. The surviving raw captures
  from the widen lane, under `<agent-scratch>/claude/s2-widen/runs`, are each
  already windowed to the range they were published at, so `extract` mode has
  no complete-run source to cut a new window from. Building the core needs a
  clean pinned BizHawk, GPGX and musl tree plus eleven exact clang-16 and
  runtime packages, none staged; the pinned repositories are reachable, so this
  is a cost question rather than an impossibility.
- **Route three, the fourth already-published window, and this one is new.**
  `s2-request-window-w13650-14400` spans the EHZ1 exit into the second special
  stage at movie row 13712, so it crosses a level transition and opens a new
  music epoch. It is in `COMPLETE_RUN_WINDOWS` and needs no capture, and it has
  never been compared because `TestS2WidenedRequestOracle` captures only to row
  12400 and skips any window ending past that.
- **Widening that capture was tried and reverted.** With
  `CAPTURE_EXCLUSIVE_END` at 14400 the run-chain replay itself fails on twelve
  axes before the audio comparison is reached: an uncompared-interior physical
  walk overrunning destination 101691, 2,122 physics comparator errors in
  segment 15 with the first non-camera mismatch at frame 2252 field `air`, and
  dynamic-art gap mismatches on two special-stage-to-EHZ1 handovers. So the
  audio window stops at 12400 because that is where the engine's own chain is
  still green, not because of anything in the audio layer.
- **What this means for step 4.** A second S2 span cannot be measured today
  without either building the observer core or moving the run chain's green
  frontier past row 12400. The second of those is a physics-lane question, not
  an audio one.

## 2026-09-04 - The second S2 recording already exists; what is missing is an engine side to compare it against

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-second-recording`, from `develop` at `33d99b0ec`.
- **Measurement and survey, not a comparison run.** No engine behaviour
  changed and no fixture was published. This records why roadmap step 4 did not
  produce a measurement, so the next lane does not repeat the search.
- **A second S2 recording is already committed.**
  `s2-request-window-cpz-w2700-3450` is cut from `s2-lvl-select-CPZ.bk2`, a
  different movie from the complete-emeralds run, with its own pinned
  digests and a `production_bound: false` declaration. Its 750 frame rows carry
  the full 8 KB Z80 RAM image per row, and the CPZ music load is inside the
  window: `zCurSong` goes `91h` to `8Eh` at movie row 2724.
- **The engine cannot be driven through it, and the code says so.**
  `S2PublishedRequestWindows` splits its published list into `ALL` and
  `COMPLETE_RUN_WINDOWS`, the latter documented as "the candidates the
  engine-side request oracle can drive today: those cut from the committed
  complete run, which the run-chain harness replays". The CPZ window is in the
  first list only.
- **Nor can a trace replay cover the load.** `src/test/resources/traces/s2/cpz`
  does replay this movie, through `TestS2CpzLevelSelectTraceReplay`, but its
  metadata gives `bk2_frame_offset: 2868`. The trace therefore starts 144 rows
  *after* the song load at 2724, so an engine replay could cover 582 of the
  window's 750 rows and none of the load the window exists to capture.
- **Capturing a fresh driver-state v2 reference is blocked on a build, not on
  a movie.** The v2 producer needs the patch-0001 GPGX audio observer core.
  No core matching `artifact-lock.json`'s
  `25ee305d8bcac2567d60fd04c14238784ddd018808d4dafe7d5ef2b8372677b6` exists
  anywhere on this machine: every `gpgx.wbx.zst` under the repository and under
  the agent scratch roots is the stock `c4231296…`. The `.NET` harness
  `BizHawk.Headless.Gpgx.exe` is built in three other worktrees but not here,
  and building the core needs `prepare-toolchain.sh` fed a pinned source tree
  and a package directory of exact clang-16 `.deb` files, neither of which is
  staged. Network access to the pinned repositories does work, so this is a
  cost question rather than an impossibility.
- **The third route, decoding the committed v2 capture into driver-state
  ticks, is real work rather than plumbing.** The v2 raw payload carries
  tokenised native events, not attributed chip writes; only the v1 reader
  produces the `ChipWrite` stream the driver-state comparator consumes, and it
  rejects a v2 schema by design.
- **Recommendation.** The cheapest route to a measured second recording is to
  build the observer core once and capture a CPZ driver-state v2 reference
  over rows 2700-3450, because the engine side then needs only the existing
  `S2OracleEngineCapture` pattern with the CPZ music id, which plays the song
  from a constant and takes recorded requests as stimuli. That avoids both the
  run-chain gap and the v2 event decode. It is an infrastructure task with its
  own risks, which is why it was not started unilaterally.

## 2026-09-04 - The S2 driver-state v2 window matches on state and writes together

- **Worktree/branch:** `.worktrees/s2-speedshoes-timer`,
  `bugfix/ai-speed-shoes-timer-phase`, after merging the lead's branch head
  (develop merged in), measured from `mvn clean`.
- **Both v2 lines are now MATCH (2,198 ticks):** state only, which this branch's
  speed-shoes ordering fix closed, and state with writes, which the audio work
  merged in from develop closed. The last recorded write-stream frontier was a
  divergence at tick 228, movie row 10430, `writes[2]`.
- Unchanged in the same run: v1 S2 driver oracle `MATCH (698 ticks)` and the
  three request windows `MATCH` at 25, 52 and 27 transfers. Parity suite 178
  tests, 0 failures, 2 skips.

## 2026-09-04 - A duration-only unit reuses everything; tick 551 -> tick 565

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `33d99b0ec`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 551, event 151, reference
  `psg 192` against engine `psg 223`. DAC stream `BYTE_DIFFERENT` run 338
  byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 565, role `SFX_FM4`, field
  `resting`, reference `false` against engine `true`. DAC stream unchanged.
  Service 551 now agrees in full, and 565 is the first service of an SFX
  request.
- **One defect, found three more times in the same service.** Last cycle
  stopped a duration-only stream unit re-deriving the *rest bit* from the
  previous unit's note. The same stale byte was still driving three more
  decisions, each fixed against the same two ROM lines: `zGetNextNote` sends a
  positive byte straight to `zStoreDuration` (Sound/Z80 Sound
  Driver.asm:917-919), which stores no frequency and silences nothing, and
  only a real `80h` byte reaches `zRestTrack`.
  1. *The silence.* The rest branch called `stopNote`, so a duration-only unit
     following a rest silenced the channel. It now runs only for a note byte
     that is actually `80h`. Event 151 was that silence landing where the
     reference sent PSG3's frequency.
  2. *The frequency.* With the silence gone, the note path recomputed the
     frequency from the stale `80h` and produced `03FFh` where the ROM keeps
     the track's existing Freq word. Both the FM and PSG branches now reuse
     the stored `baseFnum` and `baseBlock` when the unit carried no note byte.
  3. *The PSG volume.* `refreshVolume` forced the silence level `0Fh` when the
     last note byte was `80h`. The ROM's volume tail has no such rule: it adds
     Volume to the envelope value and forces `0Fh` only when that addition
     sets bit 4 (:4098-4112), while the rest *bit* one line above gates
     whether the write happens at all. It now keys on the rest bit.
- **An approach measured and dropped along the way.** Keeping a separate
  "last note that carried a frequency" field and recomputing from it still
  produced `03FFh`, because the ROM recomputes nothing at all on such a unit.
  Reusing the stored frequency word is the accurate model, and it needed no
  new state: `baseFnum` and `baseBlock` already hold it.
- **S1 and S2 checked by reading their result lines, not the failure count.**
  All four S2 oracles MATCH: the v1 driver oracle at 698 ticks, the v2
  state-and-writes and state-only oracles at 2,198 ticks each, and the three
  request windows at 25, 52 and 27 transfers. Both S1 sound-test captures
  MATCH at 14,690 and 1,967 ticks.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape.
- **Gates at this commit, all green.** The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,154
  tests, 0 failures, 10 skips.


## 2026-09-04 - S2 DAC runs are bounded by zDACLenTbl; the residual is a supersession join, not data

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `a0a5a47c7`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before, DAC stream:** `BYTE DIFFERENT in run 3 at byte 709: reference 0x80,
  engine 0x8D (92 runs, run-length delta 0, reference resyncs at engine byte 828
  for 3612 bytes)`.
- **After, DAC stream:** `BYTE DIFFERENT in run 4 at byte 0: reference 0x80,
  engine 0x8A (104 runs, run-length delta 497, no resync, previous run
  superseded: reference 709 and engine 1206 of 1320, 3 complete runs agreed)`.
- **State lines:** `MATCH (2198 ticks)` for both, unchanged.
- **The rule.** A run is now at most one decoded sample long, the bound
  `zWriteToDAC` itself enforces by decrementing `de` from `zDACLenTbl` once per
  source byte and returning to `zWaitLoop` at zero (s2.sounddriver.asm:505-518,
  :528-529, :682-726). A run ends at that length, at a service with no sample
  byte, or at a change of the current sample selector. Each side reads the bound
  from the ROM's own table through its own selector, so the rule is symmetric
  and the break-it control, which feeds the reference to both sides, stays
  green.
- **What the new line says, and it answers the question directly.** The three
  runs before the join are full-length 1,320-byte plays and agree byte for byte
  on both sides, which is what `complete runs agreed` counts. The run that then
  differs follows a pair of runs that are both short of that same 1,320-byte
  bound, 709 on the ROM and 1,206 on the engine. By the bound's own criterion
  those runs were ended by supersession, which is the excused service-duration
  quantity, not by a decode or selection error.
- **A byte inside a bounded run would be data and would be fixed.** None is.
  Aligning each side's superseding sample by its own start, 3,612 bytes agree
  with zero mismatches, and the reference's byte 709 is `0x80`, the accumulator
  value every sample starts at, reached by a step of minus nine that no
  `zDACDecodeTbl` entry can produce.
- **Also tried and reverted.** Cutting the engine's tick at its driver-service
  return, to match the reference's sampling point and move the post-service DAC
  bytes into the next tick, is the actual cause of the phase. It emptied the
  engine's write stream, taking the state-with-writes line from `MATCH (2198
  ticks)` to a divergence at tick 0, and was reverted rather than pursued: the
  write partition is fully matching and is worth more than the DAC line.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the three request windows
  `MATCH` at 25, 52 and 27 production transfers; audio packages plus the four
  extra classes with three ROM paths: 2,058 tests, 0 failures, 0 errors, 10
  skips.

## 2026-09-04 - The rest-bit contradiction settled, and a duration-only unit fixed

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `a85d1f7b6`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 551, role `MUS_PSG3`, field
  `resting`, reference `false` against engine `true`. DAC stream
  `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 551, event 151, reference
  `psg 192` against engine `psg 223`. DAC stream unchanged. Every compared
  field at 551 now agrees; the remaining difference is 151 writes into that
  service.
- **The contradiction is settled, and both of my candidate explanations were
  wrong.** A probe over the snapshot's own sequencer list shows exactly one
  entry at services 546 through 556, so the comparator was never reading a
  stale instance. Threading the oracle's own ordinal into the sequencer, in
  place of the probe's home-made service counter, shows what the earlier
  probe had mis-numbered: at service 551 the engine's `playNote` runs with
  note `80h` and correctly rests the track for the byte it read. Engine and
  reference were reading *different* notes, so the rest bit was a symptom.
- **What the streams actually do at 551, decoded on both sides.** The
  reference's data pointer moves 63039 to 63281, a jump of 242 bytes. The
  engine reads `0F8h` at offset 3728, jumps to 3966, reads `0E5h`, then reads
  `06h` at 3969 and ends at 3970: the same 242 bytes. The pointers agree. The
  final byte is a *duration*, not a note.
- **The defect.** `playNote` recomputed the rest bit from `t.note` on every
  call, including a unit that carried only a duration, where `t.note` still
  holds the previous unit's byte. The previous byte here was a rest, so the
  engine re-rested a track the ROM had just brought out of rest. The ROM does
  not: `zGetNextNote` clears the bit on entry (Sound/Z80 Sound
  Driver.asm:910-911) and a positive byte goes straight to `zStoreDuration`
  (:917-919), which touches neither the rest bit nor the saved note. Only a
  note byte of `80h` reaches `zRestTrack`. `playNote` now takes whether the
  unit carried a note byte, and the duration-only call site passes false.
- **This is the narrow form of the change that was rejected last cycle.**
  Clearing the rest bit for *every* read broke the S2 driver oracle at tick
  208. Clearing it only where the ROM's own path cannot re-set it leaves all
  four S2 oracles at MATCH, checked by reading their lines: the v1 driver
  oracle at 698 ticks, the v2 state-and-writes and state-only oracles at 2,198
  ticks each, and the three request windows at 25, 52 and 27 transfers.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape. The rest-bit clear at the note read leaves the open list, since this
  entry resolves it.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; all four S2 oracles MATCH as listed above. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,154
  tests, 0 failures, 10 skips.


## 2026-09-04 - S2 driver-state v2 reaches MATCH on all 2,198 services; the DAC join is duration, proven

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `a0a5a47c7`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before:** state with writes and state only both DIVERGENCE at tick 1,789
  (movie row 11991), `global.currentTempo`, reference `0x9e` against the
  engine's `0xbe`; 409 of 2,198 ticks divergent. DAC stream `BYTE DIFFERENT in
  run 3 at byte 709 (92 runs, run-length delta 0)`.
- **After:** state with writes `MATCH (2198 ticks)` and state only
  `MATCH (2198 ticks)`. DAC stream `BYTE DIFFERENT in run 3 at byte 709:
  reference 0x80, engine 0x8D (92 runs, run-length delta 0, reference resyncs
  at engine byte 828 for 3612 bytes)`.
- **The tempo closed from the gameplay side.** The speed-shoes countdown moved
  to the ROM's display step on develop, so this branch's merge of `a0a5a47c7`
  took the last state divergence with it. Nothing on the audio side changed for
  it.
- **The DAC join is duration, and that is now proven rather than argued.** The
  reference's bytes over its services 150-155 sum to exactly 709 and its byte
  709 is `0x80`, the value `zWriteToDAC`'s accumulator starts every sample at
  (s2.sounddriver.asm:502-517); the step from the preceding `0x89` is minus
  nine, which no `zDACDecodeTbl` entry produces, so a second sample begins
  there. Aligning each side's second sample by its own start, **3,612 bytes
  agree with zero mismatches**, and the first sample's 709 bytes had already
  agreed. The engine had played 828 bytes of the first sample where the ROM
  played 709, a ratio of 1.17 that matches the service cost it does not charge.
- **The other two hypotheses are ruled out.** It is not sample data: both
  samples decode identically once aligned. It is not the engine's DAC
  interpolation leaking into the observed stream: `Ym2612Chip`'s interpolated
  write deliberately does not call the write observer, because it has no ROM
  counterpart, so no synthetic byte can reach the compared stream.
- **What landed.** The comparator now reports, beside an existing byte
  difference, where the reference's remaining bytes resume in the engine's run
  and how many agree from there. It decides nothing and suppresses nothing; it
  distinguishes a merged-play join, which resyncs, from a decode or selection
  error, which does not. The excusal and all three rejected boundary rules are
  written up in docs/status/known-discrepancies.md under "S2 Music DAC Byte
  Stream Partition (Oracle Comparison)".
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the three request windows
  `MATCH` at 25, 52 and 27 production transfers; audio packages plus the four
  extra classes with three ROM paths: 2,058 tests, 0 failures, 0 errors, 10
  skips.

## 2026-09-04 - S2 DAC run 3 is a supersede with no gap, and no symmetric run boundary exists

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, at `042b8a858`.
- **Measurement, not a comparison run.** Three candidate run rules were built
  and measured, and all three were reverted. The committed comparator is
  unchanged, and so are its lines: state with writes and state only both stop at
  tick 1,789 `global.currentTempo`; DAC stream `BYTE DIFFERENT in run 3 at byte
  709, reference 0x80, engine 0x8D (92 runs, run-length delta 0)`.
- **Run 3's byte 709 is not a decode error.** The reference's bytes over its
  ticks 150-155 sum to exactly 709, and its byte 709 is `0x80`, the value
  `zWriteToDAC`'s accumulator starts every sample at (`ld a,80h` /
  `ex af,af'` in `zVInt`'s `.dacqueued`, s2.sounddriver.asm:502-517). The step
  from the preceding `0x89` to `0x80` is minus nine, which no entry of
  `zDACDecodeTbl` can produce. So a second sample begins there, and the engine's
  `0x8D` is a legitimate continuation of the first, plus four, which the table
  does contain. Both sides play the same sample correctly; they merely reach the
  join at different byte offsets.
- **The join has no gap, which is why the committed rule misses it.** The
  reference's own `zCurDAC` changes at its tick 155 while that service is still
  emitting 121 bytes of the outgoing sample, and the new sample's bytes begin at
  tick 156. No service is silent, so the gap rule cannot see the boundary.
- **Three rules were tried and each failed for a stated reason.**
  1. *Gap, plus a selector change closing the changing service.* Moved the
     failure to run 4 byte 0 at 100 runs, because the engine's selector and its
     bytes change in the same service while the reference's selector leads its
     bytes by one.
  2. *Selector change alone.* 91 runs, failure at run 1 byte 0. Measured cause:
     two consecutive plays of one sample change no selector at all. Over the
     window's first two plays the reference's selector reads `0` throughout and
     the engine's reads `129` throughout, and only the silent services between
     them separate the plays.
  3. *Selector change, phased per producer* — closing after the changing service
     for the ROM, before it for the engine, on the stated ground that
     `zUpdateDAC` arms a sample the loop can only play after the service returns
     while the engine's pump starts it in the same service. This is the closest
     to correct and was still reverted, because an asymmetric rule makes the
     comparison disagree with itself: the break-it control feeds the reference
     to both sides and it failed, `expected MATCH but was BYTE_DIFFERENT`.
     Weakening that control to land the rule would remove the only evidence the
     comparison works at all.
- **What this means.** A supersede with no intervening gap is not comparable
  under a partition that must be symmetric, because the two producers phase a
  queued sample differently and that phase is the service-duration quantity
  already excused for this stream. The honest next step is not another boundary
  rule. It is either an engine-side signal that marks the first byte of a
  sample directly, independent of service phase, or accepting the merge and
  bounding each run's comparison at its own sample's decoded length, which the
  ROM knows from `zDACLenTbl` and the engine from its `DacData`.
- **Gates unchanged**, since nothing landed: S2 v1 driver oracle `MATCH (698
  ticks)`, request windows `MATCH` at 25, 52 and 27 transfers.

## 2026-09-04 - S3K tick 551, and an S2 regression that no gate would have failed on

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, engine at `221d275db`.
- **Measurement, not a comparison run.** Nothing landed from this cycle. It
  records one rejected change, the reason it was rejected, and a contradiction
  in my own measurements that the next round should settle before building on
  either side of it.
- **The frontier.** `TRACK_STATE_MISMATCH`, tick 551, role `MUS_PSG3`, field
  `resting`, reference `false` against engine `true`. The DAC stream is
  unchanged at run 338 byte 0. At service 551 both sides read a new unit: the
  data pointer jumps from 63039 to 63281, the saved duration goes from 42 to
  6, and the envelope index resets. The reference leaves rest; the engine does
  not.
- **The obvious fix is right about the ROM and wrong for the engine.**
  `zGetNextNote` clears the rest bit with `res 4` in the instruction after the
  `res 1` the engine already models, before the coordination-flag loop
  (Sound/Z80 Sound Driver.asm:910-911). Adding that clear moved the S3K
  frontier not at all, and it broke S2: the S2 driver oracle went from
  `MATCH (698 ticks)` to `DIVERGENCE at tick 208, field writes[133],
  expected psg 087h against psg 09Fh`. Reverting the one line restores
  `MATCH (698 ticks)`. The change was dropped rather than gated, because it
  buys S3K nothing and the S2 behaviour it disturbs is not understood.
- **That regression would have shipped.** The S2 driver oracle's result is
  printed on a `MEASUREMENT_ONLY` line, so the audio gate stayed at 2,154
  tests and 0 failures with the regression present. It was caught only by
  reading the line. Anything touching shared sequencer state needs that line
  read, not just the failure count.
- **A contradiction to settle first, stated so it is not built on.** A
  stack-tagged probe over the services around 551 shows the engine's
  `playNote` setting `resting = false` for PSG3, with no later `restTrack`
  call in that service, yet the end-of-service snapshot the comparator reads
  has `resting = true`. Both cannot be right. The candidates are the probe's
  own service numbering, which counts music services and not oracle ordinals,
  and the possibility that the snapshot is taken from a different sequencer
  instance than the one the probe watched. Resolve that before proposing a
  mechanism.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); S1's and S2's per-track PSG silence shape;
  and now S1's and S2's rest-bit clear at the note read, which the listings
  place in their DoNext routines but which the engine cannot adopt without
  disturbing the S2 oracle.


## 2026-09-04 - zRestTrack falls through into the PSG silence; tick 502 -> tick 551

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `de1fe004c`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_MISSING`, tick 502, event 10, reference
  `psg 223` against `<missing>`. DAC stream `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 551, role `MUS_PSG3`, field
  `resting`, reference `false` against engine `true`. DAC stream unchanged.
  The whole 49-service run of missing silences, 502 through 550, now agrees.
- **The routine, and it is a fall-through with no `ret` of its own.**
  `zRestTrack` sets the rest bit, returns only when an SFX is overriding the
  track, and otherwise runs straight on into `zSilencePSGChannel`, which is
  the very next routine in the listing (Sound/Z80 Sound Driver.asm:4220-4245).
  So resting a PSG track the driver still owns also silences it, and because
  a parked volume envelope re-reads its command every pass, the silence
  repeats every pass.
- **A correction to my own earlier entry in this branch.** The 2026-09-04
  entry on the `81h`/`83h` envelope rest commands said "neither silences the
  channel, which the ROM says outright at :4208". The quoted remark belongs to
  `zSilencePSGChannel`'s noise test, not to the rest path, and I read the
  `ret nz` at :4223 as ending `zRestTrack`. It does not. `83h` reaches
  `zRestTrack` and therefore silences; `81h` sets the bit itself and returns,
  and genuinely writes nothing. The two are now modelled separately, and the
  PSG note-fill tail routes through the same helper.
- **The candidate this round was given, and why it is refuted by the
  listing.** The fade handlers were proposed as the writer. Neither can be:
  `zDoMusicFadeOut`'s loop bound is literally
  `(zSongPSG1-zTracksStart)/zTrack.len`, so it walks DAC and FM only and its
  per-track call is `zSendTL`, which is FM-only (:2331-2385).
  `zDoMusicFadeIn` walks `(zSongPSG1-zSongFM1)` FM tracks for volume and
  touches PSG tracks only at completion, where it clears their SFX-override
  bit and writes nothing (:2386-2446). No PSG volume leaves either handler.
- **What actually located it was the pattern's shape.** Scanning the whole
  reference for services ending in `0DFh 0FFh` gives 1,247 of 5,263: isolated
  ones at services 0, 1, 49, 128, 421 and 495, all of which are stop or
  silence-all events, then a 49-service run from 502 to 550, then two-service
  bursts every eight services at 557, 573, 581, 589, 597 and 605. A run plus a
  rhythmic burst pattern is a track's own part, not a global, and per-pass
  repetition is what a parked envelope command produces.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape, which no routine in either listing pins.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestSonic3kFm3SpecialMode`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,154
  tests, 0 failures, 10 skips. `TestSonic3kFm3SpecialMode` joins the per-cycle
  gate for the same reason `TestSonic3kCoordFlagParity` did.


## 2026-09-04 - The S2 tempo divergence closes: the speed-shoes countdown moves to the ROM's display step

- **Worktree/branch:** `.worktrees/s2-speedshoes-timer`,
  `bugfix/ai-speed-shoes-timer-phase`, over `develop` at `b637f4171`.
- **Command, before and after, same worktree, same properties:**

  ```
  LUA_BIN=lua5.4 mvn -Dmse=off -Dtest=com.openggf.tools.audio.parity.s2.TestS2DriverStateOracle \
    '-Dsonic2.rom.path=<worktree>/s2.gen' \
    '-Ds2.request.bk2.path=<worktree>/src/test/resources/traces/s2/runs/
     s2-sonic-tails-complete-emeralds/sonic-2-sonic-tails-complete-emeralds.bk2' test -B
  ```

  The before arm was measured by reverse-applying this branch's own diff in this
  worktree, so both arms differ only by the change.
- **State only: DIVERGENCE at tick 1,789 (movie row 11991), `global.currentTempo`,
  reference `0x9e` against `0xbe`, 409 of 2,198 ticks divergent -> MATCH (2,198
  ticks).** The whole compared window now agrees on driver state.
- **State and writes:** first divergence unchanged at tick 228 (movie row 10430),
  field `writes[2]`, reference `ym1[0xb1]=0x4` against the engine's `psg=0x87`;
  divergent ticks 496 -> 178 of 2,198. The DAC byte stream is unchanged, still
  `BYTE DIFFERENT` in run 3 at byte 709.
- **The fix, and it removed a constant rather than adding one.** The previous
  entry located the offset outside the audio layer:
  `PowerUpRules.speedShoesTimerPrePhysicsExtraTicks` was `1` for S1 and S2,
  added to the countdown's duration so the physics restore would land on the
  ROM's frame given that the engine ticked timers before the movement step.
  The ROM does both consequences on one frame, in `Sonic_ChkShoes` at the tail
  of `Sonic_Display` -- restore top speed, acceleration and deceleration, then
  jump to `PlayMusic` with `MusID_SlowDown` (`docs/s2disasm/s2.asm:36307-36326`,
  and the same shape at `docs/s1disasm/_incObj/01 Sonic.asm:182-204` and
  `docs/skdisasm/sonic3k.asm:22103-22127`). Hanging both on one compensated
  countdown pushed the music command a frame past the ROM's, which is one
  driver service. The countdown is now a `DisplayPhaseTimer` driven by
  `SpriteManager` where the ROM calls `Sonic_Display`, after the movement modes
  (`s2.asm:36242,36248`), so the queue write reaches the same frame's service.
  The constant is deleted.
- **Regression gates at this commit.** The full `-Ptrace-replay` profile with
  all three ROM paths: 854 tests, 8 failures, 6 skips, the same eight classes
  with byte-identical messages before and after, so no trace frontier moved.
  The v1 S2 driver oracle still reports `MATCH (698 ticks)` and the three
  request windows `MATCH` at 25, 52 and 27 transfers. Both S1 gameplay driver
  oracles pass at their pinned 2,562 and 5,257 ticks. Parity suite 178 tests,
  0 failures, 2 skips; ordinary suite 16,399 tests, 0 failures, 17 skips;
  `-Pguards` 607 tests, 0 failures.
- **Not measured here.** The S1 sound-test music and SFX capture gates
  (`MATCH (14690 ticks)` / `MATCH (1967 ticks)`) run through
  `tools/audio/run_s1_audio_parity.sh`, which needs the TraceChaser submodule
  and a BizHawk 2.11 installation; neither is present in this worktree. The
  sound test drives no level and no monitor, so no speed-shoes countdown
  exists on that path.

## 2026-09-04 - S3K tick 502: a per-service PSG3 and noise silence with no ROM writer found

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, engine at `cf0f5d6d2`.
- **Measurement, not a comparison run.** No engine behaviour changed. This
  records what the frontier is and, more usefully, four readings that are
  already ruled out, so the next round does not spend them again.
- **The frontier.** `EVENT_MISSING`, tick 502, event 10: the reference emits
  `psg 0DFh` where the engine emits nothing. The DAC stream is unchanged at
  run 338 byte 0.
- **What the reference does.** From service 502 onward, every service ends
  with the same eight PSG bytes: three frequency pairs, `80h 00h`,
  `0AFh 0FFh` and `0C0h 00h`, then `0DFh` and `0FFh`. The engine produces the
  three pairs and neither of the last two. The pair persists at services 503,
  504, 506, 510 and 520, so it is a steady per-service write, not a one-off.
- **`0DFh` is PSG3's volume latch and `0FFh` is the noise channel's**, decoded
  from the byte layout: bit 7 latch, bits 6-5 the channel, bit 4 selecting
  volume over tone. So the ROM writes a silence-level volume to PSG3 and to
  the noise channel on every service.
- **Ruled out, each by measurement.**
  1. *An SFX taking the channel.* At service 502 the only playing tracks are
     `MUS_PSG1`, `MUS_PSG2` and `MUS_PSG3`; no SFX role is playing on either
     side, and the mailbox is empty from services 496 through 506.
  2. *A state divergence behind the writes.* Every compared track field agrees
     at 502, which is why the comparator reports a write rather than a field.
     A probe over 499-505 confirms `MUS_PSG3` is resting on both sides with
     the same duration, saved duration, volume and frequency.
  3. *The engine's envelope-hold gate on the PSG volume.* Removing
     `envAtRest` from the volume gate for S3K moves nothing, so that flag is
     not what suppresses the write. The change was reverted.
  4. *The tempo-delay service.* Service 498 has the same accumulator carry as
     502 and carries no such pair, so the carry is not the trigger.
- **What makes it puzzling, stated plainly so the next round starts here.**
  `zUpdatePSGTrack`'s volume tail is gated on the rest bit: `.no_volenv` tests
  `bit 4, (ix+zTrack.PlaybackControl)` and returns when it is set (Sound/Z80
  Sound Driver.asm:4098-4101). All three music PSG tracks are resting, so on
  that reading the ROM should write no PSG volume at all here. Something
  writes both bytes anyway, every service. The candidates not yet checked are
  a writer outside `zUpdatePSGTrack` entirely, and the possibility that the
  recorded RAM window's rest bit is sampled after a routine that clears and
  re-sets it within the service.
- **Open items, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; S2's `zFadeOutMusic` clearing `SpeedUpFlag`
  (s2.sounddriver.asm:1677-1679); and S1's and S2's per-track PSG silence
  shape, which no routine in either listing pins.


## 2026-09-04 - S3K silences a PSG track's own channel first; tick 495 -> tick 502

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `0b3aca34b`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 495, event 161, reference
  `psg 223` against engine `psg 255`. DAC stream `BYTE_DIFFERENT` run 338
  byte 0.
- **Result after:** `EVENT_MISSING`, tick 502, event 10, reference `psg 223`
  against `<missing>`. DAC stream unchanged.
- **The routine.** `zSilencePSGChannel` writes `1Fh + VoiceControl` first,
  which is the track's own tone channel, and only then adds `0FFh` for the
  noise channel, gated on `PlaybackControl` bit 0 (Sound/Z80 Sound
  Driver.asm:4226-4245). The engine wrote a single byte for whichever channel
  the track was sounding on, so a noise PSG3 track emitted `0FFh` alone where
  the ROM emits `0DFh` then `0FFh`.
- **The listing calls its own bug here, and it matters.** Under
  `fix_sndbugs = 0` the noise test is bit 0 of `PlaybackControl`, and the
  comment says outright that "since this function is called when a new channel
  is starting, this bit will almost inevitably be 0 and the noise channel will
  not be silenced". So most calls emit the tone byte alone; service 495 is one
  where the bit was set and both went out.
- **Instrumented, not assumed.** A stack-tagged probe on every PSG `stopNote`
  showed 27 calls for a noise PSG3 track, all from the note-start path, which
  is what put the lone `0FFh` on the bus.
- **Scoped to S3K, and the reason is that there is nothing to cite for the
  others.** Neither S1 nor S2 has a per-track PSG silence routine; both
  silence all four channels at once (s2.sounddriver.asm:1412-1418). Their
  behaviour is therefore unaudited rather than established, and the new
  `PsgSilenceShape` defaults to what the engine already did for them.
- **Open items.** S2's `zNoteFillUpdate` countdown against the engine's
  elapsed comparison; S1 and S2's post-note do-not-attack clear; the
  `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297; S2's
  `zFadeOutMusic` clearing `SpeedUpFlag` with `xor a / ld (zAbsVar.SpeedUpFlag), a`
  (s2.sounddriver.asm:1677-1679), which the engine does not model; and now
  S1's and S2's own per-track PSG silence shape, which no routine in either
  listing pins. None of these is touched in this lane.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestSonic3kCoordFlagParity`, `TestRewindCoverageGuard` and
  `TestStaticStateRewindCoverageGuard`: 2,142 tests, 0 failures, 10 skips.
  `TestSonic3kCoordFlagParity` is now in the per-cycle gate, because its two
  end-state snapshots are exactly the kind of assertion the audio gate used to
  miss. The whole suite with three ROM paths is 16,397 tests, 0 failures, 22
  skips, and `-Pguards` is 607 tests, 0 failures.
- **One more engine-behaviour pin needed updating, on this same routine.**
  `TestSonic3kFm3SpecialMode`'s `F3` test asserts the PSG write list as
  incidental context around its real subject, which is raw-operand retention.
  Each silence of its noise PSG3 track is a `zSilencePSGChannel` call, so the
  tone byte now precedes the noise byte on every call and the list went from
  `0DFh, 0E5h, 0FFh, 0FFh` to `0DFh, 0E5h, 0DFh, 0FFh, 0DFh, 0FFh`. The
  previous list recorded the engine emitting the noise byte alone; the new one
  follows the routine call for call. Like the two `TestSonic3kCoordFlagParity`
  snapshots, it is reached only by the whole suite.


## 2026-09-04 - S2 releases every PSG lock before restoring; the write stream reaches the tempo frontier

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `b637f4171`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before, state with writes:** DIVERGENCE at tick 584 (movie row 10786),
  field `writes.count`, reference 9 against the engine's 8; 411 of 2,198 ticks
  divergent.
- **After, state with writes:** DIVERGENCE at tick 1,789 (movie row 11991),
  field `global.currentTempo`, reference `0x9e` against the engine's `0xbe`;
  409 of 2,198 ticks divergent. The write stream now agrees over every service
  up to the tempo frontier, and the two result lines have converged on the same
  tick and the same field.
- **DAC stream:** unchanged, `BYTE DIFFERENT in run 3 at byte 709`.
- **The divergence.** At tick 584 the reference emitted `psg=FF psg=E7` and the
  engine only `psg=FF`. The missing byte is a PSG3 noise re-latch.
- **The routine.** `zStopPSGSFXTrack` clears the SFX override bit on the
  corresponding music track, marks it resting, and if that track's
  `VoiceControl` is `0E0h`, a PSG3 noise track, writes its stored `PSGNoise`
  byte back to the chip (s2.sounddriver.asm:3581-3587).
- **What was actually wrong, measured rather than inferred.** The engine did
  attempt the write. A probe on the release showed it firing at the right
  moment with the track active and its noise parameter `7`, and a second probe
  on the driver's music write path caught the byte being dropped:
  `psg=E7 ch=3 own=3 lockHeld=true`. A noise byte's ownership channel is the
  noise channel, not the track's own, and the release loop cleared the lock on
  channel 2 and ran the override update from inside the same iteration, before
  it had reached channel 3, which this same SFX still held.
- **Where it landed.** `SmpsDriver.reconcileInactiveSfxTracks` now releases all
  of the sequencer's PSG locks first and runs the override updates in a second
  pass. The ROM has no per-channel hardware ownership that can be half
  released: it clears one bit on the music track and the write goes out.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; request windows `MATCH` at
  25, 52 and 27 production transfers; audio packages plus the four extra classes
  with three ROM paths: 2,058 tests, 0 failures, 0 errors, 10 skips.
- **Next, and it is no longer an audio-lane frontier.** Both lines now stop at
  the speed-shoes tempo, which is the gameplay timer compensation constant
  recorded two entries below and handed to a gameplay lane with the trace
  sweeps as its gates. The remaining audio-side frontier is the DAC stream's
  run-3 byte difference.

## 2026-09-04 - S2 SFX takes a PSG channel without silencing it; tick 557 -> tick 584

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `b637f4171`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before, state with writes:** DIVERGENCE at tick 557 (movie row 10759),
  field `writes[38]`, reference `psg=0xe7` against the engine's `psg=0xff`;
  455 of 2,198 ticks divergent.
- **After, state with writes:** DIVERGENCE at tick 584 (movie row 10786), field
  `writes.count`, reference 9 against the engine's 8; 411 of 2,198 ticks
  divergent.
- **DAC stream and state only:** both unchanged.
- **The divergence.** At tick 557 the engine emitted an extra `psg=FF`, a
  maximum-attenuation latch on the noise channel, immediately before the SFX's
  own `psg=E7` noise-control write. Tick 558 showed the same extra `FF` between
  the reference's `psg=9F` and `psg=F2`.
- **How it was attributed.** A probe on every PSG write printed its call stack.
  The extra byte did not come from the sequencer at all: it came from
  `SmpsDriver.writePsg`'s takeover path, which silences a PSG channel it is
  about to take from the music. The sequencer's own writes agreed exactly.
- **The routine.** `zPlaySound`'s `.sfxinitpsg` silences only PSG3, and only
  through the explicit `or 1Fh` / `xor 20h` pair that writes `DF` then `FF`
  (s2.sounddriver.asm:2221-2228). Every other PSG channel is claimed by nothing
  more than `set 2,(hl)` on the corresponding music track (:2243-2245), with no
  register write at all; the SFX's own bytecode owns everything visible from
  there. The engine had S2 on the builder default `FORCE_SILENCE`, while S1,
  whose loader is the same shape, was already off it.
- **Where it landed.** `Sonic2SmpsSequencerConfig` now sets
  `PsgSfxTakeoverMode.REGISTER_SEQUENCE`. The PSG3 pair itself is untouched: it
  is already modelled by the separate `psg3SfxAdmissionWriteMode`, and both
  sides still emit `DF FF` at the head of tick 557.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; request windows `MATCH` at
  25, 52 and 27 production transfers; audio packages plus the four extra classes
  with three ROM paths: 2,058 tests, 0 failures, 0 errors, 10 skips.
- **Next.** Tick 584 is a write-count difference, reference 9 against the
  engine's 8, so the engine is now missing a write rather than adding one.
  `fmSfxTakeoverMode` is still on the builder default `FORCE_RESET` for S2 while
  `.sfxinitfm` sets the same override bit and writes nothing (:2238-2245); that
  is the obvious next thing to check, with its own evidence.

## 2026-09-04 - S2 walks the fixed SFX RAM slots, not the SFX header order; tick 228 -> tick 557

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `b637f4171`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before, state with writes:** DIVERGENCE at tick 228 (movie row 10430),
  field `writes[2]`, reference `ym1[0xb1]=0x4` against the engine's
  `psg=0x87`; 496 of 2,198 ticks divergent.
- **After, state with writes:** DIVERGENCE at tick 557 (movie row 10759), field
  `writes[38]`, reference `psg=0xe7` against the engine's `psg=0xff`; 455 of
  2,198 ticks divergent.
- **DAC stream and state only:** both unchanged.
- **How the divergence was attributed, rather than guessed.** Probes printed
  the music sequencer's track walk, an end marker per track, a walk-end marker,
  and every chip write inline. At row 10430 the music walk ran
  `DAC, FM1-FM5, PSG1-PSG3` in the ROM's own order and produced only
  `psg=B9` and `psg=F4`. The two writes the comparator flagged, a PSG1 note
  pair and an FM5 voice load plus note, were both emitted *after* `walk-end`,
  so neither came from the music walk at all. They are the SFX pass handing
  channels back.
- **The routine.** `zVInt` updates the SFX tracks by stepping `ix` through the
  fixed SFX RAM region: `SFX_FM_TRACK_COUNT` tracks in `.fmloop`, then
  `SFX_PSG_TRACK_COUNT` more in `.psgloop` (s2.sounddriver.asm:465-487). It
  never consults the order the SFX header listed its tracks, so every FM SFX
  slot is serviced before any PSG SFX slot. The engine had S2 on the builder
  default `HEADER_ORDER` while S1, whose driver does the same thing, was
  already on `CHANNEL_RAM_ORDER`.
- **Where it landed.** `Sonic2SmpsSequencerConfig` now sets
  `SfxTrackWalkMode.CHANNEL_RAM_ORDER`. No constant was introduced and nothing
  is keyed on a zone, route or fixture.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; request windows `MATCH` at
  25, 52 and 27 production transfers; audio packages plus the four extra classes
  with three ROM paths: 2,058 tests, 0 failures, 0 errors, 10 skips.
- **Next.** Tick 557 is a PSG value difference at `writes[38]`, reference
  `0xe7` against the engine's `0xff`. Both are channel 3 volume writes, so the
  two sides disagree about the noise channel's attenuation rather than about
  ordering.

## 2026-09-04 - S2 tick 228 is a music walk-order difference, not a value difference

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, at `d82441942`.
- **Measurement, not a comparison run.** No engine behaviour changed. Lines are
  unchanged: state with writes DIVERGENCE at tick 228 (movie row 10430), field
  `writes[2]`, reference `ym1[0xb1]=0x4` against the engine's `psg=0x87`, 496 of
  2,198 ticks divergent; DAC stream `BYTE DIFFERENT in run 3 at byte 709`;
  state only tick 1,789.
- **The two sides emit the same writes in a different order.** At tick 228,
  after two leading PSG writes both sides agree on:

  - reference: an FM voice-load block ending `ym0[28]=F5`, then `psg=87 psg=0E`
  - engine: `psg=87 psg=0E`, then the identical FM voice-load block

  Nothing differs in value, register or count. The engine runs that PSG track
  before that FM track; the ROM runs it after. Tick 229 repeats the pattern.
- **The ROM's walk is fixed and cited.** `zUpdateMusic` updates the DAC track,
  then loops `MUSIC_FM_TRACK_COUNT` times over the FM tracks, then
  `MUSIC_PSG_TRACK_COUNT` times over the PSG tracks
  (s2.sounddriver.asm:554-575). Every FM music track therefore writes before
  every PSG music track within one service.
- **What is different about this tick, as a hypothesis rather than a finding.**
  At tick 226 the engine ordered FM before PSG correctly, and the ticks that
  diverge are the ones whose FM update carries a full voice load
  (`ym1[B1]`, the operator block, `ym1[41]`-`ym1[4D]`). Whether the engine
  defers a voice-load-bearing FM update past the PSG tracks, or reaches it by
  another route, was not established here and should be instrumented rather than
  assumed.
- **Not a partition artefact.** The two leading PSG writes are identical on both
  sides and are the same on the surrounding ticks, so wherever the service
  boundary places them, the reordering sits between the FM block and the PSG1
  frequency pair.

## 2026-09-04 - An S3K load service does not accumulate for the song it loads

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `38e8777b5`.
- **Command:** unchanged, both result lines.
- **Result before:** `GLOBAL_STATE_MISMATCH`, tick 495, field
  `tempoAccumulator`, reference `64` against engine `128`. DAC stream
  `BYTE_DIFFERENT` run 338 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 495, event 161, reference
  `psg 223` against engine `psg 255`. DAC stream unchanged. The tick is the
  same, but the divergence moved from the service's global state to a write
  161 events into that service's own burst.
- **The routine.** `TempoWait` lives in `zUpdateEverything`, ahead of
  `zUpdateMusic` and its `zFillSoundQueue` (Sound/Z80 Sound
  Driver.asm:653-701, :2607-2621). So the service that loads a song has
  already accumulated, using the *previous* tempo, before the load exists, and
  `zBGMLoad`'s `ld (zTempoAccumulator), a` (:1829-1831) is the value that
  service ends on. The engine seeded the accumulator and then accumulated
  again in the same service, giving twice the tempo. The newly loaded song's
  own first accumulation belongs to the next service; its track walk still
  runs in the load service, which is why the writes were already right.
- **Why this never showed until service 495.** The title music loaded at
  service 139 has tempo 0, so the engine's first-service path took its
  tempo-zero branch and accumulated nothing. Service 495 is the first load of
  a song with a non-zero tempo, 64, and the difference is exactly one
  accumulation of it: reference 64, engine 128, and the engine stayed one
  accumulation ahead for every service after.
- **This is the same ordering fact as the fade in the previous commit,** seen
  in a second place. Both come from `zUpdateEverything` doing its own work
  before the mailbox is read. S1 and S2 run their tempo step inside the music
  update, after the queue is filled, and do accumulate on the load service,
  which the engine already modelled and their oracles confirm; the new
  `tempoWaitPrecedesRequest` flag defaults to their behaviour.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown; S1 and S2's
  post-note do-not-attack clear; the `.dac_playback_loop` cycle total of 303
  against `baseCycles` of 297; and S2's `zFadeOutMusic` clearing
  `SpeedUpFlag`.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips. The whole suite with three ROM paths is 16,396
  tests, 0 failures, 22 skips, and `-Pguards` is 607 tests, 0 failures.
- **Two snapshots outside the audio gate needed updating, and the reason is
  recorded.** `TestSonic3kCoordFlagParity`'s two `zDoModulation` tests assert
  a packed frequency captured after running the engine for 60,000 samples.
  Those are end-state snapshots of engine output, not ROM-derived constants,
  and they shift whenever the run's start phase does. The fix that moved them
  is this entry's own: not accumulating tempo on the load service removes one
  accumulation from the very start of the run, so every later service of the
  60,000-sample run is reached one accumulation earlier in the tempo cycle and
  the final modulated frequency lands elsewhere. `2A94h` became `2A84h` and
  `2AADh` became `2AD6h`. Neither value was chosen; both were read back after
  the change, and they are snapshots precisely so a future shift is visible. The mechanisms they
  name, the step-counter decrement on sustain ticks and the wait-zero
  same-tick application, are untouched, and the reference oracle is the
  authority for the shift. Both now carry a comment saying what kind of value
  they hold. They are reached only by the whole suite, not by the audio gate,
  which is why they surfaced at the final full-suite run rather than at the
  per-cycle one.


## 2026-09-04 - The S3K music fade becomes driver state; tick 421 -> tick 495

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `38e8777b5`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 421, role `MUS_DAC`, field
  `playing`, reference `false` against engine `true`. DAC stream
  `BYTE_DIFFERENT` run 29 byte 0. Five services reported
  `active music fade for request 0xe1 is not modelled by this capture host`.
- **Result after:** `GLOBAL_STATE_MISMATCH`, tick 495, field
  `tempoAccumulator`, reference `64` against engine `128`. DAC stream
  `BYTE_DIFFERENT` run 338 byte 0. **No unsupported requests at all.**
- **The request.** Both `0E1h` and `0E5h` dispatch to `zFadeOutMusic`
  (Sound/Z80 Sound Driver.asm:1668, :1672). It sets `zFadeOutTimeout` to
  `28h` and both `zFadeDelayTimeout` and `zFadeDelay` to 6, then falls through
  into `zHaltDACPSG`, which zeroes the playback control of FM6/DAC, PSG3,
  PSG1 and PSG2 and jumps to `zPSGSilenceAll` (:2307-2325). The halt itself
  writes nothing; only `zPSGSilenceAll` does, which the host already applied.
  `zDoMusicFadeOut` then runs once per `zUpdateMusic` (:2331-2385).
- **Three per-driver differences, each measured and each cited.**
  1. *What the request halts.* S2's `zFadeOutMusic` stops only the DAC track,
     commenting "can't fade it" (s2.sounddriver.asm:1668-1681). S3K halts the
     three PSG tracks as well. New `SmpsSequencerConfig.FadeOutHalt`,
     defaulting to `DAC_ONLY`.
  2. *How the delay counter is tested.* S2 reads the delay, steps when it is
     already zero, and otherwise decrements and returns (:1686-1697), so a
     delay of 3 steps on the fourth service. S3K decrements first and steps
     when the result is zero (:2337-2343), so a delay of 6 steps on the sixth.
     The engine implemented S2's shape for both. New `FadeDelayCadence`,
     defaulting to S1/S2's.
  3. *When the armed fade first advances.* `zUpdateEverything` runs
     `zDoMusicFadeOut` before `zUpdateMusic` loads `zMusicNumber` for
     `zFillSoundQueue` (:653-701, :2628-2643), so the service that consumes
     the request has already run its fade handler and does not advance the
     fade it just armed. The capture host arms from the mailbox before the
     service, so without this the first step lands one service early. A fade
     armed by a coordination flag needs no such handling, because the engine's
     `musicUpdateOverflow` already runs the fade step before the track walk,
     exactly as the ROM does.
- **No constants were introduced.** `28h` and 6 come from `zFadeOutMusic` and
  were already in the S3K sequencer config from that same routine; the host
  reads them from the config rather than restating them.
- **Measured in two wrong positions before the right one, and both are worth
  recording.** With only the halt modelled, the engine was one step behind at
  service 433, reference volume 23 against 22. With the cadence corrected but
  not the ordering, it was one step ahead at 426, 21 against 22. Only with all
  three does the ramp line up, and it then agrees for the whole fade.
- **The DAC stream improved as a side effect,** from run 29 to run 338:
  halting the DAC track on the request, rather than leaving it playing,
  changes which samples the following services select. Its pin was updated
  with that reason.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown against the
  engine's elapsed comparison; S1 and S2's post-note do-not-attack clear; the
  `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297; and now
  S2's `zFadeOutMusic` clearing `SpeedUpFlag` (s2.sounddriver.asm:1677-1679),
  which the engine does not model and which was left alone here.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - S2 sends a PSG note-on frequency once, not twice; tick 170 -> tick 228

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `2fed63a53`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before, state with writes:** DIVERGENCE at tick 170 (movie row 10372),
  field `writes[2]`, reference `psg=0x90` against the engine's `psg=0x8f`;
  515 of 2,198 ticks divergent.
- **After, state with writes:** DIVERGENCE at tick 228 (movie row 10430), field
  `writes[2]`, reference `ym1[0xb1]=0x4` against the engine's `psg=0x87`;
  496 of 2,198 ticks divergent.
- **DAC stream and state only:** both unchanged, `BYTE DIFFERENT in run 3 at
  byte 709` and tick 1,789 `global.currentTempo`.
- **The divergence.** At tick 170 the reference emitted `psg=8F psg=0E psg=90`,
  one PSG1 frequency pair then the volume; the engine emitted `psg=8F psg=0E
  psg=8F psg=0E psg=90`, sending the same unchanged pair twice.
- **What the ROM actually does, against a comment that said the opposite.** The
  engine's own comment claimed "S2 genuinely sends it twice: zPSGDoNoteOn
  writes it, then zPSGUpdateFreq writes it again", citing
  s2.sounddriver.asm:1046-1053. `zPSGDoNoteOn` is not a separate write: it
  loads the frequency into `de` and falls straight through into
  `zPSGUpdateFreq`, which is the send (:1202-1209). And the cited lines are the
  proof of the opposite. `zDoModulation` pops its caller's return address on
  entry (:986-987), so each of its early returns lands past `zPSGUpdateTrack`
  and skips the trailing `jp zPSGUpdateFreq` (:1127-1131). Only the `.calcfreq`
  path returns normally, through the explicit `jp (hl)` the listing annotates
  "WILL return to zUpdateTrack" (:1046-1049). So the second send happens only
  when modulation actually recomputed the frequency.
- **Where it landed.** The note-on path set `forceModulationWrite`
  unconditionally whenever modulation was enabled, forcing a send that the ROM
  gates. It is now set only for a driver whose modulation returns into the send
  every pass, which is the property `NoteGoingFreqSend` already names. S1 sets
  `applyModOnNote(false)` and never reaches this path; S3K is `EVERY_PASS` and
  is unchanged.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; request windows `MATCH` at
  25, 52 and 27 transfers; audio packages plus the four extra classes with three
  ROM paths: 2,058 tests, 0 failures, 0 errors, 10 skips.
- **Next.** Tick 228 has the reference writing an FM register where the engine
  writes a PSG latch, so the two sides disagree about which track runs at that
  point rather than about a value.

## 2026-09-04 - S2 tempo phase: the audio layer already consumes at the head; the offset is a gameplay compensation constant

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, at `f1f721ed1`.
- **Measurement, not a comparison run.** No engine behaviour changed. The
  state-only line is unchanged: DIVERGENCE at tick 1,789 (movie row 11991),
  `global.currentTempo`, reference `0x9e` against the engine's `0xbe`.
- **The premise this round started from does not hold.** The engine does not
  consume the sound queue after its driver update.
  `AudioPresentationProducer.presentSessionForward` services the request
  boundary and applies the pending command batch *before* it calls
  `smpsSession.serviceForward()`, which is the ROM's own order: `zVInt` falls
  into `zUpdateEverything`, which runs `zCycleQueue` and `zPlaySoundByIndex`
  before either `zUpdateMusic` call (s2.sounddriver.asm:411-450).
- **Probes, on the committed BK2 run.** With prints on the speed-shoes timer,
  on the presentation's forward path and on the session's command case:

  | event | playback cursor |
  |---|---|
  | speed-shoes timer fires | 11991 |
  | next forward presentation begins | 11992 |
  | session applies the slow-down | 11992 |

  The cursor advances inside the frame's own step, so those are one engine
  frame, and the command is applied at the first driver service after the game
  wrote it. That is the same relationship the ROM has, where the 68k writes the
  queue byte in its main loop and the following V-int consumes it. The
  reference confirms the consumption side: at row 11991 the tempo is already
  `9Eh` and `QueueToPlay` is back to `80h`, so the byte arrived and was
  consumed inside that service.
- **So the offset is upstream, and it is a named constant.** The engine's
  speed-shoes expiry lands one frame later than the ROM's write.
  `PowerUpRules.speedShoesTimerPrePhysicsExtraTicks` is `1` for both S1 and S2
  (`GameRules.java:161-162`, `:316-317`), added to the timer's duration in
  `SpeedShoesTimer.durationTicks`. Its documented reason is purely about
  movement: the ROM decrements the timer inside `Sonic_Display`, which the
  control routine calls *after* dispatching the movement modes
  (s2.asm:36240-36244, decrement at :36310-36312), so the frame that zeroes the
  timer still moved boosted, and the engine ticks its timers before the
  movement step.
- **Why that constant reaches the music at all.** The ROM does both things on
  the one frame the timer hits zero: it restores top speed, acceleration and
  deceleration, and it jumps to `PlayMusic` with `MusID_SlowDown`
  (s2.asm:36313-36326). The engine hangs both on one timer, so the tick added
  to place the *physics* restore on the ROM's frame also pushes the *music*
  command a frame past it. The music consequence has no dependency on where the
  movement step sits, so it should not carry the movement compensation.
- **Not changed here, and deliberately.** The two candidate repairs both move
  gameplay timing that S2 physics traces depend on: either fire the music
  consequence `speedShoesTimerPrePhysicsExtraTicks` ticks before the physics
  one, or move the countdown after the movement step and drop the constant
  entirely. Neither belongs to an audio lane whose gate list carries no
  `*TraceReplay` sweep, and the second is the better fix precisely because it
  removes a compensation constant rather than adding a second one.
- **What this means for the frontier.** The state-only divergence at tick 1,789
  is not a driver defect and will not be fixed inside the audio layer. The 409
  ticks counted after it were never re-measured for cause; the tempo itself
  re-converges at the very next tick.

## 2026-09-04 - S2 driver-state v2: the DAC byte stream leaves the service partition; tick 0 -> tick 170

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `2fed63a53`.
- **Fixture and command:** as the earlier entries for this fixture.
- **Before, state with writes:** DIVERGENCE at tick 0 (movie row 10202), field
  `writes.count`, reference 145 against the engine's 253; 1,525 of 2,198 ticks
  divergent.
- **After, state with writes:** DIVERGENCE at tick 170 (movie row 10372), field
  `writes[2]`, reference `psg=0x90` against the engine's `psg=0x8f`; 515 of
  2,198 ticks divergent.
- **After, DAC stream (new second line):** `BYTE DIFFERENT in run 3 at byte
  709: reference 0x80, engine 0x8D (92 runs, run-length delta 0)`.
- **State only:** unchanged, DIVERGENCE at tick 1,789 (movie row 11991),
  `global.currentTempo` `0x9e` against `0xbe`. This change touches no state.
- **What moved.** The `2Ah` sample bytes now leave the per-service partition
  and are compared as their own whole-window stream, the treatment the S3K
  oracle already gives them. Which service a sample byte lands in is Z80
  duration: `zWriteToDAC` streams from outside the interrupt, bracketing each
  write with `di` / `ei` and spending the rest of its time in two `djnz $`
  busy waits (s2.sounddriver.asm:682-726), which are the only window a V-int
  can land in. The engine charges nothing for the service itself.
- **The run boundary is the ROM's, and it validated.** Sonic 2's playback loop
  writes nothing between samples: `zWaitLoop` spins on the remaining length
  being zero and touches no register (:647-650). So a completed service with no
  `2Ah` byte is a real gap. That rule gives the reference 92 runs across 2,243
  services against 91 changes of its own `zCurDAC` byte, and the engine
  independently produces the same 92, with a cumulative run-length delta of
  zero. Run structure is therefore pinned by compared data, not assumed.
- **No `2Bh` write moved.** Unlike S3K, Sonic 2's playback loop never writes the
  DAC enable per sample; every `2Bh` in this driver belongs to a song load, a
  fade or the SEGA chant (:1613, :1662, :1936, :2555, :3158), so all stay in the
  per-service partition.
- **Break-it evidence.**
  `TestS2DriverStateOracle#aCorruptedDacSampleByteBreaksTheStreamComparison`
  flips one reference sample byte and requires the verdict to move, so a stream
  that never compared cannot read as one that agreed.
- **Recorded** in docs/status/known-discrepancies.md, "S2 Music DAC Byte Stream
  Partition (Oracle Comparison)".
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the three request windows
  `MATCH` at 25, 52 and 27 production transfers; the audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, `TestRewindCoverageGuard`
  and `TestStaticStateRewindCoverageGuard` with three ROM paths: 2,058 tests,
  0 failures, 0 errors, 10 skips.
- **Next, two real divergences now visible behind the excused bytes.** The
  service line at tick 170 is a PSG attenuation off by one step, `0x90` against
  `0x8f`. The DAC line is a genuine content difference inside the fourth
  sample run, at byte 709 of that run, with the three runs before it agreeing
  in full.

## 2026-09-04 - S3K zSendTL writes all four operators; tick 342 -> tick 421

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `9c568300f`.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 342, event 4, reference
  `ym2612 port 0 register 4Ah = 16` against engine `port 0 register 28h = 2`.
  DAC stream `BYTE_DIFFERENT` run 29 byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 421, role `MUS_DAC`, field
  `playing`, reference `false` against engine `true`. DAC stream unchanged.
  Every compared field and every write now agree through service 420.
- **The routine.** `zSendTL` walks the whole FM TL table and writes an entry
  for every operator. The `or a / jp p, .skip_track_vol` test only branches
  past the track-volume add for a positive byte; the write itself is
  unconditional, and the `fix_sndbugs = 0` path strips the sign bit from what
  it sends (Sound/Z80 Sound Driver.asm:3149-3178). The engine wrote only the
  carriers, so three of the four total-level bytes never reached the chip.
  Reference service 342 shows the full set in S3K's middle-register traversal
  order: `42h = 0Fh`, `4Ah = 10h`, `46h = 32h`, `4Eh = 0Ch`, where the engine
  emitted `42h` alone.
- **This is the rule S2 already had.** `zSetFMTLs` writes every TL register
  and uses its mask only to decide where the channel volume is added
  (s2.sounddriver.asm:3385-3424, :3438-3457), which the engine already
  modelled and `TestSmpsFmVoiceWriteProfiles` already documented for S2. The
  two Z80 drivers now share one loop, differing only in the per-operator
  arithmetic: S2 rewrites non-carriers unchanged, S3K strips their sign bit.
- **A test pinned the old behaviour and was corrected, not deleted.**
  `s3kVolumeRefreshUsesBitSevenAndItsMiddleRegisterTraversal` asserted the two
  carriers alone, with no ROM citation for the omission. It now asserts all
  four with the `zSendTL` citation, and its values check out byte for byte:
  the non-carriers `15h` and `17h` go out unchanged, the carriers `96h` and
  `98h` go out as `16h` and `18h` plus the track volume.
- **The new frontier is a known capture-host limitation, not a driver defect.**
  Service 421 is the first service of an `E1h` music fade, which the host
  reports as unmodelled on every run, including before this change.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown against the
  engine's elapsed comparison; S1 and S2's post-note do-not-attack clear; and
  the `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - A resting S3K FM track freezes completely; tick 150 -> tick 342

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `9c568300f`.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 150, role `MUS_FM2`, field
  `modulationSpeed`, reference `0` against engine `1`. DAC stream
  `BYTE_DIFFERENT` run 29 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 342, event 4, reference
  `ym2612 port 0 register 4Ah = 16` against engine `port 0 register 28h = 2`.
  DAC stream unchanged. Every compared field, including the six modulation
  bytes added in the previous commit, and every write now agree through
  service 341. That is past the old service-331 frontier with strictly more
  state compared.
- **Two corrections, landed together because the second only became visible
  after the first.**
  1. *The aliased clears.* `zFinishTrackUpdate` zeroes `ModEnvIndex` and
     `ModEnvSens` for every stream unit it reads, rest or note, whenever the
     do-not-attack bit is clear (Sound/Z80 Sound Driver.asm:1055-1069). Those
     are the same bytes as `ModulationSpeed` at offset 25h and
     `ModulationValLow` at 22h (:76-92), so a track running normal modulation
     has its speed counter and the low byte of its accumulator zeroed by a
     routine that has nothing to do with normal modulation. The engine cleared
     them only when a modulation envelope was in use, which is the other
     reading of the same bytes. Landed with the ROM's own 8-bit
     `dec (ix+ModulationSpeed)` so a zeroed counter wraps to 0FFh and holds
     for 255 passes instead of advancing every pass (:1296-1301). Tick 150 ->
     151.
  2. *The rest test at the caller.* `zUpdateFMorPSGTrack`'s `.note_going`
     opens with `bit 4,(ix+zTrack.PlaybackControl) / ret nz` (:781-783), so a
     resting FM track whose note is still running advances nothing at all: no
     volume envelope, no note fill, no frequency update and no modulation
     step. `zUpdatePSGTrack` has no such test at its matching entry
     (:4066-4076), which is why a resting PSG track keeps sending its
     frequency. Tick 151 -> 342.
- **This corrects the scope of an earlier conclusion in this branch, not the
  conclusion itself.** `stepModulationAtRest` was landed on the reading that
  S3K's `zDoModulation` has no rest check. That reading is right, and the flag
  stays. What was missing is that the FM caller never reaches `zDoModulation`
  while resting, so the flag only ever applied to PSG. The two facts live at
  different sites and are cited separately.
- **The evidence was a comparison, not a decode.** With the modulation bytes
  compared, a probe over services 146 to 154 shows both sides identical
  through 150, then the reference frozen at `wait 15, speed 0, val 0, delta 6,
  steps 3` for the whole rest while the engine walked `wait` down 14, 13, 12,
  11. That is what the widened surface was for.
- **One wrong turn, caught immediately.** The first form of the rest return
  fired before the duration timer's expiry was tested, so a resting FM track
  could never read its next note and stayed rested forever; the oracle
  reported it at service 156 on the `resting` field. The ROM reaches
  `.note_going` only when the timer did not expire, so the return is gated on
  the post-decrement duration.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown against the
  engine's elapsed comparison; S1 and S2's post-note do-not-attack clear; and
  the `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - The S3K oracle now compares modulation state; reported frontier 331 -> 150

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `9c568300f`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 331, event 10, reference
  `psg 163` against engine `psg 160`. DAC stream `BYTE_DIFFERENT` run 29
  byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 150, role `MUS_FM2`, field
  `modulationSpeed`, reference `0` against engine `1`. DAC stream unchanged.
- **The frontier moved backwards on purpose, and that is the point.** The
  writes still agree through service 330; nothing regressed. Six zTrack bytes
  that the oracle never compared are now compared, and the driver state behind
  those writes turns out to diverge 181 services earlier. Comparing more state
  can only move a reported frontier earlier, never later, so this number is
  not comparable with the previous entry's and should not be read as one.
- **What was added,** from the track layout at Sound/Z80 Sound
  Driver.asm:34-97: `ModulationVal` (offsets 22h-23h, the accumulator
  `zDoModulation` adds its delta into), `ModulationWait` (24h),
  `ModulationSpeed` (25h), `ModulationDelta` (26h) and `ModulationSteps`
  (27h), all gated, plus `ModulationPtr` (20h-21h) as a diagnostic-only field
  because it is a Z80 address the engine has no equivalent for, exactly like
  the existing data pointer. All six are inside the committed
  `1C00h-1FA0h` RAM snapshot, so no fixture change was needed. The engine side
  maps to `modAccumulator`, `modDelay`, `modRateCounter`, `modCurrentDelta`
  and `modStepCounter`.
- **S3K only.** S1 and S2 have their own normalizers and neither was touched,
  so both oracles are unaffected by construction rather than by measurement.
- **What the first divergence says, and it is already diagnosed.**
  `zFinishTrackUpdate` clears `ModEnvIndex` and `ModEnvSens` whenever the
  do-not-attack bit is clear (:1055-1069). Those two names alias
  `ModulationSpeed` at offset 25h and `ModulationValLow` at 22h, so reading
  any ordinary note zeroes the modulation speed counter and the low byte of
  the modulation accumulator on a track using normal modulation. The engine
  models neither. That is the next fix.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - S3K holds its PSG modulation after a rest note; tick 331 decoded, not closed

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, engine at `fca8ecd0d`.
- **Measurement, not a comparison run.** No engine behaviour changed. This
  entry records the decoded frontier so the next round starts from the numbers
  rather than re-deriving them.
- **The frontier.** `EVENT_VALUE_DIFFERENT`, tick 331, event 10, reference
  `psg 163` against engine `psg 160`. Both are PSG2 tone latches, so the
  divergence is the emitted period, not the write's presence or position.
- **Decoded through the ROM's own two-byte PSG encoding.**
  `zUpdatePSGTrack` sends `l & 0Fh` in the latch byte and the nibble-swapped
  `(l & 0F0h) | h` in the data byte (Sound/Z80 Sound Driver.asm:4085-4095), so
  the emitted 16-bit value can be read straight back out. PSG2's emitted
  period per service:

  | service | reference | engine |
  |---|---|---|
  | 320-329 | 01DE stepping to 01C3, minus 3 each service | identical |
  | 330 | no frequency emitted | no frequency emitted |
  | 331-335 | 0103 held constant | 01C0 stepping to 01B4, minus 3 each service |
  | 336 onward | 00C9 | 00C9 |

- **What that says.** Both sides modulate identically until service 329. At
  330 a rest note is read and neither emits. From 331 the reference stops
  advancing its modulation and holds one value for the whole of the next note,
  where the engine carries the ramp straight on at the same minus-three rate
  it had before the rest. The two re-converge at 336, so this is a bounded
  five-service divergence, not a permanent drift.
- **Every compared field agrees across the window,** which is why this
  surfaces only in the write: `frequency` 1023, `detune` 4, `transpose` 244
  and `modulationCtrl` 128 are equal on both sides at services 326 through
  334, and the volume envelope index advances 0, 1, 2, 3, 4 identically from
  the note at 330. The modulation accumulator is not a compared field.
- **Candidate owner, with its kill condition.** `zUpdatePSGTrack`'s note-start
  entry tests the rest bit immediately after `zGetNextNote` and returns before
  `zPrepareModulation` (:4059-4066), so a rest note leaves the modulation
  state untouched where a sounding note would re-arm it. The engine's
  `playNote` skips its own modulation preparation for a rest note too, so the
  difference is not simply that one re-arms and the other does not. The check
  that would settle it is a probe on the ROM's `ModulationWait`,
  `ModulationSpeed` and `ModulationSteps` bytes across services 329 to 336; if
  the reference's held `0103` corresponds to `zDoModulation` returning early
  on `dec (ix+ModulationWait) / ret nz` (:1284-1289), the emitted value would
  be `Frequency + Detune` and that arithmetic must be made to agree before any
  fix is attempted.
- **Do not retry.** Reading the held value as `Frequency + Detune` from the
  *compared* fields does not work: 1023 plus 4 is 0403h, and the reference
  emits 0103h. Either the compared `frequency` projection is not the ROM's
  `Frequency` word at this point, or the modulation contributes a constant
  minus 300h. That contradiction is unresolved and is the first thing to
  settle.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown against the
  engine's elapsed comparison; S1 and S2's post-note do-not-attack clear; and
  the `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297.


## 2026-09-04 - Neither S3K PSG volume flag writes to the chip; tick 258 -> tick 331

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `00a65e743`.
- **Command:** unchanged, both result lines.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 258, event 9, reference
  `psg 131` against engine `psg 149`. DAC stream `BYTE_DIFFERENT` run 29
  byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 331, event 10, reference
  `psg 163` against engine `psg 160`. DAC stream unchanged. Services 139
  through 330 now agree.
- **The routines.** `cfSetVolume` splits on the PSG bit of `VoiceControl`: the
  FM branch falls through to `zSendTL` to "begin using new volume
  immediately", but the PSG branch ends at `zStoreTrackVolume`, which stores
  the byte and returns without touching the chip (Sound/Z80 Sound
  Driver.asm:3128-3146, :3178-3181). `cfChangePSGVolume` ends at the same
  place (:3186-3199). Neither emits a PSG write; the track's own
  `zUpdatePSGTrack` tail sends the volume on its next pass. The engine called
  `refreshVolume` from both, which put an extra volume byte on the bus ahead
  of the frequency.
- **`cfChangePSGVolume` also clears the rest bit.** It opens with
  `res 4, (ix+zTrack.PlaybackControl)` before it touches the volume at all
  (:3189). The engine cleared its envelope-at-rest flag there but not the rest
  bit itself.
- **How it was found, and one wrong turn.** A stack-tagged probe on every PSG1
  volume write showed two per tick at the divergence, one from a coordination
  flag and one from the note start, against the reference's single write after
  the frequency. `cfSetVolume` was corrected first and moved nothing, because
  the flag actually firing there is `cfChangePSGVolume`; the probe named it on
  the second pass. The `cfSetVolume` correction is landed anyway, cited, since
  the ROM is equally clear about it.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown against the
  engine's elapsed comparison; S1 and S2's post-note do-not-attack clear; and
  the `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - A parked S3K PSG envelope rest survives the next note; tick 234 -> tick 258

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `00a65e743`.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 234, role `MUS_PSG2`, field
  `resting`, reference `true` against engine `false`. DAC stream
  `BYTE_DIFFERENT` run 29 byte 0.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 258, event 9, reference
  `psg 131` against engine `psg 149`. DAC stream unchanged. Services 139
  through 257 now agree.
- **The routine.** `zUpdatePSGTrack`'s note-start entry falls through to the
  same `.skip_fill` block the note-going entry uses, so a new note's own pass
  also reads the volume envelope; only a rest note returns first, on the
  `bit 4` test after `zGetNextNote` (Sound/Z80 Sound Driver.asm:4059-4090).
  That matters when the envelope is parked on a rest command, because
  `zDoVolEnvRest` and `zDoVolEnvFullRest` never advance `VolEnv` (:4187-4208):
  `zGetNextNote` clears the rest bit for the new note and the parked command
  puts it straight back on the same pass.
- **The gate on it is the ROM's, not a carve-out.** `zFinishTrackUpdate`
  leaves `VolEnv` alone exactly when the do-not-attack bit is set
  (:1061-1068); otherwise it resets the index to zero and the pass reads the
  first byte, which is the step the engine already applies at note start. So
  the envelope is re-read on a note start only while that bit is set.
- **The evidence, and a wrong first attempt that the measurement rejected.** A
  probe over the reference showed PSG2 sitting on envelope index 20 unchanged
  from service 228 to 240 while its rest bit stayed up, its pointer advanced
  two bytes at 234 and its duration reloaded from the saved 96. Byte-level
  probes of the engine's own stream showed it never dispatches a rest note
  with the do-not-attack bit set, which ruled out the obvious reading that the
  new note was itself a rest. Running the envelope on *every* note start,
  ungated, was tried first and moved the frontier backwards to tick 138 event
  257, because it double-steps the first envelope value that `playNote`
  already applies. The gated form is what landed.
- **A defect of my own from the previous round, corrected here.** The `81h`
  and `83h` handling set `envHold`, which stopped the envelope being re-read
  at all. That summarised "the index does not advance" but discarded the
  re-read's side effect, which is the whole mechanism above. The hold is gone;
  the index is simply left parked.
- **Still open, unchanged.** S2's `zNoteFillUpdate` countdown against the
  engine's elapsed comparison; S1 and S2's post-note do-not-attack clear,
  which looks redundant on the same reading that fixed S3K; and the
  `.dac_playback_loop` cycle total of 303 against `baseCycles` of 297.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - S3K clears do-not-attack before the note read; tick 180 -> tick 234

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `00a65e743`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 180, role `MUS_FM2`, field
  `doNotAttack`, reference `true` against engine `false`. DAC stream
  `BYTE_DIFFERENT` run 29 byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 234, role `MUS_PSG2`, field
  `resting`, reference `true` against engine `false`. DAC stream unchanged.
  Services 139 through 233 now agree.
- **The routine.** `zGetNextNote` clears `PlaybackControl` bit 1 at its top,
  before the coordination-flag loop it then enters (Sound/Z80 Sound
  Driver.asm:905-915), and `cfPreventAttack` (`0E7h`) sets that bit from
  inside the loop (:3212-3221). So a prevent-attack flag read while fetching a
  note survives into the note it guards and stays set for the note's whole
  duration, until the *next* `zGetNextNote` clears it. The engine cleared the
  bit after the note started instead, so it never persisted.
- **The evidence, from a probe rather than inference.** The reference's FM2 at
  tick 180 advances its data pointer by two bytes, 63796 to 63798, reuses its
  saved duration of 6, and raises `doNotAttack`, which then stays up for ticks
  180 through 185 and drops at 186 when the next unit is read. Two bytes is
  the parameterless `0E7h` plus the note byte.
- **Scoped, and a first attempt was measured and corrected.** The engine's
  post-note clear lived in a helper called from four sites, all of which
  cleared only in the S3K `HOLD` mode. Inverting that helper to the S1/S2
  `REST` mode made those three previously inert sites fire for S1, which
  `TestS1AudioStateNormalizer#productionS1NoAttackBitRemainsLatchedForTheTiedNote`
  caught outright, along with both S1 gameplay oracles. The landed change
  instead removes the helper and its three S3K-only sites and leaves S1 and S2
  with exactly the single post-note clear they had. On the same reading S1 and
  S2 also clear before the read, so their post-note clear looks redundant too;
  that is untouched and recorded here as open rather than changed on a green
  oracle.
- **Still open, unchanged.** S2's `zNoteFillUpdate` is a per-pass countdown
  that rests the track and sends a note off (s2.sounddriver.asm:1153-1163),
  where the engine models an elapsed comparison. And the listing's annotated
  cycle total for one iteration of `.dac_playback_loop` is 303 against
  `Sonic3kSmpsLoader`'s `baseCycles` of 297.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,113
  tests, 0 failures, 10 skips.


## 2026-09-04 - S2 driver-state v2: two measured causes behind the remaining frontier

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, at `085b82f81`.
- **Measurement, not a comparison run.** No engine behaviour changed. This
  entry records what the two standing divergences are, so the next lane does
  not re-derive them.
- **Lines it was measured against.** State with writes: DIVERGENCE at tick 0
  (movie row 10202), field `writes.count`, reference 145 against the engine's
  253. State only: DIVERGENCE at tick 1,789 (movie row 11991), field
  `global.currentTempo`, reference `0x9e` against the engine's `0xbe`; 409 of
  2,198 ticks divergent.
- **The 108 surplus writes at tick 0 are DAC bytes in the wrong service.** The
  engine's tail past the reference's last write is 108 consecutive `2Ah` bytes
  beginning `80 84 80 78 B8`. Those are byte-for-byte the head of the
  *reference's next* service, tick 1. The ROM's load service emits no DAC data
  at all: `zVInt` runs with interrupts disabled and returns to
  `zPlayDigitalAudio`, so a sample the service queues is played by the loop the
  service returns to. The engine pumps it inside the same service. This is the
  same service-boundary question the S3K lane's `enterDacIdleLoop` and
  `enableDacFromIdleLoop` policy hooks answer for that game, and it did not
  surface under the v1 oracle because v1's partition was update-music-owned and
  excluded the DAC transport entirely.
- **The tempo divergence is one service of phase, not a stuck flag.** Engine
  `currentTempo` per tick against the reference's:

  | movie row | reference | engine |
  |---|---|---|
  | 11990 | BEh | BEh |
  | 11991 | 9Eh | BEh |
  | 11992 | 9Eh | 9Eh |

  The engine does apply the slow-down; it applies it one service late. Probes
  on `SmpsDriverSession`'s `SetSpeedShoes` case and on
  `SpeedShoesTimer.perform` confirm the command reaches the session with a live
  music sequencer, so nothing is lost on the way. The ROM consumes the queue at
  the *head* of the service: `zVInt` falls into `zUpdateEverything`, which runs
  `zCycleQueue` and `zPlaySoundByIndex` before either `zUpdateMusic` call
  (s2.sounddriver.asm:411-450), so `zSlowDownMusic`'s store to `CurrentTempo`
  (:2697-2707) is already visible in that same service's snapshot. The engine
  applies the command after the row's driver update instead.
- **What the 409 divergent ticks are not.** They are not 409 ticks holding the
  wrong tempo: the tempo re-converges at the very next tick. They are the count
  of ticks divergent on any compared field after the first one, and the
  accumulating `tempoTimeout` is the obvious carrier of a one-service phase
  error, but that was not confirmed here and should be measured rather than
  assumed.
- **Both are per-service ordering, not values.** Neither needs a constant, and
  neither is a zone or route condition. Both are questions about where a fixed
  piece of ROM work sits inside the V-int the oracle partitions on.

## 2026-09-04 - S2 driver-state v2: the BGM load closes with a note-off sweep over every music slot

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `f95ce7696`.
- **Fixture and command:** as the entry below.
- **Before, state with writes:** DIVERGENCE at tick 0 (movie row 10202), field
  `writes[7]`, reference `ym0[0x28]=0x0` against the engine's `ym0[0xb0]=0x8`.
- **After, state with writes:** DIVERGENCE at tick 0 (movie row 10202), field
  `writes.count`, reference 145 against the engine's 253. Every one of the
  reference's 145 writes now agrees with the engine's, in order; what remains
  at this tick is 108 extra engine writes past the end of the reference's
  service.
- **Before and after, state only:** unchanged at tick 1,789.
- **The routine.** After the FM, PSG and SFX track init, `zBGMLoad` closes with
  two loops (s2.sounddriver.asm:2051-2075): `zFMNoteOff` over all six music FM
  slots and `zPSGNoteOff` over all three music PSG slots. Both walk the fixed
  track region rather than the song's track list, so a song declaring fewer
  tracks still sweeps every slot. Each call sends that slot's own
  `VoiceControl` byte, as `28h` for FM (:2814-2827) and as
  `VoiceControl | 1Fh` to the PSG port (:1357-1367).
- **Why the reference's last FM value is `00`, and it is derived rather than
  measured.** EHZ declares six FM+DAC tracks, so slots FM1-FM5 take
  `zFMDACInitBytes`' `00 01 02 04 05` and the sixth is never touched by this
  load. It reads back zero because `zBGMLoad` calls `zInitMusicPlayback` first
  (:1739), and that clears the whole music track region (:2580-2612). Neither
  of `zFMNoteOff`'s early returns can fire either: bit 4 is inside the region
  just cleared, and under `FixDriverBugs = 0` `zInitSFX` *clears* the SFX
  override bit rather than setting it (:2036-2043), which the listing itself
  flags as a bug against S1's driver.
- **Where it landed.** `Sonic2SmpsCompatibilityPolicy.activateMusic` now builds
  the whole load program from the activation's FM+DAC and PSG track counts;
  `SmpsMusicActivation` gained the PSG count beside the FM+DAC count it already
  carried. Every value comes from `zFMDACInitBytes` and `zPSGInitBytes`
  (:2107-2113), so the program is right for any song's track counts.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the three request windows
  `MATCH` at 25, 52 and 27 production transfers; the same package run as the
  entry below: 2,054 tests, 0 failures, 0 errors, 10 skips.
- **Next.** The 108 surplus engine writes at tick 0. Beyond that the state-only
  frontier at tick 1,789 is the speed shoes expiring: reference RAM shows
  `SpeedUpFlag` going `80h` to `00h` and `CurrentTempo` `BEh` to `9Eh` at that
  service, which is `zSlowDownMusic` (:2697-2707) storing `TempoMod`. See the
  entry above for what the engine actually does, which is not what this line
  first assumed.

## 2026-09-04 - S2 driver-state v2: the BGM load disposes of FM6 before enabling the DAC

- **Worktree/branch:** `.worktrees/audio-s2-state-frontier`,
  `bugfix/ai-s2-driver-state-frontier`, over `develop` at `f95ce7696`.
- **Fixture:** `s2-driver-state-w10150-12400.reference-v2.jsonl.gz`, sampled by
  the observer core at both zVInt returns, 2,243 completed services over movie
  rows [10150,12400), aligned by service ordinal with both sides anchored on
  driver state rather than a frame.
- **Command:** `LUA_BIN=lua5.4 mvn -Dmse=off -Dtest=TestS2DriverStateOracle
  '-Dsonic2.rom.path=<abs>/s2.gen' '-Ds2.request.bk2.path=<abs>/src/test/
  resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/
  sonic-2-sonic-tails-complete-emeralds.bk2' test -B`
- **Before, state with writes:** DIVERGENCE at tick 0 (movie row 10202), field
  `writes[0]`, reference `ym0[0x28]=0x6` against the engine's `ym0[0x2b]=0x80`;
  1,525 of 2,198 ticks divergent.
- **After, state with writes:** DIVERGENCE at tick 0 (movie row 10202), field
  `writes[7]`, reference `ym0[0x28]=0x0` against the engine's `ym0[0xb0]=0x8`;
  1,525 of 2,198 ticks divergent. The first seven writes of the activation now
  agree.
- **Before and after, state only:** unchanged, DIVERGENCE at tick 1,789 (movie
  row 11991), field `global.currentTempo`, reference `0x9e` against the
  engine's `0xbe`; 409 of 2,198 ticks divergent. This change touches no state.
- **The routine.** `zBGMLoad` compares the song header's FM+DAC channel count
  against 7 (s2.sounddriver.asm:1893-1898). A seven-track song is using FM6 as
  a real FM channel, so the load writes `2Bh = 00h` and nothing else. Any other
  count leaves FM6 to the DAC track, and `.silencefm6` (:1900-1938) keys the
  channel off with `28h = 06h`, silences its four operator total-level
  registers `42h/46h/4Ah/4Eh = FFh` on part II, resets its panning with
  `B6h = C0h` because the DAC track never runs `zSetVoice`, and only then
  writes `2Bh = 80h` at `.writesilence`. The engine emitted the DAC enable
  alone, as its first write.
- **FixBugs.** The file assembles with `FixDriverBugs = fixBugs = 0` and
  `OptimiseDriver = 0` (s2.sounddriver.asm:8-9, s2.asm:27), so both the
  key-off and the total-level loop are present in the shipped ROM. Under
  `FixDriverBugs = 1` they would be deferred to a later `zFMNoteOff` and
  `zFMSilenceChannel`; the engine takes the shipped path.
- **Where it landed.** `Sonic2SmpsCompatibilityPolicy.activateMusic` now builds
  the program from `SmpsMusicActivation.fmDacTrackCount()`, which the record
  already carried. No constant was measured from the fixture: the branch and
  every register value come from the listing, so it holds for any song.
- **Gates.** S2 v1 driver oracle `MATCH (698 ticks)`; the three request windows
  `MATCH` at 25, 52 and 27 production transfers; one run of the
  `com.openggf.audio` and `com.openggf.tools.audio.parity` packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, `TestRewindCoverageGuard`
  and `TestStaticStateRewindCoverageGuard` with all three ROM paths: 2,054
  tests, 0 failures, 0 errors, 10 skips.
- **Next.** `writes[7]` is the `.fmnoteoffloop` / `.psgnoteoffloop` tail of the
  load (s2.sounddriver.asm:2054-2075): six `zFMNoteOff` calls over the fixed
  music FM slots, sending each slot's `VoiceControl` byte, then three
  `zPSGNoteOff` calls. The reference's `28h` values are `00 01 02 04 05 00` —
  the last is `00` because a six-channel song never initialises the sixth FM
  slot and `zClearTrackPlaybackMem` left it zero.

## 2026-09-04 - Three S3K rest-bit corrections; tick 150 -> tick 180

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `1b7951cf8`.
- **Command:** unchanged, both result lines.
- **Result before:** `TRACK_STATE_MISMATCH`, tick 150, role `MUS_DAC`, field
  `resting`, reference `false` against engine `true`. DAC stream
  `BYTE_DIFFERENT` run 29 byte 0.
- **Result after:** `TRACK_STATE_MISMATCH`, tick 180, role `MUS_FM2`, field
  `doNotAttack`, reference `true` against engine `false`. DAC stream
  unchanged. Services 139 through 179 now agree.
- **Three corrections, in the order the oracle surfaced them.**
  1. *No DAC track ever rests.* The engine set the rest bit from the note for
     every track type. No driver's DAC path touches it: S3K's
     `zUpdateDACTrack` jumps a rest straight to
     `zUpdateDACTrack_GetDuration` without setting `PlaybackControl` bit 4
     (Sound/Z80 Sound Driver.asm:2890-2896), and S1's `DACUpdateTrack`
     (s1.sounddriver.asm:277-307) and S2's `zDACUpdateTrack`
     (s2.sounddriver.asm:759-790) have no rest test at all. Shared
     correction, no config. Tick 150 -> 158.
  2. *The PSG volume envelope rests the track.* `zDoVolEnv` dispatches `83h`
     to `zDoVolEnvFullRest` and `81h` to `zDoVolEnvRest` (:4169-4175,
     :4187-4194, :4204-4208). Both pop the caller's return address, set the
     rest bit and end the pass; neither advances the envelope index and
     neither silences the channel, which the ROM states outright at :4208.
     Diagnosed from a probe rather than guessed: at tick 158 the reference's
     PSG2 rest bit goes up while its duration keeps counting down 77, 76, 75,
     its data pointer never moves, its note fill is zero and its volume and
     frequency are unchanged, so no note was read and no fill expired. New
     `SmpsSequencerConfig.PsgEnvRestCmd`, S3K only. Tick 158 -> 159.
  3. *Only S2 skips modulation at rest.* The engine gated that on
     `isDirect68kDriver()`, which is true for S1 and false for both Z80
     drivers, so S3K inherited S2's check. S2's `zDoModulation` does test the
     bit (`bit 1,(ix+zTrack.PlaybackControl) / ret nz`,
     s2.sounddriver.asm:988-990); S1's (s1.sounddriver.asm:483-490) and S3K's
     (:1277-1283) test only whether modulation is active. New
     `stepModulationAtRest`, set explicitly on all three and preserving S1 and
     S2 exactly. Tick 159 -> 180.
- **A fourth change that moved nothing and is landed as such.** S3K's note
  fill is a per-pass countdown whose tail splits by track type: PSG rests
  without silencing (:4070-4074, :4220-4224) where FM keys off without
  resting (:786-790, :2148-2152). The engine modelled it as an elapsed
  comparison that did neither. The frontier did not move, because no track
  reaches a fill expiry in this window; it is landed with a citation rather
  than left wrong.
- **Scoped deliberately, and the reason is recorded.** The new
  `NoteFillTail` mode is S3K only. S2's `zNoteFillUpdate` is also a per-pass
  countdown and it rests the track and sends a note off for either type
  (s2.sounddriver.asm:1153-1163), which the engine's elapsed-comparison model
  does not do. Switching S2 left its driver oracle at `MATCH (698 ticks)`, but
  that window reaches no fill expiry, so the green proves nothing. S2 is left
  on its existing model and the discrepancy is recorded here as open.
- **Still open, unchanged from the previous entry.** The listing's annotated
  cycle total for one iteration of `.dac_playback_loop` is 303;
  `Sonic3kSmpsLoader` passes 297 as `baseCycles`. The constant is untouched.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,109
  tests, 0 failures, 10 skips.


## 2026-09-04 - The S3K sample-end disable joins the DAC byte stream; tick 143 -> tick 150

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `1b7951cf8`, merged
  and clean-built before measuring.
- **Command:** unchanged, both result lines.
- **Result before:**
  - `S3K audio oracle: MISMATCH`, `EVENT_VALUE_DIFFERENT`, tick 143, event 0,
    reference `ym2612 port 0 register 0A5h = 19` against engine
    `port 0 register 2Bh = 0`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0,
    reference `0x7C` against engine `0x7F`.
- **Result after:**
  - `S3K audio oracle: MISMATCH`, `TRACK_STATE_MISMATCH`, tick 150, role
    `MUS_DAC`, field `resting`, reference `false` against engine `true`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0,
    unchanged.
- **The ruling, and the ROM behind it.** `zPlayDigitalAudio`'s entry is the
  only writer of `2Bh = 0` (Sound/Z80 Sound Driver.asm:4258-4262), and a
  sample reaches that entry only by being exhausted; a sample superseded
  mid-play jumps straight to `.dac_idle_loop` and writes no disable
  (:4343-4345). So the disable's presence encodes which of the two endings
  occurred, and which one occurs is the same Z80 service duration already
  excused for the run length. It moved into the DAC byte stream with the
  bytes. The `2Bh = 80h` enable did not move: the idle loop writes it on
  finding `zDACIndex` non-zero (:4269-4276), which happens at a service
  boundary, so every run's start stays pinned to an exact tick by strictly
  compared data. Eleven consecutive services, 139 through 149, now agree.
- **Two invariants were proposed and both were refuted by the reference
  itself, which is why neither shipped as an error.**
  - *At most one disable between two runs.* False. Run 0 carries three on both
    sides, because `zPlaySEGAPCM` returns through `zPlayDigitalAudio`'s entry
    and re-writes the disable each time the idle path is re-entered.
  - *No `2Ah` byte while the DAC is disabled.* False. The reference does it
    once, at service 3,837. The engine does it 497 times from service 496,
    which is a real difference, so it is now counted and reported rather than
    asserted.
  Both are reported on the DAC stream's result line, as `sample-end delta` and
  `idle-byte delta`, beside the existing `run-length delta`.
- **What the DAC stream still asserts:** the run count exactly, and every byte
  the two sides share in every run, in order. The excusal text in
  docs/status/known-discrepancies.md, "S3K Music DAC Byte Stream Partition",
  now says exactly this, including that the two invariants above were measured
  and dropped.
- **Break-it evidence, one mutation per claim.** Three guards were each broken
  on purpose and each broke only its own test. Reverting the disable exclusion
  in `serviceWrites` fails
  `theSampleEndDisableIsNotComparedInTheServiceStream`; widening that
  exclusion to all `2Bh` writes fails
  `theRunStartEnableStaysComparedInTheServiceStream`, which is what pins the
  enable; and dropping the idle-byte count fails
  `bytesStreamedWhileTheDacIsDisabledAreCountedNotFailed`. The existing
  corrupted-byte test still reports at run 1, ahead of the live frontier.
- **The retired v1 stream moved too, and the move is the artefact, not a
  defect.** v1's boot row physically carries the DAC entry disable as an 85th
  write, which the service comparison no longer sees, so tick 0 now agrees and
  v1's first divergence is its next sampling artefact: its row for the SEGA
  PCM start omits the `2Bh = 80h` enable the engine emits. That the enable is
  still caught there is the point.
- **Next.** Tick 150's `MUS_DAC.resting`. The engine sets a DAC track's rest
  bit from its note, but none of the three drivers' DAC paths touch it: S3K's
  `zUpdateDACTrack` jumps a rest straight to
  `zUpdateDACTrack_GetDuration` (:2889-2896) without setting
  `PlaybackControl` bit 4, and S1's `DACUpdateTrack`
  (s1.sounddriver.asm:277-307) and S2's `zDACUpdateTrack`
  (s2.sounddriver.asm:759-790) have no rest test at all. That looks like a
  shared correction rather than a per-driver one and is the next target.
- **Open, and deliberately not acted on.** The listing's annotated cycle total
  for one iteration of `.dac_playback_loop` is 303; `Sonic3kSmpsLoader` passes
  297 as `baseCycles`. The two numbers are recorded here and the constant is
  unchanged, because the present evidence does not establish which is right.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)`; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. The audio packages plus
  `TestSmpsFadeAudioThroughput`, `TestYm2612DacTiming`, the four S3K
  keep-green classes, `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,109
  tests, 0 failures, 10 skips.


## 2026-09-04 - S3K tick 143 is Z80 service duration: the DAC pump gets the whole frame

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `2607114d4`,
  engine at `29c9d01d3`.
- **Measurement, not a comparison run.** No engine behaviour changed. This
  entry records why the tick-143 frontier was not fixed against the present
  model, so the next lane does not re-derive it.
- **The frontier.** `EVENT_VALUE_DIFFERENT`, tick 143, event 0: the engine
  emits the DAC idle loop's `2Bh = 0` at the head of the service, where the
  reference is still playing and sends FM2's frequency pair. The engine's
  music sample has run out four services after it started; the ROM's is
  superseded by the next play at tick 145 without ever exhausting.
- **What was measured.** Decoded `port 0 register 2Ah` writes per service,
  reference against engine, over ticks 139-150:

  | ticks | reference | engine |
  |---|---|---|
  | 140-143 steady state | 265, 265, 266, 265 | 316, 315, 316, 304 |
  | 145-149 steady state | 251, 266, 265, 265, 265 | 313, 316, 315, 316, 178 |

  The engine plays about 1.19 times as many samples per service. Both sides
  play the same 1,438-sample run and the bytes agree, so this is rate, not
  content.
- **Where the 1.19 comes from, and it is arithmetic rather than a guess.**
  The sample is `DAC_86`, whose setup record gives rate `04h`
  (sonic3k.macros.asm:405, `DAC_Setup`), and `Ym2612Chip.dacPeriod` turns that
  into `(297 + 2 * 13 * 3) / 2` = 187.5 Z80 cycles per decoded sample. The
  engine's 316 samples per service is therefore 59,250 Z80 cycles, which is a
  whole NTSC frame (3,579,545 / 60 = 59,659). The reference's 265 is 49,688
  cycles, 83 per cent of the frame. The missing sixth of every frame is the
  time `zUpdateEverything` itself takes with interrupts disabled, during which
  `zPlayDigitalAudio`'s loop is not running and no `2Ah` byte is written
  (Sound/Z80 Sound Driver.asm:4299-4351, whose `ei` / `djnz $` pair is the only
  window the V-int can land in).
- **So it is Z80 service duration, the category already excused for the byte
  partition.** The engine's pump advances the chip by a full V-int of the
  driver's region cadence per service and charges nothing for the service
  itself. Deriving the real cost means cycle-counting the Z80 driver's own
  work per pass; it is not available from frame-granularity state, and a
  measured 0.83 would be a fitted constant of exactly the kind hard rule 3
  forbids. The run lengths are invisible to the DAC byte-stream comparison,
  which compares only shared bytes, which is why this surfaced as a `2Bh`
  write in the partitioned stream rather than as a byte difference.
- **A separate and much smaller question for whoever takes this on.** The
  listing's own annotated cycle total for one iteration of
  `.dac_playback_loop` is 303, and `Sonic3kSmpsLoader` passes 297 as
  `baseCycles`. That is a 2 per cent difference, not the 19 per cent above, and
  it was not chased here; it should be checked against the ROM before any
  service-cost model is built on top of it.
- **Ruled out.** The SEGA PCM run is not counter-evidence that the rate is
  right. All 24,111 of its samples are emitted inside service 50 by the SEGA
  PCM transport, not by the per-service pump, so it exercises none of the
  cadence above.


## 2026-09-04 - S3K latches the PSG frequency before the volume; tick 139 -> tick 143

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `2607114d4`.
- **Command:** unchanged, both result lines.
- **Result before:**
  - `S3K audio oracle: MISMATCH`, `EVENT_VALUE_DIFFERENT`, tick 139, event 7,
    reference `psg 128` against engine `psg 178`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0.
- **Result after:**
  - `S3K audio oracle: MISMATCH`, `EVENT_VALUE_DIFFERENT`, tick 143, event 0,
    reference `ym2612 port 0 register 0A5h = 19` against engine
    `port 0 register 2Bh = 0`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0,
    unchanged.
- **The routines.** `zUpdatePSGTrack`'s `.skip_fill` runs `zUpdateFreq` and
  `zDoModulation`, returns only on the SFX-override bit, latches the frequency
  to the PSG, and only then reads the volume envelope and writes the volume
  (Sound/Z80 Sound Driver.asm:4077-4110). S1 and S2 are the other order:
  `PSGUpdateVolFX` then `DoModulation` then `PSGUpdateFreq`
  (s1.sounddriver.asm:1822-1827) and `zPSGUpdateVolFX` then `zDoModulation`
  then `zPSGUpdateFreq` (s2.sounddriver.asm:1134-1138). That is now
  `SmpsSequencerConfig.PsgNoteGoingOrder`, defaulting to the S1/S2
  `VOLUME_THEN_FREQUENCY` with only the S3K preset selecting
  `FREQUENCY_THEN_VOLUME`.
- **The second half, and it is what actually cleared the tick.** S3K's rest
  check for PSG sits *after* the frequency latch and gates only the volume
  (:4085-4090, :4098-4101), unlike the FM path whose `.note_going` returns on
  the rest bit before reaching the send (:781-783). So a resting S3K PSG track
  still puts its frequency on the bus. Reference tick 139 shows exactly that:
  PSG1 and PSG3 send frequency pairs with no volume write, and only PSG2
  writes a volume. With the rest guard lifted for that path, ticks 139 through
  142 agree in full - all six FM frequency sends, all three PSG frequency
  pairs and the one PSG volume.
- **Next, and it is measured rather than inferred.** Tick 143 event 0 is the
  engine's music DAC sample running out and emitting the idle loop's
  `2Bh = 0`, where the ROM's is still playing. A per-tick byte count over
  ticks 139-150 gives the reference a steady **265** DAC bytes per service and
  the engine **316**, a factor of 1.19, so the engine plays a 1,438-byte
  sample in about four services where the ROM takes about five and a half.
  Run lengths are excused by the DAC stream comparison, which compares only
  shared bytes, so this rate error is invisible there and surfaces only as the
  early `2Bh` disable in the partitioned stream. The SEGA PCM run before it
  (24,111 bytes) agrees in both content and span, so the divergence is
  specific to the music DAC path's rate, most likely `Ym2612Chip.dacPeriod`'s
  inputs for this entry rather than the pump itself.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)` re-run through `S1AudioParityTool` capture + compare
  after this change; both S1 gameplay oracles `MATCH` at 2,562 and 5,257
  ticks; S2 driver oracle `MATCH (698 ticks)` and the three request windows
  `MATCH` at 25, 52 and 27 transfers. One run of the `com.openggf.audio` and
  `com.openggf.tools.audio.parity` packages plus `TestSmpsFadeAudioThroughput`,
  `TestYm2612DacTiming`, the four S3K keep-green classes,
  `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,106
  tests, 0 failures, 10 skips.
- **Break-it evidence.** The frontier stays pinned by assertion in
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`,
  which asserts kind, tick, event index and the exact reference write, and
  failed against the old tick 139 pin until it was moved to tick 143.


## 2026-09-04 - S3K re-sends an unchanged frequency every pass; tick 139 event 1 -> 7

- **Worktree/branch:** `.worktrees/audio-s3k-freq`,
  `bugfix/ai-s3k-oracle-freq-resend`, over `develop` at `2607114d4`.
- **Command:** unchanged from the previous entry, both result lines.
- **Result before:**
  - `S3K audio oracle: MISMATCH`, `EVENT_VALUE_DIFFERENT`, tick 139, event 1,
    reference `ym2612 port 0 register 0A5h = 19` against engine
    `port 1 register 0A4h = 27`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0,
    reference `0x7C` against engine `0x7F`.
- **Result after:**
  - `S3K audio oracle: MISMATCH`, `EVENT_VALUE_DIFFERENT`, tick 139, event 7,
    reference `psg 128` against engine `psg 178`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0,
    unchanged.
- **The routine, in all three listings.** S3K's `zDoModulation` is an ordinary
  subroutine: every one of its returns - inactive modulation
  (`ret z` on `ModulationCtrl`), an unexpired `ModulationWait`, or a completed
  step - lands on its caller, and both callers fall straight through to the
  frequency send. For FM that is `zUpdateFMorPSGTrack`'s `.keep_going`, which
  calls `zUpdateFreq`, returns only on the sustain-frequency bit, calls
  `zDoModulation` and falls through to `zFMSendFreq`
  (Sound/Z80 Sound Driver.asm:791-799, :1277-1283). For PSG it is
  `zUpdatePSGTrack`'s `.skip_fill`, which latches the frequency
  unconditionally after `zDoModulation` (:4077-4090). So a sounding S3K track
  puts its frequency on the bus every pass whether or not it moved.
- **S1 and S2 are genuinely the other shape, and both were checked.** S1's
  `DoModulation` opens with `addq.w #4,sp`
  (docs/s1disasm/s1.sounddriver.asm:483-486) and S2's with `pop de`
  (docs/s2disasm/s2.sounddriver.asm:986-987), discarding the caller's return
  address, so an inactive or unexpired modulation returns past
  `FMUpdateTrack`/`zPSGUpdateTrack` entirely and the `bra.w FMUpdateFreq` /
  `jp zFMUpdateFreq` never runs (s1:358-361, s2:832-834, :1130-1138). The
  engine implemented that shape for all three games. It is now a per-driver
  mode, `SmpsSequencerConfig.NoteGoingFreqSend`, defaulting to the S1/S2
  `MODULATION_ONLY` with only the S3K preset selecting `EVERY_PASS`.
- **What moved.** All six of the first post-load update's FM frequency sends
  now agree, which is events 1 through 6. `applyModulation` was split so the
  step and the send are separate, matching the ROM's own split between
  `zDoModulation` and `zFMSendFreq`; the S1/S2 path still sends only from
  inside it. No constant was introduced.
- **Next.** Event 7 is the PSG half of the same routine. The reference latches
  PSG1's frequency (`80h`) where the engine writes PSG1's volume (`B2h`),
  because `zUpdatePSGTrack`'s `.skip_fill` latches frequency before it reaches
  `zDoVolEnv` and the volume write (:4077-4110), while S2's `.notegoing` calls
  `zPSGUpdateVolFX` first and only then modulation and frequency
  (s2.sounddriver.asm:1134-1138). That is a second per-driver difference in
  the same pair of routines and is the next target.
- **Gates at this commit, all green.** S1 sound test `MATCH (14690 ticks)` and
  `MATCH (1967 ticks)` through `S1AudioParityTool` capture + compare; both S1
  gameplay oracles `MATCH` at 2,562 and 5,257 ticks; S2 driver oracle
  `MATCH (698 ticks)` and the three request windows `MATCH` at 25, 52 and 27
  transfers. One run of the `com.openggf.audio` and
  `com.openggf.tools.audio.parity` packages plus `TestSmpsFadeAudioThroughput`,
  `TestYm2612DacTiming`, the four S3K keep-green classes,
  `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,106
  tests, 0 failures, 10 skips.
- **Break-it evidence.** The frontier is pinned by assertion, not logged:
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`
  asserts the exact kind, tick, event index and reference write, and failed
  against the old event 1 until it was moved to event 7. The sidecar
  perturbation and DAC corruption tests in the same class remain green.


## 2026-09-04 - S3K music DAC byte pump lands unpartitioned; tick 139 event 1 becomes a track-set frontier

- **Worktree/branch:** `.worktrees/audio-s3k-dac-pump`,
  `feature/ai-s3k-dac-byte-pump`, over `develop` at `340634eb7`.
- **Command:** unchanged, plus the second result line the tool now prints.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 139, event 1, reference
  `ym2612 port 0 register 2Ah = 80h` against engine `port 1 register 0A4h = 27`.
  No DAC byte stream existed to compare.
- **Result after, two lines:**
  - `S3K audio oracle: MISMATCH`, `EVENT_VALUE_DIFFERENT`, tick 139, event 1,
    reference `ym2612 port 0 register 0A5h = 19` against engine
    `port 1 register 0A4h = 27`.
  - `S3K DAC byte stream: MISMATCH`, `BYTE_DIFFERENT`, run 29, byte 0,
    reference `0x7C` against engine `0x7F`. Twenty-eight complete sample runs
    agree byte for byte first.
- **What the DAC line proves.** The engine's decoded samples are the ROM's:
  the first music run compares equal for all 1,364 bytes the reference
  carries, and the full `DAC_86` sample the engine decodes matches the
  reference's own 1,438-byte run elsewhere byte for byte. Run 29's first byte
  differing is a different sample being selected, which is downstream of the
  partitioned stream's own divergence at tick 139 rather than a DAC defect.
- **What was excused, and it is written down.** Which service window a `2Ah`
  byte lands in, and how far a run got before a later play cut it short, are
  both Z80 service duration. Recorded with its residual table in
  docs/status/known-discrepancies.md, "S3K Music DAC Byte Stream Partition",
  together with what is still compared: run count exactly, every shared byte
  of every run in order, and the `2Bh` enable and disable which stay
  partitioned and which delimit the runs.
- **The pair now ships together.** `SmpsDriverSession` emits
  `policy.enableDacFromIdleLoop()` after a service whose DAC track queued a
  sample (Sound/Z80 Sound Driver.asm:2896-2903, :4269-4276) and
  `policy.enterDacIdleLoop()` for every sample the chip exhausts
  (:4348-4355, :4256-4260). The previous lane withheld the enable because
  only half the pair existed; with the disable modelled,
  `TestSonic3kUnifiedAudioPresentationRomIntegration`'s silence assertion
  passes.
- **No constant was introduced.** The per-byte cadence is the existing
  `Ym2612Chip.dacPeriod` from `DacData.baseCycles` and the sample's rate
  byte. The capture host advances the chip by one V-int of the driver's own
  region cadence per tick.
- **Next, and it is already diagnosed and cross-checked.** Tick 139's
  remaining service writes are a track-set divergence in the first update
  after the load: the reference sends FM2's frequency pair (`ym0 0A5h/0A1h`)
  and three PSG channels, where the engine sends FM4 and FM5 and one PSG
  channel. The tracks the engine omits are exactly the ones whose frequency
  did not change since the previous pass, and FM2's `0132h` is identical at
  ticks 139 and 140. S3K re-sends it anyway: `zUpdateFMorPSGTrack`'s
  `.note_going` path calls `zUpdateFreq`, returns early only on the
  sustain-frequency bit, then calls `zDoModulation` and **falls through** to
  `zFMSendFreq` (Sound/Z80 Sound Driver.asm:783-799), and that
  `zDoModulation` is an ordinary subroutine whose `ret z` on inactive
  modulation lands on the fall-through (:1279-1283).
  S2 is genuinely different and the engine currently implements S2's shape:
  its `zDoModulation` opens with `pop de` and deliberately does not return to
  its caller when modulation is off or the track is resting, so
  `zFMUpdateFreq` is skipped (s2.sounddriver.asm:986-997, :828-834). So this
  is a real per-game difference belonging in the sequencer config, not a
  shared correction. In the engine the per-pass write exists only inside
  `SmpsSequencer.applyModulation`, which returns before writing when
  modulation is disabled or nothing changed. The PSG ordering difference
  (engine volume-before-frequency against the reference's
  frequency-then-volume) should be re-measured after that change rather than
  before it.
- **Gates at this commit:** one run of the `com.openggf.tools.audio.parity`
  and `com.openggf.audio` packages plus `TestSmpsFadeAudioThroughput`,
  `TestYm2612DacTiming`, the four S3K keep-green classes,
  `TestSonic3kUnifiedAudioPresentationRomIntegration`,
  `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2,106
  tests, 0 failures, 14 skips. That covers the S1 sound-test and both S1
  gameplay oracles, the S2 driver oracle and the S2 request windows, none of
  which moved.
- **Break-it evidence.** `aCorruptedDacByteIsReportedAtItsRunAndOffset`
  corrupts one reference sample byte after tick 140 and requires the DAC
  stream comparison to report it at run 1, ahead of the live frontier's run
  29. Without it a stream that was never populated and one that agrees would
  read identically.


## 2026-09-04 - S3K music DAC byte pump: the cadence reconciles, the per-tick partition does not

- **Worktree/branch:** `.worktrees/audio-s3k-dac-pump`,
  `feature/ai-s3k-dac-byte-pump`, over `develop` at `340634eb7`.
- **Measurement, not a comparison run.** No engine behaviour changed. This
  entry records why the music DAC byte pump was not implemented against the
  present model, so the next lane does not re-derive it.
- **What the reference carries.** In
  `s3k-aiz1-intro-reference-v2.jsonl.gz`: 725,780 writes over 5,263 ticks, of
  which 625,699 are `ym2612 port 0 register 2Ah`, spread over 3,489 ticks and
  contiguous at the head of the tick's write list in 3,015 of them. That is
  `zPlayDigitalAudio`'s playback loop (Sound/Z80 Sound Driver.asm:4296-4351)
  streaming between V-int services, exactly as the previous entry predicted.
- **The engine's cycle model is the right model, and the reference confirms it
  twice.** First, every contiguous run of `2Ah` writes sums to a ROM sample's
  own decoded sample count read through `Sonic3kSmpsLoader.loadDacData`:
  1,438 for `DAC_86`, 3,836 for `DAC_81`, 9,294 for `DAC_88`. One `2Ah` write
  is one decoded sample, which is what `Ym2612Chip.dacSampleAt` already
  indexes. Second, taking each tick's `zDACIndex` from the reference's own RAM
  export (`z80 $1C30`; window base `$1C00`) and pairing it with that sample's
  rate byte, the Z80 cycles the model leaves unaccounted per frame,
  `59,736 - count * dacPeriod(297, rate)`, land in a consistent
  11,600-16,000 band across fifteen distinct samples spanning rates 3 to 27.
  A wrong per-byte cost would skew that residual with the rate; it does not
  (rate 3 gives 13,494, rate 27 gives 12,059).
- **The two base-cycle numbers, and why the reference cannot choose between
  them.** The listing's own `; total:` annotation for `.dac_playback_loop`
  (:4351) sums to **303**, because it counts the two `ld a, (hl)` fetches as
  `7+3` for the ROM-access delay its comment attributes to Kabuto (:4297).
  `DacData` carries **297** for S3K, the same total with those two penalties
  excluded. The difference is 6 cycles in ~450, and it disappears into the
  unaccounted residual above, which is itself unknown to several thousand
  cycles. So 297 versus 303 is not the frontier and no evidence here refutes
  either.
- **Why the per-tick count is not derivable.** The residual is the Z80
  execution cost of that frame's `zUpdateEverything`, and it is not constant.
  Within a single uninterrupted play of one sample at one fixed rate the
  reference's per-tick counts swing by 15 to 86 writes, and four separate
  plays of the same sample index peak at 268, 258, 257 and 244. The rate is
  fixed and the cycle model is fixed across all of that, so every write of the
  variation is service duration. The engine models the driver's semantics, not
  its Z80 instruction timing, and has no such quantity to offer.
- **What a correct implementation of the pump would therefore produce.** The
  oracle capture host never renders (`S3kOpenGgfAudioCapture` drives
  `serviceOuterFrame` and reads the observer; nothing calls
  `OwnedSmpsAudioStream.read`), so `Ym2612Chip.serviceDac` never runs there
  and reporting its writes to the observer emits nothing. Driving one V-int
  frame of chip time per tick instead would emit `59,736 / dacPeriod` writes,
  which is the count with the service cost set to zero: about 279 where the
  reference has 265 at rate 3, and about 133 where the reference has 211. The
  only quantity that closes that gap is the per-frame service duration, and
  supplying it as a constant is precisely the fitted model hard rule 3
  forbids.
- **The honest next step is a decision, not a fix.** Either the driver gains a
  Z80 cycle account for its own service (a large piece of work, and the only
  route that keeps the partition derivable), or the comparison stops
  partitioning the DAC stream by service boundary and compares the byte stream
  and its ordering while excusing the per-tick split, with the limit written
  into the known-discrepancies entry in the same change. Do not close it by
  measuring this fixture's counts.


## 2026-09-04 - S2 request oracle reaches MATCH on every replayable window

- **Worktree/branch:** `.worktrees/audio-s2-frontier`,
  `bugfix/ai-s2-request-frontier`, from `develop` `55b40a105`.
- **Fixtures:** `s2-request-window-w10150-10900`, `-w10900-11650` and
  `-w11650-12400` under `src/test/resources/audio/parity/s2/`.
- **Command:** `LUA_BIN=lua5.4 mvn -Dmse=off -Dtest=TestS2WidenedRequestOracle
  '-Dsonic2.rom.path=<abs>/s2.gen' '-Ds2.request.bk2.path=<abs>/src/test/
  resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/
  sonic-2-sonic-tails-complete-emeralds.bk2' test -B`
- **Before:** `w11650-12400` DIVERGENCE at transfer 21, movie row 12132,
  reference SFX `$A0` against the engine's SFX `$B5` at row 12114.
- **After:** `MATCH` on all three, at 25, 52 and 27 production transfers.
- **Two gameplay causes, both mailbox/ownership rather than driver.** The ten-
  ring and shield monitors send their sound through ROM `PlayMusic`, the music
  mailbox, not the SFX queue (s2.asm:25913-25914, :25955-25956, :1517-1527), so
  those bytes make no sound-queue transfer while the driver still classifies
  them by range at `QueueToPlay` (s2.sounddriver.asm:1565-1571). And the
  explosion sound belongs to `Obj27_Init` in the explosion's own slot
  (s2.asm:46717-46734), not to the touch that broke the monitor, which is a
  pass later whenever `Obj26_Break`'s lowest-free allocation lands below the
  monitor (:25702-25707). Commits `4dc26bea4` and `cecbb67b4`. No constant was
  introduced; both fixes are structural and hold for any recording.
- **Gates at `cecbb67b4`.** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`; both S1 gameplay oracles green; the S3K driver oracle
  unchanged at tick 128; ordinary suite 16,387 tests and `-Pguards` 607 tests
  with 0 failures and 0 errors; the full `*TraceReplay` sweep has the same 62
  failing class names as a control sweep at the base commit.



## 2026-09-04 - S3K oracle: the DAC enable belongs to the idle loop; tick 139 event 0 -> 1

- **Worktree/branch:** `.worktrees/audio-s3k-tick139`,
  `bugfix/ai-s3k-oracle-tick139`, over `develop` at `c549f543d`.
- **Command:** unchanged, and the same comparison pinned by
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 139, event 0, reference
  `ym2612 port 0 register 2Bh = 80h` against engine `port 1 register 0A4h = 27`.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 139, **event 1**, reference
  `ym2612 port 0 register 2Ah = 80h` against the same engine write. Event 0 now
  agrees.
- **What the ROM does.** `zPlayDigitalAudio` spins in `.dac_idle_loop`
  (Sound/Z80 Sound Driver.asm:4264-4271) reading `zDACIndex`, and the pass that
  finds it non-zero writes 2Bh = 80h before decoding (:4272-4276). The index is
  stored by `zUpdateDACTrack` inside a V-int service (:2896-2903), so the enable
  is emitted by the idle loop the service returns to, never by the service
  itself. A sample queued while another plays clears bit 7, so
  `jp p, .dac_idle_loop` (:4343-4345) sends the loop back through the same
  enable: one 2Bh = 80h per queued sample. The engine now records the
  `zDACIndex` store in `SmpsDriver`, discards it wherever the ROM zeroes
  `zDACIndex` (`zStopAllSound`'s wipe of the variable block that holds it,
  :134,163,214, :2461-2470), and lets the oracle's capture host emit the
  physical policy's enable at the start of the following window.
- **The runtime session deliberately does not emit it yet, and that is
  measured, not assumed.** The ROM's enable is one half of a pair: it streams
  every decoded byte to 2Ah and then clears the index and re-enters
  `zPlayDigitalAudio`, whose 2Bh = 0 turns the DAC off (:4352-4355,
  :4256-4260). The session plays music DAC inside `Ym2612Chip` and has no
  sample-end signal, so applying only the enable left the DAC on holding its
  last level: `TestSonic3kUnifiedAudioPresentationRomIntegration` failed on
  "stopping the music must actually silence the final packet", and an A/B with
  the hook disabled passed. The runtime joins the capture host when the
  sample-end disable is modelled with it.
- **No constant was introduced.** The write, its value and its position are all
  stated by the listing. S1 and S2 keep an empty `enableDacFromIdleLoop`, since
  their DAC enable is not written by an idle loop.
- **Next, and it is a subsystem, not a write.** Tick 139's remaining 36 events
  and every tick from 140 on are the music DAC byte pump: the reference carries
  ~265 `2Ah` writes per tick, contiguous and ahead of that tick's service
  writes, for 3,498 of its 5,263 ticks. The engine plays music DAC inside
  `Ym2612Chip` rather than on the write bus, so no 2Ah write is produced. The
  SEGA chant already streams this way through `SmpsSegaPcmTransport`, which is
  the shape to reuse; the per-tick count must come from the ROM playback loop's
  own annotated cycle costs (:4299-4351) and the sample's rate byte, not from
  counting the fixture's rows.


## 2026-09-04 - S3K oracle: the PSG frequency is a whole 16-bit word; tick 138 clears to tick 139

- **Worktree/branch:** `.worktrees/audio-s3k-tick138`,
  `bugfix/ai-s3k-oracle-tick138`, on top of the entry below.
- **Command:** unchanged, and the same comparison pinned by
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 138, event 255 of 259,
  reference `psg 0A0h` against engine `psg 0A3h`.
- **Result after:** `EVENT_VALUE_DIFFERENT`, **tick 139**, event 0, reference
  `ym2612 port 0 register 2Bh = 80h` against engine `port 1 register 0A4h = 27`.
  Tick 138 now matches in full: 259 writes, every one of them.
- **Three PSG facts.** `zUpdatePSGTrack` calls `zUpdateFreq` and `zDoModulation`,
  which only compute, and then sends the frequency once
  (Sound/Z80 Sound Driver.asm:4077-4095); S2 genuinely sends it twice, from
  `zPSGDoNoteOn` and again from `zPSGUpdateFreq`
  (s2.sounddriver.asm:1046-1053), so the single send is S3K's. The second PSG
  byte is not a masked shift either: S3K ORs the low byte's high nibble with the
  whole high byte and rotates right by four with no mask (:4085-4095), where S2
  masks to six bits (s2.sounddriver.asm:2835-2842). And the frequency itself is
  never masked: `zUpdateFreq` adds the sign-extended detune to a 16-bit register
  (:3080-3101), so a word above 03FFh survives into that byte. The engine was
  masking the period to ten bits, which turned the ROM's 0400h into zero and its
  0040h second byte into zero. Masking stays on for S1 and S2, where it is
  invisible because both mask the byte anyway.
- **No constant was introduced.** Every value here is a register width or a mask
  read out of the listing.
- **Next, and it is the write cycle 1 removed on purpose.** `zPlayDigitalAudio`
  writes 2Bh = 80h from its idle loop, after `zDACIndex` goes non-zero and
  outside the service that queued the sample (:4265-4275). The capture host
  models the idle loop already through `enterDacIdleLoop`, so the DAC enable
  belongs in the window after a service whose DAC track queued a sample.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`; the S1 gameplay v3 and run-2 oracles and the S2 driver
  oracle `MATCH (698 ticks)` all pass inside one run of the
  `com.openggf.tools.audio.parity` and `com.openggf.audio` packages,
  `TestSmpsFadeAudioThroughput` and the four S3K keep-green classes: 2,097
  tests, 0 failures, 10 skips. The ordinary suite with all three ROM
  paths runs 16,383 tests with 0 failures and 20 skips, and `-Pguards` runs 607
  with 0 failures. One unit test changed with the mask:
  `TestSonic3kCoordFlagParity` asserted that a PSG detune underflow wraps to a
  ten-bit 03FFh, which is the engine's old invention rather than the driver's
  behaviour, so it now asserts the 08Fh/0FFh the listing produces.


## 2026-09-04 - S3K oracle: the note path sends one frequency and no pan; tick 138 event 151 -> 255

- **Worktree/branch:** `.worktrees/audio-s3k-tick138`,
  `bugfix/ai-s3k-oracle-tick138`, on top of the entry below.
- **Command:** unchanged, and the same comparison pinned by
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 138, event 151, reference
  `ym2612 port 0 register 28h = 0F1h` against engine `port 0 register 0B5h = 0C0h`.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 138, event 255 of 259,
  reference `psg 0A0h` against engine `psg 0A3h`. Every YM2612 write of the
  title-music load now agrees, across all six FM tracks.
- **Two writes the S3K note path does not make.** `zUpdateFMorPSGTrack` runs
  `zGetNextNote`, `zPrepareModulation`, `zUpdateFreq` and `zDoModulation`, all of
  which only compute, and then a single `zFMSendFreq` before `zFMNoteOn`
  (Sound/Z80 Sound Driver.asm:776-782). `zFMSendFreq` writes 0A4h and 0A0h and
  nothing else (:815-871); the track's AMS/FMS/pan reaches the chip from the
  voice upload (:1533), not from the note. The engine was writing the pan
  between the frequency and the key-on, and was sending the frequency twice,
  once unmodulated and once from the forced note-start modulation write. The
  second write hid the first while it sat between them.
- **No constant was introduced.** Both changes delete a write the listing does
  not contain.
- **Next.** The remaining divergence is a PSG tone byte, reference `0A0h`
  against engine `0A3h`, on a PSG track's first note.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`; the S1 gameplay v3 and run-2 oracles and the S2 driver
  oracle `MATCH (698 ticks)` all pass inside one run of the
  `com.openggf.tools.audio.parity` and `com.openggf.audio` packages,
  `TestSmpsFadeAudioThroughput` and the four S3K keep-green classes: 2,097
  tests, 0 failures, 10 skips.


## 2026-09-04 - S3K oracle: cfSetVoice releases the envelope first; tick 138 event 87 -> 151

- **Worktree/branch:** `.worktrees/audio-s3k-tick138`,
  `bugfix/ai-s3k-oracle-tick138`, on top of the entry below.
- **Command:** unchanged from the entry below, and the same comparison pinned by
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 138, event 87, reference
  `ym2612 port 0 register 80h = 0FFh` against engine `port 0 register 0B4h = 0C0h`.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 138, event 151, reference
  `ym2612 port 0 register 28h = 0F1h` against engine `port 0 register 0B5h = 0C0h`.
  The whole of the first FM track now agrees, write for write, through its
  voice upload and its key-off, as does the second track's voice upload.
- **What the ROM does.** S3K's `cfSetVoice` calls `zSetMaxRelRate` for any
  non-PSG track before it even reads the voice index, writing 0FFh to
  80h + operator for all four operators so D1L is minimum and RR maximum
  (Sound/Z80 Sound Driver.asm:3444-3447, 2675-2698). `zWriteFMIorII` drops those
  writes when PlaybackControl bit 2 marks the track as SFX-overridden
  (:2701-2709). S1 `cfSetVoice` (s1.sounddriver.asm:2313-2360) and S2
  `cfSetVoice` (s2.sounddriver.asm:3271-3293) make no such call, so the release
  lives in the S3K coordination-flag handler rather than in shared code.
- **No constant was introduced.** The register base, the four-operator stride,
  the channel offset and the suppression condition all come from the listing.
- **Next, and it is already diagnosed.** The engine writes the track's
  AMS/FMS/pan between the note frequency and its key-on, but S3K's note path is
  `zFMSendFreq` then `zFMNoteOn` and writes only 0A4h and 0A0h (:780-782,
  :815-871). The pan reaches the chip from the voice upload instead.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`; the S1 gameplay v3 and run-2 oracles and the S2 driver
  oracle `MATCH (698 ticks)` all pass inside a single run of the
  `com.openggf.tools.audio.parity` and `com.openggf.audio` packages,
  `TestSmpsFadeAudioThroughput` and the four S3K keep-green classes: 2,097
  tests, 0 failures, 0 skips.


## 2026-09-04 - S3K oracle: the post-load first update grows its DAC prefix; tick 138 event 85 -> 87

- **Worktree/branch:** `.worktrees/audio-s3k-tick138`,
  `bugfix/ai-s3k-oracle-tick138`, over `develop` at `a05287cef`.
- **Command:**
  `java -cp "target/classes:$(cat target/cp.txt)"
  com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare
  --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz
  --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json
  --rom <absolute locked-on S3K ROM>`, and the same comparison pinned by
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`.
- **Result before:** `EVENT_VALUE_DIFFERENT`, tick 138, event 85, reference
  `ym2612 port 0 register 28h = 6` against engine `port 0 register 0B4h = 0C0h`.
- **Result after:** `EVENT_VALUE_DIFFERENT`, tick 138, event 87, reference
  `ym2612 port 0 register 80h = 0FFh` against engine `port 0 register 0B4h = 0C0h`.
  Events 85 and 86 now agree: the DAC track's key-off of FM6 and its FM3
  normal-mode restore.
- **What the ROM does, and it is three separate facts.** After `zBGMLoad`'s one
  0B6h write the next writes belong to the same service's `zUpdateMusic` pass,
  which updates `zSongFM6_DAC` before the FM/PSG loop
  (Sound/Z80 Sound Driver.asm:717-723). That DAC pass calls `zKeyOffIfActive`
  and then `zFM3NormalMode` before queuing the sample (:2897-2898), producing
  28h = 6 and 27h = 0; `zKeyOffIfActive` writes nothing when PlaybackControl bit
  1 or 2 is set (:3338-3341), and the 6 is the DAC track's VoiceControl byte
  from `zFMDACInitBytes` (:1897). Neither S1 `DACUpdateTrack`
  (s1.sounddriver.asm:277-331) nor S2 `zDACUpdateTrack`
  (s2.sounddriver.asm:759-816) makes either call, so the behaviour is selected
  by a sequencer-config flag rather than added to every game.
  Separately, no driver uploads an instrument when a song loads: `zBGMLoad`'s
  FM/DAC loop only calls `zInitFMDACTrack`, which writes track RAM (:1837-1856,
  :2171-2199), and S1/S2 reach the same state through `InitMusicPlayback`
  (s1.sounddriver.asm:1486-1545, s2.sounddriver.asm:1738-1739). The engine was
  refreshing voice 0 onto the chip for every FM track at load, so it now selects
  the voice into track RAM only. And the DAC enable is not a load event either:
  `zPlayDigitalAudio` disables the DAC on entry and writes 2Bh = 80h from its
  idle loop once `zDACIndex` goes non-zero (:4256-4275), which is why the
  reference carries that write as tick 139's first event, not tick 138's.
  Admitting a sequencer no longer enables the DAC under the S3K config.
- **No constant was introduced.** All three changes remove or add a write whose
  presence and position are stated by the listing; none was measured from the
  fixture.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)` through `S1AudioParityTool capture` + `compare`; the S1
  gameplay v3 and run-2 oracles pass at their pinned lines; the S2 driver oracle
  `MATCH (698 ticks)`; the `com.openggf.tools.audio.parity` and
  `com.openggf.audio` packages, `TestSmpsFadeAudioThroughput` and the four S3K
  keep-green classes run 2,097 tests with 0 failures.

## 2026-09-04 — S1 gameplay run-2 oracle: tick 1,906 → full MATCH, Sound_PlaySpecial's stale-d4 PSG pair

- **Worktree/branch:** `.worktrees/audio-s1-run2`, `bugfix/ai-s1-run2-frontier`,
  from `develop` `1e128d0d6`.
- **Fixture:** `src/test/resources/audio/parity/s1/s1-gameplay-ghz1-run2-reference.v1.jsonl.gz`
  (5,257 ticks, from the committed `s1-complete-run.bk2`).
- **Command:** `LUA_BIN=lua5.4 mvn -Dmse=off '-Dsonic1.rom.path=<absolute S1
  REV01 .gen>'
  '-Dtest=com.openggf.tools.audio.parity.TestS1GameplayRun2AudioDriverOracle'
  test -B`
- **Before:** `MISMATCH`, first divergence tick **1,906**, event 0 — the
  reference opens that tick with PSG `$1F` then `$3F`, which the engine never
  emitted.
- **After:** **`MATCH (5257 ticks)`**. Both S1 gameplay windows now match end
  to end.
- **The routine.** Tick 1,906's only dispatch is `$D0`, the GHZ waterfall
  special SFX, so the writes belong to `Sound_PlaySpecial`
  (docs/s1disasm/s1.sounddriver.asm:1117). Its `.doneoverride` tail (:1183-1191)
  tests the NORMAL SFX PSG3 slot — `tst.b v_sfx_psg3_track.PlaybackControl /
  bpl.s .locret` — and when it is playing writes `ori.b #$1F,d4 / move.b
  d4,(psg_input) / bchg #5,d4 / move.b d4,(psg_input)`. The comment calls this
  "command to silence channel", but `d4`'s last assignment is `move.b 1(a1),d4`
  at the top of `.sfxloadloop` (:1141), so it holds the voice control bits of
  the last header entry the loop read. `SndD0 - Waterfall` declares one track,
  `cFM4` = `$04` (docs/s1disasm/sound/sfx/SndD0 - Waterfall.asm:7, `cFM4` at
  docs/s1disasm/sound/_smps2asm_inc.asm:176), so `$04 | $1F` = `$1F` and the
  `bchg #5` gives `$3F` — two SN76489 *data* bytes rather than the intended
  `$DF, $FF` latch pair. That is the shipped `FixBugs = 0` behaviour
  (docs/s1disasm/sonic.asm:20) and the engine now emits it as emitted.
- **Why it fires once in 23.** The fixture dispatches `$D0` twenty-three times
  and only tick 1,906 carries the pair, because the gate is the normal SFX PSG3
  slot. `$C1` "Break Item", the one nearby SFX with a `cPSG3` track
  (docs/s1disasm/sound/sfx/SndC1 - Break Item.asm:8), was dispatched thirteen
  ticks earlier at 1,893 and was still playing. No constant was introduced: the
  emitted bytes are computed from the special SFX's own last header entry, and
  the gate is the PSG3 slot's playing state, so both generalise to any movie.
- **Placement.** New `SmpsSequencerConfig.SpecialSfxPsg3SilenceMode`, set to
  `S1_STALE_VOICE_CONTROL_PAIR` only by `Sonic1SmpsSequencerConfig`; the S2 and
  S3K Z80 drivers keep `NONE`. No shared-sequencer behaviour changed.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0 through `run_s1_audio_parity.sh`; the S1
  gameplay v3 oracle stays green; the S2 driver oracle stays at
  `MATCH (698 ticks)`; the audio and audio-tooling packages run 2,501 tests
  with 0 failures and 0 errors.


## 2026-09-03 — a second S1 gameplay source, and it diverges where the first does not

- **Worktree/branch:** `.worktrees/audio-s1-widen`,
  `feature/ai-s1-oracle-widen`, on top of the v3 entry below.
- **Why a second recording.** The first gameplay fixture matches end to end,
  so it can no longer move anything. The oracle's bar is any BK2, not one
  BK2: a second complete run of the same ROM shares the driver code and the
  GHZ song and differs only in what the player did, so anything that breaks
  there and not here is about request sequencing rather than about either
  movie. No new input was recorded; this uses the already-committed
  `src/test/resources/traces/s1/_movies/s1-complete-run.bk2` (SHA-256
  `f744c814d8e0…`, 195,493 input rows), a different complete run from the
  `sonic1-complete-withemeralds.bk2` the first fixture uses.
- **What had to change to allow it.** The gameplay capture kind was pinned to
  one movie digest in three places. The probe now carries an `ACCEPTED_MOVIES`
  table keyed by the digest the launcher computed over the BK2 it actually
  handed EmuHawk, and `AudioParitySchema.GAMEPLAY_MOVIES` is the Java side of
  the same list, so a gameplay reference is identified by *which* pinned movie
  it came from. Nothing about the window rule, the normalization, or the
  engine host changed: both fixtures are the same single-song contract over
  the same GHZ song.
- **Fixture (new):** `s1-gameplay-ghz1-run2-reference.v1.jsonl.gz`, **5,257
  ticks**, epoch opens at frame 584 on this movie's GHZ1 BGM dispatch (269
  dormant invocations before it, against the first movie's 341, because its
  title and menu play is shorter), window closes at frame 5,841 on the first
  post-epoch music request, 165 SFX and special-SFX dispatches. Two BizHawk
  captures byte-identical, uncompressed SHA-256 `0f1059071c4e3cc5…`,
  44,776,890 bytes; two OpenGGF replay captures byte-identical.
- **Command:** `tools/audio/run_s1_audio_parity.sh --mode gameplay --movie
  src/test/resources/traces/s1/_movies/s1-complete-run.bk2 --rom <absolute S1
  REV01 .gen> --bizhawk-home <BizHawk 2.11 Linux x64> --output-root <external
  run root>`.
- **Result:** **MISMATCH**, first divergence **tick 1906, event 0, field
  `decoded_write`** — the reference's tick opens with two PSG writes, `$1F`
  then `$3F`, that the engine does not emit at all; the comparator lines the
  engine's first write (YM2612 port 0 register `$A4` = 42) up against the
  reference's `$1F`. From the reference's third write onward the two streams
  agree write for write. Normalized track state agrees on both sides at that
  tick, on every role, so the gap is in what the driver puts on the bus, not
  in what it thinks it is playing. Pinned by
  `TestS1GameplayRun2AudioDriverOracle.currentFrontierIsTheFirstDivergence`
  (ROM-gated, `-Dsonic1.rom.path=`). Not investigated: this lane is
  measurement only, and a future lane should find the ROM routine that owns
  those two writes before changing engine behaviour.
- **Broken on purpose:** flipping tick 0's first YM2612 register-40 write
  value in a temp copy of the new fixture is reported at tick 0 by
  `TestS1GameplayRun2AudioDriverOracle.corruptingTheReferenceIsDetectedAtTheCorruptedTick`,
  so the tick-1906 line above is a live comparison and not a stale pin.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0 through `run_s1_audio_parity.sh`. The S1
  gameplay v3 oracle stays at `MATCH (2562 ticks)`. The
  `com.openggf.tools.audio.parity` suite runs green, including the S2 driver
  oracle at `MATCH (698 ticks)`, and `-Pguards` runs 607 tests with no
  failures.


## 2026-09-03 — the S1 gameplay capture's "BizHawk self-termination" is the probe; window republished as v3

- **Worktree/branch:** `.worktrees/audio-s1-widen`,
  `feature/ai-s1-oracle-widen` from `develop` at `3ba2f7c3b`.
- **The question.** The v1/v2 entries below record that BizHawk's headless
  client self-terminated at frame 3,219 of `sonic1-complete-withemeralds.bk2`
  through the S1 gameplay probe — clean process exit 0, no Lua error, no
  signal — and that the cause was never isolated, so the window was bounded at
  frame 3,000.
- **The cause, with evidence.** It is the probe, and the reason nobody saw it
  is a swallow. `probe_runtime.lua`'s hook wrapper calls `finish()` — which
  runs `client.exit()` — *before* it re-raises the callback's error, so
  BizHawk leaves before any Lua error can reach the console or the launcher.
  Reproduced by running the probe against a copy of `probe_runtime.lua` that
  appends every hook failure to an external file before `finish()`:

  ```
  HOOK ERROR s1_audio_ghz_epoch frame 3219
    s1_audio_parity_contract.lua:436: music $87 accepted after capture epoch
  stack traceback:
    s1_audio_parity_contract.lua:436: in method 'acceptBgm'
    [string "main"]:685: in field 'callback'
  FINISH called at frame 3219
  RUN returned normally at frame 3220 cleanupFailures 0
  ```

  Not the launcher (it has no stop-frame handling at all), not the movie (the
  input row is unremarkable held-right), not BizHawk. `$87` is
  `bgm_Invincible` (`docs/s1disasm/_Constants.asm:344`): the movie collects the
  GHZ1 invincibility monitor at frame 3,219, the ROM calls `Sound_PlayBGM`, and
  the shared contract's `acceptBgm` treats any post-epoch music request as
  capture contamination and raises
  (`tools/tracechaser/bizhawk/audio/s1_audio_parity_contract.lua:436`).
  The earlier lane's `pcall` wrapping never fired because it covered the
  `invocationLifecycle` entry/close transitions, not the BGM hook.
- **What that means for the window.** Frame 3,219 is not an accident to be
  worked around; it is where this oracle's contract stops describing the ROM.
  Both sides are single-song: the reference normalizes track state against one
  song's ROM asset range (`GHZ_ASSET_BASE`/`GHZ_ASSET_END` in the probe), and
  the engine host `S1OpenGgfSfxAudioCapture` drives exactly one music
  sequencer, loaded from the epoch song. `Sound_PlayBGM` → `InitMusicPlayback`
  (`docs/s1disasm/s1.sounddriver.asm:1498-1502`) reloads driver RAM with a
  different song, so every later tick would be normalized against the wrong
  range.
- **The fix (probe/wrapper only, no engine change).** The probe now closes the
  capture cleanly at the first post-epoch music request, discarding the
  invocation that carries it (the ROM has already swapped songs by the time
  that invocation walks its tracks). `CAPTURE_END_FRAME` drops to a secondary
  safety bound. Separately, every probe hook error is appended to
  `<OGGF_OUT>.error` before the client can exit, and
  `tools/audio/run_s1_audio_parity.sh` fails the capture on a non-empty
  sidecar — so this class of failure can never again look like a clean exit 0.
- **Fixture (new):** `s1-gameplay-ghz1-reference.v3.jsonl.gz`, **2,562 ticks**
  (up from v2's 2,343), last captured invocation at emulator frame 3,218, 90
  SFX/special-SFX dispatches (up from 81). Two BizHawk captures byte-identical,
  uncompressed SHA-256 `39519de782fc21a8…`, 22,067,859 bytes; two OpenGGF
  replay captures byte-identical. v2 is retired in the manifest with its bytes
  kept.
- **Command:** `tools/audio/run_s1_audio_parity.sh --mode gameplay --rom
  <absolute S1 REV01 .gen> --bizhawk-home <BizHawk 2.11 Linux x64>
  --output-root <external run root>`.
- **Result:** **`MATCH (2562 ticks)`** — the whole reference, end to end. The
  218 ticks v2 never reached carry no divergence. The frontier is again the end
  of the capture, but the boundary is now the ROM's, not an arbitrary number.
- **Broken on purpose:**
  `TestS1GameplayAudioDriverOracle.corruptingTheReferenceIsDetectedAtTheCorruptedTick`
  flips tick 0's first YM2612 register-40 write value in a temp copy of v3 and
  the comparator reports the divergence at tick 0, so the green above is a live
  comparison and not a skipped one.
- **The next frontier, and what it costs.** There is none inside this fixture.
  Moving past frame 3,219 needs a multi-song oracle contract, not a longer
  capture: a per-tick asset range in the stream, and a music-sequencer swap in
  the engine host modelling `Sound_PlayBGM`. A second gameplay fixture from a
  different zone needs the same generalization one step earlier — the probe's
  epoch arms on `$81` specifically, the asset range is a GHZ constant, the
  ordinal-0 track-activity expectation is a GHZ literal, and
  `AudioParitySchema` pins one gameplay capture kind, movie, and launch
  invocation count. Both are contract work, and neither was attempted here.

## 2026-09-03 — S3K driver oracle: tick 50 → tick 128; the driver owns the SEGA PCM transport

- **Context:** `.worktrees/audio-s3k-sega-pcm`, branch
  `feature/ai-s3k-sega-pcm-transport`, from `develop` at `1f3d670bb`. Engine
  change plus the matching capture-host dispatch; no fixture change.
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz`.
- **Command:**

  ```
  java -cp "target/classes:$(cat target/s3k-oracle.classpath)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
    --rom <absolute locked-on S3K ROM>
  ```

- **Result before:** `EVENT_MISSING` at tick 50, field `decoded_write`, event 0,
  reference `ym2612[port 0, register 0x2b] = 128`, engine `<missing>`; the run
  also logged `tick 49: SEGA PCM transport is outside the driver-service
  oracle` as an unsupported request.
- **Result after:** `EVENT_MISSING` at tick 128, field `decoded_write`, event 0,
  reference `ym2612[port 1, register 0x82] = 255`, engine `<missing>`. The SEGA
  request is no longer unsupported; the five remaining unsupported requests are
  the `E1h` fades, unchanged.

- **The routines.** `zPlaySegaSound` calls `zStopAllSound`, sets
  `PlaySegaPCMFlag` and returns without reaching the loop
  (`Sound/Z80 Sound Driver.asm`:2703-2719), so the request's own service carries
  only the 84-write stop. The DAC idle loop reads the flag on its next pass and
  jumps into `zPlaySEGAPCM` (:4265-4267), which runs under `di` for its whole
  duration (:4372-4424): `2Bh = 80h`, one latch of `2Ah`, then one byte of
  `SEGA_PCM` per loop iteration until the sample ends or `cmd_StopSEGA` appears
  in `zMusicNumber`. Leaving the loop re-enters `zPlayDigitalAudio`, whose entry
  writes `2Bh = 0` (:4422, :4256-4260). `SonicDriverVer` is 4 for S&K
  (`sonic3k.asm:27`), so the `SonicDriverVer==3` queue work in that routine is
  assembled out. The byte cadence is the ROM's own macro,
  `pcmLoopCounterBase(sampleRate, 105)` (`sonic3k.macros.asm:270-271`) with
  `Z80_Clock = Master_Clock/15` (`sonic3k.constants.asm:202-204`): 12 loop
  iterations, 248 Z80 cycles per sample byte. No constant here was measured
  from the fixture.

- **The change.** `SmpsPhysicalPolicy` gains an optional `segaPcmTransport()`,
  described by the new `SmpsSegaPcmTransport` record (enter block, data
  port/register, exit block, sample rate, loop base cycles) which also computes
  the ROM's loop counter and can emit the whole transport as one
  `SmpsWriteProgram`. Only S3K's policy supplies one. `SmpsDriverSession` runs
  it: `beginSegaPcmTransport` writes the DAC enable, `serviceForward` returns
  the new `SEGA_PCM_TRANSPORT` outcome and runs no update while the loop holds
  the bus, `renderFrames` sends one sample byte every 248 Z80 cycles of
  rendered time, and the exit block is written when the sample ends or a
  requested stop is reached at a byte boundary. Rendering therefore comes from
  the chip's own DAC. `AudioVoiceRegistry` routes `ReplaceRawPcm` to the
  transport when the session's policy owns it and to the presentation voice
  otherwise, so S1 and S2 SEGA screens are byte-unchanged; their equivalent
  routines (`s2.sounddriver.asm:1603-1652`, `s1disasm/sound/z80.asm:187-206`)
  are the same shape and can adopt the vocabulary later. A muted frame still
  advances the loop, so a silent presentation cannot park the driver inside it.
  Rewind: the loop position, accumulator, stop flag and sample bytes are in
  `SmpsDriverSessionSnapshot` and in the per-frame live mutation, covered by
  `TestSmpsSegaPcmTransport`.

- **What the new frontier is.** Tick 128 is the reference's post-transport
  stop-all burst with an empty mailbox — the same pre-consumption 68k-to-Z80
  mailbox limitation recorded on 2026-08-31 and typed as
  `REFERENCE_LIMITATION` / `producer_input` for the v1 stream on 2026-09-01.
  The v2 stream is per-service and is read verbatim, so that classification is
  not applied on this path and the tool reports an ordinary `EVENT_MISSING`.
  Nothing in the reference authorises an engine request here: inferring
  `cmd_StopSEGA` from the shape of the burst is exactly the inference the
  earlier entry refused. Moving this frontier needs either a pre-consumption
  mailbox probe in the producer or the v2 reader learning the same suspension
  boundary the v1 reader knows. Both are reference-side work, not a driver fix.

- **Gates at this commit.** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, run as `S1AudioParityTool capture` + `compare` against
  the committed references in an external run root. S1 gameplay oracle
  `TestS1GameplayAudioDriverOracle` green at its pinned frontier. S2
  `TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare`
  reports `MATCH (698 ticks)`. `com.openggf.audio.**`,
  `com.openggf.tools.audio.parity.**`, `com.openggf.game.sonic3k.audio.**` and
  `TestSmpsFadeAudioThroughput` run 2,083 tests with 0 failures and 0 errors.
  The four S3K keep-green classes run 55 tests, 0 failures.

- **Tests that changed, and why.** Three tests pinned the chant as a
  presentation voice on S3K (`TestSegaPcmCommandRouting` twice,
  `TestSoundTestPresentationHost` once). They now pin the driver-owned
  mechanism instead — the DAC enable, the sample bytes on the physical stream,
  and the DAC disable on exit — rather than asserting less.


## 2026-09-03 — S1 gameplay oracle reaches full MATCH: the special-SFX voice pointer outlives its sound

- **Worktree/branch:** `.worktrees/audio-s1-special-frontier-2`,
  `bugfix/ai-s1-special-sfx-frontier-2`, on top of `4be2f8c9e`.
- **Fixture and command:** unchanged from the entries below
  (`s1-gameplay-ghz1-reference.v2.jsonl.gz`, gate
  `TestS1GameplayAudioDriverOracle`).
- **Result before:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 1759, event 0,
  field `decoded_write` — reference YM2612 port 1 register 76 (`$4C`, FM4
  operator 4 total level) value 132, engine value 4.
- **Result after:** **`MATCH (2343 ticks)`** — the whole reference, end to end.
- **What the divergence was.** Tick 1746 dispatches the spring SFX (`$CC`) onto
  FM4. Its own `SetVoice` is correct on both sides. From tick 1759 its
  `smpsAlterVol` writes run through `SendVoiceTL`, and there the reference adds
  the track volume to a total-level byte of `$80` where the engine adds it to
  `$00`. The `$80` belongs to the *waterfall's* voice bank, and the waterfall
  (`$D0`, last dispatched at tick 1450) had already finished.
- **The ROM routine.** Under the shipped `FixBugs = 0`, `SendVoiceTL` loads its
  voice pointer from `SMPS_Track.VoicePtr(a6)`
  (`docs/s1disasm/s1.sounddriver.asm:2391-2398`), with the disassembly's own
  `DANGER!` note that this should have been `a5`. `VoicePtr` sits at offset 32
  in the track struct (`s1.sounddriver.ram.asm:1-30`) and offset 32 in the
  driver RAM block is `v_special_voice_ptr`
  (`s1.sounddriver.ram.asm:32-56`), so every normal SFX track reads the special
  SFX's voice bank. For a *special* track the following `bmi` falls through and
  the pointer is reloaded correctly (`:2399-2401`), so the bug is confined to
  normal SFX. `Sound_PlaySpecial` is that global's only writer (`:1128-1132`);
  nothing clears it when the special SFX ends. It is wiped only when the driver
  globals are, by `InitMusicPlayback` (`:1498-1502`) and `StopAllSound`
  (`:1468-1478`).
- **The engine change.** `SmpsDriver.s1SpecialSfxVoiceForBug` used to scan the
  live SFX sequencers for a special one, so the aliased bank vanished the moment
  the waterfall stopped and the fallback zero voice was used instead. The driver
  now holds `s1SpecialVoicePointer`, latched at special-SFX admission, cleared
  on music start and on `stopAll`, and carried through the live-command
  rollback token. That is the ROM global modelled as a global. No constant was
  introduced and no game-name or zone branch was added; only S1's profile
  selects `S1_SPECIAL_POINTER_BUG`.
- **The next frontier.** There is none inside this fixture: the capture is
  exhausted at 2,343 ticks. Moving further needs a longer or different S1
  gameplay capture, which is capture work.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0, run as `S1AudioParityTool capture` +
  `compare` against the committed v1 references. The audio packages plus
  `TestSmpsFadeAudioThroughput` run 2,487 tests with 0 failures and 11 skips,
  which includes the S2 driver oracle at `MATCH (698 ticks)`. The S3K oracle
  stays exactly at its pinned line, `EVENT_EXTRA` tick 0 event 84, engine
  `ym2612[port 0, register 0x2b] = 0` against a missing reference write.

## 2026-09-03 — S3K driver oracle: tick 0 → tick 50; the DAC loop's entry write leaves the driver init

- **Context:** `.worktrees/audio-s3k-tick0`, branch `bugfix/ai-s3k-oracle-tick0`,
  from `develop` at `2415a3ad0`. Engine fix, no fixture change.
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz`.
- **Command:**

  ```
  java -cp "target/classes:$(cat target/s3k-oracle.classpath)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
    --rom <absolute locked-on S3K ROM>
  ```

- **Result before:** `EVENT_EXTRA` at tick 0, field `decoded_write`, event 84,
  reference `<missing>`, engine `ym2612[port 0, register 0x2b] = 0`.
- **Result after:** `EVENT_MISSING` at tick 50, field `decoded_write`, event 0,
  reference `ym2612[port 0, register 0x2b] = 128`, engine `<missing>`.

- **The routine.** `zInitAudioDriver` calls `zStopAllSound`, whose last two
  writes are 2Bh then 27h with interrupts disabled (`Sound/Z80 Sound
  Driver.asm`:2506-2520), stores its driver variables, runs `ei`, and then
  `jp zPlayDigitalAudio` (:550-551) — it never returns. `zPlayDigitalAudio`
  opens with `di / ld a,2Bh / ld c,0 / call zWriteFMI` (:4256-4260). That 2Bh
  therefore belongs to the window the first `zVInt` return closes, not to the
  init. `Sonic3kSmpsPhysicalPolicy.boot()` had appended it as an 85th boot
  write, with a comment saying so.

- **The change.** `SmpsPhysicalPolicy` gains an `enterDacIdleLoop()` program,
  empty by default. S3K's policy returns the single 2Bh=0 write and its
  `boot()` is now exactly `stopAll()`'s 84 writes. `SmpsDriverSession` applies
  the entry program immediately after `boot()` at both install and hard-reset
  sites, so the physical device sees the same ordered stream it saw before. The
  S3K oracle host applies it after recording the boot tick, so the write opens
  tick 1 — matching the reference, whose tick 1 also opens with 2Bh=0. S1 and
  S2 delegate `boot()` to `LegacyCompatibilitySmpsPhysicalPolicy` and do not
  override the new method, so their write streams are byte-unchanged;
  `TestSmpsPhysicalPolicy` now pins that emptiness.

- **What the new frontier is, and why it is not a driver bug.** Tick 49 carries
  request `FFh`, the SEGA chant. `zPlaySEGAPCM` (:4372-4424) runs with `di` for
  its whole duration: it writes 2Bh=80h to enable the DAC, then one 2Ah write
  per byte of `SEGA_PCM` until the sample is exhausted or `cmd_StopSEGA`
  arrives. Because interrupts are masked throughout, the entire transport lands
  in a single service window — reference tick 50 carries 24,113 writes, against
  `SEGA_SOUND_SIZE = 0x5E2F` (24,111) in `Sonic3kSmpsConstants`. The engine
  produces none of them: it renders the chant as a presentation-layer PCM voice
  (`AudioPresentationSourceFactory.segaPcm` /
  `SampleBackedVoice.rawSegaPcm`), entirely outside the SMPS logical driver.
  The oracle host records this as an unsupported request rather than
  fabricating a stream. Closing it is an engine change — moving the chant into
  the driver as a Z80-faithful 2Ah transport — that touches presentation,
  rewind and all three games' SEGA screens, so it is subsystem work, not a
  first-divergence fix. Emitting the writes in the capture host alone would
  make the oracle agree with itself while the driver still does nothing.

- **What the wall is worth, measured.** A throwaway, uncommitted host patch
  emitted the transport exactly as the ROM writes it — 2Bh=80h, then one 2Ah
  write per byte of `SEGA_PCM` read through `Sonic3kAudioProfile.loadSegaPcm`,
  then the 2Bh=0 that `zPlayDigitalAudio` writes when `.done` jumps back into
  it (:4422, :4256-4260). That reproduces the reference row's 24,113 writes
  exactly and moves the frontier from tick 50 to **tick 128**, the PCM-exit
  service. So the transport needs no fitted quantity: its length is
  `SEGA_PCM.size` and its brackets are two entry blocks already cited above.
  What it needs is an owner. The patch was reverted rather than landed because
  emitting the stream from the capture host would make the oracle agree with
  itself while the engine's driver still produces nothing; the work belongs in
  the driver, alongside the presentation voice that currently plays the chant.

- **Tick 138 not reached.** The v1 tick-138 sampling artefact could not be
  re-checked past the tick-50 wall in this lane. The probe above stops at tick
  128, still short of 138, so nothing measured here says whether the v1
  artefact is gone.

- **Regression gates.** S2 driver oracle `MATCH (698 ticks)`, unchanged. The
  `com.openggf.tools.audio.parity.**` and `com.openggf.audio.session.**` suites
  are green. Three tests that pinned the old placement were updated, not
  weakened: the v2 contract's boundary assertion now pins the corrected
  placement on both sides (it previously documented itself as "the assertion to
  move" when the engine stopped owning the write), and two v1 contract
  assertions now record the retired stream's frame-smeared boot row as the
  artefact it is.


## 2026-09-03 — S1 gameplay oracle: tick 933 → 1759, a normal SFX silences a special one without stopping it

- **Worktree/branch:** `.worktrees/audio-s1-special-frontier`,
  `bugfix/ai-s1-special-sfx-frontier`, on top of `8d001b3f0`.
- **Fixture and command:** unchanged from the entries below.
- **Result before:** MISMATCH, `TRACK_STATE_MISMATCH`, tick 933, role FM4,
  field `overridden` — reference true, engine false.
- **Result after:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 1759, event 0,
  field `decoded_write` — reference YM2612 port 1 register 76 value 132, engine
  value 4.
- **What the divergence was.** The ring admitted at tick 911 took FM4 from the
  waterfall and the engine deactivated the waterfall's track to do it. When the
  ring finished at 933 there was no special SFX left to hand the channel back
  to, so FM4 returned to music and the music override cleared. The reference
  keeps it set: the waterfall was still playing all along.
- **The ROM routine.** `Sound_PlaySFX` loads its own tracks and then tests the
  SFX track it just wrote: with FM4 in use it sets bit 2, 'SFX is overriding',
  on `v_spcsfx_fm4_track` (`docs/s1disasm/s1.sounddriver.asm:1072-1074`), and
  PSG3 the same way (`:1077-1079`). It never clears the special track's playing
  bit. The special SFX keeps advancing silently and gets the channel back
  through `cfStopTrack`, which is the release edge the commit two below added.
- **The engine change.** The admission-time displacement scan now skips any
  pairing of one special and one normal SFX in either direction, not just the
  special-admits-over-normal direction, and `overrideIncumbentSpecialSfx` marks
  the incumbent special track overridden when a normal SFX takes its channel.
  Together with the earlier `yieldsToIncumbentSfx` this makes the two sound
  classes coexist the way the ROM's separate track RAM does.
- **The next divergence.** Tick 1759 differs on an FM4 operator total-level
  write, 132 against 4. That is a volume difference inside the special SFX
  rather than a channel-ownership one, so it is a fresh line of enquiry.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0. The audio packages plus
  `TestSmpsFadeAudioThroughput` run 2,481 tests with 0 failures and 11 skips,
  which includes the S2 driver oracle at `MATCH (698 ticks)`. The ordinary
  suite with all three ROM paths runs 16,356 tests with 0 failures and 20
  skips, and the separate `-Pguards` invocation runs 607 with 0 failures. The
  S3K oracle was not re-run, for the reason recorded three entries below.

## 2026-09-03 — S1 gameplay oracle: tick 641 → 933, the special-SFX slots walk last

- **Worktree/branch:** `.worktrees/audio-s1-special-frontier`,
  `bugfix/ai-s1-special-sfx-frontier`, on top of `4eedcbbb0`.
- **Fixture and command:** unchanged from the entries below.
- **Result before:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 641.
- **Result after:** MISMATCH, `TRACK_STATE_MISMATCH`, tick 933, role FM4,
  field `overridden` — reference true, engine false.
- **What the divergence was.** Tick 641 emitted the same 35 writes on both
  sides in a different order. The engine serviced the waterfall's three FM4
  writes first and the ring's FM5 voice load after; the reference does the
  reverse.
- **The ROM routine.** Special-SFX tracks live in their own RAM block straight
  after the SFX block (`docs/s1disasm/s1.sounddriver.ram.asm:98-105`), and
  `UpdateMusic` walks them after every normal SFX slot: SFX FM3..FM5
  (`s1.sounddriver.asm:243-250`), SFX PSG1..PSG3 (`:252-256`), then the two
  special slots, FM4 then PSG3 (`:258-268`). A normal SFX admitted later is
  still serviced before a special SFX already playing, whatever channels they
  hold. The driver's slot walk sorted purely by channel, so the special FM4
  track sorted ahead of a normal SFX on FM5.
- **The engine change.** `SmpsSequencer.sfxSlotWalkOrder` takes the sequencer's
  special-SFX flag and offsets special tracks past every normal SFX slot,
  keeping FM before PSG within the special block as the ROM does.
- **The next divergence.** At tick 933 a ring finishes on FM4 and the engine
  releases the channel to music, clearing the music override, where the ROM
  hands it back to the special track. `Sound_PlaySFX` marks the special track
  overridden rather than stopping it (`:1072-1074` for FM4, `:1077-1079` for
  PSG3), so the waterfall is still playing there; the engine deactivates the
  special track when a normal SFX takes its channel, which is the mirror of
  the admission-time bug fixed two commits below. That is the next target.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0. The audio packages plus
  `TestSmpsFadeAudioThroughput` run 2,481 tests with 0 failures and 11 skips,
  which includes the S2 driver oracle at `MATCH (698 ticks)`. The S3K oracle
  was not re-run, for the reason recorded two entries below.

## 2026-09-03 — S1 gameplay oracle: tick 629 → 641, cfStopTrack hands FM4 to the special track

- **Worktree/branch:** `.worktrees/audio-s1-special-frontier`,
  `bugfix/ai-s1-special-sfx-frontier`, on top of `39776b1af`.
- **Fixture and command:** unchanged from the entry below.
- **Result before:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 629 — reference
  YM2612 port 1 register 176 value 56, engine value 44.
- **Result after:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 641. Ticks 629
  through 640 now agree write-for-write, including the waterfall's repeating
  FM4 frequency and key-on writes.
- **What the divergence was.** At tick 629 the ring SFX finishes on FM4 and the
  engine restored the *music* voice, value 44. The reference loads value 56,
  the waterfall's own voice.
- **The ROM routine.** `cfStopTrack`'s FM4 case tests
  `v_spcsfx_fm4_track`'s PlaybackControl before anything else
  (`docs/s1disasm/s1.sounddriver.asm:2512-2517`). With a special SFX playing it
  points at the special track and at `v_special_voice_ptr`, the special SFX's
  own voice table, then falls into `.gotpointer` (`:2529-2533`): clear the
  track's 'SFX overriding' bit, set 'track at rest', reload its current voice.
  The music track is never reached, so the music override bit survives the
  release. PSG3 takes the same shape through `.getpsgptr` (`:2540-2547`,
  restore at `:2554-2556`).
- **The engine change.** `SmpsDriver.waitingSpecialSfx` finds a special SFX with
  an active track on a channel a normal SFX is releasing. When one is waiting,
  the release hands the lock to it and clears the override on *its* track
  instead of on music, which reloads its own voice through the existing
  `ROM_VOICE_RESTORE` path. The admission-time yield added in the commit below
  now also marks the yielding special SFX's own track overridden, which is the
  bit `Sound_PlaySpecial` sets at `:1180-1182` and `:1185-1187` and what makes
  the release edge exist.
- **The next divergence.** Tick 641 dispatches a ring while the waterfall holds
  FM4. The reference loads that ring's voice on FM5 (port 1 register 177) and a
  second ring on FM4 at tick 642; the engine emits only the waterfall's
  continuation and nothing for the ring. That is the next lane's target.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0. S2
  `TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare`
  `MATCH (698 ticks)`. The audio packages plus `TestSmpsFadeAudioThroughput`
  run 2,481 tests with 0 failures and 11 skips. The S3K oracle was not re-run,
  for the reason recorded in the entry below.

## 2026-09-03 — S1 gameplay oracle: tick 618 → 629, a special SFX no longer steals a busy channel

- **Worktree/branch:** `.worktrees/audio-s1-special-frontier`,
  `bugfix/ai-s1-special-sfx-frontier`, on top of `234f1f606`.
- **Fixture:** `src/test/resources/audio/parity/s1/s1-gameplay-ghz1-reference.v2.jsonl.gz`,
  unchanged. Gate `TestS1GameplayAudioDriverOracle`.
- **Command:**

  ```
  LUA_BIN=lua5.4 mvn -Dmse=off -Dsonic1.rom.path=<absolute S1 REV01 .gen> \
    -Dtest=com.openggf.tools.audio.parity.TestS1GameplayAudioDriverOracle test -B
  ```

- **Result before:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 618, event 3,
  field `decoded_write` — reference `<missing>`, engine YM2612 port 1 register
  176 (`0xB0`, feedback/algorithm) value 56.
- **Result after:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 629 — reference
  YM2612 port 1 register 176 value 56, engine value 44.
- **What the divergence was.** Tick 618 dispatches the GHZ waterfall
  (`$D0`) through `Sound_PlaySpecial` while the ring SFX admitted at tick 593
  is still playing on FM4. The engine took FM4 from the ring at admission and
  played the waterfall's first note immediately: the whole 28-write voice load
  and key-on at tick 618. The reference emits nothing on FM4 until tick 629,
  where the ring's `cfStopTrack` finally hands the channel over.
- **The ROM routine.** `Sound_PlaySpecial`
  (`docs/s1disasm/s1.sounddriver.asm:1117`) initialises only the `v_spcsfx_*`
  slots. It sets bit 2 on the *music* slot as `Sound_PlaySFX` does (`:1146` for
  FM4, `:1153` for PSG3), but it never writes `v_sfx_fm4_track` or
  `v_sfx_psg3_track`. When those normal-SFX tracks are already playing it
  instead sets bit 2 on its own special track (`:1180-1182` and `:1185-1187`),
  so the special SFX advances its timing silently. The channel changes hands
  only in `cfStopTrack`'s special-track branch (`:2514-2518`), which is exactly
  the mechanism the earlier tick-629 entry below described from the other side.
- **The engine change.** `SmpsDriver` gained one predicate,
  `yieldsToIncumbentSfx`, applied at both admission-time sites that were
  taking the channel: the displacement scan in `prepareNewSfxAdmission` and
  the ownership install in `installPreparedSfxChannelOwnership`. A special SFX
  no longer displaces or relocks a channel held by a normal SFX. The music
  override bit is still set, because the ROM sets it unconditionally. This is
  the admission-time counterpart of the precedence `shouldStealLock` already
  applied per write; no new game-name or zone branch was introduced, and only
  S1's profile ever sets the special-SFX flag.
- **The next divergence.** At tick 629 both sides now emit a voice load on the
  same tick, but load different voices: the reference's `cfStopTrack` FM4
  branch takes `v_special_voice_ptr` and applies it to the special track
  (`:2514-2521`), while the engine restores the music voice on release. That is
  the next lane's target.
- **Gates at this commit:** S1 sound-test music `MATCH (14690 ticks)` and SFX
  `MATCH (1967 ticks)`, both exit 0, run as `S1AudioParityTool capture` +
  `compare` against the committed v1 references. S2
  `TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare`
  `MATCH (698 ticks)`. The S3K oracle was not re-run: its command needs an
  external request sidecar that is not present in this worktree, and no S3K
  path can reach the changed code, since the special-SFX flag is set only by
  S1's audio profile.

## 2026-09-03 — S3K reference republished as v2, sampled at the zVInt return; frontier moves to tick 0

- **Context:** `.worktrees/audio-s3k-reference-v2`, branch
  `feature/ai-s3k-oracle-reference-v2`, with `tools/tracechaser` on
  `bugfix/ai-s3k-reference-v2` at `8e32d256e`. New producer, new manifest, new
  fixture and reader support for the v2 schema; no engine change.
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz`
  (identity in `s3k-aiz1-intro-metadata-v2.json`): 5,263 driver services over
  movie frames 0-5399 of the committed `s3k-complete-sonic-tails.bk2` from
  power-on, 725,898 decoded YM/PSG writes. Two serial captures to separate
  external roots were byte-identical at
  `c8174a3f150409bb2511c09b85d511ffd6acd98aff8abc682c1f52f632623f06`.
- **Command:**

  ```
  java -cp "target/classes:$(cat target/s3k-oracle.classpath)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz \
    --rom <absolute locked-on S3K ROM>
  ```

- **Result:** `EVENT_EXTRA` at tick 0, field `decoded_write`, event 84,
  reference `<missing>`, engine `ym2612[port 0, register 0x2b] = 0`.
- **Result before (against v1):** `TRACK_STATE_MISMATCH` at tick 138, role
  `MUS_FM1`, field `resting`. That divergence is gone, and it was not an engine
  defect: the entry below records it as a v1 sampling artefact.

- **What the producer changed.** v1 was captured by the native harness, not a
  Lua probe. `tools/audio/s3k/S3kAudioOracleReferenceCapture.cs` looped once per
  video frame and read driver RAM with `host.ReadZ80RamByte` after
  `host.Advance()` returned, so the snapshot landed wherever the Z80 happened to
  be. Its declared `sampling: "post_invocation"` only held on frames where the
  driver was already idle. v2 removes the out-of-band read: a snapshot range
  covering `1C00h..1FA0h` is attached to the observer's own service-completion
  hooks, so `emit_completion` captures the window inside the emulated CPU at the
  completing instruction and delivers it inline and ordered in the event stream.
  The pinned patch-0001 core already implements this; the S2 manifest has used
  the same mechanism for its `SoundDriverLoad` image since it was written.

- **Why the boundary is `0084h` and not the end of the music update.** The
  chosen instruction is the `ret` that ends `zVInt`
  (`Sound/Z80 Sound Driver.asm`:520; the disassembly's own `;loc_85` comment at
  :522 pins the address). `zVInt` is the RST 38h target at `0038h`, calls
  `zUpdateEverything` at `0042h`, and that call returns to `0045h` (:471-482).
  `0045h` is unusable as a per-interrupt boundary in two ways, both on shipped
  paths: the PAL double-update jumps back to `.doupdate` at `003Dh` and runs
  `zUpdateEverything` a second time inside one interrupt (:485-495), and while
  paused `zPauseUnpause` pops its own return address (:2232-2242) so neither
  `zUpdateSFXTracks` nor `zUpdateMusic` executes yet `0045h` is still reached.
  `0084h` is reached exactly once per interrupt. Within the captured window the
  two differ by one byte, `zPalDblUpdCounter` at `1C04h`. The driver assembles
  as `SonicDriverVer = 4` (`sonic3k.asm:27`), so the ver-3 PAL tempo bug and the
  `unk_1C21` write are not present; `fix_sndbugs = 0` throughout (D:16).

- **Tick semantics, stated.** One tick is one completed driver service, not one
  video frame, and each row names which service it was. Two kinds qualify: the
  one-shot `DriverInit`, and every `zVInt` return. When an invocation overruns a
  frame the tick belongs to the service that completed, so a frame may carry no
  row at all. 5,400 replayed frames yield 5,263 rows. This is the model the S2
  oracle already uses (see the 2026-08-30 S2 entry on tick recovery). Writes are
  partitioned by the same boundary, so every write still appears exactly once in
  stream order: the v2 write count, 725,898, is identical to v1's.

- **The tick-138 frame, directly.** Movie frame 252 is the title-music load. In
  v2 it produces **no row**: the driver's work runs past the frame boundary and
  `zVInt` reaches `0084h` during frame 253. The row that covers the load has
  every music track parsed, with `MUS_FM1` at `PlaybackControl 90h` — the values
  v1 only reached a frame later. `TestS3kAudioOracleFixtureContractV2` pins both
  properties.

- **What the new frontier is.** A real engine-side misplacement of one write,
  now visible because the boot boundary is exact rather than frame-smeared. The
  ROM's `zStopAllSound` writes YM `2Bh` then `27h` with interrupts disabled
  throughout (D:2506-2520), and `zInitAudioDriver` only enables interrupts
  afterwards, at :550, before `jp zPlayDigitalAudio` (:551). So the init's own
  last write is `27h`, and v2's boot row ends there with 84 writes. The `2Bh`
  write that follows in the stream is a *different* one: the first instruction
  block of `zPlayDigitalAudio` (D:4258-4262), which the init jumps into and
  never returns from. The engine emits that `2Bh` inside its own driver init, so
  it appears one service early. v1's boot service reported 85 writes because its
  frame-granular projection swept the whole frame and took that write in, which
  is why the mismatch was invisible before. **Measurement only; not fixed in
  this lane.**

- **Break-it evidence.** The comparator reads the reference bytes it claims to:
  with `zCurrentTempo` at `1C24h` corrupted in tick 0 of a copy of the fixture
  (`^ 0x55`, terminal digest repaired), the reported divergence changes from the
  above to `GLOBAL_STATE_MISMATCH` at tick 0, field `currentTempo`,
  reference `85`, engine `0`.

- **v1 status.** Retained, byte-unmodified, and retired in metadata. Its
  contract test still passes, so the retired stream remains readable.

- **Regression gates.** All green at this commit. S1 GHZ music oracle
  **`MATCH (14690 ticks)`** and S1 sound-test SFX oracle **`MATCH (1967 ticks)`**,
  both via `tools/audio/run_s1_audio_parity.sh` with live capture pairs. S2
  driver oracle **`MATCH (698 ticks)`**, unchanged. The
  `com.openggf.tools.audio.parity.**` suite reports 152 passing with 2 skips,
  the skips being ROM-gated measurements with no supplied sidecar path (155
  passing after the frame-isolation and frame-shape guards were added).
  `mvn -Dmse=off -Pguards test` reports 607 passing. Two guards failed on the
  first run and were updated deliberately, not weakened:
  `TestTraceChaserBoundaryGuard` and `TestBuildToolingGuard` pin the exact
  `tools/tracechaser` gitlink, which this change advances to `8e32d256e`.
  TraceChaser's own `repository_policy.py` and `history_audit.py` both PASS.

- **Approval note.** TraceChaser's rule 4 treats installing a canonical fixture
  as a human-approved step. This fixture is committed to an unmerged branch as
  the artifact to approve, not merged to `develop`.

- **Window frame shape**, recorded in the fixture metadata so a later reader
  knows what to expect. Of 5,400 replayed frames, 137 complete no service: 14
  before the 68k has loaded the driver, and 123 the driver's work runs past, so
  the Z80 misses that frame's vertical interrupt. Movie frame 252 is one of the
  123. The longest contiguous block starts at frame 64 and is `zPlaySEGAPCM`,
  which disables interrupts for its whole transport. **No frame completes two
  services**: `0084h` is reached once per interrupt, and this movie is NTSC so
  the PAL double-update never runs.

- **The `frame` field is provenance, never an input.** It exists so a divergence
  can name the movie frame a service completed in. The engine host and the
  comparator never read it — verified by perturbation rather than inspection, in
  `TestS3kAudioOracleFixtureContractV2`: rewriting every reference frame to
  `frame + 9000` leaves both the engine capture and the comparison bit-for-bit
  unchanged, while the same guard fails loudly when a real engine input (the
  mailbox) is perturbed instead. The engine host runs exactly one service per
  reference tick.

- **The pinned shared manifest is untouched.** The oracle boundary lives in a new
  file, `fixtures/gpgx-audio-service-manifest-s3k-oracle-v2.json`. The TraceChaser
  commit adds that one file and changes nothing else, so
  `fixtures/gpgx-audio-service-manifests-v1.json` still hashes to
  `ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0`, byte-identical
  to the previous gitlink. `GpgxZ80AudioCapabilityTests`' capability lock hashes
  that shared file and the harness tests name their manifests by exact filename
  with no directory enumeration, so nothing in this lane reaches the lock and no
  regeneration is needed.


## 2026-09-03 — S1 gameplay oracle: tick 629 (reference gap) → tick 618 (real engine divergence), special-SFX dispatch now captured

- **Worktree/branch:** `.worktrees/audio-s1-probe-special`,
  `feature/ai-s1-gameplay-probe-special`, on top of `dea9404e4`. Measurement
  only for the frontier move itself; the probe/capture-host changes that make
  the new tick observable are a small, mechanical routing fix (see below), not
  a driver-behaviour fix.
- **What changed — the probe.** `Sound_PlaySFX` (`docs/s1disasm/s1.sounddriver.asm:977`,
  PC `$721C6`) and `Sound_PlaySpecial` (`:1117`, "Sound_D0toDF") are two
  disjoint entry points reached directly from `PlaySoundID`'s shipped
  (`FixBugs = 0`) dispatch (`:690-706`): normal SFX ($A0-$CF) branches to
  `Sound_PlaySFX`, special SFX ($D0-$DF checked, though only $D0/Waterfall has
  a real `Go_SpecSoundIndex` entry -- anything else would already have crashed
  the ROM) branches directly to `Sound_PlaySpecial`. The v1 probe
  (`tools/audio/probes/s1_gameplay_driver_parity_probe.lua`) hooked only
  `Sound_PlaySFX`, so it never observed a `Sound_PlaySpecial` call -- the GHZ
  waterfall dispatch was invisible to the fixture. `Sound_PlaySpecial`'s PC
  ($7230C) was not obtainable from `RomOffsetFinder --game s1 find
  Sound_PlaySpecial` (it returned a stale/wrong offset -- cross-checked by
  re-deriving the already-known-good `Sound_PlaySFX` address the same way and
  finding it also wrong by a non-constant delta); the address was instead
  found by scanning the ROM for the opcode both routines share as their first
  instruction (`tst.b SMPS_RAM.f_1up_playing(a6)` = `4a2e0027`, byte-verified
  against v_fadeout_counter/f_fadein_flag tests and the `Go_SpecSoundIndex`
  table lookup that follows). The probe now hooks both PCs, asserting
  `$A0-$CF` at the `Sound_PlaySFX` site and `$D0-$DF` at the `Sound_PlaySpecial`
  site (the shipped-bug range, not narrowed to `$D0` -- see CLAUDE.md's
  `FixBugs` guidance) and appending either into the same flat per-tick
  `dispatches` array. **No schema change**: a recorded id's own value (>=
  `Sonic1Sfx.NORMAL_ID_MAX + 1`, i.e. `Sonic1AudioProfile.isSpecialSfx`)
  already disambiguates a special-SFX dispatch from a normal one, so a new
  field would have been redundant.
- **What changed — the replay host.** `S1OpenGgfSfxAudioCapture` (the shared
  host this oracle and the committed SFX oracle both use) unconditionally
  called `sequencer.setSpecialSfx(false)`. It now calls
  `sequencer.setSpecialSfx(profile.isSpecialSfx(soundId))` -- the engine's
  `SmpsSequencer`/`Sonic1SmpsLoader` already fully supported special-SFX
  loading and playback (`loadSfx` already redirected internally via
  `loadSpecialSfx`); the host just never asked for it.
- **Recapture.** `tools/audio/run_s1_audio_parity.sh --mode gameplay`
  (unchanged launcher, same movie/window: power-on through frame 3,000 of
  `sonic1-complete-withemeralds.bk2`). Two BizHawk captures byte-identical and
  two OpenGGF replay captures byte-identical (both checked by the launcher
  before it proceeds, `cmp -s`); uncompressed reference SHA-256
  `c8fe427155e405c234162152f23f74c941dcef24d9d9952984db63cf3c028ac7`
  (20,157,508 bytes, 2,343 ticks, **81 dispatches** vs v1's 70 -- the 11 new
  ones are all `Sound_PlaySpecial` calls to id `$D0`, GHZ waterfall).
  Published as `s1-gameplay-ghz1-reference.v2.jsonl.gz`; v1 retained
  unmodified (retired, not deleted -- see
  `src/test/resources/audio/parity/s1/fixture-manifest.json`).
- **Result:** **MISMATCH**, `EVENT_VALUE_DIFFERENT`, tick 618, event 3, field
  `decoded_write` -- reference `<missing>`, engine
  `AudioParityChipWrite[chip=ym2612, port=1, register=176(0xB0), value=56]`.
  This is a **real engine divergence**, not a reference gap: the engine's
  admitted special-SFX sequencer emits an FM frequency write the real ROM's
  `Sound_PlaySpecial` run at this tick did not. Not investigated in this lane
  (measurement only, per the brief); a future lane should chase
  `s1.sounddriver.asm`'s special-SFX track service (`:1117` onward,
  `cfStopTrack`'s special-track branch at `:2510-2515`) before changing engine
  behaviour. The old tick-629 divergence (music FM4 override surviving past a
  special-SFX release) no longer reproduces as the first divergence because
  the new frontier at 618 is strictly earlier in the same window.
- **Break-on-purpose evidence:** flipping one YM2612 register-40 write value
  in tick 0 of a temp copy of the v2 reference is reported at tick 0 by
  `TestS1GameplayAudioDriverOracle.corruptingTheReferenceIsDetectedAtTheCorruptedTick`
  (still green against v2, confirming the comparison is live, not vacuous).
- **Gates at this commit:** `run_s1_audio_parity.sh --mode music` MATCH
  (14,690 ticks); `--mode sfx` MATCH (1,967 ticks); `--mode gameplay` reports
  the mismatch above (exit 3, expected); S2
  `TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare`
  MATCH (698 ticks); `com.openggf.tools.audio.parity.**` 136 tests, 0
  failures, 4 skipped (unrelated missing S3K/other optional inputs);
  `-Pguards` green.

## 2026-09-03 — S3K tick 138 is a mid-update reference snapshot, not an engine gap

- **Context:** `.worktrees/audio-s3k-frontier`, branch
  `bugfix/ai-s3k-oracle-frontier` on `128a8864e`. No engine, tool, comparator
  or fixture code changed; this entry records a measurement and its cause.
- **Command:**

  ```
  java -cp "target/classes:$(cat target/s3k-oracle.classpath)" \
    com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz \
    --requests <external>/s3k-request-observations.json \
    --rom <absolute locked-on S3K ROM>
  ```

- **Result:** unchanged. `TRACK_STATE_MISMATCH` at tick 138, role `MUS_FM1`,
  field `resting`, reference `false`, engine `true`.
- **Diagnosis — the reference row is a partially executed driver update.**
  Tick 138 is movie frame 252, the frame that consumes the title-music request
  `25h`. The reference's own RAM snapshot for that tick is internally
  inconsistent with any completed `zUpdateMusic` pass:

  - `MUS_DAC` has parsed: `DurationTimeout` `06`, `SavedDAC` `86`, its data
    pointer advanced past the song header.
  - `MUS_FM1` is mid-`zGetNextNote`: `DurationTimeout` is `00`, so the note
    timer already ran (`zTrackRunTimer` under `fix_sndbugs = 0`,
    `Sound/Z80 Sound Driver.asm:1102-1107`), and `VoiceIndex` is already `4`,
    so a coordination flag in the track stream was already handled. But the
    track's data pointer is still the header value `F8BEh`, because
    `zFinishTrackUpdate` had not yet written it back, and the rest bit is
    still clear.
  - `MUS_FM2` through `MUS_PSG3` are untouched at their post-init
    `DurationTimeout` of `01`, the value `zZeroFillTrackRAM` writes
    (`Sound/Z80 Sound Driver.asm:2181-2196`).

  That is a strict prefix of the update order `zUpdateMusic` uses: FM6/DAC
  first, then `zSongFM1` onwards through `zTrackUpdLoop`
  (`Sound/Z80 Sound Driver.asm:703-742`). All seven music loads in the fixture
  show the same shape, each truncated at a different point in that same order:
  frame 252 stops inside FM1, frame 619 inside FM2, frame 877 inside FM1,
  frame 3969 inside the DAC track, and frames 1619, 2145 and 5166 stop before
  any track ran. A prefix of the ROM's own iteration order, cut at a varying
  point, is the signature of the Z80 being halted part-way through the
  invocation, not of any per-track rule.

- **Why this is not an engine defect.** The ROM loads music inside
  `zUpdateMusic`: `zCycleSoundQueue` reaches `zPlayMusic` and `zBGMLoad`
  (`Sound/Z80 Sound Driver.asm:658-703`, `1717-1885`) and then falls straight
  into `.update_music` in the same pass, which is exactly what the engine
  does. The engine's tick-138 state equals the reference's tick-139 state, so
  the engine is executing the right pass; the reference simply sampled the
  driver before it finished. There is no ROM rule that makes the DAC track
  parse on the load frame and the FM tracks parse on the next one: the
  fixture's own loads contradict any such rule.

- **Verdict: reference limitation of the v1 capture, at the frame-boundary
  sampling point.** The stream's metadata declares `sampling:
  "post_invocation"`, but on frames where the driver's work overruns the Z80's
  available execution the sampled RAM is mid-invocation. Modelling it engine
  side would need a per-row value naming the track the update was cut at,
  which is a value, not a scheduling outcome, and so is outside the
  hardware-timing trace contract. The fix belongs in the observer: sample
  driver RAM when the driver returns from `zVInt`, or record the halt so a
  truncated tick can be recognised as such. Until then the S3K oracle cannot
  advance past its first music load.

- **Regression gates:** not re-run. This entry changes no engine, tool or
  fixture code, so the gates recorded in the entry below still stand.


## 2026-09-03 — S3K DAC frequency pinned; frontier reaches the FM track-parse phase

- **Context:** `.worktrees/audio-s3k-observer`, branch
  `feature/ai-s3k-request-observer` on `ef8d4bcdb`. No fixture, capture or
  comparator changed; one normalizer field was pinned.
- **Command:** the same invocation as the entry below.
- **Result before:** `TRACK_STATE_MISMATCH` at tick 138, role `MUS_DAC`, field
  `frequency`, reference 134, engine `null`.
- **Result after:** `TRACK_STATE_MISMATCH` at tick 138, role `MUS_FM1`, field
  `resting`, reference `false`, engine `true`.
- **Notes:** the engine was not missing a DAC track. `S3kAudioStateNormalizer`
  reported `null` for a DAC track's frequency with the comment that the mapping
  was not pinned. It is pinned: `SavedDAC` and `FreqLow` are the same `zTrack`
  byte at offset `0Dh` (`Sound/Z80 Sound Driver.asm:45-56`), and
  `zUpdateDACTrack_cont` stores the raw sample byte there including bit 7,
  before the rest check, reusing it verbatim when a duration follows without a
  note (`D:2880-2892`). The engine already keeps that byte as the track note,
  so reporting it directly makes the two agree. The reference's 134 is `$86`,
  a DAC note with bit 7 set. `FreqHigh` at `0Eh` is unused by a DAC track.

  The next divergence is a genuine phase difference, characterised but not
  fixed. At the frame the title music loads, movie frame 252, the reference has
  its DAC track parsed (`DurationTimeout` `06`, `SavedDAC` `86`) while `MUS_FM1`
  is initialised and unparsed (`PlaybackControl` `80`, all note state zero). The
  FM track first parses on the following frame, 253, where it becomes
  `PlaybackControl` `90` with `DurationTimeout` `6c`. The engine dispatches the
  request and runs a full update in the same tick, so it parses every track on
  the load frame and reports `MUS_FM1` as resting one frame early. Fixing it
  means modelling where `zPlayMusic` hands off to the first `zUpdateMusic`, and
  why the DAC track is ahead of the FM tracks by one frame.
- **Regression gates:** S1 GHZ music oracle **`MATCH (14690 ticks)`** and S1
  sound-test SFX oracle **`MATCH (1967 ticks)`**, both exit 0. The S2 driver
  oracle is unchanged at `DIVERGENCE at tick 210 [303 of 698 ticks divergent]`,
  the same result as before this change. `TestS3kAudioParityComparator`,
  `TestS3kAudioOracleFixtureContract`, `TestS3kRequestObservationSidecar` and
  `TestS1AudioStateNormalizer` report 37 passing with one skip, the skip being
  the ROM-gated measurement when no sidecar path is supplied.

## 2026-09-03 — S3K oracle advances off the producer-input limitation to tick 138

- **Context:** `.worktrees/audio-s3k-observer`, branch
  `feature/ai-s3k-request-observer` on `a92b12513`, with `tools/tracechaser` on
  `bugfix/ai-s3k-request-observer` at `78b8c1e`. No committed fixture,
  comparator or engine owner changed.
- **Fixture:** the committed bounded oracle
  `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`,
  unchanged, plus a new external request sidecar supplying driver inputs only.
- **Command:**

  ```
  S3kAudioParityTool compare \
    --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz \
    --requests <external>/s3k-request-observations.json \
    --rom <absolute locked-on S3K ROM>
  ```

- **Result before:** `REFERENCE_LIMITATION`, tick 128, field `producer_input`.
- **Result after:** **`MISMATCH`, `TRACK_STATE_MISMATCH` at tick 138, role
  `MUS_DAC`, field `frequency`, reference 134, engine `null`.**
- **Notes:** the v1 stream samples the mailbox before each invocation, so a
  request written and consumed inside one frame is invisible to it. Two serial
  power-on captures of `[0,5400)` observed 14 requests at the `Play_Music`
  bus-release instruction while the Z80 was stopped. Both captures are
  byte-identical, SHA-256
  `2063b558c9b81ba8ccdf487ddb95d9be1bfd7979997be831d0f73bd4164639d3`, and the
  extractor reduces them to a 14-entry sidecar only when they agree.

  Thirteen of the 14 appear in the committed fixture one frame later, which is
  exactly where a pre-invocation sample would see them: `e1` at row 13 against
  fixture frame 14, `ff` at 62 against 63, `25` at 251 against 252, and so on.
  The fourteenth, `fe` at row 242, has no fixture counterpart at all. That is
  `cmd_StopSEGA`, written and consumed inside one frame, and it is the input
  the limitation was reporting as missing. The agreement on the other thirteen
  is independent corroboration that the observer reads the right byte.

  Supplying it is an input, not a compared value. The oracle already takes its
  mailbox from the reference, the way the S1 tool plays the GHZ song; the
  sidecar only supplies a byte the old capture was blind to. The default reader
  path is untouched and still reports the same limitation at tick 128, pinned
  by a test.

  The new frontier at tick 138 is a real engine gap: the reference has a music
  DAC track with a frequency, and the engine has no such track.
- **Regression gates:** S1 GHZ music oracle **`MATCH (14690 ticks)`** and S1
  sound-test SFX oracle **`MATCH (1967 ticks)`**, both exit 0. The S2 driver
  oracle against its committed raw-v1 fixture reports `DIVERGENCE at tick 210
  [303 of 698 ticks divergent]`, which is its documented pre-request-awareness
  state; the `MATCH (698 ticks)` recorded on 2026-09-03 is against the
  unpublished request-aware candidate, and that invocation needs
  `--ignore-digest` against an external path, which was blocked here.
  `TestS3kRequestObservationSidecar` passes 11 of 11.

## 2026-09-03 — S3K pre-consumption mailbox observed; request layer still not compared

- **Context:** `.worktrees/audio-s3k-observer`, branch
  `feature/ai-s3k-request-observer`, with `tools/tracechaser` on
  `bugfix/ai-s3k-request-observer`. No fixture, comparator, profile or engine
  owner changed, and **no frontier moved**.
- **Fixture:** none. This is a disposable live smoke over rows `[0,400)` of
  `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`
  (SHA-256 `82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf`,
  466,334 rows) against the locked-on S3K ROM, written to an external scratch
  path. It is not a parity comparison.
- **Core:** a freshly built observer at ABI 5, decompressed SHA-256
  `c47e8e1aef25b39d4a947d8d57f77b2680cfb013103315945a48dabc2f4a54b0`, build id
  `6feee0d1b2ca882b`, installed to a scratch BizHawk home outside the
  repository. Seven native selftests pass, including the new
  `snapshot-at-pc-harness`.
- **Result:** exit 0, 400 rows observed and published, **four mailbox
  observations**. Process inventories were empty before and after.

  | Row | Active kind at the boundary | Mailbox byte |
  |---:|---|---|
  | 13 | 6, DriverInit | `e1` |
  | 62 | 0, root | `ff` |
  | 242 | 11, UpdateEverything | `fe` |
  | 251 | 0, root | `25` |

- **Notes:** row 242 is the source frame the service-128 limitation names, and
  its byte is `$FE`, `cmd_StopSEGA`. That value is now read from Z80 RAM
  `$1C0A` while the bus is still held, at the `startZ80` instruction before it
  executes. It is not inferred from the later stop burst, from SEGA-PCM exit,
  or from the fixture.

  Two corrections to the 2026-09-02 audit stand. The bus-release instruction is
  at `$1370`, not `$1374`, which falls inside its long operand and is never an
  instruction PC. And the boundary is not a child of the SEGA-PCM iteration:
  the observer's service stack is shared across processors, so the active kind
  is whichever Z80 service happens to be on top, measured here as kind 6, kind
  11 and root. That is why the observation is now taken by a
  parent-independent native action rather than a service push and pop.

  The request layer is still `UNAVAILABLE` for comparison. Authenticating the
  reference side alone cannot yield `MATCH`: the OpenGGF side must
  independently observe an equivalent request through its own producer before
  the layer can be compared. `REFERENCE_LIMITATION / producer_input` remains in
  force and the first divergence is unchanged at service 128.

  The native build attestation was simplified in the same round, on an explicit
  human ruling: the host-image trust roots, chained recipe digests, secure
  runtime and reproduction ritual are replaced by one build script whose
  provenance is an output rather than a gate. Pinned source commits, pinned
  clang packages and the patch remain pinned.
- **Regression gates:** the TraceChaser `S3k` filter reports 143 passing. Four
  failures across the `S2` and `S3k` filters were present on the pinned
  baseline; one of them, the observer-installation test, now fails for a new
  reason because it still pins the retired identity family and has not been
  moved to the simplified contract.

## 2026-09-03 — S3K request observer reaches the boundary; the reviewed topology does not hold

- **Context:** `.worktrees/audio-s3k-observer`, branch
  `feature/ai-s3k-request-observer`, on top of `feb9ea267`, with
  `tools/tracechaser` on `bugfix/ai-s3k-request-observer` at `7dd4cf3`.
  No fixture, candidate, comparator, profile or engine owner was changed, and
  no frontier moved.
- **Fixture:** none. This is a disposable, non-authoritative live smoke, not a
  parity comparison. The movie is
  `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`
  (SHA-256 `82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf`,
  466,334 rows) against the locked-on S3K ROM.
- **Command:** a disposable reflection driver invoking the internal
  `S3kPreconsumptionRequestCaptureRunner.CaptureRawSmokePrefix` seam over rows
  `[0,400)` under `timeout --signal=TERM --kill-after=30s 20m mono`, against
  the 2026-09-02 base observer install
  (`install-a`, core SHA-256 `fa43fbc7ab2b38e2139c8288d1fc1489ecad353613283d2892a2a26399798b3a`).
  Output went to an external scratch path; no fixture destination was written.
- **Result:** exit 0, 400 rows observed and published, **zero mailbox
  submissions**. Process inventories were empty before and after.
- **Notes:** two facts in
  `docs/architecture/audits/audio/2026-09-02-s3k-preconsumption-request-producer-audit.md`
  do not survive execution.

  First, the bus-release instruction is at `$1370`, not `$1374`. The shipped
  bytes are `33fc010000a11100` at `$1358`, `0839000000a11100` at `$1360`,
  `66f6` at `$1368`, `13c000a01c0a` at `$136A`, `33fc000000a11100` at `$1370`
  and `4e75` at `$1378`. `$1374` falls inside the release instruction's long
  operand, so it is never an instruction PC and a hook placed there is silently
  unreachable. The first smoke recorded four `$1358` visits and zero `$1374`
  visits, which is what exposed it. `$1370` lies strictly inside the approved
  `$1358..$1374` interval and carries the exact approved opcode.

  Second, the diagnostic `Play_Music` does not run under the SEGA-PCM
  iteration. With the end hook at `$1370` the four invocations in `[0,400)` are
  bracketed by matched pairs at rows 13, 62, 242 and 251, and their active
  kinds are 6 (`DriverInit`), 0 (root), **11 (`UpdateEverything`)** and 0
  (root). Row 242 is the source frame the service-128 limitation names, and its
  active kind is 11, not the reviewed kind 8. Because the service stack is
  shared across CPUs, the parent of an M68K `Play_Music` is whichever Z80
  service happens to be on top, so no fixed single-parent `PUSH_BEGIN` topology
  can express this boundary. The kind-13 child therefore never opens and no
  `$1C0A` snapshot is taken.

  This is the audit's own declared stop condition: the existing exact actions
  cannot express the topology without a false lifecycle. `REFERENCE_LIMITATION
  / producer_input` and `FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE`
  both remain in force, and the service-128 first divergence is unchanged.
- **Regression gates:** the TraceChaser `S3k` filter reports 143 passing with
  the two pre-existing failures also present on the pinned baseline `8700dd0`
  (`Bk2Reader reads the canonical S3K fixture movies` needs
  `TRACECHASER_TEST_FIXTURE_ROOT`; `GpgxZ80AudioCapabilityTests lock reviewed
  S2 and S3K service manifests` fails on both trees). The 11 new
  `S3kPreconsumptionRequestProfile` cases pass.

## 2026-09-03 — S1 gameplay oracle stops at tick 629: the fixture records no special-SFX dispatch

- **Worktree/branch:** `.worktrees/audio-s1-gameplay-frontier`,
  `bugfix/ai-s1-gameplay-frontier`, at `6c788b4fe`. Measurement only; no engine
  change accompanies this entry.
- **Result:** MISMATCH, `TRACK_STATE_MISMATCH`, tick 629, role FM4, field
  `overridden` — reference true, engine false.
- **Diagnosis — a reference limitation, not an engine defect.** Tick 583
  dispatches `sfx_Spring` (`0xCC`) onto FM4 and tick 593 dispatches a ring
  (`0xB5`, resolved to the left-speaker `0xCE`) onto the same channel. `SndCE`
  is three notes totalling `$24` ticks, so it ends exactly at tick 629, and
  both streams agree write-for-write up to that point.
  `cfStopTrack` (`docs/s1disasm/s1.sounddriver.asm:2489-2563`) then chooses
  where to hand FM4 back. Its FM4 case first tests
  `v_spcsfx_fm4_track.PlaybackControl` (`:2510-2515`): when a **special** SFX is
  playing it restores `v_special_voice_ptr` into the special track and never
  touches the music track, so the music FM4 override bit survives. Only the
  fall-through `.getpointer` path (`:2519-2528`) reaches the music track and
  clears bit 2.
  The reference keeps the music FM4 override set at ticks 629-631, so the ROM
  took the special-SFX branch. Ticks 630 and 631 confirm it: their FM4
  frequency and key-on writes land *after* that invocation's music PSG writes,
  which the music FM walk (`:214-221`) precedes, so they come from the
  special-SFX section (`:243-247`).
- **Why the engine cannot follow.** GHZ's special SFX reaches the driver
  through `Sound_PlaySpecial` (`:1105`), a separate entry point with its own
  track slots. The fixture's per-tick `dispatches` array records only
  `Sound_PlaySFX` calls, and `raw_state.tracks` carries the ten music slots
  only — neither the special-SFX admission nor its track state is captured. The
  replay host has no data from which to admit that sound, so the engine
  correctly releases FM4 to music where the ROM releases it to a special SFX
  the fixture never mentions. `voice_selector` is `0x40` on every tick and is
  not evidence either way: `UpdateMusic` stores it unconditionally before the
  special-SFX section (`:243`).
- **What would move it:** a re-capture whose probe also records
  `Sound_PlaySpecial` dispatches and the two special-SFX track slots, published
  through the fixture contract. That is capture work, not engine work.
- **Gates at this commit:** S1 sound-test music MATCH (14,690 ticks) and SFX
  MATCH (1,967 ticks); the audio packages, `TestSmpsFadeAudioThroughput` and
  the S2 driver oracle run 2,470 tests with 0 failures.

## 2026-09-03 — S1 gameplay oracle: tick 316 → 629 (SFX walks fixed RAM slots)

- **Worktree/branch:** `.worktrees/audio-s1-gameplay-frontier`,
  `bugfix/ai-s1-gameplay-frontier`, on top of `2ba02dbad`.
- **Fixture and command:** unchanged from the entry below.
- **Result before:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 316, event 3 —
  reference YM2612 port 1 register `0xB0` value 4, engine PSG `0x87`.
- **Result after:** MISMATCH, `TRACK_STATE_MISMATCH`, tick 629, role FM4,
  field `overridden` — reference true, engine false.
- **What moved, part one — the walk.** S1 `UpdateMusic` has no per-sound SFX
  service. It walks one fixed array of SFX track slots, SFX FM3..FM5
  (`docs/s1disasm/s1.sounddriver.asm:222-231`) then SFX PSG1..PSG3
  (`:233-241`), with no notion of which sound owns a slot. So two live sounds
  interleave by channel, not by admission order: at tick 316 a ring on FM4 is
  serviced before the jump still holding PSG1, though the jump started
  fourteen invocations earlier. The engine serviced each SFX sequencer whole,
  in admission order. `SmpsDriver` now walks the SFX slots itself when every
  live SFX program declares `SfxTrackWalkMode.CHANNEL_RAM_ORDER` (S1 only),
  driving each sequencer's tracks through a new begin/tick/finish pass.
- **What moved, part two — the release.** With the walk in place the frontier
  landed at tick 562, where a finishing FM5 SFX restored the music voice after
  the SFX PSG1 slot instead of before it. `cfStopTrack` (`:2489-2563`) hands
  the channel back from inside the finishing track's own slot service, whether
  or not the sound has other tracks still playing; the engine deferred the
  release of a wholly finished sound to end-of-frame completion cleanup. The
  slot walk now reconciles the finishing slot inline, through a new
  `SmpsSequencerHost.reconcileFinishedSfxSlot`. Non-coordinated games keep the
  previous deferral. No constant was introduced.
- **New frontier:** tick 629, the reference still has the music FM4 track
  overridden where the engine has released it — an override-lifetime question,
  not an ordering one.
- **Gates held:** S1 sound-test music MATCH (14,690 ticks) and SFX MATCH
  (1,967 ticks); the audio packages plus `TestSmpsFadeAudioThroughput` and the
  S2 driver oracle run 2,470 tests with 0 failures.

## 2026-09-03 — S1 gameplay oracle: tick 302 → 316 (SFX admission owns its channels)

- **Worktree/branch:** `.worktrees/audio-s1-gameplay-frontier`,
  `bugfix/ai-s1-gameplay-frontier` from `develop` at `2229b5b7c`.
- **Fixture:** `src/test/resources/audio/parity/s1/s1-gameplay-ghz1-reference.v1.jsonl.gz`
  (2,343 ticks, 70 dispatches).
- **Command:**

  ```
  java -cp target/classes:<deps> com.openggf.tools.audio.parity.S1AudioParityTool \
      capture --repo <worktree> --run-root <external run root> \
      --reference <run root>/reference.jsonl --rom <abs S1 REV01 ROM> \
      --output <run root>/openggf.jsonl --capture gameplay
  java -cp target/classes:<deps> com.openggf.tools.audio.parity.S1AudioParityTool \
      compare --repo <worktree> --run-root <external run root> \
      --reference <run root>/reference.jsonl --openggf <run root>/openggf.jsonl \
      --human-report <run root>/report.txt --json-report <run root>/report.json
  ```

- **Result before:** MISMATCH, `EVENT_EXTRA`, tick 302, event 0 — engine PSG
  write `0x92` (PSG1 volume 2) with no reference counterpart.
- **Result after:** MISMATCH, `EVENT_VALUE_DIFFERENT`, tick 316, event 3 —
  reference YM2612 port 1 register `0xB0` value 4, engine PSG `0x87`.
- **What moved:** tick 302 dispatches `sfx_Jump` (`0xA0`), which loads a PSG1
  track. `Sound_PlaySFX`'s header loader sets `PlaybackControl` bit 2 on the
  displaced *music* track while loading each SFX track — `.sfx_loadloop` for FM
  (`docs/s1disasm/s1.sounddriver.asm:1029`) and `.sfxinitpsg` for PSG
  (`:1037`) — and it is reached from `PlaySoundID` at the top of `UpdateMusic`
  (`:202`), before that same invocation's DAC/FM/PSG music walk (`:208-227`).
  So the music PSG1 track is already overridden on the admitting invocation and
  emits nothing, even though the SFX track has written no register yet. The
  engine had S1 on `SfxChannelOwnershipMode.FIRST_WRITE`, so the music track
  still emitted its volume byte. S1 now uses `ADMISSION`, the mode S2 already
  derived from the identical `zPlaySound` shape. No constant was introduced.
- **New frontier:** tick 316 dispatches `sfx_Ring` (`0xB5`) onto FM4 while the
  jump SFX still holds PSG1. `UpdateMusic` walks the *fixed SFX RAM slots* —
  SFX FM3..FM5 (`:222-231`) then SFX PSG1..PSG3 (`:233-241`) — so the ring's
  FM4 writes precede the jump's PSG1 writes. The engine services SFX
  sequencer-by-sequencer in admission order, so the jump's PSG1 writes come
  first. Same writes, wrong order.
- **Gates held:** `run_s1_audio_parity.sh --mode music` MATCH (14,690 ticks);
  `--mode sfx` MATCH (1,967 ticks); S2
  `TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare`
  passes.

## 2026-09-03 — S1 gains a second audio oracle, sourced from real gameplay

- **Worktree/branch:** `.worktrees/audio-s1-complete-oracle`,
  `feature/ai-s1-complete-run-oracle` from `develop` at `feb9ea267`.
- **Fixture (new):** `src/test/resources/audio/parity/s1/s1-gameplay-ghz1-reference.v1.jsonl.gz`
  — the committed complete-run movie `sonic1-complete-withemeralds.bk2`
  (`src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/`, SHA-256
  `f2e817936d…`, 225,101 input rows), captured from power-on through frame
  3,000 (2,343 driver invocations, epoch opens at frame 656 on the real GHZ1
  BGM dispatch — 341 dormant invocations precede it, title/SEGA/menu, unlike
  the two sound-test movies' shared 514) by a new probe,
  `tools/audio/probes/s1_gameplay_driver_parity_probe.lua` (a
  movie/window-specific variant of the committed `s1_audio_sfx_parity_probe.lua`,
  same shape: driver-RAM-derived track state plus ordered YM/PSG bus writes,
  one record per `UpdateMusic` invocation, plus a `dispatches` array of any
  `Sound_PlaySFX` calls the invocation made). 70 real SFX dispatches
  (jump/ring/spring/etc., not a scripted list) are captured in this window.
  Two BizHawk captures are byte-identical
  (SHA-256 `c7d58e8721f240ef…`, both runs).
- **Command:** `tools/audio/run_s1_audio_parity.sh --mode gameplay --rom
  <absolute SHA-1-verified S1 REV01 ROM> --bizhawk-home <BizHawk 2.11 Linux
  x64> --output-root <external run root>`.
- **Result:** **MISMATCH**, first divergence **tick 302, `EVENT_EXTRA`, event
  0** — the engine emits an extra PSG write (`0x92`) that the reference does
  not have. Pinned by `TestS1GameplayAudioDriverOracle.currentFrontierIsTheFirstDivergence`
  (ROM-gated, `-Dsonic1.rom.path=`). Not investigated in this lane (measurement
  only, per the brief); a future lane should chase the ROM routine that owns
  this write before changing engine behaviour.
- **Broken on purpose before trusting the comparison** (project rule): flipping
  one YM2612 register-40 write value in tick 0 of a temp copy of the reference
  is reported at tick 0 by
  `TestS1GameplayAudioDriverOracle.corruptingTheReferenceIsDetectedAtTheCorruptedTick`.
- **Existing S1 sound-test gates unchanged:** `run_s1_audio_parity.sh --mode
  music` still reports **MATCH (14,690 ticks)**; `--mode sfx` still reports
  **MATCH (1,967 ticks)** — both re-run against the unchanged committed
  fixtures in this worktree to confirm this lane's shared-code changes
  (`AudioParitySchema`/`AudioParityMetadata`/`AudioParityJsonl`/
  `AudioParityComparator`/`S1AudioParityTool` gained a third `gameplay`
  capture kind alongside `music`/`sfx`) did not regress them.
- **Notes:** the capture window is bounded to frame 3,000, not the originally
  planned ~5,000 (see `docs/architecture/plans/audio/2026-08-09-s1-ghz1-
  gameplay-audio-timeline-plan.md`'s [860,4975) window, which this frontier
  deliberately overlaps): BizHawk's headless client consistently
  self-terminates (clean process exit 0, no Lua error surfaced to its own
  console output even with `OGGF_TRACE_QUIET=0`, no native crash signal) at
  frame 3,219 of this specific movie when driven through
  `tools/tracechaser/bizhawk/run_bizhawk_lua.sh`'s established launch shape —
  a boundary the two short sound-test movies (≤2,791 rows) never previously
  reached through this launcher. The cause was not isolated: diagnostic
  `pcall` wrapping around every `invocationLifecycle` entry/close transition
  in the probe never fired, and the movie's own input transcript has nothing
  unusual at that row (`|..|...R....|........|`, plain held-right input, no
  reset/power marker). 3,000 stays safely inside the frames that reliably
  complete; a future lane investigating the launcher itself (not this
  worktree's scope) could recover the fuller window.
  Also fixed in this lane, filed as a separate `fix(tools):` commit per
  instruction: `run_s1_ghz1_gameplay_audio_timeline.sh` (the unrelated,
  never-executed S1 GHZ1 gameplay-audio *timeline* framework —
  `S1GameplayAudioTimeline*`, a different, semantic-decision capture shape
  from this driver-register oracle) was missing `OGGF_INPUT_REPOSITORY_ROOT`
  in its `capture_reference` call, so `run_bizhawk_lua.sh` aborted before
  BizHawk ever launched. That one-line fix is necessary but insufficient: the
  script's hardcoded in-repo `OUTPUT_ROOT` is separately rejected by
  `output_policy.py`'s external-output-root requirement, so that tool remains
  unexercised end-to-end; see `tools/audio/README.md`.

## 2026-09-03 — the S2 request-window candidate is published as a committed fixture

- **Context:** `.worktrees/audio-s2-fixture-publish`, branch
  `bugfix/ai-s2-request-fixture-publish`, on top of `feb9ea267`. No comparator,
  alignment, or engine behaviour changed; the payload is the captured bytes,
  gzipped and unmodified.
- **Fixture:** new —
  `src/test/resources/audio/parity/s2/s2-request-window-w10150-10900.raw-v2.jsonl.gz`
  (gz SHA-256 `be8ab87f45499fcf5db0aee5613d699f56d79d5d6a8ffacbbfbe21592ab95c15`,
  expanded SHA-256 `a7d56fe71674d9f4a9307e6fb6078f7832409bb310916e808faf28b1e9426c2c`,
  750 rows over `[10150,10900)`, 25 request transfers), with the provenance
  sidecar `s2-request-window-w10150-10900.metadata.json`. Driven by
  `sonic-2-sonic-tails-complete-emeralds.bk2` against the S2 World REV01 ROM
  (SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`). Two independent captures
  (`coincident-extract-g-final` and `-h-final`) hash-match, which is the Task 8A
  duplicate-capture gate; human approval to publish was granted 2026-09-03.
- **Command:**

  ```
  LUA_BIN=lua5.4 mvn -Dmse=off \
    '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
    '-Ds2.request.bk2.path=<absolute complete-emeralds BK2>' \
    '-Dtest=com.openggf.tools.audio.parity.s2.TestS2RequestAwareOracleRawStream' \
    test -B
  ```

  `-Ds2.request.candidate.path=<absolute candidate>` still overrides the
  committed payload; the run was made both ways.
- **Result:** unchanged in both directions —
  `S2 unbound request candidate: MATCH: 25 production transfers agree` and
  `S2 driver oracle: MATCH (698 ticks)`. 24 tests, 0 failures, 0 skips, exit 0
  with the property and without it.
- **Notes:** publication installs a comparison reference only. `production_bound`
  stays false, the reader stays package-private and CLI-unreachable, the
  comparator stays a disposable test-only owner, and request equality remains a
  reference limitation. `TestS2RequestWindowFixture` pins both digests and the
  parsed window shape, so a drifted byte fails before any comparison can quietly
  change meaning. Widening and second-recording work is planned in
  `docs/architecture/plans/audio/2026-09-03-multi-recording-oracle-roadmap.md`.


## 2026-09-03 — S2 driver oracle reaches full MATCH over the 698-tick window

- **Context:** `.worktrees/s2-tick0-land`, branch `bugfix/ai-s2-level-playbgm-land`,
  on top of `810dbc039`. No fixture, candidate, comparator or alignment was
  changed.
- **Fixture:** the authenticated S2 driver oracle payload behind
  `TestS2AudioOracleFixture.fixturePath()`, driven by
  `sonic-2-sonic-tails-complete-emeralds.bk2` against the S2 World REV01 ROM
  (SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`).
- **Command:** the same invocation as the entry below.
- **Result before:** `S2 driver oracle: DIVERGENCE at tick 557 (movie row 10759),
  field writes.count: expected=4 actual=5 [20 of 698 ticks divergent]`.
- **Result after:** **`S2 driver oracle: MATCH (698 ticks)`**.
  `MATCH: 25 production transfers agree` is unchanged.
- **Notes:** the remaining extras were PSG attenuation bytes `0xf2`, `0xf4`,
  `0xf6`, `0xf8`, `0xfa`, `0xff` — a music PSG3 volume envelope ramping to
  silence on the noise channel. SFX `0xC1` Explosion is requested at movie row
  10759 and declares `cFM5` plus `cPSG3`, and the reference's PSG3
  `playbackControl` byte moves `0x80` to `0x84` on that exact row, so the ROM
  holds the track SFX-overridden for the whole span. The engine installs that
  override on the correct row; logging the transitions shows PSG channel 2
  flipping to overridden at row 10759. The defect was that the PSG branch of
  `SmpsSequencer.refreshVolume` consulted only the rest bit and never the
  override bit, so the envelope kept writing behind the override.

  All three drivers agree here, so this is a universal correction rather than a
  per-game one. S2 `zPSGUpdateVol` does `and 6` over the rest and override bits
  and returns (`s2.sounddriver.asm:1305-1308`); S1 `SetPSGVolume` tests the two
  bits separately (`s1.sounddriver.asm:1965-1969`); S3K reaches the same outcome
  one level up, where `zUpdatePSGTrack` returns on bit 2 before both the
  frequency pair and the volume tail (skdisasm `Sound/Z80 Sound
  Driver.asm:4079-4081`). In each case the flutter or envelope index still
  advances behind the suppressed write, which is why the gate belongs at the
  write and not at the envelope step.
- **Regression gates:** S1 GHZ music oracle `MATCH (14690 ticks)` and S1
  sound-test SFX oracle `MATCH (1967 ticks)`, both exit 0. The S1, S2, S3K and
  shared audio packages report 1,943 tests with the same five pre-existing
  failures as the entry below and no new ones.
  `TestSmpsFadeAudioThroughput` passes.

## 2026-09-03 — S2 ROM SFX-release semantics advance the driver frontier to tick 557

- **Context:** `.worktrees/s2-tick0-land`, branch `bugfix/ai-s2-level-playbgm-land`,
  base `3c54967f7`. No fixture, candidate, comparator or alignment was changed.
- **Fixture:** the authenticated S2 driver oracle payload behind
  `TestS2AudioOracleFixture.fixturePath()`, driven by
  `sonic-2-sonic-tails-complete-emeralds.bk2` against the S2 World REV01 ROM
  (SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`).
- **Command:**

  ```
  LUA_BIN=lua5.4 mvn -Dmse=off \
    '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
    '-Ds2.request.bk2.path=<absolute complete-emeralds BK2>' \
    '-Ds2.request.candidate.path=<absolute s2-request-window.oracle-raw-v2.jsonl>' \
    '-Dtest=com.openggf.tools.audio.parity.s2.TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare' \
    test -B
  ```

- **Result before:** `S2 driver oracle: DIVERGENCE at tick 266 (movie row 10468),
  field writes.count: expected=2 actual=4 [107 of 698 ticks divergent]`.
- **Result after:** `S2 driver oracle: DIVERGENCE at tick 557 (movie row 10759),
  field writes.count: expected=4 actual=5 [20 of 698 ticks divergent]`.
  `MATCH: 25 production transfers agree` is unchanged.
- **Notes:** every one of the 107 divergent ticks was the engine emitting extra
  writes and none was a missing write. At tick 266 the extras were an
  `A4`/`A0` frequency pair on FM4 continuing a modulation ramp the reference
  never emits. Decoding the reference's own `playbackControl` byte for the FM4
  music slot showed `0x9c` through movie row 10466 and `0x9a` from row 10467:
  the SFX-override bit clears and the **rest** bit sets in the same transition.
  That is `cfStopTrack`'s FM SFX tail, `s2.sounddriver.asm:3548-3553`, which does
  `res 2` / `set 1` and restores only the voice through `zSetVoiceMusic`; it
  sends no key-off, no pan rewrite and no frequency resend.
  `zStopPSGSFXTrack` (`:3581-3589`) is the same shape. Leaving the released
  music track at rest is what keeps `zDoModulation` returning early
  (`:989-991`), so the channel stays silent until its next note.

  Two changes were needed. `Sonic2SmpsSequencerConfig` now declares
  `ROM_VOICE_RESTORE` / `ROM_REST_RESTORE`, the modes S1 already declared for
  the identical routine. That alone changed nothing, because
  `SmpsAssetCatalog.copyBuilder` rebuilds every sequencer config for the
  presentation path and silently dropped five configured settings:
  `fmSfxReleaseMode`, `psgSfxReleaseMode`, `sfxTrackWalkMode`,
  `fmVolumeVoiceBankMode` and `palUpdateMode`. Every sequencer built through
  the catalog therefore reverted to the legacy full-restore behaviour,
  including S1's. Completing the copy is what delivered the fix.

  One test asserted the behaviour the ROM contradicts.
  `TestS2SfxAdmissionChannelMask.acceptedRingLeftOwnsFm4BeforeMusicFirstService`
  ended by requiring that restored music resume FM4 frequency output after the
  SFX releases the channel. Its final assertion now states the ROM's outcome
  instead, that the released track is at rest and no `A4`/`A0` pair is resent,
  with the routine cited. That class was green before this change and red with
  it, and is the only test the change moved.
- **Regression gates:** S1 GHZ music oracle `MATCH (14690 ticks)` and S1
  sound-test SFX oracle `MATCH (1967 ticks)`, both exit 0, captured and
  compared with `S1AudioParityTool` against the committed references. The audio
  test packages report 1,889 tests with five failures, all reproduced red at
  the base commit with this change reverted, or structurally unreachable from
  it: `TestSonic2RequestProductionWiring` (3) and `TestAudioPresentationBoundary`
  (1) were confirmed by a control run, and
  `TestAudioPresentationArchitectureGuard` (1) reads a fixed list of seven
  production files that includes neither file changed here.

## 2026-09-03 — S2 source-owned level-entry timing advances driver frontier to tick 266

- **Context:** landed on `bugfix/ai-s2-level-playbgm-land` from
  `.worktrees/s2-tick0-land`, base `7f5067b23`; no fixture or comparator
  semantics changed. The tranche was reviewed before landing and four defects
  were fixed on this branch: the level-music schedule was resolving the timing
  model against the zone registry's progression index instead of the ROM
  zone/act pair, which was correct only for EHZ; the title-card lifecycle
  serviced a second hardware `VINT_SERVICE` boundary per frame; the rewind
  registry adapter-count assertions were not updated for the new scheduler;
  and `LevelManager` grew against its size ratchet.
- **Command:** `LUA_BIN=lua5.4 mvn -Dmse=off
  '-Dsonic2.rom.path=$REPO/s2.gen'
  '-Ds2.request.bk2.path=$REPO/docs/BizHawk-2.11-linux-x64/Movies/sonic-2-sonic-tails-complete-emeralds.bk2'
  '-Ds2.request.candidate.path=$CAPTURES/s2-native-authority-live-evidence-20260902/coincident-extract-g-final/s2-request-window.oracle-raw-v2.jsonl'
  '-Dtest=com.openggf.tools.audio.parity.s2.TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare'
  test -B`.
- **Result:** request transfer **MATCH (25/25)**. The driver frontier advances
  from tick 0 to **tick 266 (movie row 10468)**, `writes.count`, expected 2,
  actual 4; 107 of 698 ticks diverge.
- **Evidence:** `Level_PlayBgm` publishes EHZ once at row 10195. The ROM-data
  Saxman cost produces exactly six `LOAD_PENDING` rows (10195-10200), then a
  distinct `SERVICE_IN_FLIGHT` boundary at 10201 with no committed snapshot;
  update 0 commits at 10202 (`tempoAccumulator=0x3c`) and ordinary update 1 at
  10203 (`0xda`). Thus the previous tick-0 state and write fields all match,
  while the six-row readiness movement remains exactly the source-derived
  `0x58` to `0xa4` shift rather than absorbing the completion boundary.
- **Cross-checks at landing:** S1 GHZ music oracle **MATCH (14690 ticks)** and
  S1 sound-test SFX oracle **MATCH (1967 ticks, 8 dispatches)**, both exit 0.
  No `*TraceReplay` class changed result against base `7f5067b23`, and
  `TestS2CompleteEmeraldRunChain` is unchanged. The S2 driver comparison is
  `MEASUREMENT_ONLY`; the asserting companion is
  `TestS2RequestAwareOracleRawStream#levelPlayBgmPublishesEmeraldHillOnceAtTheNativeLoadBoundary`.

## 2026-09-03 — S2 Level_PlayBgm tranche frozen after two Critical reviews

- **Critical 1 — omitted and trace-coupled service:** `f7373c1cb` advanced the pending request from `ObjectManager`'s object-visible VBlank clock.
  Normal pre-player title-card VBlanks did not advance that clock, while trace-bootstrap-selected object passes could advance it.
  The tranche froze rather than treating the row-10195 comparator result as proof of a production-owned dispatch.
- **Critical 2 — duplicate service:** fix wave `248b03ed6` added a title-card service edge alongside `LevelFrameStep`'s existing VINT edge.
  Playable title-card leave rows consequently serviced the scheduler twice; the test covered only the pre-player predicate-false arm.
  The re-review failed, so `bugfix/ai-s2-c0a-replan3` is preserved as non-merge evidence and the clean replan restarts at `b8b23a8fd`.

## 2026-09-03 — S2 request-transfer window MATCH; driver tick frontier unchanged

- **Worktree/branch:** `.worktrees/s2-c0a-replan3`,
  `bugfix/ai-s2-c0a-replan3`; measured at `7b1442846` plus the reviewed
  spike-cause diff now committed as `89eab0649`.
- **Fixture:** two independently extracted, comparison-only raw-v2 candidates
  for source rows `[10150,10900)`, each SHA-256
  `a7d56fe71674d9f4a9307e6fb6078f7832409bb310916e808faf28b1e9426c2c`;
  both remain explicitly unbound (`production_bound:false`).
- **Command:** `mvn -Dmse=off -Dsonic2.rom.path=<absolute REV01 ROM>
  -Ds2.request.candidate.path=<candidate-g-or-h raw-v2 JSONL>
  -Ds2.request.bk2.path=<pinned complete-emeralds BK2>
  -Dtest=com.openggf.tools.audio.parity.s2.TestS2RequestAwareOracleRawStream#realCandidateComparesAgainstIndependentProductionBk2Run
  test -B`.
- **Result:** candidate g and candidate h independently reported
  **`MATCH: 25 production transfers agree`**, exit 0 (one test, no
  failures/errors/skips). Before the two source-owned fixes, the first
  divergences were transfer 3 (ring B5 in SFX0 rather than ROM SFX1) and then
  transfer 20 (CPU Tails spike damage A3 rather than ROM A6). The reviewed
  observer-only same-BK2 comparison at `89bdb6eb9` then reported
  **DIVERGENCE at tick 0 (movie row 10202)**, `global.tempoTimeout`, expected
  `0x3c`, actual `0x58`; 698 of 698 ticks diverged.
- **Notes:** the raw-v2 candidates remain comparison-only and supply no driver
  input; requests arise from the BK2-driven engine. This does not authenticate
  the candidates or bind replay authority. It supersedes the old tick-210
  music-only-host result: that host supplied no SFX requests, whereas the
  same-BK2 observer measures the production request path.

  At `3045e716d`, the S2 compressed-load readiness model cost the actual EHZ
  bank-2 Saxman path at 363,255 Z80 T-states (363,283 on the enabled PAL
  path). It predicted exactly six fully masked presentation rows and moved the
  same-BK2 tick-0 value from `0x58` to **`0xa4`**, no further. The remaining
  `0xa4` versus `0x3c` mismatch is the separately measured early Music0
  request: OpenGGF submits EHZ at row 10184, while native initialization is
  bounded to the row 10194→10195 boundary.

## 2026-09-02 — S3K E4 seven-slot stop/restore source correction under review; no oracle move

- **Worktree/branch:** `.worktrees/sound-driver-roadmap-completion`,
  `feature/ai-sound-driver-roadmap-completion`, candidate over accepted retained
  S3K E4-state commit `8e0babd09`.
- **Fixture:** none changed. The authenticated S3K AIZ1 reference has no E4
  request, so it cannot establish an E4 comparison result.
- **Command:** `mvn -Dmse=off
  -Dtest=TestSmpsDriverSession,TestS3kE4StopSfxPlan,TestSmpsStatefulCommandPolicy,TestSmpsPhysicalPolicy
  test -B`.
- **Result:** the earlier candidate omitted `cfStopTrack`'s E4-local
  `zGetSFXChannelPointers` PSG sequence. The corrected plan now covers, in native
  order, the raw YM `$28` hazard, `1Fh + current SFX VoiceControl`, the stopped-SFX
  bit-0 conditional `$FF`, the FixBugs=0 unconditional `$FF`, and then an eligible
  signed music-noise re-latch; it also retains AMS/FMS for the music `$B4` restore.
  Exact direct and composite rollback tests cover physical PSG failures and the
  post-logical-mutation/pre-publication boundary.
- **Notes:** this entry records a source correction, not a product-frontier closure.
  No comparator was run and no `MATCH` is claimed. Service 128 remains the same
  authenticated `REFERENCE_LIMITATION` (`producer_input`); standalone E3 and
  PSG-SFX-admission stale-`ix`/`$FF` behaviour are separate frontiers.

## 2026-09-02 — S3K E3 PSG-silence product gap closes without moving service 128

- **Worktree/branch:** `.worktrees/sound-driver-roadmap-completion`,
  `feature/ai-sound-driver-roadmap-completion` from `8952620bf` (Task 9A
  implementation; commit containing this entry).
- **Fixture:** no capture or fixture changed. The existing authenticated
  `s3k-aiz1-intro-reference-v1.jsonl.gz` remains unchanged and does not supply
  an E3 request.
- **Command:** focused profile/resolver/queue/session/oracle-host tests:
  `LUA_BIN=lua5.4 mvn -Dmse=off
  -Ds3k.rom.path=<absolute-S3K-ROM>
  '-Dtest=com.openggf.game.sonic3k.audio.TestSonic3kSpeedShoesCommandSemantics,com.openggf.audio.presentation.TestAudioPresentationCommandResolver,com.openggf.audio.presentation.TestAudioPresentationCommandQueue,com.openggf.audio.session.TestSmpsDriverSession#psgSilenceWritesExactRomProgramWithoutMutatingSessionState+psgSilenceObserverFailureCannotPartiallyApplyOrReplay,com.openggf.tools.audio.parity.s3k.TestS3kAudioOracleFixtureContract'
  test -B`.
- **Result:** **56 tests pass** with 0 failures, 0 errors, and 0 skips. The
  production typed route and engine oracle host both execute the immutable
  `9F BF DF FF` program sourced from `zPlaySoundByIndex` /
  `zPSGSilenceAll`. The session test retains the physical identity and all
  logical music/SFX/override/tempo/pending-service state, observes no YM
  writes, and proves there is no next-service duplicate. Observer failure is
  quarantined after commit and cannot partially apply or replay E3.
- **Notes:** this closes the source-backed E3 **product gap only**. It does not
  move or reinterpret the authenticated comparison. Service **128** remains
  `REFERENCE_LIMITATION`, `field=producer_input`, because the request consumed
  while the reference producer suspended interrupt services is unavailable.

## 2026-09-01 — Override-resume producer stops at `REFERENCE_LIMITATION`

- **Worktree/branch:** `.worktrees/sound-driver-roadmap-completion`,
  `feature/ai-sound-driver-roadmap-completion`; TraceChaser producer commits
  `912fef0a`, `e3fdf73`, and mechanics hardening through `a61450ee`.
- **Fixture:** none. The dedicated
  `src/test/resources/audio/parity/override-resume-first-divergence-v1/`
  commit bundle remains absent; its proposed nested S1/S2 members have no
  independent authority, and no capture or fixture gained authority.
- **Command:** focused TraceChaser S1, S2, extractor/publisher, CLI, and Lua
  contract tests plus `verify-deterministic-build.sh`; the locked observer
  recipe verifier and the reviewed-capability guard were then run as hard
  authority checks. Exact commands and hashes are recorded in
  `docs/architecture/validation/audio/2026-09-01-override-resume-reference-limitation.md`.
- **Result:** `REFERENCE_LIMITATION`, code
  `FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE`. The current host's
  `/usr/bin/ar` differs from the locked recipe, the current collector source
  differs from the pinned capability field that Task 8 is forbidden to
  refresh, and no current-session two-build observer inputs are configured.
  A static older install is not fresh capture authority.
- **Notes:** `a61450ee` hardens fd-relative staging and identity-uncertain
  quarantine, but it cannot prove nested-path containment when a retained
  target dirfd is renamed after revalidation, and four sequential `linkat`
  calls cannot provide one visibility point. The canonical plan now requires
  private construction of the complete dedicated bundle and one
  `renameat2(RENAME_NOREPLACE)` commit under an explicit cooperative-lock and
  namespace-stability precondition. That publisher redesign is required before
  publication. The mandatory provenance inventory also remains incomplete, so
  Task 8 made no Java production change, froze no literal expectation,
  performed no live capture, and left both S1 hard gates untouched.
  The subsequent atomic-bundle implementation replaces those four links with
  private exact-inventory construction and one directory
  `renameat2(RENAME_NOREPLACE)` under the documented cooperative lock and
  namespace-stability precondition. Bundle-aware Java consumers now reject an
  absent or invalid commit object without consulting legacy leaves. This moves
  no audio frontier: the fresh authenticated native-GPGX and complete
  provenance gates remain unavailable, no fixture was published, and the same
  `REFERENCE_LIMITATION` code remains authoritative.

## 2026-09-01 — S3K service 128 is an authenticated producer-input limitation

- **Worktree/branch:** `.worktrees/sound-driver-roadmap-completion`,
  `feature/ai-sound-driver-roadmap-completion` at `e4f083172`
  (documentation fix round 3; production comparator/reference evidence is
  unchanged).
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`
  (unchanged authenticated 5,400-frame reference; projected to 5,286
  services).
- **Command:**
  `LUA_BIN=lua5.4 mvn -Dmse=off
  -Ds3k.rom.path=${OGGF_REPO_ROOT}/s3k.gen
  '-Dtest=com.openggf.tools.audio.parity.s3k.TestS3kAudioOracleFixtureContract,com.openggf.tools.audio.parity.s3k.TestS3kAudioParityComparator'
  test -B`, plus
  `java -cp target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
  com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare
  --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz
  --rom ${OGGF_REPO_ROOT}/s3k.gen --ticks 260 --format json`.
- **Result:** the 260-service run compares services 0-127, then stops at
  service/tick **128**, underlying reference event **0** (`YM port 1,
  register 82h, value FFh`). The typed report is
  `REFERENCE_LIMITATION`, `field=producer_input`, with
  `ProducerInputEvidence.Availability.UNAVAILABLE_DURING_PRODUCER_SUSPENSION`
  and reason `mailbox input was unavailable for the first observable service
  after reference producer interrupt services suspended`; the CLI exits **5**.
  This is not an engine divergence and there is no realignment. Ordinary
  missing-write evidence remains `EVENT_MISSING` and exits **3**; malformed
  reference/tool failures remain exit **4**.
- **Notes:** the limitation is selected from the source-owned
  `zPlaySEGAPCM` interrupt-suspension boundary and the first resumed service,
  not from a tick number, zone, request guess, or write shape. The exact
  84-write stop proof remains at service/tick **49** for `FFh`; service/tick
  **138** (next music activation) still begins with the exact reference
  84-write stop prefix. S1 hard gates remain `MATCH (14,690 ticks)` for GHZ
  music and `MATCH (1,967 ticks, 8 dispatches)` for sound-test SFX. Named
  remaining frontiers are the unsupported `E3h` PSG-mute product gap (not a
  structured producer-input `REFERENCE_LIMITATION`), the `E4h` seven-slot
  conditional physical write/restoration walk, and full `FFh` control-flow
  parity beyond the implemented 84-write stop/PCM transport (including the
  producer-side pre-consumption mailbox at service 128).

## 2026-08-31 — S1 sound-test SFX oracle reaches full MATCH

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` at `4c1efea6c` plus the pending S1
  implementation tranche.
- **Fixture:** `src/test/resources/audio/parity/s1/s1-soundtest-sfx-reference.v1.jsonl.gz`
  (unchanged committed reference; S1 World REV01 ROM SHA-1
  `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`).
- **Command:** `S1AudioParityTool capture --capture sfx --reference
  <run-root>/reference.jsonl.gz --rom <absolute verified ROM> --output
  <run-root>/openggf.jsonl`, followed by `S1AudioParityTool compare
  --reference <run-root>/reference.jsonl.gz --openggf
  <run-root>/openggf.jsonl --human-report <run-root>/report.txt
  --json-report <run-root>/report.json`.
- **Result:** **`S1 audio parity: MATCH (1967 ticks)`**, exit 0. A fresh
  capture/comparison against the committed GHZ music fixture remains
  **`MATCH (14690 ticks)`**, exit 0.
- **Notes:** first-divergence work from tick 377 through the end of the
  fixture modelled source-owned S1 behavior: one terminal note-off; the
  explicit PSG3 `$DF/$FF` admission pair and shared tone-3/noise ownership;
  per-track release with FM voice/pan and PSG rest/noise restoration; fixed
  SFX-RAM walk order; tied PSG volume service; raw `$B5` ring-speaker
  alternation; and shipped `FixBugs=0` `SendVoiceTL` reads through the global
  special-SFX pointer, including ROM vector bytes when it is zero. No fixture
  or comparator was changed. Human listening remains pending in the SMPS
  playback checklist.

## 2026-08-31 — S3K service projection crosses SEGA PCM to the hidden stop request

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` (the PCM-tier correction lands with this
  entry).
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`
  (unchanged authenticated 5,400-frame reference).
- **Command:** `S3kAudioParityTool compare --reference <committed fixture>
  --rom <absolute SHA-1-verified locked-on ROM>` after compiling the worktree.
- **Result:** services 0-127 match. First divergence service **128** (source
  frame **242**), `EVENT_MISSING`, event 0: reference Z80 YM part II
  `82h = FFh`, engine missing.
- **Notes:** `FFh` enters `zPlaySEGAPCM`, which clears its request flag and
  disables interrupts for the whole chant (`D:4372-4424`). The projection now
  keeps the command-owned 84-write stop-all service, excludes register `2Ah`
  sample transport / `2Bh=80` DAC entry, and emits no fictitious driver
  services for the 100 transport-only frame rows. This yields **5,286**
  complete services. At source frame 242 the reference emits another exact
  stop-all burst, consistent with `FEh` (`cmd_StopSEGA`), but its pre-frame
  mailbox is empty: the 68k request is written and consumed within
  `host.Advance`, before the post-frame RAM snapshot. The v1 producer therefore
  cannot authorize the engine request at service 128. Moving this frontier
  requires a true pre-consumption 68k-to-Z80 mailbox probe, not inference from
  the burst.

## 2026-08-31 — S3K service projection reaches the SEGA command at service 49

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` (the service projection lands with this
  entry).
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`
  (unchanged authenticated 5,400-frame reference).
- **Command:** `S3kAudioParityTool compare --reference <committed fixture>
  --rom <absolute SHA-1-verified locked-on ROM>` after compiling the worktree.
- **Result:** the complete boot service and the first ordinary `E1h`
  fade-init service match. First divergence service **49**, `EVENT_MISSING`,
  event 0: reference Z80 YM part II `82h = FFh`, engine missing.
- **Notes:** the reader now projects 5,400 frame rows into 5,386 complete Z80
  services. It groups the cross-frame boot burst by the source-owned
  `zPalDblUpdCounter` transition `0 -> 5` at `zInitAudioDriver` completion
  (`D:523-551`), producing one 85-write boot service without a frame-number
  trigger. The engine host emits the exact shipped `zStopAllSound` sequence;
  the next service consumes the `E1h` request that remained pending during
  boot and matches its unconditional `zPSGSilenceAll`. Service 49 consumes
  `FFh` (`cmd_SEGA`), whose first action is another `zStopAllSound`; SEGA PCM
  dispatch is still explicitly unsupported by the host and is the new
  frontier.

## 2026-08-31 — S3K driver projection advances from the 68k bootstrap to Z80 boot at tick 13

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` (the projection fix lands with this entry).
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`
  (unchanged committed reference; its CPU-tagged full-bus rows and terminal
  digest remain intact).
- **Command:** the entry-of-record `S3kAudioParityTool compare` invocation
  below with the absolute SHA-1-verified locked-on ROM; `--ticks 13` was run
  separately as the green-prefix gate.
- **Result:** ticks **0-12 MATCH**. First divergence tick **13**,
  `EVENT_MISSING`, event 0: reference Z80 YM part II `82h = FFh`, engine
  missing (first-divergence-only comparator).
- **Notes:** the reader now validates each captured write's observer
  `source_cpu` and projects only CPU 1 (Z80) into this driver oracle. CPU 2
  (68k) writes, including tick 3's `PSGInitValues` `9F BF DF FF`, remain in
  the digest-authenticated fixture but are outside comparison. Tick 13 is the
  genuine `zInitAudioDriver -> zStopAllSound` burst (S3K spec §1 boot and §5,
  `D:523-551,2460-2521`); it spans movie frames 13-14 before ordinary `zVInt`
  service. The current frame-shaped engine host has no source-owned driver
  installation/boot-service boundary. Emitting it at a fixture frame or
  triggering it from comparison writes would violate the no-trace-hydration
  rule, so this frontier requires a service-shaped oracle/host boundary rather
  than a production sequencer patch.

## 2026-08-31 — S2 EHZ music prefix reaches the first SFX override at tick 210

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` (the fixes land with this entry).
- **Fixture:** `src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz`
  (unchanged committed reference).
- **Command:** `S2AudioOracleTool --fixture <committed fixture> --rom
  <absolute SHA-1-verified S2 REV01 ROM>` on the compiled worktree classes.
- **Result:** DIVERGENCE at tick **210** (movie row **10412**), `writes[0]`:
  reference PSG `0x9A`, engine YM part II `A4h = 33h`; **303 of 698 ticks
  divergent**. Ticks 0-209 match.
- **Notes:** the comparator now pairs each tick with the kind-9 service
  **completion** frame, not its begin frame; update 0 begins at row 10201 and
  completes at row 10202, so the old FM2 `1424h/1428h` frontier was a
  mid-track-walk snapshot. Tick writes are likewise kind-9-owned only rather
  than mixing the parent V-int's multi-frame load burst into its child update.
  The engine fixes exposed along the prefix are source-owned S2 semantics:
  resting PSG envelopes advance without writing, FM note preparation does not
  repeat pan, `zSetChanVol` rewrites all four TLs, E7 persists on DAC while
  FM/PSG clear it at expiry, FM no-attack still keys on, and note-start
  modulation follows key-on without forcing a write. At tick 210 the
  reference FM4 has override bit 2 set by an SFX and suppresses its modulation
  write (`sd:1088-1092`); this music-only engine capture deliberately injects
  no SFX. The next S2 frontier is therefore the declared SFX/admission tier.

## 2026-08-31 — S3K tick-3 attribution retracted: this is the 68k PSG bootstrap, not `zStopAllSound`

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` at `a390f1649` plus this documentation
  correction.
- **Fixture:** `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`
  (unchanged committed reference).
- **Command:** the entry-of-record `S3kAudioParityTool compare` invocation
  below, plus direct inspection of the fixture's first non-empty write rows
  against `skdisasm/sonic3k.asm:175-184,260` and
  `Sound/Z80 Sound Driver.asm` `zInitAudioDriver` / `zStopAllSound`.
- **Result:** the comparator remains red at tick 3, event 0, PSG `0x9F`
  missing. **No production fix is valid at that frontier.** The reference row
  contains exactly `0x9F,0xBF,0xDF,0xFF`, matching the 68k power-on
  `PSGInitValues` loop before the SMPS driver is installed. The actual Z80
  initialization burst first appears at tick 13 and continues at tick 14 with
  the source-specified FM silence/SSG-EG/PSG/DAC/FM3 sequence.
- **Notes:** the 2026-08-30 entry's claim that tick 3 was
  `zInitAudioDriver -> zStopAllSound` is retracted. `S3kOpenGgfAudioCapture`
  is a driver/request host and has no 68k power-on execution boundary; adding
  the four writes on oracle tick 3 would key engine behavior to a fixture
  frame, while emitting them from `SmpsDriver` startup would assign 68k-owned
  work to the wrong subsystem. This is a host-capture scope gap. The committed
  fixture and comparator remain unchanged, so a later oracle revision must
  establish a source-owned 68k bootstrap boundary before it can expose the
  first driver-owned divergence.

## 2026-08-31 — S1 SFX frontier advances from admission tick 351 to release tick 377

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` (the fix lands in the same commit; base
  `fcc190d5f`).
- **Fixture:** `src/test/resources/audio/parity/s1/s1-soundtest-sfx-reference.v1.jsonl.gz`
  (unchanged committed reference).
- **Command:** `S1AudioParityTool capture --capture sfx` followed by `compare`
  against the committed fixture in `<external run root>/sdre2-s1-sfx`, with
  the absolute SHA-1-verified S1 REV01 ROM path.
- **Result:** **MISMATCH**, first divergence tick **377**, event 3: the engine
  emits an extra PSG `0x9F` after the reference's final event. Tick 351's prior
  `event_extra` is green. The S1 GHZ music gate remains **MATCH (14,690
  ticks)** in a fresh committed-reference engine capture.
- **Notes:** `Sound_PlaySFX` (`SD:977-1087`) has no PSG1/2 takeover write; the
  typed S1 PSG takeover profile now leaves the first visible write to the SFX
  track while legacy profiles retain the existing synthetic silence. The new
  tick-377 frontier is release-shaped: after both streams write PSG `0xB3`,
  `0xF7`, `0x9F`, the engine writes a second `0x9F` and begins immediate music
  restoration, while the reference stops. That belongs to the profile-shaped
  stop/restore gap (§6.2), not admission.

## 2026-08-31 — Cadence 2–4 land without moving the three live oracle frontiers

- **Worktree/branch:** `.worktrees/sdre2-cadence-resume`,
  `feature/ai-sdre2-cadence-resume` (the fix lands in the same commit; base
  `f07e45c44`).
- **Fixtures:** the unchanged committed S1 GHZ, S2 EHZ reload-window, and S3K
  AIZ1 intro references named in their entries below.
- **Commands:** S1 engine capture and comparison against the committed fixture
  through `S1AudioParityTool capture` / `compare` in
  `<external run root>/sdre2-s1-committed`; the entry-of-record
  S2 and S3K Java invocations below with absolute, SHA-1-verified ROM paths.
- **Results:** S1 music **MATCH (14,690 ticks)**. S2 remains at tick 0,
  `track.FM2.dataPointer`, expected `0x1424`, actual `0x1428`, **669 of 698
  ticks divergent**. S3K remains at tick 3, `EVENT_MISSING`, event 0, reference
  PSG `0x9F`, engine missing.
- **Notes:** live presentation is now outer-frame locked; S2 PAL uses the
  driver-global 6-per-5 music cadence while SFX stays single-service; S3K PAL
  repeats the complete driver pass 7-per-6 and the shared speed tail produces
  the cited 5-per-4 vector. These branches are absent from the three current
  oracle windows, so unchanged frontiers are expected; the cadence vectors are
  pinned by `TestSmpsSequencerCadence`. A fresh BizHawk S1 reference recapture
  was attempted through `run_s1_audio_parity.sh` but the local host had no X
  display; that capture failure is not reported as a parity result. The
  committed-reference engine comparison above is the recorded gate.

## 2026-08-31 — S2 frontier: tick-0 `tempoTimeout` green after the delay-frame cadence fix

- **Worktree/branch:** `.worktrees/sdre2-cadence`, `feature/ai-sdre2-cadence`
  (the fix lands in the same commit as this entry; base
  `feature/ai-sound-driver-re` `fc3e70c95`).
- **Fixture:** `src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz`
  (unchanged committed reference).
- **Command:** the entry-of-record S2 invocation (`S2AudioOracleTool --fixture
  <committed fixture> --rom <s2.gen>` on the compiled worktree classes, as in
  the 2026-08-30 first-measurement entry).
- **Result:** DIVERGENCE — first divergence still tick 0 (movie row 10201),
  now `track.FM2.dataPointer` expected `0x1424` actual `0x1428`;
  **698 → 669 of 698 ticks divergent**. The previous frontier field,
  tick-0 `global.tempoTimeout` (`0x3c` vs `0x0`), is green: the sequencer now
  seeds its accumulator at song load (`sd:1820-1822`) and runs `TempoWait` on
  the first update (`sd:545-551`), and a no-carry frame pre-increments every
  music slot's `DurationTimeout` while the track walk still runs
  (`sd:596-619`, gap analysis §1.2 #2).
- **Notes:** the exposed `dataPointer` divergence is a load/track-walk stream
  position gap (engine 4 bytes ahead on FM2 at the first update), not a
  cadence field — it belongs to a music-load/note-parse lane. Cross-checks at
  the same commit: S1 GHZ music oracle **MATCH (14,690 ticks)** held; S1
  sound-test SFX frontier unchanged (tick 351 `event_extra`); S3K frontier
  unchanged (tick 3 boot-silence `EVENT_MISSING`, unreachable by cadence).

## 2026-08-30 — S1 sound-test SFX oracle first light: red at the first SFX admission

- **Worktree/branch:** `.worktrees/sdre-oracle-s1`, `feature/ai-sdre-oracle-s1` (commit recorded with the fixture landing).
- **Fixture:** `src/test/resources/audio/parity/s1/s1-soundtest-sfx-reference.v1.jsonl.gz`
  (movie `s1-soundtest-sfx.bk2`: the pinned GHZ sound-test prefix, then eight normal SFX
  `$A0 $A4 $A6 $AA $B5 $C6 $CC $CF`, dispatched at tick ordinals 351, 525, 689,
  863, 1072, 1311, 1495 and 1664; 1,967 ticks, epoch at GHZ music acceptance).
  Reference recorded twice on BizHawk 2.11/GPGX (debug and production probe modes)
  with byte-identical emitted captures.
- **Command:**
  `tools/audio/run_s1_audio_parity.sh --mode sfx --output-root <external dir>`
  (or the recorded `S1AudioParityTool capture --capture sfx` + `compare` pair against
  the committed fixture).
- **Result:** **MISMATCH** (exit 3). First divergence: tick **351** — precisely the
  invocation whose recorded `dispatches` is `[0xA0]`, the first SFX (jump) — event
  index 2, kind `event_extra`: the engine emits `psg 0x9F` (PSG1 silence) that the ROM
  does not. Reference events at that tick run `psg 0xB3, psg 0xF6, psg 0x80, psg 0x14, …`;
  the engine inserts a PSG1 attenuation-off silence between index 1 and the ROM's
  frequency latch. There is no error count beyond the first divergence by design.
- **Notes:** the extra write comes from the engine's SFX channel-steal path
  (`SmpsDriver.writePsg` lock acquisition calls `silencePsgChannel` when an SFX takes a
  channel from music), while S1 `Sound_PlaySFX` only marks the music track overridden and
  writes nothing at admission (S1 routine map §6). Matches gap analysis §1.2 #6
  (override/restore burst shape is profile work). State and all 350 earlier ticks
  (music-only, including tick 0's music-load burst) match. Fix belongs to an
  implementation lane, not this oracle lane.

## 2026-08-30 — S1 GHZ music oracle re-established from a committed fixture: MATCH

- **Worktree/branch:** `.worktrees/sdre-oracle-s1`, `feature/ai-sdre-oracle-s1`.
- **Fixture:** `src/test/resources/audio/parity/s1/s1-soundtest-ghz-reference.v1.jsonl.gz`
  (movie `s1-soundtest-ghz.bk2`; 14,690 ticks to proven recurrence, cycle start 5,473,
  period 4,608). Uncompressed SHA-256 `5941958c…` — byte-identical to the 2026-08-09 and
  2026-08-30 audit captures, and to a fresh capture recorded this session with the
  consumer-side domain-fixed probe (`tools/audio/probes/s1_audio_driver_parity_probe.lua`).
- **Command:** `S1AudioParityTool capture` + `compare` against the committed `.gz` fixture
  (external run root; also reachable via `tools/audio/run_s1_audio_parity.sh --mode music
  --output-root <external dir>`).
- **Result:** **`S1 audio parity: MATCH (14690 ticks)`**, exit 0.
- **Break-it-on-purpose (comparator proof it actually compares):** two independent
  corruption experiments were run (this lane's, and the concurrent writer's — see the
  validation record's provenance note); all four outcomes were first divergences with
  exit 3:
  - fixture byte, run A: tick 5000 `tempoTimeout` 3→4 → `global_state_mismatch,
    tick 5000, field tempo_timeout, reference 4, openggf 3`;
  - engine write, run A: tick 3001 event 0 `ym2612 p0 reg 0xA4` 34→35 →
    `event_value_different, tick 3001, event 0`;
  - fixture byte, run B (concurrent writer's, per commit 0c1d0580e; not re-run by
    this lane): tick 5000 DAC `duration` 11→12 → `track_state_mismatch, tick 5000,
    role DAC, field duration`;
  - engine write, run B (same provenance): tick 7000 event 0 `ym2612 p0 reg 0x28`
    1→0 → `event_value_different, tick 7000, event 0`.

## 2026-08-30 — S2 driver oracle: first measurement (expected red)

- **Worktree:** `.worktrees/sdre-oracle-s2`, branch `feature/ai-sdre-oracle-s2`
  (commit recorded in the entry's own commit).
- **Oracle:** `S2AudioOracleComparator` against
  `src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz`
  (movie rows 10150-10899 of the pinned S2 complete-emeralds movie; EHZ music
  reload anchor at row 10195; recorded by the TraceChaser headless harness
  with the patch-0001 GPGX audio observer — see the fixture's metadata JSON
  and `docs/architecture/research/audio/2026-08-30-s2-driver-oracle.md`).
- **Command:**

  ```bash
  mvn -q -Dmse=off compile dependency:build-classpath -Dmdep.outputFile=target/oracle-classpath.txt
  java -cp "target/classes:$(cat target/oracle-classpath.txt)" \
    com.openggf.tools.audio.parity.s2.S2AudioOracleTool \
    --fixture "$PWD/src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz" \
    --rom "$PWD/s2.gen"
  ```

- **Result:** DIVERGENCE — 698 of 698 recovered driver-update ticks divergent.
- **First divergence:** tick 0 (movie row 10201), `global.tempoTimeout`,
  expected `0x3c`, actual `0x0`.
- **Reading:** the ROM seeds `TempoTimeout = CurrentTempo = 9Eh` at song load
  (`s2.sounddriver.asm:1820-1822`) and runs `TempoWait` at the top of the
  first `zUpdateMusic` (`sd:545-551`): `9Eh + 9Eh = 13Ch` → carry → `3Ch`.
  The engine's first update leaves its tempo accumulator at 0 — neither the
  load-time seed nor the first-update accumulation is modelled
  (gap analysis §1.2 #1/#2; behaviour spec §3.1). Every subsequent tick also
  diverges (cadence differences cascade through durations, envelope cursors
  and the write stream), so 698/698 is the honest count, and the tick-0 field
  is the frontier to move first. Two measurement facts recovered from the
  reference along the way, both now encoded in the comparator's tick
  recovery: the Saxman EHZ load masks interrupts across movie rows
  10195-10200 (those frames hold a half-initialised driver image and no
  `zUpdateMusic` service), and the caught-up Z80 misses row 10202's V-int
  entirely — one oracle tick is therefore one completed `zUpdateMusic`
  service from the observer's service stream, not one video frame.
- **Break-it evidence** (`TestS2AudioOracleComparator`, outputs from the
  evidence run at this commit):
  - untampered self-comparison: `S2 driver oracle: MATCH (698 ticks)`;
  - reference byte corrupted (tick 40, `FM1.DurationTimeout ^ 0x55`):
    `S2 driver oracle: DIVERGENCE at tick 40 (movie row 10242), field
    track.FM1.durationTimeout: … expected=0x41 actual=0x14 [1 of 698 ticks
    divergent]`;
  - engine write corrupted (tick 20, `writes[0] value ^ 0x40`):
    `S2 driver oracle: DIVERGENCE at tick 20 (movie row 10222), field
    writes[0]: … expected=ym0[0x28]=0x0 actual=ym0[0x28]=0x40 [1 of 698
    ticks divergent]`.
## 2026-08-30 - S3K oracle first frontier: boot silence burst (tick 3) — attribution retracted

- Worktree `.worktrees/sdre-oracle-s3k`, branch `feature/ai-sdre-oracle-s3k`
  (fixture, capture tooling and comparator land in the same commit as this
  entry; engine base `f087b8947` + sdre-gaps/spec-s3k docs merges).
- Fixture: `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz`
  (identity in `s3k-aiz1-intro-metadata-v1.json`): 5,400 driver invocations
  (movie frames 0-5399 of the committed `s3k-complete-sonic-tails.bk2` from
  power-on), 725,898 decoded YM/PSG writes; covers boot, SEGA chant, title
  music (`25h`), Knuckles intro theme (`1Fh`), AIZ1 music (`01h`, ~54 s) and
  ten-plus distinct gameplay SFX. Captured deterministically (two runs,
  byte-identical) by `tools/audio/run_s3k_audio_oracle_reference.sh` with the
  lock-verified patch-0001 observer core
  (`e65315743a6a1228…`, `artifact-lock.json` identity).
- Command:
  `java -cp "target/classes:$(cat target/s3k-oracle.classpath)"
  com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare
  --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz
  --rom <locked-on s3k.gen>`
- Result: **red**, as expected for the first run. **Superseded attribution:**
  the 2026-08-31 correction above proves this row is the 68k power-on PSG
  initialization loop, not the Z80 driver's initialization burst.
  First divergence: **tick 3, `EVENT_MISSING`, event 0** — the reference
  emits PSG `9Fh` as the first of the 68k bootstrap's four
  `PSGInitValues`; the driver-only engine host emits nothing. Error count:
  first divergence only (comparator stops); ticks 0-2 of the same run are
  green (`MATCH (3 ticks)` with `--ticks 3`).
- Broken on purpose before trusting the comparison (project rule): a
  corrupted `zCurrentTempo` byte in a temp copy (terminal digest recomputed)
  reports `GLOBAL_STATE_MISMATCH` at its exact tick with expected/actual
  (`64` vs `0`, exit 3); the same corruption without the digest fix is
  refused as `terminal body digest mismatch` (exit 4); a corrupted engine
  write is reported at its tick/event index by
  `TestS3kAudioParityComparator.corruptedWriteIsReportedAtItsEventIndex`.
- Unmodelled requests this run (logged by the capture host, not silently
  skipped): `E1h` fade-out (7 ticks), `FFh` SEGA chant (1 tick).

## 2026-09-03 - S3K v2 oracle: request sidecar published and wired; frontier 128 -> 138

- Worktree `.worktrees/audio-s3k-sidecar`, branch `feature/ai-s3k-request-sidecar`,
  over `develop` at `ef8e80703`.
- Command (all three cycles):
  `java -cp "target/classes:$(cat target/cp.txt)"
  com.openggf.tools.audio.parity.s3k.S3kAudioParityTool compare
  --reference src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz
  --requests src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json
  --rom <locked-on s3k.gen>`. The committed default is exercised by
  `TestS3kOracleRequestSidecarWiring#theOracleReachesTheTitleMusicLoadsTrackCadence`.
- Fixture published: `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json`,
  sha256 `d4fc2fae62c667b1da41ba428315ac121e04494836adca15e84e32c8cd696be4`,
  1,483 bytes, schema `openggf.s3k-preconsumption-request-observations.v1`,
  `production_bound:false`, 14 observations over movie rows `[0,5400)` from two
  byte-identical captures (`2063b558c9b81ba8…`). Installed byte-for-byte from the
  reviewed capture; provenance in the v2 metadata's `request_sidecar` block.
- Resolution rule (no measured constant, and cross-checked six times). The v2
  stream samples its mailbox at frame entry, but `zVInt` does not read the
  mailbox at the interrupt: `zUpdateEverything` runs `zPauseUnpause`,
  `zUpdateSFXTracks`, `TempoWait` and both fade handlers before `zUpdateMusic`
  loads `zMusicNumber` for `zFillSoundQueue` (Sound/Z80 Sound Driver.asm:653-701,
  :2628-2643). So a 68k store can be consumed by the service already running in
  its own row. The reader resolves row `R` against the first service completing
  after `R`: if that service completes in row `R+1` its entry sample settles the
  question, and if the sample is non-empty the reference already supplies the
  byte and the sidecar adds nothing. Six of the fourteen observations resolve
  that way and agree byte-for-byte with the reference's own mailbox; a sidecar
  that disagreed with one would be refused.
- Cycle 1, tick 128 -> tick 138. Supplying movie row 242's `cmd_StopSEGA` (0FEh)
  carried the whole 84-write `zStopAllSound` burst. The next divergence was
  `TRACK_STATE_MISMATCH` at tick 138, `MUS_PSG1.amsFmsPan`, reference `192`
  against `null`: the S3K normalizer suppressed `zTrack.AMSFMSPan` for PSG
  tracks. `zZeroFillTrackRAM` stores 0C0h into that byte for every track it
  initialises (:2181-2198) and `zBGMLoad`'s PSG loop calls it exactly as the
  FM/DAC loop does (:1867-1881), so the byte is real on a PSG track and the
  engine already held the same default. Suppression removed.
- Cycle 2, tick 138 event 84 -> event 85. The remaining divergence was
  `EVENT_VALUE_DIFFERENT` at tick 138 event 84: reference `ym2612 port 1
  register 0B6h = 0C0h`, engine `port 0 register 0B4h = 0C0h`. `zBGMLoad` writes
  exactly one hardware register between the song bank switch and its track loops
  (:1811-1816), 0B6h through the port 1 address/data pair. S3K was inheriting the
  legacy activation program's `2Bh=80h` instead, which `zBGMLoad` never writes
  (DAC enable belongs to the DAC path, which the engine already drives).
  `Sonic3kSmpsPhysicalPolicy.activateMusic` now returns the ROM's own program.
- Current frontier: **tick 138, `EVENT_VALUE_DIFFERENT`, event 85**, reference
  `ym2612 port 0 register 28h = 6` against engine `port 0 register 0B4h = 0C0h`.
  The ROM's first `zUpdateMusic` pass over the newly loaded song keys off FM6 and
  runs `zFM3NormalMode` plus a channel silence before its first track register
  write; the engine begins at its own first track. That is a track-init cadence
  frontier, not a request one, and is the next target.
- Perturbation evidence, and it was mutation-checked. Corrupting the sidecar's
  row-242 value changes the engine capture; rewriting every reference frame to an
  impossible value leaves both the engine capture and the comparison
  bit-for-bit unchanged. Replacing the supplied byte with a constant makes
  exactly the three tests that claim those properties fail, and no others.

## 2026-09-04 — Currently-matching audio oracles pinned as hard assertions

- **Worktree/branch:** `.worktrees/oracle-pins`, `test/ai-oracle-match-pins`, from
  `develop` `a85d1f7b6`.
- **Motivation:** several oracles printed their result on a `MEASUREMENT_ONLY`
  line and never asserted it. A regression that took the S2 driver oracle from
  `MATCH (698 ticks)` to a divergence at tick 208 left the suite at 0 failures.
  A `MATCH` that is achieved is now pinned as a hard assertion so a regression
  fails the build; comparisons that still diverge are left as
  `MEASUREMENT_ONLY` on purpose.
- **Pinned (test-only, no production code touched):**
  - `TestS2RequestAwareOracleRawStream.realCandidateAndBk2DrivenDriverStateCompare`
    now asserts `S2AudioOracleComparator.Kind.MATCH` and `driver.comparedTicks() == 698`
    (was `assertNotEquals(..., INVALID)`).
  - `TestS2DriverStateOracle.driverStateComparesAcrossTheWidenedSpan` now
    asserts `MATCH` and `comparedTicks() == 2198` for both the "state and
    writes" and the "state only" reports (was `assertNotEquals(..., INVALID)`
    for both). The DAC-stream comparison (`BYTE DIFFERENT` at run 3 byte 709)
    is left `MEASUREMENT_ONLY`; it still diverges.
  - `TestS2WidenedRequestOracle.everyReplayableWindowComparesAgainstTheEnginesOwnRequests`
    now asserts `MATCH` and `comparedTransfers() == published.requestTransfers()`
    per window (was `assertNotEquals(..., INVALID)`), pinning the three windows
    the capture bound actually exercises: `w10150-10900` (25), `w10900-11650`
    (52), `w11650-12400` (27).
  - `TestS1GameplayRun2AudioDriverOracle.wholeWindowMatches` now also asserts
    `report.ticksCompared() == 5257` alongside the existing `Kind.MATCH`
    assertion.
  - `TestS1GameplayAudioDriverOracle.oracleMatchesTheWholeReference` already
    asserted `MATCH (2562 ticks)`; unchanged.
  - `TestS2CpzDriverStateOracle.driverStateComparesAcrossTheCpzWindow` (landed
    on `develop` after this branch started, merged in) now asserts `MATCH` and
    `stateOnly.comparedTicks() == 720` for the state-only report (was
    `assertNotEquals(..., INVALID)`). The "state and writes" report for the
    same window still diverges (`DIVERGENCE` at tick 237, `writes[4]`, ym1 port
    `0xB1` vs `0xB0`) and is left `MEASUREMENT_ONLY` on purpose.
- **Left as `MEASUREMENT_ONLY` (still diverging, not pinned):** the S2 DAC byte
  stream (`BYTE DIFFERENT` at run 3 byte 709) and the S3K oracle frontier at
  service 551/tick 138.
- **S1 GHZ music oracle (`MATCH (14690 ticks)`) and S1 sound-test SFX oracle
  (`MATCH (1967 ticks)`)** are measured only via the external
  `tools/audio/run_s1_audio_parity.sh` + BizHawk wrapper; no committed JUnit
  test in `src/test/java/com/openggf/tools/audio/parity/**` currently drives
  either comparison, so there is nothing in-repo to pin for them.
- **Break-on-purpose evidence:** each new pin was temporarily broken (expected
  tick/transfer constant off by one, or `Kind.MATCH` swapped for a mismatching
  kind) and confirmed to fail before being reverted to the correct value; see
  the commit for the exact pins exercised.
- **Gate:** `com.openggf.tools.audio.parity.**` — 178 tests, 0 failures,
  0 errors, 2 skips (ROM-gated measurements with no supplied sidecar path),
  run with `-Dmse=off` and explicit absolute ROM/BK2 paths.

## 2026-09-04 — S1 sound-test music and SFX oracles brought into JUnit

- **Worktree/branch:** `.worktrees/oracle-pins`, `test/ai-s1-soundtest-oracle-tests`,
  from `develop` `319d777de`.
- **Motivation:** the S1 GHZ music oracle (`MATCH (14690 ticks)`) and the S1
  sound-test SFX oracle (`MATCH (1967 ticks)`) were regression gates quoted
  repeatedly in this log, but both were measured only via the external
  `tools/audio/run_s1_audio_parity.sh` + BizHawk wrapper. Their references —
  `s1-soundtest-ghz-reference.v1.jsonl.gz` and
  `s1-soundtest-sfx-reference.v1.jsonl.gz` — were already committed, so no
  BizHawk capture is needed to exercise them: BizHawk only produced the
  reference bytes, which are static fixtures now. The wrapper's own engine
  side already runs the same in-process capture
  (`S1AudioParityTool capture --capture music|sfx`, which calls
  `S1OpenGgfAudioCapture`/`S1OpenGgfSfxAudioCapture`), so nothing about the
  comparison itself requires BizHawk.
- **Added**, modelled on `TestS1GameplayAudioDriverOracle`:
  - `TestS1SoundTestMusicAudioDriverOracle.wholeWindowMatches`: decompresses
    the committed GHZ music reference, replays it through
    `S1OpenGgfAudioCapture.capture` (the same host `--capture music` uses),
    and asserts `AudioParityReport.Kind.MATCH` with `ticksCompared() == 14690`.
  - `TestS1SoundTestSfxAudioDriverOracle.wholeWindowMatches`: same shape for
    the sound-test SFX reference via `S1OpenGgfSfxAudioCapture.capture` (the
    same host `--capture sfx` uses), asserting `MATCH` with
    `ticksCompared() == 1967`.
  - Both classes also carry a `corruptingTheReferenceIsDetectedAtTheCorruptedTick`
    test (byte-flip on tick 0's first YM2612 write), proving the comparison is
    live rather than a comparison that never actually ran.
  - Neither test hydrates engine or gameplay state from the fixture; both are
    comparison-only, ROM-gated on `-Dsonic1.rom.path`.
- **Break-on-purpose:** both new `ticksCompared()` pins were bumped by one
  (14691 / 1968), run, and confirmed to fail
  (`expected: <14691> but was: <14690>`, `expected: <1968> but was: <1967>`),
  then reverted to the correct literals.
- **Gate:** `com.openggf.tools.audio.parity.**` full run, `-Dmse=off`,
  absolute ROM/BK2 paths — see the commit for the exact count.
