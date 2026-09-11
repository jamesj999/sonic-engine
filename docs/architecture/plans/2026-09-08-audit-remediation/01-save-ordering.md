# 01 — Serialize save access across managers

High; medium–high complexity; estimated 4–8 engineering hours.

## Problem

Pending futures are local to each SaveManager although the writer is shared. Gameplay, Data Select and Engine create different managers for the same root. A queued old write can run after a menu delete or newer synchronous write.

## Owners and evidence

Primary owners: `src/main/java/com/openggf/game/save/SaveManager.java`, `SessionSaveRequests.java`; caller evidence in `game/sonic3k/dataselect/S3kDataSelectPresentation.java` and `Engine.launchGameplayFromDataSelect`. Tests: `src/test/java/com/openggf/game/save/TestSaveManager.java` and appropriate new concurrency coverage.

## Implementation steps

1. Add deterministic two-manager tests with a delayed writer: read observes all prior writes, delete stays deleted, and a newer synchronous save cannot be overwritten by an earlier async save.
2. Establish ordering at shared storage ownership (shared writer/barrier or per-root/path coordinator), covering reads, deletes, synchronous writes and async writes. Keep filesystem work off gameplay-frame submissions.
3. Ensure no lock is held while waiting for a worker that needs the same lock; preserve submission order, shutdown draining, atomic replacement and corrupt-file handling. Address interrupted waits without allowing a stale delete/read to proceed as though drained.
4. Keep payload snapshot/encoding ownership on the submitting frame. Avoid an unbounded retention of completed futures or permanent per-manager state.
5. Test independent managers and production access pattern, error handling, and same-manager compatibility. Record limitations of filesystem failures without silently claiming persistence success.

## Acceptance

Previously queued writes cannot cross a later read/delete/write boundary through another manager. Tests demonstrate the old race and pass after the fix. Existing save/session tests remain green.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.
