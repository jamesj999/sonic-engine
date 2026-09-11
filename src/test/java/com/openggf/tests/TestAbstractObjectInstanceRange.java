package com.openggf.tests;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.CameraBounds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for ROM-style out_of_range arithmetic in AbstractObjectInstance.
 */
public class TestAbstractObjectInstanceRange {

    @Test
    public void isInRangePreservesUnsignedSubtractionNearLevelStart() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);

        assertTrue(new RangeProbe(0x00E0).isInRangeForTest());
        assertTrue(new RangeProbe(0x01C0).isInRangeForTest());
        assertFalse(new RangeProbe(0x0280).isInRangeForTest());
    }

    @Test
    public void isInRangePreservesUnsignedSubtractionAcrossSignedCameraWrap() {
        int wrappedCameraX = Short.MIN_VALUE;
        AbstractObjectInstance.updateCameraBounds(wrappedCameraX, 0, wrappedCameraX + 319, 223, 0);

        assertTrue(new RangeProbe(0x80E0).isInRangeForTest());
        assertTrue(new RangeProbe(0x81C0).isInRangeForTest());
        assertFalse(new RangeProbe(0x8280).isInRangeForTest());
    }

    @Test
    public void renderSpriteBoundsUseExclusiveRightAndBottomEdges() {
        CameraBounds bounds = new CameraBounds(0, 0, 320, 224);

        assertTrue(bounds.containsRenderSpriteBounds(319, 223, 0, 0));
        assertFalse(bounds.containsRenderSpriteBounds(320, 223, 0, 0));
        assertFalse(bounds.containsRenderSpriteBounds(319, 224, 0, 0));
        assertTrue(bounds.containsRenderSpriteBounds(320, 224, 1, 1));
        assertFalse(bounds.containsRenderSpriteBounds(321, 224, 1, 1));
        assertFalse(bounds.containsRenderSpriteBounds(320, 225, 1, 1));
    }

    private static final class RangeProbe extends AbstractObjectInstance {
        private RangeProbe(int x) {
            super(new ObjectSpawn(x, 0, 0, 0, 0, false, 0), "RangeProbe");
        }

        private boolean isInRangeForTest() {
            return isInRange();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No rendering needed for this test probe.
        }
    }
}


