package com.openggf.level;

import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.level.resources.DeferredLevelResourceManifest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSeamlessTransitionResourceHandoffRegistry {

    @Test
    void claimIsSingleUseButRewindRestoresThePendingOwner() {
        SeamlessTransitionResourceHandoffRegistry registry =
                new SeamlessTransitionResourceHandoffRegistry();
        TestHandoff handoff = new TestHandoff();
        SeamlessTransitionResourceHandoffId id =
                registry.register(handoff);
        SeamlessTransitionResourceHandoffRegistry.Snapshot beforeClaim =
                registry.capture();

        assertSame(handoff, registry.claim(id));
        assertThrows(IllegalStateException.class,
                () -> registry.claim(id));

        registry.restore(beforeClaim);
        assertSame(handoff, registry.claim(id));
        assertThrows(IllegalStateException.class,
                () -> registry.claim(id));
    }

    @Test
    void compositeRewindOnBothSidesOfClaimRestoresExactOwnership() {
        SeamlessTransitionResourceHandoffRegistry handoffs =
                new SeamlessTransitionResourceHandoffRegistry();
        RewindRegistry rewind = new RewindRegistry();
        rewind.register(handoffs);
        TestHandoff handoff = new TestHandoff();
        SeamlessTransitionResourceHandoffId id =
                handoffs.register(handoff);
        CompositeSnapshot beforeClaim = rewind.capture();

        assertSame(handoff, handoffs.claim(id));
        CompositeSnapshot afterClaim = rewind.capture();

        rewind.restore(beforeClaim);
        assertSame(handoff, handoffs.peek(id));
        rewind.restore(afterClaim);
        assertThrows(IllegalStateException.class,
                () -> handoffs.peek(id));
    }

    @Test
    void claimedHandoffStaysConsumedWhenExecutionFailsAfterClaim() {
        SeamlessTransitionResourceHandoffRegistry registry =
                new SeamlessTransitionResourceHandoffRegistry();
        SeamlessTransitionResourceHandoffId id =
                registry.register(new TestHandoff());

        registry.claim(id);

        assertThrows(IllegalStateException.class,
                () -> registry.peek(id));
        assertThrows(IllegalStateException.class,
                () -> registry.claim(id));
    }

    @Test
    void postInitTransferRunsExactlyOncePerSuccessfulClaim() {
        SeamlessTransitionResourceHandoffRegistry registry =
                new SeamlessTransitionResourceHandoffRegistry();
        TestHandoff handoff = new TestHandoff();
        SeamlessTransitionResourceHandoffId id =
                registry.register(handoff);

        registry.claim(id).transferAfterTargetInit();

        assertEquals(1, handoff.transferCount.get());
        assertThrows(IllegalStateException.class,
                () -> registry.claim(id));
    }

    @Test
    void preClaimSnapshotCannotObserveLeaseAttachedToClaimedReplacement() {
        SeamlessTransitionResourceHandoffRegistry registry =
                new SeamlessTransitionResourceHandoffRegistry();
        TestHandoff original = new TestHandoff();
        SeamlessTransitionResourceHandoffId id = registry.register(original);
        SeamlessTransitionResourceHandoffRegistry.Snapshot beforeClaim =
                registry.capture();
        RuntimeArtAdmissionLease lease = new RuntimeArtAdmissionLease(
                7, 11, 13,
                RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER);

        TestHandoff claimed = (TestHandoff) registry.claim(id);
        TestHandoff leased = (TestHandoff) claimed.withAdmissionLease(lease);

        assertNull(original.admissionLease);
        assertNull(claimed.admissionLease);
        assertSame(lease, leased.admissionLease);
        registry.restore(beforeClaim);
        TestHandoff restored = (TestHandoff) registry.peek(id);
        assertSame(original, restored);
        assertNull(restored.admissionLease,
                "a shallow registry snapshot must retain an immutable lease-free value");
    }

    @Test
    void claimedThenFailedTransferIsTerminalAndRewindable() {
        SeamlessTransitionResourceHandoffRegistry registry =
                new SeamlessTransitionResourceHandoffRegistry();
        TestHandoff original = new TestHandoff();
        SeamlessTransitionResourceHandoffId id = registry.register(original);
        var beforeClaim = registry.capture();
        RuntimeArtAdmissionLease lease = new RuntimeArtAdmissionLease(
                17, 19, 23,
                RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER);
        TestHandoff leased = (TestHandoff) ((TestHandoff) registry.claim(id))
                .withAdmissionLease(lease);

        registry.recordFailedTransfer(id, leased);
        var afterFailure = registry.capture();

        assertTrue(registry.hasFailedTransfer(id));
        assertSame(leased, registry.failedTransfer(id));
        assertThrows(IllegalStateException.class, () -> registry.claim(id));

        registry.restore(beforeClaim);
        assertSame(original, registry.peek(id));
        registry.restore(afterFailure);
        assertTrue(registry.hasFailedTransfer(id));
        assertSame(leased, registry.failedTransfer(id));
        assertThrows(IllegalStateException.class, () -> registry.claim(id));
    }

    private static final class TestHandoff
            implements SeamlessTransitionResourceHandoff {
        private final AtomicInteger transferCount = new AtomicInteger();
        private final RuntimeArtAdmissionLease admissionLease;

        private TestHandoff() {
            this(null);
        }

        private TestHandoff(RuntimeArtAdmissionLease admissionLease) {
            this.admissionLease = admissionLease;
        }

        @Override
        public DeferredLevelResourceManifest deferredResources() {
            return DeferredLevelResourceManifest.EMPTY;
        }

        @Override
        public void transferAfterTargetInit() {
            transferCount.incrementAndGet();
        }

        @Override
        public SeamlessTransitionResourceHandoff withAdmissionLease(
                RuntimeArtAdmissionLease lease) {
            return new TestHandoff(lease);
        }
    }
}
