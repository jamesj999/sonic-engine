# Rewind Object-Coverage Architecture

**Status:** Draft for review
**Date:** 2026-06-17
**Author:** Farrell (with Claude)
**Related:** `docs/architecture/designs/2026-04-07-runtime-ownership-migration-design.md`, `AGENTS_S3K.md`, branch `bugfix/ai-aiz2-rewind-loop-boss` (AIZ2 rewind fixes that motivated this)

## 1. Problem

Held rewind restores the nearest keyframe (every 60 frames) and **re-simulates forward** to the target frame; only registered snapshots are restored, and only objects with a `DynamicObjectRewindCodec` are recreated. The system is **opt-in with silent failure**: an object is correctly rewound only if someone remembered to (a) give it a recreate codec, (b) ensure every behaviorally-relevant field is a captured (non-`final`, non-`@RewindTransient`) scalar, and (c) make any event that spawns it idempotent across rewind. Nothing fails loudly when one of these is missed, so gaps ship invisibly and surface as in-game glitches.

Observed symptoms (all the same architectural cause):

| Symptom | Failure mode |
|---|---|
| Battleship / bombs vanish on rewind | (1) silent drop — no recreate codec |
| Swing vines, raise/lower platforms snap to spawn state | (2) incomplete capture — runtime state in `final`/uncaptured fields |
| Multiple airships spawn when rewinding across X≈`0x4074` | (3) event re-fire — spawn not reconciled against captured state |
| Breakable-block debris, AIZ intro cutscene break on rewind | (1)+(2)+(3) combined on objects with child trees / event control |

The three failure modes:
1. **Silent drop** — runtime-spawned object lacks a recreate path → `recreateDynamicObject` returns `null` → object gone after restore.
2. **Incomplete state capture** — object is recreated but `GenericFieldCapturer` silently skips `final` scalar fields and non-eligible state → object resets/jumps.
3. **Event-spawn non-idempotency** — an event/cutscene spawns objects based on camera/frame triggers; rewinding across the trigger and replaying re-fires the spawn and/or fails to reconcile the prior instance → duplicates or strays.

## 2. Goals / Non-goals

**Goals**
- Make rewind coverage the **default** for every `AbstractObjectInstance`: an object is recreatable and fully state-captured unless it explicitly opts out with a documented reason.
- **Fail loudly at build time** when an object is not rewind-covered (no recreate path, or has an uncaptured non-transient `final` scalar).
- Replace the fragile spawn-order/sibling-relink recreate logic with a **stable object-identity** system: references captured as ids, resolved after recreate.
- Make **event-driven spawns** rewind-deterministic (no duplicate/stray on cross-trigger rewind).
- Provide a **trace-driven random-rewind smoke harness** as the standing regression net that exercises real gameplay (interactions, cutscenes, bosses) with rewinds that **end between keyframes**.

**Non-goals**
- Perfect rewind of pure presentation state that is cheaply regenerated each frame (e.g. some DPLC/pattern-bank animation cursors) where a 1-frame visual catch-up is acceptable — these may opt out with a `@RewindTransient` + documented note, but the guard records them.
- Rewinding audio logical state beyond the existing deferred-restore design.
- Reworking the keyframe+re-simulation model itself (it is sound; the gaps are coverage).

## 3. Architecture

Seven components across three concerns.

### 3.1 Reconstruction contract (default-on recreate)

The restore path already splits objects into two kinds, and this split is **kept** — the inversion is about *enforcing* coverage, not collapsing the two paths:

1. **Layout objects** (placed in level object data, restored through the registry/placement path) recreate as today: the registry factory from the captured `ObjectSpawn` + reapply captured scalar state via `GenericFieldCapturer`/compact schema. No change.
2. **Dynamic objects** (runtime-spawned children/effects/projectiles/player-bound) frequently have **no** meaningful factory from `ObjectSpawn` alone, so they cannot use the layout default — a registry-factory guess would rebuild the wrong type or null-drop. Each dynamic-spawnable class therefore needs an explicit **default dynamic-recreation contract**: implement the `RewindRecreatable` hook below. There is no silent blanket default for dynamics; what is enforced is that *every* dynamic-spawnable class has a working recreate path:
   ```java
   interface RewindRecreatable {
       ObjectInstance recreateForRewind(RewindRecreateContext ctx);
   }
   ```
   `RewindRecreateContext` exposes the captured spawn, the captured scalar state, and id-resolution for referenced objects (see 3.2). The static guard (3.5) fails the build for any dynamic-spawnable class that has neither a registry-spawn recreate nor this hook — so "silent drop" becomes a build error.
3. **Convert (don't retire) `DYNAMIC_REWIND_CODECS`:** the per-game codec entries become `RewindRecreatable` implementations on (or for) their object classes, **keeping the dynamic/static restore split** (`ObjectManager.recreateDynamicObject` stays the dynamic entry point; layout objects keep their placement path). Retiring the split entirely is deferred until the identity model (3.2) is proven, to limit blast radius. Existing codecs (shields, points, CNZ/AIZ boss children, the AIZ2 ship/boss work on this branch) migrate first.

The object-manager restore stays "clear `dynamicObjects` + rebuild from snapshot," but the dynamic rebuild calls the `RewindRecreatable` contract instead of the ad-hoc codec map.

### 3.2 Stable object identity

- Every gameplay object carries a **stable rewind id** for its lifetime, built on the existing `com.openggf.game.rewind.identity` (`ObjectRefId`, `SpawnRefId`, `RewindIdentityTable`). Ids derive from spawn identity + a per-object instance counter so dynamically-spawned children get stable, reproducible ids across capture/restore and re-simulation.
- **Object→object references** (parent, sibling, target, rider) are captured **as ids**, never as live references.

**Concrete snapshot contract (this is currently missing and must be built — `ObjectManager.rewindCaptureContext()` today registers only *players*, not objects, while object-reference codecs throw without a registered id):**
- `PerSlotEntry` and `DynamicObjectEntry` gain a captured **object id** field; capture assigns/records it from the identity table.
- The **dynamic-id counter** (and any seed used to mint ids) is itself captured/restored, so re-simulation re-mints identical ids in the same order.
- Restore is strictly **two-phase with an ordering guarantee**. The captured per-object state divides into **construction inputs** (the spawn + the specific scalars a `RewindRecreatable` constructor needs — e.g. constructor-fed `final` fields per 3.3) and the **field blob** (the `CompactFieldCapturer` scalar/object-ref blob applied post-construction). Today that blob is applied in a single pass and its object-ref codecs (`RewindCodecs`) resolve their target id **immediately**, requiring the target to already exist — so the ordering, not a blob split, is what must change:
  1. **Recreate + register (NO field-blob apply):** for every captured entry, construct the object (layout factory or `recreateForRewind`, reading only its construction inputs) and register `id → instance` in the identity table. The field blob is **not** applied yet, because it may contain object-ref codecs whose targets are objects not yet recreated.
  2. **Apply field blobs:** only after the whole identity table is populated, apply each object's field blob in one pass. The existing immediate-resolving object-ref codecs now work **unchanged** — every target id is guaranteed present. No blob split or deferred-reference-patch machinery is needed; the guarantee is purely "all ids registered before any field blob is applied." A reference to an id with no live instance is a defined outcome (null + logged), never a spawn-order accident.
- This replaces the current "scan live objects in spawn order" guesswork and the sibling-relink fragility documented for the AIZ boss children, and removes the dependency on recreation order for correctness.

This ordered contract is the foundation that makes 3.1's `RewindRecreatable` hook robust: `recreateForRewind` registers its own id in phase 1 and resolves its parent/sibling by id in phase 2, not by spawn-order luck.

### 3.3 Capture completeness

`GenericFieldCapturer` currently *rejects* unsupported `final` scalar fields, and only has special restore handling for `final` arrays — it silently leaves the rest, the root of failure mode 2. We do **not** rely on reflectively writing Java `final` fields post-construction (fragile, and against the capturer's existing contract). Instead, runtime state that lives in a `final` scalar field must be made restorable by one of:
- **non-`final`** — so the capturer captures and reapplies it directly (the simplest remedy, used for the AIZ2 work on this branch); or
- **constructor-fed** — captured as scalar state and passed back into the object at recreate time via `RewindRecreatable` (the value is supplied to the constructor), or via a `RewindStateful` apply hook for objects that prefer an explicit post-construct apply method.

The **static guard (3.5)** fails the build when a spawnable object has a non-`@RewindTransient` `final` scalar field that is neither spawn-derivable nor fed through one of the two mechanisms above. This makes the "un-final it, or feed it through recreate, or annotate it transient" decision explicit and enforced rather than discovered in-game. No reliance on reflective `final` writes.

### 3.4 Event-spawn idempotency

Event/cutscene-driven spawns (AIZ intro, battleship at `0x4074`, boss arenas) must satisfy: **re-simulating across the trigger reproduces exactly the captured object set — no duplicates, no strays.** Rules:
- Event spawn guards (`battleshipSpawned`, intro flags, boss flags) are captured event state (already true via `ZoneEventSchemaSidecar`); the object-manager snapshot is authoritative on restore (clears+rebuilds), so a faithfully re-simulated segment lands the same objects.
- A **post-restore reconciliation pass** (generalizing the AIZ Fix-B `reconcileAfterRewindRestore`) is a first-class framework hook on the level-event manager: after object restore, each zone/event handler reconciles its one-shot guards against the live (id-resolved) object set — clearing a guard whose object is absent, and never re-spawning when its object is present.
- **De-dup on spawn, keyed by occurrence — NOT by class:** event spawns route through a helper that suppresses a duplicate only when an object with the **same stable event-occurrence id (owning event state + role)** is already live. Class- or mere-presence-based suppression is wrong — it would block legitimately-repeated dynamics of the same class (bombs, debris, projectiles, boss children, intro effects). The key is "this specific airship spawned by this specific trigger occurrence", so re-firing the same occurrence is suppressed while a genuinely new occurrence still spawns. The airship-`0x4074` duplicate is the canonical case to reproduce and close here (see Open Questions).

### 3.5 Loud static guard

A JUnit guard (no ROM) that:
- Enumerates every **spawnable** object class: per-game registries (`Sonic1/2/3kObjectRegistry`), child-spawn sites (`spawnChild`/`spawnFreeChild`/`spawnBossComponent`/`addDynamicObject`), and `RewindRecreatable` implementors.
- Fails if any spawnable class: has no recreate path (not default-constructible from spawn and not `RewindRecreatable`); or has a non-`@RewindTransient` `final` scalar field not derivable from spawn; or holds a live object reference field not captured as an id.
- Emits a coverage report (feeds 3.7). Opt-outs require a `@RewindTransient`/documented annotation so the guard passes with an explicit, auditable reason rather than silence.

### 3.6 Trace-driven random-rewind smoke harness

The primary correctness net. Extends the existing `*TraceReplay` infrastructure:
- During a trace replay, at **seeded-random frames** (reproducible per seed), inject a **multi-second held rewind that ends between keyframes** (not on a 60-frame boundary — the re-simulated case where bugs live), then resume forward.
- Bias injection timing toward **object interactions, cutscenes, and boss/airship spawns** (e.g. windows where dynamic-object count is non-trivial, or specific tagged frames like the AIZ intro and the `0x4074` crossing).
- **Invariants asserted** after each rewind+resume:
  - Object-set consistency: no object present that the captured timeline didn't have at that frame; no captured object missing (count + id-set diff).
  - Determinism: re-simulated state at the target frame matches an independently-captured reference for that frame (within the existing rewind-determinism tolerance).
  - Sanity: no NaN/garbage positions; event guard ↔ live object consistency.
- Runs against S1/S2/S3K traces; explicitly covers AIZ intro, swing vines, platforms, breakable debris, miniboss/end-boss.

### 3.7 Audit tooling

Extend `RewindFieldInventoryTool` (`com.openggf.tools.rewind`) to print, per game/zone: untracked spawnable objects, uncaptured `final` scalar fields, live-ref fields not id-captured, and current opt-outs with their documented reasons. Used to drive the phased rollout and to review coverage in PRs.

## 4. Data flow (capture / restore)

**Capture (per keyframe):** for each live object → record (id, spawn, scalar-state blob, id-encoded object refs). Event state captured via existing sidecars. Identity table snapshot captured.

**Restore:** clear live dynamic set → **phase 1:** recreate each captured object (layout factory or `recreateForRewind`, reading only construction inputs) and register its id — *no field blob applied yet* → **phase 2:** with all ids registered, apply each object's field blob in one pass (the existing immediate-resolving object-ref codecs find every target in the populated identity table) → run event-handler reconciliation (3.4) → existing tilemap/atlas/camera restore.

**Held-rewind step:** unchanged control flow (`RewindController.stepBackward` → `SegmentCache` re-sim → `registry.restore`); the correctness now comes from complete capture + faithful recreate, not from gating.

## 5. Testing strategy

- **Static guard (3.5)** — fast, runs every build; the loud gate.
- **Dynamic spot-check** — per representative object, a capture→mutate→restore round-trip asserting full state round-trips (ROM-gated where needed); covers the recreate contract and id resolution.
- **Trace-fuzz smoke (3.6)** — the broad behavioral net; seeded so failures reproduce.
- Existing must-keep-green (`TestRewindParityAgainstTrace`, `TestRewindDeterminismAuditor`, `TestS3kAiz1SkipHeadless`, S3K loading) remain green throughout.

## 6. Phased delivery

1. **Audit + static guard** — implement 3.5 + 3.7 first to make the *true* scope visible (list every gap) before changing behavior. **IMPLEMENTED (report-only) on `feature/ai-rewind-coverage-audit`; baseline = 1705 gap keys: 584 `#recreate` (mostly layout over-approximations, refined in Phase 2), 1079 `#finalScalar`, 42 `#objectRef`.** Guard starts in report-only mode, then flips to failing once the backlog is burned down.
2. **Identity model + convert codecs to `RewindRecreatable`** — 3.2 (per-object ids in the snapshot, dynamic-counter capture, ordered two-phase restore) + 3.1. **Convert** the existing `DYNAMIC_REWIND_CODECS` entries into `RewindRecreatable` implementations **while keeping the dynamic/static restore split**; do *not* retire the split yet. This is the smaller-blast-radius path: it lets the identity model be tested against the already-covered objects before any new dynamic-object path changes, and eliminates silent drop + ref fragility incrementally. Retiring the split is a later, optional step once identity is proven.
3. **Capture completeness** — 3.3; close the `final`-field gap; guard flips to failing for it.
4. **Event idempotency** — 3.4; reproduce and close the airship-`0x4074` duplicate and AIZ intro; generalize Fix-B reconcile.
5. **Trace-fuzz smoke harness** — 3.6 as the standing regression net.

Each phase is independently shippable and leaves the tree green.

## 7. Risks / open questions

- **Airship `0x4074` duplicate root cause** is not yet pinned — a headless repro (drive camera across `0x4074`, rewind across it, forward again; assert single `AizBattleshipInstance`) is the first task of Phase 4 (and a useful early data point). The earlier headless scrub test saw max 1 ship, so the duplicate likely needs the exact event-trigger geometry; the repro must hit it.
- **`final` runtime state** is made restorable by non-`final` or constructor-feeding (via `RewindRecreatable`/`RewindStateful`), never by reflective post-construction writes; the guard enforces this. The residual risk is volume — how many existing fields need un-finaling or constructor-feeding; the Phase-1 audit quantifies it.
- **Identity stability across re-simulation** — ids must be reproducible when an object is re-spawned during re-sim (deterministic counter keyed on spawn + deterministic spawn order). Validate with the determinism auditor.
- **Performance** — capturing more fields and an id table per keyframe adds cost; measure against `RewindBenchmark`; the trace-fuzz harness is dev/CI-only.
- **Scope of "spawnable" enumeration** — must catch child-spawn sites that don't go through a registry; the guard's enumeration completeness is itself worth a test.
- **Migration churn** — converting `DYNAMIC_REWIND_CODECS` to `RewindRecreatable` touches all three games; do it incrementally while keeping the dynamic/static split so each game stays green. Fully retiring the split is deferred and optional.

## 8. Success criteria

- Static guard green with zero un-annotated coverage gaps across S1/S2/S3K.
- Trace-fuzz smoke harness green across the trace corpus with rewinds ending between keyframes during interactions/cutscenes/bosses.
- The motivating symptoms (airship duplicate, swing vines, platforms, breakable debris, AIZ intro) demonstrably fixed, each with a regression assertion.
- Adding a new object requires no rewind-specific work in the common case; the guard tells the author when it does.
