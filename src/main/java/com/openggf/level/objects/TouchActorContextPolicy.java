package com.openggf.level.objects;

public enum TouchActorContextPolicy {
    MAIN_FULL_SIDEKICK_HURT_ONLY,
    MAIN_ONLY;

    public com.openggf.game.profiles.touchresponse.TouchActorContextPolicy toCanonical() {
        return com.openggf.game.profiles.touchresponse.TouchActorContextPolicy.valueOf(name());
    }

    public static TouchActorContextPolicy fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchActorContextPolicy canonical) {
        return TouchActorContextPolicy.valueOf(canonical.name());
    }
}
