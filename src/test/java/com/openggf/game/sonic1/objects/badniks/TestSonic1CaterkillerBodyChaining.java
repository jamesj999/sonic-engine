package com.openggf.game.sonic1.objects.badniks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tests.TestEnvironment;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1CaterkillerBodyChaining {
    private ObjectManager objectManager;
    private ObjectServices objectServices;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        objectServices = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };
        objectManager = new ObjectManager(List.of(), new TestNoOpObjectRegistry(), 0, null, null,
                null, GameServices.camera(), objectServices);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void bodySegmentUsesImmediateParentStateForMovementAndYDelta() {
        LevelManager levelManager = GameServices.level();
        Sonic1CaterkillerBadnikInstance head = new Sonic1CaterkillerBadnikInstance(
                new ObjectSpawn(0, 0, 0x78, 0, 0, false, 0));

        FakeParentState parentState = new FakeParentState();
        parentState.secondaryState = 1; // moving
        parentState.inertia = 0;
        parentState.xVelocity = 0x100; // +1 pixel/frame in 16.8 fixed-point
        parentState.ringBuffer[4] = 2; // apply +2 Y delta

        Sonic1CaterkillerBodyInstance body = new Sonic1CaterkillerBodyInstance(
                head, parentState, 100, 50, true, false, 0, 4);

        body.update(0, null);

        assertEquals(101, body.getX(), "Body X should follow immediate parent velocity");
        assertEquals(52, body.getY(), "Body Y should consume immediate parent ring-buffer delta");
        assertEquals(2, body.readRingBuffer(4), "Body should publish consumed delta for its own child chain");
    }

    @Test
    public void bodySegmentUsesHurtCollisionCategory() {
        LevelManager levelManager = GameServices.level();
        Sonic1CaterkillerBadnikInstance head = new Sonic1CaterkillerBadnikInstance(
                new ObjectSpawn(0, 0, 0x78, 0, 0, false, 0));
        FakeParentState parentState = new FakeParentState();

        Sonic1CaterkillerBodyInstance body = new Sonic1CaterkillerBodyInstance(
                head, parentState, 0, 0, true, false, 0, 4);

        assertEquals(0x8B, body.getCollisionFlags(), "Body touch should route through HURT category with size index 0x0B");
    }

    @Test
    public void bodyTouchTriggersFragmentBehavior() {
        LevelManager levelManager = GameServices.level();
        Sonic1CaterkillerBadnikInstance head = new Sonic1CaterkillerBadnikInstance(
                new ObjectSpawn(0, 0, 0x78, 0, 0, false, 0));
        FakeParentState parentState = new FakeParentState();

        Sonic1CaterkillerBodyInstance body = new Sonic1CaterkillerBodyInstance(
                head, parentState, 0, 0, true, false, 0, 4);

        assertFalse(head.isFragmenting(), "Head should not start in fragment mode");
        assertFalse(body.isFragmenting(), "Body should not start in fragment mode");

        body.onTouchResponse(null, new TouchResponseResult(0x0B, 8, 8, TouchCategory.HURT), 0);

        assertTrue(head.isFragmenting(), "Body contact should trigger head fragment mode");
        body.update(0, null);
        assertTrue(body.isFragmenting(), "Body should enter fragment mode after head fragment trigger");
    }

    @Test
    public void bodyDeletesWhenHeadIsUnloaded() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        LevelManager levelManager = GameServices.level();
        Sonic1CaterkillerBadnikInstance head = new Sonic1CaterkillerBadnikInstance(
                new ObjectSpawn(0, 0, 0x78, 0, 0, false, 0));
        FakeParentState parentState = new FakeParentState();

        Sonic1CaterkillerBodyInstance body = new Sonic1CaterkillerBodyInstance(
                head, parentState, 32, 32, true, false, 0, 4);

        head.onUnload();
        body.update(100, null);

        assertFalse(body.isDestroyed(),
                "Body should survive the first post-unload update while entering delete state");
        assertFalse(body.isFragmenting(), "Body should not enter fragment mode when head is unloading");

        body.update(101, null);

        assertTrue(body.isDestroyed(),
                "Body should delete on its next update after the head unload path starts");
    }

    @Test
    public void fragmentingBodyDeletesWhenOffScreen() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        LevelManager levelManager = GameServices.level();
        Sonic1CaterkillerBadnikInstance head = new Sonic1CaterkillerBadnikInstance(
                new ObjectSpawn(0, 0, 0x78, 0, 0, false, 0));
        FakeParentState parentState = new FakeParentState();

        Sonic1CaterkillerBodyInstance body = new Sonic1CaterkillerBodyInstance(
                head, parentState, 1000, 0, true, false, 0, 4);

        head.triggerFragmentFromBodyHit();
        body.update(0, null); // enters fragment mode
        body.update(1, null); // fragment physics + refreshes the ROM obRender latch

        assertFalse(body.isDestroyed(),
                "The first fragment update should only refresh the stale on-screen render flag");

        body.update(2, null); // next exec observes the cleared render flag and deletes

        assertTrue(body.isDestroyed(), "Fragmenting body segments should self-delete after obRender clears");
    }

    @Test
    public void normalHeadDestructionKeepsBodySegmentsCollidableThroughNextVblank() throws Exception {
        TestableCaterkillerHead head = new TestableCaterkillerHead(
                new ObjectSpawn(0, 0, 0x78, 0, 0, false, 0));
        head.setServices(objectServices);
        head.setSlotIndex(32);
        objectManager.initVblaCounter(0x2000);

        FakeParentState parentState = new FakeParentState();
        Sonic1CaterkillerBodyInstance body = new Sonic1CaterkillerBodyInstance(
                head, parentState, 16, 0, true, false, 0, 4);
        addBodySegment(head, body);

        head.destroyForTest();

        assertFalse(body.isDestroyed(),
                "Destroying the Caterkiller head should leave body slots alive until the next exec pass");
        assertEquals(32, objectManager.getActiveObjects().stream()
                        .filter(ExplosionObjectInstance.class::isInstance)
                        .map(ExplosionObjectInstance.class::cast)
                        .mapToInt(ExplosionObjectInstance::getSlotIndex)
                        .findFirst()
                        .orElse(-1),
                "The replacement explosion should inherit the head slot immediately");

        // Destroying the head in Sonic's slot should not make the body vanish
        // before the next VBlank touch pass. The ROM's lingering body bug relies
        // on the body still being collidable on the next frame.
        body.update(0x2000, null);

        assertFalse(body.isDestroyed(),
                "Body segments should survive the same VBlank's later object exec");
        assertEquals(0x8B, body.getCollisionFlags(),
                "Body segments must stay harmful after the head becomes ExplosionItem");

        body.update(0x2001, null);

        assertFalse(body.isDestroyed(),
                "Body segments should still exist for the following VBlank's touch pass");
        assertEquals(0x8B, body.getCollisionFlags(),
                "The next VBlank must still see the Caterkiller body as a hurt object");

        body.update(0x2002, null);

        assertFalse(body.isDestroyed(),
                "Entering delete state should not destroy the body until its later exec pass");
        assertEquals(0, body.getCollisionFlags(),
                "Once the linger window ends, the body should stop participating in touch");
    }

    @SuppressWarnings("unchecked")
    private static void addBodySegment(Sonic1CaterkillerBadnikInstance head, Sonic1CaterkillerBodyInstance body)
            throws Exception {
        Field field = Sonic1CaterkillerBadnikInstance.class.getDeclaredField("bodySegments");
        field.setAccessible(true);
        ((List<Sonic1CaterkillerBodyInstance>) field.get(head)).add(body);
    }

    private static final class FakeParentState implements CaterkillerParentState {
        int secondaryState;
        int inertia;
        int xVelocity;
        int animControl;
        final byte[] ringBuffer = new byte[16];

        @Override
        public int getSecondaryState() {
            return secondaryState;
        }

        @Override
        public int getInertia() {
            return inertia;
        }

        @Override
        public int getXVelocity() {
            return xVelocity;
        }

        @Override
        public int getAnimControl() {
            return animControl;
        }

        @Override
        public int readRingBuffer(int index) {
            return ringBuffer[index & 0x0F];
        }

        @Override
        public void writeRingBuffer(int index, int value) {
            ringBuffer[index & 0x0F] = (byte) value;
        }
    }

    private static final class TestableCaterkillerHead extends Sonic1CaterkillerBadnikInstance {
        private TestableCaterkillerHead(ObjectSpawn spawn) {
            super(spawn);
        }

        private void destroyForTest() {
            super.destroyBadnik(null);
        }
    }

    private static final class TestNoOpObjectRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "noop";
        }
    }
}

