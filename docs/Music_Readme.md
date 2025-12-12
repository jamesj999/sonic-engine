# Sonic 2 Music Reference Library

Guides in this folder document the hardware and SMPS engine details you need when wiring music and sound into a Sonic 2–style engine. Each section below summarizes an `.htm` reference and when it is useful during implementation.

## SCHG_Music Hacking_DAC Samples, Coordination Flags, Game Specifics, and Real-Time Music Editing - Sonic Retro.htm
- Overview: Explains DAC sample formats (DPCM vs. LPCM) across Sonic 1/2/3, per-game sample pointer tables, and PWM handling on 32X. Lists coordination flags ($E0–$FF) with behaviors and game-specific differences, plus RAM maps for live music editing and master playlists.
- Use cases: Mapping drum/voice samples for accurate playback, decoding/encoding song control flags in a sequencer, aligning with Z80 RAM layouts when building live-edit tools, and understanding per-game bank/pointer quirks before streaming assets in an engine.

## SCHG_Music Hacking_Pointer and Header Format - Sonic Retro.htm
- Overview: Describes how SMPS pointers work on 68k vs. Z80 (relative vs. absolute little-endian) and details music header layouts for Mega Drive, 32X, Master System/Game Gear, and Mega-CD (SMPS-PCM).
- Use cases: Parsing original song data, regenerating headers when exporting to ROM/Z80 RAM style banks, and correctly resolving channel/voice/tempo pointers when converting or authoring new tracks.

## SCHG_Music Hacking_Voice and Note Editing - Sonic Retro.htm
- Overview: Covers FM voice layout (19-byte YM2612 instrument definition), note/value encoding ($00–$7F durations, $80–$DF notes, $E0+ flags), alternate SMPS mode for S3+, and DAC sample ID maps for Sonic 1/2/3.
- Use cases: Building a converter between modern formats and SMPS notation, editing or synthesizing YM2612 patches, remapping DAC IDs, and handling alternate frequency-encoded note formats for later SMPS engines.

## SCHG_Music Hacking_Other Games and Data Locations - Sonic Retro.htm
- Overview: Catalog of games using SMPS and where their music/sfx pointer tables live (ROM offsets, banks, RAM addresses). Includes Sonic series, Chaotix, Ristar, Gunstar Heroes, Phantasy Star, Streets of Rage, etc.
- Use cases: Mining additional music data for tests or comparisons, confirming pointer bank math against known titles, and sourcing example tracks/engines when validating loader compatibility beyond Sonic 2.

## SCHG_Music Hacking_Tricks of the Trade - Sonic Retro.htm
- Overview: Practical porting notes between SMPS variants: S1 → S2 Beta, cross-porting among S3K/3D/Crackers/Chaotix, and conversions between S2 Beta and S3K-family (flag swaps $E3↔$F9, $FB↔$E9, pointer adjustments).
- Use cases: Porting legacy tracks into your engine while preserving behavior, normalizing coordination flags between driver versions, and checking pointer rewrites when relocating songs across banks.

## SCHG_Nem s2 - Sonic Retro.htm
- Overview: Legacy Sonic 2 ROM hacking notes: address listings, offset indexes, pattern load cues, collision definitions, level layouts/rings/sprites, art compression (Nemesis-style), dynamic pattern reloading, animated cues, palettes, level sizing, start positions, level order, and the per-level music playlist.
- Use cases: Understanding how Sonic 2 stores music IDs in level metadata, how assets are indexed/loaded (useful for matching timing/VRAM behavior during playback), and where to hook into ROM structures if you want to mirror original level-music mapping or debugging workflows.

## SN76489 - Development - SMS Power!.htm
- Overview: Deep dive on the PSG (SN76489/SN76496): register layout, write protocol, tone/noise generation, volume curves, hardware imperfections, and methods for PCM sample playback (1-bit, 4-bit, lookup tables).
- Use cases: Implementing accurate PSG emulation, matching vintage volume curves/noise behavior, and experimenting with PCM-on-PSG techniques for effects in absence of DAC/FM resources.

## YM2612 - Documents - Maxim’s World of Stuff.htm
- Overview: Excerpt of the Sega Genesis YM2612 manual: channel/operator model, envelope stages, full register map (LFO, timers, key on/off, DAC enable, detune, TL, attack/decay/release, algorithms, stereo), plus a simple test init program.
- Use cases: Programming or emulating YM2612 behavior, constructing register writes from SMPS data, verifying instrument parameters, and diagnosing FM playback differences (LFO, detune, envelope timing, stereo routing).

### How to use this library
- Start with the SMPS pointer/header guides when parsing songs; layer voice/note and coordination-flag references to interpret channels correctly.
- Use the DAC and game-specific sections to map samples and playlists exactly as the originals.
- Lean on SN76489 and YM2612 docs to match chip-level behavior in your Java audio backend.
- Consult porting notes and cross-game tables when importing or testing songs from other SMPS titles.
