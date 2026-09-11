# Release Readiness Roadmap

Date: 2026-06-06

This roadmap captures the architecture and code review findings gathered before
the next release. The release standard is: no known hidden failures. A release
candidate must not rely on disabled known-failing tests, CI paths that skip
policy, undocumented trace/bootstrap carve-outs, or guard tests that silently
miss known bad forms.

The work is split into two phases:

- Phase 1: Release hardening - make release validation honest and fix defects
  that can corrupt runtime state, rendering, or route confidence.
- Phase 2: Broad cleanup - remove tolerated debt, strengthen architectural
  boundaries, and reduce future trace/object/runtime migration risk.

## 2026-08-26 release freeze

Phase 1 now closes on release hardening, fresh automated candidate evidence,
and human gameplay/audio QA. Known-red trace frontiers are retained as visible
limitations under the no-regression contract in
[`docs/status/trace-scope-release-6.md`](../status/trace-scope-release-6.md);
closing each frontier is not itself a 0.6 sign-off requirement. Phase 2,
parity-only trace work, and other broad changes are deferred to the next
release. After this freeze, `develop` accepts only confirmed gameplay, crash,
packaging, platform, policy, and regression fixes needed to ship 0.6.

## Phase 1: Release Hardening

These items should be completed, explicitly documented as release limitations,
or consciously removed from release claims before cutting a release candidate.

### 1. CI and test honesty

Goal: release CI and local verification must expose known failures instead of
hiding them.

Work:

- Completed: run branch policy validation on release/master PR paths, not only PRs into
  `develop`.
  - `.github/workflows/release.yml` runs the policy gate for release PRs.
  - `.githooks/validate-policy.sh` and `.ps1` accept develop and master base
    refs for CI validation.
- Completed: resolve disabled "keep green" S3K tests.
  - `TestS3kAiz1SkipHeadless` disables hollow-log traversal scenarios.
  - `TestS3kCnzCarryHeadless` disables a known failing parity assertion.
  - Both scenarios are now enabled and passing.
- Completed: fix ROM-gated tests to use the same resolved ROM path as
  `@RequiresRom`.
  - `TestSonic3kLifeIconAddresses` uses the shared ROM availability path and
    treats optional reference fixtures as assumptions.
- Completed: make ROM availability checks validate that `rom.open(...)`
  succeeds.
  - `RomCache` validates configured files through game-specific detectors.
- Completed: tighten guard tests that can hide release-relevant problems.
  - `TestNoServicesInObjectConstructors` catches variable-based raw
    `addDynamicObject` child construction.
  - `TestSingletonLifecycleGuard` scopes `SingletonResetExtension` to the
    annotated class instead of skipping whole files on loose substrings.

Recommended order:

1. Extend CI policy coverage to release/master PRs.
2. Fix ROM path/open validation so test enablement is trustworthy.
3. Tighten guard tests to catch the known missed patterns.
4. Revisit disabled "keep green" tests and choose pass, explicit blocker, or
   documented release limitation for each.
5. Update `AGENTS.md`, `README.md`, `KNOWN_DISCREPANCIES.md`,
   `S3K_KNOWN_DISCREPANCIES.md`, or release notes where claims change.

Validation:

- Run the policy script against develop and release-style PR inputs.
- Run focused ROM-gated tests with the ROM supplied through property/env/config
  paths.
- Run the guard tests after adding fixtures for the missed forms.

### 2. Runtime and session safety

Goal: runtime-owned managers and module-owned providers must not survive beyond
their owning session or module.

Work:

- Completed: make `GameLoop.refreshRuntimeBindings()` use the gameplay context readiness
  predicate instead of only checking `getCamera() == null`.
  - Torn-down `GameplayModeContext` instances are rejected even if they retain
    non-null manager fields.
- Completed: clear `GameLoop.titleCardProvider` when the game module/session
  resets.
  - Returning to master title and switching ROM/game no longer keeps the
    previous module's title-card provider.
- Completed: keep AIZ terrain mutation on the supplied `ObjectServices` path.
  - `AizIntroTerrainSwap` builds and applies immediate mutation context through
    injected services.
- Completed: reset AIZ intro overlay ROM data across game bootstrap.
  - `AizIntroTerrainSwap.cachedOverlayData` is cleared when gameplay restarts
    from the master title path.

Recommended order:

1. Add focused tests for torn-down gameplay context binding and title-card
   provider reset.
2. Fix `GameLoop` runtime binding readiness.
3. Fix title-card provider cache invalidation.
4. Add AIZ overlay cache reset/scoping tests.
5. Keep AIZ terrain mutation on injected services, with a test that uses
   explicit services while another runtime is active or absent.

Validation:

- Run focused session/runtime tests.
- Run a headless or integration path that returns to master title and starts a
  different module.
- Run AIZ intro terrain swap tests after cache reset changes.

### 3. Object lifecycle and virtual pattern allocation

Goal: object-owned children must use shared lifecycle paths, and virtual pattern
IDs must not collide.

Work:

- Completed: move constructor-time child spawning out of constructors for:
  - `TurtloidBadnikInstance`
  - `SolBadnikInstance`
- Completed: convert those child spawns to `spawnChild` from first update so
  construction context and parent/child ownership stay on the managed path.
- Completed: move `LightningSparkObjectInstance.SPARK_PATTERN_BASE` out of the shared
  object-art range or allocate it through the owning art/provider range.
  - The spark base now uses `PatternAtlasRange.TRANSIENT_EFFECTS`.
- Completed: add guard coverage for hard-coded virtual pattern bases inside
  reserved ranges where practical.

Recommended order:

1. Add tests that fail on constructor-time service access/raw child spawn for
   Turtloid and Sol.
2. Introduce or reuse a post-registration child-spawn path.
3. Convert Turtloid and Sol.
4. Add a virtual pattern range collision test for lightning sparks.
5. Move the spark base and verify rendered pattern IDs no longer overlap object
   art allocation.

Validation:

- Run object lifecycle guard tests.
- Run focused Turtloid/Sol object tests or zone object-loading tests.
- Run focused lightning shield/spark render tests if present; otherwise add a
  non-GL atlas ID allocation test.

### 4. S3K runtime visibility

Goal: release-priority S3K zones should expose event state through the typed
runtime framework instead of ad hoc manager casts or camera formulas.

Work:

- Completed: add ICZ runtime state registration.
  - `Sonic3kLevelEventManager.installZoneRuntimeState` now installs
    `IczZoneRuntimeState`.
- Completed: move ICZ palette and animated-tile phase queries onto typed
  runtime state.
  - `Sonic3kPaletteCycler` reads the ICZ indoor gate through
    `S3kRuntimeStates.currentIcz(...)`.
  - `Sonic3kPatternAnimator` resolves ICZ scroll/event phase through
    `IczZoneRuntimeState` when runtime state is available.
- Completed: add MHZ to `currentRuntimeStateUsesThisEventInstance`.
  - MHZ and ICZ runtime states are preserved when backed by the current event
    handler instance instead of being repeatedly reinstalled/reset.
- Decide ICZ character-route release scope.
  - Current behavior is Sonic-alone focused; Knuckles/Tails routes are not
    equivalent.

Recommended order:

1. Fix MHZ current-state recognition first; it is small and concrete.
2. Add tests proving ICZ currently lacks runtime state.
3. Introduce an ICZ runtime state backed by `Sonic3kICZEvents`.
4. Move ICZ palette/pattern consumers to the runtime state.
5. Add release-scope documentation or tests for Sonic/Tails/Knuckles ICZ route
   behavior.

Validation:

- Extend `TestS3kZoneRuntimeRegistrationHeadless` to cover ICZ and MHZ.
- Run focused S3K palette/pattern animator tests.
- Run ICZ route smoke tests for each character configuration claimed by the
  release.

### 5. Trace and physics release visibility

Goal: trace and physics exceptions must be either fixed or visible as release
limitations.

Detailed current trace problem statements, frontier table, clusters, and
execution order live in `docs/architecture/plans/trace-remediation.md`.

Work:

- Release-blocking pre-level intro trace bootstrap.
  - `TraceReplayBootstrap` now uses the generic
    `TraceMetadata.hasPreLevelIntroPrefix()` fixture capability instead of an
    S3K AIZ zone/act/checkpoint identity predicate.
  - The regenerated AIZ end-to-end replay is release-blocking; pre-level rows
    advance the BK2/VBlank cursor without hydrating trace-row player state into
    the engine.
  - Trace policy tests reject new legacy trace predicates and zone/route/frame
    carve-outs in the bootstrap path.
- Deferred release debt: frame-0 bootstrap sidekick/SST snapshot coverage.
  - `AbstractTraceReplayTest.captureEngineSnapshot()` currently captures player
    history only; sidekick CPU and per-slot SST engine views remain absent.
  - Bootstrap warnings for missing engine views mean the run is not a full
    sidekick/SST parity proof, even when per-frame errors are zero.
  - Removal requires live engine accessors for those views and strict failures
    when a native-prelude trace records snapshots that the engine cannot
    compare.
- Completed: S1/S2 bottom-boundary centre-Y parity.
  - `GameRules.SONIC_1` and `SONIC_2` now use centre-Y for the
    bottom-boundary death check, matching the ROM `obY` / `y_pos` coordinate.
  - Focused `TestPlayableSpriteMovement` coverage guards both games.
- Classified: S2 Tornado ride-start bootstrap is an explicit trace comparison
  contract.
  - The bootstrap is a deterministic native prelude selected by the live ObjB2
    Tornado shape, not trace-row hydration.
  - `docs/status/known-discrepancies.md` documents the contract and the policy tests
    that keep non-Tornado S2 traces on the generic title-card prelude.
- Current trace remediation clusters after the 2026-06-12 true-frontier
  comparator sweep plus the S3K complete-run handoff-row fix:
  - **S3K complete-run startup carry / native sidekick state:** the old
    frame-0 setup signatures moved. `s3k_cnz1` now reaches frame 1
    (`y 0x061C -> 0x0600`), `s3k_mhz1` now reaches frame 1
    (`y 0x051C -> 0x0500` with `tails_cpu_routine` still expecting `0x000E`),
    and `s3k_lbz1` now reaches frame 410 (`y_speed 0x0000 -> -0100`).
    Treat these as startup carry/post-title-card sidekick state bugs before
    stepping deep traces.
  - **S1 status-bit / push timing:** `s1_credits_01_mz2`,
    `s1_credits_04_slz3`, `s1_credits_05_sbz1`,
    `s1_credits_06_sbz2`, plus `s3k_hcz1`. `s1_credits_05_sbz1`
    advanced from frame 116 to frame 413 after the Obj66 right-edge
    `SolidObject` fix; the next owner is facing/on-object handoff.
  - **Rolling and radius timing:** `s1_ghz1`, `s1_mz3`, `s1_sbz1`,
    plus the y-off-by-about-five group (`s1_lz2`, `s1_slz1`, `s1_mz2`,
    `s1_sbz2`, `s2_arz1`, `s2_cnz2`, `s2_scz1`). Hypothesis: one
    wrong roll/standing radius transition can explain both status and y
    deltas, because Sonic's standing radius is 19 and rolling/jumping radius
    is 14.
  - **Exact `0x100` speed deltas:** `s1_ghz3`, `s1_syz1`, `s1_mz1`,
    `s2_arz2`, `s2_mcz1`. Diff the single frame's movement subroutine before
    changing shared constants.
  - **S2/S3K sidekick CPU state:** `s2_htz1`, `s2_htz2`, `s2_mtz1`,
    `s2_mtz3`, `s2_ooz2`, `s3k_mhz1`, and downstream Tails movement traces
    (`s2_cpz1`, `s2_cpz2`, `s2_mcz2`, `s2_mtz2`, `s2_ooz1`, `s2_cnz1`,
    `s3k_aiz1`). Fix CPU routine/interact/status publication before treating
    later Tails speeds as independent bugs.
  - **Springs / launchers:** `s1_sbz3`, `s1_slz3`, `s1_lz1`. These have
    large same-frame y-speed mismatches and should be checked against the
    owning spring/launcher trigger routine.
  - **Individual frontiers:** `s1_lz3` vertical wrap, `s1_syz3`
    bumper/bounce sign, `s1_slz2` slope factor, `s3k_hcz1` air countdown,
    `s3k_mgz1` ring pickup, `s3k_icz1` one-pixel rounding, `s2_dez1`
    ending-route speed, and `s1_lz4` camera.
- Sidekick CPU audit open problem statements folded into this roadmap:
  - `s2_mtz2` frame 645 is currently masked by Tails being hurt by a
    Shellcracker claw where ROM Tails is rolling; suspect solid/touch
    ordering before changing sidekick CPU.
  - `s3k_lbz1` no longer stops at the frame-0 camera seed; the frontier is now
    frame 410 `y_speed`, after native startup/launch state ticks through the
    initial handoff rows.
  - `s3k_aiz1` still has a monitor/push sidekick frontier and auto-jump
    cadence sub-test failures.
  - The dead-fall path still needs ROM ordering review around
    `applyDeathMovement` and `Obj02_Dead` / `Tails_Max_Y_pos + $100`.
  - S2 signpost airborne end-of-act trigger behavior, Obj85 preserved-roll
    jump suppression, and CPU `Level_frame_counter` visibility remain open
    audit items.
  - S3K Tails-alone CPU routines `$1A-$1E`, `$20`, and `$22`, MHZ1
    carry-intro spawn, ICZ/SSZ dormant park, and star-post zero-kinematics
    reset remain incomplete.
  - `s2_scz1` frame 6370 is a merged-baseline Tornado-route y regression and
    should be treated as non-sidekick until evidence says otherwise.

Recommended order:

1. Keep the comparator broad enough to report true frontiers; do not narrow or
   tolerate status/routine mismatches to regain green output.
2. Continue the S3K complete-run startup carry/native sidekick cluster exposed
   after the frame-0 handoff-row fix.
3. Continue the status-bit cluster exposed by the widened comparator, starting
   with the earliest single-frame `Status_Push` / facing / on-object handoff.
4. Test the roll/radius hypothesis before treating the y-off-by-five traces as
   unrelated bugs.
5. Diff exact-`0x100` speed deltas at the owning movement routine.
6. Fix sidekick CPU routine/interact/status roots before downstream Tails
   position or speed symptoms.
7. Keep the accepted AIZ legacy trace predicate bounded by the release guard.
8. Add focused tests around any future AIZ intro trace bootstrap decision
   points.
9. Replace remaining fixture recognition with ROM-state-driven phase handling
   when new recorder metadata or regenerated fixtures are available.
10. Add focused bottom-boundary tests for S1/S2 centre-Y behavior before any
   future flag flip.
11. Re-run affected S1/S2 traces before changing related typed rules.
12. Keep the S2 Tornado contract tests green before changing trace bootstrap
   setup.

Validation:

- Run focused trace replay tests affected by each change.
- Update `docs/status/trace-frontier-log.md` whenever a frontier moves or a trace debt
  is explicitly accepted.

## Phase 2: Broad Cleanup

These items should not block the release if Phase 1 makes them visible and the
release notes do not overclaim. They should be tracked so the same issues do not
return under new names.

### 1. Complete object child-spawn migration

Work:

- Completed in the first Phase 2 slice:
  - `BuggernautBadnikInstance`
  - `CaterkillerJrHeadInstance`
  - `CheckpointObjectInstance`
- Completed in the second Phase 2 slice:
  - `FallingPillarObjectInstance`
  - `SeesawObjectInstance`
  - `SmallMetalPformObjectInstance`
  - added a migrated-file ratchet so these files stay on managed
    `spawnChild` / `spawnFreeChild` helpers.
- Completed in the third Phase 2 slice:
  - `BreakablePlatingObjectInstance`
  - `CollapsingPlatformObjectInstance`
  - `OOZLauncherObjectInstance`
  - extended the migrated-file ratchet to keep S2 fragment/debris spawns on
    managed lifecycle helpers.
- Completed in the fourth Phase 2 slice:
  - `SteamSpringObjectInstance`
  - extended the migrated-file ratchet to keep steam puff effect spawns on
    `spawnFreeChild`.
- Completed in the fifth Phase 2 slice:
  - `BumperObjectInstance`
  - `BonusBlockObjectInstance`
  - `CnzBumperObjectInstance`
  - extended the migrated-file ratchet to keep bumper / bonus-block score
    popup spawns on `spawnFreeChild`.
- Completed in the sixth Phase 2 slice:
  - `AizBgTreeSpawnerInstance`
  - `HTZLiftObjectInstance`
  - `SidewaysPformObjectInstance`
  - extended the migrated-file ratchet to keep AIZ background-tree, HTZ lift
    scenery, and linked sideways-platform spawns on `spawnFreeChild`.
- Completed in the seventh Phase 2 slice:
  - `BubbleGeneratorObjectInstance`
  - `LeavesGeneratorObjectInstance`
  - `SignpostObjectInstance`
  - extended the migrated-file ratchet to keep bubble, leaf, sparkle, and S2
    results-screen effect spawns on `spawnFreeChild`.
- Completed in the eighth Phase 2 slice:
  - `CluckerBadnikInstance`
  - `CoconutsBadnikInstance`
  - `NebulaBadnikInstance`
  - `OctusBadnikInstance`
  - extended the migrated-file ratchet to keep simple badnik projectile spawns
    on managed helpers, preserving `addDynamicObjectAfterCurrent` semantics with
    `spawnChild` where required.
- Completed in the ninth Phase 2 slice:
  - `TurtloidBadnikInstance`
  - extended the migrated-file ratchet to keep the remaining Turtloid projectile
    and rider-destruction aftermath spawns on `spawnFreeChild`.
- Completed in the tenth Phase 2 slice:
  - `AbstractS3kBadnikInstance`
  - `BlastoidBadnikInstance`
  - extended the migrated-file ratchet to keep shared S3K badnik projectile
    spawns and Blastoid's one-off projectile spawn on `spawnFreeChild`.
- Completed in the eleventh Phase 2 slice:
  - `MGZHeadTriggerObjectInstance`
  - `RivetObjectInstance`
  - extended the migrated-file ratchet to keep simple explosion/effect spawns
    on `spawnFreeChild`.
- Completed in the twelfth Phase 2 slice:
  - `AizEndBossBombChild`
  - `AizShipBombInstance`
  - extended the migrated-file ratchet to keep AIZ bomb smoke and ship-bomb
    fragments on managed helpers, preserving after-current fragment allocation
    with `spawnChild`.
- Completed in the thirteenth Phase 2 slice:
  - `TornadoObjectInstance`
  - extended the migrated-file ratchet to keep Tornado smoke spawns on
    `spawnFreeChild`.
- Completed in the fourteenth Phase 2 slice:
  - `OOZPoppingPlatformObjectInstance`
  - removed its constructor-time child registration exception and moved the
    burner-flame child to first update via `spawnChild`.
- Completed in the fifteenth Phase 2 slice:
  - `SpinyBadnikInstance`
  - replaced its create/remove/re-add projectile sequence with a single
    `spawnChild` supplier that preserves the deferred LoadSubObject init frame.
- Completed in the sixteenth Phase 2 slice:
  - `RexonHeadObjectInstance`
  - `SpikerBadnikInstance`
  - extended the migrated-file ratchet to keep Rexon fireball/defeat-effect
    spawns and Spiker drill projectile spawns on `spawnFreeChild`.
- Completed in the seventeenth Phase 2 slice:
  - `HTZBossLavaBall`
  - extended the migrated-file ratchet to keep the lava-ball-to-ground-fire
    transform on `spawnFreeChild`.
- Completed in the eighteenth Phase 2 slice:
  - `MonitorObjectInstance`
  - extended the migrated-file ratchet to keep the S2 monitor break explosion
    spawn on `spawnFreeChild`.
- Completed in the nineteenth Phase 2 slice:
  - `PointPokeyObjectInstance`
  - extended the migrated-file ratchet to keep the simple points popup and
    slot-machine ring/bomb prize spawns on `spawnFreeChild`.
- Completed in the twentieth Phase 2 slice:
  - `BreakableBlockObjectInstance`
  - extended the migrated-file ratchet to keep breakable-block points and
    debris fragment spawns on `spawnFreeChild`.
- Completed in the twenty-first Phase 2 slice:
  - `RisingPillarObjectInstance`
  - `SmashableGroundObjectInstance`
  - extended the migrated-file ratchet to keep rising-pillar debris,
    smashable-ground fragments, and chain-bonus points on `spawnFreeChild`.
- Completed in the twenty-second Phase 2 slice:
  - `GrounderBadnikInstance`
  - `RexonBadnikInstance`
  - extended the migrated-file ratchet to keep Grounder wall/rock cohorts and
    Rexon head segments on parent-owned `spawnChild`.
- Completed in the twenty-third Phase 2 slice:
  - `ArrowShooterObjectInstance`
  - `TiltingPlatformObjectInstance`
  - `ArrowProjectileInstance`
  - extended the raw-spawn scanner to catch `addDynamicObjectNextFrame`, then
    moved the arrow projectile and vertical-laser child paths onto
    parent-owned `spawnChild`; `ArrowProjectileInstance` preserves the ROM
    one-frame spawn gap through `skipsSameFrameUpdateAfterSpawn`.
- Completed in the twenty-fourth Phase 2 slice:
  - `EggPrisonObjectInstance`
  - removed its constructor-time raw child-registration exception by spawning
    the capsule button on first update through `spawnChild`, and moved
    explosion, destroyed-capsule, and results-screen spawns onto
    `spawnFreeChild`.
- Completed in the twenty-fifth Phase 2 slice:
  - `Sonic2HTZBossInstance`
  - extended the migrated-file ratchet to keep HTZ boss flamethrower/lava-ball
    attack children on `spawnChild` and defeat smoke on `spawnFreeChild`.
- Completed in the twenty-sixth Phase 2 slice:
  - `Sonic2EHZBossInstance`
  - extended the migrated-file ratchet to keep EHZ boss vehicle, propeller,
    wheel, spike, and propeller-reload children on parent-owned `spawnChild`.
- Completed in the twenty-seventh Phase 2 slice:
  - `Sonic2DEZEggmanInstance`
  - extended the migrated-file ratchet to keep the DEZ transition barrier-wall
    child on parent-owned `spawnChild`.
- Completed in the twenty-eighth Phase 2 slice:
  - `Sonic2MechaSonicInstance`
  - extended the migrated-file ratchet to keep Mecha Sonic startup window/sensor
    children, spikeball projectiles, and the Eggman transition spawn on managed
    construction-context helpers.
- Completed in the twenty-ninth Phase 2 slice:
  - `Sonic2DeathEggRobotInstance`
  - extended the migrated-file ratchet to keep permanent body-part children,
    bomb projectiles, and the targeting sensor on managed construction-context
    helpers.
- Completed in the thirtieth Phase 2 slice:
  - `Sonic2MCZBossInstance`
  - extended the migrated-file ratchet to keep MCZ boss falling stone/spike
    debris on `spawnFreeChild`.
- Completed in the thirty-first Phase 2 slice:
  - `Sonic2MTZBossInstance`
  - extended the migrated-file ratchet to keep the MTZ boss laser shooter and
    seven shield-orb children on managed construction-context helpers.
- Completed in the thirty-second Phase 2 slice:
  - `Sonic2ARZBossInstance`
  - extended the migrated-file ratchet to keep ARZ boss pillars, eyes, and arrow
    children on `spawnFreeChild`.
- Completed in the thirty-third Phase 2 slice:
  - `Sonic2CNZBossInstance`
  - `CNZBossElectricBall`
  - extended the migrated-file ratchet to keep the CNZ boss electric ball and
    split clone on `spawnFreeChild`.
- Completed in the thirty-fourth Phase 2 slice:
  - `ConveyorObjectInstance`
  - extended the migrated-file ratchet to keep MTZ conveyor parent-spawned
    child platforms on construction-context-managed dynamic object creation.
- Completed in the thirty-fifth Phase 2 slice:
  - `Sonic2CPZBossInstance`
  - `CPZBossContainer`
  - `CPZBossContainerExtend`
  - `CPZBossFallingPart`
  - `CPZBossGunk`
  - `CPZBossPipe`
  - `CPZBossPipeSegment`
  - `CPZBossPump`
  - extended the migrated-file ratchet to keep CPZ boss startup components,
    pipe/container runtime children, falling debris, gunk droplets, and the
    free transient boss explosion on managed construction-context helpers.
- Continue auditing remaining raw dynamic-object calls:
  - S2 object-owned production raw child spawns are now cleared from the
    refreshed inventory.
  - remaining hits are framework/helper APIs, manager bridge utilities, or
    comments.
  - accepted bridge boundaries are now documented in
    `docs/architecture/archunit-exceptions.md`.
- Keep manager/framework bridge code as explicit exceptions with documented
  rationale.
- Replace raw-call count ratchets with semantic checks where possible.

Recommended order:

1. Expand the scanner to classify direct child construction assigned to
   variables.
2. Inventory all raw object-owned child spawns.
3. Convert the easiest non-bridge cases first.
4. Document remaining bridge exceptions.
5. Ratchet the guard downward after each conversion.

### 2. Strengthen architectural guardrails

Work:

- Tighten direct map mutation detection.
  - Earlier local review noted the guard claims to ban both `getMap().setValue`
    and `map.setValue`, but the scan primarily catches the former shape.
- Improve singleton lifecycle scanning so extension use does not skip an entire
  file.
- Add a guard for hard-coded virtual pattern bases in owned ranges.
- Keep JUnit 4 and runtime-docs asset-read guards in place; no active defect was
  found there.
- Completed in Phase 2:
  - direct map mutation scanning now covers aliases assigned from
    `*.getMap()` before `alias.setValue(...)`.
  - singleton lifecycle scanning remains per-class / per-setup scoped from
    Phase 1 and has regression fixtures.
  - virtual pattern-base scanning now detects any hard-coded value inside a
    registered `PatternAtlasRange`, not just exact base duplicates.
  - in-range sub-bases in S1/S2 special-stage results, S2 special-stage
    rendering, S3K dust, and ICZ snowboard art now use
    `PatternAtlasRange.*.base() + offset`.

Recommended order:

1. Add regression fixtures for every known missed pattern.
2. Improve scanners one guard at a time.
3. Verify existing intentional exceptions still pass.
4. Document exception criteria in `docs/architecture/archunit-exceptions.md` or
   the closest guardrail doc.

### 3. Trace bootstrap and fixture contract cleanup

Work:

- Move trace bootstrap policy toward ROM-state predicates instead of trace
  names, zones, checkpoints, or route-specific heuristics.
- Make comparison-only invariants easier to audit.
- Give S2 Tornado bootstrap a named contract and tests that prove it does not
  hydrate engine state from trace rows during replay comparison.
- Completed in Phase 2:
  - added a source-signal ratchet for `TraceReplayBootstrap` and
    `TraceReplaySessionBootstrap` so new zone/profile/checkpoint/frame-shape
    bootstrap policy changes fail until explicitly documented and reviewed.
  - added a trace-row hydration source guard that rejects direct player-state
    setter writes sourced from `TraceFrame` rows during replay bootstrap, while
    keeping comparison-only reads and metadata/native-prelude setup separate.
  - split S2 Tornado metadata eligibility from live ObjB2 runtime-object
    authority by renaming the trace-side predicate to
    `isS2TornadoRideStartMetadataCandidate(...)`, keeping the old API as a
    deprecated delegate, and adding a source guard that prevents
    `TraceReplaySessionBootstrap` from treating the metadata candidate as the
    final Tornado authority.
  - documented the S2 CNZ slot-machine fixture-capability prelude and S3K
    sidekick seed-frame bootstrap heuristic as accepted trace replay debt in
    `docs/status/known-discrepancies.md`, with removal conditions and a guard that
    keeps the documentation present.
  - documented the remaining S3K complete-run segment metadata start-position
    bootstrap as bounded frame-zero debt; complete-run replay no longer seeds
    frame-zero player/sidekick/camera state from trace rows.
  - converted the S2 slot-machine prelude check from direct CNZ-named replay
    bootstrap usage to generic `TraceMetadata.hasPerFrameSlotMachineState()`
    capability metadata, keeping the old CNZ API as a deprecated recorder-schema
    compatibility alias and guarding `TraceReplayBootstrap` against regaining
    the CNZ-named predicate.
  - replaced S3K sidekick seed-frame movement-shape heuristics with explicit
    `TraceMetadata.hasSidekickSeedFramePrelude()` fixture capability metadata,
    tagged the current CNZ Sonic+Tails trace fixture with
    `sidekick_seed_frame_prelude`, and added a source guard that prevents
    `TraceReplayBootstrap` from inferring this prelude from frame-0 player
    movement shape.
  - removed the remaining trace-row primary-state substitution path from
    `TraceReplayBootstrap`; comparison now uses live engine sprite state only.
- Remaining cleanup:
  - Add engine-side sidekick CPU and per-slot SST frame-0 snapshot views so
    bootstrap warnings can become strict comparisons.

Recommended order:

1. Inventory all trace bootstrap predicates.
2. Split fixture metadata interpretation from runtime state application.
3. Add tests that reject new zone/route/frame carve-outs.
4. Convert one bootstrap exception at a time.

### 4. S3K zone runtime completion

Work:

- Bring remaining S3K zones onto runtime-owned state where event, palette,
  parallax, animated-tile, or mutation behavior is currently manager-local.
- Verify ICZ, MHZ, LBZ, and later zones against the zone analysis docs.
- Move direct camera/event formulas into typed state where the same value is
  consumed by multiple systems.
- Complete palette ownership migration for S3K direct palette writes that still
  bypass `PaletteOwnershipRegistry`.
- Remove or snapshot local animated-tile phase caches such as MHZ's last BG
  phase fields so rewind/session restore does not depend on unsnapshotted
  animator-local state.
- Completed in Phase 2:
  - `Sonic3kPatternAnimator` now snapshots and restores MHZ BG1/BG2 phase
    caches, with a rewind round-trip test.
  - `S3kPaletteWriteSupport.applyContiguousPatch` now has registry-present
    coverage, and the AIZ2 torch, BPZ / CGZ / EMZ / ICZ / LBZ / LRZ palette
    cycles plus the ICZ startup palette patch submit ownership claims through
    `PaletteOwnershipRegistry` instead of mutating palette colors directly.
  - registry-backed ICZ / LBZ / LRZ / BPZ / CGZ / EMZ palette cycles no longer
    perform direct texture uploads after submitting ownership claims; fallback
    texture upload is centralized behind a source guard for no-registry tests
    and legacy paths.
  - the HCZ act-1 event palette mutation now uses `S3kPaletteWriteSupport`
    with an explicit HCZ event owner and immediate registry resolution.
  - `CnzMinibossInstance` hit-flash and restore colors now submit sparse
    line-1 writes through `S3kPaletteWriteSupport` with the
    `s3k.cnz.miniboss` owner instead of mutating palette colors and caching the
    texture directly.
  - `MgzMinibossInstance` hit-flash and restore colors now submit sparse
    line-1 writes through `S3kPaletteWriteSupport` with a dedicated
    `s3k.mgz.miniboss` owner.
  - `TunnelbotBadnikInstance` hit-flash and restore colors now submit sparse
    line-1 writes through `S3kPaletteWriteSupport` with a dedicated
    `s3k.mgz.tunnelbot` owner.
  - shared `AbstractBossInstance` base hit-flash and restoration now submit
    color-1 writes through a game-agnostic `PaletteWriteSupport` helper with a
    `boss.flash` owner, while retaining direct-write fallback for non-registry
    contexts.
  - AIZ1 intro/gameplay AnPal and AIZ2 water/torch cycles now submit their
    palette line 2/3 writes through `PaletteOwnershipRegistry` with explicit
    `s3k.aiz1.anpal`, `s3k.aiz2.waterCycle`, and
    `s3k.aiz2.torchCycle` owners; AIZ2 torch no longer performs a direct
    texture upload after registry submission.
  - `Sonic3kPaletteCycler` Slots and Pachinko cycles now submit line 2/3
    palette writes through `PaletteOwnershipRegistry` with explicit
    `s3k.slots.zoneCycle` and `s3k.pachinko.zoneCycle` owners; the cycler file
    no longer has direct gameplay palette mutations outside the centralized
    no-registry fallback helper.
  - `HczMinibossInstance` underwater water-effect palette install now uses
    `S3kPaletteWriteSupport.applyUnderwaterLine` with the `s3k.hcz.miniboss`
    owner instead of directly replacing the underwater palette line and
    uploading the underwater texture.
  - `AizIntroPaletteCycler` now submits the intro Super Sonic line-0 colors
    through `PaletteOwnershipRegistry` with the
    `s3k.aiz.introSuperPalette` owner instead of mutating the live palette and
    uploading line 0 directly.
  - `Sonic3kSuperStateController` now delegates Super Sonic palette frame
    writes through `S3kPaletteWriteSupport.applyContiguousPatchToPalette` with
    the `s3k.super.palette` owner, preserving cross-game donor-palette fallback
    inside the shared helper.
  - AIZ intro Knuckles/emerald palette installs, CNZ2 cutscene palette-line
    restore, and MHZ1 cutscene palette-line restore now route through
    `S3kPaletteWriteSupport` with explicit cutscene owners instead of direct
    palette mutation or texture upload in object code.
- Completed or accepted boundaries in Phase 2:
  - gameplay palette ownership bypasses are reduced to documented fallback or
    load-time boundaries: `Sonic3kPaletteCycler.cacheFallbackPaletteTexture`
    when no registry exists, `AizIntroArtLoader` standalone intro-art palette
    caching, centralized `S3kPaletteWriteSupport` /
    `PaletteWriteSupport` / `PaletteOwnershipRegistry` helpers, and
    `Sonic3kLevel` initial palette construction.
  - removed unused `Sonic3kZoneEvents.cachePaletteTextureIfReady`, with a
    source guard preventing the direct palette texture upload fallback from
    returning.
  - wrapped `Sonic3kWaterDataProvider`'s Knuckles underwater palette patch in
    loader-local construction logic using the palette color accessor; the
    gameplay registry is not the right owner because this builds returned
    palette data.
  - moved `Sonic3kSpecialStageManager` palette mutation/upload paths behind the
    special-stage-local palette model and uploader helper, with a source guard
    preventing the manager from regaining direct color-array writes or palette
    texture uploads.
  - routed `S3kSpecialStageResultsScreen`'s `Pal_Results` decoding through the
    shared `PaletteLoader` instead of a hand-rolled `setColor` loop, with a
    guard keeping the shared decoder in use.
  - introduced `S3kFrontendPaletteUploader` and routed S3K data select, level
    select, and title-screen palette texture uploads through that frontend/menu
    boundary, with a source guard preventing direct uploads from returning.
  - routed the S3K special-stage results screen palette upload through
    `Sonic3kSpecialStagePaletteUploader`; the special-stage package now keeps
    direct palette texture uploads inside its local helper.
  - moved S3K special-stage palette construction and rotation/emerald/Knuckles
    patches off direct `Palette.colors[]` access and onto `Palette.getColor(...)`,
    with a guard keeping the local palette model on accessor-based writes.
  - continue auditing S3K special-stage palette color mutation semantics as new
    parity gaps are found; the special stage should stay behind local helpers
    rather than gameplay `PaletteOwnershipRegistry`, because it owns per-frame
    fade/rotation writes outside normal level runtime.
  - leave standalone character/host-compatible palette construction in
    `Sonic3k` / `Sonic3kGameModule` out of this release cleanup unless the
    scope expands to all raw `Palette` construction idioms. Current accepted
    boundaries are documented in `docs/architecture/archunit-exceptions.md`.
  - extended adapter coverage for all currently installed S3K zone runtime
    states by adding explicit LBZ and MHZ adapter tests alongside the existing
    AIZ / HCZ / CNZ / MGZ / ICZ coverage.
- Remaining release cleanup: none for this section.
- Follow-up backlog:
  - continue extending runtime-state registration and read-guard coverage as
    new zones or new runtime-state consumers are brought up.

Recommended order:

1. Treat ICZ Phase 1 runtime visibility as the baseline and extend route
   coverage beyond Sonic-alone assumptions.
2. Audit each `docs/architecture/research/s3k-zones/*-analysis.md` against registered runtime state.
3. Prioritize zones by release route impact.
4. Remove or document the unused zone-event palette upload fallback.
5. Wrap loader/screen/special-stage palette writes behind local helpers where
   gameplay ownership is not the right abstraction.
6. Remove or snapshot animator-local phase caches, starting with MHZ.
7. Add zone runtime registration tests as each zone is completed.

### 5. S3K asset provenance and documentation cleanup

Work:

- Verify S3-half address exceptions in `Sonic3kConstants`.
  - The AIZ/HCZ/MGZ/LBZ breakable-wall mapping block currently uses an
    "S3 half addresses for S3-era zones" comment rather than documenting
    per-label S&K-side lookup results.
- Refresh stale comments and docs discovered during review.
  - Example: S3K object registry comments that imply placeholder-only behavior
    despite real factories.
  - Example: boss/object comments that still describe implemented code as
    stubbed.
- Completed in Phase 2:
  - `Sonic3kObjectRegistry` comments no longer claim placeholder-only object
    coverage, and a source guard prevents that stale claim from returning.
  - the breakable-wall mapping block in `Sonic3kConstants` now documents
    per-table S3-side vs S&K-side provenance instead of inferring ROM half from
    zone era, with a guard against the old wording.
  - `HczEndBossInstance` no longer describes implemented movement and defeat
    logic as stubbed; a source guard prevents that stale claim from returning.
  - `HczEndBossInstance` child-spawn docs no longer describe the implemented
    turbine/blade/water-column path as pending placeholder task work; the same
    source guard covers the stale wording.
  - `Sonic3kMGZEvents` no longer describes the implemented MGZ end-boss route
    handoff as an unimplemented or stubbed step; the source guard now covers
    that wording too.
  - `Sonic3kMonitorObjectInstance` no longer claims the `PlayerCharacter`
    system is missing; the comment now scopes the open work to Knuckles
    glide/slide monitor-break parity.
  - S3K subtype-9 super monitors now award the ROM 50-ring bonus once and start
    transformation through `SuperStateController.activateFromMonitor()` instead
    of stopping at the previous ring-award-only path.
  - stale CNZ teleporter/miniboss/end-boss task-scaffold comments were replaced
    with current art/provenance and bounded-wrapper wording across S3K
    constants, module docs, object art loaders, and CNZ traversal object IDs;
    the source guard now rejects the old task-scaffold phrases.
  - the CNZ cannon art comment now documents that `ART_UNC_CNZ_CANNON_ADDR` is
    an S3-side lock-on offset while the DPLC table remains S&K-side.
  - S3K AIZ intro cache reset now routes through a `GameModule`
    module-scoped reset hook instead of adding concrete S3K imports to
    `Engine`; the root dependency ratchet covers this boundary.
- Remaining release cleanup: none for this section.
- Follow-up backlog:
  - verify other broad S3-half comments opportunistically as nearby S3K assets
    are touched.
  - continue removing stale boss/object "stubbed" comments as each comment is
    checked against the current implementation state.
  - refine S3K super-monitor parity for hyper-specific and sidekick-specific
    branches once ROM-state hooks expose the required player mode, hyper/super
    flags, palette status, speed constants, and spawned star/bird object state.

Recommended order:

1. Use `RomOffsetFinder` or disassembly lookup to verify each S3-half exception.
2. Update comments with exact provenance.
3. Move real discrepancies into `S3K_KNOWN_DISCREPANCIES.md`.
4. Remove stale implementation comments as files are touched for nearby work.

### 6. S1/S2 physics parity debt

Work:

- Revisit physics flags that intentionally preserve old trace baselines despite
  known ROM behavior.
- Start with bottom-boundary centre-Y because the code already documents the ROM
  evidence.
- Advance trace frontiers deliberately and update trace docs.
- Current release-cleanup decision:
  - do not flip S1/S2 `levelBoundaryUsesCentreY` in this branch without a trace
    A/B pass; ROM evidence is clear, but existing S1/S2 unit tests deliberately
    preserve old trace-baseline behavior.
  - the exact pre-flip focused unit check is:
    `mvn -q "-Dtest=com.openggf.sprites.managers.TestPlayableSpriteMovement,com.openggf.game.TestHybridGameRules" test`
  - the minimum trace A/B commands before a future flip are:
    `mvn -q -Ptrace-replay "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay,com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay" test -DfailIfNoTests=false`
    then same-game sweeps:
    `mvn -q -Ptrace-replay "-Dtest=com.openggf.tests.trace.s1.*TraceReplay" test -DfailIfNoTests=false`
    and
    `mvn -q -Ptrace-replay "-Dtest=com.openggf.tests.trace.s2.*TraceReplay,com.openggf.tests.trace.s2.*Regression" test -DfailIfNoTests=false`
- Remaining release cleanup: none for this section; the centre-Y flag flip is a
  future trace A/B project, not part of this release-hardening branch.

Recommended order:

1. Add focused unit/headless tests for the ROM-level behavior.
2. Run affected S1/S2 trace suites before flipping flags.
3. Flip one game at a time.
4. Fix resulting divergences from ROM state, not route exceptions.
5. Update `docs/status/trace-frontier-log.md`.

## Items Reviewed With No Current Fix Required

These areas were checked during the review and did not produce an actionable
defect:

- No real JUnit 4 test source leakage was found.
- No production runtime object/art/PLC loader was found reading gameplay asset
  bytes from `docs/` disassembly files.
- The shared ENEMY touch-response path still polls continuously every frame.
- Plain `renderPattern` did not show a proven virtual-ID truncation issue; the
  actionable defect is the overlapping hard-coded spark allocation.

## Recommended Branch and Commit Shape

Use one hardening branch for Phase 1, for example:

`bugfix/ai-release-hardening`

Suggested commits:

1. `fix(ci): enforce release policy checks`
2. `fix(test): make rom-gated release tests honest`
3. `fix(runtime): avoid stale gameplay bindings`
4. `fix(runtime): reset module-owned title and aiz caches`
5. `fix(objects): move constructor child spawns to lifecycle path`
6. `fix(render): move lightning spark virtual patterns out of object range`
7. `fix(s3k): register icz and mhz runtime state correctly`
8. `docs(trace): expose remaining trace bootstrap release debts`

Phase 2 should use smaller follow-up branches by subsystem rather than one broad
cleanup branch.
