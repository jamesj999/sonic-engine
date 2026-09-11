package com.openggf.level.resources;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable requested set; each load attempt receives a fresh tracker. */
public record DeferredLevelResourceManifest(
        List<DeferredLevelResourceDescriptor> descriptors) {

    public static final DeferredLevelResourceManifest EMPTY =
            new DeferredLevelResourceManifest(List.of());

    public DeferredLevelResourceManifest {
        descriptors = List.copyOf(descriptors);
        Set<DeferredLevelResourceDescriptor> unique =
                new LinkedHashSet<>(descriptors);
        if (unique.size() != descriptors.size()) {
            throw new IllegalArgumentException(
                    "deferred resource manifest contains duplicate descriptors");
        }
    }

    public DeferredLevelResourceTracker newTracker() {
        return new DeferredLevelResourceTracker(descriptors, true);
    }
}
