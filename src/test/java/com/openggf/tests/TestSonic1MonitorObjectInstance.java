package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.objects.Sonic1MonitorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1MonitorPowerUpObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSonic1MonitorObjectInstance {

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
    void cpuSidekickCannotBreakSonic1Monitor() {
        ObjectManager objectManager = mock(ObjectManager.class);
        Sonic1MonitorObjectInstance monitor = new Sonic1MonitorObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x26, 4, 0, false, 0));
        monitor.setServices(servicesWithObjectManager(objectManager));

        DummyPlayer sidekick = new DummyPlayer();
        sidekick.setCpuControlled(true);
        sidekick.setRolling(true);
        sidekick.setYSpeed((short) 5);

        monitor.onTouchResponse(sidekick, mock(TouchResponseResult.class), 1);

        assertFalse(isBroken(monitor),
                "Donated sidekicks must not be able to break Sonic 1 monitors");
        assertFalse(sidekick.hasShield(),
                "A sidekick touch must not be able to reach the monitor shield grant path");
        assertTrue(sidekick.getYSpeed() == 5,
                "Blocked sidekick touches should leave the player velocity unchanged");
        verify(objectManager, never()).markRemembered(monitor.getSpawn());
        verify(objectManager, never()).addDynamicObject(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void touchFromAboveRequiresRollAnimationNotJustRollingStatus() {
        ObjectManager objectManager = mock(ObjectManager.class);
        Sonic1MonitorObjectInstance monitor = new Sonic1MonitorObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x26, 4, 0, false, 0));
        monitor.setServices(servicesWithObjectManager(objectManager));

        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic1AnimationIds.SPRING);
        player.setYSpeed((short) 0x0120);

        monitor.onTouchResponse(player, mock(TouchResponseResult.class), 1);

        assertFalse(isBroken(monitor),
                "S1 Touch_Monitor checks anim == id_Roll, not just the rolling status bit");
        assertEquals(0x0120, player.getYSpeed() & 0xFFFF,
                "Blocked monitor hits must leave the player's Y speed unchanged");
        verify(objectManager, never()).markRemembered(monitor.getSpawn());
    }

    @Test
    void touchProfileRechecksWhileOverlappingBecauseRollAnimationCanLag() {
        Sonic1MonitorObjectInstance monitor = new Sonic1MonitorObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x26, 4, 0, false, 0));

        assertTrue(monitor.getTouchResponseProfile().continuousCallbacks(),
                "S1 ReactToItem polls monitors every frame, so stale non-roll animation on first overlap "
                        + "must not suppress a later break in the same overlap");
    }

    @Test
    void monitorTopContactStillLandsWhenPlayerWasBouncedUpward() {
        ObjectServices services = new StubObjectServices();
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x07B8);
        when(camera.getY()).thenReturn((short) 0x0220);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, camera, services);
        Sonic1MonitorObjectInstance monitor = new Sonic1MonitorObjectInstance(
                new ObjectSpawn(0x0850, 0x02D1, 0x26, 6, 0, false, 0));
        objectManager.addDynamicObjectAtSlot(monitor, 0x26);
        monitor.snapshotPreUpdatePosition();

        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setCentreX((short) 0x0848);
        player.setCentreY((short) 0x02B6);
        player.setAir(true);
        player.setYSpeed((short) -0x01F8);

        objectManager.updateSolidContacts(player);

        assertFalse(player.getAir(),
                "S1 Mon_Solid only uses upward velocity to block breaking; top contact still lands");
        assertTrue(player.isOnObject(), "Top contact should set the monitor standing bit");
        assertEquals(0, player.getYSpeed(), "Monitor landing must zero Y speed");
    }

    @Test
    void adjacentMonitorLandsJumpLatchedPlayerAfterSameFrameMonitorBreakBounce() {
        ObjectServices services = new StubObjectServices();
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x07B8);
        when(camera.getY()).thenReturn((short) 0x0220);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, camera, services);
        Sonic1MonitorObjectInstance adjacentMonitor = new Sonic1MonitorObjectInstance(
                new ObjectSpawn(0x0850, 0x02D1, 0x26, 6, 0, false, 0));
        objectManager.addDynamicObjectAtSlot(adjacentMonitor, 0x26);
        adjacentMonitor.snapshotPreUpdatePosition();

        DummyPlayer player = new DummyPlayer();
        player.setRolling(true);
        player.setJumping(true);
        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setCentreX((short) 0x0848);
        player.setCentreY((short) 0x02B6);
        player.setAir(true);
        player.setYSpeed((short) -0x0418);
        objectManager.solidContacts().markSameFrameMonitorBreakBounce(player);

        objectManager.updateSolidContacts(player);

        assertFalse(player.getAir(),
                "A break-bounce must not masquerade as a new upward jump at the adjacent monitor");
        assertTrue(player.isOnObject());
        assertEquals(0, player.getYSpeed());
    }

    @Test
    void staticMonitorPowerUpRendersStaticIcon() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        com.openggf.level.objects.ObjectSpriteSheet sheet = mock(com.openggf.level.objects.ObjectSpriteSheet.class);
        SpriteMappingPiece iconPiece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0);
        SpriteMappingFrame frame = new SpriteMappingFrame(List.of(iconPiece));

        when(renderManager.getMonitorRenderer()).thenReturn(renderer);
        when(renderManager.getMonitorSheet()).thenReturn(sheet);
        when(renderer.isReady()).thenReturn(true);
        when(sheet.getFrameCount()).thenReturn(16);
        when(sheet.getFrame(2)).thenReturn(frame);

        Sonic1MonitorPowerUpObjectInstance powerUp =
                new Sonic1MonitorPowerUpObjectInstance(0x100, 0x100, 0, new DummyPlayer());
        powerUp.setServices(servicesWithRenderManager(renderManager));

        powerUp.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawPieces(anyList(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void shieldMonitorPowerUpRendersIcon() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        com.openggf.level.objects.ObjectSpriteSheet sheet = mock(com.openggf.level.objects.ObjectSpriteSheet.class);
        SpriteMappingPiece iconPiece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0);
        SpriteMappingFrame frame = new SpriteMappingFrame(List.of(iconPiece));

        when(renderManager.getMonitorRenderer()).thenReturn(renderer);
        when(renderManager.getMonitorSheet()).thenReturn(sheet);
        when(renderer.isReady()).thenReturn(true);
        when(sheet.getFrameCount()).thenReturn(16);
        when(sheet.getFrame(6)).thenReturn(frame);

        Sonic1MonitorPowerUpObjectInstance powerUp =
                new Sonic1MonitorPowerUpObjectInstance(0x100, 0x100, 4, new DummyPlayer());
        powerUp.setServices(servicesWithRenderManager(renderManager));

        powerUp.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawPieces(anyList(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    private static ObjectServices servicesWithObjectManager(ObjectManager objectManager) {
        return new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
    }

    private static ObjectServices servicesWithRenderManager(ObjectRenderManager renderManager) {
        return new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
    }

    private static boolean isBroken(Sonic1MonitorObjectInstance monitor) {
        try {
            Field field = Sonic1MonitorObjectInstance.class.getDeclaredField("broken");
            field.setAccessible(true);
            return field.getBoolean(monitor);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
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
            rollHeight = 28;
            runHeight = 38;
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
