package com.openggf.game.sonic2;

import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.art.ObjectArtRegistration;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Maps PLC entry ROM addresses to art keys and sprite sheet builder functions.
 *
 * <p>When a PLC entry specifies a Nemesis art address, this registry determines
 * which art key to register it under and which builder method on {@link Sonic2ObjectArt}
 * to call to construct the {@link ObjectSpriteSheet}.
 *
 * <p>Art that requires complex construction (monitor composites, zone-conditional art,
 * results screen) is NOT in this registry — those are loaded manually.
 */
public final class Sonic2PlcArtRegistry {
    private static final Logger LOG = Logger.getLogger(Sonic2PlcArtRegistry.class.getName());

    /** Functional interface for sheet builder methods on Sonic2ObjectArt. */
    @FunctionalInterface
    public interface SheetBuilder {
        ObjectSpriteSheet build(Sonic2ObjectArt art);
    }

    /** Registration entry: provider-owned metadata + builder function. */
    public record ArtRegistration(ObjectArtRegistration metadata, SheetBuilder builder) {
        public String key() {
            return metadata.key();
        }
    }

    private static final Map<Integer, ArtRegistration> REGISTRY = new HashMap<>();

    static {
        // ===== Universal objects (Std1/Std2 PLCs) =====
        // Note: HUD (ART_NEM_HUD_ADDR), life icons, ring art are raw patterns,
        // not sprite sheets — they are loaded manually, not via this registry.

        reg(Sonic2Constants.ART_NEM_SPIKES_ADDR,
                ObjectArtKeys.SPIKE, Sonic2ObjectArt::loadSpikeSheet);
        reg(Sonic2Constants.ART_NEM_SPIKES_SIDE_ADDR,
                ObjectArtKeys.SPIKE_SIDE, Sonic2ObjectArt::loadSpikeSideSheet);
        reg(Sonic2Constants.ART_NEM_SPRING_VERTICAL_ADDR,
                ObjectArtKeys.SPRING_VERTICAL, Sonic2ObjectArt::loadSpringVerticalSheet);
        reg(Sonic2Constants.ART_NEM_SPRING_HORIZONTAL_ADDR,
                ObjectArtKeys.SPRING_HORIZONTAL, Sonic2ObjectArt::loadSpringHorizontalSheet);
        reg(Sonic2Constants.ART_NEM_SPRING_DIAGONAL_ADDR,
                ObjectArtKeys.SPRING_DIAGONAL, Sonic2ObjectArt::loadSpringDiagonalSheet);
        // Explosion (PLCStdWtr/PLC 2) is loaded manually in provider — not via registry dispatch.
        reg(Sonic2Constants.ART_NEM_SHIELD_ADDR,
                ObjectArtKeys.SHIELD, Sonic2ObjectArt::loadShieldSheet);
        reg(Sonic2Constants.ART_NEM_INVINCIBILITY_STARS_ADDR,
                ObjectArtKeys.INVINCIBILITY_STARS, Sonic2ObjectArt::loadInvincibilityStarsSheet);
        reg(Sonic2Constants.ART_NEM_CHECKPOINT_ADDR,
                ObjectArtKeys.CHECKPOINT, Sonic2ObjectArt::loadCheckpointSheet);
        // Signpost (PLC 39) is loaded manually in provider — not via registry dispatch.
        reg(Sonic2Constants.ART_NEM_NUMBERS_ADDR,
                ObjectArtKeys.POINTS, Sonic2ObjectArt::loadPointsSheet);
        // EggPrison (PLC 64) is loaded manually in provider — not via registry dispatch.
        // Boss explosion (PLCStdWtr/WFZ boss PLC) and Super Sonic stars are loaded manually in provider.
        // Keep the fiery explosion registered as well so event-triggered boss PLCs
        // can dispatch the ROM PLC list directly when requested at runtime.
        reg(Sonic2Constants.ART_NEM_FIERY_EXPLOSION_ADDR,
                Sonic2ObjectArtKeys.BOSS_EXPLOSION, Sonic2ObjectArt::loadBossExplosionSheet);

        // ===== EHZ zone objects =====
        // EHZ waterfall art is assembled with the zone art bundle and registered manually by provider.
        reg(Sonic2Constants.ART_NEM_BRIDGE_ADDR,
                ObjectArtKeys.BRIDGE, Sonic2ObjectArt::loadBridgeSheet);
        reg(Sonic2Constants.ART_NEM_BUZZER_ADDR,
                Sonic2ObjectArtKeys.BUZZER, Sonic2ObjectArt::loadBuzzerSheet);
        reg(Sonic2Constants.ART_NEM_COCONUTS_ADDR,
                Sonic2ObjectArtKeys.COCONUTS, Sonic2ObjectArt::loadCoconutsSheet);
        reg(Sonic2Constants.ART_NEM_MASHER_ADDR,
                Sonic2ObjectArtKeys.MASHER, Sonic2ObjectArt::loadMasherSheet);

        // ===== HTZ zone objects =====
        reg(Sonic2Constants.ART_NEM_HTZ_SEESAW_ADDR,
                Sonic2ObjectArtKeys.SEESAW, Sonic2ObjectArt::loadSeesawSheet);
        reg(Sonic2Constants.ART_NEM_SOL_ADDR,
                Sonic2ObjectArtKeys.SOL, Sonic2ObjectArt::loadSolSheet);
        reg(Sonic2Constants.ART_NEM_HTZ_ZIPLINE_ADDR,
                Sonic2ObjectArtKeys.HTZ_LIFT, Sonic2ObjectArt::loadHTZLiftSheet);
        reg(Sonic2Constants.ART_NEM_SPIKER_ADDR,
                Sonic2ObjectArtKeys.SPIKER, Sonic2ObjectArt::loadSpikerSheet);
        reg(Sonic2Constants.ART_NEM_HTZ_FIREBALL2_ADDR,
                Sonic2ObjectArtKeys.LAVA_BUBBLE, Sonic2ObjectArt::loadLavaBubbleSheet);
        reg(Sonic2Constants.ART_NEM_REXON_ADDR,
                Sonic2ObjectArtKeys.REXON, Sonic2ObjectArt::loadRexonSheet);

        // ===== CPZ zone objects =====
        reg(Sonic2Constants.ART_NEM_SPEED_BOOSTER_ADDR,
                Sonic2ObjectArtKeys.SPEED_BOOSTER, Sonic2ObjectArt::loadSpeedBoosterSheet);
        reg(Sonic2Constants.ART_NEM_CPZ_DROPLET_ADDR,
                Sonic2ObjectArtKeys.BLUE_BALLS, Sonic2ObjectArt::loadBlueBallsSheet);
        reg(Sonic2Constants.ART_NEM_CPZ_STAIRBLOCK_ADDR,
                Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK, Sonic2ObjectArt::loadCpzStairBlockSheet);
        reg(Sonic2Constants.ART_NEM_CPZ_METAL_THINGS_ADDR,
                Sonic2ObjectArtKeys.CPZ_PYLON, Sonic2ObjectArt::loadCpzPylonSheet);
        reg(Sonic2Constants.ART_NEM_PIPE_EXIT_SPRING_ADDR,
                Sonic2ObjectArtKeys.PIPE_EXIT_SPRING, Sonic2ObjectArt::loadPipeExitSpringSheet);
        reg(Sonic2Constants.ART_NEM_CPZ_ANIMATED_BITS_ADDR,
                Sonic2ObjectArtKeys.TIPPING_FLOOR, Sonic2ObjectArt::loadTippingFloorSheet);
        reg(Sonic2Constants.ART_NEM_CONSTRUCTION_STRIPES_ADDR,
                Sonic2ObjectArtKeys.BARRIER, Sonic2ObjectArt::loadBarrierSheet);
        reg(Sonic2Constants.ART_NEM_LEVER_SPRING_ADDR,
                Sonic2ObjectArtKeys.SPRINGBOARD, Sonic2ObjectArt::loadSpringboardSheet);
        reg(Sonic2Constants.ART_NEM_SPINY_ADDR,
                Sonic2ObjectArtKeys.SPINY, Sonic2ObjectArt::loadSpinySheet);
        reg(Sonic2Constants.ART_NEM_GRABBER_ADDR,
                Sonic2ObjectArtKeys.GRABBER, Sonic2ObjectArt::loadGrabberSheet);
        // Obj19 platforms use the shared platform sheet path; CPZ elevator art is not PLC-dispatched here.

        // ===== ARZ zone objects =====
        reg(Sonic2Constants.ART_NEM_CHOPCHOP_ADDR,
                Sonic2ObjectArtKeys.CHOP_CHOP, Sonic2ObjectArt::loadChopChopSheet);
        reg(Sonic2Constants.ART_NEM_WHISP_ADDR,
                Sonic2ObjectArtKeys.WHISP, Sonic2ObjectArt::loadWhispSheet);
        reg(Sonic2Constants.ART_NEM_GROUNDER_ADDR,
                Sonic2ObjectArtKeys.GROUNDER, Sonic2ObjectArt::loadGrounderSheet);
        reg(Sonic2Constants.ART_NEM_ARROW_SHOOTER_ADDR,
                Sonic2ObjectArtKeys.ARROW_SHOOTER, Sonic2ObjectArt::loadArrowShooterSheet);
        reg(Sonic2Constants.ART_NEM_LEAVES_ADDR,
                Sonic2ObjectArtKeys.LEAVES, Sonic2ObjectArt::loadLeavesSheet);
        reg(Sonic2Constants.ART_NEM_BUBBLE_GENERATOR_ADDR,
                Sonic2ObjectArtKeys.BUBBLES, Sonic2ObjectArt::loadBubblesSheet);
        reg(Sonic2Constants.ART_NEM_BUBBLES_ADDR,
                Sonic2ObjectArtKeys.BUBBLES, Sonic2ObjectArt::loadBubblesSheet);

        // ===== CNZ zone objects =====
        reg(Sonic2Constants.ART_NEM_BUMPER_ADDR,
                Sonic2ObjectArtKeys.BUMPER, Sonic2ObjectArt::loadBumperSheet);
        reg(Sonic2Constants.ART_NEM_HEX_BUMPER_ADDR,
                Sonic2ObjectArtKeys.HEX_BUMPER, Sonic2ObjectArt::loadHexBumperSheet);
        reg(Sonic2Constants.ART_NEM_BONUS_BLOCK_ADDR,
                Sonic2ObjectArtKeys.BONUS_BLOCK, Sonic2ObjectArt::loadBonusBlockSheet);
        reg(Sonic2Constants.ART_NEM_FLIPPER_ADDR,
                Sonic2ObjectArtKeys.FLIPPER, Sonic2ObjectArt::loadFlipperSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_SNAKE_ADDR,
                Sonic2ObjectArtKeys.CNZ_RECT_BLOCKS, Sonic2ObjectArt::loadCNZRectBlocksSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_BIG_BLOCK_ADDR,
                Sonic2ObjectArtKeys.CNZ_BIG_BLOCK, Sonic2ObjectArt::loadCNZBigBlockSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_ELEVATOR_ADDR,
                Sonic2ObjectArtKeys.CNZ_ELEVATOR, Sonic2ObjectArt::loadCNZElevatorSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_CAGE_ADDR,
                Sonic2ObjectArtKeys.CNZ_CAGE, Sonic2ObjectArt::loadCNZCageSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_BONUS_SPIKE_ADDR,
                Sonic2ObjectArtKeys.CNZ_BONUS_SPIKE, Sonic2ObjectArt::loadCNZBonusSpikeSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_VERT_PLUNGER_ADDR,
                Sonic2ObjectArtKeys.LAUNCHER_SPRING_VERT, Sonic2ObjectArt::loadLauncherSpringVertSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_DIAG_PLUNGER_ADDR,
                Sonic2ObjectArtKeys.LAUNCHER_SPRING_DIAG, Sonic2ObjectArt::loadLauncherSpringDiagSheet);
        reg(Sonic2Constants.ART_NEM_CRAWL_ADDR,
                Sonic2ObjectArtKeys.CRAWL, Sonic2ObjectArt::loadCrawlSheet);

        // ===== OOZ zone objects =====
        reg(Sonic2Constants.ART_NEM_LAUNCH_BALL_ADDR,
                Sonic2ObjectArtKeys.LAUNCH_BALL, Sonic2ObjectArt::loadLaunchBallSheet);
        reg(Sonic2Constants.ART_NEM_OOZ_FAN_ADDR,
                Sonic2ObjectArtKeys.OOZ_FAN_HORIZ, Sonic2ObjectArt::loadOOZFanHorizSheet);
        reg(Sonic2Constants.ART_NEM_BURNER_LID_ADDR,
                Sonic2ObjectArtKeys.OOZ_BURNER_LID, Sonic2ObjectArt::loadOOZBurnerLidSheet);
        reg(Sonic2Constants.ART_NEM_OOZ_BURN_ADDR,
                Sonic2ObjectArtKeys.OOZ_BURN_FLAME, Sonic2ObjectArt::loadOOZBurnFlameSheet);
        reg(Sonic2Constants.ART_NEM_STRIPED_BLOCKS_VERT_ADDR,
                Sonic2ObjectArtKeys.OOZ_LAUNCHER_VERT, Sonic2ObjectArt::loadOOZLauncherVertSheet);
        reg(Sonic2Constants.ART_NEM_STRIPED_BLOCKS_HORIZ_ADDR,
                Sonic2ObjectArtKeys.OOZ_LAUNCHER_HORIZ, Sonic2ObjectArt::loadOOZLauncherHorizSheet);
        reg(Sonic2Constants.ART_NEM_OOZ_COLLAPSING_PLATFORM_ADDR,
                Sonic2ObjectArtKeys.OOZ_COLLAPSING_PLATFORM, Sonic2ObjectArt::loadOOZCollapsingPlatformSheet);
        reg(Sonic2Constants.ART_NEM_SPIKY_THING_ADDR,
                Sonic2ObjectArtKeys.OOZ_SLIDING_SPIKE, Sonic2ObjectArt::loadOOZSlidingSpikeSheet);
        reg(Sonic2Constants.ART_NEM_PUSH_SPRING_ADDR,
                Sonic2ObjectArtKeys.OOZ_PRESSURE_SPRING, Sonic2ObjectArt::loadOOZPressureSpringSheet);
        reg(Sonic2Constants.ART_NEM_OCTUS_ADDR,
                Sonic2ObjectArtKeys.OCTUS, Sonic2ObjectArt::loadOctusSheet);
        reg(Sonic2Constants.ART_NEM_AQUIS_ADDR,
                Sonic2ObjectArtKeys.AQUIS, Sonic2ObjectArt::loadAquisSheet);

        // ===== MCZ zone objects =====
        reg(Sonic2Constants.ART_NEM_VINE_PULLEY_ADDR,
                Sonic2ObjectArtKeys.VINE_PULLEY, Sonic2ObjectArt::loadVinePulleySheet);
        reg(Sonic2Constants.ART_NEM_CRATE_ADDR,
                Sonic2ObjectArtKeys.MCZ_CRATE, Sonic2ObjectArt::loadMCZCrateSheet);
        reg(Sonic2Constants.ART_NEM_MCZ_GATE_LOG_ADDR,
                Sonic2ObjectArtKeys.MCZ_DRAWBRIDGE, Sonic2ObjectArt::loadMCZDrawbridgeSheet);
        reg(Sonic2Constants.ART_NEM_VINE_SWITCH_ADDR,
                Sonic2ObjectArtKeys.VINE_SWITCH, Sonic2ObjectArt::loadVineSwitchSheet);
        reg(Sonic2Constants.ART_NEM_MCZ_COLLAPSING_PLATFORM_ADDR,
                Sonic2ObjectArtKeys.MCZ_COLLAPSING_PLATFORM, Sonic2ObjectArt::loadMCZCollapsingPlatformSheet);
        reg(Sonic2Constants.ART_NEM_CRAWLTON_ADDR,
                Sonic2ObjectArtKeys.CRAWLTON, Sonic2ObjectArt::loadCrawltonSheet);
        reg(Sonic2Constants.ART_NEM_FLASHER_ADDR,
                Sonic2ObjectArtKeys.FLASHER, Sonic2ObjectArt::loadFlasherSheet);
        reg(Sonic2Constants.ART_NEM_BUTTON_ADDR,
                Sonic2ObjectArtKeys.BUTTON, Sonic2ObjectArt::loadButtonSheet);

        // ===== MTZ zone objects =====
        reg(Sonic2Constants.ART_NEM_MTZ_COG_ADDR,
                Sonic2ObjectArtKeys.MTZ_COG, Sonic2ObjectArt::loadMTZCogSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_ASST_BLOCKS_ADDR,
                Sonic2ObjectArtKeys.MTZ_NUT, Sonic2ObjectArt::loadMTZNutSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_SPIKE_ADDR,
                Sonic2ObjectArtKeys.MTZ_FLOOR_SPIKE, Sonic2ObjectArt::loadMTZFloorSpikeSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_SPIKE_BLOCK_ADDR,
                Sonic2ObjectArtKeys.MTZ_SPIKE_BLOCK, Sonic2ObjectArt::loadMTZSpikeBlockSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_STEAM_ADDR,
                Sonic2ObjectArtKeys.MTZ_STEAM, Sonic2ObjectArt::loadMTZSteamSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_LAVA_BUBBLE_ADDR,
                Sonic2ObjectArtKeys.MTZ_LAVA_BUBBLE, Sonic2ObjectArt::loadMTZLavaBubbleSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_SPIN_TUBE_FLASH_ADDR,
                Sonic2ObjectArtKeys.MTZ_SPIN_TUBE_FLASH, Sonic2ObjectArt::loadMTZSpinTubeFlashSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_WHEEL_INDENT_ADDR,
                Sonic2ObjectArtKeys.MTZ_WHEEL_INDENT, Sonic2ObjectArt::loadMTZWheelIndentSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_WHEEL_ADDR,
                Sonic2ObjectArtKeys.MTZ_WHEEL, Sonic2ObjectArt::loadMTZWheelSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_LAVA_CUP_ADDR,
                Sonic2ObjectArtKeys.MTZ_LAVA_CUP, Sonic2ObjectArt::loadMTZLavaCupSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_SUPERNOVA_ADDR,
                Sonic2ObjectArtKeys.ASTERON, Sonic2ObjectArt::loadAsteronSheet);
        reg(Sonic2Constants.ART_NEM_MTZ_MANTIS_ADDR,
                Sonic2ObjectArtKeys.SLICER, Sonic2ObjectArt::loadSlicerSheet);
        reg(Sonic2Constants.ART_NEM_SHELLCRACKER_ADDR,
                Sonic2ObjectArtKeys.SHELLCRACKER, Sonic2ObjectArt::loadShellcrackerSheet);

        // ===== WFZ zone objects =====
        reg(Sonic2Constants.ART_NEM_WFZ_HOOK_ADDR,
                Sonic2ObjectArtKeys.WFZ_HOOK, Sonic2ObjectArt::loadWFZHookSheet);
        reg(Sonic2Constants.ART_NEM_TORNADO_ADDR,
                Sonic2ObjectArtKeys.TORNADO, Sonic2ObjectArt::loadTornadoSheet);
        reg(Sonic2Constants.ART_NEM_TORNADO_THRUSTER_ADDR,
                Sonic2ObjectArtKeys.TORNADO_THRUSTER, Sonic2ObjectArt::loadTornadoThrusterSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_THRUST_ADDR,
                Sonic2ObjectArtKeys.WFZ_THRUST, Sonic2ObjectArt::loadWfzThrustSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_VRTCL_PRPLLR_ADDR,
                Sonic2ObjectArtKeys.WFZ_VPROPELLER, Sonic2ObjectArt::loadVPropellerSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_HRZNTL_PRPLLR_ADDR,
                Sonic2ObjectArtKeys.WFZ_HPROPELLER, Sonic2ObjectArt::loadHPropellerSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_WALL_TURRET_ADDR,
                Sonic2ObjectArtKeys.WFZ_WALL_TURRET, Sonic2ObjectArt::loadWallTurretSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_GUN_PLATFORM_ADDR,
                Sonic2ObjectArtKeys.WFZ_GUN_PLATFORM, Sonic2ObjectArt::loadWfzGunPlatformSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_HRZNTL_LAZER_ADDR,
                Sonic2ObjectArtKeys.WFZ_LASER, Sonic2ObjectArt::loadWFZLaserSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_VRTCL_LAZER_ADDR,
                Sonic2ObjectArtKeys.WFZ_VERTICAL_LASER, Sonic2ObjectArt::loadWFZVerticalLaserSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_LAUNCH_CATAPULT_ADDR,
                Sonic2ObjectArtKeys.WFZ_LAUNCH_CATAPULT, Sonic2ObjectArt::loadWfzLaunchCatapultSheet);
        reg(Sonic2Constants.ART_NEM_BREAK_PANELS_ADDR,
                Sonic2ObjectArtKeys.WFZ_BREAK_PANELS, Sonic2ObjectArt::loadWfzBreakPanelsSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_SWITCH_ADDR,
                Sonic2ObjectArtKeys.WFZ_RIVET, Sonic2ObjectArt::loadWfzRivetSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_BELT_PLATFORM_ADDR,
                Sonic2ObjectArtKeys.WFZ_BELT_PLATFORM, Sonic2ObjectArt::loadWFZBeltPlatformSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_UNUSED_BADNIK_ADDR,
                Sonic2ObjectArtKeys.WFZ_STICK, Sonic2ObjectArt::loadWfzStickSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_TILT_PLATFORMS_ADDR,
                Sonic2ObjectArtKeys.WFZ_TILT_PLATFORM, Sonic2ObjectArt::loadWFZTiltPlatformSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_CONVEYOR_BELT_WHEEL_ADDR,
                Sonic2ObjectArtKeys.WFZ_CONVEYOR_BELT_WHEEL, Sonic2ObjectArt::loadWFZConveyorBeltWheelSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_SCRATCH_ADDR,
                Sonic2ObjectArtKeys.CLUCKER, Sonic2ObjectArt::loadCluckerSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_PLATFORM_ADDR,
                Sonic2ObjectArtKeys.WFZ_PLATFORM, Sonic2ObjectArt::loadWfzFloatingPlatformSheet);
        reg(Sonic2Constants.ART_NEM_WFZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.WFZ_BOSS, Sonic2ObjectArt::loadWFZBossSheet);
        reg(Sonic2Constants.ART_NEM_ROBOTNIK_RUNNING_ADDR,
                Sonic2ObjectArtKeys.WFZ_ROBOTNIK, Sonic2ObjectArt::loadWFZRobotnikSheet);
        reg(Sonic2Constants.ART_NEM_ROBOTNIK_UPPER_ADDR,
                Sonic2ObjectArtKeys.WFZ_ROBOTNIK, Sonic2ObjectArt::loadWFZRobotnikSheet);
        reg(Sonic2Constants.ART_NEM_ROBOTNIK_LOWER_ADDR,
                Sonic2ObjectArtKeys.WFZ_ROBOTNIK, Sonic2ObjectArt::loadWFZRobotnikSheet);

        // ===== SCZ zone objects =====
        reg(Sonic2Constants.ART_NEM_NEBULA_ADDR,
                Sonic2ObjectArtKeys.NEBULA, Sonic2ObjectArt::loadNebulaSheet);
        reg(Sonic2Constants.ART_NEM_TURTLOID_ADDR,
                Sonic2ObjectArtKeys.TURTLOID, Sonic2ObjectArt::loadTurtloidSheet);
        reg(Sonic2Constants.ART_NEM_BALKIRY_ADDR,
                Sonic2ObjectArtKeys.BALKIRY, Sonic2ObjectArt::loadBalkirySheet);
        reg(Sonic2Constants.ART_NEM_CLOUDS_ADDR,
                Sonic2ObjectArtKeys.CLOUDS, Sonic2ObjectArt::loadCloudSheet);

        // ===== Boss PLCs =====
        reg(Sonic2Constants.ART_NEM_EGGPOD_ADDR,
                Sonic2ObjectArtKeys.CPZ_BOSS_EGGPOD, Sonic2ObjectArt::loadCPZBossEggpodSheet);
        reg(Sonic2Constants.ART_NEM_EHZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.EHZ_BOSS, Sonic2ObjectArt::loadEHZBossSheet);
        reg(Sonic2Constants.ART_NEM_HTZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.HTZ_BOSS, Sonic2ObjectArt::loadHTZBossSheet);
        reg(Sonic2Constants.ART_NEM_CPZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.CPZ_BOSS_PARTS, Sonic2ObjectArt::loadCPZBossPartsSheet);
        reg(Sonic2Constants.ART_NEM_EGGPOD_JETS_ADDR,
                Sonic2ObjectArtKeys.CPZ_BOSS_JETS, Sonic2ObjectArt::loadCPZBossJetsSheet);
        reg(Sonic2Constants.ART_NEM_BOSS_SMOKE_ADDR,
                Sonic2ObjectArtKeys.CPZ_BOSS_SMOKE, Sonic2ObjectArt::loadCPZBossSmokeSheet);
        reg(Sonic2Constants.ART_NEM_ARZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.ARZ_BOSS_MAIN, Sonic2ObjectArt::loadARZBossMainSheet);
        reg(Sonic2Constants.ART_NEM_CNZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.CNZ_BOSS, Sonic2ObjectArt::loadCNZBossSheet);
        reg(Sonic2Constants.ART_NEM_MCZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.MCZ_BOSS, Sonic2ObjectArt::loadMCZBossSheet);
        reg(Sonic2Constants.ART_NEM_OOZ_BOSS_ADDR,
                Sonic2ObjectArtKeys.OOZ_BOSS, Sonic2ObjectArt::loadOOZBossSheet);
    }

    /**
     * Looks up the art registration for a PLC entry's ROM address.
     *
     * @param romAddr the Nemesis art ROM address from a PLC entry
     * @return the registration, or null if this address is not in the registry
     */
    public static ArtRegistration lookup(int romAddr) {
        return REGISTRY.get(romAddr);
    }

    /**
     * Returns the number of registered art addresses.
     */
    public static int size() {
        return REGISTRY.size();
    }

    private static void reg(int romAddr, String key, SheetBuilder builder) {
        ObjectArtRegistration metadata = ObjectArtRegistration.sheet(key)
                .withRomSource(
                        romAddr,
                        ObjectArtRegistration.UNSPECIFIED,
                        ObjectArtRegistration.UNSPECIFIED,
                        ObjectArtRegistration.UNSPECIFIED)
                .requiringPlc();
        ArtRegistration existing = REGISTRY.put(romAddr, new ArtRegistration(metadata, builder));
        if (existing != null) {
            LOG.fine(String.format("Registry override at 0x%06X: %s -> %s", romAddr, existing.key(), key));
        }
    }
}
