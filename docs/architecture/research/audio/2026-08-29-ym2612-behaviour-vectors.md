# YM2612 behaviour vectors from public hardware knowledge

## Purpose and independence rule

This document is the hardware-side test oracle for the Nuked-OPN2-derived FM
core. It was written **without opening any emulator source** — not the pinned
Nuked-OPN2 tree, not the current `Ym2612Chip.java`, and not any other core —
so that the vectors are independent of the code they will later test. Every
number below is either printed in a public document, published as a hardware
measurement, or derived arithmetically from one of those and labelled as such.

Confidence tags used on every vector:

| Tag | Meaning |
|---|---|
| `M` | Printed in the Yamaha YM2612 / YM2608 application manual or Sega's Genesis Software Manual register description. |
| `N` | Published hardware measurement (Nemesis's YM2612 threads on gendev.spritesmind.net, the OPLx ROM decapsulation, siliconpr0n die shots). |
| `D` | Arithmetic derived from an `M`/`N` fact in this document; the derivation is shown so a reviewer can re-check it. |
| `?` | Plausible but not pinned from a public source; listed again under *Open questions*. Tests must not assert on `?` rows until the question is closed against hardware. |

Where two public statements disagree, the disagreement is recorded rather than
resolved by guessing.

After the port landed, every vector was run against it by
`TestYm2612HardwareBehaviour` and the rows that failed were re-derived from the
pinned Nuked-OPN2 die model (`tools/audio/nuked-opn2/PIN.md`) and from this
document's own arithmetic. Each correction is marked *(corrected)* inline and
listed with its reasoning under *Nuked-OPN2 cross-check* at the end; the tag
`N (die)` marks a value that rests on the die-derived model. The port itself was
not changed: it is bit-exact with the pinned C build, so a row that disagreed
with it was either a documentation error or a place where the die model refines
the printed sources.

## Clock frame used by every vector

All timings are stated in **samples** (one YM2612 output sample) so they are
clock-independent, with NTSC Mega Drive values given for convenience.

| Quantity | Value | Tag |
|---|---|---|
| Master clock (NTSC) | 53,693,175 Hz | M (Sega) |
| YM2612 clock φM | master / 7 = 7,670,453.57 Hz | M (Sega) |
| Internal prescaler | φM / 6; 24 internal cycles per sample | M/N |
| Sample period | 144 φM cycles = 1,008 master cycles = 18.77 µs (NTSC) | M |
| Sample rate Fs (NTSC) | 53,267.04 Hz | D: 7,670,453.57 / 144 |
| Sample rate Fs (PAL) | 52,781.17 Hz | D: 53,203,424 / 7 / 144 |
| Z80 clock | master / 15 = 3,579,545 Hz; 1 Z80 T-state = 15 master cycles = 1/67.2 sample | M (Sega) |

The 144 = 24 × 6 factor is the point of the time-multiplexed design: 6 channels
× 4 operators = 24 operator slots, one operator evaluated per internal cycle.

## Group 1 — Register map and address/data latch

### Ports

| 68k address | Z80 address | Role | Tag |
|---|---|---|---|
| $A04000 | $4000 | Part I address (A0) | M |
| $A04001 | $4001 | Part I data (D0) | M |
| $A04002 | $4002 | Part II address (A1) | M |
| $A04003 | $4003 | Part II data (D1) | M |

Part I addresses channels 1-3 and all global registers ($22-$2B). Part II
addresses channels 4-6 only; the per-channel register layout is identical, so a
write of address $A0 on Part II targets channel 4's F-number low byte. Register
addresses below $30 exist only in Part I; global writes ($22, $24-$28, $2A, $2B)
sent through Part II are ignored (N, Nemesis register-write tests; `?` for the
exact ignored set — see open questions).

Reading any of the four addresses returns the status byte: bit 7 = busy,
bit 1 = timer B overflow, bit 0 = timer A overflow, other bits 0 (M). Sega's
documentation reads through $4000 only; that the other three mirror it is `N`
for YM2612 and not verified here for YM3438.

### Address latch behaviour

A write to the address port latches the register number for that part; every
subsequent data write on the same part goes to that register until the address
is rewritten (M). Each part has its own address latch (M). There is no
auto-increment.

The busy flag (status bit 7) is raised after a **data** write and stays set for
32 internal cycles (φM / 6), i.e. 32 / 24 ≈ 1.33 samples ≈ 25 µs at NTSC (N,
Nemesis busy-flag measurement). Sega's software guidance — wait ≥ 17 Z80
T-states after an address write and ≥ 83 T-states (≈ 23 µs) after a data write
— is the software-side reflection of the same window (M, Sega). Whether the
address write also raises busy, and whether a data write landing inside the
busy window is dropped or queued, is `?` (open questions 1-2).

### Register summary

| Address | Bits | Meaning | Tag |
|---|---|---|---|
| $21 | — | LSI test (undocumented) | M (listed, not described) |
| $22 | 3 | LFO enable | M |
| $22 | 2-0 | LFO frequency select (Group 6) | M |
| $24 | 7-0 | Timer A bits 9-2 | M |
| $25 | 1-0 | Timer A bits 1-0 | M |
| $26 | 7-0 | Timer B | M |
| $27 | 7-6 | Channel 3 mode: 00 normal, 01 special, 10 special + CSM, 11 special + CSM | M |
| $27 | 5 / 4 | Reset timer B / A overflow flag | M |
| $27 | 3 / 2 | Enable timer B / A overflow flag (status bits) | M |
| $27 | 1 / 0 | Load (start) timer B / A | M |
| $28 | 7-4 | Key on/off per operator (Group 1 key encoding) | M |
| $28 | 2-0 | Channel select | M |
| $2A | 7-0 | DAC data, unsigned, $80 = centre | M |
| $2B | 7 | DAC enable: channel 6 output replaced by DAC | M |
| $2C | — | Test register (DAC 9th bit, DAC-on-all-channels) | N |
| $30+ | 6-4 / 3-0 | DT (detune) / MUL (multiple) | M |
| $40+ | 6-0 | TL (total level), 0.75 dB per step | M |
| $50+ | 7-6 / 4-0 | RS (rate scaling) / AR (attack rate) | M |
| $60+ | 7 / 4-0 | AM enable / D1R (first decay rate) | M |
| $70+ | 4-0 | D2R (second decay / "sustain" rate) | M |
| $80+ | 7-4 / 3-0 | D1L (sustain level) / RR (release rate) | M |
| $90+ | 3-0 | SSG-EG (Group 7) | M |
| $A0-$A2 | 7-0 | F-number low 8 bits, channels 1-3 (4-6 on Part II) | M |
| $A4-$A6 | 5-3 / 2-0 | Block / F-number high 3 bits | M |
| $A8-$AA | 7-0 | Channel 3 special-mode F-number low for the other three operators | M |
| $AC-$AE | 5-3 / 2-0 | Channel 3 special-mode block / F-number high | M |
| $B0-$B2 | 5-3 / 2-0 | Feedback / algorithm | M |
| $B4-$B6 | 7 / 6 / 5-4 / 2-0 | L enable / R enable / AMS / PMS | M |

Operator register addressing: `address = base + 4 × slotIndex + channelInPart`,
where `slotIndex` 0-3 is the **register-order** slot and `channelInPart` is 0-2.
Register-order slots map to **algorithm-order** operators as S1 = op1, S2 = op3,
S3 = op2, S4 = op4 (M — the manual's "S1, S3, S2, S4" ordering). So $30 is
ch1 op1, $34 is ch1 op3, $38 is ch1 op2, $3C is ch1 op4; $31 is ch2 op1.
Addresses with `channelInPart` = 3 ($33, $37, …) are unused and ignored (M).

### $28 key on/off encoding

Bits 7-4 are one bit per operator in **algorithm order**: bit 4 = op1,
bit 5 = op2, bit 6 = op3, bit 7 = op4 (M). Bits 2-0 select the channel:
0, 1, 2 = channels 1-3; 4, 5, 6 = channels 4-6; values 3 and 7 are invalid and
the write is ignored (M/N). Note the Part I/II distinction does not apply to
$28 — it is a global register that addresses all six channels through bit 2.

### F-number / block latch

The block + F-number-high byte written to $A4-$A6 is **not** applied on its own
write. It is held in a latch and committed together with the low byte on the
following $A0-$A2 write. The latch is shared by all three channels of a part
rather than being per channel; $AC-$AE have their own separate shared latch for
channel-3 special mode (N, Nemesis register tests). Software must therefore
write $A4 then $A0 for each channel, which is what every Sega sound driver
does. The die model holds one $A4 latch and one $AC latch for all six channels,
i.e. shared across both parts as well; tests pin the within-part sharing.

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| REG-01 | Write A0 ← $B4, D0 ← $C0; then A0 ← $30, D0 ← $71 | Channel 1: L = R = 1, AMS = 0, PMS = 0. Ch1 op1: DT = 7, MUL = 1. Channel 4 registers unchanged. | M |
| REG-02 | Write A1 ← $A4, D1 ← $22; then A1 ← $A0, D1 ← $39 | Channel 4 (not channel 1) F-number = $239, block = 4. The $A4 value is visible in the channel only after the $A0 write. | M + N |
| REG-03 | Write A0 ← $A4, D0 ← $22 only (no $A0 write) | Channel 1 block/F-number unchanged; the latch holds $22. A subsequent A0 ← $A1, D0 ← $00 sets **channel 2** to F-number $200, block 4 (shared latch). | N |
| REG-04 | Write A0 ← $28, D0 ← $F0 then D0 ← $03 | First write keys on all four operators of channel 1. Second write (channel field 3) is ignored: channel 1 stays keyed on, no other channel changes. | M + N |
| REG-05 | Write A0 ← $28, D0 ← $F5 | Channel 5 (bit 2 set, low bits 01 → channel index 4) has op1-op4 keyed on; $F6 would select channel 6. *(corrected: the row originally said channel 6, against its own encoding table)* | M |
| REG-06 | Data write on D0; read $4000 immediately, then again after 2 samples | First read: bit 7 = 1 (busy). Second read: bit 7 = 0. Busy window ≈ 1.33 samples. | N |
| REG-07 | Write A1 ← $22, D1 ← $0F (Part II) | LFO stays disabled (global register ignored through Part II); Part I read of the LFO state unchanged. | N (`?` for other globals) |

## Group 2 — Envelope generator

### Attenuation domain

The EG works on a 10-bit attenuation, 0 = full volume, $3FF = silence, in steps
of 96 dB / 1024 = **0.09375 dB** (N, die analysis; consistent with the manual's
TL step of 0.75 dB = 8 EG steps). The instantaneous operator attenuation fed to
the output stage is

```
att_total = min($3FF, eg_att + (TL << 3) + am_att)
```

TL 0-127 → 0 … 95.25 dB (M). D1L (sustain level) 0-14 → 0 … 42 dB in 3 dB
steps, i.e. `D1L << 5`; D1L = 15 → 93 dB (M), i.e. $3E0. *(corrected)* $3E0 is
a threshold like any other: decay-1 hands over to decay-2 there, so a ramp that
is to continue to silence needs D2R set as well (N (die)).

### Phases

1. **Key on** (bit 0→1 in $28 for an operator that is currently off): phase
   accumulator reset to 0; envelope enters attack from its current attenuation
   (it is *not* reset to $3FF — a key-on during release continues from where
   the release got to) (N). A key-on write for an operator already keyed on is
   ignored: no retrigger (N).
2. **Attack**: attenuation falls towards 0 along an exponential curve — the
   step size is proportional to the remaining attenuation (the manual measures
   attack time "from 96 dB to 0 dB"); ends when att = 0 → decay-1.
   If the *effective* attack rate (after key scaling) is 62 or 63 the attack is
   skipped entirely: the operator is at att = 0 on the first sample after key
   on and is already in decay-1 (N, Nemesis). Raw AR = 31 gives rate 62 + ks,
   so **AR = 31 always attacks instantly** regardless of scaling (D).
3. **Decay 1**: attenuation rises linearly (in dB) by the rate table until
   `att >= D1L << 5`, then → decay 2 (M).
4. **Decay 2 ("sustain")**: continues rising at D2R until key off or $3FF.
   D2R = 0 holds the level indefinitely (M).
5. **Release**: on key off, from the current attenuation at rate derived from
   RR (M).

### Rate formula

```
kc      = (block << 2) | (N4 << 1) | N3          ; 5-bit key code, 0..31
N4      = F11
N3      = (F11 & (F10 | F9 | F8)) | (~F11 & F10 & F9 & F8)
ks      = kc >> (3 - RS)                          ; key scaling, 0..31
rate    = 0                        if R == 0
        = min(63, 2 * R + ks)      otherwise      ; R = AR, D1R, D2R (5-bit)
release = min(63, 2 * (2 * RR + 1) + ks)          ; RR is 4-bit, so it is doubled + 1 first
```

F8-F11 are F-number bits 7-10 (M for N3/N4 and RS; N for the `R == 0` guard
and the RR "×2 + 1" expansion). In channel 3 special mode each operator uses
its own block/F-number for `kc` (N). Note that `ks` is `kc >> 3` even at
RS = 0, so a vector that names an exact effective rate must either add `ks` or
pick `kc < 8` (block 1 or lower).

### Rate → timing

The EG has a global step counter that advances once every **3 samples** (N,
Nemesis). For an effective rate `r`:

```
shift = 11 - (r >> 2)      for 4 <= r < 48       ; update when (egCounter & ((1 << shift) - 1)) == 0
row   = r & 3
cycle = (egCounter >> shift) & 7
inc   = incTable[row][cycle]
```

with, for `r < 48`,

| row | cycle 0..7 | mean per update |
|---|---|---|
| 0 | 0 1 0 1 0 1 0 1 | 4/8 |
| 1 | 0 1 0 1 1 1 0 1 | 5/8 |
| 2 | 0 1 1 1 0 1 1 1 | 6/8 |
| 3 | 0 1 1 1 1 1 1 1 | 7/8 |

and for `r >= 48` an update on every EG step with the same row patterns but the
1s replaced by larger increments: rates 48-51 use 1 (with the row-pattern
"extra" positions becoming 2), 52-55 use 2 (extras 4), 56-59 use 4 (extras 8),
and 60-63 use 8 on every step. Concretely (N, Nemesis EG thread):

| rate | per-step increments (cycle 0..7) |
|---|---|
| 48 | 1 1 1 1 1 1 1 1 |
| 49 | 1 1 1 2 1 1 1 2 |
| 50 | 1 2 1 2 1 2 1 2 |
| 51 | 1 2 2 2 1 2 2 2 |
| 52 | 2 2 2 2 2 2 2 2 |
| 53 | 2 2 2 4 2 2 2 4 |
| 54 | 2 4 2 4 2 4 2 4 |
| 55 | 2 4 4 4 2 4 4 4 |
| 56 | 4 4 4 4 4 4 4 4 |
| 57 | 4 4 4 8 4 4 4 8 |
| 58 | 4 8 4 8 4 8 4 8 |
| 59 | 4 8 8 8 4 8 8 8 |
| 60-63 | 8 8 8 8 8 8 8 8 |

In decay/release phases `inc` is added to the attenuation directly (linear in
dB). *(added)* Once a stored attenuation reaches $3F0 or more the EG treats the
operator as off: on the next sample the level is replaced by $3FF and the phase
becomes release (N (die)), so a linear ramp ends `… $3E8, $3F0, $3FF` and never
shows $3F8. For rates 49-51, 53-55 and 57-59 the die model's per-step pattern is
the same multiset as the table below but phase-shifted (the larger increment
falls on the step whose low two counter bits are 0); the means, and therefore
every duration in this document, are unchanged. In attack the same `inc` scales an exponential step of the form
`att -= ((att ^ $3FF) * inc) >> 4` (plus a possible constant). The exact
rounding of the attack step is `?` (open question 4); the *phase boundaries*
and the decay-side increments are `N`. Rates 0-3 are also `?` (open question 5); the
manual only says register value 0 means "no change".

Derived full-range (0 → $3FF, 96 dB) decay durations, in EG steps of 3 samples
(D):

| effective rate | shift | mean inc per EG step | EG steps for 1023 | samples | NTSC ms |
|---|---|---|---|---|---|
| 4 | 10 | 0.5 / 1024 | 2,095,104 | 6,285,312 | 118,000 |
| 24 | 5 | 0.5 / 32 | 65,472 | 196,416 | 3,687 |
| 47 | 0 | 7/8 | 1,169 | 3,507 | 65.8 |
| 52 | — | 2 | 512 | 1,536 | 28.8 |
| 63 | — | 8 | 128 | 384 | 7.2 |

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| EG-01 | TL = 0, AR = 31, RS = 0, key on op | On the first sample after key on the operator attenuation is 0 and the EG is in decay-1 (attack skipped). | N |
| EG-02 | AR = 31, RS = 3, block 7, F-number $7FF (kc = 31) | Effective rate = min(63, 62 + 31) = 63; still instant attack. | D |
| EG-03 | D1R = 31, D2R = 31, RS = 0, D1L = 15, key on with AR = 31 | Effective rate 62 (63 with ks) → +8 per EG step. Attenuation is $3F0 after 126 EG steps (378 samples) and $3FF on the next sample; silence within 384 samples of key on. *(corrected: D1L 15 hands over to decay-2 at $3E0, hence D2R = 31; the EG snaps $3F0 → $3FF rather than passing $3F8)* | N (die) + D |
| EG-04 | D1R = 26, RS = 0, kc < 8 (rate exactly 52), D1L = 4, D2R = 0 | Decay-1 stops rising at att ≥ $080 (4 << 5) then decay-2 takes over at D2R's rate (0 → holds at $080). Expected 64 EG steps (192 samples) to reach $080 at +2/step. *(corrected: kc pinned so that ks = 0)* | M + D |
| EG-05 | D1R = 0 | Attenuation never changes in decay-1 (rate 0 → "no change"), regardless of RS/kc. | M + N |
| EG-06 | RR = 15, RS = 0, key off from att = 0 | Release rate = 2 × 31 = 62 → +8/step; $3F0 after 126 EG steps, then $3FF: silence within 384 samples. *(corrected as EG-03)* | N (die) + D |
| EG-07 | RS = 1, block 4, F-number $439, AR = 20 | kc = 16 + N4N3. F-number $439: F11 = 1, so N4 = 1, N3 = F10\|F9\|F8 = 0\|0\|0 → 0; kc = 18; ks = 18 >> 2 = 4; rate = 40 + 4 = 44. | M + D |
| EG-08 | Key on, then key on again 10 samples later while still in attack | Second write has no effect: phase is not reset, envelope continues. | N |
| EG-09 | Key off during attack at att = $100, then key on again | Release starts from $100; a key on during the release resumes attack from whatever attenuation the release reached — never from $3FF. | N |

## Group 3 — Total level / attenuation → output mapping

The output stage is the OPN/OPL "log-sine + exponential ROM" pipeline confirmed
on the OPL3 decapsulation (N, Gambrell & Niemitalo 2008) and matched on the
YM2612 die (N, Nemesis):

```
; 10-bit phase p (top 10 bits of the 20-bit accumulator + modulation)
quarter  = p & $FF ; mirrored for the 2nd/4th quarter: index = (p & $100) ? (~p & $FF) : (p & $FF)
sign     = p & $200
logsin[i] = round(-log2(sin((i + 0.5) * pi / 512)) * 256)      ; 256 entries, 12-bit
level    = logsin[index] + (att_total << 2)                     ; 0.09375 dB → 1/256 log2 units (×4)
exp[j]   = round((2^(j / 256) - 1) * 1024)                       ; 256 entries, 10-bit mantissa
mag      = ((exp[(~level) & $FF] | $400) << 2) >> (level >> 8)  ; 13-bit magnitude
out      = sign ? -mag : +mag                                    ; 14-bit signed operator output
```

Reference ROM values (N): `logsin[0] = $859` (2137), `logsin[255] = 0`,
`exp[0] = 0`, `exp[255] = 1018`. Because attenuation is added in the log domain,
every 64 EG steps (6 dB) halve the linear amplitude; every 8 TL steps do the
same (M: 0.75 dB per TL step).

Channel mixing: each carrier's 14-bit output is summed into a 14-bit channel
accumulator clamped to −8192 … +8191 (N). The value handed to the DAC is the
top 9 bits of that (Group 9). *(corrected)* The die model does the truncation
first: each carrier's output is shifted to 9 bits (`>> 5`) before the sum, and
the sum is clamped to −256 … +255 (N (die)). The two orderings give the same
DAC value for every vector here; the 14-bit intermediate is not observable.

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| TL-01 | att_total = 0, phase index 255 (peak) | level = 0 → mag = ((1018 \| 1024) << 2) >> 0 = 8168. Operator peak = +8168 (13-bit magnitude, not 8191). | N + D |
| TL-02 | TL = 8 (6 dB), phase index 255 | level = 64 << 2 = 256 → exp index (~256)&$FF = $FF → 8168 >> 1 = 4084. Exactly half of TL-01. | M + D |
| TL-03 | TL = 127, EG att = 0, peak phase | level = 127 × 8 × 4 = 4064 → shift 15, mag = 0. Operator silent. | D |
| TL-04 | att_total = 0, phase index 0 | level = $859 = 2137 → shift 8, mantissa index (~2137)&$FF = $A6 → mag = ((exp[$A6] \| $400) << 2) >> 8 = (581 + 1024) × 4 >> 8 = 25 exactly. Sine table has no exact-zero entry: phase 0 and phase 512 differ only in sign (+25 / −25). | N + D |
| TL-05 | Channel with algorithm 7, all four ops TL = 0 at peak | The channel clamps: the 9-bit value handed to the DAC is +255 at the peak and −256 at the trough. *(corrected: the die model clamps the 9-bit sum, see above)* | N (die) |

## Group 4 — Detune / multiple → phase increment

Phase accumulator: 20 bits; the top 10 bits index the sine (N, die). From the
manual's frequency formula

```
f = fnum * 2^(block - 1) * Fs / 2^20
```

the per-sample increment before detune and multiple is
`inc0 = (fnum << block) >> 1` (D). At block 0 the F-number LSB is therefore
lost — the manual formula has 2^(B−1). Then

```
inc1 = inc0 + detune(kc, DT)          ; DT bit 6 (values 4-7) subtracts; DT 0 and 4 add 0
inc  = MUL == 0 ? inc1 >> 1 : inc1 * MUL   ; MUL 0 = ×0.5, 1..15 = ×MUL   (M)
phase += inc (mod 2^20)
```

The detune table (M, Yamaha OPN application manual; index = key code 0-31):

| DT | kc 0-15 | kc 16-31 |
|---|---|---|
| 1 | 0 0 0 0 1 1 1 1 1 1 1 1 2 2 2 2 | 2 3 3 3 4 4 4 5 5 6 6 7 8 8 8 8 |
| 2 | 1 1 1 1 2 2 2 2 2 3 3 3 4 4 4 5 | 5 6 6 7 8 8 9 10 11 12 13 14 16 16 16 16 |
| 3 | 2 2 2 2 2 3 3 3 4 4 4 5 5 6 6 7 | 8 8 9 10 11 12 13 14 16 17 19 20 22 22 22 22 |

The table unit is one LSB of `inc0`, i.e. Fs / 2^20 ≈ 0.0508 Hz at NTSC, applied
before the multiple (D). *(corrected)* DT 1 at kc 12-15 was transcribed as
1 1 1 1; the die model computes every entry as a ROM value
{16, 17, 19, 20, 22, 24, 27, 29}[row] shifted by the key code's octave, which
gives 2 2 2 2 there and reproduces the other 92 entries exactly, so the
transcription is taken to be the error. Key codes 29-31 use the kc 28 entry. Whether `inc1` wraps in 17 bits when a negative detune
underflows a small `inc0` is `?` (open question 6).

Frequency of a sine at the output: `f = inc × Fs / 2^20`.

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| PG-01 | fnum $439, block 4, DT 0, MUL 1 | inc0 = ($439 << 4) >> 1 = 8648; inc = 8648; f = 8648 × 53267.04 / 1048576 = 439.3 Hz (Sega's A4). | M + D |
| PG-02 | fnum $439, block 4, DT 0, MUL 0 | inc = 4324; f = 219.7 Hz (one octave down). | M + D |
| PG-03 | fnum $439, block 4, DT 1, MUL 2 | kc = 18 (see EG-07) → detune 3; inc = (8648 + 3) × 2 = 17302; f = 878.9 Hz. *(corrected arithmetic)* | M + D |
| PG-04 | fnum $439, block 4, DT 5 (= −1), MUL 1 | inc = 8648 − 3 = 8645; f = 439.2 Hz. Pair with PG-01 → 0.15 Hz beat (the classic OPN chorus). | M + D |
| PG-05 | fnum $001, block 0, DT 0, MUL 1 | inc0 = 0 (LSB lost at block 0) → operator phase never advances. | D from manual formula |
| PG-06 | fnum $7FF, block 7, DT 3, MUL 15 | kc = 31 → detune 22; inc = (($7FF << 7) >> 1 + 22) × 15 = (131,008 + 22) × 15 = 1,965,450, taken mod 2^20 = 916,874. *(corrected arithmetic: $7FF << 6 = 131,008)* | D (`?` on the intermediate width; the die model masks the pre-multiple value to 17 bits, which this vector does not exercise) |

## Group 5 — Algorithms and feedback

Algorithm connections (M), op numbers in algorithm order, `→` = modulates:

| ALG | Topology | Carriers |
|---|---|---|
| 0 | 1→2→3→4 | 4 |
| 1 | (1 + 2)→3→4 | 4 |
| 2 | (1 + (2→3))→4 | 4 |
| 3 | ((1→2) + 3)→4 | 4 |
| 4 | (1→2) + (3→4) | 2, 4 |
| 5 | 1→2, 1→3, 1→4 | 2, 3, 4 |
| 6 | (1→2) + 3 + 4 | 2, 3, 4 |
| 7 | 1 + 2 + 3 + 4 | 1, 2, 3, 4 |

Op1 self-feedback (M): FB 0 = none; FB 1..7 = π/16, π/8, π/4, π/2, π, 2π, 4π
peak modulation. In the 10-bit phase domain (1024 = 2π) that is
`(out_prev + out_prev2) >> (10 − FB)` for FB ≥ 1 with `out` the 14-bit operator
output (D): FB 7 at peak → (8168 × 2) >> 3 = 2042 ≈ 2 cycles = 4π ✓; FB 1 →
16336 >> 9 = 31.9 ≈ π/16 ✓. The feedback input is the average of the two
previous outputs (N).

Modulation input from a normal modulator into the next operator's phase is
`modulator_out >> 1` in 10-bit phase units (`?`, open question 7).

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| ALG-01 | ALG 7, op2-op4 TL = 127, op1 TL = 0 | Channel output equals op1 output alone; a pure sine at the op1 frequency. | M |
| ALG-02 | ALG 0, op1-op3 TL = 127, op4 TL = 0 | Channel output is a pure sine from op4: the silent modulators contribute zero phase. | M |
| ALG-03 | ALG 4, op1 TL = 127, op3 TL = 127, ops 2 and 4 TL = 0, same frequency, same phase | Output = 2 × single-carrier amplitude (two carriers summed), clamped at 8191. | M |
| ALG-04 | ALG 7, op1 only audible, FB = 7 vs FB = 0 | FB 0: pure sine (harmonics ≥ 2 absent). FB 7: strongly saw-like spectrum; peak phase deviation ≈ 2042 phase units (4π). | M + D |

## Group 6 — LFO

The LFO has a 7-bit position counter (128 steps per cycle) that advances once
every `period` samples (N, Nemesis). *(corrected)* In the die model the
sample counter is compared against the constant every internal cycle and the
reset happens in the cycle after the count reaches it, while the count still
increments once in that same sample, so the period **is** the constant:

| $22 bits 2-0 | period (samples per step) | f at 8 MHz clock (D) | f at 8 MHz printed in the manual | f at NTSC Fs (D) |
|---|---|---|---|---|
| 0 | 108 | 4.02 Hz | 3.98 Hz | 3.85 Hz |
| 1 | 77 | 5.64 Hz | 5.56 Hz | 5.40 Hz |
| 2 | 71 | 6.11 Hz | 6.02 Hz | 5.86 Hz |
| 3 | 67 | 6.48 Hz | 6.37 Hz | 6.21 Hz |
| 4 | 62 | 7.00 Hz | 6.88 Hz | 6.71 Hz |
| 5 | 44 | 9.86 Hz | 9.63 Hz | 9.46 Hz |
| 6 | 8 | 54.3 Hz | 48.1 Hz | 52.0 Hz |
| 7 | 5 | 86.8 Hz | 72.2 Hz | 83.2 Hz |

The manual's printed column fits `period + 1` (8 000 000 / 144 / (128 × 109)
= 3.98 Hz), which is where the first draft's 109/78/72/68/63/45/9/6 came from.
The die-derived counter is taken as authoritative for the period because it is
the mechanism, not a rounded table; the disagreement with the printed figures
(up to 20 % at settings 6 and 7) is recorded as open question 19 pending a
hardware recording. Tests assert on the period in samples.

LFO disabled ($22 bit 3 = 0): the position counter is held at 0 (N). What AM
contributes at that held position when AMS ≠ 0 is `?` (open question 8).

### AM

AM is a triangle over the 128-step cycle with 7-bit amplitude, right-shifted by
AMS (M for the dB depths, N for the shape):

| AMS | shift | peak attenuation | manual depth |
|---|---|---|---|
| 0 | off | 0 | 0 dB |
| 1 | >> 3 | 15 × 0.09375 = 1.4 dB | 1.4 dB |
| 2 | >> 1 | 63 × 0.09375 = 5.9 dB | 5.9 dB |
| 3 | << 0 | 126 × 0.09375 = 11.8 dB | 11.8 dB |

Applies only to operators with $60+ bit 7 set (M).

### PM

PM modulates the 11-bit F-number before the block shift, in units of **half an
F-number LSB**, from a 32-position waveform (the 5 top bits of the LFO counter:
8 steps rising, 8 falling, then the negative mirror) whose magnitude depends on
the F-number's top 7 bits and PMS (N, Nemesis). Manual peak deviations (M):

| PMS | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| cents | 0 | ±3.4 | ±6.7 | ±10 | ±14 | ±20 | ±40 | ±80 |

For an F-number with only bit 10 set ($400) the quarter-cycle magnitudes at
PMS 7 are 0 0 32 48 64 64 80 96 half-LSB units, i.e. a peak of 48 F-number
LSBs = 48 / 1024 = 4.7 % ≈ 79 cents, matching the manual's ±80 (N + D). Each
lower F-number bit contributes half as much (bit 9 → peak 48 half-units, bit 8
→ 24, …, bit 4 → 3 at PMS 7); F-number bits 3-0 do not take part (N). The
full magnitude table by PMS is `?` at row-level (open question 9); the PMS 7
column and the manual's cents are the anchors tests should use.

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| LFO-01 | $22 = $08 (enable, select 0) | LFO position advances by one every 108 samples; 128 × 108 = 13,824 samples per cycle (3.85 Hz NTSC). *(corrected)* | N (die) + D |
| LFO-02 | $22 = $0F | Position advances every 5 samples; 640 samples per cycle (83.2 Hz NTSC). *(corrected)* | N (die) + D |
| LFO-03 | $22 = $0E, AMS = 3, AM bit set, TL = 0, carrier at att 0 | Output amplitude swings between 0 dB and −11.8 dB (126 EG steps) once per 1,024 samples (8 × 128). *(corrected)* | M + N (die) |
| LFO-04 | AMS = 1, otherwise as LFO-03 | Depth 15 EG steps (1.4 dB). | M + D |
| LFO-05 | $22 = $0F, PMS = 7, fnum $400, block 4 | Effective F-number swings $400 ± 48 → phase increment 8192 ± 384 → frequency swings ±4.7 % (≈ ±80 cents) at 83.2 Hz (640-sample cycle). *(corrected rate)* | M + N + D |
| LFO-06 | $22 = $00 (disabled), PMS = 7 | No pitch modulation: the LFO counter holds at position 0 and PM at position 0 is 0. | N |

## Group 7 — SSG-EG

$90+ bits: 3 = enable, 2 = attack (invert), 1 = alternate, 0 = hold (M). With
SSG-EG enabled (values 8-15):

- The envelope "end" is attenuation ≥ $200 (48 dB), not $3FF (N).
- Decay-side envelopes run 4× faster: *(corrected)* the increment applied at
  each EG update is multiplied by 4 in decay-1, decay-2 and release (N (die);
  below rate 48 this is the same as raising the rate by 8, not 4), so a
  rate-62 ramp climbs 32 per EG step and covers 0 → $200 in 16 steps. The
  attack curve itself is not accelerated (N). Open question 10 is answered by
  the die model: all three decay-type phases.
- When `att >= $200`:
  - not hold, not alternate (8, 12): restart — phase accumulator reset to 0,
    attenuation reset to 0, attack again (repeat) (N).
  - alternate (10, 14): the inversion flag toggles and the envelope restarts
    from the attack phase (`?` whether the phase generator is also reset on
    alternate repeats — open question 11).
  - hold (9, 11, 13, 15): the envelope holds; with alternate the inversion flag
    toggles once first (M shapes; N for the toggle).
- Output attenuation while inverted = `($200 − att) & $3FF` (N).
- Key off clears the inversion; if the operator was inverted at key off the
  attenuation is replaced by `($200 − att) & $3FF` so the audible level is
  continuous into release (N).

Manual shapes (level over time, high = loud):

| Value | Bits | Shape |
|---|---|---|
| 8 | 1000 | `\|\|\|` repeated decay |
| 9 | 1001 | `\___` decay once, hold silent |
| 10 | 1010 | `\/\/` decay, rise, decay, … |
| 11 | 1011 | `\‾‾‾` decay once, then hold loud |
| 12 | 1100 | `/|/|` repeated rise |
| 13 | 1101 | `/‾‾‾` rise once, hold loud |
| 14 | 1110 | `/\/\` rise, decay, rise, … |
| 15 | 1111 | `/___` rise once, hold silent |

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| SSG-01 | SSG = 8, AR = 31, D1R = 31 (rate 62 → +8 × 4 = +32/step), D1L = 15 | Attenuation ramps 0 → $200 in 16 EG steps (48 samples), then restarts at 0 with the phase accumulator at 0. Audible: a 48-sample sawtooth envelope repeating. *(corrected for the ×4 increment)* | N (die) + D |
| SSG-02 | SSG = 9, same rates | One ramp to $200 (48 samples) then the operator stays silent (held at ≥ $200 → reported as $3FF) until key off. *(corrected)* | M + N |
| SSG-03 | SSG = 10, same rates | Ramp down 48 samples, then inversion toggles: audible level ramps back up over the next 48 samples, repeating (triangle, 96-sample period). The phase accumulator is not reset on these alternate repeats in the die model (open question 11). *(corrected)* | M + N |
| SSG-04 | SSG = 11, same rates | After the first ramp the inversion toggles and holds: audible level returns to 0 dB and stays there until key off. | M + N |
| SSG-05 | SSG = 15 vs SSG = 9 | 15 starts silent and rises once, then holds silent; 9 starts loud, decays once, holds silent. *(refined)* The mode-15 rise stops one SSG step (32 = 3 dB) short of 0 dB: the inversion clears in the same step the level reaches $200, so the last inverted value shown is $020 (N (die)). | M + N (die) |
| SSG-06 | SSG = 8, D1R = 20 (rate 40 at kc 0) vs SSG = 0, D1R = 20 | The SSG ramp runs 4× faster than the plain decay at the same register rate over the 0 → $200 span, within one 12-sample update. *(rate raised from 10 so the plain ramp fits a test)* | N (die) |

## Group 8 — Timers and CSM

| Timer | Register | Tick | Period | Range (NTSC) | Tag |
|---|---|---|---|---|---|
| A | 10-bit ($24 × 4 + $25 & 3) | 1 sample | (1024 − NA) samples | 1 … 1024 samples = 18.8 µs … 19.2 ms | M |
| B | 8-bit ($26) | 16 samples | (256 − NB) × 16 samples | 16 … 4096 samples = 0.30 … 76.9 ms | M |

The manual states these as 18 µs × (1024 − NA) and 288 µs × (256 − NB) at an
8 MHz clock; 18 µs is exactly one 144-cycle sample at 8 MHz, so the
sample-count form above is the clock-independent statement (D).

Control ($27, M):

- Load bit 0→1: counter loaded from NA/NB and starts; on overflow the counter
  reloads from the register value and keeps running (M).
- Load 1→0: counter stops.
- Enable bit (2/3) set: an overflow sets the corresponding status bit; clear:
  overflow does not set it (the timer still runs and CSM still fires) (M).
- Reset bit (4/5) written 1: clears the status bit. The reset bits are
  one-shot; they do not need to be written back to 0 (M).
- Changing NA/NB while running takes effect at the next reload (N).

CSM (bits 7-6 = 10 or 11): every timer A overflow issues a key-on to all four
operators of channel 3 that are not keyed on through $28, followed by a key-off
of the same operators one sample later, so the channel retriggers at the
timer A rate (M for the behaviour; N for the pulse width, `?` on exact sample
alignment — open question 12). Special mode (bits 7-6 ≠ 00) is also what gives
channel 3 per-operator frequencies (Group 10).

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| TMR-01 | $24 = $FF, $25 = $03 (NA = 1023); $27 = $05 (enable A, load A) | Status bit 0 set after 1 sample, and every sample after that until reset. | M + D |
| TMR-02 | NA = $3E0 (992), $27 = $05 | Status bit 0 first set 32 samples after load; overflows every 32 samples (600 µs NTSC). | M + D |
| TMR-03 | $26 = $C0 (NB = 192), $27 = $0A (enable B, load B) | Status bit 1 first set after 64 × 16 = 1024 samples (19.2 ms NTSC). | M + D |
| TMR-04 | TMR-02 then $27 = $15 (reset A while loaded) | Status bit 0 cleared immediately; timer keeps running; bit 0 set again at the next 32-sample overflow. | M |
| TMR-05 | NA = 992, $27 = $01 (load A, enable A = 0) | Timer runs and reloads, but status bit 0 never becomes 1. | M |
| TMR-06 | $27 = $85 (CSM + load A + enable A), NA = 992, channel 3 operators keyed off via $28 | Channel 3 restarts its envelope every 32 samples without any $28 write; a $4000 read shows bit 0 pulsing at the same rate. | M |

## Group 9 — DAC, 9-bit output and the ladder effect

- $2B bit 7 = 1 replaces channel 6's FM output with the DAC value; the FM
  operators of channel 6 keep running and resume audibly when the bit is
  cleared (M).
- $2A is 8-bit unsigned, $80 = centre; the DAC path is 9 bits wide on the
  YM2612, so the register value occupies the top 8 bits and the LSB comes from
  the test register $2C (N, Nemesis "9-bit DAC" finding; exact bit `?`, open
  question 13).
- Each channel's 14-bit accumulator is truncated to its top 9 bits before the
  analogue stage: −256 … +255 per channel (N).
- The six channel values are time-multiplexed onto the MOL/MOR pins, each
  channel occupying one sixth of the sample period (≈ 3.1 µs at NTSC); the
  external low-pass filter (and Sega's op-amp mixer) integrates them, so the
  *listened* level of one channel at full scale is 1/6 of the pin swing (N).
  Channel order within the sample is `?` (open question 14).
- L/R bits in $B4+ gate a channel into the left/right multiplex slots; both
  clear = the channel is absent from both outputs (M).
- **Ladder effect (YM2612 only).** The discrete YM2612 DAC has a crossover
  discontinuity: positive and negative samples sit on two ladders that do not
  meet at zero, so the output for value 0 with the sign bit set differs from
  value 0 with it clear. Small signals — the tail of a release, a quiet
  modulated channel — therefore acquire a square-wave component at the carrier
  frequency, the well-known "YM2612 buzz" that is absent on the YM3438 (N,
  Nemesis / spritesmind ladder-effect measurements). The size of the gap in
  9-bit LSBs is `?` (open question 15).
- **YM3438** (CMOS, used from Model 2 VA1.8 / Model 3 and inside the integrated
  ASICs): identical digital core and register map; no ladder discontinuity;
  different output level and drive. A YM3438-mode test must see the same
  digital 9-bit values with a monotonic DAC transfer.

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| DAC-01 | $2B = $80, $2A = $80, channel 6 L = R = 1 | Channel 6 contributes 0 to both outputs (centre). | M |
| DAC-02 | $2B = $80, $2A = $FF | Channel 6 contributes +254 (9-bit: ($FF − $80) << 1) — the maximum positive DAC value is one 9-bit LSB short of the FM maximum of +255 unless the test-register LSB is set. | N + D (`?` on the LSB) |
| DAC-03 | $2B = $80, $2A = $00 | Channel 6 contributes −256. | N + D |
| DAC-04 | FM channel at TL-01 peak, $2B = 0 | Channel value = 8168 >> 5 = +255; a −8168 sample gives −256 (arithmetic shift of the 14-bit value). | N + D |
| DAC-05 | Two channels both at +255, L = 1 | Left pin integrates to 2 × 255 / 6 of full swing; there is no digital clamp across channels — clamping is per channel. | N |
| DAC-06 (YM2612 vs YM3438) | Channel output alternating between −1 and 0 | YM2612: a visible step of several LSBs (the ladder gap) between the two levels. YM3438: a step of exactly one LSB. | N (`?` magnitude) |

## Group 10 — Channel 3 special mode

With $27 bits 7-6 ≠ 00 the four operators of channel 3 take independent
frequencies (M):

| Operator (algorithm order) | F-number low | Block / F-number high |
|---|---|---|
| op1 | $A9 | $AD |
| op2 | $AA | $AE |
| op3 | $A8 | $AC |
| op4 | $A2 | $A6 |

So the ordinary channel-3 registers ($A2/$A6) keep driving op4 only. The
$AC-$AE writes use their own shared latch, committed by the following
$A8-$AA write (N). Key code (and therefore rate scaling and detune) is
computed per operator from that operator's own block/F-number (N). In normal
mode (bits 7-6 = 00) all four operators use $A2/$A6 and the $A8-$AE registers
are stored but ignored (M).

The op2/op3 ↔ $AA/$A8 assignment above follows the manual's operator
numbering; the mapping of "op2"/"op3" onto register slots +8/+4 is the same
S1 S3 S2 S4 ordering as Group 1. It is flagged `?` (open question 16) only in
the sense that the two orderings are easy to swap and a test should pin it
against a hardware recording rather than this table.

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| CH3-01 | $27 = $40; $AD/$A9 = block 4 / $439; $AE/$AA = block 5 / $439; $AC/$A8 = block 3 / $439; $A6/$A2 = block 4 / $21A; ALG 7, all TL 0 | Phase increments 8648, 17296, 4324 and 4304: four sines at 439.3, 878.6, 219.7 and 218.6 Hz respectively. *(corrected: $21A << 4 >> 1 = 4304 → 218.6 Hz, not 219.6)* | M + D |
| CH3-02 | Same registers, $27 = $00 | Four sines all at the $A2/$A6 frequency (218.6 Hz). | M |
| CH3-03 | $27 = $40, op1 block 7 / fnum $7FF, op4 block 0 / fnum $100, RS = 3 on both | op1 kc = 31 → ks = 31; op4 kc = 0 → ks = 0. The two operators' effective rates differ by 31 for the same register rate. | N + D |

## Group 11 — Sample rate and the 24-slot multiplex

- One output sample per 144 φM cycles; 24 internal cycles per sample, one
  operator per cycle (M/N). Register writes therefore take effect at
  operator-slot granularity, not sample granularity; a test that needs
  sample-exact behaviour must apply writes at a sample boundary and compare
  from the *next* sample (N).
- The EG steps every 3 samples (Group 2); the LFO steps every `period` samples
  (Group 6); timer A ticks every sample and timer B every 16 (Group 8). All
  three run from the same internal clock, so their phase relationship is fixed
  from reset (N).
- Per sample the chip produces one left and one right value, each the
  multiplex of up to six 9-bit channel values (Group 9).

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| CLK-01 | NTSC clock, 1 second of samples | 53,267 samples (53,267.04 exactly) — the run-time reference for every ms figure in this document. | D |
| CLK-02 | 144 φM cycles | Exactly one sample produced; EG counter advances on every third; timer A decrements once. | M + N |
| CLK-03 | PAL clock | 52,781 samples per second; all sample-count vectors unchanged, all ms figures × 1.0092. | D |

## Group 12 — Power-on / reset state

Asserting /IC (reset) clears every register to 0 (M). Consequences a test can
assert on:

| Item | State after reset | Consequence | Tag |
|---|---|---|---|
| $22 | 0 | LFO disabled, counter at 0 | M |
| $24-$27 | 0 | Timers stopped, flags disabled, channel 3 normal mode, CSM off | M |
| $28 | — | All operators keyed off, envelopes at $3FF in release | M/N |
| $2B | 0 | DAC disabled | M |
| $30-$3F | 0 | DT 0, MUL 0 (×0.5) | M |
| $40-$4F | 0 | TL 0 = maximum volume — an operator becomes audible the moment it is keyed on with L/R set | M |
| $50-$8F | 0 | AR/D1R/D2R/RR 0: an operator keyed on would never leave its $3FF attenuation (attack rate 0 = no change) | M |
| $A0-$AE | 0 | fnum 0, block 0 → phase increment 0 | M |
| $B0-$B2 | 0 | Algorithm 0, feedback 0 | M |
| $B4-$B6 | 0 | L = R = 0: every channel muted until the driver writes panning. *(disagreement)* The die model resets the pan bits to L = R = 1; nothing audible follows either way because every rate is 0, so tests assert the silence, not the pan bits (`?`, open question 20) | M vs N (die) |
| Status | $00 | Not busy, no timer flags | M |
| Phase accumulators | 0 | | N |
| Address latches | 0 | | `?` |

Required /IC pulse width is `?` (open question 17).

### Vectors

| ID | Input | Expected observable | Tag |
|---|---|---|---|
| RST-01 | Reset, then read $4000 | $00. | M |
| RST-02 | Reset, then $28 ← $F0 only | Silence: every rate is 0 (attack rate 0 = no change), whatever the pan bits hold. | M |
| RST-03 | Reset; $B4 ← $C0; $50/$54/$58/$5C ← $1F; $A4 ← $24; $A0 ← $39; $28 ← $F0 | Channel 1 op1-op4 audible at once with TL 0 (instant attack); algorithm 0 means only op4 is a carrier; reset MUL 0 = ×0.5 so the increment is 4324 (219.7 Hz). *(corrected: the draft wrote AR for op1 only, $A4 ← $22 is fnum $239, and MUL 0 halves the frequency)* | M + D |
| RST-04 | Write registers, reset, read back behaviour | All Group 1 latches (including the shared $A4 latch) cleared. | M (`?` for latches) |

## Sources

Public sources only; no emulator source was consulted.

1. Yamaha, *YM2612 (OPN2) Application Manual* — register map, TL/D1L/RS/detune
   tables, LFO frequency and AM/PM depth tables, timer formulae, feedback table,
   algorithm diagrams, channel 3 special-mode register assignment.
2. Yamaha, *YM2608 (OPNA) Application Manual* — the OPN family detune and rate
   tables reproduced in Group 4, and the SSG-EG shape diagrams.
3. Sega, *Genesis Software Manual* / *Genesis Technical Overview* (1989-1991) —
   68k/Z80 port addresses, clock derivation, the 17/83-T-state write-spacing
   guidance, the A4 = $439/block 4 example note table.
4. Nemesis, YM2612 hardware test threads, gendev.spritesmind.net forum
   ("YM2612 emulation: some tests", from 2008, and the follow-up EG/LFO/timer/
   register-latch threads) — EG step every 3 samples, rate increment table,
   instant attack at rate ≥ 62, key-on/no-retrigger rules, SSG-EG $200
   threshold and inversion, shared F-number latch, busy-flag width, LFO period
   counter, PM half-LSB units, 9-bit DAC and test register, ladder effect.
5. M. Gambrell and O. Niemitalo, "OPLx decapsulated" (2008) — log-sine and
   exponential ROM contents and the mantissa/shift output stage shared by the
   OPN2 core.
6. siliconpr0n.org YM2612 and YM3438 die photographs (J. McMaster) — the
   24-slot operator pipeline, 20-bit phase accumulator, 10-bit EG, and the
   discrete-ladder DAC on the YM2612 versus the CMOS YM3438.

## Open questions

Numbered so vectors can point at them. Each stays open until a hardware
recording or a primary-source page is attached to this document.

1. Does an **address** write raise the busy flag, and for how long? (Group 1)
2. Is a data write that arrives during the busy window dropped, or queued and
   applied late? (Group 1; affects drivers that ignore the busy flag.)
3. Exactly which sub-$30 registers are ignored when written through Part II —
   all of $21-$2C, or only some? (REG-07)
4. Exact rounding of the attack-phase step, and whether the increment index
   for attack uses the same row table as decay at rates 60-61. (Group 2)
5. Behaviour of effective rates 1-3 (register value 1 with no key scaling gives
   rate 2): does the envelope advance at all, and with which increment row?
   (Group 2)
6. Bit width of the pre-multiple increment when a negative detune underflows
   a small `inc0`: does it wrap in 17 bits (large positive increment) or clamp
   at 0? (PG-06 and low-octave negative-detune patches) *Die model: wraps in
   17 bits.*
7. Shift applied to a modulator's 14-bit output before it is added to the next
   operator's 10-bit phase (>> 1 assumed). (Group 5) *Die model: >> 1, and the
   feedback input is the sum of the two previous op1 outputs shifted by
   10 − FB, as Group 5 states.*
8. AM contribution when the LFO is disabled and AMS ≠ 0: is the held position
   0 treated as zero attenuation or as the triangle's value at position 0?
   (LFO-06 sibling case) *Die model: the triangle's value at position 0, which
   is its maximum (126 before the AMS shift).*
9. Full PM magnitude table for PMS 1-6 by F-number bit; only the PMS 7 column
   and the manual's cents figures are pinned. (Group 6)
10. Which SSG-EG phases receive the 4× rate acceleration — decay-1/decay-2
    only, or release as well. (SSG-06)
11. Whether an SSG-EG alternate repeat resets the phase accumulator like the
    plain repeat does. (SSG-03) *Die model: no; only modes 8 and 12 reset it.*
12. CSM key-on pulse: is the key-off one sample (24 cycles) after the key-on,
    and where within the sample does it land relative to channel 3's operator
    slots? (TMR-06)
13. Which $2C bit supplies the DAC's 9th (LSB) bit, and which bit routes the
    DAC to all channels. (DAC-02) *Die model: bit 3 is the LSB, bit 5 routes
    the DAC to every slot.*
14. Order of the six channels within the multiplexed output slot sequence, and
    the exact slot width. (Group 9) *Die model: four cycles per channel in the
    order 2, 6, 4, 1, 5, 3.*
15. Magnitude, in 9-bit LSBs, of the YM2612 ladder gap between the positive and
    negative halves. (DAC-06)
16. Independent confirmation that channel-3 special-mode op2/op3 are $AA/$A8
    respectively rather than the reverse. (CH3-01)
17. Minimum /IC pulse width in φM cycles, and whether the address latches and
    LFO/EG counters are cleared by /IC or only the register file. (Group 12)
    *Die model: reset clears everything, latches and counters included.*
18. Whether reads of $4001-$4003 return the status byte on YM3438 as they do on
    YM2612. (Group 1)
19. LFO period: the die model's counter gives exactly 108/77/71/67/62/44/8/5
    samples per step; the manual's printed frequencies fit one sample more. A
    hardware LFO recording would settle which the silicon does. (Group 6)
20. Pan bits after /IC: the manual's "all registers 0" versus the die model's
    L = R = 1. (Group 12)

## Nuked-OPN2 cross-check

`src/test/java/com/openggf/audio/synth/TestYm2612HardwareBehaviour.java` runs
every vector above against the port (through `NukedOpn2`'s public state where
the facade hides the quantity, through `Ym2612Chip` where it does not). The
port is bit-exact with the pinned C build, so each failing row was decided by
re-deriving it: from this document's own formulas and printed tables first, and
from the die model (`ym3438.c` at the pinned commit) where the printed sources
do not reach. No port change was made. The decisions:

| Row | Finding | Decision |
|---|---|---|
| REG-05 | $F5 has channel field 5, which the doc's own table maps to channel 5, not 6. | Doc corrected. |
| Group 1 latch | The die model shares one $A4/$AC latch across both parts. | Noted; within-part sharing asserted. |
| EG-03 / EG-06 | The EG replaces any stored level ≥ $3F0 with $3FF on the next sample; D1L 15 hands decay-1 to decay-2 at $3E0. | Doc corrected (D2R 31 added to EG-03; ramp ends $3F0 → $3FF). |
| EG-04 | RS 0 still adds kc >> 3, so "rate 52" needs kc < 8. | Doc corrected (kc pinned). |
| Rate table ≥ 48 | Same increments per four steps, different phase. | Noted; means asserted. |
| TL-04 | ≈ 25 is exactly 25 by the doc's own formula. | Doc tightened. |
| TL-05 | Per-operator 9-bit truncation before a 9-bit channel clamp; the DAC value is the same. | Doc corrected to the observable +255 / −256. |
| Detune DT1 kc 12-15 | Transcribed 1 1 1 1; the ROM-and-shift structure that reproduces the other 92 entries gives 2 2 2 2. | Doc corrected. |
| PG-03, PG-06, CH3-01 | Arithmetic slips (878.9 Hz; $7FF << 6 = 131,008 → 916,874; $21A → 4304 → 218.6 Hz). | Doc corrected. |
| LFO periods | Counter period equals the constant (108 …), not constant + 1; the manual's Hz column fits + 1. | Doc corrected to the die model; recorded as open question 19. |
| SSG-EG ×4 | The increment is multiplied by 4 (rate + 8 equivalent), in decay-1, decay-2 and release. | Doc corrected; SSG-01..03 timings 48 / 96 samples; open question 10 answered. |
| SSG-05 | Mode 15's rise stops one step short of 0 dB when the inversion clears at $200. | Doc refined. |
| RST-03 | AR written for op1 only, $A4 ← $22 is fnum $239, MUL 0 halves the frequency. | Doc corrected. |
| RST pan bits | Die model resets L = R = 1; the manual says 0. | Recorded as open question 20; silence asserted. |
| Open questions 6, 7, 8, 11, 13, 14, 17 | The die model answers them. | Answers noted beside each; the questions stay open for hardware confirmation. |

Tolerances the test applies, and why: writes land at operator-slot granularity
(Group 11), so "on the first sample after key on" is asserted as within three
samples; timer B's 16-sample prescaler is free-running, so its first overflow
is asserted within one prescaler period; the facade drains each write's own bus
pacing (about 1.3 samples) through the core, so facade timer counts carry a
two-sample allowance; SSG-06 compares two ramps whose updates are 12 samples
apart, so 4× is asserted within one update.
