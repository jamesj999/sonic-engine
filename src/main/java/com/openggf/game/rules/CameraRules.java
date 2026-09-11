package com.openggf.game.rules;

public record CameraRules(
        short lookScrollDelay,
        boolean waterShimmerEnabled,
        int fastScrollCap,
        boolean uncappedLeftwardHorizontalScroll,
        boolean useScreenYWrapValueForVisibility,
        boolean playerControlAppliesVerticalWrapMask) {
}
