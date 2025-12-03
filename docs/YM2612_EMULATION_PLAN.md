## YM2612 Emulation Plan
Reference docs: `Emulating the YM2612_ Part 1-7 - jsgroth's blog.htm`, `YM2612 - Documents - Maxim's World of Stuff.htm`, SMPS voice format docs, and `docs/YM2612.java.example.txt` (Gens-based Java port used as a coding reference).

### 1) Chip Interface & Registers
- [x] Per-port addressing, key on/off (0x28), DAC enable (0x2B), LFO freq (0x22), stereo/panning/AMS/FMS (0xB4-0xB6).
- [x] Per-operator regs for DT/MUL (0x30-3F), TL (0x40-4F), RS/AR (0x50-5F), AM/D1R (0x60-6F), D2R (0x70-7F), D1L/RR (0x80-8F), SSG-EG (0x90-9F).
- [x] Sonic 2 19-byte voice loading; TL still set by SMPS via register writes.
- [~] Timer/status: timers now tick from YM master clock with overflow flags/reload and CSM on TA; busy timing still approximate; channel 3 CT3 toggle resyncs slots but quirks remain.

### 2) Phase Generation
- [x] Phase accumulators per operator with FNUM/BLOCK handling and detune multipliers.
- [x] LFO FMS applied as PM on phase increment (triangle LFO).
- [x] Detune table ported from the reference core (DT_DEF_TAB/FKEY_TAB based multiplier).
- [x] Basic frequency clamping on FNUM/BLOCK writes (masking to hardware ranges).
- [ ] Slot-specific frequency increment and KSR coupling identical to hardware; channel 3 special-mode parity still to verify.

### 3) Envelope Generator
- [x] Fixed-point EG counter (ENV_HBITS/ENV_LBITS) with tick-based stepping from EG clock; table-driven AR/DR with null-rate padding per reference.
- [x] SSG-EG enable/invert/hold/alternate handling with direction toggling; key-on resets direction with invert-aware start.
- [~] EG tick cadence/phase reasonably close (clock-derived accumulator); still needs validation against reference timing/tables and SSG edge cases.

### 4) Operator Output & Algorithms
- [x] Feedback on operator 1 with history; algorithm routing for all 8 algos.
- [x] Log-sine to exponential amplitude path with SSG inversion support; quarter-sine/log-exp derived at 0.1875 dB/step.
- [ ] Refine algorithm gain staging/output headroom to YM2612 levels (current linear mix may still be off).

### 5) LFO (AMS/FMS)
- [x] Triangle LFO with documented frequency table; vibrato depth from cents table.
- [x] AMS depth applied in dB space; FMS scaling from reference tables.
- [ ] Confirm AMS/LFO scaling and vibrato depth against hardware captures.

### 6) DAC Path
- [x] DAC playback on channel 5 when enabled; respects pan.
- [ ] Ladder/analog quirk modelling and more accurate DAC stepping/distortion.

### 7) Mixing & Output
- [x] Per-channel pan to L/R, summed and clamped; stereo render path available (legacy mono mix still present).
- [x] Headroom reduction and optional simple low-pass smoothing (placeholder).
- [ ] Optional low-pass/ladder filter and log-domain style mixing to match analog path.

### 8) Integration Steps
- [x] Replace Ym2612Chip stub with feature-complete model while keeping API.
- [x] Sequencer loads 19-byte voices and uses per-operator key-on bits.
- [ ] Sequencer coordination flags and modulation behaviours (vibrato/tremolo, tie, sustain, pan/AMS/FMS commands) matching SMPS player.
- [ ] Unit tests rendering known voices and asserting envelope progress/AMS/FMS/SSG behaviour.

### 9) Deferred/Accuracy Extras
- [ ] Full SSG-EG behaviours, analog ladder/LPF, exact output clipping, timer IRQ side effects, per-chip quirks (YM2612 DAC distortion), and verified channel 3 special-mode parity.
