# 02 — Restore SBZ3 singleton ownership across rewind

High; medium–high complexity; estimated 4–8 hours.

## Problem

The static sbz3Instance reference survives ObjectManager dropping a door while restoring a snapshot before its spawn. The next constructor treats the discarded future door as a live singleton and destroys its replacement.

## Owners and evidence

Owners: `game/sonic1/objects/Sonic1StomperDoorObjectInstance.java`, `game/sonic1/Sonic1GameModule.java`, module services/adapters and `level/objects/ObjectManager.java` restore lifecycle. Read the `s1-implement-object` skill and relevant rewind references. The owning ROM routine is `6B SBZ Stomper and Door.asm` (retail v_obj6B ownership).

## Implementation steps

1. Reproduce absent → spawn → capture/rewind-to-absent → spawn through actual ObjectManager/production adapter registration; constructor-only evidence is insufficient acceptance.
2. Model singleton ownership as restored module/manager state or a precise lifecycle adapter with identity fixup. Prefer the narrow S1 owner over a game-specific carve-out in generic ObjectManager.
3. Cover restoring a snapshot containing a door, dropped future doors, normal offscreen deletion, repeated seeks and alternate restoration/reuse paths. Avoid constructor side effects corrupting restored ownership.
4. Preserve retail first-loaded-slot-wins behavior for ordinary duplicate doors. Do not globally clear ownership every frame or disable the singleton rule.
5. Coordinate adapter/module edits with task 03 in the same workstream. Update any affected rewind baselines/discrepancy documentation honestly.

## Acceptance

Replaying across a door spawn does not suppress the restored/recreated door, and real simultaneous duplicate doors remain rejected. No new uncaptured shared state or rewind identity gap.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.
