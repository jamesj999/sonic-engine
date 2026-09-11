package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents.BossBackgroundMode;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.sonic3k.scroll.Sonic3kScrollHandlerProvider;
import com.openggf.game.sonic3k.scroll.Sonic3kZoneConstants;
import com.openggf.game.sonic3k.scroll.SwScrlCnz;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openggf.level.scroll.M68KMath.unpackBG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic3kCnzScroll {

    private static final int ZONE_CNZ = Sonic3kZoneConstants.ZONE_CNZ;
    private static final int ACT_1 = 0;
    private static final int ACT_2 = 1;
    private static final int BOSS_BG_X_OFFSET = 0x2F80;
    private static final int BOSS_BG_Y_OFFSET = 0x100;

    private Camera camera;
    private Sonic3kLevelEventManager levelEvents;

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
        SessionManager.clear();

        Sonic3kGameModule module = new Sonic3kGameModule();
        GameModuleRegistry.setCurrent(module);
        SessionManager.openGameplaySession(module);
        camera = TestEnvironment.activeGameplayMode().getCamera();
        camera.setLevelStarted(true);

        levelEvents = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        levelEvents.resetState();
        camera.setShakeOffsets(0, 0);
    }

    @AfterEach
    public void tearDown() {
        if (levelEvents != null) {
            levelEvents.resetState();
        }
        if (camera != null) {
            camera.setShakeOffsets(0, 0);
        }
        SessionManager.clear();
    }

    @Test
    public void bgXUsesRomMultiBandSpeedsAtTopOfBackground() {
        levelEvents.initLevel(ZONE_CNZ, ACT_1);
        SwScrlCnz handler = new SwScrlCnz();
        int[] buffer = new int[M68KMath.VISIBLE_LINES];

        int cameraX = 0x0400;
        handler.update(buffer, cameraX, 0x0000, 0, ACT_1);

        assertBand(buffer, 0, 0x80, -(cameraX / 32));
        assertBand(buffer, 0x80, 0x30, -(cameraX / 8));
        assertBand(buffer, 0xB0, M68KMath.VISIBLE_LINES - 0xB0, -(cameraX / 4));
        assertEquals(BossBackgroundMode.NORMAL, cnzState().bossBackgroundMode());
        assertEquals((short) 0x0000, handler.getVscrollFactorBG());
    }

    @Test
    public void bgYSelectsLaterRomDeformBands() {
        levelEvents.initLevel(ZONE_CNZ, ACT_1);
        SwScrlCnz handler = new SwScrlCnz();
        int[] buffer = new int[M68KMath.VISIBLE_LINES];
        int cameraX = 0x0400;

        // floor(1733 * 13 / 128) = $B0, the start of the 1/4-speed band.
        handler.update(buffer, cameraX, 1733, 0, ACT_1);
        assertEquals((short) -(cameraX / 4), unpackBG(buffer[0]));
        assertEquals((short) -(cameraX * 7 / 16), unpackBG(buffer[0x60]));

        // floor(4570 * 13 / 128) = $1D0, the terminator/remainder 1/2-speed band.
        handler.update(buffer, cameraX, 4570, 0, ACT_1);
        assertEquals((short) -(cameraX / 2), unpackBG(buffer[0]));
        assertEquals((short) -(cameraX / 2), unpackBG(buffer[M68KMath.VISIBLE_LINES - 1]));
    }

    @Test
    public void bgYFollowsCnzDeformMathWithShakeApplied() {
        levelEvents.initLevel(ZONE_CNZ, ACT_1);
        // ROM: Screen_shake_flag = $14. The CNZ shake now flows from the event
        // layer (mirroring AIZ) into the scroll handler and the shared
        // ParallaxManager -> Camera propagation, not a direct camera write.
        Sonic3kCNZEvents events = levelEvents.getCnzEventsForTest();
        events.triggerScreenShake(0x14);
        events.update(ACT_1, 0);
        assertEquals(0, events.getScreenShakeOffsetY(),
                "the trigger frame renders the preceding Screen_shake_offset sample");
        events.update(ACT_1, 1);

        SwScrlCnz handler = new SwScrlCnz();
        int shakeY = handler.getShakeOffsetY();
        assertNotEquals(0, shakeY, "screen shake should be active after triggering");

        int[] buffer = new int[M68KMath.VISIBLE_LINES];
        int cameraY = 0x0400;
        handler.update(buffer, 0x0400, cameraY, 0, ACT_1);

        int expected = (cameraY * 13 / 128) + shakeY;
        assertEquals((short) expected, handler.getVscrollFactorBG());
    }

    @Test
    public void act2UsesSameDeformMathAsAct1() {
        SwScrlCnz handler = new SwScrlCnz();
        int[] act1Buffer = new int[M68KMath.VISIBLE_LINES];
        int[] act2Buffer = new int[M68KMath.VISIBLE_LINES];

        levelEvents.initLevel(ZONE_CNZ, ACT_1);
        camera.setShakeOffsets(0, 3);
        handler.update(act1Buffer, 0x1800, 0x0400, 11, ACT_1);
        short act1BgY = handler.getVscrollFactorBG();

        levelEvents.initLevel(ZONE_CNZ, ACT_2);
        camera.setShakeOffsets(0, 3);
        handler.update(act2Buffer, 0x1800, 0x0400, 11, ACT_2);

        assertEquals(act1BgY, handler.getVscrollFactorBG());
        assertArrayEquals(act1Buffer, act2Buffer);
    }

    @Test
    public void act2ScreenEventAppliesRomYStartBandsFromPlayerX() {
        levelEvents.initLevel(ZONE_CNZ, ACT_2);
        camera.setFocusedSprite(new Sonic("sonic", (short) 0, (short) 0));
        camera.getFocusedSprite().setCentreX((short) 0x093F);
        camera.setMinY((short) 0);

        cnzEvents().update(ACT_2, 0);

        assertEquals((short) 0x0580, camera.getMinY(),
                "CNZ2_ScreenEvent sets Camera_min_Y_pos=$580 while P1 x_pos is before $940");

        camera.getFocusedSprite().setCentreX((short) 0x0940);
        cnzEvents().update(ACT_2, 1);

        assertEquals((short) 0, camera.getMinY(),
                "CNZ2_ScreenEvent restores Camera_min_Y_pos=0 once P1 reaches $940");

        camera.setMinY((short) 0x0120);
        camera.getFocusedSprite().setCentreX((short) 0x4600);
        cnzEvents().update(ACT_2, 2);

        assertEquals((short) 0x0120, camera.getMinY(),
                "CNZ2_ScreenEvent leaves Camera_min_Y_pos untouched once P1 reaches $4600");
    }

    @Test
    public void bossBackgroundModeUsesBossScrollPathForEarlyAndLateRefreshPhases() {
        levelEvents.initLevel(ZONE_CNZ, ACT_1);
        SwScrlCnz handler = new SwScrlCnz();
        int[] defaultBuffer = new int[M68KMath.VISIBLE_LINES];
        int[] minibossBuffer = new int[M68KMath.VISIBLE_LINES];
        int[] postBossBuffer = new int[M68KMath.VISIBLE_LINES];

        int cameraX = 0x0400;
        int cameraY = 0x0200;
        short defaultBg = (short) -(cameraX / 32);

        handler.update(defaultBuffer, cameraX, cameraY, 0, ACT_1);
        assertEquals(BossBackgroundMode.NORMAL, cnzState().bossBackgroundMode());
        assertEquals(defaultBg, unpackBG(defaultBuffer[0]));
        assertEquals((short) -(cameraX * 7 / 16), unpackBG(defaultBuffer[M68KMath.VISIBLE_LINES - 1]));

        camera.setX((short) 0x3000);
        cnzEvents().update(ACT_1, 0);
        assertEquals(BossBackgroundMode.ACT1_MINIBOSS_PATH, cnzState().bossBackgroundMode());
        handler.update(minibossBuffer, cameraX, cameraY, 0, ACT_1);

        short bossBg = (short) -(cameraX - BOSS_BG_X_OFFSET);
        short bossVscroll = (short) (cameraY - BOSS_BG_Y_OFFSET);
        assertEquals(bossBg, unpackBG(minibossBuffer[0]));
        assertEquals(bossBg, unpackBG(minibossBuffer[M68KMath.VISIBLE_LINES - 1]));
        assertEquals(bossVscroll, handler.getVscrollFactorBG());
        assertNotEquals(defaultBg, unpackBG(minibossBuffer[0]));

        cnzEvents().setEventsFg5(true);
        cnzEvents().update(ACT_1, 1);
        assertEquals(BossBackgroundMode.ACT1_POST_BOSS, cnzState().bossBackgroundMode());
        handler.update(postBossBuffer, cameraX, cameraY, 0, ACT_1);

        assertEquals(bossBg, unpackBG(postBossBuffer[0]));
        assertEquals(bossBg, unpackBG(postBossBuffer[M68KMath.VISIBLE_LINES - 1]));
        assertEquals(bossVscroll, handler.getVscrollFactorBG());
        assertArrayEquals(minibossBuffer, postBossBuffer);
    }

    @Test
    public void providerReturnsCnzHandlerForCnzZone() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());

        ZoneScrollHandler handler = provider.getHandler(ZONE_CNZ);
        assertNotNull(handler);
        assertTrue(handler instanceof SwScrlCnz);
    }

    private Sonic3kCNZEvents cnzEvents() {
        return levelEvents.getCnzEvents();
    }

    private static void assertBand(int[] buffer, int start, int count, int expectedBg) {
        for (int line = start; line < start + count; line++) {
            assertEquals((short) expectedBg, unpackBG(buffer[line]), "BG scroll at line " + line);
        }
    }

    private CnzZoneRuntimeState cnzState() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(CnzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("CNZ runtime state should be installed"));
    }
}
