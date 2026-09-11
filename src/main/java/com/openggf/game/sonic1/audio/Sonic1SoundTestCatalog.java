package com.openggf.game.sonic1.audio;

import com.openggf.audio.debug.AbstractSoundTestCatalog;

/**
 * Sound test catalog for Sonic 1. Music titles from s1disasm Constants.asm,
 * SFX names from s1disasm sound driver labels.
 */
public final class Sonic1SoundTestCatalog extends AbstractSoundTestCatalog {
    private static final Sonic1SoundTestCatalog INSTANCE = new Sonic1SoundTestCatalog();

    public Sonic1SoundTestCatalog() {
        super(Sonic1Music.titleMap(), Sonic1Sfx.nameMap(),
                Sonic1Music.GHZ.id, Sonic1Sfx.ID_BASE, Sonic1Sfx.ID_MAX, "Sonic 1");
    }

    public static Sonic1SoundTestCatalog getInstance() {
        return INSTANCE;
    }
}
