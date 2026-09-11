package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHCZConveyorBeltObjectInstance {

    {
        TestEnvironment.activeGameplayMode();
    }
    private final Camera camera = GameServices.camera();

    @BeforeEach
    void setUp() {
        HCZConveyorBeltObjectInstance.resetLoadArray();
    }

    @AfterEach
    void tearDown() throws Exception {
        HCZConveyorBeltObjectInstance.resetLoadArray();
        constructionContext().remove();
        SessionManager.clear();
    }

    @Test
    void pairedTopAndBottomSubtypesDoNotDeduplicate() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestObjectServices services = servicesFor(camera, player);
        player.setCentreX((short) 0x0800);

        HCZConveyorBeltObjectInstance top = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);
        HCZConveyorBeltObjectInstance bottom = buildBelt(services, 0x0B28, 0x022A, 0x10, 1);

        top.update(1, player);
        bottom.update(1, player);

        assertFalse(top.isDestroyed());
        assertFalse(bottom.isDestroyed());
    }

    @Test
    void duplicateRawSubtypeStillDeduplicates() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestObjectServices services = servicesFor(camera, player);
        player.setCentreX((short) 0x0800);

        HCZConveyorBeltObjectInstance first = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);
        HCZConveyorBeltObjectInstance duplicate = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        first.update(1, player);
        duplicate.update(1, player);

        assertFalse(first.isDestroyed());
        assertTrue(duplicate.isDestroyed());
    }

    @Test
    void topReleaseCooldownDoesNotBlockBottomPartnerCapture() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestObjectServices services = servicesFor(camera, player);
        player.setCentreX((short) 0x0C00);
        player.setCentreY((short) 0x0221);
        player.setYSpeed((short) 0);

        HCZConveyorBeltObjectInstance top = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);
        HCZConveyorBeltObjectInstance bottom = buildBelt(services, 0x0B28, 0x022A, 0x10, 1);

        top.update(50, player);
        bottom.update(50, player);
        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertEquals(0x63, player.getMappingFrame());
        assertEquals(0x0214, player.getCentreY() & 0xFFFF);

        player.setJumpInputPressed(true);
        player.setLogicalInputState(false, false, false, false, true, true);
        top.update(51, player);
        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertEquals(0x0214, player.getCentreY() & 0xFFFF,
                "Obj_HCZConveyorBelt loc_312D4 does not write y_pos when releasing");
        assertEquals(-0x500, player.getYSpeed());

        player.setJumpInputPressed(false);
        player.setCentreY((short) 0x021B);
        player.setGSpeed((short) 1);

        top.update(52, player);
        bottom.update(52, player);

        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertEquals(0x65, player.getMappingFrame());
        assertEquals(0x0216, player.getCentreY() & 0xFFFF);
    }

    @Test
    void logicalJumpPressReleasesAfterRawEdgeIsConsumed() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        TestObjectServices services = servicesFor(camera, player);
        player.setCentreX((short) 0x0C00);
        player.setCentreY((short) 0x0221);
        player.setYSpeed((short) 0);
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, player);
        assertTrue(player.isObjectControlled());

        player.setJumpInputPressed(false, false);
        player.setLogicalInputState(false, false, false, false, true, true);
        belt.update(11, player);

        assertFalse(player.isObjectControlled());
        assertTrue(player.getAir());
        assertTrue(player.getRolling());
        assertEquals(-0x500, player.getYSpeed());
    }

    @Test
    void nativeP2CaptureUsesObjectPlayerQueryWhenRawSidekickListIsEmpty() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0800, (short) 0x0200);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0C00, (short) 0x0221);
        nativeP2.setYSpeed((short) 0);
        ObjectServices services = new QueryOnlyPlayerServices(camera, main, List.of(nativeP2), List.of());
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, main);

        assertTrue(nativeP2.isObjectControlled(),
                "HCZ conveyor has native P1/P2 slots, so P2 must be resolved through ObjectPlayerQuery");
        assertEquals(0x63, nativeP2.getMappingFrame());
        assertEquals(0x0214, nativeP2.getCentreY() & 0xFFFF);
    }

    @Test
    void coarseBackCameraCullKeepsTrace3355BeltAliveForNativeP2Capture() throws Exception {
        camera.setX((short) 0x1A21);
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x1ABE, (short) 0x0368);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x19AA, (short) 0x0322);
        nativeP2.setYSpeed((short) 0x0318);
        nativeP2.setXSpeed((short) 0x02DF);
        ObjectServices services = new QueryOnlyPlayerServices(camera, main, List.of(nativeP2), List.of(nativeP2));
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x1928, 0x030B, 0x05, 0);

        belt.update(3355, main);

        assertFalse(belt.isDestroyed(),
                "Obj_HCZConveyorBelt culls against Camera_X_pos_coarse_back, not raw cameraX&$FF80");
        assertTrue(nativeP2.isObjectControlled());
        assertTrue(nativeP2.isObjectControlAllowsCpu());
        assertTrue(nativeP2.isObjectControlSuppressesMovement());
        assertEquals(0, nativeP2.getXSpeed());
        assertEquals(0, nativeP2.getYSpeed());
        assertEquals(0x63, nativeP2.getMappingFrame());
        assertEquals(0x031F, nativeP2.getCentreY() & 0xFFFF);
    }

    @Test
    void capturePreservesStatusRollLikeRom() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0C00, (short) 0x0221);
        TestObjectServices services = servicesFor(camera, player);
        player.setRolling(true);
        player.setCentreX((short) 0x0C00);
        player.setCentreY((short) 0x0221);
        player.setYSpeed((short) 0);
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, player);

        assertTrue(player.isObjectControlled());
        assertTrue(player.getRolling(), "Obj_HCZConveyorBelt capture does not bclr Status_Roll");
        assertEquals(7, player.getXRadius());
        assertEquals(14, player.getYRadius());
    }

    @Test
    void capturePreservesSubpixelsLikeRomYPosWrite() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0C00, (short) 0x0221);
        TestObjectServices services = servicesFor(camera, player);
        player.setYSpeed((short) 0);
        player.setSubpixelRaw(0x4800, 0x3000);
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, player);

        assertTrue(player.isObjectControlled());
        assertEquals(0x0214, player.getCentreY() & 0xFFFF);
        assertEquals(0x4800, player.getXSubpixelRaw());
        assertEquals(0x3000, player.getYSubpixelRaw());
    }

    @Test
    void activeMovementAndAnimationPreserveSubpixelsLikeRomWordWrites() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0C00, (short) 0x0221);
        TestObjectServices services = servicesFor(camera, player);
        player.setYSpeed((short) 0);
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, player);
        player.setSubpixelRaw(0x5200, 0x0400);
        player.setDirectionalInputPressed(false, false, true, false);

        belt.update(11, player);

        assertTrue(player.isObjectControlled());
        assertEquals(0x0C01, player.getCentreX() & 0xFFFF,
                "left input subtracts 1, then the rightward belt adds 2");
        assertEquals(0x0214, player.getCentreY() & 0xFFFF);
        assertEquals(0x5200, player.getXSubpixelRaw());
        assertEquals(0x0400, player.getYSubpixelRaw());
    }

    @Test
    void updatePlayerRemainsNativeP1WhenQueryOnlyReturnsNativeP2() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite updatePlayer = new TestablePlayableSprite("sonic", (short) 0x0C00, (short) 0x0221);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0C00, (short) 0x0221);
        updatePlayer.setYSpeed((short) 0);
        nativeP2.setYSpeed((short) 0);
        ObjectServices services = new QueryOnlyPlayerServices(camera, null, List.of(nativeP2), List.of());
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, updatePlayer);

        assertTrue(updatePlayer.isObjectControlled(),
                "The update player is the observable native P1 fallback when ObjectPlayerQuery has no main");
        assertTrue(nativeP2.isObjectControlled(),
                "The query-only sidekick should still occupy the native P2 conveyor slot");
    }

    @Test
    void extendedSidekickDoesNotShareNativeP2ConveyorState() throws Exception {
        camera.setX((short) 0x0C00);
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0800, (short) 0x0200);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0800, (short) 0x0221);
        TestablePlayableSprite extendedSidekick = new TestablePlayableSprite("knuckles", (short) 0x0C00, (short) 0x0221);
        nativeP2.setYSpeed((short) 0);
        extendedSidekick.setYSpeed((short) 0);
        List<PlayableEntity> sidekicks = List.of(nativeP2, extendedSidekick);
        ObjectServices services = new QueryOnlyPlayerServices(camera, main, sidekicks, sidekicks);
        HCZConveyorBeltObjectInstance belt = buildBelt(services, 0x0B28, 0x0200, 0x00, 0);

        belt.update(10, main);

        assertFalse(extendedSidekick.isObjectControlled(),
                "Additional engine sidekicks need separate state before they can participate in the conveyor");
    }

    private static HCZConveyorBeltObjectInstance buildBelt(
            ObjectServices services, int x, int y, int subtype, int renderFlags) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            HCZConveyorBeltObjectInstance belt = new HCZConveyorBeltObjectInstance(
                    new ObjectSpawn(x, y, 0x3E, subtype, renderFlags, false, 0));
            belt.setServices(services);
            return belt;
        } finally {
            context.remove();
        }
    }

    private static TestObjectServices servicesFor(Camera camera, PlayableEntity main) {
        return new TestObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> main, List::of);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ObjectServices> constructionContext() throws Exception {
        Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
        field.setAccessible(true);
        return (ThreadLocal<ObjectServices>) field.get(null);
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final Camera camera;
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private final List<PlayableEntity> rawSidekicks;

        private QueryOnlyPlayerServices(Camera camera, PlayableEntity main,
                                        List<? extends PlayableEntity> queriedSidekicks,
                                        List<PlayableEntity> rawSidekicks) {
            this.camera = camera;
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            this.rawSidekicks = List.copyOf(rawSidekicks);
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return rawSidekicks;
        }
    }
}
