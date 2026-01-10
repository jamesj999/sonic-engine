package uk.co.jamesj999.sonic.game.sonic2.audio;

import uk.co.jamesj999.sonic.audio.smps.SmpsSequencerConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Sonic2SmpsSequencerConfig {
    public static final int TEMPO_MOD_BASE = 0x100;
    public static final int[] FM_CHANNEL_ORDER = { 0x16, 0, 1, 2, 4, 5, 6 };
    public static final int[] PSG_CHANNEL_ORDER = { 0x80, 0xA0, 0xC0 };

    public static final Map<Integer, Integer> SPEED_UP_TEMPOS;
    public static final SmpsSequencerConfig CONFIG;

    static {
        Map<Integer, Integer> tempos = new HashMap<>();
        tempos.put(0x92, 0x68); // 2P Results
        tempos.put(0x81, 0xBE); // Emerald Hill
        tempos.put(0x85, 0xFF); // Mystic Cave 2P
        tempos.put(0x8F, 0xF0); // Oil Ocean
        tempos.put(0x82, 0xFF); // Metropolis
        tempos.put(0x94, 0xDE); // Hill Top
        tempos.put(0x86, 0xFF); // Aquatic Ruin
        tempos.put(0x80, 0xDD); // Casino Night 2P
        tempos.put(0x83, 0x68); // Casino Night
        tempos.put(0x87, 0x80); // Death Egg
        tempos.put(0x84, 0xD6); // Mystic Cave
        tempos.put(0x91, 0x7B); // Emerald Hill 2P
        tempos.put(0x8E, 0x7B); // Sky Chase
        tempos.put(0x8C, 0xFF); // Chemical Plant
        tempos.put(0x90, 0xA8); // Wing Fortress
        tempos.put(0x9B, 0xFF); // Hidden Palace
        tempos.put(0x89, 0x87); // Options
        tempos.put(0x88, 0xFF); // Special Stage
        tempos.put(0x8D, 0xFF); // Boss
        tempos.put(0x8B, 0xC9); // Final Boss
        tempos.put(0x8A, 0x97); // Ending
        tempos.put(0x93, 0xFF); // Super Sonic
        tempos.put(0x99, 0xFF); // Invincibility
        tempos.put(0xB5, 0xCD); // 1-Up
        tempos.put(0x96, 0xCD); // Title
        tempos.put(0x97, 0xAA); // Act Clear
        tempos.put(0xB8, 0xF2); // Game Over
        tempos.put(0x00, 0xDB); // Continue
        tempos.put(0xBA, 0xD5); // Chaos Emerald
        tempos.put(0xBD, 0xF0); // Credits
        SPEED_UP_TEMPOS = Collections.unmodifiableMap(tempos);
        CONFIG = new SmpsSequencerConfig(SPEED_UP_TEMPOS, TEMPO_MOD_BASE, FM_CHANNEL_ORDER, PSG_CHANNEL_ORDER);
    }

    private Sonic2SmpsSequencerConfig() {
    }
}
