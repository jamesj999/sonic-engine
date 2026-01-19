package uk.co.jamesj999.sonic.game.sonic2.constants;

public final class Sonic2ObjectIds {
    public static final int LAYER_SWITCHER = 0x03;
    public static final int SPRING = 0x41;
    public static final int SPIKES = 0x36;
    public static final int MONITOR = 0x26;
    public static final int CHECKPOINT = 0x79;
    public static final int BRIDGE = 0x11;
    public static final int SWINGING_PLATFORM = 0x15;
    public static final int GENERIC_PLATFORM_A = 0x18;
    public static final int GENERIC_PLATFORM_B = 0x19;
    public static final int BRIDGE_STAKE = 0x1C;
    public static final int SPIRAL = 0x06;
    public static final int EHZ_WATERFALL = 0x49;

    // EHZ Badniks
    public static final int BUZZER = 0x4B;
    public static final int MASHER = 0x5C;
    public static final int COCONUTS = 0x9D;

    // Supporting objects spawned by Badniks
    public static final int EXPLOSION = 0x27;
    public static final int ANIMAL = 0x28;
    public static final int POINTS = 0x29;

    // Level completion
    public static final int SIGNPOST = 0x0D; // End of level signpost
    public static final int INVISIBLE_BLOCK = 0x74;

    // CPZ Objects
    public static final int TIPPING_FLOOR = 0x0B; // CPZ Tipping Floor (Obj0B)
    public static final int SPEED_BOOSTER = 0x1B; // Speed Booster (Obj1B)
    public static final int CPZ_SPIN_TUBE = 0x1E; // CPZ Spin Tube (Obj1E)
    public static final int BLUE_BALLS = 0x1D;    // Blue Balls / CPZ Droplet hazard (Obj1D)
    public static final int BREAKABLE_BLOCK = 0x32; // Breakable Block (Obj32) - CPZ metal blocks / HTZ rocks
    public static final int PIPE_EXIT_SPRING = 0x7B; // Pipe Exit Spring (Obj7B) - CPZ warp tube exit spring

    // CPZ/HTZ/MTZ/ARZ/DEZ Barrier (One-way rising platform)
    public static final int BARRIER = 0x2D;    // Barrier (Obj2D) - one-way rising barrier
    public static final int CPZ_STAIRCASE = 0x78; // CPZ Staircase (Obj78) - 4-piece triggered elevator platform

    // CPZ/ARZ/MCZ Objects
    public static final int SPRINGBOARD = 0x40;  // Pressure spring / lever spring (Obj40)

    // CNZ Objects
    public static final int BUMPER = 0x44;       // Round Bumper (Obj44)
    public static final int HEX_BUMPER = 0xD7;   // Hexagonal Bumper (ObjD7)
    public static final int BONUS_BLOCK = 0xD8; // Bonus Block / Drop Target (ObjD8)
    public static final int FLIPPER = 0x86;    // CNZ Flipper (Obj86)

    // CPZ Foreground Objects
    public static final int CPZ_PYLON = 0x7C;  // CPZ Pylon (Obj7C) - decorative background pylon

    // MTZ/CPZ Platform Objects
    public static final int MTZ_PLATFORM = 0x6B; // MTZ Platform (Obj6B) - multi-purpose platform with 12 movement subtypes

    private Sonic2ObjectIds() {
    }

}
