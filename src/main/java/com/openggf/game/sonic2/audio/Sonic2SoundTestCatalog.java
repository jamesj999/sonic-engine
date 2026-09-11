package com.openggf.game.sonic2.audio;

import com.openggf.audio.debug.AbstractSoundTestCatalog;

import java.util.Map;

/**
 * Shared sound test catalog for Sonic 2 (music IDs + helpers).
 */
public final class Sonic2SoundTestCatalog extends AbstractSoundTestCatalog {
    private static final Sonic2SoundTestCatalog INSTANCE = new Sonic2SoundTestCatalog();

    public Sonic2SoundTestCatalog() {
        super(Sonic2Music.titleMap(), Sonic2Sfx.nameMap(),
                Sonic2Music.CHEMICAL_PLANT.id, Sonic2Sfx.ID_BASE, Sonic2Sfx.ID_MAX, "Sonic 2");
    }

    public static Sonic2SoundTestCatalog getInstance() {
        return INSTANCE;
    }

    /** Returns an unmodifiable view of the S2 music title map. */
    public static Map<Integer, String> getTitleMap() {
        return INSTANCE.getTitleMapView();
    }

    public static boolean isMusicId(int id) {
        return INSTANCE.lookupTitle(id) != null;
    }

    public static boolean isSfxId(int id) {
        return id >= Sonic2Sfx.ID_BASE && id <= Sonic2Sfx.ID_MAX;
    }

    public static int toSoundId(int soundTestValue) {
        return (soundTestValue & 0x7F) + 0x80;
    }
}
