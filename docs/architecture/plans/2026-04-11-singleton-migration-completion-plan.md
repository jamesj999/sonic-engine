# Singleton Migration Completion Plan

> **Owner:** current Codex session
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close the remaining singleton-migration gaps identified in review item 1 and item 3 by removing production `RuntimeManager.getEngineServices()` service-locator usage, eliminating shared-object `GameServices` access where `ObjectServices` should be used, and updating the stale singleton-lifecycle documentation so the codebase and docs describe the same architecture.

**Architecture:** production code should access process/runtime services through explicit constructor wiring, `GameServices`, or injected `ObjectServices` depending on layer. Shared object instances in `com.openggf.level.objects` must not reach out to static facades during gameplay logic. Guard tests should scan the actual remaining risk surface instead of narrow allowlists.

**Tech Stack:** Java 21, Maven, JUnit 5 / Jupiter only, OpenGGF `EngineServices` / `GameServices` / `ObjectServices` architecture.

---

## Current Audit

Worktree: `C:\Users\farre\IdeaProjects\sonic-engine\.worktrees\next-singleton-di`

Branch: `next`

Current known production locator surface:

```text
20 RuntimeManager.getEngineServices(...) call sites in src/main/java/com/openggf
```

Known concrete violations from review:

- `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- `src/main/java/com/openggf/debug/DebugRenderer.java`
- `src/main/java/com/openggf/debug/DebugObjectArtViewer.java`
- `src/main/java/com/openggf/debug/DebugOverlayManager.java`
- `src/main/java/com/openggf/level/objects/InvincibilityStarsObjectInstance.java`
- `docs/architecture/singleton-lifecycle.md`

Current guard gap:

- `TestProductionSingletonClosureGuard` passes, but it only scans selected packages and misses the debug/audio/sprites surface.
- `TestObjectServicesMigrationGuard` scans game-specific object packages but not shared `com/openggf/level/objects`, so shared objects can regress silently.

Focused verification bundle for this plan:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.TestEngineServices,com.openggf.game.TestGameRuntime" test
```

---

## Task 1: Expand the Guards to Match the Real Risk Surface

**Files:**
- Modify: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`

- [ ] **Step 1: add package/file coverage for the known remaining production surface**

Extend `TestProductionSingletonClosureGuard` so it scans at least:

- `com/openggf/audio/`
- `com/openggf/audio/smps/`
- `com/openggf/debug/`
- `com/openggf/sprites/managers/`

The scan should fail on `RuntimeManager.getEngineServices(` in production code, with a tightly documented allowlist only if a composition-root file still needs a temporary bridge.

- [ ] **Step 2: extend the object guard to the shared object package**

Update `TestObjectServicesMigrationGuard` to scan:

- `com/openggf/level/objects/`

The shared-package scan should fail on direct `GameServices.` usage inside object instance code where `services()` is available. Keep any allowlist minimal and source-commented.

- [ ] **Step 3: run the guard bundle and confirm RED before implementation**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

Expected: FAIL on the reviewed production and shared-object violations.

---

## Task 2: Remove the Remaining Production Engine-Services Locator Uses

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/debug/DebugRenderer.java`
- Modify: `src/main/java/com/openggf/debug/DebugObjectArtViewer.java`
- Modify: `src/main/java/com/openggf/debug/DebugOverlayManager.java`

- [ ] **Step 1: classify each usage by the dependency it actually needs**

Replace service-locator calls with the narrow dependency actually consumed:

- configuration access -> `GameServices.configuration()` or constructor-injected `SonicConfigurationService`
- profiler access -> constructor-injected `PerformanceProfiler` or `EngineServices` supplied at composition boundary
- playback debug access -> constructor-injected `PlaybackDebugManager`
- audio backend restore path -> injected `AudioManager` / `AudioBackend`

Avoid replacing one static escape hatch with another broad locator.

- [ ] **Step 2: refactor constructors or fields so these classes own explicit dependencies**

Preferred shapes:

```java
public LWJGLAudioBackend(SonicConfigurationService configuration) { ... }
public DebugRenderer(SonicConfigurationService configuration,
                     DebugOverlayManager overlayManager,
                     PlaybackDebugManager playbackDebugManager,
                     PerformanceProfiler profiler) { ... }
```

Retain convenience constructors only where they delegate to an already-approved facade rather than `RuntimeManager.getEngineServices()`.

- [ ] **Step 3: verify there are no remaining production locator calls in the covered surface**

```powershell
rg -n "RuntimeManager\\.getEngineServices\\(" src/main/java/com/openggf/audio src/main/java/com/openggf/debug src/main/java/com/openggf/sprites/managers
```

Expected: no output.

---

## Task 3: Finish the Shared Object Migration

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/InvincibilityStarsObjectInstance.java`
- Verify: any additional files flagged by the new shared-object guard

- [ ] **Step 1: replace shared-object `GameServices` access with injected services**

`InvincibilityStarsObjectInstance` currently derives `sonic1TrailMode` via `GameServices.module()`. Refactor it to use `services().zoneFeatureProvider()` or another injected capability instead of a static gameplay facade.

The result should preserve constructor safety:

- no `services()` access before injection is valid
- no new singleton/static access added to shared objects

- [ ] **Step 2: sweep any other shared-object violations found by the new guard**

Use the guard output as the source of truth. Keep fixes local to the object layer; do not weaken the guard to make the scan pass.

- [ ] **Step 3: run the object migration guard**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

Expected: PASS.

---

## Task 4: Rewrite the Singleton Lifecycle Documentation

**Files:**
- Modify: `docs/architecture/singleton-lifecycle.md`

- [ ] **Step 1: update the document from inventory-of-singletons to lifecycle-of-services**

The current doc still describes `SpriteManager.getInstance()`, `Camera.getInstance()`, and `LevelManager.getInstance()` as architectural norms. Replace that with the actual architecture:

- process-level services assembled at the engine composition root
- runtime-owned gameplay state inside `GameRuntime`
- static facade access through `GameServices` for non-object code
- per-object access through injected `ObjectServices`
- test reset guidance aligned with current runtime/session lifecycle

- [ ] **Step 2: preserve any still-useful lifecycle and test-reset content, but rewrite examples**

Examples should prefer:

```java
GameServices.camera()
GameServices.level()
services().audioManager()
services().objectManager()
```

Remove or clearly label obsolete singleton-era instructions.

---

## Task 5: Final Verification

- [ ] **Step 1: rerun the focused migration bundle**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.TestEngineServices,com.openggf.game.TestGameRuntime" test
```

Expected: PASS.

- [ ] **Step 2: perform source scans**

```powershell
rg -n "RuntimeManager\\.getEngineServices\\(" src/main/java/com/openggf
rg -n "GameServices\\." src/main/java/com/openggf/level/objects
```

Expected:

- first scan has no output, or only explicitly documented composition-root leftovers if any remain temporarily
- second scan has no shared-object-instance violations

- [ ] **Step 3: check branch diff hygiene**

```powershell
git -C .worktrees/next-singleton-di diff --check
git -C .worktrees/next-singleton-di status --short
```

Expected: no whitespace issues; only intended files modified.

---

## Notes for Execution

- Do not treat current guard pass results as evidence that migration is complete; the first task is to make the guards honest.
- Favor constructor injection or narrow facades over introducing new static helper methods.
- If a remaining `RuntimeManager.getEngineServices()` call turns out to be a true composition-root concern, document that explicitly in the guard allowlist and in the code comment rather than leaving it implicit.
