package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.runtime.IczZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.scroll.SwScrlMhz;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.openggf.level.scroll.M68KMath.negWord;
import static com.openggf.level.scroll.M68KMath.packScrollWords;

class TestSonic3kMhzRuntimeStateRegistration {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void initLevelInstallsMhzRuntimeState() throws Exception {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        manager.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);

        ZoneRuntimeState current = GameServices.zoneRuntimeRegistry().current();
        assertInstanceOf(MhzZoneRuntimeState.class, current);
        assertEquals(Sonic3kZoneIds.ZONE_MHZ, current.zoneIndex());
        assertEquals(0, current.actIndex());
        assertEquals("s3k", current.gameId());
    }

    @Test
    void initLevelInstallsIczRuntimeState() throws Exception {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        manager.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);

        ZoneRuntimeState current = GameServices.zoneRuntimeRegistry().current();
        assertInstanceOf(IczZoneRuntimeState.class, current);
        assertEquals(Sonic3kZoneIds.ZONE_ICZ, current.zoneIndex());
        assertEquals(0, current.actIndex());
        assertEquals("s3k", current.gameId());
    }

    @Test
    void updateRuntimeStateKeepsInstalledMhzAndIczAdaptersBackedByCurrentEvents() throws Exception {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        manager.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        ZoneRuntimeState mhz = GameServices.zoneRuntimeRegistry().current();
        manager.ensureZoneRuntimeStateInstalled();
        assertEquals(mhz, GameServices.zoneRuntimeRegistry().current(),
                "MHZ runtime state should be recognized as current when backed by the active event instance");

        manager.initLevel(Sonic3kZoneIds.ZONE_ICZ, 0);
        ZoneRuntimeState icz = GameServices.zoneRuntimeRegistry().current();
        manager.ensureZoneRuntimeStateInstalled();
        assertEquals(icz, GameServices.zoneRuntimeRegistry().current(),
                "ICZ runtime state should be recognized as current when backed by the active event instance");
    }

    @Test
    void mhzScrollPublishesDeformOutputsForAnimatedTiles() throws Exception {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        ZoneRuntimeState current = GameServices.zoneRuntimeRegistry().current();
        SwScrlMhz handler = new SwScrlMhz();

        handler.update(new int[224], 0x0400, 0x0400, 0, 0);

        MhzZoneRuntimeState mhzState = assertInstanceOf(MhzZoneRuntimeState.class, current);
        assertEquals(0x0180, mhzState.publishedBgCameraX());
        assertEquals(0x0140, mhzState.middleBgCameraX());
        assertEquals(0x0100, mhzState.nearBgCameraX());
        assertTrue(mhzState.backgroundLayer1Phase() >= 0);
    }

    @Test
    void mhzScrollAppliesScreenShakeOffsetToBackgroundCameraCopy() throws Exception {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_MHZ, 0);
        Sonic3kMHZEvents events = manager.getMhzEvents();
        events.setScreenShakeOffsetForTest(4);
        ZoneRuntimeState current = GameServices.zoneRuntimeRegistry().current();
        SwScrlMhz handler = new SwScrlMhz();
        int[] hScroll = new int[224];

        handler.update(hScroll, 0x0400, 0x0400, 0, 0);

        MhzZoneRuntimeState mhzState = assertInstanceOf(MhzZoneRuntimeState.class, current);
        assertEquals(packScrollWords(negWord(0x0400), negWord(0x0182)), hScroll[0],
                "sub_554B8 subtracts Screen_shake_offset before MHZ_Deform X math, then adds it back to Camera_X_pos_BG_copy");
        assertEquals(0x0182, mhzState.publishedBgCameraX());
        assertEquals(0x013E, mhzState.middleBgCameraX());
        assertEquals(0x00FF, mhzState.nearBgCameraX());
    }

    @Test
    void rewindSnapshotRestoresMushroomCapPositionCounter() {
        MhzZoneRuntimeState state = new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS);
        state.publishDeformOutputs(0x1234, 0x2345, 0x3456);
        state.publishMushroomCapPositionCounter(0x56);

        byte[] snapshot = state.captureBytes();
        state.publishDeformOutputs(0, 0, 0);
        state.publishMushroomCapPositionCounter(0);

        state.restoreBytes(snapshot);

        assertEquals(0x1234, state.publishedBgCameraX());
        assertEquals(0x2345, state.middleBgCameraX());
        assertEquals(0x3456, state.nearBgCameraX());
        assertEquals(0x56, state.mushroomCapPositionCounter(),
                "The MHZ Anim_Counters+$F mirror must rewind with the runtime state so mushroom caps keep ROM phase");
    }
}
