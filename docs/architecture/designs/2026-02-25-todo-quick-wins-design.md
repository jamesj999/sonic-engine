# TODO Quick Wins — Design Doc

**Date:** 2026-02-25
**Scope:** Resolve 7 actionable TODOs + clean up 4 outdated TODO comments

## Context

The test-writing session (commit d0b90d69) added 29 TestTodo files with executable specs for each TODO item. Validation against disassembly and existing code confirmed which items genuinely need changes vs. which are outdated comments on already-implemented features.

## Validated Scope

### Tier 1: Actionable Implementation (7 items)

| # | Item | File | Effort | Change |
|---|------|------|--------|--------|
| 7 | LZ rumbling SFX | `Sonic1LZWaterEvents:483` | ~1 line | Add `playSfx(Sonic1Sfx.RUMBLING.id)` |
| 1 | S2 water heights | `Sonic2ZoneFeatureProvider:303` | ~1 line | Delegate to `WaterSystem.getInstance().getWaterLevelY()` |
| 35 | Control lockout timer | `Sonic1LZWaterEvents:1028` | ~15 lines | Add countdown timer field, decrement per frame, block input during lockout |
| 17 | Boss flag animation gating | `Sonic3kPatternAnimator:106,118` | ~20 lines | Read boss flag from `Sonic3kAIZEvents`, skip flagged animations when boss inactive |
| 5 | Water distortion sine table | `WaterSystem:529` | ~30 lines | Replace synthetic sine with ROM's 66-byte `SwScrl_RippleData` table |
| 3 | Monitor effects | `MonitorObjectInstance:292` | ~50 lines | Implement Eggman (hurt player), Static (no-op), Teleport (no-op stub) effects |
| 8 | Yadrin spiky-top collision | `Sonic1YadrinBadnikInstance:300` | ~50 lines | Add `TouchResponseListener` for React_Special, spiky top geometry check |

### Tier 2: Outdated Comment Cleanup (4 items)

| # | Item | File | Change |
|---|------|------|--------|
| 37 | Sliding spikes subtypes | `SlidingSpikesObjectInstance:48` | Remove TODO, add comment documenting that only subtype 0 exists in ROM |
| 34 | Water slide detection | `Sonic1LZWaterEvents:914` | Remove TODO, code already implements chunk ID lookup |
| 2 | Dual collision addresses | `Sonic2Level:334` | Remove TODO, both primary/secondary paths are loaded |
| 6 | SolidTile angles | `SolidTile:21` | Remove TODO, `getAngle(hFlip, vFlip)` handles all transforms |

### Excluded

| # | Item | Reason |
|---|------|--------|
| 31 | End-game loop | Blocked — requires ending screens that don't exist |

## Test Strategy

Each actionable item has a corresponding `TestTodo{N}_*.java` with `@Ignore` tests that document expected behavior. After implementation:
1. Remove `@Ignore` from resolved tests
2. Verify tests pass with `mvn test -Dtest=TestTodoN_*`
3. Full suite regression: `mvn test`

## Implementation Approach

Parallel agents in worktree isolation, grouped by game:
- **Agent A (S1):** Items #7, #35, #8, #34 (Sonic 1 water/collision)
- **Agent B (S2):** Items #1, #3, #5, #37, #2 (Sonic 2 core/objects)
- **Agent C (S3K):** Item #17, #6 (S3K animation, cross-cutting cleanup)
