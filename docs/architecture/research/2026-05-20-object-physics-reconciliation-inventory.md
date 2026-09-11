# Object Physics Reconciliation Inventory

Date: 2026-05-20
Branch: `feature/ai-object-physics-standardization`

## Purpose

This is the Phase 0 handoff for reconciling existing object code with the object
physics standardization seams already present on this branch. It inventories
legacy direct object-control, player-participation, lifecycle, solid-provider,
and touch-provider patterns so implementation agents can migrate narrowly and
ratchet guard baselines without changing gameplay behavior by accident.

The local blueprint specs under `docs/architecture/designs/` remain untracked
planning input. This research artifact is the committed worker handoff.

## Rules For All Slices

- Run `TestObjectPhysicsStandardizationGuard` before and after each slice.
- If a fragment is migrated, remove or reduce the matching `BASELINE` entry in
  `TestObjectPhysicsStandardizationGuard` in the same change.
- Do not grow a guard baseline without user approval.
- Add or run the focused tests named below before migrating a target.
- If a migrated object appears in a trace fixture, run the focused trace or the
  canonical smoke trace set and update `docs/status/trace-frontier-log.md` only if a
  trace frontier moves, regresses, or is intentionally advanced.
- Treat `BOSS_OR_CUTSCENE_ESCAPE_HATCH` and `CUTSCENE_SCRIPT` entries as
  explicit deferrals unless the exact handoff has focused parity coverage.

## Object-Control Inventory

Low-risk entries are exact `$81`-style capture/release mappings to
`ObjectControlState.nativeBit7FullControl()` and `ObjectControlState.none()`.
They still need focused characterization because many are route-impact objects.

| file | fragment | category | current_behavior | replacement_seam | risk | required_tests | trace_relevance | guard_baseline_action | reason_code |
|---|---|---|---|---|---|---|---|---|---|
| `game/sonic1/objects/Sonic1TeleporterObjectInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | Teleporter captures player with ROM `$81`, rolls/locks scripted movement, then clears control on release. | `ObjectControlState.nativeBit7FullControl().applyTo(player)` and `ObjectControlState.none().applyTo(player)`; keep explicit control-lock calls. | medium | `TestSonic1TeleporterObjectInstance`; add state-bit assertion if absent. | `TestS1Credits00Ghz1TraceReplay`, `TestS1Credits07Ghz1bTraceReplay` if touched by credits/teleporter path. | reduce true/false entries after migration. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/LauncherBallObjectInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | CNZ launcher captures player into a scripted path and releases through normal/emergency exits. | `ObjectControlState.nativeBit7FullControl()` / `none()`. | medium | Add or run launcher capture, final release, and emergency release tests. | `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay`. | reduce true entry and false count. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/LauncherSpringObjectInstance.java` | `player.setObjectControlled(true);` | direct object-control setter | Explicit ROM `$81` capture; skips movement/physics while preserving pinball state. | `ObjectControlState.nativeBit7FullControl()`. | medium | `TestLauncherSpringObjectInstance`; add release-state assertion if absent. | `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay`. | remove true entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/OOZLauncherObjectInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | OOZ launcher captures player with ROM `$81` and releases on exit/offscreen cleanup. | `ObjectControlState.nativeBit7FullControl()` / `none()`. | medium | Add focused OOZ launcher capture/offscreen release tests. | `TestS2OozLevelSelectTraceReplay`, `TestS2Ooz2LevelSelectTraceReplay`. | remove or reduce true/false entries. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/CPZSpinTubeObjectInstance.java` | `player.setObjectControlled(true);` | direct object-control setter | Tube fully owns movement with ROM `$81`. | `ObjectControlState.nativeBit7FullControl()`. | medium | Existing CPZ object regression tests; add state matrix assertion if missing. | `TestS2CpzLevelSelectTraceReplay`, `TestS2Cpz2LevelSelectTraceReplay`. | remove true entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/MTZSpinTubeObjectInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | Tube captures and clears scripted movement control. | `ObjectControlState.nativeBit7FullControl()` / `none()`. | medium | Add MTZ spin-tube capture/release test. | `TestS2MtzLevelSelectTraceReplay`, `TestS2Mtz2LevelSelectTraceReplay`, `TestS2Mtz3LevelSelectTraceReplay`. | remove both entries. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/AutomaticTunnelObjectInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | S3K tunnel captures and releases route movement. | `ObjectControlState.nativeBit7FullControl()` / `none()`. | medium | Add S3K automatic tunnel traversal/headless state test. | `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay` depending zone placement. | remove both entries. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/CnzCannonInstance.java` | `player.setObjectControlled(true);` | direct object-control setter | CNZ cannon captures player with comment-mapped `$81` state. | `ObjectControlState.nativeBit7FullControl()`. | medium | `TestCnzCannonInstance`; include directed traversal/capture state. | `TestS3kCnzTraceReplay`. | remove true entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/CnzSpiralTubeInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | CNZ spiral tube captures/releases scripted tube movement. | `ObjectControlState.nativeBit7FullControl()` / `none()`. | medium | Add CNZ spiral tube traversal state tests. | `TestS3kCnzTraceReplay`. | remove both entries. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java` | `player.setObjectControlled(true);` / `player.setObjectControlled(false);` | direct object-control setter | Bonus cage captures/releases player; low route impact. | `ObjectControlState.nativeBit7FullControl()` / `none()`. | low | `TestS3kSlotBonusCageObjectInstance`. | Low gameplay-route relevance. | remove both entries. | `PENDING_PARITY_TRIAGE` |

High-risk object-control deferrals:

| file | fragment | category | current_behavior | replacement_seam | risk | required_tests | trace_relevance | guard_baseline_action | reason_code |
|---|---|---|---|---|---|---|---|---|---|
| `game/sonic1/objects/Sonic1PoleThatBreaksObjectInstance.java` | true/false object-control setters | direct object-control setter | Comment indicates `f_playerctrl = 1`, not `$81`; current setter may over-suppress touch. | Likely `nativeBits0To6CpuAllowedMovementSuppressed()`, but disassembly must verify. | high | `TestSonic1PoleThatBreaksObjectInstance` plus touch/collision assertions. | `TestS1Credits03Lz3TraceReplay` is the exact available credits route most likely to cover LZ pole behavior; run only if the changed fixture participates. | keep until verified. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/GrabObjectInstance.java` | true/false object-control setters | direct object-control setter | ROM `obj_control = 1`; current full-control behavior may be engine approximation. | Likely `nativeBits0To6CpuAllowedMovementSuppressed()` or explicit escape hatch. | high | Add Grab capture/release P1/P2 tests; no exact focused test exists currently. | No exact trace class currently identified for this object; run canonical S2 smoke traces if a fixture match is found during implementation. | keep until verified. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/FlipperObjectInstance.java` | `setObjectControlSuppressesMovement(...)` | direct object-control setter | Movement-only suppression; clearing with `none()` would clear unrelated object ownership. | `ObjectControlState.movementSuppressedOnly()` only if exclusive ownership is proven. | high | Add flipper lock/release and pinball tests; existing CNZ headless coverage includes `TestS2Cnz1Headless`. | `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay`. | keep until narrower seam is safe. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/HCZConveyorBeltObjectInstance.java` | true/false object-control setters | direct object-control setter | Comment maps to `object_control = 3`; wrap/capture state is custom. | Likely bits-0-to-6 state, not bit 7. | high | `TestHCZConveyorBeltObjectInstance`, `TestS3kHcz1ConveyorBeltWrapRegression`, plus disassembly parity. | No exact HCZ trace replay class currently exists; use the named HCZ focused tests as the required gate. | keep until verified. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/MGZTwistingLoopObjectInstance.java` | `player.setObjectControlled(true);` | direct object-control setter | Jump-out path deliberately leaves object control true for deferred release. | Needs exact release helper or documented deferral, not naive `none()`. | high | `TestS3kMgzTwistingLoopObject`, `TestS3kMgzTwistingLoopSpindashRouteRegression`, `TestS3kMgzTopLauncherTwistingLoopRegression`. | `TestS3kMgzTraceReplay`. | keep. | `PENDING_PARITY_TRIAGE` |

Boss/cutscene escape-hatch entries remain out of scope for opportunistic burn
down:

| file | fragment | category | current_behavior | replacement_seam | risk | required_tests | trace_relevance | guard_baseline_action | reason_code |
|---|---|---|---|---|---|---|---|---|---|
| `game/sonic3k/objects/AbstractS3kFloatingEndEggCapsuleInstance.java` | `sprite.setObjectControlled(true);` | direct object-control setter | End-capsule scripted handoff controls player during transition flow. | Only migrate with a cutscene-specific `ObjectControlState` review. | escape-hatch | End-capsule/cutscene focused test. | `TestS3kAizTraceReplay` or route-specific S3K trace when covered. | keep. | `BOSS_OR_CUTSCENE_ESCAPE_HATCH` |
| `game/sonic3k/objects/bosses/CnzEndBossInstance.java` | `sprite.setObjectControlled(false);` | direct object-control setter | Boss transition release. | Cutscene/boss-specific control profile only after focused parity. | escape-hatch | CNZ boss defeat/release test. | `TestS3kCnzTraceReplay`. | keep. | `BOSS_OR_CUTSCENE_ESCAPE_HATCH` |
| `game/sonic3k/objects/bosses/HczEndBossEggCapsuleInstance.java` | `sprite.setObjectControlled(true);` | direct object-control setter | HCZ boss/capsule scripted player handoff. | Cutscene/boss-specific control profile only after focused parity. | escape-hatch | Add HCZ end-boss capsule focused test before migration. | No exact HCZ trace replay class currently exists; do not migrate from trace evidence alone. | keep. | `BOSS_OR_CUTSCENE_ESCAPE_HATCH` |
| `game/sonic3k/objects/bosses/HczEndBossGeyserCutscene.java` | `player.setObjectControlled(true);` | direct object-control setter | Geyser cutscene captures player. | Cutscene-specific state wrapper only with test. | escape-hatch | Add geyser cutscene focused test before migration. | No exact HCZ trace replay class currently exists; do not migrate from trace evidence alone. | keep. | `BOSS_OR_CUTSCENE_ESCAPE_HATCH` |
| `game/sonic3k/objects/bosses/HczEndBossWaterColumn.java` | `sprite.setObjectControlled(true/false);` | direct object-control setter | Water-column boss/cutscene carry-release. | Cutscene/boss-specific state wrapper only with test. | escape-hatch | Add water-column carry/release focused test before migration. | No exact HCZ trace replay class currently exists; do not migrate from trace evidence alone. | keep. | `BOSS_OR_CUTSCENE_ESCAPE_HATCH` |
| `game/sonic3k/objects/HczMinibossInstance.java` | `player/sidekick/sprite.setObjectControlled(...)` | direct object-control setter | Miniboss arena control and release. | Boss-specific state wrapper only after focused tests. | escape-hatch | Add HCZ miniboss player/sidekick release focused tests before migration. | No exact HCZ trace replay class currently exists; do not migrate from trace evidence alone. | keep. | `BOSS_OR_CUTSCENE_ESCAPE_HATCH` |

Cutscene-script entries are also out of opportunistic scope but use the existing
`CUTSCENE_SCRIPT` reason code rather than the boss escape-hatch code:
`AizPlaneIntroInstance`, `CutsceneKnucklesAiz1Instance`,
`IczSnowboardIntroInstance`, `S3kResultsScreenObjectInstance`,
`S3kSignpostInstance`, and `Sonic3kSSEntryRingObjectInstance`.

## Participation Inventory

Use `ObjectServices.playerQuery()` and `ObjectPlayerParticipationPolicy` rather
than raw `sidekicks.getFirst()` or direct sprite-manager sidekick access.

| file | fragment | category | current_behavior | replacement_seam | risk | required_tests | trace_relevance | guard_baseline_action | reason_code |
|---|---|---|---|---|---|---|---|---|---|
| `game/sonic2/objects/BumperObjectInstance.java` | `sidekicks.getFirst()` | raw native-P2 access | Pending collision property bit 2 applies bounce to first sidekick only. | `playerQuery().playersFor(NATIVE_P1_P2)` or `nativeP2OrNull()` preserving first-sidekick-only behavior. | low | Add/extend S2 bumper P1/P2 bounce test. | `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay` if bumper fixture is affected. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/HexBumperObjectInstance.java` | `sidekicks.getFirst()` | raw native-P2 access | Same pending bit pattern as normal bumper. | Same as bumper. | low | `TestHexBumperObjectInstance` P1/P2 bounce assertion. | `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay` if affected. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/badniks/BuzzerBadnikInstance.java` | `PlayableEntity firstSidekick = sidekicks.getFirst();` | raw native-P2 access | Alternates targeting main player and first sidekick by VBlank parity. | `ObjectPlayerQuery.nativeP2OrNull()` with main fallback. | low | `TestS2Ehz1BuzzerSpawnRegression` plus focused target-selection test. | `TestS2Ehz1TraceReplay`, `TestS2Ehz1BuzzerSpawnRegression`. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/BridgeObjectInstance.java` | `spriteManager.getSidekicks().getFirst()` | direct sprite-manager sidekick access | Bridge tracks first sidekick for log depression/standing behavior. | `ObjectPlayerQuery.nativeP2OrNull()`. | medium | `TestS2Ehz1BridgeTailsStandingRegression`; bridge headless tests. | `TestS2Ehz1TraceReplay`, `TestS2Ehz1BridgeTailsStandingRegression`. | remove entry after regression passes. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/BonusBlockObjectInstance.java` | `sidekicks.getFirst()` | raw native-P2 access | Bonus block reaction includes first sidekick when property bit requests it. | `nativeP2OrNull()` or `playersFor(NATIVE_P1_P2)` depending call site. | medium | Bonus block P1/P2 interaction tests. | `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay` if bonus-block fixture is affected. | remove entry after tests. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/OOZPoppingPlatformObjectInstance.java` | first-sidekick local variables | raw native-P2 access | Platform logic has multiple first-sidekick reads near object-control state. | `playersFor(NATIVE_P1_P2)` with per-player state review. | high | Popping platform main/sidekick lock/release tests. | `TestS2OozLevelSelectTraceReplay`, `TestS2Ooz2LevelSelectTraceReplay`. | keep until object-control slice covers it. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/AizRideVineObjectInstance.java` / `AizGiantRideVineObjectInstance.java` | first-sidekick local variables | raw native-P2 access | Vine logic stores per-player sidekick latch state. | `playersFor(NATIVE_P1_P2)` only after latch-state equivalence. | high | Existing vine handle tests plus sidekick ride/release tests. | `TestS3kAizTraceReplay`. | keep until sidekick latch tests exist. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/CnzCylinderInstance.java` | first-sidekick local variables | raw native-P2 access | Route-impact cylinder slot logic distinguishes first sidekick. | `ObjectPlayerQuery.nativeP2OrNull()` if native slot behavior is confirmed. | high | CNZ cylinder route/headless tests. | `TestS3kCnzTraceReplay`. | keep for Phase 5. | `PENDING_PARITY_TRIAGE` |

## Lifecycle Inventory

Use `ObjectLifetimeOps` for named lifecycle intent. Low-risk entries are direct
spawn remembrance or active-spawn removal with no child ownership or slot
transfer.

| file | fragment | category | current_behavior | replacement_seam | risk | required_tests | trace_relevance | guard_baseline_action | reason_code |
|---|---|---|---|---|---|---|---|---|---|
| `game/sonic1/objects/Sonic1MonitorObjectInstance.java` | `objectManager.markRemembered(spawn);` | direct lifecycle operation | Broken monitor is latched in remembered table. | `ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn)`. | low | `TestSonic1MonitorObjectInstance`, `TestMonitorIconTiming`. | `TestS1Ghz1TraceReplay`, `TestS1Mz1TraceReplay` if the touched route contains monitors. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/MonitorObjectInstance.java` | `objectManager.markRemembered(spawn);` | direct lifecycle operation | Broken monitor is remembered, then riding state is cleared. | `ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn)`; keep `clearRidingObject`. | low | `TestMonitorObjectInstance`, `TestS2Ehz1MonitorBreakRegression`. | `TestS2Ehz1TraceReplay`, `TestS2Ehz1MonitorBreakRegression`. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/Sonic3kMonitorObjectInstance.java` | `objectManager.markRemembered(spawn);` | direct lifecycle operation | Broken monitor is remembered after touch release. | `ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn)`. | low | `TestSonic3kMonitorObjectInstance`. | `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay` depending zone fixture. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic1/objects/Sonic1GiantRingObjectInstance.java` | `objectManager.markRemembered(spawn);` | direct lifecycle operation | Giant ring despawn latch prevents respawn, then child flash spawns. | `ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn)`. | low | Add focused giant-ring remember/flash test if missing. | `TestS1Ghz1TraceReplay`, `TestS1Mz1TraceReplay` if the touched route contains a giant ring. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic2/objects/SignpostObjectInstance.java` | `objMgr.markRemembered(spawn);` | direct lifecycle operation | Non-MTZ2 signpost self-remembers before destroy to avoid spawn loop. | `ObjectLifetimeOps.markSpawnRemembered(objMgr, spawn)`. | low | Signpost lifecycle focused test. | `TestS2Ehz1TraceReplay` or the relevant S2 level-select trace for the touched zone. | remove entry. | `PENDING_PARITY_TRIAGE` |
| `game/sonic1/objects/Sonic1CollapsingFloorObjectInstance.java` / `Sonic1CollapsingLedgeObjectInstance.java` / `Sonic1PlatformObjectInstance.java` | `removeFromActiveSpawns(spawn);` | direct lifecycle operation | Respawnable/offscreen behavior removes active spawn without permanent remembrance. | `ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn)`. | low | Existing platform/collapsing terrain tests. | `TestS1Ghz1TraceReplay`, `TestS1Mz1TraceReplay`, or the matching credits trace when the touched object appears there. | remove entries. | `PENDING_PARITY_TRIAGE` |
| `game/sonic3k/objects/badniks/AbstractS3kBadnikInstance.java` | `setSlotIndex(-1);` | direct lifecycle operation | S3K badnik slot detach after destruction. | Existing `ObjectLifetimeOps.detachSlotForTransfer(this)` or `expireDynamic` only after behavior proof. | medium | S3K badnik destruction and rewind identity tests. | `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay` depending badnik fixture. | keep until slot semantics tested. | `PENDING_PARITY_TRIAGE` |
| `level/objects/DefaultPowerUpSpawner.java` | `setSlotIndex(-1)` / `addDynamicObjectAtSlot(...)` | direct lifecycle operation | Power-up transforms preserve or restore fixed object slots. | `ObjectLifetimeOps.detachSlotForTransfer(...)` and `addReplacementAtTransferredSlot(...)`. | medium | `TestObjectLifetimeOps`, monitor/power-up spawn tests. | `TestS2Ehz1MonitorBreakRegression`; use S1/S3K monitor trace classes matching the touched fixture if power-up replacement is exercised there. | reduce entries after focused tests. | `PENDING_PARITY_TRIAGE` |

## Solid And Touch Profile Inventory

The current source guard does not yet enforce no-new-growth for provider
surfaces, so Phase 4 must add or explicitly defer guard coverage before claiming
baseline ratcheting here.

| file/family | fragment | category | current_behavior | replacement_seam | risk | required_tests | trace_relevance | guard_baseline_action | reason_code |
|---|---|---|---|---|---|---|---|---|---|
| `AbstractMonitorObjectInstance` subclasses (`Sonic1MonitorObjectInstance`, `MonitorObjectInstance`, `Sonic3kMonitorObjectInstance`) | `implements SolidObjectProvider` / `TouchResponseProvider` | solid/touch provider | Monitors combine solid box behavior, break-on-touch behavior, and per-game offsets. | Characterize through canonical `SolidRoutineProfile`, `TouchResponseProfile`, and compatibility wrappers first. | medium | S1/S2/S3K monitor tests, `TestTouchResponseProfileMapping`, `TestSolidRoutineProfiles`. | S2 exact gates: `TestS2Ehz1TraceReplay`, `TestS2Ehz1MonitorBreakRegression`. S1/S3K monitor exact trace class is not currently identified; if implementation finds a fixture containing the touched monitor, name it in that slice summary before running it. | add provider guard after mapping is proven. | `LEGACY_PROVIDER_COMPAT` |
| Simple springs (`LauncherSpringObjectInstance`, pipe/exit springs, S1/S2 spring objects if touched) | `SolidObjectProvider` methods | solid provider | Spring solids often need offscreen bypass and inclusive edge behavior. | `SolidRoutineProfile` compatibility wrapper; keep behavior delegated. | medium | Spring object tests plus `TestSolidRoutineProfiles`. | S2 CNZ launcher exact gates: `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay`. No exact generic spring trace class is currently identified for other spring families; use focused spring tests unless the implementation slice identifies a fixture and names it. | add guard only after compatibility. | `LEGACY_PROVIDER_COMPAT` |
| Flat/top-only platforms (`BridgeObjectInstance`, simple moving platforms, collapsing platforms) | `SolidObjectProvider` methods | solid provider | Top-solid and carrying semantics are sensitive to call timing and rider state. | `SolidRoutineProfile` only after focused platform characterization. | high | `TestS2Ehz1BridgeTailsStandingRegression`, `TestS1BridgeSolidParity`, `TestS1Ghz2BridgeJumpRegression`, `TestS1Ghz3BridgeDropStability`, `TestS1Ghz3BridgeTerrainCollision`, plus touched platform tests. | `TestS2Ehz1TraceReplay`, `TestS2Ehz1BridgeTailsStandingRegression`, `TestS1Ghz1TraceReplay` when S1 GHZ bridge/platform behavior is touched. | defer broad migration. | `PENDING_PARITY_TRIAGE` |
| Simple badniks/projectiles via `AbstractBadnikInstance` / `AbstractProjectileInstance` | `TouchResponseProvider` / `TouchResponseAttackable` | touch provider | Standard enemy/hurt/attackable behavior already centralized in base classes. | `TouchResponseProfile` compatibility wrappers. | medium | `TestTouchResponseProfileMapping`, `TestTouchResponseManager`, `TestSonic1YadrinBadnikInstance`, `TestTurtloidBadnikInstance`, S3K badnik focused tests matching the touched class. | S1: `TestS1Mz1BatbrainEncounterRegression` for Batbrain-like touch paths; S2: `TestS2Ehz1BuzzerSpawnRegression` for Buzzer-like paths; S3K: `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, or `TestS3kMgzTraceReplay` if the touched badnik appears there. | add no-new-growth guard after representative pass. | `LEGACY_PROVIDER_COMPAT` |
| Bumpers/bonus blocks/balloons/Clamer-style continuous callbacks | `requiresContinuousTouchCallbacks()` / render-flag opt-outs | touch provider | Continuous response and render-flag opt-out are behavior-sensitive. | `TouchResponseProfile` mapping only; no dispatch change until sidekick continuation is tested. | high | Bumper, hex bumper, CNZ bumper, triangle bumper, touch manager tests. | S2 CNZ: `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay`; S3K CNZ: `TestS3kCnzTraceReplay`. | defer behavior migration. | `PENDING_PARITY_TRIAGE` |

## Recommended First Implementation Slices

1. Lifecycle low-risk monitors:
   `Sonic1MonitorObjectInstance`, `MonitorObjectInstance`,
   `Sonic3kMonitorObjectInstance`. This burns down three direct lifecycle
   entries with focused tests already present.
2. Participation low-risk bumpers:
   `BumperObjectInstance`, `HexBumperObjectInstance`, and
   `BuzzerBadnikInstance`. These use first-sidekick semantics that map directly
   to `ObjectPlayerQuery.nativeP2OrNull()`.
3. Object-control low-route-impact bonus cage:
   `S3kSlotBonusCageObjectInstance`, covered by an existing focused test and
   low gameplay-route trace relevance.
4. Route-impact `$81` carriers only after focused tests are in place:
   CNZ cannon, spiral tube, S2 launcher ball/spring, and automatic tunnel.

## Phase 0 Exit Status

Every migration category has at least one classified target and named tests.
Low-risk entries are identified, but most object-control entries remain
medium-risk because they affect traversal routes. High-risk and escape-hatch
entries are explicitly deferred. The next phase should start with a small
low-risk slice and cross-review the baseline ratchet before expanding scope.
