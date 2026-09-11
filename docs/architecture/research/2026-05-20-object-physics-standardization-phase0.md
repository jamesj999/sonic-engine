# Object Physics Standardization Phase 0

Date: 2026-05-20
Branch: `feature/ai-object-physics-standardization`
Worktree: `.worktrees/ai-object-physics-standardization`

## Status

Phase 0 inventory is complete enough to start implementation. The repository baseline is green after merging `origin/develop` at `ef8634dc8 bugfix: restore object service guard baseline`.

Verified baseline:

```powershell
mvn "-Dmse=off" -DskipTests compile
mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.tests.TestNoServicesInObjectConstructors" test
```

Both commands passed in the feature worktree before implementation work.

## Execution Rule

Do not replace object physics behavior directly. First add characterization tests, profile or ops adapters, and no-new-growth guards with explicit baselines. Existing direct behavior stays delegated unless a test proves the profile abstraction is equivalent.

The blueprint is intentionally local planning input and is not part of this branch. This artifact is the branch-owned Phase 0 handoff for agent orchestration.

## Guard Infrastructure

Existing source-guard patterns to reuse:

- `ObjectGuardSourceScanner` for comment-stripped source scans.
- Inline known-violation sets and approved access records in `TestObjectServicesMigrationGuard`.
- Inline baseline sets in `TestConstructionContextGuard`.
- `FreezingArchRule` only where structural ArchUnit baselines are already appropriate.

New guards should use stable source fragments plus reason codes rather than raw line numbers. Recommended baseline fields:

```text
path
symbol
lineFragment
violationKind
reasonCode
targetAbstraction
justification
```

Common reason codes:

- `LEGACY_PROVIDER_COMPAT`
- `PENDING_PARITY_TRIAGE`
- `DYNAMIC_CHILD_LIFETIME`
- `PERSISTENT_SELF_MANAGED`
- `BOSS_OR_CUTSCENE_ESCAPE_HATCH`
- `CUTSCENE_SCRIPT`
- `SPAWN_SNAP`
- `CHECKPOINT_RESTORE`
- `BOUNDARY_CLAMP`
- `TOP_LEFT_RENDER_SPACE`

## Solid And Sloped Providers

Provider surfaces:

- `SolidObjectProvider`
- `SlopedSolidProvider`
- `SolidObjectParams`
- `ObjectManager.SolidContacts`

Good first-pass `SolidRoutineProfile` fields:

- `kind`
- `topSolidOnly`
- `monitorSolidity`
- `monitorVerticalOffset`
- `inclusiveRightEdge`
- `stickyContactBuffer`
- `usesPlatformLandingSnap`
- `usesCollisionHalfWidthForTopLanding`
- `usesGroundHalfHeightForTopSolidContact`
- `bypassesOffscreenSolidGate`
- `allowsObjectControlledSolidContacts`
- `forceAirOnRideExit`
- `dropOnFloor`
- `carriesAirborneRiderAfterExitPlatform`

Good first-pass `SlopedSolidRoutineProfile` fields:

- `usesSlopeForNewLanding`
- `usesGroundedStandingCatchWindow`
- `addsSlopeCatchRangeToVerticalOverlap`
- `slopeBaseline`

Must remain delegated initially:

- `getSolidParams()`
- `isSolidFor(player)`
- `solidExecutionMode()`
- previous-position/history frame hooks
- zero-distance acceptance and rejection hooks
- object-managed ride hooks
- top landing snap adjustment
- sidekick offscreen render-flag skips
- custom top landing width
- stale input windows
- bottom-overlap y-radius hook
- pushing-bit bookkeeping
- horizontal rider carry hook
- slope sample suppression and ride-exit sampling
- `getSlopeData()` and `isSlopeFlipped()`

Compatibility test priorities:

- default provider maps to default full-solid profile
- top-only PlatformObject style
- SolidObject_Landed style: zero-distance allowed and platform snap disabled
- S1/S2/S3K monitor differences
- S2/S3K spring offscreen bypass and inclusive right edge
- sloped bridge profile with `usesSlopeForNewLanding=false`
- spy provider proving delegated hooks are still called

## Touch Responses

Provider surface:

- `TouchResponseProvider`
- `TouchResponseListener`
- `TouchResponseAttackable`
- `TouchCategory`
- `TouchResponseResult`

Good first-pass `TouchResponseProfile` fields:

- category decode mode: normal, S2 special-property, S3K special-property
- continuous vs edge-trigger callbacks
- render-flag gate requirement
- single-region vs multi-region source exists, while geometry delegates
- shield deflect capability and flags, while effects delegate
- attack bounce policy: standard enemy kill, boss reflect, custom handled, none
- actor context: main hurt/death/rings vs sidekick hurt only
- stop-after-first-overlap policy

Must remain delegated initially:

- `getCollisionFlags()`
- `getCollisionProperty()`
- `getMultiTouchRegions()`
- `onTouchResponse(...)`
- `onPlayerAttack(...)`
- `onShieldDeflect(...)`
- destroyed/touchable state encoded through flags

Compatibility hazards:

- Main and sidekick share `processCollisionLoop`, but response handlers differ.
- Main hurt can scatter rings or kill; sidekick hurt does not.
- Single-region touch stops after first overlap for both.
- Multi-region touch currently stops for main but lets sidekick continue scanning later objects. Preserve this until a dedicated test authorizes a behavior change.
- Shield deflect only runs for single-region `HURT` category today.

Compatibility test priorities:

- profile defaults match provider defaults
- S2 special property mapping for bumper, bonus block, Crawl
- S3K special property mapping for CNZ balloon, Clamer, ICZ harmful ice
- render-flag opt-out mapping for S2 bumper/bonus/hex bumper and Turtloid rider
- continuous callback mapping for bumpers, balloons, Clamer, item orbs, water drops, MegaChopper
- multi-region geometry delegation and current sidekick continuation behavior
- enemy bounce: already destroyed vs newly destroyed vs HP-positive target
- shield deflect and fire-shield damage cause path

## Lifecycle

Core lifecycle hubs:

- `ObjectManager` placement, slot, dynamic object, child-slot, remembered-object APIs
- `AbstractObjectInstance` destruction flags and child creation helpers
- `DestructionEffects.destroyBadnik`
- `AbstractBadnikInstance.destroyBadnik`

First `ObjectLifetimeOps` adapter categories:

- `destroyLatched(ObjectInstance)`
- `destroyRespawnableOffscreen(ObjectInstance)`
- `deleteNoRespawn(ObjectInstance)`
- `expireDynamic(ObjectInstance)`
- `transferSlotTo(ObjectInstance from, ObjectInstance to)`
- `releaseParentSlotKeepingChildren(ObjectInstance parent)`
- `cleanupLinkedChildren(ObjectInstance parent)`

Direct lifecycle calls are widespread and require a baseline. Do not hard-fail all existing direct calls on the first pass.

Initial baseline kinds:

- `DIRECT_SET_DESTROYED`
- `DIRECT_MARK_REMEMBERED`
- `DIRECT_REMOVE_ACTIVE_SPAWN`
- `MANUAL_SLOT_TRANSFER`
- `MANUAL_CHILD_CLEANUP`

Compatibility test priorities:

- lifetime ops map to current flags and manager calls
- badnik slot transfers to explosion exactly as today
- reserved child slot allocation/release remains unchanged
- object rewind capture tests still pass
- placement remembered/unload tests still pass

## Native Position Ops

Native ROM `x_pos` and `y_pos` map to center-coordinate APIs: `getCentreX()` and `getCentreY()`.

First API should target `AbstractPlayableSprite`, not `PlayableEntity`, because preserve-subpixel methods live on `AbstractSprite`/`AbstractPlayableSprite` and are not part of `PlayableEntity`.

Recommended first methods:

```java
writeXPosPreserveSubpixel(AbstractPlayableSprite player, int x)
writeYPosPreserveSubpixel(AbstractPlayableSprite player, int y)
addXPosPreserveSubpixel(AbstractPlayableSprite player, int dx)
addYPosPreserveSubpixel(AbstractPlayableSprite player, int dy)
writeXPosResetSubpixel(AbstractPlayableSprite player, int x)
writeYPosResetSubpixel(AbstractPlayableSprite player, int y)
```

First production target:

- `ObjectManager.SolidContacts` Y snaps and X pushes, because it already contains `AbstractPlayableSprite`-specific preserve-subpixel fallbacks.

Guard baseline kind:

- `DIRECT_PLAYER_POSITION_WRITE`

Initial reason codes:

- `TOP_LEFT_RENDER_SPACE`
- `SPAWN_SNAP`
- `CHECKPOINT_RESTORE`
- `CUTSCENE_SCRIPT`
- `BOUNDARY_CLAMP`
- `PENDING_PARITY_TRIAGE`

Compatibility test priorities:

- preserve X keeps raw X subpixel unchanged
- preserve Y keeps raw Y subpixel unchanged
- reset X/Y clears only the relevant subpixel lane
- add X/Y handles positive and negative deltas
- center-coordinate mapping is explicit

## Player Participation

`ObjectPlayerQuery.mainPlayer()` should prefer the active main-character sprite from `SpriteManager`/`LevelManager` semantics, not camera focus. Camera focus remains legacy/fallback context only.

Recommended participation policies:

- `MAIN_ONLY_NATIVE`
- `NATIVE_P1_P2`
- `MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED`
- `ALL_ENGINE_PLAYERS`
- `NEAREST_ENGINE_PLAYER`

Keep `ObjectServices.sidekicks()` for transitional compatibility. New or migrated object logic should route through `ObjectPlayerQuery.playersFor(...)` or specific query helpers.

Guard baseline kinds:

- `RAW_SIDEKICK_ACCESS`
- `DIRECT_SPRITE_MANAGER_SIDEKICKS`
- `FIRST_SIDEKICK_NATIVE_P2`
- `FOCUSED_MAIN_PLAYER`
- `PARTICIPATION_UNDECLARED`

Compatibility test priorities:

- main player is resolved from active main-character selection, not first sidekick
- sidekick order mirrors `SpriteManager.getSidekicks()`
- native P1/P2 returns main plus first sidekick only
- all-player query excludes nulls and duplicates
- nearest-player tie behavior is stable
- raw new sidekick loops fail without baseline or policy use

## Object Control State

Current flags live on `AbstractPlayableSprite`:

- `objectControlled`
- `objectControlAllowsCpu`
- `objectControlSuppressesMovement`

First wrapper states must preserve all observed combinations:

- `none()`: controlled false, allows CPU false, movement not suppressed
- `nativeBit7FullControl()`: controlled true, allows CPU false, movement suppressed
- `nativeBits0To6CpuAllowedMovementSuppressed()`: controlled true, allows CPU true, movement suppressed
- `nativeBits0To6CpuAllowedMovementActive()`: controlled true, allows CPU true, movement active
- `movementSuppressedOnly()`: controlled false, movement suppressed
- `engineScriptedTouchSuppressedMovementActive()`: controlled true, allows CPU false, movement active

Do not collapse this to only three states. `FlipperObjectInstance` uses movement-only suppression without object control, and ICZ snowboard has scripted touch-suppressed movement-active states.

Feature-flag scan result:

- No `GameId`, `getGameId()`, or `GameModuleRegistry` hits were found in target object/physics/playable production packages.
- Existing guards already block behavior branches on game identity outside approved routing/composition files.

Compatibility test priorities:

- object-control state matrix for movement, CPU, touch, and solid-contact predicates
- movement-only suppression remains distinct from full object control
- bit-7 suppresses touch; CPU-allowed states do not
- solid-contact blocking and MGZ exception remain stable
- sidekick CPU bit-7 gate and diagnostic byte remain stable

## Implementation Sequence

Start with adapter characterization and guards, then wire production code narrowly.

1. Add `NativePositionOps` and tests, then migrate only `ObjectManager.SolidContacts` preserve-subpixel writes.
2. Add `ObjectControlState` wrappers and tests, then migrate representative direct setter combinations without changing predicates.
3. Add `ObjectPlayerQuery` and tests, then migrate a small representative set of raw sidekick loops.
4. Add solid/sloped profile records and mapping tests, with ObjectManager still delegating risky hooks.
5. Add touch-response profile records and mapping tests, preserving multi-region sidekick behavior.
6. Add lifecycle ops adapters and tests around badnik destruction/slot transfer and remembered placement.
7. Add source guards with baselines after the first wrappers exist, so new code has a compliant route.

Safe parallel work:

- Native position ops can proceed independently of player query.
- Object-control wrappers can proceed independently of solid/touch profiles if production rewires are limited.
- Solid/sloped and touch profiles can be developed in parallel as pure mapping tests and records.

Do not parallelize edits to `ObjectManager.java` without coordination. It is the shared integration hotspot.

## Escalation Points

Stop and ask for review if any of these appear:

- a profile field needs to replace a stateful hook that Phase 0 marked delegated
- a test exposes behavior difference in multi-region sidekick touch scanning
- a lifecycle wrapper changes remembered placement, slot identity, child-slot ownership, or rewind capture identity
- a coordinate change requires broadening `PlayableEntity`
- a participation policy would exclude sidekicks from an object that currently handles them
