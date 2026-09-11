package com.openggf.level.resources;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDeferredLevelResourceTracker {

    private static final DeferredLevelResourceDescriptor PATTERNS =
            new DeferredLevelResourceDescriptor(
                    DeferredLevelResourceDescriptor.Kind.PATTERNS_8X8,
                    0x123456,
                    CompressionType.KOSINSKI_MODULED,
                    0xFFFF1780);

    @Test
    void distinguishesNoPolicyFromAnExplicitEmptyManifest() {
        assertFalse(DeferredLevelResourceTracker.none()
                .hasExplicitPolicy());
        assertTrue(DeferredLevelResourceManifest.EMPTY.newTracker()
                .hasExplicitPolicy());
    }

    @Test
    void consumesEveryExactDescriptorOnce() {
        DeferredLevelResourceTracker tracker =
                new DeferredLevelResourceManifest(List.of(PATTERNS))
                        .newTracker();

        assertTrue(tracker.omitIfRequested(PATTERNS));
        tracker.verifyFullyConsumed();
        assertThrows(IllegalStateException.class,
                () -> tracker.omitIfRequested(PATTERNS));
    }

    @Test
    void rejectsMissingAndDuplicateManifestEntries() {
        DeferredLevelResourceTracker tracker =
                new DeferredLevelResourceManifest(List.of(PATTERNS))
                        .newTracker();
        DeferredLevelResourceDescriptor other =
                new DeferredLevelResourceDescriptor(
                        PATTERNS.kind(),
                        PATTERNS.romSourceAddress() + 2,
                        PATTERNS.compressionType(),
                        PATTERNS.destinationAddress());

        assertFalse(tracker.omitIfRequested(other));
        assertThrows(IllegalStateException.class,
                tracker::verifyFullyConsumed);
        assertThrows(IllegalArgumentException.class,
                () -> new DeferredLevelResourceManifest(
                        List.of(PATTERNS, PATTERNS)));
    }

    @Test
    void eachLoadGetsAnIndependentConsumptionFence() {
        DeferredLevelResourceManifest manifest =
                new DeferredLevelResourceManifest(List.of(PATTERNS));
        DeferredLevelResourceTracker first = manifest.newTracker();
        DeferredLevelResourceTracker second = manifest.newTracker();

        assertTrue(first.omitIfRequested(PATTERNS));
        first.verifyFullyConsumed();
        assertTrue(second.omitIfRequested(PATTERNS));
        second.verifyFullyConsumed();
    }

    @Test
    void exactManifestRejectsMissingExtraAndNonmatchingRequests() {
        DeferredLevelResourceDescriptor other =
                new DeferredLevelResourceDescriptor(
                        PATTERNS.kind(),
                        PATTERNS.romSourceAddress() + 2,
                        PATTERNS.compressionType(),
                        PATTERNS.destinationAddress());
        DeferredLevelResourceManifest expected =
                new DeferredLevelResourceManifest(List.of(PATTERNS));

        assertThrows(IllegalStateException.class,
                () -> DeferredLevelResourceManifest.EMPTY.newTracker()
                        .verifyExactRequest(expected));
        assertThrows(IllegalStateException.class,
                () -> new DeferredLevelResourceManifest(
                        List.of(PATTERNS, other)).newTracker()
                        .verifyExactRequest(expected));
        assertThrows(IllegalStateException.class,
                () -> new DeferredLevelResourceManifest(List.of(other))
                        .newTracker().verifyExactRequest(expected));
        expected.newTracker().verifyExactRequest(expected);
    }
}
