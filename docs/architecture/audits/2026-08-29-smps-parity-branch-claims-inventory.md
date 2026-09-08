# `feature/ai-smps-transaction-parity` claims inventory

- Date: 2026-08-29
- Branch tip: `83723907d5705aa4cab9bda850982f5c434b3ff7` (`83723907d`, "Merge branch 'develop' into feature/ai-smps-transaction-parity")
- Merge base with `develop`: `7039887187948f86089e39a9f0fff0f17b26bdab` (`703988718`)
- `develop` at inventory time: `d41de71a0140e82b073b0ddc9adda2095d82bc1b` (`d41de71a0`)
- Inventoried in worktree `.worktrees/ss-ring-pan` (`bugfix/ai-s3k-ss-ring-pan`, based on
  `feature/ai-audio-mix-calibration`, which carries the Nuked-OPN2 `Ym2612Chip` +
  `NukedOpn2` and the clean-room `PsgChip`).
- Commit count: `git log --oneline develop..feature/ai-smps-transaction-parity` = 95
  (93 non-merge + 2 merges).

## Framing

The branch was reverted for 0.6 because it produced audible regressions its tests did
not catch. **It is not a source of fixes.** Its driver-level changes are not trusted and
will not be re-landed; each game's Z80 sound driver will be reverse-engineered properly
from the driver source later. This document is an inventory of the *behaviour claims*
the branch made about the shipped drivers, with the ROM routine each claim purports to
model, so the future driver reverse-engineering can derive each behaviour from the
driver source and settle it there. Nothing here asserts that any implementation on the
branch is correct.

## Classes

| Class | Meaning | Count |
|---|---|---|
| (a) | Chip-level, now moot because the new Nuked-OPN2 `Ym2612Chip` / clean-room `PsgChip` models it natively | **0** (see "Chip-level commits are not moot") |
| (b) | Driver / sequencer / presentation level; a claim about shipped driver behaviour | **23** |
| (c) | Tooling, infra, tests, docs, merges only | **72** |
| (d) | Superseded or reverted wholesale within the branch | **0** whole commits; 6 partial supersessions noted below |

Class (c) breaks down as 2 merge commits, 16 docs-only commits (all rewriting the same
two files under `docs/architecture/{designs,plans}/audio/2026-08-13-cross-game-smps-semantic-transaction-parity-*.md`),
and 54 tooling/test commits (BizHawk headless C#/native observer, `gpgx-audio-*`
fixtures, and the Java `com.openggf.tools.audio.completerun` comparator plus its tests).
No (c) commit touches `src/main/java/com/openggf/audio` runtime code; verified by
listing every commit's `src/main` paths outside `com/openggf/tools/audio`.

None of the 23 (b) changes is present in this worktree: `grep` under `src/main` for
`PalServicePolicy`, `sfxPriorityLatch`, `DriverServiceOrder`, `initializeSpeedShoes`,
`loadModEnvelopeFromData`, `OrdinaryMusicSfxPolicy`, `PsgSfxReleaseMode`,
`FadeOutChannelPolicy`, `MusicOverrideSpeedPolicy`, `SfxRequestTransformPolicy`,
`FadeInChannelPolicy`, `PausePolicy`, `advancePausedHardware`,
`MusicOverridePriorityPolicy`, `MusicOverrideDacRestorePolicy`,
`requireSaxmanPayloadLength`, `requirePsgEnvelope`, `Z80_MOD_ENVELOPE_COUNT`,
`adoptActiveSfxFrom`, `ChipClockProfile` all return 0 hits.

## Chip-level commits are not moot

Three commits touch `audio/synth`. Each was checked against the new core in this
worktree; these are facts about what the new core does and does not model, not advice
about the branch's implementations.

| Commit | What it changed | New-core state (this worktree) |
|---|---|---|
| `a0e346e41` regional chip clocks | Added `VirtualSynthesizer.ChipClockProfile {NTSC, PAL}` (YM = master/7, PSG = master/15), `Ym2612Chip.setClockRates`, `PsgChip.setInputClock`, snapshot fields; `SmpsDriver.setRegion` switched the profile. | `src/main/java/com/openggf/audio/synth/Ym2612Chip.java:90-93` hard-codes `MASTER_CLOCK_HZ = 7670453.0` and derives `INTERNAL_RATE` from it; `Ym2612Chip.java:183-184` fixes the FM-cycles-per-Z80-cycle ratio to NTSC `(7670453/6)/3579545`; `src/main/java/com/openggf/audio/synth/PsgChip.java:55-59` hard-codes `INPUT_CLOCK_HZ = 53_693_175/15` and `TICK_RATE_HZ`. `grep ChipClockProfile\|setChipClockProfile src/main` = 0 hits. The new core has no PAL clock domain. |
| `8d2f0dcf2` reference chip output defaults | Flipped `audio.dacInterpolate` and `audio.psgNoiseShiftEveryToggle` defaults to `false` (GPGX/libvgm behaviour) in `SonicConfigurationService`, `config.yaml`, `CONFIGURATION.md`, and the chip field defaults. | `src/main/java/com/openggf/configuration/SonicConfigurationService.java:672,676` still default both to `true`; `src/main/resources/config.yaml:56,58` and `CONFIGURATION.md:278,280` document `true`; `Ym2612Chip.java:231` `dacInterpolate = true` with the interpolation path at `Ym2612Chip.java:564`; `PsgChip.java:389` `if (rising \|\| noiseShiftOnEveryToggle)` still honours the every-toggle mode. `CONFIGURATION.md:280` describes `false` as "the documented SN76489 behaviour". The new core did not change these defaults. |
| `4e5e2def3` S3K SEGA PCM through YM DAC | `SampleBackedVoice.rawSegaPcm` gained a `YM2612_DAC` render mode owning a `VirtualSynthesizer`, writing `0x2B=0x80` then streaming each source byte as a `0x2A` write; snapshot carried `renderMode`/`synthSnapshot`/`lastDacSourceFrame`; also added `equals`/`hashCode` to the old `Ym2612Chip.Snapshot`, `ChannelSnapshot`, `PsgChip`, `BlipDeltaBuffer`, `BlipResampler`. | `src/main/java/com/openggf/audio/presentation/SampleBackedVoice.java:53-57` renders SEGA PCM as a host-linear `oneShot` at `YM_DAC_GAIN_Q16`; no `renderMode`. The new core routes SMPS DAC drums through the chip natively (`Ym2612Chip.java:191 DAC_REGISTER = 0x2A`, DAC stream serviced per cycle at `:546-565`, `write()` handles `0x2A/0x2B` at `:629`), but SEGA PCM does not use that path. `Ym2612Chip.Snapshot` is now a record (`:831`), so the branch's hand-written `equals` hunks target code that no longer exists. |

## Ring panning / stereo / special-stage ring SFX

**No commit on the branch addresses ring panning, stereo, or special-stage ring SFX.**

- `git log -i --grep='pan\|ring\|stereo\|special' develop..feature/ai-smps-transaction-parity`
  returns all 93 non-merge commits, which is a false positive: with `-i` the pattern
  `ring` matches "during"/"hardening"/"ordering" and `pan` matches "expand" in the
  commit bodies and trailers. Restricting to subjects gives zero ring/pan/stereo hits.
- `git log -S` for `RingSpeaker`, `zRingSpeaker`, `RING_RIGHT`: zero commits.
  `git log -S ringLeft`: `6cd882b00` and `282a0a794` only, and those touch the
  test-side complete-run alias maps (`CompleteRunAudioTrace.NativeSoundIdentity ringLeft`
  in the comparator tests), not the runtime.
- The whole-branch diff of `AudioManager.java` contains no ring-related hunk; the
  only `ringLeft` line in the runtime diff (`AudioVoiceRegistry.reset`, `ringLeft = true;`)
  is unchanged context.
- The branch tip carries the same bypass as develop: `AudioManager.playSfx(GameSound, float)`
  (worktree `src/main/java/com/openggf/audio/AudioManager.java:1718-1731`) alternates
  `RING_LEFT`/`RING_RIGHT` and toggles `ringLeft` only when `sound == GameSound.RING`,
  while `AudioManager.playSfx(int, float)` (`AudioManager.java:1828-1834`) goes straight
  to `playBaseSfx(baseAudioSource, sfxId, pitch)` with no alternation. The ROM toggles
  `zRingSpeaker` on *every* raw ring request in `zPlaySound_CheckRing`
  (`docs/skdisasm/Sound/Z80 Sound Driver.asm:1919-1925`:
  `sub sfx__First / or a / jp nz,... / ld a,(zRingSpeaker) / xor 1 / ld (zRingSpeaker),a`),
  and resets it to left in the music path at `:547`.

The SS ring-pan bug is therefore independent of this branch in both directions.

## Per-commit table

Order is newest first (as `git log`). For (b) rows the table records only the claim the
commit makes about shipped driver behaviour and the routine it purports to model.
Anchors are in `docs/skdisasm/Sound/Z80 Sound Driver.asm` (5315 lines,
`fix_sndbugs = 0` at line 16) unless another file is named; S1 is
`docs/s1disasm/s1.sounddriver.asm`, S2 is `docs/s2disasm/s2.sounddriver.asm`.

| sha | subject | class | claim (b) / content (c) | purported ROM routine |
|---|---|---|---|---|
| `83723907d` | Merge branch 'develop' into feature/ai-smps-transaction-parity | c | Merge commit. | — |
| `6d393dcf7` | fix(audio): harden retail SMPS catalogs | b | S2 Saxman song payload lengths are little-endian and S2 has exactly four uncompressed playlist entries; every S1 PSG envelope ends in a `$80` hold terminator; S3K has exactly 8 modulation-envelope pointers and `$27` PSG volume-envelope pointers; a DAC table that cannot be read is not a playable catalog. | S2 playlist/Saxman framing (`s2.sounddriver.asm`); S1 PSG envelope table (`s1.sounddriver.asm`); S3K `zID_*` pointer tables used by `zPlaySound_Bankswitch` (`:1928`) — the previous engine comment cited "Mod. Pointer List: 130E (W, 3C)", which disagrees with the commit's 8 |
| `4e5e2def3` | fix(audio): render S3K SEGA PCM through YM DAC | b | The SEGA chant is unsigned PCM streamed byte-by-byte to the YM2612 DAC (`$2B` enable, `$2A` data) at the region's Z80 clock, not a host-rate sample. | `zPlaySEGAPCM` (`:4372`) |
| `a0e346e41` | fix(audio): use regional Mega Drive chip clocks | b | On PAL hardware the YM2612 (master/7) and PSG (master/15) run from the 53,203,424 Hz master clock, so pitch and DAC timing differ from NTSC, not just the VInt rate. | Hardware clocks (no driver routine) |
| `1465d9b5e` | fix(audio): preserve Sonic 1 restore DAC bug | b | S1 `FixBugs=0` does not repair `$2B` (DAC enable) when restoring the displaced song after a 1-up, so the jingle's DAC mode persists and FM6 can stay inaudible. | S1 `cfFadeInToPrevious` (`s1.sounddriver.asm`) |
| `1ad387c2d` | fix(audio): match retail 1-up SFX lifecycle | b | When the 1-up jingle starts all active SFX stop and new SFX are blocked until the driver's restore boundary; S1 clears the saved SFX priority; S2 `FixDriverBugs=0` clears `zSFXPriorityVal` only after LDIR saved it, so the restored song restores the stale latch; S3K reopens SFX immediately on song restore. | S3K `zFadeInToPrevious` (`:2725-2735`); S1/S2 1-up save/restore in `s1.sounddriver.asm` / `s2.sounddriver.asm` |
| `76855b939` | fix(audio): clock DAC through SMPS pause | b | While paused, track service stops but an already-started DAC sample keeps playing; S3K leaves FM6/DAC unmuted. | `zPauseAudio` (`:2541`), `zPauseUnpause` (`:2232`) |
| `9aa3acc35` | fix(audio): apply retail SMPS pause protocols | b | Pause protocols: S1 pans FM1-6 to zero, keys them off and silences PSG; S2 `FixDriverBugs=0` `zFMSilenceAll` destructively writes `$FF` to `$30-$8F` on both ports and voices are reloaded on resume; S3K mutes FM1-5, leaves FM6/DAC running, and calls `zPSGSilenceAll` redundantly. | S3K `zPauseAudio` (`:2541-2560`), `zPSGSilenceAll` (`:2587`, redundant call `:2543`); S1 `PauseMusic`; S2 `zFMSilenceAll` |
| `f266983a3` | fix(audio): make S3K SEGA PCM exclusive | b | Starting the SEGA chant stops music, overrides and every SFX first; `fix_sndbugs=0` StopSEGA leaves the driver silent rather than restoring the discarded voices. | `zPlaySEGAPCM` (`:4372`), `zStopAllSound` (`:2460`) |
| `7ac56b1b4` | fix(audio): fade restored S3K music through FM | b | Restoring the displaced song after a 1-up starts a `$40`-step FM-only fade-in; PSG is not faded. | `zFadeInToPrevious` (`:2725`), `zDoMusicFadeIn` |
| `4f7e23dd7` | fix(audio): match Sonic 2 spindash rev ladder | b | The spindash-rev transpose is owned by the sound driver: S2 keeps a `$3C`-service timeout and a saturating 0-11 semitone index advanced per request; the gameplay charge counter does not set pitch. The commit also asserts an S3K equivalent ("E9"). | S2 `zPlaySound_CheckSpindash` (`s2.sounddriver.asm`); S3K `zPlaySoundByIndex` (`:1641`, `fix_sndbugs=0` block `:1659`) |
| `68d0c38fa` | fix(audio): preserve S3K one-up speed state | b | The S3K 1-up jingle runs at normal speed; the displaced song's speed-up state is saved in `zTempoSpeedupSave` and restored with the song. | `zTempoSpeedupSave` (`:161`, saved `:1751`, restored `:2730`) |
| `d08af3083` | fix(audio): preserve S3K modulation loop bug | b | In the `fix_sndbugs=0` modulation-envelope interpreter, `$82`/`$84` fetch their operand via `INC BC / LD A,(BC)` with BC still holding the envelope index, so the operand comes from low Z80 RAM at index+1, not the envelope data. | `zDoModulation` (`:1279`), `fix_sndbugs` blocks `:1303`, `:1349` |
| `442c3cd70` | fix(audio): match shipped fade lifecycle | b | Fade-out: S1 stops SFX when the fade starts; S3K halts DAC and PSG immediately and fades FM only; S1/S2 clear speed shoes; all three stop on the terminal count without applying a final volume step. | S3K `zFadeOutMusic` (`:2307-2330`, `jp zPSGSilenceAll` `:2323`), `zDoMusicFadeOut`; S1/S2 fade routines |
| `76f9571e1` | fix(audio): match shipped SFX release state | b | When an SFX releases a channel, S1/S2 leave the interrupted PSG music track resting until its next note and S2 performs no synthetic FM takeover reset; S3K restores live state in the same VInt. | S1/S2 SFX-stop paths (`s1.sounddriver.asm`, `s2.sounddriver.asm`); S3K `zUpdateSFXTracks` (`:727`) |
| `644fb6f68` | fix(audio): preserve Sonic 1 SFX across BGM loads | b | S1 `FixBugs=0` `Sound_PlayBGM` keeps normal and special SFX tracks, their channel locks, continuous state and priority latch alive across an ordinary song change and marks their channels as overrides on the new song; S2 and S3K stop SFX before loading ordinary BGM. | S1 `Sound_PlayBGM` (`s1.sounddriver.asm`); S2 `zPlayMusic`; S3K `zPlayMusic` (`:1717`) |
| `10b27e755` | fix(audio): apply signed S3K modulation envelopes | b | Modulation-envelope bytes `$85-$FF` are signed pitch deltas; only `$80-$84` are commands. | `zDoModulation` (`:1279`) |
| `8d2f0dcf2` | fix(audio): default to reference chip output | b | Hardware PSG noise shifts the LFSR on positive edges only and the DAC output is stepped, uninterpolated. | Chip behaviour (GPGX/libvgm reference), no driver routine |
| `0b269a9be` | fix(audio): correct Sonic 2 DPCM cadence | b | S2 `fixBugs=0` `zWriteToDAC` takes 295 Z80 cycles per two decoded samples (not 288). | S2 `zWriteToDAC` / `.dac_playback_loop` (`s2.sounddriver.asm`) |
| `556ab16c0` | fix(audio): preserve Sonic 2 spindash transpose bug | b | The shipped `FixMusicAndSFXDataBugs=0` spindash-release SFX data really contains the invalid `$90` FM5 transpose; the fixed `$10` is not the retail path. | S2 SFX data (`s2disasm/sound/sfx`) |
| `4bd00b064` | fix(audio): match S3K SFX service order | b | S3K services SFX tracks before music each VInt, so an SFX that finishes releases its channel before the same VInt's music update; the PAL repeat keeps that order. | `zUpdateEverything` (`:653-655`), `zUpdateSFXTracks` (`:727`) |
| `2c0844a9f` | fix(audio): enforce shipped SFX priority latch | b | S1 and S2 gate every SFX request through one global priority latch: lower priority is rejected, bit 7 keeps the old latch, channels are taken at admission, and the latch clears when an SFX track stops; S3K has no priority latch. | S1/S2 `zSFXPriorityVal` handling (`s1.sounddriver.asm`, `s2.sounddriver.asm`); S3K `zPlaySound` (`:1975`) |
| `9334eaba0` | fix(audio): match S3K PAL driver cadence | b | On PAL, locked-on S3K repeats the complete driver update using `zPalDblUpdCounter`: `fix_sndbugs=0` checks before decrementing, reloads 6, repeats `zUpdateEverything`, and that repeated tail decrements to 5. | `zPalDblUpdCounter` (`:123`, `:485-499`, `:549`) |
| `34ebb6653` | fix(audio): match shipped SMPS scheduler cadence | b | All three loaders seed `DurationTimeout` to 1 and the tempo accumulator from the header tempo; S2/S3K `TempoWait` runs before track service and a carry extends every active duration rather than skipping service (envelopes, note fill, modulation still run); S1 resets the accumulator on tempo change, S2/S3K preserve it; S2 PAL performs an extra music-only update every fifth VInt; S3K `zDoSpeedUp` is the shared tail of `zUpdateSFXTracks` and every `zUpdateMusic` and drives speed shoes by `zSpeedupTimeout`; no `x1.2` PAL tempo scaling exists in any driver. | S3K `TempoWait` (`:2607`), `zUpdateEverything`/`zUpdateMusic` (`:653-700`), `zTrackUpdLoop` (`:734`), `zSpeedupTimeout` (`:748-757`); S1 `DOTEMPO`; S2 `TempoWait` and PAL counter |
| `0e16ee22f` | fix: preserve ABI5 component order | c | Native observer patch, selftest tables, C# observer. | — |
| `01bbe7f1d` | feat(audio): authenticate complete-run ROM content | c | `tools/audio/completerun` comparator + tests. | — |
| `b08ea1be3` | docs(audio): define ROM-authenticated content resolution | c | Design/plan doc revision. | — |
| `d410f4593` | fix(audio): correct S1 A1 semantic predicates | c | C# semantic evidence + fixtures. | — |
| `8c0e356d0` | fix(audio): correct S1 A1 native selector oracles | c | Native selftest tables. | — |
| `ea565d7fe` | fix(audio): close S3K stop-SEGA service tail | c | Fixtures, selftest, C# profile. | — |
| `be4883f89` | fix(audio): close Task5C semantic evidence | c | Fixture regeneration + C# evidence. | — |
| `9a40e41eb` | fix(audio): authenticate Task5C diagnostic core | c | Fixture + C# test. | — |
| `815c48ee4` | fix(audio): bind exact semantic condition authority | c | C# observer/manifest/tests. | — |
| `f24b61eb8` | fix(audio): complete ABI5 typed component transport | c | Native patch, tables, C# observer. | — |
| `1adae1737` | feat(audio): bind S3K pending restore cover | c | `tools/audio/completerun` + tests. | — |
| `50696d1d8` | docs(audio): define S3K pending-cover contract | c | Doc revision. | — |
| `fddb8b86d` | feat(audio): add typed ABI5 semantic components | c | Native patch, selftests, C#. | — |
| `50d725bfb` | feat(audio): enforce canonical source-causal transactions | c | `tools/audio/completerun` + tests. | — |
| `b42be7234` | docs(audio): bind exact clear variants | c | Doc revision. | — |
| `0aeb16411` | docs(audio): amend SMPS causal projection contract | c | Doc revision. | — |
| `e408b374e` | fix: bind S1 queue scans to concrete request context | c | C# observer/evidence/manifest + fixtures. | — |
| `89ddd23d2` | fix(audio): close SFX request lifecycle transactions | c | Fixtures + C# + completerun tests. | — |
| `abbf3a086` | test(audio): refresh ABI5 source-causal tables | c | Native selftest tables. | — |
| `04599c835` | fix(audio): scope lifecycle epochs by exact owner | c | completerun + test. | — |
| `d38a4b814` | fix(audio): make bounded semantic evidence source causal | c | Fixtures (v2/v4), C#, completerun. | — |
| `46e533aba` | fix(audio): bind S2 one-up activation store | c | Selftest table fix. | — |
| `f51715d53` | fix(audio): correct final source-causal coordinates | c | Selftest table fix. | — |
| `5f3a6b09c` | fix(audio): admit pre-service causal branch evidence | c | Native patch + tables. | — |
| `ba8ec4268` | fix(audio): correct ABI5 source-causal tables | c | Native patch + tables. | — |
| `9fa9ce006` | fix(audio): retain exact terminal validation identity | c | completerun + tests. | — |
| `64841d322` | fix(audio): bound complete validation context | c | completerun + tests. | — |
| `ae19c5cd6` | fix(audio): retain source causal transaction histories | c | completerun + tests. | — |
| `021dd2e9e` | refactor(audio): harden source causal validation | c | completerun + tests. | — |
| `7e1b5ece5` | test(audio): preserve physical resource alias coverage | c | Test only. | — |
| `e95760af6` | refactor(audio): make canonical ledger source causal | c | completerun rewrite + tests. | — |
| `7c213c3dc` | docs(audio): share restore epochs across actions | c | Doc revision. | — |
| `cbf495379` | docs(audio): type lifecycle restore contracts | c | Doc revision. | — |
| `cb417e23d` | docs(audio): close causal plan review gaps | c | Doc revision. | — |
| `7ba13d56f` | docs(audio): make parity plan source causal | c | Plan doc rewrite; earlier plan states (`cf2ed69ea`, `b5376e605`) are superseded in content. | — |
| `71dec5f38` | fix(audio): isolate ABI5 publication epoch | c | C# observer, capture runners, fixtures. | — |
| `0b2c9f1b6` | fix(audio): bind S2 direct requests at the bridge | c | Native patch, fixtures, C#. | — |
| `f708ca8ca` | fix(audio): serialize ABI5 service generations | c | Native patch + selftests. | — |
| `3a6d49d17` | fix(audio): authenticate ABI5 token generations | c | C# + fixtures. | — |
| `f2fd7619b` | fix(audio): authenticate complete semantic instructions | c | C# + fixtures. | — |
| `1fd017a36` | fix(audio): correct authentic ABI5 site bindings | c | Fixtures, tables, C# tests. | — |
| `30c71ec99` | fix(audio): harden ABI5 semantic projection | c | C# observer + tests. | — |
| `fc4d3429d` | feat(audio): project managed semantic ABI5 | c | C# observer/manifest/native binding. | — |
| `c80327dd7` | fix(audio): isolate reset observer staging | c | Native patch + selftests. | — |
| `96e67af6c` | fix(audio): stage reset observer transactions | c | Native patch + selftests. | — |
| `95f9beade` | fix(audio): make semantic ABI5 transactions atomic | c | Native patch + selftests. | — |
| `c56d3291a` | feat(audio): add grouped semantic observer ABI5 | c | Native patch, compiled tables, README. | — |
| `c20ee0519` | fix(audio): preserve compiled ABI metadata | c | C# evidence + tests. | — |
| `b4ae75fb5` | fix(audio): compile full semantic ABI tables | c | C# evidence + fixtures. | — |
| `825ddf5d9` | feat(audio): enrich bounded semantic evidence | c | Fixtures v2/v3 + C#. | — |
| `1784e6b9c` | docs(audio): define grouped semantic ABI | c | Doc revision. | — |
| `4e9f3b9d0` | feat(audio): close bounded selected-run semantics | c | C# projects, fixtures, high-risk cases. | — |
| `48eaea09a` | test(audio): exercise discovery against patched core | c | C# test. | — |
| `af042df80` | test(audio): verify discovery layouts with native harness | c | Native selftest. | — |
| `ea3950b62` | test(audio): strengthen discovery boundary proof | c | Native selftests. | — |
| `af604ce1b` | fix(audio): lazily bind discovery exports | c | C# discovery adapter. | — |
| `79d5dda03` | feat(audio): add dual CPU discovery plane | c | Native gpgx observer patch, C# discovery, csproj. | — |
| `a895a9e02` | docs(audio): bound SMPS parity to selected runs | c | Doc revision. | — |
| `81df6e44a` | fix(audio): retain producer-neutral cutoff identity | c | completerun + tests. | — |
| `ccc68f367` | fix(audio): scale complete-run proof state | c | completerun + tests. | — |
| `282a0a794` | fix(audio): enforce exact native projection proof | c | completerun + tests (test-side `ringLeft` alias map only). | — |
| `6cd882b00` | fix(audio): enforce exact canonical profile truth | c | completerun + tests (test-side `ringLeft` alias map only). | — |
| `53ae38ba7` | fix(audio): harden canonical transaction contract | c | completerun + tests. | — |
| `9a04410d0` | feat(audio): define canonical SMPS transaction schema | c | New `tools/audio/completerun` classes + tests. | — |
| `c7896f686` | docs(audio): make integration ROM setup self-contained | c | Doc revision. | — |
| `9e436fc2b` | docs(audio): harden ROM discovery and Maven gates | c | Doc revision. | — |
| `dc032441d` | docs(audio): correct shared ROM discovery contract | c | Doc revision. | — |
| `b5376e605` | docs(audio): plan exact SMPS transaction parity | c | Plan doc revision (superseded in content by `7ba13d56f` and later). | — |
| `d4dd50ca7` | Merge remote-tracking branch 'origin/develop' into feature/ai-smps-transaction-parity | c | Merge commit. | — |
| `cf2ed69ea` | docs(audio): plan cross-game SMPS parity proof | c | Initial plan doc (superseded in content by later revisions). | — |
| `a6773986a` | docs(audio): design cross-game SMPS parity proof | c | Initial design doc (revised by every later docs commit). | — |

Partial supersessions within the branch (no whole commit is reversed):

1. `34ebb6653` S3K `LEGACY_TEMPO_SCALE` placeholder → replaced by `9334eaba0` `FULL_DRIVER_REPEAT_EVERY_SIXTH`.
2. `9334eaba0` single-pass `driverClock` service loop → rewritten by `4bd00b064` two-pass loop.
3. `7ac56b1b4` `MusicOverrideRestorePolicy.FM_FADE_IN` → renamed `DRIVER_FADE_IN` by `1ad387c2d`.
4. `0b269a9be` empty-`DacData` 295 fallback → removed (`return null`) by `6d393dcf7`.
5. `f266983a3` host-linear exclusive PCM → routed through the YM DAC by `4e5e2def3`.
6. `4e5e2def3` old-core `Snapshot.equals`/`hashCode` hunks → target code replaced by the new core (`Ym2612Chip.Snapshot` record, `Ym2612Chip.java:831`).

## Behaviour checklist for the driver reverse-engineering

One line per distinct behaviour the branch claimed, deduplicated across commits. Each
must be derived from the driver source at the cited anchor; the branch's reading is
not evidence. S3K anchors are `docs/skdisasm/Sound/Z80 Sound Driver.asm`; S1 is
`docs/s1disasm/s1.sounddriver.asm`; S2 is `docs/s2disasm/s2.sounddriver.asm`.

Tempo and service cadence
- [ ] Initial `DurationTimeout` and tempo-accumulator seeding on song load, per game (S3K `zPlayMusic` `:1717`; S1/S2 `PlayMusic`) — `34ebb6653`
- [ ] `TempoWait` position relative to track service and what a carry does (extend durations vs skip service), per game (S3K `TempoWait` `:2607`, `zUpdateMusic` `:658`, `zTrackUpdLoop` `:734`; S1 `DOTEMPO`; S2 `TempoWait`) — `34ebb6653`
- [ ] Whether a tempo-set command resets or preserves the accumulator, per game (S3K `fix_sndbugs` blocks near `:1659`; S1/S2 tempo coordination flag handlers) — `34ebb6653`
- [ ] Zero-tempo songs: how the first service tick happens (S3K title screen `FF 00`) (S3K `TempoWait` `:2607`) — `34ebb6653`
- [ ] S3K speed-up: `zDoSpeedUp` as the shared tail of `zUpdateSFXTracks` and every `zUpdateMusic`, `zSpeedupTimeout` reload and decrement (`:748-757`, `zTempoSpeedup` `:127`) — `34ebb6653`
- [ ] S2 PAL: extra music-only update every fifth VInt (S2 PAL counter in `s2.sounddriver.asm`) — `34ebb6653`
- [ ] S3K PAL: `zPalDblUpdCounter` check-before-decrement, reload 6, repeat `zUpdateEverything`, decrement to 5 (`:485-499`, `:549`) — `9334eaba0`
- [ ] Whether any driver applies a tempo multiplier on PAL (branch claims none) (S1/S2/S3K PAL paths) — `34ebb6653`
- [ ] S3K per-VInt order: `zPauseUnpause`, `zUpdateSFXTracks`, then `zUpdateMusic`, and channel release of a finished SFX before the same VInt's music (`zUpdateEverything` `:653-655`, `zUpdateSFXTracks` `:727`) — `4bd00b064`
- [ ] S1/S2 per-VInt order of SFX vs music service (S1/S2 update entry points) — `4bd00b064`

SFX admission, priority and release
- [ ] S1/S2 global SFX priority latch: rejection when lower, bit-7 "keep latch" semantics, where the latch is set, and clearing on SFX track stop (`zSFXPriorityVal` in S1/S2) — `2c0844a9f`
- [ ] S3K: absence of any SFX priority gate (`zPlaySound` `:1975`, `zPlaySoundByIndex` `:1641`) — `2c0844a9f`
- [ ] When SFX channels are claimed (at request vs at first service), per game (S1/S2 SFX load; S3K `zPlaySound` `:1975`) — `2c0844a9f`
- [ ] State of an interrupted PSG music track after SFX release (rest until next note vs live restore), per game (S1/S2 SFX-stop; S3K `zUpdateSFXTracks` `:727`) — `76f9571e1`
- [ ] Whether S2 performs any FM register reset on SFX takeover/release (S2 SFX load/stop) — `76f9571e1`
- [ ] S1 `Sound_PlayBGM` retention of normal and special SFX tracks, channel locks, continuous state and priority across an ordinary song change, and override marking on the new song (S1 `Sound_PlayBGM`) — `644fb6f68`
- [ ] S2/S3K stopping SFX before an ordinary BGM load (S2 `zPlayMusic`; S3K `zPlayMusic` `:1717`) — `644fb6f68`
- [ ] Ring SFX speaker alternation on every raw ring request and reset on music play (S3K `zPlaySound_CheckRing` `:1919-1925`, `:547`; S1/S2 equivalents) — raised by this inventory, not by the branch
- [ ] Spindash-rev transpose ownership: S2 `$3C` timeout and 0-11 semitone index per request (S2 `zPlaySound_CheckSpindash`); whether S3K has a driver-side equivalent (`zPlaySoundByIndex` `:1641`, `:1659`) — `4f7e23dd7`
- [ ] S2 spindash-release SFX data containing the `$90` FM5 transpose under `FixMusicAndSFXDataBugs=0` (S2 SFX data) — `556ab16c0`

1-up / music override lifecycle
- [ ] SFX stopped at 1-up start and blocked until the driver's restore boundary, per game (S3K `zUpdateMusic` `:658-680` 1-up queue clearing, `zFadeInToPrevious` `:2725`; S1/S2 1-up handling) — `1ad387c2d`
- [ ] Saved-priority handling across 1-up: S1 clears; S2 `FixDriverBugs=0` LDIR copies `zSFXPriorityVal` before clearing so restore reinstates it (S1/S2 1-up save/restore) — `1ad387c2d`
- [ ] When SFX reopen after restore: immediately (S3K) vs after fade-in (S1/S2) (S3K `zFadeInToPrevious` `:2725`; S1/S2) — `1ad387c2d`
- [ ] S3K 1-up speed: jingle at normal speed, `zTempoSpeedupSave` saved at `:1751` and restored at `:2730` — `68d0c38fa`
- [ ] Restore fade-in: S3K `$40`-step FM-only fade, PSG excluded (`zFadeInToPrevious` `:2725`, `zDoMusicFadeIn`); S1/S2 restore fade shape — `7ac56b1b4`, `1ad387c2d`
- [ ] S1 `FixBugs=0` `cfFadeInToPrevious` omitting the `$2B` DAC-enable repair (S1 `cfFadeInToPrevious`) — `1465d9b5e`

Fades
- [ ] Fade-out channel set per game: S3K halts DAC and PSG immediately and fades FM only (`zFadeOutMusic` `:2307-2330`, `:2323`); S1/S2 channel set — `442c3cd70`
- [ ] S1 stopping SFX when a fade-out starts (S1 fade routine) — `442c3cd70`
- [ ] S1/S2 clearing speed shoes on fade-out (S1/S2 fade routines) — `442c3cd70`
- [ ] Terminal fade count: decrement-then-stop with no final volume step, per game (S3K `zDoMusicFadeOut`; S1/S2) — `442c3cd70`

Pause
- [ ] S1 pause: FM1-6 pan to zero, key off, PSG silence (S1 `PauseMusic`) — `9aa3acc35`
- [ ] S2 pause: `FixDriverBugs=0` `zFMSilenceAll` writing `$FF` to `$30-$8F` on both ports; voice reload on resume (S2 `zFMSilenceAll`, unpause path) — `9aa3acc35`
- [ ] S3K pause: FM1-5 muted, FM6/DAC left running, redundant `zPSGSilenceAll` (`zPauseAudio` `:2541-2560`, `:2543`, `zPSGSilenceAll` `:2587`) — `9aa3acc35`
- [ ] DAC sample continuing to play while paused with no track service, per game (S3K `zPauseUnpause` `:2232`, `zPauseAudio` `:2541`) — `76855b939`
- [ ] Resume: what is re-sent to FM (pan/AMS/FMS only vs full instrument), per game (S1/S2/S3K unpause paths) — `9aa3acc35`

Modulation and envelopes (S3K)
- [ ] Modulation-envelope byte ranges: `$80-$84` commands, `$85-$FF` signed deltas (`zDoModulation` `:1279`) — `10b27e755`
- [ ] `fix_sndbugs=0` operand fetch for `$82`/`$84` via `INC BC / LD A,(BC)` with BC holding the envelope index (`zDoModulation` `:1279`, `:1303`, `:1349`) — `d08af3083`
- [ ] Number of modulation-envelope pointers (8 vs `$3C`) and PSG volume-envelope pointers (`$27`) (pointer tables reached from `zPlaySound_Bankswitch` `:1928`) — `6d393dcf7`

SEGA PCM (S3K)
- [ ] SEGA chant start stopping music, overrides and all SFX (`zPlaySEGAPCM` `:4372`) — `f266983a3`
- [ ] `fix_sndbugs=0` StopSEGA leaving the driver silent rather than restoring voices (`zStopAllSound` `:2460`, `zPlaySEGAPCM` tail) — `f266983a3`
- [ ] PCM delivery: unsigned bytes written to `$2A` with `$2B` enabled, and the Z80-cycle pacing of the loop (`zPlaySEGAPCM` `:4372`) — `4e5e2def3`

DAC timing
- [x] S2 `fixBugs=0` `zWriteToDAC` cycle budget per two decoded samples (295) (S2 `zWriteToDAC` / `.dac_playback_loop`) — covered by `TestSonic2DacCadence`
- [ ] S1 (301) and S3K (297) DAC base cycles, currently unverified engine constants (S1 DAC loop; S3K DAC loop near `zPlaySEGAPCM`) — `0b269a9be` (by implication)

Data framing
- [ ] S2 Saxman payload-length endianness and the exact uncompressed playlist entries (S2 playlist in `s2.sounddriver.asm`) — `6d393dcf7`
- [ ] S1 PSG envelope `$80` hold terminator on every envelope (S1 PSG envelope table) — `6d393dcf7`

Hardware clocks (not driver behaviour, recorded for completeness)
- [ ] PAL YM2612 = 53,203,424/7 Hz and PSG = 53,203,424/15 Hz; NTSC = 53,693,175/7 and /15 — `a0e346e41`
- [ ] PSG noise LFSR shifting on positive edges only; DAC output stepped, not interpolated (libvgm / GPGX chip reference) — `8d2f0dcf2`
