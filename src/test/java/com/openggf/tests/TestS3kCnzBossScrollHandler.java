package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.sonic3k.scroll.SwScrlCnz;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kCnzBossScrollHandler {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void normalCnzDeformPublishesBothAnimatedTileInputs() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);

        SwScrlCnz handler = new SwScrlCnz();
        int[] hscroll = new int[224];

        handler.update(hscroll, 0x2000, 0x0300, 0, 0);

        CnzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
                .currentAs(CnzZoneRuntimeState.class)
                .orElseThrow();

        assertEquals(0x0A00, state.deformPhaseBgX());
        assertEquals(0x0E00, state.publishedBgCameraX());
    }

    @Test
    void timedCnz2ShakeOffsetsForegroundAndBackgroundScrollTogether() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 1);
        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.triggerScreenShake(0x14);
        events.update(1, 1);

        assertEquals(0, events.getScreenShakeOffsetY(),
                "CNZ2_ScreenEvent consumes the previous sample on the button-press frame");
        events.update(1, 2);

        SwScrlCnz handler = new SwScrlCnz();
        int[] hscroll = new int[224];
        int cameraY = 0x0A00;

        handler.update(hscroll, 0x45C0, cameraY, 1, 1);

        int shakeY = -5;
        int expectedBgY = (cameraY * 13 >> 7) + shakeY;
        assertEquals(cameraY + shakeY, handler.getVscrollFactorFG() & 0xFFFF,
                "CNZ2_ScreenEvent adds Screen_shake_offset to Camera_Y_pos_copy, so Plane A must shake");
        assertEquals(expectedBgY, handler.getVscrollFactorBG() & 0xFFFF,
                "CNZ1_Deform removes the shake before scaling and adds it back to Plane B");
        assertEquals(shakeY, handler.getShakeOffsetY(),
                "the same native shake sample must move sprites with both tile planes");
    }

    @Test
    void minibossBossScrollAddsPublishedVerticalTunnelOffset() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);
        events.setBossScrollState(0x0120, 0);

        SwScrlCnz handler = new SwScrlCnz();
        int[] hscroll = new int[224];

        handler.update(hscroll, 0x3200, 0x01C0, 0, 0);

        assertEquals(0x01E0, handler.getVscrollFactorBG() & 0xFFFF,
                "CNZ miniboss BG Y should include Events_bg+$08 so the tunnel scrolls vertically");
    }

    @Test
    void minibossBossScrollUsesCameraYMinus100PlusPublishedOffsetFormula() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);
        events.setBossScrollState(0x0040, 0);

        SwScrlCnz handler = new SwScrlCnz();
        int[] hscroll = new int[224];

        handler.update(hscroll, 0x3200, 0x0300, 0, 0);

        assertEquals(0x0240, handler.getVscrollFactorBG() & 0xFFFF,
                "CNZ miniboss BG Y formula is Camera_Y_pos - $100 + Events_bg+$08");
    }

    @Test
    void minibossBossScrollPublishesBackgroundCameraXForCollision() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);
        events.setBossScrollState(0x01C0, 0);

        SwScrlCnz handler = new SwScrlCnz();
        int[] hscroll = new int[224];

        handler.update(hscroll, 0x323B, 0x028C, 0, 0);

        assertEquals(0x02BB, handler.getBgCameraX(),
                "CNZ1_BossLevelScroll2 must expose Camera_X_pos_BG_copy = Camera_X_pos_copy - $2F80 "
                        + "for dual-path background collision (docs/skdisasm/sonic3k.asm:107721-107725)");
    }

    @Test
    void minibossBossScrollPrimesLiveBackgroundCameraXBeforeParallaxUpdate() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);
        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.forceBossBackgroundMode(Sonic3kCNZEvents.BossBackgroundMode.ACT1_MINIBOSS_PATH);
        GameServices.camera().setX((short) 0x323B);
        GameServices.gameState().setBackgroundCollisionFlag(true);

        SwScrlCnz handler = new SwScrlCnz();

        assertEquals(0x02BB, handler.getBgCameraX(),
                "S3K player physics can query background collision before the render parallax update; "
                        + "CNZ boss mode must still expose the ROM Camera_X_pos_BG_copy "
                        + "(docs/skdisasm/sonic3k.asm:107721-107725)");
    }
}
