package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.SwingMotion;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $9E — Tunnelbot (MGZ Act 1).
 *
 * <p>A ceiling-drilling badnik that hovers in mid-air, detects the nearest player,
 * drills upward to the ceiling, embeds and shakes the screen while dropping debris.
 * On completion, sets {@code Level_trigger_array+8} and deletes itself.
 *
 * <p>Shares art with the MGZ Miniboss ({@code ObjDat_Tunnelbot → Map_MGZMiniboss,
 * ArtTile_MGZMiniboss}). Art is loaded by {@code PLCKosM_MGZ1} at level start.
 *
 * <p>Based on {@code Obj_Tunnelbot} (sonic3k.asm, lines 184710–185006).
 *
 * <h3>State machine:</h3>
 * <ul>
 *   <li>PATROL: Swing up/down ({@code Swing_UpAndDown}), detect player within $60 px</li>
 *   <li>DRILLING: Swing + accelerating drill animation ({@code Animate_RawGetFaster}),
 *       4 full loops then transition</li>
 *   <li>TUNNELING: Rise 1 px/frame while animating, stop at ceiling</li>
 *   <li>SHAKING: Embedded in ceiling, vibrate ±1 px, spawn debris every 8 frames,
 *       play {@code sfx_Rumble2}, screen shake active for $BF (191) frames</li>
 * </ul>
 *
 * <h3>Collision:</h3>
 * Main body: {@code collision_flags = $10} (non-enemy, size 16). Effectively
 * invincible ({@code collision_property = $FE}). Two invisible arm children at
 * offsets (-$1C, -$16) and (+$1C, -$16) with badnik collision ({@code $9E}).
 *
 * <h3>Key differences from MGZ Miniboss:</h3>
 * <ul>
 *   <li>Uses {@code Obj_WaitOffscreen} (offscreen gating)</li>
 *   <li>{@code collision_property = -2} (effectively invincible)</li>
 *   <li>Player detection via {@code Find_SonicTails} at range $60</li>
 *   <li>Shake duration $BF (191 frames) vs miniboss $7F (127 frames)</li>
 *   <li>Post-shake: sets Level_trigger_array+8, deletes self</li>
 *   <li>No spire debris (bit 1 of $38 not set)</li>
 * </ul>
 */
public final class TunnelbotBadnikInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {

    // ── Constants from ObjDat_Tunnelbot ──────────────────────────────────

    // collision_flags = $10: non-enemy collision, size index 16
    private static final int COLLISION_SIZE_INDEX = 0x10;

    // dc.w $280 → priority bucket 5
    private static final int PRIORITY_BUCKET = 5;

    // y_radius = $28 (40 pixels)
    private static final int Y_RADIUS = 0x28;

    // width_pixels = $28 (40 pixels), height_pixels = $0C (12 pixels)
    private static final int WIDTH_PIXELS = 0x28;

    // ── Swing_Setup1 constants ───────────────────────────────────────────

    // y_vel initial and max amplitude: $C0 (192)
    private static final int SWING_MAX_VELOCITY = 0xC0;

    // $40: acceleration step per frame: $10 (16)
    private static final int SWING_ACCELERATION = 0x10;

    // ── Detection ────────────────────────────────────────────────────────

    // loc_884A2: cmpi.w #$60,d2 — player detection range
    private static final int DETECT_RANGE = 0x60;

    // ── Animation data ───────────────────────────────────────────────────

    // byte_88B73: Animate_RawGetFaster format
    // dc.b 5, 4, 0, 1, 2, $FC
    private static final int DRILL_ANIM_INITIAL_DELAY = 5;
    private static final int DRILL_ANIM_LOOP_COUNT = 4;
    private static final int[] DRILL_ANIM_FRAMES = {0, 1, 2};

    // byte_88B79: Animate_Raw format
    // dc.b 0, 0, 1, 2, $FC
    private static final int TUNNEL_ANIM_DELAY = 0;
    private static final int[] TUNNEL_ANIM_FRAMES = {0, 1, 2};

    // ── Shake phase ──────────────────────────────────────────────────────

    // loc_88514: move.w #$BF,$2E(a0) — shake timer
    private static final int SHAKE_DURATION = 0xBF;

    // Debris spawn interval: every 8 frames (ROM: andi.b #7,d1)
    private static final int DEBRIS_INTERVAL = 8;

    // Level_trigger_array+8 index
    private static final int LEVEL_TRIGGER_INDEX = 8;

    // Obj_WaitOffscreen installs Map_Offscreen with width/height $20 while it
    // waits for Render_Sprites to set render_flags bit 7 (sonic3k.asm:180266-180298).
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;

    // ── Hit flash (sub_88A62) ───────────────────────────────────────────

    // ROM: move.b #$20,$20(a0) — flash duration 32 frames
    private static final int HIT_FLASH_DURATION = 0x20;

    // Palette line 1 (engine 0-based), colors 12-14.
    // ROM: Normal_palette_line_2 (1-based naming) + $18/$1A/$1C
    private static final int FLASH_PALETTE_LINE = 1;
    private static final int FLASH_COLOR_START = 12;
    private static final int FLASH_COLOR_COUNT = 3;
    private static final int[] FLASH_COLOR_INDICES = {12, 13, 14};
    private static final int[] FLASH_WHITE_WORDS = {0x0EEE, 0x0EEE, 0x0EEE};

    // ── Arm child offsets (ChildObjDat_88B2C) ────────────────────────────

    private static final int ARM_LEFT_X_OFFSET = -0x1C;
    private static final int ARM_RIGHT_X_OFFSET = 0x1C;
    private static final int ARM_Y_OFFSET = -0x16;

    // Arm collision_flags = $9E (badnik type 0x80 | size 0x1E)
    private static final int ARM_COLLISION_FLAGS = 0x9E;

    // ── Debris constants (ObjDat3_88B02) ─────────────────────────────────

    // Debris uses frames 0-3 from Map_MGZEndBossDebris
    private static final int DEBRIS_FRAME_COUNT = 4;

    // MoveDraw_SpriteTimed2 timeout: $5F (95 frames)
    private static final int DEBRIS_LIFETIME = 0x5F;

    // Debris priority bucket
    private static final int DEBRIS_PRIORITY = 4;

    // ── State ────────────────────────────────────────────────────────────

    // ROM routine counter values: 0=Init, 2=Patrol, 4=Drilling, 6=Tunneling, 8=Shaking
    private enum State {
        INIT,       // Routine 0: SetUp_ObjAttributes, Swing_Setup1, CreateChild1_Normal
        PATROL,     // Routine 2: swing + detect player
        DRILLING,   // Routine 4: swing + accelerating animation
        TUNNELING,  // Routine 6: rise to ceiling + animate
        SHAKING     // Routine 8: vibrate in ceiling, spawn debris
    }

    private State state = State.INIT;

    // Position and motion
    private int currentX;
    private int currentY;
    private int yVelocity;
    private int ySubpixel;
    private boolean swingDirectionDown; // ROM: bit 0 of $38

    // Animation state
    private int mappingFrame;
    private int animFrameIndex;
    private int animTimer;

    // Animate_RawGetFaster state
    private int drillDelay;         // ROM: $2E — current delay between frames (decreases)
    private int drillLoopCounter;   // ROM: $2F — completed loops

    // Shake state
    private int shakeTimer;         // ROM: $2E during shake phase

    // Hit flash state (sub_88A62)
    private int hitFlashTimer;      // ROM: $20(a0) — counts down from $20 (32)
    private boolean hitFlashActive;
    private final int[] savedFlashSegaWords = new int[FLASH_COLOR_COUNT];

    // Children
    private TunnelbotArm leftArm;
    private TunnelbotArm rightArm;

    private boolean waitingForOnscreen = true;

    // Frame counter from update (for vibration)
    private int vIntRunCount;

    public TunnelbotBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Tunnelbot");
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // Swing_Setup1: y_vel = $C0, direction = up (bit 0 clear)
        this.yVelocity = SWING_MAX_VELOCITY;
        this.swingDirectionDown = false;
    }

    private void attachArmForRewind(TunnelbotArm arm) {
        if (arm.xOffset < 0) {
            leftArm = arm;
        } else {
            rightArm = arm;
        }
    }

    private static TunnelbotBadnikInstance findLiveParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectManager() == null) {
            return null;
        }
        TunnelbotBadnikInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectManager().getActiveObjects()) {
            if (!(instance instanceof TunnelbotBadnikInstance tunnelbot) || tunnelbot.isDestroyed()) {
                continue;
            }
            long dx = tunnelbot.getX() - ctx.spawn().x();
            long dy = tunnelbot.getY() - ctx.spawn().y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tunnelbot;
            }
        }
        return best;
    }

    /**
     * ROM: Obj_Tunnelbot → routine dispatch → sub_88A62 → Sprite_CheckDeleteTouch.
     * <p>
     * After Obj_WaitOffscreen activates the object, Sprite_CheckDeleteTouch at the
     * end handles deletion when the object is too far from the camera. The engine's
     * ObjectManager only checks X distance for the spawn window; we add the Y bounds
     * check from Sprite_CheckDeleteTouchXY (sonic3k.asm:178982-178986) to prevent
     * Tunnelbots on a different vertical path from triggering while far off-screen:
     * <pre>
     *     move.w  y_pos(a0),d0
     *     sub.w   (Camera_Y_pos).w,d0
     *     addi.w  #$80,d0
     *     cmpi.w  #$200,d0
     *     bhi.w   Go_Delete_Sprite
     * </pre>
     */
    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        this.vIntRunCount = vIntRunCount;

        // ROM: Obj_Tunnelbot begins with jsr (Obj_WaitOffscreen).l
        // (sonic3k.asm:184710-184717). Obj_WaitOffscreen keeps the SST slot
        // alive as loc_85AD2 and suppresses the object's routine, hit-flash
        // helper, and child allocation until render_flags bit 7 is set
        // (sonic3k.asm:180266-180298). This preserves the ROM's delayed
        // Tunnelbot activation after the placement loader has reserved a slot.
        if (waitingForOnscreen) {
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                return;
            }
            // loc_85B02 restores the saved operation pointer and immediately
            // returns; the restored Tunnelbot routine runs on the next object
            // pass, so child allocation is one frame after the visible wrapper.
            waitingForOnscreen = false;
            return;
        }

        // Y bounds check matching Sprite_CheckDeleteTouchXY (sonic3k.asm:178982).
        // Prevents Tunnelbots at a vertically distant path from running their logic.
        // Range: Camera_Y - $80 to Camera_Y + $180 (512px window).
        if (!isWithinVerticalBounds()) {
            return;
        }

        switch (state) {
            case INIT -> {
                // Routine 0 (loc_88480): SetUp_ObjAttributes advances routine to 2.
                spawnArmChildren();
                state = State.PATROL;
            }
            case PATROL -> updatePatrol((AbstractPlayableSprite) playerEntity);
            case DRILLING -> updateDrilling();
            case TUNNELING -> updateTunneling();
            case SHAKING -> updateShaking();
        }

        // ROM: bsr.w sub_88A62 — hit flash runs every frame after routine dispatch
        updateHitFlash();
    }

    /**
     * Sprite_CheckDeleteTouchXY Y bounds (sonic3k.asm:178982-178986).
     * <pre>
     *     (y_pos - Camera_Y + $80) unsigned <= $200
     * </pre>
     * Returns true if the object is within ~512px vertically of the camera.
     */
    private boolean isWithinVerticalBounds() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) return false;
        int cameraY = svc.camera().getY();
        int d0 = (currentY - cameraY + 0x80) & 0xFFFF; // unsigned 16-bit
        return d0 <= 0x200;
    }

    // ── Routine 2: Patrol — swing and detect player ─────────────────────

    /**
     * Swing up/down and scan for nearest player within detection range.
     * <pre>
     * loc_884A2:
     *     jsr (Swing_UpAndDown).l
     *     jsr (MoveSprite2).l
     *     jsr Find_SonicTails(pc)
     *     cmpi.w #$60,d2
     *     bhs.w locret_884D0
     *     move.b #4,routine(a0)
     * </pre>
     */
    private void updatePatrol(AbstractPlayableSprite player) {
        updateSwing();
        applyVelocity();

        int distance = findNearestPlayerXDistance(player);
        if (distance < DETECT_RANGE) {
            // Transition to DRILLING (routine 4)
            // ROM: Animate_RawGetFaster initializes $2E from byte_88B73[0] = 5,
            // but anim_frame_timer is 0 from init — first subq wraps to $FF,
            // so the first frame advances immediately.
            state = State.DRILLING;
            drillDelay = DRILL_ANIM_INITIAL_DELAY;
            drillLoopCounter = 0;
            animFrameIndex = 0;
            animTimer = 0;
            mappingFrame = DRILL_ANIM_FRAMES[0];
        }
    }

    // ── Routine 4: Drilling — swing with accelerating animation ─────────

    /**
     * Continue swinging while playing drill animation with decreasing delay.
     * <pre>
     * loc_884D2:
     *     jsr (Swing_UpAndDown).l
     *     jsr (MoveSprite2).l
     *     jmp Animate_RawGetFaster(pc)
     * </pre>
     */
    private void updateDrilling() {
        updateSwing();
        applyVelocity();
        animateRawGetFaster();
    }

    /**
     * Port of Animate_RawGetFaster (sonic3k.asm:177749).
     * Plays animation frames with decreasing delay per loop.
     * After DRILL_ANIM_LOOP_COUNT complete loops, transitions to TUNNELING.
     */
    private void animateRawGetFaster() {
        animTimer--;
        if (animTimer >= 0) return;

        // Advance to next frame
        animFrameIndex++;
        if (animFrameIndex >= DRILL_ANIM_FRAMES.length) {
            // Loop complete — decrease delay
            animFrameIndex = 0;
            if (drillDelay > 0) {
                drillDelay--;
            } else {
                // Delay reached 0 — increment loop counter
                drillLoopCounter++;
                if (drillLoopCounter >= DRILL_ANIM_LOOP_COUNT) {
                    // All loops done — transition to TUNNELING (loc_884E2)
                    drillLoopCounter = 0;
                    transitionToTunneling();
                    return;
                }
            }
        }

        mappingFrame = DRILL_ANIM_FRAMES[animFrameIndex];
        animTimer = drillDelay;
    }

    /**
     * Callback loc_884E2: set up tunneling phase.
     * <pre>
     * loc_884E2:
     *     move.b #6,routine(a0)
     *     move.l #byte_88B79,$30(a0)
     *     move.l #loc_88514,$34(a0)
     * </pre>
     */
    private void transitionToTunneling() {
        state = State.TUNNELING;
        // ROM: callback loc_884E2 only sets routine, $30 (anim ptr), and $34 (callback).
        // anim_frame and anim_frame_timer carry over from Animate_RawGetFaster completion
        // (both are 0). First Animate_Raw call: subq wraps timer to $FF (negative),
        // advances anim_frame 0→1, reads byte_88B79[2] = 1 → mapping_frame = 1.
        animFrameIndex = 0;
        animTimer = 0;
        // Don't set mappingFrame — first animateRaw() call will advance to frame 1
    }

    // ── Routine 6: Tunneling — rise to ceiling ──────────────────────────

    /**
     * Rise 1 pixel per frame while animating. Stop at ceiling.
     * <pre>
     * loc_884FA:
     *     jsr Animate_Raw(pc)
     *     subq.w #1,y_pos(a0)
     *     jsr (ObjCheckCeilingDist).l
     *     tst.w d1
     *     bpl.s locret_88512
     * </pre>
     */
    private void updateTunneling() {
        animateRaw();
        currentY -= 1; // subq.w #1,y_pos(a0)

        // ObjCheckCeilingDist (docs/skdisasm/sonic3k.asm:20351-20366) reaches the
        // ceiling through FindFloor with the caller's `eori.w #$F,d2` low-nibble
        // transform, which is what checkNativeUpwardCeilingDist models -- the
        // legacy checkCeilingDist entry is the S1/S2 object-ceiling contract and
        // reports one pixel more clearance here, costing an extra rise frame and
        // leaving the whole rumble ladder 1px low. MgzMinibossInstance, which
        // ports this same TunnelbotMiniboss_CeilingRise routine, already uses the
        // native probe.
        TerrainCheckResult result = ObjectTerrainUtils.checkNativeUpwardCeilingDist(
                currentX, currentY, Y_RADIUS);
        if (result.distance() < 0) {
            // Ceiling reached — transition to shaking (loc_88514)
            transitionToShaking();
        }
    }

    /**
     * Port of Animate_Raw: simple frame cycling with constant delay.
     * byte_88B79: dc.b 0, 0, 1, 2, $FC
     */
    private void animateRaw() {
        animTimer--;
        if (animTimer >= 0) return;

        animFrameIndex++;
        if (animFrameIndex >= TUNNEL_ANIM_FRAMES.length) {
            animFrameIndex = 0; // $FC: loop back
        }
        mappingFrame = TUNNEL_ANIM_FRAMES[animFrameIndex];
        animTimer = TUNNEL_ANIM_DELAY;
    }

    /**
     * Callback loc_88514: begin shaking phase.
     * <pre>
     * loc_88514:
     *     move.b #8,routine(a0)
     *     st (Screen_shake_flag).w
     *     move.w #$BF,$2E(a0)
     * </pre>
     */
    private void transitionToShaking() {
        state = State.SHAKING;
        shakeTimer = SHAKE_DURATION;
        setScreenShake(true);
    }

    // ── Routine 8: Shaking — vibrate and spawn debris ───────────────────

    /**
     * Vibrate in the ceiling, spawn debris every 8 frames, play rumble SFX.
     * <pre>
     * loc_8852E:
     *     jsr Animate_Raw(pc)
     *     moveq #-2,d0
     *     move.b (V_int_run_count+3).w,d1
     *     btst #0,d1
     *     beq.s loc_88540
     *     moveq #1,d0
     * loc_88540:
     *     add.w d0,y_pos(a0)
     *     andi.b #7,d1
     *     bne.s loc_88556
     *     moveq #signextendB(sfx_Rumble2),d0
     *     jsr (Play_SFX).l
     *     bsr.w sub_88A32
     * loc_88556:
     *     jmp Obj_Wait(pc)
     * </pre>
     */
    private void updateShaking() {
        animateRaw();

        // Vibrate: odd frames +1, even frames -2
        int d1 = vIntRunCount & 0xFF;
        int dy = ((d1 & 1) != 0) ? 1 : -2;
        currentY += dy;

        // Every 8 frames: play rumble SFX and spawn debris
        if ((d1 & 7) == 0) {
            services().playSfx(Sonic3kSfx.RUMBLE_2.id);
            spawnDebris();
        }

        // Apply screen shake offset (alternating pattern)
        applyShakeOffset();

        // Obj_Wait: subq.w #1,$2E(a0); bmi.s — branches when timer goes negative
        shakeTimer--;
        if (shakeTimer < 0) {
            onShakeComplete();
        }
    }

    /**
     * Callback loc_8855A: shake complete — clean up and delete.
     * <pre>
     * loc_8855A:
     *     clr.w (Screen_shake_flag).w
     *     st (Level_trigger_array+8).w
     *     jmp (Go_Delete_Sprite).l
     * </pre>
     */
    private void onShakeComplete() {
        setScreenShake(false);
        Sonic3kLevelTriggerManager.setAll(LEVEL_TRIGGER_INDEX);
        destroyArms();
        setDestroyed(true);
    }

    // ── Swing motion ────────────────────────────────────────────────────

    private void updateSwing() {
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCELERATION, yVelocity, SWING_MAX_VELOCITY, swingDirectionDown);
        yVelocity = result.velocity();
        swingDirectionDown = result.directionDown();
    }

    /** MoveSprite2: apply velocity to position (16:8 fixed-point, no gravity). */
    private void applyVelocity() {
        int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
        yPos24 += yVelocity;
        currentY = yPos24 >> 8;
        ySubpixel = yPos24 & 0xFF;
    }

    // ── Player detection ────────────────────────────────────────────────

    /**
     * Find nearest player by horizontal distance.
     * ROM: {@code Find_SonicTails} — returns abs X distance to closest player.
     */
    private int findNearestPlayerXDistance(AbstractPlayableSprite mainPlayer) {
        ObjectServices svc = tryServices();
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> svc != null ? svc.playerQuery().sidekicks() : List.of());
        return query.nearestByRomX(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS,
                currentX,
                TunnelbotBadnikInstance::isLivePlayable).distance();
    }

    private static boolean isLivePlayable(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite && !sprite.getDead();
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Sprite_CheckDeleteTouch runs after Tunnelbot's movement routine and
        // adds the SST address to Collision_response_list. The following player
        // pass dereferences that live x_pos/y_pos, not the older pre-movement
        // coordinate retained by the engine's generic snapshot.
        // sonic3k.asm:184710-184723,20656-20708,21200-21210.
        return true;
    }

    // ── Screen shake ────────────────────────────────────────────────────

    /**
     * ROM {@code st (Screen_shake_flag).w} (docs/skdisasm/sonic3k.asm:184784,
     * :184886, :184907) — the Tunnelbot only raises the continuous-shake flag.
     * The offset itself is {@code ShakeScreen_Setup}'s
     * {@code ScreenShakeArray2[Level_frame_counter & $3F]}
     * (sonic3k.asm:104200-104209), computed once per frame by the zone's
     * background event, not by this object. Indexing the table here with the
     * object clock ({@code V_int_run_count}) de-phased the shake by the
     * accumulated lag count, which is not the clock the ROM routine reads.
     */
    private void applyShakeOffset() {
        MgzZoneRuntimeState mgzHandler = resolveMgzRuntimeState();
        if (mgzHandler == null) return;
        mgzHandler.requestContinuousScreenShake();
    }

    private void setScreenShake(boolean active) {
        if (!active) {
            MgzZoneRuntimeState handler = resolveMgzRuntimeState();
            if (handler != null) {
                handler.clearScreenShakeOffset();
            }
        }
    }

    private MgzZoneRuntimeState resolveMgzRuntimeState() {
        ObjectServices svc = tryServices();
        if (svc == null) return null;
        if (svc.zoneRuntimeRegistry() == null) return null;
        return S3kRuntimeStates.currentMgz(svc.zoneRuntimeRegistry()).orElse(null);
    }

    // ── Child spawning ──────────────────────────────────────────────────

    /**
     * Spawn two invisible arm collision children.
     * <pre>
     * ChildObjDat_88B2C:
     *     dc.w 2-1
     *     dc.l loc_887F6
     *     dc.b -$1C,-$16
     *     dc.l loc_887F6
     *     dc.b  $1C,-$16
     * </pre>
     */
    private void spawnArmChildren() {
        leftArm = spawnChild(() -> new TunnelbotArm(spawn, ARM_LEFT_X_OFFSET, ARM_Y_OFFSET));
        rightArm = spawnChild(() -> new TunnelbotArm(spawn, ARM_RIGHT_X_OFFSET, ARM_Y_OFFSET));
    }

    private void destroyArms() {
        if (leftArm != null) leftArm.setDestroyed(true);
        if (rightArm != null) rightArm.setDestroyed(true);
    }

    /**
     * Spawn a debris child at a random position above the camera.
     * <pre>
     * sub_88A32 → ChildObjDat_88B3A → loc_88820:
     *     Random frame 0-3
     *     Random X within $1FF range from Camera_X_pos - $40
     *     Y at Camera_Y_pos - $20
     *     MoveDraw_SpriteTimed2 with timeout $5F
     * </pre>
     */
    private void spawnDebris() {
        var camera = services().camera();
        if (camera == null) return;

        var rng = services().rng();
        // ROM: random X within $1FF range from Camera_X - $40
        int debrisX = camera.getX() - 0x40 + rng.nextInt(0x200);
        // ROM: Camera_Y_pos - $20
        int debrisY = camera.getY() - 0x20;
        int frame = rng.nextInt(DEBRIS_FRAME_COUNT);

        spawnChild(() -> new TunnelbotDebris(spawn, debrisX, debrisY, frame));
    }

    // ── Collision (main body) ───────────────────────────────────────────

    @Override
    public int getCollisionFlags() {
        // ROM: sub_88A62 clears collision_flags during flash, restores after.
        // While flashing, return 0 to prevent re-triggering.
        if (hitFlashActive) return 0;
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getCollisionProperty() {
        // collision_property = -2 ($FE): effectively invincible
        return 0xFE;
    }

    /**
     * ROM: sub_88A62 hit handler. Player attack triggers flash + SFX.
     * With collision_property=$FE the Tunnelbot never reaches defeat.
     */
    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (hitFlashActive) return;
        hitFlashActive = true;
        hitFlashTimer = HIT_FLASH_DURATION;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        savePaletteColors();
    }

    // ── Hit flash (sub_88A62) ───────────────────────────────────────────

    /**
     * ROM: sub_88A62 runs every frame after routine dispatch.
     * Toggles palette line 1 colors 12-14 between white ($EEE) and normal.
     * Flash lasts $20 (32) frames, then restores collision_flags.
     */
    private void updateHitFlash() {
        if (!hitFlashActive) return;

        hitFlashTimer--;
        if (hitFlashTimer <= 0) {
            // Flash done — restore palette and re-enable collision
            restorePaletteColors();
            hitFlashActive = false;
            return;
        }

        // ROM: bchg #6,status(a0) — toggle between white and normal every frame
        boolean whiteFrame = (hitFlashTimer & 1) != 0;
        applyFlashPalette(whiteFrame);
    }

    private void savePaletteColors() {
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= FLASH_PALETTE_LINE) return;
        var palette = level.getPalette(FLASH_PALETTE_LINE);
        for (int i = 0; i < FLASH_COLOR_COUNT; i++) {
            var c = palette.getColor(FLASH_COLOR_START + i);
            savedFlashSegaWords[i] = segaWordFromColor(c);
        }
    }

    private void restorePaletteColors() {
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= FLASH_PALETTE_LINE) return;
        applyFlashColors(savedFlashSegaWords);
    }

    private void applyFlashPalette(boolean white) {
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= FLASH_PALETTE_LINE) return;
        if (white) {
            // ROM: move.w #$EEE → white
            applyFlashColors(FLASH_WHITE_WORDS);
        } else {
            // Restore normal colors
            applyFlashColors(savedFlashSegaWords);
        }
    }

    private void applyFlashColors(int[] segaWords) {
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.MGZ_TUNNELBOT,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                FLASH_PALETTE_LINE,
                FLASH_COLOR_INDICES,
                segaWords);
    }

    private static int segaWordFromColor(Palette.Color color) {
        int r3 = quantizeSegaComponent(color.r);
        int g3 = quantizeSegaComponent(color.g);
        int b3 = quantizeSegaComponent(color.b);
        return (b3 << 9) | (g3 << 5) | (r3 << 1);
    }

    private static int quantizeSegaComponent(byte component) {
        return (((component & 0xFF) * 7) + 127) / 255;
    }

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) return;
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.MGZ_TUNNELBOT);
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(com.openggf.debug.DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, WIDTH_PIXELS / 2, Y_RADIUS,
                0.0f, 1.0f, 1.0f);
        ctx.drawWorldLabel(currentX, currentY, -2,
                "Tunnelbot:" + state.name(), com.openggf.debug.DebugColor.CYAN);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Arm child: invisible collision proxy
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Invisible collision proxy following the Tunnelbot parent.
     * <pre>
     * loc_887F6 → loc_88804:
     *     collision_flags = $9E (badnik touch response)
     *     Refresh_ChildPositionAdjusted each frame
     *     Add_SpriteToCollisionResponseList
     *     Delete when parent bit 7 set
     * </pre>
     */
    private final class TunnelbotArm extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseAttackable, RewindRecreatable {

        private int xOffset;
        private int yOffset;
        private int armX;
        private int armY;

        TunnelbotArm(ObjectSpawn ownerSpawn, int xOffset, int yOffset) {
            super(ownerSpawn, "TunnelbotArm");
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.armX = currentX + xOffset;
            this.armY = currentY + yOffset;
        }

        private TunnelbotArm(int xOffset, int yOffset) {
            this(
                    TunnelbotBadnikInstance.this.spawn,
                    xOffset != 0 ? xOffset : ARM_RIGHT_X_OFFSET,
                    yOffset != 0 ? yOffset : ARM_Y_OFFSET);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            TunnelbotBadnikInstance parent = findLiveParentForRewind(ctx);
            if (parent == null) {
                return null;
            }
            ObjectSpawn capturedSpawn = ctx.spawn() != null ? ctx.spawn() : parent.spawn;
            int xOffset = capturedSpawn.x() < parent.getX() ? ARM_LEFT_X_OFFSET : ARM_RIGHT_X_OFFSET;
            TunnelbotArm restored = parent.new TunnelbotArm(capturedSpawn, xOffset, ARM_Y_OFFSET);
            parent.attachArmForRewind(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // Check if parent is destroyed
            if (TunnelbotBadnikInstance.this.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            // Refresh_ChildPositionAdjusted: follow parent with offsets
            armX = currentX + xOffset;
            armY = currentY + yOffset;
        }

        @Override
        public int getCollisionFlags() {
            // collision_flags = $9E (badnik collision: 0x80 | 0x1E)
            return ARM_COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
            // Arms are attackable — player bounces off but arm is not destroyed
            // (in ROM, the parent's sub_88A62 handles the hit, not the arm)
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(armX, armY);
        }

        @Override
        public int getX() {
            return armX;
        }

        @Override
        public int getY() {
            return armY;
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY_BUCKET;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible collision proxy — no rendering
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Debris child: gravity-affected falling piece
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Falling debris spawned during the shaking phase.
     * <pre>
     * loc_88820 → ObjDat3_88B02:
     *     Map_MGZEndBossDebris, ArtTile_MGZMiniBossDebris ($0570), palette 2
     *     Random frame 0-3
     *     MoveDraw_SpriteTimed2 with timeout $5F (95 frames)
     * </pre>
     */
    private static final class TunnelbotDebris extends AbstractObjectInstance
            implements RewindRecreatable {

        // MoveSprite_LightGravity (sonic3k.asm:178352): moveq #$20,d1
        private static final int GRAVITY = 0x20;

        private int debrisX;
        private int debrisY;
        private int xVelocity;
        private int yVelocity;
        private int xSubpixel;
        private int ySubpixel;
        private int frame;
        private int lifetime;

        TunnelbotDebris(ObjectSpawn ownerSpawn, int x, int y, int frame) {
            super(ownerSpawn, "TunnelbotDebris");
            this.debrisX = x;
            this.debrisY = y;
            this.frame = frame;
            this.lifetime = DEBRIS_LIFETIME;
            // No initial velocity — debris just falls with gravity
            this.xVelocity = 0;
            this.yVelocity = 0;
        }

        private TunnelbotDebris() {
            this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn capturedSpawn = ctx.spawn();
            int x = capturedSpawn != null ? capturedSpawn.x() : 0;
            int y = capturedSpawn != null ? capturedSpawn.y() : 0;
            return new TunnelbotDebris(
                    capturedSpawn != null ? capturedSpawn : new ObjectSpawn(0, 0, 0, 0, 0, false, 0),
                    x,
                    y,
                    0);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // MoveSprite: apply gravity and velocity
            yVelocity += GRAVITY;

            int xPos24 = (debrisX << 8) | (xSubpixel & 0xFF);
            int yPos24 = (debrisY << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            debrisX = xPos24 >> 8;
            debrisY = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;

            // MoveDraw_SpriteTimed2: subq.w #1,$2E(a0); bmi — delete when negative
            lifetime--;
            if (lifetime < 0) {
                setDestroyed(true);
            }
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(debrisX, debrisY);
        }

        @Override
        public int getX() {
            return debrisX;
        }

        @Override
        public int getY() {
            return debrisY;
        }

        @Override
        public int getPriorityBucket() {
            return DEBRIS_PRIORITY;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager rm = services().renderManager();
            if (rm == null) return;
            PatternSpriteRenderer renderer = rm.getRenderer(
                    Sonic3kObjectArtKeys.MGZ_TUNNELBOT_DEBRIS);
            if (renderer == null || !renderer.isReady()) return;
            renderer.drawFrameIndex(frame, debrisX, debrisY, false, false);
        }
    }
}
