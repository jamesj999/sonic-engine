# S3K trace object identity: the object pointer tables cannot supply it

**Follow-up to [*S3K object-slot occupancy: what the divergence consists of*](2026-08-15-s3k-object-slot-occupancy-scoping.md),
which named this as its Step A.** Measured at `572960f8d` (develop merge base), in a clean
worktree of that commit.

**Nothing under `src/` changed. No trace sweep was run and none is owed. No Maven run was
required or performed** — every number below is derived from `s3k.gen` and the committed trace
fixture, offline.

**Headline: the round's premise is refuted.** The hypothesis handed to this round was that
inverting S3K's object pointer tables yields the missing *code address → object id* mapping.
The tables were located in the ROM and read successfully, and they do not contain the values the
recorder emits. **Only 7 of the 189 distinct code pointers in the HCZ complete-run `slot_dump`
stream — 4.26% of entries — are object pointer table entries at all.** The mapping the audit's
Step A assumed exists does not exist, and the reason is structural rather than incidental.

---

## 1. MEASURED — where the tables live, and that they read correctly

The object loader picks the table from `Current_zone` and stores its address in
`Object_index_addr`:

```
loc_1B6A8:
        move.l  #Sprite_ListingK,d0
        move.b  (Current_zone).w,d1
        cmpi.b  #$16,d1
        bhs.s   loc_1B6CA               ; use Sprite_ListingK
        cmpi.b  #$E,d1
        bhs.s   loc_1B6C4               ; use Sprite_Listing3
        cmpi.b  #7,d1
        bhs.s   loc_1B6CA               ; use Sprite_ListingK
loc_1B6C4:
        move.l  #Sprite_Listing3,d0
loc_1B6CA:
        move.l  d0,(Object_index_addr).w
```
(`docs/skdisasm/sonic3k.asm:37411-37430` — used for offset discovery only.)

Both `move.l #imm,d0` instructions are `203C` immediates, so the table addresses are readable
**from the ROM itself** rather than transcribed:

| ROM address | bytes | meaning |
|---|---|---|
| `0x01B6A8` | `20 3C 00 09 52 A2` | `move.l #$000952A2,d0` → **`Sprite_ListingK` = `0x000952A2`** |
| `0x01B6C4` | `20 3C 00 09 4E A2` | `move.l #$00094EA2,d0` → **`Sprite_Listing3` = `0x00094EA2`** |

Reading 256 longs at `0x00094EA2` and 185 at `0x000952A2` gives well-formed tables: every entry
is even and within the S&K half (`0x1000 < a < 0x200000`), the two bases are exactly `0x400`
apart as 256 longs require, and entry `$00` is `0x0001A51A` in both — `Obj_Ring`, matching the
disassembly's first `dc.l`. **The tables read correctly. The problem is not the read.**

- `Sprite_Listing3` (S3KL, zones 0-6): 256 entries, **219 distinct** addresses.
- `Sprite_ListingK` (SKL, zones 7-13): 185 entries, **171 distinct** addresses.

---

## 2. MEASURED — the engine never loads these tables, and never has

Task item 1 asked whether the code addresses are retained or discarded. **Neither: they are
never read.**

`Sonic3kObjectRegistry.getPrimaryName(int)` is a hand-written 256-arm Java `switch` returning
name *strings*
(`src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java:1329`), with
`getPrimaryName(int, S3kZoneSet)` delegating to a second such switch for SKL
(`:1316-1326`). Its own javadoc says the names were transcribed from
`Object pointers - SK Set 1.asm` at authoring time. Nothing in `src/main` reads
`Sprite_Listing3`, `Sprite_ListingK`, `Object_index_addr`, or any object pointer table from the
ROM — grep for those labels returns only Sonic 2 `ObjPtr_*` mentions in unrelated javadoc.

So there was no existing loading pipeline to invert. Inverting the tables requires first
*adding* a ROM reader for them. That is cheap (§1 shows it), and it is not the obstacle.

---

## 3. MEASURED — the obstacle: the SST first long is a live program counter, not a type

The S3K SST has **no object id field**. `sonic3k.constants.asm` defines offset `4` as
`render_flags` and `5` as `routine`; there is no `id` (`:9,20`). Offsets `0-3` are the code
pointer that `Process_Sprites` jumps through.

And that pointer is **overwritten during play**. The dominant S3K idiom is for an object to
replace its own dispatch pointer with a *sub*-routine address to advance state:

```
move.l  #Obj_TitleBanner_Main,(a0)
```

There are **1,758 such `move.l #<label>,(a0)` sites in `sonic3k.asm`**. After the first frame,
the value in the slot is whichever internal routine the object is currently in — an address that
by construction is *not* a table entry, because table entries are entry points.

This is measurable directly against the recorder's own output.

**MEASURED, HCZ complete-run `aux_state.jsonl.gz`, 2,388 `slot_dump` frames, 56,993 slot
entries, 189 distinct code pointers:**

| class | entries | share | distinct codes |
|---|---:|---:|---:|
| code **is** a `Sprite_Listing3` entry — invertible | 2,428 | **4.26%** | 7 |
| code is below the table's minimum (`0x1A51A`) | 2,016 | 3.54% | 2 |
| code is an internal routine inside the ROM's object code region | 52,549 | **92.20%** | 180 |

The seven invertible ones are the objects that dispatch through a `routine` byte instead of
rewriting their pointer — `Obj_Monitor` (`$01`), `Obj_Bubbler` (`$54`), `Obj_StarPost` (`$34`),
`Obj_EggCapsule` (`$81`), `Obj_HCZLargeFan` (`$39`), `Obj_HCZEndBoss` (`$9A`),
`Obj_HCZWaterSplash` (`$6D`). `Obj_Ring` is of that kind too
(`move.b routine(a0),d0 / jmp Ring_Index(pc,d1.w)`, `sonic3k.asm:35401-35405`) — it simply does
not appear in this run's dumps under its entry address.

Across the whole event stream including `object_near` (399,374 entries, 197 distinct codes) the
invertible share is **2.6%**.

**This refutes the round's premise on measurement, not on inspection.** None of the twelve
codes the previous audit named — `0x0002D95C`, `0x000384B2`, `0x000301DE`, `0x00020B74`,
`0x00025724`, `0x00018164`, `0x0001ABB6`, `0x00030834`, `0x00085AD2`, `0x0001365C`,
`0x0002D690`, `0x0002D8E2` — appears in either table.

---

## 4. MEASURED / INFERRED — containment is the obvious rescue, and it is unsound

The natural repair is *containment*: sort the distinct table entries and map a routine address
to the greatest entry at or below it, on the assumption that an object's sub-routines live
between its entry point and the next object's. Applied to the observed codes it looks
encouraging, and it independently reproduces several of the previous audit's co-occurrence
labels:

| code | containing entry | resolved to | previous audit's inferred label |
|---|---|---|---|
| `0x000384B2` | `0x00383BC` +`0xF6` | `Obj_HCZWaterSplash` `$6D` | fixed slot 5 occupant ✓ |
| `0x000301DE` | `0x002FEF6` +`0x2E8` | `Obj_HCZWaterWall` `$3B` | `WaterWallSprayChild` ✓ |
| `0x00025724` | `0x00256BE` +`0x66` | `Obj_HCZSnakeBlocks` `$67` | `HCZSnakeBlocksObjectInstance` ✓ |
| `0x00030834` | `0x0030580` +`0x2B4` | `Obj_HCZCGZFan` `$38` | purity 0.21, unresolved ✓ (now clean) |

**It is nevertheless not derivable.** Two independent failures, both measured:

**(a) It attributes shared and unlisted code to the wrong object.** The previous audit's
`0x00085AD2 → PoindexterBadnikInstance` is inconsistent with containment: `Obj_Poindexter` is
`$98 → 0x0008827C`, while `0x00085AD2` falls in the span opened by `Obj_HiddenMonitor`
(`$80 → 0x000836E0`), whose next table neighbour is `0x000862AE` — a **`0x2BCE` gap** holding
code for several objects that are in no table at all. Containment cannot tell a genuine
sub-routine of `Obj_HiddenMonitor` from an unlisted child object or a routine shared by
badniks. The largest offsets it produces are `+0x2520`, `+0x24F6`, `+0x253A` into
`Obj_HiddenMonitor` and `+0x6732` into `Obj_SOZRapelWire` — offsets far larger than any single
object body, i.e. the bucket is simply wrong.

**(b) It has no ground truth to be checked against.** The only corroboration available is the
previous audit's co-occurrence map, which is itself a fitted model of this one fixture, and
which §4(a) shows disagrees with containment on a high-volume code. Two inferences agreeing is
not derivation, and one of them is already known to be 0.21 pure. Landing containment would
satisfy rule 1's letter (the bytes come from the ROM) while breaking rule 3's substance: the
*rule* mapping address to owner is fitted, not read.

---

## 5. Answers to the three cases the round was told not to paper over

**Several ids share a code address.** Real, and benign. `Sprite_Listing3` has exactly two
ambiguous addresses: `0x0001A51A` (`Obj_Ring`) is the filler for 37 ids — `$00`, `$0B`, `$1C`,
`$25`, `$C7`, `$D1`-`$DF`, `$EE`-`$FE` — i.e. the table's unused-slot padding, and `0x00029216`
covers `$20` and `$52`. `Sprite_ListingK` has one, `0x0001A51A` over 15 ids. **The correct
comparison for these is set membership**, and a resolver should return an id *set*, never a
representative. This is not what blocks the round — but the point stands for the 4.26% that can
be inverted, and any resolver that lands later must carry it.

**Some occupants are not layout objects.** Confirmed with a stronger statement than "no layout
id": they are not `Dynamic_object_RAM` occupants at all. `slot_dump` covers slots `4-90` only
(measured: no slot below 4 appears in 2,388 frames), and of the nine sub-table codes in the
stream, only two (`0x00018164`, `0x00018B3E`, 2,016 entries) ever occupy a dynamic slot; the
other seven — including the three highest-volume codes overall, `0x0001365C` (21,666),
`0x000160D2` (21,664), `0x00019922` (18,471) — appear **exclusively in `object_near`**. Those
are the fixed SST records the constants file lists after `Dynamic_object_RAM`: `Player_1`,
`Player_2`, `Tails_tails`, `Dust`, `Shield`, `Breathing_bubbles`, `Wave_Splash`
(`sonic3k.constants.asm:304-322`). **They have no object id because they are never spawned from
layout**, and an occupancy comparison must exclude them by *slot range*, which it already does,
rather than by identity.

**The two zone sets remap ids.** Confirmed and quantified: the tables agree on `$00`-`$02` and
`$04`-`$05` but diverge at `$03` (`0x0001F746` vs `0x0003DC9C`) and `$06`, and share only some
addresses thereafter. Any inverse **must** be keyed on the active `S3kZoneSet`, exactly as
`getPrimaryName(int, S3kZoneSet)` already is. This is a real constraint on a future resolver; it
is not what blocks this round.

---

## 6. Corrected divergence breakdown (task item 5)

**The 19,519 genuine presence/absence divergence survives in full, unchanged.** That figure was
computed from slot occupancy alone and never consulted the `type` field, so correct identity
cannot move it — as the previous audit itself predicted for its Step A. The 14,463
ROM-occupied/engine-empty and 5,056 engine-occupied/ROM-empty split is likewise untouched, and
so is the 2,387/2,387 frame headline.

**The 41.3 / 23.3 / 23.8 / 11.6 mixture cannot be corrected, and should be treated as
withdrawn rather than refined.** It was produced by classifying co-occupied slots with the
inferred co-occurrence map. The ROM supplies a sound replacement for **4.26% of slot entries**
and nothing for the remaining 95.74%, so there is no identity-correct recomputation to report.
Reclassifying 4% of the population would move the four percentages by less than the width of
the inference error in the other 96%, and publishing the result would dress an unchanged
inference in a ROM citation. The honest statement is that **the split between "permutation" and
"population" divergence is not currently measurable**, and the previous audit's §4 conclusions
that rested on it — the 142-routine long tail, the 2,235 missing episodes, the excess/shortfall
coupling — inherit that uncertainty.

What *is* now measurable and unchanged: mean occupancy 19.9 engine vs 23.9 ROM, the engine short
on 1,423 frames, over on 225, level on 739. Those come from counting occupied slots and need no
identity at all.

---

## 7. What this means for the decomposition

The previous audit's **Step A is not landable as specified**, and Steps B-D depend on it.

- **Step A as written** ("resolve the code pointer to the engine's object identity through a
  ROM-cited per-object constant") is achievable only for the 4.26% that dispatch through a
  `routine` byte. For the other 95.74% there is no ROM-cited constant to resolve *to*: the
  address is an internal label, and the ROM does not record which object owns it.
- **The comparison S3K can support is not "same object id".** It is **"same ROM routine
  pointer"** — which would require the engine to expose each object's current state as the
  corresponding ROM code address. That is a per-object annotation of every ported S3K object
  (1,758 candidate sites), not a table inversion, and it is a far larger programme than the
  previous audit's Step A implied.
- A cheaper intermediate exists and should be scoped before either: compare **occupancy alone**
  — presence/absence and slot index over `Dynamic_object_RAM`, with no identity — which is
  exactly the 19,519-entry signal that is already sound today. That measures the population
  shortfall the previous audit's Step C targets, needs no mapping, and is the one comparison
  that can be turned on without first solving identity.
- `compareObjectNearEvents()` **must stay off for S3K**, now for a firmer reason than before:
  not merely that it truncates a pointer, but that the field it truncates carries no object
  identity at any width.

## 8. Verification statement

Nothing under `src/` changed; no `src/main` or `src/test` file was created or edited. **No
Maven run was performed and no trace sweep is owed.** All figures are offline derivations from
`s3k.gen` (`CRC32 63522553`) and `src/test/resources/traces/s3k/hcz_completerun/`, both read-only.
The main repository was on `develop` with a clean working tree throughout; all work was done in a
separate worktree, since removed.
