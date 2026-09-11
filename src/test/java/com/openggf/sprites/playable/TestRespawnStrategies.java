package com.openggf.sprites.playable;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.rules.GameRules;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.SolidTile;
import com.openggf.level.WaterSystem;
import com.openggf.physics.Direction;
import com.openggf.trace.TraceCharacterState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRespawnStrategies {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
        public void setGameRulesForTest(GameRules featureSet) {
            super.setGameRulesForTest(featureSet);
        }
        void enterDrowningPreDeathForTest() {
            drowningDeath = true;
            drownPreDeathTimer = 60;
        }
    }

    static class GameplayWaterLineSystem extends WaterSystem {
        @Override
        public int getWaterLevelY(int zoneId, int actId) {
            return 0x0500;
        }

        @Override
        public int getGameplayWaterLevelY(int zoneId, int actId) {
            return 0x0520;
        }
    }

    static class CountingRespawnStrategy implements SidekickRespawnStrategy {
        int beginCount;
        AbstractPlayableSprite lastLeader;

        @Override
        public boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                                         int frameCounter) {
            return false;
        }

        @Override
        public boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
            beginCount++;
            lastLeader = leader;
            return true;
        }
    }

    static class CrossingRespawnStrategy implements SidekickRespawnStrategy {
        @Override
        public boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                                         int frameCounter) {
            sidekick.setCentreX((short) (sidekick.getCentreX() + 20));
            return false;
        }

        @Override
        public boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
            return true;
        }

        @Override
        public boolean requiresPhysics() {
            return true;
        }
    }

    @Test
    void tailsIsDefaultStrategy() {
        TestableSprite sk = new TestableSprite("tails_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        assertInstanceOf(TailsRespawnStrategy.class, ctrl.getRespawnStrategy());
    }

    @Test
    void sonicSidekickUsesSonicRespawnStrategyByDefault() {
        TestableSprite sk = new TestableSprite("sonic_p2");
        TestableSprite main = new TestableSprite("tails");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        assertInstanceOf(SonicRespawnStrategy.class, ctrl.getRespawnStrategy());
    }

    @Test
    void knucklesStrategyAlwaysBegins() {
        TestableSprite sk = new TestableSprite("knux_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        KnucklesRespawnStrategy strategy = new KnucklesRespawnStrategy(ctrl);
        // beginApproach always returns true for Knuckles
        assertTrue(strategy.beginApproach(sk, main));
    }

    @Test
    void tailsS2FlyingTimeoutClearsFacingBit() {
        TestableSprite sk = new TestableSprite("tails_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);
        main.prefillPositionHistoryWithCentre((short) 0, (short) 1000);
        sk.setDirection(Direction.LEFT);
        sk.setAir(true);
        sk.setRenderFlagOnScreen(false);

        for (int i = 0; i < 300; i++) {
            assertFalse(strategy.updateApproaching(sk, main, i),
                    "S2 off-screen flying timeout should stay in the approach/spawning loop");
        }

        assertEquals(0, sk.getCentreX());
        assertEquals(0, sk.getCentreY());
        assertEquals(0x02, TraceCharacterState.statusByteFromSprite(sk),
                "S2 TailsCPU_Flying timeout writes status=Status_InAir, clearing the facing bit");
    }

    @Test
    void sonicStrategyReturnsFalseWithoutLevel() {
        TestableSprite sk = new TestableSprite("sonic_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        SonicRespawnStrategy strategy = new SonicRespawnStrategy(ctrl);
        // No level loaded = no terrain = beginApproach returns false
        assertFalse(strategy.beginApproach(sk, main));
    }

    @Test
    void sonicRunInSpeedOutpacesFastMovingLeader() {
        installFlatFloorLevelManager();

        TestableSprite sk = new TestableSprite("sonic_p2");
        TestableSprite main = new TestableSprite("sonic");
        main.setXSpeed((short) 0x0900);
        main.setGSpeed((short) 0x0900);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        SonicRespawnStrategy strategy = new SonicRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertTrue(sk.getRolling(), "Fast Sonic sidekick entries should still use the rolling run-in state");
        assertEquals((short) 0x0B00, sk.getGSpeed(),
                "The sidekick needs a meaningful ground-speed reserve over the running leader to close the gap");
    }

    @Test
    void sonicRunInSpeedKeepsApproachDirectionWhenLeaderRunsLeft() {
        installFlatFloorLevelManager();

        TestableSprite sk = new TestableSprite("sonic_p2");
        TestableSprite main = new TestableSprite("sonic");
        main.setXSpeed((short) -0x0900);
        main.setGSpeed((short) -0x0900);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        SonicRespawnStrategy strategy = new SonicRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertTrue(sk.getRolling(), "Fast Sonic sidekick entries should still use the rolling run-in state");
        assertEquals((short) -0x0B00, sk.getGSpeed(),
                "The sidekick should outpace the leader in the direction it is running in from");
    }

    @Test
    void staggeredSonicRunInUsesMainPlayerSpeedWhenDirectParentIsSlow() {
        installFlatFloorLevelManager();

        TestableSprite main = new TestableSprite("sonic");
        main.setCentreX((short) 0x0180);
        main.setXSpeed((short) 0x0600);
        main.setGSpeed((short) 0x0600);
        TestableSprite parent = new TestableSprite("tails_p2");
        parent.setCpuControlled(true);
        parent.setCentreX((short) 0x0120);
        parent.setXSpeed((short) 0x0100);
        parent.setGSpeed((short) 0x0100);
        TestableSprite subSidekick = new TestableSprite("sonic_p3");
        subSidekick.setCpuControlled(true);

        SidekickCpuController parentCtrl = new SidekickCpuController(parent, main);
        SidekickCpuController subCtrl = new SidekickCpuController(subSidekick, parent);
        parentCtrl.setSidekickCount(2);
        subCtrl.setSidekickCount(2);
        parentCtrl.forceStateForTest(SidekickCpuController.State.NORMAL, 20);
        subCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);

        subCtrl.update(64);

        assertEquals(SidekickCpuController.State.APPROACHING, subCtrl.getState());
        assertEquals((short) 0x0800, subSidekick.getGSpeed(),
                "A staggered Sonic should reserve enough entry speed to catch the main player, not just its slow parent");
    }

    @Test
    void sonicRunInSkipsScreenEdgeFloorWhenBodyWouldOverlapWallTerrain() {
        installScreenEdgeWallThenClearFloorLevelManager();

        TestableSprite sk = new TestableSprite("sonic_p2");
        TestableSprite main = new TestableSprite("sonic");
        main.setXSpeed((short) 0x0100);
        main.setGSpeed((short) 0x0100);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        SonicRespawnStrategy strategy = new SonicRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertEquals((short) 16, sk.getCentreX(),
                "The run-in spawn should move inward to the first floor position whose wall probes are clear");
    }

    @Test
    void sonicRespawnClearsStaleObjectAnimationAndDrowningState() {
        installFlatFloorLevelManager();

        TestableSprite sk = new TestableSprite("sonic_p2");
        sk.setCpuControlled(true);
        sk.setObjectMappingFrameControl(true);
        sk.enterDrowningPreDeathForTest();
        TestableSprite main = new TestableSprite("sonic");
        main.setAir(false);
        main.setRollingJump(false);
        main.setInWater(false);
        main.setPreventTailsRespawn(false);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        ctrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);

        ctrl.update(0);

        assertEquals(SidekickCpuController.State.APPROACHING, ctrl.getState());
        assertFalse(sk.isObjectMappingFrameControl(),
                "Respawn must drop stale object-controlled mapping frames from cylinders and similar objects");
        assertFalse(sk.isDrowningPreDeath(),
                "Respawn must cancel the pre-death drowning animation state");
    }

    @Test
    void multiSidekickRespawnStartsOnlyFirstSidekickOnSharedFrame() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite first = new TestableSprite("tails_p2");
        TestableSprite second = new TestableSprite("sonic_p3");
        TestableSprite third = new TestableSprite("knux_p4");
        first.setCpuControlled(true);
        second.setCpuControlled(true);
        third.setCpuControlled(true);

        SidekickCpuController firstCtrl = new SidekickCpuController(first, main);
        SidekickCpuController secondCtrl = new SidekickCpuController(second, first);
        SidekickCpuController thirdCtrl = new SidekickCpuController(third, second);
        CountingRespawnStrategy firstStrategy = new CountingRespawnStrategy();
        CountingRespawnStrategy secondStrategy = new CountingRespawnStrategy();
        CountingRespawnStrategy thirdStrategy = new CountingRespawnStrategy();
        firstCtrl.setRespawnStrategy(firstStrategy);
        secondCtrl.setRespawnStrategy(secondStrategy);
        thirdCtrl.setRespawnStrategy(thirdStrategy);
        firstCtrl.setSidekickCount(3);
        secondCtrl.setSidekickCount(3);
        thirdCtrl.setSidekickCount(3);
        firstCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        secondCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        thirdCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);

        firstCtrl.update(64);
        secondCtrl.update(64);
        thirdCtrl.update(64);

        assertEquals(SidekickCpuController.State.APPROACHING, firstCtrl.getState());
        assertEquals(SidekickCpuController.State.SPAWNING, secondCtrl.getState(),
                "The second sidekick should wait until its direct leader has become a usable respawn anchor");
        assertEquals(SidekickCpuController.State.SPAWNING, thirdCtrl.getState(),
                "The third sidekick should not skip over an unsettled direct leader and spawn on the same frame");
        assertEquals(1, firstStrategy.beginCount);
        assertEquals(0, secondStrategy.beginCount);
        assertEquals(0, thirdStrategy.beginCount);
    }

    @Test
    void multiSidekickRespawnUsesDirectLeaderAfterApproachAnchorDelay() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite first = new TestableSprite("tails_p2");
        TestableSprite second = new TestableSprite("sonic_p3");
        first.setCpuControlled(true);
        second.setCpuControlled(true);

        SidekickCpuController firstCtrl = new SidekickCpuController(first, main);
        SidekickCpuController secondCtrl = new SidekickCpuController(second, first);
        CountingRespawnStrategy firstStrategy = new CountingRespawnStrategy();
        CountingRespawnStrategy secondStrategy = new CountingRespawnStrategy();
        firstCtrl.setRespawnStrategy(firstStrategy);
        secondCtrl.setRespawnStrategy(secondStrategy);
        firstCtrl.setSidekickCount(2);
        secondCtrl.setSidekickCount(2);
        firstCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        secondCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);

        firstCtrl.update(64);
        for (int frame = 65; frame < 77; frame++) {
            firstCtrl.update(frame);
        }
        secondCtrl.update(128);

        assertEquals(SidekickCpuController.State.APPROACHING, secondCtrl.getState());
        assertSame(first, secondStrategy.lastLeader,
                "A staggered sidekick should approach its direct sidekick leader once that leader is usable");
    }

    @Test
    void multiSidekickRespawnFallsBackWhenDirectLeaderNeverBecomesUsable() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite stuckLeader = new TestableSprite("tails_p2");
        TestableSprite second = new TestableSprite("sonic_p3");
        stuckLeader.setCpuControlled(true);
        second.setCpuControlled(true);

        SidekickCpuController stuckCtrl = new SidekickCpuController(stuckLeader, main);
        SidekickCpuController secondCtrl = new SidekickCpuController(second, stuckLeader);
        CountingRespawnStrategy secondStrategy = new CountingRespawnStrategy();
        stuckCtrl.setSidekickCount(2);
        secondCtrl.setSidekickCount(2);
        stuckCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        secondCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        secondCtrl.setRespawnStrategy(secondStrategy);

        for (int frame = 64; frame < 192; frame++) {
            secondCtrl.update(frame);
        }

        assertEquals(SidekickCpuController.State.APPROACHING, secondCtrl.getState(),
                "The stagger gate must not deadlock if an earlier sidekick cannot begin respawn");
        assertSame(main, secondStrategy.lastLeader,
                "After the fallback timeout, the sidekick should recover through the nearest effective leader");
    }

    @Test
    void multiSidekickFallbackStartsOnlyNextBlockedSidekick() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite stuckLeader = new TestableSprite("tails_p2");
        TestableSprite second = new TestableSprite("sonic_p3");
        TestableSprite third = new TestableSprite("knux_p4");
        stuckLeader.setCpuControlled(true);
        second.setCpuControlled(true);
        third.setCpuControlled(true);

        SidekickCpuController stuckCtrl = new SidekickCpuController(stuckLeader, main);
        SidekickCpuController secondCtrl = new SidekickCpuController(second, stuckLeader);
        SidekickCpuController thirdCtrl = new SidekickCpuController(third, second);
        CountingRespawnStrategy secondStrategy = new CountingRespawnStrategy();
        CountingRespawnStrategy thirdStrategy = new CountingRespawnStrategy();
        stuckCtrl.setSidekickCount(3);
        secondCtrl.setSidekickCount(3);
        thirdCtrl.setSidekickCount(3);
        stuckCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        secondCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        thirdCtrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        secondCtrl.setRespawnStrategy(secondStrategy);
        thirdCtrl.setRespawnStrategy(thirdStrategy);

        for (int frame = 64; frame <= 128; frame++) {
            secondCtrl.update(frame);
            thirdCtrl.update(frame);
        }

        assertEquals(SidekickCpuController.State.APPROACHING, secondCtrl.getState(),
                "The next blocked sidekick should recover through the fallback timeout");
        assertEquals(SidekickCpuController.State.SPAWNING, thirdCtrl.getState(),
                "Lower sidekicks should not all consume their expired fallback timers on the same frame");
        assertEquals(1, secondStrategy.beginCount);
        assertEquals(0, thirdStrategy.beginCount);
    }

    @Test
    void subSidekickApproachCompletesWhenCrossingMainPlayerBeforeParent() {
        TestableSprite main = new TestableSprite("sonic");
        main.setCentreX((short) 100);
        main.setCentreY((short) 200);
        TestableSprite parent = new TestableSprite("tails_p2");
        parent.setCpuControlled(true);
        parent.setCentreX((short) 200);
        parent.setCentreY((short) 200);
        TestableSprite subSidekick = new TestableSprite("sonic_p3");
        subSidekick.setCpuControlled(true);
        subSidekick.setCentreX((short) 90);
        subSidekick.setCentreY((short) 200);

        SidekickCpuController parentCtrl = new SidekickCpuController(parent, main);
        SidekickCpuController subCtrl = new SidekickCpuController(subSidekick, parent);
        parentCtrl.setSidekickCount(2);
        subCtrl.setSidekickCount(2);
        parentCtrl.forceStateForTest(SidekickCpuController.State.NORMAL, 20);
        subCtrl.forceStateForTest(SidekickCpuController.State.APPROACHING, 0);
        subCtrl.setRespawnStrategy(new CrossingRespawnStrategy());

        subCtrl.update(1);

        assertEquals(SidekickCpuController.State.NORMAL, subCtrl.getState(),
                "A lower sidekick should finish approach when it crosses the main player, even if its parent is farther ahead");
    }

    @Test
    void knucklesDropsAfterTimeout() {
        TestableSprite sk = new TestableSprite("knux_p2");
        sk.setCpuControlled(true);
        TestableSprite main = new TestableSprite("sonic");
        main.setX((short) 160);
        main.setY((short) 400);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        KnucklesRespawnStrategy strategy = new KnucklesRespawnStrategy(ctrl);
        strategy.beginApproach(sk, main);

        // Run 180 frames (timeout)
        for (int i = 0; i < 180; i++) {
            strategy.updateApproaching(sk, main, i);
        }
        // After timeout, Knuckles should be in dropping phase
        // The next updateApproaching should not move horizontally
        // (We can't fully verify drop without physics, but we can verify
        // the approach doesn't complete before landing)
        boolean complete = strategy.updateApproaching(sk, main, 180);
        // Not complete yet — sidekick is still in air (no physics engine in unit test)
        assertFalse(complete, "Should not complete until landed");
    }
    @Test
    void tailsDoesNotCompleteFlyInWhenOnlyVerticalStepReachesTarget() {
        TestableSprite sk = new TestableSprite("tails_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x0100);
        Arrays.fill(yHistory, (short) 0x0200);
        main.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        sk.setCentreX((short) 0x0100);
        sk.setCentreY((short) 0x01FF);

        assertFalse(strategy.updateApproaching(sk, main, 0),
                "ROM keeps Tails in fly-in mode when the vertical +/-1 step reaches the target this frame");
        assertEquals(0x0200, sk.getCentreY() & 0xFFFF);

        assertTrue(strategy.updateApproaching(sk, main, 1),
                "ROM exits fly-in once the pre-move vertical delta is already zero");
    }

    @Test
    void sonic2TailsRespawnPreservesExistingVelocity() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_2);
        sk.setXSpeed((short) 0x041C);
        sk.setYSpeed((short) 0x0012);
        sk.setGSpeed((short) 0x041C);
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertEquals((short) 0x041C, sk.getXSpeed(),
                "S2 TailsCPU_Respawn writes position/target fields but does not clear x_vel");
        assertEquals((short) 0x0012, sk.getYSpeed(),
                "S2 TailsCPU_Respawn writes position/target fields but does not clear y_vel");
        assertEquals((short) 0x041C, sk.getGSpeed(),
                "S2 TailsCPU_Respawn writes position/target fields but does not clear inertia");
    }

    @Test
    void tailsRespawnTargetYClampsToGameplayWaterLine() {
        GameplayModeContext mode = TestEnvironment.activeGameplayMode();
        mode.attachLevelManagers(new GameplayWaterLineSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), mode.getLevelManager());

        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setCpuControlled(true);
        sk.setGameRulesForTest(GameRules.SONIC_2);
        TestableSprite main = new TestableSprite("sonic");
        main.setCentreY((short) 0x0600);
        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x0200);
        Arrays.fill(yHistory, (short) 0x0600);
        main.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));
        assertFalse(strategy.updateApproaching(sk, main, 1));

        assertEquals(0x0510, strategy.diagnosticTargetY(0),
                "TailsCPU_Respawn clamps target_y to Water_Level_1-$10, not the non-oscillated base level");
    }

    @Test
    void sonic2DeadLeaderEntryUsesFlyingRoutineWithoutRespawnTeleport() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_2);
        sk.setCpuControlled(true);
        sk.setCentreX((short) 0x1234);
        sk.setCentreY((short) 0x0456);
        sk.setRolling(true);
        sk.setOnObject(true);
        sk.setPushing(true);
        sk.setInWater(true);
        sk.setSpindash(true);
        sk.setSpindashCounter((short) 0x20);
        sk.setDirection(com.openggf.physics.Direction.LEFT);

        TestableSprite main = new TestableSprite("sonic");
        main.setDead(true);

        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        ctrl.hydrateFromRomCpuState(0x06, 0, 0, 0, true, 0, 0);
        ctrl.forceStateForTest(SidekickCpuController.State.NORMAL, 0);
        short preEntryX = sk.getCentreX();
        short preEntryY = sk.getCentreY();

        ctrl.update(0);

        assertEquals(SidekickCpuController.State.APPROACHING, ctrl.getState(),
                "S2 dead-Sonic branch enters TailsCPU_Flying routine 4, not the S3K auto-recovery routine");
        assertEquals(preEntryX, sk.getCentreX(),
                "TailsCPU_Normal dead-Sonic entry does not run TailsCPU_Respawn's teleport");
        assertEquals(preEntryY, sk.getCentreY());
        assertFalse(sk.getSpindash(), "ROM clears spindash_flag on dead-Sonic flight entry");
        assertEquals((short) 0, sk.getSpindashCounter());
        assertTrue(sk.getAir(), "ROM writes status=$02, leaving only Status_InAir set");
        assertFalse(sk.getRolling());
        assertFalse(sk.isOnObject());
        assertFalse(sk.getPushing());
        assertFalse(sk.isInWater());
        assertEquals(com.openggf.physics.Direction.RIGHT, sk.getDirection());
        assertTrue(sk.isObjectControlSuppressesMovement());
        assertEquals(1, ctrl.getDiagnosticJumpingFlag(),
                "S2 TailsCPU_Normal's dead-Sonic branch writes routine 4 and flight state "
                        + "without clearing Tails_CPU_jumping (s2.asm:39254-39264)");
    }

    @Test
    void sonic2RespawnRoutinePreservesCpuJumpingFlagOnFlyingEntry() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_2);
        sk.setCpuControlled(true);

        TestableSprite main = new TestableSprite("sonic");
        main.setGameRulesForTest(GameRules.SONIC_2);
        main.setCentreX((short) 0x032C);
        main.setCentreY((short) 0x032C);
        main.setAir(false);
        main.setRollingJump(false);
        main.setInWater(false);
        main.setPreventTailsRespawn(false);

        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        ctrl.hydrateFromRomCpuState(0x06, 0, 0, 0, true, 0, 0);
        ctrl.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        sk.setAnimationId(0x20);
        sk.setAnimationFrameIndex(1);
        sk.setAnimationTick(0);
        sk.getAnimationManager().restoreRewindState(
                new com.openggf.sprites.managers.PlayableSpriteAnimation.RewindState(0x20, 0x20));

        ctrl.update(0);

        assertEquals(SidekickCpuController.State.APPROACHING, ctrl.getState(),
                "S2 TailsCPU_Respawn writes Tails_CPU_routine=4 when the respawn gate fires");
        assertEquals(1, ctrl.getDiagnosticJumpingFlag(),
                "S2 TailsCPU_Respawn does not clear Tails_CPU_jumping "
                        + "(s2.asm:39116-39130); the normal filter clears it later only when grounded");
        assertEquals(0x20, sk.getAnimationManager().captureRewindState().lastAnimationId(),
                "TailsCPU_Respawn does not write prev_anim or restart the existing Fly script");
        assertEquals(1, sk.getAnimationFrameIndex());
        assertEquals(0, sk.getAnimationTick());
    }

    @Test
    void sonic2RespawnAndFlyingPreserveExistingAnimation() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_2);
        sk.setAnimationId(sk.resolveAnimationId(CanonicalAnimation.WAIT));
        sk.setForcedAnimationId(-1);
        TestableSprite main = new TestableSprite("sonic");
        main.setGameRulesForTest(GameRules.SONIC_2);
        main.prefillPositionHistoryWithCentre((short) 0x0200, (short) 0x0600);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));
        assertEquals(sk.resolveAnimationId(CanonicalAnimation.WAIT), sk.getAnimationId());
        assertEquals(-1, sk.getForcedAnimationId(),
                "S2 TailsCPU_Respawn does not write anim or prev_anim");
        assertFalse(sk.getAir(),
                "S2 TailsCPU_Respawn preserves status; the following Obj02 movement path owns floor loss");

        assertFalse(strategy.updateApproaching(sk, main, 1));
        assertEquals(sk.resolveAnimationId(CanonicalAnimation.WAIT), sk.getAnimationId());
        assertEquals(-1, sk.getForcedAnimationId(),
                "ordinary S2 TailsCPU_Flying homing does not own the animation byte");
    }

    @Test
    void sonic2RespawnReleasesOneShotTimeoutFlyOwnerWithoutChangingAnimByte() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_2);
        int fly = sk.resolveAnimationId(CanonicalAnimation.FLY);
        sk.setAnimationId(fly);
        sk.setAnimationFrameIndex(1);
        sk.setAnimationTick(0);
        sk.setForcedAnimationId(fly);
        TestableSprite main = new TestableSprite("sonic");
        main.setGameRulesForTest(GameRules.SONIC_2);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertEquals(fly, sk.getAnimationId(),
                "TailsCPU_Respawn does not rewrite the timeout's Fly animation byte");
        assertEquals(1, sk.getAnimationFrameIndex(),
                "releasing the synthetic owner must not restart the native Fly script");
        assertEquals(0, sk.getAnimationTick());
        assertEquals(-1, sk.getForcedAnimationId(),
                "the timeout's anim=Fly write must not continuously suppress later native movement animation writes");
    }

    @Test
    void sonic2TailsFlyInKeepsNormalAirPhysicsActive() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_2);
        sk.setObjectControlled(true);
        sk.setObjectControlSuppressesMovement(false);
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertTrue(strategy.requiresPhysics(),
                "Obj02 continues through its movement dispatcher after TailsCPU_Respawn returns "
                        + "when inherited obj_control bit 0 is clear");

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) main.getCentreX());
        Arrays.fill(yHistory, (short) main.getCentreY());
        main.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        assertFalse(strategy.updateApproaching(sk, main, 1));

        assertTrue(strategy.requiresPhysics(),
                "S2 TailsCPU_Flying manually nudges x/y, then Obj02_MdAir still runs ObjectMoveAndFall");
        assertFalse(sk.isObjectControlSuppressesMovement(),
                "S2 TailsCPU_Respawn/Flying does not write obj_control=$81 except on the off-screen timeout path");
        assertFalse(sk.isControlLocked(),
                "S2 TailsCPU_Respawn leaves normal Obj02 movement dispatch active (s2.asm:39122-39140)");
    }

    @Test
    void sonic3kTailsCatchUpRespawnClearsVelocity() {
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setGameRulesForTest(GameRules.SONIC_3K);
        sk.setXSpeed((short) 0x041C);
        sk.setYSpeed((short) 0x0012);
        sk.setGSpeed((short) 0x041C);
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        assertTrue(strategy.beginApproach(sk, main));

        assertEquals((short) 0, sk.getXSpeed(),
                "S3K Tails_Catch_Up_Flying clears x_vel on the catch-up teleport");
        assertEquals((short) 0, sk.getYSpeed(),
                "S3K Tails_Catch_Up_Flying clears y_vel on the catch-up teleport");
        assertEquals((short) 0, sk.getGSpeed(),
                "S3K Tails_Catch_Up_Flying clears ground velocity on the catch-up teleport");
        assertEquals(sk.resolveAnimationId(CanonicalAnimation.FLY), sk.getForcedAnimationId(),
                "S3K Tails_Catch_Up_Flying explicitly calls Tails_Set_Flying_Animation");
        assertTrue(sk.getAir(), "S3K catch-up entry explicitly writes status=Status_InAir");
    }

    @Test
    void tailsCompletesFlyInWhenHorizontalCatchUpClosesRemainingGap() {
        TestableSprite sk = new TestableSprite("tails_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x0105);
        Arrays.fill(yHistory, (short) 0x0200);
        main.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);
        main.setXSpeed((short) 0x0500);

        sk.setCentreX((short) 0x0100);
        sk.setCentreY((short) 0x0200);

        assertTrue(strategy.updateApproaching(sk, main, 0),
                "ROM fly-in completion uses the post-horizontal residual, so closing the X gap this frame exits approach");
        assertEquals(0x0105, sk.getCentreX() & 0xFFFF);
        assertEquals(0x0200, sk.getCentreY() & 0xFFFF);
    }

    @Test
    void tailsFlyInCompletionCopiesLeaderCollisionBitsAndPriority() {
        TestableSprite sk = new TestableSprite("tails_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x0100);
        Arrays.fill(yHistory, (short) 0x0200);
        main.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        main.setTopSolidBit((byte) 0x0E);
        main.setLrbSolidBit((byte) 0x0F);
        main.setHighPriority(true);

        sk.setTopSolidBit((byte) 0x0C);
        sk.setLrbSolidBit((byte) 0x0D);
        sk.setHighPriority(false);
        sk.setMoveLockTimer(13);
        sk.setObjectControlAllowsCpu(true);
        sk.setObjectControlSuppressesMovement(true);
        sk.setRolling(true);
        sk.setOnObject(true);
        sk.setPushing(true);
        sk.setInWater(true);
        sk.setDirection(com.openggf.physics.Direction.LEFT);
        sk.setCentreX((short) 0x0100);
        sk.setCentreY((short) 0x0200);

        ctrl.setInitialState(SidekickCpuController.State.APPROACHING);
        ctrl.update(0);

        assertEquals(SidekickCpuController.State.NORMAL, ctrl.getState());
        assertEquals(0x0E, sk.getTopSolidBit() & 0xFF);
        assertEquals(0x0F, sk.getLrbSolidBit() & 0xFF);
        assertTrue(sk.isHighPriority());
        assertEquals(0, sk.getMoveLockTimer(),
                "Tails_Catch_Up_Flying exit clears move_lock before normal CPU control resumes");
        assertEquals(sk.resolveAnimationId(CanonicalAnimation.WALK), sk.getAnimationId(),
                "S2 TailsCPU_Flying completion writes AniIDSonAni_Walk");
        assertTrue(sk.getAir(), "S2 TailsCPU_Flying completion writes status=$02");
        assertFalse(sk.getRolling());
        assertFalse(sk.isOnObject());
        assertFalse(sk.getPushing());
        assertFalse(sk.isInWater());
        assertEquals(com.openggf.physics.Direction.RIGHT, sk.getDirection());
        assertFalse(sk.isObjectControlled(),
                "Tails_Catch_Up_Flying exit clears object_control before normal CPU control resumes");
        assertFalse(sk.isObjectControlAllowsCpu(),
                "Tails_Catch_Up_Flying exit clears the object-control CPU allowance mirror");
        assertFalse(sk.isObjectControlSuppressesMovement(),
                "Tails_Catch_Up_Flying exit clears the object-control movement suppression mirror");
    }

    @Test
    void tailsFlyInUsesSignedHighByteOfLeaderVelocityForHorizontalBonus() {
        TestableSprite sk = new TestableSprite("tails_p2");
        TestableSprite main = new TestableSprite("sonic");
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        TailsRespawnStrategy strategy = new TailsRespawnStrategy(ctrl);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x010B);
        Arrays.fill(yHistory, (short) 0x0200);
        main.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        sk.setCentreX((short) 0x0100);
        sk.setCentreY((short) 0x0200);
        main.setXSpeed((short) 0xFF82);

        assertFalse(strategy.updateApproaching(sk, main, 0));
        assertEquals(0x0102, sk.getCentreX() & 0xFFFF,
                "ROM uses the signed high byte of Sonic's x_vel during Tails fly-in, so 0xFF82 adds a +1 speed bonus");
    }

    private static void installFlatFloorLevelManager() {
        GameplayModeContext mode = TestEnvironment.activeGameplayMode();
        LevelManager levelManager = mock(LevelManager.class);
        ChunkDesc solidFloor = new ChunkDesc(7 | 0x1000);
        byte[] heights = new byte[SolidTile.TILE_SIZE_IN_ROM];
        Arrays.fill(heights, (byte) 8);
        SolidTile flatTile = new SolidTile(7, heights, new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0);

        when(levelManager.getCurrentLevel()).thenReturn(mock(Level.class));
        when(levelManager.getChunkDescAt(eq((byte) 0), anyInt(), anyInt())).thenReturn(solidFloor);
        when(levelManager.getSolidTileForChunkDesc(eq(solidFloor), eq(0x0C), anyBoolean())).thenReturn(flatTile);

        mode.attachLevelManagers(mode.getWaterSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), levelManager);
    }

    private static void installScreenEdgeWallThenClearFloorLevelManager() {
        GameplayModeContext mode = TestEnvironment.activeGameplayMode();
        LevelManager levelManager = mock(LevelManager.class);
        ChunkDesc edgeWallFloor = new ChunkDesc(8 | 0x3000);
        ChunkDesc clearFloor = new ChunkDesc(7 | 0x1000);
        byte[] floorHeights = new byte[SolidTile.TILE_SIZE_IN_ROM];
        byte[] wallWidths = new byte[SolidTile.TILE_SIZE_IN_ROM];
        Arrays.fill(floorHeights, (byte) 8);
        Arrays.fill(wallWidths, (byte) 16);
        SolidTile wallFloorTile = new SolidTile(8, floorHeights, wallWidths, (byte) 0);
        SolidTile clearFloorTile = new SolidTile(7, floorHeights, new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0);

        when(levelManager.getCurrentLevel()).thenReturn(mock(Level.class));
        when(levelManager.getChunkDescAt(eq((byte) 0), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int x = invocation.getArgument(1);
                    return x < 0 ? edgeWallFloor : clearFloor;
                });
        when(levelManager.getSolidTileForChunkDesc(eq(edgeWallFloor), eq(0x0C), anyBoolean())).thenReturn(wallFloorTile);
        when(levelManager.getSolidTileForChunkDesc(eq(edgeWallFloor), eq(0x0D), anyBoolean())).thenReturn(wallFloorTile);
        when(levelManager.getSolidTileForChunkDesc(eq(clearFloor), eq(0x0C), anyBoolean())).thenReturn(clearFloorTile);

        mode.attachLevelManagers(mode.getWaterSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), levelManager);
    }
}
