package com.openggf.game.sonic1.constants;

/**
 * Object type IDs for Sonic the Hedgehog 1.
 * From the Sonic 1 disassembly object pointer table.
 */
public final class Sonic1ObjectIds {

    private Sonic1ObjectIds() {
    }

    public static final int SONIC           = 0x01;
    public static final int BREAKABLE_POLE  = 0x0B;
    public static final int FLAPPING_DOOR   = 0x0C;
    public static final int SPINNING_LIGHT  = 0x12;
    public static final int SIGNPOST        = 0x0D;
    public static final int LAVA_BALL_MAKER = 0x13;
    public static final int LAVA_BALL       = 0x14;
    public static final int BRIDGE          = 0x11;
    public static final int SWINGING_PLATFORM = 0x15;
    public static final int HARPOON           = 0x16;
    public static final int SPIKED_POLE_HELIX = 0x17;
    public static final int PLATFORM        = 0x18;
    public static final int COLLAPSING_LEDGE = 0x1A;
    public static final int SCENERY         = 0x1C;
    public static final int BALL_HOG        = 0x1E; // id_BallHog - Ball Hog enemy (SBZ)
    public static final int CRABMEAT        = 0x1F;
    public static final int CANNONBALL      = 0x20; // id_Cannonball - thrown by Ball Hog (dynamically spawned)
    public static final int BUZZ_BOMBER     = 0x22;
    public static final int BUZZ_BOMBER_MISSILE = 0x23;
    public static final int MISSILE_DISSOLVE = 0x24;
    public static final int RING            = 0x25;
    public static final int MONITOR         = 0x26;
    public static final int EXPLOSION_ITEM  = 0x27;
    public static final int ANIMALS         = 0x28; // id_Animals - escaped animals (badnik/capsule + ending)
    public static final int SBZ_SMALL_DOOR  = 0x2A; // id_AutoDoor - small vertical door (SBZ)
    public static final int CHOPPER         = 0x2B;
    public static final int JAWS            = 0x2C;
    public static final int BURROBOT        = 0x2D;
    public static final int POWER_UP        = 0x2E;
    public static final int MZ_LARGE_GRASSY_PLATFORM = 0x2F;
    public static final int MZ_GLASS_BLOCK  = 0x30;
    public static final int CHAINED_STOMPER = 0x31;
    public static final int BUTTON          = 0x32;
    public static final int PUSH_BLOCK      = 0x33;
    public static final int BURNING_GRASS   = 0x35;
    public static final int SPIKES          = 0x36;
    public static final int ROCK            = 0x3B;
    public static final int BREAKABLE_WALL  = 0x3C;
    public static final int GHZ_BOSS        = 0x3D;
    public static final int EGG_PRISON      = 0x3E;
    public static final int EXPLOSION       = 0x3F;
    public static final int MOTOBUG         = 0x40;
    public static final int SPRING          = 0x41;
    public static final int EDGE_WALLS      = 0x44;
    public static final int MZ_BRICK        = 0x46;
    public static final int NEWTRON         = 0x42;
    public static final int ROLLER          = 0x43;
    public static final int BUMPER          = 0x47;
    public static final int BOSS_BALL       = 0x48;
    public static final int WATERFALL_SOUND = 0x49;
    public static final int GIANT_RING      = 0x4B;
    public static final int LAVA_GEYSER_MAKER = 0x4C;
    public static final int LAVA_GEYSER     = 0x4D;
    public static final int LAVA_WALL       = 0x4E;
    public static final int YADRIN          = 0x50;
    public static final int SMASH_BLOCK     = 0x51;
    public static final int MOVING_BLOCK    = 0x52;
    public static final int COLLAPSING_FLOOR = 0x53;
    public static final int LAVA_TAG        = 0x54;
    public static final int BATBRAIN        = 0x55;
    public static final int FLOATING_BLOCK  = 0x56;
    public static final int SPIKED_BALL_CHAIN = 0x57;
    public static final int BIG_SPIKED_BALL = 0x58;
    public static final int SLZ_ELEVATOR    = 0x59;
    public static final int SLZ_CIRCLING_PLATFORM = 0x5A;
    public static final int SLZ_STAIRCASE   = 0x5B;
    public static final int PYLON           = 0x5C;
    public static final int FAN             = 0x5D;
    public static final int SEESAW          = 0x5E;
    public static final int BOMB            = 0x5F;
    public static final int ORBINAUT        = 0x60;
    public static final int LABYRINTH_BLOCK = 0x61; // id_LabyrinthBlock
    public static final int GARGOYLE        = 0x62;
    public static final int LZ_CONVEYOR     = 0x63; // id_LabyrinthConvey
    public static final int BUBBLES         = 0x64;
    public static final int WATERFALL       = 0x65;
    public static final int JUNCTION        = 0x66; // id_Junction - rotating disc junction that grabs Sonic (SBZ)
    public static final int RUNNING_DISC    = 0x67; // id_RunningDisc - disc spot that orbits in a circle (SBZ)
    public static final int SBZ_CONVEYOR_BELT = 0x68; // id_Conveyor - conveyor belts (SBZ)
    public static final int SBZ_SPINNING_PLATFORM = 0x69; // id_SpinPlatform - trapdoors & spinning platforms
    public static final int SBZ_SAW               = 0x6A; // id_Saws - ground saws & pizza cutters (SBZ)
    public static final int SBZ_STOMPER_DOOR      = 0x6B; // id_ScrapStomp - stomper and sliding door (SBZ)
    public static final int SBZ_VANISHING_PLATFORM = 0x6C; // id_VanishPlatform - vanishing platforms
    public static final int FLAMETHROWER    = 0x6D; // id_Flamethrower - flame thrower (SBZ)
    public static final int ELECTROCUTER    = 0x6E; // id_Electro - electrocution orbs (SBZ)
    public static final int SBZ_SPIN_CONVEYOR = 0x6F; // id_SpinConvey - spinning platforms on conveyor belt (SBZ)
    public static final int GIRDER          = 0x70; // id_Girder - large girder block (SBZ)
    public static final int INVISIBLE_BARRIER = 0x71;
    public static final int TELEPORTER        = 0x72; // id_Teleport - teleporter tubes (SBZ)
    public static final int MZ_BOSS           = 0x73; // id_BossMarble
    public static final int BOSS_FIRE         = 0x74; // id_BossFire
    public static final int SYZ_BOSS          = 0x75; // id_BossSpringYard
    public static final int SYZ_BOSS_BLOCK    = 0x76; // id_BossBlock
    public static final int LZ_BOSS           = 0x77; // id_BossLabyrinth
    public static final int CATERKILLER     = 0x78;
    public static final int LAMPPOST        = 0x79;
    public static final int SLZ_BOSS          = 0x7A; // id_BossStarLight
    public static final int SLZ_BOSS_SPIKEBALL = 0x7B; // id_BossSpikeball (dynamically spawned by SLZ boss)
    public static final int RING_FLASH      = 0x7C;
    public static final int HIDDEN_BONUS    = 0x7D;
    public static final int SCRAP_EGGMAN    = 0x82; // id_ScrapEggman - Eggman cutscene in SBZ2
    public static final int FALSE_FLOOR     = 0x83; // id_FalseFloor - collapsing floor blocks in SBZ2
    public static final int EGGMAN_CYLINDER = 0x84; // id_EggmanCylinder - FZ crushing cylinders
    public static final int FZ_BOSS         = 0x85; // id_BossFinal - Final Zone boss
    public static final int BOSS_PLASMA     = 0x86; // id_BossPlasma - FZ plasma ball launcher
    public static final int END_SONIC       = 0x87; // id_EndSonic - Ending sequence Sonic
    public static final int END_CHAOS       = 0x88; // id_EndChaos - Ending sequence chaos emeralds
    public static final int END_STH         = 0x89; // id_EndSTH - Ending sequence "SONIC THE HEDGEHOG" text
    public static final int CREDITS_TEXT    = 0x8A; // id_CreditsText - Credits / "TRY AGAIN" text
    public static final int END_EGGMAN     = 0x8B; // id_EndEggman - Eggman on TRY AGAIN / END screens
    public static final int TRY_CHAOS      = 0x8C; // id_TryChaos - Chaos emeralds on TRY AGAIN screen
}
