package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameId;
import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
class TestHczTransitionBubbleInstance {

    @BeforeEach
    void setUp() {
        TestEnvironment.activeGameplayMode();
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void initDispatchDrawsWithoutMovingThenUsesRomCarrierAcceleration() {
        HczTransitionBubbleInstance bubble = bubble(0x80, 0x60, 0xC0, false);

        assertEquals(0x800, bubble.getXVelocityForTest(),
                "loc_6A710 overwrites sub_6A940's signed helper result with abs(delta*2)<<4");
        bubble.update(0, null);
        assertEquals(0x80, bubble.getX());
        assertEquals(0x60, bubble.getY());
        assertFalse(bubble.isHiddenForTest());

        bubble.update(1, null);
        assertEquals(0x900, bubble.getXVelocityForTest());
        assertEquals(0x89, bubble.getX());
        assertEquals(0x62, bubble.getY());
        assertTrue(bubble.isHiddenForTest());

        bubble.update(2, null);
        assertEquals(0xA00, bubble.getXVelocityForTest());
        assertEquals(0x93, bubble.getX());
        assertEquals(0x64, bubble.getY());
        assertFalse(bubble.isHiddenForTest());
    }

    @Test
    void randomAnimationPhaseCanKeepFirstActiveDispatchVisible() {
        HczTransitionBubbleInstance bubble = bubble(0x80, 0x60, 0xC0, true);

        bubble.update(0, null);
        bubble.update(1, null);

        assertFalse(bubble.isHiddenForTest());
    }

    @Test
    void precedingRenderFlagGateDeletesOffscreenBubbleBeforeMovement() {
        HczTransitionBubbleInstance bubble = bubble(0x500, 0x60, 0x540, false);

        bubble.update(0, null);
        bubble.update(1, null);

        assertTrue(bubble.isDestroyed());
        assertEquals(0x500, bubble.getX());
        assertEquals(0x60, bubble.getY());
    }

    @Test
    void rewindRoundTripRecreatesTransitionBubbleWithIdentityAndActiveAnimationState() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S3K);
        var manager = harness.objectManager();
        HczTransitionBubbleInstance source = manager.createDynamicObject(
                () -> new HczTransitionBubbleInstance(0x80, 0x60, 0xC0, 2, false));
        source.update(0, null);
        source.update(1, null);
        ObjectRefId sourceId = manager.captureIdentityContext().requireIdentityTable().idFor(source);
        int sourceX = source.getX();
        int sourceY = source.getY();
        int sourceVelocity = source.getXVelocityForTest();
        boolean sourceHidden = source.isHiddenForTest();

        harness.roundTrip();

        var restored = manager.getActiveObjects().stream()
                .filter(HczTransitionBubbleInstance.class::isInstance)
                .map(HczTransitionBubbleInstance.class::cast)
                .toList();
        assertEquals(1, restored.size(), "restore must retain exactly one transition-bubble object slot");
        HczTransitionBubbleInstance bubble = restored.getFirst();
        assertNotSame(source, bubble, "rewind must recreate rather than retain the live bubble instance");
        assertEquals(sourceId, manager.captureIdentityContext().requireIdentityTable().idFor(bubble));
        assertEquals(sourceX, bubble.getX());
        assertEquals(sourceY, bubble.getY());
        assertEquals(sourceVelocity, bubble.getXVelocityForTest());
        assertEquals(sourceHidden, bubble.isHiddenForTest());
    }

    private static HczTransitionBubbleInstance bubble(
            int x, int y, int targetX, boolean secondAnimationStep) {
        HczTransitionBubbleInstance bubble = new HczTransitionBubbleInstance(
                x, y, targetX, 2, secondAnimationStep);
        bubble.setServices(TestEnvironment.objectServices());
        return bubble;
    }
}
