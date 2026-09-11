# Object Physics Standardization Phases 1-4 Integration

Date: 2026-05-20
Branch: `feature/ai-object-physics-standardization`

## Scope

This artifact records the post-Phase-0 execution for the object physics standardization blueprint.

Implemented stages:

- Phase 1: canonical profile scaffolding
- Phase 2: player participation service API
- Phase 3: object-control state foundation migration for representative S3K carriers
- Phase 4 foundation guardrails for no-new-growth enforcement

The local blueprint spec remains planning input and is not staged by default.

## Architecture Decision

Canonical profile vocabulary now lives under `com.openggf.game.profiles`:

- `com.openggf.game.profiles.solidroutine`
- `com.openggf.game.profiles.touchresponse`
- `com.openggf.game.profiles.objectlifecycle`

Existing `com.openggf.level.objects` profile classes remain source-compatible wrappers because `ObjectManager` and existing tests already consume that package. The wrappers convert to and from canonical records rather than forcing a broad import migration in the same pass.

Object-facing player access now prefers `ObjectServices.playerQuery()`, while raw `ObjectServices.sidekicks()` remains available for transitional compatibility.

Object-control storage remains on `AbstractPlayableSprite`; representative object scripts now apply named `ObjectControlState` combinations where the existing direct setter sequence was already understood.

## Feature Design

Profile scaffolding is adapter-first:

- solid and sloped profiles map current provider defaults and retain delegated provider hooks;
- touch profiles map current decode, shield, region, and stop-policy defaults without changing dispatch;
- lifecycle profiles name Phase 0 categories as data-only contracts and do not own slot identity.

Guardrails are no-new-growth tests:

- direct object-control setters in production object code;
- raw native-P2-style sidekick access;
- direct lifecycle operations that should route through `ObjectLifetimeOps`.

Existing historical occurrences are baselined with reason codes in `TestObjectPhysicsStandardizationGuard`.

## Implementation Plan Result

Completed work packages:

- WP1 canonical profile packages and compatibility wrappers.
- WP2 `ObjectServices.playerQuery()` integration and tests.
- WP3 representative object-control migration:
  - `AizHollowTreeObjectInstance`
  - `AizVineHandleLogic`
  - `CnzWireCageObjectInstance`
- WP4 guard expansion with baseline/no-new-growth checks.

No broad `ObjectManager` rewrite was performed in this phase.

## Integration Report

Primary files changed:

- `src/main/java/com/openggf/game/profiles/**`
- `src/main/java/com/openggf/level/objects/*RoutineProfile.java`
- `src/main/java/com/openggf/level/objects/Touch*Policy.java`
- `src/main/java/com/openggf/level/objects/ObjectServices.java`
- `src/main/java/com/openggf/game/sonic3k/objects/AizHollowTreeObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/AizVineHandleLogic.java`
- `src/main/java/com/openggf/game/sonic3k/objects/CnzWireCageObjectInstance.java`
- `src/test/java/com/openggf/game/profiles/TestCanonicalObjectPhysicsProfiles.java`
- `src/test/java/com/openggf/level/objects/TestObjectPlayerQuery.java`
- `src/test/java/com/openggf/level/objects/TestObjectPhysicsStandardizationGuard.java`

Verification:

```powershell
mvn "-Dmse=off" "-Dtest=com.openggf.game.sonic3k.objects.TestAizVineHandleLogic,com.openggf.game.sonic3k.objects.TestCnzWireCageObjectInstance,com.openggf.sprites.playable.TestObjectControlState" test
```

Result: 23 tests, 0 failures.

```powershell
mvn "-Dmse=off" "-Dtest=com.openggf.level.objects.TestSolidRoutineProfiles,com.openggf.level.objects.TestTouchResponseProfileMapping,com.openggf.level.objects.TestObjectLifetimeOps,com.openggf.game.profiles.TestCanonicalObjectPhysicsProfiles,com.openggf.level.objects.TestObjectPlayerQuery,com.openggf.level.objects.TestObjectPhysicsStandardizationGuard,com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.tests.TestNoServicesInObjectConstructors,com.openggf.game.sonic3k.objects.TestAizVineHandleLogic,com.openggf.game.sonic3k.objects.TestCnzWireCageObjectInstance,com.openggf.sprites.playable.TestObjectControlState" test
```

Result: 93 tests, 0 failures.

Known wider-suite status:

- Full `mvn "-Dmse=off" test` was red before these phases.
- Representative failures reproduce on `develop`, including `TestAizHollowTreeObjectInstance#captureSetsObjectControlBitsSixAndOneWithoutSuppressingMovement` and `TestS2MczRotPformsLifecycle#subtype18SpawnsChildrenAfterObjectManagerServicesAreAvailable`.

## End-to-End Review

No blocker remains for the adapter/profile/guard foundation.

Residual risks:

- Guard baselines are intentionally broad for historical object-control and lifecycle calls. The next migration campaign should burn down entries instead of adding new ones.
- Canonical profile packages are now available, but execution still mostly flows through existing provider methods.
- Touch dispatch compatibility refactor is not implemented yet; it should be the next adapter-only phase before behavior-changing touch migrations.
- Full-suite red status remains a repository baseline concern and should not be attributed to this phase without a fresh reproduction after baseline fixes.

Recommended next step:

Start Work Package 5: touch dispatch compatibility refactor. Keep it adapter-only, route main and sidekick dispatch through one internal helper, and preserve the current multi-region sidekick continuation behavior unless a dedicated test authorizes a change.
