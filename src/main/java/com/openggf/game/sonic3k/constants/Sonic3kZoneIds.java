package com.openggf.game.sonic3k.constants;

/**
 * Zone ID constants for Sonic 3 &amp; Knuckles.
 * These match the ROM's zone numbering used for table indexing.
 */
public class Sonic3kZoneIds {

    private Sonic3kZoneIds() {}

    public static final int ZONE_AIZ = 0x00;  // Angel Island
    public static final int ZONE_HCZ = 0x01;  // Hydrocity
    public static final int ZONE_MGZ = 0x02;  // Marble Garden
    public static final int ZONE_CNZ = 0x03;  // Carnival Night
    public static final int ZONE_FBZ = 0x04;  // Flying Battery
    public static final int ZONE_ICZ = 0x05;  // IceCap
    public static final int ZONE_LBZ = 0x06;  // Launch Base
    public static final int ZONE_MHZ = 0x07;  // Mushroom Hill
    public static final int ZONE_SOZ = 0x08;  // Sandopolis
    public static final int ZONE_LRZ = 0x09;  // Lava Reef
    public static final int ZONE_SSZ = 0x0A;  // Sky Sanctuary
    public static final int ZONE_DEZ = 0x0B;  // Death Egg
    public static final int ZONE_DDZ = 0x0C;  // Doomsday

    // Scene-only zone: act 0 is the AIZ intro, act 1 the ending scene
    // (LevelSizes, skdisasm/sonic3k.asm:38106-38107).
    public static final int ZONE_INTRO_ENDING = 0x0D;

    // Boss/hub zones. Zone 0x16 act 1 is Hidden Palace
    // (Current_zone_and_act $1601, skdisasm/sonic3k.asm:62150); act 0 is the
    // Lava Reef boss act ($1600, sonic3k.asm:10183). Zone 0x17 act 0 is the
    // Death Egg boss act ($1700, sonic3k.asm:10185) and act 1 the Super
    // Emerald special-stage arena ($1701, sonic3k.asm:4994-4996).
    public static final int ZONE_LRZ_BOSS_HPZ = 0x16;
    public static final int ZONE_HPZ = 0x16;  // Hidden Palace (act 1 of 0x16)
    public static final int ZONE_DEZ_BOSS_SS_ARENA = 0x17;

    // Competition zones (LevelSizes rows 28-37, skdisasm/sonic3k.asm:38108-38117)
    public static final int ZONE_ALZ = 0x0E;  // Azure Lake
    public static final int ZONE_BPZ = 0x0F;  // Balloon Park
    public static final int ZONE_DPZ = 0x10;  // Desert Palace
    public static final int ZONE_CGZ = 0x11;  // Chrome Gadget
    public static final int ZONE_EMZ = 0x12;  // Endless Mine

    // Bonus stages
    public static final int ZONE_GUMBALL        = 0x13;  // Gumball Machine
    public static final int ZONE_GLOWING_SPHERE = 0x14;  // Glowing Spheres (Pachinko)
    public static final int ZONE_SLOT_MACHINE   = 0x15;  // Slot Machine

    public static final int MAIN_ZONE_COUNT = 13; // AIZ through DDZ

    /**
     * Number of zone rows the ROM's level tables address, AIZ(0) through the
     * Death Egg boss / special-stage arena pair(23). LevelPtrs is 48
     * longwords (skdisasm/sonic3k.asm:200438-200485) and Load_Level indexes it
     * as zone*2 + act (sonic3k.asm:38746-38753); LevelSizes
     * (sonic3k.asm:38096-38143) and LevelMusic_Playlist
     * (sonic3k.asm:7476-7500) are the same 48 entries with the same index.
     */
    public static final int TOTAL_ZONE_COUNT = 24;
}
