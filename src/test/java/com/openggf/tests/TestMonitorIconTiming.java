package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.session.EngineContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMonitorIconTiming {

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
    void separateMonitorContentsFallThroughToRiseOnFirstTick() {
        TestMonitor monitor = new TestMonitor();
        DummyPlayer player = new DummyPlayer();

        monitor.startRise(player);

        for (int i = 0; i < 32; i++) {
            monitor.stepIcon();
        }

        assertFalse(monitor.effectApplied(),
                "The rise reaches zero velocity on the 32nd icon tick, so the effect is still pending");

        monitor.stepIcon();

        assertTrue(monitor.effectApplied(),
                "Pow_Main/Obj2E_Init falls through to the rise routine, so effect applies on tick 33");
        assertEquals(1, monitor.effectCount());
    }

    @Test
    void embeddedMonitorShellCanSkipSameFrameBreakUpdate() {
        TestEmbeddedMonitor monitor = new TestEmbeddedMonitor();
        DummyPlayer player = new DummyPlayer();

        monitor.startRise(player);

        for (int i = 0; i < 33; i++) {
            monitor.stepIcon();
        }

        assertFalse(monitor.effectApplied(),
                "Embedded shells must not let the parent same-frame update advance monitor contents");

        monitor.stepIcon();

        assertTrue(monitor.effectApplied(),
                "After the skipped parent update, the content effect applies one frame later");
        assertEquals(1, monitor.effectCount());
    }

    private static final class TestMonitor extends AbstractMonitorObjectInstance {
        private int effectCount;

        private TestMonitor() {
            super(new ObjectSpawn(0x100, 0x100, 0, 0, 0, false, 0), "TestMonitor");
        }

        private void startRise(PlayableEntity player) {
            startIconRise(0x100, player);
        }

        private void stepIcon() {
            updateIcon();
        }

        private boolean effectApplied() {
            return effectApplied;
        }

        private int effectCount() {
            return effectCount;
        }

        @Override
        protected void applyPowerup(PlayableEntity player) {
            effectCount++;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }
    }

    private static final class TestEmbeddedMonitor extends AbstractMonitorObjectInstance {
        private int effectCount;

        private TestEmbeddedMonitor() {
            super(new ObjectSpawn(0x100, 0x100, 0, 0, 0, false, 0), "TestEmbeddedMonitor");
        }

        private void startRise(PlayableEntity player) {
            startIconRise(0x100, player);
        }

        private void stepIcon() {
            updateIcon();
        }

        private boolean effectApplied() {
            return effectApplied;
        }

        private int effectCount() {
            return effectCount;
        }

        @Override
        protected boolean delayFirstIconUpdateAfterBreak() {
            return true;
        }

        @Override
        protected void applyPowerup(PlayableEntity player) {
            effectCount++;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }
    }

    private static final class DummyPlayer extends AbstractPlayableSprite {
        private DummyPlayer() {
            super("TEST", (short) 0x100, (short) 0x100);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
        }
    }
}
