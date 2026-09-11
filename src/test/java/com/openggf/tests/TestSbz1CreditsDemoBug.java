package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for S1 SBZ1 credits demo replay.
 *
 * Replays the SBZ1 credits demo input sequence and verifies the engine
 * completes it without crashing or Sonic dying.
 *
 * History: this test originally checked for a bug where Sonic's speeds were
 * all reset to 0 at Y=889. After trace-replay-verified physics corrections
 * (commit 8a894f39b), the demo trajectory changed â€” Sonic no longer reaches
 * Y=889 with the corrected collision model. The test now verifies the demo
 * replays cleanly and Sonic moves from the start position.
 *
 * Demo input sequence (from ROM at 0x5E4C):
 *   Idle 37f, Left 82f, Idle 37f, Left 35f, Idle 231f, Left 104f, Idle 13f, Right 110f
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestSbz1CreditsDemoBug {

    private static final int ZONE_SBZ = 5;
    private static final int ACT_1 = 0;
    private static final short START_X = 0x1570;
    private static final short START_Y = 0x016C;

    /**
     * Demo input pairs: [buttonMask, duration].
     * Button bits: 0x04=Left, 0x08=Right, 0x00=None.
     */
    private static final int[][] DEMO_INPUTS = {
        {0x00, 0x25}, // Idle 37 frames
        {0x04, 0x52}, // Left 82 frames
        {0x00, 0x25}, // Idle 37 frames
        {0x04, 0x23}, // Left 35 frames
        {0x00, 0xE7}, // Idle 231 frames
        {0x04, 0x68}, // Left 104 frames
        {0x00, 0x0D}, // Idle 13 frames
        {0x08, 0x6E}, // Right 110 frames
    };
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_SBZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    public void demoFadeStartsWithThePublishedForegroundScroll(int demo) throws Exception {
        // A preceding demo leaves the reused player and movement-animation
        // latch live. Exercise the real movement path before loading the next.
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        var provider = org.mockito.Mockito.mock(com.openggf.game.EndingProvider.class);
        org.mockito.Mockito.when(provider.getDemoZone()).thenReturn(
                com.openggf.game.sonic1.credits.Sonic1CreditsDemoData.DEMO_ZONE[demo]);
        org.mockito.Mockito.when(provider.getDemoAct()).thenReturn(
                com.openggf.game.sonic1.credits.Sonic1CreditsDemoData.DEMO_ACT[demo]);
        org.mockito.Mockito.when(provider.getDemoStartX()).thenReturn(
                com.openggf.game.sonic1.credits.Sonic1CreditsDemoData.START_X[demo]);
        org.mockito.Mockito.when(provider.getDemoStartY()).thenReturn(
                com.openggf.game.sonic1.credits.Sonic1CreditsDemoData.START_Y[demo]);
        if (demo == 3) {
            // EndDemo_LampVar: the one credits demo that restores saved camera state.
            org.mockito.Mockito.when(provider.getDemoLamppostState()).thenReturn(
                    new com.openggf.game.DemoLamppostState(0xA00, 0x62C, 13,
                            0x957, 0x5CC, 0x800, 0x308, 1));
        }
        var loop = new com.openggf.GameLoop();
        var field = com.openggf.GameLoop.class.getDeclaredField("endingProvider");
        field.setAccessible(true);
        field.set(loop, provider);
        var load = com.openggf.GameLoop.class.getDeclaredMethod("loadEndingDemoZone");
        load.setAccessible(true);
        load.invoke(loop);

        var player = fixture.sprite();
        System.out.printf("DEMO %d PLAYER: anim=%02X mapping=%02X air=%s speed=%04X%n",
                demo, player.getAnimationId(), player.getMappingFrame(), player.getAir(),
                player.getGSpeed() & 0xffff);
        assertEquals(5, player.getAnimationId(),
                "Level_LoadObj executes Sonic_Move with zero input/inertia before the fade: id_Wait");
        assertEquals(1, player.getMappingFrame(), "SonAni_Wait starts with fr_Stand (1)");
        int initialAnimationTick = player.getAnimationTick();
        int initialAnimationFrame = player.getAnimationFrameIndex();
        var camera = com.openggf.game.GameServices.camera();
        var parallax = com.openggf.game.GameServices.parallax();
        System.out.printf("DEMO %d BEFORE FADE: camera=(%04X,%04X) render=(%04X,%04X) FG VSRAM=%04X%n",
                demo, camera.getX() & 0xffff, camera.getY() & 0xffff,
                camera.getXWithShake() & 0xffff, camera.getYWithShake() & 0xffff,
                parallax.getVscrollFactorFG() & 0xffff);
        assertEquals(camera.getY(), parallax.getVscrollFactorFG(),
                "Level's pre-fade DeformLayers must publish v_scrposy_vdp before gameplay starts");
        assertEquals(camera.getX(), camera.getXWithShake());
        assertEquals(camera.getY(), camera.getYWithShake());
        int[] initialScroll = parallax.getHScroll().clone();
        short initialY = parallax.getVscrollFactorFG();
        var update = com.openggf.GameLoop.class.getDeclaredMethod("updateEndingCreditsDemo");
        update.setAccessible(true);
        // Frozen PalFadeIn_Alt frames must retain the prepared viewport.
        for (int frame = 0; frame < 22; frame++) {
            update.invoke(loop);
            assertEquals(1, player.getMappingFrame(), "PalFadeIn_Alt does not execute Sonic_Animate");
            assertEquals(initialAnimationTick, player.getAnimationTick());
            assertEquals(initialAnimationFrame, player.getAnimationFrameIndex());
            assertEquals(initialY, parallax.getVscrollFactorFG());
            assertArrayEquals(initialScroll, parallax.getHScroll());
        }
    }

    @Test
    public void testSonicDoesNotGetStuckInSbz1Tube() {
        AbstractPlayableSprite sprite = fixture.sprite();

        // Set the credits demo start position
        sprite.setCentreX(START_X);
        sprite.setCentreY(START_Y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(true);  // Start airborne to settle onto terrain

        fixture.camera().updatePosition(true);

        // Let Sonic settle onto the ground
        for (int i = 0; i < 30; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        int settledX = sprite.getX();
        int settledY = sprite.getY();
        assertFalse(sprite.getAir(), "Sonic should settle onto ground");

        // Replay the demo input sequence
        int totalFrame = 0;
        int minX = settledX;

        for (int[] pair : DEMO_INPUTS) {
            int buttons = pair[0];
            int duration = pair[1];

            boolean left = (buttons & 0x04) != 0;
            boolean right = (buttons & 0x08) != 0;
            boolean up = (buttons & 0x01) != 0;
            boolean down = (buttons & 0x02) != 0;
            boolean jump = (buttons & 0x70) != 0;

            for (int f = 0; f < duration; f++) {
                fixture.stepFrame(up, down, left, right, jump);
                totalFrame++;

                int x = sprite.getX();
                if (x < minX) {
                    minX = x;
                }
            }
        }

        // Verify Sonic moved during the demo (the leftward inputs should move him)
        assertTrue(minX < settledX - 50, "Sonic should have moved left during demo (minX=" + minX
                + " settledX=" + settledX + ")");

        // Verify Sonic didn't die
        assertFalse(sprite.getDead(), "Sonic should not die during SBZ1 demo");
    }
}
