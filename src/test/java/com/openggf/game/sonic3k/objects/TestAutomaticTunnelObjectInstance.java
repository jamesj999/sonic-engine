package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAutomaticTunnelObjectInstance {
    @Test
    void sidekickProcessingUsesNativeP2QueryOnly() {
        AutomaticTunnelObjectInstance tunnel = new AutomaticTunnelObjectInstance(
                new ObjectSpawn(0x0F60, 0x0578, 0x24, 0, 0, false, 0));
        TestPlayableSprite nativeP2 = new TestPlayableSprite();
        TestPlayableSprite extraSidekick = new TestPlayableSprite();
        tunnel.setServices(new TestObjectServices()
                .withSidekicks(List.of(nativeP2, extraSidekick))
                .withIsolatedObjectManager());

        TestPlayableSprite main = new TestPlayableSprite();
        main.setCentreX((short) 0x0100);
        main.setCentreY((short) 0x0100);
        nativeP2.setCentreX((short) 0x0F60);
        nativeP2.setCentreY((short) 0x0578);
        extraSidekick.setCentreX((short) 0x0F60);
        extraSidekick.setCentreY((short) 0x0578);

        tunnel.update(0, main);

        assertFalse(main.isObjectControlled());
        assertTrue(nativeP2.isObjectControlled());
        assertFalse(extraSidekick.isObjectControlled());
    }

    @Test
    void captureUsesFullObjectControlWithoutWritingSeparateControlLock() {
        AutomaticTunnelObjectInstance tunnel = new AutomaticTunnelObjectInstance(
                new ObjectSpawn(0x0F60, 0x0578, 0x24, 0, 0, false, 0));
        tunnel.setServices(new TestObjectServices().withIsolatedObjectManager());

        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0F60);
        player.setCentreY((short) 0x0578);
        player.setControlLocked(false);
        player.setObjectControlled(false);

        tunnel.update(0, player);

        assertTrue(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertTrue(player.isTouchResponseSuppressedByObjectControl());
        assertFalse(player.isControlLocked(),
                "move.b #$81,object_control does not write the separate Ctrl_1_locked byte");

        for (int frame = 1; frame <= 80 && player.isObjectControlled(); frame++) {
            tunnel.update(frame, player);
        }

        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
        assertFalse(player.isControlLocked());
    }

    @Test
    void captureWritesNativePositionWordsWithoutForcingRollingStatus() {
        AutomaticTunnelObjectInstance tunnel = new AutomaticTunnelObjectInstance(
                new ObjectSpawn(0x0D40, 0x0770, 0x24, 1, 0, false, 0));
        tunnel.setServices(new TestObjectServices().withIsolatedObjectManager());

        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0D40);
        player.setCentreY((short) 0x0770);
        player.setSubpixelRaw(0x5100, 0x8500);
        player.setRolling(false);

        tunnel.update(0, player);

        assertEquals(0x5100, player.getXSubpixelRaw());
        assertEquals(0x8500, player.getYSubpixelRaw());
        assertFalse(player.getRolling(), "move.b #2,anim does not set Status_Roll");
        assertEquals(2, player.getAnimationId());
        assertTrue(player.getAir());
    }

    @Test
    void maintainedExitVelocityMovesPlayerOnFinalWaypointFrame() {
        AutomaticTunnelObjectInstance tunnel = new AutomaticTunnelObjectInstance(
                new ObjectSpawn(0x0D40, 0x0770, 0x24, 0x42, 0, false, 0));
        tunnel.setServices(new TestObjectServices().withIsolatedObjectManager());

        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0D40);
        player.setCentreY((short) 0x0770);
        player.setSubpixelRaw(0x5100, 0x8500);

        for (int frame = 0; frame <= 40; frame++) {
            tunnel.update(frame, player);
        }

        assertEquals(0x0D70, player.getCentreX());
        assertEquals(0x0678, player.getCentreY(),
                "loc_2970A falls through to loc_29768 after snapping to y_pos $688");
        assertEquals(0x5100, player.getXSubpixelRaw());
        assertEquals(0x8500, player.getYSubpixelRaw());
    }

    @Test
    void lbz2ModeSpawnsTunnelExhaustAtPlayerExitWithCurrentVelocity() {
        RecordingServices services = new RecordingServices();
        AutomaticTunnelObjectInstance tunnel = new AutomaticTunnelObjectInstance(
                new ObjectSpawn(0x0F60, 0x0578, 0x24, 0x60, 0, false, 0));
        tunnel.setServices(services);

        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0F60);
        player.setCentreY((short) 0x0578);

        for (int frame = 0; frame < 120 && services.children.isEmpty(); frame++) {
            tunnel.update(frame, player);
        }

        assertEquals(1, services.children.size(),
                "ROM bit 5 creates Obj_TunnelExhaustControl when the automatic tunnel route exits");
        AbstractObjectInstance child = services.children.get(0);
        assertInstanceOf(TunnelExhaustControlObjectInstance.class, child);
        assertEquals(player.getCentreX(), child.getX());
        assertEquals(0x0378, child.getY(),
                "the exhaust is allocated at the final waypoint before loc_29768 applies retained exit velocity");
        assertEquals(0, intField(child, "subtype"),
                "Obj_AutomaticTunnel exit spawn does not copy subtype; subtype 0 selects directional exhaust");
        assertEquals(0, intField(child, "xVel"));
        assertEquals(0xF000, intField(child, "yVel") & 0xFFFF);
    }

    @Test
    void tunnelExhaustParticleRendersRomWaterFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_TUNNEL_EXHAUST)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        TunnelExhaustParticleInstance particle = new TunnelExhaustParticleInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0),
                0, 0x400, true);
        particle.setServices(new RenderingServices(renderManager));

        particle.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.LBZ_TUNNEL_EXHAUST);
        verify(renderer).drawFrameIndex(1, 0x1800, 0x0640, false, false, 2);
    }

    @Test
    void tunnelExhaustControlUsesRomVerticalWaterFrameForUpwardExit() {
        RecordingServices services = new RecordingServices();
        services.act = 1; // the water exhaust is the act 2 branch of loc_298F4
        TunnelExhaustControlObjectInstance control = new TunnelExhaustControlObjectInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0),
                0, 0, -0x1000);
        control.setServices(services);

        control.update(0, new TestPlayableSprite());

        assertEquals(1, services.children.size());
        AbstractObjectInstance child = services.children.get(0);
        assertInstanceOf(TunnelExhaustParticleInstance.class, child);
        assertEquals(1, intField(child, "mappingFrame"));
        assertEquals(0x86, intField(child, "renderFlags"));
        assertEquals(0, motionVelocity(child, "xVel"));
        assertEquals(0xFA00, motionVelocity(child, "yVel") & 0xFFFF);
        assertFalse(booleanField(child, "horizontal"));
    }

    @Test
    void tunnelExhaustControlUsesRomHorizontalWaterFrameForSideExit() {
        RecordingServices services = new RecordingServices();
        services.act = 1; // the water exhaust is the act 2 branch of loc_298F4
        TunnelExhaustControlObjectInstance control = new TunnelExhaustControlObjectInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0),
                0, 0x1000, 0);
        control.setServices(services);

        control.update(0, new TestPlayableSprite());

        assertEquals(1, services.children.size());
        AbstractObjectInstance child = services.children.get(0);
        assertInstanceOf(TunnelExhaustParticleInstance.class, child);
        assertEquals(0, intField(child, "mappingFrame"));
        assertEquals(0x84, intField(child, "renderFlags"));
        assertEquals(0x0600, motionVelocity(child, "xVel") & 0xFFFF);
        assertEquals(0, motionVelocity(child, "yVel"));
        assertTrue(booleanField(child, "horizontal"));
    }

    @Test
    void tunnelExhaustControlPuffsDissipatingSmokeInActOne() {
        RecordingServices services = new RecordingServices();
        TunnelExhaustControlObjectInstance control = new TunnelExhaustControlObjectInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0),
                0, 0x0800, -0x0400);
        control.setServices(services);

        control.update(0, new TestPlayableSprite());

        assertEquals(1, services.children.size(),
                "loc_298F4 routes act 1 to Obj_TunnelExSmoke");
        AbstractObjectInstance child = services.children.get(0);
        assertInstanceOf(FireShieldDissipateInstance.class, child);
        assertEquals(0x1800, child.getX());
        assertEquals(0x0640, child.getY());
        assertEquals(0x0800, motionVelocity(child, "xVel"),
                "Obj_TunnelExSmoke copies the controller's x_vel");
        assertEquals(0xFC00, motionVelocity(child, "yVel") & 0xFFFF,
                "Obj_TunnelExSmoke copies the controller's y_vel");

        // ROM gates on (Level_frame_counter & 3) == 0 — nothing on the next three frames.
        for (int frame = 1; frame <= 3; frame++) {
            control.update(frame, new TestPlayableSprite());
        }
        assertEquals(1, services.children.size());

        control.update(4, new TestPlayableSprite());
        assertEquals(2, services.children.size());
    }

    @Test
    void actTwoTunnelExhaustControlStillSpraysWaterExhaust() {
        RecordingServices services = new RecordingServices();
        services.act = 1;
        TunnelExhaustControlObjectInstance control = new TunnelExhaustControlObjectInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0),
                0, 0, -0x1000);
        control.setServices(services);

        control.update(0, new TestPlayableSprite());

        assertEquals(1, services.children.size());
        assertInstanceOf(TunnelExhaustParticleInstance.class, services.children.get(0));
    }

    @Test
    void dissipatingSmokeDriftsWithoutGravityAndExpiresAfterFourFrames() {
        FireShieldDissipateInstance smoke = new FireShieldDissipateInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0), 0x0100, 0);
        smoke.setServices(new TestObjectServices());

        TestPlayableSprite player = new TestPlayableSprite();
        smoke.update(0, player);

        assertEquals(0x1801, smoke.getX(), "MoveSprite2 applies x_vel at one pixel per frame");
        assertEquals(0x0640, smoke.getY(), "MoveSprite2 does not apply gravity");
        assertEquals(1, intField(smoke, "mappingFrame"));

        // Mapping frames 1..4 hold for four frames each; frame 5 deletes the sprite.
        for (int frame = 1; frame < 4 * 4 - 1 && !smoke.isDestroyed(); frame++) {
            smoke.update(frame, player);
        }
        assertEquals(4, intField(smoke, "mappingFrame"));
        assertFalse(smoke.isDestroyed());

        smoke.update(16, player);
        assertTrue(smoke.isDestroyed(), "mapping_frame 5 hits Delete_Current_Sprite");
    }

    @Test
    void dissipatingSmokeRendersExplosionArtFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(ObjectArtKeys.EXPLOSION)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        FireShieldDissipateInstance smoke = new FireShieldDissipateInstance(
                new ObjectSpawn(0x1800, 0x0640, 0, 0, 0, false, 0), 0, 0);
        smoke.setServices(new RenderingServices(renderManager));

        smoke.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndex(1, 0x1800, 0x0640, false, false);
    }

    private static int intField(Object instance, String name) {
        try {
            var field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean booleanField(Object instance, String name) {
        try {
            var field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int motionVelocity(Object instance, String fieldName) {
        try {
            var motionField = instance.getClass().getDeclaredField("motion");
            motionField.setAccessible(true);
            Object motion = motionField.get(instance);
            var velocityField = motion.getClass().getDeclaredField(fieldName);
            velocityField.setAccessible(true);
            return velocityField.getInt(motion);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingServices extends TestObjectServices {
        private final ObjectManager objectManager;
        private final List<AbstractObjectInstance> children = new ArrayList<>();
        private int act;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            when(objectManager.solidContacts()).thenReturn(
                    mock(com.openggf.level.objects.ObjectSolidContactController.class));
            doAnswer(invocation -> {
                children.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
        }

        @Override
        public int currentAct() {
            return act;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }

    private static final class RenderingServices extends TestObjectServices {
        private final ObjectRenderManager renderManager;

        private RenderingServices(ObjectRenderManager renderManager) {
            this.renderManager = renderManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }
    }
}
