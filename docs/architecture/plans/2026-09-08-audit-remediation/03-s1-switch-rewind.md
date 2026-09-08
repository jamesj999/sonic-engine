# 03 — Capture Sonic 1 switch RAM

High rewind correctness; medium complexity; estimated 2–4 hours.

## Problem

Sonic1SwitchManager owns 16 mutable switch bytes consumed by multiple objects, but no production rewind adapter captures them. The structural baseline explicitly accepts this real gap.

## Owners and evidence

Owners: `game/sonic1/Sonic1SwitchManager.java`, `Sonic1GameModule.java`, `events/Sonic1LevelEventManager.java`, existing S1 state adapters, and `src/test/resources/rewind/static-state-coverage-baseline.txt`. Production registration is in GameplayModeContext; conveyor/floating-block adapters already work.

## Implementation steps

1. Add a production-registry round-trip test that captures switch bytes, changes them, restores, and checks a consumer observes captured state before its next update.
2. Add an immutable/defensively copied snapshot for all 16 bytes, with existing adapter identity/reset conventions. Register it through the owning S1 lifecycle.
3. Test both bit 0 and bit 7, cleared/set state, repeated restores and snapshot non-aliasing. Missing-snapshot behavior must reset safely.
4. Remove only the now-fixed switch-manager missing-adapter baseline entry; preserve unrelated accepted gaps.
5. Coordinate with task 02. Do not change retail button/update clocks or gameplay switch semantics.

## Acceptance

The production registry restores all switch RAM; the existing coverage guard recognizes the adapter without a new exception. Consumer-based regression and S1 rewind tests pass.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.
