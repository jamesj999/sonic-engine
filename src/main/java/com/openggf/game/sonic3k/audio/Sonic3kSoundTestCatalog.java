package com.openggf.game.sonic3k.audio;

import com.openggf.audio.debug.AbstractSoundTestCatalog;

/**
 * Sound test catalog for Sonic 3 &amp; Knuckles. Music titles from S3K disassembly,
 * SFX names from known S3K SFX labels.
 */
public final class Sonic3kSoundTestCatalog extends AbstractSoundTestCatalog {
    private static final Sonic3kSoundTestCatalog INSTANCE = new Sonic3kSoundTestCatalog();

    public Sonic3kSoundTestCatalog() {
        super(Sonic3kMusic.titleMap(), Sonic3kSfx.nameMap(),
                Sonic3kMusic.AIZ1.id, Sonic3kSfx.ID_BASE, Sonic3kSfx.ID_MAX, "Sonic 3&K");
    }

    public static Sonic3kSoundTestCatalog getInstance() {
        return INSTANCE;
    }

    public static boolean isMusicId(int id) {
        return INSTANCE.lookupTitle(id) != null;
    }

    public static boolean isSfxId(int id) {
        return id >= Sonic3kSfx.ID_BASE && id <= Sonic3kSfx.ID_MAX;
    }

    /**
     * S3K sound test values map directly to sound IDs (no offset).
     */
    public static int toSoundId(int soundTestValue) {
        return soundTestValue & 0xFF;
    }
}
