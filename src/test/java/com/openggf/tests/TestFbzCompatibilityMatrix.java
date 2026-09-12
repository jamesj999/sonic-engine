package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.configuration.WidescreenAspect;
import com.openggf.game.CheckpointState;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kFBZEvents;
import com.openggf.game.sonic3k.objects.FbzBossPillarInstance;
import com.openggf.game.sonic3k.objects.FbzCompatibilityInteractionProbe;
import com.openggf.game.sonic3k.objects.FbzCompatibilityInteractionProbe.Evidence;
import com.openggf.game.sonic3k.objects.FbzEndBossEventControlInstance;
import com.openggf.game.sonic3k.objects.FbzEndBossInstance;
import com.openggf.game.sonic3k.objects.TestFbzAct2TraversalPreboss;
import com.openggf.game.sonic3k.objects.TestFbzAct2TraversalPreboss.RouteCompletionEvidence;
import com.openggf.game.sonic3k.runtime.FbzZoneRuntimeState;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.spawn.PlacementViewportWidth;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting FBZ compatibility audit.
 *
 * <p>Each matrix row first executes the real synchronous FBZ1 -> FBZ2 reload,
 * then reboots a fresh production session for the cold-start FBZ2 mechanical
 * route through traversal, both bosses, capsule, exit, and the Sandopolis Act
 * 0 request. The complete-run BK2 trace, not this direct Act-2 route, is the
 * oracle for inherited magnetic cadence.</p>
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestFbzCompatibilityMatrix {
    private static final int PLANE_TRIGGER_X = 0x2E80;
    private static final int BOSS_CAMERA_MAX_X = 0x32B8;
    private static final int BOSS_REBASED_CAMERA_X = BOSS_CAMERA_MAX_X - 0x45C;
    private static final List<InputRun> STARPOST_6_TO_BOSS = List.of(
            new InputRun(600, 0x00),
            new InputRun(300, 0x08),
            new InputRun(20, 0x18),
            new InputRun(300, 0x08),
            new InputRun(800, 0x00),
            new InputRun(60, 0x08),
            new InputRun(2140, 0x00),
            new InputRun(20, 0x18),
            new InputRun(100, 0x08));

    @ParameterizedTest(name = "multi-sidekick synchronous transition preflight: {0}")
    @MethodSource("teamCases")
    void configuredTeamSynchronousTransitionPreflight(TeamCase team) throws Exception {
        try (ConfigurationScope ignored = ConfigurationScope.open()) {
            configureNative(team.sidekicks(), WidescreenAspect.NATIVE_4_3);
            assertSynchronousMagneticTransitionCompatibility(
                    team.label(), 320, "off",
                    transition -> assertTeamGraph(transition.sprite(), team));
        }
    }

    @ParameterizedTest(name = "widescreen synchronous transition preflight: {0}px")
    @MethodSource("widthCases")
    void viewportSynchronousTransitionPreflight(WidescreenAspect aspect, int width)
            throws Exception {
        try (ConfigurationScope ignored = ConfigurationScope.open()) {
            configureNative("tails", aspect, width);
            assertSynchronousMagneticTransitionCompatibility(width + "px", width, "off",
                    transition -> assertEquals(width,
                            transition.camera().getWidth() & 0xFFFF));
        }
    }

    @ParameterizedTest(name = "donor synchronous transition preflight: {0}")
    @MethodSource("donationCases")
    void donorSynchronousTransitionPreflight(String donor, Path donorRom) throws Exception {
        assertTrue(donor.equals("off") || donorRom != null,
                "Required " + donor.toUpperCase(java.util.Locale.ROOT)
                        + " donor ROM is unavailable; mandatory compatibility rows fail closed");
        try (ConfigurationScope ignored = ConfigurationScope.open()) {
            configureCompatibility("", WidescreenAspect.NATIVE_4_3, donor, donorRom);
            assertSynchronousMagneticTransitionCompatibility(
                    "donor=" + donor, 320, donor, transition -> {
                        if (donor.equals("off")) {
                            assertSame(GameServices.module().getRules(),
                                    transition.sprite().getGameRules());
                        } else {
                            assertNotSame(GameServices.module().getRules(),
                                    transition.sprite().getGameRules());
                        }
                    });
        }
    }

    @ParameterizedTest(name = "multi-sidekick complete route: {0}")
    @MethodSource("teamCases")
    void configuredTeamSurvivesSharedPlaneAndBossState(TeamCase team) throws Exception {
        try (ConfigurationScope ignored = ConfigurationScope.open()) {
            configureNative(team.sidekicks(), WidescreenAspect.NATIVE_4_3);
            // The matching standalone preflight covers this configuration's
            // synchronous transition. This route starts from a fresh session.
            restartFreshGameplaySession();
            List<AbstractPlayableSprite> routedSidekicks = new ArrayList<>();
            AbstractPlayableSprite[] routedMain = new AbstractPlayableSprite[1];
            RouteCompletionEvidence completion = TestFbzAct2TraversalPreboss
                    .runNativeStartFixedInputsReachSafeLateFrontierWithAllRouteMilestones(start -> {
                        routedMain[0] = start.sprite();
                        routedSidekicks.addAll(GameServices.sprites().getSidekicks());
                        assertTeamGraph(start.sprite(), team);
                    });
            assertMandatoryRouteCompatibility(completion, team.label());
            assertEquals(completion.frames(), completion.sidekickAuditFrames(),
                    "every complete-route frame must audit the configured team");
            assertTrue(completion.sidekickIdentityOrderPreserved(),
                    "sidekick identity/order changed during the complete route");
            assertTrue(completion.sidekickAliveEveryFrame(),
                    "a configured sidekick was dead during the complete route");
            assertTrue(completion.sidekickControllerEveryFrame(),
                    "a sidekick lost CPU-controller ownership during the complete route");
            assertTrue(completion.sidekickLeaderChainEveryFrame(),
                    "the sidekick daisy-chain leader graph diverged during the complete route");
            assertSame(routedMain[0], GameServices.sprites().getMainPlayable(),
                    "P1 must retain completion/transition authority");
            assertEquals(routedSidekicks, GameServices.sprites().getSidekicks(),
                    "the complete route must retain the exact sidekick instances and ordering");
            assertTeamGraph(routedMain[0], team);
            assertFalse(routedMain[0].getDead(), "P1 died before the SOZ request");

            Evidence optional = FbzCompatibilityInteractionProbe.run(320);
            assertOptionalInteractionCompatibility(optional, team.label());

            // Authority isolation is a separate production fixture after the
            // fresh cold-start mechanical route.
            restartFreshGameplaySession();
            HeadlessTestFixture fixture = buildAct2Fixture();
            assertTeamGraph(fixture.sprite(), team);
            assertExtraSidekicksCannotTriggerPlaneEvent(fixture);
            fixture = buildAct2Fixture();
            assertTeamGraph(fixture.sprite(), team);

            RouteSliceEvidence evidence = runStarpost6ToBoss(fixture);
            assertRouteSliceReachedBossAlive(fixture, evidence, team.label());
            assertTeamGraph(fixture.sprite(), team);
        }
    }

    @ParameterizedTest(name = "widescreen complete route: {0}px")
    @MethodSource("widthCases")
    void viewportKeepsWorldThresholdsCullingAndBossContainment(
            WidescreenAspect aspect, int width) throws Exception {
        try (ConfigurationScope ignored = ConfigurationScope.open()) {
            configureNative("tails", aspect, width);
            // The matching standalone preflight covers this configuration's
            // synchronous transition. This route starts from a fresh session.
            restartFreshGameplaySession();
            RouteCompletionEvidence completion = TestFbzAct2TraversalPreboss
                    .runNativeStartFixedInputsReachSafeLateFrontierWithAllRouteMilestones(start -> {
                        assertEquals(width, start.camera().getWidth() & 0xFFFF);
                        GameServices.graphics().setViewport(0, 0, width, 224);
                    });
            assertMandatoryRouteCompatibility(completion, width + "px");
            Evidence optional = FbzCompatibilityInteractionProbe.run(width);
            assertOptionalInteractionCompatibility(optional, width + "px");
            assertTrue(completion.minScreenX() <= 16,
                    "the complete route must exercise the authored left horizontal extreme");
            int placementLoadAhead = Math.max(0x280, width + 0x80);
            assertTrue(completion.maxPlacedSpawnScreenX() >= placementLoadAhead - 0x80
                            && completion.maxPlacedSpawnScreenX() < placementLoadAhead + 0x80,
                    "canonical placement must materialize inside the viewport-scaled right "
                            + "load-ahead chunks; actual=" + completion.maxPlacedSpawnScreenX());
            assertTrue(completion.minPlacedDespawnScreenX() >= -0x180
                            && completion.minPlacedDespawnScreenX() <= -0x80,
                    "the same canonical placement must cull inside the ROM left unload chunks; "
                            + "actual=" + completion.minPlacedDespawnScreenX());
            assertTrue(completion.maxScreenX() >= 320 - 24,
                    "the boss route must exercise the ROM-authored right player boundary");
            assertTrue(completion.maxPlayerX() >= BOSS_CAMERA_MAX_X + 320 - 24,
                    "the route must reach the fixed native right world edge at every width");

            restartFreshGameplaySession();
            HeadlessTestFixture fixture = buildAct2Fixture();
            GameServices.graphics().setViewport(0, 0, width, 224);

            assertEquals(width, PlacementViewportWidth.current());
            assertEquals(width, fixture.camera().getWidth() & 0xFFFF);
            assertEquals(width, GameServices.graphics().getViewportWidth());

            RouteSliceEvidence evidence = runStarpost6ToBoss(fixture);
            assertRouteSliceReachedBossAlive(fixture, evidence, width + "px");
            assertTrue(evidence.observedWaitingBelowWorldTrigger(),
                    "viewport width must not activate the plane event before P1 reaches world X $2E80");
            assertTrue(evidence.observedTriggerAtOrBeyondWorldThreshold(),
                    "the production event must still activate at world X $2E80");
            assertTrue(evidence.controllerObserved() && evidence.pillarObserved(),
                    "viewport-scaled placement/culling must retain both persistent pre-boss objects");
            assertTrue(evidence.arenaWorldBoundaryObserved(),
                    "the ROM $32B8 arena boundary must be observed independently of viewport width");
            assertTrue(evidence.exactArenaWorldLockObserved(),
                    "the arena must lock at the exact ROM $32B8 world coordinate; "
                            + evidence.diagnostic());

            int cameraLeft = fixture.camera().getX() & 0xFFFF;
            int cameraRight = cameraLeft + width;
            assertEquals(evidence.cameraMinX(), evidence.cameraMaxX(),
                    "the boss arena must converge to a closed horizontal lock; "
                            + evidence.diagnostic());
            assertEquals(BOSS_REBASED_CAMERA_X, evidence.cameraMaxX(),
                    "the $45C boss-load rebase must preserve the exact $32B8 world lock; "
                            + evidence.diagnostic());
            assertTrue(evidence.maxPlayerX() <= BOSS_CAMERA_MAX_X + 320 - 24 + 1,
                    "P1 must remain inside the ROM-authored world edge instead of escaping into "
                            + "widescreen-only void; " + evidence.diagnostic());
            assertTrue(evidence.stage8Frame() > evidence.triggerFrame()
                            && evidence.stage12Frame() > evidence.stage8Frame(),
                    "the carrier, arena lock, and plane-refresh stages must finish in order; "
                            + evidence.diagnostic());
            assertTrue(cameraLeft >= GameServices.level().getCurrentLevel().getMinX());
            assertTrue(cameraRight <= GameServices.level().getCurrentLevel().getMaxX() + width,
                    "the visible right extreme must not wrap outside the level's world range");
            assertTrue((fixture.sprite().getCentreY() & 0xFFFF)
                            < (fixture.camera().getMaxY() & 0xFFFF) + 224,
                    "neither horizontal camera extreme may expose a lethal fall below the arena");

        }
    }

    @ParameterizedTest(name = "donated complete mandatory route: {0}")
    @MethodSource("donationCases")
    void donatedMovementProfileCanReachTheMandatoryBossEntryWithoutSpindash(
            String donor, Path donorRom) throws Exception {
        assertTrue(donor.equals("off") || donorRom != null,
                "Required " + donor.toUpperCase(java.util.Locale.ROOT)
                        + " donor ROM is unavailable; mandatory compatibility rows fail closed");
        try (ConfigurationScope ignored = ConfigurationScope.open()) {
            configureCompatibility("", WidescreenAspect.NATIVE_4_3, donor, donorRom);

            assertEquals(!donor.equals("off"), CrossGameFeatureProvider.isActive());
            if (!donor.equals("off")) {
                assertEquals(donor, CrossGameFeatureProvider.getInstance().getDonorGameId());
            }

            // The matching standalone preflight covers this configuration's
            // synchronous transition. This route starts from a fresh session.
            restartFreshGameplaySession();

            RouteCompletionEvidence completion = TestFbzAct2TraversalPreboss
                    .runNativeStartFixedInputsReachSafeLateFrontierWithAllRouteMilestones(start -> {
                        if (donor.equals("off")) {
                            assertSame(GameServices.module().getRules(), start.sprite().getGameRules());
                        } else {
                            assertNotSame(GameServices.module().getRules(), start.sprite().getGameRules(),
                                    "the fresh route must create P1 with composed donor rules");
                        }
                    }, donor);
            assertMandatoryRouteCompatibility(completion, "donor=" + donor);
            if (donor.equals("s1")) {
                assertTrue(completion.s1DonationUpperLoopAssistConsumed(),
                        "S1 route crossed the upper-loop envelope without consuming its typed assist");
                assertTrue(completion.s1DonationLowerLoopAssistConsumed(),
                        "S1 route crossed the lower-loop envelope without consuming its typed assist");
            } else {
                assertFalse(completion.s1DonationUpperLoopAssistConsumed(),
                        donor + " must not consume the S1-only upper-loop assist");
                assertFalse(completion.s1DonationLowerLoopAssistConsumed(),
                        donor + " must not consume the S1-only lower-loop assist");
            }
            // Mandatory traversal follows the authored lower route. Upper
            // optional branches (including the placed $09C8 $E5 crane) are
            // exercised below through exact production-object probes instead
            // of being misclassified as boss-entry requirements.
            Evidence optional = FbzCompatibilityInteractionProbe.run(320);
            assertOptionalInteractionCompatibility(optional, "donor=" + donor);
            if (donor.equals("s1")) {
                assertFalse(completion.spindashObserved(),
                        "the S1-donated complete route must not depend on unavailable spindash");
            }
        }
    }

    private static HeadlessTestFixture buildAct2Fixture() {
        return HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 1)
                .build();
    }

    private static void assertSynchronousMagneticTransitionCompatibility(
            String label, int width, String donor,
            Consumer<HeadlessTestFixture> configurationAssertion)
            throws Exception {
        HeadlessTestFixture.Builder transitionBuilder = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre();
        if (!donor.equals("off")) {
            transitionBuilder.withCrossGameDonation(donor);
        }
        HeadlessTestFixture transition = transitionBuilder.build();
        GameServices.graphics().setViewport(0, 0, width, 224);
        assertEquals(width, transition.camera().getWidth() & 0xFFFF,
                label + " did not retain its configured camera width in FBZ1");
        configurationAssertion.accept(transition);

        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        Sonic3kFBZEvents act1Events = manager.getFbzEvents();
        FbzZoneRuntimeState act1 = assertInstanceOf(
                FbzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        // Contract sentinel only: this proves the native global survives the
        // real reload. It is not trace-state seeding; the BK2 run owns cadence.
        act1.restoreMagneticTransitionState(new FbzZoneRuntimeState.MagneticTransitionState(
                Sonic3kFBZEvents.MagneticPolarity.ACTIVE, 0xA5, true, 0x1200));

        act1Events.setEventsFg5(true);
        act1Events.updateAct1BackgroundEvent(transition.sprite().getCentreX(),
                transition.sprite().getCentreY(), false);

        assertEquals(1, GameServices.level().getCurrentAct(),
                label + " did not execute the production synchronous FBZ reload");
        FbzZoneRuntimeState act2 = assertInstanceOf(
                FbzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        assertNotSame(act1, act2, label + " retained the dead Act-1 runtime adapter");
        assertTrue(act2.isBackedBy(manager.getFbzEvents()),
                label + " did not bind the replacement Act-2 event owner");
        assertEquals(Sonic3kFBZEvents.MagneticPolarity.ACTIVE, act2.magneticPolarity(),
                label + " lost the ROM-global magnetic polarity at reload");
        assertEquals(0xA5, act2.magneticTimerPhase(),
                label + " lost the magnetic phase at reload");
        assertTrue(act2.magneticEdgeObserved(),
                label + " lost the magnetic edge guard at reload");
        assertEquals(0x1200, act2.magneticLastEdgeFrame(),
                label + " lost the last magnetic edge frame at reload");
        assertEquals(width, transition.camera().getWidth() & 0xFFFF,
                label + " changed camera width during the synchronous reload");
        configurationAssertion.accept(transition);
        assertFalse(GameServices.level().isApplyingSynchronousScreenEventTransition(
                        Sonic3kZoneIds.ZONE_FBZ, 0, Sonic3kZoneIds.ZONE_FBZ, 1),
                label + " leaked the one-shot transition context");
    }

    private static void assertTeamGraph(AbstractPlayableSprite main, TeamCase team) {
        assertSame(main, GameServices.sprites().getMainPlayable());
        assertFalse(main.isCpuControlled(), "P1 must retain native event/completion authority");
        assertFalse(main.getDead(), "P1 must remain alive through the asserted route boundary");
        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getSidekicks();
        assertEquals(team.expectedCodes(), sidekicks.stream()
                .map(AbstractPlayableSprite::getCode).toList());
        assertEquals(team.expectedCharacterNames(), sidekicks.stream()
                .map(GameServices.sprites()::getSidekickCharacterName).toList());
        assertEquals(team.sidekicks().isBlank()
                        ? PlayerCharacter.SONIC_ALONE : PlayerCharacter.SONIC_AND_TAILS,
                assertInstanceOf(FbzZoneRuntimeState.class,
                        GameServices.zoneRuntimeRegistry().current()).playerCharacter(),
                "extra participants must not invent a new native Player_mode");

        Set<PlayerSpriteRenderer> renderers = Collections.newSetFromMap(new IdentityHashMap<>());
        int previousBankEnd = LevelManager.SIDEKICK_PATTERN_BASE;
        for (int index = 0; index < sidekicks.size(); index++) {
            AbstractPlayableSprite sidekick = sidekicks.get(index);
            assertFalse(sidekick.getDead(), "configured sidekick died or lost its live identity");
            assertTrue(sidekick.isCpuControlled());
            assertNotNull(sidekick.getCpuController());
            assertNotNull(sidekick.getAnimationProfile());
            PlayerSpriteRenderer renderer = sidekick.getSpriteRenderer();
            assertNotNull(renderer);
            assertTrue(renderers.add(renderer),
                    "each configured participant needs its own renderer/DPLC bank owner");
            assertTrue(renderer.patternBankBase() >= previousBankEnd,
                    "live sidekick DPLC ranges must not overlap");
            assertTrue(renderer.patternBankBase() >= LevelManager.SIDEKICK_PATTERN_BASE);
            assertTrue(renderer.patternBankBase() > 0x7FF,
                    "sidekick art must use the virtual pattern-ID path");
            assertTrue(renderer.patternBankCapacity() > 0);
            previousBankEnd = renderer.patternBankBase() + renderer.patternBankCapacity();
            if (index == 0) {
                assertSame(main, sidekick.getCpuController().getLeader());
            } else {
                assertSame(sidekicks.get(index - 1), sidekick.getCpuController().getLeader());
            }
        }
    }

    private static void assertExtraSidekicksCannotTriggerPlaneEvent(HeadlessTestFixture fixture) {
        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getSidekicks();
        if (sidekicks.isEmpty()) return;
        restartAtCheckpoint6(fixture);
        assertTrue((fixture.sprite().getCentreX() & 0xFFFF) < PLANE_TRIGGER_X);
        for (int index = 0; index < sidekicks.size(); index++) {
            sidekicks.get(index).setCentreX((short) (PLANE_TRIGGER_X + 0x100 + index * 0x20));
            assertTrue((sidekicks.get(index).getCentreX() & 0xFFFF) >= PLANE_TRIGGER_X);
        }
        FbzEndBossEventControlInstance controller = null;
        for (int frame = 0; frame < 8 && controller == null; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            controller = GameServices.level().getObjectManager()
                    .activeObjectsOfType(FbzEndBossEventControlInstance.class)
                    .stream().findFirst().orElse(null);
        }
        assertNotNull(controller, "authority fixture did not materialize the production controller");
        assertEquals(0x31C0, controller.getX());
        assertEquals(0x0690, controller.getY(),
                "participants beyond $2E80 must not substitute for P1 event authority");
    }

    private static void assertMandatoryRouteCompatibility(
            RouteCompletionEvidence evidence, String label) {
        int width = GameServices.camera().getWidth() & 0xFFFF;
        int placementLoadAhead = Math.max(0x280, width + 0x80);
        assertEquals(evidence.frames(), evidence.sidekickAuditFrames(),
                label + " did not audit the configured team on every route frame");
        assertTrue(evidence.sidekickIdentityOrderPreserved(),
                label + " changed sidekick identity/order during the complete route");
        assertTrue(evidence.sidekickAliveEveryFrame(),
                label + " observed a dead sidekick during the complete route");
        assertTrue(evidence.sidekickControllerEveryFrame(),
                label + " observed a sidekick without CPU-controller ownership");
        assertTrue(evidence.sidekickLeaderChainEveryFrame(),
                label + " observed a broken sidekick daisy-chain leader graph");
        assertTrue(evidence.cageCapture(), label + " missed the wire-cage grab");
        assertTrue(evidence.elevatorRide(), label + " missed the elevator carrier");
        assertTrue(evidence.launcherRide(), label + " missed the floor launcher solid");
        assertTrue(evidence.chainControl(), label + " missed chain forced movement");
        assertTrue(evidence.nonPersistentSpawn() && evidence.nonPersistentDespawn(),
                label + " did not prove viewport placement spawn and cull lifecycle");
        assertTrue(evidence.maxPlacedSpawnScreenX() != Integer.MIN_VALUE,
                label + " did not record a placed nonpersistent spawn frontier");
        assertTrue(evidence.minPlacedDespawnScreenX() != Integer.MAX_VALUE,
                label + " did not record a placed nonpersistent despawn frontier");
        assertTrue(evidence.maxPlacedSpawnScreenX() >= placementLoadAhead - 0x80
                        && evidence.maxPlacedSpawnScreenX() < placementLoadAhead + 0x80,
                label + " placed spawn escaped the width-derived load-ahead chunks: "
                        + evidence.maxPlacedSpawnScreenX());
        assertTrue(evidence.minPlacedDespawnScreenX() >= -0x180
                        && evidence.minPlacedDespawnScreenX() <= -0x80,
                label + " placed despawn escaped the ROM left unload chunks: "
                        + evidence.minPlacedDespawnScreenX());
        assertTrue(evidence.hazardObserved(), label + " did not materialize a live hazard");
        assertTrue(evidence.exactArenaLock(), label + " missed exact $32B8 arena lock");
        assertTrue(evidence.bossCombat(), label + " never entered live boss combat");
        assertTrue(evidence.bossDefeat(), label + " never entered the boss defeat sequence");
        assertTrue(evidence.bossArenaPlayerContained(),
                label + " escaped the native boss world bounds during combat");
        assertTrue(evidence.capsuleObserved(), label + " never materialized the end capsule");
        assertTrue(evidence.capsuleCameraRelease(), label + " missed camera target $2FDC");
        assertTrue(evidence.exitCameraRelease(), label + " missed camera target $3738");
        assertTrue(evidence.forcedExit(), label + " never requested SOZ act 0");
        assertFalse(evidence.unsafeFall(), label + " entered a below-camera lethal fall band");
    }

    private static void assertOptionalInteractionCompatibility(Evidence evidence, String label) {
        assertTrue(evidence.prisonOpened(),
                label + " failed the exact placed $CF button/destruction graph probe");
        assertTrue(evidence.flamethrowerHazardActive(),
                label + " failed to materialize an active $E4 flame hazard");
        assertTrue(evidence.flamethrowerStandingSuppressed(),
                label + " failed $E4 standing suppression/timer behavior");
        assertTrue(evidence.flamethrowerAllEligibleSolid(),
                label + " failed scalable $E4 solid participation");
        assertTrue(evidence.magneticBothSubtypesMoved(),
                label + " failed active/inactive $74 polarity on subtypes $0E/$0F");
        assertTrue(evidence.magneticAllEligibleCoherent(),
                label + " dropped an eligible participant from a $74 carrier");
        assertTrue(evidence.spiderMainCapturedMovedReleased(),
                label + " failed the exact placed $E5 capture/motion/release graph");
        assertTrue(evidence.spiderSidekickAuthorityPreserved(),
                label + " allowed an extra sidekick to steal/stick in $E5 authority");
    }

    private static void configureCompatibility(
            String sidekicks, WidescreenAspect aspect, String donor, Path donorRom) throws Exception {
        CrossGameFeatureProvider provider = CrossGameFeatureProvider.getInstance();
        provider.resetState();
        SonicConfigurationService configuration = SonicConfigurationService.getInstance();
        configuration.clearSessionOverrides();
        configuration.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        configuration.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, sidekicks);
        configuration.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, aspect.name());
        configuration.resolveDisplayAspect();
        configuration.setSessionOverride(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED,
                !donor.equals("off"));
        configuration.setSessionOverride(SonicConfiguration.CROSS_GAME_SOURCE, donor);
        // Establish the S3K host module before donor initialization, without
        // constructing playable sprites or a level. The second open below is
        // the fresh production gameplay session consumed by the route fixture.
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        if (!donor.equals("off")) {
            SonicConfiguration romKey = donor.equals("s1")
                    ? SonicConfiguration.SONIC_1_ROM : SonicConfiguration.SONIC_2_ROM;
            configuration.setSessionOverride(romKey, donorRom.toAbsolutePath().toString());
            provider.initialize(donor);
        }
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    private static RouteSliceEvidence runStarpost6ToBoss(HeadlessTestFixture fixture) {
        restartAtCheckpoint6(fixture);

        boolean controllerObserved = false;
        boolean pillarObserved = false;
        boolean waitingBelowWorldTrigger = false;
        boolean triggerAtOrBeyondWorldThreshold = false;
        boolean arenaWorldBoundaryObserved = false;
        boolean exactArenaWorldLockObserved = false;
        boolean spindashObserved = false;
        FbzEndBossInstance boss = null;
        ObjectManager objects = GameServices.level().getObjectManager();
        int frames = 0;
        int triggerFrame = -1;
        int stage8Frame = -1;
        int stage12Frame = -1;
        int maxPlayerX = 0;
        int maxPlayerY = 0;

        outer:
        for (InputRun run : STARPOST_6_TO_BOSS) {
            for (int frame = 0; frame < run.frames(); frame++) {
                frames++;
                stepMask(fixture, run.mask());
                int playerX = fixture.sprite().getCentreX() & 0xFFFF;
                int playerY = fixture.sprite().getCentreY() & 0xFFFF;
                maxPlayerX = Math.max(maxPlayerX, playerX);
                maxPlayerY = Math.max(maxPlayerY, playerY);
                spindashObserved |= fixture.sprite().getSpindash();
                List<FbzEndBossEventControlInstance> controllers =
                        objects.activeObjectsOfType(FbzEndBossEventControlInstance.class);
                if (!controllers.isEmpty()) {
                    controllerObserved = true;
                    arenaWorldBoundaryObserved |= (fixture.camera().getMaxX() & 0xFFFF)
                            == BOSS_CAMERA_MAX_X;
                    exactArenaWorldLockObserved |= (fixture.camera().getMinX() & 0xFFFF)
                            == BOSS_CAMERA_MAX_X
                            && (fixture.camera().getMaxX() & 0xFFFF) == BOSS_CAMERA_MAX_X;
                    FbzEndBossEventControlInstance controller = controllers.getFirst();
                    boolean atNativeOrigin = controller.getX() == 0x31C0
                            && controller.getY() == 0x0690;
                    if (playerX < PLANE_TRIGGER_X) {
                        waitingBelowWorldTrigger |= atNativeOrigin;
                        assertTrue(atNativeOrigin,
                                "only P1's world x_pos may start the plane event");
                    } else if (!atNativeOrigin) {
                        triggerAtOrBeyondWorldThreshold = true;
                        if (triggerFrame < 0) triggerFrame = frames;
                    }
                }
                FbzZoneRuntimeState frameRuntime = assertInstanceOf(
                        FbzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
                if (frameRuntime.act2ForegroundStage() == 8 && stage8Frame < 0) stage8Frame = frames;
                if (frameRuntime.act2ForegroundStage() == 0x0C && stage12Frame < 0) stage12Frame = frames;
                pillarObserved |= !objects.activeObjectsOfType(FbzBossPillarInstance.class).isEmpty();
                List<FbzEndBossInstance> bosses = objects.activeObjectsOfType(FbzEndBossInstance.class);
                if (!bosses.isEmpty()) {
                    boss = bosses.getFirst();
                    break outer;
                }
                if (fixture.sprite().getDead()) {
                    break outer;
                }
            }
        }
        FbzZoneRuntimeState runtime = assertInstanceOf(
                FbzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        return new RouteSliceEvidence(controllerObserved, pillarObserved,
                waitingBelowWorldTrigger, triggerAtOrBeyondWorldThreshold,
                arenaWorldBoundaryObserved, exactArenaWorldLockObserved,
                spindashObserved, boss, frames,
                fixture.sprite().getCentreX() & 0xFFFF,
                fixture.sprite().getCentreY() & 0xFFFF,
                fixture.camera().getX() & 0xFFFF,
                fixture.camera().getY() & 0xFFFF,
                fixture.camera().getMinX() & 0xFFFF,
                fixture.camera().getMaxX() & 0xFFFF,
                runtime.act2ForegroundStage(),
                objects.occupiedDynamicSlotIds().size(),
                triggerFrame, stage8Frame, stage12Frame, maxPlayerX, maxPlayerY,
                objects.activeObjectsOfType(FbzEndBossEventControlInstance.class).stream()
                        .findFirst().map(FbzEndBossEventControlInstance::getX).orElse(-1),
                objects.activeObjectsOfType(FbzEndBossEventControlInstance.class).stream()
                        .findFirst().map(FbzEndBossEventControlInstance::getY).orElse(-1),
                fixture.camera().getMinY() & 0xFFFF,
                fixture.camera().getMaxY() & 0xFFFF);
    }

    private static void restartAtCheckpoint6(HeadlessTestFixture fixture) {
        ObjectSpawn checkpoint = GameServices.level().getCurrentLevel().getObjects().stream()
                .filter(spawn -> spawn.objectId() == GameServices.module().getCheckpointObjectId())
                .filter(spawn -> (spawn.subtype() & 0x7F) == 6)
                .findFirst().orElseThrow();
        CheckpointState state = assertInstanceOf(
                CheckpointState.class, GameServices.level().getCheckpointState());
        state.restoreFromSaved(checkpoint.x(), checkpoint.y(),
                checkpoint.x() - 0xA0, checkpoint.y() - 0x60, 6);
        GameServices.level().respawnPlayer();
    }

    private static void assertRouteSliceReachedBossAlive(
            HeadlessTestFixture fixture, RouteSliceEvidence evidence, String label) {
        assertFalse(fixture.sprite().getDead(), label + " died during the production route slice");
        assertTrue(evidence.controllerObserved(), label + " never materialized the plane controller");
        assertTrue(evidence.pillarObserved(), label + " never materialized the boss pillar");
        assertNotNull(evidence.boss(), label + " never allocated the real end-boss graph; "
                + evidence.diagnostic());
        assertEquals(FbzEndBossInstance.Phase.PRE_MUSIC, evidence.boss().phase());
    }

    private static void stepMask(HeadlessTestFixture fixture, int mask) {
        fixture.stepFrame(
                (mask & 0x01) != 0,
                (mask & 0x02) != 0,
                (mask & 0x04) != 0,
                (mask & 0x08) != 0,
                (mask & 0x10) != 0);
    }

    private static void configureNative(String sidekicks, WidescreenAspect aspect) {
        configureNative(sidekicks, aspect, aspect.pixelWidth());
    }

    private static void configureNative(
            String sidekicks, WidescreenAspect aspect, int pixelWidth) {
        CrossGameFeatureProvider.getInstance().resetState();
        SonicConfigurationService configuration = SonicConfigurationService.getInstance();
        configuration.clearSessionOverrides();
        configuration.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        configuration.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, sidekicks);
        configuration.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, aspect.name());
        configuration.resolveDisplayAspect();
        configuration.setSessionOverride(SonicConfiguration.SCREEN_WIDTH_PIXELS, pixelWidth);
        configuration.setSessionOverride(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        configuration.setSessionOverride(SonicConfiguration.CROSS_GAME_SOURCE, "off");
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    private static void restartFreshGameplaySession() {
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    private static Stream<TeamCase> teamCases() {
        return Stream.of(
                TeamCase.of("Sonic", "", List.of(), List.of()),
                TeamCase.of("Sonic + Tails", "tails", List.of("tails_p2"), List.of("tails")),
                TeamCase.of("Sonic + Tails + Knuckles", "tails,knuckles",
                        List.of("tails_p2", "knuckles_p3"), List.of("tails", "knuckles")),
                TeamCase.of("Sonic + Tails + Knuckles + Sonic", "tails,knuckles,sonic",
                        List.of("tails_p2", "knuckles_p3", "sonic_p4"),
                        List.of("tails", "knuckles", "sonic")),
                TeamCase.of("Sonic + three duplicate Sonics", "sonic,sonic,sonic",
                        List.of("sonic_p2", "sonic_p3", "sonic_p4"),
                        List.of("sonic", "sonic", "sonic")));
    }

    private static Stream<Arguments> widthCases() {
        return Stream.of(
                Arguments.of(WidescreenAspect.NATIVE_4_3, 320),
                Arguments.of(WidescreenAspect.WIDE_16_9, 400),
                Arguments.of(WidescreenAspect.WIDE_16_9, 512),
                Arguments.of(WidescreenAspect.ULTRA_21_9, 640),
                Arguments.of(WidescreenAspect.SUPER_32_9, 800));
    }

    private static Stream<Arguments> donationCases() {
        java.io.File s1Rom = RomTestUtils.ensureSonic1RomAvailable();
        java.io.File s2Rom = RomTestUtils.ensureSonic2RomAvailable();
        return Stream.of(
                Arguments.of("off", (Path) null),
                Arguments.of("s1", s1Rom == null ? null : s1Rom.toPath()),
                Arguments.of("s2", s2Rom == null ? null : s2Rom.toPath()));
    }

    private record InputRun(int frames, int mask) { }

    private record RouteSliceEvidence(
            boolean controllerObserved,
            boolean pillarObserved,
            boolean observedWaitingBelowWorldTrigger,
            boolean observedTriggerAtOrBeyondWorldThreshold,
            boolean arenaWorldBoundaryObserved,
            boolean exactArenaWorldLockObserved,
            boolean spindashObserved,
            FbzEndBossInstance boss,
            int frames,
            int playerX,
            int playerY,
            int cameraX,
            int cameraY,
            int cameraMinX,
            int cameraMaxX,
            int foregroundStage,
            int occupiedSlots,
            int triggerFrame,
            int stage8Frame,
            int stage12Frame,
            int maxPlayerX,
            int maxPlayerY,
            int controllerX,
            int controllerY,
            int cameraMinY,
            int cameraMaxY) {
        String diagnostic() {
            return "frames=" + frames
                    + " p=(0x" + Integer.toHexString(playerX) + ",0x"
                    + Integer.toHexString(playerY) + ") camera=(0x"
                    + Integer.toHexString(cameraX) + ",0x" + Integer.toHexString(cameraY)
                    + ") boundsX=(0x" + Integer.toHexString(cameraMinX) + ",0x"
                    + Integer.toHexString(cameraMaxX) + ") fgStage=0x"
                    + Integer.toHexString(foregroundStage) + " occupied=" + occupiedSlots
                    + " milestones=(trigger:" + triggerFrame + ",stage8:" + stage8Frame
                    + ",stageC:" + stage12Frame + ") maxP=(0x"
                    + Integer.toHexString(maxPlayerX) + ",0x" + Integer.toHexString(maxPlayerY)
                    + ") controller=(0x" + Integer.toHexString(controllerX) + ",0x"
                    + Integer.toHexString(controllerY) + ") boundsY=(0x"
                    + Integer.toHexString(cameraMinY) + ",0x"
                    + Integer.toHexString(cameraMaxY) + ")";
        }
    }

    private record TeamCase(
            String label,
            String sidekicks,
            List<String> expectedCodes,
            List<String> expectedCharacterNames) {
        static TeamCase of(String label, String sidekicks,
                           List<String> codes, List<String> names) {
            return new TeamCase(label, sidekicks, List.copyOf(codes), List.copyOf(names));
        }

        @Override public String toString() { return label; }
    }

    private static final class ConfigurationScope implements AutoCloseable {
        private final SonicConfigurationService configuration;
        private final String main;
        private final String sidekicks;
        private final String displayAspect;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;

        private ConfigurationScope(SonicConfigurationService configuration) {
            this.configuration = configuration;
            this.main = configuration.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
            this.sidekicks = configuration.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
            this.displayAspect = configuration.getString(SonicConfiguration.DISPLAY_ASPECT);
            this.viewportX = GameServices.graphics().getViewportX();
            this.viewportY = GameServices.graphics().getViewportY();
            this.viewportWidth = GameServices.graphics().getViewportWidth();
            this.viewportHeight = GameServices.graphics().getViewportHeight();
        }

        static ConfigurationScope open() {
            return new ConfigurationScope(SonicConfigurationService.getInstance());
        }

        @Override public void close() {
            CrossGameFeatureProvider.getInstance().resetState();
            configuration.clearSessionOverrides();
            configuration.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                    main == null ? "sonic" : main);
            configuration.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    sidekicks == null ? "tails" : sidekicks);
            configuration.setConfigValue(SonicConfiguration.DISPLAY_ASPECT,
                    displayAspect == null ? WidescreenAspect.NATIVE_4_3.name() : displayAspect);
            configuration.resolveDisplayAspect();
            GameServices.graphics().setViewport(
                    viewportX, viewportY, viewportWidth, viewportHeight);
            SessionManager.clear();
            TestEnvironment.activeGameplayMode();
        }
    }
}
