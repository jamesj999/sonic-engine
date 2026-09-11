package com.openggf.level.objects;

public enum TouchAttackBouncePolicy {
    STANDARD_ENEMY_KILL,
    BOSS_REFLECT,
    CUSTOM_HANDLED,
    NONE;

    public com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy toCanonical() {
        return com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy.valueOf(name());
    }

    public static TouchAttackBouncePolicy fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy canonical) {
        return TouchAttackBouncePolicy.valueOf(canonical.name());
    }
}
