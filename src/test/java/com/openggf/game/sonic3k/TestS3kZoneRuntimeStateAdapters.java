package com.openggf.game.sonic3k;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.HczZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.IczZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMGZEvents;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kZoneRuntimeStateAdapters {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void aizAdapterMirrorsNamedStateFromExistingEvents() {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setBossFlag(true);
        events.setEventsFg5(true);

        AizZoneRuntimeState state = new AizZoneRuntimeState(0, PlayerCharacter.KNUCKLES, events);

        assertEquals("s3k", state.gameId());
        assertEquals(0, state.zoneIndex());
        assertEquals(0, state.actIndex());
        assertEquals(PlayerCharacter.KNUCKLES, state.playerCharacter());
        assertTrue(state.isBossFlagActive());
        assertTrue(state.isActTransitionFlagActive());
        assertFalse(state.isPostFireHazeActive());
        assertTrue(state.rightWallDeepProbePreservesPenetration());

        AizZoneRuntimeState actTwoState = new AizZoneRuntimeState(1, PlayerCharacter.KNUCKLES, events);
        assertFalse(actTwoState.rightWallDeepProbePreservesPenetration());
    }

    @Test
    void hczAdapterMirrorsTransitionFlagAndRoutine() {
        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(0);
        events.setEventsFg5(true);
        events.setDynamicResizeRoutine(8);

        HczZoneRuntimeState state = new HczZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS, events);

        assertEquals("s3k", state.gameId());
        assertEquals(1, state.zoneIndex());
        assertEquals(0, state.actIndex());
        assertEquals(PlayerCharacter.SONIC_AND_TAILS, state.playerCharacter());
        assertTrue(state.isActTransitionFlagActive());
        assertEquals(8, state.getDynamicResizeRoutine());
    }

    @Test
    void hczAdapterExposesBackgroundPlaneWindowInAllAct2BgStates() {
        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(1);
        HczZoneRuntimeState state = new HczZoneRuntimeState(
                1, PlayerCharacter.SONIC_AND_TAILS, events);

        // ROM keeps Plane B a 512px VDP window through every act-2 BG state:
        // DrawBGAsYouMove during the wall chase, Draw_PlaneVert* afterwards.
        for (int routine : new int[]{0, 4, 8, 0xC, 0x10}) {
            events.setAct2BgRoutine(routine);
            assertTrue(state.backgroundPlaneWindowActive(),
                    "window must stay active in act-2 BG routine 0x" + Integer.toHexString(routine));
        }
    }

    @Test
    void hczAdapterReportsNoBackgroundPlaneWindowInAct1() {
        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(0);
        HczZoneRuntimeState state = new HczZoneRuntimeState(
                0, PlayerCharacter.SONIC_AND_TAILS, events);

        assertFalse(state.backgroundPlaneWindowActive());
    }

    @Test
    void cnzAdapterCarriesPlayerCharacterAndBossBackgroundMode() {
        Sonic3kCNZEvents events = new Sonic3kCNZEvents();
        events.init(0);
        events.setEventsFg5(true);

        CnzZoneRuntimeState state = new CnzZoneRuntimeState(0, PlayerCharacter.TAILS_ALONE, events);

        assertEquals("s3k", state.gameId());
        assertEquals(3, state.zoneIndex());
        assertEquals(0, state.actIndex());
        assertEquals(PlayerCharacter.TAILS_ALONE, state.playerCharacter());
        assertTrue(state.isActTransitionFlagActive());
        assertNotNull(state.bossBackgroundMode());
    }

    @Test
    void mgzAdapterOwnsScrollRuntimePublications() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setEventsFg5(true);
        events.setBgRiseRoutine(8);
        events.setBgRiseOffset(0x120);

        MgzZoneRuntimeState state = new MgzZoneRuntimeState(1, PlayerCharacter.KNUCKLES, events);

        assertEquals("s3k", state.gameId());
        assertEquals(2, state.zoneIndex());
        assertEquals(1, state.actIndex());
        assertEquals(PlayerCharacter.KNUCKLES, state.playerCharacter());
        assertTrue(state.isActTransitionFlagActive());
        assertEquals(8, state.bgRiseRoutine());
        assertEquals(0x120, state.bgRiseOffset());

        assertFalse(state.hasBossBgScrollOffset());
        state.publishBossBgScrollOffset(0x13D80);
        assertTrue(state.hasBossBgScrollOffset());
        assertEquals(0x3D80, state.bossBgScrollOffset());

        state.requestScreenShakeOffset(2);
        state.requestScreenShakeOffset(6);
        state.requestScreenShakeOffset(3);
        assertEquals(6, state.consumeScreenShakeOffset());
        assertEquals(0, state.consumeScreenShakeOffset());
    }

    @Test
    void iczAdapterMirrorsPaletteAndBackgroundEventState() {
        Sonic3kICZEvents events = new Sonic3kICZEvents();
        events.init(0);
        events.setEventsFg5(true);
        events.setIndoorPaletteCyclingActive(true);
        events.forceAct1NormalBackgroundRoutineForTest();

        IczZoneRuntimeState state = new IczZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE, events);

        assertEquals("s3k", state.gameId());
        assertEquals(5, state.zoneIndex());
        assertEquals(0, state.actIndex());
        assertEquals(PlayerCharacter.SONIC_ALONE, state.playerCharacter());
        assertTrue(state.isActTransitionFlagActive());
        assertTrue(state.isIndoorPaletteCyclingActive());
        assertEquals(events.getIcz1BackgroundRoutine(), state.icz1BackgroundRoutine());
        assertTrue(state.isBackedBy(events));
    }

    @Test
    void mhzAdapterPublishesDeformAndObjectRuntimeState() {
        Sonic3kMHZEvents events = new Sonic3kMHZEvents();
        events.init(1);
        events.setActTransitionFlag(true);

        MhzZoneRuntimeState state = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_AND_TAILS, events);
        state.publishDeformOutputs(0x1200, 0x1215, 0x1231);
        state.publishMushroomCapPositionCounter(0x5A);
        state.publishEndBossArenaHelperXPositions(0x12345, new int[]{0x10, 0x20});

        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(state);

        assertSame(state, S3kRuntimeStates.currentMhz(registry).orElseThrow());
        assertEquals("s3k", state.gameId());
        assertEquals(Sonic3kZoneIds.ZONE_MHZ, state.zoneIndex());
        assertEquals(1, state.actIndex());
        assertEquals(PlayerCharacter.SONIC_AND_TAILS, state.playerCharacter());
        assertTrue(state.isActTransitionFlagActive());
        assertEquals(0x1200, state.publishedBgCameraX());
        assertEquals(0x15, state.backgroundLayer2Phase());
        assertEquals(0x11, state.backgroundLayer1Phase());
        assertEquals(0x5A, state.mushroomCapPositionCounter());
        assertEquals(0x2345, state.endBossArenaTallSupportX());
        assertEquals(0x10, state.endBossArenaSpikeX(0));
        assertEquals(0x20, state.endBossArenaSpikeX(1));
        assertTrue(state.isBackedBy(events));
    }

    @Test
    void lbzAdapterCarriesInteriorAlarmAndRollingDrumState() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE);
        state.setAlarmAnimationActive(true);
        state.setActiveInteriorLayoutMod(3);
        state.setInteriorLayoutMod3Disabled(true);
        state.setRollingDrumAngle(0, 0x123);
        state.setRollingDrumAngle(1, -1);
        state.requestScreenShakeOffset(2);
        state.requestScreenShakeOffset(6);
        state.requestScreenShakeOffset(3);
        state.requestLbz1KnucklesBoundaryPublish();

        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(state);

        assertSame(state, S3kRuntimeStates.currentLbz(registry).orElseThrow());
        assertEquals("s3k", state.gameId());
        assertEquals(Sonic3kZoneIds.ZONE_LBZ, state.zoneIndex());
        assertEquals(0, state.actIndex());
        assertEquals(PlayerCharacter.SONIC_ALONE, state.playerCharacter());
        assertTrue(state.isAlarmAnimationActive());
        assertEquals(0, state.getActiveInteriorLayoutMod());
        assertTrue(state.isInteriorLayoutMod3Disabled());
        assertEquals(0x23, state.getRollingDrumAngle(0));
        assertEquals(0xFF, state.getRollingDrumAngle(1));
        assertEquals(6, state.consumeScreenShakeOffset());
        assertEquals(0, state.consumeScreenShakeOffset());
        assertTrue(state.isLbz1KnucklesBoundaryPublishPending());

        LbzZoneRuntimeState restored = new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE);
        restored.restoreBytes(state.captureBytes());
        assertTrue(restored.isLbz1KnucklesBoundaryPublishPending(),
                "The pending object-to-camera-tail publication must survive rewind");
        restored.clearLbz1KnucklesBoundaryPublishPending();
        assertFalse(restored.isLbz1KnucklesBoundaryPublishPending());
    }
}
