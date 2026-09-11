# OpenSMPSDeck - FM/PSG Music Tracker Design

## Overview

OpenSMPSDeck is a standalone SMPS-native music tracker for composing YM2612 FM and SN76489 PSG music. It reuses the chip emulation core from the OpenGGF sonic-engine project and provides a traditional tracker interface for authoring SMPS-compatible songs.

**Key decisions:**
- **Interface:** Traditional tracker grid (keyboard-driven, vertical rows, channel columns)
- **Project type:** Standalone JavaFX application in a new repository
- **Internal model:** SMPS-native — the bytecode IS the source of truth, UI is a decoded view
- **Output format:** SMPS binary, directly compatible with Sonic 1/2/3K ROMs and the sonic-engine
- **Instruments:** Visual FM voice editor + PSG envelope editor with preset library from Sonic ROMs

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   JavaFX UI                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │ Tracker  │ │ Order    │ │ FM Voice Editor  │ │
│  │ Grid     │ │ List     │ │ + PSG Envelope   │ │
│  └────┬─────┘ └────┬─────┘ └────────┬─────────┘ │
│       │             │                │           │
│  ┌────┴─────────────┴────────────────┴─────────┐ │
│  │           Song Model (SMPS-native)          │ │
│  │  Patterns → Tracks → SMPS bytecodes         │ │
│  │  Voice bank, PSG envelopes, DAC samples     │ │
│  └──────────────────┬──────────────────────────┘ │
└─────────────────────┼───────────────────────────┘
                      │
         ┌────────────┴────────────┐
         │      Synth Core         │
         │  SmpsDriver → Sequencer │
         │  Ym2612Chip + PsgChip   │
         │  → javax.sound output   │
         └─────────────────────────┘
```

**Three layers:**

1. **Synth Core** — Extracted from sonic-engine's `audio.synth` and `audio.smps` packages (~2500 lines). Pure Java, no UI or game dependencies. Public API: `SmpsDriver.read(short[])` produces stereo PCM samples.
2. **Song Model** — SMPS data structures: songs, patterns, tracks, voice bank. Edits mutate SMPS bytecode directly.
3. **JavaFX UI** — Tracker grid, order list, instrument editors. Views over the model.

### Synth Core Extraction

Copy from sonic-engine (not submodule). Files to extract:

**Core chip emulators (zero game coupling):**
- `Ym2612Chip.java` — YM2612 FM synthesis (2141 lines)
- `PsgChipGPGX.java` — SN76489 PSG with BlipDelta anti-aliasing (393 lines)
- `VirtualSynthesizer.java` — Combines both chips (162 lines)
- `BlipDeltaBuffer.java` — Anti-aliased resampling (334 lines)
- `BlipResampler.java` — Sample rate conversion (200 lines)

**SMPS sequencer (game-agnostic):**
- `SmpsSequencer.java` — SMPS bytecode interpreter (~2000 lines)
- `SmpsSequencerConfig.java` — Tempo modes, channel config (300+ lines)
- `AbstractSmpsData.java` — Data interface (152 lines)
- `DacData.java` — DAC sample metadata (30 lines)
- `SmpsDriver.java` — Multi-sequencer orchestrator (500+ lines)

**Not extracted (game-coupled):**
- AudioManager, LWJGLAudioBackend, GameAudioProfile
- Game-specific loaders/data (Sonic1/2/3kSmpsLoader, etc.)

**Audio output:** `javax.sound.sampled.SourceDataLine` replaces OpenAL/LWJGL. Built into JDK, no external dependencies.

## Data Model

```
Song
 ├── name: String
 ├── smpsMode: S1 | S2 | S3K
 ├── tempo: int
 ├── dividingTiming: int
 ├── voiceBank: FmVoice[0..n]
 ├── psgEnvelopes: PsgEnvelope[0..n]
 ├── dacSamples: DacSample[0..n]
 ├── orderList: int[order][10]       # pattern ID per channel per row
 ├── loopPoint: int                  # order row to loop back to
 └── patterns: Pattern[0..n]
      ├── id: int
      ├── rows: int
      └── tracks: byte[][10]         # raw SMPS bytecode per channel
```

### Channel Mapping (10 channels)

| Index | Type | Hardware |
|-------|------|----------|
| 0 | FM 1 | YM2612 Ch1 |
| 1 | FM 2 | YM2612 Ch2 |
| 2 | FM 3 | YM2612 Ch3 |
| 3 | FM 4 | YM2612 Ch4 |
| 4 | FM 5 | YM2612 Ch5 |
| 5 | DAC/FM 6 | YM2612 Ch6 (DAC mode) |
| 6 | PSG 1 | SN76489 Tone 1 |
| 7 | PSG 2 | SN76489 Tone 2 |
| 8 | PSG 3 | SN76489 Tone 3 |
| 9 | PSG Noise | SN76489 Noise |

### FM Voice (25 bytes, SMPS slot order)

```
byte[0]:    Algorithm (bits 0-2) | Feedback (bits 3-5)
byte[1-4]:  Op1: DT_MUL, TL, RS_AR, AM_D1R, D2R, D1L_RR
byte[5-8]:  Op3: (same layout)
byte[9-12]: Op2: (same layout)
byte[13-16]: Op4: (same layout)
byte[17-20]: TL overrides (S3K mode only)
```

Operators stored in SMPS order (1,3,2,4), reordered to YM register order on chip write.

### PSG Envelope

Array of volume steps (0-7, where 0=loudest), terminated by `0x80`. Applied per-frame during note sustain.

### Track Bytecode

Literal SMPS bytes. The tracker grid is a decoded view:
- Inserting a note writes `[noteValue, duration]`
- Inserting a control command writes the SMPS command bytes (e.g. `E0 pp` for pan, `E1 ii` for instrument, `F0 dd rr dd ss` for modulation)

## UI Layout

```
┌──────────────────────────────────────────────────────────┐
│  [Song1.osmpsd] [Song2.osmpsd] [+]           ← tab bar   │
├──────────────────────────────────────────────────────────┤
│  Toolbar: [New] [Open] [Save] [Export] | Mode: S2 ▼     │
│  Transport: [Play] [Stop] [Pause] | Tempo: 120          │
├───────────────────────────────────┬──────────────────────┤
│                                   │                      │
│         TRACKER GRID              │   INSTRUMENT PANEL   │
│                                   │                      │
│  Row | FM1     | FM2     | FM3    │  ┌────────────────┐  │
│  ----+---------+---------+-----   │  │ Voice Bank     │  │
│  00  | C-5 01  | --- --  | E-4 03│  │ 00: Bass       │  │
│  01  | D-5 01  | C-4 02  | --- --│  │ 01: Lead    ◄  │  │
│  02  | --- --  | D-4 02  | G-4 03│  │ 02: Pad        │  │
│  03  | E7  --  | --- --  | F0 0A │  │ 03: Strings    │  │
│  04  | E-5 01  | E-4 02  | A-4 03│  │ [+] [Edit]     │  │
│  05  | F3 04   | --- --  | --- --│  ├────────────────┤  │
│                                   │  │ PSG Envelopes  │  │
│  ◄ scroll channels ►              │  │ 00: Pluck      │  │
│                                   │  │ 01: Sustain    │  │
├───────────────────────────────────┤  │ [+] [Edit]     │  │
│         ORDER LIST                │  └────────────────┘  │
│                                   │                      │
│  Ord | FM1| FM2| FM3| ...| PSG3  │                      │
│  00  | 00 | 00 | 00 | ...| --    │                      │
│  01  | 01 | 01 | 03 | ...| --    │                      │
│  02► | 00 | 00 | 00 | ...| --    │                      │
│  >> Loop to 01                    │                      │
└───────────────────────────────────┴──────────────────────┘
```

### Multi-Document Support

- Songs open as tabs within the main window
- Tabs can be dragged out into separate windows and docked back
- Copy-paste across songs triggers an instrument resolution dialog:
  - **Copy into this song** — appends voice to destination bank, rewrites IDs in pasted bytes
  - **Remap to existing** — rewrites `E1 xx` instrument commands to map to a chosen destination voice
  - **Skip** — keeps original ID, no instrument copied
- Same-song paste: no dialog (IDs already valid)
- Cross-song paste with byte-identical voices: auto-remap silently

## FM Voice Editor

Modal dialog with real-time preview.

```
┌─ FM Voice Editor: Voice 01 "Lead" ──────────────────────────┐
│                                                              │
│  Name: [Lead          ]   Algorithm: [2 ▼]  Feedback: [5 ▼] │
│                                                              │
│  ┌─ Algorithm Diagram ─┐                                    │
│  │  [Op1]──►[Op2]──►    │  Visual topology, updates live.   │
│  │  [Op3]──►[Op4]──►OUT │  Carriers highlighted.            │
│  └──────────────────────┘                                    │
│                                                              │
│  ┌─ Operators ──────────────────────────────────────────────┐│
│  │        Op1         Op2         Op3         Op4           ││
│  │  MUL   [slider]    [slider]    [slider]    [slider]      ││
│  │  DT    [slider]    [slider]    [slider]    [slider]      ││
│  │  TL    [slider]    [slider]    [slider]    [slider]      ││
│  │  AR    [slider]    [slider]    [slider]    [slider]      ││
│  │  D1R   [slider]    [slider]    [slider]    [slider]      ││
│  │  D2R   [slider]    [slider]    [slider]    [slider]      ││
│  │  D1L   [slider]    [slider]    [slider]    [slider]      ││
│  │  RR    [slider]    [slider]    [slider]    [slider]      ││
│  │  RS    [dropdown]  [dropdown]  [dropdown]  [dropdown]    ││
│  │  AM    [checkbox]  [checkbox]  [checkbox]  [checkbox]    ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌─ Envelope Preview ──────────────────────────────────┐    │
│  │  ADSR curves per operator, color-coded              │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  [Preview]  [Copy]  [Paste]  [Init]      [OK]  [Cancel]    │
└──────────────────────────────────────────────────────────────┘
```

- **Algorithm diagram** — 8 standard Yamaha 4-op topologies, carrier vs modulator highlighting
- **Carrier TL highlighting** — TL sliders on carriers visually distinguished (carrier TL = output volume, modulator TL = modulation depth)
- **Envelope preview** — Computed ADSR curves per operator, color-coded overlay
- **Preview** — Holds a test note, configurable pitch/octave
- **Copy/Paste** — 25-byte voice data, works across editors and songs
- **Init** — Reset to basic sine carrier

## PSG Envelope Editor

```
┌─ PSG Envelope Editor: Envelope 02 "Pluck" ──────────────┐
│                                                           │
│  Name: [Pluck         ]                                   │
│                                                           │
│  ┌─ Volume Steps (bar graph, click to edit) ─────────────┐│
│  │  ████                                                  ││
│  │  ████████                                              ││
│  │  ████████████████                                      ││
│  │  ████████████████████████████████                      ││
│  │  Step: 00 01 02 03 04 05 06 07 08 09 0A 0B [END]     ││
│  │  Vol:   0  0  1  1  2  3  4  5  6  7                  ││
│  └────────────────────────────────────────────────────────┘│
│                                                           │
│  [+Step] [-Step]  Length: 10   [Preview]   [OK] [Cancel]  │
└───────────────────────────────────────────────────────────┘
```

- Click bars to set volume, or type hex values directly
- `0x80` end marker managed automatically
- Preview plays test tone through PSG with envelope applied

## Preset Library & ROM Import

### ROM Import

- Point at a Sonic 1, 2, or 3K ROM file
- Parses every song's voice table using existing SMPS loaders
- Lists all unique FM voices with source song attribution
- Filter by song name or algorithm type
- Preview any voice before importing
- Import copies 25-byte voice data into song's voice bank
- PSG envelopes similarly importable (13 per game)

### Song/Track Import

- **ROM import:** Select a song from a Sonic ROM by music ID and name (e.g. `0x02 - Emerald Hill Zone`). Parses header, voice table, and all track data into a full Song model.
- **SMPSPlay file import:** Load raw `.bin` SMPS files from SMPSPlay rips or any directory. Parses the SMPS binary directly using header format.
- Imported songs produce one flat pattern per channel (no auto-splitting in MVP).
- Essential for validation: load a known track, verify playback, inspect structure.

### Preset Files

Voices can be saved/loaded as `.osmpsvoice` files (25 bytes + name string) for sharing between projects without needing a ROM.

## Playback Engine

```
Song Model ──► PatternCompiler ──► SmpsDriver ──► Ym2612Chip
                                   (sequencer)    PsgChipGPGX
                                                      │
                                                      ▼
                                                 javax.sound
                                                SourceDataLine
```

### PatternCompiler

Assembles a valid SMPS binary blob from the song model:

1. Walk the order list, concatenate pattern track data per channel
2. Insert loop jump (`F4`) at end pointing to loop point's byte offset
3. Prepend SMPS header (voice pointer, channel counts, tempo, dividing timing, track pointers)
4. Append voice bank
5. Hand blob to `SmpsDriver.loadMusic()`

### Recompilation

Any song edit triggers recompile and reload. Sequencer restarts from current playback position. Fast — SMPS binaries are typically 2-8 KB.

### Transport

| Control | Behavior |
|---------|----------|
| Play | Compile full song, play from order row 0 |
| Play from cursor | Compile, seek to current order + pattern row |
| Stop | Key-off all channels, silence |
| Pause | Freeze sequencer tick, hold state |
| Solo/Mute | Per-channel toggle, skips key-on without altering SMPS data |

### Audio Thread

Separate thread: `SmpsDriver.read(buffer)` → write to `SourceDataLine`. 1024-sample buffers at 44.1 kHz (~23ms latency). UI thread recompiles and swaps SMPS blob; audio thread picks up on next buffer boundary.

### Playback Position Feedback

Sequencer tracks byte offset per channel. Playback engine maps back to order row + pattern row, driving the scrolling cursor highlight in the tracker grid.

## File I/O

### Project Format (`.osmpsd`)

JSON file. Human-readable, diffable, version-control friendly.

```json
{
  "version": 1,
  "name": "My Song",
  "smpsMode": "S2",
  "tempo": 120,
  "dividingTiming": 1,
  "loopPoint": 1,
  "voiceBank": [
    { "name": "Bass", "data": "3A 1F 12 0A 05 1C 00 ..." },
    { "name": "Lead", "data": "22 7F 33 1F 05 00 ..." }
  ],
  "psgEnvelopes": [
    { "name": "Pluck", "steps": [0,0,1,1,2,3,4,5,6,7] }
  ],
  "orderList": [
    [0,0,0,1,2,0,0,1,-1],
    [1,1,3,1,2,1,2,1,-1]
  ],
  "patterns": [
    {
      "id": 0,
      "rows": 64,
      "tracks": { "0": "C5 01 -- | ...", "1": "-- -- -- | ..." }
    }
  ]
}
```

### SMPS Binary Export (`.bin`)

PatternCompiler output — identical to playback blob. Can be:
- Injected into Sonic ROMs
- Loaded by sonic-engine's SmpsLoader
- Played by any SMPS-compatible player

Export dialog selects target format (S1/S2/S3K).

### WAV Export

Offline render of playback engine to `.wav`. Configurable loop count with fade-out.

## Keyboard Controls

| Key | Action |
|-----|--------|
| `Q-P`, `A-L`, `Z-M` rows | Note entry (piano keyboard layout) |
| `Shift+letter` | Sharp notes |
| `0-9`, `A-F` | Hex digit entry for instrument/effect values |
| `Up/Down` | Move cursor row |
| `Left/Right` | Move cursor column (note → instrument → effect → next channel) |
| `Tab` / `Shift+Tab` | Jump to next/previous channel |
| `Space` | Toggle playback |
| `Enter` | Play from current row |
| `Escape` | Stop playback |
| `Delete` | Clear current cell |
| `Insert` | Insert blank row, shift down |
| `Backspace` | Delete row, shift up |
| `Ctrl+C/V/X` | Copy/cut/paste selection |
| `Shift+Arrow` | Extend selection |
| `+` / `-` | Transpose selection up/down one semitone |
| `Shift+` / `Shift-` | Transpose selection up/down one octave |
| `Ctrl+Up/Down` | Change current octave |
| `F1-F8` | Set current octave directly |
| `Ctrl+Z/Y` | Undo/redo |

## MVP Scope

### v0.1 — Core Tracker

- Synth core extracted and working standalone
- Single-song tracker grid with note/instrument/effect entry
- Order list with pattern management (add, remove, duplicate)
- FM voice editor with sliders, algorithm diagram, preview
- PSG envelope editor with bar graph
- Playback engine (play, stop, play from cursor, solo/mute)
- Project save/load (`.osmpsd` JSON)
- SMPS binary export
- ROM and SMPSPlay track import (load existing songs for validation)
- Keyboard-driven editing (full key map)
- Undo/redo
- S2 mode only

### v0.2 — Multi-Document & Presets

- Multi-document tabs + cross-song copy-paste with instrument resolution dialog
- ROM voice/envelope import browser (preset library)
- WAV export
- Detachable tab windows

### v0.3 — Multi-Game & Sharing

- S1 and S3K mode support (different command sets, tempo modes)
- Preset voice file sharing (`.osmpsvoice`)
- DAC sample management (custom samples for channel 6)

### v0.4 — Polish

- Envelope preview curves in FM editor
- Playback position feedback (scrolling cursor)
- Pattern row highlighting (configurable beat grid)

## Repository Structure

```
opensmps-deck/
  synth-core/                    # Extracted chip emulators
    src/main/java/
      com/opensmps/synth/        # Ym2612Chip, PsgChipGPGX, VirtualSynthesizer
      com/opensmps/smps/         # SmpsSequencer, SmpsDriver, SmpsSequencerConfig
      com/opensmps/resampler/    # BlipDeltaBuffer, BlipResampler
  app/
    src/main/java/
      com/opensmps/deck/
        model/                   # Song, Pattern, FmVoice, PsgEnvelope
        ui/                      # JavaFX: TrackerGrid, OrderList, VoiceEditor, etc.
        audio/                   # javax.sound playback, PatternCompiler
        io/                      # File I/O: .osmpsd save/load, SMPS export, ROM import
  pom.xml                        # Multi-module Maven build
```
