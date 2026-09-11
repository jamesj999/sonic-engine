# ROM Object-Windowing Port — Design

- **Date:** 2026-06-04
- **Status:** Draft for review
- **Author:** trace-green campaign (Claude)
- **Topic:** Frame-exact port of the ROM object load / unload / slot-recycle windowing, replacing the engine's active-window/dormancy heuristics, to unblock the MTZ1/MTZ3 sidekick-cluster traces and align object presence with ROM across S1/S2/S3K.

## 1. Context & motivation

After the slot-based sidekick `interact(a0)` foundation (develop `bd74180e5`/`7d45d28a9`), MTZ1 (f375) and MTZ3 (f2638) are blocked on the engine's object load/unload/slot-recycle **timing** diverging from ROM by a few frames:

- **MTZ1 f375:** Obj42 SteamSpring is not loaded at the frame ROM has it loaded+executing, so it never springs Tails (`SteamSpringObjectInstance.update()` first runs only at engine-f2491).
- **MTZ3 f2638:** the engine empties the interact slot (object unloaded) where ROM has recycled that slot to a different live id; the sidekick despawn compare therefore mis-fires.

The engine already has the correct **model** (execute every loaded slot every frame; camera coarse-X load window; slot recycle via free+linear-realloc; `interactSlotIndex`/`objectIdInSlot`). What diverges is the **frame-exact timing**: coarse-camera math, load/unload thresholds, per-frame caller ordering, and the presence of non-ROM heuristics (distance-based `isOutOfRangeS1`, dormancy) running beside it.

This is a correct-foundation change with the same cascade shape as the sidekick foundation: it retimes object load/unload for **every** trace, so prior per-trace fixes tuned to the wrong timing will cascade-regress. Per maintainer decision, we **land the correct timing universally (all three games), map the cascade, and re-derive** regressed frontiers — accepting documented short-term regressions.

## 2. Goal & ROM invariants to replicate frame-exactly

Per frame, the engine's set **and slot-occupancy** of loaded objects must match ROM. Invariants:

1. **Execute every loaded slot every frame**, independent of camera position. Unloading is object-driven (each object's own off-screen check), not a loop-level cull. *(Engine already does this — `ObjectManager.java:817` is unconditional; keep it.)*
2. **Camera coarse-X tracking**, per game (note S2's load base differs from its unload base — see §4).
3. **Directional load** via two cursors over the object-position list (a right/forward cursor and a left/back cursor), advancing as the camera moves; each game has its own thresholds.
4. **Object-side unload** (per game): when an object's coarse-X leaves the unload window, clear its respawn-table "loaded" bit (bit 7) when it has a respawn entry, then delete it — zeroing the slot identity.
5. **Slot recycle**: a deleted slot (identity field cleared — per the **per-game empty predicate**, see §3) is the first candidate for the next allocation (linear first-empty scan), so a different object reuses the same slot address/index; `interact(a0)` dereferences the recycled slot and yields the **new** id. *(Foundation already consumes this.)*
6. **Respawn-table semantics**: bit 7 = "currently loaded", set by the loader, cleared by the object-side unload; prevents double-loads when the camera stalls. `respawn_index == 0` means "no respawn entry" → never touch the table for that object.

## 3. Architecture — windowing port over an ObjectManager-owned slot allocator

The new unit is a **ROM placement/windowing *port*** — it owns the *timing* of object-list load and object-side unload, **not** all object lifetime. Slot allocation stays a **shared primitive owned by `ObjectManager`**, because slots are also allocated by child spawning, dynamic objects, bosses/projectiles, and framework-bridge paths; a second allocation authority would split the source of truth.

Three pieces:

1. **Shared slot table / allocator contract (owned by `ObjectManager`).** A per-game-parameterized contract exposing: the **empty predicate** (S1/S2: id byte == 0; **S3K: routine-pointer longword == 0**, `tst.l (a1)`), the **identity-clear/delete** behavior (`DeleteObject` zeroes the slot incl. id byte for S1/S2; `Delete_Current_Sprite` zeroes the slot incl. the routine-pointer for S3K), the **allocation scan start** (the game's dynamic-slot range / first-empty linear scan), and **respawn-entry addressing** (how a slot maps to its respawn-table entry). All slot create/free — windowing, child spawn, dynamic, boss — goes through this one contract. Design it around S3K's "routine-pointer empty" model **now** so it does not bake in S1/S2 id-byte assumptions.
2. **Per-game ROM loader (the placement/windowing port).** S1/S2/S3K each drive their own cursor model + load thresholds against the object-position list, and **delegate** actual slot creation to the contract in (1). This replaces the *timing/dormancy heuristics*, not the allocator.
3. **Per-game object-side unload.** S1 `RememberState`/`out_of_range`, S2 `MarkObjGone`, S3K `Sprite_OnScreen_Test` — each its own off-screen compare (different bases, thresholds, respawn-address models) → clears the respawn "loaded" bit (when the object has a respawn entry) → delegates deletion to the contract in (1).

**Boundary / seam:** the windowing port owns *load/unload timing*; `ObjectManager` owns the *slot allocator primitive* and the execution loop; collision/touch and the sidekick interact-slot foundation are untouched. Dynamic object creation and child spawning continue to use `ObjectManager` allocator APIs unchanged.

**Removal (clarified):** delete the current **manager-level heuristic/dormancy unload path** — `isOutOfRangeS1` distance-unload (`ObjectManager.java:2485-2561`), `AbstractPlacementManager`'s window-recompute, and the dormancy BitSets (`ObjectManager.java:3459-3758`) — **after** replacing it with the per-game ROM object-side unload helpers in (3). Object-side coarse-window unload does **not** go away; the *non-ROM manager heuristic* does. Do not leave the old path running beside the ROM loader (it would double-drive occupancy).

Wording: this is a **"shared SST/respawn/allocator substrate (owned by ObjectManager) + per-game loader + per-game object-side unload semantics"** — *not* a single shared `MarkObjGone`/`AllocateObject`, and *not* a windowing component that owns allocation. The games share concepts but differ in exact fields, empty predicate, thresholds, caller ordering, and respawn-address model.

## 4. Per-game loaders & unload (verified thresholds + citations)

### S2 (reference model)
- **Load** (`ObjectsManager_Main`, `s2.asm:33026`): `camRounded = Camera_X_pos & $FF80` (**no −$80** on the load base). Stated as **FINAL compare boundaries from `camRounded`** (not the ROM's internal post-shift deltas):
  - forward (right) **load edge** = `camRounded + $280`; left-cursor **trim edge** = `camRounded − $80`.
  - backward (left) **load edge** = `camRounded − $80`; right-cursor **trim edge** = `camRounded + $280`.
  - i.e. the live window is `[camRounded − $80, camRounded + $280]` (width **$300**). NOTE: ROM reaches the trim edges via an `addi/subi #$300` applied to `d6` **after** it has already moved to the load edge (e.g. forward: `d6 += $280`, then left-trim `d6 − $300` = `camRounded − $80`). The `$300` is the internal delta / window width, **NOT** a boundary measured from `camRounded` — tests must assert the final `+$280` / `−$80` boundaries, not `±$300` from `camRounded`.
- **`ChkLoadObj`** (`s2.asm:33592`): `bset #7` of the respawn entry both **tests and sets**. If bit 7 was already set → advance the list pointer by 6 and return **success** (`d0=0`) so scanning continues; only a **full SST** (allocation failure) returns failure. *(Required unit test: skip-vs-full distinction.)*
- **Allocate** (`AllocateObject`, `s2.asm:33675`): linear forward search for first slot whose **empty predicate** holds — for S1/S2 that is `id byte == 0`. (S3K's predicate differs — see below; the §3 contract abstracts this.)
- **Delete** (`DeleteObject`, `s2.asm:30324`): zero the whole slot ($40 bytes incl. the id byte).
- **Object-side unload** (`MarkObjGone`, `s2.asm:30209`): `(x_pos & $FF80) − Camera_X_pos_coarse > $80 + roundToNextMultiple(screen_width,$80) + $80`. At native 320px width this constant is **$280** (320 is not a multiple of $80, so it rounds up to $180); since the compared value is $80-coarse, the **first deleting bucket is $300**. `Camera_X_pos_coarse = (Camera_X_pos − $80) & $FF80`. On out-of-range: if `respawn_index != 0`, clear bit 7 of its respawn entry, then `DeleteObject`.

### S1 (own faithful port; structurally close to two-cursor)
- **Loader** (`ObjPosLoad.asm:7`): counter-based; `v_opl_data` = right cursor, `v_opl_data+4` = left cursor; two respawn counters in `v_objstate`. `cameraRounded` from the screen-pos. **FINAL compare boundaries (same shape as S2):** forward **load edge** = `cameraRounded + $280` (640); left **trim edge** = `cameraRounded − $80` (−128); backward **load edge** = `cameraRounded − $80` (−128); right **trim edge** = `cameraRounded + $280`. NOTE: the right trim is `+$280`, **not** `+$300`/+768 — ROM subtracts `$80` first (to the backward load edge) and later adds `$300`, netting to `cameraRounded + $280`. The `+768`/`$300` is the internal delta, not the final boundary.
- **Object-side unload** (`out_of_range` macro `Macros.asm:261`; `RememberState.asm:5`): native compare `128 + 320 + 192 = $280`, with `screenX = (v_screenposx − 128) & $FF80`. Object-driven (sets respawn state, deletes) — **not** placement-window deletion.

### S3K (distinct model — not S2 + a Y filter)
- **State** (`sonic3k.constants.asm:662`): `Object_load_addr_front/back`, `Object_respawn_index_front/back`, a **one-byte** respawn-table entry per object, `Camera_X_pos_coarse`, `Camera_Y_pos_coarse`, `Camera_X_pos_coarse_back`.
- **Empty predicate / delete (DIFFERS from S1/S2):** `AllocateObject` tests the first **longword / object routine-pointer** (`tst.l (a1)`), not the id byte; `Delete_Current_Sprite` zeroes the slot (incl. the routine pointer). The §3 contract's empty predicate must be "routine-pointer longword == 0" for S3K.
- **Load thresholds:** front = first X `>= coarse + $280`; back = first X `>= coarse − $80`.
- **Object-side unload** (`Sprite_OnScreen_Test`, `sonic3k.asm:37262`): `(x_pos & $FF80) − Camera_X_pos_coarse_back > $280`, then clear bit 7 via `respawn_addr(a0)` and `Delete_Current_Sprite`. Note `Camera_X_pos_coarse_back = (Camera_X_pos − $80) & $FF80` — a different subtract target than S2's load base.
- **Respawn-table selection (call out now, even though S3K is staged last):** S3K's loader *selects between respawn tables* and has `Respawn_table_keep`-style retention during setup. The §3 shared substrate must **not** assume a single global respawn table — respawn-entry addressing is per-game and, for S3K, table-selecting. Bake this into the contract's "respawn-entry addressing" surface from the start.

## 5. Per-game frame ordering (NOT universal)

Caller order within the level frame differs per game and must be honored:

- **S1:** execute objects **before** placement loading (`sonic.asm:2950`).
- **S2:** execute objects **before** placement loading (`RunObjects` then `ObjectsManager`, `s2.asm:5095` then `:5112`).
- **S3K:** `Load_Sprites` **before** `Process_Sprites` — i.e. **load-then-execute** (normal level loop `sonic3k.asm:7884`, the calls at `:7893-7894`). *(Not `sonic3k.asm:63150`, which is HPZ/special-stage setup context.)*

Do **not** encode a single "execute then windowing-load" rule for all games. Each game's `LevelFrameStep`-equivalent wires its own order.

## 6. Slot recycle + interact(a0) integration

The slot-recycle behavior (deleted slot **identity cleared** per the per-game empty predicate → reused by next allocate → `interact(a0)` dereference of the recycled **slot → id/type/pointer** yields the new object) is the regression surface that blocks MTZ3 and that the sidekick foundation depends on. With the ROM loader driving load/unload/allocation on ROM-exact frames, the recycle will occur on the ROM-exact frame and the foundation's `objectIdInSlot(interactSlotIndex)` compare will match ROM. No change to the foundation itself; this design supplies the correct slot timeline it reads.

## 7. Verification — slot→id/type occupancy oracle

The traces carry `object_appeared` / `object_near` / `object_removed` aux events: the ROM object-presence timeline. Build a **comparison-only** verifier (no engine-state hydration) that:

1. Reconstructs the **expected SST occupancy** (slot/identity timeline) from `object_appeared`/`object_removed`.
2. Diffs the **engine's per-frame slot → id/type occupancy** against it — slot-level, not merely "set of loaded ids" (slot reuse and recycled-interact are the regression surface).
3. Reports first-divergence frame + slot.

**Recorder caveat:** confirm the recorder emits same-frame remove/appear/**recycle** transitions per slot (a delete + reallocate of the same slot in one frame). If it only emits coarse transition events and can drop a same-frame recycle, the oracle must instead compare **explicit per-frame slot snapshots** (full slot→id/type table per frame), which may require a recorder extension (per the trace-replay-bug-fixing recorder-extension recipe). Decide this empirically before relying on the oracle.

Plus: the full `*TraceReplay` sweep with `-Dsurefire.forkCount=1 -DreuseForks=true` (the pom-correct fork flag), and the four S3K must-keep-green unit tests.

## 8. Testing (required edge cases)

Headless unit tests (no ROM/GL), following existing `Test*` patterns:

- Coarse-camera math per game (S2 load base `cam & $FF80` vs unload base `(cam−$80)&$FF80`; S3K `Camera_X_pos_coarse_back`).
- S1/S2 native **$280** unload compare (and S1's `(v_screenposx−128)&$FF80` screenX).
- S1/S2 cursor boundaries — assert the **FINAL** compare boundaries from `camRounded`: forward load `+$280`, left-trim `−$80`, backward load `−$80`, right-trim `+$280` (live window `[−$80, +$280]`). Explicitly assert the trim edges are `−$80` / `+$280`, **NOT** `±$300` from `camRounded` — a test using `±$300` would bless a loader that trims/loads $80 too far out.
- S2 `ChkLoadObj` `bset #7` **skip-vs-full** distinction (already-set → continue scan; SST-full → fail).
- **Per-game empty predicate**: S1/S2 slot empty ⇔ id byte == 0; **S3K slot empty ⇔ routine-pointer longword == 0** (`tst.l`). Allocation scan finds the first slot satisfying the game's predicate.
- `DeleteObject` / `Delete_Current_Sprite` clear the **slot identity field** the predicate reads (S1/S2 id byte → 0; S3K routine-pointer longword → 0), making the slot allocatable again.
- `respawn_index == 0` (no respawn entry) → **no** bit-clear / no respawn-table touch.
- Slot allocation is the single `ObjectManager`-owned primitive: a windowing load, a child spawn, and a dynamic/boss spawn all draw from the same free-slot scan (no second allocator).
- S3K `Camera_X_pos_coarse_back` unload math (`(x&$FF80) − coarse_back > $280`).
- Slot recycle: delete then allocate reuses the same slot index; `objectIdInSlot` reflects the new id.

## 9. Cascade management & staged rollout

Staged, each stage on its own branch, never merging a stage until its cascade is mapped:

1. **S2 loader + unload + ordering** first (validates the substrate; MTZ1/MTZ3 live here). Full before→after `*TraceReplay` sweep → cascade map (frontier moves + attribution to prior compensating fixes) → re-derive regressed S2 frontiers → merge (accepting documented regressions, per the sidekick-foundation philosophy).
2. **S1 loader + unload + ordering** — same loop.
3. **S3K loader + unload + ordering (front/back cursors, two-axis)** — same loop; keep the four S3K must-keep-green units green; do not regress AIZ/CNZ/MGZ below current frontiers without documenting.

Each stage uses the §7 occupancy oracle to prove frame-exact object presence before judging trace frontiers.

## 10. Risks & non-goals

- **Risk — broad cascade:** retiming object load/unload moves many frontiers; some advance, some regress. Mitigated by the occupancy oracle (proves the timing is *correct*, separating "correct-but-cascaded" from "bug") and staged per-game rollout.
- **Risk — S3K two-axis complexity:** S3K's front/back + Y cursors are the hardest; isolated to stage 3.
- **Risk — removing heuristics destabilizes a currently-green trace:** acceptable per the land-correct philosophy, but every such regression must be attributed in the cascade map and re-derived.
- **Non-goal:** changing the execution loop, collision/touch resolution, or the sidekick interact-slot foundation. Non-goal: widescreen load-window scaling changes beyond matching ROM at native 320px.

## 11. Open question for implementation planning

Whether the per-game loaders share enough to live behind one strategy interface with three implementations, or whether S3K's two-axis model warrants its own structure — to be resolved in the implementation plan after reading the current per-game placement code in full.
