# Mod Support Phase 0 (Engine Foundations) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the three mod-support foundations: the GamePatch framework with ordered composition (workstream A), the LoadOp/ResourceLoader load-source abstraction (B), and editor completion — spawn editing, collision editing, save payload v2, trace-entry refusal (C).

**Architecture:** A executes the approved KiS2 plan's framework tasks (`docs/architecture/plans/2026-06-12-game-patch-kis2.md`, Tasks 2/3/7/8/9) with the Phase 0 spec's amendments, replacing that plan's Task 4 with a composing `GamePatchRegistry` + `PatchEnablement` seam. B widens `LoadOp` behind its existing factory statics so ~30 call sites see zero churn. C wires the already-existing-but-uncalled `MutableLevel` spawn-mutation API into editor commands, adds collision-bit commands over already-persisted state, and bumps the save envelope to v2.

**Tech Stack:** Java 21, existing engine seams only. No new dependencies.

**Specs:** `docs/architecture/designs/2026-07-09-mod-support-phase0-design.md` (authoritative, incl. its five amendments to the KiS2 artifacts); parent `2026-07-09-mod-support-design.md`; KiS2 design + plan as amended.

## Global Constraints

- **JUnit 5 / Jupiter only.**
- **Commit trailers:** all 7 trailers on every commit; `feat`/`fix` commits touching `src/main/` use `Changelog: n/a: covered by final phase-0 changelog entry in this branch` until the final docs task sets `Changelog: updated`.
- **Never `git add -A`** — exact paths only (shared repo, concurrent sessions).
- **No new singletons; no new Maven dependencies.**
- **Branch:** `feature/ai-mod-support-phase0` off `develop`.
- **Workstream order:** A tasks (A1–A7), then B (B1–B2), then C (C1–C6). A/B/C are independent — a different order or parallel worktrees is fine, but within a workstream the order is load-bearing.
- **The KiS2 plan is the code source for A.** Where a task below says "execute KiS2-plan Task N", open `docs/architecture/plans/2026-06-12-game-patch-kis2.md`, follow that task's steps/code verbatim, applying only the amendment deltas listed here. Do not re-derive its code.
- **Read-first rule:** recon anchors are from 2026-07-09; before modifying any existing file, read the target region and follow the named methods if lines moved.
- **ArchUnit watch:** new package `com.openggf.game.patch` sits inside the `game` slice (`com.openggf.game.*` → top-level slice `game`), so A adds **no** new top-level edges. B and C stay inside existing `level`/`editor` slices. If `TestArchUnitRules` fails anyway, add exactly the edges its failure message names, with a comment citing this plan.
- **Trace safety:** any task that could affect gameplay behavior ends by running the S3K must-keep-green set: `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test`.

---

## Workstream A — GamePatch framework + composition

**Not in this plan:** KiS2 content (KiS2-plan Tasks 1, 5–6, 10–12). Execute those from the KiS2 plan afterwards, applying spec Amendment 3 (no `PhysicsFeatureSet`: skip Task 5 Step 2's feature-set decision, drop the `featureSetIsModuleScopedSonic2` test and the `getFeatureSet()` override; Task 1's feature-set pointer feeds the rule-placement decision instead).

### Task A1: GamePatch contracts + DelegatingGameModule + guard test

**Files:** exactly as KiS2-plan Task 2 (package `com.openggf.game.patch`: `GamePatch`, `PatchContext`, `GameplayLaunchRequest`, `LogicalRom`, `DelegatingGameModule`, test `TestDelegatingGameModuleCoversInterface`).

**Interfaces:**
- Consumes: `GameModule` (all abstract + default methods), KiS2-plan Task 2 code.
- Produces (names are KiS2-plan Task 2's, authoritative): `record GameplayLaunchRequest(String gameId, String mainCharacter, List<String> sidekicks)` (the 3-component form — spec Amendment 4); `GamePatch` with `id()/displayName()/baseGameId()/activatesFor(GameplayLaunchRequest)/romPrerequisites() -> Set<LogicalRom>/providedMainCharacters() -> List<String>/apply(GameModule, PatchContext)`; `DelegatingGameModule` forwarding every `GameModule` method; the reflection guard test.

- [ ] **Step 1:** Execute KiS2-plan Task 2 verbatim (its steps contain the full code and the guard test). **Amendment delta (spec Amendment 1):** Task 2's `GamePatch` Javadoc says patches "never stack (one patch per session)" — replace that sentence with the composition wording ("patches compose as an ordered stack; see GamePatchRegistry"). No other deltas (Task 2 already defines the 3-component record; if anything else drifts from the spec's pinned shapes, flag it to the spec owner instead of improvising).
- [ ] **Step 2:** Run the task's tests as specified there. Expected: PASS.
- [ ] **Step 3:** Commit with this plan's trailer convention:

```bash
git add src/main/java/com/openggf/game/patch/ src/test/java/com/openggf/game/patch/
git commit -m "feat: GamePatch contracts, DelegatingGameModule, coverage guard

Changelog: n/a: covered by final phase-0 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task A2: `PatchEnablement` seam + contract test

**Files:**
- Create: `src/main/java/com/openggf/game/patch/PatchEnablement.java`
- Test: `src/test/java/com/openggf/game/patch/TestPatchEnablementContract.java`

**Interfaces:**
- Produces: `PatchEnablement` with `boolean isEnabled(String patchId)`, `int orderOf(String patchId)`, constant `ALL_ENABLED`. **Pinned unknown-id contract (spec Amendment 2):** an id the implementation does not manage MUST be enabled and ordered before all managed patches; among unknown ids, registration order decides (the registry, not this interface, supplies registration order — see Task A4's two-key sort). Contract test is written against the interface semantics so Phase 2's `ModCatalog`-backed implementation can extend it.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.patch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestPatchEnablementContract {

    /** Reference managed implementation used to validate the contract shape. */
    private static PatchEnablement managed(Map<String, Integer> orderByEnabledId) {
        return new PatchEnablement() {
            @Override
            public boolean isEnabled(String patchId) {
                return !orderByEnabledId.containsKey(patchId) // unknown -> enabled
                        || orderByEnabledId.get(patchId) >= 0;
            }

            @Override
            public int orderOf(String patchId) {
                Integer order = orderByEnabledId.get(patchId);
                return order == null ? PatchEnablement.UNMANAGED_ORDER : order;
            }
        };
    }

    @Test
    void allEnabledEnablesEverythingInRegistrationOrder() {
        assertTrue(PatchEnablement.ALL_ENABLED.isEnabled("kis2"));
        assertTrue(PatchEnablement.ALL_ENABLED.isEnabled("anything"));
        assertEquals(PatchEnablement.UNMANAGED_ORDER,
                PatchEnablement.ALL_ENABLED.orderOf("anything"));
    }

    @Test
    void unknownIdsAreEnabledAndOrderedBeforeManagedOnes() {
        PatchEnablement e = managed(Map.of("mod-a", 0, "mod-b", 1));
        assertTrue(e.isEnabled("kis2")); // built-in, unmanaged
        assertTrue(e.orderOf("kis2") < e.orderOf("mod-a"));
        assertTrue(e.orderOf("mod-a") < e.orderOf("mod-b"));
    }

    @Test
    void unmanagedOrderIsBelowAnyValidManagedOrder() {
        assertTrue(PatchEnablement.UNMANAGED_ORDER < 0);
    }
}
```

- [ ] **Step 2:** Run `mvn "-Dtest=com.openggf.game.patch.TestPatchEnablementContract" test` — COMPILE FAILURE.
- [ ] **Step 3: Implement**

```java
package com.openggf.game.patch;

/**
 * Enablement + ordering gate for patch composition (Phase 0 spec Amendment 2).
 *
 * Contract for UNKNOWN ids — pinned because Phase 2's mod-catalog-backed
 * implementation depends on it: an id the implementation does not manage
 * (built-in patches such as "kis2" never appear in a mod catalog) MUST report
 * isEnabled = true and MUST be ordered before all managed patches (orderOf
 * returns UNMANAGED_ORDER). Among unknown ids, the registry's registration
 * order breaks ties. This keeps built-ins the base-most layer, active
 * regardless of mod-manager state or force-disable.
 */
public interface PatchEnablement {

    /** Order value for unmanaged/built-in ids: sorts before every managed order (>= 0). */
    int UNMANAGED_ORDER = -1;

    boolean isEnabled(String patchId);

    int orderOf(String patchId);

    /** Default: everything enabled, everything unmanaged (registration order). */
    PatchEnablement ALL_ENABLED = new PatchEnablement() {
        @Override
        public boolean isEnabled(String patchId) {
            return true;
        }

        @Override
        public int orderOf(String patchId) {
            return UNMANAGED_ORDER;
        }
    };
}
```

- [ ] **Step 4:** Run the test — all 3 PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/patch/PatchEnablement.java src/test/java/com/openggf/game/patch/TestPatchEnablementContract.java
git commit -m "feat: PatchEnablement seam with pinned unknown-id contract

Changelog: n/a: covered by final phase-0 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task A3: Logical ROM resolver

- [ ] **Step 1:** Execute KiS2-plan Task 3 verbatim (LogicalRom enum, combined-ROM backend via `RomManager.getSecondaryRom("s3k")`, `>= 0x200000` bounds guard, tests). No amendments apply.
- [ ] **Step 2:** Run its tests — PASS. Commit per its staging list with this plan's trailer convention (subject `feat: logical ROM resolver for patch prerequisites`).

---

### Task A4: Composing `GamePatchRegistry` (replaces KiS2-plan Task 4)

**Files:**
- Create: `src/main/java/com/openggf/game/patch/GamePatchRegistry.java`
- Test: `src/test/java/com/openggf/game/patch/TestGamePatchRegistry.java`

**Interfaces:**
- Consumes: `GamePatch`, `PatchContext`, `GameplayLaunchRequest`, `PatchEnablement` (A1/A2), logical-ROM resolver (A3). Read KiS2-plan Task 4 first.
- Produces — **KiS2-plan Task 4's STATIC facade shape is kept.** Tasks 7/8/9 (executed verbatim in A5/A6) call `GamePatchRegistry.availableMainCharacters(String)`, static `resolveModule(...)`, `resetState()`, and `setPrerequisiteCheckForTests(...)` from production code and tests; an instance registry would break them. Carry over Task 4's statics (registration storage, prerequisite checking, `PatchContext` construction) verbatim. Deltas:
  - `static void setEnablement(PatchEnablement enablement)` — new; field defaults to `ALL_ENABLED`; `resetState()` also resets it to `ALL_ENABLED`.
  - `static List<String> availableMainCharacters(String gameId)` — Task 4's query (Task 4's name and parameter type, NOT `availableCharacters(GameId)`), additionally filtered to `enablement.isEnabled(id)` patches.
  - `static GameModule resolveModule(GameModule base, GameplayLaunchRequest request)` — **composition replaces Task 4's single-patch body (spec Amendment 1):** filter registered patches whose `baseGameId()` matches (Task 4's existing base-game matching) to those where `enablement.isEnabled(id)` AND `activatesFor(request)` AND ROM prereqs resolve; sort by `(enablement.orderOf(id), registrationIndex)`; fold left `module = patch.apply(module, patchContextFor(patch))`. Zero survivors → `base` unchanged (same instance).

- [ ] **Step 1: Read KiS2-plan Task 4** end-to-end; only `resolveModule`'s body and the enablement additions change — everything else carries over.
- [ ] **Step 2: Write the failing test**

```java
package com.openggf.game.patch;

import com.openggf.game.GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGamePatchRegistry {

    /** Marker each stub patch layers on so composition order is observable. */
    interface PatchTrail {
        List<String> appliedPatchIds();
    }

    // NOTE: Task 4's own FakePatch returns a bare DelegatingGameModule and does
    // NOT record a trail — this trail-recording stub is NEW code in this test.
    // Method names/ctor per KiS2-plan Task 2's real signatures (verify in Step 1).
    private static GamePatch stubPatch(String id, String baseGameId, boolean activates) {
        return new GamePatch() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public String baseGameId() { return baseGameId; }
            @Override public boolean activatesFor(GameplayLaunchRequest request) { return activates; }
            @Override public java.util.Set<LogicalRom> romPrerequisites() { return java.util.Set.of(); }
            @Override public List<String> providedMainCharacters() { return List.of(); }
            @Override public GameModule apply(GameModule base, PatchContext ctx) {
                List<String> trail = new ArrayList<>(
                        base instanceof PatchTrail t ? t.appliedPatchIds() : List.of());
                trail.add(id);
                class Patched extends DelegatingGameModule implements PatchTrail {
                    Patched() { super(base, id); }
                    @Override public List<String> appliedPatchIds() { return List.copyOf(trail); }
                }
                return new Patched();
            }
        };
    }

    private static GameplayLaunchRequest anyRequest() {
        return new GameplayLaunchRequest("s2", "sonic", List.of());
    }

    @BeforeEach
    void reset() {
        GamePatchRegistry.resetState(); // per Task 4; also resets enablement now
    }

    @Test
    void zeroSurvivorsReturnsBaseInstanceUnchanged() {
        GameModule base = new Sonic2GameModule();
        GamePatchRegistry.register(stubPatch("p1", "s2", false)); // activation rejects
        assertSame(base, GamePatchRegistry.resolveModule(base, anyRequest()));
    }

    @Test
    void survivorsComposeInEnablementThenRegistrationOrder() {
        GamePatchRegistry.register(stubPatch("builtin", "s2", true)); // unmanaged
        GamePatchRegistry.register(stubPatch("mod-b", "s2", true));
        GamePatchRegistry.register(stubPatch("mod-a", "s2", true));
        GamePatchRegistry.setEnablement(new PatchEnablement() {
            @Override public boolean isEnabled(String id) { return !id.equals("mod-b"); }
            @Override public int orderOf(String id) {
                return switch (id) { case "mod-a" -> 0; case "mod-b" -> 1;
                    default -> PatchEnablement.UNMANAGED_ORDER; };
            }
        });
        GameModule resolved = GamePatchRegistry.resolveModule(new Sonic2GameModule(), anyRequest());
        assertEquals(List.of("builtin", "mod-a"), ((PatchTrail) resolved).appliedPatchIds());
    }

    @Test
    void wrongBaseGameNeverApplies() {
        GameModule base = new Sonic2GameModule();
        GamePatchRegistry.register(stubPatch("p1", "s3k", true));
        assertSame(base, GamePatchRegistry.resolveModule(base, anyRequest()));
    }
}
```

(Adapt the stub's overridden method names/ctor to Task 2's real signatures found in Step 1; if `new Sonic2GameModule()` needs collaborators headless, use Task 4's base-module fixture instead. The three composition assertions are the contract and are non-negotiable.)

- [ ] **Step 3:** Run `mvn "-Dtest=com.openggf.game.patch.TestGamePatchRegistry" test` — COMPILE FAILURE.
- [ ] **Step 4: Implement** `GamePatchRegistry` — KiS2-plan Task 4's static-facade class with the static `resolveModule` body replaced:

```java
public static GameModule resolveModule(GameModule base, GameplayLaunchRequest request) {
    List<RegisteredPatch> survivors = registeredFor(base) // Task 4's base-game matching
            .stream()
            .filter(rp -> enablement.isEnabled(rp.patch().id()))
            .filter(rp -> rp.patch().activatesFor(request))
            .filter(GamePatchRegistry::romPrerequisitesSatisfied)
            .sorted(Comparator
                    .comparingInt((RegisteredPatch rp) -> enablement.orderOf(rp.patch().id()))
                    .thenComparingInt(RegisteredPatch::registrationIndex))
            .toList();
    GameModule module = base;
    for (RegisteredPatch rp : survivors) {
        module = rp.patch().apply(module, patchContextFor(rp.patch()));
    }
    return module;
}
```

(`record RegisteredPatch(GamePatch patch, int registrationIndex)` wraps Task 4's storage; `registeredFor`, `romPrerequisitesSatisfied`, `patchContextFor` per Task 4's structure; new field `private static PatchEnablement enablement = PatchEnablement.ALL_ENABLED;`, set by `setEnablement`, reset in `resetState()`.)

- [ ] **Step 5:** Run the test — all 3 PASS. **Port** KiS2-plan Task 4's still-applicable test methods (e.g. `availableMainCharactersReflectsPrerequisites`) into this test file — the new file replaces Task 4's test, so its coverage must move, not vanish. **Explicitly NOT ported:** `resolvingAnAlreadyPatchedModuleDoesNotDoubleWrap` — Task 4's unwrap-first re-resolution is deliberately dropped under composition (unwrapping would strip legitimately stacked patches down to the raw base). Re-resolving an already-resolved module is unsupported; every choke point resolves a freshly detected base module, which A6's wiring preserves.
- [ ] **Step 6: Commit** (`feat: composing GamePatchRegistry with enablement-gated ordered stack`; stage the registry + test; this plan's trailers).

> **Spec-acceptance note:** the spec's §A acceptance cites "KiS2-plan Task 13's headless integration test shape". Task 13 executes on the follow-on KiS2-content branch (spec open question "KiS2 content timing"); within Phase 0, this task's composition test is the stacking coverage. Recorded here so that criterion is visibly mapped, not silently dropped.

---

### Task A5: LaunchProfile availability union

- [ ] **Step 1:** Execute KiS2-plan Task 7 verbatim (threads `GamePatchRegistry.availableMainCharacters(String)` into `LaunchProfile.mainCharacterValues`/`sanitizedFor` — the static facade A4 kept, so Task 7's code compiles as written). No amendments.
- [ ] **Step 2:** Its tests PASS; commit per its staging list, this plan's trailers.

---

### Task A6: Choke-point wiring + save-context sanitization

- [ ] **Step 1:** Execute KiS2-plan Task 8 (wire `resolveModule` at `Engine.initializeGame()`, `HeadlessGameBoot.boot`) and Task 9 (data-select path: request from `SaveSessionContext.selectedTeam()`, team sanitization) with these deltas:
   - Requests are the 3-component record (already Task 2's shape).
   - The trace path needs **no** extra wiring (spec Amendment 4: `TraceReplaySessionBootstrap.prepareConfiguration` already writes the character config keys before launch, so `initializeGame`'s synthesis covers traces).
   - **Task 8's `TestPatchResolutionAtBoot` cannot be taken verbatim** — it registers `com.openggf.game.sonic2.kis2.Kis2GamePatch` and asserts on `Kis2PhysicsProvider`, classes that only exist after the KiS2-content tasks this plan defers. Substitute a local fake patch (Task 7's inline `knucklesPatch()` stub idiom, or A4's trail stub): register it, assert a Knuckles-request launch resolves a decorated module (`instanceof DelegatingGameModule` / the stub's marker) and a Sonic-request launch resolves the same base instance. Keep every other assertion of that test.
- [ ] **Step 2:** Full `mvn test` + the S3K must-keep-green set + a trace spot sweep (`s1_ghz1`, `s2_ehz1`, `s3k_aiz1` replay classes) — all green: with no patches registered, `resolveModule` returns the base instance and behavior is bit-identical.
- [ ] **Step 3:** Commit per KiS2-plan Task 8/9 staging, this plan's trailers. Update `docs/status/trace-frontier-log.md` with the sweep (command, commit, pass/fail) per repo policy.

---

### Task A7: Bootstrap-bypass audit + guard test

**Files:**
- Test: `src/test/java/com/openggf/game/patch/TestBootstrapModuleProviderCachingGuard.java`

**Interfaces:** scanner-based source test in the `TestObjectServicesMigrationGuard` idiom (read that class first and copy its file-walking/allowlist mechanics).

- [ ] **Step 1:** Read `TestObjectServicesMigrationGuard` for the scan idiom. Grep `src/main` for `bootstrapGameModule()` and `getBootstrapDefault()` call sites; for each, verify a session-rebind path exists (the known one: `AbstractPlayableSprite.resolvePhysicsProfile` + `refreshRuntimeBoundStateIfNeeded`).
- [ ] **Step 2:** Write the guard: fails if any `src/main` file outside the allowlist calls `bootstrapGameModule()`/`getBootstrapDefault()` **and** stores the result of a `get*Provider()` chained call in a field (regex over source: `bootstrapGameModule\(\)\s*\.\s*get\w+Provider\(\)` and two-line assignment variants). Allowlist = the audited call sites from Step 1 with a comment justifying each.
- [ ] **Step 3:** Run it — PASS (if it fails, the failure is a real Phase 0 finding: fix the consumer to re-resolve via the session module, then re-run).
- [ ] **Step 4:** Commit (`test: guard against provider caching from bootstrap module`; this plan's trailers).

---

## Workstream B — Load-source abstraction

### Task B1: `LoadSource` + widened `LoadOp`

**Files:**
- Create: `src/main/java/com/openggf/level/resources/LoadSource.java`
- Modify: `src/main/java/com/openggf/level/resources/LoadOp.java`
- Test: `src/test/java/com/openggf/level/resources/TestLoadOpSources.java`

**Interfaces:**
- Produces:
  - `sealed interface LoadSource permits LoadSource.RomAddress, LoadSource.ModAsset` with `record RomAddress(int addr)` and `record ModAsset(Path jar, String entryPath)` (the parent spec's `ModAssetSource`).
  - `record LoadOp(LoadSource source, CompressionType compressionType, int destOffsetBytes)`.
  - **Every existing factory static keeps its exact signature** (`base(int, CompressionType)`, `overlay`, `append`, `kosinskiBase/Overlay/Append`, `kosinskiMBase/MOverlay/MAppend`, `uncompressedBase` — read the current file for the authoritative list) and wraps the int in `RomAddress`.
  - New factories: `modAssetBase(Path jar, String entry)`, `modAssetOverlay(Path jar, String entry, int destOffsetBytes)`, `modAssetAppend(Path jar, String entry)` — all `CompressionType.UNCOMPRESSED`.
  - Compat accessor `int romAddr()` — returns `RomAddress.addr()`, throws `IllegalStateException` for mod sources; Javadoc marks it legacy-compat (Phase 2 may remove).

- [ ] **Step 1: Read `LoadOp.java` fully** (current recon: 3-component record, factory statics, `APPEND_TO_PREVIOUS` sentinel, `appendsToPrevious()`).
- [ ] **Step 2: Write the failing test**

```java
package com.openggf.level.resources;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestLoadOpSources {

    @Test
    void romFactoriesProduceRomAddressSourcesAndCompatAccessor() {
        LoadOp op = LoadOp.kosinskiBase(0x123456);
        assertEquals(new LoadSource.RomAddress(0x123456), op.source());
        assertEquals(0x123456, op.romAddr());
        assertEquals(CompressionType.KOSINSKI, op.compressionType());
    }

    @Test
    void appendSentinelBehaviorUnchanged() {
        assertTrue(LoadOp.kosinskiAppend(0x10).appendsToPrevious());
        assertFalse(LoadOp.kosinskiBase(0x10).appendsToPrevious());
    }

    @Test
    void modAssetFactoriesAreUncompressedAndRejectRomAddrAccessor() {
        LoadOp op = LoadOp.modAssetBase(Path.of("m.jar"), "assets/patterns.bin");
        assertEquals(CompressionType.UNCOMPRESSED, op.compressionType());
        assertInstanceOf(LoadSource.ModAsset.class, op.source());
        assertThrows(IllegalStateException.class, op::romAddr);
        LoadOp overlay = LoadOp.modAssetOverlay(Path.of("m.jar"), "a.bin", 0x80);
        assertEquals(0x80, overlay.destOffsetBytes());
        assertTrue(LoadOp.modAssetAppend(Path.of("m.jar"), "a.bin").appendsToPrevious());
    }
}
```

- [ ] **Step 3:** Run `mvn "-Dtest=com.openggf.level.resources.TestLoadOpSources" test` — COMPILE FAILURE.
- [ ] **Step 4: Implement.** `LoadSource.java`:

```java
package com.openggf.level.resources;

import java.nio.file.Path;

/**
 * Where a LoadOp reads its bytes. RomAddress is the historical behavior
 * (byte-identical); ModAsset reads a mod-jar entry, whose own length supplies
 * the size UNCOMPRESSED ops previously lacked. This is the parent mod-support
 * spec's "ModAssetSource".
 */
public sealed interface LoadSource {

    record RomAddress(int addr) implements LoadSource {
    }

    record ModAsset(Path jar, String entryPath) implements LoadSource {
    }
}
```

`LoadOp.java`: widen to `record LoadOp(LoadSource source, CompressionType compressionType, int destOffsetBytes)`; keep `APPEND_TO_PREVIOUS` + `appendsToPrevious()`; every existing factory delegates through a private `rom(int addr, CompressionType c, int dest)` helper; add the three mod factories; add:

```java
/**
 * Legacy accessor for ROM-sourced ops. Prefer source() in new code; Phase 2
 * may remove this once ad-hoc art loads migrate to source-typed factories.
 */
public int romAddr() {
    if (source instanceof LoadSource.RomAddress rom) {
        return rom.addr();
    }
    throw new IllegalStateException("LoadOp has a non-ROM source: " + source);
}
```

- [ ] **Step 5:** Run the new test AND the package's existing tests (`mvn "-Dtest=com.openggf.level.resources.*" test`, plus `LevelResourceOverlayTest` wherever it lives — find it by name) — all PASS; then `mvn package` to prove the ~30 factory call sites compile untouched.
- [ ] **Step 6: Commit** (`feat: LoadSource abstraction behind LoadOp factory statics`; stage the two main files + test; this plan's trailers).

---

### Task B2: `ResourceLoader` source dispatch

**Files:**
- Modify: `src/main/java/com/openggf/level/resources/ResourceLoader.java`
- Test: `src/test/java/com/openggf/level/resources/TestResourceLoaderModSources.java`

**Interfaces:**
- Consumes: `LoadSource`/`LoadOp` (B1); jar-writing test helper idiom from `TestModRepositoryScanner.writeJar` (Phase 1 plan Task 2 — if Phase 1 hasn't landed yet, inline the same ~10-line `JarOutputStream` helper in this test).
- Produces: `decompress(LoadOp)` dispatches on source: `RomAddress` → exactly today's switch; `ModAsset` → read the jar entry fully (`UNCOMPRESSED` only; any other compression type on a mod source → `IllegalArgumentException` at load with the entry path in the message). **Logging must become source-aware:** `loadWithOverlays` currently formats every op with `op.romAddr()` in its `LOG.fine(String.format(...))` calls (~ResourceLoader.java:86-93) and the format arguments evaluate eagerly — with B1's `romAddr()` throwing for mod sources, those log lines would crash mod loads before `decompress` even runs. Replace every `op.romAddr()` use in ResourceLoader logging with a `private static String describeSource(LoadOp op)` helper (ROM → `"ROM 0x%06X"`, mod → `jar!entry`). Otherwise `loadWithOverlays`/`loadWithOverlaysAligned`/`loadSingle` are unchanged above the dispatch.

- [ ] **Step 1: Read `ResourceLoader.java` fully** (recon: `decompress` switch, `UNCOMPRESSED` throws, all reads via `Rom`).
- [ ] **Step 2: Write the failing test**

```java
package com.openggf.level.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TestResourceLoaderModSources {

    static void writeJar(Path jar, Map<String, byte[]> entries) throws Exception {
        try (OutputStream fileOut = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(fileOut)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }

    @Test
    void modAssetOpsComposeWithOverlaysWithoutRom(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("m.jar");
        writeJar(jar, Map.of(
                "base.bin", new byte[]{1, 1, 1, 1},
                "overlay.bin", new byte[]{9, 9}));
        ResourceLoader loader = new ResourceLoader(null); // no ROM needed for mod sources
        byte[] composed = loader.loadWithOverlays(List.of(
                LoadOp.modAssetBase(jar, "base.bin"),
                LoadOp.modAssetOverlay(jar, "overlay.bin", 1)), 4);
        assertArrayEquals(new byte[]{1, 9, 9, 1}, composed);
    }

    @Test
    void modAssetAppendGrowsBuffer(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("m.jar");
        writeJar(jar, Map.of("a.bin", new byte[]{1, 2}, "b.bin", new byte[]{3, 4}));
        byte[] composed = new ResourceLoader(null).loadWithOverlays(List.of(
                LoadOp.modAssetBase(jar, "a.bin"),
                LoadOp.modAssetAppend(jar, "b.bin")), 2);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, composed);
    }

    @Test
    void missingEntryAndCompressedModSourceFailClearly(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("m.jar");
        writeJar(jar, Map.of("a.bin", new byte[]{1}));
        ResourceLoader loader = new ResourceLoader(null);
        assertThrows(Exception.class, () -> loader.loadSingle(LoadOp.modAssetBase(jar, "nope.bin")));
        LoadOp bad = new LoadOp(new LoadSource.ModAsset(jar, "a.bin"),
                CompressionType.KOSINSKI, 0);
        assertThrows(IllegalArgumentException.class, () -> loader.loadSingle(bad));
    }
}
```

Note: if `ResourceLoader`'s constructor rejects a null `Rom` (read it in Step 1), add an overload/factory `ResourceLoader.forModAssetsOnly()` or make the `Rom` nullable with a null-check only on the ROM branch — pick whichever the file's style supports; the test's intent (mod ops need no ROM) is the contract.

- [ ] **Step 3:** Run — COMPILE FAILURE / test failure.
- [ ] **Step 4: Implement.** In `decompress(LoadOp)`:

```java
// Plus: replace op.romAddr() in the LOG.fine(...) format calls with
// describeSource(op) AND switch those format specifiers from 0x%06X to %s.
private static String describeSource(LoadOp op) {
    if (op.source() instanceof LoadSource.RomAddress rom) {
        return String.format("ROM 0x%06X", rom.addr());
    }
    LoadSource.ModAsset asset = (LoadSource.ModAsset) op.source();
    return asset.jar().getFileName() + "!" + asset.entryPath();
}

private byte[] decompress(LoadOp op) throws IOException {
    if (op.source() instanceof LoadSource.ModAsset modAsset) {
        if (op.compressionType() != CompressionType.UNCOMPRESSED) {
            throw new IllegalArgumentException("mod asset " + modAsset.entryPath()
                    + " must be UNCOMPRESSED, was " + op.compressionType());
        }
        return readModAsset(modAsset);
    }
    int romAddr = op.romAddr();
    // ... existing switch, verbatim, using romAddr ...
}

private byte[] readModAsset(LoadSource.ModAsset asset) throws IOException {
    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(asset.jar().toFile())) {
        java.util.jar.JarEntry entry = jar.getJarEntry(asset.entryPath());
        if (entry == null) {
            throw new IOException("missing mod asset entry " + asset.entryPath()
                    + " in " + asset.jar());
        }
        try (java.io.InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }
}
```

- [ ] **Step 5:** New tests PASS; then the regression gate: `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test` (S3K is the heaviest plan consumer) and the existing resources-package tests — all green. **Then the spec's S2 HTZ gate** (HTZ is the only S2 zone on the plan seam): find the HTZ trace-replay class(es) (`grep -ril htz src/test/java --include="*TraceReplay*.java"`) and run them under the trace profile per the trace-replay skill's invocation; expected: same pass/error state as the `develop` baseline at branch point (record in `docs/status/trace-frontier-log.md` per repo policy).
- [ ] **Step 6: Commit** (`feat: ResourceLoader reads mod-jar assets beside ROM sources`; this plan's trailers).

---

## Workstream C — Editor completion

### Task C1: Trace-mode editor-entry refusal (new production code)

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java` (`toggleEditorPlaytestMode()`, ~line 1156) and/or `src/main/java/com/openggf/GameLoop.java` (editor toggle branch, ~867–878 — put the check at whichever single choke point both paths flow through; read both first)
- Test: `src/test/java/com/openggf/editor/TestEditorTraceModeGuard.java`

- [ ] **Step 1:** Read both toggle paths. The refusal: when `TraceSessionLauncher.active() != null`, decline editor entry with a log line, mirroring the adjacent trace gates in `GameLoop.stepInternal` (~826/833/891).
- [ ] **Step 2:** Write the failing test against a **pure decision helper** — extract `static boolean editorEntryAllowed(boolean editorEnabled, boolean traceActive)` (or equivalent) so the rule is testable without booting Engine or touching `TraceSessionLauncher` state: false whenever `traceActive`, else `editorEnabled`. The production toggle path passes `TraceSessionLauncher.active() != null` as the second argument. (If an integration-level assertion is wanted too, the existing precedent for manipulating the launcher is reflection on its private static `activeSession` field — see `TestGameLoopSpecialStageSkipGate` ~line 117; do NOT add a package-visible seam, the test lives in a different package.)
- [ ] **Step 3:** Implement the pure decision helper + wire it into the toggle path(s); run the test — PASS.
- [ ] **Step 4:** Commit (`fix: refuse editor entry during trace sessions`; this plan's trailers, `Changelog: n/a: covered by final phase-0 changelog entry in this branch`).

---

### Task C2: Object/ring spawn editing commands

**Files:**
- Create: `src/main/java/com/openggf/editor/commands/PlaceObjectSpawnCommand.java`, `MoveObjectSpawnCommand.java`, `DeleteObjectSpawnCommand.java`, `PlaceRingSpawnCommand.java`, `DeleteRingSpawnCommand.java`
- Create: `src/main/java/com/openggf/editor/EditorSpawnFactory.java`
- Modify: `src/main/java/com/openggf/level/MutableLevel.java` (one new method — sorted insert)
- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`, `src/main/java/com/openggf/editor/EditorInputHandler.java`, `src/main/java/com/openggf/editor/EditorFocusRegion.java` (or a new spawn-edit sub-mode enum — follow the file's structure)
- Test: `src/test/java/com/openggf/editor/TestEditorSpawnCommands.java`

**Interfaces:**
- Consumes: `MutableLevel.addObjectSpawn/removeObjectSpawn/moveObjectSpawn/addRingSpawn/removeRingSpawn` (exist, zero callers — recon), `ObjectSpawn` record (`x, y, objectId, subtype, renderFlags, respawnTracked, rawYWord, layoutIndex`), `EditorCommand` interface, `EditorHistory`.
- Produces: five undoable commands (each `apply()` = the mutation, `undo()` = the inverse mutation); `EditorSpawnFactory.createObjectSpawn(x, y, objectId, subtype, renderFlags, respawnTracked)` centralizing `rawYWord`/`layoutIndex` construction; **`MutableLevel.insertObjectSpawnSorted(ObjectSpawn spawn)`** — new method beside `addObjectSpawn` (which appends) that inserts at the first index whose `x` exceeds the spawn's, preserving the X-sorted order ROM layouts have and the placement windowing assumes; it raises `objectsDirty` like its siblings. **Every re-add path routes through the sorted insert:** `PlaceObjectSpawnCommand.apply()` AND `DeleteObjectSpawnCommand.undo()` (an appending undo would break the order the place path establishes); `MoveObjectSpawnCommand` re-inserts sorted when x changed. Ring commands mirror this if ring windowing shares the X-sorted assumption (Step 1 confirms against `RingManager`'s resync path; if it does, add `insertRingSpawnSorted` likewise). Controller verbs `placeObjectSpawnAtCursor(objectId, subtype)`, `deleteSpawnAtCursor()`, `moveSelectedSpawn(dx, dy)`, `eyedropSpawnAtCursor()` (copies id/subtype into the "current spawn brush"); input bindings in a spawn-edit sub-mode (entered via the TAB focus cycle, keys following the existing hardcoded style: SPACE place, DELETE remove, E eyedrop, arrows move selection).

- [ ] **Step 1: Read first:** `ObjectSpawn` (how `rawYWord` encodes y+respawnTracked and what consumes `layoutIndex`), one ROM placement loader (`Sonic2ObjectPlacement.load`) to mirror field construction, `MutableLevel`'s five spawn methods (note: `addObjectSpawn` APPENDS — the sorted insert is the new method this task adds), `PlaceBlockCommand` as the command template, and `ObjectManager.resyncSpawnList` (confirm the X-sorted assumption; if `layoutIndex` turns out to be display-only, document that in the factory Javadoc).
- [ ] **Step 2: Write the failing test** — command-level, no GL:

```java
package com.openggf.editor;

import com.openggf.editor.commands.DeleteObjectSpawnCommand;
import com.openggf.editor.commands.MoveObjectSpawnCommand;
import com.openggf.editor.commands.PlaceObjectSpawnCommand;
import com.openggf.level.MutableLevel;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestEditorSpawnCommands {

    // Build a minimal MutableLevel the way TestEditorCommands does (read that
    // test first and reuse its fixture helper for a small in-memory level).

    @Test
    void placeAppliesAndUndoRemoves() {
        MutableLevel level = fixtureLevel();
        int before = level.getObjects().size();
        ObjectSpawn spawn = EditorSpawnFactory.createObjectSpawn(512, 256, 0x26, 0, 0, true);
        PlaceObjectSpawnCommand cmd = new PlaceObjectSpawnCommand(level, spawn);
        cmd.apply();
        assertEquals(before + 1, level.getObjects().size());
        assertTrue(level.consumeObjectsDirty());
        cmd.undo();
        assertEquals(before, level.getObjects().size());
    }

    @Test
    void moveIsUndoableAndPreservesIdentityFields() {
        MutableLevel level = fixtureLevel();
        ObjectSpawn spawn = EditorSpawnFactory.createObjectSpawn(512, 256, 0x26, 3, 0, true);
        new PlaceObjectSpawnCommand(level, spawn).apply();
        MoveObjectSpawnCommand move = new MoveObjectSpawnCommand(level, spawn, 528, 256);
        move.apply();
        assertTrue(level.getObjects().stream()
                .anyMatch(s -> s.x() == 528 && s.objectId() == 0x26 && s.subtype() == 3));
        move.undo();
        assertTrue(level.getObjects().stream().anyMatch(s -> s.x() == 512));
    }

    @Test
    void deleteIsUndoable() {
        MutableLevel level = fixtureLevel();
        ObjectSpawn spawn = EditorSpawnFactory.createObjectSpawn(512, 256, 0x26, 0, 0, true);
        new PlaceObjectSpawnCommand(level, spawn).apply();
        DeleteObjectSpawnCommand del = new DeleteObjectSpawnCommand(level, spawn);
        del.apply();
        assertFalse(level.getObjects().contains(spawn));
        del.undo();
        assertTrue(level.getObjects().contains(spawn));
    }

    @Test
    void placeCommandInsertsPreservingXOrder() {
        MutableLevel level = fixtureLevel();
        new PlaceObjectSpawnCommand(level,
                EditorSpawnFactory.createObjectSpawn(800, 0, 1, 0, 0, false)).apply();
        new PlaceObjectSpawnCommand(level,
                EditorSpawnFactory.createObjectSpawn(400, 0, 2, 0, 0, false)).apply();
        // PlaceObjectSpawnCommand routes through the new
        // MutableLevel.insertObjectSpawnSorted, not the appending addObjectSpawn.
        var xs = level.getObjects().stream().map(ObjectSpawn::x).toList();
        assertEquals(xs.stream().sorted().toList(), xs);
    }

    private static MutableLevel fixtureLevel() { /* per TestEditorCommands' fixture */ }
}
```

(Adapt accessor names — `ObjectSpawn.x()` etc. — to the real record read in Step 1; `consumeObjectsDirty` name per recon. Ring commands get two analogous tests.)

- [ ] **Step 3:** COMPILE FAILURE, then implement commands (each ~15 lines, `PlaceBlockCommand` shape: hold the level + the spawn (+ old/new position for move), `apply`/`undo` call the `MutableLevel` methods), the factory, controller verbs, and input wiring per the read-first findings.
- [ ] **Step 4:** Tests PASS; `mvn "-Dtest=com.openggf.editor.*" test` for the existing editor suite — no regressions.
- [ ] **Step 5: Commit** (`feat: editor object and ring spawn placement commands`; this plan's trailers).

---

### Task C3: Spawn overlay rendering

**Files:**
- Modify: `src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java` (+ `EditorOverlayRenderer` orchestration if needed)

- [ ] **Step 1:** Read `EditorWorldOverlayRenderer` + `EditorTextRenderer`. Add a spawn-markers pass: for each `level.getObjects()` / `getRings()` entry in the visible region, draw a marker + `%02X:%02X`-formatted id/subtype text at world coords (rings: a simple marker). Toggle rides the spawn-edit sub-mode (markers always on while in it; off otherwise).
- [ ] **Step 2:** Rendering is smoke-covered only: extend `TestEditorRenderingSmoke` (read it first) with the sub-mode active so the new pass executes headless without throwing.
- [ ] **Step 3:** Commit (`feat: editor spawn overlay markers`; this plan's trailers).

---

### Task C4: Collision editing commands

**Files:**
- Create: `src/main/java/com/openggf/editor/commands/CycleCellCollisionModeCommand.java`, `SetChunkSolidTileIndexCommand.java`
- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`, `EditorInputHandler.java` (one new key in the existing style, e.g. `C` = cycle collision mode at BLOCK depth / reassign at CHUNK depth), plus a collision-overlay toggle key reusing `LevelDebugRenderer`'s collision drawing against the editor camera (read how `EditorOverlayRenderer` composes passes first)
- Test: `src/test/java/com/openggf/editor/TestEditorCollisionCommands.java`

**Interfaces:**
- Consumes: `ChunkDesc` (`0x3000` primary / `0xC000` secondary collision-mode masks, `set(int)`, `getPrimary/SecondaryCollisionMode()`), `Chunk.setSolidTileIndex/setSolidTileAltIndex` (+ getters), both already persisted via `Block.saveState()`/`Chunk.saveState()` — no schema change.
- Produces: `CycleCellCollisionModeCommand(block, cellIndex, whichLayer)` — cycles the 2-bit mode field (00→01→10→11→00) on the cell's `ChunkDesc`, undo restores the prior raw value; `SetChunkSolidTileIndexCommand(chunk, primary|alt, newIndex)` — undo restores the old index.

- [ ] **Step 1:** Read `ChunkDesc`, `Chunk`, and how `DeriveBlockFromChunksCommand` addresses block cells (reuse its addressing). Confirm which layer bit (`0x3000` vs `0xC000`) maps to which collision path (primary bits 0x0C/0x0D vs secondary 0x0E/0x0F per the dual-path model) and document in the command Javadoc.
- [ ] **Step 2:** Failing tests: cycle command walks all four modes and returns to start across 4 applies; undo restores the exact raw desc value (including flip bits untouched); solid-tile command round-trips. (Same fixture style as C2.)
- [ ] **Step 3:** Implement + wire input + overlay toggle; tests PASS; editor suite green.
- [ ] **Step 4:** Commit (`feat: editor collision mode and solid-tile-index editing`; this plan's trailers).

---

### Task C5: Save payload v2 (spawns) + reader compat + toolbar warning

**Files:**
- Modify: `src/main/java/com/openggf/editor/persistence/EditorSavePayload.java`, `EditorSaveEnvelope.java` (if version lives there), `EditorSaveManager.java`, `src/main/java/com/openggf/editor/render/EditorToolbarRenderer.java`
- Test: extend `src/test/java/com/openggf/editor/persistence/TestEditorSaveManager.java`

**Interfaces:**
- Produces: payload gains `List<ObjectSpawnState> objectSpawns` + `List<RingSpawnState> ringSpawns` where `record ObjectSpawnState(int x, int y, int objectId, int subtype, int renderFlags, boolean respawnTracked)` (rawYWord/layoutIndex re-derived by `EditorSpawnFactory` on apply — do not persist derived fields) and `record RingSpawnState(int x, int y)` (adapt to the real ring-spawn shape read first). `VERSION = 2`. **Write policy (spec §C.3): full current spawn tables written unconditionally on every v2 save.** **Read policy:** v1 accepted (no spawn fields → spawn tables untouched), v2 replaces both lists then raises `objectsDirty`/`ringsDirty` so `ObjectManager.resyncSpawnList` fires next frame; version > 2 → quarantine. Toolbar: when the current game's `supportsRuntimeEditApply` is false and unsaved-or-saved edits exist, append a warning segment to the existing status line ("S3K: edits saved but not re-applied").

- [ ] **Step 1:** Read `EditorSavePayload`/`EditorSaveManager.buildPayload`/`applyPayload` + the ring-spawn type. Write failing tests: v2 round-trip including spawns; v1 file (hand-built JSON fixture) still applies with spawns untouched; v3 file quarantines; apply raises the dirty flags (assert via `consumeObjectsDirty()`).
- [ ] **Step 2:** Implement; tests PASS; run the full editor + persistence suites.
- [ ] **Step 3:** Commit (`feat: editor save payload v2 with spawn tables`; this plan's trailers).

---

### Task C6: Authoring smoke + docs + changelog

- [ ] **Step 1: Manual smoke (requires ROM + display):** in EHZ with `debug.flags.editor` on — place a badnik and a ring, move both, delete one, toggle a cell's collision mode, reassign a solid-tile index, Ctrl+S, exit to gameplay (badnik spawns, solidity blocks the player), re-enter editor (edits present), restart engine (edits re-applied from sidecar). Record results in the commit body.
- [ ] **Step 2:** Update `CHANGELOG.md` (one phase-0 entry covering A+B+C), `CLAUDE.md` + `AGENTS.md` (GamePatch framework + editor capabilities pointers), `docs/status/known-discrepancies.md` only if the smoke surfaced an accepted divergence.
- [ ] **Step 3:** Full `mvn test` + S3K must-keep-green + trace spot sweep; update `docs/status/trace-frontier-log.md` for the sweep.
- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md CLAUDE.md AGENTS.md docs/status/known-discrepancies.md
git commit -m "docs: phase-0 foundations changelog and agent-doc updates

Changelog: updated
Guide: n/a
Known-Discrepancies: updated
S3K-Known-Discrepancies: n/a
Agent-Docs: updated
Configuration-Docs: n/a
Skills: n/a"
```

(Drop `Known-Discrepancies: updated` to `n/a` if Step 2 didn't touch it.)

---

## Execution notes

- A1/A3/A5/A6 are thin wrappers over KiS2-plan tasks — the executor's first action in each is opening that plan. A4 REPLACES its Task 4; do not execute both.
- The KiS2 content tasks (1, 5–6, 10–12) + its Task 13/14 verification are the natural follow-on branch after this plan merges; they validate the framework with a real patch.
- Merge flow: `superpowers:finishing-a-development-branch`; merging to `develop` needs the README release-log note.
