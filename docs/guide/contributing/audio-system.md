# Audio System

This page covers the engine's audio architecture for contributors who want to fix audio
bugs or improve SMPS accuracy.

## Architecture Overview

```
ROM data
  |
  v
SmpsLoader          Per-game loaders (Sonic2SmpsLoader, etc.) parse
  |                 music/SFX from ROM using pointer tables. Produces
  |                 SmpsMusic/SmpsSfx with channel assignments and
  |                 sequence data.
  v
SmpsSequencer       Processes sequence commands each frame.
  |                 Manages channel state, tempo, loops, modulation.
  |                 Produces register writes.
  |
  +---> Ym2612Chip  FM synthesis (6 channels). Software emulation based
  |                 on Genesis Plus GX reference core.
  |
  +---> PsgChip     PSG (4 channels: 3 tone + 1 noise). Software emulation
  |                 based on SMS Power documentation.
  |
  +---> DacData     PCM drum samples. Manages sample data from ROM,
  |                 applies correct playback rate.
  |
  v
AudioOutput         Mixes all sources and sends to system audio.
```

## SMPS Format

SMPS (Sample Music Playback System) is the sound driver format used by all three games.
Each music track or sound effect consists of:

### Music Header

| Field | Size | Description |
|-------|------|-------------|
| Voice pointer | Word | Offset to FM voice (instrument) table |
| FM channel count | Byte | Number of FM channels used |
| PSG channel count | Byte | Number of PSG channels used |
| Tempo | Byte | Tempo divider value |
| DAC channel pointer | Word | Offset to DAC channel data (if used) |
| FM channel headers | N x 4 bytes | Per-channel: data pointer (word), transpose (byte), volume (byte) |
| PSG channel headers | N x 6 bytes | Per-channel: data pointer (word), transpose (byte), volume (byte), voice (byte), detune (byte) |

### Sequence Commands

Channel data is a stream of bytes. Values below $80 are note durations. Values $80-$DF
are note pitches. Values $E0-$FF are commands:

| Range | Meaning |
|-------|---------|
| $01-$7F | Set note duration |
| $80 | Rest (silence for duration) |
| $81-$DF | Note on (pitch, played for current duration) |
| $E0-$FF | Commands (tempo set, loop, volume, modulation, etc.) |

The exact command set varies slightly between S1, S2, and S3K, which is why each game has
its own SMPS configuration.

S3K coordinate-flag commands are decoded by `Sonic3kCoordFlagHandler`. Most
live commands have engine semantics. The ROM inventory in
`docs/architecture/research/audio/2026-08-08-s3k-smps-meta-command-reachability.md`
proves by fixed-point control-flow traversal that shipped S&K music (`01-33`),
S3 music (`01-32`), and all 169 S&K-loader SFX streams (`33-DB`) never reach meta
subcommands `SND_CMD`, `MUS_PAUSE`, or `COPY_MEM`. The same proof covers both
native SFX tables (`33-DF`, 173 entries each) with strict full-bank traversal,
including the differing `9B`/`AD` payloads and `DC-DF` aliases. Native dispatch
has S&K's `DC` CreditsK music special case while S3 dispatches `DC-DF` as SFX.
The strict CFG also treats bank-end falloff, malformed roots/pointers, and
unknown commands/subcommands as unresolved rather than silently advancing;
`EB` follows its ROM index-plus-pointer layout. The handler
consumes their documented operands only to keep a custom/imported stream
aligned; it does not claim native sound dispatch, all-track halt/resume, or
shared-Z80-memory copying. If a supported ROM or custom stream reaches one,
port the Z80-owned behavior at the sequencer/driver boundary using the native
contract before treating it as implemented.

## Per-Game Driver Differences

The three games use subtly different SMPS driver configurations. These are captured in
`SmpsSequencerConfig` rather than in separate driver implementations.

### Tempo Mode

| Game | Mode | Behavior | Effect |
|------|------|----------|--------|
| S1 | -- | Direct timing | -- |
| S2 | OVERFLOW2 | Overflow = tick | Higher value = faster |
| S3K | OVERFLOW | Overflow = skip | Higher value = slower |

S2 and S3K use **inverted** overflow logic. In S2, when the tempo counter overflows, the
sequencer processes a tick (advances the music). In S3K, overflow causes a skip (delays
the music). This means the same tempo byte value produces different speeds in the two
games.

### Note Mapping

S1 uses a different base note table than S2/S3K. This affects which FM frequency is
produced for each SMPS note value. The engine stores per-game note tables in the
sequencer config.

### PSG Envelopes

PSG (square wave) channels use volume envelopes defined by a table of values. Each game
has its own envelope table at a different ROM address with potentially different envelope
shapes. The engine loads the correct table based on the active game's `SmpsConstants`.

### FM Operator Order

The YM2612 has 4 operators per channel. The order in which they are written affects the
final sound. S1 uses a different operator ordering than S2/S3K. The engine's
`SmpsSequencerConfig` specifies the operator order for each game.

## Playing Sounds from Objects

Objects play sound effects through the services layer:

```java
// Play a sound effect by ID
services().playSfx(Sonic2Sfx.SPRING.id);

// Play music
services().playMusic(Sonic2Music.BOSS.id);
```

SFX IDs are defined in per-game enums (`Sonic2Sfx`, `Sonic1Sfx`) that map hex IDs to
named constants. The hex IDs match the `SndID_` constants in the disassembly.

## Finding Audio Data in the ROM

### Music Pointer Table

Each game has a music pointer table in the ROM. The table maps music IDs to offsets
where the SMPS data begins.

| Game | Constants class | Key field |
|------|----------------|-----------|
| S1 | `Sonic1SmpsConstants` | `MUSIC_PTR_TABLE_ADDR` |
| S2 | `Sonic2SmpsConstants` | `MUSIC_PTR_TABLE_ADDR` |
| S3K | `Sonic3kSmpsConstants` | `Z80_MUSIC_BANK_LIST` (bank-based) |

S3K's music is bank-switched, which adds complexity: the music bank list gives a Z80
bank number for each song, and the music pointer within that bank gives the data offset.

### SFX Pointer Table

Similar structure to music but for sound effects. The base SFX ID varies by game (e.g.,
in S2, SFX IDs start at a different value than music IDs).

### Using RomOffsetFinder

```bash
# Find music-related addresses
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search Mus" -q

# Find SFX pointer tables
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search SndPtr" -q

# Search ROM for a pointer pattern
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search-rom \"07 72 73 26\"" -q
```

## Comparing Against SMPSPlay

[SMPSPlay](https://github.com/ValleyBell/SMPSPlay) by ValleyBell is the reference SMPS
playback tool. It supports all three games and provides the comparison target for
the same ROM data; OpenGGF does not yet claim bit-accurate output for every command
or register write. To verify the engine's audio accuracy:

1. Play a song in SMPSPlay and capture the output.
2. Play the same song in OpenGGF and capture the output.
3. Compare channel-by-channel: FM voice settings, note timing, volume envelopes,
   modulation.

Common sources of differences:
- **Tempo calculation:** The overflow direction or counter initialization may differ.
- **Modulation timing:** Vibrato/tremolo delay and depth.
- **DAC sample rate:** Derived from Z80 `djnz` loop timing; different base cycle counts
  per game.
- **PSG noise behavior:** The noise LFSR always shifts once per rising edge of the noise
  square wave (the libvgm/hardware rule); there is no configurable every-toggle mode.

## Common Audio Bugs

### Music plays at wrong speed

Check the tempo mode. S2 uses OVERFLOW2 (overflow = tick), S3K uses OVERFLOW (overflow =
skip). If the wrong mode is active, music will play at a very different speed.

Also check for tempo-0 songs. S3K songs with header tempo=0 (e.g., Title Screen) rely on
an unconditional first tick to process their `FF 00` (TEMPO_SET) command. The engine
handles this via the `tempoOnFirstTick` flag in the sequencer config.

### Missing or wrong instruments

Check the FM voice table pointer. If it points to the wrong ROM address, all instruments
in the song will sound wrong. Also check the operator order -- S1 uses a different order
than S2/S3K.

### DAC drums sound wrong

Check the DAC sample rate calculation. The formula involves the Z80 `djnz` base cycle
count, which differs per game: S1=301, S2=288, S3K=297. If the wrong value is used,
drums will play at the wrong pitch.

### SFX plays the wrong sound

Check the SFX ID mapping. The SFX pointer table is zero-indexed but sound IDs may be
offset by a base value. S3K music/SFX tables are 0-indexed: index 0 = song ID 0x01.
An off-by-one error here will play the wrong sound for every SFX call.

## Next Steps

- [Architecture](architecture.md) -- Overall engine architecture
- [Testing](testing.md) -- Audio regression testing
