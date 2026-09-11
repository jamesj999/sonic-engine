package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x3B - HCZ Water Wall / Geyser (Sonic 3 &amp; Knuckles, Hydrocity Zone).
 *
 * <p>Two completely separate behaviours based on subtype:
 * <ul>
 *   <li><b>Subtype 0 (Horizontal geyser):</b> Endpoint of the Act 1 water rush sequence.
 *       A wall of water erupts when the player is swept past it. Spawns debris and spray
 *       particles that move right and fall into the water.</li>
 *   <li><b>Subtype != 0 (Vertical geyser):</b> Triggered when the player is nearby.
 *       Water erupts upward, captures and launches the player.</li>
 * </ul>
 *
 * <p>ROM references: sonic3k.asm lines 64835-65307 (Obj_HCZWaterWall).
 * Mappings: Map_HCZWaterWall, Map_HCZWaterWallDebris.
 * Art: ArtKosM_HCZGeyserHorz (0x390C02), ArtKosM_HCZGeyserVert (0x391394).
 */
public class HCZWaterWallObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(HCZWaterWallObjectInstance.class.getName());

    // ===== Subtype 0: Horizontal Geyser Constants =====
    private static final int HORZ_Y_GUARD = 0x500;
    private static final int HORZ_INITIAL_TIMER = 0x20; // 32 frames
    private static final int HORZ_PLAYER_TRIGGER_OFFSET = 0x60;
    private static final int HORZ_SPRAY_MOVE_PX = 8;
    private static final int HORZ_CLEANUP_TIMER = 150;

    // Horizontal debris spawn table: {xOff, yOff, xVel, yVel} x 8
    // ROM: byte_3000C (sonic3k.asm line ~65036)
    private static final int[][] HORZ_DEBRIS_TABLE = {
            {0, -0x18, 0x400, -0x80},
            {0, -0x08, 0x600, -0x40},
            {0,  0x08, 0x600,  0x40},
            {0,  0x18, 0x400,  0x80},
            {0, -0x18, 0x300, -0x380},
            {0, -0x08, 0x400, -0x340},
            {0,  0x08, 0x300,  0x100},
            {0,  0x18, 0x500, -0x100},
    };

    // ===== Subtype != 0: Vertical Geyser Constants =====
    // ROM loc_30294 tests two *unsigned* windows, not symmetric ranges:
    //   (Player_1+x_pos - x_pos + $30) u< $60  ->  dx in [-$30, +$2F]
    //   (Player_1+y_pos - y_pos + $40) u< $10  ->  dy in [-$40, -$31]
    // The x window is only 0x60 wide *in total* and biased so the geyser fires
    // when the player is nearly on top of it (sonic3k.asm:65126-65134).
    private static final int VERT_X_TRIGGER_BIAS = 0x30;
    private static final int VERT_X_TRIGGER_WINDOW = 0x60;
    private static final int VERT_Y_TRIGGER_BIAS = 0x40;
    private static final int VERT_Y_TRIGGER_WINDOW = 0x10;
    private static final int VERT_ART_LOAD_PULL_PX = 8;
    private static final int VERT_RISE_TIMER = 0x60; // 96 frames
    private static final int VERT_ERUPTION_TRIGGER = 0x28;
    private static final int VERT_RISE_PX = 8;
    private static final int VERT_ERUPTION_Y_VEL = -0xA00;
    private static final int VERT_ERUPTION_PLAYER_Y_VEL = -0xC00;
    private static final int VERT_ERUPTION_MOVE_PX = 0x0A;
    private static final int VERT_FALLING_Y_VEL = -0x800;
    private static final int VERT_SPRAY_X_OFFSET = 0x10;
    private static final int VERT_SPRAY_Y_OFFSET = 0x50;
    private static final int VERT_SPRAY_Y_VEL = -0x700;
    private static final int VERT_FALLING_GRAVITY = 0x48;
    private static final int VERT_CLEANUP_TIMER = 0x1E; // 30 frames

    // Vertical debris spawn table: {xOff, yOff, xVel, yVel} x 8
    // ROM: byte_303EA (sonic3k.asm line ~65290)
    private static final int[][] VERT_DEBRIS_TABLE = {
            {-0x18, 0, -0x200, -0xB00},
            {-0x08, 0, -0x100, -0xC00},
            {-0x18, 0, -0x400, -0x800},
            {-0x08, 0, -0x300, -0xA00},
            { 0x08, 0,  0x300, -0xC00},
            { 0x18, 0,  0x400, -0xB00},
            { 0x08, 0,  0x100, -0xA00},
            { 0x18, 0,  0x200, -0x800},
    };

    // ===== State Machine =====
    private enum HorzPhase {
        Y_GUARD,        // Phase 1: Wait for player Y < 0x500 guard
        ART_LOAD,       // Phase 2: Load art (simulated instant)
        WAIT_PROXIMITY, // Phase 4: Wait for player proximity
        SPRAY_ANIM,     // Phase 5: Spray animation + move right
        CLEANUP         // Phase 6: Timer countdown before delete
    }

    private enum VertPhase {
        PROXIMITY_CHECK, // Phase 1: Wait for player proximity
        ART_LOAD,        // Phase 2: Load art + pull players up
        RISE,            // Phase 4: Rise phase (timer-driven)
        ERUPTION,        // Phase 5: Eruption (launch upward)
        FALLING,         // Phase 6: Falling water
        CLEANUP          // Phase 7: Timer countdown before delete
    }

    // Instance state
    private boolean isHorizontal;
    private int x;
    private int y;
    private int timer;
    private boolean artLoaded;

    // Horizontal state
    private HorzPhase horzPhase = HorzPhase.Y_GUARD;

    // Vertical state
    private VertPhase vertPhase = VertPhase.PROXIMITY_CHECK;
    private int yVel;
    private int ySub;
    private boolean playersControlled;
    private boolean debrisSpawned;
    private S3kKosModuleQueue artQueue;
    private HardwareWorkHandle artHandle;
    private long artOrdinal = -1;

    // ROM render_flags bit 7 as observed by the object: Render_Sprites sets or
    // clears it after the object executed, so a routine testing it reacts one
    // frame after the sprite left the draw cull. True until a phase that
    // maintains it observes a non-drawn frame.
    private boolean drawnLastFrame = true;

    // Mapping frame for rendering
    private int mappingFrame;

    // Art key resolved at init based on subtype
    private final String artKey;

    public HCZWaterWallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZWaterWall");
        this.x = spawn.x();
        this.y = spawn.y();
        this.isHorizontal = (spawn.subtype() == 0);
        this.artKey = isHorizontal
                ? Sonic3kObjectArtKeys.HCZ_GEYSER_HORZ
                : Sonic3kObjectArtKeys.HCZ_GEYSER_VERT;
        this.mappingFrame = isHorizontal ? 0 : 1;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    /**
     * {@code Obj_HCZWaterWall} owns every delete test it has; none of them is
     * the shared {@code out_of_range} / {@code MarkObjGone} camera unload, so
     * the manager must not apply one on the object's behalf.
     *
     * <p>Auditing the whole object body
     * ({@code docs/skdisasm/sonic3k.asm:64836-65080}) there are exactly three
     * deletes and no range macro at all:
     * <ul>
     *   <li>{@code HCZWaterWall_Horizontal_CheckPlayerY} (:64845-64850)
     *       {@code Delete_Current_Sprite} when Player 1 {@code y_pos < $500} —
     *       a player-Y test on the first dispatch, not a camera test;</li>
     *   <li>{@code HCZWaterWall_Vertical_DeleteIfFar} (:65135-65136)
     *       {@code Delete_Sprite_If_Not_In_Range}, reached only from
     *       {@code HCZWaterWall_Vertical_WaitPlayer} — modelled in
     *       {@link #updateVertProximityCheck};</li>
     *   <li>{@code HCZGeyser_ReloadEnemyArtAndDelete} (:65002-65005), the end
     *       of the 150-frame {@code HCZGeyser_CleanupDelay} countdown.</li>
     * </ul>
     * The three {@code Sprite_OnScreen_Test} tails (:64837, :64919, :64994) are
     * draw calls, not unloads.
     *
     * <p>This matters beyond the object itself:
     * {@code HCZGeyser_CleanupDelay} (:64996-65000) is a bare
     * {@code subq.w #1,$30(a0)} with no range test, and its expiry runs
     * {@code jsr (LoadEnemyArt).l} — re-queueing all four {@code PLCKosM_HCZ1}
     * archives (:64354-64359) whose VRAM the geyser sheet overwrote. The
     * horizontal geyser scrolls off screen long before that countdown ends, so
     * a shared camera unload kills the object mid-countdown and the ROM's
     * {@code Queue_Kos_Module} submissions never happen.
     */
    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    /** @see #usesCustomOutOfRangeCheck() — the ROM object has no range unload. */
    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) return;
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;

        if (isHorizontal) {
            updateHorizontal(vIntRunCount, player);
        } else {
            updateVertical(vIntRunCount, player);
        }
    }

    // =====================================================================
    // HORIZONTAL GEYSER (Subtype 0)
    // =====================================================================

    private void updateHorizontal(int vIntRunCount, AbstractPlayableSprite player) {
        switch (horzPhase) {
            case Y_GUARD -> updateHorzYGuard(player);
            case ART_LOAD -> updateHorzArtLoad(player);
            case WAIT_PROXIMITY -> updateHorzWaitProximity(player);
            case SPRAY_ANIM -> updateHorzSprayAnim(player);
            case CLEANUP -> updateHorzCleanup();
        }
    }

    /**
     * Phase 1: Y-check guard.
     * ROM: loc_2FF04 - If player 1 y_pos < 0x500, delete self.
     */
    private void updateHorzYGuard(AbstractPlayableSprite player) {
        if (player.getCentreY() < HORZ_Y_GUARD) {
            setDestroyed(true);
            return;
        }
        horzPhase = HorzPhase.ART_LOAD;
        // ROM: HCZWaterWall_Horizontal_CheckPlayerY falls through into
        // HCZWaterWall_Horizontal_QueueArt — the guard and the Queue_Kos_Module
        // call happen on the object's first execution frame.
        updateHorzArtLoad(player);
    }

    /**
     * Phase 2: Load art.
     * ROM: HCZWaterWall_Horizontal_QueueArt - Queue ArtKosM_HCZGeyserHorz,
     * then HCZWaterWall_Horizontal_WaitArt polls Kos_modules_left.
     */
    private void updateHorzArtLoad(AbstractPlayableSprite player) {
        if (!queueArtIfNeeded(
                Sonic3kConstants.ART_KOSM_HCZ_GEYSER_HORZ_ADDR)) {
            return;
        }
        artLoaded = true;
        // ROM setup (HCZWaterWall_Horizontal_Init): render_flags=4,
        // priority=$300, width=$80, height=$20, timer $30 = $20
        timer = HORZ_INITIAL_TIMER;
        horzPhase = HorzPhase.WAIT_PROXIMITY;
        // ROM: HCZWaterWall_Horizontal_Init falls through into
        // HCZWaterWall_Horizontal_WaitPlayer — the proximity check runs on the
        // same frame the art-wait completes.
        updateHorzWaitProximity(player);
    }

    /**
     * Phase 4: Wait for player proximity.
     * ROM: loc_2FF7C - Checks player1 x_pos - 0x60 >= object x_pos.
     * When triggered, plays SFX and spawns 8 debris children.
     */
    private void updateHorzWaitProximity(AbstractPlayableSprite player) {
        int playerX = player.getCentreX();
        if (playerX - HORZ_PLAYER_TRIGGER_OFFSET < x) {
            // Not yet in range - check if off-screen for deletion
            if (!isOnScreenX(0x100)) {
                setDestroyed(true);
            }
            return;
        }

        // Triggered! Play geyser SFX
        try {
            services().playSfx(Sonic3kSfx.GEYSER.id);
        } catch (Exception e) {
            LOG.fine(() -> "HCZWaterWall: Failed to play SFX: " + e.getMessage());
        }

        // Spawn 8 debris children
        for (int i = 0; i < HORZ_DEBRIS_TABLE.length; i++) {
            int[] entry = HORZ_DEBRIS_TABLE[i];
            int debrisX = x + HORZ_PLAYER_TRIGGER_OFFSET + entry[0];
            int debrisY = y + entry[1];
            int debrisFrame = 7 - i; // mapping_frame = loop counter (7 down to 0)

            WaterWallDebrisChild debris = new WaterWallDebrisChild(
                    debrisX, debrisY, entry[2], entry[3],
                    debrisFrame, artKey);
            spawnDynamicObject(debris);
        }

        horzPhase = HorzPhase.SPRAY_ANIM;
    }

    /**
     * Phase 5: Spray animation.
     * ROM: loc_3003C - Moves right 8px/frame while the timer runs, spawns a
     * spray child every frame regardless, and only hands over to the cleanup
     * countdown once render_flags bit 7 is clear — i.e. when the wall has
     * scrolled off screen, not when the timer expires.
     */
    private void updateHorzSprayAnim(AbstractPlayableSprite player) {
        // ROM: HCZWaterWall_Horizontal_Erupt — while $30 is non-zero the geyser
        // moves right 8px/frame; the move stopping does NOT end the phase.
        if (timer > 0) {
            timer--;
            x += HORZ_SPRAY_MOVE_PX;
        }

        // ROM: HCZWaterWall_Horizontal_SpawnSpray runs every frame the phase is
        // active (also consuming Random_Number), not only while moving.
        spawnHorzSprayChild();

        // ROM: HCZWaterWall_Horizontal_UpdateChildSprites tests render_flags
        // bit 7 — cleanup begins the frame after Render_Sprites' cull
        // (width_pixels=$80 / height_pixels=$20, sonic3k.asm loc_1AEA2) stops
        // drawing the sprite, not on the move timer.
        boolean wasDrawn = drawnLastFrame;
        drawnLastFrame = isWithinRenderSpriteBounds(0x80, 0x20);
        if (!wasDrawn) {
            // ROM: clr.b (Palette_cycle_counters+$00).w
            HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.setActive(false);
            timer = HORZ_CLEANUP_TIMER;
            horzPhase = HorzPhase.CLEANUP;
        }
    }

    /**
     * Phase 6: Cleanup.
     * ROM: HCZGeyser_CleanupDelay - Counts down timer, then
     * HCZGeyser_ReloadEnemyArtAndDelete calls LoadEnemyArt (re-queueing the
     * act's PLCKosM enemy archives the geyser sheet overwrote) and deletes.
     */
    private void updateHorzCleanup() {
        timer--;
        if (timer <= 0) {
            reloadEnemyArt();
            setDestroyed(true);
        }
    }

    /** ROM: jsr (LoadEnemyArt).l from HCZGeyser_ReloadEnemyArtAndDelete. */
    private void reloadEnemyArt() {
        var module = services().gameModule();
        if (module != null
                && module.getObjectArtProvider()
                instanceof Sonic3kObjectArtProvider provider) {
            provider.reloadEnemyKosArt();
        }
    }

    /**
     * Spawns a horizontal spray child (ROM: loc_301DE routine).
     * Random x offset: (random & 0xF) * 8 - 0x50, y offset: +0x18.
     * 75% main art, 25% bubble art.
     */
    private void spawnHorzSprayChild() {
        // ROM loc_3004A: one random number supplies both the x offset
        // (((rand & $F) << 3) - $50) and the animation ((rand >> 4) & 3); the
        // bubble art is used exactly when that animation is 0.
        int random = services().rng().nextWord();
        int sprayX = x + (((random & 0xF) << 3) - 0x50);
        int sprayY = y + 0x18;
        int animId = (random >> 4) & 3;
        boolean useBubbleArt = (animId == 0);

        WaterWallSprayChild spray = new WaterWallSprayChild(
                sprayX, sprayY, 0x400, 0,
                useBubbleArt, animId, artKey, 0);
        spawnDynamicObject(spray);
    }

    // =====================================================================
    // VERTICAL GEYSER (Subtype != 0)
    // =====================================================================

    private void updateVertical(int vIntRunCount, AbstractPlayableSprite player) {
        switch (vertPhase) {
            case PROXIMITY_CHECK -> updateVertProximityCheck(player);
            case ART_LOAD -> updateVertArtLoad(player);
            case RISE -> updateVertRise(player);
            case ERUPTION -> updateVertEruption(player);
            case FALLING -> updateVertFalling();
            case CLEANUP -> updateVertCleanup();
        }
    }

    /**
     * Phase 1: Player proximity check.
     * ROM: loc_30294 - both tests are 16-bit unsigned windows, so the geyser
     * only fires for dx in [-$30, +$2F] and dy in [-$40, -$31]. A symmetric
     * +/-$60 x test triggers the eruption roughly half a screen too early.
     */
    private void updateVertProximityCheck(AbstractPlayableSprite player) {
        int px = player.getCentreX();
        int py = player.getCentreY();

        int xWindow = (px + VERT_X_TRIGGER_BIAS - x) & 0xFFFF;
        int yWindow = (py + VERT_Y_TRIGGER_BIAS - y) & 0xFFFF;

        boolean xInRange = xWindow < VERT_X_TRIGGER_WINDOW;
        boolean yInRange = yWindow < VERT_Y_TRIGGER_WINDOW;

        if (xInRange && yInRange) {
            vertPhase = VertPhase.ART_LOAD;
            // Set object_control = $81 for player(s), then fall through into
            // loc_302E6 in the same tick while the queued art is pending.
            lockPlayers(player);
            updateVertArtLoad(player);
            return;
        }

        // Not in range - check for deletion
        if (!isOnScreenX(0x100)) {
            setDestroyed(true);
        }
    }

    /**
     * Phase 2: Load art + pull players up.
     * ROM: loc_302BE queues ArtKosM_HCZGeyserVert, then loc_302E6 polls
     * Kos_modules_left. While nonzero it pulls players up 8px/frame; when zero,
     * loc_302FA falls through into the first loc_30338 rise tick.
     */
    private void updateVertArtLoad(AbstractPlayableSprite player) {
        if (!queueArtIfNeeded(
                Sonic3kConstants.ART_KOSM_HCZ_GEYSER_VERT_ADDR)) {
            pullPlayersUp(player, VERT_ART_LOAD_PULL_PX);
            return;
        }

        // ROM: loc_302FA - Visual setup
        // mapping_frame = 1, timer $30 = $60, player anim = BLANK (0x1C)
        artLoaded = true;
        mappingFrame = 1;
        timer = VERT_RISE_TIMER;
        setPlayerAnim(player, Sonic3kAnimationIds.BLANK);

        vertPhase = VertPhase.RISE;
        updateVertRise(player);
    }

    private boolean queueArtIfNeeded(int sourceAddress) {
        rebindArtAfterRestore();
        try {
            if (artHandle == null) {
                artQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
                artHandle = artQueue.queue(
                        services().rom(),
                        sourceAddress,
                        Sonic3kConstants.ARTTILE_HCZ_GEYSER);
                artOrdinal = artHandle.ordinal();
                return false;
            }
            if (!artQueue.isReady(artHandle)) {
                return false;
            }
            artQueue.claim(artHandle);
            artHandle = null;
            artQueue = null;
            artOrdinal = -1;
            return true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue HCZ geyser KosM art", e);
        }
    }

    private void rebindArtAfterRestore() {
        if (artOrdinal < 0 || artQueue != null) {
            return;
        }
        artHandle = services().hardwareTiming().pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE, artOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing restored HCZ water-wall KosM job " + artOrdinal));
        artQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
    }

    /**
     * Phase 4: Rise phase.
     * ROM: loc_30338 - Object rises 8px/frame while timer > 0, players always
     * rise 8px/frame. When timer <= 0x28: transition to eruption, set player
     * anim to $1A (HURT), play SFX, spawn debris.
     */
    private void updateVertRise(AbstractPlayableSprite player) {
        // ROM: tst.w $30(a0) / beq loc_30346 / subq.w #1,$30(a0) / subq.w #8,y_pos
        // Object only moves up while timer > 0
        if (timer > 0) {
            timer--;
            y -= VERT_RISE_PX;
        }

        // ROM: subi.w #8,(Player_1+y_pos) — players ALWAYS move up
        pullPlayersUp(player, VERT_RISE_PX);

        // ROM: cmpi.w #$28,$30(a0) / bhi locret_303E8
        // Transition to eruption as soon as timer <= 0x28
        if (timer <= VERT_ERUPTION_TRIGGER && !debrisSpawned) {
            debrisSpawned = true;

            // ROM: move.b #$1A,(Player_1+anim).w — HURT animation, not SPRING
            setPlayerAnim(player, Sonic3kAnimationIds.HURT);

            // Play geyser SFX
            try {
                services().playSfx(Sonic3kSfx.GEYSER.id);
            } catch (Exception e) {
                LOG.fine(() -> "HCZWaterWall: Failed to play SFX: " + e.getMessage());
            }

            // ROM: move.b #1,(Palette_cycle_counters+$00).w
            HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.setActive(true);

            // Spawn 8 debris
            for (int i = 0; i < VERT_DEBRIS_TABLE.length; i++) {
                int[] entry = VERT_DEBRIS_TABLE[i];
                int debrisX = x + entry[0];
                int debrisY = y - 0x80 + entry[1];

                WaterWallDebrisChild debris = new WaterWallDebrisChild(
                        debrisX, debrisY, entry[2], entry[3],
                        i, artKey);
                spawnDynamicObject(debris);
            }

            // Transition to eruption phase immediately
            yVel = VERT_ERUPTION_Y_VEL;
            vertPhase = VertPhase.ERUPTION;
        }
    }

    /**
     * Phase 5: Eruption.
     * ROM: loc_3041A - Object moves up with gravity, players move up 0xA px/frame.
     * When timer reaches 0: release players, set player y_vel = -$C00.
     */
    private void updateVertEruption(AbstractPlayableSprite player) {
        // ROM: tst.w $30(a0) / beq loc_30470 — while the timer runs, y_vel is
        // re-armed to -$A00 every frame, so the accumulated $48 gravity never
        // slows the column down. Only the release frame drops it to -$800.
        if (timer != 0) {
            yVel = VERT_ERUPTION_Y_VEL;
            timer--;
            if (timer == 0) {
                // Release players
                releasePlayers(player);

                // Set player velocities: x_vel=0, y_vel=-$C00, jumping=0
                launchPlayer(player);
                for (PlayableEntity sidekickEntity : sidekickParticipants(player)) {
                    if (sidekickEntity instanceof AbstractPlayableSprite sidekick) {
                        launchPlayer(sidekick);
                    }
                }

                // Object continues falling with reduced velocity
                yVel = VERT_FALLING_Y_VEL;
                vertPhase = VertPhase.FALLING;
            }
        }

        // ROM loc_30470 runs on the release frame too, before loc_3052A takes
        // over next frame.
        pullPlayersUp(player, VERT_ERUPTION_MOVE_PX);

        // Move object with gravity
        SubpixelMotion.State motionState = new SubpixelMotion.State(x, y, 0, ySub, 0, yVel);
        SubpixelMotion.moveSprite(motionState, VERT_FALLING_GRAVITY);
        y = motionState.y;
        ySub = motionState.ySub;
        yVel = motionState.yVel;

        // Spawn spray pairs each frame
        spawnVertSprayPair();
    }

    /** ROM loc_3041A release: x_vel=0, y_vel=-$C00, jumping=0. */
    private void launchPlayer(AbstractPlayableSprite player) {
        player.setXSpeed((short) 0);
        player.setYSpeed((short) VERT_ERUPTION_PLAYER_Y_VEL);
        player.setJumping(false);
        player.setAir(true);
    }

    /**
     * Phase 6: Falling water.
     * ROM: loc_3052A - MoveSprite + gravity. Delete when off screen.
     */
    private void updateVertFalling() {
        // ROM: HCZWaterWall_Vertical_Fall tests render_flags bit 7 first —
        // the sprite falls only while Render_Sprites still drew it last frame
        // (width_pixels=$20 / height_pixels=$60, HCZWaterWall_Vertical_InitRise).
        if (!drawnLastFrame) {
            // ROM: clr.b (Palette_cycle_counters+$00).w
            HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.setActive(false);
            timer = VERT_CLEANUP_TIMER;
            vertPhase = VertPhase.CLEANUP;
            return;
        }

        SubpixelMotion.State motionState = new SubpixelMotion.State(x, y, 0, ySub, 0, yVel);
        SubpixelMotion.moveSprite(motionState, VERT_FALLING_GRAVITY);
        y = motionState.y;
        ySub = motionState.ySub;
        yVel = motionState.yVel;
        drawnLastFrame = isWithinRenderSpriteBounds(0x20, 0x60);
    }

    /**
     * Phase 7: Cleanup.
     * ROM: loc_30106 - Counts down timer, then deletes.
     */
    private void updateVertCleanup() {
        timer--;
        if (timer <= 0) {
            // ROM: the vertical fall path also ends in HCZGeyser_CleanupDelay ->
            // HCZGeyser_ReloadEnemyArtAndDelete (LoadEnemyArt + delete).
            reloadEnemyArt();
            setDestroyed(true);
        }
    }

    /**
     * Spawns a pair of vertical spray children.
     * ROM: sub_304DA - Random positioning, 75% main art / 25% bubble art.
     */
    private void spawnVertSprayPair() {
        // ROM loc_30470 draws ONE random number and derives both children from
        // it: the pair is mirrored about the column (x +/- $10) and shares the
        // same animation, which is also what selects the bubble art.
        int random = services().rng().nextWord();
        int sprayXVel = (random & 0xF) << 6;
        int animId = (random >> 4) & 3;
        boolean useBubbleArt = (animId == 0);

        // sub_304DA: both children start at (x_pos, y_pos - $50).
        int sprayY = y - VERT_SPRAY_Y_OFFSET;

        WaterWallSprayChild right = new WaterWallSprayChild(
                x + VERT_SPRAY_X_OFFSET, sprayY, sprayXVel, VERT_SPRAY_Y_VEL,
                useBubbleArt, animId, artKey, 0);
        spawnDynamicObject(right);

        WaterWallSprayChild left = new WaterWallSprayChild(
                x - VERT_SPRAY_X_OFFSET, sprayY, -sprayXVel, VERT_SPRAY_Y_VEL,
                useBubbleArt, animId, artKey, 0);
        spawnDynamicObject(left);
    }

    // ===== Player Control Helpers =====

    private void lockPlayers(AbstractPlayableSprite player) {
        if (playersControlled) return;
        playersControlled = true;

        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setControlLocked(true);

        for (PlayableEntity sidekickEntity : sidekickParticipants(player)) {
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick) {
                ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
                sidekick.setControlLocked(true);
            }
        }
    }

    private void releasePlayers(AbstractPlayableSprite player) {
        if (!playersControlled) return;
        playersControlled = false;

        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);

        for (PlayableEntity sidekickEntity : sidekickParticipants(player)) {
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick) {
                ObjectControlState.none().applyTo(sidekick);
                sidekick.setControlLocked(false);
            }
        }
    }

    private void pullPlayersUp(AbstractPlayableSprite player, int pixels) {
        player.setY((short) (player.getY() - pixels));

        for (PlayableEntity sidekickEntity : sidekickParticipants(player)) {
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick) {
                sidekick.setY((short) (sidekick.getY() - pixels));
            }
        }
    }

    private void setPlayerAnim(AbstractPlayableSprite player, Sonic3kAnimationIds animId) {
        // Obj_HCZWaterWall owns the native anim byte after setting
        // object_control=$81. HCZ_WaterTunnels returns immediately when that
        // bit is set, so an earlier tunnel animation must no longer remain as
        // an engine-side forced override (sonic3k.asm:8848-8850, 65161-65168,
        // 65223-65230).
        player.setForcedAnimationId(-1);
        player.setAnimationId(animId);

        for (PlayableEntity sidekickEntity : sidekickParticipants(player)) {
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick) {
                sidekick.setForcedAnimationId(-1);
                sidekick.setAnimationId(animId);
            }
        }
    }

    private List<PlayableEntity> sidekickParticipants(AbstractPlayableSprite player) {
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> player,
                () -> services().playerQuery().sidekicks());
        return query.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS).stream()
                .filter(candidate -> candidate != player)
                .toList();
    }

    // ===== Rendering =====

    /**
     * ROM parity: only the routines that actually reach {@code Draw_Sprite}
     * put the geyser in the sprite table.
     * <p>
     * Horizontal: {@code loc_2FF04}/{@code loc_2FF2A} (Y guard, art queue) and
     * {@code loc_30106} (cleanup countdown) end in {@code rts} or
     * {@code Delete_Current_Sprite}; {@code loc_2FF7C} and {@code loc_3003C}
     * both end at {@code Sprite_OnScreen_Test} -&gt; {@code Draw_Sprite}.
     * <p>
     * Vertical: {@code loc_30294} ends at {@code Delete_Sprite_If_Not_In_Range}
     * and {@code loc_302E6} / {@code loc_30338} end in {@code rts}, so the
     * column stays invisible while it waits, loads art and rises. It first
     * appears at {@code loc_3041A} (eruption) and stays visible through
     * {@code loc_3052A} (falling), then vanishes again for the cleanup
     * countdown (sonic3k.asm:65176-65190, 65238-65246).
     */
    private boolean isDrawnThisPhase() {
        if (isHorizontal) {
            return horzPhase == HorzPhase.WAIT_PROXIMITY
                    || horzPhase == HorzPhase.SPRAY_ANIM;
        }
        return vertPhase == VertPhase.ERUPTION
                || vertPhase == VertPhase.FALLING;
    }

    @Override
    public int getPriorityBucket() {
        // ROM loc_2FF32 / loc_302FA: priority $300 / $80 = 6.
        return 6;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!isDrawnThisPhase()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, x, y, false, false);
        } else {
            // Debug fallback: draw coloured box
            float r = isHorizontal ? 0.2f : 0.3f;
            float g = 0.5f;
            float b = 0.9f;
            int hw = isHorizontal ? 0x40 : 0x10;
            int hh = isHorizontal ? 0x10 : 0x30;
            appendDebugBox(commands, x, y, hw, hh, r, g, b);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw trigger zone in debug mode
        if (isHorizontal) {
            if (horzPhase == HorzPhase.WAIT_PROXIMITY) {
                int triggerX = x + HORZ_PLAYER_TRIGGER_OFFSET;
                ctx.drawRect(triggerX, y, 4, 0x20, 0.0f, 0.8f, 1.0f);
            }
        } else {
            if (vertPhase == VertPhase.PROXIMITY_CHECK) {
                // ROM trigger box: dx in [-$30, +$2F], dy in [-$40, -$31].
                int boxCentreX = x - VERT_X_TRIGGER_BIAS + (VERT_X_TRIGGER_WINDOW / 2);
                int boxCentreY = y - VERT_Y_TRIGGER_BIAS + (VERT_Y_TRIGGER_WINDOW / 2);
                ctx.drawRect(boxCentreX, boxCentreY,
                        VERT_X_TRIGGER_WINDOW / 2, VERT_Y_TRIGGER_WINDOW / 2,
                        0.0f, 0.8f, 1.0f);
            }
        }
    }

    // ===== Utility =====

    private int getWaterLevel() {
        try {
            var ws = services().waterSystem();
            if (ws != null) {
                return ws.getWaterLevelY(services().romZoneId(), services().currentAct());
            }
        } catch (Exception e) {
            LOG.fine(() -> "HCZWaterWall.getWaterLevel: " + e.getMessage());
        }
        return 0;
    }

    private static ObjectSpawn createChildSpawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x3B, 0, 0, false, y);
    }

    private static ObjectSpawn createDebrisSpawn(int x, int y, int xVel, int yVel, int initialFrame) {
        return new ObjectSpawn(x, y, 0x3B, debrisSubtype(xVel, yVel, initialFrame), 0, false, y);
    }

    private static int debrisSubtype(int xVel, int yVel, int initialFrame) {
        for (int i = 0; i < HORZ_DEBRIS_TABLE.length; i++) {
            int[] entry = HORZ_DEBRIS_TABLE[i];
            if (entry[2] == xVel && entry[3] == yVel && initialFrame == 7 - i) {
                return i;
            }
        }
        for (int i = 0; i < VERT_DEBRIS_TABLE.length; i++) {
            int[] entry = VERT_DEBRIS_TABLE[i];
            if (entry[2] == xVel && entry[3] == yVel && initialFrame == i) {
                return 0x80 | i;
            }
        }
        return initialFrame & 7;
    }

    private static ObjectSpawn createSpraySpawn(int x, int y, int xVel, int yVel,
            boolean useBubbleArt, int animId, int initialAnimTimer) {
        int subtype = (initialAnimTimer & 0x03)
                | ((animId & 0x03) << 2)
                | (useBubbleArt ? 0x40 : 0)
                | (yVel < 0 ? 0x80 : 0);
        return new ObjectSpawn(x, y, 0x3B, subtype, 0, false, xVel);
    }

    private static int signedRawYWord(ObjectSpawn spawn) {
        return (short) spawn.rawYWord();
    }

    private static void appendDebugBox(List<GLCommand> commands, int cx, int cy,
            int hw, int hh, float r, float g, float b) {
        int l = cx - hw, right = cx + hw, t = cy - hh, bot = cy + hh;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, l, t, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, right, t, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, right, t, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, right, bot, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, right, bot, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, l, bot, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, l, bot, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, l, t, 0, 0));
    }

    // =====================================================================
    // CHILD: Water Wall Debris
    // =====================================================================

    /**
     * Debris child spawned by both horizontal and vertical geysers.
     * <p>
     * ROM: loc_3011A - Animates through 8 frames, moves with gravity (0x38).
     * When y &gt; Water_level: stops y_vel, halves x_vel twice, spawns splash,
     * then continues sinking until it goes off-screen.
     */
    static class WaterWallDebrisChild extends AbstractObjectInstance implements SpawnRewindRecreatable {

        private static final int GRAVITY = 0x38;
        private static final int SLOW_GRAVITY = 8;
        private static final int ANIM_RESET_TIMER = 2;
        private enum DebrisState { FLYING, SINKING }

        private final SubpixelMotion.State motion;
        private int mappingFrame;
        private int animTimer = ANIM_RESET_TIMER;
        private DebrisState state = DebrisState.FLYING;
        private boolean splashSpawned;

        WaterWallDebrisChild(int x, int y, int xVel, int yVel,
                int initialFrame, String parentArtKey) {
            this(createDebrisSpawn(x, y, xVel, yVel, initialFrame));
        }

        private WaterWallDebrisChild(ObjectSpawn spawn) {
            super(spawn, "WaterWallDebris");
            int subtype = spawn.subtype();
            boolean vertical = (subtype & 0x80) != 0;
            int tableIndex = subtype & 7;
            int[] entry = vertical
                    ? VERT_DEBRIS_TABLE[tableIndex]
                    : HORZ_DEBRIS_TABLE[tableIndex];
            int x = spawn.x();
            int y = spawn.y();
            int xVel = entry[2];
            int yVel = entry[3];
            this.motion = new SubpixelMotion.State(x, y, 0, 0, xVel, yVel);
            this.mappingFrame = vertical ? tableIndex : 7 - tableIndex;
        }

        @Override
        public int getPriorityBucket() {
            // ROM: horizontal debris priority $380 -> 7 (loc_2FFAE),
            // vertical debris priority $280 -> 5 (loc_30390). Read back from
            // the spawn subtype so the child carries no extra rewind scalar.
            return (spawn.subtype() & 0x80) != 0 ? 5 : 7;
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

        private void updateFlying() {
            // Animate
            animTimer--;
            if (animTimer <= 0) {
                animTimer = ANIM_RESET_TIMER;
                mappingFrame = (mappingFrame + 1) & 7;
            }

            // Move with gravity
            SubpixelMotion.moveSprite(motion, GRAVITY);

            // Check water level
            int waterLevel = getWaterLevel();
            if (waterLevel > 0 && motion.y > waterLevel) {
                // Stop vertical, halve horizontal twice (asr twice)
                motion.yVel = 0;
                motion.xVel >>= 2;

                // Spawn water splash at water level
                if (!splashSpawned) {
                    splashSpawned = true;
                    WaterWallSplashChild splash = new WaterWallSplashChild(motion.x, waterLevel);
                    spawnDynamicObject(splash);
                }

                state = DebrisState.SINKING;
            }
        }

        private void updateSinking() {
            // ROM loc_301A8: keep moving with reduced gravity until the sprite
            // leaves the screen; there is no short post-splash lifetime.
            SubpixelMotion.moveSprite(motion, SLOW_GRAVITY);

            // ROM loc_301A8 ends in Sprite_OnScreen_Test.  The debris writes
            // width/height=$18, so use the sprite render bounds rather than
            // the manager's wider point-margin unload band.
            if (!isWithinRenderSpriteBounds(0x18, 0x18)) {
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
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM: Debris uses Map_HCZWaterWallDebris (8 frames, separate from main geyser).
            // Art tiles are at ArtTile_HCZGeyser+$58, registered under HCZ_GEYSER_DEBRIS.
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_GEYSER_DEBRIS);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, motion.x, motion.y, false, false);
            } else {
                appendDebugBox(commands, motion.x, motion.y, 8, 12, 0.3f, 0.6f, 1.0f);
            }
        }
    }

    // =====================================================================
    // CHILD: Water Wall Spray Particle
    // =====================================================================

    /**
     * Spray particle child.
     * <p>
     * ROM: loc_301DE - Moves with gravity (0x28).
     * When y &gt; Water_level: snaps to water level, advances anim by 4,
     * transitions to surface animation. Deletes when anim ends.
     */
    static class WaterWallSprayChild extends AbstractObjectInstance implements SpawnRewindRecreatable {

        private static final int GRAVITY = 0x28;

        private enum SprayState { FALLING, SURFACE }

        private final SubpixelMotion.State motion;
        private SprayState state = SprayState.FALLING;
        private int animId;
        private int animTimer;
        private int animFrame;
        private boolean useBubbleArt;
        private int surfaceFrameCount;

        WaterWallSprayChild(int x, int y, int xVel, int yVel,
                boolean useBubbleArt, int animId, String parentArtKey, int initialAnimTimer) {
            this(createSpraySpawn(x, y, xVel, yVel, useBubbleArt, animId, initialAnimTimer));
        }

        private WaterWallSprayChild(ObjectSpawn spawn) {
            super(spawn, "WaterWallSpray");
            int subtype = spawn.subtype();
            int xVel = signedRawYWord(spawn);
            int yVel = (subtype & 0x80) != 0 ? -0x700 : 0;
            this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, xVel, yVel);
            this.useBubbleArt = (subtype & 0x40) != 0;
            this.animId = (subtype >> 2) & 0x03;
            // ROM: freshly allocated slots start with anim_frame_timer = 0, so
            // Animate_Sprite loads the first frame on the child's first tick.
            this.animTimer = subtype & 0x03;
        }

        @Override
        public int getPriorityBucket() {
            // ROM loc_3004A / sub_304DA: spray priority $380 / $80 = 7.
            return 7;
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
                case FALLING -> updateFalling();
                case SURFACE -> updateSurface();
            }
        }

        private void updateFalling() {
            SubpixelMotion.moveSprite(motion, GRAVITY);

            int waterLevel = getWaterLevel();
            if (waterLevel > 0 && motion.y > waterLevel) {
                motion.y = waterLevel;
                // Advance to surface animation
                animId += 4;
                state = SprayState.SURFACE;
                surfaceFrameCount = 0;
            }

            // Off-screen check
            // ROM loc_301DE ends in Sprite_OnScreen_Test with width/height=$18.
            // A broad point margin keeps the vertical geyser's two-per-frame
            // spray allocation alive long after the ROM has recycled it.
            if (!isWithinRenderSpriteBounds(0x18, 0x18)) {
                setDestroyed(true);
            }
        }

        private void updateSurface() {
            // Surface animation plays through, then deletes
            animTimer--;
            if (animTimer <= 0) {
                animTimer = 3;
                animFrame++;
                surfaceFrameCount++;
                if (surfaceFrameCount > 12) {
                    setDestroyed(true);
                }
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

        /**
         * Returns the mapping frame index for the current animation state.
         * <p>
         * ROM uses Ani_HCZWaterWall animations, with 25% of spray using
         * ArtTile_Bubbles (separate bubble art). Since we don't have bubble
         * art loaded, bubble spray uses frame 2 (single tiny dot) from the
         * geyser sheet, giving a small bubble-like appearance.
         * <p>
         * Non-bubble spray: anim 0-3 map to splash effect frames.
         * Surface anims (after hitting water): smaller fade-out frames.
         */
        private int getCurrentMappingFrame() {
            if (useBubbleArt) {
                // Bubble spray: tiny single-tile dot, frame 2
                return 2;
            }
            return switch (animId) {
                case 0 -> 2;  // tiny dot
                case 1 -> 3 + (animFrame % 3); // frames 3,4,5 (splash cycle)
                case 2, 3 -> 6 + (animFrame % 2); // frames 6,7 (splash cycle)
                case 4, 5, 6, 7 -> 8; // small surface frame
                default -> 2;
            };
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM: spray art_tile = ArtTile_HCZGeyser+$30 (75%) or ArtTile_Bubbles (25%).
            // Use the spray-specific sheet (tile indices offset by $30) for non-bubble,
            // or the bubble sheet for bubble spray.
            String renderKey = useBubbleArt
                    ? Sonic3kObjectArtKeys.HCZ_BUBBLES
                    : Sonic3kObjectArtKeys.HCZ_GEYSER_SPRAY;
            PatternSpriteRenderer renderer = getRenderer(renderKey);
            if (renderer != null) {
                int frame = getCurrentMappingFrame();
                renderer.drawFrameIndex(frame, motion.x, motion.y, false, false);
            }
        }
    }

    // =====================================================================
    // CHILD: Water Wall Splash
    // =====================================================================

    /**
     * Water splash child spawned when debris hits the water surface.
     * <p>
     * ROM: loc_3023E - Uses Map_HCZWaterWall with ArtTile_HCZGeyser+$30, palette 1.
     * Animates through splash frames, then deletes.
     */
    static class WaterWallSplashChild extends AbstractObjectInstance implements SpawnRewindRecreatable {

        private static final int TOTAL_FRAMES = 8;

        private int x;
        private int y;
        private int animTimer = 3;
        private int totalFramesPlayed;

        WaterWallSplashChild(int x, int y, String parentArtKey) {
            this(createChildSpawn(x, y));
        }

        WaterWallSplashChild(int x, int y) {
            this(createChildSpawn(x, y));
        }

        private WaterWallSplashChild(ObjectSpawn spawn) {
            super(spawn, "WaterWallSplash");
            this.x = spawn.x();
            this.y = spawn.y();
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getPriorityBucket() {
            // ROM loc_30130 splash allocation: priority $200 / $80 = 4.
            return 4;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) return;

            animTimer--;
            if (animTimer <= 0) {
                animTimer = 3;
                totalFramesPlayed++;
                if (totalFramesPlayed >= TOTAL_FRAMES) {
                    setDestroyed(true);
                }
            }
        }

        /**
         * Returns mapping frame for splash animation (Ani_HCZWaterWall anim 8).
         * ROM sequence: frames 1,9,1,A,0,9,1,A,1,9,0,A,4,8,2,FC
         * Simplified: cycle through small splash frames from the geyser sheet.
         */
        private int getSplashMappingFrame() {
            // Anim 8 sequence uses geyser sheet frames 9 and 10 primarily
            return switch (totalFramesPlayed % 4) {
                case 0 -> 9;
                case 1 -> 10;
                case 2 -> 9;
                case 3 -> 10;
                default -> 9;
            };
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM: splash art_tile = ArtTile_HCZGeyser+$30, palette 1.
            // Use the spray-specific sheet (tile indices offset by $30).
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_GEYSER_SPRAY);
            if (renderer != null) {
                renderer.drawFrameIndex(getSplashMappingFrame(), x, y, false, false);
            }
        }
    }
}
