package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostInstance;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionSnapshot;
import com.openggf.game.sonic3k.resources.S3kKosModuleSnapshot;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardState;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzAct1EventFlow {

    private static final List<KosParent> RESULTS_PARENTS = List.of(
            new KosParent(Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR, 0x520),
            new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR, 0x568),
            new KosParent(Sonic3kConstants.ART_KOSM_RESULTS_SONIC_ADDR, 0x578));
    private static final List<KosParent> CNZ2_TITLE_PARENTS = List.of(
            new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR, 0x500),
            new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR, 0x510),
            new KosParent(Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR, 0x53D),
            new KosParent(Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS[Sonic3kZoneIds.ZONE_CNZ], 0x54D));
    private static final List<KosParent> CNZ_ENEMY_PARENTS = List.of(
            new KosParent(Sonic3kConstants.ART_KOSM_CNZ_SPARKLE_ADDR,
                    Sonic3kConstants.ARTTILE_CNZ_SPARKLE),
            new KosParent(Sonic3kConstants.ART_KOSM_CNZ_BATBOT_ADDR,
                    Sonic3kConstants.ARTTILE_CNZ_BATBOT),
            new KosParent(Sonic3kConstants.ART_KOSM_CLAMER_SHOT_ADDR,
                    Sonic3kConstants.ARTTILE_CNZ_CLAMER_SHOT),
            new KosParent(Sonic3kConstants.ART_KOSM_CNZ_BALLOON_ADDR,
                    Sonic3kConstants.ARTTILE_CNZ_BALLOON_PLC));

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        GameServices.audio().setBackend(new NullAudioBackend());
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void firstEventsFg5FallsThroughToFgRefresh_notActReload() {
        Sonic3kCNZEvents events = initCnzEvents(0);
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_AFTER_BOSS);
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_POST_BOSS);
        events.setEventsFg5(true);

        events.update(0, 0);

        assertEquals(Sonic3kCNZEvents.BG_FG_REFRESH, events.getBackgroundRoutine(),
                "CNZ1BGE_FGRefresh must keep Background_collision_flag active until "
                        + "Draw_PlaneVertSingleBottomUp exhausts Draw_delayed_rowcount "
                        + "(docs/skdisasm/sonic3k.asm:107523-107539)");
        assertFalse(events.isAct2TransitionRequested());
        assertFalse(events.isEventsFg5());

        advanceCnzPostBossRefresh(events, 1, 15);

        assertEquals(Sonic3kCNZEvents.BG_FG_REFRESH_2, events.getBackgroundRoutine());
    }

    @Test
    void secondEventsFg5AtTransitionStageRequestsSeamlessActSwap() {
        Sonic3kCNZEvents events = initCnzEvents(0);
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_DO_TRANSITION);
        events.setEventsFg5(true);
        GameServices.camera().setMinX((short) 0x31E0);
        GameServices.camera().setMaxX((short) 0x3260);
        GameServices.camera().setMinY((short) 0x00E0);
        GameServices.camera().setMaxY((short) 0x0300);
        GameServices.camera().setMaxYTarget((short) 0x0300);

        events.update(0, 1);

        assertTrue(events.isAct2TransitionRequested());
        assertEquals(0x0301, events.getPendingZoneActWord());
        assertEquals(-0x3000, events.getTransitionWorldOffsetX());
        assertEquals(0x0200, events.getTransitionWorldOffsetY());

        SeamlessLevelTransitionRequest request =
                GameServices.level().consumeSeamlessTransitionRequest();
        assertNotNull(request);
        assertEquals(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL, request.type());
        assertEquals(Sonic3kZoneIds.ZONE_CNZ, request.targetZone());
        assertEquals(1, request.targetAct());
        assertEquals(RuntimeArtAdmissionPolicy.TITLE_OWNER,
                request.runtimeArtAdmissionPolicy(),
                "the carried Obj_LevelResults/Obj_TitleCard SST owns CNZ2 enemy admission");
        assertEquals(-0x3000, request.playerOffsetX());
        assertEquals(0x0200, request.playerOffsetY());
        assertEquals(-0x3000, request.cameraOffsetX());
        assertEquals(0x0200, request.cameraOffsetY());
        assertTrue(request.preserveLevelGamestate(),
                "CNZ1BGE_DoTransition reloads level data but does not clear the current timer/rings "
                        + "(docs/skdisasm/sonic3k.asm:107603-107653)");
        assertTrue(request.preserveOffsetCameraPosition(),
                "CNZ1BGE_DoTransition offsets Camera_X/Y_pos directly; it does not recenter after Load_Level");
        assertEquals(0x01E0, request.postTransitionMinX(),
                "CNZ1BGE_DoTransition subtracts d0=$3000 from Camera_min_X_pos "
                        + "(docs/skdisasm/sonic3k.asm:107642)");
        assertEquals(0x0260, request.postTransitionMaxX(),
                "CNZ1BGE_DoTransition subtracts d0=$3000 from Camera_max_X_pos "
                        + "(docs/skdisasm/sonic3k.asm:107643)");
        assertEquals(0x02E0, request.postTransitionMinY(),
                "CNZ1BGE_DoTransition subtracts d1=-$200 from Camera_min_Y_pos "
                        + "(docs/skdisasm/sonic3k.asm:107644)");
        assertEquals(0x0500, request.postTransitionMaxY(),
                "CNZ1BGE_DoTransition subtracts d1=-$200 from Camera_max_Y_pos "
                        + "(docs/skdisasm/sonic3k.asm:107645)");
        assertEquals(0x0500, request.postTransitionMaxYTarget(),
                "CNZ1BGE_DoTransition copies Camera_max_Y_pos to Camera_target_max_Y_pos "
                        + "(docs/skdisasm/sonic3k.asm:107646)");
    }

    @Test
    void productionPostBossChainAdvancesToReloadGateAndManagerSeesTransitionRequest() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);

        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_POST_BOSS);
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_AFTER_BOSS);

        events.setEventsFg5(true);
        events.update(0, 0);
        assertEquals(Sonic3kCNZEvents.BG_FG_REFRESH, events.getBackgroundRoutine());
        assertFalse(manager.isAct2TransitionRequested());

        advanceCnzPostBossRefresh(events, 1, 15);
        assertEquals(Sonic3kCNZEvents.BG_FG_REFRESH_2, events.getBackgroundRoutine());
        assertFalse(manager.isAct2TransitionRequested());

        advanceCnzPostBossRefresh(events, 16, 16);
        assertEquals(Sonic3kCNZEvents.BG_DO_TRANSITION, events.getBackgroundRoutine());
        assertFalse(manager.isAct2TransitionRequested());

        events.setEventsFg5(true);
        events.update(0, 30);

        assertTrue(events.isAct2TransitionRequested());
        assertTrue(manager.isAct2TransitionRequested());
        assertEquals(0x0301, events.getPendingZoneActWord());
    }

    @Test
    void secondPostBossRefreshCompletionSpawnsRomEndSignAtTransitionGate() {
        CapturingCnzEvents events = new CapturingCnzEvents();
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_FG_REFRESH_2);

        advanceCnzPostBossRefresh(events, 0, 16);

        assertEquals(Sonic3kCNZEvents.BG_DO_TRANSITION, events.getBackgroundRoutine());
        assertNotNull(events.spawned,
                "CNZ1BGE_FGRefresh2 must allocate Obj_EndSign before CNZ1BGE_DoTransition "
                        + "(docs/skdisasm/sonic3k.asm:107590-107601)");
        assertTrue(events.spawned instanceof S3kSignpostInstance,
                "CNZ1BGE_FGRefresh2 allocates Obj_EndSign "
                        + "(docs/skdisasm/sonic3k.asm:107596-107597)");
        assertEquals(0x32C0, events.spawned.getX(),
                "CNZ1BGE_FGRefresh2 writes Obj_EndSign x_pos=$32C0 "
                        + "(docs/skdisasm/sonic3k.asm:107596-107598)");
    }

    @Test
    void cnzDoTransitionAppliesRomCoordinateRemapImmediately() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .startPosition((short) 0x32D0, (short) 0x04AC)
                .startPositionIsCentre()
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.camera().setX((short) 0x323B);
        GameServices.camera().setY((short) 0x044C);
        GameServices.camera().setMinX((short) 0x31E0);
        GameServices.camera().setMaxX((short) 0x3260);
        GameServices.camera().setMinY((short) 0x00E0);
        GameServices.camera().setMaxY((short) 0x0300);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .showInLevelTitleCard(false)
                .preserveOffsetCameraPosition(true)
                .postTransitionMinX(0x01E0)
                .postTransitionMaxX(0x0260)
                .postTransitionMinY(0x02E0)
                .postTransitionMaxY(0x0500)
                .postTransitionMaxYTarget(0x0500)
                .playerOffset(-0x3000, 0x0200)
                .cameraOffset(-0x3000, 0x0200)
                .build();

        GameServices.level().executeActTransition(request);

        assertEquals(0x02D0, fixture.sprite().getCentreX() & 0xFFFF,
                "CNZ1BGE_DoTransition subtracts d0=$3000 from Player_1 x_pos "
                        + "(docs/skdisasm/sonic3k.asm:107626-107629)");
        assertEquals(0x06AC, fixture.sprite().getCentreY() & 0xFFFF,
                "CNZ1BGE_DoTransition subtracts d1=-$200 from Player_1 y_pos "
                        + "(docs/skdisasm/sonic3k.asm:107626-107629)");
        assertEquals(0x023B, GameServices.camera().getX() & 0xFFFF,
                "CNZ1BGE_DoTransition subtracts d0=$3000 from Camera_X_pos "
                        + "(docs/skdisasm/sonic3k.asm:107626-107631)");
        assertEquals(0x064C, GameServices.camera().getY() & 0xFFFF,
                "CNZ1BGE_DoTransition subtracts d1=-$200 from Camera_Y_pos "
                        + "(docs/skdisasm/sonic3k.asm:107626-107631)");
        assertEquals(0x01E0, GameServices.camera().getMinX() & 0xFFFF,
                "CNZ1BGE_DoTransition offsets Camera_min_X_pos after Load_Level "
                        + "(docs/skdisasm/sonic3k.asm:107642)");
        assertEquals(0x0260, GameServices.camera().getMaxX() & 0xFFFF,
                "CNZ1BGE_DoTransition offsets Camera_max_X_pos after Load_Level "
                        + "(docs/skdisasm/sonic3k.asm:107643)");
        assertEquals(0x02E0, GameServices.camera().getMinY() & 0xFFFF,
                "CNZ1BGE_DoTransition offsets Camera_min_Y_pos after Load_Level "
                        + "(docs/skdisasm/sonic3k.asm:107644)");
        assertEquals(0x0500, GameServices.camera().getMaxY() & 0xFFFF,
                "CNZ1BGE_DoTransition offsets Camera_max_Y_pos after Load_Level "
                        + "(docs/skdisasm/sonic3k.asm:107645)");
        assertEquals(0x0500, GameServices.camera().getMaxYTarget() & 0xFFFF,
                "CNZ1BGE_DoTransition copies Camera_max_Y_pos to Camera_target_max_Y_pos "
                        + "(docs/skdisasm/sonic3k.asm:107646)");
    }

    @Test
    void cnzDoTransitionKeepsSignpostAndResultsObjectsAlive() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .startPosition((short) 0x32D0, (short) 0x04AC)
                .startPositionIsCentre()
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());

        S3kSignpostInstance signpost = new S3kSignpostInstance(0x32C0, 0);
        S3kResultsScreenObjectInstance results = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(
                        com.openggf.game.PlayerCharacter.SONIC_AND_TAILS, 0));
        GameServices.level().getObjectManager().addDynamicObject(signpost);
        GameServices.level().getObjectManager().addDynamicObject(results);

        GameServices.level().executeActTransition(cnzAct2TransitionRequest());

        List<ObjectInstance> active = new ArrayList<>(GameServices.level().getObjectManager().getActiveObjects());
        assertTrue(active.contains(signpost),
                "CNZ1BGE_DoTransition reloads the level behind Obj_EndSign; "
                        + "the signpost must not vanish when Obj_LevelResults starts");
        assertTrue(active.contains(results),
                "Obj_LevelResults must survive the CNZ Act 1 reload it requests so the results screen can show");
    }

    @Test
    void productionCnzReloadCarriesTheResultsTitleOwnerUntilTitleCompletion()
            throws Exception {
        CnzResultsLifecycle lifecycle = startCarriedCnzResultsLifecycle();
        HeadlessTestFixture fixture = lifecycle.fixture();
        HardwareTimingService timing = lifecycle.timing();
        S3kResultsScreenObjectInstance carriedResults = lifecycle.results();
        advanceToResultsPublication(fixture, timing);
        assertSame(carriedResults, reacquireResultsOwner());
        assertModuleParents(timing, RESULTS_PARENTS,
                "the child-retirement publication dispatch submits no title parent");

        fixture.stepFrame(false, false, false, false, false);
        assertFalse(GameServices.level().getObjectManager().getActiveObjects()
                .contains(carriedResults),
                "the following rebuilt ObjectManager dispatch retires the results SST");
        assertModuleParents(timing, joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS),
                "the following rebuilt ObjectManager dispatch submits the exact four CNZ2 title parents");
        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();
        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        assertEquals(artProvider.capture().runtimeArtAdmissionLeaseId(),
                title.capture().runtimeArtAdmissionLeaseId(),
                "the carried title binds the exact CNZ reload lease");

        int titleFrames = 0;
        while (!title.isComplete() && titleFrames++ < 2_000) {
            fixture.stepFrame(false, false, false, false, false);
            if (!title.isComplete()) {
                assertModuleParents(timing, joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS),
                        "CNZ enemy parents remain held throughout live title dispatch");
            }
        }
        assertTrue(title.isComplete(), "the live CNZ2 title must reach COMPLETE");
        assertModuleParents(timing,
                joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS, CNZ_ENEMY_PARENTS),
                "the later provider pump after COMPLETE submits the exact four CNZ enemy parents");
        fixture.stepFrame(false, false, false, false, false);
        assertModuleParents(timing,
                joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS, CNZ_ENEMY_PARENTS),
                "later logical frames cannot duplicate either title or enemy parents");
    }

    @Test
    void productionCnzCarriedTitleOwnerRewindsAtEveryOwnershipBoundary()
            throws Exception {
        CnzResultsLifecycle lifecycle = startCarriedCnzResultsLifecycle();
        HeadlessTestFixture fixture = lifecycle.fixture();
        HardwareTimingService timing = lifecycle.timing();
        RewindRegistry rewind = lifecycle.rewind();

        ResultsPublicationCheckpoint beforeTitlePublication =
                advanceToResultsPublication(fixture, timing);
        S3kResultsScreenObjectInstance targetOwnerBeforeRestore = reacquireResultsOwner();
        fixture.stepFrame(false, false, false, false, false);
        assertModuleParents(timing, joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS),
                "the production path reaches the title-owned checkpoint");

        rewind.restore(beforeTitlePublication.snapshot());
        S3kResultsScreenObjectInstance restoredTargetOwner = reacquireResultsOwner();
        assertNotSame(targetOwnerBeforeRestore, restoredTargetOwner,
                "target-root restore recreates the dynamic owner through the replacement ObjectManager adapter");
        assertEquals(beforeTitlePublication.resultsState(), restoredTargetOwner.traceDebugDetails(),
                "target-root restore retains the complete externally observable results routine and timers");
        assertModuleParents(timing, RESULTS_PARENTS,
                "pre-title restore removes work submitted after the checkpoint");
        fixture.stepFrame(false, false, false, false, false);
        assertModuleParents(timing, RESULTS_PARENTS,
                "the replayed child-retirement publication dispatch still submits no title");
        fixture.stepFrame(false, false, false, false, false);
        assertModuleParents(timing, joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS),
                "the replayed following manager dispatch submits the same exact title parents once");

        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();
        int titleFrames = 0;
        while (!title.isComplete() && titleFrames++ < 2_000) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(title.isComplete(), "the replayed CNZ2 title must reach COMPLETE");
        CompositeSnapshot afterCompletion = rewind.capture();
        assertModuleParents(timing,
                joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS, CNZ_ENEMY_PARENTS),
                "the post-completion root owns the exact admitted inventory");
        fixture.stepFrame(false, false, false, false, false);
        rewind.restore(afterCompletion);
        fixture.stepFrame(false, false, false, false, false);
        assertModuleParents(timing,
                joined(RESULTS_PARENTS, CNZ2_TITLE_PARENTS, CNZ_ENEMY_PARENTS),
                "post-completion restore cannot duplicate the admitted enemy batch");
    }

    @Test
    void stalePreReloadTitleOwnerCannotClaimTheNewCnzLease() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());
        HardwareTimingService timing = GameServices.hardwareTiming();
        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        Sonic3kCNZEvents events = initCnzEvents(0);
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_DO_TRANSITION);
        events.setEventsFg5(true);
        fixture.stepFrame(false, false, false, false, false);
        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();
        assertTrue(TestEnvironment.activeGameplayMode().getRewindRegistry()
                .capture().entries().containsKey(Sonic3kTitleCardManager.REWIND_KEY));
        GameServices.level().requestInLevelTitleCard(Sonic3kZoneIds.ZONE_CNZ, 1);
        fixture.stepFrame(false, false, false, false, false);
        int titleFrames = 0;
        while (!title.willSetInLevelEndOfLevelFlagThisUpdate() && titleFrames++ < 2_000) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(title.willSetInLevelEndOfLevelFlagThisUpdate(),
                "the session-registered title must naturally reach its final EXIT dispatch");
        Sonic3kTitleCardManager.Snapshot staleOwner = title.capture();
        long staleLease = staleOwner.runtimeArtAdmissionLeaseId();
        assertTrue(staleLease >= 0,
                "the pre-reload production title owner must carry its exact scalar lease id");

        GameServices.level().executeActTransition(cnzAct2TransitionRequest());
        assertTrue(artProvider.capture().runtimeArtAdmissionLeaseId() != staleLease,
                "the target CNZ batch receives a new exact lease identity");

        title.restore(staleOwner);
        var providerBeforeRejectedAction = artProvider.capture();
        var hardwareBeforeRejectedAction = timing.capture();
        assertThrows(IllegalStateException.class,
                () -> fixture.stepFrame(false, false, false, false, false),
                "the stale pre-reload title scalar cannot bind or consume the new CNZ lease");
        assertEquals(providerBeforeRejectedAction, artProvider.capture(),
                "rejected stale action cannot change provider generation, lease identity, fingerprint, owner, bound/consumed/armed state, descriptors, or handles");
        assertHardwareSnapshotUnchanged(
                hardwareBeforeRejectedAction,
                timing.capture(),
                "rejected stale action cannot change the complete hardware job inventory");
        assertEquals(Sonic3kTitleCardState.EXIT, title.capture().state());
    }

    @Test
    void cnzPostTransitionResultsHandoffRestoresPlayerControlAtResultsPublication() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x02D0, (short) 0x06AC);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x029A, (short) 0x06B0);
        tails.setCpuControlled(true);
        GameServices.sprites().addSprite(player);
        GameServices.sprites().addSprite(tails, "tails");
        GameServices.camera().setFocusedSprite(player);

        ObjectControlState.nativeBit7FullControl().applyTo(player);
        ObjectControlState.nativeBit7FullControl().applyTo(tails);
        player.setControlLocked(true);
        tails.setControlLocked(true);
        player.setAir(true);
        tails.setAir(true);

        manager.requestCnzPostTransitionRelease();
        assertTrue(player.isObjectControlled());
        assertTrue(tails.isObjectControlled());
        assertTrue(player.getAir());
        assertTrue(tails.getAir());

        manager.restorePendingPostResultsPlayerControl();
        assertFalse(player.isObjectControlled(),
                "Obj_EndSignControlAwaitStart calls Restore_PlayerControl after "
                        + "Obj_LevelResults loc_2DD06 clears _unkFAA8 "
                        + "(docs/skdisasm/sonic3k.asm:62708-62720,180407-180412)");
        assertFalse(tails.isObjectControlled(),
                "Restore_PlayerControl2 clears Player_2 object_control in the same handoff "
                        + "(docs/skdisasm/sonic3k.asm:180359-180367)");
        assertFalse(player.isControlLocked());
        assertFalse(tails.isControlLocked());
        assertFalse(player.getAir());
        assertFalse(tails.getAir());
    }

    @Test
    void cnzPostTransitionStartsRomAct2LevelSizeGradualAfterTitleCardHandoff() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);

        GameServices.camera().setMinX((short) 0x01E0);
        GameServices.camera().setMaxX((short) 0x0260);
        GameServices.camera().setMinY((short) 0x0580);
        GameServices.camera().setMaxY((short) 0x1000);
        GameServices.camera().setMaxYTarget((short) 0x1000);

        manager.requestCnzPostTransitionRelease();

        for (int i = 0; i < 20; i++) {
            updateCnzProductionSlots(manager);
        }

        assertEquals(0x0260, GameServices.camera().getMaxX() & 0xFFFF,
                "Change_Act2Sizes must wait on the in-level title-card End_of_level_flag, not elapsed frames");

        GameServices.gameState().setEndOfLevelFlag(true);
        for (int i = 0; i < 4; i++) {
            updateCnzProductionSlots(manager);
        }

        assertEquals(0x0260, GameServices.camera().getMaxX() & 0xFFFF,
                "Obj_EndSignControlDoStart retains two dispatches before allocating "
                        + "Obj_IncLevEndXGradual, which accumulates $4000 and does not move "
                        + "Camera_max_X_pos until its fourth update "
                        + "(docs/skdisasm/sonic3k.asm:180407-180419,178154-178168)");

        updateCnzProductionSlots(manager);

        assertEquals(0x0261, GameServices.camera().getMaxX() & 0xFFFF,
                "Obj_EndSignControlDoStart calls Change_Act2Sizes after the in-level title-card "
                        + "End_of_level_flag, then Obj_IncLevEndXGradual begins expanding Act 2 bounds "
                        + "(docs/skdisasm/sonic3k.asm:180415-180419,180575-180632,178154-178168)");

        for (int i = 0; i < 4; i++) {
            updateCnzProductionSlots(manager);
        }

        assertEquals(0x0266, GameServices.camera().getMaxX() & 0xFFFF,
                "Obj_IncLevEndXGradual keeps its full 16.16 object accumulator in $30(a0) and "
                        + "applies the swapped high word each frame; it is not a delta-only "
                        + "fractional carry (docs/skdisasm/sonic3k.asm:178154-178168)");
    }

    private static void updateCnzProductionSlots(Sonic3kLevelEventManager manager) {
        manager.updateAfterObjectsBeforeCamera();
        manager.update();
    }

    @Test
    void lowerRouteMinibossEntryRemapsPlayersAndCameraIntoBossTunnel() {
        Sonic3kCNZEvents events = initCnzEvents(0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x3000, (short) 0x0650);
        GameServices.sprites().addSprite(player);
        GameServices.camera().setFocusedSprite(player);
        GameServices.camera().setMinY((short) 0);
        GameServices.camera().setX((short) 0x3000);
        GameServices.camera().setY((short) 0x0600);

        events.update(0, 0);

        assertEquals(0x0650 - 0x0700, player.getCentreY(),
                "CNZ1 lower-route miniboss entry should mirror the ROM's -$700 player Y remap");
        assertEquals(0x0600 - 0x0700, GameServices.camera().getY(),
                "CNZ1 lower-route miniboss entry should mirror the ROM's -$700 camera Y remap");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y, GameServices.camera().getMinY() & 0xFFFF,
                "CNZ1BGE_Normal should set the tunnel minimum Y before the $31E0 arena gate");
        assertTrue(events.isWallGrabSuppressed(),
                "CNZ1BGE_Normal should suppress wall-grab interactions inside the boss tunnel");
        assertFalse(events.isBossFlag(),
                "Early tunnel entry must not set Boss_flag before the $31E0 arena gate");
        assertEquals(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH,
                events.getBossBackgroundMode());
    }

    @Test
    void lowerRouteTunnelModeAt3000DoesNotArmRomArenaGateUntil31E0() {
        GameServices.audio().commandTimeline().clear();

        Sonic3kCNZEvents events = initCnzEvents(0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x3000, (short) 0x0650);
        GameServices.sprites().addSprite(player);
        GameServices.camera().setFocusedSprite(player);
        GameServices.camera().setMinX((short) 0x0000);
        GameServices.camera().setMaxX((short) 0x4000);
        GameServices.camera().setMinY((short) 0x0000);
        GameServices.camera().setMaxY((short) 0x1000);
        GameServices.camera().setMaxYTarget((short) 0x1000);
        GameServices.camera().setX((short) 0x3000);
        GameServices.camera().setY((short) 0x0600);

        events.update(0, 0);

        assertEquals(0x0650 - 0x0700, player.getCentreY(),
                "The early lower-route remap must still use ROM centre coordinates");
        assertEquals(0x0600 - 0x0700, GameServices.camera().getY(),
                "The early lower-route remap must move the foreground camera into the tunnel");
        assertEquals(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH,
                events.getBossBackgroundMode(),
                "Camera X $3000 may enter the miniboss tunnel scroll mode");
        assertFalse(events.isBossFlag(), "Camera X $3000 must not set Boss_flag");
        assertTrue(events.isWallGrabSuppressed(), "Camera X $3000 should suppress wall-grab during the boss tunnel setup");
        assertEquals(0x0000, GameServices.camera().getMinX() & 0xFFFF,
                "Camera X $3000 must not clamp Camera_min_X_pos to the ROM arena gate");
        assertEquals(0x4000, GameServices.camera().getMaxX() & 0xFFFF,
                "Camera X $3000 must not clamp Camera_max_X_pos to the ROM arena gate");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y, GameServices.camera().getMinY() & 0xFFFF,
                "Camera X $3000 should apply the ROM tunnel minimum Y");
        assertEquals(0x1000, GameServices.camera().getMaxYTarget() & 0xFFFF,
                "Camera X $3000 must not set the arena target max Y");
        assertEquals(0, commandsOfType(AudioCommand.FadeOutMusic.class).size(),
                "Camera X $3000 must not fade music for the arena gate");
        assertTrue(commandsOfType(AudioCommand.PlayMusic.class).isEmpty(),
                "Camera X $3000 must not start miniboss music");

        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
        events.update(0, 1);
        events.enterMinibossArenaFromObjectSlot();

        assertTrue(events.isBossFlag(), "Camera X $31E0 is the ROM arena gate that sets Boss_flag");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X, GameServices.camera().getMinX() & 0xFFFF);
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X, GameServices.camera().getMaxX() & 0xFFFF);
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y, GameServices.camera().getMinY() & 0xFFFF);
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y, GameServices.camera().getMaxYTarget() & 0xFFFF);
        assertEquals(1, commandsOfType(AudioCommand.FadeOutMusic.class).size(),
                "The real arena gate fades current music before the 120-frame wait");
        assertTrue(commandsOfType(AudioCommand.PlayMusic.class).isEmpty(),
                "Miniboss music must wait for the outer 120-frame release timer");
    }

    @Test
    void arenaGateReleasesMinibossMusicOnceAfter120FrameWait() {
        GameServices.audio().commandTimeline().clear();

        Sonic3kCNZEvents events = initCnzEvents(0);
        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);

        events.enterMinibossArenaFromObjectSlot();
        events.update(0, 0);

        assertEquals(1, commandsOfType(AudioCommand.FadeOutMusic.class).size(),
                "Arena gate should issue the ROM fade-out immediately");
        assertTrue(commandsOfType(AudioCommand.PlayMusic.class).isEmpty(),
                "Miniboss music should not start on the gate frame");

        for (int frame = 1; frame <= 120; frame++) {
            events.update(0, frame);
        }

        assertEquals(List.of(Sonic3kMusic.MINIBOSS.id), commandsOfType(AudioCommand.PlayMusic.class)
                        .stream().map(AudioCommand.PlayMusic::musicId).toList(),
                "The outer gate releases mus_Miniboss after the 120-frame wait");

        for (int frame = 120; frame < 180; frame++) {
            events.update(0, frame);
        }

        assertEquals(List.of(Sonic3kMusic.MINIBOSS.id), commandsOfType(AudioCommand.PlayMusic.class)
                        .stream().map(AudioCommand.PlayMusic::musicId).toList(),
                "The release timer must not replay miniboss music after it fires once");
    }

    @Test
    void bossScrollStartAdvancesWhenTunnelBackgroundReachesRomThreshold() {
        Sonic3kCNZEvents events = initCnzEvents(0);
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_BOSS_START);
        events.setBossScrollState(0x0120, 0);
        GameServices.camera().setY((short) 0x01C0);

        events.update(0, 0);

        assertEquals(Sonic3kCNZEvents.BG_BOSS, events.getBackgroundRoutine());
    }

    @Test
    void act2KnucklesEntryStartsTeleporterRoute_notModeOnly() {
        Sonic3kCNZEvents events = initCnzEvents(1);

        events.beginKnucklesTeleporterRoute();

        assertEquals(Sonic3kCNZEvents.FG_ACT2_KNUCKLES_ROUTE, events.getForegroundRoutine());
        assertTrue(events.isKnucklesTeleporterRouteActive());
        assertEquals(0x4750, events.getCameraMinXClamp());
        assertEquals(0x48E0, events.getCameraMaxXClamp());
    }

    @Test
    void act2KnucklesRouteEndReleasesTeleporterClamp() {
        Sonic3kCNZEvents events = initCnzEvents(1);
        events.beginKnucklesTeleporterRoute();

        events.endKnucklesTeleporterRoute();

        assertFalse(events.isKnucklesTeleporterRouteActive(),
                "CutsceneKnux_CNZ2B clears Ctrl_1_locked and deletes itself after the camera has moved down; "
                        + "the engine-side CNZ route clamp must be released at the same handoff");
        assertEquals(Sonic3kCNZEvents.FG_ACT2_NORMAL, events.getForegroundRoutine());
        assertEquals(0, events.getCameraMinXClamp());
        assertEquals(0, events.getCameraMaxXClamp());
    }

    private Sonic3kCNZEvents initCnzEvents(int act) {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, act);
        return manager.getCnzEvents();
    }

    private SeamlessLevelTransitionRequest cnzAct2TransitionRequest() {
        return SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .showInLevelTitleCard(false)
                .preserveOffsetCameraPosition(true)
                .postTransitionMinX(0x01E0)
                .postTransitionMaxX(0x0260)
                .postTransitionMinY(0x02E0)
                .postTransitionMaxY(0x0500)
                .postTransitionMaxYTarget(0x0500)
                .playerOffset(-0x3000, 0x0200)
                .cameraOffset(-0x3000, 0x0200)
                .build();
    }

    private static S3kResultsScreenObjectInstance reacquireResultsOwner() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(S3kResultsScreenObjectInstance.class::isInstance)
                .map(S3kResultsScreenObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private CnzResultsLifecycle startCarriedCnzResultsLifecycle() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .startPosition((short) 0x32D0, (short) 0x04AC)
                .startPositionIsCentre()
                .build();
        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.gameState().setEndOfLevelActive(true);
        HardwareTimingService timing = GameServices.hardwareTiming();
        RewindRegistry rewind = TestEnvironment.activeGameplayMode().getRewindRegistry();
        S3kResultsScreenObjectInstance results = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(
                        com.openggf.game.PlayerCharacter.SONIC_AND_TAILS, 0));
        S3kResultsScreenObjectInstance initiallyAllocatedResults = results;
        GameServices.level().getObjectManager().addDynamicObject(results);
        int sourceSlot = results.getSlotIndex();
        assertModuleParents(timing, List.of(),
                "allocating the production results owner must not run Obj_LevelResultsInit");
        fixture.stepFrame(false, false, false, false, false);
        assertModuleParents(timing, RESULTS_PARENTS,
                "the first production dispatch queues the exact three ROM results parents");
        drainModuleHardware(timing);
        fixture.stepFrame(false, false, false, false, false);

        CompositeSnapshot beforeReload = rewind.capture();
        String beforeReloadState = results.traceDebugDetails();
        fixture.stepFrame(false, false, false, false, false);
        rewind.restore(beforeReload);
        assertEquals(0, GameServices.level().getCurrentAct());
        results = reacquireResultsOwner();
        assertNotSame(initiallyAllocatedResults, results,
                "ObjectManager dynamic rewind restore recreates the results Java object");
        assertEquals(sourceSlot, results.getSlotIndex(),
                "rewind recreation retains the logical results slot");
        assertEquals(beforeReloadState, results.traceDebugDetails(),
                "the pre-reload checkpoint restores while the source act identity is still live");

        var sourceObjects = GameServices.level().getObjectManager();
        String preTransitionResultsState = results.traceDebugDetails();
        int preTransitionResultsRoutine = results.getState();
        int preTransitionResultsSlot = results.getSlotIndex();
        Sonic3kCNZEvents events = initCnzEvents(0);
        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_DO_TRANSITION);
        events.setEventsFg5(true);
        fixture.stepFrame(false, false, false, false, false);
        var targetObjects = GameServices.level().getObjectManager();
        assertNotSame(sourceObjects, targetObjects);
        assertEquals(1, GameServices.level().getCurrentAct());
        S3kResultsScreenObjectInstance carried = reacquireResultsOwner();
        assertSame(results, carried,
                "the target manager carries the semantic results SST while the CNZ bridge owns only delayed control release");
        assertEquals(preTransitionResultsSlot, carried.getSlotIndex(),
                "the persistent transition handoff retains the results slot");
        assertEquals(preTransitionResultsRoutine, carried.getState(),
                "the persistent transition handoff retains the results routine");
        assertTrue(carried.traceDebugDetails().contains("act=0"),
                "the carried results owner retains its source apparent-act scalar");
        assertTrue(preTransitionResultsState.contains("complete=false")
                        && carried.traceDebugDetails().contains("complete=false"),
                "the transition cannot substitute an already-complete delay bridge");
        assertTrue(carried.isPersistent(),
                "the carried owner remains the production persistent results SST, not a delay bridge substitute");
        var targetAdmission = ((Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider()).capture();
        assertEquals(RuntimeArtAdmissionOwnerKind.TITLE_OWNER,
                targetAdmission.runtimeArtAdmissionOwnerKind(),
                "CNZ reload must issue the target batch to the carried title owner");
        assertFalse(targetAdmission.runtimeArtAdmissionBound(),
                "the target batch remains unbound until the carried results SST publishes the title");
        assertFalse(targetAdmission.runtimeArtAdmissionConsumed(),
                "the target batch remains unconsumed until title completion");
        assertModuleParents(timing, RESULTS_PARENTS,
                "the actual transition and pending-title poll create neither title nor enemy parents");

        CompositeSnapshot afterRecreation = rewind.capture();
        int targetVbla = targetObjects.getVblaCounter();
        String targetResultsState = carried.traceDebugDetails();
        int targetResultsSlot = carried.getSlotIndex();
        S3kResultsScreenObjectInstance targetOwnerBeforeRestore = carried;
        fixture.stepFrame(false, false, false, false, false);
        rewind.restore(afterRecreation);
        assertSame(targetObjects, GameServices.level().getObjectManager());
        assertEquals(targetVbla, targetObjects.getVblaCounter(),
                "the new-root snapshot restores through the replacement object adapter");
        carried = reacquireResultsOwner();
        assertNotSame(targetOwnerBeforeRestore, carried,
                "replacement-manager dynamic rewind restore recreates the target results owner");
        assertEquals(targetResultsSlot, carried.getSlotIndex(),
                "replacement-manager restore retains the target logical results slot");
        assertEquals(targetResultsState, carried.traceDebugDetails(),
                "the new-root snapshot restores the carried target-manager owner");

        return new CnzResultsLifecycle(fixture, timing, rewind, carried);
    }

    private static ResultsPublicationCheckpoint advanceToResultsPublication(
            HeadlessTestFixture fixture, HardwareTimingService timing) {
        int frames = 0;
        while (reacquireResultsOwner().getState() != 4 && frames++ < 2_000) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(4, reacquireResultsOwner().getState(),
                "production results progression must reach its child-retirement exit phase");

        CompositeSnapshot beforePublication = null;
        String beforePublicationResultsState = null;
        while (GameServices.level().getApparentAct() == 0 && frames++ < 2_100) {
            beforePublication = TestEnvironment.activeGameplayMode().getRewindRegistry().capture();
            beforePublicationResultsState = reacquireResultsOwner().traceDebugDetails();
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(1, GameServices.level().getApparentAct(),
                "a rebuilt ObjectManager dispatch must publish the results-owned apparent-act mutation");
        assertTrue(GameServices.level().getObjectManager().getActiveObjects()
                        .contains(reacquireResultsOwner()),
                "results publication retains its SST for the following title initialization dispatch");
        assertNotNull(beforePublication);
        assertNotNull(beforePublicationResultsState);
        assertModuleParents(timing, RESULTS_PARENTS,
                "results publication itself must not submit title or enemy work");
        return new ResultsPublicationCheckpoint(beforePublication, beforePublicationResultsState);
    }

    private static void assertModuleParents(
            HardwareTimingService timing, List<KosParent> expected, String message) {
        var actual = timing.capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
        assertEquals(expected.size(), actual.size(), message);
        for (int i = 0; i < expected.size(); i++) {
            KosParent parent = expected.get(i);
            var job = actual.get(i);
            assertEquals(parent.sourceAddress(), job.romSourceAddress(), message + " source " + i);
            assertEquals(parent.destinationTile() * 32, job.destinationAddress(),
                    message + " destination " + i);
            assertTrue(job.handle().submissionFingerprint().startsWith("sha256:"),
                    message + " stable fingerprint " + i);
        }
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

    @SafeVarargs
    private static List<KosParent> joined(List<KosParent>... groups) {
        List<KosParent> joined = new ArrayList<>();
        for (List<KosParent> group : groups) {
            joined.addAll(group);
        }
        return List.copyOf(joined);
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

    private void advanceCnzPostBossRefresh(Sonic3kCNZEvents events, int firstFrame, int updates) {
        for (int i = 0; i < updates; i++) {
            events.update(0, firstFrame + i);
        }
    }

    private <T extends AudioCommand> List<T> commandsOfType(Class<T> type) {
        return GameServices.audio().commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static final class CapturingCnzEvents extends Sonic3kCNZEvents {
        private ObjectInstance spawned;

        @Override
        protected <T extends ObjectInstance> T spawnObject(Supplier<T> factory) {
            T object = factory.get();
            spawned = object;
            return object;
        }
    }

    private record KosParent(int sourceAddress, int destinationTile) {
    }

    private record CnzResultsLifecycle(
            HeadlessTestFixture fixture,
            HardwareTimingService timing,
            RewindRegistry rewind,
            S3kResultsScreenObjectInstance results) {
    }

    private record ResultsPublicationCheckpoint(
            CompositeSnapshot snapshot,
            String resultsState) {
    }
}
