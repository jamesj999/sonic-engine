package com.openggf.level.objects;

public enum SolidRoutineKind {
    FULL_SOLID,
    TOP_SOLID_ONLY,
    MONITOR_SOLID;

    public com.openggf.game.profiles.solidroutine.SolidRoutineKind toCanonical() {
        return com.openggf.game.profiles.solidroutine.SolidRoutineKind.valueOf(name());
    }

    public static SolidRoutineKind fromCanonical(
            com.openggf.game.profiles.solidroutine.SolidRoutineKind canonical) {
        return SolidRoutineKind.valueOf(canonical.name());
    }
}
