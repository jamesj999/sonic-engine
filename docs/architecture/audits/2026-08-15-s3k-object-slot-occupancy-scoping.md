# S3K object-slot occupancy: what the divergence consists of, and what closing it would take

**Scoping audit. Nothing behavioural landed.** Measured at `05981829c` (develop), in a clean
worktree of that commit. No file under `src/` was changed, so **no trace sweep is owed and none
was run** — the only Maven runs were two probe-armed executions of one class, both green.

Companion to the known-discrepancy
[*S3K object-slot occupancy is not compared, and diverges from ROM everywhere*](../../status/known-discrepancies.md)
and to the 2026-08-15 `hcz complete-run 29095` entry in
[the trace frontier log](../../status/trace-frontier-log.md). **This audit corrects both of them
on one material point** (see §1).

---

## Runs

| profile | class | probe | result | slot_dump frames compared |
|---|---|---|---|---|
| `trace-replay` | `TestS3kHczCompleteRunTraceReplay` | `OGGF_SLOT_PROBE=1` | 2 tests, 0 failures, 0 errors | 2387 |
| `trace-replay` | `TestS3kHczCompleteRunTraceReplay` | `OGGF_SLOT_PROBE=1 OGGF_SLOT_PROBE_FULL=1` | 2 tests, 0 failures, 0 errors | 2387 |

`target/surefire-reports` was removed before each run. Probe dumps are **1.7 MB** (diff-only) and
**5.5 MB** (full maps); they are **not committed**, and live under
`$AGENT_SCRATCH_ROOT/tasks/s3k-slot-occupancy-scoping-20260815T081142Z-3601864-44037894/`
alongside the six analysis scripts that produced every number below.

**Control reproduced.** 2387 of 2387 sampled frames divergent, 60,274 divergent slot-entries —
the figures in the existing frontier entry, to the unit.

---

## 1. MEASURED — the probe's largest divergence class is an artefact, not a divergence

**67.6% of the 60,274 entries (40,755) compare two numbers drawn from different spaces.**

S3K does not store an object *id* byte in its SST. `Process_Sprites` loads the first long of the
slot and jumps to it — `move.l (a0),d0 / movea.l d0,a1 / jsr (a1)`
(`docs/skdisasm/sonic3k.asm:35985-35988`). So the recorder's `slot_dump` carries **32-bit ROM code
pointers**:

```
{"frame":0,"vfc":1,"event":"slot_dump","slots":[[5,"0x000384B2"],[8,"0x0002D690"],
 [9,"0x0002D95C"],[10,"0x0002D95C"],[11,"0x0002D95C"],[12,"0x0002D8E2"]]}
```

`SlotOccupancyProbe.parseId` reduces that to `id & 0xFF`
(`src/test/java/com/openggf/tests/trace/SlotOccupancyProbe.java:148-151`), yielding `0xB2`, `0x90`,
`0x5C`… — the low byte of a *code address* — and compares it against the engine's layout object id
from `occupiedDynamicSlotIds()` (`ObjectManager.java:1669-1686`). The two agree only by accident.
The tell is in the data: every "ROM id" the probe reports is even, because addresses are.

**This is not confined to the diagnostic probe.** The committed comparator has the identical
defect. `TraceBinder.compareObjectNear` does `parseHexByte(expected.objectType())`
(`src/main/java/com/openggf/trace/TraceBinder.java:352`, `598-610`) against
`actual.objectId() & 0xFF`. For S1 that is sound — S1 `object_near` carries `"type":"0x25"`, a real
one-byte id, which is why `TestS1Lz2/Sbz2/Sbz3CompleteRunTraceReplay` can and do enable it. For S3K
`object_near` carries `"type":"0x0001365C"`.

> **Consequence, and the single most important output of this round: turning
> `compareObjectNearEvents()` on for an S3K class today would not measure occupancy. It would
> compare an engine object id against the low byte of a ROM code address.** The brief's
> expectation that it "would red essentially every S3K trace class" is correct, but for the wrong
> reason, and the resulting red would be uninterpretable. It must not be enabled first.

**Correction to the existing frontier entry and known-discrepancy.** "60,274 divergent
slot-entries" overstates the real occupancy divergence by roughly threefold. The genuine
presence/absence divergence is **19,519 entries** (14,463 ROM-occupied/engine-empty, 5,056
engine-occupied/ROM-empty). The other 40,755 are co-occupied slots whose identity the probe cannot
currently judge. The *frame* count is barely affected: 2025 of 2387 frames carry a genuine
presence/absence difference, and only 362 frames were divergent on the artefact class alone. The
headline "diverges nearly everywhere" survives; its magnitude does not.

---

## 2. MEASURED / INFERRED — the divergence by kind

To classify the 42,529 co-occupied entries I built a ROM-code → engine-class map by taking, for
each ROM code, the engine class it most often co-occupies with over the whole run. That map is
**INFERRED**, not authoritative — but it is strongly corroborated: the recorder self-labels
`0x00018164` in its own `air_countdown_state` events, and the inferred map independently returns
`S3kAirCountdownObjectInstance` for it; and the dominant class is semantically right for every one
of the top 25 codes (`0x000301DE`→`WaterWallSprayChild`, `0x00025724`→`HCZSnakeBlocksObjectInstance`,
`0x00020B74`→`BridgeFragment`, `0x00085AD2`→`PoindexterBadnikInstance`, …).

Classifying all 64,457 sampled object-instances (ROM instances plus engine-only ones):

| kind | instances | share |
|---|---:|---:|
| correct object, **correct** slot | 26,614 | 41.3% |
| correct object, **different** slot — **permutation** | 15,032 | 23.3% |
| ROM object with no engine counterpart — **shortfall** | 15,346 | 23.8% |
| engine object with no ROM counterpart — **excess** | 7,465 | 11.6% |

At frame granularity: **96** of 2387 frames match completely, **186** are pure permutation (right
population, wrong slots), and **2105** carry a population difference.

**It is a mixture, and neither half is small.** Permutation and population are within a few
percent of each other by instance count, and population dominates at frame level. Mean occupancy is
**19.9 engine vs 23.9 ROM** slots; the engine is short on 1423 frames, over on 225, level on 739.

The spatial signature is consistent: **missing entries concentrate in high slots** (22-45, peak
470 at slot 37) and **excess entries in low slots** (8-23, peak 259 at slot 11). That is what an
under-populated pool looks like — fewer live objects means the linear allocator never climbs, so
the engine's occupants sit lower and the ROM's high slots read empty. The permutation is largely
*downstream of* the shortfall, not independent of it.

---

## 3. MEASURED — the ROM's allocation discipline **is** modelled, correctly

This was the round's leading candidate cause. It is refuted.

```
AllocateObject:
        lea     (Dynamic_object_RAM).w,a1
        moveq   #((Dynamic_object_RAM_end-Dynamic_object_RAM)/object_size)-1,d0
        bra.s   AllocateObjectAfterCurrent.loop
AllocateObjectAfterCurrent:
        movea.l a0,a1
        move.w  #Dynamic_object_RAM_end,d0
        sub.w   a0,d0
        lsr.w   #6,d0                   ; Divide by $40... even though SSTs are $4A bytes long
        move.b  .lookup(pc,d0.w),d0
        bmi.s   .return
.loop:
        lea     next_object(a1),a1
        tst.l   (a1)
        dbeq    d0,.loop
.return:
        rts
```
(`docs/skdisasm/sonic3k.asm:37911-37944`; `object_size = $4A`, `Dynamic_object_RAM = 90 objects`,
`docs/skdisasm/sonic3k.constants.asm:113-114, 303-309`.)

Three properties, each already carried by the engine:

1. **Linear first-empty scan from the base**, freeness tested as `tst.l` on the first long.
   `SlotAllocator.allocate()` → `used.nextClearBit(0)`
   (`src/main/java/com/openggf/level/objects/SlotAllocator.java:28-60`).
2. **The pre-increment quirk.** `AllocateObject` jumps into `.loop`, which does
   `lea next_object(a1),a1` *before* the first `tst.l` — so the first dynamic slot is never
   returned. The engine encodes this as `firstDynamicSlot = 4` (global SST slot 3 is
   `Dynamic_object_RAM`'s first entry and is skipped), already cited at
   `ObjectSlotLayout.java:31-50`.
3. **`AllocateObjectAfterCurrent` scans strictly forward from the parent**;
   `SlotAllocator.allocateAfter(parentSlot)` starts at `toExecIndex(parentSlot) + 1`
   (`SlotAllocator.java:47-50`).

The one thing that looked like a modelling gap — the `.lookup` table's `lsr.w #6` division by `$40`
against a `$4A` object size, flagged in the disassembly's own *"There's a mistake here"* comment —
**does not bite for the S3K dynamic array.** Evaluating the table's
`(.b-.c-1)/object_size-1` for every parent position `n = 1..90` gives exactly `n-2`, the exact
remaining-slot count, at every position. The truncation happens to cancel. The engine's
scan-to-true-end `allocateAfter` is therefore faithful, not accidentally more generous.

**So a wrong search order is not the cause, and cannot be — it would produce a permutation with a
matching population, and we measure a population difference on 2105 of 2387 frames.**

---

## 4. INFERRED — how many distinct causes

**A long tail, not two or three mechanisms.** Collapsing the missing entries into contiguous
`(slot, ROM code)` lifetimes gives **2,235 distinct missing-object episodes over 142 distinct ROM
routines**; the top 37 routines are needed to cover 80% of episodes. The excess side is **1,025
episodes over 85 engine classes**, top 15 covering well under half.

Three things temper that count, and they are the reason this is tractable at all:

- Many episodes are **one frame long**. The largest single contributor, ROM code `0x0001ABB6`, has
  453 episodes and 453 entries — every one a single sampled frame. That is an object whose engine
  lifetime is off by a frame at spawn or death, not an unimplemented object.
- The engine **has** the objects. Purity of the inferred map is 1.00 for fixed-slot occupants
  (`0x000301DE`, `0x000384B2`) and 0.84-0.97 for several high-volume dynamic ones. Where purity is
  low (`0x00030834` at 0.21 across 20 engine classes) it is because the *slot* is wrong, not
  because the object is absent.
- Excess and shortfall are **coupled**: `S3kAirCountdownObjectInstance` is simultaneously the #4
  excess class (71 episodes) and, as ROM code `0x00018164`, the #4 missing routine (112 episodes).
  Those are the same object, present on both sides, in different slots. Counting them as two
  independent defects would double-count.

Honest estimate: **not a handful of mechanisms, but nowhere near 142 independent bugs.** The
measurable cause count cannot be pinned down further without object identity in the comparison —
which is precisely §1's blocker, and precisely why the decomposition below starts there.

---

## 5. Recommended decomposition

Ordered. Each step is independently landable and independently measurable. **Step A is a
prerequisite for every later step**, because until it lands there is no honest measurement to
regress against.

### Step A — give S3K object identity a real comparison key *(first, small, landable alone)*

Teach the comparison path that an S3K `object_near` / `slot_dump` `type` is a **ROM code pointer**,
not an id byte, and resolve it to the engine's object identity through a ROM-cited per-object
constant — the same place each S3K object already cites its disassembly routine. Concretely: stop
`parseHexByte`-truncating a 32-bit `type` (`TraceBinder.java:352`), and stop `& 0xFF`-truncating in
`SlotOccupancyProbe.parseId`.

- **Landable alone:** yes. It changes no gameplay code and no test's enabled/disabled state.
- **Measurable alone:** the probe's `id_mismatch` class must collapse from 40,755 entries to
  something interpretable; the frame count of 2387 must not move (identity resolution cannot
  change occupancy).
- **Guard against the obvious trap:** the mapping must come from each object's own cited ROM
  routine address, never from the inferred co-occurrence map used in §2 of this audit. An inferred
  map is a fitted model of *this fixture* and would desync the first different recording.
- **Not in scope for step A:** enabling `compareObjectNearEvents()` anywhere.

### Step B — measure, with identity, on one bounded S3K class

Re-run the probe with step A's key and re-derive the §2 table. Only then is "how many causes" a
real number rather than an estimate. Expect the permutation share to move: some of §2's
"permutation" is inferred-map error, and some of its "shortfall" will resolve into permutation.

### Step C — close the population shortfall before the permutation

The measured coupling says the permutation is largely downstream of the shortfall (fewer live
objects ⇒ the linear allocator never reaches high slots). Attacking the permutation first would be
attacking a symptom, and any slot-nudging fix applied at that stage is a rule-3 violation wearing a
disguise. Work the ROM routines with the most *episodes*, not the most entries — the one-frame
episodes are lifetime-edge bugs and are the cheapest real wins.

### Step D — only then, enable `compareObjectNearEvents()` on an S3K class

And on a **segment** class first, not `TestS3kHczCompleteRunTraceReplay`, so the red set is
attributable.

### What this unblocks

`b31069c3f` (the held MegaChopper pair) becomes decidable on ROM evidence rather than on which
layout happens to green a ring counter. It should stay held until at least step C. It is not
touched by this audit.

---

## 6. Verification statement

Nothing under `src/` changed. **No trace sweep was run and none is owed.** The two Maven runs above
are the complete set; both were `-Ptrace-replay`, single-class, and green (2 tests, 0 failures, 0
errors each). The main repository was left on `develop` at `05981829c` with a clean working tree
throughout; all work was done in a separate worktree, since removed.
