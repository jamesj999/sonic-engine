package com.openggf.tests;

import com.openggf.audio.NullAudioBackend;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionSnapshot;
import com.openggf.game.sonic3k.resources.S3kKosModuleSnapshot;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMgzLbzCarriedResultsTitleOwnership {

    private static final List<KosParent> RESULTS_PARENTS = List.of(
            new KosParent(Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR, 0x520),
            new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR, 0x568),
            new KosParent(Sonic3kConstants.ART_KOSM_RESULTS_SONIC_ADDR, 0x578));

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        GameServices.audio().setBackend(new NullAudioBackend());
        SessionManager.clear();
    }

    @Test
    void mgzCarriedResultsIsTheOnlyTitlePublisherAndRetainsNativeDispatchTiming()
            throws Exception {
        Route route = new Route(
                Sonic3kZoneIds.ZONE_MGZ,
                List.of(
                        new KosParent(Sonic3kConstants.ART_KOSM_MGZ_SPIKER_ADDR,
                                Sonic3kConstants.ARTTILE_MGZ_SPIKER),
                        new KosParent(Sonic3kConstants.ART_KOSM_MGZ_MANTIS_ADDR,
                                Sonic3kConstants.ARTTILE_MGZ_MANTIS)),
                30,
                3,
                true);

        verifyCarriedResultsLifecycle(route);
    }

    @Test
    void lbzCarriedResultsHoldsEnemyAdmissionUntilItsTitleOwnerPoll()
            throws Exception {
        Route route = new Route(
                Sonic3kZoneIds.ZONE_LBZ,
                List.of(
                        new KosParent(Sonic3kConstants.ART_KOSM_SNALE_BLASTER_ADDR,
                                Sonic3kConstants.ARTTILE_SNALE_BLASTER),
                        new KosParent(Sonic3kConstants.ART_KOSM_ORBINAUT_ADDR,
                                Sonic3kConstants.ARTTILE_ORBINAUT),
                        new KosParent(Sonic3kConstants.ART_KOSM_RIBOT_ADDR,
                                Sonic3kConstants.ARTTILE_RIBOT),
                        new KosParent(Sonic3kConstants.ART_KOSM_CORKEY_ADDR,
                                Sonic3kConstants.ARTTILE_CORKEY)),
                38,
                11,
                false);

        verifyCarriedResultsLifecycle(route);
    }

    @Test
    void stalePreReloadTitleOwnerCannotConsumeTheNewMgzLease() throws Exception {
        verifyStalePreReloadTitleOwnerRejected(Sonic3kZoneIds.ZONE_MGZ);
    }

    @Test
    void stalePreReloadTitleOwnerCannotConsumeTheNewLbzLease() throws Exception {
        verifyStalePreReloadTitleOwnerRejected(Sonic3kZoneIds.ZONE_LBZ);
    }

    private void verifyCarriedResultsLifecycle(Route route) throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(route.zone(), 0)
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.gameState().setEndOfLevelActive(true);
        HardwareTimingService timing = GameServices.hardwareTiming();
        RewindRegistry rewind = TestEnvironment.activeGameplayMode().getRewindRegistry();
        S3kResultsScreenObjectInstance results = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(PlayerCharacter.SONIC_AND_TAILS, 0));
        GameServices.level().getObjectManager().addDynamicObject(results);
        drainModuleHardware(timing);
        fixture.stepFrame(false, false, false, false, false);
        assertExactParentOccurrences(timing, RESULTS_PARENTS, 1,
                "the production results owner submits its three ROM parents once");

        var sourceObjects = GameServices.level().getObjectManager();
        armProductionTransition(route.zone());
        int transitionFrames = 0;
        while (GameServices.level().getCurrentAct() == 0 && transitionFrames++ < 100_000) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(1, GameServices.level().getCurrentAct(),
                "the production zone event must execute the Act 2 reload");
        var targetObjects = GameServices.level().getObjectManager();
        assertNotSame(sourceObjects, targetObjects,
                "the title owner must continue through the rebuilt ObjectManager");
        S3kResultsScreenObjectInstance carried = reacquireResultsOwner();
        assertSame(results, carried,
                "the exact semantic results SST must survive the production reload");

        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        var heldAdmission = artProvider.capture();
        assertEquals(RuntimeArtAdmissionOwnerKind.TITLE_OWNER,
                heldAdmission.runtimeArtAdmissionOwnerKind());
        assertFalse(heldAdmission.runtimeArtAdmissionBound());
        assertFalse(heldAdmission.runtimeArtAdmissionConsumed());
        assertExactParentOccurrences(timing, titleParents(route.zone()), 0,
                "the executor must not create a competing title overlay");
        assertExactParentOccurrences(timing, route.enemyParents(), 0,
                "target enemy admission must remain held by the carried title owner");

        CompositeSnapshot afterRecreation = rewind.capture();
        String afterRecreationState = carried.traceDebugDetails();
        fixture.stepFrame(false, false, false, false, false);
        rewind.restore(afterRecreation);
        carried = reacquireResultsOwner();
        assertEquals(afterRecreationState, carried.traceDebugDetails(),
                "the target-root snapshot must restore the carried owner through the rebuilt manager");

        int resultsFrames = 0;
        CompositeSnapshot beforePublication = null;
        while (GameServices.level().getApparentAct() == 0 && resultsFrames++ < 2_100) {
            beforePublication = rewind.capture();
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(1, GameServices.level().getApparentAct(),
                "child retirement publishes apparent Act 2 on its native dispatch");
        assertTrue(targetObjects.getActiveObjects().contains(reacquireResultsOwner()),
                "the results SST remains for the following title-init dispatch");
        assertExactParentOccurrences(timing, titleParents(route.zone()),
                route.titleCardInitializesOnPublication() ? 1 : 0,
                route.titleCardInitializesOnPublication()
                        ? "the carried title owner submits its parents on the native publication dispatch"
                        : "the child-retirement publication dispatch queues no title parent");
        assertTrue(beforePublication != null);

        fixture.stepFrame(false, false, false, false, false);
        assertExactParentOccurrences(timing, titleParents(route.zone()), 1,
                "the following rebuilt-manager dispatch queues exactly four title parents once");
        assertExactParentOccurrences(timing, route.enemyParents(), 0,
                "title initialization cannot admit target enemies");
        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();
        assertEquals(artProvider.capture().runtimeArtAdmissionLeaseId(),
                title.capture().runtimeArtAdmissionLeaseId(),
                "the carried title must bind the exact target batch lease");
        assertEquals(route.expectedResetDispatches(),
                title.capture().resetLevelGamestateCountdown(),
                "the carried title must retain the results-to-title reset timing");
        assertEquals(route.expectedExitDispatches(),
                title.capture().inLevelExitDelayFrames(),
                "the carried title must retain the route's phase-overlap-normalized exit timing");

        rewind.restore(beforePublication);
        assertExactParentOccurrences(timing, titleParents(route.zone()), 0,
                "pre-title restore removes the first publication");
        fixture.stepFrame(false, false, false, false, false);
        assertExactParentOccurrences(timing, titleParents(route.zone()),
                route.titleCardInitializesOnPublication() ? 1 : 0,
                route.titleCardInitializesOnPublication()
                        ? "replayed publication submits the carried title parents once"
                        : "replayed child retirement still publishes no title parent");
        fixture.stepFrame(false, false, false, false, false);
        assertExactParentOccurrences(timing, titleParents(route.zone()), 1,
                "replayed following dispatch publishes the four title parents once");

        int titleFrames = 0;
        int observedResetDispatches = 0;
        int observedExitDelayDispatches = 0;
        int levelGamestateResets = 0;
        var previousLevelGamestate = GameServices.level().getLevelGamestate();
        var previousTitleState = title.capture();
        while (!title.isComplete() && titleFrames++ < 2_000) {
            fixture.stepFrame(false, false, false, false, false);
            var currentTitleState = title.capture();
            observedResetDispatches += Math.max(0,
                    previousTitleState.resetLevelGamestateCountdown()
                            - currentTitleState.resetLevelGamestateCountdown());
            observedExitDelayDispatches += Math.max(0,
                    previousTitleState.inLevelExitDelayFrames()
                            - currentTitleState.inLevelExitDelayFrames());
            var currentLevelGamestate = GameServices.level().getLevelGamestate();
            if (currentLevelGamestate != previousLevelGamestate) {
                levelGamestateResets++;
                previousLevelGamestate = currentLevelGamestate;
            }
            previousTitleState = currentTitleState;
            if (!title.isComplete()
                    && !title.hasPublishedInLevelRuntimeArtAdmission()) {
                assertExactParentOccurrences(timing, route.enemyParents(), 0,
                        "target enemies remain held before the title owner's LoadEnemyArt poll");
            }
        }
        assertTrue(title.isComplete());
        assertEquals(route.expectedResetDispatches(), observedResetDispatches,
                "the title owner must execute every requested reset countdown dispatch");
        assertEquals(1, levelGamestateResets,
                "the carried title owner must publish exactly one display-time gamestate reset");
        assertEquals(route.expectedExitDispatches(), observedExitDelayDispatches,
                "the title owner must execute every route-owned exit delay dispatch before COMPLETE");
        assertTrue(title.ownsHeldLevelCounter(),
                "the carried title must retain reset/countdown timing ownership through COMPLETE");
        assertExactParentOccurrences(timing, route.enemyParents(), 1,
                "the provider pump after COMPLETE admits the exact target enemy batch once");

        CompositeSnapshot afterCompletion = rewind.capture();
        fixture.stepFrame(false, false, false, false, false);
        rewind.restore(afterCompletion);
        fixture.stepFrame(false, false, false, false, false);
        assertExactParentOccurrences(timing, titleParents(route.zone()), 1,
                "post-completion restore cannot duplicate title parents");
        assertExactParentOccurrences(timing, route.enemyParents(), 1,
                "post-completion restore cannot duplicate the target enemy batch");
    }

    private void verifyStalePreReloadTitleOwnerRejected(int zone) throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(zone, 0)
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());
        HardwareTimingService timing = GameServices.hardwareTiming();
        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();

        artProvider.prepareRuntimeArtForActTransition(
                zone, com.openggf.game.RuntimeArtAdmissionPolicy.TITLE_OWNER);
        GameServices.level().requestInLevelTitleCard(zone, 0);
        fixture.stepFrame(false, false, false, false, false);
        int titleFrames = 0;
        while (!title.willSetInLevelEndOfLevelFlagThisUpdate() && titleFrames++ < 2_000) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(title.willSetInLevelEndOfLevelFlagThisUpdate(),
                "the source title must naturally reach its lease-consuming dispatch");
        Sonic3kTitleCardManager.Snapshot staleOwner = title.capture();
        long staleLeaseId = staleOwner.runtimeArtAdmissionLeaseId();
        assertTrue(staleLeaseId >= 0);
        fixture.stepFrame(false, false, false, false, false);
        drainAllHardware(timing);

        GameServices.gameState().setEndOfLevelActive(true);
        GameServices.gameState().setEndOfLevelFlag(false);
        armProductionTransition(zone);
        int transitionFrames = 0;
        while (GameServices.level().getCurrentAct() == 0 && transitionFrames++ < 100_000) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(1, GameServices.level().getCurrentAct());
        drainAllHardware(timing);
        assertTrue(artProvider.capture().runtimeArtAdmissionLeaseId() != staleLeaseId,
                "the target route must issue a new exact title-owner lease");

        title.restore(staleOwner);
        var providerBeforeRejectedAction = artProvider.capture();
        var hardwareBeforeRejectedAction = timing.capture();
        assertThrows(IllegalStateException.class,
                () -> fixture.stepFrame(false, false, false, false, false),
                "a stale pre-reload title owner cannot bind or consume the target lease");
        assertEquals(providerBeforeRejectedAction, artProvider.capture(),
                "stale-owner rejection must leave the full provider state unchanged");
        assertHardwareSnapshotUnchanged(hardwareBeforeRejectedAction, timing.capture(),
                "stale-owner rejection must leave the full hardware inventory unchanged");
    }

    private static void armProductionTransition(int zone) {
        Sonic3kLevelEventManager events = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        if (zone == Sonic3kZoneIds.ZONE_MGZ) {
            events.getMgzEvents().setEventsFg5(true);
        } else if (zone == Sonic3kZoneIds.ZONE_LBZ) {
            events.getLbzEvents().setEventsFg5(true);
        } else {
            throw new IllegalArgumentException("unsupported route " + zone);
        }
    }

    private static S3kResultsScreenObjectInstance reacquireResultsOwner() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(S3kResultsScreenObjectInstance.class::isInstance)
                .map(S3kResultsScreenObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static List<KosParent> titleParents(int zone) {
        return List.of(
                new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR, 0x500),
                new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR, 0x510),
                new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR, 0x53D),
                new KosParent(Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS[zone], 0x54D));
    }

    private static void assertExactParentOccurrences(
            HardwareTimingService timing,
            List<KosParent> expected,
            long occurrences,
            String message) {
        for (KosParent parent : expected) {
            var matches = timing.capture().jobs().stream()
                    .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                    .filter(job -> job.romSourceAddress() == parent.sourceAddress())
                    .filter(job -> job.destinationAddress() == parent.destinationTile() * 32)
                    .toList();
            assertEquals(occurrences, matches.size(), message + " " + parent);
            for (var match : matches) {
                assertTrue(match.handle().submissionFingerprint().startsWith("sha256:"),
                        message + " stable fingerprint " + parent);
            }
        }
    }

    private static void drainModuleHardware(HardwareTimingService timing) {
        for (int frame = 0;
                frame < 100_000
                        && timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE) > 0;
                frame++) {
            HardwareBoundaryPump.service(timing, S3kRuntimeArtCoordinator.current(),
                    HardwareServiceBoundary.PRE_MAIN_LOOP);
            HardwareBoundaryPump.service(timing, S3kRuntimeArtCoordinator.current(),
                    HardwareServiceBoundary.POST_OBJECTS);
        }
        assertEquals(0, timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    private static void drainAllHardware(HardwareTimingService timing) {
        for (int frame = 0;
                frame < 100_000
                        && (timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE) > 0
                        || timing.incompleteCount(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE) > 0);
                frame++) {
            HardwareBoundaryPump.service(timing, S3kRuntimeArtCoordinator.current(),
                    HardwareServiceBoundary.PRE_MAIN_LOOP);
            HardwareBoundaryPump.service(timing, S3kRuntimeArtCoordinator.current(),
                    HardwareServiceBoundary.POST_OBJECTS);
        }
        assertEquals(0, timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE));
        assertEquals(0, timing.incompleteCount(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE));
    }

    private static void assertHardwareSnapshotUnchanged(
            HardwareTimingSnapshot before, HardwareTimingSnapshot after, String message) {
        assertEquals(before.nextOrdinals(), after.nextOrdinals(), message + " ordinals");
        assertEquals(before.admissionPolicies(), after.admissionPolicies(), message + " policies");
        assertEquals(before.recordedAdmissionActive(), after.recordedAdmissionActive(),
                message + " recorded admission");
        assertEquals(before.hasSubmitted(), after.hasSubmitted(), message + " submitted flag");
        assertEquals(before.lastServicedBoundary(), after.lastServicedBoundary(),
                message + " service boundary");
        assertEquals(before.jobs().size(), after.jobs().size(), message + " job count");
        for (int i = 0; i < before.jobs().size(); i++) {
            assertHardwareJobUnchanged(before.jobs().get(i), after.jobs().get(i),
                    message + " job " + i);
        }
    }

    private static void assertHardwareJobUnchanged(
            HardwareTimingJob.Snapshot before, HardwareTimingJob.Snapshot after, String message) {
        assertEquals(before.kind(), after.kind(), message + " kind");
        assertEquals(before.romSourceAddress(), after.romSourceAddress(), message + " source");
        assertEquals(before.compressedLength(), after.compressedLength(), message + " compressed length");
        assertEquals(before.destinationAddress(), after.destinationAddress(), message + " destination");
        assertEquals(before.destinationLength(), after.destinationLength(), message + " destination length");
        assertEquals(before.compressionVariant(), after.compressionVariant(), message + " variant");
        assertEquals(before.moduleCount(), after.moduleCount(), message + " module count");
        assertEquals(before.exportableAcrossSegment(), after.exportableAcrossSegment(), message + " exportability");
        assertEquals(before.features(), after.features(), message + " features");
        assertEquals(before.handle(), after.handle(), message + " handle");
        assertArrayEquals(before.preparedPayload(), after.preparedPayload(), message + " prepared payload");
        assertEquals(before.ready(), after.ready(), message + " ready");
        assertEquals(before.claimed(), after.claimed(), message + " claimed");
        assertEquals(before.profileActive(), after.profileActive(), message + " profile active");
        assertEquals(before.physicallyRetired(), after.physicallyRetired(), message + " retired");
        assertEquals(before.assignedServiceFrames(), after.assignedServiceFrames(), message + " assigned frames");
        assertEquals(before.remainingServiceFrames(), after.remainingServiceFrames(), message + " remaining frames");
        assertEquals(before.eligibleBoundaries(), after.eligibleBoundaries(), message + " eligible boundaries");
        assertEquals(before.decisionSource(), after.decisionSource(), message + " decision source");
        assertEquals(before.serviceModel(), after.serviceModel(), message + " service model");
        assertPreparationSnapshotUnchanged(
                before.preparationSnapshot(), after.preparationSnapshot(), message + " preparation");
    }

    private static void assertPreparationSnapshotUnchanged(
            Object before, Object after, String message) {
        assertEquals(before.getClass(), after.getClass(), message + " type");
        if (before instanceof S3kKosModuleSnapshot beforeModule
                && after instanceof S3kKosModuleSnapshot afterModule) {
            assertEquals(beforeModule.descriptor(), afterModule.descriptor(), message + " descriptor");
            assertArrayEquals(beforeModule.archive(), afterModule.archive(), message + " archive");
            assertEquals(beforeModule.completedModules(), afterModule.completedModules(), message + " completed modules");
            assertEquals(beforeModule.activeModuleOffset(), afterModule.activeModuleOffset(), message + " active offset");
            assertEquals(beforeModule.activeChild(), afterModule.activeChild(), message + " active child");
            assertEquals(beforeModule.activeChildCompressedLength(), afterModule.activeChildCompressedLength(),
                    message + " active child length");
            assertArrayEquals(beforeModule.output(), afterModule.output(), message + " output");
            assertEquals(beforeModule.prepared(), afterModule.prepared(), message + " prepared");
            return;
        }
        if (before instanceof S3kKosDecompressionSnapshot beforeKos
                && after instanceof S3kKosDecompressionSnapshot afterKos) {
            assertEquals(beforeKos.descriptor(), afterKos.descriptor(), message + " descriptor");
            assertArrayEquals(beforeKos.compressedBytes(), afterKos.compressedBytes(), message + " compressed bytes");
            var beforeDecoder = beforeKos.decoder();
            var afterDecoder = afterKos.decoder();
            assertArrayEquals(beforeDecoder.input(), afterDecoder.input(), message + " decoder input");
            assertEquals(beforeDecoder.moduleStart(), afterDecoder.moduleStart(), message + " decoder module start");
            assertEquals(beforeDecoder.readPosition(), afterDecoder.readPosition(), message + " decoder read position");
            assertEquals(beforeDecoder.descriptor(), afterDecoder.descriptor(), message + " decoder descriptor");
            assertEquals(beforeDecoder.descriptorBitsRemaining(), afterDecoder.descriptorBitsRemaining(),
                    message + " decoder descriptor bits");
            assertArrayEquals(beforeDecoder.output(), afterDecoder.output(), message + " decoder output");
            assertEquals(beforeDecoder.complete(), afterDecoder.complete(), message + " decoder complete");
            return;
        }
        assertEquals(before, after, message);
    }

    private record Route(
            int zone,
            List<KosParent> enemyParents,
            int expectedResetDispatches,
            int expectedExitDispatches,
            boolean titleCardInitializesOnPublication) {
    }

    private record KosParent(int sourceAddress, int destinationTile) {
    }
}
