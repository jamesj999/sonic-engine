# Object and Physics Standardization Blueprint

Date: 2026-05-20

## Purpose

OpenGGF has recently fixed many parity bugs around player physics, solid object
collision, touch response, object lifecycle, and CPU sidekick behavior. The
pattern is clear: many objects are re-implementing shared native 68k gameplay
contracts locally through ad hoc booleans, direct state writes, and object-local
edge-case code.

This blueprint defines a staged standardization effort that future sessions can
implement incrementally. The goal is to make new object implementation safer,
reduce repeated parity regressions, and preserve the engine-exclusive
multi-sidekick feature deliberately rather than accidentally.

This document is design and implementation guidance. It intentionally does not
change code.

## Orchestration Contract

This blueprint is intended to drive an agent-automated orchestration pipeline.
The implementation session should require user input only for important
product, parity, or repository-risk decisions. Agents should make conservative
engineering choices when the blueprint and local code evidence are sufficient.

### Repository Basis

Before implementation begins:

1. Use the current local branch as the source of truth.
2. Merge the local branch with `origin/develop`, resolve conflicts normally,
   run the agreed baseline verification, and push that integrated branch.
3. Create the implementation worktree from the pushed integrated branch.
4. Create feature branches using `feature/ai-...` naming.

The blueprint file itself is local planning input unless the user explicitly
asks to stage or commit it. Implementation artifacts may cite it, but the
implementation branch should not depend on the blueprint being committed.

### Agent Autonomy Rules

Agents may proceed without asking the user when:

- a decision is already specified in this blueprint;
- the local codebase clearly establishes the pattern to follow;
- the choice preserves current behavior and only adds compatibility contracts,
  tests, or guard baselines;
- a guard baseline is needed only to prevent historical cleanup from blocking
  new hard-fail enforcement.

Agents must stop and ask the user when:

- a proposed change intentionally changes gameplay behavior;
- a trace replay frontier moves or regresses and the fix path is not obvious;
- the implementation would require broad deletion of old provider methods;
- the implementation would touch rewind identity, slot-handle identity, or
  child lifecycle ownership beyond the adapter-only scope;
- the worktree baseline tests fail before new implementation work begins;
- repository integration requires rewriting published history or bypassing
  policy hooks.

Each orchestration stage must produce a named artifact:

- Requirements Extraction
- Phase 0 Inventory and Baseline Report
- Architecture Decision
- Feature Design
- Implementation Plan
- Integration Report
- End-to-End Review

Each artifact must include evidence: file paths, symbols, test names, commands,
and any assumptions. A stage is not green until the next agent can continue from
the artifact without additional context.

## Requirements

### Goals

- Prioritize reusable profiles for shared object and physics behavior.
- Preserve existing trace parity while moving toward clearer contracts.
- Make native disassembly-style field semantics explicit without overusing the
  term "ROM".
- Support engine-exclusive multi-sidekick scenarios across S1, S2, and S3K.
- Add guardrails that prevent new instances of common mistakes.
- Enable future object implementations to declare behavior profiles instead of
  copying local collision/touch/lifecycle code.

### Non-Goals

- Do not perform a broad behavior rewrite in one pass.
- Do not remove existing provider methods until compatibility profiles prove
  equivalent behavior.
- Do not force all objects into one generic model. Bosses, cutscenes, and
  bespoke control objects will keep escape hatches.
- Do not treat engine multi-sidekick behavior as native game parity. It is a
  deliberate OpenGGF extension that must still be mechanically coherent.

### Constraints

- Per-game differences must continue to flow through `PhysicsFeatureSet`,
  profile data, or game/module construction, not game-name branches in physics
  or object code.
- Object code must continue to use `ObjectServices` rather than singletons.
- Trace replay fixtures are diagnostic input only; engine state must not be
  hydrated from traces to make tests pass.
- Existing object rewinding and identity behavior must not be broken by slot or
  lifecycle refactors.

### Acceptance Criteria

- New profiles exist as compatibility adapters with tests proving they map to
  current behavior.
- Phase 0 inventory artifacts exist before any hard-fail guardrail or profile
  integration work is merged.
- New object implementation can choose solid, touch, lifecycle, participation,
  and native-position behavior through named contracts.
- Static guard tests fail on new high-risk patterns unless explicitly justified.
- First migrations are small, trace-safe, and reversible.
- The implementation plan has clear task ownership, disjoint file ownership
  where possible, explicit dependencies, and verification commands.
- Behavior-changing object migrations do not start until the compatibility
  adapters and baseline guardrails are green.

## Core Design Decisions

### 1. Use "Native" Naming, Not "ROM" Naming

Avoid names like `RomPositionOps` or `romWriteXPos`. The behavior being encoded
is not "ROM semantics" in the abstract. It is the semantics of native 68k
object/player fields as inferred from the disassembly, such as word writes to
`x_pos(a0)` or `y_pos(a1)` preserving the low-word subpixel field.

Recommended naming:

- `NativePositionOps`
- `NativeFieldOps`
- `NativeMotionOps`

Preferred initial name: `NativePositionOps`.

Decision: use `NativePositionOps` for the first implementation pass. Document
"native" at the type and package level as "Mega Drive / 68k disassembly field
semantics", not platform-native Java/JNI behavior. If the name proves ambiguous
in review, the fallback should be a more explicit variant such as
`Native68kPositionOps`, not a `Rom*` name.

Example target API:

```java
NativePositionOps.writeXPosPreserveSubpixel(player, x);
NativePositionOps.writeYPosPreserveSubpixel(player, y);
NativePositionOps.addXPosPreserveSubpixel(player, dx);
NativePositionOps.addYPosPreserveSubpixel(player, dy);
NativePositionOps.writeXPosResetSubpixel(player, x);
NativePositionOps.writeYPosResetSubpixel(player, y);
```

The naming should document intent:

- `x_pos` / `y_pos` map to engine centre coordinates.
- preserve-subpixel methods mirror native word writes.
- reset-subpixel methods are explicit hard snaps.
- top-left `setX` / `setY` remains available for bounds/render-space code but
  should not be the default in disassembly-derived object movement.

### 2. Profiles Are the Priority Foundation

The profile work should come before broad migrations. Profiles give new object
implementations a stable vocabulary and allow current behavior to be mapped
without changing semantics.

Initial profile families:

- `SolidRoutineProfile`
- `TouchResponseProfile`
- `ObjectLifecycleProfile`

Decision: profile families should live under `com.openggf.game.profiles` with a
subpackage per profile family. The initial package layout should be:

- `com.openggf.game.profiles.solidroutine`
- `com.openggf.game.profiles.touchresponse`
- `com.openggf.game.profiles.objectlifecycle`

These packages are deliberately game-layer contracts, not `level.objects`
implementation details. ObjectManager and object classes can consume the
profiles, but the profile vocabulary should remain available to module setup,
tests, future tooling, and cross-game object implementation.

These should start as adapters over existing provider methods and flags. Do not
delete existing booleans until the profile layer has characterization tests and
some migrations behind it.

Because `SolidObjectProvider` currently contains many specific compatibility
hooks, first-pass profiles must not pretend to fully replace it. The first
implementation should use a compatibility profile that can delegate unknown or
unmodeled behavior back to the existing provider methods. This keeps the profile
vocabulary useful for new code while preventing accidental behavior changes from
an incomplete record shape.

Required first-pass profile rule:

- If a profile kind maps cleanly to a known helper routine, expose named fields
  for that helper's stable semantics.
- If a provider method has no stable profile field yet, keep the provider method
  as the source of truth and document the gap in the Phase 0 inventory.
- Mapping tests should prove that the adapter returns the same decisions as the
  current provider methods for representative objects. They should not claim the
  profile model is complete until all provider hooks used by that object family
  are represented or intentionally delegated.

### 3. Multi-Sidekick Support Is Engine Behavior

Native games support either solo play or specific two-player/main-plus-Tails
arrangements. Sonic 1 has no sidekick system at all. OpenGGF extends this with
arbitrary sidekick teams.

The engine therefore needs two explicit concepts:

- Native player slots: what the original routine knew about, usually P1 and
  optional P2/Tails.
- Engine participants: all active playable entities in OpenGGF, including
  arbitrary sidekicks.

Object code should not decide this informally by using either focused sprite or
`services().sidekicks()`. It should declare a participation policy.

Recommended initial enum:

```java
enum PlayerParticipationPolicy {
    MAIN_ONLY_NATIVE,
    NATIVE_P1_P2,
    ALL_ENGINE_PLAYERS,
    NEAREST_ENGINE_PLAYER,
    MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED
}
```

Default guidance:

- New route-critical interactive objects should usually use
  `ALL_ENGINE_PLAYERS` or `MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED`.
- Routines proven P1-only should declare `MAIN_ONLY_NATIVE` with a comment or
  test.
- Routines whose native logic has a P2/Tails slot but engine behavior should
  extend to all sidekicks should use the extended policy.
- If only the closest actor matters, use `NEAREST_ENGINE_PLAYER`.

Policy implications:

- `MAIN_ONLY_NATIVE`: Use when the native routine is truly P1-only, or when
  allowing sidekicks would change the mechanic rather than extend it. This has
  the lowest native-parity risk and the highest engine-sidekick exclusion risk.
  It should require an explicit comment or test because it means sidekicks do
  not participate in that object behavior.
- `NATIVE_P1_P2`: Use when a routine genuinely models the original P1 plus
  native P2/Tails slot and extra engine sidekicks must not be treated as
  equivalent. This preserves two-slot parity, but it is a poor default for
  OpenGGF because sidekick 2+ will silently miss the interaction.
- `ALL_ENGINE_PLAYERS`: Use for independent physical interactions where every
  active player should receive the same object behavior: springs, monitors,
  hazards, badnik contact, simple platforms, bumpers, rings, and item pickups.
  This is usually the right engine-faithful behavior, but native P1/P2 state
  bits may need to become per-actor state maps rather than two hardcoded bits.
- `NEAREST_ENGINE_PLAYER`: Use for activation or AI targeting when exactly one
  actor should drive behavior. This prevents every sidekick from repeatedly
  triggering a one-target routine, but it needs tests because native routines
  may have fixed P1-first or P2-aware priority instead of nearest-actor logic.
- `MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED`: Use when native logic has
  a P2/Tails path and OpenGGF should extend that sidekick intent to every
  engine sidekick. This is likely the most important policy for the arbitrary
  sidekick feature. It preserves the original intent that sidekicks can interact
  while avoiding a hard stop at the first sidekick slot. It also implies
  per-sidekick latches for contacts, riding, cooldowns, and one-shot triggers.

Recommended enforcement:

- Interactive object profiles should declare a participation policy explicitly.
- If there is no proven P1-only reason, simple independent contact should
  default to `ALL_ENGINE_PLAYERS`.
- If the native routine has P1/P2 branches and the sidekick behavior is
  mechanically valid for more than one sidekick, default to
  `MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED`.
- Avoid silent fallback to `NATIVE_P1_P2`; that policy is correct only when the
  native two-slot limit is part of the intended behavior.

## Proposed Architecture

### Profile Package Layout

Profile contracts should be added under `com.openggf.game.profiles`:

```text
com.openggf.game.profiles.solidroutine
com.openggf.game.profiles.touchresponse
com.openggf.game.profiles.objectlifecycle
```

Each package should contain the public profile type, supporting enums/records,
and small adapter helpers where needed. ObjectManager-specific execution code
should remain in `level.objects`; the profile packages should describe behavior,
not own the collision pipeline.

### SolidRoutineProfile

Purpose: bundle native solid helper behavior that is currently spread across
`SolidObjectProvider` booleans and `ObjectManager.SolidContacts` conditionals.

Candidate enum/record shape:

```java
public enum SolidRoutineKind {
    S1_SOLID_OBJECT,
    S1_PLATFORM_OBJECT,
    S1_SOLID_OBJECT_2F,
    S2_SOLID_OBJECT,
    S2_PLATFORM_OBJECT,
    S2_SOLID_ALWAYS_SINGLE,
    S2_SLOPED_SOLID_SINGLE,
    S3K_SOLID_FULL_1P,
    S3K_SOLID_FULL2_1P,
    S3K_SOLID_TOP_1P,
    MONITOR_S1,
    MONITOR_S2,
    MONITOR_S3K,
    CUSTOM
}
```

`CUSTOM` means the object remains provider-driven. It is not a loophole for
unreviewed new behavior. New `CUSTOM` use must include a local comment or test
explaining why the known helper kinds do not fit.

```java
public record SolidRoutineProfile(
        SolidRoutineKind kind,
        boolean delegatesUnsupportedProviderHooks,
        boolean topSolidOnly,
        boolean monitorSolidity,
        int monitorVerticalOffset,
        boolean inclusiveRightEdge,
        boolean usesPlatformLandingSnap,
        boolean usesCollisionHalfWidthForTopLanding,
        boolean usesGroundHalfHeightForTopSolidContact,
        boolean bypassesOffscreenSolidGate,
        boolean allowsObjectControlledSolidContacts,
        SlopedSolidRoutineProfile sloped
) {}
```

The exact fields should be adjusted during implementation, but the direction is
to bundle coherent native helper modes instead of making objects combine many
independent booleans manually.

Compatibility behavior:

- `SolidRoutineProfile.fromProvider(SolidObjectProvider provider)` or an
  equivalent adapter helper should be the first integration point.
- `ObjectManager.SolidContacts` may consult profile fields only where the field
  is semantically complete. For delegated fields, it must continue to call the
  existing provider method.
- The first implementation must not remove or weaken existing provider methods.

Initial integration:

- Add `default SolidRoutineProfile solidRoutineProfile()` to
  `SolidObjectProvider`.
- Implement it as a compatibility mapping from current provider methods.
- Teach `ObjectManager.SolidContacts` to consult the profile but preserve
  existing method fallbacks.
- Add characterization tests proving common provider configurations map to the
  same behavior.

First migrations:

- low-risk flat/top-only platforms
- monitors
- springs
- bridges
- high-churn S2/S3K route blockers

High-risk migrations:

- springboards
- sloped/multi-piece platforms
- staircases/segmented solids
- bosses and scripted control solids

### SlopedSolidRoutineProfile

Purpose: centralize the recurring sloped-object pitfalls:

- slope baseline
- new landing vs already riding sampling
- catch-window behavior
- fast-path standing persistence
- slope table coordinate interpretation

Candidate:

```java
public record SlopedSolidRoutineProfile(
        int slopeBaseline,
        boolean usesSlopeForNewLanding,
        boolean usesGroundedStandingCatchWindow,
        boolean slopeSamplesAreAbsoluteOffsets,
        boolean suppressesSlopeSampleOnTransitionFrame
) {}
```

This should initially wrap `SlopedSolidProvider` defaults.

As with `SolidRoutineProfile`, the first `SlopedSolidRoutineProfile` must be an
adapter over `SlopedSolidProvider`. Existing methods such as
`addsSlopeCatchRangeToVerticalOverlap()` and `getSlopeBaseline()` remain
authoritative until their semantics are represented in named profile fields.

### TouchResponseProfile and TouchAttackOutcome

Purpose: make touch dispatch, bounce, hurt, and custom special behavior explicit.

Existing pain points:

- main-player and sidekick touch paths duplicate dispatch logic
- single-region and multi-region behavior diverge
- destroyed-object gating is implicit
- custom radial bounce can accidentally receive standard enemy bounce too
- monitors and bumpers duplicate game-specific eligibility and effect code

Candidate:

```java
public enum TouchAttackOutcome {
    IGNORED,
    DAMAGED_TARGET,
    DESTROYED_TARGET,
    CUSTOM_BOUNCE_APPLIED,
    LISTENER_ONLY
}
```

```java
public enum TouchSpecialPropertyMode {
    NONE,
    S2_COLLISION_PROPERTY,
    S3K_COLLISION_PROPERTY,
    CUSTOM
}
```

```java
public enum AttackBouncePolicy {
    STANDARD_ENEMY_KILL,
    BOSS_REFLECT,
    CUSTOM_HANDLED,
    NONE
}
```

```java
public record TouchResponseProfile(
        TouchSpecialPropertyMode specialPropertyMode,
        AttackBouncePolicy attackBouncePolicy,
        boolean continuousEnemyDispatch,
        boolean destroyedObjectsRemainTouchable,
        boolean appliesShieldDeflect,
        boolean stopAfterFirstActorHit
) {}
```

Introduce an actor-aware internal context:

```java
record TouchActor(PlayableEntity player, ActorKind kind, int frame) {}
record TouchContext(TouchActor actor, ObjectInstance object,
                    TouchResponseResult result, int regionIndex) {}
```

Do not start by changing behavior. First, route the existing main and sidekick
paths through one internal helper with compatibility defaults.

First migrations:

- multi-region parity fixes
- bumpers and special-touch objects
- monitors
- Crawl and other custom-bounce badniks
- direct hurt-on-solid hazards

Bosses and miniboss children should migrate last.

### ObjectLifecycleProfile

Purpose: replace independent lifecycle toggles and ad hoc object-local unload
patterns with declared lifecycle modes.

Candidate:

```java
public record ObjectLifecycleProfile(
        ObjectSlotLayout slotLayout,
        PlacementMode placementMode,
        RespawnLatchPolicy respawnLatchPolicy,
        VerticalSpawnGateMode verticalSpawnGateMode,
        SlotLimitPolicy slotLimitPolicy,
        OutOfRangeStrategy defaultOutOfRangeStrategy
) {}
```

Candidate strategy enums:

```java
enum OutOfRangeStrategy {
    STANDARD_MARK_OBJ_GONE,
    S1_REMEMBER_STATE,
    DELETE_BEHIND_SCREEN,
    CHK_OBJECT_VISIBLE,
    CUSTOM_BOUNDS,
    PERSISTENT_SELF_MANAGED
}
```

Add `ObjectLifetimeOps` as the object-facing API:

```java
interface ObjectLifetimeOps {
    void destroyLatched(ObjectInstance object);
    void destroyRespawnableOffscreen(ObjectInstance object);
    void deleteNoRespawn(ObjectInstance object);
    void expireDynamic(ObjectInstance object);
    void transferSlotTo(ObjectInstance from, ObjectInstance to);
}
```

Initial integration:

- Build lifecycle profiles from the Phase 0 lifecycle inventory, not from a
  guess at one central owner. The current behavior is spread across
  `ObjectManager` placement/unload handling, `DestructionEffects`, shared base
  object classes, and per-object direct calls.
- Add tests proving S1/S2/S3K profile values match current behavior.
- Migrate `DestructionEffects` and `AbstractBadnikInstance` to lifetime ops
  first.
- Leave direct methods available until static guards are hard-failing new direct
  lifecycle calls, with baselines only for documented legacy exceptions.

High-risk work deferred:

- slot handle identity rewrite
- rewind/identity integration
- child-slot lifecycle consolidation

Required Phase 0 lifecycle inventory categories:

- respawn-latched static object destruction;
- dynamic child/object expiry with no respawn latch;
- offscreen object deletion that allows respawn;
- offscreen or route-passed deletion that permanently remembers the spawn;
- parent/child slot transfer or child cleanup;
- persistent self-managed objects that should not use default range deletion.

The first lifecycle profile pass should cover categories, not every object.
If a direct lifecycle call does not fit a category, baseline it with a reason
code and leave behavior unchanged.

### ObjectPlayerQuery and Participation Policy

Purpose: make object participation explicit and sidekick behavior robust.

Candidate API exposed from `ObjectServices`:

```java
interface ObjectPlayerQuery {
    PlayableEntity mainPlayer();
    List<PlayableEntity> sidekicks();
    List<PlayableEntity> allPlayers();
    List<PlayableEntity> nativeP1P2();
    List<PlayableEntity> playersFor(PlayerParticipationPolicy policy);
    Optional<PlayableEntity> nearestPlayerTo(int centreX, int centreY,
                                             PlayerParticipationPolicy policy);
}
```

Semantics:

- `mainPlayer()` resolves through the active `SpriteManager` focus/main playable
  entity. If no playable main entity is available, it should fail explicitly in
  tests or return the same null/empty behavior already used by the caller; do
  not silently substitute the first sidekick.
- `nativeP1P2()` means main plus first sidekick where the native routine has a
  P2/Tails slot.
- `allPlayers()` means engine behavior over all active sidekicks.
- `playersFor(policy)` centralizes the policy decision.
- `firstRomSidekick()` may be added if needed, but the name must be explicit
  enough to discourage accidental use.

Static guard:

- flag `services().sidekicks().getFirst()` unless accompanied by a
  `native-p2-only` or equivalent justification.
- flag collision/trigger methods that inspect only focused sprite unless they
  declare `MAIN_ONLY_NATIVE`.

Compatibility rule:

- Keep `ObjectServices.sidekicks()` initially. Add `ObjectServices.players()` or
  `ObjectServices.playerQuery()` as the new preferred API. Do not remove raw
  sidekick access until object migrations and guard baselines prove the new
  participation path covers the known cases.

### ObjectControlState

Purpose: model native object-control bits and engine-derived predicates without
conflating movement, CPU, touch, and solid-contact suppression.

Candidate:

```java
public final class ObjectControlState {
    public static ObjectControlState none();
    public static ObjectControlState nativeBit0();
    public static ObjectControlState nativeBits0And6();
    public static ObjectControlState nativeBit7();

    public boolean active();
    public boolean suppressesMovement();
    public boolean allowsCpuNormal();
    public boolean suppressesTouchResponse();
    public boolean allowsSolidContact(ObjectInstance candidate);
}
```

Compatibility:

- Keep `setObjectControlled`, `setObjectControlAllowsCpu`, and
  `setObjectControlSuppressesMovement` initially.
- Add typed wrappers that set those fields in known combinations.
- Static guard new direct calls to `setObjectControlled(true)` outside approved
  wrappers or allowlisted legacy code.

### NativePositionOps

Purpose: encode center-coordinate and subpixel intent at call sites.

Candidate package:

- `com.openggf.sprites` if operations are sprite-general
- `com.openggf.game.nativeops` only if a broader native-helper package is
  introduced
- avoid `com.openggf.rom` naming

Initial scope:

- `AbstractPlayableSprite` position writes first, because preserve-subpixel
  operations require APIs that are not currently present on `PlayableEntity`.
- object-to-player position nudges
- preserve/reset subpixel tests

Do not make this too broad initially. It should be a small helper layer plus
guardrails, not a new abstraction for every movement operation.

Target type rule:

- First implementation should expose overloads for `AbstractPlayableSprite`.
- If object code only has a `PlayableEntity`, it may call `NativePositionOps`
  only after an `instanceof AbstractPlayableSprite` check or through a helper
  that explicitly documents fallback behavior.
- Do not add broad subpixel mutation methods to `PlayableEntity` in the first
  pass unless the implementation plan proves every implementation can support
  them safely.

## Guardrail Plan

Decision direction: guardrails should hard-fail. If current code is already
clean for a rule, add the rule as a normal hard failure immediately. If existing
violations are found, use an explicit baseline allowlist with reason codes and
hard-fail anything not in that baseline. This is still hard-fail enforcement for
new violations; it is not a warning-only mode.

Rationale for baseline/no-new-growth as an option:

- Some high-risk patterns may already exist in legitimate transitional code.
  Making the first guardrail PR fail on the entire historical backlog can turn a
  prevention mechanism into a broad refactor before the replacement profiles are
  ready.
- A baseline allowlist preserves momentum while preventing additional debt. New
  call sites fail immediately unless they use the new abstraction or add an
  explicit reviewed reason.
- The baseline becomes a burn-down list for the systematic sweep. Each migration
  removes entries rather than relying on memory or informal code review.

For this project, prefer hard fail first. Fall back to a baseline only when the
initial scan shows enough existing violations that fixing them would be larger
than the profile/scaffold work itself.

Phase 0 must run the initial scan before Work Package 4 is implemented. The
expected outcome is not "zero historical violations"; it is a reviewed baseline
that turns every non-baselined future occurrence into a hard failure. Guard
tests should be designed as no-new-growth gates from the first PR.

Baseline entries must contain:

- source file path;
- line or stable source fragment;
- violation kind;
- reason code;
- short human-readable justification;
- optional target abstraction that should eventually replace it.

Allowed reason codes:

- `LEGACY_PROVIDER_COMPAT`
- `TOP_LEFT_RENDER_SPACE`
- `SPAWN_SNAP`
- `CHECKPOINT_RESTORE`
- `CUTSCENE_SCRIPT`
- `BOUNDARY_CLAMP`
- `NATIVE_P2_ONLY`
- `MAIN_PLAYER_ONLY`
- `DYNAMIC_CHILD_LIFETIME`
- `PERSISTENT_SELF_MANAGED`
- `BOSS_OR_CUTSCENE_ESCAPE_HATCH`
- `PENDING_PARITY_TRIAGE`

New baseline entries added after Phase 0 should be treated as review-sensitive.
Agents may add them only when the change preserves existing behavior and the
justification names the future migration path.

### Coordinate and Subpixel Guard

Flag new high-risk uses of:

- `player.setX(...)`
- `player.setY(...)`
- `player.setCentreX(...)`
- `player.setCentreY(...)`

when used in object/player parity-sensitive paths and not routed through
`NativePositionOps` or justified as top-left/render-space/cutscene snap.

Allowed reasons should be explicit:

- `TOP_LEFT_RENDER_SPACE`
- `SPAWN_SNAP`
- `CHECKPOINT_RESTORE`
- `CUTSCENE_SCRIPT`
- `BOUNDARY_CLAMP`
- `PENDING_PARITY_TRIAGE`

### Sidekick Participation Guard

Flag:

- `services().sidekicks().getFirst()`
- focused-sprite-only trigger/check/collision methods
- object code that has collision/activation/bounce logic but no declared
  participation policy

Allow:

- `native-p2-only: reason`
- `main-player-only: reason`
- render/debug/camera-only code

### Lifecycle Guard

Flag object-local offscreen/range code that calls:

- `setDestroyed(true)`
- `markRemembered`
- `removeFromActiveSpawns`
- manual slot transfer

unless it goes through `ObjectLifetimeOps` or is in a baseline allowlist.

### Feature Flag Guard

Tighten package-specific rules:

- no `GameId`
- no `getGameId()`
- no `GameModuleRegistry`

inside physics, playable movement, object contact, and touch dispatch code,
except module/profile construction paths.

## Phased Implementation Plan

### Phase 0: Characterization and Baseline

Goal: prove current behavior before adding new abstractions.

Tasks:

- inventory current `SolidObjectProvider` boolean combinations
- inventory `TouchResponseProvider` special cases
- inventory lifecycle escape hatches
- inventory direct position writes
- add baseline data for scanner tests
- inventory raw sidekick access and focused-player-only object participation
- inventory object-control setter combinations
- produce the Phase 0 Inventory and Baseline Report

Tests:

- no behavior changes yet
- scanner tests may pass with current baseline
- focused characterization tests for common profile candidates
- no hard-fail guard may be introduced without either zero violations or an
  explicit baseline

Verification:

```powershell
mvn -Dmse=off -DskipTests compile
mvn test -Dtest=TestArchitecturalSourceGuard,TestObjectServicesMigrationGuard,TestNoServicesInObjectConstructors
```

Phase 0 exit criteria:

- every proposed first-pass guard has either no existing violations or a
  baseline format ready for implementation;
- every first-pass profile family has a list of provider methods it represents
  and a list of methods it delegates;
- the implementation plan identifies which work packages can run in parallel
  after Phase 0 and which must wait for a dependency;
- no behavior-changing object migration is included.

### Phase 1: Profile Adapters

Goal: introduce profile types with compatibility defaults.

Tasks:

- add `SolidRoutineProfile` and default `SolidObjectProvider.solidRoutineProfile()`
- add `SlopedSolidRoutineProfile` and adapter from `SlopedSolidProvider`
- add `TouchResponseProfile` and `TouchAttackOutcome` compatibility defaults
- add `ObjectLifecycleProfile` populated from Phase 0 lifecycle categories
- add tests proving delegated profile gaps still call current provider methods

Do not migrate many objects yet.

Tests:

- profile mapping tests for S1/S2/S3K representative objects
- no behavior change tests for solid contact and touch dispatch

Verification:

```powershell
mvn test -Dtest=TestSolidObjectManager,TestSolidExecutionRegistry,TestSolidOrderingSentinelsHeadless,TestSolidObjectTopBranchUpwardLift
mvn test -Dtest=TestPhysicsProfile,TestHybridPhysicsFeatureSet
```

### Phase 2: Participation and Native Position Foundations

Goal: make player participation and native field write intent explicit.

Tasks:

- add `PlayerParticipationPolicy`
- add `ObjectPlayerQuery` through `ObjectServices`
- add `NativePositionOps`
- add source guards as hard-fail rules, using explicit baselines only where the
  initial scan proves current violations are too broad for the same PR
- do not migrate broad object behavior; production usage should be limited to
  one low-risk caller or none

Tests:

- all-player vs native-P1/P2 query tests
- native position preserve/reset subpixel tests
- scanner tests for new direct position writes and sidekick-blind checks
- tests for missing main-player handling and multiple-sidekick ordering

Verification:

```powershell
mvn test -Dtest=TestObjectServices,TestObjectServicesConstructionContext
mvn test -Dtest=TestCoordinateSemanticsGuard,TestSidekickCoverageGuard
```

### Phase 3: Object-Control State Foundation

Goal: replace ambiguous object-control setter combinations with typed intent.

Tasks:

- add `ObjectControlState` wrappers
- map existing booleans without changing storage yet
- add matrix tests for movement/touch/solid/CPU predicates
- migrate `SidekickCpuController` and a small number of object-control users
  only after tests are in place
- add guard baseline for direct `setObjectControlled(true)` calls before
  hard-failing new direct calls

Verification:

```powershell
mvn test -Dtest=TestPlayableSpriteMovement,TestSidekickCpuController*,TestSidekickCpuDespawnParity
```

### Phase 4: Low-Risk Profile Migrations

Goal: start using the profiles in real object families.

Suggested order:

1. flat/top-only platforms
2. monitors
3. simple springs
4. simple badnik touch profiles
5. `DestructionEffects` and `AbstractBadnikInstance` lifecycle ops

Rules:

- one object family per PR
- characterization test first
- trace replay targeted to the affected game/zone

Verification examples:

```powershell
mvn test -Dtest=TestMonitorObjectInstance,TestSonic3kMonitorObjectInstance,TestMonitorIconTiming
mvn test -Dtest=TestSpringObjectInstance,TestSonic3kSpringObjectInstance
mvn test -Dtest=TestTouchResponseManager,TestCNZObjectBugs
```

### Phase 5: High-Impact Route Migrations

Goal: apply standard profiles to known trace-frontier bug families.

Targets:

- S2 CNZ bumpers/flippers/bonus blocks/Crawl
- S2 HTZ/MTZ platforms and seesaws
- S3K AIZ/CNZ object-control carriers
- S3K springs/cylinders/cages/cannons/tubes

Verification:

Run focused trace replay tests for every migrated route. Update
`docs/status/trace-frontier-log.md` only when frontiers actually move or regressions
are discovered.

### Phase 6: High-Risk Consolidation

Deferred until profile usage is established:

- multi-piece solid profile priority
- slot identity/latch rewrite
- boss/miniboss touch profile migration
- broad removal of old provider booleans
- object lifecycle slot handle integration with rewind identity

## First Work Packages for the Next Session

These packages are the first executable units for an automated orchestration
pipeline. Phase 0 is mandatory and must complete before the other packages are
allowed to edit production code. After Phase 0, Work Packages 1, 2, 3, and 4
may run in parallel only where file ownership is disjoint and each worker has
the Phase 0 artifact.

### Work Package 0: Inventory and Baseline

Ownership:

- `docs/architecture/research/2026-05-20-object-physics-standardization-phase0.md`
- scanner baseline files or test-local allowlists if created
- characterization tests only where needed to freeze existing behavior

Deliverable:

- Phase 0 Inventory and Baseline Report
- provider-method inventory for `SolidObjectProvider`, `SlopedSolidProvider`,
  and `TouchResponseProvider`
- lifecycle escape-hatch inventory with reason codes
- direct position-write inventory with reason codes
- raw sidekick/focused-player access inventory with reason codes
- object-control setter combination inventory
- guard baseline schema, even if some guards have zero baseline entries

Do not:

- introduce behavior changes
- migrate objects
- remove provider methods
- hard-fail a guard without either zero current violations or an explicit
  baseline

### Work Package 1: Profile Type Scaffolding

Dependency:

- Work Package 0 must be complete.

Ownership:

- `src/main/java/com/openggf/game/profiles/solidroutine/SolidRoutineProfile.java`
- `src/main/java/com/openggf/game/profiles/solidroutine/SlopedSolidRoutineProfile.java`
- `src/main/java/com/openggf/game/profiles/touchresponse/TouchResponseProfile.java`
- `src/main/java/com/openggf/game/profiles/objectlifecycle/ObjectLifecycleProfile.java`
- provider interfaces only as needed

Deliverable:

- profile records/enums
- default adapter methods
- delegated compatibility mode for unmodeled provider hooks
- mapping tests proving current behavior is represented or delegated

Do not:

- rewrite `ObjectManager.SolidContacts`
- migrate many objects
- remove existing provider methods

### Work Package 2: Native Position Foundation

Dependency:

- Work Package 0 must be complete.

Ownership:

- `src/main/java/com/openggf/sprites/NativePositionOps.java`
- tests under `src/test/java/com/openggf/sprites`
- scanner baseline if needed

Deliverable:

- preserve/reset subpixel helper tests for `AbstractPlayableSprite`
- minimal helper usage in one low-risk caller or no production usage yet
- documented fallback rule for `PlayableEntity` callers

Do not:

- mechanically replace all `setX` / `setY` calls
- add broad new mutation methods to `PlayableEntity` unless the implementation
  plan proves every implementation can support them safely

### Work Package 3: Player Participation API

Dependency:

- Work Package 0 must be complete.

Ownership:

- `ObjectServices`
- `DefaultObjectServices`
- new `ObjectPlayerQuery`
- tests in `src/test/java/com/openggf/level/objects`

Deliverable:

- explicit query methods for main/native P1-P2/all participants
- tests covering S1-with-donated-sidekick, S2, S3K, and multiple sidekicks
- missing-main-player behavior defined and tested
- raw `sidekicks()` retained for compatibility

Do not:

- migrate all object code immediately

### Work Package 4: Guardrails

Dependency:

- Work Package 0 must be complete.

Ownership:

- `ObjectGuardSourceScanner`
- `TestCoordinateSemanticsGuard`
- `TestSidekickCoverageGuard`
- `TestPhysicsFeatureFlagGuard`
- lifecycle guard if small enough

Deliverable:

- hard-fail tests
- explicit baseline allowlists with reason codes from Work Package 0
- clear suppression comments for intentional exceptions

Do not:

- block profile scaffolding on a broad historical cleanup if a narrow baseline
  can hard-fail all new violations
- convert a historical backlog into same-PR cleanup work

### Work Package 5: Touch Dispatch Compatibility Refactor

Dependency:

- Work Packages 0 and 1 must be complete.

Ownership:

- `ObjectManager.TouchResponses`
- `TouchResponseProfile`
- focused touch tests

Deliverable:

- one actor-aware internal dispatch helper used by main and sidekick paths
- single-region and multi-region parity tests
- no intended behavior change

Do not:

- migrate bosses or monitors in the same pass

## Risk Register

### Trace Churn

Risk: profile migrations subtly change ordering or state resets.

Mitigation:

- adapters first
- tests before migrations
- one object family per PR
- focused trace replay per affected zone

### Over-Abstraction

Risk: profiles become another layer of booleans without simplifying object code.

Mitigation:

- profiles must represent named native helper routines or engine participation
  policies
- avoid adding fields that have only one user unless that user is a known
  native helper category

### Multi-Sidekick Semantics Drift

Risk: extending native P2 logic to all sidekicks may create non-native behavior
that feels arbitrary.

Mitigation:

- declare participation policy at object/profile level
- test all-player behavior explicitly
- document when behavior is engine extension, not native parity

### Naming Confusion

Risk: "native" could be confused with platform-native Java/JNI.

Mitigation:

- document in class Javadocs that "native" means Mega Drive / 68k disassembly
  field semantics
- consider `Native68kPositionOps` if ambiguity becomes a problem

### Rewind and Slot Identity

Risk: lifecycle and slot profile work can break rewind identity and object
state capture.

Mitigation:

- defer slot handle rewrite
- keep `ObjectLifecycleProfile` as adapter first
- add slot transfer tests before touching identity

## Explicit Deferrals

- Removing old provider booleans.
- Rewriting all object position writes.
- Replacing `PhysicsFeatureSet` with nested records.
- Slot identity/latch rewrite.
- Boss/miniboss profile migration.
- Full trace sweep.

## Automated Execution Sequence

The intended one-shot orchestration flow is:

1. Prepare repository basis: merge local work with `origin/develop`, push the
   integrated branch, create a feature worktree from that pushed branch.
2. Run baseline verification in the worktree. If it fails before new edits,
   stop and ask the user whether to fix baseline or continue with known
   failures.
3. Dispatch Phase 0 inventory agents in parallel by domain:
   solid/sloped providers, touch response, lifecycle, position writes,
   sidekick participation, object-control setters, and existing guard tests.
4. Synthesize the Phase 0 Inventory and Baseline Report locally. Resolve
   conflicts by inspecting code; do not vote between agents.
5. Dispatch Work Packages 1, 2, 3, and 4 only after Phase 0 is green. Workers
   must have disjoint file ownership and must not revert each other's edits.
6. Integrate package outputs in dependency order: profile scaffolding and
   native/player APIs first, guard tests after their baseline files exist,
   touch dispatch compatibility after touch profiles exist.
7. Run focused verification after each integrated package and the full agreed
   verification set before review.
8. Dispatch independent end-to-end reviewers. Fix blockers. Surface non-blocking
   deferrals in the Integration Report.
9. Stop for human review before merge to `develop`.

Automated execution should favor small commits per package. Commit messages on
non-`master` branches must satisfy the repository trailer policy. Do not use
`--no-verify`.

## Human Escalation Points

Ask the user before proceeding when:

- a worker proposes a gameplay behavior change rather than an adapter or guard;
- a guard baseline would be so broad that it no longer prevents new violations;
- a profile field needs a naming decision not covered by this document;
- trace replay evidence conflicts with existing unit tests;
- a package requires changing rewind identity, slot identity, or child lifecycle
  ownership;
- the branch cannot be reconciled with `origin/develop` without non-trivial
  conflict resolution.

## Recommended Starting Point

Start the next session with Work Package 0. It is the gate that lets the rest of
the pipeline run with limited user interaction.

After Work Package 0 is green, run these in parallel where possible:

- Work Package 1: profile scaffolding
- Work Package 2: native position foundation
- Work Package 3: player participation API
- Work Package 4: guardrails using the Phase 0 baselines

Then run Work Package 5 once touch profiles exist. Only after those foundations
are green should the migration campaign begin. The migration campaign should be
a systematic codebase sweep, not a one-off object cleanup. Monitors are a good
first behavior-changing family because they exercise solidity, touch response,
item/lifecycle behavior, and cross-game differences without starting on the most
complex moving solids.

The first behavior-changing migration should be small and measurable, such as a
monitor or spring profile conversion with existing focused tests. Avoid choosing
springboard, multi-piece solids, or boss children as the first migration.
