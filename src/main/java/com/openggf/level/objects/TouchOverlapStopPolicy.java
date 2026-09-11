package com.openggf.level.objects;

public enum TouchOverlapStopPolicy {
    STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
    STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY;

    public com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy toCanonical() {
        return com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy.valueOf(name());
    }

    public static TouchOverlapStopPolicy fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy canonical) {
        return TouchOverlapStopPolicy.valueOf(canonical.name());
    }
}
