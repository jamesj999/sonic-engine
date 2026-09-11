package com.openggf.game.sonic2;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.events.Sonic2WFZEvents;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.level.ParallaxManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2WfzBgBridgeIntegration {

    private static final int CAMERA_X = 0x2800;
    private static final int CAMERA_Y = 0x5A0;
    private static final int ACT = 0;

    private Camera camera;
    private Sonic2LevelEventManager levelEvents;
    private Sonic2WFZEvents wfzEvents;
    private ParallaxManager parallax;

    @BeforeEach
    void setUp() throws IOException {
        camera = GameServices.camera();
        camera.setX((short) CAMERA_X);
        camera.setY((short) CAMERA_Y);

        levelEvents = (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_WFZ, ACT);
        levelEvents.update();

        parallax = GameServices.parallax();
        parallax.load(TestEnvironment.currentRom());
        parallax.update(Sonic2LevelEventManager.ZONE_WFZ, ACT, camera, 0, 0);

        levelEvents.setEventRoutine(6);
        wfzEvents = levelEvents.getWfzEvents();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void wfzEndingEventPositionsReachRenderedScrollThroughRuntimeRegistry() throws IOException {
        advanceEndingScroll(20);

        assertNotEquals(0, wfzEvents.getBgXOffset());
        assertNotEquals(0, wfzEvents.getBgYOffset());
        assertNotEquals(CAMERA_X, wfzEvents.getBgXPos());
        assertNotEquals(CAMERA_Y, wfzEvents.getBgYPos());

        parallax.update(Sonic2LevelEventManager.ZONE_WFZ, ACT, camera, 21, 0);

        assertEquals((short) wfzEvents.getBgYPos(), parallax.getVscrollFactorBG());

        byte[] segments = new ParallaxTables(TestEnvironment.currentRom()).getWfzTransArray();
        int staticLayerScanline = firstStaticLayerScanline(segments, wfzEvents.getBgYPos());
        assertTrue(staticLayerScanline >= 0, "Expected a visible WFZ static/ship layer scanline");
        short renderedBgScroll = (short) parallax.getHScroll()[staticLayerScanline];
        assertEquals(negateWord(wfzEvents.getBgXPos()), renderedBgScroll);
    }

    @Test
    void rewindRestoreImmediatelyRecomputesRenderedVscrollFromRestoredEventState() {
        advanceEndingScroll(20);
        LevelEventSnapshot snapshot = levelEvents.capture();
        int restoredBgY = wfzEvents.getBgYPos();

        advanceEndingScroll(12);
        assertNotEquals(restoredBgY, wfzEvents.getBgYPos());
        levelEvents.restore(snapshot);

        parallax.update(Sonic2LevelEventManager.ZONE_WFZ, ACT, camera, 21, 0);

        assertEquals(restoredBgY, wfzEvents.getBgYPos());
        assertEquals((short) restoredBgY, parallax.getVscrollFactorBG(),
                "The first render pass after restore must use the restored live event state");
    }

    private void advanceEndingScroll(int frames) {
        for (int i = 0; i < frames; i++) {
            levelEvents.update();
        }
    }

    private static int firstStaticLayerScanline(byte[] segments, int bgYPos) {
        int arrayPos = 0;
        int remainingBgY = bgYPos & 0x7FF;
        while (arrayPos + 1 < segments.length) {
            remainingBgY -= segments[arrayPos] & 0xFF;
            if (remainingBgY < 0) {
                break;
            }
            arrayPos += 2;
        }

        int linesInSegment = -remainingBgY;
        int screenLine = 0;
        while (screenLine < ParallaxManager.VISIBLE_LINES && arrayPos + 1 < segments.length) {
            int layerIndex = (segments[arrayPos + 1] & 0xFF) >> 2;
            if (layerIndex == 0 || layerIndex == 1) {
                return screenLine;
            }
            screenLine += Math.min(linesInSegment, ParallaxManager.VISIBLE_LINES - screenLine);
            arrayPos += 2;
            if (arrayPos + 1 < segments.length) {
                linesInSegment = segments[arrayPos] & 0xFF;
            }
        }
        return -1;
    }

    private static short negateWord(int value) {
        return (short) -(short) value;
    }
}
