package uk.co.jamesj999.sonic.game.sonic2.constants;

public final class Sonic2AudioConstants {
    public static final int SFX_JUMP = 0xA0;
    public static final int SFX_RING_LEFT = 0xCE;
    public static final int SFX_RING_RIGHT = 0xB5;
    public static final int SFX_RING_SPILL = 0xC6;
    public static final int SFX_SPINDASH_CHARGE = 0xE0;
    public static final int SFX_SPINDASH_RELEASE = 0xBC;
    public static final int SFX_SKID = 0xA4;
    public static final int SFX_HURT = 0xA3;
    public static final int SFX_DEATH = SFX_HURT;
    public static final int SFX_BADNIK_HIT = 0xC1;
    public static final int SFX_CHECKPOINT = 0xA1;
    public static final int SFX_SPIKE_HIT = 0xA6;
    public static final int SFX_SPIKES_MOVE = 0xB6;
    public static final int SFX_DROWN = 0xB2;
    public static final int SFX_SPRING = 0xCC;
    public static final int SFX_BUMPER = 0xB4; // Round/Hex bumper (SndID_Bumper)
    public static final int SFX_LARGE_BUMPER = 0xD9; // CNZ map triangular bumpers (SndID_LargeBumper)
    public static final int SFX_FLIPPER = 0xE3;  // CNZ Flipper (SndID_Flipper)
    public static final int SFX_ROLLING = 0xBE;
    public static final int SFX_SHIELD = 0xAF;
    public static final int SFX_EXPLOSION = 0xC1;
    public static final int SFX_ERROR = 0xED; // Error/fail sound (SndID_Error)

    public static final int MUS_CASINO_NIGHT_2P = 0x80;
    public static final int MUS_EMERALD_HILL = 0x81;
    public static final int MUS_METROPOLIS = 0x82;
    public static final int MUS_CASINO_NIGHT = 0x83;
    public static final int MUS_MYSTIC_CAVE = 0x84;
    public static final int MUS_MYSTIC_CAVE_2P = 0x85;
    public static final int MUS_AQUATIC_RUIN = 0x86;
    public static final int MUS_DEATH_EGG = 0x87;
    public static final int MUS_SPECIAL_STAGE = 0x88;
    public static final int MUS_OPTIONS = 0x89;
    public static final int MUS_ENDING = 0x8A;
    public static final int MUS_FINAL_BOSS = 0x8B;
    public static final int MUS_CHEMICAL_PLANT = 0x8C;
    public static final int MUS_BOSS = 0x8D;
    public static final int MUS_SKY_CHASE = 0x8E;
    public static final int MUS_OIL_OCEAN = 0x8F;
    public static final int MUS_WING_FORTRESS = 0x90;
    public static final int MUS_EMERALD_HILL_2P = 0x91;
    public static final int MUS_2P_RESULTS = 0x92;
    public static final int MUS_SUPER_SONIC = 0x93;
    public static final int MUS_HILL_TOP = 0x94;
    public static final int MUS_TITLE = 0x96;
    public static final int MUS_ACT_CLEAR = 0x97;
    public static final int MUS_INVINCIBILITY = 0x99;
    public static final int MUS_HIDDEN_PALACE = 0x9B;
    public static final int MUS_EXTRA_LIFE = 0xB5;
    public static final int MUS_GAME_OVER = 0xB8;
    public static final int MUS_GOT_EMERALD = 0xBA;
    public static final int MUS_CREDITS = 0xBD;
    public static final int MUS_UNDERWATER = 0xDC;

    // Special stage sounds
    public static final int SFX_SPECIAL_STAGE_ENTRY = 0xCA; // SndID_SpecStageEntry - warp in/out sound

    public static final int CMD_SPEED_UP = 0xFB;
    public static final int CMD_SLOW_DOWN = 0xFC;

    private Sonic2AudioConstants() {
    }
}
