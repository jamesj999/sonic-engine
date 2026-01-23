package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.LevelEventProvider;
import uk.co.jamesj999.sonic.level.ParallaxManager;
import uk.co.jamesj999.sonic.level.WaterSystem;

/**
 * Sonic 2 implementation of dynamic level events.
 * ROM equivalent: RunDynamicLevelEvents (s2.asm:20297-20340)
 *
 * This system allows levels to dynamically adjust camera boundaries
 * based on player position, triggering boss arenas, vertical section
 * transitions, and other gameplay sequences.
 *
 * Each zone has its own event routine dispatched via the zone index.
 */
public class LevelEventManager implements LevelEventProvider {
    private static LevelEventManager instance;

    private final Camera camera;

    // Current zone and act
    private int currentZone = -1;
    private int currentAct = -1;

    // Per-zone event routine state (ROM: Dynamic_Resize_Routine)
    // Incremented by 2 as each event completes, similar to ROM behavior
    private int eventRoutine = 0;

    // Zone constants (matches ROM zone ordering)
    public static final int ZONE_EHZ = 0;
    public static final int ZONE_UNUSED_1 = 1;
    public static final int ZONE_ARZ = 2;
    public static final int ZONE_CNZ = 3;
    public static final int ZONE_HTZ = 4;
    public static final int ZONE_MCZ = 5;
    public static final int ZONE_OOZ = 6;
    public static final int ZONE_MTZ = 7;
    public static final int ZONE_UNUSED_2 = 8;
    public static final int ZONE_SCZ = 9;
    public static final int ZONE_WFZ = 10;
    public static final int ZONE_DEZ = 11;
    // CPZ uses zone index 1 in level event ordering (ROM zone ID 0x0D)
    public static final int ZONE_CPZ = 1;

    private LevelEventManager() {
        this.camera = Camera.getInstance();
    }

    /**
     * Called when entering a new level to reset event state.
     */
    @Override
    public void initLevel(int zone, int act) {
        this.currentZone = zone;
        this.currentAct = act;
        this.eventRoutine = 0;
        this.bossSpawnDelay = 0;
    }

    /**
     * Called every frame to run dynamic level events.
     * Must be called BEFORE camera.updateBoundaryEasing().
     */
    @Override
    public void update() {
        if (currentZone < 0) {
            return;
        }

        // Dispatch to zone-specific event handler
        switch (currentZone) {
            case ZONE_EHZ -> updateEHZ();
            case ZONE_CPZ -> updateCPZ();
            case ZONE_HTZ -> updateHTZ();
            case ZONE_MCZ -> updateMCZ();
            case ZONE_ARZ -> updateARZ();
            case ZONE_CNZ -> updateCNZ();
            case ZONE_OOZ -> updateOOZ();
            case ZONE_MTZ -> updateMTZ();
            case ZONE_WFZ -> updateWFZ();
            case ZONE_DEZ -> updateDEZ();
            case ZONE_SCZ -> updateSCZ();
            default -> {
                // No events for this zone
            }
        }
    }

    // =========================================================================
    // Zone Event Handlers
    // ROM: LevEvents_xxx routines starting at s2.asm:20369
    // =========================================================================

    // Boss spawn delay counter (ROM: Boss_spawn_delay)
    private int bossSpawnDelay = 0;

    /**
     * Emerald Hill Zone events.
     * ROM: LevEvents_EHZ (s2.asm:20357-20503)
     *
     * Act 1: No dynamic events
     * Act 2: Boss arena boundary changes when reaching end of level
     */
    private void updateEHZ() {
        if (currentAct == 0) {
            // Act 1: No dynamic events (ROM: LevEvents_EHZ1 just returns)
            return;
        }

        // Act 2: Boss arena setup
        // ROM: LevEvents_EHZ2 (s2.asm:20363-20503)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0 (s2.asm:20377-20388): Wait for camera X >= $2780
                if (camera.getX() >= 0x2780) {
                    // ROM: Set minX to current camera X (immediate, prevents backtracking)
                    camera.setMinX(camera.getX());
                    // ROM: Set maxY TARGET to $390 (will ease down to boss arena height)
                    camera.setMaxYTarget((short) 0x390);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                // Routine 1 (s2.asm:20396-20411): Wait for camera X >= $28F0
                if (camera.getX() >= 0x28F0) {
                    // ROM: Lock X boundaries immediately for boss arena (not eased)
                    camera.setMinX((short) 0x28F0);
                    camera.setMaxX((short) 0x2940);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    // TODO: Trigger music fade, load boss PLC
                }
            }
            case 4 -> {
                // Routine 2 (s2.asm:20414-20435): Lock floor and spawn boss
                // ROM: Set minY when camera Y reaches $388 (immediate, not eased)
                if (camera.getY() >= 0x388) {
                    camera.setMinY((short) 0x388);
                }
                // ROM: Increment delay every frame, spawn boss at $5A (90) frames
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    // TODO: Spawn EHZ boss object
                    eventRoutine += 2;
                    // TODO: Start boss music
                }
            }
            case 6 -> {
                // Routine 3 (s2.asm:20438+): Wait for boss defeat
                // TODO: Check boss defeated flag and handle post-boss events
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Hill Top Zone events.
     * ROM: LevEvents_HTZ (s2.asm:20543-20670)
     *
     * HTZ has complex earthquake/lava events with screen shake zones.
     * The screen shake is triggered when the player enters certain areas
     * and cleared when they exit those areas.
     *
     * Act 1 shake triggers:
     * - Routine 1: Camera_X >= 0x1800 AND Camera_Y >= 0x400
     * - Routine 3: Camera_X < 0x1F00
     *
     * Act 2 shake triggers:
     * - Routine 1: Camera_X >= 0x14C0
     * - Routine 3: Camera_X < 0x1B00
     * - Routine 5: Camera_X < 0x1B00
     */
    private void updateHTZ() {
        if (currentAct == 0) {
            updateHTZAct1();
        } else {
            updateHTZAct2();
        }
    }

    // HTZ Act 1 screen shake trigger coordinates
    private static final int HTZ1_SHAKE_TRIGGER_X = 0x1800;
    private static final int HTZ1_SHAKE_TRIGGER_Y = 0x400;
    private static final int HTZ1_SHAKE_EXIT_X = 0x1F00;

    // HTZ Act 2 screen shake trigger coordinates
    private static final int HTZ2_SHAKE_TRIGGER_X = 0x14C0;
    private static final int HTZ2_SHAKE_EXIT_X = 0x1B00;

    /**
     * HTZ Act 1 events.
     * ROM: LevEvents_HTZ1 (s2.asm:20674-20838)
     */
    private void updateHTZAct1() {
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0: Wait for shake trigger
                // ROM: LevEvents_HTZ_Routine1 checks Camera_Y >= $400 AND Camera_X >= $1800
                if (camera.getY() >= HTZ1_SHAKE_TRIGGER_Y &&
                    camera.getX() >= HTZ1_SHAKE_TRIGGER_X) {
                    // Enable screen shake
                    ParallaxManager.getInstance().setHtzScreenShake(true);
                    eventRoutine += 2;
                } else {
                    // Routine 1 Part 2: If shake was active but we're out of range, clear it
                    ParallaxManager.getInstance().setHtzScreenShake(false);
                }
            }
            case 2 -> {
                // Routine 1: Shaking area - check for exit at X >= $1E00 to clear shake flag
                if (camera.getX() >= 0x1E00) {
                    // Exit shake area
                    ParallaxManager.getInstance().setHtzScreenShake(false);
                    eventRoutine += 2;
                }
            }
            case 4 -> {
                // Routine 2: Post-shake area
                // Check for re-entry into shake zone (Routine 3)
                if (camera.getX() < HTZ1_SHAKE_EXIT_X) {
                    ParallaxManager.getInstance().setHtzScreenShake(true);
                    eventRoutine -= 2;  // Go back to routine 1
                }
            }
            default -> {
                // Further routines handle boss area etc.
            }
        }
    }

    /**
     * HTZ Act 2 events.
     * ROM: LevEvents_HTZ2 (s2.asm:20920-21193)
     */
    private void updateHTZAct2() {
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0: Wait for shake trigger
                if (camera.getX() >= HTZ2_SHAKE_TRIGGER_X) {
                    ParallaxManager.getInstance().setHtzScreenShake(true);
                    eventRoutine += 2;
                } else {
                    ParallaxManager.getInstance().setHtzScreenShake(false);
                }
            }
            case 2 -> {
                // Routine 1: Shaking area
                if (camera.getX() >= 0x1A00) {
                    ParallaxManager.getInstance().setHtzScreenShake(false);
                    eventRoutine += 2;
                }
            }
            case 4 -> {
                // Routine 2: Post-shake
                if (camera.getX() < HTZ2_SHAKE_EXIT_X) {
                    ParallaxManager.getInstance().setHtzScreenShake(true);
                    eventRoutine -= 2;
                }
            }
            default -> {
                // Further routines handle additional shake zones and boss
            }
        }
    }

    /**
     * Chemical Plant Zone events.
     * ROM: LevEvents_CPZ (s2.asm:20504-20542)
     *
     * Act 1: No dynamic events
     * Act 2: Water (Mega Mack) rises when player reaches trigger X coordinate
     *
     * CPZ2 water rising: When camera X >= trigger point, set water target level
     * and WaterSystem will gradually raise it each frame until target reached.
     */
    private void updateCPZ() {
        if (currentAct != 1) {
            // Only Act 2 has water rise events
            return;
        }

        // CPZ Act 2: Rising Mega Mack
        // ROM zone ID for CPZ is 0x0D
        final int ZONE_ID_CPZ_ROM = 0x0D;

        // X coordinate trigger for water rise (from original game)
        // This is where the famous "rising water" section begins
        final int WATER_RISE_TRIGGER_X = 0x1E80;

        // Target water level (lower Y = higher water on screen)
        // Initial level is 0x710 (1808), target is approximately 0x508 (1288)
        final int WATER_TARGET_Y = 0x508;

        switch (eventRoutine) {
            case 0 -> {
                // Routine 0: Wait for player X to reach trigger point
                // Note: Original game checks player position, not camera position
                var player = camera.getFocusedSprite();
                if (player != null && player.getX() >= WATER_RISE_TRIGGER_X) {
                    // Trigger water to start rising
                    WaterSystem.getInstance().setWaterLevelTarget(
                            ZONE_ID_CPZ_ROM, currentAct, WATER_TARGET_Y);
                    eventRoutine += 2;
                }
            }
            default -> {
                // Water is rising or has reached target
                // WaterSystem.update() handles the gradual rise
            }
        }
    }

    /**
     * Mystic Cave Zone events.
     * ROM: LevEvents_MCZ (s2.asm:20777-20851)
     */
    private void updateMCZ() {
        // MCZ has vertical section changes
        // Implement as needed
    }

    /**
     * Aquatic Ruin Zone events.
     * ROM: LevEvents_ARZ (s2.asm:20852-20937)
     */
    private void updateARZ() {
        // ARZ has water level and boss events
        // Implement as needed
    }

    /**
     * Casino Night Zone events.
     * ROM: LevEvents_CNZ (s2.asm:20671-20776)
     */
    private void updateCNZ() {
        // CNZ has vertical section events
        // Implement as needed
    }

    /**
     * Oil Ocean Zone events.
     * ROM: LevEvents_OOZ (s2.asm:20938-21035)
     */
    private void updateOOZ() {
        // OOZ has oil level events
        // Implement as needed
    }

    /**
     * Metropolis Zone events.
     * ROM: LevEvents_MTZ (s2.asm:21036-21173)
     */
    private void updateMTZ() {
        // MTZ has vertical wrapping section events
        // Implement as needed
    }

    /**
     * Wing Fortress Zone events.
     * ROM: LevEvents_WFZ (s2.asm:21174-21310)
     */
    private void updateWFZ() {
        // WFZ has platform ride and boss events
        // Implement as needed
    }

    /**
     * Death Egg Zone events.
     * ROM: LevEvents_DEZ (s2.asm:21311-21395)
     */
    private void updateDEZ() {
        // DEZ is a single boss arena
        // Implement as needed
    }

    /**
     * Sky Chase Zone events.
     * ROM: LevEvents_SCZ (s2.asm:21396-21485)
     */
    private void updateSCZ() {
        // SCZ is auto-scrolling
        // Implement as needed
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public int getEventRoutine() {
        return eventRoutine;
    }

    public void setEventRoutine(int routine) {
        this.eventRoutine = routine;
    }

    public static synchronized LevelEventManager getInstance() {
        if (instance == null) {
            instance = new LevelEventManager();
        }
        return instance;
    }
}
