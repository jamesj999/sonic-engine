package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.game.PlayableEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link AbstractBadnikInstance} rewind capture/restore
 * of badnik-specific movement fields (currentX, currentY, xVelocity, yVelocity,
 * animTimer, animFrame, facingLeft).
 *
 * <p>Tests that these 7 fields round-trip through
 * {@link AbstractObjectInstance#captureRewindState()} /
 * {@link AbstractObjectInstance#restoreRewindState(PerObjectRewindSnapshot)}.
 *
 * <p>Uses a minimal concrete subclass ({@code TestBadnik}) with no-op overrides.
 * The constructor does NOT go through {@link ObjectManager}.
 */
class TestAbstractBadnikInstanceRewindCapture {

    // ------------------------------------------------------------------
    // Minimal concrete subclass
    // ------------------------------------------------------------------

    private static final class TestBadnik extends AbstractBadnikInstance {

        TestBadnik(ObjectSpawn spawn) {
            super(spawn, "TestBadnik");
        }

        @Override
        protected void updateMovement(int vIntRunCount, PlayableEntity player) {
            // no-op
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ObjectSpawn spawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x01, 0, 0, false, 0);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void roundTrip_badnikMovementFields() {
        TestBadnik badnik = new TestBadnik(spawn(100, 200));

        // Set all 7 badnik-specific fields
        badnik.currentX = 500;
        badnik.currentY = 600;
        badnik.xVelocity = 1000;
        badnik.yVelocity = -500;
        badnik.animTimer = 10;
        badnik.animFrame = 3;
        badnik.facingLeft = true;

        PerObjectRewindSnapshot snap = badnik.captureRewindState();

        // Verify capture includes badnik extra (indirectly via restore test)
        // Mutate all fields
        badnik.currentX = 999;
        badnik.currentY = 999;
        badnik.xVelocity = 9999;
        badnik.yVelocity = 9999;
        badnik.animTimer = 99;
        badnik.animFrame = 99;
        badnik.facingLeft = false;

        // Restore and verify all 7 fields round-trip
        badnik.restoreRewindState(snap);
        assertEquals(500, badnik.currentX, "currentX should be restored");
        assertEquals(600, badnik.currentY, "currentY should be restored");
        assertEquals(1000, badnik.xVelocity, "xVelocity should be restored");
        assertEquals(-500, badnik.yVelocity, "yVelocity should be restored");
        assertEquals(10, badnik.animTimer, "animTimer should be restored");
        assertEquals(3, badnik.animFrame, "animFrame should be restored");
        assertTrue(badnik.facingLeft, "facingLeft should be restored to true");
    }

    @Test
    void roundTrip_badnikMovementFieldsZeroValues() {
        TestBadnik badnik = new TestBadnik(spawn(0, 0));

        // All fields should start at default (0 or false)
        assertEquals(0, badnik.currentX);
        assertEquals(0, badnik.currentY);
        assertEquals(0, badnik.xVelocity);
        assertEquals(0, badnik.yVelocity);
        assertEquals(0, badnik.animTimer);
        assertEquals(0, badnik.animFrame);
        assertFalse(badnik.facingLeft);

        // Capture defaults
        PerObjectRewindSnapshot snap = badnik.captureRewindState();

        // Mutate
        badnik.currentX = 123;
        badnik.currentY = 456;
        badnik.xVelocity = 789;
        badnik.yVelocity = 321;
        badnik.animTimer = 12;
        badnik.animFrame = 5;
        badnik.facingLeft = true;

        // Restore and verify all zero/false values are restored
        badnik.restoreRewindState(snap);
        assertEquals(0, badnik.currentX);
        assertEquals(0, badnik.currentY);
        assertEquals(0, badnik.xVelocity);
        assertEquals(0, badnik.yVelocity);
        assertEquals(0, badnik.animTimer);
        assertEquals(0, badnik.animFrame);
        assertFalse(badnik.facingLeft);
    }

    @Test
    void roundTrip_negativeBadnikVelocities() {
        TestBadnik badnik = new TestBadnik(spawn(50, 75));

        // Test negative velocities
        badnik.xVelocity = -2000;
        badnik.yVelocity = -1500;

        PerObjectRewindSnapshot snap = badnik.captureRewindState();

        badnik.xVelocity = 0;
        badnik.yVelocity = 0;

        badnik.restoreRewindState(snap);
        assertEquals(-2000, badnik.xVelocity);
        assertEquals(-1500, badnik.yVelocity);
    }

    @Test
    void roundTrip_largeAnimationFrameValues() {
        TestBadnik badnik = new TestBadnik(spawn(0, 0));

        // Test large frame values (simulate many animation frames)
        badnik.animTimer = 255;
        badnik.animFrame = 127;

        PerObjectRewindSnapshot snap = badnik.captureRewindState();

        badnik.animTimer = 0;
        badnik.animFrame = 0;

        badnik.restoreRewindState(snap);
        assertEquals(255, badnik.animTimer);
        assertEquals(127, badnik.animFrame);
    }

    @Test
    void roundTrip_badnikInheritedObjectFields() {
        TestBadnik badnik = new TestBadnik(spawn(10, 20));

        // Set both badnik-specific and inherited AbstractObjectInstance fields
        badnik.currentX = 300;
        badnik.facingLeft = true;
        badnik.setSlotIndex(5);
        badnik.setDestroyed(true);

        PerObjectRewindSnapshot snap = badnik.captureRewindState();

        // Mutate all
        badnik.currentX = 999;
        badnik.facingLeft = false;
        badnik.setSlotIndex(99);
        badnik.setDestroyed(false);

        // Restore and verify both layers round-trip
        badnik.restoreRewindState(snap);
        assertEquals(300, badnik.currentX, "badnik currentX should be restored");
        assertTrue(badnik.facingLeft, "badnik facingLeft should be restored");
        assertEquals(5, badnik.getSlotIndex(), "inherited slotIndex should be restored");
        assertTrue(badnik.isDestroyed(), "inherited destroyed flag should be restored");
    }

    @Test
    void roundTrip_consecutiveSnapshotRestoreAndRecapture() {
        TestBadnik badnik = new TestBadnik(spawn(0, 0));

        // First state
        badnik.currentX = 100;
        badnik.xVelocity = 200;
        badnik.facingLeft = false;
        PerObjectRewindSnapshot snap1 = badnik.captureRewindState();

        // Mutate
        badnik.currentX = 500;
        badnik.xVelocity = 600;
        badnik.facingLeft = true;

        // Restore to snap1
        badnik.restoreRewindState(snap1);
        assertEquals(100, badnik.currentX);
        assertEquals(200, badnik.xVelocity);
        assertFalse(badnik.facingLeft);

        // Re-capture (should match snap1)
        PerObjectRewindSnapshot snap2 = badnik.captureRewindState();
        assertEquals(snap1.destroyed(), snap2.destroyed());
        // Note: we cannot directly compare BadnikRewindExtra from the public API,
        // but we can verify that restored state re-captures identically

        // Mutate again
        badnik.currentX = 999;
        badnik.xVelocity = 999;
        badnik.facingLeft = false;

        // Restore to snap2 (which should equal snap1) and verify match
        badnik.restoreRewindState(snap2);
        assertEquals(100, badnik.currentX);
        assertEquals(200, badnik.xVelocity);
        assertFalse(badnik.facingLeft);
    }
}
