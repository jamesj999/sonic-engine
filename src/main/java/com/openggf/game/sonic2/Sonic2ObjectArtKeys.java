package com.openggf.game.sonic2;

import com.openggf.level.objects.ObjectArtKeys;

/**
 * Sonic 2-specific object art keys.
 * These keys are for objects that only exist in Sonic 2 or have
 * Sonic 2-specific implementations.
 */
public final class Sonic2ObjectArtKeys {

    private Sonic2ObjectArtKeys() {
        // Constants only
    }

    // EHZ-specific
    public static final String WATERFALL = "waterfall";

    // CNZ-specific
    public static final String BUMPER = "bumper";
    public static final String HEX_BUMPER = "hex_bumper";
    public static final String BONUS_BLOCK = "bonus_block";
    public static final String FLIPPER = "flipper";
    // Animation keys intentionally alias the base sprite sheet keys - animations and sheets
    // are stored in separate maps, so the same string key is used for both lookups.
    public static final String ANIM_FLIPPER = "flipper";
    public static final String LAUNCHER_SPRING_VERT = "launcher_spring_vert";
    public static final String LAUNCHER_SPRING_DIAG = "launcher_spring_diag";
    public static final String CNZ_RECT_BLOCKS = "cnz_rect_blocks";
    public static final String CNZ_BIG_BLOCK = "cnz_big_block";
    public static final String CNZ_ELEVATOR = "cnz_elevator";
    public static final String CNZ_CAGE = "cnz_cage";
    public static final String CNZ_BONUS_SPIKE = "cnz_bonus_spike";

    // CPZ-specific
    public static final String SPEED_BOOSTER = "speed_booster";
    public static final String BLUE_BALLS = "blue_balls";
    public static final String BREAKABLE_BLOCK = "breakable_block";
    public static final String CPZ_PLATFORM = "cpz_platform";
    public static final String CPZ_STAIR_BLOCK = "cpz_stair_block";
    public static final String CPZ_PYLON = "cpz_pylon";
    public static final String PIPE_EXIT_SPRING = "pipe_exit_spring";
    public static final String ANIM_PIPE_EXIT_SPRING = "pipe_exit_spring";
    public static final String TIPPING_FLOOR = "tipping_floor";
    public static final String ANIM_TIPPING_FLOOR = "tipping_floor";

    // CPZ/DEZ shared
    public static final String BARRIER = "barrier";

    // CPZ/MCZ shared
    public static final String SIDEWAYS_PFORM = "sideways_pform";

    // CPZ/ARZ/MCZ shared
    public static final String SPRINGBOARD = "springboard";
    public static final String ANIM_SPRINGBOARD = "springboard";

    // Underwater objects (ARZ/CPZ/HPZ) — delegates to cross-game constant
    public static final String BUBBLES = ObjectArtKeys.BUBBLES;

    // ARZ specific
    public static final String LEAVES = "leaves";

    // OOZ-specific
    public static final String LAUNCH_BALL = "launch_ball";
    public static final String OOZ_FAN_HORIZ = "ooz_fan_horiz";
    public static final String OOZ_FAN_VERT = "ooz_fan_vert";
    public static final String OOZ_LAUNCHER_VERT = "ooz_launcher_vert";
    public static final String OOZ_LAUNCHER_HORIZ = "ooz_launcher_horiz";
    public static final String OOZ_BURNER_LID = "ooz_burner_lid";
    public static final String OOZ_BURN_FLAME = "ooz_burn_flame";
    public static final String OOZ_SLIDING_SPIKE = "ooz_sliding_spike";
    public static final String OOZ_PRESSURE_SPRING = "ooz_pressure_spring";

    // Collapsing Platform (OOZ/MCZ)
    public static final String OOZ_COLLAPSING_PLATFORM = "ooz_collapsing_platform";
    public static final String MCZ_COLLAPSING_PLATFORM = "mcz_collapsing_platform";

    // OOZ Badniks
    public static final String OCTUS = "octus";
    public static final String AQUIS = "aquis";
    public static final String OOZ_BOSS = "ooz_boss";

    // EHZ Badniks
    public static final String MASHER = "masher";
    public static final String BUZZER = "buzzer";
    public static final String COCONUTS = "coconuts";

    // MCZ Badniks
    public static final String CRAWLTON = "crawlton";
    public static final String FLASHER = "flasher";

    // CPZ Badniks
    public static final String SPINY = "spiny";
    public static final String GRABBER = "grabber";
    public static final String GRABBER_STRING = "grabber_string";

    // ARZ Badniks
    public static final String CHOP_CHOP = "chopchop";
    public static final String WHISP = "whisp";
    public static final String GROUNDER = "grounder";
    public static final String GROUNDER_ROCK = "grounder_rock";

    // MTZ Badniks
    public static final String SHELLCRACKER = "shellcracker";
    public static final String SLICER = "slicer";
    public static final String ASTERON = "asteron";

    // HTZ Badniks
    public static final String SPIKER = "spiker";
    public static final String SOL = "sol";
    public static final String REXON = "rexon";

    // SCZ Badniks
    public static final String NEBULA = "nebula";
    public static final String TURTLOID = "turtloid";
    public static final String BALKIRY = "balkiry";

    // WFZ Badniks
    public static final String CLUCKER = "clucker";
    public static final String WFZ_STICK = "wfz_stick";

    // CNZ Badniks
    public static final String CRAWL = "crawl";

    // HTZ Objects
    public static final String SEESAW = "seesaw";
    public static final String SEESAW_BALL = "seesaw_ball";
    public static final String HTZ_LIFT = "htz_lift";
    public static final String SMASHABLE_GROUND = "smashable_ground";

    // ARZ Objects
    public static final String ARROW_SHOOTER = "arrow_shooter";

    // MTZ/MCZ Objects
    public static final String BUTTON = "button";
    public static final String MTZ_PLATFORM = CPZ_STAIR_BLOCK;
    public static final String MTZ_OBJ6A_6B = CPZ_STAIR_BLOCK;
    public static final String MTZ_COG = "mtz_cog";
    public static final String MTZ_NUT = "mtz_nut";
    public static final String MTZ_FLOOR_SPIKE = "mtz_floor_spike";
    public static final String MTZ_SPIKE_BLOCK = "mtz_spike_block";
    public static final String MTZ_SPIKE = "mtz_spike";
    public static final String MTZ_LAVA_BUBBLE = "mtz_lava_bubble";
    public static final String MTZ_STEAM = "mtz_steam";
    public static final String MTZ_SPIN_TUBE_FLASH = "mtz_spin_tube_flash";
    public static final String MTZ_STEAM_PISTON = "mtz_steam_piston";
    public static final String MTZ_WHEEL = "mtz_wheel";
    public static final String MTZ_WHEEL_INDENT = "mtz_wheel_indent";
    public static final String MTZ_LAVA_CUP = "mtz_lava_cup";

    // MCZ Objects
    public static final String VINE_PULLEY = "vine_pulley";
    public static final String MCZ_CRATE = "mcz_crate";
    public static final String MCZ_BRIDGE = "mcz_bridge";
    public static final String MCZ_DRAWBRIDGE = "mcz_drawbridge";
    public static final String VINE_SWITCH = "vine_switch";

    // WFZ Objects
    public static final String WFZ_HOOK = "wfz_hook";
    public static final String WFZ_UNKNOWN = "wfz_unknown";
    public static final String WFZ_PLATFORM = "wfz_platform";
    public static final String WFZ_TILT_PLATFORM = "wfz_tilt_platform";
    public static final String WFZ_BELT_PLATFORM = "wfz_belt_platform";
    public static final String WFZ_GUN_PLATFORM = "wfz_gun_platform";
    public static final String TORNADO = "tornado";
    public static final String TORNADO_THRUSTER = "tornado_thruster";
    public static final String WFZ_THRUST = "wfz_thrust";
    public static final String WFZ_VPROPELLER = "wfz_vpropeller";
    public static final String WFZ_HPROPELLER = "wfz_hpropeller";
    public static final String WFZ_WALL_TURRET = "wfz_wall_turret";
    public static final String WFZ_LASER = "wfz_laser";
    public static final String WFZ_VERTICAL_LASER = "wfz_vertical_laser";
    public static final String WFZ_LAUNCH_CATAPULT = "wfz_launch_catapult";
    public static final String WFZ_BREAK_PANELS = "wfz_break_panels";
    public static final String WFZ_RIVET = "wfz_rivet";
    public static final String WFZ_CONVEYOR_BELT_WHEEL = "wfz_conveyor_belt_wheel";

    // SCZ Objects
    public static final String CLOUDS = "clouds";

    // Bosses
    public static final String EHZ_BOSS = "ehz_boss";
    public static final String MCZ_BOSS = "mcz_boss";
    public static final String MCZ_FALLING_ROCKS = "mcz_falling_rocks";
    public static final String BOSS_EXPLOSION = ObjectArtKeys.BOSS_EXPLOSION;
    public static final String CPZ_BOSS_EGGPOD = "cpz_boss_eggpod";
    public static final String CPZ_BOSS_PARTS = "cpz_boss_parts";
    public static final String CPZ_BOSS_JETS = "cpz_boss_jets";
    public static final String CPZ_BOSS_SMOKE = "cpz_boss_smoke";
    public static final String ARZ_BOSS_MAIN = "arz_boss_main";
    public static final String ARZ_BOSS_PARTS = "arz_boss_parts";
    public static final String CNZ_BOSS = "cnz_boss";
    public static final String HTZ_BOSS = "htz_boss";
    public static final String HTZ_BOSS_SMOKE = "htz_boss_smoke";
    public static final String MTZ_BOSS = "mtz_boss";
    public static final String DEZ_SILVER_SONIC = "dez_silver_sonic";
    public static final String DEZ_WINDOW = "dez_window";
    public static final String DEZ_BOSS = "dez_boss";
    public static final String DEZ_EGGMAN = "dez_eggman";
    public static final String DEZ_WALL = "dez_wall";
    public static final String WFZ_BOSS = "wfz_boss";
    public static final String WFZ_ROBOTNIK = "wfz_robotnik";
    public static final String WFZ_ROBOTNIK_PLATFORM = "wfz_robotnik_platform";

    // Lava objects (HTZ)
    public static final String LAVA_BUBBLE = "lava_bubble";
    public static final String GROUND_FIRE = "ground_fire";

    // Super Sonic stars (Object 0x7E) — delegates to cross-game constant
    public static final String SUPER_SONIC_STARS = ObjectArtKeys.SUPER_SONIC_STARS;
}
