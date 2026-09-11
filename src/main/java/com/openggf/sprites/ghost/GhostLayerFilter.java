package com.openggf.sprites.ghost;

public final class GhostLayerFilter {
    private GhostLayerFilter() {
    }

    public static boolean matches(int requestedBucket, boolean requestedHighPriority,
                                  int realBucket, boolean realHighPriority) {
        return requestedBucket == realBucket && requestedHighPriority == realHighPriority;
    }
}
