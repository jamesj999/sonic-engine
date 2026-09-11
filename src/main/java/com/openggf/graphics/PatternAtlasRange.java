package com.openggf.graphics;

/**
 * Central registry of virtual pattern ID ranges used above the Mega Drive
 * VDP's native 11-bit tile index space.
 */
public enum PatternAtlasRange {
    LEVEL_TILES(0x00000, 0x02000, "Level tiles"),
    SPECIAL_STAGE_PLAYFIELD(0x03000, 0x0D000, "Special stage playfield"),
    SONIC1_SPECIAL_STAGE(0x10000, 0x08000, "Sonic 1 special stage"),
    OBJECTS(0x20000, 0x08000, "Objects"),
    HUD(0x28000, 0x08000, "HUD"),
    WATER_SURFACE(0x30000, 0x08000, "Water surface"),
    SIDEKICK_BANKS(0x38000, 0x08000, "Sidekick banks"),
    TITLE_CARDS(0x40000, 0x08000, "Title cards"),
    TRANSIENT_EFFECTS(0x48000, 0x08000, "Transient effects"),
    MENU_AND_DATA_SELECT(0x50000, 0x08000, "Menu and data select"),
    RESULTS_SCREENS(0x60000, 0x08000, "Results screens"),
    SPECIAL_STAGE_RESULTS(0x70000, 0x08000, "Special stage results"),
    SONIC2_TITLE_CREDIT_TEXT(0x80000, 0x08000, "Sonic 2 title credit text"),
    SONIC1_TITLE_FOREGROUND(0x90000, 0x08000, "Sonic 1 title foreground"),
    SONIC1_TITLE_SPRITES(0xA0000, 0x08000, "Sonic 1 title sprites"),
    SONIC1_CREDIT_TEXT(0xB0000, 0x08000, "Sonic 1 credit text"),
    SONIC1_TITLE_TM(0xC0000, 0x08000, "Sonic 1 title TM"),
    SONIC1_TITLE_GHZ_BACKGROUND(0xD0000, 0x08000, "Sonic 1 title GHZ background"),
    S3K_TITLE_SCREEN_ANIMATION(0xE0000, 0x08000, "S3K title screen animation"),
    S3K_TITLE_SCREEN_SPRITES(0xE8000, 0x08000, "S3K title screen sprites"),
    SONIC2_ENDING_CHARACTER(0xF0000, 0x01000, "Sonic 2 ending character"),
    SONIC2_ENDING_FINAL_TORNADO(0xF1000, 0x01000, "Sonic 2 ending final tornado"),
    SONIC2_ENDING_PICS(0xF2000, 0x01000, "Sonic 2 ending photo frame"),
    SONIC2_ENDING_MINI_TORNADO(0xF3000, 0x01000, "Sonic 2 ending mini tornado"),
    SONIC2_ENDING_CLOUDS(0xF4000, 0x01000, "Sonic 2 ending clouds"),
    SONIC2_ENDING_ANIMAL(0xF5000, 0x01000, "Sonic 2 ending animal"),
    SONIC2_CREDITS_LOGO(0xF6000, 0x02000, "Sonic 2 credits logo"),
    SONIC2_ENDING_VRAM(0xF8000, 0x08000, "Sonic 2 ending VRAM-relative art"),
    SEGA_BOOT_LOGOS(0x100000, 0x08000, "SEGA boot logos"),
    MGZ_ZOOM_CUES(0x108000, 0x80000, "MGZ zoom-cue instance banks"),
    CONTINUE_SCREEN(0x188000, 0x10000, "Continue screen");

    private final int base;
    private final int size;
    private final String category;

    PatternAtlasRange(int base, int size, String category) {
        this.base = base;
        this.size = size;
        this.category = category;
    }

    public int base() {
        return base;
    }

    public int size() {
        return size;
    }

    public int endExclusive() {
        return base + size;
    }

    public boolean contains(int patternId) {
        return patternId >= base && patternId < endExclusive();
    }

    public String category() {
        return category;
    }

    public static PatternAtlasRange forPatternId(int patternId) {
        for (PatternAtlasRange range : values()) {
            if (range.contains(patternId)) {
                return range;
            }
        }
        return null;
    }
}
