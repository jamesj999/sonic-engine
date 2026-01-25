# Music Implementation Strategy

This plan describes how to add sound effects and music playback to the Sonic engine while staying faithful to Sonic 2’s SMPS driver behavior. It highlights architecture, data flow, and which references in `docs/` to consult at each step. Updated to reflect current status and remaining accuracy work (YM2612 EG/LFO/gain, SMPS flags).

## Goals and scope
- Match Sonic 2 playback as closely as possible (YM2612 FM + SN76489 PSG + DAC drums).
- Stream from original ROM data when available; allow a fallback asset path for missing data in dev/test.
- Keep the audio stack modular so alternate backends (software emulation, different API) can be swapped in later.
- Expose configuration for enabling/disabling audio, channel volumes, and debug metrics.

## Key references (docs/)
- SMPS structure: `SCHG_Music Hacking_Pointer and Header Format - Sonic Retro.htm` (headers/pointers), `SCHG_Music Hacking_Voice and Note Editing - Sonic Retro.htm` (note/event encoding), `SCHG_Music Hacking_DAC Samples, Coordination Flags, Game Specifics, and Real-Time Music Editing - Sonic Retro.htm` (samples + flags).
- Game data: `SCHG_Nem s2 - Sonic Retro.htm` (per-level music IDs, ROM offsets/pointer tables).
- Porting nuances: `SCHG_Music Hacking_Tricks of the Trade - Sonic Retro.htm` (flag/pointer differences across SMPS variants).
- Chip behavior: `YM2612 - Documents - Maxim’s World of Stuff.htm` (FM register model), `SN76489 - Development - SMS Power!.htm` (PSG noise/volume).
- Cross-title data source: `SCHG_Music Hacking_Other Games and Data Locations - Sonic Retro.htm` (useful for comparisons or extra test material).

## Architecture outline
- `audio` package (new):
  - `AudioSystem`: JOAL/AL context + device lifecycle; publishes capabilities (channel count, sample rate).
  - `AudioBackend` interface: abstract play/stop/stream; allows swapping JOAL or software emu.
  - `SoundBank` + `MusicBank`: lookup tables for decoded PCM buffers and streamed tracks; populated from ROM or fallback assets.
  - `SmpsLoader`/`SmpsParser`: reads SMPS headers/pointers, voices, coordination flags (see Pointer and Header Format, Voice and Note Editing docs).
  - `SmpsSequencer`: interprets SMPS events into chip writes; yields timed commands for YM2612, PSG, DAC (see DAC/Coordination Flags doc).
  - `ChipSynth` interfaces: `Ym2612Synth`, `Sn76489Synth`, `DacChannel`. Initial implementation can map commands into PCM via existing backend; later swap to higher accuracy synths using chip manuals.
  - `MusicPlayer`: streaming queue with loop points; converts sequencer events to chip writes and feeds buffers; handles tempo/coordination flags.
  - `SfxPlayer`: low-latency one-shot playback from `SoundBank`; source pooling.
  - `AudioManager`: facade for engine; exposes `playSfx(id)`, `playMusic(id)`, `setVolume`, `pause/resume`, tick/update.
- Configuration: extend `SonicConfiguration`/service to add `audio.enabled`, `music.enabled`, master/channel volumes, debug overlay toggle.
- Debug overlay: hook into `debug.DebugRenderer` to show active sources, buffer underruns, song ID, and timing (optional).

## Data flow
1. **Load banks**: On level load, `LevelManager` asks `AudioManager` to load level music/SFX IDs using ROM pointer tables (see `SCHG_Nem s2` for mapping).
2. **Parse SMPS**: `SmpsLoader` resolves pointers (68k vs Z80 relative/absolute; consult Pointer and Header Format doc) and builds sequences + voice tables.
3. **Sequence → chip commands**: `SmpsSequencer` walks events (note encoding, durations, coordination flags) using Voice and Note Editing and DAC/Coordination docs.
4. **Chip → PCM**:
   - JOAL backend v1: emulate YM2612/PSG minimally to produce PCM; feed DAC samples directly (LPCM from ROM; see DAC Samples doc).
   - Future backend: replace emulation with higher-accuracy YM2612/PSG synthesis guided by chip manuals.
5. **Streaming**: `MusicPlayer` keeps a double-buffered queue; refills via sequencer, applies loop points and tempo changes.
6. **One-shots**: `SfxPlayer` plays decoded buffers via pooled sources.

## Phased implementation plan
### Phase 0: Scaffolding and configuration
- Add `audio` package skeleton (interfaces, manager stubs, config flags).
- Provide a `NullAudioBackend` to keep headless/testing stable.
- Ensure defaults in `SonicConfigurationService` cover headless/test runs (ROM filename, debug flags, screen sizes).

### Phase 1: Asset loading and parsing
- Implement `SmpsLoader` to read headers/pointers and voice tables (use Pointer/Header and Voice/Note docs).
- Map ROM music/SFX IDs per level using `SCHG_Nem s2`.
- Extract DAC sample tables (addresses/format) using DAC Samples doc.
- Allow fallback to external PCM/OGG directory when ROM data absent (keeps engine runnable without ROM).
- Detect SMPS endian mode (68k big-endian vs Z80 little-endian) based on ROM; parse pointers accordingly.

### Phase 2: Minimal playback backend
- Use JOAL to open device/context; allocate sources and streaming buffers.
- Implement a simple YM2612/PSG translator: convert SMPS events into approximate PCM (basic operator math and PSG tone/noise per chip manuals).
- Stream music with loop handling; play SFX via buffer sources.
- Wire `AudioManager` hooks into game states (`LevelManager` for music select; player actions/physics for SFX).

### Phase 3: Accuracy and robustness
- Improve YM2612 (envelopes, LFO, detune, stereo) per YM2612 doc; refine PSG volume curves/noise per SN76489 doc.
- Implement coordination flags fully (tempo, jumps, retriggers) from DAC/Coordination doc.
- Add porting compatibility layer for other SMPS variants using Tricks of the Trade doc.
- Current focus: table-driven YM2612 EG/SSG-EG, LFO AMS/FMS scaling, gain/feedback/headroom alignment, FINC/KSR parity (incl. channel 3 special mode), coordination flags (pan/AMS/FMS/tie/sustain/vibrato/tremolo/key displacement). Add regression tests for AMS/FMS writes, SSG-EG progression, DAC playback, timer flags.

### Phase 4: Debugging and tooling
- Debug overlay metrics (active channels, buffer lag, current pattern/measure).
- Optional live-edit hooks reflecting RAM maps from DAC Samples doc for real-time tweaking.
- CLI or test harness to parse and render short clips for regression tests.

## Integration points
- `Engine` lifecycle: init/shutdown audio; tick `AudioManager` each frame.
- `LevelManager`: trigger `playMusic(levelMusicId)` on load; stop/transition on act completion.
- `Control`/`sprites`/`physics`: call `playSfx` for UI blips, jumps, rings, monitors, damage, roll, spindash charge/release, collisions.
- Pause/resume: tie to game pause; optionally duck music during pause and keep SFX silent.

## Testing strategy
- Unit tests: SMPS header/pointer parsing, voice table decoding, coordination flag handling (skip ROM-dependent tests when ROM missing).
- Integration tests: render short PCM buffers from known sequences and assert non-silence/loop correctness.
- Regression tests to cover YM2612 tone/AMS/FMS, SSG-EG behaviour, DAC latch/sample playback, timer flags, and SMPS flag-driven register writes.
- Manual: run level load with ROM, verify correct track selection per `SCHG_Nem s2` playlist; check SFX timing.

## Future-proofing
- Keep `AudioBackend` and `ChipSynth` pluggable for alternate APIs or higher-accuracy emulation.
- Support user-added music/SFX by letting external banks override ROM lookup.
- Preserve SMPS conversion utilities for exporting/importing tracks using the Voice/Note and Pointer/Header docs.
