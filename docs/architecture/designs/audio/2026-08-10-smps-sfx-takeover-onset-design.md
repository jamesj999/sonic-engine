# SMPS SFX Takeover Onset Design

## Problem

Constructing an FM SFX `SmpsSequencer` currently mutates the shared synthesizer before
`SmpsDriver.addSequencer(..., true)` identifies and arbitrates the SFX. For Sonic 1's
`$C1` badnik explosion this uploads the FM5 voice while GHZ still owns and may still have
FM5 keyed on. The constructor emits DAC-enable, the complete FM voice, and duplicate pan
writes; the first real SFX service follows roughly one NTSC frame later. The result is a
brief old-note/new-instrument transient. PSG-only jump does not take this path.

The eventual takeover also invokes `forceSilenceChannel`, directly clearing YM2612
feedback, MEM, envelope, and key state and injecting an extra key-off. Sonic 1's shipped
68k driver does neither: it initializes track RAM, then the track executes its own
`smpsSetvoice`, note-off, frequency, and note-on writes during `UpdateMusic`.

## Approved design

### SFX construction is state-only

Constructing an SFX `SmpsSequencer` may parse immutable SMPS data, initialize track
fields, select initial voice bytes, and install DAC data. It must not issue chip-register
writes. In particular, the shared constructor's DAC enable is retained for music but
suppressed for SFX, and SFX FM voice/pan initialization remains logical state only.

FM SFX tracks retain voice 0 as logical initial state without refreshing hardware. Their
bytecode remains authoritative: `smpsSetvoice` performs the first instrument upload at
the exact source-defined point. Music construction and startup behavior are deliberately
unchanged in this scoped fix.

### SFX identity precedes hardware mutation

`SmpsDriver.addSequencer(seq, true)` establishes the cached SFX identity before any SFX
hardware write. The first real write therefore passes through contention arbitration and
cannot alter a music-owned channel beforehand.

### Sonic 1 follows register-visible takeover behavior

For the direct 68k Sonic 1 profile, stealing an FM role does not call
`forceSilenceChannel` and does not inject the legacy pre-voice key-off. The track emits
the shipped sequence: voice registers in `SetVoice` order, then the track's note-off,
frequency, and note-on writes. Other driver profiles retain their existing takeover
behavior until separately proven against their source drivers.

The direct YM reset API remains available for existing non-S1 callers during this scoped
change, but no Sonic 1 SFX admission may use it.

## Verification contract

- Constructing an SFX against a live `SmpsDriver` leaves the chip-write stream and synth
  snapshot unchanged.
- Existing music construction/start behavior remains unchanged.
- ROM-backed `$C1` produces no writes before SFX attachment/service.
- Its first FM5 service writes one voice upload followed by `$28=$05`, frequency, and
  `$28=$F5`, without a hidden reset or injected leading key-off.
- PSG jump remains PSG-only at admission.
- Signpost, SFX restoration, same-ID replacement, snapshots, and chip-write observer
  ordering remain covered.
- No test or runtime code reads disassembly assets; ROM data remains authoritative.

## Non-goals

- Changing YM2612 operator or slot ordering.
- Retiming gameplay requests versus ROM admissions.
- Generalizing Sonic 1 takeover behavior to Sonic 2 or Sonic 3&K without their own
  source-backed parity work.
- Merging or pushing the branch before human audio testing.
