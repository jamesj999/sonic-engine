package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.LevelEventProvider;

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
     */
    private void updateHTZ() {
        // HTZ has complex earthquake/lava events
        // Implement as needed
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
