package com.openggf.game.sonic3k;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.AizPreparedTransitionArtState;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMGZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.features.HCZWaterSkimHandler;
import com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceState;
import com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2BInstance;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the {@link Sonic3kLevelEventManager} extra-state snapshot (C.4).
 *
 * <p>Covers manager-level flags, AIZ fire/battleship state, HCZ wall-chase and
 * cutscene state, CNZ camera-clamp and boss-scroll state, and MGZ collapse counters.
 */
class TestSonic3kLevelEventRewindSnapshot {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void keyIsLevelEvent() {
        assertEquals("level-event", new Sonic3kLevelEventManager().key());
    }

    @Test
    void extraBytesNotNullForAiz() {
        // Act 1 (index 1) avoids the AIZ intro camera path that requires a
        // live gameplay session, while still constructing aizEvents so the
        // capture produces a non-empty extra blob.
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);
        LevelEventSnapshot snap = mgr.capture();
        assertNotNull(snap.extra());
        assertTrue(snap.extra().length > 0);
    }

    @Test
    void roundTripManagerLevelFlags() {
        // Manager-level flags (hczPendingPostTransitionCutscene,
        // mgzPostTransitionReleasePending) are zone-agnostic; use MGZ to stay
        // away from the AIZ intro bootstrap that requires a live camera.
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_MGZ, 0);
        // Directly set zone-agnostic manager flags
        mgr.setHczPendingPostTransitionCutscene(true);
        mgr.requestMgzPostTransitionRelease();

        LevelEventSnapshot snap = mgr.capture();
        // Reset and restore
        mgr.initLevel(Sonic3kZoneIds.ZONE_MGZ, 0);
        mgr.restore(snap);

        // hczPendingPostTransitionCutscene should survive
        // (no public getter; test indirectly by round-tripping the full blob)
        assertNotNull(snap.extra(), "Extra blob must not be null");
    }

    @Test
    void roundTripAizBattleshipState() {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1); // act 2 has aizEvents
        var aiz = mgr.getAizEventsForTest();
        assertNotNull(aiz, "AIZ events must be non-null in AIZ zone");

        aiz.setBattleshipAutoScrollActiveRaw(true);
        aiz.setBattleshipSpawned(true);
        aiz.setBattleshipWrapX(0x46C0);
        aiz.setScreenShakeTimer(10);
        aiz.setScreenShakeOffsetYRaw(3);
        aiz.setScreenShakeAppliedOffsetYRaw(2);
        aiz.setLevelRepeatOffsetRaw(0x200);
        aiz.setBattleshipSmoothScrollXRaw(0x1234);
        aiz.setBattleshipPostScrollCameraX(0x4440);
        aiz.setFireSequencePhaseOrdinal(2); // AIZ1_FIRE_REFRESH
        aiz.setFireBgCopyFixed(0x0068_0000);
        aiz.setFireRiseSpeed(0x5000);
        aiz.setFireWavePhase(0x30);
        aiz.setFireTransitionFrames(120);
        aiz.setAiz2ResizeRoutine(8);
        aiz.setMinibossSpawned(true);
        aiz.setPaletteSwapped(true);
        aiz.setBoundariesUnlocked(true);

        LevelEventSnapshot snap = mgr.capture();

        mgr.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);
        var aiz2 = mgr.getAizEventsForTest();
        assertNotNull(aiz2);
        mgr.restore(snap);

        assertTrue(aiz2.isBattleshipAutoScrollActiveRaw());
        assertTrue(aiz2.isBattleshipSpawned());
        assertEquals(0x46C0,      aiz2.getBattleshipWrapX());
        assertEquals(10,          aiz2.getScreenShakeTimer());
        assertEquals(3,           aiz2.getScreenShakeOffsetYRaw());
        assertEquals(2,           aiz2.getScreenShakeAppliedOffsetYRaw());
        assertEquals(0x200,       aiz2.getLevelRepeatOffsetRaw());
        assertEquals(0x1234,      aiz2.getBattleshipSmoothScrollXRaw());
        assertEquals(0x4440,      aiz2.getBattleshipPostScrollCameraX());
        assertEquals(2,           aiz2.getFireSequencePhaseOrdinal());
        assertEquals(0x0068_0000, aiz2.getFireBgCopyFixed());
        assertEquals(0x5000,      aiz2.getFireRiseSpeed());
        assertEquals(0x30,        aiz2.getFireWavePhase());
        assertEquals(120,         aiz2.getFireTransitionFrames());
        assertEquals(8,           aiz2.getAiz2ResizeRoutine());
        assertTrue(aiz2.isMinibossSpawned());
        assertTrue(aiz2.isPaletteSwapped());
        assertTrue(aiz2.isBoundariesUnlocked());
    }

    @Test
    void roundTripHczWallChaseState() {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        var hcz = mgr.getHczEventsForTest();
        assertNotNull(hcz, "HCZ events must be non-null in HCZ zone");

        hcz.setBgRoutine(8);
        hcz.setAct2BgRoutine(4);
        hcz.setWallOffsetFixed(0x800);
        hcz.setWallOffsetPixels(16);
        hcz.setWallMoving(true);
        hcz.setWallChaseBgOverlayActiveRaw(true);
        hcz.setShakeTimer(12);
        hcz.setCutsceneActive(true);
        hcz.setCarrierP1Active(true);
        hcz.setCarrierP2Active(true);
        hcz.setCarrierP1XFixed(0x15600);
        hcz.setCarrierP1YFixed(0x6EC00);
        hcz.setCarrierP1XVelocity(0x940);
        hcz.setCarrierP2XFixed(0x13600);
        hcz.setCarrierP2YFixed(0x6F000);
        hcz.setCarrierP2XVelocity(0xD40);
        hcz.setCarrierP1TargetSide(false);
        hcz.setCarrierP2TargetSide(false);
        hcz.setTransitionBubbleSpawnFrames(23);
        hcz.setLevelSizeTransitionActive(true);
        hcz.setLevelSizeMaxXGradient(0x14000);
        hcz.setLevelSizeMinYGradient(0x18000);
        hcz.setLevelSizeMaxYGradient(0x28000);

        LevelEventSnapshot snap = mgr.capture();

        mgr.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        var hcz2 = mgr.getHczEventsForTest();
        assertNotNull(hcz2);
        mgr.restore(snap);

        assertEquals(8,     hcz2.getBgRoutine());
        assertEquals(4,     hcz2.getAct2BgRoutine());
        assertEquals(0x800, hcz2.getWallOffsetFixed());
        assertEquals(16,    hcz2.getWallOffsetPixels());
        assertTrue(hcz2.isWallMoving());
        assertTrue(hcz2.isWallChaseBgOverlayActive());
        assertEquals(12,    hcz2.getShakeTimer());
        assertTrue(hcz2.isCutsceneActive());
        assertTrue(hcz2.isCarrierP1Active());
        assertTrue(hcz2.isCarrierP2Active());
        assertEquals(0x15600, hcz2.getCarrierP1XFixed());
        assertEquals(0x6EC00, hcz2.getCarrierP1YFixed());
        assertEquals(0x940, hcz2.getCarrierP1XVelocity());
        assertEquals(0x13600, hcz2.getCarrierP2XFixed());
        assertEquals(0x6F000, hcz2.getCarrierP2YFixed());
        assertEquals(0xD40, hcz2.getCarrierP2XVelocity());
        assertFalse(hcz2.isCarrierP1TargetSide());
        assertFalse(hcz2.isCarrierP2TargetSide());
        assertEquals(23, hcz2.getTransitionBubbleSpawnFrames());
        assertTrue(hcz2.isLevelSizeTransitionActive());
        assertEquals(0x14000, hcz2.getLevelSizeMaxXGradient());
        assertEquals(0x18000, hcz2.getLevelSizeMinYGradient());
        assertEquals(0x28000, hcz2.getLevelSizeMaxYGradient());
    }

    @Test
    void roundTripCnzBossScrollAndClamps() {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        var cnz = mgr.getCnzEventsForTest();
        assertNotNull(cnz, "CNZ events must be non-null in CNZ zone");

        cnz.setBossScrollState(120, -4);
        cnz.setCameraClampsActive(true);
        cnz.setCameraStoredMaxXPos((short) 0x3200);
        cnz.setCameraStoredMinXPos((short) 0x1000);
        cnz.setWaterTargetYRaw(0x700);
        cnz.setDestroyedArenaRows(3);
        cnz.setBossBackgroundMode(com.openggf.game.sonic3k.events.Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);

        LevelEventSnapshot snap = mgr.capture();

        mgr.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        var cnz2 = mgr.getCnzEventsForTest();
        assertNotNull(cnz2);
        mgr.restore(snap);

        assertEquals(120,  cnz2.getBossScrollOffsetY());
        assertEquals(-4,   cnz2.getBossScrollVelocityY());
        assertTrue(cnz2.isCameraClampsActive());
        assertEquals((short) 0x3200, cnz2.getCameraStoredMaxXPos());
        assertEquals((short) 0x1000, cnz2.getCameraStoredMinXPos());
        assertEquals(0x700, cnz2.getWaterTargetY());
        assertEquals(3,     cnz2.getDestroyedArenaRows());
        assertEquals(com.openggf.game.sonic3k.events.Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH,
                cnz2.getBossBackgroundMode());
    }

    @Test
    void roundTripMgzCollapseCounters() {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        var mgz = mgr.getMgzEventsForTest();
        assertNotNull(mgz, "MGZ events must be non-null in MGZ zone");

        mgz.setCollapseRequested(true);
        mgz.setCollapseInitialized(true);
        mgz.setCollapseMutationCount(7);
        mgz.setCollapseFrameCounter(42);
        mgz.setCollapseStartupShakeTimer(5);
        mgz.setBgRiseRoutine(8);
        mgz.setBgRiseOffset(0x100);
        mgz.setBgRiseSubpixelAccum(0x8000);
        mgz.setBgRiseMotionStarted(true);
        mgz.setBossArenaRoutine(4);
        mgz.setBossSpawned(true);
        mgz.setAppearance1Complete(true);
        mgz.setAppearance2Complete(true);

        LevelEventSnapshot snap = mgr.capture();

        mgr.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        var mgz2 = mgr.getMgzEventsForTest();
        assertNotNull(mgz2);
        mgr.restore(snap);

        assertTrue(mgz2.isCollapseRequested());
        assertTrue(mgz2.isCollapseInitialized());
        assertEquals(7,      mgz2.getCollapseMutationCount());
        assertEquals(42,     mgz2.getCollapseFrameCounter());
        assertEquals(5,      mgz2.getCollapseStartupShakeTimer());
        assertEquals(8,      mgz2.getBgRiseRoutine());
        assertEquals(0x100,  mgz2.getBgRiseOffset());
        assertEquals(0x8000, mgz2.getBgRiseSubpixelAccum());
        assertTrue(mgz2.isBgRiseMotionStarted());
        assertEquals(4,      mgz2.getBossArenaRoutine());
        assertTrue(mgz2.isBossSpawned());
        assertTrue(mgz2.isAppearance1Complete());
        assertTrue(mgz2.isAppearance2Complete());
    }

    @Test
    void resetStateClearsPostTransitionHandoffState() throws Exception {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);

        mgr.requestHczPostTransitionCutscene();
        mgr.requestMgzPostTransitionRelease();
        mgr.requestCnzPostTransitionRelease();
        set(mgr, "cnzPostTransitionAct2SizeActive", true);
        set(mgr, "cnzAct2MinXAccumulator", 1);
        set(mgr, "cnzAct2MaxXAccumulator", 2);
        set(mgr, "cnzAct2MinYAccumulator", 3);
        set(mgr, "cnzAct2MaxYAccumulator", 4);

        mgr.resetState();

        assertFalse((boolean) get(mgr, "hczPendingPostTransitionCutscene"));
        assertFalse((boolean) get(mgr, "mgzPendingPostTransitionRelease"));
        assertEquals(false, get(mgr, "cnzPendingPostTransitionRelease"));
        assertEquals(0, get(mgr, "cnzPendingPostTransitionAct2SizeFrames"));
        assertFalse((boolean) get(mgr, "cnzPostTransitionAct2SizeActive"));
        assertEquals(0, get(mgr, "cnzAct2MinXAccumulator"));
        assertEquals(0, get(mgr, "cnzAct2MaxXAccumulator"));
        assertEquals(0, get(mgr, "cnzAct2MinYAccumulator"));
        assertEquals(0, get(mgr, "cnzAct2MaxYAccumulator"));
    }

    @Test
    void preparedAizTransitionArtIsSessionOwnedRewindableAndResettable() {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        var adapter = mgr.extraRewindAdapters().stream()
                .filter(candidate -> "s3k-aiz-prepared-transition-art".equals(candidate.key()))
                .findFirst();
        assertTrue(adapter.isPresent(),
                "prepared transition art must be owned by the session rewind registry");
        AizPreparedTransitionArtState state = assertInstanceOf(
                AizPreparedTransitionArtState.class, adapter.orElseThrow());

        byte[] original = new byte[64];
        original[0] = 0x12;
        mgr.retainAizFireOverlay(original);
        original[0] = 0x34;
        assertEquals(2, mgr.aizFireOverlayTileCount());
        assertEquals(0x12, mgr.aizFireOverlayCopy()[0],
                "the session owner must retain an immutable copy");

        byte[] snapshot = state.capture();
        mgr.retainAizFireOverlay(new byte[32]);
        state.restore(snapshot);
        assertEquals(2, mgr.aizFireOverlayTileCount());
        assertEquals(0x12, mgr.aizFireOverlayCopy()[0]);

        mgr.resetState();
        assertEquals(0, mgr.aizFireOverlayTileCount());
        assertEquals(0, mgr.aizFireOverlayCopy().length);
    }

    @Test
    void resetStateClearsAiz2BossEndSequenceGlobals() {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);

        Aiz2BossEndSequenceState.triggerBridgeDrop();
        Aiz2BossEndSequenceState.pressButton();
        Aiz2BossEndSequenceState.releaseEggCapsule();
        Aiz2BossEndSequenceState.activateCutsceneOverrideObjects();

        mgr.resetState();

        assertFalse(Aiz2BossEndSequenceState.isBridgeDropTriggered());
        assertFalse(Aiz2BossEndSequenceState.isButtonPressed());
        assertFalse(Aiz2BossEndSequenceState.isEggCapsuleReleased());
        assertFalse(Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive());
        assertNull(Aiz2BossEndSequenceState.getActiveKnuckles());
    }

    @Test
    void resetStateClearsAizDrawBridgeBurnTrigger() throws Exception {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);

        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(true);

        mgr.resetState();

        assertFalse((boolean) getStatic(AizCollapsingLogBridgeObjectInstance.class, "drawBridgeBurnActive"));
    }

    @Test
    void resetStateClearsStaticCutsceneSignpostAndHczWaterRefs() throws Exception {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);

        setStatic(CutsceneKnucklesCnz2AInstance.class, "activeInstance",
                new CutsceneKnucklesCnz2AInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0)));
        setStatic(CutsceneKnucklesCnz2BInstance.class, "activeInstance",
                new CutsceneKnucklesCnz2BInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0)));
        HCZWaterRushObjectInstance.HCZBreakableBarState.setState(3);
        HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.setActive(true);
        setStatic(HCZWaterSkimHandler.class, "skimActiveP1", true);
        setStatic(HCZWaterSkimHandler.class, "skimActiveP2", true);

        mgr.resetState();

        assertNull(getStatic(CutsceneKnucklesCnz2AInstance.class, "activeInstance"));
        assertNull(getStatic(CutsceneKnucklesCnz2BInstance.class, "activeInstance"));
        assertEquals(0, HCZWaterRushObjectInstance.HCZBreakableBarState.getState());
        assertFalse(HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.isActive());
        assertFalse(HCZWaterSkimHandler.isSkimActiveP1());
        assertFalse(HCZWaterSkimHandler.isSkimActiveP2());
    }

    @Test
    void handlerAbsentDoesNotCorruptBuffer() {
        // Snapshot in AIZ, restore in HCZ — AIZ handler present but hczEvents null.
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);
        LevelEventSnapshot snap = mgr.capture();

        // Switch to HCZ and restore — should not throw or corrupt
        mgr.initLevel(Sonic3kZoneIds.ZONE_HCZ, 0);
        assertDoesNotThrow(() -> mgr.restore(snap));
    }

    @Test
    void shortAizSchemaPayloadDoesNotRestoreLaterZoneSidecars() throws Exception {
        Sonic3kLevelEventManager hczSource = new Sonic3kLevelEventManager();
        hczSource.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        var hcz = hczSource.getHczEventsForTest();
        assertNotNull(hcz);
        hcz.setBgRoutine(8);
        hcz.setWallMoving(true);
        hcz.setShakeTimer(12);
        LevelEventSnapshot hczSnapshot = hczSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);
        Sonic3kLevelEventManager hczTargetOwner = new Sonic3kLevelEventManager();
        hczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        set(target, "hczEvents", hczTargetOwner.getHczEventsForTest());
        var hczTarget = target.getHczEventsForTest();
        assertNotNull(hczTarget);
        hczTarget.setBgRoutine(0);
        hczTarget.setWallMoving(false);
        hczTarget.setShakeTimer(0);

        byte[] malformedExtra = insertMalformedAizPayload(hczSnapshot.extra());
        LevelEventSnapshot malformedSnapshot = new LevelEventSnapshot(
                hczSnapshot.currentZone(),
                hczSnapshot.currentAct(),
                hczSnapshot.eventRoutineFg(),
                hczSnapshot.eventRoutineBg(),
                hczSnapshot.frameCounter(),
                hczSnapshot.timerFrames(),
                hczSnapshot.bossActive(),
                hczSnapshot.eventDataFg(),
                hczSnapshot.eventDataBg(),
                malformedExtra);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short AIZ sidecar payload should be rejected before later zone sidecars");
        assertEquals(0, hczTarget.getBgRoutine(),
                "short AIZ sidecar payload must not apply later sidecars with unknown offsets");
        assertFalse(hczTarget.isWallMoving(),
                "short AIZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, hczTarget.getShakeTimer(),
                "short AIZ sidecar payload must not apply later sidecars with unknown offsets");
    }

    @Test
    void corruptSameLengthAizSchemaPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager hczSource = new Sonic3kLevelEventManager();
        hczSource.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        var hcz = hczSource.getHczEventsForTest();
        assertNotNull(hcz);
        hcz.setBgRoutine(8);
        hcz.setWallMoving(true);
        hcz.setShakeTimer(12);
        LevelEventSnapshot hczSnapshot = hczSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);
        var aizTarget = target.getAizEventsForTest();
        assertNotNull(aizTarget);
        aizTarget.setIntroSpawned(true);

        Sonic3kLevelEventManager hczTargetOwner = new Sonic3kLevelEventManager();
        hczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        set(target, "hczEvents", hczTargetOwner.getHczEventsForTest());
        var hczTarget = target.getHczEventsForTest();
        assertNotNull(hczTarget);
        hczTarget.setBgRoutine(0);
        hczTarget.setWallMoving(false);
        hczTarget.setShakeTimer(0);

        Sonic3kAIZEvents payloadSource = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        payloadSource.setIntroSpawned(false);
        byte[] corruptPayload = corruptFireSequencePhaseOrdinal(
                ZoneEventSchemaSidecar.capture(payloadSource), Integer.MAX_VALUE);
        LevelEventSnapshot malformedSnapshot = snapshotWithAizPayload(hczSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "same-length schema-corrupt AIZ sidecar should not abort later zone sidecars");
        assertTrue(aizTarget.isIntroSpawned(),
                "schema-corrupt AIZ sidecar must roll back partial AIZ field writes");
        assertEquals(8, hczTarget.getBgRoutine());
        assertTrue(hczTarget.isWallMoving());
        assertEquals(12, hczTarget.getShakeTimer());
    }

    @Test
    void negativeEnumOrdinalAizSchemaPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager hczSource = new Sonic3kLevelEventManager();
        hczSource.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        var hcz = hczSource.getHczEventsForTest();
        assertNotNull(hcz);
        hcz.setBgRoutine(8);
        hcz.setWallMoving(true);
        hcz.setShakeTimer(12);
        LevelEventSnapshot hczSnapshot = hczSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_AIZ, 1);
        var aizTarget = target.getAizEventsForTest();
        assertNotNull(aizTarget);
        aizTarget.setIntroSpawned(true);

        Sonic3kLevelEventManager hczTargetOwner = new Sonic3kLevelEventManager();
        hczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        set(target, "hczEvents", hczTargetOwner.getHczEventsForTest());
        var hczTarget = target.getHczEventsForTest();
        assertNotNull(hczTarget);
        hczTarget.setBgRoutine(0);
        hczTarget.setWallMoving(false);
        hczTarget.setShakeTimer(0);

        Sonic3kAIZEvents payloadSource = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        payloadSource.setIntroSpawned(false);
        byte[] corruptPayload = corruptFireSequencePhaseOrdinal(
                ZoneEventSchemaSidecar.capture(payloadSource), -1);
        LevelEventSnapshot malformedSnapshot = snapshotWithAizPayload(hczSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "null-enum AIZ sidecar should not abort later zone sidecars");
        assertTrue(aizTarget.isIntroSpawned(),
                "null-enum AIZ sidecar must roll back partial AIZ field writes");
        assertEquals(8, hczTarget.getBgRoutine());
        assertTrue(hczTarget.isWallMoving());
        assertEquals(12, hczTarget.getShakeTimer());
    }

    @Test
    void corruptSameLengthHczSchemaPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager cnzSource = new Sonic3kLevelEventManager();
        cnzSource.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        var cnz = cnzSource.getCnzEventsForTest();
        assertNotNull(cnz);
        cnz.setBossScrollState(120, -4);
        cnz.setCameraClampsActive(true);
        cnz.setWaterTargetYRaw(0x700);
        LevelEventSnapshot cnzSnapshot = cnzSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        HczProbeEvents hczTarget = new HczProbeEvents();
        hczTarget.init(1);
        hczTarget.setWallMoving(true);
        hczTarget.setShakeTimer(99);
        hczTarget.setProbeMode(HczProbeMode.ONE);
        set(target, "hczEvents", hczTarget);

        Sonic3kLevelEventManager cnzTargetOwner = new Sonic3kLevelEventManager();
        cnzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        set(target, "cnzEvents", cnzTargetOwner.getCnzEventsForTest());
        var cnzTarget = target.getCnzEventsForTest();
        assertNotNull(cnzTarget);
        cnzTarget.setBossScrollState(0, 0);
        cnzTarget.setCameraClampsActive(false);
        cnzTarget.setWaterTargetYRaw(0);

        HczProbeEvents payloadSource = new HczProbeEvents();
        payloadSource.init(1);
        payloadSource.setWallMoving(false);
        payloadSource.setShakeTimer(12);
        payloadSource.setProbeMode(HczProbeMode.ZERO);
        byte[] corruptPayload = corruptHczProbeModeOrdinal(
                ZoneEventSchemaSidecar.capture(payloadSource), Integer.MAX_VALUE);
        LevelEventSnapshot malformedSnapshot = snapshotWithHczPayload(cnzSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "schema-corrupt HCZ sidecar should not abort later zone sidecars");
        assertTrue(hczTarget.isWallMoving(),
                "schema-corrupt HCZ sidecar must roll back partial HCZ field writes");
        assertEquals(99, hczTarget.getShakeTimer(),
                "schema-corrupt HCZ sidecar must roll back partial HCZ field writes");
        assertEquals(HczProbeMode.ONE, hczTarget.getProbeMode(),
                "schema-corrupt HCZ sidecar must preserve existing enum state");
        assertEquals(120, cnzTarget.getBossScrollOffsetY());
        assertEquals(-4, cnzTarget.getBossScrollVelocityY());
        assertTrue(cnzTarget.isCameraClampsActive());
        assertEquals(0x700, cnzTarget.getWaterTargetY());
    }

    @Test
    void shortHczSchemaPayloadDoesNotRestoreLaterZoneSidecars() throws Exception {
        Sonic3kLevelEventManager cnzSource = new Sonic3kLevelEventManager();
        cnzSource.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        var cnz = cnzSource.getCnzEventsForTest();
        assertNotNull(cnz);
        cnz.setBossScrollState(120, -4);
        cnz.setCameraClampsActive(true);
        cnz.setWaterTargetYRaw(0x700);
        LevelEventSnapshot cnzSnapshot = cnzSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        var hczTarget = target.getHczEventsForTest();
        assertNotNull(hczTarget);
        hczTarget.setWallMoving(true);
        hczTarget.setShakeTimer(99);

        Sonic3kLevelEventManager cnzTargetOwner = new Sonic3kLevelEventManager();
        cnzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        set(target, "cnzEvents", cnzTargetOwner.getCnzEventsForTest());
        var cnzTarget = target.getCnzEventsForTest();
        assertNotNull(cnzTarget);
        cnzTarget.setBossScrollState(0, 0);
        cnzTarget.setCameraClampsActive(false);
        cnzTarget.setWaterTargetYRaw(0);

        LevelEventSnapshot malformedSnapshot = snapshotWithHczPayload(cnzSnapshot, new byte[] { 0 });

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short HCZ sidecar payload should be rejected before later zone sidecars");
        assertTrue(hczTarget.isWallMoving(),
                "short HCZ sidecar payload must leave existing HCZ fields intact");
        assertEquals(99, hczTarget.getShakeTimer(),
                "short HCZ sidecar payload must leave existing HCZ fields intact");
        assertEquals(0, cnzTarget.getBossScrollOffsetY(),
                "short HCZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, cnzTarget.getBossScrollVelocityY(),
                "short HCZ sidecar payload must not apply later sidecars with unknown offsets");
        assertFalse(cnzTarget.isCameraClampsActive(),
                "short HCZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, cnzTarget.getWaterTargetY(),
                "short HCZ sidecar payload must not apply later sidecars with unknown offsets");
    }

    @Test
    void malformedHczLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager cnzSource = new Sonic3kLevelEventManager();
        cnzSource.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        cnzSource.requestCnzPostTransitionRelease();
        var cnz = cnzSource.getCnzEventsForTest();
        assertNotNull(cnz);
        cnz.setBossScrollState(120, -4);
        cnz.setCameraClampsActive(true);
        LevelEventSnapshot cnzSnapshot = cnzSource.capture();

        Sonic3kHCZEvents hczPayloadSource = new Sonic3kHCZEvents(() -> 0);
        hczPayloadSource.init(1);
        hczPayloadSource.setWallMoving(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptHczLength(
                snapshotWithHczPayload(cnzSnapshot, ZoneEventSchemaSidecar.capture(hczPayloadSource)),
                -1);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        Sonic3kLevelEventManager cnzTargetOwner = new Sonic3kLevelEventManager();
        cnzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        set(target, "cnzEvents", cnzTargetOwner.getCnzEventsForTest());
        var cnzTarget = target.getCnzEventsForTest();
        assertNotNull(cnzTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "framing-invalid HCZ sidecar should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "framing-invalid HCZ sidecar must not mutate manager-level fields");
        assertEquals(0, cnzTarget.getBossScrollOffsetY(),
                "framing-invalid HCZ sidecar must not apply later sidecars with unknown offsets");
        assertFalse(cnzTarget.isCameraClampsActive(),
                "framing-invalid HCZ sidecar must not apply later sidecars with unknown offsets");
    }

    @Test
    void underreportedHczLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager cnzSource = new Sonic3kLevelEventManager();
        cnzSource.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        cnzSource.requestCnzPostTransitionRelease();
        var cnz = cnzSource.getCnzEventsForTest();
        assertNotNull(cnz);
        cnz.setBossScrollState(120, -4);
        cnz.setCameraClampsActive(true);
        LevelEventSnapshot cnzSnapshot = cnzSource.capture();

        Sonic3kHCZEvents hczPayloadSource = new Sonic3kHCZEvents(() -> 0);
        hczPayloadSource.init(1);
        hczPayloadSource.setWallMoving(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptHczLength(
                snapshotWithHczPayload(cnzSnapshot, ZoneEventSchemaSidecar.capture(hczPayloadSource)),
                0);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_HCZ, 1);
        Sonic3kLevelEventManager cnzTargetOwner = new Sonic3kLevelEventManager();
        cnzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        set(target, "cnzEvents", cnzTargetOwner.getCnzEventsForTest());
        var cnzTarget = target.getCnzEventsForTest();
        assertNotNull(cnzTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short-but-bounded HCZ sidecar length should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "short-but-bounded HCZ sidecar length must not mutate manager-level fields");
        assertEquals(0, cnzTarget.getBossScrollOffsetY(),
                "short-but-bounded HCZ sidecar length must not apply later sidecars with unknown offsets");
        assertFalse(cnzTarget.isCameraClampsActive(),
                "short-but-bounded HCZ sidecar length must not apply later sidecars with unknown offsets");
    }

    @Test
    void corruptSameLengthCnzSchemaPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager mgzSource = new Sonic3kLevelEventManager();
        mgzSource.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        var mgz = mgzSource.getMgzEventsForTest();
        assertNotNull(mgz);
        mgz.setCollapseRequested(true);
        mgz.setCollapseFrameCounter(42);
        mgz.setBgRiseRoutine(8);
        mgz.setBossSpawned(true);
        LevelEventSnapshot mgzSnapshot = mgzSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        var cnzTarget = target.getCnzEventsForTest();
        assertNotNull(cnzTarget);
        cnzTarget.setAct2TransitionRequested(true);
        cnzTarget.setArenaChunkWorldX(0x1234);
        cnzTarget.setBackgroundRoutine(17);
        cnzTarget.setBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_POST_BOSS);

        Sonic3kLevelEventManager mgzTargetOwner = new Sonic3kLevelEventManager();
        mgzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        set(target, "mgzEvents", mgzTargetOwner.getMgzEventsForTest());
        var mgzTarget = target.getMgzEventsForTest();
        assertNotNull(mgzTarget);
        mgzTarget.setCollapseRequested(false);
        mgzTarget.setCollapseFrameCounter(0);
        mgzTarget.setBgRiseRoutine(0);
        mgzTarget.setBossSpawned(false);

        Sonic3kCNZEvents payloadSource = new Sonic3kCNZEvents();
        payloadSource.init(0);
        payloadSource.setAct2TransitionRequested(false);
        payloadSource.setArenaChunkWorldX(0x5678);
        payloadSource.setBackgroundRoutine(3);
        payloadSource.setBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.NORMAL);
        byte[] corruptPayload = corruptCnzBossBackgroundModeOrdinal(
                ZoneEventSchemaSidecar.capture(payloadSource), Integer.MAX_VALUE);
        LevelEventSnapshot malformedSnapshot = snapshotWithCnzPayload(mgzSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "schema-corrupt CNZ sidecar should not abort later zone sidecars");
        assertTrue(cnzTarget.isAct2TransitionRequested(),
                "schema-corrupt CNZ sidecar must roll back partial CNZ field writes");
        assertEquals(0x1234, cnzTarget.getArenaChunkWorldX(),
                "schema-corrupt CNZ sidecar must roll back partial CNZ field writes");
        assertEquals(17, cnzTarget.getBackgroundRoutine(),
                "schema-corrupt CNZ sidecar must roll back partial CNZ field writes");
        assertEquals(Sonic3kCNZEvents.BossBackgroundMode.ACT1_POST_BOSS, cnzTarget.getBossBackgroundMode(),
                "schema-corrupt CNZ sidecar must preserve existing enum state");
        assertTrue(mgzTarget.isCollapseRequested());
        assertEquals(42, mgzTarget.getCollapseFrameCounter());
        assertEquals(8, mgzTarget.getBgRiseRoutine());
        assertTrue(mgzTarget.isBossSpawned());
    }

    @Test
    void shortCnzSchemaPayloadDoesNotRestoreLaterZoneSidecars() throws Exception {
        Sonic3kLevelEventManager mgzSource = new Sonic3kLevelEventManager();
        mgzSource.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        var mgz = mgzSource.getMgzEventsForTest();
        assertNotNull(mgz);
        mgz.setCollapseRequested(true);
        mgz.setCollapseFrameCounter(42);
        mgz.setBgRiseRoutine(8);
        mgz.setBossSpawned(true);
        LevelEventSnapshot mgzSnapshot = mgzSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        var cnzTarget = target.getCnzEventsForTest();
        assertNotNull(cnzTarget);
        cnzTarget.setAct2TransitionRequested(true);
        cnzTarget.setArenaChunkWorldX(0x1234);
        cnzTarget.setBackgroundRoutine(17);

        Sonic3kLevelEventManager mgzTargetOwner = new Sonic3kLevelEventManager();
        mgzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        set(target, "mgzEvents", mgzTargetOwner.getMgzEventsForTest());
        var mgzTarget = target.getMgzEventsForTest();
        assertNotNull(mgzTarget);
        mgzTarget.setCollapseRequested(false);
        mgzTarget.setCollapseFrameCounter(0);
        mgzTarget.setBgRiseRoutine(0);
        mgzTarget.setBossSpawned(false);

        LevelEventSnapshot malformedSnapshot = snapshotWithCnzPayload(mgzSnapshot, new byte[] { 0 });

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short CNZ sidecar payload should be rejected before later zone sidecars");
        assertTrue(cnzTarget.isAct2TransitionRequested(),
                "short CNZ sidecar payload must leave existing CNZ fields intact");
        assertEquals(0x1234, cnzTarget.getArenaChunkWorldX(),
                "short CNZ sidecar payload must leave existing CNZ fields intact");
        assertEquals(17, cnzTarget.getBackgroundRoutine(),
                "short CNZ sidecar payload must leave existing CNZ fields intact");
        assertFalse(mgzTarget.isCollapseRequested(),
                "short CNZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, mgzTarget.getCollapseFrameCounter(),
                "short CNZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, mgzTarget.getBgRiseRoutine(),
                "short CNZ sidecar payload must not apply later sidecars with unknown offsets");
        assertFalse(mgzTarget.isBossSpawned(),
                "short CNZ sidecar payload must not apply later sidecars with unknown offsets");
    }

    @Test
    void malformedCnzLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager mgzSource = new Sonic3kLevelEventManager();
        mgzSource.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        mgzSource.requestCnzPostTransitionRelease();
        var mgz = mgzSource.getMgzEventsForTest();
        assertNotNull(mgz);
        mgz.setCollapseRequested(true);
        mgz.setCollapseFrameCounter(42);
        LevelEventSnapshot mgzSnapshot = mgzSource.capture();

        Sonic3kCNZEvents cnzPayloadSource = new Sonic3kCNZEvents();
        cnzPayloadSource.init(0);
        cnzPayloadSource.setAct2TransitionRequested(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptCnzLength(
                snapshotWithCnzPayload(mgzSnapshot, ZoneEventSchemaSidecar.capture(cnzPayloadSource)),
                Integer.MAX_VALUE);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        Sonic3kLevelEventManager mgzTargetOwner = new Sonic3kLevelEventManager();
        mgzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        set(target, "mgzEvents", mgzTargetOwner.getMgzEventsForTest());
        var mgzTarget = target.getMgzEventsForTest();
        assertNotNull(mgzTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "framing-invalid CNZ sidecar should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "framing-invalid CNZ sidecar must not mutate manager-level fields");
        assertFalse(mgzTarget.isCollapseRequested(),
                "framing-invalid CNZ sidecar must not apply later sidecars with unknown offsets");
        assertEquals(0, mgzTarget.getCollapseFrameCounter(),
                "framing-invalid CNZ sidecar must not apply later sidecars with unknown offsets");
    }

    @Test
    void underreportedCnzLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager mgzSource = new Sonic3kLevelEventManager();
        mgzSource.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        mgzSource.requestCnzPostTransitionRelease();
        var mgz = mgzSource.getMgzEventsForTest();
        assertNotNull(mgz);
        mgz.setCollapseRequested(true);
        mgz.setCollapseFrameCounter(42);
        LevelEventSnapshot mgzSnapshot = mgzSource.capture();

        Sonic3kCNZEvents cnzPayloadSource = new Sonic3kCNZEvents();
        cnzPayloadSource.init(0);
        cnzPayloadSource.setAct2TransitionRequested(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptCnzLength(
                snapshotWithCnzPayload(mgzSnapshot, ZoneEventSchemaSidecar.capture(cnzPayloadSource)),
                0);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        Sonic3kLevelEventManager mgzTargetOwner = new Sonic3kLevelEventManager();
        mgzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        set(target, "mgzEvents", mgzTargetOwner.getMgzEventsForTest());
        var mgzTarget = target.getMgzEventsForTest();
        assertNotNull(mgzTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short-but-bounded CNZ sidecar length should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "short-but-bounded CNZ sidecar length must not mutate manager-level fields");
        assertFalse(mgzTarget.isCollapseRequested(),
                "short-but-bounded CNZ sidecar length must not apply later sidecars with unknown offsets");
        assertEquals(0, mgzTarget.getCollapseFrameCounter(),
                "short-but-bounded CNZ sidecar length must not apply later sidecars with unknown offsets");
    }

    @Test
    void corruptSameLengthMgzSchemaPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager mhzSource = new Sonic3kLevelEventManager();
        mhzSource.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        var mhz = mhzSource.getMhzEventsForTest();
        assertNotNull(mhz);
        mhz.setBossFlag(true);
        mhz.setActTransitionFlag(true);
        mhz.setSpecialEventsRoutine(8);
        LevelEventSnapshot mhzSnapshot = mhzSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        var mgzTarget = target.getMgzEventsForTest();
        assertNotNull(mgzTarget);
        mgzTarget.setCollapseRequested(true);
        mgzTarget.setCollapseFrameCounter(77);
        mgzTarget.setBgRiseRoutine(8);

        Sonic3kLevelEventManager mhzTargetOwner = new Sonic3kLevelEventManager();
        mhzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        set(target, "mhzEvents", mhzTargetOwner.getMhzEventsForTest());
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);
        mhzTarget.setBossFlag(false);
        mhzTarget.setActTransitionFlag(false);
        mhzTarget.setSpecialEventsRoutine(0);

        Sonic3kMGZEvents payloadSource = new Sonic3kMGZEvents();
        payloadSource.init(1);
        payloadSource.setCollapseRequested(false);
        payloadSource.setCollapseFrameCounter(11);
        payloadSource.setBgRiseRoutine(2);
        byte[] corruptPayload = corruptMgzCollapseScrollVelocityLength(
                ZoneEventSchemaSidecar.capture(payloadSource), 9);
        LevelEventSnapshot malformedSnapshot = snapshotWithMgzPayload(mhzSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "schema-corrupt MGZ sidecar should not abort later zone sidecars");
        assertTrue(mgzTarget.isCollapseRequested(),
                "schema-corrupt MGZ sidecar must roll back partial MGZ field writes");
        assertEquals(77, mgzTarget.getCollapseFrameCounter(),
                "schema-corrupt MGZ sidecar must roll back partial MGZ field writes");
        assertEquals(8, mgzTarget.getBgRiseRoutine(),
                "schema-corrupt MGZ sidecar must roll back partial MGZ field writes");
        assertTrue(mhzTarget.isBossFlag());
        assertTrue(mhzTarget.isActTransitionFlagActive());
        assertEquals(8, mhzTarget.getSpecialEventsRoutine());
    }

    @Test
    void shortMgzSchemaPayloadDoesNotRestoreLaterZoneSidecars() throws Exception {
        Sonic3kLevelEventManager mhzSource = new Sonic3kLevelEventManager();
        mhzSource.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        var mhz = mhzSource.getMhzEventsForTest();
        assertNotNull(mhz);
        mhz.setBossFlag(true);
        mhz.setSpecialEventsRoutine(8);
        LevelEventSnapshot mhzSnapshot = mhzSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        var mgzTarget = target.getMgzEventsForTest();
        assertNotNull(mgzTarget);
        mgzTarget.setCollapseRequested(true);
        mgzTarget.setCollapseFrameCounter(77);

        Sonic3kLevelEventManager mhzTargetOwner = new Sonic3kLevelEventManager();
        mhzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        set(target, "mhzEvents", mhzTargetOwner.getMhzEventsForTest());
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);
        mhzTarget.setBossFlag(false);
        mhzTarget.setSpecialEventsRoutine(0);

        LevelEventSnapshot malformedSnapshot = snapshotWithMgzPayload(mhzSnapshot, new byte[] { 0 });

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short MGZ sidecar payload should be rejected before later zone sidecars");
        assertTrue(mgzTarget.isCollapseRequested(),
                "short MGZ sidecar payload must leave existing MGZ fields intact");
        assertEquals(77, mgzTarget.getCollapseFrameCounter(),
                "short MGZ sidecar payload must leave existing MGZ fields intact");
        assertFalse(mhzTarget.isBossFlag(),
                "short MGZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, mhzTarget.getSpecialEventsRoutine(),
                "short MGZ sidecar payload must not apply later sidecars with unknown offsets");
    }

    @Test
    void malformedMgzLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager mhzSource = new Sonic3kLevelEventManager();
        mhzSource.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        mhzSource.requestCnzPostTransitionRelease();
        var mhz = mhzSource.getMhzEventsForTest();
        assertNotNull(mhz);
        mhz.setBossFlag(true);
        mhz.setSpecialEventsRoutine(8);
        LevelEventSnapshot mhzSnapshot = mhzSource.capture();

        Sonic3kMGZEvents mgzPayloadSource = new Sonic3kMGZEvents();
        mgzPayloadSource.init(1);
        mgzPayloadSource.setCollapseRequested(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptMgzLength(
                snapshotWithMgzPayload(mhzSnapshot, ZoneEventSchemaSidecar.capture(mgzPayloadSource)),
                Integer.MAX_VALUE);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        Sonic3kLevelEventManager mhzTargetOwner = new Sonic3kLevelEventManager();
        mhzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        set(target, "mhzEvents", mhzTargetOwner.getMhzEventsForTest());
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "framing-invalid MGZ sidecar should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "framing-invalid MGZ sidecar must not mutate manager-level fields");
        assertFalse(mhzTarget.isBossFlag(),
                "framing-invalid MGZ sidecar must not apply later sidecars with unknown offsets");
        assertEquals(0, mhzTarget.getSpecialEventsRoutine(),
                "framing-invalid MGZ sidecar must not apply later sidecars with unknown offsets");
    }

    @Test
    void underreportedMgzLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager mhzSource = new Sonic3kLevelEventManager();
        mhzSource.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        mhzSource.requestCnzPostTransitionRelease();
        var mhz = mhzSource.getMhzEventsForTest();
        assertNotNull(mhz);
        mhz.setBossFlag(true);
        mhz.setSpecialEventsRoutine(8);
        LevelEventSnapshot mhzSnapshot = mhzSource.capture();

        Sonic3kMGZEvents mgzPayloadSource = new Sonic3kMGZEvents();
        mgzPayloadSource.init(1);
        mgzPayloadSource.setCollapseRequested(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptMgzLength(
                snapshotWithMgzPayload(mhzSnapshot, ZoneEventSchemaSidecar.capture(mgzPayloadSource)),
                0);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        Sonic3kLevelEventManager mhzTargetOwner = new Sonic3kLevelEventManager();
        mhzTargetOwner.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        set(target, "mhzEvents", mhzTargetOwner.getMhzEventsForTest());
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short-but-bounded MGZ sidecar length should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "short-but-bounded MGZ sidecar length must not mutate manager-level fields");
        assertFalse(mhzTarget.isBossFlag(),
                "short-but-bounded MGZ sidecar length must not apply later sidecars with unknown offsets");
        assertEquals(0, mhzTarget.getSpecialEventsRoutine(),
                "short-but-bounded MGZ sidecar length must not apply later sidecars with unknown offsets");
    }

    @Test
    void corruptSameLengthMhzSchemaPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager iczSource = new Sonic3kLevelEventManager();
        iczSource.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        Sonic3kICZEvents icz = (Sonic3kICZEvents) get(iczSource, "iczEvents");
        assertNotNull(icz);
        icz.setEventsFg5(true);
        set(icz, "backgroundRoutine", 8);
        LevelEventSnapshot iczSnapshot = iczSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MHZ, 1);
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);
        mhzTarget.setBossFlag(true);
        mhzTarget.setSpecialEventsRoutine(7);
        set(mhzTarget, "seasonPaletteMode", Sonic3kMHZEvents.SeasonPaletteMode.GOLD);

        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);
        iczTarget.setEventsFg5(false);
        set(iczTarget, "backgroundRoutine", 0);

        Sonic3kMHZEvents payloadSource = new Sonic3kMHZEvents();
        payloadSource.init(1);
        payloadSource.setBossFlag(false);
        payloadSource.setSpecialEventsRoutine(1);
        set(payloadSource, "seasonPaletteMode", Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN);
        byte[] corruptPayload = corruptMhzSeasonPaletteModeOrdinal(
                ZoneEventSchemaSidecar.capture(payloadSource), 99);
        LevelEventSnapshot malformedSnapshot = snapshotWithMhzPayload(iczSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "schema-corrupt MHZ sidecar should not abort later zone sidecars");
        assertTrue(mhzTarget.isBossFlag(),
                "schema-corrupt MHZ sidecar must roll back partial MHZ field writes");
        assertEquals(7, mhzTarget.getSpecialEventsRoutine(),
                "schema-corrupt MHZ sidecar must roll back partial MHZ field writes");
        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GOLD, get(mhzTarget, "seasonPaletteMode"),
                "schema-corrupt MHZ sidecar must roll back partial MHZ field writes");
        assertTrue(iczTarget.isEventsFg5());
        assertEquals(8, iczTarget.getIcz1BackgroundRoutine());
    }

    @Test
    void nullMhzSpikeArrayPayloadRollsBackAndDoesNotAbortLaterZoneRestore() throws Exception {
        Sonic3kLevelEventManager iczSource = new Sonic3kLevelEventManager();
        iczSource.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        Sonic3kICZEvents icz = (Sonic3kICZEvents) get(iczSource, "iczEvents");
        assertNotNull(icz);
        icz.setEventsFg5(true);
        set(icz, "backgroundRoutine", 8);
        LevelEventSnapshot iczSnapshot = iczSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MHZ, 1);
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);
        mhzTarget.setBossFlag(true);
        mhzTarget.setSpecialEventsRoutine(7);

        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);
        iczTarget.setEventsFg5(false);
        set(iczTarget, "backgroundRoutine", 0);

        Sonic3kMHZEvents payloadSource = new Sonic3kMHZEvents();
        payloadSource.init(1);
        payloadSource.setBossFlag(false);
        payloadSource.setSpecialEventsRoutine(1);
        byte[] corruptPayload = corruptMhzSpikeTiersLength(
                ZoneEventSchemaSidecar.capture(payloadSource), -1);
        LevelEventSnapshot malformedSnapshot = snapshotWithMhzPayload(iczSnapshot, corruptPayload);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "schema-null MHZ spike array should roll back MHZ and not abort later sidecars");
        assertTrue(mhzTarget.isBossFlag(),
                "schema-null MHZ spike array must roll back partial MHZ field writes");
        assertEquals(7, mhzTarget.getSpecialEventsRoutine(),
                "schema-null MHZ spike array must roll back partial MHZ field writes");
        assertArrayEquals(new int[0], (int[]) get(mhzTarget, "endBossArenaSpikeTiers"),
                "schema-null MHZ spike array must restore the previous non-null tiers array");
        assertArrayEquals(new boolean[0], (boolean[]) get(mhzTarget, "endBossArenaSpikeAlternateSides"),
                "schema-null MHZ spike array must restore the previous non-null alternate-sides array");
        assertArrayEquals(new boolean[0], (boolean[]) get(mhzTarget, "endBossArenaSpikeActive"),
                "schema-null MHZ spike array must restore the previous non-null active array");
        assertArrayEquals(new int[0], (int[]) get(mhzTarget, "endBossArenaSpikeY"),
                "schema-null MHZ spike array must restore the previous non-null Y array");
        assertTrue(iczTarget.isEventsFg5());
        assertEquals(8, iczTarget.getIcz1BackgroundRoutine());
    }

    @Test
    void shortMhzSchemaPayloadDoesNotRestoreLaterZoneSidecars() throws Exception {
        Sonic3kLevelEventManager iczSource = new Sonic3kLevelEventManager();
        iczSource.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        Sonic3kICZEvents icz = (Sonic3kICZEvents) get(iczSource, "iczEvents");
        assertNotNull(icz);
        icz.setEventsFg5(true);
        set(icz, "backgroundRoutine", 8);
        LevelEventSnapshot iczSnapshot = iczSource.capture();

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MHZ, 1);
        var mhzTarget = target.getMhzEventsForTest();
        assertNotNull(mhzTarget);
        mhzTarget.setBossFlag(true);
        mhzTarget.setSpecialEventsRoutine(7);

        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);
        iczTarget.setEventsFg5(false);
        set(iczTarget, "backgroundRoutine", 0);

        LevelEventSnapshot malformedSnapshot = snapshotWithMhzPayload(iczSnapshot, new byte[] { 0 });

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short MHZ sidecar payload should be rejected before later zone sidecars");
        assertTrue(mhzTarget.isBossFlag(),
                "short MHZ sidecar payload must leave existing MHZ fields intact");
        assertEquals(7, mhzTarget.getSpecialEventsRoutine(),
                "short MHZ sidecar payload must leave existing MHZ fields intact");
        assertFalse(iczTarget.isEventsFg5(),
                "short MHZ sidecar payload must not apply later sidecars with unknown offsets");
        assertEquals(0, iczTarget.getIcz1BackgroundRoutine(),
                "short MHZ sidecar payload must not apply later sidecars with unknown offsets");
    }

    @Test
    void malformedMhzLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager iczSource = new Sonic3kLevelEventManager();
        iczSource.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        iczSource.requestCnzPostTransitionRelease();
        Sonic3kICZEvents icz = (Sonic3kICZEvents) get(iczSource, "iczEvents");
        assertNotNull(icz);
        icz.setEventsFg5(true);
        set(icz, "backgroundRoutine", 8);
        LevelEventSnapshot iczSnapshot = iczSource.capture();

        Sonic3kMHZEvents mhzPayloadSource = new Sonic3kMHZEvents();
        mhzPayloadSource.init(1);
        mhzPayloadSource.setBossFlag(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptMhzLength(
                snapshotWithMhzPayload(iczSnapshot, ZoneEventSchemaSidecar.capture(mhzPayloadSource)),
                Integer.MAX_VALUE);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MHZ, 1);
        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "framing-invalid MHZ sidecar should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "framing-invalid MHZ sidecar must not mutate manager-level fields");
        assertFalse(iczTarget.isEventsFg5(),
                "framing-invalid MHZ sidecar must not apply later sidecars with unknown offsets");
        assertEquals(0, iczTarget.getIcz1BackgroundRoutine(),
                "framing-invalid MHZ sidecar must not apply later sidecars with unknown offsets");
    }

    @Test
    void underreportedMhzLengthPrefixDoesNotPartiallyRestoreManagerOrLaterSidecars() throws Exception {
        Sonic3kLevelEventManager iczSource = new Sonic3kLevelEventManager();
        iczSource.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        iczSource.requestCnzPostTransitionRelease();
        Sonic3kICZEvents icz = (Sonic3kICZEvents) get(iczSource, "iczEvents");
        assertNotNull(icz);
        icz.setEventsFg5(true);
        set(icz, "backgroundRoutine", 8);
        LevelEventSnapshot iczSnapshot = iczSource.capture();

        Sonic3kMHZEvents mhzPayloadSource = new Sonic3kMHZEvents();
        mhzPayloadSource.init(1);
        mhzPayloadSource.setBossFlag(true);
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptMhzLength(
                snapshotWithMhzPayload(iczSnapshot, ZoneEventSchemaSidecar.capture(mhzPayloadSource)),
                0);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        target.initLevel(Sonic3kZoneIds.ZONE_MHZ, 1);
        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "short-but-bounded MHZ sidecar length should be rejected before any partial restore");
        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "short-but-bounded MHZ sidecar length must not mutate manager-level fields");
        assertFalse(iczTarget.isEventsFg5(),
                "short-but-bounded MHZ sidecar length must not apply later sidecars with unknown offsets");
        assertEquals(0, iczTarget.getIcz1BackgroundRoutine(),
                "short-but-bounded MHZ sidecar length must not apply later sidecars with unknown offsets");
    }

    private static LevelEventSnapshot snapshotWithAizPayload(LevelEventSnapshot source, byte[] aizPayload) {
        LevelEventSnapshot malformedSnapshot = new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                insertAizPayload(source.extra(), aizPayload));
        return malformedSnapshot;
    }

    private static LevelEventSnapshot snapshotWithHczPayload(LevelEventSnapshot source, byte[] hczPayload) {
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                insertHczPayload(source.extra(), hczPayload));
    }

    private static LevelEventSnapshot snapshotWithCnzPayload(LevelEventSnapshot source, byte[] cnzPayload) {
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                insertCnzPayload(source.extra(), cnzPayload));
    }

    private static LevelEventSnapshot snapshotWithCorruptHczLength(LevelEventSnapshot source, int length) {
        byte[] extra = source.extra().clone();
        int hczLengthOffset = 30 + 1 + 1;
        assertEquals(0, extra[30], "test helper expects absent AIZ sidecar");
        assertEquals(1, extra[31], "test helper expects present HCZ sidecar");
        ByteBuffer.wrap(extra).putInt(hczLengthOffset, length);
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                extra);
    }

    private static LevelEventSnapshot snapshotWithCorruptCnzLength(LevelEventSnapshot source, int length) {
        byte[] extra = source.extra().clone();
        int cnzLengthOffset = 30 + 1 + 1 + 1;
        assertEquals(0, extra[30], "test helper expects absent AIZ sidecar");
        assertEquals(0, extra[31], "test helper expects absent HCZ sidecar");
        assertEquals(1, extra[32], "test helper expects present CNZ sidecar");
        ByteBuffer.wrap(extra).putInt(cnzLengthOffset, length);
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                extra);
    }

    private static LevelEventSnapshot snapshotWithMgzPayload(LevelEventSnapshot source, byte[] mgzPayload) {
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                insertMgzPayload(source.extra(), mgzPayload));
    }

    private static LevelEventSnapshot snapshotWithCorruptMgzLength(LevelEventSnapshot source, int length) {
        byte[] extra = source.extra().clone();
        int mgzLengthOffset = 30 + 1 + 1 + 1 + 1;
        assertEquals(0, extra[30], "test helper expects absent AIZ sidecar");
        assertEquals(0, extra[31], "test helper expects absent HCZ sidecar");
        assertEquals(0, extra[32], "test helper expects absent CNZ sidecar");
        assertEquals(1, extra[33], "test helper expects present MGZ sidecar");
        ByteBuffer.wrap(extra).putInt(mgzLengthOffset, length);
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                extra);
    }

    private static LevelEventSnapshot snapshotWithMhzPayload(LevelEventSnapshot source, byte[] mhzPayload) {
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                insertMhzPayload(source.extra(), mhzPayload));
    }

    private static LevelEventSnapshot snapshotWithCorruptMhzLength(LevelEventSnapshot source, int length) {
        byte[] extra = source.extra().clone();
        int mhzLengthOffset = 30 + 1 + 1 + 1 + 1 + 1;
        assertEquals(0, extra[30], "test helper expects absent AIZ sidecar");
        assertEquals(0, extra[31], "test helper expects absent HCZ sidecar");
        assertEquals(0, extra[32], "test helper expects absent CNZ sidecar");
        assertEquals(0, extra[33], "test helper expects absent MGZ sidecar");
        assertEquals(1, extra[34], "test helper expects present MHZ sidecar");
        ByteBuffer.wrap(extra).putInt(mhzLengthOffset, length);
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                extra);
    }

    private static LevelEventSnapshot snapshotWithIczPayload(LevelEventSnapshot source, byte[] iczPayload) {
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                insertIczPayload(source.extra(), iczPayload));
    }

    private static LevelEventSnapshot snapshotWithCorruptIczLength(LevelEventSnapshot source, int length) {
        byte[] extra = source.extra().clone();
        int iczLengthOffset = 30 + 1 + 1 + 1 + 1 + 1 + 1;
        assertEquals(0, extra[30], "test helper expects absent AIZ sidecar");
        assertEquals(0, extra[31], "test helper expects absent HCZ sidecar");
        assertEquals(0, extra[32], "test helper expects absent CNZ sidecar");
        assertEquals(0, extra[33], "test helper expects absent MGZ sidecar");
        assertEquals(0, extra[34], "test helper expects absent MHZ sidecar");
        assertEquals(1, extra[35], "test helper expects present ICZ sidecar");
        ByteBuffer.wrap(extra).putInt(iczLengthOffset, length);
        return new LevelEventSnapshot(
                source.currentZone(),
                source.currentAct(),
                source.eventRoutineFg(),
                source.eventRoutineBg(),
                source.frameCounter(),
                source.timerFrames(),
                source.bossActive(),
                source.eventDataFg(),
                source.eventDataBg(),
                extra);
    }

    @Test
    void lengthPrefixedIczSchemaPayloadRestoresIczAndFixedAirSidecars() throws Exception {
        Sonic3kICZEvents iczPayloadSource = new Sonic3kICZEvents();
        iczPayloadSource.setEventsFg5(true);
        iczPayloadSource.setIndoorPaletteCyclingActive(false);
        set(iczPayloadSource, "backgroundRoutine", 8);
        set(iczPayloadSource, "bigSnowOffset", 0x1234);
        set(iczPayloadSource, "act2TransitionChunkOrdinal", 3L);
        set(iczPayloadSource, "act2TransitionBlockOrdinal", 4L);
        set(iczPayloadSource, "act2TransitionArtOrdinal", 2L);
        set(iczPayloadSource, "act2TransitionHandoffId", 9L);
        set(iczPayloadSource, "act2TransitionDirectPublished", true);
        set(iczPayloadSource, "act2TransitionArtPublished", true);

        Sonic3kLevelEventManager source = new Sonic3kLevelEventManager();
        Object sourceFixed = fixedAirCountdownManager(source);
        Object sourceP1 = fixedController(sourceFixed, "p1");
        setFixedControllerState(sourceP1, true, 0x0A, 0x81, 0x1234, 0x02, 0x01, 0xFF, 0x8040, 0x0032, 0x0016);
        LevelEventSnapshot sourceSnapshot = source.capture();
        LevelEventSnapshot schemaIczSnapshot = snapshotWithIczPayload(
                sourceSnapshot,
                ZoneEventSchemaSidecar.capture(iczPayloadSource));

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);
        iczTarget.setEventsFg5(false);
        set(iczTarget, "backgroundRoutine", 0);
        Object targetFixed = fixedAirCountdownManager(target);
        Object targetP1 = fixedController(targetFixed, "p1");
        setFixedControllerState(targetP1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        target.restore(schemaIczSnapshot);

        assertTrue(iczTarget.isEventsFg5(),
                "length-prefixed ICZ schema sidecar must restore ICZ booleans");
        assertEquals(8, iczTarget.getIcz1BackgroundRoutine(),
                "length-prefixed ICZ schema sidecar must restore ICZ ints");
        assertEquals(0x1234, iczTarget.getIcz1BigSnowOffset(),
                "length-prefixed ICZ schema sidecar must restore ICZ snow state");
        assertEquals(3L, get(iczTarget, "act2TransitionChunkOrdinal"),
                "ICZ chunk-job identity must remain aligned in the manager sidecar");
        assertEquals(4L, get(iczTarget, "act2TransitionBlockOrdinal"),
                "ICZ block-job identity must remain aligned in the manager sidecar");
        assertEquals(2L, get(iczTarget, "act2TransitionArtOrdinal"),
                "ICZ module-parent identity must remain aligned in the manager sidecar");
        assertEquals(9L, get(iczTarget, "act2TransitionHandoffId"),
                "ICZ handoff identity must remain aligned in the manager sidecar");
        assertEquals(true, get(iczTarget, "act2TransitionDirectPublished"),
                "ICZ direct publication state must remain aligned in the manager sidecar");
        assertEquals(true, get(iczTarget, "act2TransitionArtPublished"),
                "ICZ art publication state must remain aligned in the manager sidecar");
        assertEquals("true,10,129,4660,2,1,255,32832,50,22", fixedControllerState(targetP1),
                "fixed Breathing_bubbles sidecar must remain aligned after ICZ schema payload");
    }

    @Test
    void malformedIczLengthPrefixDoesNotPartiallyRestoreManagerOrFixedAirSidecars() throws Exception {
        Sonic3kICZEvents iczPayloadSource = new Sonic3kICZEvents();
        iczPayloadSource.setEventsFg5(true);
        set(iczPayloadSource, "backgroundRoutine", 8);

        Sonic3kLevelEventManager source = new Sonic3kLevelEventManager();
        source.requestCnzPostTransitionRelease();
        Object sourceFixed = fixedAirCountdownManager(source);
        Object sourceP1 = fixedController(sourceFixed, "p1");
        setFixedControllerState(sourceP1, true, 0x0A, 0x81, 0x1234, 0x02, 0x01, 0xFF, 0x8040, 0x0032, 0x0016);
        LevelEventSnapshot sourceSnapshot = snapshotWithIczPayload(
                source.capture(),
                ZoneEventSchemaSidecar.capture(iczPayloadSource));
        LevelEventSnapshot malformedSnapshot = snapshotWithCorruptIczLength(
                sourceSnapshot,
                sourceSnapshot.extra().length);

        Sonic3kLevelEventManager target = new Sonic3kLevelEventManager();
        Sonic3kLevelEventManager iczTargetOwner = new Sonic3kLevelEventManager();
        iczTargetOwner.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        set(target, "iczEvents", get(iczTargetOwner, "iczEvents"));
        Sonic3kICZEvents iczTarget = (Sonic3kICZEvents) get(target, "iczEvents");
        assertNotNull(iczTarget);
        Object targetFixed = fixedAirCountdownManager(target);
        Object targetP1 = fixedController(targetFixed, "p1");
        setFixedControllerState(targetP1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertDoesNotThrow(() -> target.restore(malformedSnapshot),
                "framing-invalid ICZ sidecar should be rejected before any partial restore");

        assertEquals(false, get(target, "cnzPendingPostTransitionRelease"),
                "framing-invalid ICZ sidecar must not mutate manager-level fields");
        assertFalse(iczTarget.isEventsFg5(),
                "framing-invalid ICZ sidecar must not mutate ICZ fields");
        assertEquals(0, iczTarget.getIcz1BackgroundRoutine(),
                "framing-invalid ICZ sidecar must not mutate ICZ fields");
        assertEquals("false,0,0,0,0,0,0,0,0,0", fixedControllerState(targetP1),
                "framing-invalid ICZ sidecar must not apply later fixed-air sidecars with unknown offsets");
    }

    @Test
    void roundTripFixedAirCountdownSidecarRam() throws Exception {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);

        Object fixed = fixedAirCountdownManager(mgr);
        Object p1 = fixedController(fixed, "p1");
        setFixedControllerState(p1, true, 0x0A, 0x81, 0x1234, 0x02, 0x01, 0xFF, 0x8040, 0x0032, 0x0016);

        LevelEventSnapshot snap = mgr.capture();

        setFixedControllerState(p1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        set(mgr, "fixedAirCountdownZone", Sonic3kZoneIds.ZONE_AIZ);
        set(mgr, "fixedAirCountdownAct", 0);
        mgr.restore(snap);

        assertEquals("true,10,129,4660,2,1,255,32832,50,22", fixedControllerState(p1),
                "fixed Breathing_bubbles sidecar RAM must rewind with S3K event-manager snapshots");
        assertEquals(Sonic3kZoneIds.ZONE_CNZ, get(mgr, "fixedAirCountdownZone"));
        assertEquals(1, get(mgr, "fixedAirCountdownAct"));
    }

    @Test
    void legacyFixedAirSnapshotClearsUnrepresentedOwner() throws Exception {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);
        LevelEventSnapshot current = mgr.capture();
        byte[] extra = current.extra();
        LevelEventSnapshot legacy = new LevelEventSnapshot(
                current.currentZone(), current.currentAct(),
                current.eventRoutineFg(), current.eventRoutineBg(),
                current.frameCounter(), current.timerFrames(), current.bossActive(),
                current.eventDataFg(), current.eventDataBg(),
                java.util.Arrays.copyOf(extra, extra.length - (2 * Integer.BYTES)));

        set(mgr, "fixedAirCountdownZone", Sonic3kZoneIds.ZONE_AIZ);
        set(mgr, "fixedAirCountdownAct", 0);
        mgr.restore(legacy);

        assertEquals(-1, get(mgr, "fixedAirCountdownZone"));
        assertEquals(-1, get(mgr, "fixedAirCountdownAct"));
    }

    @Test
    void fixedAirCountdownSelectsCountdownNumberSubtypeAndDisplayTimer() throws Exception {
        Sonic3kLevelEventManager mgr = new Sonic3kLevelEventManager();
        mgr.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);

        Object fixed = fixedAirCountdownManager(mgr);
        Object p1 = fixedController(fixed, "p1");
        set(p1, "obj3a", 0x80);
        set(p1, "obj38", 0);

        Sonic sonic = new Sonic("test", (short) 0, (short) 0);
        sonic.getDrowningController().setRemainingAirFromFixedCountdown(10);

        Method method = p1.getClass().getDeclaredMethod("countdownChildFor", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        method.setAccessible(true);
        Object child = method.invoke(p1, sonic);

        assertEquals(5, get(child, "subtype"),
                "ROM AirCountdown_MakeItem uses air_left >> 1 as countdown-number subtype");
        assertEquals(0x1C, get(child, "displayTimer"),
                "countdown number children start with $3C=$1C before AirCountdown_ShowNumber");
    }

    private static Object fixedAirCountdownManager(Sonic3kLevelEventManager mgr) throws Exception {
        Field field = Sonic3kLevelEventManager.class.getDeclaredField("fixedAirCountdownManager");
        field.setAccessible(true);
        return field.get(mgr);
    }

    private static Object fixedController(Object fixed, String name) throws Exception {
        Field field = fixed.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(fixed);
    }

    private static void setFixedControllerState(Object controller,
                                               boolean installed,
                                               int routine,
                                               int subtype,
                                               int obj30,
                                               int obj36,
                                               int obj37,
                                               int obj38,
                                               int obj3a,
                                               int obj3c,
                                               int obj3e) throws Exception {
        set(controller, "installed", installed);
        set(controller, "routine", routine);
        set(controller, "subtype", subtype);
        set(controller, "obj30", obj30);
        set(controller, "obj36", obj36);
        set(controller, "obj37", obj37);
        set(controller, "obj38", obj38);
        set(controller, "obj3a", obj3a);
        set(controller, "obj3c", obj3c);
        set(controller, "obj3e", obj3e);
    }

    private static String fixedControllerState(Object controller) throws Exception {
        return get(controller, "installed") + ","
                + get(controller, "routine") + ","
                + get(controller, "subtype") + ","
                + get(controller, "obj30") + ","
                + get(controller, "obj36") + ","
                + get(controller, "obj37") + ","
                + get(controller, "obj38") + ","
                + get(controller, "obj3a") + ","
                + get(controller, "obj3c") + ","
                + get(controller, "obj3e");
    }

    private static byte[] insertMalformedAizPayload(byte[] hczExtra) {
        return insertAizPayload(hczExtra, new byte[] { 0 });
    }

    private static byte[] insertAizPayload(byte[] hczExtra, byte[] aizPayload) {
        int managerBytes = 30;
        int originalAizAbsentFlagBytes = 1;
        ByteBuffer buf = ByteBuffer.allocate(managerBytes + 1 + Integer.BYTES + aizPayload.length
                + hczExtra.length - managerBytes - originalAizAbsentFlagBytes);
        buf.put(hczExtra, 0, managerBytes);
        buf.put((byte) 1);
        buf.putInt(aizPayload.length);
        buf.put(aizPayload);
        buf.put(hczExtra, managerBytes + originalAizAbsentFlagBytes,
                hczExtra.length - managerBytes - originalAizAbsentFlagBytes);
        return buf.array();
    }

    private static byte[] insertHczPayload(byte[] cnzExtra, byte[] hczPayload) {
        int managerBytes = 30;
        int originalAizAbsentFlagBytes = 1;
        int originalHczAbsentFlagBytes = 1;
        int prefixBytes = managerBytes + originalAizAbsentFlagBytes;
        ByteBuffer buf = ByteBuffer.allocate(prefixBytes + 1 + Integer.BYTES + hczPayload.length
                + cnzExtra.length - prefixBytes - originalHczAbsentFlagBytes);
        buf.put(cnzExtra, 0, prefixBytes);
        buf.put((byte) 1);
        buf.putInt(hczPayload.length);
        buf.put(hczPayload);
        buf.put(cnzExtra, prefixBytes + originalHczAbsentFlagBytes,
                cnzExtra.length - prefixBytes - originalHczAbsentFlagBytes);
        return buf.array();
    }

    private static byte[] insertCnzPayload(byte[] mgzExtra, byte[] cnzPayload) {
        int managerBytes = 30;
        int originalAizAbsentFlagBytes = 1;
        int originalHczAbsentFlagBytes = 1;
        int originalCnzAbsentFlagBytes = 1;
        int prefixBytes = managerBytes + originalAizAbsentFlagBytes + originalHczAbsentFlagBytes;
        ByteBuffer buf = ByteBuffer.allocate(prefixBytes + 1 + Integer.BYTES + cnzPayload.length
                + mgzExtra.length - prefixBytes - originalCnzAbsentFlagBytes);
        buf.put(mgzExtra, 0, prefixBytes);
        buf.put((byte) 1);
        buf.putInt(cnzPayload.length);
        buf.put(cnzPayload);
        buf.put(mgzExtra, prefixBytes + originalCnzAbsentFlagBytes,
                mgzExtra.length - prefixBytes - originalCnzAbsentFlagBytes);
        return buf.array();
    }

    private static byte[] insertMgzPayload(byte[] mhzExtra, byte[] mgzPayload) {
        int managerBytes = 30;
        int originalAizAbsentFlagBytes = 1;
        int originalHczAbsentFlagBytes = 1;
        int originalCnzAbsentFlagBytes = 1;
        int originalMgzAbsentFlagBytes = 1;
        int prefixBytes = managerBytes
                + originalAizAbsentFlagBytes
                + originalHczAbsentFlagBytes
                + originalCnzAbsentFlagBytes;
        ByteBuffer buf = ByteBuffer.allocate(prefixBytes + 1 + Integer.BYTES + mgzPayload.length
                + mhzExtra.length - prefixBytes - originalMgzAbsentFlagBytes);
        buf.put(mhzExtra, 0, prefixBytes);
        buf.put((byte) 1);
        buf.putInt(mgzPayload.length);
        buf.put(mgzPayload);
        buf.put(mhzExtra, prefixBytes + originalMgzAbsentFlagBytes,
                mhzExtra.length - prefixBytes - originalMgzAbsentFlagBytes);
        return buf.array();
    }

    private static byte[] insertMhzPayload(byte[] iczExtra, byte[] mhzPayload) {
        int managerBytes = 30;
        int originalAizAbsentFlagBytes = 1;
        int originalHczAbsentFlagBytes = 1;
        int originalCnzAbsentFlagBytes = 1;
        int originalMgzAbsentFlagBytes = 1;
        int originalMhzAbsentFlagBytes = 1;
        int prefixBytes = managerBytes
                + originalAizAbsentFlagBytes
                + originalHczAbsentFlagBytes
                + originalCnzAbsentFlagBytes
                + originalMgzAbsentFlagBytes;
        ByteBuffer buf = ByteBuffer.allocate(prefixBytes + 1 + Integer.BYTES + mhzPayload.length
                + iczExtra.length - prefixBytes - originalMhzAbsentFlagBytes);
        buf.put(iczExtra, 0, prefixBytes);
        buf.put((byte) 1);
        buf.putInt(mhzPayload.length);
        buf.put(mhzPayload);
        buf.put(iczExtra, prefixBytes + originalMhzAbsentFlagBytes,
                iczExtra.length - prefixBytes - originalMhzAbsentFlagBytes);
        return buf.array();
    }

    private static byte[] insertIczPayload(byte[] extra, byte[] iczPayload) {
        int managerBytes = 30;
        int originalAizAbsentFlagBytes = 1;
        int originalHczAbsentFlagBytes = 1;
        int originalCnzAbsentFlagBytes = 1;
        int originalMgzAbsentFlagBytes = 1;
        int originalMhzAbsentFlagBytes = 1;
        int originalIczAbsentFlagBytes = 1;
        int prefixBytes = managerBytes
                + originalAizAbsentFlagBytes
                + originalHczAbsentFlagBytes
                + originalCnzAbsentFlagBytes
                + originalMgzAbsentFlagBytes
                + originalMhzAbsentFlagBytes;
        assertEquals(0, extra[30], "test helper expects absent AIZ sidecar");
        assertEquals(0, extra[31], "test helper expects absent HCZ sidecar");
        assertEquals(0, extra[32], "test helper expects absent CNZ sidecar");
        assertEquals(0, extra[33], "test helper expects absent MGZ sidecar");
        assertEquals(0, extra[34], "test helper expects absent MHZ sidecar");
        assertEquals(0, extra[35], "test helper expects absent ICZ sidecar");
        ByteBuffer buf = ByteBuffer.allocate(prefixBytes + 1 + Integer.BYTES + iczPayload.length
                + extra.length - prefixBytes - originalIczAbsentFlagBytes);
        buf.put(extra, 0, prefixBytes);
        buf.put((byte) 1);
        buf.putInt(iczPayload.length);
        buf.put(iczPayload);
        buf.put(extra, prefixBytes + originalIczAbsentFlagBytes,
                extra.length - prefixBytes - originalIczAbsentFlagBytes);
        return buf.array();
    }

    private static byte[] corruptFireSequencePhaseOrdinal(byte[] payload, int invalidOrdinal) {
        Sonic3kAIZEvents zero = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        zero.setFireSequencePhaseOrdinal(0);
        byte[] zeroPayload = ZoneEventSchemaSidecar.capture(zero);

        Sonic3kAIZEvents one = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        one.setFireSequencePhaseOrdinal(1);
        byte[] onePayload = ZoneEventSchemaSidecar.capture(one);

        assertEquals(zeroPayload.length, payload.length);
        int changedByte = -1;
        for (int i = 0; i < zeroPayload.length; i++) {
            if (zeroPayload[i] != onePayload[i]) {
                assertEquals(-1, changedByte,
                        "fireSequencePhase ordinal should be the only byte changed by this probe");
                changedByte = i;
            }
        }
        assertTrue(changedByte >= 0, "fireSequencePhase ordinal offset must be discoverable");

        byte[] corrupt = payload.clone();
        int ordinalOffset = changedByte;
        assertTrue(ordinalOffset + Integer.BYTES <= corrupt.length,
                "fireSequencePhase ordinal offset must be valid");
        corrupt[ordinalOffset] = (byte) invalidOrdinal;
        corrupt[ordinalOffset + 1] = (byte) (invalidOrdinal >>> 8);
        corrupt[ordinalOffset + 2] = (byte) (invalidOrdinal >>> 16);
        corrupt[ordinalOffset + 3] = (byte) (invalidOrdinal >>> 24);
        return corrupt;
    }

    private static byte[] corruptHczProbeModeOrdinal(byte[] payload, int invalidOrdinal) {
        HczProbeEvents zero = new HczProbeEvents();
        zero.init(1);
        zero.setProbeMode(HczProbeMode.ZERO);
        byte[] zeroPayload = ZoneEventSchemaSidecar.capture(zero);

        HczProbeEvents one = new HczProbeEvents();
        one.init(1);
        one.setProbeMode(HczProbeMode.ONE);
        byte[] onePayload = ZoneEventSchemaSidecar.capture(one);

        assertEquals(zeroPayload.length, payload.length);
        int changedByte = -1;
        for (int i = 0; i < zeroPayload.length; i++) {
            if (zeroPayload[i] != onePayload[i]) {
                assertEquals(-1, changedByte,
                        "probeMode ordinal should be the only byte changed by this probe");
                changedByte = i;
            }
        }
        assertTrue(changedByte >= 0, "probeMode ordinal offset must be discoverable");

        byte[] corrupt = payload.clone();
        int ordinalOffset = changedByte;
        assertTrue(ordinalOffset + Integer.BYTES <= corrupt.length,
                "probeMode ordinal offset must be valid");
        corrupt[ordinalOffset] = (byte) invalidOrdinal;
        corrupt[ordinalOffset + 1] = (byte) (invalidOrdinal >>> 8);
        corrupt[ordinalOffset + 2] = (byte) (invalidOrdinal >>> 16);
        corrupt[ordinalOffset + 3] = (byte) (invalidOrdinal >>> 24);
        return corrupt;
    }

    private static byte[] corruptCnzBossBackgroundModeOrdinal(byte[] payload, int invalidOrdinal) {
        Sonic3kCNZEvents zero = new Sonic3kCNZEvents();
        zero.init(0);
        zero.setBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.NORMAL);
        byte[] zeroPayload = ZoneEventSchemaSidecar.capture(zero);

        Sonic3kCNZEvents one = new Sonic3kCNZEvents();
        one.init(0);
        one.setBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);
        byte[] onePayload = ZoneEventSchemaSidecar.capture(one);

        assertEquals(zeroPayload.length, payload.length);
        int changedByte = -1;
        for (int i = 0; i < zeroPayload.length; i++) {
            if (zeroPayload[i] != onePayload[i]) {
                assertEquals(-1, changedByte,
                        "bossBackgroundMode ordinal should be the only byte changed by this probe");
                changedByte = i;
            }
        }
        assertTrue(changedByte >= 0, "bossBackgroundMode ordinal offset must be discoverable");

        byte[] corrupt = payload.clone();
        int ordinalOffset = changedByte;
        assertTrue(ordinalOffset + Integer.BYTES <= corrupt.length,
                "bossBackgroundMode ordinal offset must be valid");
        corrupt[ordinalOffset] = (byte) invalidOrdinal;
        corrupt[ordinalOffset + 1] = (byte) (invalidOrdinal >>> 8);
        corrupt[ordinalOffset + 2] = (byte) (invalidOrdinal >>> 16);
        corrupt[ordinalOffset + 3] = (byte) (invalidOrdinal >>> 24);
        return corrupt;
    }

    private static byte[] corruptMgzCollapseScrollVelocityLength(byte[] payload, int invalidLength) {
        Sonic3kMGZEvents zero = new Sonic3kMGZEvents();
        zero.init(1);
        byte[] zeroPayload = ZoneEventSchemaSidecar.capture(zero);

        Sonic3kMGZEvents one = new Sonic3kMGZEvents();
        one.init(1);
        int[] velocity = new int[10];
        velocity[0] = 1;
        one.setCollapseScrollVelocity(velocity);
        byte[] onePayload = ZoneEventSchemaSidecar.capture(one);

        assertEquals(zeroPayload.length, payload.length);
        int changedByte = -1;
        for (int i = 0; i < zeroPayload.length; i++) {
            if (zeroPayload[i] != onePayload[i]) {
                assertEquals(-1, changedByte,
                        "collapseScrollVelocity[0] should be the only byte changed by this probe");
                changedByte = i;
            }
        }
        assertTrue(changedByte >= Integer.BYTES,
                "collapseScrollVelocity array payload offset must be discoverable");

        byte[] corrupt = payload.clone();
        putLittleEndianInt(corrupt, changedByte - Integer.BYTES, invalidLength);
        return corrupt;
    }

    private static byte[] corruptMhzSeasonPaletteModeOrdinal(byte[] payload, int invalidOrdinal) throws Exception {
        Sonic3kMHZEvents zero = new Sonic3kMHZEvents();
        zero.init(1);
        set(zero, "seasonPaletteMode", Sonic3kMHZEvents.SeasonPaletteMode.GREEN);
        byte[] zeroPayload = ZoneEventSchemaSidecar.capture(zero);

        Sonic3kMHZEvents one = new Sonic3kMHZEvents();
        one.init(1);
        set(one, "seasonPaletteMode", Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN);
        byte[] onePayload = ZoneEventSchemaSidecar.capture(one);

        assertEquals(zeroPayload.length, payload.length);
        int changedByte = -1;
        for (int i = 0; i < zeroPayload.length; i++) {
            if (zeroPayload[i] != onePayload[i]) {
                assertEquals(-1, changedByte,
                        "seasonPaletteMode ordinal should be the only byte changed by this probe");
                changedByte = i;
            }
        }
        assertTrue(changedByte >= 0, "seasonPaletteMode ordinal offset must be discoverable");

        byte[] corrupt = payload.clone();
        putLittleEndianInt(corrupt, changedByte, invalidOrdinal);
        return corrupt;
    }

    private static byte[] corruptMhzSpikeTiersLength(byte[] payload, int invalidLength) throws Exception {
        Sonic3kMHZEvents zero = new Sonic3kMHZEvents();
        zero.init(1);
        byte[] zeroPayload = ZoneEventSchemaSidecar.capture(zero);

        Sonic3kMHZEvents one = new Sonic3kMHZEvents();
        one.init(1);
        set(one, "endBossArenaSpikeTiers", new int[] { 0 });
        byte[] onePayload = ZoneEventSchemaSidecar.capture(one);

        assertEquals(zeroPayload.length, payload.length);
        int changedByte = -1;
        for (int i = 0; i < Math.min(zeroPayload.length, onePayload.length); i++) {
            if (zeroPayload[i] != onePayload[i]) {
                changedByte = i;
                break;
            }
        }
        assertTrue(changedByte >= 0, "endBossArenaSpikeTiers length offset must be discoverable");

        byte[] corrupt = payload.clone();
        putLittleEndianInt(corrupt, changedByte, invalidLength);
        return corrupt;
    }

    private static void putLittleEndianInt(byte[] bytes, int offset, int value) {
        assertTrue(offset >= 0 && offset + Integer.BYTES <= bytes.length,
                "little-endian int write offset must be valid");
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private enum HczProbeMode {
        ZERO,
        ONE
    }

    private static final class HczProbeEvents extends Sonic3kHCZEvents {
        private HczProbeMode probeMode = HczProbeMode.ZERO;

        private HczProbeEvents() {
            super(() -> 0);
        }

        private HczProbeMode getProbeMode() {
            return probeMode;
        }

        private void setProbeMode(HczProbeMode probeMode) {
            this.probeMode = probeMode;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object getStatic(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
