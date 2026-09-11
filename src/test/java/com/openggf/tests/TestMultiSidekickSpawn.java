package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration smoke tests verifying multi-sidekick spawning, chain wiring,
 * following behavior, and chain healing in a real EHZ1 level.
 *
 * Requires the Sonic 2 ROM to be present; tests are skipped if unavailable.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestMultiSidekickSpawn {
    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_EHZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic mainPlayer;
    private AbstractPlayableSprite[] sidekicks;
    private SidekickCpuController[] controllers;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        mainPlayer = (Sonic) fixture.sprite();
        mainPlayer.setCentreX((short) 96);
        mainPlayer.setCentreY((short) 655);
        mainPlayer.setAir(false);

        // Manually create 3 sidekick sprites and chain their leaders,
        // mirroring what Engine's spawn loop would do with config
        // SIDEKICK_CHARACTER_CODE = "tails,sonic,sonic".
        sidekicks = new AbstractPlayableSprite[3];
        controllers = new SidekickCpuController[3];

        sidekicks[0] = new Tails("tails_p2", (short) 56, (short) 655);
        sidekicks[1] = new Sonic("sonic_p3", (short) 36, (short) 655);
        sidekicks[2] = new Sonic("sonic_p4", (short) 16, (short) 655);

        SpriteManager sm = GameServices.sprites();
        for (int i = 0; i < 3; i++) {
            sidekicks[i].setCpuControlled(true);

            AbstractPlayableSprite leader = (i == 0) ? mainPlayer : sidekicks[i - 1];
            controllers[i] = new SidekickCpuController(sidekicks[i], leader);
            controllers[i].setSidekickCount(3);
            sidekicks[i].setCpuController(controllers[i]);

            String charName = (i == 0) ? "tails" : "sonic";
            sm.addSprite(sidekicks[i], charName);
        }
    }

    // ========== Test 1: Multi-sidekick spawn verification ==========

    @Test
    public void testMultiSidekickSpawn() {
        List<AbstractPlayableSprite> registered = GameServices.sprites().getSidekicks();
        assertEquals(3, registered.size(), "Should have 3 sidekicks registered");

        // Verify chain leader assignment
        assertSame(mainPlayer, controllers[0].getLeader(), "sidekick[0]'s leader should be main player");
        assertSame(sidekicks[0], controllers[1].getLeader(), "sidekick[1]'s leader should be sidekick[0]");
        assertSame(sidekicks[1], controllers[2].getLeader(), "sidekick[2]'s leader should be sidekick[1]");

        // Verify all are CPU-controlled
        for (int i = 0; i < 3; i++) {
            assertTrue(sidekicks[i].isCpuControlled(), "sidekick[" + i + "] should be CPU-controlled");
            assertNotNull(sidekicks[i].getCpuController(), "sidekick[" + i + "] should have a CPU controller");
        }
    }

    // ========== Test 2: Multi-sidekick following / X ordering ==========

    @Test
    public void testMultiSidekickFollowing() {
        // Walk right for 120 frames; HeadlessTestRunner drives the full
        // SpriteManager update path, including CPU sidekicks.
        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        int mainX = mainPlayer.getCentreX();
        int sk0X = sidekicks[0].getCentreX();
        int sk1X = sidekicks[1].getCentreX();
        int sk2X = sidekicks[2].getCentreX();

        assertTrue(mainX > sk0X, "mainPlayer.X (" + mainX + ") should be > sidekick[0].X (" + sk0X + ")");
        assertTrue(sk0X > sk1X, "sidekick[0].X (" + sk0X + ") should be > sidekick[1].X (" + sk1X + ")");
        assertTrue(sk1X > sk2X, "sidekick[1].X (" + sk1X + ") should be > sidekick[2].X (" + sk2X + ")");
    }

    // ========== Test 3: Chain healing integration ==========

    @Test
    public void testSidekickChainHealingIntegration() {
        // Step enough frames for all sidekicks to reach NORMAL and settle
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        // Verify all sidekicks are in NORMAL state and settled
        for (int i = 0; i < 3; i++) {
            assertEquals(SidekickCpuController.State.NORMAL, controllers[i].getState(), "sidekick[" + i + "] should be in NORMAL state after 60 frames");
            assertTrue(controllers[i].isSettled(), "sidekick[" + i + "] should be settled after 60 frames");
        }

        // Force sidekick[1] to SPAWNING state (simulating despawn)
        controllers[1].setInitialState(SidekickCpuController.State.SPAWNING);

        assertFalse(controllers[1].isSettled(), "sidekick[1] should NOT be settled after forced SPAWNING");

        // Verify chain healing: sidekick[2]'s effective leader should skip
        // the unsettled sidekick[1] and resolve to sidekick[0] or main player
        AbstractPlayableSprite effectiveLeader = controllers[2].getEffectiveLeader();
        assertTrue(effectiveLeader == sidekicks[0] || effectiveLeader == mainPlayer, "sidekick[2]'s effective leader should skip unsettled sidekick[1] "
                        + "and resolve to sidekick[0] (settled) or mainPlayer. Got: " + effectiveLeader);

        // sidekick[0] is settled, so it should be the effective leader
        assertSame(sidekicks[0], effectiveLeader, "sidekick[2]'s effective leader should be sidekick[0] (first settled in chain)");
    }

    @Test
    public void testTailsFlyInDoesNotLandWhenCrossingRootSonicBeforeChainLeader() {
        Sonic sonicLeader = new Sonic("sonic_p3", (short) 220, (short) 655);
        sonicLeader.setCpuControlled(true);
        SidekickCpuController sonicController = new SidekickCpuController(sonicLeader, mainPlayer);
        sonicController.setSidekickCount(2);
        sonicController.setInitialState(SidekickCpuController.State.NORMAL);
        sonicLeader.setCpuController(sonicController);
        sonicLeader.setAir(false);
        sonicLeader.prefillPositionHistoryWithCentre((short) 220, (short) 655);

        Tails trailingTails = new Tails("tails_p4", (short) 96, (short) 655);
        trailingTails.setCpuControlled(true);
        SidekickCpuController tailsController = new SidekickCpuController(trailingTails, sonicLeader);
        tailsController.setSidekickCount(2);
        tailsController.setInitialState(SidekickCpuController.State.APPROACHING);
        trailingTails.setCpuController(tailsController);
        trailingTails.setAir(true);

        mainPlayer.setCentreX((short) 100);
        mainPlayer.setCentreY((short) 655);
        mainPlayer.setAir(false);

        tailsController.update(1);

        assertEquals(SidekickCpuController.State.APPROACHING, tailsController.getState(),
                "Tails fly-in should keep targeting its settled chain leader; "
                        + "only physics-driven Sonic approach should complete on root-Sonic crossing");
    }

    @Test
    public void testTrailingTailsUsesFreshNormalSonicLeaderImmediately() {
        mainPlayer.setCentreX((short) 160);
        mainPlayer.setCentreY((short) 655);
        mainPlayer.setAir(false);
        mainPlayer.setDead(false);
        mainPlayer.prefillPositionHistoryWithCentre((short) 160, (short) 655);

        Sonic freshSonicLeader = new Sonic("sonic_fresh_leader", (short) 136, (short) 655);
        freshSonicLeader.setCpuControlled(true);
        freshSonicLeader.setAir(false);
        freshSonicLeader.prefillPositionHistoryWithCentre((short) 96, (short) 655);
        SidekickCpuController freshSonicController = new SidekickCpuController(freshSonicLeader, mainPlayer);
        freshSonicController.setSidekickCount(2);
        freshSonicController.setInitialState(SidekickCpuController.State.APPROACHING);
        freshSonicLeader.setCpuController(freshSonicController);

        freshSonicController.update(1);

        assertEquals(SidekickCpuController.State.NORMAL, freshSonicController.getState(),
                "Sonic sidekick should complete approach before Tails chooses a follow leader");
        assertFalse(freshSonicController.isSettled(),
                "Freshly landed Sonic sidekick should not yet expose warm delayed follow history");

        Tails trailingTails = new Tails("tails_after_fresh_sonic", (short) 96, (short) 655);
        trailingTails.setCpuControlled(true);
        trailingTails.setAir(false);
        trailingTails.prefillPositionHistoryWithCentre((short) 96, (short) 655);
        SidekickCpuController tailsController = new SidekickCpuController(trailingTails, freshSonicLeader);
        tailsController.setSidekickCount(2);
        tailsController.setInitialState(SidekickCpuController.State.NORMAL);
        trailingTails.setCpuController(tailsController);

        assertSame(freshSonicLeader, tailsController.getEffectiveLeader(),
                "A direct CPU leader in NORMAL is usable before the settled-frame threshold; "
                        + "history warmup only affects the leader's delayed follow sample");

        tailsController.update(2);

        assertEquals(SidekickCpuController.State.NORMAL, tailsController.getState());
        assertFalse(tailsController.getInputRight(),
                "Trailing Tails should follow the direct NORMAL Sonic sidekick immediately, not fall back "
                        + "to root Sonic while the direct leader's 16-frame history warms; generatedInput=0x"
                        + Integer.toHexString(tailsController.getDiagnosticGeneratedHeldInput())
                        + ", directLeaderTargetX=" + (freshSonicLeader.getCentreX(16) & 0xFFFF)
                        + ", rootTargetX=" + (mainPlayer.getCentreX(16) & 0xFFFF)
                        + ", tailsX=" + (trailingTails.getCentreX() & 0xFFFF));
    }

    @Test
    void chainedFollowerStillInitializesItsOwnLeadersHistory() {
        GameServices.level().spawnSidekicks(-40, 0);
        AbstractPlayableSprite chainedLeader = sidekicks[0];
        chainedLeader.setCentreX((short) 300);
        chainedLeader.setCentreY((short) 400);
        chainedLeader.prefillPositionHistoryWithCentre((short) 11, (short) 22);

        controllers[1].update(0);

        assertHistoryFilled(chainedLeader, 300, 400);
    }

    private static void assertHistoryFilled(
            AbstractPlayableSprite leader, int expectedX, int expectedY) {
        short[] xHistory = leader.copyXHistory();
        short[] yHistory = leader.copyYHistory();
        assertEquals(64, xHistory.length);
        assertEquals(64, yHistory.length);
        for (int slot = 0; slot < 64; slot++) {
            assertEquals(expectedX, xHistory[slot], "history X slot " + slot);
            assertEquals(expectedY, yHistory[slot], "history Y slot " + slot);
        }
    }
}
