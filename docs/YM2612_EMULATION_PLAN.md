## YM2612 Emulation Plan
Reference docs: `Emulating the YM2612_ Part 1-7 - jsgroth's blog.htm`, `YM2612 - Documents - Maxim's World of Stuff.htm`, SMPS voice format docs, and `docs/YM2612.java.example.txt` (Gens-based Java port used as a coding reference).

### 1) Chip Interface & Registers
- [x] Per-port addressing, key on/off (0x28), DAC enable (0x2B), LFO freq (0x22), stereo/panning/AMS/FMS (0xB4-0xB6).
- [x] Per-operator regs for DT/MUL (0x30-3F), TL (0x40-4F), RS/AR (0x50-5F), AM/D1R (0x60-6F), D2R (0x70-7F), D1L/RR (0x80-8F), SSG-EG (0x90-9F).
- [x] Sonic 2 19-byte voice loading; TL still set by SMPS via register writes.
- [ ] Timer/status behaviour (sample-clocked counters added, still needs accuracy) and shadow channel 3 quirks (per-slot FNUM/BLOCK wired; still need full parity with reference).

### 2) Phase Generation
- [x] Phase accumulators per operator with FNUM/BLOCK handling and detune multipliers.
- [x] LFO FMS applied to carrier base frequency.
- [x] Detune table ported from the reference core (DT_DEF_TAB/FKEY_TAB based multiplier).
- [x] Basic frequency clamping on FNUM/BLOCK writes (masking to hardware ranges).
- [ ] Slot-specific frequency increment and KSR coupling identical to hardware.

### 3) Envelope Generator
- [x] ADSR state machine with RS/KSR-aware rate scaling and proper D1L sustain level conversion.
- [x] AMS depth applied only on AM-enabled operators; TL in dB steps.
- [x] Basic SSG-EG enable/invert/hold handling; restart alternation added.
- [x] Table-driven EG step sizes derived from EG clock (approximate tables precomputed).
- [ ] Complete SSG-EG waveforms (alternate/attack variants) matching the doc diagrams (logic in place but still approximate).

### 4) Operator Output & Algorithms
- [x] Feedback on operator 1 with history; algorithm routing for all 8 algos.
- [x] Log-sine to exponential amplitude path (improved resolution) with SSG inversion support.
- [x] Higher-resolution quarter-sine/log-exp tables generated to mirror YM attenuation steps (0.1875 dB/step), now derived with the same math as the reference example.
- [ ] Refine algorithm gain staging to align with YM2612 output levels (headroom partially adjusted).
- [ ] Refine algorithm gain staging to align with YM2612 output levels (headroom partially adjusted).

### 5) LFO (AMS/FMS)
- [x] Triangle LFO with documented frequency table; vibrato depth from cents table.
- [x] AMS depth applied in dB space.
- [x] Sine-based LFO shape to match the example core.
- [x] AMS/FMS scaling updated to use reference tables (AMS shift + FMS step table).
- [ ] Confirm AMS/LFO scaling and vibrato depth against hardware captures.

### 6) DAC Path
- [x] DAC playback on channel 5 when enabled; respects pan.
- [ ] Ladder/analog quirk modelling and more accurate DAC stepping.

### 7) Mixing & Output
- [x] Per-channel pan to L/R, summed and clamped.
- [x] Stereo render path added (legacy mono mix still available); pan applied to DAC as well.
- [x] Headroom reduced (scaled output gain) to mitigate clipping.
- [x] Optional simple low-pass smoothing added (placeholder for analog/ladder filter).
- [ ] Optional low-pass/ladder filter from Part 5.

### 8) Integration Steps
- [x] Replace Ym2612Chip stub with feature-complete model while keeping API.
- [x] Sequencer loads 19-byte voices and uses per-operator key-on bits.
- [ ] Sequencer coordination flags and modulation behaviours (vibrato/tremolo, tie, sustain, pan/AMS/FMS commands) matching SMPS player.
- [ ] Unit tests rendering known voices and asserting envelope progress.

### 9) Deferred/Accuracy Extras
- [ ] Full SSG-EG behaviours, analog ladder/LPF, exact output clipping, timer IRQ side effects, and per-chip quirks (YM2612 DAC distortion).
