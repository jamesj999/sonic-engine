# SMPS engine architecture map

**Date:** 2026-08-30
**Branch:** `feature/ai-sdre-engine-map` (from `feature/ai-sound-driver-re` at `f087b8947`)
**Kind:** audit (point-in-time map of the engine's SMPS driver model)
**Purpose:** record what the engine's sound-driver model is today, class by class,
so a driver-RAM-shaped comparison against the ROM drivers can be attached at the right
seams. Gaps are recorded as adaptation points, not as a rewrite proposal.

**Source rule.** Statements about ROM driver behaviour cite only the three
disassemblies (`docs/s1disasm/s1.sounddriver.asm`, `docs/s1disasm/s1.sounddriver.ram.asm`,
`docs/s2disasm/s2.sounddriver.asm`, `docs/skdisasm/Sound/Z80 Sound Driver.asm`). Engine
code was read to describe what the engine does, never as evidence of hardware behaviour.
Where a disassembly detail was not read for this audit it is listed under open
questions rather than resolved from memory.

**Provenance note (engine, not ROM).** `SmpsSequencer` still carries seven comments
that cite SMPSPlay by name (frequency tables, fade clamp, `CF_SND_OFF`, `DoNoteOn`),
`Sonic3kCoordFlagHandler` carries one, and `docs/architecture/engine-map.md:422-426`
names SMPSPlay as a sequencer reference. None of that was consulted here; it is
recorded because a sources-closed re-derivation will need to re-cite those sites from
the disassemblies.

---

## 1. Responsibility map

Line counts are `wc -l` at `f087b8947`.

### 1.1 Shared runtime (`com.openggf.audio.*`)

| Class | Lines | Owns |
|---|---|---|
| `smps/SmpsSequencer` | 3494 | One SMPS program (music or SFX): track construction from the parsed header, the tick (`tickTracks`), tempo modes, the fade state machine, all coordination flags common to the three drivers, note-on/off, FM/PSG frequency and volume, voice upload (three write profiles), PSG volume envelopes, FM volume envelopes (S3K `FF 06`), custom modulation and modulation envelopes, DAC note dispatch, continuous-SFX delegation, rewind snapshot/restore, `DebugState`. Implements `AudioStream` and `CoordFlagContext`. |
| `smps/SmpsSequencerConfig` | 377 | Immutable per-driver profile (builder): tempo mode, tempo base, channel orders, speed-up tempo map, coord-flag param overrides, extra track-end flags, pointer format, first-tick tempo, `direct68kDriver`, FM SFX takeover mode, FM voice write profile, volume mode, PSG env `0x80` semantics, note-on-prevent, delay-freq, coord-flag handler, modulation algorithm, fade defaults. |
| `smps/AbstractSmpsData` | 274 | Parsed SMPS header + raw program bytes: voice pointer, FM/PSG counts, dividing timing, tempo, DAC pointer, per-track pointer/transpose/volume/mod-env/instrument arrays, `z80StartAddress`, id, PAL-speedup flag. Implements `SmpsProgramView` (read-only byte/pointer/voice/envelope accessors). Subclasses own `parseHeader`, `getVoice`, `getPsgEnvelope`, `read16`, base-note offsets. |
| `smps/SmpsProgramView` | 48 | Read-only view the sequencer and coord handlers consume. |
| `smps/SmpsSfxData` | 19 | SFX-shaped program contract: tick multiplier + per-track `(channelMask, pointer, transpose, volume)` entries. |
| `smps/Sonic1SmpsData` | 210 | S1 68k header parser (lives in the shared package; the S2/S3K parsers live under `game/`). |
| `smps/DacData` | 84 | Immutable DAC sample bank + note→(sampleId, rate) map + per-game `baseCycles` (S1=301, S2=288, S3K=297 per its comment). |
| `smps/AbstractSmpsLoader`, `SmpsLoader` | 79, 19 | Loader contract (`loadMusic`, `loadSfx(int|String)`, `loadDacData`, `findMusicOffset`) + music/SFX caches. |
| `smps/CoordFlagContext` | 140 | The surface a game coord-flag handler may call on the sequencer: program view, voice/envelope load, stop note, refresh volume/instrument, jump-pointer read, tempo/dividing timing, modulation clear, fade in/out, comm byte, direct `writeFm`/`writePsg`/`playDac`/`stopDac`, continuous-SFX predicates. |
| `smps/CoordFlagHandler` | 48 | `handleFlag(ctx, track, cmd) -> boolean`, `flagParamLength(cmd)`, `onSfxStart(sfxId)`. |
| `smps/SmpsCoordFlagHandlerOwner`, `SmpsCoordFlagRuntimeState` | 89, 34 | Per-presentation registry of game handlers keyed by game id, with transactional configure/rollback; the only cross-track driver RAM a handler may keep is `spindashRevCounter` (snapshot/restore/reset). |
| `driver/SmpsDriver` | 2221 | `extends VirtualSynthesizer`. One chip pair shared by one music sequencer plus N SFX sequencers: sequencer list, SFX admission (prepare/commit, replacement of same-id SFX, channel-conflict kills, continuous-SFX id/flag/loop count), FM/PSG/DAC lock arbitration per chip channel (`fmLocks[6]`, `psgLocks[4]`), override propagation back to music tracks, region, `ReadMode` (`HYBRID`/`SAMPLE_ACCURATE`) render loop, service-observer and contention-observer hooks, live-command mutation tokens, rewind snapshot/restore, `stopAll`/`stopAllSfx`. |
| `driver/PreparedSfxAdmission`, `SfxAdmissionMutationJournal` | 87, 70 | Two-phase SFX admission (validate → commit) and its rollback journal. |
| `driver/SmpsRequestAdmissionPolicy` | 57 | Whole-request admission decision (`PERMISSIVE` default; games may override via `GameAudioProfile.getSfxAdmissionPolicy`). |
| `driver/SmpsDriverServiceObserver` | 157 | Diagnostic boundary: `onServiceBegin/End(ServiceEvent, SmpsDriverSnapshot)` around every `SEQUENCER_TICK`, `FADE_STEP`, `COMPLETION_CLEANUP`; lifecycle events (`DRIVER_CREATED`, `RESET`, `PAUSE`, `RESUME`, `STOP_ALL`, `STOP_ALL_SFX`, `SAVE`, `RESTORE`, `SEGA_PCM_ENTER/LEAVE`). |
| `driver/SfxContentionObserver` | 51 | Lock-arbitration outcomes per bus/channel. |
| `synth/VirtualSynthesizer` | 256 | Facade over `Ym2612Chip` + `PsgChip`: write routing, DAC data binding, stereo render/mix (`MASTER_GAIN_SHIFT`, `PSG_PREAMP_PERCENT`), mutes, snapshot, chip-write observer install. |
| `synth/ChipWriteObserver` | — | `onYm2612Write(port, reg, value)`, `onPsgWrite(value)`; fed by the chips themselves at enqueue/apply time. |
| `synth/Ym2612Chip` | 887 | Facade over the Nuked-OPN2 port: register-write queue with bus pacing, DAC streaming from `DacData`, resampler, mutes, snapshot. |
| `synth/PsgChip` | 620 | Clean-room SN76489; writes apply at the tick boundary the last render stopped on. |
| `AudioManager` | 3123 | Request front door (`playMusic/playSfx/playDonor*/fadeOutMusic/stopMusic/setSpeed*`), profile-owned system commands, ring-speaker alternation, music-override stack semantics, the `AudioCommandTimeline` (rewind replay), the presentation graph ("shadow" fields), logical snapshot, pause/resume, capture leases. |
| `AbstractSmpsAudioBackend` (+ `LWJGLAudioBackend`, `HeadlessSmpsAudioBackend`) | 1219 | Legacy source-construction backend. `AudioManager.sendLiveBackendCommands()` returns `false` unconditionally, so this class no longer drives live audio; it retains sequencer/driver construction, music stack, and `StateForTesting`. Guarded by `TestAudioPresentationArchitectureGuard` (`lwjglBackendDoesNotOwnIndependentMusicOrSfxSources`, `backendHasNoSecondCoordFlagOwnerOrCompatibilityFactory`, …). |
| `presentation/AudioPresentationSourceFactory` | 1277 | Builds `SmpsDriver`s and `SmpsSequencer`s for the live graph from frozen `SmpsAssetCatalog` entries; installs diagnostic observers; resolves SFX policy (priority/special/continuous) into `ResolvedSmpsSfxSource`. |
| `presentation/SmpsAssetCatalog` | 720 | Frozen, generation-keyed ROM program registry (`ProgramEntry`: program, program view, dependency, source descriptor, asset id, track count, SFX policy). |
| `presentation/AudioVoiceRegistry` | 1767 | Applies `AudioPresentationCommand`s to voices: music replace/push/restore/end override, `AddSmpsSfx` → driver admission, stop, fade, speed, tempo, mute/solo, snapshot/restore. |
| `presentation/AudioPresentationCommandResolver` | 479 | Turns manager requests into presentation commands (loads/registers assets, applies `SfxPolicy`). |
| `presentation/AudioPresentationCommandQueue`, `AudioPresentationCommand` | 139, 249 | Frame-boundary command queue and the command vocabulary (`ReplaceMusic`, `PushMusicOverride`, `RestoreMusicOverride`, `EndMusicOverride`, `AddSmpsSfx`, `StartSampleSfx`, `ReplaceRawPcm`, `StopRawPcm`, `StopMusic`, `StopAllSfx`, `FadeMusic`, `SetVoiceGain`, `SetVoicePitch`, `SetSpeedShoes`, `SetSpeedMultiplier`, `ChangeMusicTempo`, `ResetRingAlternation`, `ToggleMute`, `ToggleSolo`, `RewindBoundary`, `HardReset`). |
| `presentation/SmpsCompositeVoice` | 124 | One `SmpsDriver` as a mixer voice: `mixInto` calls `driver.read(scratch, stereoFrames*2)`. |
| `presentation/AudioPresentationProducer` | 777 | The only frame clock and PCM history owner: `present(commandFrame, mode)` per outer frame. |
| `presentation/OuterFramePresentation` | 63 | Single production entry into `presentFrame`; resolves `SILENT` (modal/pause/frame-step), `REVERSE`, `FORWARD`. |
| `runtime/AudioFrameClock` | 59 | `samplesForNextFrame() = (sampleRate + remainder) / frameRate` with carried remainder. |
| `rewind/SmpsTrackSnapshot`, `SmpsSequencerSnapshot`, `SmpsDriverSnapshot` | 145, 48, 135 | Immutable copies of every `Track` field, every sequencer scalar, and driver locks/continuous state/synth snapshot. |
| `rewind/AudioCommand`, `AudioCommandTimeline`, `AudioKeyframeStore`, `AudioLogicalSnapshot` | 47, 130, 99, — | Gameplay-frame-stamped request log and keyframe replay. |

### 1.2 Per-game (`com.openggf.game.sonicX.audio.*`)

| Game | Class | Lines | Owns |
|---|---|---|---|
| S1 | `Sonic1AudioProfile` | 148 | `GameSound`/`GameMusic` maps, loader factory, `Sonic1SmpsSequencerConfig.CONFIG`, command ids, SEGA PCM spec, `getSfxPriority` (from `Sonic1SmpsConstants.SOUND_PRIORITIES`, a Java table), `isSpecialSfx` (`0xD0`). |
| S1 | `Sonic1SmpsSequencerConfig` | 86 | `TIMEOUT` tempo, `ED` 0-param override, `EE` track-end, relative pointers, tempo on first tick, `direct68kDriver`, `S1_68K` voice profile, `REGISTER_SEQUENCE` takeover, speed-up tempo map. |
| S1 | `Sonic1SmpsLoader` | 385 | ROM music/SFX/special-SFX pointer tables, PSG envelope table, DAC driver samples from the Z80 blob, data-size discovery. |
| S1 | `Sonic1SfxData` | 209 | 68k SFX header (`SmpsSfxData`). |
| S1 | `Sonic1SmpsConstants`, `Sonic1Music`, `Sonic1Sfx`, `Sonic1SoundTestCatalog` | 174, 76, 124, 20 | ROM addresses, ids, priority table. |
| S2 | `Sonic2AudioProfile` | 123 | As S1; `getSfxPriority` from `Sonic2SmpsConstants.SFX_PRIORITY_TABLE` (Java table); no special-SFX class. |
| S2 | `Sonic2SmpsSequencerConfig` | 67 | `OVERFLOW2` tempo, `S2_Z80` voice profile, speed-up tempo map; everything else is the builder default (the builder defaults *are* the S2 profile). |
| S2 | `Sonic2SmpsLoader`, `Sonic2SmpsData`, `Sonic2SfxData`, `Sonic2PsgEnvelopes` | 705, 119, 189, 28 | Saxman-compressed bank loading, uncompressed songs, SFX bank, PCM bank; `Sonic2PsgEnvelopes` is a Java-resident envelope table. |
| S2 | `Sonic2SmpsConstants`, `Sonic2Music`, `Sonic2Sfx` | 124, 80, 138 | |
| S3K | `Sonic3kAudioProfile` | 194 | Registers `Sonic3kCoordFlagHandler` with the presentation owner, `FRAME_MULTIPLY` speed mode (`0x08`), continuous SFX predicate (`>= 0xBC`), spindash pitch override, no `getSfxPriority` override (all S3K SFX get the shared default `0x70`). |
| S3K | `Sonic3kSmpsSequencerConfig` | 75 | `OVERFLOW` tempo, `MOD_Z80`, `BIT7` volume mode, PSG env `0x80` = `RESET`, `NoteOnPrevent.HOLD`, `DelayFreq.KEEP`, `S3K_Z80` voice profile, fade constants, coord handler. |
| S3K | `Sonic3kCoordFlagHandler` | 681 | Full `E0–FF` dispatch for the Z80 driver including `FF 00–07` meta commands, `FC` continuous loop, `E9` spindash rev, `EE` direct FM write, `FE` FM3 special (consumed), `FD` raw-frequency mode. Owns nothing but `runtimeState`. Note: `E2 FF` calls `GameServices.audio().restoreMusic()` — the one static-service reach-out in the coord path. |
| S3K | `Sonic3kSmpsLoader`, `Sonic3kSmpsData`, `Sonic3kSfxData`, `Sonic3kDpcmDecoder` | 992, 260, 237, 49 | Z80 driver decompression, bank/pointer lists (S&K and S3 halves), global instrument table, PSG/mod envelope tables, DPCM DAC samples. |
| S3K | `Sonic3kSmpsConstants`, `Sonic3kMusic`, `Sonic3kSfx`, `Sonic3kSoundTestCatalog` | 164, 126, 228, 35 | |

Shared runtime: 7 558 lines in `audio.driver` + `audio.smps`; ~20 300 in the rest of
`audio.*`. Per-game: 5 874 lines. Audio tests: 129 files across `audio/**`,
`game/*/audio/**`, and `tools/audio/**`.

### 1.3 Where ROM data is decoded

| Data | Decoded by | Consumed as |
|---|---|---|
| Music/SFX program bytes and header | `SonicNSmpsLoader` → `SonicNSmpsData`/`SonicNSfxData.parseHeader` | `SmpsProgramView` in the sequencer; frozen into `SmpsAssetCatalog.ProgramEntry` for the live graph (`TestFrozenSmpsDataImmutability`) |
| Voices | `getVoice(id)` per data class (S3K resolves song-local vs global table; SFX fall back to the music program's voices via `setFallbackVoiceData`) | `Track.voiceData` (25-byte blob), uploaded by `refreshInstrument` |
| PSG envelopes | S1 loader table; S2 `Sonic2PsgEnvelopes` (Java); S3K loader from the Z80 blob | `Track.envData` copied per track |
| Modulation envelopes | S3K loader only | `Track.modEnvData` |
| DAC samples + rate map | `loadDacData()` per loader (S1 from Z80 DAC driver; S2 PCM bank; S3K DPCM decode) | `DacData` bound to `Ym2612Chip` |
| SFX priority | Java tables in `Sonic1SmpsConstants`/`Sonic2SmpsConstants` (the ROM `SOUND_PRIORITIES_ADDR` constant is declared but only `RomOffsetFinder` references it) | `SmpsSequencer.sfxPriority` via `SfxPolicy` |
| Speed-up tempos | Java maps in `SonicNSmpsSequencerConfig` | `calculateTempo()` |

### 1.4 Model ownership by concept

| Concept | Owner | Shape |
|---|---|---|
| Tracks | `SmpsSequencer.tracks` (`List<Track>`) | One `Track` per header entry; DAC track is `TrackType.DAC` on channel 5; FM channels linear 0–5; PSG 0–2 (noise uses PSG3's slot with `noiseMode`). |
| Channel ownership / SFX override | `SmpsDriver.fmLocks/psgLocks`, `Track.overridden` | Locks are per chip channel and per sequencer identity; `updateOverrides` flips `Track.overridden` on the music sequencer; release restores instrument/volume/pan/frequency (`setChannelOverridden`). |
| Voices | `Track.voiceData/voiceId/voiceScratch`; upload in `refreshInstrument` by `FmVoiceWriteProfile` | TL carrier masking by `VolMode` (`ALGO` table vs `BIT7`), 7-bit vs 8-bit TL by profile. |
| DAC | `Track` of type `DAC` + `Ym2612Chip` DAC streamer (`playDac`/`stopDac`, `dacPeriod(baseCycles, rate)`, `0x2A` strobes through the same bus model) | The Z80 DAC loop is modelled inside the chip facade, not in the sequencer. |
| PSG envelopes | `processPsgEnvelope` (`0x80` per `PsgEnvCmd80`, `0x81` hold, `0x82` loop, `0x83` stop, `0x84` consumed) | `envData/envPos/envValue/envHold/envAtRest`. |
| FM volume envelopes (S3K `FF 06`) | `processFmVolEnvelope` | `fmVolEnvData/Pos/Value/Hold/OpMask`. |
| Coordination flags | `SmpsSequencer.handleFlag` (shared `E0–F9`, `FD` custom fade-out) after `CoordFlagHandler` first refusal; S3K handler takes everything | Param lengths: handler → config overrides → static table. |
| Tempo | `SmpsSequencer.processTempoFrame` by `TempoMode` | `TIMEOUT` (S1), `OVERFLOW2` (S2), `OVERFLOW` (S3K) + `speedMultiplier`/`speedupTimeout` double-update. |
| Speed shoes | `speedShoes` + `getSpeedUpTempos()` (S1/S2) or `speedMultiplier` (S3K) | Selected by `GameAudioProfile.getSpeedMode()`. |
| Fades | `FadeState` (steps, delayInit, delayCounter, addFm, addPsg, active, fadeOut) processed in `processObservedFadeStep` before the tempo decision | `triggerFadeIn/Out`, `E4`, `FD`, `onFadeComplete` callback (unblocks SFX). |
| Pause | Not modelled in the driver. `OuterFramePresentation` presents `SILENT` frames (no `present` of voices, clock still advances); `AudioManager.pause/resume` only pause the sink and emit lifecycle events. | ROM `f_pausemusic` / `zPauseFlag` / `PauseMusic` have no engine counterpart. |
| SFX priority | `SmpsSequencer.sfxPriority/specialSfx` + `SmpsDriver.shouldStealLock` | Per-channel arbitration: music always loses; special class loses to normal; bit 7 = transient; higher wins; equal → newer sequencer wins. |
| Continuous SFX | `SmpsDriver.continuousSfxId/Flag/contSfxLoopCnt` + `Sonic3kCoordFlagHandler.handleContSfx` | Extension path `prepareContinuousSfxExtension`. |
| Communication byte (`E2`) | `SmpsSequencer.commData` | Not exposed to gameplay. |
| Ring speaker toggle | `AudioManager.ringLeft` (+ `ResetRingAlternation` command) | Mirrors `Sound_ChkRing` / `zPlaySound_CheckRing`. |
| Music override stack (1-up / invincibility) | `AudioVoiceRegistry` music slot + override stack, `AudioPresentationSnapshot.overrideStack` | The ROM's `v_1up_ram_copy` / `zTracksSaveStart` block copy is modelled as a second live `SmpsDriver` voice, not as a RAM copy. |

---

## 2. Per-track state: engine `Track` versus the ROM track RAM

### 2.1 ROM structs (from the disassemblies)

S1 `SMPS_Track` (`s1.sounddriver.ram.asm`): `PlaybackControl, VoiceControl, TempoDivider,
(pad), DataPointer(l), Transpose, Volume, AMSFMSPan, VoiceIndex, VolEnvIndex,
StackPointer, DurationTimeout, SavedDuration, SavedDAC/Freq(w), NoteTimeout,
NoteTimeoutMaster, ModulationPtr(l), ModulationWait, ModulationSpeed, ModulationDelta,
ModulationSteps, ModulationVal(w), Detune, PSGNoise/FeedbackAlgo, VoicePtr(l),
LoopCounters(3 longs)/GoSubStack`.

S2 `zTrack` (`s2.sounddriver.asm`): `PlaybackControl, VoiceControl, TempoDivider,
DataPointerLow/High, Transpose, Volume, AMSFMSPan, VoiceIndex, VolFlutter, StackPointer,
DurationTimeout, SavedDuration, FreqLow/High, NoteFillTimeout, NoteFillMaster,
ModulationPtrLow/High, ModulationWait, ModulationSpeed, ModulationDelta,
ModulationSteps, ModulationValLow/High, Detune, VolTLMask, PSGNoise, VoicePtrLow/High,
TLPtrLow/High, LoopCounters($A)`.

S3K `zTrack` (`Z80 Sound Driver.asm:21-95`): `PlaybackControl, VoiceControl,
TempoDivider, DataPointerLow/High, Transpose, Volume, ModulationCtrl, VoiceIndex,
StackPointer, AMSFMSPan, DurationTimeout, SavedDuration, FreqLow/High, VoiceSongID,
Detune, Unk11h, (5 unused), VolEnv, FMVolEnv|HaveSSGEGFlag, FMVolEnvMask|SSGEGPointerLow,
PSGNoise|SSGEGPointerHigh, FeedbackAlgo, TLPtrLow/High, NoteFillTimeout, NoteFillMaster,
ModulationPtrLow/High, ModulationValLow|ModEnvSens, ModulationValHigh, ModulationWait,
ModulationSpeed|ModEnvIndex, ModulationDelta, ModulationSteps, LoopCounters(2),
VoicesLow/High, Stack_top(4)`.

### 2.2 Engine `SmpsSequencer.Track` fields (all captured by `SmpsTrackSnapshot`)

`pos, type, channelId, duration, note, active, overridden, rawDuration, scaledDuration,
fill, fillCounter, resting, keyOffset, volumeOffset, tieNext, pan, ams, fms, voiceData,
voiceScratch, voiceId, baseFnum, baseBlock, loopCounters[8+], loopTarget, returnStack[16],
returnSp, dividingTiming, modDelay, modDelayInit, modRate, modDelta, modSteps,
modStepsFull, modPendingDelayInit, modPendingRate, modPendingDelta, modPendingSteps,
modPendingStepsFull, modRateCounter, modStepCounter, modAccumulator, modCurrentDelta,
modEnabled, customModEnabled, detune, modEnvId, modEnvData, modEnvPos, modEnvMult,
modEnvCache, modEnvHold, rawFreqMode, rawFrequency, instrumentId, noiseMode,
psgNoiseParam, decayOffset, decayTimer, envData, envPos, envValue, envHold, envAtRest,
fmVolEnvData, fmVolEnvPos, fmVolEnvValue, fmVolEnvHold, fmVolEnvOpMask, forceRefresh,
ssgEg[4], dacMuted, modStepInEffect, modStepChanged, modStepDelta, forceModulationWrite,
modEnvStepInEffect, modEnvStepChanged, modEnvStepDelta`.

### 2.3 Field-by-field mapping

| ROM field | Engine representation | Note |
|---|---|---|
| `PlaybackControl` bit 7 (playing) | `active` | |
| bit 2 (SFX overriding) | `overridden` | Set by `SmpsDriver.updateOverrides` on the music sequencer only. |
| bit 1 (resting, S1/S2) | `resting` | Maintained on the `direct68kDriver` path; Z80 path derives rest from `note == 0x80`. |
| bit 4 (do-not-attack) | `tieNext` | Cleared per driver family (`clearTransientNoAttack`). |
| bit 3 (modulation on, S1/S2) / `ModulationCtrl` (S3K) | `modEnabled`, `customModEnabled` | S3K `ModulationCtrl` value byte itself (which of custom/envelope modes) is split across two booleans + `modEnvId`. |
| other bits (pause-locked, etc.) | — | Not represented. |
| `VoiceControl` | `type` + `channelId` (+ `noiseMode`) | Hardware byte is recomputed at write time (`chVal`, `0x80|ch<<5`). |
| `TempoDivider` | `dividingTiming` | |
| `DataPointer` | `pos` (program-relative offset) | ROM absolute/Z80 address = `pos + z80StartAddress` (Z80) or program-base (68k). |
| `Transpose` | `keyOffset` | signed |
| `Volume` | `volumeOffset` | S3K clamps 0..0x7F in the handler; S1/S2 8-bit add. |
| `AMSFMSPan` | `pan`, `ams`, `fms` | Recomposed as `(pan&0xC0)|(ams<<4)|fms`. |
| `VoiceIndex` | `voiceId` (FM/DAC) / `instrumentId` (PSG) | |
| `VolEnvIndex` (S1) / `VolEnv` (S3K) / envelope cursor | `envPos`, `envValue`, `envHold`, `envAtRest` | Engine keeps the decoded value; ROM keeps the index. |
| `VolFlutter` (S2) | `envPos`/`decayOffset` | `decayOffset/decayTimer` are set but the only writer is note-on reset. |
| `StackPointer` + `GoSubStack`/`Stack_top` | `returnSp`, `returnStack[16]` | ROM stack overlaps `LoopCounters`; engine keeps them separate. |
| `DurationTimeout` | `duration` | |
| `SavedDuration` | `scaledDuration` (`rawDuration` = pre-scale byte) | |
| `SavedDAC` / `Freq` | `note` (DAC) / `baseFnum`+`baseBlock` (FM) / `baseFnum` (PSG); `rawFrequency` under `FD` | Engine stores detune-free base; ROM stores the detuned/modulated word in some paths (open question 5). |
| `NoteFillTimeout` / `NoteTimeout` | `fillCounter` (68k path) / derived from `fill+duration-scaledDuration` (Z80 path) | |
| `NoteFillMaster` | `fill` | |
| `ModulationPtr` | `modPending*` (copied values) | Engine copies the 4 bytes at `F0`, ROM keeps the pointer and re-reads. |
| `ModulationWait/Speed/Delta/Steps` | `modDelay/modRate/modDelta/modSteps` (+ `*Init`, `*Full`, counters) | Engine splits ROM's single decrementing byte into init + counter pairs. |
| `ModulationVal` | `modAccumulator` (short), `modCurrentDelta` | |
| `ModEnvSens/ModEnvIndex` (S3K) | `modEnvMult`, `modEnvPos`, `modEnvCache`, `modEnvHold`, `modEnvData` | |
| `Detune` | `detune` | |
| `PSGNoise` | `psgNoiseParam`, `noiseMode` | |
| `FeedbackAlgo` | `voiceData[0]` | Not a separate field. |
| `VoicePtr` / `Voices` / `TLPtr` | `voiceData` (materialised copy) | No pointer retained; `voiceSongId` only exists as the sequencer-level `fallbackVoiceData`. |
| `VoiceSongID` (S3K) | — (sequencer `fallbackVoiceData`) | |
| `VolTLMask` (S2) | recomputed from `voiceData` algorithm each write | |
| `FMVolEnv/FMVolEnvMask` (S3K) | `fmVolEnvData/Pos/Value/Hold/OpMask` | |
| `HaveSSGEGFlag/SSGEGPointer` (S3K) | `ssgEg[4]` values | |
| `LoopCounters` | `loopCounters[8]` (grows to 256) | ROM has 2 (S3K), 10 (S2), 12 (S1) bytes and overlaps the stack. |
| `Unk11h`, unused 12h–16h | — | |
| — | `dacMuted`, `forceRefresh`, `rawFreqMode`, `loopTarget`, `modStep*`/`modEnvStep*` scratch, `voiceScratch` | Engine-only. |

### 2.4 Driver-global state

| ROM variable (S1 / S2 / S3K) | Engine |
|---|---|
| `v_main_tempo` / `CurrentTempo` / `zCurrentTempo` | `SmpsSequencer.normalTempo` (raw) and `tempoWeight` (after speed-up/PAL) |
| `v_main_tempo_timeout` / `TempoTimeout` / `zTempoAccumulator` | `SmpsSequencer.tempoAccumulator` |
| `v_speeduptempo`, `f_speedup` / `TempoTurbo`, `SpeedUpFlag` / `zTempoSpeedup`, `zSpeedupTimeout` | `speedShoes` + config map / `speedMultiplier`, `speedupTimeout` |
| `v_sndprio` / `SFXPriorityVal` / (S3K: none, `zID_PriorityList` unused) | Not a driver global: priority lives per SFX sequencer; `SmpsRequestAdmissionPolicy` carries `priorityBefore/After` diagnostically |
| `v_fadeout_counter`, `v_fadeout_delay`, `f_fadein_flag`, `v_fadein_delay/counter` / same / `zFadeOutTimeout`, `zFadeDelay`, `zFadeDelayTimeout`, `zFadeInTimeout` | `FadeState` (one struct for both directions) |
| `v_communication_byte` / `Communication` / — | `commData` |
| `f_pausemusic` / `zPauseFlag`, `zHaltFlag` | — (presentation `SILENT` mode) |
| `v_soundqueue0..2`, `v_sound_id` / `Queue0..2`, `QueueToPlay` / `zSoundQueue0..2`, `zMusicNumber`, `zSFXNumber0/1`, `zNextSound` | `AudioCommandTimeline` entries + `AudioPresentationCommandQueue` (applied at the outer frame boundary, not cycled one-per-update) |
| `f_1up_playing`, `v_1up_ram_copy` / `1upPlaying`, `zTracksSaveStart` / `zTracksSaveStart`, `z*Save` | `AudioVoiceRegistry` override stack of live drivers |
| `v_ring_speaker` / `zRingSpeaker` | `AudioManager.ringLeft` |
| `f_updating_dac` / `DACUpdating` / `zUpdatingSFX` | implicit (`sfxMode` per sequencer) |
| `zContinuousSFX`, `zContinuousSFXFlag`, `zContSFXLoopCnt` | `SmpsDriver.continuousSfxId/continuousSfxFlag/contSfxLoopCnt` |
| `zSpindashRev` | `SmpsCoordFlagRuntimeState.spindashRevCounter` |
| `zSFXTempoDivider`, `zSFXVoiceTblPtr` | per-SFX-sequencer `dividingTiming`, `fallbackVoiceData` |
| `zPalFlag`, `zPalDblUpdCounter` / `IsPalFlag`, `zPALUpdTick` | `Region.PAL` → `tempoWeight *= 1.2` (engine models PAL as a tempo multiplier, not as the ROM's every-Nth-frame double update) |
| `zFM3Settings`, `zSpecFM3Freqs` | — (`FE` consumed) |
| `v_voice_ptr`, `v_special_voice_ptr` / `VoiceTblPtr` / `zVoiceTblPtr` | `AbstractSmpsData.voicePtr` per program |

---

## 3. Update cadence

### 3.1 ROM

- **S1**: `VBlank_Music` and the delayed-transfer path both `jsr UpdateMusic`
  (`sonic.asm:682`, `:1062`) — one 68k driver pass per V-blank. `UpdateMusic` decrements
  `v_main_tempo_timeout`; on zero `TempoWait` reloads it from `v_main_tempo` and adds 1 to
  every music track's `DurationTimeout` (`s1.sounddriver.asm:1549-1559`); then fades,
  queue, and the track loop run every frame.
- **S2**: Z80 `zVInt` → `zUpdateEverything` (fade-out, fade-in, queue cycle, play, spindash
  counter, PAL tick) → `zUpdateMusic` → `TempoWait`: `TempoTimeout += CurrentTempo`;
  on **carry** it returns, otherwise it increments every music track's `DurationTimeout`
  (`s2.sounddriver.asm:596-613`). SFX tracks are updated every frame irrespective of tempo.
- **S3K**: Z80 `zVInt` → `zUpdateEverything` (`zPauseUnpause`, `zUpdateSFXTracks`) →
  `zUpdateMusic` → `TempoWait`: `zTempoAccumulator += zCurrentTempo`; on **no carry**
  it returns, on carry it increments every music track's `DurationTimeout`
  (`Z80 Sound Driver.asm:2607-2620`). PAL: after the update, `zPalDblUpdCounter` counts
  down and every sixth frame (`SonicDriverVer==3 && fix_sndbugs=0`) the whole update is
  run again (`:470-500`). Speed-up: after the track loop, `zSpeedupTimeout` counts down
  from `zTempoSpeedup` and on expiry `zUpdateMusic` is jumped to a second time
  (`:743-757`). Note that in both Z80 drivers the track loop still runs on a "delay"
  frame; only `DurationTimeout` is pre-incremented.

### 3.2 Engine

1. `GameLoop` calls `AudioManager.beginGameplayAudioFrame(frame)` once per gameplay tick
   (stamps the command timeline; clamps monotonic).
2. `OuterFramePresentation.present(...)` is the single production entry that calls
   `AudioManager.presentFrame(mode)` once per presented outer frame (`GameLoop:784-794`,
   `HeadlessGameBoot:305`, `SoundTestApp` timer at 16 ms).
3. `AudioPresentationProducer.present`: `stereoFrames = AudioFrameClock.samplesForNextFrame()`
   (48000/60 → 800; 44100/60 → 735 with remainder carry). In `FORWARD` mode it first applies
   every pending `AudioPresentationCommand` (this is where `PlaySfx`/`PlayMusic` requests
   reach a driver, i.e. the engine's analogue of the sound queue is drained once per outer
   frame, before rendering), then `mixer.mix(registry, stereoFrames)`.
4. Each `SmpsCompositeVoice.mixInto` calls `SmpsDriver.read(scratch, stereoFrames*2)`.
5. `SmpsDriver.readHybrid` splits the request into chunks no longer than
   `min(getSamplesUntilNextTempoFrame() - 1, getSamplesUntilNextObservableEvent() - 1)`
   across all sequencers (single-sample fallback below `MIN_BATCH_SAMPLES = 32` or when a
   sequencer has `speedMultiplier > 1`). Per chunk: `advanceSequencersBatch(n)` then
   `renderChunk`.
6. `SmpsSequencer.advanceBatch(n)`: `sampleCounter += n`; while
   `sampleCounter >= samplesPerFrame` (`sampleRate / region.frameRate`, a double) run
   `processTempoFrame()`. So the sequencer's "frame" is a 1/60 s (or 1/50 s) sample-domain
   period that is phase-independent of the outer presentation frame: it starts at
   sequencer construction and drifts by the fractional remainder, whereas the ROM's driver
   pass is locked to V-blank.
7. `processTempoFrame` = `processObservedFadeStep` (fade) then the tempo decision:
   `TIMEOUT` ticks every frame and extends durations (S1 shape); `OVERFLOW2` ticks only on
   accumulator overflow; `OVERFLOW` ticks only when the accumulator does **not** overflow
   (plus the S3K `speedupTimeout` extra tick). SFX sequencers tick every frame in all
   modes. The first `read` primes: `TIMEOUT`/`OVERFLOW` run one `processTempoFrame`
   immediately (`tempoOnFirstTick`), `OVERFLOW2` runs one `tick()`.
8. `tick` → `SmpsDriver.beginSequencerService(SEQUENCER_TICK)` → `tickTracks()` (per
   track: duration decrement, note-fill, PSG/FM envelope, modulation, then stream
   interpretation until a note/rest is issued) → `endSequencerService` which hands the
   observer a full `SmpsDriverSnapshot`.
9. Chip writes: `synth.writeFm/writePsg/playDac` go to `SmpsDriver` (lock arbitration) →
   `VirtualSynthesizer` → `Ym2612Chip.write` (reports to `ChipWriteObserver`, enqueues) /
   `PsgChip.write` (reports, applies at the chip's current time). Queued YM writes are
   applied in order before the next rendered sample with a bus-pacing model (address
   strobe, 32-cycle busy hold, `DATA_SETTLE_CYCLES`), so every write issued during one
   tick lands at the start of the next rendered chunk and is spaced only by the chip's
   busy model, never by Z80/68k instruction timing between writes.
10. `AudioPresentationSink` (`OpenAlPcmSink` or `NoDeviceAudioSink`) receives one packet
    per outer frame; `PcmHistoryRing` records it for reverse presentation.

Pause: `OuterFramePresentation.modeFor` yields `SILENT`; no sequencer advances, no chip
write is issued. Music restore/override, speed changes and tempo changes are all commands
applied at step 3.

Music-override cadence: `PushMusicOverride` creates a second `SmpsDriver` (own chip pair)
for the 1-up/invincibility program; the interrupted driver keeps its state but is not
mixed; `RestoreMusicOverride` resumes it. The ROM instead copies track RAM
(`v_1up_ram_copy`, `zTracksSaveStart`) and the restored song replays through the same
chips after `zFadeInToPrevious`/`DoFadeIn`.

---

## 4. Per-game differences: config/profile versus branching

### 4.1 Carried by `SmpsSequencerConfig` (no game names in the sequencer)

`tempoMode`, `tempoModBase`, `fmChannelOrder`, `psgChannelOrder`, `speedUpTempos`,
`coordFlagParamOverrides`, `extraTrkEndFlags`, `relativePointers`, `tempoOnFirstTick`,
`direct68kDriver`, `fmSfxTakeoverMode`, `fmVoiceWriteProfile`, `volMode`, `psgEnvCmd80`,
`noteOnPrevent`, `delayFreq`, `modAlgo`, `applyModOnNote`, `halveModSteps`,
`fadeOut/InSteps/Delay`, `coordFlagHandler`.

Use counts inside `SmpsSequencer` (grep at `f087b8947`): `isDirect68kDriver` 14,
`getTempoMode` 6, `getFmVoiceWriteProfile` 4, `getVolMode` 4, `isApplyModOnNote` 4,
`getCoordFlagHandler` 3, `getDelayFreq` 2, `getPsgEnvCmd80` 1, `getModAlgo` 1,
`isHalveModSteps` 1, `isRelativePointers` 1, `isTempoOnFirstTick` 1,
`getExtraTrkEndFlags` 1, `getCoordFlagParamOverrides` 1, `getSpeedUpTempos` 1,
**`getNoteOnPrevent` 0** and **`getFmSfxTakeoverMode` 0 in the sequencer** (the latter is
read by `SmpsDriver.usesForcedFmTakeover`; the former is declared but unread — see open
question 6). There is no `instanceof SonicN*`, no game-id string, and no music/zone id
branch in `SmpsSequencer` or `SmpsDriver`.

### 4.2 Carried by `GameAudioProfile` / `AbstractAudioProfile`

Sound and music id maps, loader factory, `getSequencerConfig`, command ids
(fade-out, stop-all, SEGA, stop-SEGA, speed up/down), `SegaPcmSpec`, `getSpeedMode`
(`TEMPO_TABLE` vs `FRAME_MULTIPLY`) + multiplier value, `isMusicOverride`,
`isSfxBlockingMusic`, `blocksSfxDuringMusicRestoreFadeIn`, `getSfxPriority`,
`getSfxAdmissionPolicy`, `isContinuousSfx`, `isSpecialSfx`, `adjustSfxPitch`,
`handleSystemCommand`, `presentationGameId`, `configurePresentationCoordFlagHandlers`.

### 4.3 Carried by a game handler

`Sonic3kCoordFlagHandler` (whole `E0–FF` table). S1 and S2 have no handler: their
flag semantics are the shared `handleFlag` switch plus the `ED`/`EE` config overrides.

### 4.4 Residual branching that is *family*-shaped rather than *game*-shaped

`direct68kDriver` is a boolean for "S1" in practice (S2's Z80 driver is 68k-derived but is
configured `false`), and it gates fourteen sites: note-fill counting, rest bookkeeping,
`tieNext` clearing, TL carry-skip, instrument TL adjust, pan-on-note, key-on-under-hold,
PSG rest envelope priming, modulation-at-rest, FM frequency byte write. `TempoMode` is a
three-way enum with S1/S2/S3K semantics. Both are legitimate profile knobs, but they are
where a fourth driver variant would first need new enum members rather than data.

### 4.5 Data that is Java-resident rather than ROM-read

- SFX priority tables (`Sonic1SmpsConstants.SOUND_PRIORITIES`, `Sonic2SmpsConstants.SFX_PRIORITY_TABLE`).
- Speed-up tempo maps (`SonicNSmpsSequencerConfig.SPEED_UP_TEMPOS`).
- S2 PSG envelopes (`Sonic2PsgEnvelopes`).
- FM/PSG frequency tables (`SmpsSequencer.FNUM_TABLE_68K/Z80`, `PSG_FREQ_TABLE_68K/Z80_T2`).
- Fade constants (`fadeOutSteps=0x28`, etc.) and `DacData.baseCycles`.

These are driver tables, not art, so hard rule 1 as written does not bind them; they are
listed because a RAM-shaped comparison that includes `CurrentTempo`, `SFXPriorityVal`, or
envelope cursors will read the ROM's copy on the reference side and the Java copy on the
engine side, and the two must be verified equal once (the S1 sound-priority ROM address is
already declared as `SOUND_PRIORITIES_ADDR`).

---

## 5. Extension points for a driver-RAM-shaped comparison

### 5.1 What already exists

- **Invocation boundary.** `SmpsDriverServiceObserver.onServiceEnd(ServiceEvent, SmpsDriverSnapshot)`
  fires after every `tickTracks()` and every fade step, with a full driver snapshot
  (all sequencers, all tracks, locks, continuous state, synth). `ServiceEvent` carries a
  monotonically increasing ordinal, the driver identity (music vs SFX origin, admission
  ordinal, sound id) and the sequencer identity. Installed via
  `AbstractSmpsAudioBackend.setDriverServiceObserver` / `AudioPresentationSourceFactory`.
  This is the engine's equivalent of "after `UpdateMusic` / `zUpdateEverything`".
- **Chip-write stream.** `ChipWriteObserver` sees every YM/PSG byte in issue order; the
  complete-run tooling already pairs it with state (`CompleteRunAudioTrace.YmWrite/PsgWrite`).
- **Snapshot records.** `SmpsTrackSnapshot` copies every `Track` field; `SmpsSequencerSnapshot`
  copies every sequencer scalar (tempo weight/accumulator, fade, sample counter, priority,
  speed); `SmpsDriverSnapshot` adds locks and continuous-SFX state.
- **An S1 field registry.** `tools/audio/parity/S1AudioFieldRegistry` already names 29
  fields as `"<romName>" ← SmpsTrackSnapshot.<field>` (e.g. `transpose ← keyOffset`,
  `durationReload ← scaledDuration`, `doNotAttack ← tieNext`, `returnStack ←
  returnStack[0..returnSp)`), and `S1OpenGgfAudioCapture` samples `sequencer.captureSnapshot()`
  after each `advanceBatch(NTSC_SAMPLES)`.
- **Reference-side normalisers for all three games.** `S1/S2/S3kCompleteRunStateNormalizer`
  each define `GLOBAL_FIELDS` and `ACTIVE_ROLE_FIELDS` in ROM vocabulary
  (S3K: 37 globals + 38 per active role, including `playbackControl`, `voiceControl`,
  `tempoDivider`, `cursor`, `stackPointer`, `sharedStackStorage`, `voiceSongId`) and
  decode the reference RAM into `CompleteRunAudioTrace.NormalizedState`.

### 5.2 Gaps (adaptation points)

1. **No engine-side producer of the ROM-vocabulary `NormalizedState`.**
   `S3kCompleteRunStateNormalizer.normalizeEngine` has no caller in `src/main`; only
   `normalizeReference` is wired (from `S3kCompleteRunReferencePreflight`). The S2 and S1
   complete-run normalisers likewise consume decoded reference RAM. The adaptation is a
   mapping `SmpsDriverSnapshot → S3kCompleteRunStateNormalizer.Snapshot` (and the S1/S2
   equivalents) that reuses the `S1AudioFieldRegistry` idea per game, attached to
   `onServiceEnd`.
2. **Fields the engine does not keep and would have to synthesise or declare
   non-comparable:** `PlaybackControl` as one byte (bits 0, 5, 6 and any pause-lock bit),
   `VoiceControl` byte, `VoiceSongID`, `VoicesLow/High` / `VoicePtr` / `TLPtr` (pointer to
   the voice table; engine holds a copy), `VolTLMask`, `FeedbackAlgo` (derivable from
   `voiceData[0]`), the modulation pointer (engine copies the four bytes), the ROM's
   envelope *index* vs engine's decoded `envValue`, `Unk11h`, `ModulationCtrl` as a byte,
   `HaveSSGEGFlag`/`SSGEGPointer` (engine keeps the four values, not the pointer),
   `zFM3Settings`/`zSpecFM3Freqs`, `zPauseFlag`/`zHaltFlag`, the sound queue bytes and
   `zNextSound`. Each needs an explicit `derived`, `engine-only`, or `not-compared`
   classification in the field registry rather than an ad-hoc default.
3. **Coordinate systems.** `Track.pos` is program-relative; ROM `DataPointer` is a Z80
   address (S2/S3K) or 68k address (S1). The S3K normaliser already expresses cursors as
   `(assetKey, pointer - romBase)`, so the engine mapping needs `SmpsSourceDescriptor` +
   `z80StartAddress` to produce the same tuple. Loop counters and return stacks are
   physically separate arrays in the engine but one overlapping region in the ROM
   (`sharedStackStorage` in the S3K normaliser); the mapping has to re-pack them.
4. **Boundary phase.** The ROM boundary is "after one V-blank driver pass"; the engine
   boundary is "after one `tickTracks`", which on `OVERFLOW`/`OVERFLOW2` delay frames does
   not fire at all, and which is phase-free relative to the outer frame (section 3.2 step
   6). A comparison keyed by driver invocation needs either a per-frame `ServiceKind`
   (emit an event even when the tempo gate skips the tick, carrying the same snapshot) or
   a normaliser that tolerates missing engine rows on delay frames. Neither exists.
5. **Delay-frame side effects.** On a ROM delay frame (S2 `TempoWait` no-carry, S3K
   `TempoWait` carry) the track loop still executes with `DurationTimeout` pre-incremented,
   so sustain-phase work (`zDoFMVolEnv`, `zDoModulation`, PSG envelope, note-fill
   countdown) advances. The engine's `OVERFLOW`/`OVERFLOW2` branches skip `tick()`
   entirely on those frames. A RAM comparison of `ModulationVal`, `VolEnv` cursor, or
   `NoteFillTimeout` will diverge on every delay frame unless the engine models "tick with
   duration extended" (the shape it already uses for S1 `TIMEOUT`). Verify by measurement
   before changing (open question 1).
6. **PAL.** The ROM runs the whole update twice every sixth frame (S3K) / by
   `zPALUpdTick` (S2); the engine scales `tempoWeight` by 1.2. A PAL RAM comparison
   cannot line up; NTSC-only until a double-update model exists.
7. **Priority as driver RAM.** `SFXPriorityVal`/`v_sndprio` is a single global that gates
   admission of the *next* request in S1/S2; the engine stores priority per SFX sequencer
   and arbitrates per channel lock. `SmpsRequestAdmissionPolicy.SmpsAdmissionContext`
   already carries `priorityBefore/After`, so the global can be reconstructed for the
   comparison from admission decisions rather than from driver state.
8. **Queue bytes.** The engine drains all pending commands at the outer-frame boundary in
   submission order; the ROM cycles at most three queue entries per update and clears
   `zSFXNumber0/1` under the 1-up rule (`Z80 Sound Driver.asm:665-690`). To compare
   `queue0..2`/`nextSoundId` the engine would need to expose the pending
   `AudioPresentationCommandQueue` contents at the boundary.
9. **Override stack.** ROM `savedMusic` fields (`zTracksSaveStart`, `v_1up_ram_copy`)
   are the interrupted song's RAM; the engine's interrupted song is a parked live
   `SmpsCompositeVoice`. Its `SmpsDriverSnapshot` is available through
   `AudioPresentationSnapshot.overrideStack` → registry voice snapshot, so the mapping can
   populate `SavedMusic` from a second driver snapshot rather than from a RAM copy.
10. **Guard boundaries to respect.** `TestCompleteRunAudioAuthorityGuard` forbids
    production code outside `tools/` from importing complete-run capture authority and
    requires OpenGGF producers to reject reference readers; `TestAudioTimelineAuthorityGuard`
    forbids timeline producers from calling audio mutation; `TestHardwareTimingAuthorityGuard`
    keeps trace-driven admission out of gameplay owners. An engine-side normaliser belongs in
    `tools/audio/completerun/<game>/` and must consume only `SmpsDriverSnapshot` + asset
    catalog, never the reference decoder.

---

## 6. Open questions (disassembly not yet read for these)

1. Do the S2/S3K track loops advance modulation, envelope and note-fill state on a tempo
   delay frame exactly as on a normal frame (i.e. is the only effect of `TempoWait` the
   `DurationTimeout` increment)? Read `zTrackUpdLoop`/`zFMUpdateTrack` around
   `Z80 Sound Driver.asm:730-745` and `s2.sounddriver.asm` `zUpdateMusic` before changing
   the engine's skip-tick model.
2. S3K: is there any SFX priority gate in `zPlaySound`/`zCycleSoundQueue`
   (`zID_PriorityList = 0 ; unused` suggests none), and does "new SFX always replaces on
   its channels" match the engine's equal-priority "newer wins" plus channel-conflict kill?
3. S2 `SFXPriorityVal` semantics versus the engine's per-channel arbitration: does the ROM
   reject a lower-priority *request* outright (no track init) rather than per channel?
4. What exactly does `zPauseUnpause`/`PauseMusic` write (key-offs, PSG silence, DAC) and is
   the track RAM left untouched, so that an engine pause model could be "no tick +
   silence writes" rather than presentation-level silence?
5. Which of the ROM `Freq` stores hold the detuned/modulated word versus the base note
   (S1 `FMPrepareNote`, S3K `zFMUpdateFreq`): needed to decide whether `baseFnum/baseBlock`
   or a reconstructed `baseFnum + modAccumulator + detune` is the comparable value.
6. `SmpsSequencerConfig.noteOnPrevent` is set to `HOLD` for S3K but never read; what ROM
   behaviour was it meant to select and is that behaviour currently produced elsewhere?
7. S3K `zPalDblUpdCounter` reload value (6 under `SonicDriverVer==3 && fix_sndbugs=0`,
   else 5) — confirm which the locked-on ROM takes (the `SonicDriverVer` the engine's
   loader targets).
8. Does the 68k S1 driver's `UpdateMusic` run twice on a lag frame (`VBlank_Lag` path) or
   never? `sonic.asm:1062` is a second call site in the delayed-transfer path; its frame
   relationship to `VBlank_Music` was not traced here.
