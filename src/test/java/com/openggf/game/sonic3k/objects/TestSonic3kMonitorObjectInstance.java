package com.openggf.game.sonic3k.objects;

import com.openggf.audio.AudioManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kSuperStateController;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SuperState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestSonic3kMonitorObjectInstance {

    private static final TouchResponseResult TOUCH_RESULT =
            new TouchResponseResult(0, 0x0E, 0x0E, TouchCategory.SPECIAL);

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void touchFromAboveRequiresRollAnimationNotJustRollingStatus() {
        Sonic3kMonitorObjectInstance monitor = monitor();
        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic3kAnimationIds.SPRING);
        player.setYSpeed((short) 0x05A0);

        monitor.onTouchResponse(player, TOUCH_RESULT, 1);

        assertEquals(0x46, monitor.getCollisionFlags(),
                "S3K Touch_Monitor checks anim == AniIDSonAni_Roll, not just the rolling status bit");
        assertEquals(0x05A0, player.getYSpeed() & 0xFFFF,
                "Blocked monitor hits must leave the player's Y speed unchanged");
        assertTrue(monitor.isSolidFor(player),
                "SolidObject_Monitor_SonicKnux uses the same animation-id gate");
    }

    @Test
    void touchProfileRechecksWhileOverlappingBecauseRollAnimationCanLag() {
        Sonic3kMonitorObjectInstance monitor = monitor();

        TouchResponseProfile profile = monitor.getTouchResponseProfile();

        assertTrue(profile.continuousCallbacks(),
                "S3K TouchResponse polls monitors every frame; if the first SPECIAL callback sees a stale "
                        + "non-roll animation, a later callback in the same overlap must still break it");
    }

    @Test
    void touchFromAboveBreaksMonitorWhenRollAnimationIsActive() {
        Sonic3kMonitorObjectInstance monitor = monitor();
        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setYSpeed((short) 0x05A0);

        monitor.onTouchResponse(player, TOUCH_RESULT, 1);

        assertEquals(0, monitor.getCollisionFlags());
        assertEquals(0xFA60, player.getYSpeed() & 0xFFFF,
                "Breaking the monitor should negate the player's downward Y speed");
        assertFalse(monitor.isSolidFor(player));
    }

    @Test
    void speedShoesEffectFeedsSameFrameAirAccelerationAfterContentUpdate() {
        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0050, 0x01, 0x04, 0, false, 0));
        monitor.setServices(new TestObjectServices().withAudioManager(mock(AudioManager.class)));
        DummyPlayer player = new DummyPlayer();
        player.setCentreX((short) 0x0100);
        player.setCentreY((short) 0x0050);
        player.setRolling(true);
        player.setRollingJump(false);
        player.setAir(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setYSpeed((short) 0x05A0);
        player.setXSpeed((short) 0x02D2);

        monitor.update(0, player);
        monitor.onTouchResponse(player, TOUCH_RESULT, 1);
        for (int i = 0; i < 33; i++) {
            monitor.update(i, player);
        }

        new PlayableSpriteMovement(player).handleMovement(false, false, false, true,
                false, false, false, false);

        assertEquals(0x0302, player.getXSpeed() & 0xFFFF,
                "Airborne ChgJumpDir must use boosted same-frame run acceleration after monitor contents");
        assertTrue(player.hasSpeedShoes(),
                "Monitor_Give_SpeedShoes sets status before the next player acceleration read");
    }

    @Test
    void superMonitorAwardsRingsAndStartsTransformationWithoutDebugDoubleAward() {
        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0050, 0x01, 0x09, 0, false, 0));
        monitor.setServices(new TestObjectServices());
        DummyPlayer player = new DummyPlayer();
        player.setSuperStateController(new Sonic3kSuperStateController(player));
        player.setRingCount(0);

        monitor.applyPowerup(player);

        assertEquals(50, player.getRingCount(),
                "Monitor_Give_SuperSonic adds 50 rings once before starting the transform");
        assertTrue(player.isSuperSonic(),
                "S3K subtype 9 monitors should trigger the Super/Hyper transformation path");
        assertEquals(SuperState.TRANSFORMING, player.getSuperStateController().getState(),
                "Monitor-triggered transformation should start immediately without jump/emerald preconditions");
    }

    @Test
    void breakWithPriorPushingContactReleasesPlayerIntoAir() {
        Sonic3kMonitorObjectInstance monitor = monitor();
        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setYSpeed((short) 0);
        player.setAir(false);
        player.setPushing(true);

        monitor.setPlayerPushing(player, true);
        monitor.onTouchResponse(player, TOUCH_RESULT, 1);
        monitor.update(1, player);

        assertTrue(player.getAir(),
                "Obj_MonitorBreak sets Status_InAir when the monitor still has p1_pushing set");
        assertFalse(player.getPushing(),
                "Obj_MonitorBreak clears the player's pushing status via andi.b #$D7");
    }

    @Test
    void breakWithSameFrameClearedSidekickStandingContactReleasesSidekickIntoAir() {
        Sonic3kMonitorObjectInstance monitor = monitor();
        DummyPlayer sonic = new DummyPlayer();
        sonic.setRolling(true);
        sonic.setAnimationId(Sonic3kAnimationIds.ROLL);
        sonic.setYSpeed((short) 0x05A0);

        DummyPlayer tails = new DummyPlayer();
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setPushing(true);

        monitor.onSolidContact(tails, new SolidContact(true, false, false, true, false), 125);
        monitor.onSolidContactCleared(tails, 125);
        monitor.onTouchResponse(sonic, TOUCH_RESULT, 125);
        monitor.update(125, sonic);

        assertTrue(tails.getAir(),
                "Obj_MonitorBreak must still see a same-frame P2 standing contact when Sonic breaks the shell");
        assertFalse(tails.isOnObject(),
                "Breaking a monitor clears the released sidekick's object ride bit");
        assertFalse(tails.getPushing(),
                "Breaking a monitor clears the released sidekick's pushing bit");
    }

    @Test
    void breakInfersSidekickStandingBitFromMonitorEdgeGeometry() {
        DummyPlayer tails = new DummyPlayer();
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0141);
        tails.setCentreY((short) 0x03F0);
        tails.setAir(false);

        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x0128, 0x03F0, 0x01, 0x03, 0, false, 0));
        monitor.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        DummyPlayer sonic = new DummyPlayer();
        sonic.setRolling(true);
        sonic.setAnimationId(Sonic3kAnimationIds.ROLL);
        sonic.setYSpeed((short) 0x05A0);

        monitor.onTouchResponse(sonic, TOUCH_RESULT, 125);
        monitor.update(125, sonic);

        assertTrue(tails.getAir(),
                "S3K Monitor_ChkOverEdge keeps the P2 standing bit through the exact right edge before break release");
        assertEquals(0, tails.getYSpeed(),
                "Monitor break release changes P2 status without injecting immediate vertical velocity");
    }

    @Test
    void drySidekickMonitorReleaseLeavesNextGravityStepEnabled() {
        DummyPlayer tails = new DummyPlayer();
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setOnObject(true);

        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x0128, 0x03F0, 0x01, 0x03, 0, false, 0));
        monitor.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        DummyPlayer sonic = new DummyPlayer();
        sonic.setRolling(true);
        sonic.setAnimationId(Sonic3kAnimationIds.ROLL);
        sonic.setYSpeed((short) 0x05A0);
        tails.setCentreX((short) 0x0141);
        tails.setCentreY((short) 0x03F0);

        monitor.onTouchResponse(sonic, TOUCH_RESULT, 125);
        monitor.update(125, sonic);

        assertTrue(tails.getAir(),
                "Obj_MonitorBreak releases P2 by setting Status_InAir");
        assertFalse(tails.consumeSuppressNextGravityStep(),
                "Obj_MonitorBreak runs after the player slot, so gravity belongs to the following frame");
    }

    @Test
    void underwaterSidekickMonitorReleaseDoesNotSuppressNextGravityStep() {
        DummyPlayer tails = new DummyPlayer();
        tails.setCpuControlled(true);
        tails.setInWater(true);
        tails.setAir(false);
        tails.setOnObject(true);

        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x0128, 0x03F0, 0x01, 0x03, 0, false, 0));
        monitor.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        DummyPlayer sonic = new DummyPlayer();
        sonic.setRolling(true);
        sonic.setAnimationId(Sonic3kAnimationIds.ROLL);
        sonic.setYSpeed((short) 0x05A0);
        tails.setCentreX((short) 0x0141);
        tails.setCentreY((short) 0x03F0);

        monitor.onTouchResponse(sonic, TOUCH_RESULT, 125);
        monitor.update(125, sonic);

        assertTrue(tails.getAir(),
                "Obj_MonitorBreak releases underwater P2 by setting Status_InAir");
        assertFalse(tails.consumeSuppressNextGravityStep(),
                "Underwater release must run the later movement gravity path so water reduction composes normally");
    }

    @Test
    void solidContactLandsAtZeroDistanceUsingS3kSolidObjectVerticalOffset() {
        TestObjectServices services = new TestObjectServices();
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x0840, 0x06E9, 0x01, 0x03, 0, false, 0));
        objectManager.addDynamicObject(monitor);
        monitor.snapshotPreUpdatePosition();

        DummyPlayer player = new DummyPlayer();
        player.setWidth(18);
        player.setHeight(38);
        player.setCentreX((short) 0x0842);
        player.setCentreY((short) 0x06C2);
        player.setXSpeed((short) 0x06B6);
        player.setGSpeed((short) 0x06B6);
        player.setYSpeed((short) 0x01C0);
        player.setAir(true);

        objectManager.updateSolidContacts(player);

        assertEquals(0x06C5, player.getCentreY() & 0xFFFF);
        assertEquals(0, player.getYSpeed());
        assertFalse(player.getAir());
        assertTrue(player.isOnObject());
    }

    @Test
    void cpuTailsRollAnimationStillSolidInOnePlayerMode() {
        Sonic3kMonitorObjectInstance monitor = monitor();
        DummyPlayer tails = new DummyPlayer();
        tails.setCpuControlled(true);
        tails.setRolling(true);
        tails.setAnimationId(Sonic3kAnimationIds.ROLL);

        assertTrue(monitor.isSolidFor(tails),
                "S3K SolidObject_Monitor_Tails reaches SolidObject_cont in one-player mode before the roll gate");
    }

    @Test
    void exposesSonic3kMonitorWrapperAsSolidObjectContProfile() {
        Sonic3kMonitorObjectInstance monitor = monitor();

        SolidRoutineProfile profile = monitor.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertFalse(profile.monitorSolidity());
        assertEquals(0, profile.monitorVerticalOffset(),
                "S3K monitor wrappers branch into SolidObject_cont; the +4 normal-gravity overlap offset is applied by the shared full-solid path");
        assertFalse(profile.stickyContactBuffer());
        assertTrue(monitor.usesInclusiveRightEdge(),
                "S3K SolidObject_cont keeps the exact right edge inside the side-contact range");
        assertTrue(profile.inclusiveRightEdge(),
                "S3K monitor profiles must preserve the SolidObject_cont cmp/bhi right-edge comparison");
    }

    @Test
    void rewindCaptureIncludesFinalMotionState() throws Exception {
        // Break the monitor so the icon-rise motion state machine is active,
        // then advance it to a non-initial mid-rise motion state.
        Sonic3kMonitorObjectInstance monitor = monitor();
        DummyPlayer breaker = new DummyPlayer();
        breaker.setRolling(true);
        breaker.setAnimationId(Sonic3kAnimationIds.ROLL);
        breaker.setYSpeed((short) 0x05A0);
        monitor.onTouchResponse(breaker, TOUCH_RESULT, 1);
        for (int i = 0; i < 5; i++) {
            monitor.update(i, breaker);
        }

        // Read the live final motion-state values BEFORE capture.
        boolean originalIconActive = readBooleanField(monitor, "iconActive");
        int originalIconSubY = readIntField(monitor, "iconSubY");
        int originalIconVelY = readIntField(monitor, "iconVelY");
        int originalIconWait = readIntField(monitor, "iconWaitFrames");

        // The break must have produced a non-default motion state, otherwise the
        // round-trip below could pass vacuously against a freshly constructed
        // monitor's zeroed state. iconActive flips true and iconSubY is seeded to
        // posY()<<8 on break, so both differ from the default.
        assertTrue(originalIconActive, "icon-rise must be active after breaking the monitor");
        assertNotEquals(0, originalIconSubY,
                "iconSubY must be seeded (posY()<<8) on break, not the default 0");

        // Capture, then restore into a fresh monitor and read its motion state by VALUE.
        PerObjectRewindSnapshot captured = monitor.captureRewindState();
        Sonic3kMonitorObjectInstance restored = monitor();
        restored.restoreRewindState(captured);

        assertEquals(originalIconActive, readBooleanField(restored, "iconActive"),
                "iconActive must round-trip through rewind restore");
        assertEquals(originalIconSubY, readIntField(restored, "iconSubY"),
                "iconSubY must round-trip through rewind restore");
        assertEquals(originalIconVelY, readIntField(restored, "iconVelY"),
                "iconVelY (final motion state) must round-trip through rewind restore");
        assertEquals(originalIconWait, readIntField(restored, "iconWaitFrames"),
                "iconWaitFrames must round-trip through rewind restore");
    }

    private static int readIntField(Sonic3kMonitorObjectInstance monitor, String name) throws Exception {
        java.lang.reflect.Field field = resolveField(monitor.getClass(), name);
        field.setAccessible(true);
        return field.getInt(monitor);
    }

    private static boolean readBooleanField(Sonic3kMonitorObjectInstance monitor, String name)
            throws Exception {
        java.lang.reflect.Field field = resolveField(monitor.getClass(), name);
        field.setAccessible(true);
        return field.getBoolean(monitor);
    }

    private static java.lang.reflect.Field resolveField(Class<?> type, String name)
            throws NoSuchFieldException {
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up to AbstractMonitorObjectInstance which declares the icon fields
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Sonic3kMonitorObjectInstance monitor() {
        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0x1E30, 0x0530, 0x01, 0x03, 0, false, 0));
        monitor.setServices(new TestObjectServices());
        return monitor;
    }

    private static final class DummyPlayer extends AbstractPlayableSprite {
        private int localRings;

        private DummyPlayer() {
            super("sonic", (short) 0x1E30, (short) 0x0500);
        }

        @Override
        public int getRingCount() {
            return localRings;
        }

        @Override
        public void setRingCount(int ringCount) {
            localRings = ringCount;
        }

        @Override
        public void addRings(int delta) {
            localRings += delta;
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0x0C;
            runDecel = 0;
            friction = 0;
            max = 0x0C00;
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
            groundSensors = new Sensor[] {
                    new EmptySensor(this, Direction.DOWN),
                    new EmptySensor(this, Direction.DOWN)
            };
            ceilingSensors = new Sensor[] {
                    new EmptySensor(this, Direction.UP),
                    new EmptySensor(this, Direction.UP)
            };
            pushSensors = new Sensor[] {
                    new EmptySensor(this, Direction.LEFT),
                    new EmptySensor(this, Direction.RIGHT)
            };
        }

        @Override
        public void draw() {
        }

    }

    private static final class EmptySensor extends Sensor {
        private EmptySensor(AbstractPlayableSprite sprite, Direction direction) {
            super(sprite, direction, (byte) 0, (byte) 0, true);
        }

        @Override
        protected SensorResult doScan(short dx, short dy) {
            return new SensorResult((byte) 0, (byte) 0x7F, 0, getDirection());
        }
    }
}
