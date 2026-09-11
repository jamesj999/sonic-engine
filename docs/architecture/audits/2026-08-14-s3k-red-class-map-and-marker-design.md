# S3K red-class map, and a design for the segment frontier markers

**Date:** 2026-08-14
**Status:** Investigation and mapping complete. Two engine fixes landed; one cluster closed as
fixture design; one design proposal awaiting your decision. Nothing here was applied to the
markers.

Every number is stamped with the commit it was measured at. **A load-bearing measurement older
than current `HEAD` must be re-measured before it is relied on** — this session produced two
misbriefed rounds by carrying a frontier position past the commit that fixed it.

## The finding that reframed everything

`843 / 9F / 56E / 4S, 65 red` had been quoted all session as a stable baseline. **64 of those
65 red classes are S3K** — the priority game. The only non-S3K red is
`TestS2CompleteEmeraldRunChain`. The count was never wrong; nobody had ever *attributed* it.
A baseline you intend to hold steady must be attributed by class before it is a baseline at
all, or "unchanged" means nothing.

## Two masking assertions, removed in sequence

Both aborted the whole comparison on a *symptom*, hiding the *cause* inside the same class's
own report.

| commit | assertion | gated | what it hid |
|---|---|---|---|
| `ce65000f2` | unmatched recorded completion | ~18 | HCZ 561 `x_speed`, MGZ 321 `camera_y`, MHZ 75 `player_mapping_frame` |
| `741e6b299` | `unexpected pending hardware submissions at final run` | 14 | Aiz2 1033 errors from frame 0; Icz 5196 from frame 470 |

Neither is a queue defect: the queue, fingerprints, ordinal base and admission port are all
correct. The engine had diverged, so it never reached the ROM's camera-gated submission point.

**The discriminators that make such a demotion legitimate** — worth applying to any future one:

1. **Authority, not annoyance.** Legitimate when the assertion carried *verdict* authority
   (what is reported first); illegitimate when it carried *behavioural* authority (the only
   thing preventing a release, admission or mutation). Both kept the release-side refusal
   provably intact: a dropped edge never reaches `admitReadiness()`.
2. **The arithmetic must go n → n+1.** Both did (1033→1034, 5196→5197). A demotion that
   *reduces* downstream error counts has absorbed something.
3. **A demotion must never flip red to green by itself.** Both kept the red set identical by
   name in both directions.
4. **No ratchet.** One layer of demotion per invariant, ever. A future proposal to quiet an
   error these demotions *created* is itself the tripwire.

Two implementation details are the house pattern for any future demotion: make it **opt-in**
(a driver with nowhere to record still gets the original abort, so the live launcher is
untouched), and record **on the last row the comparison actually reached**, so the demoted
error structurally cannot displace an earlier divergence as first error.

## The map: 64 red S3K classes, 8 causes

Measured at `c383419c9` by reading each class's own surefire assertion message, which carries
its first-error frame/field/counts and is immune to the trace-report filename collision between
standalone `s3k_<zone>` classes and `TestS3kSonicTails*` segment classes.

| cluster | size | cause |
|---|---|---|
| Frame-0 segment-entry state not seeded | **~25** | cold start — **fixture design, see below** |
| Structural — segment cannot load | **11** | missing engine level-list entries |
| Mid-run physics from a clean start | ~13 | genuine engine divergences |
| Special-stage close | 9 | separate assertion, no physics evidence behind it |
| Chains and complete-runs | 4 | boundary/closure issues |

## Cluster: cold-start segments — closed, no legal engine fix

**Verdict: fixture design, not an engine defect.** The decisive contrast is same zone, same
route, same harness, one variable:

| class | bk2 offset | first error |
|---|---|---|
| `TestS3kSonicTailsAizSegmentTraceReplay` | 577 (**route start**) | frame 2247, **3 errors** over 2290 rows |
| `TestS3kSonicTailsAiz2SegmentTraceReplay` | 8817 (resumed) | **frame 0**, 1034 errors |

Every segment is a slice of one BK2 at a nonzero offset; only `aiz` is the route start. Each
segment class boots a fresh fixture and performs a fresh level load. The chain instead builds
**one** `GameLoop` and drives every segment through it in order with boundary carry-over
asserted. **The chain earns the state; the standalone class starts cold.**

No legal lever exists. Seeding from the recorded row is rule-4 hydration and is already
guard-enforced off — three `TraceReplayBootstrap` predicates hard-return `false` (`:447-458`)
with `TestTraceReplayStartPositionPolicy:494` asserting the literal body by source regex. A
constant is fitted; starting at frame 1 weakens an assertion; deleting the classes deletes
assertions.

**These 63 classes are not badly posed — they are deliberately red.** All 63 carry the javadoc
*"New frontier harness: expected RED. It was added deliberately, to say WHERE this third S3K
route diverges, not as a regression."* They did that job: this map exists because they exist.
**None should be retired on the strength of this document.**

## Proposal: three-bin self-classifying markers

A marker whose first error is always frame-0 bootstrap reports the same non-information for
every mid-route segment. The improvement is to make each marker classify itself **by measuring
its own frame-0 field agreement** — no taxonomy, no zone or route predicate, rule-2 clean by
construction:

1. **Seed-matched** → the frontier is valid; report it.
2. **Mismatch confined to route-carried fields** (rings, sidekick momentum) → "cold-start
   incompatible; mismatched seed fields are X, Y, Z", and **no frontier claim**.
3. **Mismatch touching load-determined fields** — fields the ROM's own init code writes at that
   entry → a **frame-0 finding**, investigated as a real divergence.

**Bin 3 should ship defined and empty.** On current evidence every attributable frame-0
mismatch is route-carried. An empty, correctly-specified bin is a standing falsifiable
prediction — the first genuine load-determined mismatch lands in it loudly. Do not populate it
for credibility.

**Do not compute a "first post-bootstrap divergence" figure.** Downstream of a wrong seed the
engine is evolving a different run, so such a number has the units of a frontier and the
information content of noise — and would read as a finding. That is worse than frame-0
non-information, which at least looks uninformative.

**Entry kind must come from ROM structure, never from naming.** The authoritative answer to
"what kind of entry is this" is the ROM's own transition code — which routine moved the player
(level load, act transition, sub-zone warp, cutscene) — citable per edge. This document's author
was misled twice by inferring entry semantics from manifest directory names; the design should
make that mistake structurally unavailable.

**Worked example, and its caveat.** `hpz` fails at frame 0 on `y` (0x0FA6 vs 0x0FAE) — an 8px
*position* mismatch at what looks like a zone entry, which would suggest bin 3. It isn't: `hpz`
(zone 10) is preceded in the route by `hpz22_2` (**zone 22**), HPZ's own act-2 hub, so this is a
sub-zone transition, not a fresh load, and `y` is not load-determined there. **Bin 2 —
provisionally.** Zone 22 is one of the eleven structural classes the engine cannot load at all,
so the engine-side half of that comparison is a default-state stand-in for a segment that never
ran. When zone 22 becomes loadable, `hpz`'s frame-0 story may change, possibly *into* bin 3.
Carry it as "bin 2 pending zone-22 loadability", not as settled.

### The eventual upgrade

`AbstractRunChainTest.assertChainReplayThroughSegmentRow` (`:908`) already provides a prefix
pin that stops the chain on an interior segment's row. **One execution, many verdicts**: have
the chain report each segment's comparison span as a separate verdict — it already stamps
segment boundaries — so every segment gets a real frontier with no cold-start fiction and no
extra wall-clock. The trade is isolation: an early divergence contaminates later segments, which
the chains already accept, and which the two demotions above soften.

**Check before building it:** confirm the chains actually cover what the standalone classes
compare — same routes, fields, spans. Anything a standalone segment compares that no chain
reaches needs a chain *extended*, not a class deleted.

## Re-ranking: cluster E is route-blocker work, not plumbing

Eleven classes cannot load at all — zone_id 23 (DEZ act 2, 8 classes), zone_id 22 (HPZ act 2,
2), DDZ act 2 (1) have **no engine level-list entries**.

This looked like the cheap structural win. It is more than that: these are **missing engine
entries for real ROM content that the recorded route traverses**, which is route-blocker-shaped
work and squarely priority-2 in `CLAUDE.md` terms. And at least one other segment's
classification is downstream of it — `hpz`'s bin assignment cannot be settled until zone 22
loads. Cluster E unblocks its own eleven *and* de-provisionalises entries elsewhere, which
plausibly makes it the highest-leverage cluster on the map despite appearances.

### A concrete starting point (MEASURED at `f4eae0871`, source read only)

`src/main/java/com/openggf/game/sonic3k/constants/Sonic3kZoneIds.java` contains:

```java
public static final int ZONE_HPZ = 0x16;  // Hidden Palace
...
public static final int TOTAL_ZONE_COUNT = 22; // AIZ(0) through Slots(21), including gaps
```

**`0x16` is 22 decimal, and `TOTAL_ZONE_COUNT = 22` spans indices 0–21.** So HPZ's own declared
zone id is one past the end of any array sized by that constant — which is exactly the reported
`Index 22 out of bounds for length 22` for the two HPZ act-2 classes. The constant's comment
("AIZ(0) through Slots(21)") is self-consistent; it simply does not account for the HPZ id
declared eleven lines above it.

**Zone id 23 (`0x17`) has no constant in that file at all**, which is consistent with the
`Index 23 out of bounds for length 22` reported by the eight DEZ act-2 classes.

Two cautions for whoever takes this:

- **Do not simply widen the constant to 23 or 24.** The right size follows from what the ROM's
  own level-list actually contains; a number chosen to stop an exception is a fitted constant
  under rule 3 even though it looks like plumbing. Derive it from the disassembly's level table
  and cite the routine.
- **Confirm what zone `0x17` is in the ROM before adding it.** The trace directory names it
  `dez23`, but this document's author has twice been misled by inferring semantics from
  directory naming — the answer must come from the ROM's level table, not the fixture layout.

## Uncovered route, for a future capture ask

Only segments no trace exercises at all, red or green — the recorded corpus already covers act
transitions and later acts, they are simply failing, so a capture ask should not cite them:

- CNZ act 2, MGZ act 2, SOZ act 2, DDZ act 1
- SSZ on the Knuckles route
- Every character set other than Sonic+Tails and Knuckles

**Two adjacent gaps are HARNESS, not capture, and must not become a capture ask:** the Knuckles
route's 67 segments have no per-segment classes (only the chain, which stops in segment 0), and
the multibonus runs are validated for recorded timing but never replayed.

`TestS3kBonusRoundTripChain` **never runs** — both methods `assumeTrue` on run directories that
do not exist. Recorded as a finding; no fixtures were created to satisfy it.


> **Updated 2026-08-15 after a day of S3K rounds.** Two further asks have been measured into
> existence since this list was written, and one of them is a *capture-format* change rather than
> an engine one.

### 5. A stable object identity at record time — a capture-format ask

**Object-slot occupancy parity cannot be measured from the current recording, and this is not a
comparator feature waiting to be built.** S3K's SST has **no `id` field at any offset** — its
conventions begin `code = 0 ; longword` — and objects overwrite their own dispatch pointer with
internal sub-routine addresses to advance state (1,758 `move.l #<label>,(a0)` sites in
`sonic3k.asm`). So the value the recorder captures as the object "type" is a **live program
counter**, not a type at any width.

Measured against both object tables read from the ROM at the addresses the loader itself uses:
only **4.26%** of `slot_dump` entries are table entries, and those are exactly the objects that
dispatch via a `routine` byte instead. A containment rule (nearest preceding table entry) was
tested and **rejected as fitted** — it reproduces several plausible labels and then places others
in unrelated gaps.

**The ask:** a stable object identity emitted at record time — engine-side or Lua-side — so that
`object_near` / `slot_dump` carry something invertible. Without it, `compareObjectNearEvents()`
must stay off for S3K permanently, and the suite cannot see object-layout drift at all. This joins
the capture section rather than the engine backlog.

### 6. Slot-occupancy parity as a named open frontier

`CLAUDE.md`'s priority 3 already lists **"sidekick/object-lifetime mismatches"**. This is its
cleanest specimen *and* its measurement gap, produced together:

- Engine-vs-ROM occupancy diverges on **2387 of 2387** sampled frames, on a clean tree.
- Genuine presence/absence divergence is **19,519 entries across 2025 frames** (the larger figure
  first published was 67.6% truncation artefact and has been corrected).
- Mean occupancy **19.9 engine vs 23.9 ROM**; engine short on 1,423 frames, over on 225.
- Missing clusters in **high** slots, excess in **low** — the signature of an under-populated pool
  whose linear allocator never climbs.
- The ROM allocator itself is **refuted as the cause** and already modelled faithfully, including
  the pre-increment that makes the first dynamic slot unreachable and the `.lookup` division the
  disassembly flags as a mistake (verified to evaluate correctly at all 90 parent positions).

It is already producing user-visible consequences: a ROM-correct badnik fix shifted slot phase and
changed when a scattered ring is collected, because `Obj_Bouncing_Ring` gates its floor probe on
its own slot index. That is now a documented deliberate red on
`TestS3kHczCompleteRunTraceReplay`, with the removal condition being exactly this frontier.


### 7. A hooks-on recorder regeneration of ~30 AIZ frames

**A third capture ask, and the smallest.** The S3K top-solid zero-distance defect is fully
diagnosed except for one link, and the recorder **already carries the exact probe needed** —
`aiz_handoff_terrain_state` in the trace recorder (`V69_AIZ`: `sonic_floor_distance`,
`sonic_floor_probe_x/y`, `solid_pre_y`, `solid_surface_y`, `solid_delta`). It is simply **hooks-off**
in `aiz1_to_hcz_fullrun` (`sonic_floor_seen: false`, `solid_vertical_seen: false`).

**What is known:** the ROM rejects `d0 == 0` landings (`sonic3k.asm:42005-42015`, `blo` is
unsigned), the engine accepts them, and flipping that reds five classes. Instrumentation shows
the engine's Sonic *does* descend and land inside the ROM's window — **16 frames late**, owned by
the ground-sensor floor-distance snap, not by anything in the solid path. Every field the fixture
records is **identical across those 16 frames** — position, sub-pixel, speed, radius, status,
event routines, object slots — so the trigger is invisible at frame granularity.

**The ask:** a hooks-on regeneration of roughly frames 5410–5440 of that run. That single
measurement should close a defect that has now consumed three rounds and is blocking a fix worth
LRZ 11942 → 6480 errors.

## Decisions needing your authority

1. **Build the three-bin markers?** Small, honest reporting change; bin 3 empty by design.
2. **Chain per-segment verdicts?** The larger upgrade, and the author-intent-respecting version
   of "retire the standalone classes" — it preserves attribution rather than deleting it.
3. **Fixture regeneration for the S2 recorder discard defect**, documented separately in
   `2026-08-14-s2-dynamic-art-discard-handover.md`. Not requested, not started.
4. **Capture ordering** — the uncovered list above is what makes that ask specific. It is the
   *second* ask; there is a large real frontier to work first.
