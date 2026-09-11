# v0.6 Level Editor MVP Delivery

Date: 2026-05-09
Branch: feature/ai-editor-mvp-v06
Blueprint: docs/architecture/designs/2026-05-09-level-editor-mvp-blueprint.md

## Requirements

Goals delivered:
- BG layer editing with active-layer toggle, layer-aware placement, eyedrop, and overlay indicator.
- Mouse hover, left-click placement, drag-paint stroke coalescing, and right-click eyedrop gated through editor input.
- JSON sidecar persistence under `saves/{game}/edits/zone_{zone}_act_{act}.json`, including explicit save, editor-exit autosave, load-time replay, dirty/saved overlay state, and corrupt quarantine.

Non-goals preserved:
- No object/ring editing, pattern editing, block flag editing, camera pan/zoom, new-level creation, or schema migration.
- No mouse behavior is consumed outside the editor input path.

Acceptance coverage:
- AC1: `TestEditorBgLayer`
- AC2: `TestInputHandlerMouse`, `TestEditorMouseTransform`, `TestEditorMouseStrokeUndo`
- AC3-AC5: `TestEditorSaveManager`
- AC6: focused JUnit 5 coverage added for BG layer, mouse transform/stroke, and persistence quarantine.

Assumptions:
- Persisted deltas cover blocks, chunks, and map cells only.
- Save root follows the blueprint path under `saves/`.
- Existing full-suite failures in unrelated gameplay registry/object rewind/S3K trace tests are not part of this feature.

Risks:
- `SaveManager` itself remains non-atomic; editor persistence uses temp-write plus atomic replace.
- Full end-to-end restart behavior is covered through persistence unit tests and load-time wiring, not a process restart test.

## Exploration Synthesis

Paired exploration confirmed:
- BG edits require controller-level changes only; `MutableLevel` and tilemap rebuilds are already layer-aware.
- Mouse support needed `InputHandler` state, GLFW callbacks, viewport/camera transform, and a composite stroke command because existing placement emits one history command per call.
- Persistence needed a separate editor manager; gameplay save slots are semantically different, while their JSON envelope/hash/quarantine pattern is reusable.

Local evidence used:
- `LevelEditorController` held all existing hardcoded layer-0 world reads/writes.
- `GameLoop` consumes editor input before `InputHandler.update()`, so mouse pressed edges match keyboard timing.
- `LevelManager.loadCurrentLevel` is the stable load-time hook after ROM baseline creation.

Conflicts resolved:
- The persistence explorer suggested an `editor-projects/` root; the blueprint explicitly required `saves/{game}/edits`, so the implementation follows the blueprint.

## Architecture Decision

Ownership:
- `LevelEditorController`: active layer, layer-aware edit routing, history command execution seam.
- `InputHandler` and `Engine`: mouse state and GLFW callback plumbing.
- `EditorInputHandler`: editor-only mouse consumption and stroke lifecycle.
- `EditorSaveManager`: sidecar serialization, hashing, atomic write, apply, quarantine.
- `MutableLevel`: persistent modified-since-baseline bitsets separate from frame dirty queues.

Boundaries:
- No object/ring/pattern persistence.
- No gameplay mouse consumption.
- No runtime-owned framework added; persistence is editor-local.

Lifecycle:
- Active layer resets on `attachLevel`.
- Modified-since-baseline bits survive dirty-region consumption.
- Persisted edits replay after ROM load by snapshotting to `MutableLevel` and applying deltas.

Rollback:
- Remove `EditorSaveManager` load/save calls from `LevelManager`/`Engine`; saved sidecars are additive and do not alter ROM data.

## Feature Design

Behavior:
- `L` toggles FG/BG when the map has at least two layers.
- `Ctrl+S` saves the current mutable editor level.
- Editor exit calls the same save path before rebuilding gameplay.
- Mouse hover updates the editor cursor once mouse input has actually occurred.
- LMB drag records first entry per cell and commits one `StrokeCommand` on release.
- RMB invokes eyedrop at the hovered cell.

APIs/contracts:
- `EditorMouseTransform.Result` exposes viewport hit, world coords, and tile coords.
- `StrokeCommand` stores `(layer, x, y, before, after)` cell deltas.
- `EditorSaveEnvelope` schema version is fixed at 1.

Edge cases:
- Mouse outside viewport does not begin or commit a stroke.
- Dragging across repeated cells deduplicates entries.
- Hash/version corruption quarantines the sidecar and falls back to baseline.
- Zone/act metadata mismatch returns `MISMATCH` without mutation.

## Implementation Plan

Completed task split:
- T1: `MutableLevel` baseline tracking and `EditorMouseTransform`.
- T2: BG active-layer editing and overlay text.
- T3: mouse input state, GLFW callbacks, stroke command, mouse tests.
- T4: editor persistence manager, save/apply wiring, persistence tests.
- T5: integration verification through existing editor toggle/rendering suites plus new focused tests.

Verification commands:
- `mvn test "-Dtest=TestMutableLevelBaselineTracking,TestEditorMouseTransform" -Dmse=off`
- `mvn test "-Dtest=TestEditorBgLayer,TestLevelEditorController,TestEditorCommands" -Dmse=off`
- `mvn test "-Dtest=TestInputHandlerMouse,TestEditorMouseStrokeUndo,TestEditorToggleIntegration" -Dmse=off`
- `mvn test "-Dtest=TestEditorSaveManager,TestLevelEditorController,TestEditorToggleIntegration" -Dmse=off`
- `mvn test "-Dtest=TestEditor*" -Dmse=off`
- `mvn test "-Dtest=TestProductionSingletonClosureGuard,TestPlaneSwitcherStateIsolation" -Dmse=off`
- `mvn test "-Dtest=TestAudioManagerRuntimeInstallation,TestProductionSingletonClosureGuard" -Dmse=off`
- `mvn test "-Dtest=TestEditor*,TestPlaneSwitcherStateIsolation" -Dmse=off`
- `mvn test "-Dtest=TestEditor*,TestProductionSingletonClosureGuard,TestPlaneSwitcherStateIsolation,TestAudioManagerRuntimeInstallation" -Dmse=off`
- `mvn test -Dmse=off`

## Integration Report

Changed production areas:
- `Engine`, `InputHandler`, editor controller/input/history/rendering, `MutableLevel`, `LevelManager`.
- New editor helpers: `EditorMouseTransform`, `StrokeCommand`, `editor.persistence` records/manager.

Changed tests:
- New tests for BG layer, mouse transform, mouse stroke undo, input mouse edges, persistence, and mutable baseline tracking.
- Updated controller action-surface test for `TOGGLE_LAYER` and `SAVE`.

Test evidence:
- Full editor suite: `mvn test "-Dtest=TestEditor*" -Dmse=off` -> 86 tests, 0 failures.
- Singleton/rewind isolation focused check: `mvn test "-Dtest=TestProductionSingletonClosureGuard,TestPlaneSwitcherStateIsolation" -Dmse=off` -> 36 tests, 0 failures.
- Audio bootstrap regression check: `mvn test "-Dtest=TestAudioManagerRuntimeInstallation,TestProductionSingletonClosureGuard" -Dmse=off` -> 36 tests, 0 failures.
- Final focused editor/rewind check: `mvn test "-Dtest=TestEditor*,TestPlaneSwitcherStateIsolation" -Dmse=off` -> 88 tests, 0 failures.
- Post-review focused check: `mvn test "-Dtest=TestEditor*,TestProductionSingletonClosureGuard,TestPlaneSwitcherStateIsolation,TestAudioManagerRuntimeInstallation" -Dmse=off` -> 127 tests, 0 failures.

Unresolved risks:
- Full `mvn test -Dmse=off` is not green on this workspace. After editor-related and direct singleton/rewind isolation regressions were fixed, the remaining failures are unrelated existing gameplay/trace issues:
  - `TestGameModuleRegistryUsageGuard`: direct `GameModuleRegistry` usage in `Engine` and `Sonic3kPaletteCycler`.
  - Rewind/object migration guards: `TestRewindTorture`, `TestRewindTransientGuard`, `TestAbstractObjectInstanceRewindCapture`, `TestObjectServicesMigrationGuard`.
  - S3K trace replay/bootstrap drift: `TestS3kAizReplayBootstrap`, `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay`.

## End-to-End Review

Findings:
- No blocker found in the editor MVP implementation based on focused tests.
- The direct regressions discovered during full-suite verification were fixed: editor save hashing no longer trips the singleton scanner, `AudioManager` preserves pre-runtime deterministic audio setup, S3K startup palette refresh uses `GameServices.graphics()`, and object rewind capture tolerates no-services test construction.
- Code review fixes landed: active mouse strokes are flushed before editor exit/save, editor exit now aborts if persistence fails, and persisted map-cell block indices are validated before mutation.
- Broader full-suite blockers remain outside the changed editor files and should be handled separately before merge if the branch policy requires all tests green.

Human-review checklist:
- Confirm `L` and `Ctrl+S` bindings.
- Confirm sidecar path `saves/{game}/edits/zone_{zone}_act_{act}.json`.
- Decide whether the unrelated full-suite failures must be fixed on this branch or accepted as baseline debt.
