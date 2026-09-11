package com.openggf.level.objects;

import com.openggf.tests.ObjectGuardSourceScanner;
import com.openggf.tests.ObjectGuardSourceScanner.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestObjectPhysicsStandardizationGuard {
    private static volatile List<SourceViolation> productionScanCache;
    private static final Pattern OBJECT_CONTROL_SETTER = Pattern.compile(
            "\\.setObjectControl(?:led|AllowsCpu|SuppressesMovement)\\s*\\(");
    private static final Pattern NATIVE_P2_SIDEKICK_ACCESS = Pattern.compile(
            "(?:getFirst\\s*\\(\\s*\\)|\\.get\\s*\\(\\s*0\\s*\\)|\\.stream\\s*\\(\\s*\\)\\.findFirst\\s*\\()");
    private static final Pattern NATIVE_P2_SIDEKICK_ITERATION = Pattern.compile(
            "\\bfor\\s*\\([^:]+:\\s*[^;{}]*\\.sidekicks\\s*\\(\\s*\\)\\s*\\)");
    private static final Pattern NATIVE_P2_SIDEKICK_ALIAS_ASSIGNMENT = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*)\\s*=\\s*(?:(?:[A-Za-z_$][\\w$]*|services\\s*\\(\\s*\\))"
                    + "\\s*\\.\\s*sidekicks\\s*\\(\\s*\\)|getSidekicks\\s*\\(\\s*\\))");
    private static final Pattern NATIVE_P2_SIDEKICK_SNAPSHOT_ALIAS_ASSIGNMENT = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*)\\s*=\\s*(?:List\\.copyOf\\s*\\(|new\\s+ArrayList\\s*(?:<[^>]*>)?\\s*\\()"
                    + "[^;]*getSidekicks\\s*\\(");
    private static final Pattern NATIVE_P2_SIDEKICK_SNAPSHOT_ALIAS_START = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*)\\s*=\\s*(?:List\\.copyOf\\s*\\(|new\\s+ArrayList\\s*(?:<[^>]*>)?\\s*\\()");
    private static final Pattern RAW_OBJECT_SERVICES_SIDEKICKS = Pattern.compile(
            "(?:\\b(?:services|svc|objectServices)\\b\\s*|\\bservices\\s*\\(\\s*\\)\\s*)"
                    + "\\.\\s*sidekicks\\s*\\(");
    private static final Pattern RAW_OBJECT_SERVICES_SIDEKICK_METHOD_REFERENCE = Pattern.compile(
            "(?:\\b(?:services|svc|objectServices)\\b\\s*|\\bservices\\s*\\(\\s*\\)\\s*)"
                    + "::\\s*sidekicks\\b");
    private static final Pattern RAW_GET_SIDEKICKS_SUPPLIER = Pattern.compile(
            "(?:->\\s*[^;]*\\.\\s*getSidekicks\\s*\\(|::\\s*getSidekicks\\b)");
    private static final Pattern RAW_NATIVE_POSITION_PRESERVE_SUBPIXEL_WRITE = Pattern.compile(
            "\\.setCentre[XY]PreserveSubpixel\\s*\\(");
    private static final Pattern DIRECT_LIFECYCLE_OPERATION = Pattern.compile(
            "(?:setSlotIndex\\s*\\(\\s*-\\s*1\\s*\\)|\\.markRemembered\\s*\\(|"
                    + "\\.removeFromActiveSpawns\\s*\\(|\\.addDynamicObjectAtSlot\\s*\\(|"
                    + "\\.allocateSlotAfter\\s*\\()");
    private static final Pattern RAW_SET_DESTROYED_TRUE = Pattern.compile(
            "\\b(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)?setDestroyed\\s*\\(\\s*true\\s*\\)");
    private static final Pattern RAW_ADD_DYNAMIC_OBJECT = Pattern.compile(
            "\\.\\s*addDynamicObject(?:AfterCurrent(?:NextFrame)?|AfterSlot|NextFrame)?\\s*\\(");
    private static final Pattern TOUCH_PROFILE_HOOK_WITHOUT_PROFILE = Pattern.compile(
            "\\bpublic\\s+(?:boolean|int|TouchRegion\\[\\]|TouchResponseProvider\\.TouchRegion\\[\\])\\s+"
                    + "(?:requiresContinuousTouchCallbacks|usesS3kTouchSpecialPropertyResponse|"
                    + "usesSonic2TouchSpecialPropertyResponse|"
                    + "enablesPostSpecialTouchAirborneSideVelocityPreservation|requiresRenderFlagForTouch|"
                    + "getMultiTouchRegions|getShieldReactionFlags|onShieldDeflect)\\s*\\(");
    private static final List<Pattern> DIRECT_TOUCH_POLICY_CALLS = List.of(
            Pattern.compile("(?<!\\btouchProfile)\\.requiresRenderFlagForTouch\\s*\\("),
            Pattern.compile("(?<!\\btouchProfile)\\.requiresContinuousTouchCallbacks\\s*\\("),
            Pattern.compile("\\bprovider\\s*\\.\\s*enablesPostSpecialTouchAirborneSideVelocityPreservation\\s*\\("),
            Pattern.compile("\\bprovider\\s*\\.\\s*getShieldReactionFlags\\s*\\("),
            Pattern.compile("\\.usesS3kTouchSpecialPropertyResponse\\s*\\("),
            Pattern.compile("\\.usesSonic2TouchSpecialPropertyResponse\\s*\\("),
            Pattern.compile("\\bTouchResponseProvider\\s+touchProfile\\b"),
            Pattern.compile("\\bvar\\s+touchProfile\\s*=\\s*(?:\\(\\s*TouchResponseProvider\\s*\\)\\s*)?provider(?!\\s*\\.)\\b"),
            Pattern.compile("\\btouchProfile\\s*=\\s*(?:\\(\\s*TouchResponseProvider\\s*\\)\\s*)?provider(?!\\s*\\.)\\b"));
    private static final String[] PHYSICS_STANDARDIZATION_SCAN_PACKAGE_PATHS = {
            "com/openggf/game/sonic1/events",
            "com/openggf/game/sonic1/features",
            "com/openggf/game/sonic1/objects",
            "com/openggf/game/sonic2/events",
            "com/openggf/game/sonic2/features",
            "com/openggf/game/sonic2/objects",
            "com/openggf/game/sonic3k/events",
            "com/openggf/game/sonic3k/features",
            "com/openggf/game/sonic3k/objects",
            "com/openggf/level/objects",
    };
    private static final String[] PHYSICS_STANDARDIZATION_SCAN_FILE_PATHS = {
            "com/openggf/game/sonic3k/Sonic3kLevelEventManager.java",
    };
    // 2026-07-02 triage of the bb69af121 S2 boss/prize batch: these files declare
    // touch-property hooks inline without a TouchResponseProfile. The behavior is
    // trace-validated (CNZ/MCZ boss arenas, Flasher, SpikyBlock); migrate them to
    // canonical profiles in a follow-up instead of risking just-greened traces.
    // Do NOT add new files here without the same trace-parity justification.
    private static final Set<String> TRIAGED_TOUCH_PROFILE_HOOK_FILES = Set.of(
            "com/openggf/game/sonic2/objects/badniks/FlasherBadnikInstance.java",
            "com/openggf/game/sonic2/objects/bosses/CNZBossElectricBall.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2CNZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2MCZBossInstance.java",
            "com/openggf/game/sonic2/objects/SpikyBlockSpikeInstance.java");

    private static final Set<String> LEGACY_RAW_NATIVE_POSITION_WRITE_FILES = Set.of(
            "com/openggf/game/sonic1/events/Sonic1LZWaterEvents.java",
            "com/openggf/game/sonic1/objects/Sonic1JunctionObjectInstance.java",
            // Obj0B Pole .grab/.moveup/.movedown use word-only obX/obY writes;
            // see TestSonic1PoleThatBreaksObjectInstance subpixel guard.
            "com/openggf/game/sonic1/objects/Sonic1PoleThatBreaksObjectInstance.java",
            "com/openggf/game/sonic2/OilSurfaceManager.java",
            "com/openggf/game/sonic2/objects/BreakableBlockObjectInstance.java",
            "com/openggf/game/sonic2/objects/FlipperObjectInstance.java",
            "com/openggf/game/sonic2/objects/ForcedSpinObjectInstance.java",
            "com/openggf/game/sonic2/objects/LauncherSpringObjectInstance.java",
            "com/openggf/game/sonic2/objects/MovingVineObjectInstance.java",
            "com/openggf/game/sonic2/objects/PointPokeyObjectInstance.java",
            "com/openggf/game/sonic2/objects/RisingPillarObjectInstance.java",
            "com/openggf/game/sonic2/objects/SmashableGroundObjectInstance.java",
            "com/openggf/game/sonic2/objects/SpeedLauncherObjectInstance.java",
            "com/openggf/game/sonic2/objects/SpiralObjectInstance.java",
            "com/openggf/game/sonic2/objects/SpringObjectInstance.java",
            "com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java",
            "com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java",
            "com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java",
            "com/openggf/game/sonic3k/objects/AizHollowTreeObjectInstance.java",
            "com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java",
            "com/openggf/game/sonic3k/objects/AizVineHandleLogic.java",
            "com/openggf/game/sonic3k/objects/AutoSpinObjectInstance.java",
            "com/openggf/game/sonic3k/objects/ClamerObjectInstance.java",
            "com/openggf/game/sonic3k/objects/CnzBarberPoleObjectInstance.java",
            "com/openggf/game/sonic3k/objects/CnzCannonInstance.java",
            "com/openggf/game/sonic3k/objects/CnzCylinderInstance.java",
            "com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java",
            "com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java",
            "com/openggf/game/sonic3k/objects/CnzVacuumTubeInstance.java",
            "com/openggf/game/sonic3k/objects/CnzWireCageObjectInstance.java",
            "com/openggf/game/sonic3k/objects/IczSnowboardIntroInstance.java",
            "com/openggf/game/sonic3k/objects/Sonic3kSpringObjectInstance.java",
            "com/openggf/game/sonic3k/sidekick/Sonic3kCnzCarryTrigger.java"
    );
    private static final Set<String> LIFECYCLE_RATCHET_OWNER_FILES = Set.of(
            "com/openggf/level/objects/AbstractObjectInstance.java",
            "com/openggf/level/objects/DestructionEffects.java",
            "com/openggf/level/objects/ObjectLifetimeOps.java",
            "com/openggf/level/objects/ObjectManager.java"
    );
    private static final int OBJECT_LIFECYCLE_RATCHET_MINIMUM_SCANNED_FILES = 250;
    // 2026-07-02: re-ratcheted 577 -> 584 for the post-45270bed9 S2 boss/prize
    // batch (bb69af121 merge); new implementations must still prefer
    // ObjectLifetimeOps.destroyLatched(...)/destroyRespawnableOffscreen(...).
    // 2026-07-12: tightened 583 -> 582 after the AIZ control/glow and
    // screen-shake timer objects moved to the shared dynamic-expiry path.
    private static final int RAW_SET_DESTROYED_TRUE_OBJECT_PACKAGE_BUDGET = 582;
// 2026-07-02: 10 -> 11 for the CPZ2 Obj7A trace-frontier fix (fca42ed8d),
    // whose direct addDynamicObject insert is part of the trace-validated slot
    // cadence; migrating it to spawnChild would change allocation order.
    // 2026-08-20: 11 -> 10. The two water-splash spawn sites in
    // DefaultPowerUpSpawner collapsed into one rules-driven helper when the S1
    // splash moved to its ROM fixed SST (v_splash, slot 12) instead of
    // consuming a level-object slot.
    private static final int RAW_ADD_DYNAMIC_OBJECT_OBJECT_PACKAGE_BUDGET = 10;

    @Test
    void objectManagerUsesNativePositionOpsForPlayablePreserveSubpixelWrites() throws IOException {
        SourceText source = source("com/openggf/level/objects/ObjectManager.java");

        assertEquals(List.of(), forbiddenLines(source,
                "setCentreXPreserveSubpixel(",
                "setCentreYPreserveSubpixel("));
    }

    @Test
    void productionPlayableNativePositionRawPreserveSubpixelWriteFilesDoNotGrow() throws IOException {
        assertEquals(List.of(), rawNativePositionWriteViolationsOutsideLegacyFiles());
    }

    @Test
    void badnikDestructionUsesLifetimeOpsForSlotTransferAndDestroyedFlag() throws IOException {
        SourceText source = source("com/openggf/level/objects/AbstractBadnikInstance.java");

        assertEquals(List.of(), forbiddenLines(source,
                "setSlotIndex(-1)",
                "setDestroyed(true)"));
    }

    @Test
    void destructionEffectsUsesLifetimeOpsForSpawnAndReplacementLifecycle() throws IOException {
        SourceText source = source("com/openggf/level/objects/DestructionEffects.java");

        assertEquals(List.of(), forbiddenLines(source,
                ".markRemembered(",
                ".removeFromActiveSpawns(",
                ".addDynamicObjectAtSlot("));
    }

    @Test
    void objectManagerTouchDispatchConsumesTouchResponseProfileForStablePolicy() throws IOException {
        SourceText source = source("com/openggf/level/objects/ObjectManager.java");

        assertEquals(List.of(), forbiddenLines(source, DIRECT_TOUCH_POLICY_CALLS));
    }

    @Test
    void touchPolicyGuardAllowsProfilePolicyReadsInDispatcherSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void dispatch(TouchResponseProfile profile) {",
                "    if (profile.enablesPostSpecialTouchAirborneSideVelocityPreservation()) {",
                "      preserveSideVelocity();",
                "    }",
                "    int flags = profile.getShieldReactionFlags();",
                "  }",
                "}"));

        assertEquals(List.of(), forbiddenLines(source, DIRECT_TOUCH_POLICY_CALLS));
    }

    @Test
    void touchPolicyGuardDetectsRawProviderPolicyReadsInDispatcherSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void dispatch(TouchResponseProvider provider) {",
                "    if (provider.enablesPostSpecialTouchAirborneSideVelocityPreservation()) {",
                "      preserveSideVelocity();",
                "    }",
                "    int flags = provider.getShieldReactionFlags();",
                "  }",
                "}"));

        assertEquals(List.of(
                        "if (provider.enablesPostSpecialTouchAirborneSideVelocityPreservation()) {",
                        "int flags = provider.getShieldReactionFlags();"),
                forbiddenLines(source, DIRECT_TOUCH_POLICY_CALLS));
    }

    @Test
    void productionObjectPhysicsStandardizationHasNoUnapprovedViolations() throws IOException {
        List<SourceViolation> first = scanProductionSources();
        assertSame(first, scanProductionSources(), "production scan must be cached once per class");
        assertEquals(List.of(), first);
    }

    @Test
    void productionObjectLifecycleRawCallCountsDoNotGrow() throws IOException {
        List<String> lifecycleViolations = scanProductionLifecycleRatchetSources();
        List<String> rawSetDestroyed = lifecycleViolations
                .stream()
                .filter(violation -> violation.contains("raw setDestroyed(true)"))
                .toList();
        List<String> rawAddDynamicObject = lifecycleViolations
                .stream()
                .filter(violation -> violation.contains("raw addDynamicObject(...)"))
                .toList();

        assertTrue(lifecycleCountIsWithinBudget(rawSetDestroyed.size(),
                        RAW_SET_DESTROYED_TRUE_OBJECT_PACKAGE_BUDGET),
                "Raw setDestroyed(true) calls in object production packages must not grow beyond budget. Current count is "
                        + rawSetDestroyed.size() + "; budget is "
                        + RAW_SET_DESTROYED_TRUE_OBJECT_PACKAGE_BUDGET + ". "
                        + "Prefer ObjectLifetimeOps.destroyLatched(...) or "
                        + "ObjectLifetimeOps.destroyRespawnableOffscreen(...). Current violations:\n  "
                        + String.join("\n  ", rawSetDestroyed));
        assertEquals(RAW_ADD_DYNAMIC_OBJECT_OBJECT_PACKAGE_BUDGET, rawAddDynamicObject.size(),
                "Raw addDynamicObject* calls in object production packages must not grow or drift silently. Current count is "
                        + rawAddDynamicObject.size() + "; budget is "
                        + RAW_ADD_DYNAMIC_OBJECT_OBJECT_PACKAGE_BUDGET + ". "
                        + "Prefer spawnChild(...), spawnFreeChild(...), or "
                        + "ObjectManager.createDynamicObject(...). Current violations:\n  "
                        + String.join("\n  ", rawAddDynamicObject));
    }

    @Test
    void lifecycleRawDestroyBudgetAcceptsReductionsButStillRejectsGrowth() {
        assertTrue(lifecycleCountIsWithinBudget(577, 582));
        assertTrue(lifecycleCountIsWithinBudget(582, 582));
        assertFalse(lifecycleCountIsWithinBudget(583, 582));
    }

    private static boolean lifecycleCountIsWithinBudget(int actual, int budget) {
        return actual <= budget;
    }

    @Test
    void productionObjectPhysicsStandardizationScanIncludesSonic3kLevelEventManagerOnly() throws IOException {
        List<String> paths = productionScanRelativePaths();

        assertEquals(true, paths.contains("com/openggf/game/sonic3k/Sonic3kLevelEventManager.java"));
        assertEquals(true, paths.contains("com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java"));
        assertEquals(false, paths.contains("com/openggf/game/sonic3k/Sonic3kLevelAnimationManager.java"));
    }

    @Test
    void c2EventAndFeaturePlayerQueriesDeclareAllEnginePlayerParticipation() throws IOException {
        assertOwnedSourceUsesAllEnginePlayers(
                "com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java",
                "update");
        assertOwnedQueryHelperSuppliesSidekicks(
                "com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java",
                "playerQueryFromRuntime");
        // 165c1e481 moved the oil participation loops out of update() into the
        // pre-physics and reserved-slot passes so the ROM's NonWaterEffects /
        // Obj07 ordering is honoured. The participation contract is unchanged, so
        // the guard follows the loops to the two methods that now own them rather
        // than pinning an empty update().
        assertOwnedSourceUsesAllEnginePlayers(
                "com/openggf/game/sonic2/events/Sonic2OOZEvents.java",
                "updatePrePhysics");
        assertOwnedSourceUsesAllEnginePlayers(
                "com/openggf/game/sonic2/events/Sonic2OOZEvents.java",
                "updateReservedObjectSlots");
        assertOwnedQueryHelperSuppliesSidekicks(
                "com/openggf/game/sonic2/events/Sonic2OOZEvents.java",
                "playerQueryFromRuntime");
    }

    @Test
    void s3kResultsScreenUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "S3kResultsScreenObjectInstance.java");
    }

    @Test
    void s3kSignpostUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "S3kSignpostInstance.java");
    }

    @Test
    void sonic3kSsEntryRingUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "Sonic3kSSEntryRingObjectInstance.java");
    }

    @Test
    void cnzEndBossUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "bosses/CnzEndBossInstance.java");
    }

    @Test
    void hczMinibossUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "HczMinibossInstance.java");
    }

    @Test
    void aizPlaneIntroUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "AizPlaneIntroInstance.java");
    }

    @Test
    void cutsceneKnucklesAiz1UsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "CutsceneKnucklesAiz1Instance.java");
    }

    @Test
    void capsuleAndGeyserBossCutscenesUseObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "AbstractS3kFloatingEndEggCapsuleInstance.java",
                "bosses/HczEndBossEggCapsuleInstance.java",
                "bosses/HczEndBossGeyserCutscene.java");
    }

    @Test
    void hczEndBossWaterColumnUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "bosses/HczEndBossWaterColumn.java");
    }

    @Test
    void iczSnowboardIntroUsesObjectControlStatePolicyInsteadOfBaseline() throws IOException {
        assertNoProductionViolationsEndingWith(
                ViolationKind.DIRECT_OBJECT_CONTROL_SETTER,
                "IczSnowboardIntroInstance.java");
    }

    @Test
    void guardDetectsDirectObjectControlSetterInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(Player player) {",
                "    player.setObjectControlled (true);",
                "  }",
                "}"));

        String path = "com/openggf/game/sonic2/objects/Sample.java";

        assertEquals(List.of(new SourceViolation(
                        path,
                        "player.setObjectControlled (true);",
                        ViolationKind.DIRECT_OBJECT_CONTROL_SETTER)),
                scanSource(path, source));
    }

    @Test
    void guardDetectsRawNativeP2SidekickAccessInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(List<Player> sidekicks) {",
                "    Player p2 = sidekicks.getFirst();",
                "    Player p2b = sidekicks.get( 0 );",
                "    Player p2c = getSidekicks().stream().findFirst().orElse(null);",
                "    for (PlayableEntity p2d : services().sidekicks()) {",
                "      applyNativeP2Effect(p2d);",
                "    }",
                "    ObjectPlayerQuery query = new ObjectPlayerQuery(() -> player,",
                "        () -> services != null ? services.sidekicks() : List.of());",
                "  }",
                "}"));

        String path = "com/openggf/game/sonic2/objects/Sample.java";

        assertEquals(List.of(
                        new SourceViolation(
                        path,
                        "Player p2 = sidekicks.getFirst();",
                        ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "Player p2b = sidekicks.get( 0 );",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "Player p2c = getSidekicks().stream().findFirst().orElse(null);",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "for (PlayableEntity p2d : services().sidekicks()) {",
                        ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "() -> services != null ? services.sidekicks() : List.of());",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS)),
                scanSource(path, source));
    }

    @Test
    void guardDetectsRawSidekickServiceAliasBeforeIterationOrIndexAccessInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(ObjectServices svc) {",
                "    List<PlayableEntity> sks = svc.sidekicks();",
                "    for (PlayableEntity sk : sks) {",
                "      apply(sk);",
                "    }",
                "    var serviceSidekicks = services().sidekicks();",
                "    PlayableEntity nativeP2 = serviceSidekicks.get(0);",
                "    List<PlayableEntity> reassigned;",
                "    reassigned = services().sidekicks();",
                "    PlayableEntity nativeP2b = reassigned.stream().findFirst().orElse(null);",
                "    List<PlayableEntity> snapshot = List.copyOf(GameServices.sprites().getSidekicks());",
                "    PlayableEntity nativeP2c = snapshot.getFirst();",
                "    var copied = new ArrayList<>(spriteManager().getSidekicks());",
                "    for (PlayableEntity sk : copied) {",
                "      apply(sk);",
                "    }",
                "    List<PlayableEntity> multiline = List.copyOf(",
                "        GameServices.sprites().getSidekicks());",
                "    PlayableEntity nativeP2d = multiline.getFirst();",
                "  }",
                "}"));

        String path = "com/openggf/game/sonic3k/objects/Sample.java";

        assertEquals(List.of(
                        new SourceViolation(
                                path,
                                "List<PlayableEntity> sks = svc.sidekicks();",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "for (PlayableEntity sk : sks) {",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "var serviceSidekicks = services().sidekicks();",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "PlayableEntity nativeP2 = serviceSidekicks.get(0);",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "reassigned = services().sidekicks();",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "PlayableEntity nativeP2b = reassigned.stream().findFirst().orElse(null);",
                        ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "PlayableEntity nativeP2c = snapshot.getFirst();",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "for (PlayableEntity sk : copied) {",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "PlayableEntity nativeP2d = multiline.getFirst();",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS)),
                scanSource(path, source));
    }

    @Test
    void guardDetectsRawSidekickMethodReferenceSuppliersInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(ObjectServices svc) {",
                "    ObjectPlayerQuery query = new ObjectPlayerQuery(() -> player, services()::sidekicks);",
                "    Supplier<List<PlayableEntity>> supplier = svc::sidekicks;",
                "  }",
                "}"));

        String path = "com/openggf/game/sonic1/objects/Sample.java";

        assertEquals(List.of(
                        new SourceViolation(
                                path,
                                "ObjectPlayerQuery query = new ObjectPlayerQuery(() -> player, services()::sidekicks);",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "Supplier<List<PlayableEntity>> supplier = svc::sidekicks;",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS)),
                scanSource(path, source));
    }

    @Test
    void guardDetectsRawGetSidekicksSuppliersInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update() {",
                "    ObjectPlayerQuery query = new ObjectPlayerQuery(",
                "        () -> GameServices.camera().getFocusedSprite(),",
                "        () -> GameServices.sprites().getSidekicks());",
                "    Supplier<List<AbstractPlayableSprite>> supplier = GameServices.sprites()::getSidekicks;",
                "  }",
                "}"));

        String path = "com/openggf/game/sonic3k/features/Sample.java";

        assertEquals(List.of(
                        new SourceViolation(
                                path,
                                "() -> GameServices.sprites().getSidekicks());",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS),
                        new SourceViolation(
                                path,
                                "Supplier<List<AbstractPlayableSprite>> supplier = GameServices.sprites()::getSidekicks;",
                                ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS)),
                scanSource(path, source));
    }

    @Test
    void guardAllowsPlayerQuerySidekickSuppliersInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(ObjectServices services) {",
                "    ObjectPlayerQuery query = new ObjectPlayerQuery(",
                "        () -> player,",
                "        () -> services.playerQuery().sidekicks());",
                "  }",
                "}"));

        assertEquals(List.of(), scanSource("com/openggf/game/sonic3k/objects/Sample.java", source));
    }

    @Test
    void guardDetectsDirectLifecycleOperationInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(ObjectManager objectManager, Spawn spawn) {",
                "    objectManager.markRemembered (spawn);",
                "    objectManager.addDynamicObjectAtSlot (null, 0);",
                "    objectManager.allocateSlotAfter (0);",
                "    setSlotIndex(- 1);",
                "  }",
                "}"));

        assertEquals(List.of(
                        new SourceViolation(
                                "Sample.java",
                                "objectManager.markRemembered (spawn);",
                                ViolationKind.DIRECT_LIFECYCLE_OPERATION),
                        new SourceViolation(
                                "Sample.java",
                                "objectManager.addDynamicObjectAtSlot (null, 0);",
                                ViolationKind.DIRECT_LIFECYCLE_OPERATION),
                        new SourceViolation(
                                "Sample.java",
                                "objectManager.allocateSlotAfter (0);",
                                ViolationKind.DIRECT_LIFECYCLE_OPERATION),
                        new SourceViolation(
                                "Sample.java",
                                "setSlotIndex(- 1);",
                                ViolationKind.DIRECT_LIFECYCLE_OPERATION)),
                scanSource("Sample.java", source));
    }

    @Test
    void lifecycleRatchetDetectsRawDestroyedAndDynamicSpawnCallsInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  void update(ObjectManager objectManager, AbstractObjectInstance child) {",
                "    setDestroyed(true);",
                "    child.setDestroyed ( true );",
                "    objectManager.addDynamicObject(new Sparkle());",
                "    objectManager.addDynamicObjectAfterCurrent(new Sparkle());",
                "    objectManager.addDynamicObjectAfterCurrentNextFrame(new Sparkle());",
                "    objectManager.addDynamicObjectAfterSlot(new Sparkle(), 3);",
                "    objectManager.addDynamicObjectNextFrame(new Sparkle());",
                "  }",
                "}"));

        assertEquals(List.of(
                        "Sample.java:3 - raw setDestroyed(true); use ObjectLifetimeOps.destroyLatched(...) "
                                + "or ObjectLifetimeOps.destroyRespawnableOffscreen(...)",
                        "Sample.java:4 - raw setDestroyed(true); use ObjectLifetimeOps.destroyLatched(...) "
                                + "or ObjectLifetimeOps.destroyRespawnableOffscreen(...)",
                        "Sample.java:5 - raw addDynamicObject(...); use spawnChild(...), spawnFreeChild(...), "
                                + "or ObjectManager.createDynamicObject(...)",
                        "Sample.java:6 - raw addDynamicObject(...); use spawnChild(...), spawnFreeChild(...), "
                                + "or ObjectManager.createDynamicObject(...)",
                        "Sample.java:7 - raw addDynamicObject(...); use spawnChild(...), spawnFreeChild(...), "
                                + "or ObjectManager.createDynamicObject(...)",
                        "Sample.java:8 - raw addDynamicObject(...); use spawnChild(...), spawnFreeChild(...), "
                                + "or ObjectManager.createDynamicObject(...)",
                        "Sample.java:9 - raw addDynamicObject(...); use spawnChild(...), spawnFreeChild(...), "
                                + "or ObjectManager.createDynamicObject(...)"),
                scanLifecycleRatchetSource("Sample.java", source));
    }

    @Test
    void lifecycleRatchetAllowsSharedWrapperOwnerSources() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class ObjectLifetimeOps {",
                "  void replace(ObjectManager objectManager, AbstractObjectInstance replacement) {",
                "    objectManager.addDynamicObject(replacement);",
                "    replacement.setDestroyed(true);",
                "  }",
                "}"));

        assertEquals(List.of(), scanLifecycleRatchetSource(
                "com/openggf/level/objects/ObjectLifetimeOps.java", source));
    }

    @Test
    void guardDetectsTouchProfileHookWithoutProfileInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  public boolean requiresContinuousTouchCallbacks() {",
                "    return true;",
                "  }",
                "}"));

        String path = "com/openggf/game/sonic3k/objects/Sample.java";

        assertEquals(List.of(new SourceViolation(
                        path,
                        "public boolean requiresContinuousTouchCallbacks() {",
                        ViolationKind.TOUCH_PROFILE_HOOK_WITHOUT_PROFILE)),
                scanSource(path, source));
    }

    @Test
    void guardAllowsTouchProfileHookWhenProfileIsDeclaredInSampleSource() {
        SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(List.of(
                "class Sample {",
                "  public TouchResponseProfile getTouchResponseProfile() {",
                "    return TouchResponseProfile.standardEnemy();",
                "  }",
                "  public boolean requiresContinuousTouchCallbacks() {",
                "    return true;",
                "  }",
                "}"));

        assertEquals(List.of(), scanSource("com/openggf/game/sonic3k/objects/Sample.java", source));
    }

    private static SourceText source(String relativePath) throws IOException {
        Path srcMain = ObjectGuardSourceScanner.findSourceRoot();
        if (srcMain == null) {
            throw new IOException("Could not locate src/main/java");
        }
        return ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(
                Files.readAllLines(srcMain.resolve(relativePath)));
    }

    private static List<String> forbiddenLines(SourceText source, String... fragments) {
        return source.lines().stream()
                .map(String::trim)
                .filter(line -> containsAny(line, fragments))
                .toList();
    }

    private static List<String> forbiddenLines(SourceText source, List<Pattern> patterns) {
        return source.lines().stream()
                .map(String::trim)
                .filter(line -> matchesAny(line, patterns))
                .toList();
    }

    private static List<String> rawNativePositionWriteViolationsOutsideLegacyFiles() throws IOException {
        Path srcMain = ObjectGuardSourceScanner.findSourceRoot();
        if (srcMain == null) {
            throw new IOException("Could not locate src/main/java");
        }
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : ObjectGuardSourceScanner.javaFilesUnderPackages(
                srcMain, new String[] {"com/openggf/game", "com/openggf/level/objects"})) {
            String path = srcMain.relativize(sourceFile).toString().replace('\\', '/');
            if (isObjectPhysicsStandardizationOwner(path)
                    || LEGACY_RAW_NATIVE_POSITION_WRITE_FILES.contains(path)) {
                continue;
            }
            SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(
                    Files.readAllLines(sourceFile));
            source.lines().stream()
                    .map(String::trim)
                    .filter(line -> RAW_NATIVE_POSITION_PRESERVE_SUBPIXEL_WRITE.matcher(line).find())
                    .map(line -> path + ": " + line)
                    .forEach(violations::add);
        }
        return violations;
    }

    private static boolean containsAny(String line, String... fragments) {
        for (String fragment : fragments) {
            if (line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String line, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<SourceViolation> scanProductionSources() throws IOException {
        List<SourceViolation> cached = productionScanCache;
        if (cached != null) {
            return cached;
        }
        synchronized (TestObjectPhysicsStandardizationGuard.class) {
            cached = productionScanCache;
            if (cached == null) {
                cached = List.copyOf(scanProductionSourcesUncached());
                productionScanCache = cached;
            }
            return cached;
        }
    }

    private static List<SourceViolation> scanProductionSourcesUncached() throws IOException {
        Path srcMain = ObjectGuardSourceScanner.findSourceRoot();
        if (srcMain == null) {
            throw new IOException("Could not locate src/main/java");
        }
        List<SourceViolation> violations = new ArrayList<>();
        for (Path sourceFile : productionScanFiles(srcMain)) {
            String path = srcMain.relativize(sourceFile).toString().replace('\\', '/');
            SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(
                    Files.readAllLines(sourceFile));
            violations.addAll(scanSource(path, source));
        }
        return violations;
    }

    private static List<String> scanProductionLifecycleRatchetSources() throws IOException {
        Path srcMain = ObjectGuardSourceScanner.findSourceRoot();
        if (srcMain == null) {
            throw new IOException("Could not locate src/main/java");
        }
        List<String> violations = new ArrayList<>();
        List<Path> sourceFiles = ObjectGuardSourceScanner.javaFilesUnderPackages(
                srcMain, ObjectGuardSourceScanner.OBJECT_PACKAGE_PATHS);
        assertTrue(sourceFiles.size() >= OBJECT_LIFECYCLE_RATCHET_MINIMUM_SCANNED_FILES,
                "Lifecycle ratchet scan root is unexpectedly small: scanned "
                        + sourceFiles.size() + " files, minimum is "
                        + OBJECT_LIFECYCLE_RATCHET_MINIMUM_SCANNED_FILES);
        for (Path sourceFile : sourceFiles) {
            String path = srcMain.relativize(sourceFile).toString().replace('\\', '/');
            if (LIFECYCLE_RATCHET_OWNER_FILES.contains(path)) {
                continue;
            }
            SourceText source = ObjectGuardSourceScanner.sourceWithoutCommentOnlyLines(
                    Files.readAllLines(sourceFile));
            violations.addAll(scanLifecycleRatchetSource(path, source));
        }
        return violations;
    }

    private static List<String> productionScanRelativePaths() throws IOException {
        Path srcMain = ObjectGuardSourceScanner.findSourceRoot();
        if (srcMain == null) {
            throw new IOException("Could not locate src/main/java");
        }
        return productionScanFiles(srcMain)
                .stream()
                .map(sourceFile -> srcMain.relativize(sourceFile).toString().replace('\\', '/'))
                .toList();
    }

    private static List<Path> productionScanFiles(Path srcMain) throws IOException {
        List<Path> files = new ArrayList<>(ObjectGuardSourceScanner.javaFilesUnderPackages(
                srcMain, PHYSICS_STANDARDIZATION_SCAN_PACKAGE_PATHS));
        for (String filePath : PHYSICS_STANDARDIZATION_SCAN_FILE_PATHS) {
            Path sourceFile = srcMain.resolve(filePath);
            if (Files.isRegularFile(sourceFile)) {
                files.add(sourceFile);
            }
        }
        return files;
    }

    private static List<SourceViolation> scanSource(String path, SourceText source) {
        if (isObjectPhysicsStandardizationOwner(path)) {
            return List.of();
        }
        boolean gameObjectPath = isGameObjectPath(path);
        boolean physicsStandardizationPath = isPhysicsStandardizationPath(path);
        boolean hasTouchResponseProfile = hasTouchResponseProfile(source);
        Set<String> nativeP2SidekickAliases = new HashSet<>();
        Set<String> pendingNativeP2SidekickSnapshotAliases = new HashSet<>();
        List<SourceViolation> violations = new ArrayList<>();
        for (String line : source.lines()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ((gameObjectPath || isSonic3kEventPath(path)) && OBJECT_CONTROL_SETTER.matcher(trimmed).find()) {
                violations.add(new SourceViolation(path, trimmed,
                        ViolationKind.DIRECT_OBJECT_CONTROL_SETTER));
            }
            if (physicsStandardizationPath && isRawNativeP2SidekickAccess(trimmed, nativeP2SidekickAliases)) {
                violations.add(new SourceViolation(path, trimmed,
                        ViolationKind.RAW_NATIVE_P2_SIDEKICK_ACCESS));
            }
            if (physicsStandardizationPath) {
                recordNativeP2SidekickAlias(
                        trimmed,
                        nativeP2SidekickAliases,
                        pendingNativeP2SidekickSnapshotAliases);
            }
            if (isDirectLifecycleOperation(trimmed)) {
                violations.add(new SourceViolation(path, trimmed,
                        ViolationKind.DIRECT_LIFECYCLE_OPERATION));
            }
            if (gameObjectPath && !hasTouchResponseProfile
                    && !TRIAGED_TOUCH_PROFILE_HOOK_FILES.contains(path)
                    && TOUCH_PROFILE_HOOK_WITHOUT_PROFILE.matcher(trimmed).find()) {
                violations.add(new SourceViolation(path, trimmed,
                        ViolationKind.TOUCH_PROFILE_HOOK_WITHOUT_PROFILE));
            }
        }
        return violations;
    }

    private static List<String> scanLifecycleRatchetSource(String path, SourceText source) {
        if (LIFECYCLE_RATCHET_OWNER_FILES.contains(path)) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        Matcher destroyedMatcher = RAW_SET_DESTROYED_TRUE.matcher(source.text());
        while (destroyedMatcher.find()) {
            violations.add(path + ":" + source.lineAt(destroyedMatcher.start())
                    + " - raw setDestroyed(true); use ObjectLifetimeOps.destroyLatched(...) "
                    + "or ObjectLifetimeOps.destroyRespawnableOffscreen(...)");
        }
        Matcher dynamicMatcher = RAW_ADD_DYNAMIC_OBJECT.matcher(source.text());
        while (dynamicMatcher.find()) {
            violations.add(path + ":" + source.lineAt(dynamicMatcher.start())
                    + " - raw addDynamicObject(...); use spawnChild(...), spawnFreeChild(...), "
                    + "or ObjectManager.createDynamicObject(...)");
        }
        return violations;
    }

    private static boolean isObjectPhysicsStandardizationOwner(String path) {
        return path.equals("com/openggf/level/objects/ObjectManager.java")
                || path.equals("com/openggf/level/objects/ObjectLifetimeOps.java")
                || path.equals("com/openggf/level/objects/ObjectPlayerQuery.java");
    }

    private static boolean isGameObjectPath(String path) {
        for (String packagePath : ObjectGuardSourceScanner.GAME_OBJECT_PACKAGE_PATHS) {
            if (path.startsWith(packagePath + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSonic3kEventPath(String path) {
        return path.startsWith("com/openggf/game/sonic3k/events/")
                || path.equals("com/openggf/game/sonic3k/Sonic3kLevelEventManager.java");
    }

    private static boolean isPhysicsStandardizationPath(String path) {
        for (String packagePath : PHYSICS_STANDARDIZATION_SCAN_PACKAGE_PATHS) {
            if (path.startsWith(packagePath + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRawNativeP2SidekickAccess(String trimmed, Set<String> nativeP2SidekickAliases) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return (NATIVE_P2_SIDEKICK_ACCESS.matcher(trimmed).find()
                && (lower.contains("sidekick") || trimmed.contains("getSidekicks()")))
                || RAW_OBJECT_SERVICES_SIDEKICKS.matcher(trimmed).find()
                || RAW_OBJECT_SERVICES_SIDEKICK_METHOD_REFERENCE.matcher(trimmed).find()
                || RAW_GET_SIDEKICKS_SUPPLIER.matcher(trimmed).find()
                || NATIVE_P2_SIDEKICK_ITERATION.matcher(trimmed).find()
                || isRawNativeP2SidekickAliasAccess(trimmed, nativeP2SidekickAliases);
    }

    private static boolean isRawNativeP2SidekickAliasAccess(String trimmed, Set<String> nativeP2SidekickAliases) {
        for (String alias : nativeP2SidekickAliases) {
            String quotedAlias = Pattern.quote(alias);
            if (Pattern.compile("\\bfor\\s*\\([^:]+:\\s*" + quotedAlias + "\\s*\\)").matcher(trimmed).find()
                    || Pattern.compile("\\b" + quotedAlias + "\\s*\\.\\s*get\\s*\\(\\s*0\\s*\\)")
                    .matcher(trimmed).find()
                    || Pattern.compile("\\b" + quotedAlias + "\\s*\\.\\s*getFirst\\s*\\(\\s*\\)")
                    .matcher(trimmed).find()
                    || Pattern.compile("\\b" + quotedAlias + "\\s*\\.\\s*stream\\s*\\(\\s*\\)\\s*\\.\\s*findFirst\\s*\\(")
                    .matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }

    private static void recordNativeP2SidekickAlias(
            String trimmed,
            Set<String> nativeP2SidekickAliases,
            Set<String> pendingNativeP2SidekickSnapshotAliases) {
        Matcher matcher = NATIVE_P2_SIDEKICK_ALIAS_ASSIGNMENT.matcher(trimmed);
        while (matcher.find()) {
            nativeP2SidekickAliases.add(matcher.group(1));
        }
        matcher = NATIVE_P2_SIDEKICK_SNAPSHOT_ALIAS_ASSIGNMENT.matcher(trimmed);
        while (matcher.find()) {
            nativeP2SidekickAliases.add(matcher.group(1));
        }
        matcher = NATIVE_P2_SIDEKICK_SNAPSHOT_ALIAS_START.matcher(trimmed);
        while (matcher.find()) {
            pendingNativeP2SidekickSnapshotAliases.add(matcher.group(1));
        }
        if (!pendingNativeP2SidekickSnapshotAliases.isEmpty() && trimmed.contains("getSidekicks(")) {
            nativeP2SidekickAliases.addAll(pendingNativeP2SidekickSnapshotAliases);
        }
        if (trimmed.contains(";")) {
            pendingNativeP2SidekickSnapshotAliases.clear();
        }
    }

    private static boolean isDirectLifecycleOperation(String trimmed) {
        return DIRECT_LIFECYCLE_OPERATION.matcher(trimmed).find();
    }

    private static boolean hasTouchResponseProfile(SourceText source) {
        return source.lines().stream()
                .anyMatch(line -> line.contains("getTouchResponseProfile("));
    }

    private static void assertNoProductionViolationsEndingWith(
            ViolationKind kind, String... pathSuffixes) throws IOException {
        assertEquals(List.of(), scanProductionSources().stream()
                .filter(violation -> violation.kind() == kind)
                .filter(violation -> endsWithAny(violation.path(), pathSuffixes))
                .toList());
    }

    private static void assertOwnedSourceUsesAllEnginePlayers(String relativePath,
                                                             String methodName) throws IOException {
        SourceText source = source(relativePath);
        String body = methodBody(source, methodName);
        String compactBody = body.replaceAll("\\s+", "");
        assertEquals(true, body.contains("ObjectPlayerQuery"),
                methodName + " should route player participation through ObjectPlayerQuery");
        assertEquals(true, compactBody.contains(
                        "playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)"),
                methodName + " should obtain participants via playersFor(ALL_ENGINE_PLAYERS)");
        assertEquals(false, body.contains("getFocusedSprite("),
                methodName + " should not use focused-player shortcuts in guarded participation logic");
        assertEquals(false, body.contains("getSidekicks("),
                methodName + " should not use raw sidekick shortcuts in guarded participation logic");
    }

    private static void assertOwnedQueryHelperSuppliesSidekicks(String relativePath,
                                                               String methodName) throws IOException {
        SourceText source = source(relativePath);
        String body = methodBody(source, methodName);
        String compactBody = body.replaceAll("\\s+", "");
        assertEquals(true, body.contains("new ObjectPlayerQuery"),
                methodName + " should construct the local ObjectPlayerQuery adapter");
        assertEquals(true, compactBody.contains("List.copyOf(")
                        && compactBody.contains(".getSidekicks())"),
                methodName + " should snapshot runtime sidekicks into the query adapter");
        assertEquals(true, compactBody.contains("()->sidekicks"),
                methodName + " should pass the sidekick snapshot to ObjectPlayerQuery");
    }

    private static String methodBody(SourceText source, String methodName) {
        String text = String.join("\n", source.lines());
        Matcher declaration = Pattern.compile(
                "\\b(?:public|private|protected)\\b[^\\n;{}]*\\b"
                        + Pattern.quote(methodName) + "\\s*\\(")
                .matcher(text);
        if (!declaration.find()) {
            throw new AssertionError("Missing method " + methodName);
        }
        int bodyStart = text.indexOf('{', declaration.end());
        if (bodyStart < 0) {
            throw new AssertionError("Missing method body for " + methodName);
        }
        int depth = 0;
        for (int i = bodyStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(bodyStart, i + 1);
                }
            }
        }
        throw new AssertionError("Unterminated method body for " + methodName);
    }

    private static boolean endsWithAny(String path, String... suffixes) {
        for (String suffix : suffixes) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private enum ViolationKind {
        DIRECT_OBJECT_CONTROL_SETTER,
        RAW_NATIVE_P2_SIDEKICK_ACCESS,
        DIRECT_LIFECYCLE_OPERATION,
        TOUCH_PROFILE_HOOK_WITHOUT_PROFILE
    }

    private record SourceViolation(String path, String lineFragment, ViolationKind kind) {
    }
}
