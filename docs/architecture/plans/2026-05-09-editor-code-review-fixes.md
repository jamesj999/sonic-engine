# Editor Code Review Fixes Implementation Plan

> **For implementers:** Use `superpowers:subagent-driven-development` to delegate the independent tasks below. Use `superpowers:test-driven-development` for each behavioral fix and `superpowers:verification-before-completion` before reporting completion.

## Goal

Address the architectural review findings from the level editor MVP branch and keep the implementation aligned with the runtime-ownership design:

- editor save/resume must persist the `LevelEditorController`'s current `MutableLevel`, even after gameplay runtime teardown;
- editor mode must keep an editor-safe `LevelManager` view after `RuntimeManager.destroyCurrent()`;
- editor rendering must flush dirty mutable-level regions before drawing;
- mutable-level change tracking must be baseline-aware, so reverted edits do not persist as stale deltas.

## Architecture

The editor owns the editable `MutableLevel` through `LevelEditorController`; gameplay sessions own runtime managers through `GameplayModeContext`. Editor entry tears down the gameplay runtime, so any editor path that relies on runtime-owned managers must either capture the required world state before teardown or restore an explicitly editor-safe facade afterward.

Do not hydrate committed engine state from trace data. This plan does not touch trace replay code.

## Task 1: Save Editor State From Controller-Owned MutableLevel

**Files:** `src/main/java/com/openggf/Engine.java`, `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

1. Add a regression test that enters editor mode, mutates `levelEditorController.currentLevel()`, resumes playtest, and verifies the persisted editor save contains that mutation.
2. Update `Engine.resumePlaytestFromEditor()` to save the editor controller's current `MutableLevel` before repairing or rebuilding gameplay runtime state.
3. Derive game id, zone, and act from `WorldSession`; avoid querying `GameModuleRegistry` or runtime-owned managers during editor teardown.
4. Keep existing editor save behavior for normal mouse/keyboard save commands.

**Verification:**

```bash
mvn -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration#resumePlaytestFromEditor_savesEditorControllerMutableLevelAfterRuntimeTeardown" test
mvn -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration" test
```

## Task 2: Restore an Editor-Safe LevelManager View After Runtime Teardown

**Files:** `src/main/java/com/openggf/Engine.java`, `src/main/java/com/openggf/level/LevelManager.java`, `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

1. Add a regression test proving that after editor entry, editor draw paths can still access the inherited level through `LevelManager`.
2. Reuse or add a narrowly scoped `LevelManager` restoration method that binds the captured `MutableLevel` for editor rendering without recreating a gameplay runtime.
3. Keep the restored view read/editor-oriented; gameplay manager counters must still be rebuilt only when resuming playtest.

**Verification:**

```bash
mvn -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration#enterEditor_restoresLevelManagerViewForEditorRenderingAfterRuntimeTeardown" test
```

## Task 3: Flush Dirty Regions During Editor Rendering

**Files:** `src/main/java/com/openggf/Engine.java`, `src/main/java/com/openggf/level/MutableLevel.java`, `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

1. Add a regression test that mutates a visible editor tile, invokes the editor draw path, and verifies dirty regions are processed.
2. Ensure editor rendering calls the same dirty-region processing path gameplay uses before drawing level layers.
3. Preserve deterministic mutation order; do not process dirty regions from input callbacks if rendering already owns the frame flush.

**Verification:**

```bash
mvn -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration#editorDrawPathFlushesMutableLevelDirtyRegionsBeforeRendering" test
```

## Task 4: Track Save Deltas Against the Original Baseline

**Files:** `src/main/java/com/openggf/level/MutableLevel.java`, `src/test/java/com/openggf/level/TestMutableLevelBaselineTracking.java`, `src/test/java/com/openggf/editor/persistence/TestEditorSaveManager.java`

1. Add tests showing a block, chunk, or map-cell edit that is changed back to its original value is removed from the save delta.
2. Store immutable baseline snapshots for chunks, blocks, and map cells when a `MutableLevel` is created or restored from baseline state.
3. In mutators, mark a location changed only when the current value differs from the baseline value; clear the bit when it matches the baseline again.
4. Keep `modifiedSinceLastSave` as a UI/session dirty indicator, but make save payload consumption baseline-aware.

**Verification:**

```bash
mvn -Dmse=off "-Dtest=com.openggf.level.TestMutableLevelBaselineTracking,com.openggf.editor.persistence.TestEditorSaveManager" test
```

## Task 5: Focused Regression Run

Run the editor-focused test set after all tasks land:

```bash
mvn -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration,com.openggf.editor.TestLevelEditorController,com.openggf.editor.TestEditorMouseStrokeUndo,com.openggf.editor.TestEditorBgLayer,com.openggf.editor.TestEditorMouseTransform,com.openggf.control.TestInputHandlerMouse,com.openggf.editor.persistence.TestEditorSaveManager,com.openggf.level.TestMutableLevelBaselineTracking" test
```

If the full suite is run, report any pre-existing unrelated failures separately from this editor review-fix work.

## Self-Review Checklist

- No runtime-owned gameplay managers are used as the source of truth while editor mode is active.
- Editor entry and exit preserve `WorldSession` state and rebuild gameplay counters only on resume.
- Dirty-region flushing happens once per editor draw frame.
- Save deltas represent differences from the original mutable-level baseline, not append-only edit history.
- Tests use JUnit 5 only.
