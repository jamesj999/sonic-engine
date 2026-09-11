# Rewind Phase 2: Generic Recreate — Eliminate Hand-Written Codecs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the hand-written per-object `DynamicObjectRewindCodec` (the defect source behind every rewind bug this campaign hit) by making object recreation **generic and uniform** — identity-resolved object references + adoption-in-place + a generated round-trip harness as the authoritative gate.

**Architecture:** Today restore uses *two* mechanisms (constructor reconstruction re-spawns children; ~200 hand-written codecs recreate dynamic objects) and verifies them with *codec-presence* tests that never exercise a round-trip. Phase 2 unifies on **one** mechanism: every captured object is recreated from its captured `ObjectSpawn` + captured constructor inputs, its object-reference fields are captured as **stable ids** (`RewindIdentityTable`) and resolved in a **two-phase restore** (recreate+register all → resolve refs + apply state), and construction-spawned children are **adopted in place** (the keystone shipped 2026-06-18). A generic `capture→restore→diff` harness over every spawnable object replaces codec-presence tests and the hand-maintained `coverage-baseline.txt`.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only. Maven (`mvn -Dmse=off`). Headless object construction via `StubObjectServices` + `GraphicsManager.initHeadless()`. ROM-gated tests pass `-Ds3k.rom.path=...` (and the S1/S2 ROM paths) only where construction needs ROM.

**Spec:** `docs/architecture/designs/2026-06-17-rewind-object-coverage-architecture-design.md` (Phase 2 = its §3.1 reconstruction contract, §3.2 stable identity, §3.3 capture completeness, §3.5 guard, §3.6 trace-fuzz harness). Phase 1 (report-only coverage audit) and the inner-class enumeration + exact-state adoption keystone already shipped to develop (`ba8e386a6`).

## Global Constraints

- JUnit 5 / Jupiter only — no JUnit 4, no `org.junit.*` imports, no assertion-free `@Test`.
- Source files end with a newline. `CHANGELOG.md` is **LF** on this branch (no `.gitattributes` eol rule for `*.md`) — a plain edit is fine; do **NOT** add CRLF (`\r`), which flips the entire file into a ~9.5k-line diff. After editing, confirm `git show <commit> --stat -- CHANGELOG.md` shows only your added lines.
- ArchUnit (`TestArchUnitRules`, frozen baseline, `allowStoreUpdate=false`): shared layers (`com.openggf.game.rewind.*`, `.session`, `.level.objects`) must **not** reference `sonic1/2/3k` types or `GameServices` from object packages. Game-specific recreate logic stays in the game registries; shared code consumes interfaces (`ObjectServices`, `DynamicObjectRecreateContext`).
- Non-`master` commits carry the trailer block (`Changelog`/`Guide`/`Known-Discrepancies`/`S3K-Known-Discrepancies`/`Agent-Docs`/`Configuration-Docs`/`Skills`, each `updated|n/a`). `feat`/`fix` touching `src/main/` need `Changelog: updated` + a CHANGELOG line, or a justified `n/a`. End commit messages with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Must-keep-green every task** (this campaign learned these the hard way — run ALL of them): `TestRewindCoverageGuard`, `TestArchUnitRules`, `TestRewindArchitectureGuard`, `TestObjectServicesMigrationGuard`, `TestGameplayModeContextRewindRegistry`, `TestAiz2ShipLoopRewindRoundTrip`, `TestAiz2TransientChildRewind`, `TestBossChildNoDoubleSpawnParity`, `TestBossChildRewindDoubleSpawn`, `TestBossChildExactStateRewind`, `TestRewindParityAgainstTrace`, `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`.
- **Invariant: no behavior change to forward gameplay.** All work is on the rewind capture/restore path; suppression/adoption is active only inside `ObjectConstructionContext.isRewindActiveRestore()`.
- Branch from `develop` (`feature/ai-rewind-phase2-*` or `bugfix/ai-*`), never `master`.

---

## File Structure

| File | Responsibility | Status |
|------|----------------|--------|
| `src/test/java/com/openggf/game/rewind/TestEveryObjectRewindRoundTrip.java` | **The gate.** Parametrized capture→restore→diff over every headless-constructible spawnable object; asserts count + scalar-state identity through the real `ObjectManager` restore path. | Create (Task 1) |
| `src/test/java/com/openggf/game/rewind/coverage/RewindRoundTripProbe.java` | Existing probe — refactor its construction helpers into a shared `RewindRoundTripHarness` used by the gate. | Modify (Task 1) |
| `src/test/java/com/openggf/game/rewind/RewindRoundTripHarness.java` | Shared headless construct + capture + restore + diff helpers. | Create (Task 1) |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | `rewindCaptureContext()` registers objects (not just players); `DynamicObjectEntry`/`PerSlotEntry` carry an `ObjectRefId`; restore becomes two-phase. | Modify (Tasks 2,5) |
| `src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java` | Add the object's own captured `ObjectRefId` to each entry (its identity). **No object-ref-field map** — ref fields ride the existing compact blob (see the RewindCodecs row). | Modify (Task 2) |
| `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java` + `.../CompactFieldCapturer.java` | Object-ref fields are **already** encoded here (`RewindCodecs.ObjectReferenceCodec`, ~line 97; written/read via `writeObjectRef`/`readObjectRef` ~line 1561) and restored in the compact field blob (`CompactFieldCapturer.restore(...)` (the public entry; the per-field loop is in private `restoreWithSchema`), ~line 84) using the capture context's identity table. Task 3 makes this resolve by registering objects (Task 2) — **no new side channel.** | Reuse (Task 3) |
| `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java` | The default (non-compact) capturer wrapper — note the real path is `…/rewind/GenericFieldCapturer.java`, NOT `…/rewind/schema/…`. Ensure ref-bearing classes route through the compact-blob path so `ObjectReferenceCodec` handles their refs. | Reference (Task 3) |
| `src/main/java/com/openggf/game/rewind/identity/RewindIdentityTable.java` | Add `registerObject`/`idFor`/`resolve` for non-player objects. | Modify (Task 2) |
| `src/main/java/com/openggf/level/objects/RewindRecreatable.java` | The uniform recreate hook replacing codecs. | Create (Task 4) |
| `src/main/java/com/openggf/level/objects/ObjectRewindDynamicCodecs.java` | Generic default recreate (spawn factory + adoption); shared codecs migrate to `RewindRecreatable` or are deleted. | Modify (Tasks 4,6) |
| `src/main/java/com/openggf/game/sonic{1,2,3k}/objects/Sonic{1,2,3k}ObjectRegistry.java` | `DYNAMIC_REWIND_CODECS` entries migrate to `RewindRecreatable`/generic and are deleted. | Modify (Task 6) |
| `src/test/java/com/openggf/tests/TestNoConstructorSideEffects.java` | Guard: spawnable object constructors must not `spawnChild`/`addDynamicObject`/mutate managers. | Create (Task 7) |
| `src/main/java/com/openggf/game/rewind/coverage/RewindCoverageAnalyzer.java` + `coverage-baseline.txt` | Retire in favor of the round-trip gate. | Modify/Delete (Task 8) |

---

## Task 1: Generic round-trip harness (the gate)

**Why first:** It is low-risk (test-only), immediately useful (retroactively guards everything shipped this campaign), and becomes the pass/fail oracle for Tasks 2–8. Unlike the existing `RewindRoundTripProbe` (which re-constructs from spawn and so passes tautologically), this drives the **real** `ObjectManager` capture→restore path so it catches double-spawn, drops, and missing-state.

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/RewindRoundTripHarness.java`
- Create: `src/test/java/com/openggf/game/rewind/TestEveryObjectRewindRoundTrip.java`
- Modify: `src/test/java/com/openggf/game/rewind/coverage/RewindRoundTripProbe.java` (delegate construction to the harness)
- Reference (copy setup from): `src/test/java/com/openggf/game/rewind/TestBossChildExactStateRewind.java`, `src/test/java/com/openggf/game/rewind/TestBossChildNoDoubleSpawnParity.java`

**Interfaces:**
- Produces: `RewindRoundTripHarness.build(GameId)` → a headless `ObjectManager` wired for capture/restore; `RewindRoundTripHarness.roundTrip(om)` → captures the snapshot, restores it, returns a `RoundTripResult(beforeCounts, afterCounts, scalarDiffs)`; `RewindRoundTripHarness.spawnAndStep(om, ObjectSpawn, frames)`.

- [ ] **Step 1: Write the failing test** — assert the boss-child case round-trips through the harness (count + a non-init scalar), reusing the construction from `TestBossChildExactStateRewind`:

```java
@Test
void dezRobotChildrenRoundTripExactlyThroughHarness() {
    RewindRoundTripHarness h = RewindRoundTripHarness.build(GameId.S2);
    var boss = h.spawnPlacedAndStep(Sonic2DeathEggRobotInstance.class, /*frames*/ 6);
    var before = h.countByType();                 // includes ArticulatedChild x6, ForearmChild x2, ...
    int capturedX = h.firstArticulatedChildX();   // a non-init value
    h.roundTrip();                                // capture -> restore through ObjectManager
    assertEquals(before, h.countByType(), "no double-spawn, no drop");
    assertEquals(capturedX, h.firstArticulatedChildX(), "exact state, zero re-sim");
}
```

- [ ] **Step 2: Run it — expect PASS** (the adoption keystone already makes this true). `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.TestEveryObjectRewindRoundTrip" test`. If it FAILS, the harness wiring is wrong (it must call the same `RewindRegistry`/`ObjectManager` capture+restore that `TestBossChildExactStateRewind` uses) — fix the harness, not the assertion.
- [ ] **Step 3a: Make the scanner test-usable.** `com.openggf.game.rewind.coverage.ObjectClasspathScan` is `final class` package-private and its `findConcreteObjectInstances(...)` method + `SourceClass` record are package-private (`ObjectClasspathScan.java:30,60`). Either (a) widen `ObjectClasspathScan` + its scan method + `SourceClass` to `public` (minimal — keeps the harness in `com.openggf.game.rewind`), OR (b) place `RewindRoundTripHarness`/`TestEveryObjectRewindRoundTrip` in `com.openggf.game.rewind.coverage` (no visibility change, but the boss-child tests in `…rewind` then import a public harness from `…coverage`). Pick (a). Run `TestRewindCoverageAnalyzer`/`TestRewindCoverageGuard` after to confirm the visibility change didn't break the existing scanner users.
- [ ] **Step 3b: Add the parametrized sweep** — enumerate spawnable classes via `ObjectClasspathScan` (now usable, includes inner classes), filter to those the harness can construct headlessly (catch construction failures → record as "unprobed", do NOT silently pass). For each: build placed + step a few frames, round-trip, assert count unchanged AND every captured scalar field identical. Emit a report of probed / unprobed / state-mismatch / count-mismatch.
- [ ] **Step 4: Run the sweep — expect GREEN for everything currently codec'd or adopted, and a known unprobed list.** Record the unprobed count in the test's report assertion (so shrinking it is visible). `mvn -Dmse=off -Ds3k.rom.path="…" "-Dtest=com.openggf.game.rewind.TestEveryObjectRewindRoundTrip" test`.
- [ ] **Step 5: Point `RewindRoundTripProbe` at `RewindRoundTripHarness`** so there is one construction path, and run the must-keep-green set.
- [ ] **Step 6: Commit** (`feat(rewind): generic round-trip harness as the rewind correctness gate`; `Changelog: updated`).

**Deliverable:** a single test that fails if any spawnable object fails to round-trip (count or state) — the oracle for the rest of Phase 2.

---

## Task 2: Register every captured object in the identity table

**Why:** `RewindIdentityTable` exists but `ObjectManager.rewindCaptureContext()` (around line 3575) registers only players (`registerPlayer`). To capture object references as ids (Task 3), every captured object needs a stable `ObjectRefId`. The id derives from spawn identity + a per-object instance counter so re-simulation re-mints identical ids.

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/identity/RewindIdentityTable.java` (add object registration)
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (`rewindCaptureContext()` + the capture loops at ~3160 and ~3133)
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java` (`PerSlotEntry`/`DynamicObjectEntry` gain `ObjectRefId objectId`)
- Test: `src/test/java/com/openggf/game/rewind/identity/TestObjectIdentityCapture.java`

**Interfaces:**
- Consumes: `RewindRoundTripHarness` (Task 1).
- Produces: `RewindIdentityTable.registerObject(AbstractObjectInstance, ObjectRefId)`, `RewindIdentityTable.idFor(AbstractObjectInstance)` (→ `ObjectRefId` or null), `RewindIdentityTable.resolve(ObjectRefId)` (→ instance or null). `ObjectRefId.forObject(SpawnRefId spawn, int instanceCounter)`. The dynamic-id counter is itself captured/restored so re-mint order is identical.

- [ ] **Step 1: Write the failing test** — capture a scene with two objects, assert each has a distinct stable `ObjectRefId` and that capturing again (same scene) mints the same ids:

```java
@Test
void everyCapturedObjectGetsAStableId() {
    RewindRoundTripHarness h = RewindRoundTripHarness.build(GameId.S3K);
    var a = h.spawnDynamic(spawnA); var b = h.spawnDynamic(spawnB);
    var ctx1 = h.captureContext();
    ObjectRefId idA = ctx1.identityTable().idFor(a);
    ObjectRefId idB = ctx1.identityTable().idFor(b);
    assertNotNull(idA); assertNotNull(idB); assertNotEquals(idA, idB);
    assertEquals(idA, h.captureContext().identityTable().idFor(a)); // stable across captures
}
```

- [ ] **Step 2: Run — expect FAIL** (`idFor` returns null; objects unregistered).
- [ ] **Step 3: Implement** — add the object-id counter (a field on `ObjectManager`, captured in the snapshot), `registerObject`/`idFor`/`resolve` on the table, and register every object in `rewindCaptureContext()` and at capture time. Add `ObjectRefId objectId` to `PerSlotEntry`/`DynamicObjectEntry` (default-null-tolerant so old snapshots/tests compile).
- [ ] **Step 4: Run the test + Task-1 harness — GREEN.**
- [ ] **Step 5: Commit** (`feat(rewind): register every captured object in the identity table`; `Changelog: updated`).

---

## Task 3: Resolve object-reference fields through the EXISTING compact blob (no side channel)

**Why:** Object-ref fields (`parent`, `owner`, `target`, `head`/`jet`, list members) are why codecs need bespoke relink lambdas. They are **already** captured/restored through the compact field blob: `RewindCodecs.ObjectReferenceCodec` (`RewindCodecs.java:97`) encodes a ref via `writeObjectRef`/`encodeObjectRef` (`:1561`) using the `RewindCaptureContext` identity table, and `CompactFieldCapturer.restore(...)` (`:84`) reads it back. The ONLY reason refs don't resolve today is that the capture context's identity table holds players but not objects — which **Task 2 fixes**. So this task does NOT add an `objectRefs` map or `captureObjectRefs/applyObjectRefs`; it makes the existing path work and routes ref-bearing classes through it.

**Files:**
- Verify/Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java` — confirm `encodeObjectRef`/`readObjectRef` use the now-populated object-id table from Task 2 (resolve to the registered/restored instance, not null); fix the resolve path if it only handled players.
- Verify/Modify: `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java` (the **real** non-compact wrapper, NOT `…/schema/…`) — ensure ref-bearing object classes prefer the compact-blob path (which has `ObjectReferenceCodec`); if a class falls back to the generic path, delegate its `AbstractObjectInstance`-typed fields to `ObjectReferenceCodec` instead of skipping them.
- Test: `src/test/java/com/openggf/game/rewind/schema/TestObjectRefCompactRoundTrip.java`

**Interfaces:**
- Consumes: `RewindIdentityTable.registerObject/idFor/resolve` (Task 2), the existing `RewindCodecs.ObjectReferenceCodec` + `CompactFieldCapturer.restore(...)`. Phase-2 ordering (forward refs) comes from Task 5.
- Produces: object-ref fields round-trip through the existing compact blob with no new snapshot side channel.

- [ ] **Step 1: Write the failing test** — a child with a `parent` ref round-trips with the parent relinked to the *restored* parent instance (identity, not a fresh object), through the compact blob:

```java
@Test
void parentRefRoundTripsViaCompactBlobToRestoredInstance() {
    RewindRoundTripHarness h = RewindRoundTripHarness.build(GameId.S3K);
    var parent = h.spawnDynamic(parentSpawn);
    var child  = h.spawnChildOf(parent);   // child has an AbstractObjectInstance parent field
    h.roundTrip();
    assertSame(h.restored(parent), h.restored(child).parentForTest(),
        "child.parent resolves by id to the restored parent");
}
```

- [ ] **Step 2: Run — expect FAIL** (`ObjectReferenceCodec` resolves the ref to null today because objects weren't in the id table, or the class uses the generic path that skips the ref).
- [ ] **Step 3: Implement** — with Task 2's object registration, make `encodeObjectRef`/`readObjectRef` resolve object ids; route ref-bearing classes through the compact path (or delegate their ref fields to `ObjectReferenceCodec` in the generic wrapper). Do NOT add an `objectRefs` map to `ObjectManagerSnapshot`. Forward-ref ordering is handled by Task 5's two-phase restore.
- [ ] **Step 4: Run test + harness — GREEN.**
- [ ] **Step 5: Commit** (`feat(rewind): resolve object-ref fields via the compact blob + object identity`; `Changelog: updated`).

---

## Task 4: `RewindRecreatable` — the uniform recreate hook

**Why:** Replace per-object codecs with one contract: recreate from the captured `ObjectSpawn` + the captured constructor scalars, defaulting to the registry factory; objects that need non-spawn ctor inputs implement `RewindRecreatable`. Construction-spawned children continue to use the adoption keystone (no recreate needed).

**Files:**
- Create: `src/main/java/com/openggf/level/objects/RewindRecreatable.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectRewindDynamicCodecs.java` (a `genericRecreate(entry, ctx)` that: if the class is `RewindRecreatable`, call its hook; else build from spawn via the registry; never returns a duplicate of an adopted child)
- Test: `src/test/java/com/openggf/level/objects/TestGenericRecreate.java`

**Interfaces:**
- Consumes: `DynamicObjectRecreateContext`, `RewindIdentityTable` (Task 2).
- Produces:
```java
public interface RewindRecreatable {
    /** Recreate this object's instance from its captured spawn + state during rewind restore.
        Object-reference fields are NOT set here — they are resolved by id post-recreate (Task 3/5). */
    AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx);
}
```
`RewindRecreateContext` exposes `entry.spawn()`, the captured scalar state (the compact field blob), and `objectServices()`. It does NOT expose an object-ref map — ref fields are resolved by the compact blob in Task 5's phase 2.

- [ ] **Step 1: Write the failing test** — a spawn-constructible object recreates via `genericRecreate` with no per-object codec registered:

```java
@Test
void spawnConstructibleObjectRecreatesGenerically() {
    var entry = capturedEntryFor(new HtzGroundFireObjectInstance(0,0,0)); // no codec registered
    var inst = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);
    assertNotNull(inst);
    assertEquals(HtzGroundFireObjectInstance.class, inst.getClass());
}
```

- [ ] **Step 2: Run — expect FAIL** (`genericRecreate` doesn't exist; current path needs a registered codec).
- [ ] **Step 3: Implement** `genericRecreate` + `RewindRecreatable`. Migrate `ForearmChild`-style non-spawn children to `RewindRecreatable` where adoption doesn't apply.
- [ ] **Step 4: Run test + harness — GREEN.**
- [ ] **Step 5: Commit** (`feat(rewind): RewindRecreatable + generic recreate contract`; `Changelog: updated`).

---

## Task 5: Two-phase restore (register all → resolve refs + apply state)

**Why:** Object-ref resolution must be order-independent. Phase 1: recreate (generic/adopt) and register every object's id. Phase 2: apply scalar state + resolve object-ref ids to live instances. This is the spec §3.2 ordering guarantee and removes spawn-order fragility.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (`restore()` ~3208–3360: split the single loop into **phase 1** recreate-and-register-id for every entry, then **phase 2** apply each entry's compact field blob — `CompactFieldCapturer.restore(...)`, whose `ObjectReferenceCodec` resolves refs against the now-fully-populated identity table)
- Test: `src/test/java/com/openggf/level/objects/TestTwoPhaseRestoreOrdering.java`

**Interfaces:**
- Consumes: Tasks 2–4. Produces: deterministic restore where a ref to a not-yet-recreated target resolves correctly (target recreated in phase 1 before any phase-2 resolve).

- [ ] **Step 1: Write the failing test** — capture A→B and B→A mutual refs in the order that breaks single-pass resolution; assert both relink after restore.
- [ ] **Step 2: Run — expect FAIL** under the current single-pass apply (forward ref unresolved).
- [ ] **Step 3: Implement** the two-phase split. Keep adoption (phase 1 adopts construction-spawned children; phase 1 generic-recreates the rest).
- [ ] **Step 4: Run test + the FULL must-keep-green set + harness — GREEN.** (This is the riskiest core-path task; the AIZ2 round-trips and boss parity are the canaries.)
- [ ] **Step 5: Commit** (`feat(rewind): two-phase restore with id-resolved object refs`; `Changelog: updated`).

---

## Task 6: Migrate and DELETE the hand-written codecs (batched)

**Why:** With generic recreate + id refs + adoption, the ~200 `DYNAMIC_REWIND_CODECS` entries are redundant. Delete them in reviewed batches, the Task-1 harness verifying each batch round-trips identically.

**Files:** `Sonic{1,2,3k}ObjectRegistry.java`, `ObjectRewindDynamicCodecs.java`, `coverage-baseline.txt` (ratchet up as `#recreate` becomes irrelevant), per-game `TestRewindFix*Codecs` (delete codec-presence assertions superseded by the harness).

- [ ] **Step 1:** For one registry batch (e.g. S1 projectiles), DELETE the codec entries; run the Task-1 harness for those classes → must still round-trip (generic path covers them).
- [ ] **Step 2:** If any class regresses (drop/double/state), it needs `RewindRecreatable` or `@RewindTransient` on a field — add the minimum, re-run.
- [ ] **Step 3:** Remove the now-dead relink helpers and imports; keep `TestArchUnitRules` + `TestObjectServicesMigrationGuard` green.
- [ ] **Step 4: Commit per batch** (`refactor(rewind): delete <area> codecs, covered by generic recreate`; `Changelog: updated`). Repeat per registry area until `DYNAMIC_REWIND_CODECS` is empty (or only genuinely-bespoke entries remain, each justified in a comment).
- [ ] **Step 5:** Update `docs/status/known-discrepancies.md` / `S3K_KNOWN_DISCREPANCIES.md` — remove the "construction-spawned / accept-drop" sections that the generic path makes obsolete.

---

## Task 7: Construction-side-effect guard

**Why:** Constructors that `spawnChild`/`addDynamicObject` are the root of double-spawn and orphan-refs. Forbid them for spawnable objects (push child-spawning to a restore-aware lifecycle hook), eliminating the bug class at the source.

**Files:**
- Create: `src/test/java/com/openggf/tests/TestNoConstructorSideEffects.java` (source-scan guard, modeled on `TestObjectServicesMigrationGuard`'s scanner)
- Modify: any object whose constructor spawns children → move to `initAfterSpawn()`/an explicit hook called outside construction (with a `KNOWN_LEGACY` allowlist for ones not yet migrated, like the existing migration guard).

- [ ] **Step 1: Write the failing test** asserting no spawnable-object constructor references `spawnChild`/`spawnFreeChild`/`addDynamicObject` (allowlist current offenders, e.g. `AbstractBossInstance` → `initializeBossState`).
- [ ] **Step 2: Run — expect FAIL** listing current offenders; seed the allowlist with them.
- [ ] **Step 3:** Make the guard green with the allowlist; file a follow-up to drain the allowlist (migrating `initializeBossState` to a post-construction hook makes adoption unnecessary for those too).
- [ ] **Step 4: Commit** (`test(rewind): guard against constructor child-spawn side effects`; `Changelog: n/a: dev guard`).

---

## Task 8: Converge the two coverage checks — retire the baseline ONLY when both read zero

**Why:** The Task-1 harness and the static `RewindCoverageAnalyzer` are **complementary, not redundant**: the harness verifies dynamic round-trip correctness for objects it can *construct and exercise*; the static analyzer is the only check for classes the harness **cannot instantiate** and for **dormant fields** a few-frame probe never touches. Deleting the gap-key machinery while a `HARNESS_UNCONSTRUCTIBLE` allowlist exists would silently drop `#finalScalar`/`#objectRef`/`#recreate` coverage for exactly those classes/fields. So keep the analyzer as a **failing** guard until the harness fully subsumes it.

**Files:** `RewindCoverageAnalyzer.java`, `TestRewindCoverageGuard.java`, `coverage-baseline.txt`, `RewindFieldInventoryTool.java`.

- [ ] **Step 1: Drive the harness unprobed set toward zero.** For each class the harness can't construct headlessly, make it constructible (extend the harness setup) rather than allowlisting. Track the unprobed count in the Task-1 report assertion so it visibly shrinks.
- [ ] **Step 2: FLIP the static guard from report-only to FAILING** for `#finalScalar`/`#objectRef`/`#recreate`, ratcheting the baseline DOWN as Tasks 4–6 close gaps. The static analyzer now actively fails CI on any field/recreate gap on a class the harness can't reach — covering the harness's blind spots. Keep `TestArchUnitRules` green.
- [ ] **Step 3: Retire the baseline ONLY when both conditions hold:** the harness reports **zero unprobed spawnables** AND the static analyzer reports **zero `#finalScalar`/`#objectRef`/`#recreate` gaps**. At that point the harness alone is a strict superset of the static check; delete `coverage-baseline.txt` + the gap-key machinery and repoint `TestRewindCoverageGuard` to assert harness completeness. **If either is non-zero, do NOT delete — keep both guards** and leave the remaining work as documented TODO. Demote `RewindCoverageAnalyzer` to a reporting-only tool only after deletion.
- [ ] **Step 4: Commit** (`refactor(rewind): converge static + round-trip coverage; retire baseline when both read zero`; `Changelog: updated`).

---

## Self-Review

- **Spec coverage:** §3.1 reconstruction contract → Tasks 4,5,6 (+ the shipped adoption keystone). §3.2 stable identity / two-phase → Tasks 2,3,5. §3.3 capture completeness → Tasks 3,7. §3.5 loud guard → Task 1 (harness) + Task 7 (constructor guard). §3.6 trace-fuzz harness → Task 1 is the static version; a held-rewind-between-keyframes fuzz over traces is a worthwhile **follow-up** (out of this plan's scope — note it in the handoff). §3.4 event-spawn idempotency (the airship-`0x4074` repro, task #10) is **not** covered here — it is a separate event-layer concern; keep it as its own plan.
- **Type consistency:** `ObjectRefId` / `RewindIdentityTable.idFor`/`resolve` / `RewindRecreatable.recreateForRewind` / `genericRecreate` / `RewindRoundTripHarness` names are used identically across tasks.
- **Risk ordering:** Task 1 (gate) is risk-free and gates the rest. Task 5 (two-phase restore) is the highest-risk core-path change — its canaries are the AIZ2 round-trips + boss parity, already in must-keep-green. Task 6 only deletes once the harness proves the generic path covers each class.
- **YAGNI:** no new framework; reuses `RewindIdentityTable`, `GenericFieldCapturer`, the adoption keystone, and `ObjectClasspathScan`.

## Out of Scope (separate plans)
- **Event-spawn idempotency** (multi-airship at X≈`0x4074`, task #10) — event-layer de-dup, its own plan.
- **Trace-driven random held-rewind fuzz harness** (rewinds ending between keyframes during interactions/cutscenes) — a dynamic complement to Task 1's static sweep; worthwhile follow-up.
