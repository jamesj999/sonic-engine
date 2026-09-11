package com.openggf.game.sonic3k.specialstage;

/**
 * ROM offsets for S3K Blue Ball special stage art, layouts, and palettes.
 * <p>
 * All offsets verified with RomOffsetFinder against the S3K combined ROM.
 * <p>
 * S&K half assets are at offsets < 0x200000.
 * S3 half assets (Tails art, S3 layouts) are at offsets >= 0x200000.
 */
public final class Sonic3kSpecialStageRomOffsets {
    private Sonic3kSpecialStageRomOffsets() {}

    // ==================== Nemesis Compressed Art ====================

    /** ArtNem_SStageSphere - sphere graphics (all 4 palette variants) */
    public static final long ART_NEM_SPHERE = 0xAD904;
    public static final int ART_NEM_SPHERE_SIZE = 1627;

    /** ArtNem_SStageRing - ring animation graphics */
    public static final long ART_NEM_RING = 0xADF60;
    public static final int ART_NEM_RING_SIZE = 1164;

    /** ArtNem_SStageBG - starfield background tiles */
    public static final long ART_NEM_BG = 0xAEED0;
    public static final int ART_NEM_BG_SIZE = 132;

    /** ArtNem_SStageLayout - checkerboard floor tile art */
    public static final long ART_NEM_LAYOUT = 0xB07B8;
    public static final int ART_NEM_LAYOUT_SIZE = 9853;

    /** ArtNem_SStageShadow - player shadow art */
    public static final long ART_NEM_SHADOW = 0xAD430;
    public static final int ART_NEM_SHADOW_SIZE = 66;

    /** ArtNem_GetBlueSpheres - "Get Blue Spheres" text art */
    public static final long ART_NEM_GET_BLUE_SPHERES = 0xAD472;
    public static final int ART_NEM_GET_BLUE_SPHERES_SIZE = 385;

    /** ArtNem_GBSArrow - "Get Blue Spheres" arrow art */
    public static final long ART_NEM_GBS_ARROW = 0xAD5F4;
    public static final int ART_NEM_GBS_ARROW_SIZE = 93;

    /** ArtNem_SStageDigits - HUD number font */
    public static final long ART_NEM_DIGITS = 0xAD650;
    public static final int ART_NEM_DIGITS_SIZE = 364;

    /** ArtNem_SStageIcons - HUD sphere/ring icons */
    public static final long ART_NEM_ICONS = 0xAD7BC;
    public static final int ART_NEM_ICONS_SIZE = 327;

    // ==================== KosinskiM Compressed Art ====================

    /** ArtKosM_SStageChaosEmerald - chaos emerald sprite */
    public static final long ART_KOSM_CHAOS_EMERALD = 0xAE3EC;
    public static final int ART_KOSM_CHAOS_EMERALD_SIZE = 1458;

    /** ArtKosM_SStageSuperEmerald - super emerald sprite */
    public static final long ART_KOSM_SUPER_EMERALD = 0xAE99E;
    public static final int ART_KOSM_SUPER_EMERALD_SIZE = 1042;

    // ==================== Enigma Compressed Maps ====================

    /** MapEni_SStageBG - background plane mapping */
    public static final long MAP_ENI_BG = 0xAEDB0;
    public static final int MAP_ENI_BG_SIZE = 288;

    /** MapEni_SStageLayout - floor plane mapping */
    public static final long MAP_ENI_LAYOUT = 0xAEF54;
    public static final int MAP_ENI_LAYOUT_SIZE = 6244;

    // ==================== Kosinski Compressed Data ====================

    /** SStageKos_PerspectiveMaps - pre-computed perspective projection tables */
    public static final long PERSPECTIVE_MAPS = 0xB2E36;
    public static final int PERSPECTIVE_MAPS_SIZE = 15120;

    // ==================== Layout Data ====================

    /** SSLayoutData1_Kos - SK Set 1 compressed metadata (not the actual layouts) */
    public static final long LAYOUT_SK_SET_1 = 0x1FCF2A;
    public static final int LAYOUT_SK_SET_1_SIZE = 1696;

    /** SSLayoutData2_Kos - SK Set 2 compressed (Blue Spheres standalone) */
    public static final long LAYOUT_SK_SET_2 = 0x1FD5CA;
    public static final int LAYOUT_SK_SET_2_SIZE = 7760;

    /**
     * SStage1_Layout - first S3 layout (in S3 Lockon ROM half).
     * Each layout is 0x408 bytes (0x400 grid + 8 bytes params).
     * 8 consecutive layouts.
     */
    public static final long LAYOUT_S3_STAGE_1 = 0x26A0E0;
    /** Size of each individual stage layout. */
    public static final int LAYOUT_STAGE_SIZE = 0x408;

    // ==================== Uncompressed Player Art ====================

    /** ArtUnc_SStageSonic - Sonic special stage sprites */
    public static final long ART_UNC_SONIC = 0xAAA7C;
    public static final int ART_UNC_SONIC_SIZE = 4992;

    /** ArtUnc_SStageKnuckles - Knuckles special stage sprites */
    public static final long ART_UNC_KNUCKLES = 0xABF22;
    public static final int ART_UNC_KNUCKLES_SIZE = 5088;

    /** ArtUnc_SStageTails - Tails special stage sprites (S3 half) */
    public static final long ART_UNC_TAILS = 0x28F95A;
    public static final int ART_UNC_TAILS_SIZE = 3936;

    /** ArtUnc_SStageTailstails - Tails' tails special stage sprites (S3 half) */
    public static final long ART_UNC_TAILS_TAILS = 0x2909E8;
    public static final int ART_UNC_TAILS_TAILS_SIZE = 1792;

    // ==================== Scalar Table ====================

    /** ScalarTable2 - sine/cosine lookup for 3D projection (256 words = 512 bytes) */
    public static final long SCALAR_TABLE = 0xA264;
    public static final int SCALAR_TABLE_SIZE = 512;

    // ==================== Palettes ====================

    /** Pal_SStage_Main - 4 palette lines (128 bytes) */
    public static final long PAL_MAIN = 0x896E;
    public static final int PAL_MAIN_SIZE = 128;

    /** Pal_SStage_Knux - Knuckles palette patch (16 bytes) */
    public static final long PAL_KNUX = 0x89EE;
    public static final int PAL_KNUX_SIZE = 16;

    /** Pal_SStage_3_1 - base address for S3 stage palettes (8 consecutive, 38 bytes each) */
    public static final long PAL_S3_BASE = 0x89FE;
    /** Each stage palette is 38 bytes. */
    public static final int PAL_STAGE_SIZE = 38;

    /** Pal_SStage_K_1 - base address for S&K stage palettes (8 consecutive, 38 bytes each) */
    public static final long PAL_K_BASE = 0x8B2E;

    /** Pal_SStage_Emeralds - emerald colors (64 bytes = 8 stages x 8 bytes) */
    public static final long PAL_EMERALDS = 0x9D1E;
    public static final int PAL_EMERALDS_SIZE = 64;

    // ==================== HUD Maps (Uncompressed) ====================

    /** MapUnc_SSNum - HUD number tile mappings (120 bytes) */
    public static final long MAP_UNC_HUD_NUMBERS = 0x8CB4;
    public static final int MAP_UNC_HUD_NUMBERS_SIZE = 120;

    /** MapUnc_SSNum000 - HUD "000" display template (48 bytes) */
    public static final long MAP_UNC_HUD_DISPLAY = 0x8D2C;
    public static final int MAP_UNC_HUD_DISPLAY_SIZE = 48;

    /**
     * Check if critical ROM offsets have been verified.
     */
    public static boolean areOffsetsVerified() {
        return ART_NEM_SPHERE != -1
                && ART_NEM_LAYOUT != -1
                && PERSPECTIVE_MAPS != -1
                && PAL_MAIN != -1
                && LAYOUT_SK_SET_1 != -1;
    }
}
