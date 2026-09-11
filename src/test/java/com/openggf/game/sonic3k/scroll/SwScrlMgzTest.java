package com.openggf.game.sonic3k.scroll;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.data.Rom;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.level.scroll.ZoneScrollHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SwScrlMgzTest {
    private GameModule previousModule;

    @BeforeEach
    void setUpRuntime() {
        previousModule = GameModuleRegistry.getCurrent();
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDownRuntime() {
        SessionManager.clear();
        if (previousModule != null) {
            GameModuleRegistry.setCurrent(previousModule);
        } else {
            GameModuleRegistry.reset();
        }
    }

    @Test
    public void act1MatchesExactPackedScrollAndOffsetBounds() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] hScroll = new int[224];

        handler.update(hScroll, 0x1200, 0x0400, 1, 0);

        assertEquals(0, handler.getVscrollFactorBG());
        assertEquals(3456, handler.getMinScrollOffset());
        assertEquals(4356, handler.getMaxScrollOffset());
        assertEquals(-301924604, hScroll[0]);
        assertEquals(-301924604, hScroll[15]);
        assertEquals(-301924676, hScroll[16]);
        assertEquals(-301924676, hScroll[19]);
        assertEquals(-301924748, hScroll[20]);
        assertEquals(-301924748, hScroll[23]);
        assertEquals(-301924820, hScroll[24]);
        assertEquals(-301924820, hScroll[31]);
        assertEquals(-301924892, hScroll[32]);
        assertEquals(-301924892, hScroll[39]);
        assertEquals(-301924928, hScroll[40]);
        assertEquals(-301925504, hScroll[223]);
    }

    @Test
    public void act2MatchesExactScatterFilledPackedScrollAndOffsetBounds() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] hScroll = new int[224];

        handler.update(hScroll, 0x1200, 0x0200, 1, 1);

        assertEquals(96, handler.getVscrollFactorBG());
        assertEquals(2304, handler.getMinScrollOffset());
        assertEquals(4522, handler.getMaxScrollOffset());
        assertEquals(-301924772, hScroll[0]);
        assertEquals(-301924772, hScroll[7]);
        assertEquals(-301924535, hScroll[8]);
        assertEquals(-301924944, hScroll[16]);
        assertEquals(-301924675, hScroll[32]);
        assertEquals(-301924761, hScroll[40]);
        assertEquals(-301924589, hScroll[48]);
        assertEquals(-301924718, hScroll[64]);
        assertEquals(-301924449, hScroll[72]);
        assertEquals(-301924492, hScroll[80]);
        assertEquals(-301924438, hScroll[88]);
        assertEquals(-301924632, hScroll[93]);
        assertEquals(-301924944, hScroll[136]);
        assertEquals(-301925224, hScroll[148]);
        assertEquals(-301925504, hScroll[154]);
        assertEquals(-301925784, hScroll[160]);
        assertEquals(-301926096, hScroll[168]);
        assertEquals(-301926656, hScroll[200]);
        assertEquals(-301926656, hScroll[223]);
    }

    @Test
    public void mgz1CloudDriftIsFrameDerivedAndRewindSafe() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] frame2 = new int[224];
        int[] frameLater = new int[224];
        int[] frame2Again = new int[224];

        // Prime so the initial resetActState (lastActId -1 -> 0) has already run;
        // subsequent same-act frames differ only by the frame-derived cloud phase.
        handler.update(new int[224], 0x1200, 0x0400, 1, 0);

        // MGZ1 clouds drift ~0x500 (sub-pixel) per frame, so drift only shows in
        // the integer scroll words after enough frames to cross a pixel boundary.
        handler.update(frame2, 0x1200, 0x0400, 2, 0);
        handler.update(frameLater, 0x1200, 0x0400, 200, 0);
        assertFalse(java.util.Arrays.equals(frame2, frameLater),
                "MGZ1 clouds should auto-scroll across a wide frame span");

        // Drive further forward, then re-derive frame 2: the cloud scroll must
        // return to its frame-2 value, not keep drifting off the call count.
        for (int frame = 201; frame <= 260; frame++) {
            handler.update(new int[224], 0x1200, 0x0400, frame, 0);
        }
        handler.update(frame2Again, 0x1200, 0x0400, 2, 0);
        assertArrayEquals(frame2, frame2Again,
                "Re-deriving frame 2 must reproduce its cloud scroll exactly (rewind-safe)");
    }

    @Test
    public void actStateReArmsCloudAnchorOnFrameZeroAndActChange() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] warm = new int[224];
        int[] afterReset = new int[224];
        int[] fresh = new int[224];

        // A fresh handler's very first act-1 frame.
        handler.update(fresh, 0x1200, 0x0400, 1, 0);

        // Warm the anchor with several frames, switch act, then return to act 1
        // at frameCounter 0 — resetActState must re-arm so the cloud phase
        // restarts from the new anchor instead of carrying the old drift.
        for (int frame = 2; frame <= 20; frame++) {
            handler.update(warm, 0x1200, 0x0400, frame, 0);
        }
        handler.update(new int[224], 0x1200, 0x0200, 5, 1);   // act change
        handler.update(afterReset, 0x1200, 0x0400, 0, 0);     // frameCounter 0 re-entry

        // Frame-0 act-1 render re-anchors; its cloud phase matches a first frame.
        int[] freshFrameZero = new int[224];
        new SwScrlMgz().update(freshFrameZero, 0x1200, 0x0400, 0, 0);
        assertArrayEquals(freshFrameZero, afterReset,
                "resetActState at frameCounter 0 should re-anchor the cloud phase");
    }

    @Test
    public void initClearsMutableEventStateForLevelReentry() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] hScroll = new int[224];

        handler.setBgRiseState(8, 0x200);
        handler.setBossBgScrollOffset(0x3D80);
        handler.setScreenShakeOffset(3);
        handler.update(hScroll, 0x3500, 0x0850, 1, 1);

        handler.init(1, 0x1200, 0x0200);
        handler.update(hScroll, 0x1200, 0x0200, 1, 1);

        assertEquals(0, handler.getShakeOffsetY());
        assertEquals(0, handler.getBgCameraX(),
                "MGZ2 normal deformation clears Camera_X_pos_BG_copy after level re-entry");
        assertEquals(96, handler.getVscrollFactorBG(),
                "MGZ handler init should clear BG rise overrides between level loads");
    }

    @Test
    public void act2StateEightKeepsCloudParallaxAtTopOfScreen() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] rising = new int[224];
        int cameraX = 0x3500;
        int cameraY = 0x0850;
        short lockedTerrainScroll = (short) -(short) (cameraX - 0x3200);

        handler.setBgRiseState(8, 0x200);
        handler.update(rising, cameraX, cameraY, 1, 1);

        Set<Short> uniqueBgScrolls = new HashSet<>();
        for (int packed : rising) {
            uniqueBgScrolls.add(unpackBgScroll(packed));
        }
        assertTrue(uniqueBgScrolls.size() > 1,
                "MGZ2 state 8 should not flatten every scanline to one BG scroll value");
        assertNotEquals(lockedTerrainScroll, unpackBgScroll(rising[0]),
                "MGZ2 state 8 should keep the cloud band on parallax instead of the locked terrain scroll");
        assertTrue(uniqueBgScrolls.contains(lockedTerrainScroll),
                "MGZ2 state 8 should still include the locked terrain scroll value somewhere in the frame");
    }

    @Test
    public void bossBgScrollOffsetDrivesAct2ParallaxWhenForegroundCameraIsFixed() {
        SwScrlMgz initialBossScroll = new SwScrlMgz();
        SwScrlMgz advancedBossScroll = new SwScrlMgz();
        int[] initial = new int[224];
        int[] advanced = new int[224];

        initialBossScroll.setBossBgScrollOffset(0x3C80);
        initialBossScroll.update(initial, 0x3C80, 0x0600, 1, 1);

        advancedBossScroll.setBossBgScrollOffset(0x3D80);
        advancedBossScroll.update(advanced, 0x3C80, 0x0600, 1, 1);

        assertNotEquals(unpackBgScroll(initial[32]), unpackBgScroll(advanced[32]),
                "ROM MGZ2SE_MoveBG substitutes Events_bg+$0C for Camera_X_pos_copy, so the BG should scroll even while the foreground camera is fixed");
    }

    @Test
    public void bossBgScrollOffsetKeepsRomZeroBackgroundCameraCopy() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] hScroll = new int[224];

        handler.setBossBgScrollOffset(0x3D80);
        handler.update(hScroll, 0x3C80, 0x0600, 1, 1);

        assertEquals(0, handler.getBgCameraX(),
                "ROM loc_23D24C substitutes Events_bg+$0C only for HScroll_table math; "
                        + "loc_23D220 keeps Camera_X_pos_BG_copy cleared for the boss sky");
    }

    @Test
    public void act2StateEightKeepsVdpSizedBgPeriod() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] rising = new int[224];

        handler.setBgRiseState(8, 0x200);
        handler.update(rising, 0x3500, 0x0850, 1, 1);

        assertEquals(512, handler.getBgPeriodWidth(),
                "MGZ2 state 8 should keep the normal 32-cell VDP plane width; the ROM only changes HScroll values, not the plane period");
    }

    @Test
    public void screenShakeOffsetsBothForegroundAndBackgroundVScrollInStateEight() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] rising = new int[224];

        handler.setBgRiseState(8, 0x200);
        handler.setScreenShakeOffset(3);
        handler.update(rising, 0x3500, 0x0850, 1, 1);
        handler.update(rising, 0x3500, 0x0850, 2, 1);

        assertEquals(0x0853, handler.getVscrollFactorFG() & 0xFFFF);
        assertEquals(0x0163, handler.getVscrollFactorBG() & 0xFFFF,
                "MGZ state 8 BG should pick up the same shake offset as the foreground so the cloud/fake-floor plane moves with the camera rumble");
    }

    @Test
    public void sameFrameZeroShakeRequestDoesNotClobberEarlierActiveShake() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] rising = new int[224];

        handler.setBgRiseState(8, 0x200);
        handler.setScreenShakeOffset(3);
        handler.setScreenShakeOffset(0);
        handler.update(rising, 0x3500, 0x0850, 1, 1);
        assertEquals(0, handler.getShakeOffsetY(),
                "ShakeScreen_Setup should publish its newly computed offset on the next frame");

        handler.update(rising, 0x3500, 0x0850, 2, 1);

        assertEquals(3, handler.getShakeOffsetY(),
                "MGZ should preserve the strongest same-frame shake request so MGZ2 events do not clear an active dash-trigger platform shake");

        handler.update(rising, 0x3500, 0x0850, 3, 1);

        assertEquals(0, handler.getShakeOffsetY(),
                "Without a new request on the next frame, MGZ shake should clear normally");
    }

    @Test
    public void act2CloudsStayFrozenWhenStateEightIsReenteredAfterAfterMove() {
        SwScrlMgz handler = new SwScrlMgz();
        int[] afterMove = new int[224];
        int[] reenteredStateEight = new int[224];
        int[] reenteredStateEightNextFrame = new int[224];

        handler.setBgRiseState(0xC, 0x1D0);
        handler.update(afterMove, 0x3900, 0x07F0, 1, 1);

        handler.setBgRiseState(8, 0x1D0);
        handler.update(reenteredStateEight, 0x3A40, 0x0800, 2, 1);
        handler.update(reenteredStateEightNextFrame, 0x3A40, 0x0800, 3, 1);

        assertEquals(unpackBgScroll(reenteredStateEight[0]), unpackBgScroll(reenteredStateEightNextFrame[0]),
                "Re-entering MGZ2 state 8 after after-move should keep the cloud band frozen instead of restarting drift on subsequent frames");
    }

    @Test
    public void setBgRiseStatePrimesLiveBgCollisionStateBeforeParallaxUpdate() {
        SwScrlMgz handler = new SwScrlMgz();
        GameServices.camera().setX((short) 0x3500);
        GameServices.camera().setY((short) 0x0850);

        handler.setBgRiseState(8, 0x20);

        assertEquals(0x300, handler.getBgCameraX(),
                "state 8 should prime the BG camera X immediately so pre-physics collision sees the locked terrain band");
        assertEquals((short) -0x80, handler.getVscrollFactorBG(),
                "state 8 should prime BG VScroll immediately so pre-physics collision uses the lifted BG plane");
    }

    @Test
    public void providerRoutesMgzToDedicatedHandler() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());

        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneConstants.ZONE_MGZ);
        assertNotNull(handler);
        assertTrue(handler instanceof SwScrlMgz);
    }

    private short unpackBgScroll(int packedScrollWord) {
        return (short) packedScrollWord;
    }
}
