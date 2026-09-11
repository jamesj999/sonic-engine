# S3K red-class census: how much red is downstream of the save-game inventory boundary

**Date:** 2026-08-15
**Measured at:** `21bfdf082` (develop), clean worktree, no `src/` changes in this round.
**Status:** Census only. Nothing under `src/` was touched, no assertion was weakened, no
fixture regenerated. No sweep is owed for a fix, because there was no fix — the verification
section below says what was and was not run.

Successor to
[2026-08-14-s3k-red-class-map-and-marker-design.md](2026-08-14-s3k-red-class-map-and-marker-design.md),
which mapped the same red set into eight causes. This document asks a narrower question that
day's work made askable: **how much of the remaining S3K red is downstream of one fixture-design
decision — that a standalone segment replay starts with an empty save-game inventory (P50/P63)?**

The short answer, stated before the evidence so it cannot be inflated by it:

> **Four of 63 red S3K classes have their first substantive divergence *at* an inventory-gated
> ROM branch, carrying 19,782 of 86,206 measured errors — 22.9% of the S3K error mass.**
> Two of those four (**CNZ**, **LBZ**) were not previously known to be inventory-gated; two
> (**ICZ**, **MGZ**) were.
> A further **14 classes contain an inventory-gated event inside their compared span** but
> diverge earlier for other reasons, so the decision is a *necessary but not sufficient*
> condition for them. Counting those, **18 of 63 classes and 65,157 of 86,206 errors (75.6%)
> sit in classes that cannot go green without the decision** — but only the 22.9% is
> *attributed* to it.

## Baseline (MEASURED)

```
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=<per-tree empty tmpdir>" \
  mvn -Ptrace-replay -Dmse=off test -Dsurefire.runOrder=alphabetical \
      -Dtest.cds.argLine="-Xshare:off" \
      -Dsonic1.rom.path=<s1> -Dsonic2.rom.path=<s2> -Ds3k.rom.path=<s3k>
```
`target/surefire-reports` was removed immediately before the run; an isolated worktree at
`21bfdf082` was used.

| quantity | value |
|---|---|
| tests run | **843** (the 2026-08-14 map's 834 has grown) |
| failures / errors | 53 / 11 |
| **red classes** | **64** — 63 S3K, 1 S2 (`TestS2CompleteEmeraldRunChain`) |
| total reported physics errors across the 51 S3K classes that report a count | **86,206** |

The 64/63 shape matches the previous map. **The composition does not**, and two corrections
belong on the record:

- **The "structural — segment cannot load" cluster is gone.** All eleven HPZ-act-2 / DEZ-act-2 /
  DDZ classes now load and produce ordinary frame-0 divergences. Whatever closed the zone-22 /
  zone-23 level-list gap has landed since that map was written. `hpz`'s bin assignment is
  therefore no longer "pending zone-22 loadability".
- `TestS3kSonicTailsAizSegmentTraceReplay`, `Ss2`, `Ss6` and `Ss` are **green** at this commit;
  `TestS3kHczCompleteRunTraceReplay` and `TestS3kMhzCompleteRunTraceReplay` are **red**.

## Method

Three measurements, in order, each cheap and each falsifiable.

**1. Per-class first error and error count** come from each class's **own surefire assertion
message**, which is immune to the `target/trace-reports` filename collision. That collision is
worse than previously documented: report names are `<game>_<zone><act>`, so within the *same*
run `aiz` and `aiz_2` both write `s3k_aiz1_report.json`. No batch is attributable; the 13
classes needing per-error data were each re-run **alone**, with `target/trace-reports` archived
after each invocation.

**2. Per-error start-frame clustering.** The first *reported* error is frequently a
self-healing transient — ICZ reports frame 1983, and that entry is a **1-frame,
`cascading:false`** tails blip with only four of 2,862 error groups starting before frame 2337.
Every nonzero-frontier class was clustered by `errors[].start_frame` from its own report.

**3. A fixture-side discriminator for inventory gating, requiring no probe** (the P63
corollary: a branch whose two arms write *different compared fields* tells you which arm each
side took). Two ROM events are inventory-gated and leave a signature in the fixture's own
`physics.csv`:

| event | ROM | fixture signature | engine cannot produce it because |
|---|---|---|---|
| Giant-ring 50-ring award | `Obj_SSEntryRing` arm `loc_61794`, `sonic3k.asm:128327-128328` — `moveq #50,d0 / jmp (AddRings).l`. The **only** `moveq #50,d0` feeding `AddRings` in the whole disassembly | `rings` steps **+0x32 in a single frame** | the `cmpi.b #7,(Chaos_emerald_count).w` at `:128283` reads 0 in a segment |
| Super/Hyper transformation | `Sonic_Transform`, `sonic3k.asm:23492` — `move.b #$1F,anim(a0)` (Knuckles' twin at `:32596`; the only two writers) | `player_animation_id == 0x1F` | `SuperStateController.canTransform()` (`:241`) requires `hasTransformationEmeralds()` → `GameStateManager.hasAllEmeralds()`, and `emeraldCount` is 0 for a fresh segment |

The engine's *other* arm is equally visible: `loc_6173A` (`:128290-128295`) writes
`mapping_frame = 0`, `anim = $1C`, `object_control = $53`.

## The signature, and why it is an attribution rather than a coincidence

All four confirmed classes show the identical pair of leading error groups — the engine's
capture arm against the ROM's award arm — followed one frame later by the ROM's `rings` step:

| class | first substantive frame | leading error groups | fixture `rings` step |
|---|---|---|---|
| `…CnzSegment` | **5754** | `player_animation_id` exp `0x0000` act **`0x001C`**; `player_mapping_frame` exp `0x0002` act **`0x0000`** | `0x005E → 0x0090` at 5755 |
| `…LbzSegment` | **958** | `player_animation_id` exp `0x0002` act **`0x001C`**; `player_mapping_frame` exp `0x0097` act **`0x0000`** | `0x0010 → 0x0042` at 959 |
| `…IczSegment` | **2336** | `player_animation_id` exp `0x000D` act **`0x001C`**; `player_mapping_frame` exp `0x00A0` act **`0x0000`** | `0x001E → 0x0050` at 2337 |
| `…MgzSegment` | **17383** | (previously documented) | `0x0028 → 0x005A` at 17383 |

`0x1C` and `mapping_frame = 0` are `loc_6173A` transcribed. Nothing else in the compared field
set writes that pair on the frame the ROM adds exactly fifty rings.

## Bucket (a) — inventory/progression-gated at the first substantive divergence

**4 classes, 19,782 errors, 22.9% of the S3K error mass.**

| class | errors | inventory event | groups starting at/after it |
|---|---|---|---|
| `TestS3kSonicTailsLbzSegmentTraceReplay` | 7,174 | giant ring @959 | 7,172 / 7,174 = **100.0%** |
| `TestS3kSonicTailsCnzSegmentTraceReplay` | 6,300 | giant ring @5755 | 6,298 / 6,300 = **100.0%** |
| `TestS3kSonicTailsMgzSegmentTraceReplay` | 3,446 | giant ring @17383 | 3,410 / 3,446 = **99.0%** |
| `TestS3kSonicTailsIczSegmentTraceReplay` | 2,862 | giant ring @2337 | 2,858 / 2,862 = **99.9%** |

CNZ and LBZ are the new members. LBZ additionally transforms Super at frame 1146, so it is
double-gated. The residue in each — 2, 2, 36 and 4 groups respectively — is the genuine
engine-defect frontier that survives the decision, and is small.

## Bucket (b) — other cold start (frame-0 seed)

**37 classes, 44,700 errors, 51.9% of the mass.** Every one reports its first error at frame 0.
This is the cluster the 2026-08-14 map closed as fixture design; nothing here reopens it, and —
per that map's explicit instruction — **no post-bootstrap frontier figure is computed for them**,
because downstream of a wrong seed the engine is evolving a different run.

Ten of the 37 report `rings` itself as the frame-0 first error (expected 77/85/96/117/126/150/
156/177/180/201, actual 0). That is the same run-scoped quantity as the emerald counter, seen at
frame 0 rather than at a branch — worth noting because any fixture decision that seeds inventory
would very likely seed these too.

Classes: `Aiz2 Aiz3 Aiz4 Aiz5 Ddz Dez23 Dez232 Dez233 Dez234 Dez235 Dez236 Dez237 Dez238 Hcz2
Hcz3 Hcz4 Hpz Hpz2 Hpz3 Hpz22 Hpz222 Icz2 Mhz2 Mhz3 Mhz4 Mhz5 Mhz6 Mhz7 Mhz8 Mhz9 Pachinko
Pachinko2 Pachinko3 Soz Soz2 Ssz Zone0c` (all `TestS3kSonicTails…`).

## Bucket (c) — genuine engine defect, reachable and fixable now

**9 classes.** These are the honest targets for a fix round.

| class | first substantive divergence | errors | note |
|---|---|---|---|
| `…FbzSegment` | 116, `tails_x_sub` | 8,599 | 2,909 groups precede its giant ring at 10106 |
| `…LrzSegment` | 208, `tails_y_speed` | 11,942 | 2,931 groups precede its giant ring at 8029; the top-solid frontier |
| `…SlotsBonus` | 5, `player_mapping_frame` | 900 | 603 of the groups are `player_mapping_frame` |
| `…GumballBonus` | 33, `tails_x` | 119 | |
| `…Gumball2Bonus` | 33, `tails_x_speed` | 144 | |
| `…MhzSegment` | 1276, `camera_y` | 9 | 8 of 9 groups are `hardware_timing.unmatched_completions` |
| `…Ss4SpecialStage` | 1689, `spheres_left` | 2 | |
| `…Ss5SpecialStage` | 3906, `spheres_left` | 7 | |
| `TestS3kHczCompleteRunTraceReplay` | 29095, `rings` (exp 1 act 2) | 2 | already the documented deliberate red for the slot-occupancy frontier |

## Bucket (d) — structural / other

**13 classes.** No physics comparison reached; the assertion is about harness or hardware-timing
closure, so none of them can be classified by first divergence at all.

- Nine `…SsNSpecialStage` classes (`Ss3 Ss7 Ss8 Ss9 Ss10 Ss11 Ss12 Ss13 Ss14`): *unmatched
  recorded hardware completions were never reported*, a `KOS_DECOMPRESSION_QUEUE` /
  `KOS_MODULE_QUEUE` pair per class.
- `TestS3kMhzCompleteRunTraceReplay`: *Invalid rewind reference closure* at traceIndex 12271.
- `TestS3kSonicTailsCompleteEmeraldRunChain`: comparator cannot exhaust after the `aiz` boundary.
- `TestS3kKnucklesSuperEmeraldRunChain`: segment 0 exit boundary (`giant_ring`) never observed.
- `TestS3kMegaRunChain`: unconsumed hardware completion edge at segment end.

(The one non-S3K red, `TestS2CompleteEmeraldRunChain`, is outside this census.)

## The latent set: where the decision is necessary but not sufficient

Fourteen further classes contain a giant-ring award or a Super transformation **inside their
compared span**, so fixing their own first divergence cannot green them while the inventory
stays empty. They are counted in their primary bucket above, not double-counted here.

| class | primary bucket | inventory event in span |
|---|---|---|
| `…LrzSegment` | c | ring+50 @8029, 34405, 36098; Super @24113 |
| `…FbzSegment` | c | ring+50 @10106, 11381, 20913, 24513 |
| `…SozSegment` | b | ring+50 @9620; Super @4868 |
| `…Soz2Segment` | b | ring+50 @10281; Super @28595 |
| `…SszSegment` | b | Super @17790 |
| `…HpzSegment` | b | Super @3975 |
| `…Hpz3Segment` | b | Super @1079 |
| `…Hpz222Segment` | b | Super @5652 |
| `…Hcz4Segment` | b | Super @1931 |
| `…Icz2Segment` | b | ring+50 @3669; Super @4334 |
| `…Dez238Segment` | b | Super @3182 |
| `…DdzSegment` | b | ring+50 @13632; Super @13632 |
| `…Zone0cSegment` | b | ring+50 @23; Super @23 |
| `…Mhz9Segment` | b | ring+50 @2168 |

Those fourteen carry 24,834 + 8,599 + 11,942 = **45,375** errors. With bucket (a)'s 19,782 that
is **65,157 of 86,206 = 75.6% of the S3K error mass sitting in classes the decision gates** —
against **22.9% the decision is measured to *cause***. The gap between those two numbers is the
honest part of this census: do not quote the 75.6% as an attribution.

## What is NOT claimed

- **No claim that seeding inventory would green anything.** For bucket (a) it removes the
  attributed cause; whether the residue is small was measured (2–36 groups), whether it is
  *zero* was not, and could not be without running the forbidden experiment.
- **No claim about the frame-0 classes' post-bootstrap behaviour.** Deliberately not computed.
- **No claim that the giant-ring branch is the only inventory gate.** The in-level ROM readers of
  run-scoped inventory were enumerated from the disassembly and are: `Obj_SSEntryRing`
  (`:128218-128427`), `Sonic_CheckTransform` (`:23460`), `Tails_Test_For_Flight` (`:28633`),
  `Knux_Test_For_Glide` (`:32547`), `Obj_583BE` (`:117841`, `:118406`), `Obj_5A94C` /
  `HPZS_ScreenInit` (`:120807`, `:120943`), `Obj_DEZ3_Boss` (`:171525`) and
  `Obj_DEZ3_Boss_Fireball` (`:173326`). Only the first two have a fixture-visible signature
  usable without a probe, so the HPZ shrine objects and the DEZ3 boss are **unclassified** in
  this census — see below.
- **Bucket (d) is unclassified with respect to inventory**, by construction: no physics
  comparison runs.

## Classes I could not classify

Stated plainly, per the brief's instruction that a partial census with honest gaps beats a
complete-looking one:

- The **13 bucket-(d)** classes cannot be placed on the (a)/(b)/(c) axis at all.
- The **HPZ segments** (`Hpz Hpz2 Hpz3 Hpz22 Hpz222`) reach `Obj_5A94C` / `HPZS_ScreenInit`,
  which read `Chaos_emerald_count` — an inventory gate with **no** distinctive compared-field
  signature, so their (b)-vs-(a) split beyond the Super transformation is undetermined.
- **`…DdzSegment`** likewise reaches `Obj_Difficulty_MasterEmerald` (`:166666`) and the DEZ3
  boss family; its 32 errors were not decomposed.
- Whether any bucket-(b) class's frame-0 mismatch is *itself* load-determined (the 2026-08-14
  design's empty "bin 3") was not re-tested here.

## Consequence for the handover

The 63-class red list is, in decision terms:

| | classes | errors | share |
|---|---|---|---|
| (a) inventory-gated, attributed | 4 | 19,782 | 22.9% |
| (b) other cold start | 37 | 44,700 | 51.9% |
| (c) genuine engine defect | 9 | 21,724 | 25.2% |
| (d) structural / harness | 13 | — | — |

Nine classes are workable today without any decision being made, and two of them (`Lrz` 11,942,
`Fbz` 8,599) hold 24% of the error mass between them. That is where a fix round should go. The
inventory decision is worth taking for the 22.9% it owns and the 18 classes it blocks — but it
is not the majority of the red, and the earlier framing that it might be does not survive
measurement.
