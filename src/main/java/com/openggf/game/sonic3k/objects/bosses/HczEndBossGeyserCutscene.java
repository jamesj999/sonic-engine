package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance;
import com.openggf.game.sonic3k.scroll.SwScrlHcz;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ2 end-boss post-defeat geyser cutscene (ROM: loc_6B7BC-loc_6B8C8).
 *
 * <p>Spawned by {@link HczEndBossInstance} when the egg capsule is opened.
 * Runs three sequential phases:
 *
 * <ol>
 *   <li><b>SHAKE (95 frames)</b> -- screen shakes, sfx_Rumble2 plays every 8 frames.</li>
 *   <li><b>GEYSER_RISE</b> -- a geyser object rises from Camera_Y + 0x130 at 6 px/frame.
 *       When the geyser top reaches player_Y - 0x60 the player is grabbed (HURT anim set)
 *       and the phase transitions to CARRY.</li>
 *   <li><b>CARRY (95 frames)</b> -- geyser and grabbed player both move up 6 px/frame.
 *       After 95 frames the engine requests a transition to MGZ Act 1
 *       (zone 0x02, act 0) matching the ROM {@code StartNewLevel} call.</li>
 * </ol>
 *
 * <p>Art: uses Map_HCZWaterWall frame 1 as a single ROM-accurate sprite.
 *
 * <p>Debris: 8 {@link GeyserDebrisChild} objects are spawned at geyser setup time
 * (ROM: loc_6BCB2). These use Map_HCZWaterWallDebris with 8 cycling frames,
 * fly outward with gravity, and stop at the water level.
 *
 * <p>Screen shake is applied via {@link SwScrlHcz#setScreenShakeOffset} on the
 * existing HCZ scroll handler, exactly as the wall-stop shake in
 * {@code Sonic3kHCZEvents}.
 */
public class HczEndBossGeyserCutscene extends AbstractObjectInstance
        implements SpawnCoordinateRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(HczEndBossGeyserCutscene.class.getName());

    // =========================================================================
    // Phase constants
    // =========================================================================
    private static final int PHASE_SHAKE       = 0;
    private static final int PHASE_GEYSER_RISE = 1;
    private static final int PHASE_CARRY       = 2;
    private static final int PHASE_DONE        = 3;
    private static final int PHASE_SETUP_DELAY = 4;

    // =========================================================================
    // Timing constants (ROM: loc_6B7BC)
    // =========================================================================
    /** Duration of the initial screen-shake phase (frames). ROM: $5F. */
    private static final int SHAKE_DURATION    = 0x5F;  // 95 frames
    /** How often to replay sfx_Rumble2 during the shake phase (frames). */
    private static final int RUMBLE_INTERVAL   = 8;
    /** Duration of the carry phase before zone transition (frames). ROM: $5F. */
    private static final int CARRY_DURATION    = 0x5F;  // 95 frames

    // =========================================================================
    // Movement constants (ROM: loc_6B882)
    // =========================================================================
    /** Rise and carry speed in pixels per frame (ROM: #6). */
    private static final int GEYSER_RISE_SPEED = 6;
    /**
     * Vertical offset above player centre Y at which the geyser grabs the player.
     * ROM: {@code subi.w #$60,d0; cmp.w y_pos(a1),d0; bhs.s loc_6B8B2}
     */
    private static final int GRAB_Y_OFFSET     = 0x60;

    // =========================================================================
    // Spawn position constants (ROM: loc_6B864)
    // =========================================================================
    /**
     * Geyser column spawn Y offset below the camera top edge.
     * ROM: {@code Camera_Y + $130} places the geyser base 0x130 px below the camera.
     */
    private static final int SPAWN_CAMERA_Y_OFFSET = 0x130;

    // =========================================================================
    // Art: Map_HCZWaterWall frame indices (dedicated geyser cutscene art)
    // Frame 1 = tall vertical water column (12 pieces, ~96 px tall)
    // Frames 3-5 = splash sprites at the geyser top
    // =========================================================================
    private static final int COLUMN_FRAME_INDEX = 1;
    private static final int SPLASH_FRAME_BASE  = 3;
    private static final int SPLASH_FRAME_COUNT = 3; // frames 3, 4, 5
    private static final int SPLASH_ANIM_SPEED  = 4; // ticks per splash frame

    // =========================================================================
    // Debris spawn table (ROM: byte_303EA, sonic3k.asm ~65190)
    // {xOff, yOff, xVel, yVel} x 8 — same table as the vertical water wall.
    // Debris Y is offset by -0x80 from geyser y_pos (ROM: subi.w #$80,d3).
    // =========================================================================
    private static final int[][] DEBRIS_TABLE = {
            {-0x18, 0, -0x200, -0xB00},
            {-0x08, 0, -0x100, -0xC00},
            {-0x18, 0, -0x400, -0x800},
            {-0x08, 0, -0x300, -0xA00},
            { 0x08, 0,  0x300, -0xC00},
            { 0x18, 0,  0x400, -0xB00},
            { 0x08, 0,  0x100, -0xA00},
            { 0x18, 0,  0x200, -0x800},
    };

    private static ObjectSpawn createDebrisSpawn(int x, int y, int xVel, int yVel, int initialFrame) {
        for (int i = 0; i < DEBRIS_TABLE.length; i++) {
            int[] entry = DEBRIS_TABLE[i];
            if (entry[2] == xVel && entry[3] == yVel
                    && initialFrame == (DEBRIS_TABLE.length - 1) - i) {
                return new ObjectSpawn(x, y, 0, i, 0, false, y);
            }
        }
        return new ObjectSpawn(x, y, 0, initialFrame & 7, 0, false, y);
    }

    // =========================================================================
    // Next level (ROM: StartNewLevel zone $0200 = MGZ Act 1)
    // =========================================================================
    private static final int NEXT_ZONE = Sonic3kZoneIds.ZONE_MGZ;  // 0x02
    private static final int NEXT_ACT  = 0;                        // Act 1 (0-based)

    // =========================================================================
    // ROM screen-shake table (ScreenShakeArray, sonic3k.asm ~104226)
    // Same table used by Sonic3kHCZEvents for timed shakes.
    // =========================================================================
    private static final byte[] SCREEN_SHAKE_TABLE = {
            1, -1, 1, -1, 2, -2, 2, -2, 3, -3, 3, -3, 4, -4, 4, -4,
            5, -5, 5, -5
    };

    // =========================================================================
    // Instance state
    // =========================================================================
    private int phase       = PHASE_SHAKE;
    private int timer;
    private int rumbleTimer;

    /** ROM y_pos for the geyser object, updated each frame in RISE/CARRY. */
    private int geyserY;

    /** Geyser column X (player_1 X at spawn time, held constant). */
    private int geyserX;

    /** True once the player has been grabbed by the rising column. */
    private boolean playerGrabbed;

    /** True once debris children have been spawned. */
    private boolean debrisSpawned;
    private boolean targetsNativeP2;
    /** Native loc_6B7BC/loc_6B7D2 dispatches before the shake routine starts. */
    private int initialRoutineDispatchesRemaining;
    private S3kKosModuleQueue artQueue;
    private HardwareWorkHandle artHandle;
    private long artOrdinal = -1;
    private boolean artLoaded;

    /** ROM root object uses priority $280. */
    private static final int GEYSER_PRIORITY_BUCKET = 5;
    /** Debris uses priority $280 in the ROM. */
    private static final int DEBRIS_PRIORITY_BUCKET = 5;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * @param spawnX  Player_1 X position at capsule-open time (ROM: Player_1 x_pos).
     * @param spawnY  Camera Y + SPAWN_CAMERA_Y_OFFSET at spawn time.
     */
    public HczEndBossGeyserCutscene(int spawnX, int spawnY) {
        this(spawnX, spawnY, false, 0);
    }

    static HczEndBossGeyserCutscene createWithQueuedArt(int spawnX, int spawnY) {
        return new HczEndBossGeyserCutscene(spawnX, spawnY);
    }

    private HczEndBossGeyserCutscene(int spawnX, int spawnY,
            boolean targetsNativeP2, int setupDelay) {
        super(new ObjectSpawn(spawnX, spawnY, 0, 0, 0, false, 0), "HCZGeyserCutscene");
        this.geyserX    = spawnX;
        this.geyserY    = spawnY;
        this.targetsNativeP2 = targetsNativeP2;
        this.initialRoutineDispatchesRemaining = targetsNativeP2 ? 0 : 2;
        this.phase = setupDelay > 0 ? PHASE_SETUP_DELAY : PHASE_SHAKE;
        this.timer = setupDelay > 0 ? setupDelay : SHAKE_DURATION;
        this.rumbleTimer = 0;
        this.playerGrabbed = false;
        this.debrisSpawned = false;
        LOG.fine("HCZ Geyser Cutscene: spawned at X=" + spawnX + " Y=" + spawnY);
    }

    private HczEndBossGeyserCutscene() {
        this(0, 0);
    }

    // =========================================================================
    // Main update
    // =========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        serviceQueuedArt();
        if (initialRoutineDispatchesRemaining > 0) {
            initialRoutineDispatchesRemaining--;
            return;
        }
        AbstractPlayableSprite player = resolveTargetPlayer(playerEntity);

        switch (phase) {
            case PHASE_SHAKE       -> updateShake(player);
            case PHASE_SETUP_DELAY -> updateSetupDelay(player);
            case PHASE_GEYSER_RISE -> updateGeyserRise(player);
            case PHASE_CARRY       -> updateCarry(player);
            case PHASE_DONE        -> { /* terminal */ }
            default                -> { }
        }
    }

    private void serviceQueuedArt() {
        if (targetsNativeP2 || artLoaded) {
            return;
        }
        rebindArtAfterRestore();
        try {
            if (artHandle == null && artQueue == null) {
                artQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
                artHandle = artQueue.queue(
                        services().rom(),
                        Sonic3kConstants.ART_KOSM_HCZ_GEYSER_VERT_ADDR,
                        Sonic3kConstants.ARTTILE_HCZ_CUTSCENE_GEYSER);
                artOrdinal = artHandle.ordinal();
                return;
            }
            if (artHandle != null && artQueue.isReady(artHandle)) {
                artQueue.claim(artHandle);
                artHandle = null;
                artQueue = null;
                artOrdinal = -1;
                artLoaded = true;
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue HCZ end-cutscene geyser KosM art", e);
        }
    }

    private void rebindArtAfterRestore() {
        if (artOrdinal < 0 || artQueue != null) {
            return;
        }
        artHandle = services().hardwareTiming().pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE, artOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing restored HCZ end-geyser KosM job " + artOrdinal));
        artQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
    }

    private AbstractPlayableSprite resolveTargetPlayer(PlayableEntity playerEntity) {
        if (targetsNativeP2) {
            PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
            return nativeP2 instanceof AbstractPlayableSprite sprite ? sprite : null;
        }
        return playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    private void updateSetupDelay(AbstractPlayableSprite player) {
        timer--;
        if (timer >= 0) {
            return;
        }
        setupGeyserColumn(player);
    }

    // =========================================================================
    // Phase: SHAKE (ROM: loc_6B7EC-loc_6B804)
    // =========================================================================

    /**
     * Ticks the screen shake and Rumble2 SFX for 95 frames, then advances
     * to GEYSER_RISE.
     *
     * <p>ROM behavior: Screen_shake_flag set each frame; sfx_Rumble2 replayed every
     * 8 frames; timer counts down from $5F to 0.
     */
    private void updateShake(AbstractPlayableSprite player) {
        // Apply screen shake via the HCZ scroll handler
        applyScreenShake(timer);

        // Replay sfx_Rumble2 every 8 frames (ROM: btst #3,d0 / bne.s skip_sfx)
        rumbleTimer--;
        if (rumbleTimer <= 0) {
            services().playSfx(Sonic3kSfx.RUMBLE_2.id);
            rumbleTimer = RUMBLE_INTERVAL;
        }

        timer--;
        if (timer <= 0) {
            if (!targetsNativeP2 && services().playerQuery().nativeP2OrNull() != null) {
                // loc_6B804 allocates a second owner with subtype=-1 and a
                // four-count Obj_Wait before loc_6B832 targets Player_2.
                spawnChild(() -> new HczEndBossGeyserCutscene(
                        geyserX, geyserY, true, 4));
            }
            // Shake complete -- set up the geyser column (ROM: loc_6B832)
            clearScreenShake();
            setupGeyserColumn(player);
            LOG.fine("HCZ Geyser Cutscene: shake done, geyser rising from Y=" + geyserY);
        }
    }

    // =========================================================================
    // Geyser setup (ROM: loc_6B832-loc_6B864)
    // =========================================================================

    /**
     * Sets up the geyser column after the shake phase completes.
     * ROM: loc_6B832 -- SetUp_ObjAttributes with ObjDat3_6BD7E,
     * plays sfx_Geyser, activates palette cycling, sets up position,
     * and spawns 8 debris children (loc_6BCB2).
     */
    private void setupGeyserColumn(AbstractPlayableSprite player) {
        // ROM: st (Screen_shake_flag).w -- keep shake active during rise
        // ROM: st (Palette_cycle_counters+$00).w -- activate palette cycling
        HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.setActive(true);

        // ROM: sfx_Geyser
        try {
            services().playSfx(Sonic3kSfx.GEYSER.id);
        } catch (Exception e) {
            LOG.fine(() -> "HCZ Geyser Cutscene: Failed to play SFX: " + e.getMessage());
        }

        // ROM: move.w x_pos(a1),d0 / move.w d0,x_pos(a0)
        // ROM: Camera_Y + $130 -> y_pos(a0)
        var camera = services().camera();
        if (player != null) {
            geyserX = player.getCentreX();
        }
        geyserY = camera.getY() + SPAWN_CAMERA_Y_OFFSET;

        // Spawn 8 debris children (ROM: jmp loc_6BCB2)
        spawnDebris();

        timer = -1;
        phase = PHASE_GEYSER_RISE;
    }

    // =========================================================================
    // Debris spawning (ROM: loc_6BCB2)
    // =========================================================================

    /**
     * Spawns 8 water debris children around the geyser.
     * ROM: loc_6BCB2 -- reads byte_303EA table, spawns debris at
     * geyser X + xOff, geyser Y - 0x80 + yOff with xVel/yVel.
     * Debris uses Map_HCZWaterWallDebris with art_tile ArtTile_HCZCutsceneGeyser+$58.
     */
    private void spawnDebris() {
        if (debrisSpawned) return;
        debrisSpawned = true;

        int debrisBaseY = geyserY - 0x80; // ROM: subi.w #$80,d3

        for (int i = 0; i < DEBRIS_TABLE.length; i++) {
            int[] entry = DEBRIS_TABLE[i];
            int debrisX = geyserX + entry[0];
            int debrisY = debrisBaseY + entry[1];

            // ROM: mapping_frame = d1 (loop counter 7..0, so frame = 7-i)
            // dbf d1 counts 7,6,5,...0 and mapping_frame = d1
            int mappingFrame = (DEBRIS_TABLE.length - 1) - i;

            GeyserDebrisChild debris = new GeyserDebrisChild(
                    debrisX, debrisY, entry[2], entry[3], mappingFrame);
            spawnDynamicObject(debris);
        }
        LOG.fine("HCZ Geyser Cutscene: spawned " + DEBRIS_TABLE.length + " debris children");
    }

    // =========================================================================
    // Phase: GEYSER_RISE (ROM: loc_6B882)
    // =========================================================================

    /**
     * Rises the geyser column at 6 px/frame. When the column top reaches
     * the player, the player is grabbed (object_control set, HURT anim,
     * velocities cleared) and the phase transitions to CARRY.
     *
     * <p>ROM: {@code subq.w #6,d0; move.w d0,y_pos(a0); subi.w #$60,d0;
     * cmp.w y_pos(a1),d0; bhs.s loc_6B8B2} -- keep rising while
     * (geyserY - $60) >= playerY; grab when (geyserY - $60) < playerY.
     */
    private void updateGeyserRise(AbstractPlayableSprite player) {
        geyserY -= GEYSER_RISE_SPEED;

        if (player == null || playerGrabbed) {
            return;
        }

        int playerY = player.getCentreY();
        // ROM: d0 = geyserY - $60; cmp.w y_pos(a1),d0; bhs.s skip_grab
        // Grab when (geyserY - GRAB_Y_OFFSET) < playerY
        if (geyserY - GRAB_Y_OFFSET < playerY) {
            // Grab the player (ROM: loc_6B882 grab branch)
            // ROM: move.b #$81,object_control(a1)
            ObjectControlState.nativeBit7FullControl().applyTo(player);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);

            // ROM: move.b #$1A,anim(a1) -- HURT animation
            player.setAnimationId(Sonic3kAnimationIds.HURT);

            playerGrabbed = true;
            timer = CARRY_DURATION;
            phase = PHASE_CARRY;
            LOG.fine("HCZ Geyser Cutscene: player grabbed at geyserY=" + geyserY
                    + " playerY=" + playerY);
        }
    }

    // =========================================================================
    // Phase: CARRY (ROM: loc_6B8C8)
    // =========================================================================

    /**
     * Carries the grabbed player upward at 6 px/frame for 95 frames, then
     * requests a zone transition to MGZ Act 1 (ROM: {@code StartNewLevel #$0200}).
     */
    private void updateCarry(AbstractPlayableSprite player) {
        // ROM: subq.w #6,y_pos(a0)
        geyserY -= GEYSER_RISE_SPEED;

        // ROM: subq.w #6,y_pos(a1) -- directly subtract from player Y each frame
        if (player != null && playerGrabbed) {
            player.setY((short) (player.getY() - GEYSER_RISE_SPEED));
        }

        // loc_6B8C8 moves both owners, but subtype=-1 branches back to draw
        // before touching $2E. Only the primary owns the transition timer
        // (sonic3k.asm:141621-141633).
        if (targetsNativeP2) {
            return;
        }

        timer--;
        if (timer < 0) {
            playerGrabbed = false;
            phase = PHASE_DONE;
            setDestroyed(true);

            // loc_6B8C8 enters StartNewLevel without restoring object_control;
            // the carried position/control state survives this dispatch. The
            // engine observes the transition before its camera step, so the
            // level-frame executor already preserves the ROM's pre-dispatch Y.
            // The post-results fade is still active here.  Start MGZ1 after
            // its level load, rather than letting that source-zone fade mute
            // the destination's ordinary level-start command.
            services().requestZoneAndAct(
                    NEXT_ZONE, NEXT_ACT, true, Sonic3kMusic.MGZ1.id);
            LOG.info("HCZ Geyser Cutscene: carry complete, requesting MGZ Act 1");
        }
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /**
     * Draws the geyser as the ROM does: a single Map_HCZWaterWall frame-1 sprite.
     *
     * <p>ROM: ObjDat3_6BD7E specifies Map_HCZWaterWall, palette 2,
     * width $20 (32px), height $60 (96px), mapping_frame 1.
     */
    @Override
    public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
        if (isDestroyed() || phase == PHASE_SHAKE || phase == PHASE_SETUP_DELAY) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_GEYSER_VERT);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(COLUMN_FRAME_INDEX, geyserX, geyserY, false, false);
    }

    // =========================================================================
    // Screen shake helpers
    // =========================================================================

    /**
     * Apply a shake offset via the HCZ scroll handler.
     * Uses the ROM's timed shake table indexed by the remaining timer value.
     * When the timer is outside the table range a small alternating offset is used.
     *
     * @param remaining frames remaining in shake phase
     */
    private void applyScreenShake(int remaining) {
        SwScrlHcz handler = resolveHczScrollHandler();
        if (handler == null) {
            return;
        }
        // Map remaining timer (SHAKE_DURATION..1) into the table
        // Use modulo of the table length for a repeating pattern
        int idx = remaining % SCREEN_SHAKE_TABLE.length;
        handler.setScreenShakeOffset(SCREEN_SHAKE_TABLE[idx]);
    }

    /** Clears screen shake on the HCZ scroll handler. */
    private void clearScreenShake() {
        SwScrlHcz handler = resolveHczScrollHandler();
        if (handler != null) {
            handler.setScreenShakeOffset(0);
        }
    }

    /**
     * Resolves the {@link SwScrlHcz} scroll handler from the parallax manager,
     * or returns null if unavailable (headless / unit-test context).
     */
    private SwScrlHcz resolveHczScrollHandler() {
        try {
            var parallax = services().parallaxManager();
            if (parallax == null) return null;
            ZoneScrollHandler handler = parallax.getHandler(Sonic3kZoneIds.ZONE_HCZ);
            return (handler instanceof SwScrlHcz hcz) ? hcz : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(GEYSER_PRIORITY_BUCKET);
    }

    // =========================================================================
    // Inner class: Geyser Debris Child (ROM: loc_3011A / loc_6BCB2)
    // =========================================================================

    /**
     * Water debris child spawned by the geyser cutscene.
     *
     * <p>ROM: loc_3011A -- animates through 8 mapping frames (Map_HCZWaterWallDebris),
     * moves with MoveSprite2 and gravity ($38). When Y exceeds Water_level, stops
     * Y velocity and halves X velocity twice, then continues sinking until off-screen.
     *
     * <p>Art: ArtTile_HCZCutsceneGeyser+$58, palette 2. Uses existing
     * {@link Sonic3kObjectArtKeys#HCZ_GEYSER_DEBRIS} art key (same debris mapping
     * frames, compatible tile layout).
     */
    static class GeyserDebrisChild extends AbstractObjectInstance implements SpawnRewindRecreatable {

        private static final int GRAVITY = 0x38;        // ROM: addi.w #$38,y_vel
        private static final int SLOW_GRAVITY = 8;      // ROM: loc_301A8 addi.w #8,y_vel
        private static final int ANIM_TIMER_RESET = 2;  // ROM: move.b #2,anim_frame_timer

        private enum DebrisState { FLYING, SINKING }

        private final SubpixelMotion.State motion;
        private int mappingFrame;
        private int animTimer = ANIM_TIMER_RESET;
        private DebrisState state = DebrisState.FLYING;
        private boolean splashSpawned;

        GeyserDebrisChild(int x, int y, int xVel, int yVel, int initialFrame) {
            this(createDebrisSpawn(x, y, xVel, yVel, initialFrame));
        }

        private GeyserDebrisChild(ObjectSpawn spawn) {
            super(spawn, "GeyserCutsceneDebris");
            int tableIndex = spawn.subtype() & 7;
            int[] entry = DEBRIS_TABLE[tableIndex];
            int x = spawn.x();
            int y = spawn.y();
            int xVel = entry[2];
            int yVel = entry[3];
            this.motion = new SubpixelMotion.State(x, y, 0, 0, xVel, yVel);
            this.mappingFrame = (DEBRIS_TABLE.length - 1) - tableIndex;
        }

        @Override
        public int getX() {
            return motion.x;
        }

        @Override
        public int getY() {
            return motion.y;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) return;

            switch (state) {
                case FLYING -> updateFlying();
                case SINKING -> updateSinking();
            }
        }

        /**
         * ROM: loc_3011A -- animate frame, move with gravity, check water level.
         */
        private void updateFlying() {
            // Animate: cycle mapping_frame 0-7 every 2 ticks
            animTimer--;
            if (animTimer <= 0) {
                animTimer = ANIM_TIMER_RESET;
                mappingFrame = (mappingFrame + 1) & 7;
            }

            // Move with gravity
            SubpixelMotion.moveSprite(motion, GRAVITY);

            // Check water level (ROM: cmp.w y_pos(a0),Water_level / bhs)
            int waterLevel = getWaterLevel();
            if (waterLevel > 0 && motion.y > waterLevel) {
                // ROM: stop Y velocity, halve X velocity twice (asr twice)
                motion.yVel = 0;
                motion.xVel >>= 2;
                state = DebrisState.SINKING;
            }
        }

        /**
         * ROM: loc_301A8 -- keep moving with reduced gravity until off-screen.
         */
        private void updateSinking() {
            SubpixelMotion.moveSprite(motion, SLOW_GRAVITY);

            // ROM: tst.b render_flags(a0) / bpl Delete_Current_Sprite
            if (!isOnScreen(0x80)) {
                setDestroyed(true);
            }
        }

        private int getWaterLevel() {
            try {
                var ws = services().waterSystem();
                if (ws != null) {
                    return ws.getWaterLevelY(services().romZoneId(), services().currentAct());
                }
            } catch (Exception e) {
                // fallback
            }
            return 0;
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
            // Use the existing debris art (Map_HCZWaterWallDebris).
            // The cutscene debris uses ArtTile_HCZCutsceneGeyser+$58, palette 2,
            // which is compatible with the HCZ_GEYSER_DEBRIS art key.
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_GEYSER_DEBRIS);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, motion.x, motion.y, false, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(DEBRIS_PRIORITY_BUCKET);
        }
    }
}
