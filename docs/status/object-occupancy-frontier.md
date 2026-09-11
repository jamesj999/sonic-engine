# Object-slot occupancy: current state

**Measured at** `694745c09`, 2026-08-21; **per-object rows re-measured at `0fd7b7811`**,
2026-08-21, on a verified sweep (94 reports, 15,283 full-map frames, `Tests run: 800`). Every
per-object row carries the SHA it was measured at — a row without one is a fact with no expiry
date, which is how this page drifted between those two commits. Sources: `SlotOccupancyProbe` armed with
`OGGF_SLOT_PROBE=1 OGGF_SLOT_PROBE_FULL=1` over the full `-Ptrace-replay` sweep (94 reports,
29,832 comparable frames). The narrative is in the 2026-08-21 entries of
[trace-frontier-log.md](trace-frontier-log.md); this page is the state, so nobody has to read
eight of them to act.

**Nothing in the suite compares occupancy.** The probe decides no test outcome. Every number
here is invisible to CI.

## The three metric definitions, and why each replaced the last

| # | metric | replaced because |
|---|---|---|
| 1 | **per-slot presence** — a ROM slot the engine leaves empty | **Counts relocation as absence.** The same object at a different slot index produces a "missing" entry at the ROM's slot. Put `RING` at the top of the ranking; ring *counts* were equal on 83 of 87 divergent frames. |
| 2 | **per-type count deficit** — `romCount(T) - engCount(T)`, placement-invariant | **Ignored reserved slots.** The probe renders a parent-held child reservation as `RESERVED`, not a hex id, so slots that correctly model folded children read as absent. Put `BRIDGE` at the top at 1,969; crediting reservations leaves **19**. |
| 3 | **both-directions, reserved-credited** — short *and* over, with `RESERVED`/`UNATTRIB` credited against the shortfall | **Current.** A one-way metric reports an object that moved between slots as unchanged, and hides over-counts entirely — `Projectile` reads as "340 short" while the engine holds **373 more** than the ROM. |

Two definitions that follow from #3 and should be used as definitions, not rediscovered:

- **Reserved-slot credit.** `RESERVED` and `UNATTRIB` are engine occupancy. A shortfall covered
  by reserved slots on the same frame is **not** a deficit; it is a correctly folded composite.
- **The one-way / two-way axis.** *One-way* (short, ~no over) means the engine never makes them.
  *Two-way* (short and over comparable) means it makes them at the wrong time or in the wrong
  slot. These are different defects with different questions, and the axis cuts across every
  earlier family grouping.

## Fixed

| object | id | was | now |
|---|---|---|---|
| **CPZStaircase** (S2) | `0x78` | 966 short, per-frame shape exactly {3, 6} | **0 residual** (12 raw, all covered by `RESERVED` on the same frames). CPZ2 divergent frames 249 → 81; CPZ1 111 → 75. Landed `55a313ff4`. |

## Documented as CORRECT — do not re-target

Both reached the top of a ranking and both are correct code. Named here so the next ranking
does not rediscover them.

| object | id | why it looked wrong | why it is right |
|---|---|---|---|
| **BRIDGE** (S1) | `0x11` | 1,969 "deficit", metric #2 | Folds its logs into the parent **and reserves the child slots** (`allocateChildSlots`). 1,950 of 1,969 explained by `RESERVED` on the same frames; residual **19**. |
| **RING** (S1) | `0x25` | first place under metric #1 | Ring counts equal on 83 of 87 divergent frames — the rings exist, at other slot indices. Residual short 566/over 6 is real but third-order, not the "missing rings" the first ranking implied. |
| Vertical cannonball delete | — | read as `FixBugs`-only | **Both** ROM branches delete; the conditional is ordering alone. Engine margin `0xE0` = 224 matches `v_limitbtm2 + 224`. |

## Confirmed and unlanded

| object | id | shortfall | status |
|---|---|---|---|
| **CANNONBALL** (S1) | `0x20` | 581 short / 0 over | **Two defects diagnosed, fix parked.** `explode()` destroys and spawns instead of rewriting `obID` in place; plus an uncited `!isOnScreenX(256)` deletion `CBal` does not have. Must land together. **Prediction, PARTLY REFUTED at `0fd7b7811`:** the `0x20` half is still open, but the `0x3F` half is dead — EXPLOSION shows no conversion pairing with CANNONBALL (12 and 6 slots against 511/511 for the confirmed case), so fixing `0x20` should not be expected to move `0x3F`. See the two-way table's note. **And the 581 → 620 degradation is NOT recent:** `0x20`
measures 620 identically at `cae3ede3c` and `0fd7b7811` (28/98 in `SONIC_1_50`/`51`, 51/196 in
chain segments 8/9 and again in 30/31), so it shifted before `cae3ede3c` and has been stable
since — a different story from the prediction, not the same one. Independent confirmation of the
refutation from this side too: what the engine holds where the ROM has `0x20` is `0x20` itself
(41.9%), nothing (14.9%), then `0x15`/`0x78`/`0x25` — **`0x3F` is not a significant partner in
either direction.** Blocked on the convert-in-place capability — see [the design note](../architecture/designs/2026-08-21-object-convert-in-place.md). |

## One-way — the engine never makes them

The remaining value. Same question for each: *why is this never created?*

**Re-measured at `0fd7b7811`, 2026-08-21.** Every row below carries the SHA it was measured
at. A row without one is a fact with no expiry date, which is how this page drifted the first
time. `was` is the figure published at `694745c09`.

| game | object | id | short | over | fixtures | measured | was (`694745c09`) |
|---|---|---|---|---|---|---|---|
| S2 | RexonHead | `0x97` | 503 | 0 | 2 | `0fd7b7811` | 503 / 0 / 2 — unchanged |
| S1 | CANNONBALL | `0x20` | **620** | 0 | 6 | `0fd7b7811` | 581 / 0 / 5 — worse |
| S1 | MZ_BOSS | `0x73` | 279 | 0 | 2 | `0fd7b7811` | 279 / 0 / 2 — unchanged |
| S1 | FZ_BOSS | `0x85` | **225** | 0 | 3 | `0fd7b7811` | 210 / 0 / 2 — worse |
| S1 | SBZ_SAW | `0x6A` | 181 | 0 | 4 | `0fd7b7811` | 181 / 0 / 4 — unchanged |
| S1 | RING | `0x25` | **58** | 2 | 5 | `0fd7b7811` | 566 / 6 / 22 — **mostly gone** |
| S1 | CHAINED_STOMPER | `0x31` | **7** | 0 | 1 | `0fd7b7811` | 314 / 0 / 4 — **mostly gone** |

`Bubbles` (`0x24`) has moved off this table: it now measures **193 short / 66 over** across 2
fixtures at `0fd7b7811`, which is two-way, not one-way. `RING` and `CHAINED_STOMPER` are kept
only so the drop is visible; at 58 and 7 neither is worth a round, and both should be dropped
at the next re-measure if they hold.

**Check the reserved-slot question first for each** — several object families use
`allocateChildSlots`, and two commissioned targets have already turned out to be correct folds.

## Re-attributed — was the largest one-way target, is not one-way and is not its own defect

**BossExplosion (S2, `0x58`) and Eggrobo (S2, `0xC7`) are the same defect.**
**Re-measured at `0fd7b7811`, 2026-08-21**, on a verified sweep (94 reports, 15,283 full-map
frames). Do not work them separately.

**Read the denominator first.** Of **14,488** ROM `0x58` slots across the S2 fixtures:

| engine holds | slots | share |
|---|---|---|
| `0x58` — correct, same slot | **12,292** | **84.8%** |
| `-` — genuinely absent | 1,580 | 10.9% |
| **`0xC7`** — not converted | **511** | 3.5% |
| `0x5D` — not converted | 75 | 0.5% |
| `0xAF` | 28 | 0.2% |

An earlier version of this section quoted 70.6% absent and 22.2% converted. **Those were shares
of the divergent LINES, not of the object's occurrences** — a factor-of-seven difference in how
alarming the defect sounds. The engine gets 84.8% of BossExplosion slots exactly right; the
defect is a tail.

| what | measurement at `0fd7b7811` |
|---|---|
| reserved-slot credit | **none** — no reserved involvement on any S2 divergent frame. Not a fold. |
| metric #3, both directions | **1,324 short / 22 over**, 5 fixtures (was 1,725 / 17 / 6 at `694745c09`) |
| sampling density | **not a blip** — the DEZ shortfall is one contiguous multi-hundred-frame episode, far longer than the ~14-frame sample gap |
| "engine never makes them" | **false** — it holds a `BossExplosion` on the large majority of the episode's frames |

**The pairing is exact, and it reproduced across two independent sweeps.** Where the ROM has
`0x58` the engine holds `0xC7` on **511** slots; where the engine holds `0xC7` and the ROM does
not, the ROM holds `0x58` on **511** slots and anything else on 31. **511 = 511**, identically
at `cae3ede3c` and `0fd7b7811`. The same slots, counted twice under two names, in two different
tables of this page — one as the largest *one-way* target, the other as a *two-way* one.

**Mechanism: convert-in-place, the capability CANNONBALL is already parked on.** The ROM
rewrites the id of the live object rather than deleting and respawning:
`ObjC5_PlatformExplode` does `move.b #ObjID_BossExplosion,id(a0)`
(`docs/s2disasm/s2.asm:81762`), and `Obj5D_Main_Explode2` and `loc_3DFBA` (inside `ObjC7`) are
the same shape. The engine leaves the object under its original id, which reads as a
BossExplosion shortfall *and* an Eggrobo over-count simultaneously. This reading rests on the
disassembly alone and does not depend on the probe.

So this is **blocked on the same capability** as CANNONBALL — see
[the design note](../architecture/designs/2026-08-21-object-convert-in-place.md) — and should
land with that work. That fix is worth more than its CANNONBALL prediction implied: it should
move `0x58`, `0xC7`, `0x5D` and `0x3F` together.

**Still worth its own round:** the **10.9% genuinely absent** (1,580 slots). That is a real
absence, cleanly separated from the conversion defect, and it is the only part of BossExplosion
I would commission again.

**A trap for the next ranking.** Grouping by raw recorded id mixes games: `0x58` also appears in
S1 and S3K fixtures, but it is BossExplosion only in S2 (`ObjPtr_BossExplosion: dc.l Obj58`,
`s2.asm:30004`). This page *is* correctly game-filtered — filtering to S2 is what reproduces its
own fixture counts — but any NEW ranking must filter by game before it means anything.

## Two-way — made at the wrong time or place

Different question: *why is this mistimed?* A deficit-ranked list under-reports these, and for
three of them the engine holds **more** than the ROM.

**Re-measured at `0fd7b7811`, 2026-08-21.**

| game | object | id | short | over | fixtures | measured | was (`694745c09`) |
|---|---|---|---|---|---|---|---|
| S2 | Eggrobo † | `0xC7` | 6 | **542** | 1 | `0fd7b7811` | 6 / 523 / 1 |
| S2 | Rexon2 | `0x96` | 0 | **513** | 2 | `0fd7b7811` | 0 / 457 / 2 |
| S2 | Projectile | `0x98` | 317 | **617** | 22 | `0fd7b7811` | 340 / 373 / 22 — over much worse |
| S1 | EXPLOSION | `0x3F` | 179 | **369** | 16 | `0fd7b7811` | 282 / 275 / 15 |
| S2 | LeavesGenerator | `0x2C` | 197 | **358** | 5 | `0fd7b7811` | 262 / 115 / 5 — over much worse |
| S2 | EggPrison | `0x3E` | 291 | 104 | 2 | `0fd7b7811` | 301 / 96 / 4 |
| S2 | SmallBubbles | `0x0A` | 202 | 278 | 5 | `0fd7b7811` | 231 / 247 / 5 |
| S2 | ArrowShooter | `0x22` | 210 | 135 | 3 | `0fd7b7811` | 246 / 82 / 2 |
| S1 | LZ_CONVEYOR | `0x63` | 0 | **202** | 4 | `0fd7b7811` | 0 / 181 / 4 |
| S1 | FLOATING_BLOCK | `0x56` | 52 | 149 | 13 | `0fd7b7811` | 53 / 126 / 13 |
| S2 | Bubbles | `0x24` | 193 | 66 | 2 | `0fd7b7811` | 240 / 45 / 2 (was listed one-way) |

**Two of these are far more concentrated than the totals suggest**, measured at `0fd7b7811`:

- **`Projectile` over-count is 602 of 617 in ONE fixture** — `SONIC_2_90`, WFZ act 1. And
  **54.3%** of those are slots where the ROM holds *nothing at all*, so it is over-creation or
  late deletion, not misplacement. **Diagnosed 2026-08-21: it is a LIFETIME defect, not a
  spawn-condition one** — see below. Owner is whoever owns object lifetime, not object spawning.
- **`LeavesGenerator` over-count is 354 of 358 in chain segments 16/18/19.**

**They do not share a cause.** Disjoint fixtures, and disjoint partner distributions —
`Projectile`'s non-empty partners are `0xBD`/`0x19`, `LeavesGenerator`'s are `0x03`/`0x22`/`0x83`.
**Nor are they recent regressions:** both are *identical* at `cae3ede3c` and `0fd7b7811`, so they
degraded somewhere in the wider window before `cae3ede3c` and have been stable since. `EXPLOSION`
did move across that last window (211/424 → 179/369) and is still in motion.

† **Eggrobo is not a separate target.** 511 of its 523 over-counts are slots where the ROM has
converted the object to `BossExplosion` in place — see the re-attribution section above. It
lands with that work.

`Eggrobo`, `Rexon2` and `LZ_CONVEYOR` have **no shortfall at all** and were invisible to every
deficit-ranked list produced before metric #3. **`EXPLOSION`'s two-way shape is NOT a symptom of the CANNONBALL
conversion defect. Prediction tested and refuted at `0fd7b7811`, 2026-08-21.** If it were, the
conversion would leave the signature that confirmed BossExplosion/Eggrobo: the engine holding
the pre-conversion id where the ROM holds the post-conversion one, in matching counts. Measured:
the ROM holds `0x3F` where the engine holds `0x20` on **12** slots, and the engine holds `0x3F`
where the ROM holds `0x20` on **6** — against **511 and 511** for the confirmed case. There is
no pairing. What the engine actually holds where the ROM has `0x3F` is `0x3F` itself (58.6%),
nothing (22.6%), then a long tail with no dominant partner.

**So EXPLOSION is its own defect and nobody has looked at it.** It does not land with the
convert-in-place work, and it should not be sized into that programme.

## Scope limits

- **S3K is excluded from all per-object rows.** Its recorded id is the truncated low byte of a
  32-bit code pointer, not an identity, so grouping S3K entries by object groups by an artefact.
  Its presence divergence is real (10 of 10 fixtures) and cannot be attributed. See
  [known-discrepancies](known-discrepancies.md).
- **Sampling DENSITY limits what one sample can tell you.** `slot_dump` frames are ~14 apart,
  so a divergence shorter than that may be caught once or missed entirely. A one-sample
  divergence is therefore weak evidence about **how long** the divergence lasted — it says
  nothing about duration, which is the quantity that separates a real defect from a blip. The
  rings measurement is the worked example *of this density reading*: episodes persist a mean of
  4.06 samples, so they are sustained rather than momentary. **Establish persistence per object
  before treating a small divergence as an engine defect.**

  *Corrected 2026-08-21.* This bullet previously said a one-sample divergence "cannot be
  distinguished from an intra-frame timing difference". That mechanism is **retracted**: an S1
  lane established that objects and physics are recorded at the **same instant**, so there is no
  intra-frame offset between the two streams for a divergence to be confused with. The 4.06
  figure was cited as support for that phase claim and never supported it — it is evidence about
  persistence. **The operational advice is unchanged**, so a conclusion reached by establishing
  persistence still holds; only a conclusion that rested on the phase mechanism itself needs
  revisiting.
- **Occupancy is not landable as a comparison yet** — 88 of 94 fixtures diverge on presence
  alone. Fix, re-measure with the probe (free), and wire it in when the red set is reviewable.
