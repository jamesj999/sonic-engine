package com.openggf.game.sonic2.objects;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.Sensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMonitorObjectInstance {

    private static final TouchResponseResult TOUCH_RESULT =
            new TouchResponseResult(0, 0x0E, 0x0E, TouchCategory.SPECIAL);

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

    @Test
    void touchFromAboveRequiresRollAnimationNotJustRollingStatus() {
        ObjectManager objectManager = mockObjectManager();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x26, 0x00, 0, false, 0),
                "Monitor");
        monitor.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic2AnimationIds.SPRING);
        player.setYSpeed((short) 0x0120);

        monitor.onTouchResponse(player, TOUCH_RESULT, 1);

        assertFalse(isBroken(monitor),
                "S2 Touch_Monitor checks anim == AniIDSonAni_Roll, not just the rolling status bit");
        assertEquals(0x0120, player.getYSpeed() & 0xFFFF,
                "Blocked monitor hits must leave the player's Y speed unchanged");
        verify(objectManager, never()).markRemembered(monitor.getSpawn());
    }

    @Test
    void touchProfileRechecksWhileOverlappingBecauseRollAnimationCanLag() {
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x26, 0x00, 0, false, 0),
                "Monitor");

        TouchResponseProfile profile = monitor.getTouchResponseProfile();

        assertTrue(profile.continuousCallbacks(),
                "S2 TouchResponse polls monitors every frame; if the first SPECIAL callback sees a stale "
                        + "non-roll animation, a later callback in the same overlap must still break it");
    }

    @Test
    void touchFromAboveBreaksMonitorWhenRollAnimationIsActive() {
        ObjectManager objectManager = mockObjectManager();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x26, 0x00, 0, false, 0),
                "Monitor");
        monitor.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setYSpeed((short) 0x0120);

        monitor.onTouchResponse(player, TOUCH_RESULT, 1);

        assertTrue(isBroken(monitor));
        assertEquals(0xFEE0, player.getYSpeed() & 0xFFFF,
                "Breaking the monitor should negate the player's downward Y speed");
        verify(objectManager).markRemembered(monitor.getSpawn());
    }

    @Test
    void touchFromAboveSpawnsMonitorContentsBeforeExplosion() {
        ObjectManager objectManager = mockObjectManager();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x26, 0x06, 0, false, 0),
                "Monitor");
        monitor.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setYSpeed((short) 0x0120);

        monitor.onTouchResponse(player, TOUCH_RESULT, 1);

        InOrder order = inOrder(objectManager);
        order.verify(objectManager).addDynamicObject(
                org.mockito.ArgumentMatchers.argThat(MonitorContentsObjectInstance.class::isInstance));
        order.verify(objectManager).addDynamicObject(
                org.mockito.ArgumentMatchers.argThat(ExplosionObjectInstance.class::isInstance));
    }

    @Test
    void staleSidekickMonitorBitDoesNotReleaseSeparateObjectRideOnBreak() {
        ObjectManager objectManager = mockObjectManager();
        SpriteManager spriteManager = mock(SpriteManager.class);
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x16F0, 0x06F1, 0x26, 0x00, 0, false, 0),
                "Monitor");
        monitor.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public SpriteManager spriteManager() {
                return spriteManager;
            }
        });

        DummyPlayer sonic = new DummyPlayer();
        sonic.setRolling(true);
        sonic.setAnimationId(Sonic2AnimationIds.ROLL);
        sonic.setYSpeed((short) 0x0120);

        DummyPlayer tails = new DummyPlayer();
        tails.setCpuControlled(true);
        tails.setOnObject(true);
        tails.setAir(true);
        tails.setPushing(false);
        when(spriteManager.getAllSprites()).thenReturn(List.of(tails));
        when(objectManager.isRidingObject(tails, monitor)).thenReturn(false);

        monitor.onSolidContact(tails, new com.openggf.level.objects.SolidContact(
                false, true, false, false, true), 0);

        monitor.onTouchResponse(sonic, TOUCH_RESULT, 1);

        verify(spriteManager, never()).deferCrossPlayableMutationUntilPostTick(
                any(AbstractPlayableSprite.class), any(Runnable.class));
        assertTrue(tails.isOnObject(),
                "A stale Obj26 p2 bit must not clear Tails' separate Obj69 Status_OnObj latch");
        assertTrue(tails.getAir());
    }

    @Test
    void sidekickCannotBreakMonitorFromAbove() {
        ObjectManager objectManager = mockObjectManager();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x26, 0x00, 0, false, 0),
                "Monitor");
        monitor.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        DummyPlayer sidekick = new DummyPlayer();
        sidekick.setCpuControlled(true);
        sidekick.setRolling(true);
        sidekick.setAnimationId(Sonic2AnimationIds.ROLL);
        sidekick.setYSpeed((short) 0x0120);

        monitor.onTouchResponse(sidekick, TOUCH_RESULT, 1);

        assertFalse(isBroken(monitor),
                "S2 Touch_Monitor gates the break path on cmpa.w #MainCharacter,a0 — a CPU "
                        + "sidekick cannot break a monitor in single-player mode");
        assertEquals(0x0120, sidekick.getYSpeed() & 0xFFFF,
                "Blocked sidekick break must leave the player's Y speed unchanged");
        verify(objectManager, never()).markRemembered(monitor.getSpawn());
    }

    @Test
    void sidekickCanStillKnockMonitorDownFromBelow() {
        ObjectManager objectManager = mockObjectManager();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x26, 0x00, 0, false, 0),
                "Monitor");
        monitor.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        // ROM Touch_Monitor: the cmpa.w #MainCharacter gate lives only in the
        // break path. The hit-from-below "knock it down" branch runs first and
        // is not gated, so a sidekick can still make a monitor fall.
        DummyPlayer sidekick = new DummyPlayer();
        sidekick.setCpuControlled(true);
        sidekick.setYSpeed((short) -0x0120); // moving upward, into the bottom
        sidekick.setY((short) 0x0130);       // player centre well below the monitor

        monitor.onTouchResponse(sidekick, TOUCH_RESULT, 1);

        assertFalse(isBroken(monitor), "Hitting from below must not break the monitor");
        assertTrue(isFalling(monitor),
                "A sidekick hitting the monitor from below should still knock it down");
    }

    @Test
    void solidityUsesGenericSolidObjectContGeometry() {
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x1E10, 0x0291, 0x26, 0x00, 0, false, 0),
                "Monitor");

        assertFalse(monitor.hasMonitorSolidity(),
                "S2 Obj26 branches to SolidObject_cont after the roll-animation gate");
    }

    @Test
    void exposesSonic2MonitorSolidRoutineProfile() {
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x1E10, 0x0291, 0x26, 0x00, 0, false, 0),
                "Monitor");

        SolidRoutineProfile profile = monitor.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertFalse(profile.monitorSolidity());
        assertEquals(0, profile.monitorVerticalOffset());
        assertFalse(profile.stickyContactBuffer());
        assertTrue(profile.inclusiveRightEdge());
        assertTrue(profile.bypassesOffscreenSolidGate());
        assertTrue(monitor.projectsPreMovementGroundXForSolidContact(null));
    }

    private static ObjectManager mockObjectManager() {
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.solidContacts()).thenReturn(
                mock(com.openggf.level.objects.ObjectSolidContactController.class));
        return objectManager;
    }

    private static boolean isBroken(MonitorObjectInstance monitor) {
        return readBooleanField(monitor, "broken");
    }

    private static boolean isFalling(MonitorObjectInstance monitor) {
        return readBooleanField(monitor, "falling");
    }

    private static boolean readBooleanField(MonitorObjectInstance monitor, String name) {
        try {
            Field field = MonitorObjectInstance.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(monitor);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class DummyPlayer extends AbstractPlayableSprite {
        private DummyPlayer() {
            super("sonic", (short) 0x0100, (short) 0x0100);
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
