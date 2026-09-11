package com.openggf.game.rewind;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGenericRewindEligibilityCache {

    @Test
    void defaultObjectSubclassCaptureIsStableAcrossRepeatedCalls() {
        for (int i = 0; i < 3; i++) {
            assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(DefaultObject.class),
                    "call " + i + " must report default capture for a no-override subclass");
        }
    }

    @Test
    void overridingObjectSubclassCaptureIsStableAcrossRepeatedCalls() {
        for (int i = 0; i < 3; i++) {
            assertFalse(GenericRewindEligibility.usesDefaultObjectSubclassCapture(OverrideObject.class),
                    "call " + i + " must report non-default capture for an overriding subclass");
        }
    }

    @Test
    void defaultBadnikSubclassCaptureIsStableAcrossRepeatedCalls() {
        for (int i = 0; i < 3; i++) {
            assertTrue(GenericRewindEligibility.usesDefaultBadnikSubclassCapture(DefaultBadnik.class),
                    "call " + i + " must report default badnik capture for a no-override badnik");
            assertFalse(GenericRewindEligibility.usesDefaultObjectSubclassCapture(DefaultBadnik.class),
                    "call " + i + ": badniks inherit AbstractBadnikInstance's concrete override");
        }
    }

    @Test
    void overridingBadnikSubclassCaptureIsStableAcrossRepeatedCalls() {
        for (int i = 0; i < 3; i++) {
            assertFalse(GenericRewindEligibility.usesDefaultBadnikSubclassCapture(OverrideBadnik.class),
                    "call " + i + " must report non-default badnik capture for an overriding badnik");
        }
    }

    @Test
    void abstractBaseTypesNeverUseDefaultCapture() {
        for (int i = 0; i < 3; i++) {
            assertFalse(GenericRewindEligibility.usesDefaultObjectSubclassCapture(AbstractObjectInstance.class));
            assertFalse(GenericRewindEligibility.usesDefaultBadnikSubclassCapture(AbstractBadnikInstance.class));
        }
    }

    private static class DefaultObject extends AbstractObjectInstance {
        private int phase;

        DefaultObject() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "DefaultObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static class OverrideObject extends DefaultObject {
        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot s) {
            super.restoreRewindState(s);
        }
    }

    private static class DefaultBadnik extends AbstractBadnikInstance {
        private int aiTimer;

        DefaultBadnik() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "DefaultBadnik");
        }

        @Override
        protected void updateMovement(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class OverrideBadnik extends DefaultBadnik {
        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot s) {
            super.restoreRewindState(s);
        }
    }
}
