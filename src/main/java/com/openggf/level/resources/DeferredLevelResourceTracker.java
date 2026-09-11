package com.openggf.level.resources;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-load exact-once consumption tracker for deferred resource descriptors.
 */
public final class DeferredLevelResourceTracker {
    private final Set<DeferredLevelResourceDescriptor> requested;
    private final boolean explicitPolicy;
    private final Set<DeferredLevelResourceDescriptor> consumed =
            new LinkedHashSet<>();

    DeferredLevelResourceTracker(
            List<DeferredLevelResourceDescriptor> descriptors,
            boolean explicitPolicy) {
        requested = new LinkedHashSet<>(descriptors);
        this.explicitPolicy = explicitPolicy;
    }

    public static DeferredLevelResourceTracker none() {
        return new DeferredLevelResourceTracker(List.of(), false);
    }

    public boolean hasExplicitPolicy() {
        return explicitPolicy;
    }

    public boolean omitIfRequested(
            DeferredLevelResourceDescriptor descriptor) {
        if (!requested.contains(descriptor)) {
            return false;
        }
        if (!consumed.add(descriptor)) {
            throw new IllegalStateException(
                    "deferred level resource was consumed more than once: "
                            + descriptor);
        }
        return true;
    }

    public void verifyFullyConsumed() {
        if (consumed.size() != requested.size()) {
            Set<DeferredLevelResourceDescriptor> missing =
                    new LinkedHashSet<>(requested);
            missing.removeAll(consumed);
            throw new IllegalStateException(
                    "deferred level resources were not consumed exactly once: "
                            + missing);
        }
    }

    public void verifyExactRequest(
            DeferredLevelResourceManifest expectedManifest) {
        Set<DeferredLevelResourceDescriptor> expected =
                new LinkedHashSet<>(expectedManifest.descriptors());
        if (!requested.equals(expected)) {
            Set<DeferredLevelResourceDescriptor> missing =
                    new LinkedHashSet<>(expected);
            missing.removeAll(requested);
            Set<DeferredLevelResourceDescriptor> extra =
                    new LinkedHashSet<>(requested);
            extra.removeAll(expected);
            throw new IllegalStateException(
                    "deferred level resource request does not match the target profile"
                            + "; missing=" + missing
                            + "; extra=" + extra);
        }
    }

    public boolean isEmpty() {
        return requested.isEmpty();
    }
}
