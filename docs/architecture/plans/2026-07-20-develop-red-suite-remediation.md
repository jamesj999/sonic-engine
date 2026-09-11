# Develop Red-Suite Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn all 36 current develop reds green with ROM-driven behavior fixes, behavior-neutral extraction, or narrowly justified test/API reconciliation.

**Architecture:** Work in dependency order: child-graph rewind before rewind inventories, shared physics before object consumers, then isolated gameplay/rendering fixes. Each task owns one root cause, runs the named tests red then green, and produces one coherent commit.

**Tech Stack:** Java 17, Maven, JUnit 5, OpenGGF compact rewind schema, object-control/profile APIs, palette ownership, native-position helpers.

---

## D1: Rewind and architecture integrity

### Task D1.1: Restore the Spiker child slot graph

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/badniks/SpikerBadnikInstance.java`
- Modify only if the generic relink seam is missing: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kBadnikChildGraphRewind.java`

- [ ] Run the named test and preserve the exact-identity assertion. Expected red: Spiker's launcher slot is null after restore.

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind#spikerTopSpikeRestoresExactParentAndCooldownState" test
```

- [ ] Add an assertion that `leftLauncher` resolves to the exact recreated launcher identity; verify it fails before implementation.
- [ ] Restore Spiker's `leftLauncher`/top-spike slot from the recreated child id instead of accepting a structurally equal replacement.
- [ ] Run the named method, then the complete `TestS3kBadnikChildGraphRewind`; expected PASS.
- [ ] Commit as `fix: restore Spiker rewind child slot`, updating `CHANGELOG.md` and trailers.

### Task D1.2: Restore the Mantis managed child graph

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/badniks/MantisBadnikInstance.java`
- Modify only if the generic relink seam is missing: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kBadnikChildGraphRewind.java`

- [ ] Run `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind#mantisChildRelinksToRestoredParentAndParentSlot" test`; expected red before rewind because two parents own zero managed children.
- [ ] Add a pre-rewind assertion that each parent owns exactly one managed child; verify it fails.
- [ ] Route child creation through `spawnChild(...)`, register rewind identity, and restore parent/slot links by identity.
- [ ] Re-run the named method and full graph class; expected PASS.
- [ ] Commit as `fix: restore Mantis rewind child graph`, updating `CHANGELOG.md` and trailers.

### Task D1.3: Reconcile MGZ boss compact policy and annotations

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxEggCapsuleInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzDrillingRobotnikInstance.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindPolicyRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kBadnikChildGraphRewind.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindFieldDispositionGuard.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java`

- [ ] Run `TestRewindFieldDispositionGuard`, `TestRewindArchitectureGuard`, and `TestRewindTransientGuard`. Expected red: undisposed `childComponents`, one new deferred annotation, three transient annotations, including redundant `airZoomCueRenderer`.
- [ ] Add a graph round-trip assertion proving `childComponents` recreates the exact managed children and relationships.
- [ ] Register gameplay graph fields in the compact/reference policy; remove renderer/cache annotations already covered by central default-transient policy. Do not classify `childComponents` as transient.
- [ ] Run:

```powershell
mvn "-Dtest=TestRewindFieldDispositionGuard,TestRewindArchitectureGuard,TestRewindTransientGuard,TestS3kBadnikChildGraphRewind" test
```

Expected: PASS with no blanket baseline count increase.
- [ ] Commit as `fix: capture MGZ boss rewind graph`, updating `CHANGELOG.md` and trailers.

### Task D1.3a: Centralize origin-integrated CNZ structural rewind policies

This task was added after the latest `origin/develop` integration. It does not add a red JUnit method identity: the nine new annotation occurrences are diagnostics from the already-ledgered `TestRewindArchitectureGuard#objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage` method owned by D1.3. The historical `36 develop / 179 union / 83 in-scope` method accounting therefore remains unchanged. Do not add these occurrences to `TestRewindArchitectureGuard.ALLOWED_ANNOTATION_COUNTS`.

Root-cause triage shows three distinct dispositions. The `boss` fields on `CnzEndBossArmChild`, `CnzEndBossFieldChild`, `CnzEndBossMagnetChild`, and `CnzEndBossRobotnikShipChild` are already covered by `DefaultObjectRewindPolicies.STRUCTURAL_OBJECT_FIELD_NAMES`. The three `ship` fields are the same constructor/recreate structural-parent pattern and should be covered by the central structural field-name policy. The flash `owner` back-link and field-child `xOffset` are class-specific derived state: add exact `TRANSIENT` policies, because the button's captured `spawnedFlash` link plus `afterRewindRestoreSettled()` rebuilds `owner`, while `xOffset` is deterministically reconstructed from captured spawn subtype. Existing D1.4a graph tests prove every boss/ship identity and the signed field offsets; strengthen the flash graph test to prove the derived owner back-link. If any focused graph assertion fails, stop and implement the missing capture/recreate behavior instead of marking that field transient.

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/DefaultObjectRewindPolicies.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzLightsFlashChildInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossArmChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossExplosionControllerChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossFieldChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossMagnetChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRobotnikFlameChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRobotnikHeadChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRobotnikShipChild.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindPolicyRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kCnz2CutsceneButtonGraphRewind.java`
- Verify: `src/test/java/com/openggf/game/rewind/TestS3kCnzEndBossGraphRewind.java`
- Verify: `src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java`
- Verify: `src/test/java/com/openggf/game/rewind/schema/TestRewindFieldDispositionGuard.java`
- Verify: `src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java`

- [ ] Reproduce the exact origin-integrated architecture red:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestRewindArchitectureGuard#objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage" test
```

Expected: one failing method reporting exactly nine unexpected `@RewindTransient` occurrences: one each in `CnzLightsFlashChildInstance`, `CnzEndBossArmChild`, `CnzEndBossExplosionControllerChild`, `CnzEndBossMagnetChild`, `CnzEndBossRobotnikFlameChild`, `CnzEndBossRobotnikHeadChild`, and `CnzEndBossRobotnikShipChild`, plus two in `CnzEndBossFieldChild`.

- [ ] Add `defaultObjectPolicyCentralizesCnzDerivedAndStructuralLinks` to `TestRewindPolicyRegistry`. Resolve package-private boss children with `Class.forName(...)`, then assert `RewindPolicyRegistry.policyForAudit(...) == TRANSIENT` for all four `boss` fields, all three `ship` fields, `CnzLightsFlashChildInstance.owner`, and `CnzEndBossFieldChild.xOffset`. Run it before changing the policy. Expected: FAIL for `ship`, `owner`, and `xOffset`; the `boss` assertions already pass through the existing structural-name rule.

```powershell
mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindPolicyRegistry#defaultObjectPolicyCentralizesCnzDerivedAndStructuralLinks" test
```

- [ ] Add `ship` to `DefaultObjectRewindPolicies.STRUCTURAL_OBJECT_FIELD_NAMES`. Add exact `TRANSIENT` entries for `CnzLightsFlashChildInstance.owner` and `CnzEndBossFieldChild.xOffset`, with comments naming their authoritative reconstruction paths. Do not add a package-wide rule or broaden `owner`, because other owner references have captured/deferred semantics.

- [ ] Remove all nine `@RewindTransient` annotations and now-unused imports from the eight classes. Do not alter their fields, constructors, `recreateForRewind(...)`, or relink callbacks unless the next graph-test step demonstrates a real restore defect.

- [ ] In `TestS3kCnz2CutsceneButtonGraphRewind#cnz2CutsceneButtonRestoresSpawnedFlashLinkWithoutDropsOrStaleRefs`, assert that the recreated flash's private `owner` is the exact recreated button after restore. Run it together with the three D1.4a end-boss graph methods. Expected: PASS, proving the new central transient policies describe derived structural state rather than hiding state loss.

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestS3kCnz2CutsceneButtonGraphRewind#cnz2CutsceneButtonRestoresSpawnedFlashLinkWithoutDropsOrStaleRefs,com.openggf.game.rewind.TestS3kCnzEndBossGraphRewind#nativeGraphRestoresExactShipHeadMagnetAndArmIdentities+chargeGraphRestoresExactFieldIdentitiesAndOffsets+defeatGraphRestoresExactExplosionFlameAndBoundaryControllerIdentities" test
```

- [ ] Run the focused policy test, both complete CNZ graph classes, and all three annotation/disposition guards:

```powershell
mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindPolicyRegistry#defaultObjectPolicyCentralizesCnzDerivedAndStructuralLinks,com.openggf.game.rewind.TestS3kCnz2CutsceneButtonGraphRewind,com.openggf.game.rewind.TestS3kCnzEndBossGraphRewind,com.openggf.game.rewind.TestRewindArchitectureGuard#objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage,com.openggf.game.rewind.schema.TestRewindFieldDispositionGuard#noNewSilentlyDroppedRewindFieldsBeyondBaseline,com.openggf.game.rewind.TestRewindTransientGuard#fieldsCoveredByDefaultTransientPolicyDoNotNeedExplicitAnnotations" test
```

Expected: all selected tests PASS; annotation growth is zero; graph identity, back-links, signed offsets, and mutable child scalars remain exact; no architecture or field-disposition baseline changes.

- [ ] Commit as `fix: centralize CNZ structural rewind policies`, updating `CHANGELOG.md` and all required policy trailers.

### Task D1.4: Reconcile parent-dependent and tail inventories

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestParentDependentGraphCoverageGuard.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestRemainingRewindTailInventory.java`
- Modify: `src/test/resources/rewind/parent-dependent-graph-coverage-baseline.txt`
- Modify: `src/test/resources/rewind/round-trip-tail-inventory.txt`
- Test production graph classes named by each changed row.

- [ ] Run both guards. Expected red totals: tail moved from `842/664/178` to `858/672/178`, with new HCZ/MGZ no-probe and parent-dependent entries.
- [ ] For implemented HCZ/MGZ classes, add focused recreate/relink tests named in the inventory row and move them into the covered bucket only after those tests pass.
- [ ] For a genuinely unfinished S&K class, add one itemized debt row with class name, missing recreate path, owning unfinished zone, and reason; never ratchet only the aggregate totals.
- [ ] Run both guards plus every graph test named by a changed row; expected PASS and internally consistent totals.
- [ ] Commit as `test: reconcile rewind graph inventory` with `Changelog: n/a: rewind coverage inventory and tests`.

### Task D1.4a: Close origin-integrated CNZ end-boss rewind gaps

This task was added after merging `origin/develop` at `00498f16e` into the remediation branch. The merge did not add new red JUnit method identities: it exposed eight new object-class diagnostics inside the two D1.4 guard methods that were already part of the original 36-red develop ledger. Therefore the historical `36 develop / 179 union / 83 in-scope` method accounting remains unchanged while the current object-class sweep grows from `858/672/186` to `871/677/186` and has eight non-passing CNZ entries.

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestS3kCnzEndBossGraphRewind.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestGraphCoveredIsolatedProbeClassification.java`
- Modify: `src/test/java/com/openggf/game/rewind/RewindRoundTripHarness.java`
- Modify: `src/test/resources/rewind/round-trip-tail-inventory.txt`
- Verify unchanged/empty: `src/test/resources/rewind/parent-dependent-graph-coverage-baseline.txt`
- Modify only when a new focused graph test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossInstance.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossArmChild.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossBoundaryController.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossExplosionControllerChild.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossFieldChild.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossMagnetChild.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRobotnikFlameChild.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRobotnikHeadChild.java`
- Modify only when its focused test proves a production restore defect: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRobotnikShipChild.java`
- Modify only when recreate ordering/link lookup is the proven root cause: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossRewindLinks.java`

- [ ] Reproduce the origin-integrated red set exactly:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestParentDependentGraphCoverageGuard#parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests,com.openggf.game.rewind.TestRemainingRewindTailInventory#remainingRoundTripTailMatchesInventory" test
```

Expected: two failing methods. The tail diagnostic is `total=871 passed=677 graphCovered=186 noCodec=0`; `no-probe-ctor` adds `CnzEndBossArmChild`, `CnzEndBossBoundaryController`, `CnzEndBossFieldChild`, `CnzEndBossRobotnikFlameChild`, and `CnzEndBossRobotnikHeadChild`; `parent-dependent` adds `CnzEndBossExplosionControllerChild`, `CnzEndBossMagnetChild`, and `CnzEndBossRobotnikShipChild`.

- [ ] Add three phase-real round-trip tests to `TestS3kCnzEndBossGraphRewind`: `nativeGraphRestoresExactShipHeadMagnetAndArmIdentities`, `chargeGraphRestoresExactFieldIdentitiesAndOffsets`, and `defeatGraphRestoresExactExplosionFlameAndBoundaryControllerIdentities`. Before restore, record every participating object's `ObjectRefId` plus one meaningful non-default scalar per class. After an out-of-place restore, resolve by the recorded id and assert `assertNotSame(source, restored)`, the exact restored parent reference, boss/ship slot reference where one exists, multiplicity, and the recorded scalar. Do not accept count-only, class-name-only, or structurally equal replacement assertions.

The native graph test must cover all four arms independently, the boss's exact `magnetChild`, and the ship's exact head. The charge test must cover both field children and their signed offsets. The defeat test must cover the ship-owned explosion controller and flame plus every live gradual boundary controller, including axis, target, and accumulator state.

- [ ] Run the three focused graph methods before changing classification. Expected: either PASS, establishing existing production graph correctness, or a focused failure naming the exact lost identity/link/scalar. If a method fails, make the smallest production recreate-order or relink fix proven by that assertion and rerun until all three pass.

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestS3kCnzEndBossGraphRewind#nativeGraphRestoresExactShipHeadMagnetAndArmIdentities+chargeGraphRestoresExactFieldIdentitiesAndOffsets+defeatGraphRestoresExactExplosionFlameAndBoundaryControllerIdentities" test
```

- [ ] Only after those graph tests pass, classify the eight owner/session-dependent classes as graph-covered. Add all eight exact FQNs to `RewindRoundTripHarness.GRAPH_COVERED_ISOLATED_PROBE_CLASSES`, each naming `TestS3kCnzEndBossGraphRewind`, and add `cnzEndBossChildrenAreReportedAsGraphCovered` to `TestGraphCoveredIsolatedProbeClassification` with this exact set:

```java
Map<String, String> expected = Map.of(
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossArmChild", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossBoundaryController", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossExplosionControllerChild", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossFieldChild", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossMagnetChild", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikFlameChild", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikHeadChild", CNZ_END_BOSS_GRAPH_TEST,
        "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikShipChild", CNZ_END_BOSS_GRAPH_TEST);
expected.forEach(this::assertGraphCovered);
```

Do not add any of these classes to a debt/baseline bucket merely to satisfy the aggregate guard. If investigation proves one class can and should be an isolated probe instead, add a probe-compatible constructor/recreate test for that class and let it increment `passed`; never both probe-pass and graph-classify the same class.

- [ ] Update `round-trip-tail-inventory.txt` from the measured post-fix sweep. With all eight using the owner-backed graph classification above, the exact green summary is `total=871 passed=677 graph-covered=194 no-codec=0 no-probe-ctor=0 parent-dependent=0 other-failure=0 count-mismatch=0 scalar-mismatch=0`. Keep `parent-dependent-graph-coverage-baseline.txt` empty because all three new parent-dependent classes now have executable evidence rather than accepted debt.

- [ ] Run the focused graph tests, classification test, and both inventories green:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestS3kCnzEndBossGraphRewind,com.openggf.game.rewind.TestGraphCoveredIsolatedProbeClassification#cnzEndBossChildrenAreReportedAsGraphCovered,com.openggf.game.rewind.TestParentDependentGraphCoverageGuard#parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests,com.openggf.game.rewind.TestRemainingRewindTailInventory#remainingRoundTripTailMatchesInventory" test
```

Expected: all selected tests PASS, no tail rows, no baseline debt, and the exact `871/677/194/0` aggregate above.

- [ ] Commit as `fix: close CNZ boss rewind graph after origin merge`. Update `CHANGELOG.md` only if production changes; otherwise use `Changelog: n/a: focused rewind graph evidence and executable inventory`, plus all required policy trailers.

### Task D1.5: Declare canonical touch-response profiles

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/BreakablePlatingObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzMinibossInstance.java`
- Modify: `src/main/java/com/openggf/game/profiles/touchresponse/TouchResponseProfile.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectPhysicsStandardizationGuard.java`

- [ ] Run `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations" test`; expected violations name BreakablePlating and MgzMiniboss hooks.
- [ ] Add focused tests for continuous plating callbacks and miniboss shield deflect.
- [ ] Declare both behaviors through canonical `TouchResponseProfile` and consume that profile from the hooks; do not add a guard baseline.
- [ ] Run focused tests and the named guard; expected PASS.
- [ ] Commit as `fix: declare object touch-response profiles`, updating `CHANGELOG.md`.

### Task D1.5a: Declare origin-integrated CNZ multi-region touch profiles

This task owns three diagnostics introduced inside the already-counted D1.5 guard method by the `origin/develop` integration at `6af04f87e`. It adds no JUnit method identity to the ledger and does not change the historical `36 develop / 179 union / 83 in-scope` accounting. The three CNZ publishers always return a non-null, native-centre `TouchRegion[]`; their missing declaration is canonical profile debt, not a reason to add them to `TRIAGED_TOUCH_PROFILE_HOOK_FILES`.

**Files:**
- Modify: `src/main/java/com/openggf/game/profiles/touchresponse/TouchResponseProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossArmChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/CnzEndBossMagnetChild.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/bosses/TestCnzEndBossTouchResponseProfiles.java`
- Verify unchanged: `src/test/java/com/openggf/level/objects/TestObjectPhysicsStandardizationGuard.java`

- [ ] Reproduce the exact origin-integrated diagnostic:

```powershell
mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations" test
```

Expected: one failing JUnit method whose only three violations are `TOUCH_PROFILE_HOOK_WITHOUT_PROFILE` for `CnzEndBossInstance`, `CnzEndBossArmChild`, and `CnzEndBossMagnetChild` at `getMultiTouchRegions()`.

- [ ] Add `TestCnzEndBossTouchResponseProfiles` with `bossArmAndMagnetDeclareCanonicalMultiRegionEnemyProfiles`. Construct one boss, one phase-zero arm, and one magnet in the package-local test. For each publisher, assert both profile overloads are declared on its concrete class, `getTouchResponseProfile()` equals `getTouchResponseProfile(true)`, and the result has exactly `NORMAL`, `continuousCallbacks=false`, `requiresRenderFlagForTouch=true`, `multiRegionSource=true`, `NONE` shield deflection with flags `0`, `STANDARD_ENEMY_KILL`, `MAIN_FULL_SIDEKICK_HURT_ONLY`, and `STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY`. Also assert each current `getMultiTouchRegions()` result is non-null and contains exactly one region at that object's native centre; this prevents satisfying the source guard with a profile that changes the existing collision geometry.

- [ ] Run the new test before production changes:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossTouchResponseProfiles#bossArmAndMagnetDeclareCanonicalMultiRegionEnemyProfiles" test
```

Expected: FAIL because the three concrete classes do not declare `getTouchResponseProfile()` and `getTouchResponseProfile(boolean)`.

- [ ] Add `TouchResponseProfile.standardEnemy(boolean multiRegionSource)` to the canonical `com.openggf.game.profiles.touchresponse` record. It must preserve every field from `standardEnemy()` while selecting `STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY` when `multiRegionSource` is true and `STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS` otherwise; make the existing no-argument factory delegate to `standardEnemy(false)`. In each CNZ class, cache the compatibility profile created with `com.openggf.level.objects.TouchResponseProfile.fromCanonical(TouchResponseProfile.standardEnemy(true))`, implement both overloads, and return that fixed multi-region profile. Do not call `fromProvider(this)` from the override, which would recurse through `getTouchResponseProfile`, and do not alter collision flags, regions, callbacks, or attack behavior.

- [ ] Run the focused profile test, the source guard, and the nearby CNZ behavior suites:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossTouchResponseProfiles,com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossChildren,com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossDefeatScatter,com.openggf.tests.TestS3kCnzEndBossHeadless,com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations" test
```

Expected: all selected tests PASS; the guard reports no CNZ hooks and neither its triage set nor any budget changes.

- [ ] Commit as `fix: declare CNZ boss touch-response profiles`, updating `CHANGELOG.md` and using the required policy trailers.

### Task D1.6: Route playable writes through NativePositionOps

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPlayerRuntime.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/PachinkoMagnetOrbObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
- Modify only if an operation is missing: `src/main/java/com/openggf/level/objects/NativePositionOps.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectPhysicsStandardizationGuard.java`

- [ ] Run `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionPlayableNativePositionRawPreserveSubpixelWriteFilesDoNotGrow" test`; expected five raw writes.
- [ ] Add focused integer/fraction assertions for slots X/Y, Pachinko Y, and cage X/Y.
- [ ] Replace the five raw writes with the semantically matching `NativePositionOps` calls.
- [ ] Run focused tests and named guard; expected PASS without a baseline change.
- [ ] Commit as `fix: route playable native position writes`, updating `CHANGELOG.md`.

### Task D1.7: Replace post-budget raw destruction calls

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossFallingDebrisChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxCollapseEmitter.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxDefeatPart.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxDrillChild.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/bosses/HczEndBossBladeImpactExplosion.java`
- Modify only if an operation is missing: `src/main/java/com/openggf/level/objects/ObjectLifetimeOps.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectPhysicsStandardizationGuard.java`

- [ ] Run `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectLifecycleRawCallCountsDoNotGrow" test`; expected count `585` versus `582`.
- [ ] Use baseline commit `0dfda47b77` to confirm eight additions and five removals (net +3) across the six listed files.
- [ ] Add a focused latched-versus-respawnable destruction test for each distinct lifecycle path, then replace all eight post-budget additions with the matching `ObjectLifetimeOps` operation.
- [ ] Run focused tests and named guard; expected PASS with budget still `582`.
- [ ] Commit as `fix: adopt MGZ object lifetime operations`, updating `CHANGELOG.md`.

### Task D1.8: Remove direct zone-event runtime access

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java`
- Modify only the existing event dependency surface needed to inject the service.
- Test: `src/test/java/com/openggf/game/TestZoneEventRuntimeAccessGuard.java`

- [ ] Add/retain a focused HCZ behavior test covering the operation currently reached through `GameServices`.
- [ ] Run the behavior test and guard; expected guard red names `Sonic3kHCZEvents`.
- [ ] Route the dependency through the event manager/context/provider pattern used by a passing event class; remove the direct `GameServices` reference.
- [ ] Run the focused HCZ test and complete guard; expected PASS.
- [ ] Commit as `refactor: inject HCZ event runtime dependency`, using explicit changelog justification if behavior is unchanged.

### Task D1.9: Split PlayerMovementRules at its narrow owner

**Files:**
- Modify: `src/main/java/com/openggf/game/rules/PlayerMovementRules.java`
- Modify: `src/main/java/com/openggf/game/rules/GameRules.java`
- Test: `src/test/java/com/openggf/tests/game/TestPerGameRuleArchitectureGuard.java`

- [ ] Run `typedRuleRecordsStaySmallEnoughToReview`; expected red: 23 components versus limit 22.
- [ ] Identify the newest component and add tests for its S1/S2/S3K values at the narrower provider/profile owner.
- [ ] Move that component into a cohesive existing/new sub-record and delegate from callers; do not raise the record-size limit or branch on game names.
- [ ] Run the guard and cross-game rule tests; expected PASS with identical values.
- [ ] Commit as `refactor: narrow player movement rule ownership` with documentation trailers.

### Task D1.10: Extract ObjectManager growth into its controller

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify as owned by the extracted block: `src/main/java/com/openggf/level/objects/ObjectPlacementController.java`
- Modify as owned by the extracted block: `src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java`
- Modify as owned by the extracted block: `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java`
- Test: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`

- [ ] Run `objectManagerFacadeStaysWithinExtractedCollaboratorBudget`; expected `2986 > 2914`.
- [ ] Before extraction, run the focused behavior tests for the newly grown section and add delegation assertions where none exist.
- [ ] Perform behavior-neutral extractions into existing collaborators; do not change budgets.
- [ ] Run focused behavior tests and the named guard; expected PASS.
- [ ] Commit as `refactor: extract object manager behavior` with explicit changelog justification.

### Task D1.11: Extract results/title-card transition preparation

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/InLevelTitleCardCoordinator.java`
- Test: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`

- [ ] Run `rootDispatchMethodsDoNotGrowBeyondCurrentBudgets`; expected `enterTitleCardFromResults` `98 > 91`.
- [ ] Add/retain transition delegation tests, perform behavior-neutral extraction, and keep the root method at or below 91.
- [ ] Run transition tests and named guard; expected PASS.
- [ ] Commit as `refactor: extract results title-card transition` with explicit changelog justification.

### Task D1.11a: Extract origin-integrated bonus-stage transition state

Run this leaf after D1.11. The earlier results/title-card extraction is already present; the current source reds instead come from the star-post return and live-ring carry-over blocks added to `enterBonusStage` and `doExitBonusStage` by recent `origin/develop` bonus-round-trip parity commits. Both overruns are one responsibility: capturing level state before a bonus-stage boundary and restoring it after the level reload. Keep loading, provider lifecycle, fades, title-card setup, audio, and `GameMode` changes in `GameLoop`.

**Files:**
- Create: `src/main/java/com/openggf/game/BonusStageTransitionCoordinator.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Create: `src/test/java/com/openggf/game/TestBonusStageTransitionCoordinator.java`
- Modify only if a delegation seam needs coverage: `src/test/java/com/openggf/TestGameLoop.java`
- Verify unchanged: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`
- Regression: `src/test/java/com/openggf/game/sonic3k/TestBonusStageReturnWaterRestore.java`
- Regression: `src/test/java/com/openggf/game/sonic3k/TestPachinkoTitleCardIntegration.java`

- [ ] Reproduce both exact guard methods together:

```powershell
mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard#rootDispatchMethodsDoNotGrowBeyondCurrentBudgets+releaseCriticalLargeClassesDoNotGrowWithoutExtraction" test
```

Expected: two failing JUnit methods with only these diagnostics: `GameLoop#doExitBonusStage 167 > 142`, `GameLoop#enterBonusStage 119 > 86`, and `GameLoop 3024 > 3005` effective source lines.

- [ ] Write `TestBonusStageTransitionCoordinator#captureEntryUsesActiveStarPostStateAndRomZeroCheckpointIndex`. Build an active `RespawnState` with distinct saved player coordinates, checkpoint subtype/activation mark, plus mocked level game state, event routines, camera, collision bits, timer, and water height. Assert the returned `EntryCapture` contains a `BonusStageState` whose player position is the star-post position rather than the live player position, whose `savedLastStarPostHit()` is ROM value `0`, and whose remaining ring/timer/camera/event/collision/water fields exactly match the inputs; assert `pendingStarPostActivationMark()` retains the activation high-water. Add `captureEntryWithoutCheckpointUsesLivePlayerPositionAndNoActivationMark` for the inactive/null-checkpoint path.

- [ ] Write `TestBonusStageTransitionCoordinator#restoreReturnStatePreservesLiveInteriorRingsCheckpointMarkAndRuntimeState`. Given a saved state, a distinct live interior HUD ring total, a saved activation mark, rewards containing both rings and lives, and mocks/fakes for the checkpoint, event manager, playable, camera, water system, game state, and life sink, invoke the coordinator after the simulated level reload. Assert it restores the checkpoint index and activation mark separately, restores both event routine bytes, restores player centre/collision bits and zeroes all three speeds, resolves the supplied shield without adding reward rings, clears bonus high-priority state, restores camera/max-Y/water/timer, carries the live interior ring total exactly, and awards only `rewards.lives()`. Add `captureInteriorExitRingCountFallsBackToSavedEntryRingsWithoutLiveGameState` and assert it returns `savedState.savedRingCount()`; add the defensive null-state case separately and assert `0`.

- [ ] Run only the new coordinator test before creating production code:

```powershell
mvn "-Dtest=com.openggf.game.TestBonusStageTransitionCoordinator" test
```

Expected: FAIL to compile because `BonusStageTransitionCoordinator` and its `EntryCapture` contract do not exist.

- [ ] Implement a gameplay-agnostic `BonusStageTransitionCoordinator` under `com.openggf.game`. Move, without semantic edits, the entry snapshot block currently in `GameLoop.enterBonusStage` into `captureEntry(...)`, returning an immutable `EntryCapture(BonusStageState savedState, int pendingStarPostActivationMark)`. Move `captureInteriorExitRingCount(...)` and the post-reload checkpoint/event/player/camera/water/HUD/life restoration into focused coordinator methods. Pass explicit `LevelManager`, `Camera`, `WaterSystem`, playable, event-provider, shield, rewards, and life-award dependencies; the collaborator must not call `GameServices`, change modes, load levels, control fades/audio/title cards, or own a `BonusStageProvider`. Preserve the current ordering: capture live rings before `provider.onExit()`, load the level in `GameLoop`, then restore checkpoint/event/player/camera/water/HUD/lives.

- [ ] Replace the moved blocks in `GameLoop` with thin calls. Keep `pendingBonusReturnStarPostMark` only if it remains the minimal boundary handoff; otherwise store the returned mark in a narrowly named coordinator field, with `-1` retaining its existing no-entry meaning. Keep `resolveShieldToRestore`, `pendingBonusStageShieldRestore`, provider/session registration, level-load exception paths, title-card initialization, music, fades, and game-mode listener dispatch in `GameLoop`. Do not move unrelated bonus gameplay or increase any architecture budget.

- [ ] Run the coordinator tests and bonus-boundary regression set:

```powershell
mvn "-Dtest=com.openggf.game.TestBonusStageTransitionCoordinator,com.openggf.TestGameLoop#testDoExitBonusStageDoesNotWriteSaveForActiveSlot,com.openggf.game.sonic3k.TestBonusStageReturnWaterRestore,com.openggf.game.sonic3k.TestPachinkoTitleCardIntegration,com.openggf.game.sonic3k.TestBonusStageLifecycle" test
```

Expected: all selected tests PASS; star-post position/activation, live-ring carry-over, water restoration, title-card preparation, and provider lifecycle are unchanged.

- [ ] Run both architecture guards and the complete `TestGameLoop` class:

```powershell
mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard#rootDispatchMethodsDoNotGrowBeyondCurrentBudgets+releaseCriticalLargeClassesDoNotGrowWithoutExtraction,com.openggf.TestGameLoop" test
```

Expected: all selected tests PASS; `enterBonusStage <= 86`, `doExitBonusStage <= 142`, and `GameLoop <= 3005` effective lines with the existing budgets unchanged.

- [ ] Commit as `refactor: extract bonus-stage transition state` with `Changelog: n/a: behavior-neutral responsibility extraction` and the remaining required policy trailers.

### Task D1.12: Extract playable-sprite growth into its controller

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/com/openggf/sprites/playable/PlayableSpriteController.java`
- Test: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`

- [ ] Run `releaseCriticalLargeClassesDoNotGrowWithoutExtraction`; expected `3233 > 3159`.
- [ ] Add/retain delegation tests, perform behavior-neutral extraction, and keep the class at or below 3159.
- [ ] Run playable tests and named guard; expected PASS.
- [ ] Commit as `refactor: extract playable sprite behavior` with explicit changelog justification.

## D2: Shared physics and collision

### Task D2.1: Repair plane-aware terrain reflection tests

**Files:**
- Modify: `src/test/java/com/openggf/physics/TestObjectTerrainUtils.java`

- [ ] Run the class; expected four `NoSuchMethodException` errors because helpers request obsolete six-argument methods.
- [ ] Update floor and wall reflection signatures to append `byte.class`, and invoke with `(byte) 0`. Do not change production terrain math or expected distances/angles.
- [ ] Run the class; expected PASS including `-13`, `-5`, `-5`, and `-21` floor cases.
- [ ] Commit as `test: update terrain utility layer fixtures` with `Changelog: n/a: test API drift only`.

### Task D2.2: Preserve subpixel only for exact-edge side contact

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java`
- Test: `src/test/java/com/openggf/level/objects/TestSolidObjectManager.java`

- [ ] Run the named test; expected red: nonzero overlap retains low word `0x8000` instead of snapping to zero.
- [ ] Keep exact `distX == 0` motion on the preserve-subpixel path. Route nonzero correction through the native integer-word snap unless the broader profile explicitly preserves edge subpixel motion.
- [ ] Run the named method, complete solid manager tests, and representative S1/S2/S3K solid-object tests; expected PASS.
- [ ] Commit as `fix: limit exact-edge subpixel preservation`, updating `CHANGELOG.md`.

### Task D2.3: Separate forced-spin roll animation from the aliased spindash byte

**Files:**
- Modify: `src/main/java/com/openggf/sprites/animation/ScriptedVelocityAnimationProfile.java`
- Modify only if ownership belongs there: `ForcedSpinObjectInstance.java`
- Test: `src/test/java/com/openggf/game/sonic2/objects/TestForcedSpinObjectInstance.java`

- [ ] Run the named test; expected red: resolver returns null instead of ROLL while rolling+pinball+aliased byte are set.
- [ ] Add a genuine charging-spindash regression test and retain normal pinball/roll coverage.
- [ ] Resolve roll when pinball mode owns the aliased byte; keep genuine charging spindash selection unchanged and do not clear movement state.
- [ ] Run the complete forced-spin and shared animation-profile suites; expected PASS.
- [ ] Commit as `fix: preserve forced-spin roll animation`, updating `CHANGELOG.md`.

### Task D2.4: Align floating-platform top-solid expectation with ROM profile

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestTopSolidRoutineProfileAdoption.java`

- [ ] Run the class; expected red compares generic `topSolid(true)` with deliberate platform-snap=false, ground-height=true behavior.
- [ ] Replace the borrowed generic expected instance with independent assertions for the ROM `SolidObjectTop` flags (`height + 1`, relative `y_pos += d0 + 3`). Do not modify production behavior.
- [ ] Run floating-platform contact/headless tests and this guard; expected PASS.
- [ ] Commit as `test: assert floating-platform ROM top-solid profile`.

### Task D2.5: Restore rock sidekick push cadence

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestAizLrzRockPlayerParticipation.java`

- [ ] Reproduce rock red (`0x1000` instead of `0x0FFF`) and identify the false sustained-contact gate for native P2; preserve the existing “first sidekick contact does not move main player” test.
- [ ] Make the selected participant consume the shared push cadence and write the rock centre with the intended integer/subpixel operation; do not substitute P1.
- [ ] Run the full rock class and shared participation tests; expected PASS.
- [ ] Commit as `fix: restore sidekick rock push cadence`, updating `CHANGELOG.md`.

### Task D2.6: Release invalid CNZ cylinder riders without jump setup

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] Run the fully qualified named method; expected hurt rider remains object-controlled.
- [ ] Add/retain assertions for cleared slot/support/control, jumping=false, and ySpeed=0.
- [ ] Make forced invalid-rider release relinquish cylinder ownership unconditionally without invoking jump setup.
- [ ] Run neighboring release methods and the full class; expected PASS.
- [ ] Commit as `fix: release invalid CNZ cylinder rider`, updating `CHANGELOG.md`.

## D3: S3-era gameplay and rendering

### Task D3.1: Apply AIZ2 boss-activation player priority

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java`
- Test: `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAIZEvents.java`

- [ ] Run the fully qualified `aiz2EndBossActivationKeepsSonicHighPriorityAtWaterfall`; expected Sonic remains low priority.
- [ ] Assert priority changes on the native activation transition, then apply it through the event's player-participation surface.
- [ ] Run the full AIZ events class; expected PASS.
- [ ] Commit as `fix: restore AIZ2 boss player priority`, updating `CHANGELOG.md`.

### Task D3.2: Resolve the ICZ2 PalPointers palette restore

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kBossDefeatSignpostFlow.java`
- Test: `src/test/java/com/openggf/tests/TestS3kIczMinibossObject.java`

- [x] Run the named PalPointers method; the assertion stopped after the installation call plus 119 `WAIT_FADE` decrements (call 120), one object call before cleanup (call 121).
- [x] Preserve the registry-backed `S3kPaletteWriteSupport`/immediate-resolution path and assert the ICZ miniboss owner on both ends of the restored line.
- [x] Run the full ICZ class and palette-ownership tests; expected PASS.
- [x] Commit as `test: align ICZ2 post-boss palette cleanup`, updating `CHANGELOG.md`.

### Task D3.3: Initialize the CNZ electric ball from Obj51

**Files:**
- Inspect: `src/main/java/com/openggf/game/sonic2/objects/bosses/CNZBossElectricBall.java`
- Test: `src/test/java/com/openggf/tests/TestCNZBossArtAndAnimation.java`

- [x] Run `electricBallUsesObj51ProjectileMappingFrames`; observed frame `0x12` at X=0 instead of `0x2A46`.
- [x] Trace Obj51 allocation ownership: production already captures the ROM-visible parent `x_pos` in the child `ObjectSpawn`; correct the synthetic fixture to use that canonical coordinate and assert attached native centre coordinates before first render. Existing split-position coverage remains in `TestSonic2CNZBossCollision`.
- [x] Run the full class, CNZ boss collision/position suite, and ROM-backed Obj51 mapping decoder; all 18 tests pass.
- [x] Commit as `test: align CNZ electric ball mapping fixture`, updating `CHANGELOG.md`.

### Task D3.4: Restore the S3K seamless-results ready handshake

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/S3kTransitionWriteSupport.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kResultsScreenObjectInstance.java`

- [x] Run the named HCZ/MGZ method; confirmed the native ready flag remains clear when no retained control owner exists.
- [x] Add a provider/runtime-state assertion for an armed seamless handoff, and set the ready flag from that state without raw zone comparisons.
- [x] Run the full results class plus transition bridge, HCZ event, CNZ event-flow, in-level title-card, and act-transition tests; all 48 tests pass.
- [x] Commit as `fix: restore seamless results handoff`, updating `CHANGELOG.md`.

### Task D3.5: Poll MGZ collapse rumble every 16 frames

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java`
- Test: `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kMgz2QuakeEvents.java`

- [x] Run `collapsePlaysBigRumbleEvery16Frames`; reproduced the stale fixture's one-vs-two failure, then corrected the fixture from the ROM sequence so the pending request and positive `$14` startup shake require zero premature `BIG_RUMBLE` calls.
- [x] Preserve the pre-dispatch `RUMBLE_2` handoff poll, emit `BIG_RUMBLE` only from the initialized scrolling-collapse path, and retain the visible gameplay counter's ROM `(counter - 1) & $F` cadence; added startup, handoff, and off-cadence assertions.
- [x] Run the full 20-test quake class plus collapse, end-boss, event-rewind, event-schema, runtime-access, and architecture coverage; all 194 targeted tests pass.
- [x] Commit as `fix: restore MGZ collapse rumble cadence`, updating `CHANGELOG.md`.

### Task D3.6: Restore the MGZ miniboss return-swing routine boundary

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzMinibossInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestMgzMinibossInstance.java`

- [x] Run `upsideDownStatePersistsThroughDropAndRiseThenClearsBeforeTunnelUp`; reproduced the stale assertion collapsing the native extra wait callback into the tunnel-up handoff.
- [x] Preserve the existing native callback transition so the flip persists through drop/rise, clears in `StartNextCycle` while routine `$12` remains active for the complete `$1F` countdown (31 dispatches through zero, callback on the 32nd), and enters tunnel-up only from the later `RestartRumble` callback; tightened the focused assertions around both boundaries and the full wait duration.
- [x] Run the full class plus touch, art, and rewind coverage; the D3.6 assertion and all related coverage pass, with only the independently catalogued D3.7 defeat-camera assertion still red in the full class.
- [x] Commit as `fix: restore MGZ miniboss return routine`, updating `CHANGELOG.md`.

### Task D3.7: Lock all MGZ defeat-camera bounds atomically

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzMinibossInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestMgzMinibossInstance.java`

- [x] Run `defeatCameraHelperLocksBothCameraBoundsWhileScrolling`; reproduced X/minX=`0x2E00` with stale maxX=`0x2DFF`.
- [x] Update X, minX, and maxX atomically from the persistent camera owner until the same target is reached; tightened coverage around the intermediate lock, target-time helper release, and restored entry at or above the target without overshoot.
- [x] Run the full class plus related camera, MGZ-event, and rewind tests; all selected tests pass.
- [x] Commit as `fix: lock MGZ defeat camera bounds`, updating `CHANGELOG.md`.

### Task D3.8: Order MGZ thruster flames around the body bucket

**Files:**
- Verify canonical owner: `src/main/java/com/openggf/game/sonic3k/objects/MgzDrillingRobotnikInstance.java`
- Verify canonical profile: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossRenderChild.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestMgzDrillingRobotnikInstance.java`

- [x] Run `splitsThrusterFlamesIntoRomPriorityBuckets`; reproduced the stale role-order traversal, first as a missing ship frame 9 after the rear flame and then as a missing body frame after tightening the complete painter sequence.
- [x] Confirm the production owners already publish the disassembly priorities (`$300` rear flame/body, `$280` pod, `$180` front flame), and make the managed-graph helper consume the runtime bucket, tile-priority, and SST-slot ordering contract rather than enum order.
- [x] Run the full class plus related render/art and rewind tests; 2,810 selected checks passed with one ROM-conditional skip. The full music-transition class still exposes only its separately catalogued D3.9–D3.11 failures (managed rear-drill assertion, wait boundary, and flame gameplay-clock dependency).
- [x] Commit as `fix: restore MGZ thruster priority buckets`, updating `CHANGELOG.md`.

### Task D3.9: Assert the managed MGZ rear-drill child

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kMgzBossMusicTransition.java`
- Modify only if managed render order is wrong: `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossRenderChild.java`

- [x] Run `endBossDrawsDrillPieceBehindMainBodyWhenItAppears`; reproduced the obsolete parent-inline assertion: only the parent body frame was emitted when the managed child graph was skipped.
- [x] Replace it with a managed-graph assertion for `ROLE_STATIC_BACK`, frame 1, offset `(-0x14,+0x0F)` before the parent body; production already matches `ChildObjDat_6D7C0`, `word_6D77C` (`$380`), and `ObjDat_MGZDrillBoss` (`$300`).
- [x] Run the exact method and related render-child, handoff, Knuckles-graph, rewind-codec, and art-provider/PLC tests; all selected D3.9 checks pass. The full music class still exposes only the separately catalogued D3.10-D3.12 failures.
- [x] Commit as `test: assert managed MGZ drill child`; no production change was required.

### Task D3.10: Start MGZ boss music after the 120-decrement Obj_Wait delay

**Files:**
- Test: `src/test/java/com/openggf/tests/TestS3kMgzBossMusicTransition.java`

- [x] Run `drillingRobotnikFadesZoneMusicBeforeDelayedBossMusic`; reproduced its stale expectation that boss music would start on frame 119.
- [x] Preserve the trace-accurate production cadence: the placement init pass writes `$2E=120`, frames 1 through 120 perform the 120 `Obj_Wait` decrements, and the signed-underflow callback starts End Boss music on frame 121.
- [x] Strengthen coverage for one fade, no early play, fade-before-theme order, and one boss-music command. The exact method passes.
- [x] Run the full class; the five completed D3.9/D3.10 checks pass and only the separately planned D3.11 gameplay-clock null dereference remains, which also prevents D3.12 from evaluating its pre-existing profile.
- [x] Commit as `test: preserve MGZ boss music callback timing`, updating `CHANGELOG.md`; no production change was required because the July trace-cadence fix already matches the ROM.

### Task D3.11: Drive thruster touch from the gameplay clock

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzDrillingRobotnikInstance.java`
- Modify only if absent: the existing gameplay-clock accessor in `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Test: `src/test/java/com/openggf/tests/TestS3kMgzBossMusicTransition.java`

- [x] Run `endBossThrusterFlameTouchUsesGameplayFrameWithoutRenderPass`; reproduced the null object-manager dereference while reading the V-int phase.
- [x] Preserve the alternating no-render assertions and source phase from the injected runtime gameplay/V-int clock, whose compatible default uses zero phase when no manager exists and the manager-owned replay offset when present.
- [x] Run the full class; all seven methods pass without a render dependency or test-only branch. The canonical multi-region profile already existed in ancestor commit `0c7b3b4f95`; D3.11 solely removed the evaluation null dereference that made D3.12 red.
- [x] Commit as `fix: drive MGZ thruster touch from gameplay clock`, updating `CHANGELOG.md`.

### Task D3.12: Expose MGZ multi-region touch dispatch

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MgzDrillingRobotnikInstance.java`
- Modify only if canonical vocabulary lacks it: `src/main/java/com/openggf/game/profiles/touchresponse/TouchResponseProfile.java`
- Test: `src/test/java/com/openggf/tests/TestS3kMgzBossMusicTransition.java`

- [x] Run `endBossTouchProfileExposesMultiRegionDispatch`; after D3.11, the method passes because the canonical profile from ancestor commit `0c7b3b4f95` can now evaluate its multi-touch regions through the gameplay clock without a render pass.
- [x] Confirm the existing assertions cover `multiRegionSource()` and `STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY`; the adjacent no-render touch test evaluates the actual body, drill, and alternating flame regions from the gameplay clock.
- [x] Run the full class and touch standardization guard. All seven MGZ methods pass; the guard's only violations are the separately owned latest-origin CNZ multi-region classes (`CnzEndBossInstance`, `CnzEndBossArmChild`, and `CnzEndBossMagnetChild`), not MGZ.
- [x] No production, test, or changelog change is required: the profile predates the initial plan, and D3.11 removed its sole evaluation blocker. Record this stale-red disposition in the plan rather than creating an empty behavior commit.

## Literal Maven command contract

Each task uses its row below for the initial red run and final green regression run. Run commands as two separate PowerShell invocations; a row never abbreviates a class or method name.

| Task | Red command | Green regression command |
|---|---|---|
| D1.1 | `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind#spikerTopSpikeRestoresExactParentAndCooldownState" test` | `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind" test` |
| D1.2 | `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind#mantisChildRelinksToRestoredParentAndParentSlot" test` | `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind" test` |
| D1.3 | `mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindFieldDispositionGuard#noNewSilentlyDroppedRewindFieldsBeyondBaseline,com.openggf.game.rewind.TestRewindArchitectureGuard#objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage,com.openggf.game.rewind.TestRewindTransientGuard#fieldsCoveredByDefaultTransientPolicyDoNotNeedExplicitAnnotations" test` | `mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindFieldDispositionGuard,com.openggf.game.rewind.TestRewindArchitectureGuard,com.openggf.game.rewind.TestRewindTransientGuard,com.openggf.game.rewind.TestS3kBadnikChildGraphRewind" test` |
| D1.3a | `mvn "-Dtest=com.openggf.game.rewind.TestRewindArchitectureGuard#objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage" test` | `mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindPolicyRegistry#defaultObjectPolicyCentralizesCnzDerivedAndStructuralLinks,com.openggf.game.rewind.TestS3kCnz2CutsceneButtonGraphRewind,com.openggf.game.rewind.TestS3kCnzEndBossGraphRewind,com.openggf.game.rewind.TestRewindArchitectureGuard#objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage,com.openggf.game.rewind.schema.TestRewindFieldDispositionGuard#noNewSilentlyDroppedRewindFieldsBeyondBaseline,com.openggf.game.rewind.TestRewindTransientGuard#fieldsCoveredByDefaultTransientPolicyDoNotNeedExplicitAnnotations" test` |
| D1.4 | `mvn "-Dtest=com.openggf.game.rewind.TestParentDependentGraphCoverageGuard#parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests,com.openggf.game.rewind.TestRemainingRewindTailInventory#remainingRoundTripTailMatchesInventory" test` | `mvn "-Dtest=com.openggf.game.rewind.TestParentDependentGraphCoverageGuard,com.openggf.game.rewind.TestRemainingRewindTailInventory" test` |
| D1.4a | `mvn "-Dtest=com.openggf.game.rewind.TestParentDependentGraphCoverageGuard#parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests,com.openggf.game.rewind.TestRemainingRewindTailInventory#remainingRoundTripTailMatchesInventory" test` | `mvn "-Dtest=com.openggf.game.rewind.TestS3kCnzEndBossGraphRewind,com.openggf.game.rewind.TestGraphCoveredIsolatedProbeClassification#cnzEndBossChildrenAreReportedAsGraphCovered,com.openggf.game.rewind.TestParentDependentGraphCoverageGuard#parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests,com.openggf.game.rewind.TestRemainingRewindTailInventory#remainingRoundTripTailMatchesInventory" test` |
| D1.5 | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations" test` | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard" test` |
| D1.5a | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossTouchResponseProfiles,com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossChildren,com.openggf.game.sonic3k.objects.bosses.TestCnzEndBossDefeatScatter,com.openggf.tests.TestS3kCnzEndBossHeadless,com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations" test` |
| D1.6 | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionPlayableNativePositionRawPreserveSubpixelWriteFilesDoNotGrow" test` | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard" test` |
| D1.7 | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard#productionObjectLifecycleRawCallCountsDoNotGrow" test` | `mvn "-Dtest=com.openggf.level.objects.TestObjectPhysicsStandardizationGuard" test` |
| D1.8 | `mvn "-Dtest=com.openggf.game.TestZoneEventRuntimeAccessGuard#zoneEventImplementations_shouldNotReferenceGameServicesDirectly" test` | `mvn "-Dtest=com.openggf.game.TestZoneEventRuntimeAccessGuard" test` |
| D1.9 | `mvn "-Dtest=com.openggf.tests.game.TestPerGameRuleArchitectureGuard#typedRuleRecordsStaySmallEnoughToReview" test` | `mvn "-Dtest=com.openggf.tests.game.TestPerGameRuleArchitectureGuard" test` |
| D1.10 | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard#objectManagerFacadeStaysWithinExtractedCollaboratorBudget" test` | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard" test` |
| D1.11 | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard#rootDispatchMethodsDoNotGrowBeyondCurrentBudgets" test` | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard" test` |
| D1.11a | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard#rootDispatchMethodsDoNotGrowBeyondCurrentBudgets+releaseCriticalLargeClassesDoNotGrowWithoutExtraction" test` | `mvn "-Dtest=com.openggf.game.TestBonusStageTransitionCoordinator,com.openggf.TestGameLoop,com.openggf.game.sonic3k.TestBonusStageReturnWaterRestore,com.openggf.game.sonic3k.TestPachinkoTitleCardIntegration,com.openggf.tests.TestArchitecturalSourceGuard#rootDispatchMethodsDoNotGrowBeyondCurrentBudgets+releaseCriticalLargeClassesDoNotGrowWithoutExtraction" test` |
| D1.12 | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard#releaseCriticalLargeClassesDoNotGrowWithoutExtraction" test` | `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard" test` |
| D2.1 | `mvn "-Dtest=com.openggf.physics.TestObjectTerrainUtils#floorFullTileEdgeChecksPreviousFullTileLikeSubF30C+floorFullTileEdgeKeepsPreviousCollisionAngleWhenItsSampleIsEmpty+floorRegressToEmptyPreviousTileMatchesSubF30CEmptyResult+floorRegressToPreviousSlopeUsesSingleRomTileOffset" test` | `mvn "-Dtest=com.openggf.physics.TestObjectTerrainUtils" test` |
| D2.2 | `mvn "-Dtest=com.openggf.level.objects.TestSolidObjectManager#zeroDistanceOnlyMotionHookPreservesExactEdgeWithoutChangingNonzeroCorrection" test` | `mvn "-Dtest=com.openggf.level.objects.TestSolidObjectManager" test` |
| D2.3 | `mvn "-Dtest=com.openggf.game.sonic2.objects.TestForcedSpinObjectInstance#forcedSpinEntryKeepsRollAnimationWhenPinballModeSharesSpindashByte" test` | `mvn "-Dtest=com.openggf.game.sonic2.objects.TestForcedSpinObjectInstance" test` |
| D2.4 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestTopSolidRoutineProfileAdoption#floatingPlatformDeclaresTopSolidRoutineProfile" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestTopSolidRoutineProfileAdoption" test` |
| D2.5 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestAizLrzRockPlayerParticipation#sustainedSidekickPushMovesThatPlayerAndPreservesSubpixel" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestAizLrzRockPlayerParticipation" test` |
| D2.6 | `mvn "-Dtest=com.openggf.tests.TestS3kCnzDirectedTraversalHeadless#cnzCylinderForcedReleaseClearsInvalidRiderStateWithoutUsingTheJumpPath" test` | `mvn "-Dtest=com.openggf.tests.TestS3kCnzDirectedTraversalHeadless" test` |
| D3.1 | `mvn "-Dtest=com.openggf.game.sonic3k.events.TestSonic3kAIZEvents#aiz2EndBossActivationKeepsSonicHighPriorityAtWaterfall" test` | `mvn "-Dtest=com.openggf.game.sonic3k.events.TestSonic3kAIZEvents" test` |
| D3.2 | `mvn "-Dtest=com.openggf.tests.TestS3kIczMinibossObject#icz2AfterBossCleanupRestoresObjectPaletteLineFromPalPointers" test` | `mvn "-Dtest=com.openggf.tests.TestS3kIczMinibossObject" test` |
| D3.3 | `mvn "-Dtest=com.openggf.tests.TestCNZBossArtAndAnimation#electricBallUsesObj51ProjectileMappingFrames" test` | `mvn "-Dtest=com.openggf.tests.TestCNZBossArtAndAnimation" test` |
| D3.4 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestS3kResultsScreenObjectInstance#hczAndMgzSeamlessActOneExitSetsTransitionReadyFlag" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestS3kResultsScreenObjectInstance" test` |
| D3.5 | `mvn "-Dtest=com.openggf.game.sonic3k.events.TestSonic3kMgz2QuakeEvents#collapsePlaysBigRumbleEvery16Frames" test` | `mvn "-Dtest=com.openggf.game.sonic3k.events.TestSonic3kMgz2QuakeEvents" test` |
| D3.6 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMgzMinibossInstance#upsideDownStatePersistsThroughDropAndRiseThenClearsBeforeTunnelUp" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMgzMinibossInstance" test` |
| D3.7 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMgzMinibossInstance#defeatCameraHelperLocksBothCameraBoundsWhileScrolling" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMgzMinibossInstance" test` |
| D3.8 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMgzDrillingRobotnikInstance#splitsThrusterFlamesIntoRomPriorityBuckets" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMgzDrillingRobotnikInstance" test` |
| D3.9 | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition#endBossDrawsDrillPieceBehindMainBodyWhenItAppears" test` | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition" test` |
| D3.10 | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition#drillingRobotnikFadesZoneMusicBeforeDelayedBossMusic" test` | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition" test` |
| D3.11 | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition#endBossThrusterFlameTouchUsesGameplayFrameWithoutRenderPass" test` | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition" test` |
| D3.12 | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition#endBossTouchProfileExposesMultiRegionDispatch" test` | `mvn "-Dtest=com.openggf.tests.TestS3kMgzBossMusicTransition,com.openggf.level.objects.TestObjectPhysicsStandardizationGuard" test` |

## Develop wave gate

- [x] Run all 36 formerly red methods in one selection; zero failures/errors on 2026-07-21.
- [x] Run every affected package/guard batch; 3,382 passed, zero failures/errors, three fixture-dependent skips.
- [x] Run `mvn test` twice consecutively; both complete develop runs passed with 12,473 passed, zero failures/errors, and 15 skips per run.
- [x] Update the inventory: all 36 frozen develop identities are now `fixed-on-develop` (shared `develop+next` identities remain unverified on `next`).

### Post-origin full-gate findings

The first full run after merging `origin/develop` through `3f011aa2c` exposed nine methods outside the frozen 36-method baseline. Eight were reproducible stale fixtures and are fixed by `14d5689e9` and `5610326b7`: CNZ cannon control ownership; CNZ miniboss deferred callback/live-timer cadence (three methods); MGZ debris camera-bound isolation; CNZ shake previous-sample latency; and CNZ end-boss defeat/capsule cadence (three methods). The ninth, the S1 lava-geyser rewind graph method, passed its exact rerun, full class, related rewind guards, and both final full-suite passes without a change, so it is recorded as a non-reproducing first-pass result rather than new implementation debt.
