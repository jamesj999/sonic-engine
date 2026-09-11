package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;

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
public class Sonic2HTZEvents extends Sonic2ZoneEvents {

    // =========================================================================
    // HTZ Earthquake State (ROM: Camera_BG_Y_offset, HTZ_Terrain_Direction, HTZ_Terrain_Delay)
    // =========================================================================

    /**
     * Camera_BG_Y_offset for HTZ earthquake lava positioning.
     * ROM: $FFFFF72E - Controls vertical offset of rising lava platforms.
     * Range: 224 (sinking limit) to 320 (risen limit).
     * When screen shake starts, initialized to 320.
     */
    private int cameraBgYOffset;

    /**
     * HTZ terrain direction flag.
     * ROM: $FFFFF7C7 - When false, lava is rising (offset goes toward risen limit).
     * When true, lava is sinking (offset goes toward sunken limit).
     */
    private boolean htzTerrainSinking;

    /**
     * HTZ terrain delay counter.
     * ROM: $FFFFF7C8 (word) - Counts down from $78 (120 frames) before direction toggles.
     */
    private int htzTerrainDelay;

    /**
     * Master HTZ earthquake-active flag (ROM: Screen_Shaking_Flag_HTZ at $FFFFF7C3).
     *
     * <p>Stays set for the entire earthquake sequence (including delay periods at
     * the oscillation limits when the general {@code Screen_Shaking_Flag} is
     * cleared). Owned exclusively by {@link Sonic2HTZEvents}; consumers read it
     * via {@link com.openggf.game.sonic2.runtime.HtzRuntimeState#earthquakeActive()}.
     */
    private boolean earthquakeActive;

    /**
     * Active HTZ oscillation limits for the currently-entered earthquake mode.
     * These vary by act and by top/bottom route in Act 2.
     */
    private int htzCurrentRisenLimit;
    private int htzCurrentSunkenLimit;
    private int htzCurrentBgXOffset;

    // =========================================================================
    // HTZ Act 1 Constants
    // =========================================================================

    /** Initial Camera_BG_Y_offset when earthquake starts (ROM: 320 = $140) */
    static final int HTZ1_LAVA_OFFSET_RISEN = 320;
    /** Lower limit of Camera_BG_Y_offset when sinking (ROM: 224 = $E0) */
    static final int HTZ1_LAVA_OFFSET_SUNKEN = 224;
    private static final int HTZ1_SHAKE_TRIGGER_X = 0x1800;
    private static final int HTZ1_SHAKE_TRIGGER_Y = 0x400;
    private static final int HTZ1_SHAKE_EXIT_X = 0x1F00;
    /** Act 1: oscillation begins at X >= $1978 */
    private static final int HTZ1_OSCILLATE_START_X = 0x1978;
    /** Act 1: general screen shake disabled at X >= $1E00 */
    private static final int HTZ1_SHAKE_DISABLE_X = 0x1E00;

    // =========================================================================
    // HTZ Act 2 Constants
    // =========================================================================

    /** Act 2 top route risen limit (ROM: 0x2C0 = 704) */
    static final int HTZ2_LAVA_OFFSET_RISEN_TOP = 0x2C0;
    /** Act 2 bottom route risen limit (ROM: 0x300 = 768) */
    static final int HTZ2_LAVA_OFFSET_RISEN_BOTTOM = 0x300;
    /** Act 2 sunken limit (ROM: 0) */
    static final int HTZ2_LAVA_OFFSET_SUNKEN = 0;
    /** Camera_BG_X_offset for top route / Act 1 (ROM: 0). */
    static final int HTZ_BG_X_OFFSET_TOP = 0;
    /** Camera_BG_X_offset for Act 2 bottom route (ROM: -$680). */
    static final int HTZ_BG_X_OFFSET_BOTTOM = -0x680;
    private static final int HTZ2_SHAKE_TRIGGER_X = 0x14C0;
    private static final int HTZ2_SHAKE_EXIT_X = 0x1B00;
    /** Y threshold for HTZ Act 2 zone detection (ROM: $380) */
    private static final int HTZ2_Y_ZONE_THRESHOLD = 0x380;
    /** Act 2 top: oscillation begins at X >= $1678 */
    private static final int HTZ2_TOP_OSCILLATE_START_X = 0x1678;
    /** Act 2 top: general screen shake disabled at X >= $1A00 */
    private static final int HTZ2_TOP_SHAKE_DISABLE_X = 0x1A00;
    /** Act 2 bottom: oscillation begins at X >= $15F0 */
    private static final int HTZ2_BOTTOM_OSCILLATE_START_X = 0x15F0;
    /** Act 2 bottom: general screen shake disabled at X >= $1AC0 */
    private static final int HTZ2_BOTTOM_SHAKE_DISABLE_X = 0x1AC0;
    /** Delay frames before direction toggle (ROM: $78 = 120 frames) */
    private static final int HTZ_TERRAIN_DELAY_INIT = 0x78;

    // =========================================================================
    // HTZ Act 2 Boss Arena Constants (from disassembly LevEvents_HTZ2)
    // =========================================================================

    /** Cutoff trigger X to start boss preparation (ROM: $2B00) */
    private static final int HTZ2_BOSS_CUTOFF_X = 0x2B00;
    /** Boss prep trigger X (ROM: $2C50) */
    private static final int HTZ2_BOSS_ARENA_TRIGGER_X = 0x2C50;
    /** Boss arena camera lock trigger X (ROM: cmpi.w #$2EDF) */
    private static final int HTZ2_BOSS_ARENA_LOCK_TRIGGER_X = 0x2EDF;
    /** Boss arena left camera lock X (ROM: $2EE0) */
    private static final int HTZ2_BOSS_ARENA_LEFT = 0x2EE0;
    /** Boss arena right camera lock X (ROM: $2F5E) */
    private static final int HTZ2_BOSS_ARENA_RIGHT = 0x2F5E;
    /** Boss arena max Y target (ROM: $480) */
    private static final int HTZ2_BOSS_ARENA_MAX_Y = 0x480;
    /** Boss arena floor Y lock (ROM: $478) */
    private static final int HTZ2_BOSS_FLOOR_Y = 0x478;

    private Sonic2HTZBossInstance htzBoss;

    public Sonic2HTZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        htzBoss = null;
        cameraBgYOffset = 0;
        htzTerrainSinking = false;
        htzTerrainDelay = 0;
        earthquakeActive = false;
        htzCurrentRisenLimit = (act == 0) ? HTZ1_LAVA_OFFSET_RISEN : HTZ2_LAVA_OFFSET_RISEN_TOP;
        htzCurrentSunkenLimit = (act == 0) ? HTZ1_LAVA_OFFSET_SUNKEN : HTZ2_LAVA_OFFSET_SUNKEN;
        htzCurrentBgXOffset = HTZ_BG_X_OFFSET_TOP;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act == 0) {
            updateHTZAct1(frameCounter);
        } else {
            updateHTZAct2(frameCounter);
        }
    }

    // =========================================================================
    // Public Getters (delegated by manager)
    // =========================================================================

    /**
     * Gets the current Camera_BG_Y_offset for HTZ rising lava.
     * Used by RisingLavaObjectInstance to calculate Y position.
     *
     * @return current BG Y offset (0 when not in earthquake, 224-320 during earthquake)
     */
    public int getCameraBgYOffset() {
        return cameraBgYOffset;
    }

    public boolean isEarthquakeActive() {
        return earthquakeActive;
    }

    /**
     * Sets the HTZ earthquake-active flag.
     * Mirrors ROM writes to {@code Screen_Shaking_Flag_HTZ} ($FFFFF7C3) and
     * the general {@code Screen_Shaking_Flag} ($FFFFF7C2). Also dirties the
     * BG tilemap so {@code LevelTilemapManager} rebuilds it with full-width
     * BG data while the earthquake overlay is active.
     */
    public void setEarthquakeActive(boolean active) {
        boolean wasActive = earthquakeActive;
        boolean wasGeneralShake = gameState().isScreenShakeActive();
        earthquakeActive = active;
        gameState().setScreenShakeActive(active);
        if (wasActive != active || wasGeneralShake != active) {
            LevelManager lm = levelManager();
            if (lm != null) {
                lm.invalidateAllTilemaps();
            }
        }
    }

    /**
     * Gets the current Camera_BG_X_offset used by HTZ earthquake BG scrolling.
     * Top route / Act 1 use 0; Act 2 bottom route uses -$680.
     */
    public int getHtzBgXOffset() {
        return htzCurrentBgXOffset;
    }

    /**
     * Returns the relative BG vertical shift for HTZ earthquake.
     * 0 = normal/risen position, positive = BG scrolled up (more lava visible).
     * This is used by SwScrlHtz to offset vscrollFactorBG without modifying bgCamera.bgYPos.
     */
    // ---- Rewind accessors ----
    public int getCameraBgYOffsetRaw()    { return cameraBgYOffset; }
    public void setCameraBgYOffset(int v) { cameraBgYOffset = v; }
    public boolean isHtzTerrainSinking()  { return htzTerrainSinking; }
    public void setHtzTerrainSinking(boolean v) { htzTerrainSinking = v; }
    public int getHtzTerrainDelay()       { return htzTerrainDelay; }
    public void setHtzTerrainDelay(int v) { htzTerrainDelay = v; }
    public boolean isEarthquakeActiveRaw(){ return earthquakeActive; }
    public void setEarthquakeActiveRaw(boolean v) { earthquakeActive = v; }
    public int getHtzCurrentRisenLimit()  { return htzCurrentRisenLimit; }
    public void setHtzCurrentRisenLimit(int v) { htzCurrentRisenLimit = v; }
    public int getHtzCurrentSunkenLimit() { return htzCurrentSunkenLimit; }
    public void setHtzCurrentSunkenLimit(int v){ htzCurrentSunkenLimit = v; }
    public int getHtzCurrentBgXOffset()   { return htzCurrentBgXOffset; }
    public void setHtzCurrentBgXOffset(int v) { htzCurrentBgXOffset = v; }
    // ---- end rewind accessors ----

    public int getHtzBgVerticalShift() {
        if (!earthquakeActive) {
            return 0;
        }
        return htzCurrentRisenLimit - cameraBgYOffset;
    }

    // =========================================================================
    // HTZ Act 1 Events
    // ROM: LevEvents_HTZ1 (s2.asm:20674-20838)
    // =========================================================================

    private void updateHTZAct1(int frameCounter) {
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0: Wait for shake trigger
                // ROM: LevEvents_HTZ_Routine1 checks Camera_Y >= $400 AND Camera_X >= $1800
                int cameraX0 = camera().getX();
                if (camera().getY() >= HTZ1_SHAKE_TRIGGER_Y &&
                    cameraX0 >= HTZ1_SHAKE_TRIGGER_X &&
                    cameraX0 < HTZ1_SHAKE_EXIT_X) {
                    // Enable screen shake and initialize earthquake
                    setEarthquakeActive(true);
                    initHtzEarthquake(HTZ1_LAVA_OFFSET_RISEN, HTZ1_LAVA_OFFSET_SUNKEN, HTZ_BG_X_OFFSET_TOP);
                    eventRoutine += 2;
                } else if (cameraX0 >= HTZ1_SHAKE_EXIT_X) {
                    // Already past earthquake zone (e.g. teleported) - skip to post-shake
                    eventRoutine = 4;
                } else {
                    // Before earthquake zone or Y not met
                    if (earthquakeActive) {
                        exitHtzEarthquakeArea();
                    }
                }
            }
            case 2 -> {
                int cameraX = camera().getX();

                // ROM: LevEvents_HTZ_Routine2 only updates oscillation in [$1978, $1E00).
                if (cameraX >= HTZ1_OSCILLATE_START_X && cameraX < HTZ1_SHAKE_DISABLE_X) {
                    updateHtzLavaOscillation(frameCounter);
                } else if (cameraX >= HTZ1_SHAKE_DISABLE_X) {
                    // ROM: move.b #0,(Screen_Shaking_Flag).w
                    gameState().setScreenShakeActive(false);
                }

                // Keep routine 2 active while in [$1800, $1F00), matching disassembly.
                if (cameraX < HTZ1_SHAKE_TRIGGER_X) {
                    exitHtzEarthquakeArea();
                    eventRoutine -= 2;
                } else if (cameraX >= HTZ1_SHAKE_EXIT_X) {
                    exitHtzEarthquakeArea();
                    eventRoutine += 2;
                }
            }
            case 4 -> {
                // Routine 3: Post-shake area
                // Check for re-entry into shake zone
                if (camera().getX() < HTZ1_SHAKE_EXIT_X) {
                    setEarthquakeActive(true);
                    initHtzEarthquake(HTZ1_LAVA_OFFSET_RISEN, HTZ1_LAVA_OFFSET_SUNKEN, HTZ_BG_X_OFFSET_TOP);
                    eventRoutine -= 2;  // Go back to routine 2
                }
            }
            default -> {
                // Further routines handle boss area etc.
            }
        }
    }

    // =========================================================================
    // HTZ Act 2 Events
    // ROM: LevEvents_HTZ2 (s2.asm:20920-21193)
    // =========================================================================

    /**
     * HTZ Act 2 events.
     * Act 2 has multiple earthquake zones based on Camera Y position:
     * - Top area (Camera_Y < $380): Routines 0-4
     * - Bottom area (Camera_Y >= $380): Routines 6-8
     *
     * When entering earthquake zone in bottom area, ROM jumps directly to routine 8
     * (line 20946-20951: cmpi.w #$380,(Camera_Y_pos).w / blo.s + / addq.w #4,d0)
     */
    private void updateHTZAct2(int frameCounter) {
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0: Wait for shake trigger
                int cameraX0 = camera().getX();
                if (cameraX0 >= HTZ2_SHAKE_TRIGGER_X && cameraX0 < HTZ2_SHAKE_EXIT_X) {
                    // ROM: Check Y position to determine which earthquake zone
                    // If Camera_Y >= $380, jump to bottom area routines
                    if (camera().getY() >= HTZ2_Y_ZONE_THRESHOLD) {
                        // Bottom area
                        setEarthquakeActive(true);
                        initHtzEarthquake(HTZ2_LAVA_OFFSET_RISEN_BOTTOM, HTZ2_LAVA_OFFSET_SUNKEN,
                                HTZ_BG_X_OFFSET_BOTTOM);
                        eventRoutine = 8;
                    } else {
                        // Top area
                        setEarthquakeActive(true);
                        initHtzEarthquake(HTZ2_LAVA_OFFSET_RISEN_TOP, HTZ2_LAVA_OFFSET_SUNKEN, HTZ_BG_X_OFFSET_TOP);
                        eventRoutine = 2;
                    }
                } else if (cameraX0 >= HTZ2_SHAKE_EXIT_X) {
                    // Already past earthquake zone (e.g. teleported to last checkpoint)
                    // Skip to appropriate post-shake routine based on Y position
                    if (camera().getY() >= HTZ2_Y_ZONE_THRESHOLD) {
                        // Bottom zone
                        if (cameraX0 >= HTZ2_BOSS_CUTOFF_X) {
                            eventRoutine = 12;  // Skip to boss prep
                        } else {
                            eventRoutine = 10;  // Skip to post-shake (bottom)
                        }
                    } else {
                        eventRoutine = 4;  // Skip to post-shake (top)
                    }
                } else {
                    // Before earthquake zone
                    if (earthquakeActive) {
                        exitHtzEarthquakeArea();
                    }
                }
            }
            case 2 -> {
                int cameraX = camera().getX();

                // ROM: LevEvents_HTZ2_Routine2 oscillates only in [$1678, $1A00).
                if (cameraX >= HTZ2_TOP_OSCILLATE_START_X && cameraX < HTZ2_TOP_SHAKE_DISABLE_X) {
                    updateHtzLavaOscillation(frameCounter);
                } else if (cameraX >= HTZ2_TOP_SHAKE_DISABLE_X) {
                    // ROM: move.b #0,(Screen_Shaking_Flag).w
                    gameState().setScreenShakeActive(false);
                }

                // Keep routine 2 active while in [$14C0, $1B00).
                if (cameraX < HTZ2_SHAKE_TRIGGER_X) {
                    exitHtzEarthquakeArea();
                    eventRoutine = 0;
                } else if (cameraX >= HTZ2_SHAKE_EXIT_X) {
                    exitHtzEarthquakeArea();
                    eventRoutine += 2;
                }
            }
            case 4 -> {
                // Routine 2: Post-shake (top zone)
                if (camera().getX() < HTZ2_SHAKE_EXIT_X) {
                    setEarthquakeActive(true);
                    initHtzEarthquake(HTZ2_LAVA_OFFSET_RISEN_TOP, HTZ2_LAVA_OFFSET_SUNKEN, HTZ_BG_X_OFFSET_TOP);
                    eventRoutine -= 2;
                }
            }
            case 6 -> {
                // Routine 3: Wait for bottom area shake trigger
                // This handles re-entry to earthquake zone in bottom area
                if (camera().getX() >= HTZ2_SHAKE_TRIGGER_X) {
                    setEarthquakeActive(true);
                    initHtzEarthquake(HTZ2_LAVA_OFFSET_RISEN_BOTTOM, HTZ2_LAVA_OFFSET_SUNKEN,
                            HTZ_BG_X_OFFSET_BOTTOM);
                    eventRoutine += 2;
                } else {
                    if (earthquakeActive) {
                        exitHtzEarthquakeArea();
                    }
                }
            }
            case 8 -> {
                int cameraX = camera().getX();

                // ROM: LevEvents_HTZ2_Routine4 oscillates only in [$15F0, $1AC0).
                if (cameraX >= HTZ2_BOTTOM_OSCILLATE_START_X && cameraX < HTZ2_BOTTOM_SHAKE_DISABLE_X) {
                    updateHtzLavaOscillation(frameCounter);
                } else if (cameraX >= HTZ2_BOTTOM_SHAKE_DISABLE_X) {
                    // ROM: move.b #0,(Screen_Shaking_Flag).w
                    gameState().setScreenShakeActive(false);
                }

                // Keep routine 8 active while in [$14C0, $1B00).
                if (cameraX < HTZ2_SHAKE_TRIGGER_X) {
                    exitHtzEarthquakeArea();
                    eventRoutine = 6;
                } else if (cameraX >= HTZ2_SHAKE_EXIT_X) {
                    exitHtzEarthquakeArea();
                    eventRoutine = 10;
                }
            }
            case 10 -> {
                // Routine 5: Post-shake (bottom zone)
                if (camera().getX() < HTZ2_SHAKE_EXIT_X) {
                    setEarthquakeActive(true);
                    initHtzEarthquake(HTZ2_LAVA_OFFSET_RISEN_BOTTOM, HTZ2_LAVA_OFFSET_SUNKEN,
                            HTZ_BG_X_OFFSET_BOTTOM);
                    eventRoutine -= 2;
                } else if (camera().getX() >= HTZ2_BOSS_CUTOFF_X) {
                    // Approaching boss area - advance to boss routines
                    exitHtzEarthquakeArea();
                    eventRoutine = 12;
                }
            }
            case 12 -> {
                // Routine 6: Boss area cutoff (LevEvents_HTZ2_Routine6)
                // ROM: s2.asm:21197-21206
                if (camera().getX() >= HTZ2_BOSS_ARENA_TRIGGER_X) {
                    // ROM: Set minX to lock arena left boundary
                    camera().setMinX(camera().getX());
                    // ROM: Set maxY TARGET to allow boss area access
                    camera().setMaxYTarget((short) HTZ2_BOSS_ARENA_MAX_Y);
                    setSidekickBounds((int) camera().getX(), null, HTZ2_BOSS_ARENA_MAX_Y);
                    eventRoutine += 2;
                }
            }
            case 14 -> {
                // Routine 7: Boss arena camera shift (LevEvents_HTZ2_Routine7)
                // ROM: s2.asm:21222-21238
                if (camera().getX() >= HTZ2_BOSS_ARENA_LOCK_TRIGGER_X) {
                    // ROM: Lock camera X boundaries
                    camera().setMinX((short) HTZ2_BOSS_ARENA_LEFT);
                    camera().setMaxX((short) HTZ2_BOSS_ARENA_RIGHT);
                    setSidekickBounds(HTZ2_BOSS_ARENA_LEFT, HTZ2_BOSS_ARENA_RIGHT, null);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    // ROM: Fade out music
                    audio().fadeOutMusic();
                    // ROM: Set Current_Boss_ID to 3 (HTZ boss)
                    gameState().setCurrentBossId(3);
                    requestSonic2Plc(Sonic2Constants.PLC_HTZ_BOSS);
                }
            }
            case 16 -> {
                // Routine 8: Boss spawn (LevEvents_HTZ2_Routine8)
                // ROM: s2.asm:21241-21258
                // ROM: Lock minY when camera Y reaches boss floor
                if (camera().getY() >= HTZ2_BOSS_FLOOR_Y) {
                    camera().setMinY((short) HTZ2_BOSS_FLOOR_Y);
                }
                // ROM: Increment delay every frame, spawn boss at $5A (90) frames
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    spawnHTZBoss();
                    eventRoutine += 2;
                    // Start boss music
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 18 -> {
                // Routine 9: Boss end / camera extend (LevEvents_HTZ2_Routine9)
                // ROM: s2.asm:21261-21277
                syncSidekickBoundsToCamera();
                if (!gameState().isBossDefeatedFlag()) {
                    // ROM: tst.b (Boss_defeated_flag).w / beq.s ++ (s2.asm:21293-21295).
                    return;
                }

                // ROM: Camera_Min_X_pos follows Camera_X_pos after boss defeat.
                short cameraX = camera().getX();
                camera().setMinX(cameraX);

                // ROM: once camera reaches $30E0, ease Y bounds up toward $428/$430.
                if (cameraX >= 0x30E0) {
                    if (camera().getMinY() >= 0x428) {
                        camera().setMinY((short) (camera().getMinY() - 2));
                    }
                    if (camera().getMaxYTarget() >= 0x430) {
                        camera().setMaxYTarget((short) (camera().getMaxYTarget() - 2));
                    }
                }
            }
            default -> {
                // Post-boss: no more routines
            }
        }
    }

    // =========================================================================
    // HTZ Earthquake Helper Methods
    // =========================================================================

    /**
     * Initialize HTZ earthquake state when entering shake zone.
     * ROM: LevEvents_HTZ_Routine1 lines 20698-20707
     * Uses different oscillation limits for Act 1 vs Act 2 (and top/bottom route).
     *
     * @param risenLimit risen Camera_BG_Y_offset limit for this earthquake zone
     * @param sunkenLimit sunken Camera_BG_Y_offset limit for this earthquake zone
     * @param bgXOffset Camera_BG_X_offset for this earthquake zone
     */
    private void initHtzEarthquake(int risenLimit, int sunkenLimit, int bgXOffset) {
        cameraBgYOffset = risenLimit;
        htzCurrentRisenLimit = risenLimit;
        htzCurrentSunkenLimit = sunkenLimit;
        htzCurrentBgXOffset = bgXOffset;
        htzTerrainSinking = false;
        htzTerrainDelay = 0;
    }

    /**
     * Exit HTZ earthquake area - clear screen shake and reset offset.
     * ROM: LevEvents_HTZ_Routine1_Part2 lines 20714-20724
     */
    private void exitHtzEarthquakeArea() {
        setEarthquakeActive(false);
        cameraBgYOffset = 0;
        htzTerrainSinking = false;
        htzTerrainDelay = 0;
        htzCurrentBgXOffset = HTZ_BG_X_OFFSET_TOP;
    }

    /**
     * Update HTZ lava oscillation each frame while in earthquake zone.
     * ROM: LevEvents_HTZ_Routine2 lines 20726-20771
     *
     * The lava moves 1 pixel every 4 frames (when frameCounter & 3 == 0).
     * When it reaches the limit, delay counter decrements until 0,
     * then direction toggles and delay resets to 120 frames.
     *
     * Act 1: oscillates between 224-320 (96px range)
     * Act 2 top: oscillates between 0-704
     * Act 2 bottom: oscillates between 0-768
     */
    private void updateHtzLavaOscillation(int frameCounter) {
        // Use the limits set by initHtzEarthquake for the current earthquake zone
        int risenLimit = htzCurrentRisenLimit;
        int sunkenLimit = htzCurrentSunkenLimit;

        // ROM: tst.b (HTZ_Terrain_Direction).w / bne.s .sinking
        if (!htzTerrainSinking) {
            // Rising: offset goes toward risen limit
            if (cameraBgYOffset >= risenLimit) {
                // At limit - check delay for direction change
                handleHtzTerrainDelayAndToggle();
            } else {
                // Move every 4 frames: andi.w #3,d0 / bne.s continue
                if ((frameCounter & 3) == 0) {
                    cameraBgYOffset++;
                    // Play rumbling sound every 64 frames
                    if ((frameCounter & 0x3F) == 0) {
                        audio().playSfx(Sonic2Sfx.RUMBLING_2.id);
                    }
                }
            }
        } else {
            // Sinking: offset goes toward sunken limit
            if (cameraBgYOffset <= sunkenLimit) {
                // At limit - check delay for direction change
                handleHtzTerrainDelayAndToggle();
            } else {
                // Move every 4 frames
                if ((frameCounter & 3) == 0) {
                    cameraBgYOffset--;
                    // Play rumbling sound every 64 frames
                    if ((frameCounter & 0x3F) == 0) {
                        audio().playSfx(Sonic2Sfx.RUMBLING_2.id);
                    }
                }
            }
        }
    }

    /**
     * Handle delay countdown and direction toggle at oscillation limits.
     * ROM: .flip_delay lines 20765-20771
     */
    private void handleHtzTerrainDelayAndToggle() {
        // Disable general screen shaking while at limit (waiting)
        gameState().setScreenShakeActive(false);
        htzTerrainDelay--;
        if (htzTerrainDelay < 0) {
            // Toggle direction and reset delay
            htzTerrainDelay = HTZ_TERRAIN_DELAY_INIT;
            htzTerrainSinking = !htzTerrainSinking;
            // Re-enable screen shake for movement
            gameState().setScreenShakeActive(true);
        }
    }

    /**
     * Spawns the HTZ Act 2 boss.
     * ROM: Creates Object 0x52 at arena position
     */
    private void spawnHTZBoss() {
        // HTZ boss initial position from Obj52_Init
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x3040, 0x0580, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0);
        htzBoss = new Sonic2HTZBossInstance(bossSpawn);
        spawnObject(htzBoss);
    }
}
