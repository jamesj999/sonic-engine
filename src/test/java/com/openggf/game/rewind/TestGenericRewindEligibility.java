package com.openggf.game.rewind;

import com.openggf.game.sonic2.objects.badniks.MasherBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGenericRewindEligibility {
    @BeforeEach
    void clearEligibilityBeforeTest() {
        GenericRewindEligibility.clearForTest();
    }

    @AfterEach
    void clearEligibilityAfterTest() {
        GenericRewindEligibility.clearForTest();
    }

    @Test
    void classMustBeExplicitlyEligibleForSidecarCapture() {
        assertFalse(GenericRewindEligibility.isEligible(MasherBadnikInstance.class));
    }

    @Test
    void noOverrideObjectSubclassesUseDefaultObjectCapture() {
        assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(DefaultCapturedObject.class));
    }

    @Test
    void objectSubclassesWithConcreteOverridesDoNotUseDefaultObjectCapture() {
        assertFalse(GenericRewindEligibility.usesDefaultObjectSubclassCapture(OverrideCapturedObject.class));
        assertTrue(GenericRewindEligibility.declaresConcreteObjectRewindOverride(OverrideCapturedObject.class));
    }

    @Test
    void objectSubclassesWithInheritedConcreteOverridesDoNotUseDefaultObjectCapture() {
        assertFalse(GenericRewindEligibility.usesDefaultObjectSubclassCapture(InheritedOverrideCapturedObject.class));
        assertTrue(GenericRewindEligibility.declaresConcreteObjectRewindOverride(
                InheritedOverrideCapturedObject.class));
    }

    @Test
    void registeredClassesAreEligibleAndAuditable() {
        GenericRewindEligibility.registerForTestOrMigration(MasherBadnikInstance.class);

        assertTrue(GenericRewindEligibility.isEligible(MasherBadnikInstance.class));
        assertTrue(GenericRewindEligibility.eligibleClassesForAudit().contains(MasherBadnikInstance.class));
    }

    @Test
    void eligibleAuditSetIsACopy() {
        GenericRewindEligibility.registerForTestOrMigration(MasherBadnikInstance.class);

        var copy = GenericRewindEligibility.eligibleClassesForAudit();

        assertThrows(UnsupportedOperationException.class, () -> copy.clear());
        GenericRewindEligibility.clearForTest();
        assertTrue(copy.contains(MasherBadnikInstance.class));
        assertFalse(GenericRewindEligibility.eligibleClassesForAudit().contains(MasherBadnikInstance.class));
    }

    private static class DefaultCapturedObject extends AbstractObjectInstance {
        private int phase;

        DefaultCapturedObject() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "DefaultCapturedObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static class OverrideCapturedObject extends DefaultCapturedObject {
        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot s) {
            super.restoreRewindState(s);
        }
    }

    private static final class InheritedOverrideCapturedObject extends OverrideCapturedObject {
    }
}
