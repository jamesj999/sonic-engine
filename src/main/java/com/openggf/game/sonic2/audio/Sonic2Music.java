package com.openggf.game.sonic2.audio;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for all Sonic 2 music IDs and display names.
 * <p>IDs from s2disasm, names from sound test labels.
 * Sparse ID range requires HashMap-based lookup.
 */
public enum Sonic2Music {
    // ContinueScreen / zMusIDPtr_Continue (s2.constants.asm:860), not the empty mailbox byte.
    CONTINUE(0x9C, "Continue"),
    CASINO_NIGHT_2P(0x80, "Casino Night Zone (2P)"),
    EMERALD_HILL(0x81, "Emerald Hill Zone"),
    METROPOLIS(0x82, "Metropolis Zone"),
    CASINO_NIGHT(0x83, "Casino Night Zone"),
    MYSTIC_CAVE(0x84, "Mystic Cave Zone"),
    MYSTIC_CAVE_2P(0x85, "Mystic Cave Zone (2P)"),
    AQUATIC_RUIN(0x86, "Aquatic Ruin Zone"),
    DEATH_EGG(0x87, "Death Egg Zone"),
    SPECIAL_STAGE(0x88, "Special Stage"),
    OPTIONS(0x89, "Option Screen"),
    ENDING(0x8A, "Ending"),
    FINAL_BOSS(0x8B, "Final Battle"),
    CHEMICAL_PLANT(0x8C, "Chemical Plant Zone"),
    BOSS(0x8D, "Boss"),
    SKY_CHASE(0x8E, "Sky Chase Zone"),
    OIL_OCEAN(0x8F, "Oil Ocean Zone"),
    WING_FORTRESS(0x90, "Wing Fortress Zone"),
    EMERALD_HILL_2P(0x91, "Emerald Hill Zone (2P)"),
    RESULTS_2P(0x92, "2P Results Screen"),
    SUPER_SONIC(0x93, "Super Sonic"),
    HILL_TOP(0x94, "Hill Top Zone"),
    TITLE(0x96, "Title Screen"),
    ACT_CLEAR(0x97, "Stage Clear"),
    INVINCIBILITY(0x99, "Invincibility"),
    HIDDEN_PALACE(0x9B, "Hidden Palace Zone"),
    // zMusIDPtr_ExtraLife / MusID_ExtraLife in the shipped REV01 table
    // (s2.constants.asm:856); 0xB5 is the distinct right-ring SFX.
    EXTRA_LIFE(0x98, "1-Up"),
    GAME_OVER(0xB8, "Game Over"),
    GOT_EMERALD(0xBA, "Got an Emerald"),
    CREDITS(0xBD, "Credits"),
    UNDERWATER(0xDC, "Underwater Timing");

    /** Native music ID. */
    public final int id;

    /** Human-readable display name. */
    public final String displayName;

    private static final Map<Integer, String> TITLE_MAP;
    private static final Map<Integer, Sonic2Music> BY_ID;

    static {
        Map<Integer, String> titles = new LinkedHashMap<>();
        Map<Integer, Sonic2Music> lookup = new HashMap<>();
        for (Sonic2Music music : values()) {
            titles.put(music.id, music.displayName);
            lookup.put(music.id, music);
        }
        TITLE_MAP = Collections.unmodifiableMap(titles);
        BY_ID = Collections.unmodifiableMap(lookup);
    }

    Sonic2Music(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** ID-to-name map for the SoundTestCatalog. */
    public static Map<Integer, String> titleMap() {
        return TITLE_MAP;
    }

    /** Look up enum constant by native ID, or null if not a music ID. */
    public static Sonic2Music fromId(int id) {
        return BY_ID.get(id);
    }
}
