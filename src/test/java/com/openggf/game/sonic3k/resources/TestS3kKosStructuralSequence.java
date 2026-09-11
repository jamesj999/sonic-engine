package com.openggf.game.sonic3k.resources;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;
import com.openggf.game.sonic3k.objects.AizEndBossInstance;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.HCZLargeFanObjectInstance;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance;
import com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostObjectInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.RecordedCompletionAuthority;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingReplaySnapshot;
import com.openggf.trace.timing.HardwareTimingSchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kKosStructuralSequence {
    private static final Spec TITLE_RED = new Spec(
            "title red/ACT", 0x0D6F28, 0x500, 470, 1952, 1,
            "sha256:10eb568a70724c579f022914f56227c2c7fa421aafa8578aebaa874f0cffb0ca");
    private static final Spec TITLE_ZONE = new Spec(
            "title ZONE", 0x15C3A2, 0x510, 174, 384, 1,
            "sha256:05324378670c6afa8c6d99f6e5313d625d2d926e6bc16f25cd9d8d1a5a195bf8");
    private static final Spec TITLE_NUM1 = new Spec(
            "title act 1", 0x0D6D84, 0x53D, 185, 512, 1,
            "sha256:7059802f2a045495d22a16d8c858c1758b786bf5d5f46c45838fa10bc0d1f6aa");
    private static final Spec TITLE_NUM2 = new Spec(
            "title act 2", 0x0D6E46, 0x53D, 217, 512, 1,
            "sha256:6a444320f9895e832344fe96decfe18d3ca75b574d8882cd62864ed9992f6b58");
    private static final Spec RESULTS_GENERAL = new Spec(
            "results general", 0x0D6A62, 0x520, 788, 2304, 1,
            "sha256:b6debc7640297e307a6987331dd3176f6bf9566d601a8c0d7febad45b7bf458d");
    private static final Spec RESULTS_NUM1 = new Spec(
            "results act 1", 0x0D6D84, 0x568, 185, 512, 1,
            "sha256:64a9a2959de0cc8b5687a55d59bcccaa445b97c2400dac2de1352753c0b4acdb");
    private static final Spec RESULTS_SONIC = new Spec(
            "results Sonic", 0x15B95C, 0x578, 349, 576, 1,
            "sha256:fbb1ebaf94db4f7c6ced41ba88435b601af68df3ea0ac04d5d6ea1b1098dfe43");
    private static final Spec AIZ_TITLE = new Spec(
            "AIZ title", 0x39BDC8, 0x54D, 260, 960, 1,
            "sha256:8db61dd608db47c1390b250bccdfde88a1a1ff9c1f04cbf894fda75b33c3cd87");
    private static final Spec AIZ_MONKEY = new Spec(
            "AIZ Monkey Dude", 0x36800C, 0x548, 486, 736, 1,
            "sha256:65c8c371e1ca1f70acf3a74cc1fa689867dcffbe93617a8c968e3de9242f89b3");
    private static final Spec AIZ_BLOOMINATOR = new Spec(
            "AIZ Bloominator", 0x367DCA, 0x52A, 575, 960, 1,
            "sha256:5c387ee74a9433eebd0f6700c270d1def12cc8157434d37e33eaf8c422312399");
    private static final Spec AIZ_CATERKILLER = new Spec(
            "AIZ Caterkiller Jr", 0x3681FE, 0x55F, 509, 928, 1,
            "sha256:4728f00c19173741198c7795aa9cbea9b8277cde40083df9543743c89d273805");
    private static final Spec STARPOST_RED = new Spec(
            "StarPost red bonus stars", 0x187C4E, 0x5EC,
            93, 96, 1,
            "sha256:28a69b8f385d0f7355d90a7aa996d75d45e26eb4b2672d7ce3e0eec11a513b3f");
    private static final Spec AIZ_INTRO_PLANE = new Spec(
            "AIZ intro plane", 0x382624, 0x529, 1951, 4352, 2,
            "sha256:4423f6be47e039925c8575c68ed5eb22e9cba75f2aadd05f1d288d6c9579e723");
    private static final Spec AIZ_INTRO_EMERALDS = new Spec(
            "AIZ intro emeralds", 0x387CA6, 0x5B1, 208, 224, 1,
            "sha256:34b575dc3ee07365ac9f621cf3d1f8afb74e90e851c6ed50d6b9e1d1c92f62c5");
    private static final Spec AIZ1_MAIN = new Spec(
            "AIZ1 main level", 0x3A944E, 0x0BE, 10218, 18720, 5,
            "sha256:d713465cec785d85b0b2b0196b833180a4fd98b69f2477314b2a2c77ca0a8d74");
    private static final Spec AIZ_FIRE = new Spec(
            "AIZ fire overlay", 0x3AF5D0, 0x500, 2676, 3872, 1,
            "sha256:b218e238a2aff440adaeeb63571042dfa0ee0accaa18f1722284e498e8d55057");
    private static final Spec AIZ2_PRIMARY = new Spec(
            "AIZ2 primary transition", 0x3B15D2, 0x000, 8619, 16256, 4,
            "sha256:485c7bb659d1a5dce87d1614bbac566f95cf7097d549081fca20bacd583cae54");
    private static final Spec AIZ2_SECONDARY = new Spec(
            "AIZ2 secondary transition", 0x3B3784, 0x1FC, 4416, 7584, 2,
            "sha256:1987e0b39f825e15063286364154a0894ccef84ffdee1089ae6559a65b9e66ea");
    private static final Spec AIZ_BATTLESHIP_TERRAIN = new Spec(
            "AIZ battleship terrain", 0x3B48C6, 0x1FC, 2331, 4448, 2,
            "sha256:ffe5b36ee372ce7fde0c0520724b6336ea1213fe6305b6f4d06ab03f980c4fc8");
    private static final Spec AIZ_BATTLESHIP_OBJECT = new Spec(
            "AIZ battleship object", 0x399CC4, 0x500, 2751, 5632, 2,
            "sha256:ed2f3c00caace7384b32049364ad6052ccfb0a00335b10cb94b22f33a407a44d");
    private static final Spec AIZ_END_BOSS = new Spec(
            "AIZ end boss", 0x365260, 0x180, 7996, 15712, 4,
            "sha256:958df94ce3ad5d9b800ec4c16000eba2838e987f8c407a1939fd993f89225889");
    private static final Spec HCZ_TITLE = new Spec(
            "HCZ title", 0x39BEDA, 0x54D, 332, 1248, 1,
            "sha256:2c17b1dba426e8b37a9b4dd2e2fc175f78010f755f4d1d43d7b0a9948eee4ba2");
    private static final Spec HCZ_BLASTOID = new Spec(
            "HCZ Blastoid", 0x36A7C6, 0x539, 413, 640, 1,
            "sha256:f7d726c95e019598b69ed655a53fca44b42967d9361230092ba02e539abfa45f");
    private static final Spec HCZ_JAWZ = new Spec(
            "HCZ Jawz", 0x36A552, 0x539, 369, 640, 1,
            "sha256:9c1e13887bd31bf56ed6edecb5dc212e2befe5289a10318081cc54989e78dc2e");
    private static final Spec HCZ_TURBO_SPIKER = new Spec(
            "HCZ Turbo Spiker", 0x36A968, 0x500, 1044, 1824, 1,
            "sha256:a0cfa4e1dcbf68a9f43d444fe8b581988a40218afc636158cffc6eecdbad5cde");
    private static final Spec HCZ_MEGA_CHOPPER = new Spec(
            "HCZ Mega Chopper", 0x36A6C4, 0x54D, 252, 384, 1,
            "sha256:03987fe59d7e6ee2d0b3f8aa2cdf093d9583175911848154872734d89f503deb");
    private static final Spec HCZ_POINTDEXTER = new Spec(
            "HCZ Pointdexter", 0x36AD8A, 0x559, 674, 992, 1,
            "sha256:0956edb5f52b6e64cc09fc8ab775b37a044c3f3c3d97de85e62fdf4af9690197");
    private static final Spec HCZ2_SECONDARY = new Spec(
            "HCZ2 secondary", 0x3BFA6C, 0x11B, 7802, 17568, 5,
            "sha256:ceb4359c81d1986f274a43be8623ff580e9af1be0670ee213ff4845fa329c4b2");
    private static final Spec HCZ_HORIZONTAL_WALL = new Spec(
            "HCZ horizontal water wall", 0x390C02, 0x500, 1933, 4096, 1,
            "sha256:804c9cc21e4ec5733686f6562c70c6532aaecd95b0f6f0e06d37cbd176c56e75");
    private static final Spec HCZ_LARGE_FAN = new Spec(
            "HCZ large fan", 0x390900, 0x500, 763, 1440, 1,
            "sha256:90ab34bd03d56e718d56d5e21005533d0dbcb5bf111364664a3212673f871e8b");
    private static final Spec HCZ_VERTICAL_WALL = new Spec(
            "HCZ vertical water wall", 0x391394, 0x500, 1946, 4096, 1,
            "sha256:98f1422c1b71284596ac21cd7def4e840f1e202a3c18b827dcbd6ab3a5107e58");
    private static final Spec HCZ_END_GEYSER = new Spec(
            "HCZ end-cutscene vertical geyser", 0x391394, 0x36B, 1946, 4096, 1,
            "sha256:72b7cd0cfd5745df13ac9c638eb04c40ab8f14875de8fb0c38bb52cd7410b4ee");

    @AfterEach
    void tearDown() {
        new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL).init(0);
        AizPlaneIntroInstance.resetIntroPhaseState();
        SessionManager.clear();
    }

    @Test
    void productionAizGameplayOwnersBuildLiteralLedgerInNativeGroups()
            throws Exception {
        HardwareTimingService timing = startLevel(0, 0);

        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        provider.reloadStandaloneArtForActTransition(0);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initialize(0, 0);
        assertLiteralJob(timing, 0, 0x0D6F28, 0x500);
        assertLiteralJob(timing, 1, 0x15C3A2, 0x510);
        assertLiteralJob(timing, 2, 0x0D6D84, 0x53D);
        assertLiteralJob(timing, 3, 0x39BDC8, 0x54D);
        drainHardware(timing);
        advanceTitleToCompletion(title);

        provider.processRuntimeArtQueue();
        assertLiteralJob(timing, 4, 0x36800C, 0x548);
        assertLiteralJob(timing, 5, 0x367DCA, 0x52A);
        assertLiteralJob(timing, 6, 0x3681FE, 0x55F);
        drainHardware(timing);
        provider.processRuntimeArtQueue();

        AizPlaneIntroInstance intro = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
        intro.setServices(TestEnvironment.objectServices());
        Sonic player = new Sonic("sonic", (short) 0x40, (short) 0x420);
        GameServices.camera().setFocusedSprite(player);
        for (int frame = 0; frame < 80
                && moduleJobs(timing).size() < 9; frame++) {
            intro.update(frame, player);
        }
        assertLiteralJob(timing, 7, 0x382624, 0x529);
        assertLiteralJob(timing, 8, 0x387CA6, 0x5B1);
        drainHardware(timing);
        intro.update(80, player);

        AizPlaneIntroInstance.resetIntroPhaseState();
        Sonic3kAIZEvents act1 =
                new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act1.init(0);
        GameServices.camera().setX((short) 0x1400);
        act1.update(0, 0);
        assertLiteralJob(timing, 9, 0x3A944E, 0x0BE);
        drainHardware(timing);
        act1.update(0, 1);

        GameServices.camera().setX((short) 0x2F10);
        GameServices.camera().setY((short) 0x0200);
        act1.setEventsFg5(true);
        for (int frame = 2; frame < 100_000
                && !act1.isAct2TransitionRequested(); frame++) {
            service(timing, HardwareServiceBoundary.PRE_MAIN_LOOP);
            act1.update(0, frame);
            service(timing, HardwareServiceBoundary.POST_OBJECTS);
        }
        assertTrue(act1.isAct2TransitionRequested());
        assertLiteralJob(timing, 10, 0x3AF5D0, 0x500);
        assertLiteralJob(timing, 11, 0x3B15D2, 0x000);
        assertLiteralJob(timing, 12, 0x3B3784, 0x1FC);
        assertAiz2FireTransitionDirectOrder(timing);

        assertCapturedSession(timing, List.of(
                TITLE_RED,
                TITLE_ZONE,
                TITLE_NUM1,
                AIZ_TITLE,
                AIZ_MONKEY,
                AIZ_BLOOMINATOR,
                AIZ_CATERKILLER,
                AIZ_INTRO_PLANE,
                AIZ_INTRO_EMERALDS,
                AIZ1_MAIN,
                AIZ_FIRE,
                AIZ2_PRIMARY,
                AIZ2_SECONDARY));
    }

    @Test
    void suppressedDirectAdmissionRetiresPhysicalHeadAndRewindsBeforeParentStep()
            throws Exception {
        HardwareTimingService timing = startLevel(0, 0);
        S3kRuntimeArtCoordinator coordinator =
                S3kRuntimeArtCoordinator.current();
        coordinator.resetForMissingSnapshot();
        timing.resetForMissingSnapshot();
        RecordedCompletionAuthority authority = timing.beginRecordedAdmission(Map.of(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.NEMESIS_PLC_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED));
        HardwareWorkHandle parent = coordinator.moduleQueue().queue(
                GameServices.rom().getRom(),
                AIZ_MONKEY.source(),
                AIZ_MONKEY.destinationTile());
        coordinator.beforeTimingService(HardwareServiceBoundary.POST_OBJECTS);
        timing.service(HardwareServiceBoundary.POST_OBJECTS);
        coordinator.afterTimingService(HardwareServiceBoundary.POST_OBJECTS);
        HardwareWorkHandle child = timing.pendingHandles().stream()
                .filter(handle -> handle.kind()
                        == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .findFirst()
                .orElseThrow();
        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertTrue(timing.coordinatorPreparation(child).isPrepared());
        assertTrue(coordinator.directQueue().decompressionsPending());

        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(new HardwareTimingSchedule(List.of(
                new HardwareCompletionEdge(
                        1,
                        HardwareServiceBoundary.PRE_MAIN_LOOP,
                        child.kind(),
                        child.ordinal(),
                        child.submissionFingerprint()))));
        port.beginRawFrame(1);
        timing.service(HardwareServiceBoundary.VINT_SERVICE);
        port.apply(HardwareServiceBoundary.VINT_SERVICE);
        HardwareTimingSnapshot timingBeforeAdmission = timing.capture();
        HardwareTimingReplaySnapshot portBeforeAdmission = port.capture();
        S3kKosDecompressionQueueSnapshot directBeforeAdmission =
                coordinator.directQueue().capture();

        assertTrue(port.applySuppressedRowCompletion());
        coordinator.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);

        assertFalse(coordinator.directQueue().decompressionsPending());
        assertFalse(timing.coordinatorPreparation(parent).isPrepared(),
                "the KosM parent remains owned by its next POST_OBJECTS step");

        timing.restore(timingBeforeAdmission);
        port.restore(portBeforeAdmission);
        coordinator.directQueue().restore(directBeforeAdmission);
        assertTrue(coordinator.directQueue().decompressionsPending());

        assertTrue(port.applySuppressedRowCompletion());
        coordinator.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertFalse(coordinator.directQueue().decompressionsPending());
        assertEquals(1, port.capture().consumedIdentities().size());

        coordinator.beforeTimingService(HardwareServiceBoundary.POST_OBJECTS);
        timing.service(HardwareServiceBoundary.POST_OBJECTS);
        coordinator.afterTimingService(HardwareServiceBoundary.POST_OBJECTS);
        assertTrue(timing.coordinatorPreparation(parent).isPrepared());
        assertFalse(timing.isReady(parent),
                "recorded authority still owns the parent's later completion edge");
    }

    @Test
    void productionAiz2GameplayOwnersBuildIndependentLiteralLedger() {
        HardwareTimingService timing = startLevel(0, 1);
        Sonic3kAIZEvents act2 =
                new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2.init(1);
        act2.setDynamicResizeRoutine(8);
        GameServices.camera().setX((short) 0x4880);
        GameServices.camera().setY((short) 0x0200);
        act2.update(1, 0);
        assertLiteralJob(timing, 0, 0x3B48C6, 0x1FC);
        assertLiteralJob(timing, 1, 0x399CC4, 0x500);
        drainHardware(timing);
        act2.update(1, 1);

        Sonic player = new Sonic("sonic", (short) 0x40, (short) 0x420);
        AizEndBossInstance boss = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new AizEndBossInstance(
                        new ObjectSpawn(0x48E0, 0x015A, 0x92, 0, 0, false, 0)));
        boss.setServices(TestEnvironment.objectServices());
        boss.update(0, player);
        assertLiteralJob(timing, 2, 0x365260, 0x180);
        drainHardware(timing);
        boss.update(1, player);
        boss.update(2, player);
        assertEquals(3, moduleJobs(timing).size(),
                "AIZ end-boss art is a one-shot ROM load and must not be re-queued "
                        + "after its owner claims the prepared sheet");

        assertCapturedSession(timing, List.of(
                AIZ_BATTLESHIP_TERRAIN,
                AIZ_BATTLESHIP_OBJECT,
                AIZ_END_BOSS));
    }

    @Test
    void productionStarPostActivationQueuesExactBonusStarArt()
            throws Exception {
        HardwareTimingService timing = startLevel(0, 0);
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        provider.reloadStandaloneArtForActTransition(0);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initialize(0, 0);
        drainHardware(timing);
        advanceTitleToCompletion(title);
        provider.processRuntimeArtQueue();
        drainHardware(timing);
        provider.processRuntimeArtQueue();

        var services = TestEnvironment.objectServices();
        GameServices.gameState().configureSpecialStageProgress(7, 7);
        GameServices.level().getCheckpointState().clear();
        Sonic3kStarPostObjectInstance starPost =
                ObjectConstructionContext.construct(
                        services,
                        () -> new Sonic3kStarPostObjectInstance(
                                new ObjectSpawn(
                                        0x0200, 0x0300, 0x34, 1,
                                        0, false, 0)));
        starPost.setServices(services);
        Sonic player =
                new Sonic("sonic", (short) 0x0200, (short) 0x0300);
        player.setCentreX((short) 0x0200);
        player.setCentreY((short) 0x0300);
        player.addRings(28);
        starPost.update(0, player);

        assertLiteralJob(timing, 7, 0x187C4E, 0x5EC);
        drainHardware(timing);
        provider.processRuntimeArtQueue();
        var job = moduleJobs(timing).get(7);
        assertEquals(STARPOST_RED.compressedLength(),
                job.compressedLength());
        assertEquals(STARPOST_RED.destinationLength(),
                job.destinationLength());
        assertEquals(STARPOST_RED.moduleCount(), job.moduleCount());
        assertEquals(STARPOST_RED.fingerprint(),
                job.handle().submissionFingerprint());
        assertTrue(job.claimed());
    }

    @Test
    void productionAizPostReloadPreservesFireOverlayWithoutRequeue() {
        HardwareTimingService timing = startLevel(0, 0);
        AizPlaneIntroInstance.setMainLevelPhaseActive(true);
        Sonic3kAIZEvents act1 = new Sonic3kAIZEvents(
                new Sonic3kLoadBootstrap(
                        Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null));
        act1.init(0);
        GameServices.camera().setX((short) 0x2F10);
        GameServices.camera().setY((short) 0x0200);
        act1.setEventsFg5(true);

        for (int frame = 0; frame < 100_000
                && !act1.isAct2TransitionRequested(); frame++) {
            service(timing, HardwareServiceBoundary.PRE_MAIN_LOOP);
            act1.update(0, frame);
            service(timing, HardwareServiceBoundary.POST_OBJECTS);
        }
        assertTrue(act1.isAct2TransitionRequested());
        assertLiteralJob(timing, 0, 0x3AF5D0, 0x500);
        assertLiteralJob(timing, 1, 0x3B15D2, 0x000);
        assertLiteralJob(timing, 2, 0x3B3784, 0x1FC);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module()
                        .getLevelEventProvider();
        Sonic3kAIZEvents postReload = manager.getAizEvents();
        assertNotNull(postReload);
        assertTrue(postReload.isFireOverlayTilesLoaded(),
                "ROM-visible fire art must survive the act reload");
        assertTrue(postReload.getFireOverlayTileCount() > 0,
                "the resumed fire curtain must retain its prepared tile range");

        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        for (int frame = 0; frame < 64 && moduleJobs(timing).size() < 6; frame++) {
            postReload.update(1, frame);
            provider.processRuntimeArtQueue();
            service(timing, HardwareServiceBoundary.POST_OBJECTS);
            service(timing, HardwareServiceBoundary.PRE_MAIN_LOOP);
        }
        assertLiteralJob(timing, 3, 0x36800C, 0x548);
        assertLiteralJob(timing, 4, 0x367DCA, 0x52A);
        assertLiteralJob(timing, 5, 0x3681FE, 0x55F);
        assertEquals(6, moduleJobs(timing).size(),
                "AIZ1BGE_Finish continues with one LoadEnemyArt batch "
                        + "without inserting a second fire-overlay job");
    }

    @Test
    void productionResultsOwnerBuildsIndependentTerminalLedger() {
        HardwareTimingService timing = startLevel(0, 0);
        Sonic player = new Sonic("sonic", (short) 0x40, (short) 0x420);
        S3kResultsScreenObjectInstance results =
                ObjectConstructionContext.construct(
                        TestEnvironment.objectServices(),
                        () -> new S3kResultsScreenObjectInstance(
                                PlayerCharacter.SONIC_AND_TAILS, 0));
        results.setServices(TestEnvironment.objectServices());

        results.update(0, player);
        assertLiteralJob(timing, 0, 0x0D6A62, 0x520);
        assertLiteralJob(timing, 1, 0x0D6D84, 0x568);
        assertLiteralJob(timing, 2, 0x15B95C, 0x578);
        drainHardware(timing);
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module()
                        .getLevelEventProvider();
        for (int dispatch = 0;
                dispatch < manager.resultsCreateGateDispatches();
                dispatch++) {
            results.update(dispatch, player);
        }

        assertCapturedSession(timing, List.of(
                RESULTS_GENERAL,
                RESULTS_NUM1,
                RESULTS_SONIC));
    }

    @Test
    void productionHczOwnersBuildLiteralLedgerAndWaterRushAddsNoOrdinal()
            throws Exception {
        HardwareTimingService timing = startLevel(1, 0);
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        provider.reloadStandaloneArtForActTransition(1);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initialize(1, 0);
        drainHardware(timing);
        advanceTitleToCompletion(title);

        provider.processRuntimeArtQueue();
        assertLiteralJob(timing, 4, 0x36A7C6, 0x539);
        assertLiteralJob(timing, 5, 0x36A968, 0x500);
        assertLiteralJob(timing, 6, 0x36A6C4, 0x54D);
        assertLiteralJob(timing, 7, 0x36AD8A, 0x559);
        drainHardware(timing);
        provider.processRuntimeArtQueue();

        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(0);
        events.setEventsFg5(true);
        events.update(0, 0);
        assertLiteralJob(timing, 8, 0x3BFA6C, 0x11B);
        drainHardware(timing);
        events.update(0, 1);
        assertTrue(events.isTransitionRequested());

        Sonic player = new Sonic("sonic", (short) 0x0220, (short) 0x0520);
        var services = TestEnvironment.objectServices();
        HCZWaterWallObjectInstance horizontalWall =
                new HCZWaterWallObjectInstance(
                        new ObjectSpawn(0x0200, 0x0500, 0x3B, 0, 0, false, 0));
        horizontalWall.setServices(services);
        horizontalWall.update(0, player);
        horizontalWall.update(1, player);
        assertLiteralJob(timing, 9, 0x390C02, 0x500);
        drainHardware(timing);
        horizontalWall.update(2, player);

        int beforeWaterRush = moduleJobs(timing).size();
        HCZWaterRushObjectInstance waterRush = ObjectConstructionContext.construct(
                services,
                () -> new HCZWaterRushObjectInstance(
                        new ObjectSpawn(0x0200, 0x0500, 0x37, 0, 0, false, 0)));
        waterRush.setServices(services);
        waterRush.update(0, player);
        assertEquals(beforeWaterRush, moduleJobs(timing).size(),
                "HCZ Water Rush is Nemesis level-PLC art and must not consume a KosM ordinal");
        assertEquals(0x390348, Sonic3kConstants.ART_NEM_HCZ_WATER_RUSH_ADDR);
        assertEquals(0x037A, Sonic3kConstants.ARTTILE_HCZ_WATER_RUSH);

        HCZLargeFanObjectInstance fan = new HCZLargeFanObjectInstance(
                new ObjectSpawn(0x0200, 0x0500, 0x39, 0, 0, false, 0));
        fan.setServices(services);
        fan.update(0, player);
        assertLiteralJob(timing, 10, 0x390900, 0x500);
        drainHardware(timing);
        fan.update(1, player);

        Sonic geyserPlayer =
                new Sonic("sonic", (short) 0x0200, (short) 0x01C8);
        geyserPlayer.setCentreX((short) 0x0200);
        geyserPlayer.setCentreY((short) 0x01C8);
        HCZWaterWallObjectInstance verticalWall =
                new HCZWaterWallObjectInstance(
                        new ObjectSpawn(0x0200, 0x0200, 0x3B, 1, 0, false, 0));
        verticalWall.setServices(services);
        verticalWall.update(0, geyserPlayer);
        assertLiteralJob(timing, 11, 0x391394, 0x500);
        drainHardware(timing);
        verticalWall.update(1, geyserPlayer);

        HczEndBossGeyserCutscene endGeyser =
                new HczEndBossGeyserCutscene(0x0200, 0x0300);
        endGeyser.setServices(services);
        endGeyser.update(0, geyserPlayer);
        assertLiteralJob(timing, 12, 0x391394, 0x36B);
        drainHardware(timing);
        endGeyser.update(1, geyserPlayer);

        assertCapturedSession(timing, List.of(
                TITLE_RED,
                TITLE_ZONE,
                TITLE_NUM1,
                HCZ_TITLE,
                HCZ_BLASTOID,
                HCZ_TURBO_SPIKER,
                HCZ_MEGA_CHOPPER,
                HCZ_POINTDEXTER,
                HCZ2_SECONDARY,
                HCZ_HORIZONTAL_WALL,
                HCZ_LARGE_FAN,
                HCZ_VERTICAL_WALL,
                HCZ_END_GEYSER));
    }

    @Test
    void productionHcz2TitleAndEnemyOwnersReplaceBlastoidWithJawz()
            throws Exception {
        HardwareTimingService timing = startLevel(1, 1);
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        provider.reloadStandaloneArtForActTransition(1);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initialize(1, 1);
        drainHardware(timing);
        advanceTitleToCompletion(title);

        provider.processRuntimeArtQueue();
        assertLiteralJob(timing, 4, 0x36A552, 0x539);
        assertLiteralJob(timing, 5, 0x36A968, 0x500);
        assertLiteralJob(timing, 6, 0x36A6C4, 0x54D);
        assertLiteralJob(timing, 7, 0x36AD8A, 0x559);
        drainHardware(timing);
        provider.processRuntimeArtQueue();

        assertCapturedSession(timing, List.of(
                TITLE_RED,
                TITLE_ZONE,
                TITLE_NUM2,
                HCZ_TITLE,
                HCZ_JAWZ,
                HCZ_TURBO_SPIKER,
                HCZ_MEGA_CHOPPER,
                HCZ_POINTDEXTER));
    }

    /**
     * A mid-level {@code jsr (LoadEnemyArt).l} runs {@code Queue_Kos_Module}
     * for every entry during its caller's own dispatch
     * ({@code docs/skdisasm/sonic3k.asm:64281-64313}), so the parent batch is
     * on the module FIFO before that iteration's
     * {@code Process_Kos_Module_Queue} state step (7908) — no recorded timing
     * signal is involved.
     */
    @Test
    void midLevelEnemyBatchPublishesDuringItsOwnDispatch() {
        HardwareTimingService timing = startLevel(0, 0);
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        int before = moduleJobs(timing).size();

        provider.reloadEnemyKosArt();

        assertEquals(before + 3, moduleJobs(timing).size(),
                "the mid-level LoadEnemyArt owner publishes its parent batch");
    }

    private static HardwareTimingService startLevel(int zone, int act) {
        TestEnvironment.resetAll();
        SessionManager.clear();
        EngineServices.configure(
                EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        HeadlessTestFixture.builder()
                .withZoneAndAct(zone, act)
                .build();
        HardwareTimingService timing = GameServices.hardwareTiming();
        timing.resetForMissingSnapshot();
        return timing;
    }

    private static void assertLiteralJob(
            HardwareTimingService timing,
            int ordinal,
            int source,
            int destinationTile) {
        var job = timing.capture().jobs().stream()
                .filter(candidate -> candidate.kind()
                        == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE)
                .filter(candidate -> candidate.handle().ordinal() == ordinal)
                .findFirst()
                .orElseThrow();
        assertEquals(source, job.romSourceAddress());
        assertEquals(destinationTile * 32,
                job.destinationAddress());
    }

    private static void drainHardware(HardwareTimingService timing) {
        for (int frame = 0;
                frame < 100_000
                        && timing.incompleteCount(
                        com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE) > 0;
                frame++) {
            service(timing, HardwareServiceBoundary.PRE_MAIN_LOOP);
            service(timing, HardwareServiceBoundary.POST_OBJECTS);
        }
        assertEquals(0, timing.incompleteCount(
                com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    private static void advanceTitleToCompletion(Sonic3kTitleCardManager title) {
        for (int dispatch = 0; dispatch < 1_000 && !title.isComplete(); dispatch++) {
            title.update();
        }
        assertTrue(title.isComplete(),
                "enemy art remains title-owned until the EXIT to COMPLETE dispatch");
    }

    private static void service(
            HardwareTimingService timing,
            HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(
                timing, S3kRuntimeArtCoordinator.current(), boundary);
    }

    private static void assertCapturedSession(
            HardwareTimingService timing, List<Spec> expected) {
        var jobs = moduleJobs(timing);
        assertEquals(expected.size(), jobs.size());
        for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
            Spec spec = expected.get(ordinal);
            var job = jobs.get(ordinal);
            assertEquals(ordinal, job.handle().ordinal(), spec.owner());
            assertEquals(spec.fingerprint(),
                    job.handle().submissionFingerprint(), spec.owner());
            assertEquals(spec.source(), job.romSourceAddress(), spec.owner());
            assertEquals(spec.compressedLength(),
                    job.compressedLength(), spec.owner());
            assertEquals(spec.destinationTile() * 32,
                    job.destinationAddress(), spec.owner());
            assertEquals(spec.destinationLength(),
                    job.destinationLength(), spec.owner());
            assertEquals(spec.moduleCount(), job.moduleCount(), spec.owner());
            assertEquals("kosinski_moduled",
                    job.compressionVariant(), spec.owner());
            assertTrue(job.claimed(),
                    spec.owner() + " must retire through its production consumer");
        }
        assertTrue(timing.pendingHandles().isEmpty());
    }

    private static List<com.openggf.game.timing.HardwareTimingJob.Snapshot>
            moduleJobs(HardwareTimingService timing) {
        return timing.capture().jobs().stream()
                .filter(job -> job.kind()
                        == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
    }

    private static void assertAiz2FireTransitionDirectOrder(
            HardwareTimingService timing) throws Exception {
        var jobs = timing.capture().jobs().stream()
                .filter(job -> job.kind()
                        == com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .toList();
        int transitionStart = java.util.stream.IntStream.range(0, jobs.size())
                .filter(index -> jobs.get(index).handle().submissionFingerprint().equals(
                        "sha256:1cea11e3ea8787a99e5ff28cc80e9766d7047dcc60bf1078513826406d326083"))
                .findFirst()
                .orElse(-1);
        assertTrue(transitionStart >= 0,
                "AIZ fire transition must submit three direct terrain jobs "
                        + "before the first primary KosM child");

        int entry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
        var rom = GameServices.rom().getRom();
        assertDirectJob(jobs.get(transitionStart),
                rom.read32BitAddr(entry + 16) & 0x00FF_FFFF,
                S3kKosRamDestinations.RAM_START,
                "sha256:1cea11e3ea8787a99e5ff28cc80e9766d7047dcc60bf1078513826406d326083");
        assertDirectJob(jobs.get(transitionStart + 1),
                rom.read32BitAddr(entry + 8) & 0x00FF_FFFF,
                S3kKosRamDestinations.BLOCK_TABLE,
                "sha256:6ab93e490c16f5e4ec937fc30ccfdc3f8c36542ae3e0012ec9b6ee12f977d491");
        assertDirectJob(jobs.get(transitionStart + 2),
                rom.read32BitAddr(entry + 12) & 0x00FF_FFFF,
                S3kKosRamDestinations.blockTableOffset(0x0AB8),
                "sha256:3b4e06b082fdfed67a97e1eac519e483b98e7d2049b5270385f1c41d266f586c");
        assertEquals(
                "sha256:086520e8ae25a3855e9227dad5d3cd367bd30f50b95e92f70dd173f3c2d1325a",
                jobs.get(transitionStart + 3).handle().submissionFingerprint(),
                "the primary KosM child must enter the physical FIFO after "
                        + "the three ordinary Queue_Kos jobs");
    }

    private static void assertDirectJob(
            com.openggf.game.timing.HardwareTimingJob.Snapshot job,
            int source,
            int destination,
            String fingerprint) {
        assertEquals(source, job.romSourceAddress());
        assertEquals(destination, job.destinationAddress());
        assertEquals(fingerprint, job.handle().submissionFingerprint());
        assertTrue(job.claimed(),
                "the AIZ transition owner must retire its direct payload");
    }

    private record Spec(
            String owner,
            int source,
            int destinationTile,
            int compressedLength,
            int destinationLength,
            int moduleCount,
            String fingerprint) {
    }
}
