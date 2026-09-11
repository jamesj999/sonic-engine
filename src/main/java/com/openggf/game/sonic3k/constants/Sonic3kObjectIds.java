package com.openggf.game.sonic3k.constants;

/**
 * Named Sonic 3 & Knuckles object IDs used by the engine's S3K object registry.
 *
 * <p>The S3KL/SKL pointer tables remap some high IDs by zone set. New constants
 * added for the CNZ bring-up intentionally follow the S3KL naming from
 * {@code Object pointers - SK Set 1.asm}, while registry code remains zone-set
 * aware so the same numeric slot can still resolve to DEZ names on the SKL side.
 */
public final class Sonic3kObjectIds {
    public static final int MONITOR = 0x01;
    public static final int PATH_SWAP = 0x02;
    public static final int AIZ_HOLLOW_TREE = 0x03;
    public static final int MHZ_TWISTED_VINE = 0x03;
    public static final int COLLAPSING_PLATFORM = 0x04;
    public static final int AIZLRZ_ROCK = 0x05;
    public static final int AIZ_RIDE_VINE = 0x06;
    public static final int MHZ_PULLEY_LIFT = 0x06;
    public static final int SPRING = 0x07;
    public static final int SPIKES = 0x08;
    public static final int AIZ1_TREE = 0x09;
    public static final int MHZ_CURLED_VINE = 0x09;
    public static final int AIZ1_ZIPLINE_PEG = 0x0A;
    public static final int MHZ_STICKY_VINE = 0x0A;
    public static final int MHZ_SWING_BAR_HORIZONTAL = 0x0B;
    public static final int AIZ_GIANT_RIDE_VINE = 0x0C;
    public static final int MHZ_SWING_BAR_VERTICAL = 0x0C;
    public static final int BREAKABLE_WALL = 0x0D;
    public static final int TWISTED_RAMP = 0x0E;
    public static final int COLLAPSING_BRIDGE = 0x0F;
    public static final int LBZ_TUBE_ELEVATOR = 0x10;
    public static final int MHZ_SWING_VINE = 0x10;
    public static final int LBZ_MOVING_PLATFORM = 0x11;
    public static final int MHZ_MUSHROOM_PLATFORM = 0x11;
    public static final int MHZ_MUSHROOM_PARACHUTE = 0x12;
    // S3KL object table: Obj_LBZExplodingTrigger.
    public static final int LBZ_EXPLODING_TRIGGER = 0x13;
    public static final int MHZ_MUSHROOM_CATAPULT = 0x13;
    // S3KL object table: Obj_LBZTriggerBridge. SKL reuses $14 for Obj_Updraft.
    public static final int LBZ_TRIGGER_BRIDGE = 0x14;
    public static final int UPDRAFT = 0x14;
    // S3KL object table: Obj_LBZPlayerLauncher.
    public static final int LBZ_PLAYER_LAUNCHER = 0x15;
    // S3KL object table: Obj_LBZFlameThrower.
    public static final int LBZ_FLAME_THROWER = 0x16;
    // S3KL object table: Obj_LBZRideGrapple.
    public static final int LBZ_RIDE_GRAPPLE = 0x17;
    // S3KL object table: Obj_LBZCupElevator.
    public static final int LBZ_CUP_ELEVATOR = 0x18;
    // S3KL object table: Obj_LBZCupElevatorPole.
    public static final int LBZ_CUP_ELEVATOR_POLE = 0x19;
    // S3KL object table: Obj_LBZPipePlug. SKL reuses $1B for Obj_LRZFireballLauncher.
    public static final int LBZ_PIPE_PLUG = 0x1B;
    // S3KL object table: Obj_LBZSpinLauncher. SKL reuses $1E for Obj_LRZDashElevator.
    public static final int LBZ_SPIN_LAUNCHER = 0x1E;
    // S3KL object table: Obj_LBZLoweringGrapple. SKL reuses $1F for Obj_LRZLavaFall.
    public static final int LBZ_LOWERING_GRAPPLE = 0x1F;
    public static final int AUTOMATIC_TUNNEL = 0x24;
    public static final int AUTO_SPIN = 0x26;
    public static final int CORK_FLOOR = 0x2A;
    public static final int AIZ_FLIPPING_BRIDGE = 0x2B;
    public static final int AIZ_COLLAPSING_LOG_BRIDGE = 0x2C;
    public static final int AIZ_FALLING_LOG = 0x2D;
    public static final int AIZ_SPIKED_LOG = 0x2E;
    // S3KL object table: Obj_LBZRollingDrum.
    public static final int LBZ_ROLLING_DRUM = 0x31;
    public static final int MHZ_MUSHROOM_CAP = 0x23;
    public static final int INVISIBLE_BLOCK = 0x28;
    public static final int AIZ_DISAPPEARING_FLOOR = 0x29;
    public static final int AIZ_DRAW_BRIDGE = 0x32;
    public static final int SINKING_MUD = 0x4F;
    public static final int MGZ_TWISTING_LOOP = 0x50;
    public static final int FLOATING_PLATFORM = 0x51;
    public static final int BUMPER = 0x4A;
    // S3KL object table: Obj_CNZTriangleBumpers.
    public static final int CNZ_TRIANGLE_BUMPER = 0x4B;
    public static final int BUBBLER = 0x54;
    public static final int BUTTON = 0x33;
    public static final int STAR_POST = 0x34;
    public static final int AIZ_FOREGROUND_PLANT = 0x35;
    public static final int HCZ_BREAKABLE_BAR = 0x36;
    public static final int HCZ_WATER_RUSH = 0x37;
    public static final int HCZ_CGZ_FAN = 0x38;
    /** Obj_FBZDEZPlayerLauncher -- id $78 in both SK object pointer sets. */
    public static final int FBZ_DEZ_PLAYER_LAUNCHER = 0x78;
    public static final int HCZ_LARGE_FAN = 0x39;
    public static final int HCZ_SPINNING_COLUMN = 0x68;
    public static final int HCZ_WATER_WALL = 0x3B;
    public static final int DOOR = 0x3C;
    public static final int HCZ_HAND_LAUNCHER = 0x3A;
    public static final int HCZ_CONVEYOR_BELT = 0x3E;
    public static final int HCZ_CONVEYOR_SPIKE = 0x3F;
    public static final int HCZ_BLOCK = 0x40;
    // S3KL object table: Obj_CNZBalloon.
    public static final int CNZ_BALLOON = 0x41;
    // S3KL object table: Obj_CNZCannon.
    public static final int CNZ_CANNON = 0x42;
    // S3KL object table: Obj_CNZRisingPlatform.
    public static final int CNZ_RISING_PLATFORM = 0x43;
    // S3KL object table: Obj_CNZTrapDoor.
    public static final int CNZ_TRAP_DOOR = 0x44;
    // S3KL object table: Obj_CNZLightBulb.
    public static final int CNZ_LIGHT_BULB = 0x45;
    // S3KL object table: Obj_CNZHoverFan.
    public static final int CNZ_HOVER_FAN = 0x46;
    // S3KL object table: Obj_CNZCylinder.
    public static final int CNZ_CYLINDER = 0x47;
    // S3KL object table: Obj_CNZVacuumTube. Controller-only object; no separate mapping/art owner.
    public static final int CNZ_VACUUM_TUBE = 0x48;
    // S3KL object table: Obj_CNZGiantWheel.
    public static final int CNZ_GIANT_WHEEL = 0x49;
    // S3KL object table: Obj_CNZSpiralTube. Controller-only object; no separate mapping/art owner.
    public static final int CNZ_SPIRAL_TUBE = 0x4C;
    // S3KL object table: Obj_CNZBarberPoleSprite.
    public static final int CNZ_BARBER_POLE = 0x4D;
    // S3KL object table: Obj_CNZWireCage.
    public static final int CNZ_WIRE_CAGE = 0x4E;
    public static final int MGZLBZ_SMASHING_PILLAR_ALT = 0x20;
    // S3KL object table: Obj_LBZGateLaser. SKL reuses $21 for Obj_LRZSmashingSpikePlatform.
    public static final int LBZ_GATE_LASER = 0x21;
    // S3KL object table: Obj_LBZAlarm.
    public static final int LBZ_ALARM = 0x22;
    public static final int MGZLBZ_SMASHING_PILLAR = 0x52;
    public static final int MGZ_SWINGING_PLATFORM = 0x53;
    public static final int MGZ_HEAD_TRIGGER = 0x55;
    public static final int MGZ_MOVING_SPIKE_PLATFORM = 0x56;
    public static final int MGZ_TRIGGER_PLATFORM = 0x57;
    public static final int MGZ_SWINGING_SPIKE_BALL = 0x58;
    public static final int MGZ_DASH_TRIGGER = 0x59;
    public static final int MGZ_PULLEY = 0x5A;
    public static final int MGZ_TOP_PLATFORM = 0x5B;
    public static final int MGZ_TOP_LAUNCHER = 0x5C;
    public static final int HCZ_SNAKE_BLOCKS = 0x67;
    public static final int TENSION_BRIDGE = 0x6C;
    public static final int INVISIBLE_HURT_BLOCK_H = 0x6A;
    public static final int INVISIBLE_HURT_BLOCK_V = 0x6B;
    public static final int SS_ENTRY_RING = 0x85;
    public static final int GUMBALL_MACHINE = 0x86;
    public static final int GUMBALL_TRIANGLE_BUMPER = 0x87;
    // S3KL object table: Obj_CNZWaterLevelCorkFloor.
    public static final int CNZ_WATER_LEVEL_CORK_FLOOR = 0x88;
    // S3KL object table: Obj_CNZWaterLevelButton.
    public static final int CNZ_WATER_LEVEL_BUTTON = 0x89;
    public static final int BLOOMINATOR = 0x8C;
    public static final int MADMOLE = 0x8C;
    public static final int RHINOBOT = 0x8D;
    public static final int MUSHMEANIE = 0x8D;
    public static final int MONKEY_DUDE = 0x8E;
    public static final int DRAGONFLY = 0x8E;
    public static final int CATERKILLER_JR = 0x8F;
    public static final int BUTTERDROID = 0x8F;
    public static final int CLUCKOID = 0x90;
    public static final int BLASTOID = 0x94;
    public static final int BUGGERNAUT = 0x95;
    public static final int TURBO_SPIKER = 0x96;
    public static final int MEGA_CHOPPER = 0x97;
    public static final int POINDEXTER = 0x98;
    public static final int BUBBLES_BADNIK = 0x9B;
    public static final int SPIKER = 0x9C;
    public static final int MANTIS = 0x9D;
    public static final int MGZ_MINIBOSS = 0x9F;
    public static final int MGZ_END_BOSS = 0xA1;
    public static final int MGZ_END_BOSS_KNUX = 0xA2;
    public static final int MHZ1_CUTSCENE_KNUCKLES = 0xA8;
    public static final int MHZ1_CUTSCENE_BUTTON = 0xA9;
    public static final int STILL_SPRITE = 0x2F;
    public static final int ANIMATED_STILL_SPRITE = 0x30;
    public static final int HIDDEN_MONITOR = 0x80;
    public static final int EGG_CAPSULE = 0x81;
    public static final int CUTSCENE_KNUCKLES = 0x82;
    public static final int CUTSCENE_BUTTON = 0x83;
    public static final int AIZ_MINIBOSS_CUTSCENE = 0x90;
    public static final int AIZ_MINIBOSS = 0x91;
    public static final int MHZ_MINIBOSS_TREE = 0x91;
    public static final int AIZ_END_BOSS = 0x92;
    public static final int MHZ_MINIBOSS = 0x92;
    public static final int JAWZ = 0x93;
    public static final int MHZ_END_BOSS = 0x93;
    public static final int CORKEY = 0xC1;
    // S3KL object table: Obj_LBZ1Robotnik.
    public static final int LBZ1_ROBOTNIK = 0xC3;
    // S3KL object table: Obj_LBZMinibossBox (star-post restart staging box).
    public static final int LBZ_MINIBOSS_BOX = 0xC4;
    // S3KL object table: Obj_LBZMinibossBoxKnux (Knuckles dual-boss staging box).
    public static final int LBZ_MINIBOSS_BOX_KNUX = 0xC5;
    // S3KL object table: Obj_LBZ2RobotnikShip.
    public static final int LBZ2_ROBOTNIK_SHIP = 0xC6;
    // S3KL object table: Obj_LBZKnuxPillar.
    public static final int LBZ_KNUX_PILLAR = 0xC8;
    public static final int LBZ_MINIBOSS = 0xC9;
    // S3KL object table: Obj_LBZFinalBoss1.
    public static final int LBZ_FINAL_BOSS_1 = 0xCA;
    // S3KL object table: Obj_LBZEndBoss.
    public static final int LBZ_END_BOSS = 0xCB;
    // S3KL object table: Obj_LBZFinalBoss2.
    public static final int LBZ_FINAL_BOSS_2 = 0xCC;
    // S3KL object table: Obj_LBZFinalBossKnux.
    public static final int LBZ_FINAL_BOSS_KNUX = 0xCD;
    public static final int PACHINKO_TRIANGLE_BUMPER = 0xE6;
    public static final int PACHINKO_FLIPPER = 0xE7;
    public static final int PACHINKO_ENERGY_TRAP = 0xE8;
    public static final int PACHINKO_INVISIBLE_UNKNOWN = 0xE9;
    public static final int PACHINKO_PLATFORM = 0xEA;
    public static final int GUMBALL_ITEM = 0xEB;
    public static final int PACHINKO_MAGNET_ORB = 0xEC;
    public static final int PACHINKO_ITEM_ORB = 0xED;
    public static final int HCZ_TWISTING_LOOP = 0x69;
    public static final int HCZ_WATER_SPLASH = 0x6D;
    public static final int HCZ_WATER_DROP = 0x6E;
    // S3KL object table: Obj_ICZPathFollowPlatform.
    public static final int ICZ_PATH_FOLLOW_PLATFORM = 0xB0;
    // S3KL object table: Obj_ICZBreakableWall.
    public static final int ICZ_BREAKABLE_WALL = 0xB1;
    // S3KL object table: Obj_ICZFreezer.
    public static final int ICZ_FREEZER = 0xB2;
    public static final int ICZ_SEGMENT_COLUMN = 0xB3;
    // S3KL object table: Obj_ICZSwingingPlatform.
    public static final int ICZ_SWINGING_PLATFORM = 0xB4;
    // S3KL object table: Obj_ICZStalagtite.
    public static final int ICZ_STALAGTITE = 0xB5;
    // S3KL object table: Obj_ICZIceCube.
    public static final int ICZ_ICE_CUBE = 0xB6;
    // S3KL object table: Obj_ICZIceSpikes. SKL reuses $B7 for DDZAsteroid.
    public static final int ICZ_ICE_SPIKES = 0xB7;
    // S3KL object table: Obj_ICZHarmfulIce. SKL reuses $B8 for DDZMissile.
    public static final int ICZ_HARMFUL_ICE = 0xB8;
    // S3KL object table: Obj_ICZSnowPile.
    public static final int ICZ_SNOW_PILE = 0xB9;
    // S3KL object table: Obj_ICZTensionPlatform.
    public static final int ICZ_TENSION_PLATFORM = 0xBA;
    // S3KL object table: Obj_ICZIceBlock.
    public static final int ICZ_ICE_BLOCK = 0xBB;
    // S3KL object table: Obj_ICZMiniboss.
    public static final int ICZ_MINIBOSS = 0xBC;
    // S3KL object table: Obj_ICZEndBoss.
    public static final int ICZ_END_BOSS = 0xBD;
    // S3KL object table: Obj_SnaleBlaster.
    public static final int SNALE_BLASTER = 0xBE;
    // S3KL object table: Obj_Ribot.
    public static final int RIBOT = 0xBF;
    // S3KL object table: Obj_Orbinaut.
    public static final int ORBINAUT = 0xC0;
    // S3KL object table: Obj_Flybot767.
    public static final int FLYBOT_767 = 0xC2;
    public static final int TUNNELBOT = 0x9E;
    public static final int HCZ_MINIBOSS = 0x99;
    public static final int HCZ_END_BOSS = 0x9A;
    // S3KL object table: Obj_Clamer.
    public static final int CLAMER = 0xA3;
    // S3KL object table: Obj_Sparkle.
    public static final int SPARKLE = 0xA4;
    // S3KL object table: Obj_Batbot.
    public static final int BATBOT = 0xA5;
    // S3KL object table: Obj_CNZMiniboss. SKL reuses $A6 for DEZMiniboss.
    public static final int CNZ_MINIBOSS = 0xA6;
    // S3KL object table: Obj_CNZEndBoss. SKL reuses $A7 for DEZEndBoss.
    public static final int CNZ_END_BOSS = 0xA7;
    // S3KL object table: Obj_Penguinator.
    public static final int PENGUINATOR = 0xAD;
    // S3KL object table: Obj_StarPointer.
    public static final int STAR_POINTER = 0xAE;
    // S3KL object table: Obj_ICZCrushingColumn.
    public static final int ICZ_CRUSHING_COLUMN = 0xAF;

    private Sonic3kObjectIds() {
    }
}
