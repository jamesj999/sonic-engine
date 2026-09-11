package com.openggf.level.objects;

public enum TouchCategoryDecodeMode {
    NORMAL,
    FORCE_ENEMY,
    S1_SPECIAL_PROPERTY,
    SONIC2_SPECIAL_PROPERTY,
    S3K_SPECIAL_PROPERTY;

    public com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode toCanonical() {
        return com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode.valueOf(name());
    }

    public static TouchCategoryDecodeMode fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode canonical) {
        return TouchCategoryDecodeMode.valueOf(canonical.name());
    }
}
