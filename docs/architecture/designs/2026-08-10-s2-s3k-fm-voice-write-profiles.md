# S2/S3K FM Voice Write Profiles

## Problem

The YM2612 core now keeps the hardware register-slot permutation at its bus
boundary. `Ym2612Chip.setInstrument()` still applies an older SMPS middle-slot
swap before issuing register writes. That swap compensated for the former chip
mapping and now assigns the middle operators incorrectly for Sonic 2 and Sonic
3 & Knuckles.

The two games have the same effective operator assignment, but their shipped
drivers represent and traverse voice bytes differently:

| Driver | Stored group order | Register traversal |
|---|---|---|
| S2 Z80 `zSetVoice` | driver/register order | `30,34,38,3C` |
| S3K Z80 `zSendFMInstrument` | `Op4,Op3,Op2,Op1` | `30,38,34,3C` |

Those combinations land equivalent semantic operators in equivalent YM slots.
The distinction matters because the YM2612 observes an ordered stream of writes,
not an atomic instrument object.

## Design

`SmpsSequencerConfig` will own an `FmVoiceWriteProfile` identifying the shipped
driver contract: `S1_68K`, `S2_Z80`, or `S3K_Z80`. This is a game-wide runtime
rule and therefore belongs in typed configuration rather than the shared chip
core.

`SmpsSequencer.refreshInstrument()` will issue the profile's exact register
sequence through `Synthesizer.writeFm()`:

- S1 retains its existing direct 68k sequence.
- S2 writes feedback/algorithm, grouped operator parameters in ascending YM
  register order, pan/AMS/FMS, then total levels.
- S3K writes pan/AMS/FMS first, then feedback/algorithm, grouped operator
  parameters through the driver's `30,38,34,3C` table, then total levels.

The Z80 profiles will not inject the generic helper's key-off or unconditional
SSG-EG clears because neither shipped voice-loading routine performs those
writes. S3K's explicit SSG-EG coordination-flag restoration remains a separate
driver action.

S3K voice loaders will return the 25 ROM bytes unchanged. Music, local SFX,
bank-shared voices, and global voices all use the same raw contract. S2 loaders
already preserve the raw bytes and remain unchanged.

`Ym2612Chip` remains responsible only for interpreting raw YM register writes.
Its corrected `REG_TO_OP`, key-on, algorithm, and CH3 routing are not changed.
The generic `setInstrument()` helper remains available for non-SMPS callers,
but ROM-driven S1/S2/S3K playback no longer relies on it.

## Volume Updates

Total-level selection follows the driver's own byte traversal:

- S2 rotates the algorithm carrier mask across the four stored TL bytes and
  writes `40,44,48,4C`.
- S3K reads the carrier bit from each stored TL byte and writes through
  `40,48,44,4C`.
- S1 retains its existing 68k behavior.

This mapping is shared by initial voice loads, fades, volume envelopes, and
music restoration after SFX contention.

## Verification

Tests use distinct literal values for every operator group and assert the real
chip-write observer stream. Separate fixtures cover S2 music/SFX and S3K
music/local/global SFX voice resolution. Mutating either middle register order,
reintroducing a key-off, or restoring the S3K loader swap must fail a test.

Focused verification covers the new mapping tests, existing YM2612 GPGX parity,
chip-write observer ordering, S2 sequencer tests, S3K voice/coordination tests,
and snapshot tests. ROM-backed smoke tests use the discovered REV01 S2 and
locked-on S3K ROMs when available. No integration, merge, push, or worktree
cleanup is part of this change; human audio testing remains the release gate.
