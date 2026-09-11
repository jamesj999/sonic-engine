# Runtime Session Migration Completion Blueprint

**Date:** 2026-05-10
**Status:** Runtime/session migration complete; Maven compile and targeted validation green
**Owner:** current Codex orchestration session

## Requirements

### Goals

- Finish the runtime/session migration so the codebase has one primary ownership model:
  - `EngineContext` owns process-wide services.
  - `WorldSession` owns durable loaded-world state.
  - `GameplayModeContext` and `EditorModeContext` own swappable mode state.
  - `SessionManager` owns world/mode lifecycle.
- Retain gameplay parity and editor round-trip behavior while reducing legacy singleton/bootstrap paths.
- Convert `GameRuntime` and `RuntimeManager` from active mental models into temporary adapters, then make them removable.
- Strengthen architecture guards so future code follows the intended ownership model.

### Non-Goals

- Do not implement new editor UI, tools, object editing, undo/redo, or persistence behavior.
- Do not change gameplay physics, object behavior, rendering parity, audio behavior, or trace semantics.
- Do not remove every process singleton in one pass; process-wide services may remain behind `EngineContext`.
- Do not refactor unrelated zone/gameplay systems except where required by ownership boundaries.

### Constraints

- Existing editor flow must preserve `WorldSession` and loaded `MutableLevel` data across editor entry/exit.
- Editor exit must rebuild fresh gameplay-scoped state unless explicitly using the existing playtest stash path.
- New runtime-owned access should prefer `GameplayModeContext` or `GameServices`; object code should prefer injected `ObjectServices`.
- Bootstrap-only globals are allowed only before an active session exists or at explicit composition roots.
- New or changed tests must use JUnit 5 / Jupiter.

### Acceptance Criteria

- Architecture guards reject direct gameplay use of `GameModuleRegistry.getCurrent()`, runtime-owned singleton `getInstance()` access, and broad service-locator usage outside documented composition roots.
- `SessionManager` owns gameplay open/close/editor mode transition semantics end to end.
- Runtime manager graph construction no longer has to be understood as a `RuntimeManager` responsibility by production callers.
- `GameRuntime` is either removed or reduced to a deprecated compatibility adapter with no new production call sites.
- Existing editor lifecycle, runtime registry lifecycle, and game runtime tests remain green.
- Documentation describes the current architecture without stale `parkCurrent` / `resumeParked` language.

### Assumptions

- The current code has already completed the first major migration: `GameplayModeContext` owns managers, and `GameRuntime` delegates to it.
- `RuntimeManager.currentEngineServices()` remains necessary temporarily for engine globals, but should be narrowed by guard policy.
- `GameLoop`, editor integration, trace launchers, and object service construction are likely the highest-risk facade consumers.

### Risks

- Editor teardown can regress if `SessionManager` and runtime teardown are separated incorrectly.
- Moving composition too early without guards can preserve the two-model problem under new names.
- Deleting `GameRuntime` before `GameLoop` and trace/bootstrap callers are migrated creates broad churn.
- Scanner-based guards can miss aliasing or helper indirection; they are a first line of defense, not proof.

## Exploration Synthesis

### Prior Plans

The April runtime ownership design chose a four-layer target: `EngineContext`, `WorldSession`, `ModeContext`, and `SessionManager`. It explicitly allows bootstrap-only globals before session creation and requires gameplay systems to be owned by explicit session/mode objects after a session exists.

The April implementation plan introduced a bridge model: `GameplayModeContext` becomes the real owner, while `GameRuntime` stays as a temporary facade. Later singleton-removal plans identify `RuntimeManager.getEngineServices()` / `currentEngineServices()` as the remaining process service-locator issue.

### Current Code Evidence

- `GameRuntime` is already a facade over `EngineContext`, `WorldSession`, and `GameplayModeContext`; gameplay manager getters delegate to `GameplayModeContext`.
- `RuntimeManager` still stores the current runtime and configured `EngineContext`, and still constructs the gameplay manager graph in `createGameplay(...)`.
- `GameServices` gameplay accessors already resolve through `SessionManager.getCurrentGameplayMode()`.
- `SessionManager` owns current world/gameplay/editor references, but mode destruction does not itself tear down gameplay managers; teardown still flows through `RuntimeManager.destroyCurrent()` -> `GameRuntime.destroy()` -> `GameplayModeContext.tearDownManagers()`.
- Editor entry/resume is already teardown/rebuild, not runtime parking, but stale comments still mention parking.
- Existing guard coverage is strong but uneven; `GameModuleRegistry.getCurrent()` policy appears to have at least one remaining production violation.

### Recommendation

Proceed in three phases:

1. Guard-first cleanup.
2. Move gameplay composition and teardown into session ownership.
3. Retire facades after callers are migrated.

This sequencing reduces regression risk and aligns with the documented end state.

## Architecture Decision

### Decision

Conditional approval: the direction is sound, but facade retirement is blocked until lifecycle ownership is made explicit. `SessionManager` becomes the authoritative lifecycle boundary for session and mode state. A session-owned composition path creates and attaches gameplay managers to `GameplayModeContext`. `RuntimeManager` becomes a compatibility adapter during migration, not the architectural owner.

The migration must not delete `GameRuntime` or replace `RuntimeManager.getCurrent()` mechanically until the following contracts are implemented and tested:

- Gameplay composition is owned by a session-owned factory or `SessionManager`, not by `RuntimeManager.createGameplay(...)`.
- Gameplay teardown is owned by the same session lifecycle path that owns mode transitions.
- World-preserving teardown no longer depends on ad hoc snapshot/restore of `WorldSession` fields.
- Engine-global service access has a replacement root before `RuntimeManager.currentEngineServices()` is retired.
- Active-runtime semantics used by trace, rewind, save, bonus stage, and editor paths are replaced with explicit session/gameplay state contracts.

### Ownership

- `EngineContext`: configuration, graphics, audio, ROM manager, profiler, debug overlay, playback debug, ROM detection, cross-game features.
- `WorldSession`: active game module, save session, current level, zone/act/apparent act, future durable editor-visible world state.
- `GameplayModeContext`: camera, timers, game state, fade, RNG, solid execution, water, parallax, terrain/collision, sprites, level manager, runtime-shared registries, rewind controllers, active bonus stage provider.
- `EditorModeContext`: cursor and editor/playtest handoff data.
- `SessionManager`: active world/mode lifecycle and mode transitions.

### Data Flow

Bootstrap creates/configures `EngineContext`. Opening gameplay creates a `WorldSession`, creates a `GameplayModeContext`, attaches gameplay managers, and publishes the active mode. Non-object gameplay code reads through `GameServices` or direct `GameplayModeContext`. Object instances receive `ObjectServices`.

### Lifecycle Contracts

Editor entry ordering must become contractual:

1. Prepare or snapshot the mutable editor-visible level.
2. Preserve world-owned state and camera bounds through explicit world/session fields.
3. Tear down gameplay managers exactly once.
4. Enter `EditorModeContext`.
5. Restore editor level view from `WorldSession`.

Editor exit ordering must become contractual:

1. Read cursor and playtest stash from `EditorModeContext`.
2. Destroy editor mode state.
3. Create a fresh `GameplayModeContext` against the existing `WorldSession`.
4. Attach fresh gameplay managers and shared registries.
5. Restore inherited level into `LevelManager`.
6. Reinitialize fresh gameplay counters.
7. Apply playtest stash only after fresh runtime state exists.

Session close/open semantics:

- Opening a new gameplay session over an existing gameplay mode must tear down the old managers exactly once before replacing world/mode state.
- Clearing a session must tear down active gameplay managers, destroy editor state if present, and then clear world state.
- `GameplayModeContext.destroy()` must either be the teardown method or delegate to the teardown method once the editor transition no longer requires a special empty destroy path.

### Migration

- Phase 1 blocks new legacy use and fixes known violations.
- Phase 2 moves composition and teardown ownership atomically into session-owned code.
- Phase 3 deletes or deprecates compatibility surfaces once callers stop depending on them.

### Rollback

Each phase should be independently revertible:

- Phase 1 changes guards and narrow call sites only.
- Phase 2 keeps `RuntimeManager` adapter methods while moving ownership.
- Phase 3 removes adapters only after replacement callers and tests are green.

## Feature Design

### Phase 1: Guard-First Cleanup

Behavior:

- `GameModuleRegistry.getCurrent()` is bootstrap-only, not gameplay flow.
- Stale runtime parking language is removed from comments/docs.
- Guard allowlists reflect current intended ownership.
- New production uses of `GameRuntime`, `RuntimeManager.getCurrent`, `RuntimeManager.getActiveRuntime`, and `GameServices.runtimeOrNull()` are blocked outside documented migration roots.

Tests:

- Extend `TestGameModuleRegistryUsageGuard`.
- Extend `TestRuntimeSingletonGuard` / `TestProductionSingletonClosureGuard` only where policy is clear.
- Keep `TestObjectServicesMigrationGuard` green.

### Phase 2: Session-Owned Composition And Teardown

Behavior:

- A new session-owned composition method creates the gameplay manager graph and attaches it to `GameplayModeContext`.
- `SessionManager` exposes an authoritative close/destroy path that tears down managers before clearing mode state.
- Editor transition code uses session lifecycle APIs, not manual runtime preservation shims where avoidable.
- `RuntimeManager.createGameplay(...)` delegates to the session-owned composition path while compatibility callers are migrated.

Tests:

- Extend `TestSessionManager` for teardown ownership.
- Extend editor integration tests around world preservation and gameplay reset.
- Keep runtime registry lifecycle tests green.

### Phase 3: Facade Retirement

Behavior:

- `GameLoop` consumes `GameplayModeContext` / `GameServices`, not `GameRuntime` manager getters.
- Runtime facade constructors and `DefaultObjectServices(GameRuntime)` paths are replaced by explicit mode/service inputs.
- `GameServices.runtimeOrNull()` becomes unnecessary or test-only.
- `GameRuntime` and `RuntimeManager.getCurrent()` are removed or deprecated with a narrow adapter window.
- Save, rewind, trace, bonus-stage, and data-select code no longer use `GameRuntime` as a lifecycle/token object.

Tests:

- Guard tests reject new facade use outside adapters.
- `TestGameRuntime` is reduced, rewritten, or retired when the facade disappears.
- Editor and trace replay smoke tests remain green.

## Implementation Plan

### Task A: Guard And Known-Violation Cleanup

Ownership:

- Guard tests under `src/test/java/com/openggf/game/`.
- Known production module access call sites.
- Stale comments in runtime/session/editor files.

Steps:

1. Run current guard tests to capture baseline.
2. Replace direct production `GameModuleRegistry.getCurrent()` with `GameServices.module()`, `SessionManager.requireCurrentGameModule()`, or explicit bootstrap module access as appropriate.
3. Add explicit guards for new production use of `GameRuntime`, `RuntimeManager.getCurrent`, `RuntimeManager.getActiveRuntime`, and `GameServices.runtimeOrNull()` outside documented migration roots.
4. Expand guard allowlists only for composition roots.
5. Remove stale `parkCurrent` / `resumeParked` references and update outdated `restoreInheritedLevel` comments.
6. Convert low-risk tests from `RuntimeManager.getCurrent().getGameplayModeContext()` to `SessionManager.getCurrentGameplayMode()` where they only need the mode context.

Verification:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

### Task B: Session-Owned Gameplay Composition

Ownership:

- `src/main/java/com/openggf/game/session/SessionManager.java`
- `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- New: `src/main/java/com/openggf/game/session/GameplaySessionFactory.java` or equivalent session-owned composition helper.
- New session-owned composition helper if needed.
- `src/main/java/com/openggf/game/RuntimeManager.java` adapter surface.
- Runtime/session tests.

Steps:

1. Add tests proving `SessionManager` can open gameplay with attached managers and close it with teardown.
2. Add a test proving opening a new gameplay session over an existing gameplay mode tears down old managers exactly once and creates fresh manager instances.
3. Extract manager graph creation from `RuntimeManager.createGameplay(...)` into a session-owned factory or `SessionManager` private helper.
4. Make `RuntimeManager.createGameplay(...)` delegate to the session-owned path without retaining ownership logic.
5. Add an authoritative `SessionManager.closeGameplaySession()` / equivalent teardown path.
6. Keep `GameplayModeContext.destroy()` behavior unchanged until editor-entry world-preservation tests prove teardown can move safely.
7. Update editor entry/resume to use session-owned lifecycle.

Verification:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.session.TestSessionManager,com.openggf.game.session.TestEditorModeContextLifecycle,com.openggf.editor.TestEditorToggleIntegration,com.openggf.game.TestRuntimeOwnedRegistryLifecycle,com.openggf.game.TestGameRuntime" test
```

### Task C: Split Gameplay Reset From World Reset

Ownership:

- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/game/session/SessionManager.java`
- Editor integration tests.

Steps:

1. Add a failing test around world fields surviving gameplay teardown without manual preservation.
2. Add or extend an editor round-trip test proving loaded `MutableLevel`, zone, act, apparent act, and camera bounds survive editor entry while gameplay counters, registries, mutation queues, render effects, and rewind controllers do not.
3. Split `LevelManager.resetState()` into explicit gameplay-only and full/session reset semantics if needed.
4. Replace or shrink `SessionManager.runRuntimeTeardownPreservingWorld(...)`.

Verification:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration,com.openggf.game.session.TestSessionManager" test
```

### Task D: Migrate High-Value Facade Callers

Ownership:

- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- `src/main/java/com/openggf/level/objects/BootstrapObjectServices.java`
- Trace/bootstrap helper call sites as surfaced by guards.
- `src/main/java/com/openggf/game/save/RuntimeSaveContext.java`
- `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Migrate `GameLoop` away from cached `GameRuntime` manager fields where practical.
2. Replace object-service runtime constructor paths with `GameplayModeContext` or explicit manager/service dependencies.
3. Replace save snapshot and trace/rewind install paths that use `GameRuntime` as a lifecycle token with explicit session/gameplay context shapes.
4. Add a `GameLoop` binding test proving clearing gameplay returns fade handling to the graphics-owned bootstrap fade manager.
5. Add guards preventing new `RuntimeManager.getCurrent().getX()` production calls outside adapters.

Verification:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.TestGameLoop,com.openggf.editor.TestEditorToggleIntegration,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.TestRuntimeSingletonGuard" test
```

### Task E: Retire Or Deprecate Facades

Ownership:

- `src/main/java/com/openggf/game/GameRuntime.java`
- `src/main/java/com/openggf/game/RuntimeManager.java`
- `src/main/java/com/openggf/game/GameServices.java`
- Tests that mention `GameRuntime`.

Steps:

1. Source-scan for remaining production `GameRuntime` and `RuntimeManager.getCurrent()` callers.
2. Migrate remaining callers to `SessionManager`, `GameplayModeContext`, `GameServices`, or explicit `EngineContext`.
3. Introduce an explicit engine-services root outside `RuntimeManager` before retiring `RuntimeManager.currentEngineServices()`.
4. Remove `GameServices.runtimeOrNull()` if no longer needed.
5. Delete `GameRuntime`, or mark it deprecated and enforce no new production call sites.
6. Fold remaining `RuntimeManager` responsibilities into `SessionManager` or reduce it to `EngineContext` adapter only.

Verification:

```powershell
mvn -q -Dmse=off -DskipTests compile
mvn -q -Dmse=off -DskipTests test-compile
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestRuntimeSingletonGuard,com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.editor.TestEditorToggleIntegration" test
```

## Integration Report

Planning orchestration and implementation are complete.

- Created this blueprint artifact.
- Delegated architecture critique and implementation-plan review.
- Integrated review feedback into lifecycle contracts, task split, facade-retirement blockers, and verification strategy.
- Removed stale `parkCurrent` / `resumeParked` lifecycle language from runtime/session comments.
- Moved gameplay manager graph construction into `GameplaySessionFactory`, with `RuntimeManager` reduced to a compatibility publisher for the temporary `GameRuntime` facade.
- Routed session gameplay close/mode replacement through `SessionManager` teardown ownership.
- Migrated low-risk production callers from runtime facade lookups to `SessionManager`, `GameplayModeContext`, or `GameServices.hasRuntime()`.
- Converted production `DefaultObjectServices` construction, save snapshot capture, Sonic 2 results-screen creation, and S3K slot bonus-stage runtime access to session-owned gameplay context paths.
- Tightened architecture guards so `GameServices.runtimeOrNull()` is now limited to `Engine`/`GameLoop`, and `GameModuleRegistry.getCurrent()` is limited to `RuntimeManager`.
- Next slice: removed all production callers of `GameServices.runtimeOrNull()`, marked it deprecated for removal, changed `GameLoop`/`Engine` to observe the active runtime facade only, and moved live/trace rewind installation to `GameplayModeContext`.
- Tightened runtime locator guards so destructive `RuntimeManager.getCurrent()` is now limited to `GameServices`/`RuntimeManager`; `getActiveRuntime()` remains allowed only in the compatibility shell (`Engine`, `GameLoop`, `TraceSessionLauncher`, `GameServices`, `RuntimeManager`).
- Current slice: introduced `EngineServices` as the process-wide `EngineContext` holder and migrated production engine-global callers off `RuntimeManager.currentEngineServices()` / `configureEngineServices()`.
- Current slice: moved `Engine` and `GameLoop` cached gameplay state from `GameRuntime` to `GameplayModeContext`; `RuntimeManager.createGameplay(...)` still publishes the temporary facade for compatibility, but it is no longer the local state handle for the main loop.
- Current slice: removed all production calls to destructive `RuntimeManager.getCurrent()`. `RuntimeManager.getActiveRuntime()` now remains only in `GameServices.runtimeOrNull()` and the live trace fixture compatibility bridge.
- Completion slice for facade retirement: changed `TraceReplayFixture` to expose `GameplayModeContext` instead of `GameRuntime`, removed the live trace fixture's active-runtime bridge, and deleted `GameServices.runtimeOrNull()` after migrating its remaining tests.
- Completion slice for facade retirement: production source now has zero direct calls to `RuntimeManager.getCurrent()`, `RuntimeManager.getActiveRuntime()`, and `GameServices.runtimeOrNull()` outside the compatibility adapter definitions themselves.
- Final orchestration slice: removed the remaining test helper dependency on the runtime facade, migrated save snapshot tests to `GameplayModeContext`, removed `GameLoop.setRuntime(GameRuntime)`, removed `DefaultObjectServices(GameRuntime)`, removed `RuntimeSaveContext(GameRuntime, ...)`, removed `GameplaySessionFactory.createRuntime(...)`, and deleted `GameRuntime.java` and `RuntimeManager.java`.
- Final orchestration slice: rewired the last production engine-services caller to `EngineServices`, removed stale production references to `RuntimeManager` / `GameRuntime`, and tightened guards so runtime facade references cannot return to production code.

## End-to-End Review

Implementation review status: green for the runtime/session migration scope. Maven is available through the local tool install and the source now compiles, test-compiles, and passes the targeted lifecycle, guard, object-service, editor, engine, and game-loop validation slices.

Known residual risks:

- The full Maven suite currently fails in unrelated rewind coverage (`TestRewindIter1631Diagnostic`, `TestRewindTransientGuard`, and `TestRewindTorture` divergences around rewind/object state). These failures are outside the runtime/session facade migration and should be handled under the rewind debugging workflow.
- `RuntimeManager` / `GameRuntime` names remain in test guard strings and historical plan text, but `src/main/java` has no remaining references and both facade source files are deleted.
- Some tests still carry method names such as `resetRuntime`; these are naming leftovers, not runtime-facade dependencies.

## Human Review Checklist

- Review the runtime/session migration diff and the targeted Maven validation results.
- Review whether historical docs outside this blueprint should be renamed from "runtime" terminology to "session" terminology.

## Completion Orchestration Roadmap

### Team Split

**Team A: Facade/Test Removal**

Ownership:

- `src/main/java/com/openggf/game/GameRuntime.java`
- `src/main/java/com/openggf/game/RuntimeManager.java`
- `src/main/java/com/openggf/game/session/GameplaySessionFactory.java`
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- `src/main/java/com/openggf/game/save/RuntimeSaveContext.java`
- Test harnesses: `src/test/java/com/openggf/tests/TestEnvironment.java`, `src/test/java/com/openggf/tests/HeadlessTestFixture.java`

Mission:

1. Replace `RuntimeManager.createGameplay(...)` in tests with a session test helper that opens a `WorldSession`, creates a `GameplayModeContext`, and attaches managers through `GameplaySessionFactory`.
2. Replace `RuntimeManager.getCurrent()` in tests with `SessionManager.getCurrentGameplayMode()` or direct helper return values.
3. Replace `new DefaultObjectServices(RuntimeManager.getCurrent())` with `new DefaultObjectServices(gameplayMode, EngineServices.current())`.
4. Replace `new RuntimeSaveContext(GameRuntime, ...)` with `RuntimeSaveContext.forGameplayMode(...)`.
5. Remove `GameLoop.setRuntime(GameRuntime)`, `DefaultObjectServices(GameRuntime)`, and `RuntimeSaveContext(GameRuntime, ...)`.
6. Delete `GameRuntime` only after production and tests no longer compile against it.

Key risk:

- Tests currently use `RuntimeManager.createGameplay()` as a one-call manager graph bootstrap. Introduce the replacement helper first, then migrate call sites by package.

Verification:

```powershell
rg -n "\bGameRuntime\b|RuntimeManager\.(getCurrent|getActiveRuntime|createGameplay|setCurrent)" src/main/java src/test/java
rg -n "DefaultObjectServices\(RuntimeManager\.getCurrent|RuntimeSaveContext\(.*GameRuntime" src/main/java src/test/java
mvn -q -Dmse=off "-Dtest=com.openggf.tests.HeadlessTestFixture,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.level.objects.TestObjectServicesExpansion,com.openggf.game.sonic3k.dataselect.TestS3kSaveSnapshotProvider" test
```

**Team B: Session Teardown Ownership**

Ownership:

- `src/main/java/com/openggf/game/session/SessionManager.java`
- `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/Engine.java`
- `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`
- `src/test/java/com/openggf/game/session/TestSessionManager.java`

Mission:

1. Add a focused test proving editor-mode entry preserves `WorldSession.currentLevel`, zone, act, and apparent act without `runRuntimeTeardownPreservingWorld(...)`.
2. Split `LevelManager.resetState()` into gameplay-only teardown and full world/session clearing. Runtime teardown must clear the local gameplay projection and submanagers without writing durable `WorldSession` fields to null/zero.
3. Make `GameplayModeContext.destroy()` call manager teardown directly and idempotently.
4. Make `SessionManager.destroyCurrentMode()` / `closeGameplaySession()` own gameplay teardown without calling `RuntimeManager.destroyCurrent()`.
5. Replace `Engine.enterEditorFromCurrentPlayer(...)` use of `runRuntimeTeardownPreservingWorld(...)` with direct session lifecycle teardown.
6. Remove or shrink `runRuntimeTeardownPreservingWorld(...)` after tests prove it is obsolete.

Key risk:

- `LevelManager.resetState()` currently writes through to `WorldSession`. Removing that side effect must not leave stale local mirrors or break full session clearing.

Verification:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration,com.openggf.game.session.TestSessionManager,com.openggf.game.TestRuntimeOwnedRegistryLifecycle,com.openggf.game.session.TestGameplayModeContextRewindRegistry,com.openggf.game.session.TestGameplayModeContextPlaybackController" test
```

**Team C: End-State Guards And Docs**

Ownership:

- `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`
- `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`
- `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`
- `AGENTS.md`
- `CLAUDE.md`
- `docs/architecture/singleton-lifecycle.md`
- `CHANGELOG.md`
- `README.md` if merging into `develop`

Mission:

1. Preparation slice: freeze the current production `GameRuntime` compatibility surface without blocking ongoing Team A/B migration work.
2. Preparation slice: block new `RuntimeManager.createGameplay(...)` production call sites outside the current engine adapter.
3. After Teams A/B remove compatibility, add guards forbidding production references to `GameRuntime`, `RuntimeManager`, `RuntimeManager.`, `new GameRuntime(`, and `DefaultObjectServices(GameRuntime`.
4. Add a deletion guard for removed files if `GameRuntime.java` / `RuntimeManager.java` are deleted.
5. Add or update session lifecycle tests that replace `TestGameRuntime` with session-named coverage.
6. Update architecture docs to state the final model: `EngineServices` owns process globals, `SessionManager` owns lifecycle, `WorldSession` owns durable state, and `GameplayModeContext` owns gameplay managers.
7. Update changelog and branch docs policy artifacts as required.

Key risk:

- Guard hardening before Teams A/B finish will block useful intermediate commits. Land final strict guards after compatibility APIs are removed or explicitly mark remaining allowlist entries as test-only.

Current guard-prep checklist:

- [x] Production `RuntimeManager.getCurrent(...)`, `RuntimeManager.getActiveRuntime()`, and `GameServices.runtimeOrNull()` call-site spread remains blocked by existing guards.
- [x] Production `RuntimeManager.createGameplay(...)` call sites have been removed; gameplay manager graph construction now goes through `GameplaySessionFactory.attachManagers(...)`.
- [x] Production `GameRuntime` references are now limited to the current compatibility surface.
- [x] `LevelManager.resetState()` has been split into full clearing and gameplay-only teardown, so editor/world state no longer needs preservation wrapping.
- [x] `GameplayModeContext.destroy()` now owns idempotent manager teardown, and `SessionManager` owns mode destruction.
- [x] `Engine.enterEditorFromCurrentPlayer(...)` no longer uses `runRuntimeTeardownPreservingWorld(...)`; the helper has been removed.
- [x] `GameRuntime.java` has been deleted.
- [x] `RuntimeManager.java` has been deleted.
- [x] `DefaultObjectServices(GameRuntime)`, `RuntimeSaveContext(GameRuntime, ...)`, and `GameLoop.setRuntime(GameRuntime)` have been removed.
- [x] Broad production `RuntimeManager.` references have been removed from `src/main/java`.

Current remaining work after orchestration:

- Investigate the unrelated rewind full-suite failures separately if full-suite green is required before merge.
- Optionally rename lingering test method names/comments that use "runtime" generically after Maven verification confirms the source changes.

Verification:

```powershell
$env:JAVA_HOME='C:\Users\farre\Tools\jdk-21'
$env:PATH='C:\Users\farre\IdeaProjects\OpenGGF\.lwjgl\3.3.3+5\x64;' + $env:PATH
& 'C:\Users\farre\Tools\apache-maven\bin\mvn.cmd' -q -Dmse=off -DskipTests compile
& 'C:\Users\farre\Tools\apache-maven\bin\mvn.cmd' -q -Dmse=off -DskipTests test-compile
& 'C:\Users\farre\Tools\apache-maven\bin\mvn.cmd' -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.session.TestSessionManager,com.openggf.editor.TestEditorToggleIntegration,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.game.TestRuntimeOwnedRegistryLifecycle" test
& 'C:\Users\farre\Tools\apache-maven\bin\mvn.cmd' -q -Dmse=off "-Dtest=com.openggf.game.sonic3k.objects.TestIczSnowboardArtLoader,com.openggf.game.sonic3k.dataselect.TestS3kSaveSnapshotProvider,com.openggf.level.objects.TestObjectServicesExpansion,com.openggf.level.objects.TestObjectServicesRuntimeDefaults,com.openggf.TestGameLoop,com.openggf.TestEngine" test
rg -n "RuntimeManager|GameRuntime|runtimeOrNull|currentEngineServices|configureEngineServices" src/main/java
```

### Sequencing

1. **B1-B3 first:** split reset semantics and make `GameplayModeContext.destroy()` real. This removes the highest-risk lifecycle blocker.
2. **A1 helper next:** introduce a session test helper so test migration has a stable target.
3. **A2-A5 in package batches:** migrate tests and compatibility constructors by subsystem, starting with harnesses, editor/session tests, object service tests, and save snapshot tests.
4. **B4-B6:** remove `RuntimeManager.destroyCurrent()` from session/engine lifecycle and delete `runRuntimeTeardownPreservingWorld(...)`.
5. **A6:** delete `GameRuntime` and collapse `RuntimeManager` once no production/test call sites remain.
6. **C final:** land strict guards and docs/changelog updates.

### Completion Criteria

- `rg -n "\bGameRuntime\b|\bRuntimeManager\b" src/main/java` returns no production use except intentionally retained documentation strings, or returns no matches if both classes are deleted.
- No tests call `RuntimeManager.getCurrent()`, `RuntimeManager.getActiveRuntime()`, `RuntimeManager.createGameplay()`, or `RuntimeManager.setCurrent()`.
- `GameLoop`, `DefaultObjectServices`, and `RuntimeSaveContext` have no `GameRuntime` compatibility overloads.
- `SessionManager` and `GameplayModeContext` own teardown without routing through `RuntimeManager`.
- `LevelManager` has separate gameplay teardown and full world/session clearing semantics.
- Editor round-trip tests prove `MutableLevel` and world metadata survive while gameplay-scoped counters/registries reset.
- Maven compile, test-compile, targeted lifecycle/editor tests, production guard tests, and impacted engine/game-loop slices pass.
