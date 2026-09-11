package com.openggf.game.sonic2.constants;

public final class Sonic2ObjectIds {
    public static final int LAYER_SWITCHER = 0x03;
    public static final int OIL = 0x07;                  // OOZ oil surface (Obj07)
    public static final int SPRING = 0x41;
    public static final int STEAM_SPRING = 0x42;   // SteamSpring (Obj42) - MTZ steam-powered spring piston
    public static final int BUTTON = 0x47;         // Button (Obj47) - trigger button that activates other objects via ButtonVine_Trigger
    public static final int SPIKES = 0x36;
    public static final int MONITOR = 0x26;
    public static final int MONITOR_CONTENTS = 0x2E;
    public static final int CHECKPOINT = 0x79;
    public static final int BRIDGE = 0x11;
    public static final int SEESAW = 0x14;
    public static final int SWINGING_PLATFORM = 0x15;
    public static final int GENERIC_PLATFORM_A = 0x18;
    public static final int GENERIC_PLATFORM_B = 0x19;
    public static final int BRIDGE_STAKE = 0x1C;
    public static final int SPIRAL = 0x06;
    public static final int EHZ_WATERFALL = 0x49;

    // OOZ Badniks
    public static final int OCTUS = 0x4A;
    public static final int AQUIS = 0x50;

    // EHZ Badniks
    public static final int BUZZER = 0x4B;
    public static final int MASHER = 0x5C;
    public static final int COCONUTS = 0x9D;

    // MCZ Badniks
    public static final int CRAWLTON = 0x9E; // Snake badnik from MCZ - lunges at player with trailing body
    public static final int FLASHER = 0xA3;

    // MTZ Badniks
    public static final int SHELLCRACKER = 0x9F;   // Shellcracker (crab badnik)
    public static final int SHELLCRACKER_CLAW = 0xA0; // Shellcracker's claw (spawned dynamically)
    public static final int SLICER = 0xA1;         // Slicer (praying mantis badnik)
    public static final int SLICER_PINCERS = 0xA2; // Slicer's thrown pincers (projectile)
    public static final int ASTERON = 0xA4;

    // CPZ Badniks
    public static final int SPINY = 0xA5;
    public static final int SPINY_ON_WALL = 0xA6;
    public static final int GRABBER = 0xA7;

    // SCZ Badniks
    public static final int NEBULA = 0x99;          // Nebula (bomber badnik from SCZ)
    public static final int TURTLOID = 0x9A;
    public static final int TURTLOID_RIDER = 0x9B;
    public static final int BALKIRY_JET = 0x9C;     // Balkiry's jet exhaust (child of Balkiry, spawned dynamically)
    public static final int BALKIRY = 0xAC;         // Balkiry (jet badnik from SCZ)

    // WFZ Badniks
    public static final int CLUCKER_BASE = 0xAD;   // CluckerBase (turret platform) from WFZ
    public static final int CLUCKER = 0xAE;         // Clucker (chicken badnik) from WFZ

    // ARZ Badniks
    public static final int CHOP_CHOP = 0x91;

    // HTZ Badniks
    public static final int SPIKER = 0x92;       // Spiker (drill badnik)
    public static final int SPIKER_DRILL = 0x93; // Spiker drill projectile
    public static final int REXON = 0x94;        // Rexon (lava snake) body
    public static final int SOL = 0x95;          // Sol (fireball badnik)
    public static final int REXON2 = 0x96;       // Rexon alias (same as 0x94)
    public static final int REXON_HEAD = 0x97;   // Rexon's head segment (spawned dynamically)

    // CNZ Badniks
    public static final int CRAWL = 0xC8;  // Bouncer badnik from CNZ - bounces player from front
    public static final int WHISP = 0x8C;
    public static final int GROUNDER_IN_WALL = 0x8D;   // Grounder hiding behind wall (spawns walls + rocks)
    public static final int GROUNDER_IN_WALL2 = 0x8E;  // Grounder variant (skips wall setup, walks immediately)
    public static final int GROUNDER_WALL = 0x8F;      // Wall piece (spawned dynamically, not in level data)
    public static final int GROUNDER_ROCKS = 0x90;     // Rock projectiles (spawned dynamically)

    // ARZ Objects
    public static final int ARROW_SHOOTER = 0x22;
    public static final int BUBBLES = 0x24;       // Bubble Generator (Obj24) - spawns breathable bubbles underwater

    // Supporting objects spawned by Badniks
    public static final int PROJECTILE = 0x98;
    public static final int EXPLOSION = 0x27;
    public static final int ANIMAL = 0x28;
    public static final int POINTS = 0x29;

    // Level completion
    public static final int SIGNPOST = 0x0D; // End of level signpost
    public static final int EGG_PRISON = 0x3E; // Egg prison / capsule (end of act 2)
    public static final int INVISIBLE_BLOCK = 0x74;

    // Bosses
    public static final int CNZ_BOSS = 0x51; // CNZ Act 2 Boss (electricity boss)
    public static final int HTZ_BOSS = 0x52; // HTZ Act 2 Boss (lava flamethrower boss)
    public static final int MTZ_BOSS_ORB = 0x53; // Obj53 - Shield orbs that surround MTZ boss
    public static final int MTZ_BOSS = 0x54;     // Obj54 - MTZ boss (orbiting shield + laser)
    public static final int OOZ_BOSS = 0x55;     // Obj55 - OOZ Act 2 submarine / laser boss
    public static final int EHZ_BOSS = 0x56; // EHZ Act 2 Boss (drill car boss)
    public static final int MCZ_BOSS = 0x57; // MCZ Act 2 Boss (drill-digger boss)
    public static final int CPZ_BOSS = 0x5D; // CPZ Act 2 Boss (water dropper boss)
    public static final int ARZ_BOSS = 0x89; // ARZ Act 2 Boss (hammer/arrow boss)
    public static final int MECHA_SONIC = 0xAF; // DEZ Silver Sonic / Mecha Sonic (first DEZ boss)
    public static final int WFZ_BOSS = 0xC5;    // WFZ Boss (ObjC5) - laser platform boss
    public static final int DEZ_EGGMAN = 0xC6; // DEZ Eggman transition object (ObjC6)
    public static final int DEATH_EGG_ROBOT = 0xC7; // DEZ Death Egg Robot (ObjC7) - final boss
    public static final int BOSS_EXPLOSION = 0x58; // Boss explosion (Obj58)
    public static final int LAVA_BUBBLE = 0x20;    // Lava bubble (Obj20) - spawned when HTZ lava ball hits ground

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

    // ARZ Objects
    public static final int LEAVES_GENERATOR = 0x2C; // Leaves Generator (Obj2C) - spawns falling leaves on contact

    // CPZ/ARZ/MCZ Objects
    public static final int SPRINGBOARD = 0x40;  // Pressure spring / lever spring (Obj40)

    // CNZ Objects
    public static final int BUMPER = 0x44;       // Round Bumper (Obj44)
    public static final int HEX_BUMPER = 0xD7;   // Hexagonal Bumper (ObjD7)
    public static final int BONUS_BLOCK = 0xD8; // Bonus Block / Drop Target (ObjD8)
    public static final int FLIPPER = 0x86;    // CNZ Flipper (Obj86)

    // CPZ Foreground Objects
    public static final int CPZ_PYLON = 0x7C;  // CPZ Pylon (Obj7C) - decorative background pylon

    // MTZ Objects
    public static final int MTZ_TWIN_STOMPERS = 0x64; // MTZ Twin Stompers (Obj64) - crushing piston pair from MTZ
    public static final int MTZ_LONG_PLATFORM = 0x65; // MTZ Long Platform (Obj65) - long moving platform with cog child
    public static final int MTZ_SPRING_WALL = 0x66;   // MTZ Spring Wall (Obj66) - invisible wall that bounces player
    public static final int MTZ_SPIN_TUBE = 0x67;     // MTZ Spin Tube (Obj67) - tube transport with sinusoidal entry
    public static final int FLOOR_SPIKE = 0x6D; // Floor Spike (Obj6D) - retractable floor spike from MTZ
    public static final int LARGE_ROT_PFORM = 0x6E; // LargeRotPform (Obj6E) - circular moving platform from MTZ
    public static final int COG = 0x70; // Cog (Obj70) - giant rotating cog from MTZ
    public static final int MTZ_PLATFORM = 0x6B; // MTZ Platform (Obj6B) - multi-purpose platform with 12 movement subtypes
    public static final int CONVEYOR = 0x6C; // Conveyor (Obj6C) - small platform on pulleys from MTZ
    public static final int MTZ_LAVA_BUBBLE = 0x71; // MTZ Lava Bubble (Obj71) - animated lava bubble scenery
    public static final int SIDEWAYS_PFORM = 0x7A; // Sideways Platform (Obj7A) - CPZ/MCZ horizontal moving platform

    // ARZ Objects
    public static final int FALLING_PILLAR = 0x23; // Falling Pillar (Obj23) - ARZ pillar that drops its lower section
    public static final int RISING_PILLAR = 0x2B;  // Rising Pillar (Obj2B) - ARZ pillar that rises and launches player
    public static final int SWINGING_PFORM = 0x82; // Swinging Platform (Obj82) - ARZ swinging vine platform

    // OOZ Objects
    public static final int OOZ_POPPING_PLATFORM = 0x33; // OOZPoppingPform (Obj33) - green burner platform that pops up
    public static final int OOZ_LAUNCHER = 0x3D; // OOZLauncher (Obj3D) - breakable block that launches rolling player
    public static final int FAN = 0x3F; // Fan (Obj3F) - OOZ wind fan (horizontal/vertical push)
    public static final int SLIDING_SPIKE = 0x43; // SlidingSpike (Obj43) - paired sliding spike obstacle
    public static final int OOZ_SPRING = 0x45; // OOZSpring (Obj45) - pressure spring from OOZ
    public static final int LAUNCHER_BALL = 0x48; // LauncherBall (Obj48) - OOZ transporter ball

    // OOZ/MCZ/ARZ Objects
    public static final int COLLAPSING_PLATFORM = 0x1F; // Collapsing Platform (Obj1F) - OOZ/MCZ/ARZ

    // MCZ Objects
    public static final int STOMPER = 0x2A;         // Stomper (Obj2A) - MCZ ceiling crusher
    public static final int MCZ_BRICK = 0x75;       // MCZ Brick / Spike Ball (Obj75)
    public static final int SLIDING_SPIKES = 0x76;  // MCZ Sliding Spikes (Obj76)
    public static final int VINE_SWITCH = 0x7F;     // VineSwitch (Obj7F) - MCZ pull switch that triggers ButtonVine
    public static final int MOVING_VINE = 0x80;     // MovingVine (Obj80) - MCZ vine / WFZ hook on chain
    public static final int MCZ_BRIDGE = 0x77;      // MCZ Bridge (Obj77) - horizontal gate triggered by ButtonVine
    public static final int MCZ_DRAWBRIDGE = 0x81;  // MCZ Drawbridge (Obj81) - rotatable drawbridge triggered by ButtonVine

    // MTZ Objects
    public static final int SPIKY_BLOCK = 0x68; // SpikyBlock (Obj68) - block with rotating spike from MTZ
    public static final int NUT = 0x69; // Nut (Obj69) - screw nut that moves vertically when player pushes it

    // MCZ/MTZ Rotating Platforms
    public static final int MCZ_ROT_PFORMS = 0x6A; // Rotating Platforms (Obj6A) - MCZ wooden crate / MTZ moving platform

    // ARZ Rotating Platforms
    public static final int ARZ_ROT_PFORMS = 0x83; // Rotating Platforms (Obj83) - 3 platforms orbiting center

    // SCZ/WFZ Objects
    public static final int TORNADO = 0xB2; // Tornado (ObjB2) - SCZ/WFZ scripted biplane sequence
    public static final int CLOUD = 0xB3; // Cloud (ObjB3) - decorative clouds from Sky Chase Zone
    public static final int HPROPELLER = 0xB5; // HPropeller (ObjB5) - horizontal spinning propeller from WFZ/SCZ

    // CNZ/HTZ Objects
    public static final int FORCED_SPIN = 0x84; // ForcedSpin/Pinball Mode (Obj84) - CNZ/HTZ
    public static final int LAUNCHER_SPRING = 0x85; // LauncherSpring (Obj85) - CNZ pressure spring

    // HTZ Objects
    public static final int HTZ_LIFT = 0x16;          // HTZ Zipline Lift (Obj16) - diagonal moving platform
    public static final int SMASHABLE_GROUND = 0x2F;  // Smashable Ground (Obj2F) - breakable rock platform
    public static final int RISING_LAVA = 0x30;       // Rising Lava (Obj30) - invisible solid platform during earthquakes
    public static final int LAVA_MARKER = 0x31;       // Lava Marker (Obj31) - invisible hazard collision zone

    // WFZ Objects
    public static final int WFZ_PAL_SWITCHER = 0x8B; // WFZPalSwitcher (Obj8B) - cycling palette switcher from WFZ
    public static final int VPROPELLER = 0xB4; // VPropeller (ObjB4) - vertical propeller platform from WFZ/SCZ
    public static final int TILTING_PLATFORM = 0xB6; // TiltingPlatform (ObjB6) - tilting/spinning platform from WFZ
    public static final int VERTICAL_LASER = 0xB7; // VerticalLaser (ObjB7) - unused huge vertical laser from WFZ
    public static final int WALL_TURRET = 0xB8; // WallTurret (ObjB8) - wall-mounted turret from WFZ
    public static final int LASER = 0xB9; // Laser (ObjB9) - horizontal laser beam from WFZ that shoots down the Tornado
    public static final int WFZ_WHEEL = 0xBA; // WFZWheel (ObjBA) - conveyor belt wheel decoration from WFZ
    public static final int WFZ_UNKNOWN = 0xBB; // Removed unknown WFZ object (ObjBB)
    public static final int WFZ_SHIP_FIRE = 0xBC; // WFZShipFire (ObjBC) - fire/flame from Robotnik's ship in WFZ
    public static final int SMALL_METAL_PFORM = 0xBD; // SmallMetalPform (ObjBD) - ascending/descending belt platform from WFZ
    public static final int LATERAL_CANNON = 0xBE; // LateralCannon (ObjBE) - retracting platform from WFZ
    public static final int WFZ_STICK = 0xBF; // WFZStick (ObjBF) - unused rotating stick badnik from WFZ
    public static final int SPEED_LAUNCHER = 0xC0; // SpeedLauncher (ObjC0) - catapult platform from WFZ
    public static final int BREAKABLE_PLATING = 0xC1; // BreakablePlating (ObjC1) - breakable plating from WFZ / Robotnik's getaway ship
    public static final int RIVET = 0xC2; // Rivet (ObjC2) - rivet at end of WFZ that opens the ship when busted
    public static final int TORNADO_SMOKE = 0xC3; // TornadoSmoke (ObjC3) - plane smoke from WFZ
    public static final int TORNADO_SMOKE_2 = 0xC4; // TornadoSmoke2 (ObjC4) - same routine as ObjC3
    public static final int GRAB = 0xD9; // Grab (ObjD9) - invisible hang-on point from WFZ

    // CNZ/MTZ/WFZ Objects
    public static final int CNZ_CONVEYOR_BELT = 0x72; // Conveyor Belt (Obj72) - invisible velocity zone

    // CNZ Flashing Blocks
    public static final int CNZ_RECT_BLOCKS = 0xD2; // CNZ Rect Blocks (ObjD2) - flashing "caterpillar" blocks
    public static final int CNZ_BIG_BLOCK = 0xD4;   // CNZ Big Block (ObjD4) - large 64x64 oscillating platform
    public static final int CNZ_ELEVATOR = 0xD5;    // CNZ Elevator (ObjD5) - vertical platform that moves when stood on
    public static final int POINT_POKEY = 0xD6;     // CNZ Point Pokey (ObjD6) - cage that captures player and awards points
    public static final int BOMB_PRIZE = 0xD3;     // CNZ Bomb Prize (ObjD3) - bomb/spike prize from slot machine
    public static final int RING_PRIZE = 0xDC;     // CNZ Ring Prize (ObjDC) - ring prize from slot machine

    private Sonic2ObjectIds() {
    }

}
