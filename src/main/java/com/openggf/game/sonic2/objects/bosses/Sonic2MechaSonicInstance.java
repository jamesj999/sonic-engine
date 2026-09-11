package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.function.Supplier;

/**
 * DEZ Silver Sonic / Mecha Sonic (Object 0xAF).
 * ROM Reference: s2.asm ObjAF (Silver Sonic)
 *
 * First boss of Death Egg Zone. A robotic hedgehog that performs dashing,
 * jumping, and spikeball attacks in a circular pattern of 16 attack slots.
 *
 * State Machine (main routine):
 * - 0: Init (set radii, spawn children, HP=8)
 * - 2: Wait for Camera_X >= $224, lock camera, boss_id=9, fade music
 * - 4: 60-frame countdown, then play boss music
 * - 6: Descend at constant y_vel=$100, fire sound every 32 frames
 * - 8: Idle on ground (100-frame timer), buzz at 50 remaining
 * - A: Attack dispatch (reads from 16-entry attack table)
 * - C: Defeat (256 iterations of explosions, then unlock camera)
 *
 * Animation: AnimateSprite_Checked is NOT called every frame. The ROM calls
 * it only in specific routines/phases: idle (routine 8 at loc_3986A) and
 * specific attack sub-phases. Descent (routine 6) does not animate at all.
 */
public class Sonic2MechaSonicInstance extends AbstractBossInstance implements RewindRecreatable {

    // State machine routine constants
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_WAIT_CAMERA = 0x02;
    private static final int ROUTINE_COUNTDOWN = 0x04;
    private static final int ROUTINE_DESCEND = 0x06;
    private static final int ROUTINE_IDLE = 0x08;
    private static final int ROUTINE_ATTACK = 0x0A;
    private static final int ROUTINE_DEFEAT = 0x0C;

    // Attack types (routine_secondary values from attack table)
    private static final int ATTACK_DASH_ACROSS = 0x00;
    private static final int ATTACK_AIM_AND_DASH = 0x06;
    private static final int ATTACK_AIM_DASH_WALK = 0x10;
    private static final int ATTACK_AIM_JUMP_SPIKEBALLS = 0x1E;

    // Attack pattern table (16 entries, circular via & 0x0F)
    // ROM: byte_398B0
    private static final int[] ATTACK_TABLE = {
            0x06, 0x00, 0x10, 0x06, 0x06, 0x1E, 0x00, 0x10,
            0x06, 0x06, 0x10, 0x06, 0x00, 0x06, 0x10, 0x1E
    };

    // Position constants
    /** Y radius for terrain collision (ROM: move.b #$1B,y_radius(a0)) */
    private static final int Y_RADIUS = 0x1B;
    /** Camera lock X position (ROM: $224) */
    private static final int CAMERA_LOCK_X = 0x224;
    /** Descent constant velocity (ROM: move.w #$100,y_vel(a0)) */
    private static final int DESCENT_SPEED = 0x100;

    // Velocity constants (8.8 fixed-point)
    /** Dash speed (ROM: move.w #$800,d0) */
    private static final int DASH_SPEED = 0x800;
    /** Slow dash speed (ROM: move.w #$400,d0) */
    private static final int SLOW_DASH_SPEED = 0x400;
    /** Deceleration rate per frame (ROM: $20) */
    private static final int DECEL_RATE = 0x20;
    /** Jump Y velocity (ROM: move.w #-$600,y_vel(a0)) */
    private static final int JUMP_Y_VEL = -0x600;

    // Timing constants
    /** Idle timer duration (ROM: move.b #$64,objoff_2A(a0)) */
    private static final int IDLE_DURATION = 0x64;
    /** Countdown for boss music (60 frames) */
    private static final int MUSIC_COUNTDOWN = 60;
    /** Aim hold duration (ROM: $20 frames) */
    private static final int AIM_HOLD_DURATION = 0x20;
    /** Invulnerability duration (ROM: move.b #$20,objoff_30(a0)) */
    private static final int INVULN_DURATION = 0x20;
    /** Defeat explosion timer (ROM: move.w #$FF,objoff_32(a0)) */
    private static final int DEFEAT_TIMER = 0xFF;
    /** Buzz sound threshold (play at 50 frames remaining) */
    private static final int BUZZ_THRESHOLD = 0x32;

    // Collision size indices
    /** Standing collision (ROM: move.b #$1A,collision_flags(a0)) */
    private static final int COLLISION_STANDING = 0x1A;
    /** Ball form collision (ROM: move.b #$9A,collision_flags(a0)) */
    private static final int COLLISION_BALL = 0x9A;

    // Rendering frame indices (from ROM mappings ObjAF_MapUnc_39E68)
    private static final int FRAME_STAND = 0;
    private static final int FRAME_WALK_A = 3;
    private static final int FRAME_BALL_A = 6;
    private static final int FRAME_BALL_B = 7;
    private static final int FRAME_BALL_C = 8;

    // ========================================================================
    // ROM Animation Table: Ani_objAF (off_39DE2)
    // AnimateSprite_Checked: reads anim field, indexes table, sequences frames
    // at given speed, handles terminators ($FF=loop, $FC=stall, $FD=advance).
    // Called ONLY in specific routines, NOT every frame.
    // ========================================================================
    private static final int ANIM_TERM_LOOP = 0xFF;
    private static final int ANIM_TERM_STALL = 0xFC;
    private static final int ANIM_TERM_ADVANCE = 0xFD;

    // Anim 0: standing walk cycle (speed 2, loop)
    private static final int[] ANIM_0_STANDING = {0, 1, 2};
    // Anim 1: crouch (speed $45/69, advance routine_secondary)
    private static final int[] ANIM_1_CROUCH = {3};
    // Anim 2: dash start (speed 3, stall) — used in Dash Across second half
    private static final int[] ANIM_2_DASH = {4, 5, 4, 3};
    // Anim 3: speed-up ball form (speed 3, stall) — aim phase wind-up.
    // ROM byte_39DFE (s2.asm:78141-78142):
    //   dc.b 3, 3,3, 6,6,6, 7,7,7, 8,8,8, 6,6, 7,7, 8,8, 6,7,8, $FC
    // byte[0]=$3 is the animation SPEED (held separately in ANIM_SPEEDS[3]), NOT a
    // displayed frame. The displayed-frame list is the 20 bytes after it (two leading
    // standing "3" frames, then the ball/spin frames). A prior change mis-counted the
    // speed byte as a third leading "3" (21 displayed frames). AnimateSprite_Checked
    // holds each frame for speed+1 (=4) game frames, so the spurious frame stretched
    // the Aim&Dash wind-up (loc_39A1C) by 4 frames every attack cycle; by the second
    // DEZ Mecha Sonic attack the boss body lagged ROM by ~0x1F px and the rolling
    // player's boss-hit overlap registered ~4 frames late, so the ROM deflection
    // (neg.w x_vel / neg.w y_vel, Touch_Enemy multi_sprite branch s2.asm:85261-85276)
    // never fired at trace frame 1023. loc_39D24 gates ball vs standing collision on
    // mapping_frame (s2.asm:78017-78039).
    private static final int[] ANIM_3_SPEEDUP = {3, 3, 6, 6, 6, 7, 7, 7, 8, 8, 8, 6, 6, 7, 7, 8, 8, 6, 7, 8};
    // Anim 4: spin loop (speed 2, loop) — continuous ball spin during dash
    private static final int[] ANIM_4_SPIN = {6, 7, 8};
    // Anim 5: decel spin-down (speed 3, stall) — post-dash walk-out
    private static final int[] ANIM_5_DECEL = {8, 7, 6, 8, 8, 7, 7, 6, 6, 8, 8, 8, 7, 7, 7, 6, 6, 6, 3, 3};

    private static final int[][] ANIM_FRAMES = {
            ANIM_0_STANDING, ANIM_1_CROUCH, ANIM_2_DASH,
            ANIM_3_SPEEDUP, ANIM_4_SPIN, ANIM_5_DECEL
    };
    private static final int[] ANIM_SPEEDS = {2, 0x45, 3, 3, 2, 3};
    private static final int[] ANIM_TERMINATORS = {
            ANIM_TERM_LOOP, ANIM_TERM_ADVANCE, ANIM_TERM_STALL,
            ANIM_TERM_STALL, ANIM_TERM_LOOP, ANIM_TERM_STALL
    };

    // Spikeball data table (8 entries from ROM byte_39D92)
    private static final int[][] SPIKEBALL_DATA = {
            {0x00, -0x18, 0x000, -0x300, 0x0F},
            {-0x10, -0x10, -0x200, -0x200, 0x10},
            {-0x18, 0x00, -0x300, 0x000, 0x11},
            {-0x10, 0x10, -0x200, 0x200, 0x12},
            {0x00, 0x18, 0x000, 0x300, 0x13},
            {0x10, 0x10, 0x200, 0x200, 0x14},
            {0x18, 0x00, 0x300, 0x000, 0x15},
            {0x10, -0x10, 0x200, -0x200, 0x16}
    };
    // Internal state
    private int actionTimer;
    private int attackIndex;
    private int attackSubRoutine;
    private int attackPhase;
    private int dashRepeatCount;
    private int defeatTimer;
    private int currentFrame;
    /**
     * ROM loc_398C0 (s2.asm:77570-77584) calls loc_39D1C to refresh collision_flags
     * from mapping_frame BEFORE the routine handler runs AnimateSprite_Checked (which
     * mutates mapping_frame). collision_flags therefore reflects the PREVIOUS frame's
     * mapping_frame -- a 1-frame lag between the displayed frame and the touch
     * response category (loc_39D24, s2.asm:78017-78039: $1A standing vs $9A ball).
     * We mirror that lag by latching the collision-relevant frame at the start of each
     * boss update from the prior frame's currentFrame.
     */
    private int collisionFrame = FRAME_STAND;
    private boolean facingLeft;
    private boolean ballForm;
    private boolean spikeballsFired;
    private boolean dashDirectionToggle;
    /** Ground Y position, captured on first landing for floor-snap during idle */
    private int groundY;

    // AnimateSprite_Checked state
    private int anim;
    private int prevAnim;
    private int animFrame;
    private int animFrameDuration;
    private boolean animTerminatorReached;

    // Child component references
    private MechaSonicDEZWindow dezWindow;
    private MechaSonicTargetingSensor targetingSensor;
    private MechaSonicLEDWindow ledWindow;

    public Sonic2MechaSonicInstance(ObjectSpawn spawn) {
        super(spawn, "DEZ Mecha Sonic");
    }

    @Override
    public Sonic2MechaSonicInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(
                ctx.objectServices(),
                () -> new Sonic2MechaSonicInstance(ctx.spawn()));
    }

    @Override
    protected void initializeBossState() {
        state.routine = ROUTINE_INIT;
        state.routineSecondary = 0;
        attackIndex = 0;
        actionTimer = 0;
        defeatTimer = 0;
        currentFrame = FRAME_STAND;
        facingLeft = false;
        ballForm = false;
        spikeballsFired = false;
        dashDirectionToggle = false;

        anim = 0;
        prevAnim = -1;
        animFrame = 0;
        animFrameDuration = 0;
        animTerminatorReached = false;

        state.routine = ROUTINE_WAIT_CAMERA;
        if (getSpawn().objectId() == Sonic2ObjectIds.MECHA_SONIC) {
            spawnChildObjects();
        }
    }

    private void spawnChildObjects() {
        ledWindow = spawnTrackedFreeChild(() -> new MechaSonicLEDWindow(this));
        targetingSensor = spawnTrackedFreeChild(() -> new MechaSonicTargetingSensor(this));
        dezWindow = spawnTrackedFreeChild(() -> new MechaSonicDEZWindow(this));
    }

    private <T extends AbstractBossChild> T spawnTrackedFreeChild(Supplier<T> factory) {
        T child = spawnFreeChild(factory);
        childComponents.add(child);
        return child;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM loc_39D1C (s2.asm:78013-78039) runs at the top of the main routine,
        // BEFORE the per-routine handler animates the sprite. Latch the collision
        // frame from the value mapping_frame held going into this update so the touch
        // response category lags the displayed frame by one frame, exactly as ROM.
        collisionFrame = currentFrame;
        // ROM: AnimateSprite_Checked is NOT called globally. Each routine/phase
        // calls it explicitly only when needed.
        switch (state.routine) {
            case ROUTINE_WAIT_CAMERA -> updateWaitCamera();
            case ROUTINE_COUNTDOWN -> updateCountdown();
            case ROUTINE_DESCEND -> updateDescend(vIntRunCount);
            case ROUTINE_IDLE -> updateIdle();
            case ROUTINE_ATTACK -> updateAttack(player);
            case ROUTINE_DEFEAT -> updateDefeat(vIntRunCount);
        }
    }

    // ========================================================================
    // AnimateSprite_Checked — called only by routines that need it
    // ========================================================================

    private void animateSpriteChecked() {
        if (anim < 0 || anim >= ANIM_FRAMES.length) {
            return;
        }
        if (anim != prevAnim) {
            prevAnim = anim;
            animFrame = 0;
            animFrameDuration = ANIM_SPEEDS[anim];
            animTerminatorReached = false;
            currentFrame = ANIM_FRAMES[anim][0];
            ballForm = isBallFrame(currentFrame);
            return;
        }
        if (animTerminatorReached) {
            return;
        }
        animFrameDuration--;
        if (animFrameDuration >= 0) {
            return;
        }
        animFrameDuration = ANIM_SPEEDS[anim];
        animFrame++;
        int[] frames = ANIM_FRAMES[anim];
        if (animFrame >= frames.length) {
            int term = ANIM_TERMINATORS[anim];
            switch (term) {
                case ANIM_TERM_LOOP -> {
                    animFrame = 0;
                    currentFrame = frames[0];
                }
                case ANIM_TERM_STALL -> {
                    animFrame = frames.length - 1;
                    currentFrame = frames[animFrame];
                    animTerminatorReached = true;
                }
                case ANIM_TERM_ADVANCE -> {
                    animFrame = frames.length - 1;
                    currentFrame = frames[animFrame];
                    animTerminatorReached = true;
                    state.routineSecondary += 2;
                }
            }
        } else {
            currentFrame = frames[animFrame];
        }
        ballForm = isBallFrame(currentFrame);
    }

    private static boolean isBallFrame(int frame) {
        return frame == FRAME_BALL_A || frame == FRAME_BALL_B || frame == FRAME_BALL_C;
    }

    // ========================================================================
    // Routine 2: Wait for camera
    // ========================================================================

    private void updateWaitCamera() {
        Camera camera = services().camera();
        if (camera.getX() >= CAMERA_LOCK_X) {
            state.routine = ROUTINE_COUNTDOWN;
            actionTimer = MUSIC_COUNTDOWN;
            state.yVel = DESCENT_SPEED;
            camera.setMinX((short) CAMERA_LOCK_X);
            camera.setMaxX((short) CAMERA_LOCK_X);
            services().gameState().setCurrentBossId(9);
            services().fadeOutMusic();
        }
    }

    // ========================================================================
    // Routine 4: Countdown
    // ========================================================================

    private void updateCountdown() {
        actionTimer--;
        if (actionTimer < 0) {
            services().playMusic(Sonic2Music.BOSS.id);
            state.routine = ROUTINE_DESCEND;
            // ROM: No anim set here. anim stays 0, but AnimateSprite_Checked
            // is NOT called during descent, so mapping_frame stays as-is.
        }
    }

    // ========================================================================
    // Routine 6: Descend (no AnimateSprite_Checked in ROM)
    // ROM: loc_397FE — ObjectMove + positioning only
    // ========================================================================

    private void updateDescend(int vIntRunCount) {
        if ((vIntRunCount & 0x1F) == 0) {
            services().playSfx(Sonic2Sfx.FIRE.id);
        }

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, Y_RADIUS);
        if (floor.distance() < 0) {
            state.y += floor.distance();
            state.yFixed = state.y << 16;
            state.yVel = 0;
            groundY = state.y;
            transitionToIdle();
            return;
        }

        // ROM: ObjectMove — constant velocity, no gravity, no AnimateSprite
        state.applyVelocity();

        // ROM: Position children during descent (LED at offset 0,0; sensor at $C,-$C)
        if (ledWindow != null) {
            ledWindow.syncPositionWithParent();
        }
        if (targetingSensor != null) {
            targetingSensor.syncPositionWithParent();
        }
        // currentFrame stays as FRAME_STAND (set in init)
    }

    // ========================================================================
    // Routine 8: Idle — ROM calls AnimateSprite_Checked at loc_3986A
    // ========================================================================

    private void updateIdle() {
        anim = 0; // Standing walk cycle
        ballForm = false;

        // ROM: ObjCheckFloorDist called dynamically every frame during idle to snap to floor.
        // Probe terrain each frame rather than using cached groundY.
        TerrainCheckResult floorIdle = ObjectTerrainUtils.checkFloorDist(state.x, state.y, Y_RADIUS);
        if (floorIdle != null && floorIdle.distance() <= 0) {
            state.y += floorIdle.distance();
            state.yFixed = state.y << 16;
        }

        actionTimer--;
        if (actionTimer == 0) {
            state.routine = ROUTINE_ATTACK;
            attackSubRoutine = ATTACK_TABLE[attackIndex & 0x0F];
            attackIndex = (attackIndex + 1) & 0xFF;
            attackPhase = 0;
            dashRepeatCount = 0;
            spikeballsFired = false;
            if (targetingSensor != null) {
                targetingSensor.setCollisionEnabled(false);
            }
            return;
        }

        if (actionTimer == BUZZ_THRESHOLD) {
            services().playSfx(Sonic2Sfx.MECHA_SONIC_BUZZ.id);
        }

        // ROM: loc_3986A — AnimateSprite_Checked during idle
        animateSpriteChecked();
        // ROM: loc_39D4A aligns the targeting sensor after idle animation.
        if (targetingSensor != null) {
            targetingSensor.syncPositionWithParent();
        }
    }

    private void transitionToIdle() {
        state.routine = ROUTINE_IDLE;
        actionTimer = IDLE_DURATION;
        anim = 0;
        currentFrame = FRAME_STAND;
        ballForm = false;
        state.xVel = 0;
        // ROM: play SndID_MechaSonicBuzz on every idle transition
        services().playSfx(Sonic2Sfx.MECHA_SONIC_BUZZ.id);
        if (targetingSensor != null) {
            targetingSensor.setCollisionEnabled(true);
        }
        // ROM: LED routine $12 (hidden) when returning to idle
        if (ledWindow != null) {
            ledWindow.setVisible(false);
        }
        // ROM: bset #status.npc.y_flip — signal DEZ window to begin blind-opening animation
        if (dezWindow != null) {
            dezWindow.signalLandingComplete();
        }
    }

    // ========================================================================
    // Routine A: Attack dispatch
    // ========================================================================

    private void updateAttack(AbstractPlayableSprite player) {
        // ROM loc_398C0 dispatches the phase handler first, aligns the LED and targeting
        // sensor (loc_39D4A), then executes exactly one ObjectMove before display.
        switch (attackSubRoutine) {
            case ATTACK_DASH_ACROSS -> updateDashAcross(player);
            case ATTACK_AIM_AND_DASH -> updateAimAndDash(player);
            case ATTACK_AIM_DASH_WALK -> updateAimDashWalk(player);
            case ATTACK_AIM_JUMP_SPIKEBALLS -> updateAimJumpSpikeballs(player);
        }
        syncAttackChildrenBeforeObjectMove();
        state.applyVelocity();
    }

    private void syncAttackChildrenBeforeObjectMove() {
        if (ledWindow != null) {
            ledWindow.syncPositionWithParent();
        }
        if (targetingSensor != null) {
            targetingSensor.syncPositionWithParent();
        }
    }

    // ========================================================================
    // Attack $00: Dash Across
    // ROM: loc_3991E → loc_3992E → loc_39946 → loc_3994E → loc_39976
    // ========================================================================

    private void updateDashAcross(AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> {
                // ROM: loc_3991E — mapping_frame=3 directly (not via anim)
                currentFrame = FRAME_WALK_A;
                ballForm = false;
                actionTimer = AIM_HOLD_DURATION;
                attackPhase = 1;
                dashRepeatCount = 2;
                // ROM: loc_3992E — LED routine $10 (visible), anim 1 (back thruster)
                if (ledWindow != null) {
                    ledWindow.setAnimId(1);
                    ledWindow.setVisible(true);
                }
            }
            case 1 -> {
                // ROM: loc_39946 — timer countdown, NO AnimateSprite
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 2;
                    actionTimer = 0x40;
                    // ROM: loc_3994E — anim=1 (crouch, frame 3), start dash, play sound
                    anim = 1;
                    startDash(DASH_SPEED);
                    services().playSfx(Sonic2Sfx.SPINDASH_RELEASE.id);
                    // ROM: loc_3994E — LED anim 2 (back thruster variant) on dash start
                    if (ledWindow != null) ledWindow.setAnimId(2);
                }
            }
            case 2 -> {
                // ROM: loc_39976 — dashing with AnimateSprite_Checked
                actionTimer--;
                if (actionTimer < 0) {
                    dashRepeatCount--;
                    if (dashRepeatCount > 0) {
                        attackPhase = 1;
                        actionTimer = AIM_HOLD_DURATION;
                        state.xVel = 0;
                        // ROM: bra.w loc_3992E — reset to wait phase
                        currentFrame = FRAME_WALK_A;
                        // ROM: loc_3992E — LED routine $10 (visible), anim 1 (back thruster)
                        if (ledWindow != null) {
                            ledWindow.setAnimId(1);
                            ledWindow.setVisible(true);
                        }
                    } else {
                        transitionToIdle();
                    }
                } else {
                    // ROM: At timer=$20, switch to anim 2 (dash start: {4,5,4,3})
                    // ROM: LED routine $12 (hidden) midway through dash
                    if (actionTimer == 0x20) {
                        anim = 2;
                        if (ledWindow != null) ledWindow.setVisible(false);
                    }
                    applyDeceleration();
                    animateSpriteChecked();
                    // ROM: direction flip at anim=2, frame index 2, duration=3
                    if (anim == 2 && animFrame == 2 && animFrameDuration == ANIM_SPEEDS[2]) {
                        facingLeft = !facingLeft;
                    }
                }
            }
        }
    }

    // ========================================================================
    // Attack $06: Aim & Dash
    // ROM: loc_39A0A → loc_39A1C → loc_39A2A/loc_39A44 → loc_39A56/loc_39A68
    //   → loc_39A7C → loc_39A96
    // ========================================================================

    private void updateAimAndDash(AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> {
                // ROM: loc_39A0A — mapping_frame=3, anim=3, no AnimateSprite
                currentFrame = FRAME_WALK_A;
                anim = 3;
                ballForm = false;
                attackPhase = 1;
            }
            case 1 -> {
                // ROM: loc_39A1C — AnimateSprite_Checked with anim 3 (speed-up)
                // Wait for $FC terminator (d0=1)
                animateSpriteChecked();
                if (animTerminatorReached) {
                    attackPhase = 2;
                    actionTimer = AIM_HOLD_DURATION;
                    anim = 4; // Spin loop
                    // ROM: loc_39A2A — play laser beam sound
                    services().playSfx(Sonic2Sfx.LASER_BEAM.id);
                }
            }
            case 2 -> {
                // ROM: loc_39A44 — timer + AnimateSprite_Checked
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 3;
                    actionTimer = 0x40;
                    // ROM: loc_39A56 — start dash, no sound, no anim change
                    startDash(DASH_SPEED);
                } else {
                    animateSpriteChecked();
                }
            }
            case 3 -> {
                // ROM: loc_39A68 — dashing with AnimateSprite_Checked
                actionTimer--;
                if (actionTimer < 0) {
                    // ROM: loc_39A7C — stop, flip, anim=5
                    attackPhase = 4;
                    anim = 5;
                    facingLeft = !facingLeft;
                    state.xVel = 0;
                    state.yVel = 0;
                } else {
                    applyDeceleration();
                    animateSpriteChecked();
                }
            }
            case 4 -> {
                // ROM: loc_39A96 — AnimateSprite_Checked, wait for $FC stall
                animateSpriteChecked();
                if (animTerminatorReached) {
                    transitionToIdle();
                }
            }
        }
    }

    // ========================================================================
    // Attack $10: Aim, Dash, Walk (jump variant)
    // ROM: shares loc_39A0A/loc_39A1C with Attack $06, then diverges
    // ========================================================================

    private void updateAimDashWalk(AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> {
                // ROM: loc_39A0A — same as Aim & Dash
                currentFrame = FRAME_WALK_A;
                anim = 3;
                ballForm = false;
                attackPhase = 1;
            }
            case 1 -> {
                // ROM: loc_39A1C — AnimateSprite_Checked anim 3
                animateSpriteChecked();
                if (animTerminatorReached) {
                    attackPhase = 2;
                    actionTimer = AIM_HOLD_DURATION;
                    anim = 4;
                    services().playSfx(Sonic2Sfx.LASER_BEAM.id);
                }
            }
            case 2 -> {
                // ROM: loc_39AAA — timer + AnimateSprite_Checked
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 3;
                    actionTimer = 0x40;
                    // ROM: loc_39ABC — slow dash, no sound
                    startDash(SLOW_DASH_SPEED);
                } else {
                    animateSpriteChecked();
                }
            }
            case 3 -> {
                // ROM: loc_39ACE — dashing, jump trigger at timer=60
                actionTimer--;
                if (actionTimer == 60) {
                    // ROM: loc_39AE8 — set y_vel, advance
                    attackPhase = 4;
                    state.yVel = JUMP_Y_VEL;
                }
                // ROM: AnimateSprite_Checked every frame in this phase
                animateSpriteChecked();
            }
            case 4 -> {
                // ROM: loc_39AF4 — airborne after jump
                actionTimer--;
                if (actionTimer < 0) {
                    // ROM: loc_39AF4 -> loc_39A7C when the airborne timer expires.
                    transitionToGroundRun();
                    return;
                }
                TerrainCheckResult floorADW = ObjectTerrainUtils.checkFloorDist(state.x, state.y, Y_RADIUS);
                if (floorADW.distance() < 0) {
                    // ROM: loc_39B1A — landed, snap Y to floor then fall through
                    // to loc_39B0A which re-applies gravity; the outer attack loop
                    // performs ObjectMove after child alignment. On the landing frame,
                    // yVel = GRAVITY ($38) pushes slightly below floor; next frame's
                    // ground-run snap corrects.
                    state.y += floorADW.distance();
                    state.yFixed = state.y << 16;
                    state.yVel = 0;
                    state.yVel += GRAVITY;
                    animateSpriteChecked();
                    attackPhase = 5;
                    return;
                }
                state.yVel += GRAVITY;
                // ROM: loc_39B0A — AnimateSprite_Checked
                animateSpriteChecked();
            }
            case 5 -> {
                // ROM: ground-run phase — constant velocity with timer-based stop.
                // ROM: subq.b #1,objoff_2A(a0) / bmi.w loc_39A7C
                // Velocity stays constant (whatever was set during dash), timer counts down.
                // When timer reaches -1, instantly zero velocity, flip, transition.
                actionTimer--;
                if (actionTimer < 0) {
                    // ROM: loc_39A7C — stop, flip direction, anim=5
                    attackPhase = 6;
                    anim = 5;
                    state.xVel = 0;
                    state.yVel = 0;
                    facingLeft = !facingLeft;
                } else {
                    // ROM: loc_39B28 — ObjCheckFloorDist + add.w d1,y_pos(a0)
                    TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, Y_RADIUS);
                    if (floor != null) {
                        state.y += floor.distance();
                        state.yFixed = state.y << 16;
                    }
                    animateSpriteChecked();
                }
            }
            case 6 -> {
                // ROM: loc_39A96 — walk-out, wait for $FC stall
                animateSpriteChecked();
                if (animTerminatorReached) {
                    transitionToIdle();
                }
            }
        }
    }

    // ========================================================================
    // Attack $1E: Aim, Jump + Spikeballs
    // ROM: shares aim phases with $10, diverges at airborne phase
    // ========================================================================

    private void updateAimJumpSpikeballs(AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> {
                currentFrame = FRAME_WALK_A;
                anim = 3;
                ballForm = false;
                attackPhase = 1;
            }
            case 1 -> {
                animateSpriteChecked();
                if (animTerminatorReached) {
                    attackPhase = 2;
                    actionTimer = AIM_HOLD_DURATION;
                    anim = 4;
                    services().playSfx(Sonic2Sfx.LASER_BEAM.id);
                }
            }
            case 2 -> {
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 3;
                    actionTimer = 0x40;
                    startDash(SLOW_DASH_SPEED);
                } else {
                    animateSpriteChecked();
                }
            }
            case 3 -> {
                // ROM: loc_39ACE — dashing, jump trigger at timer=60
                actionTimer--;
                if (actionTimer == 60) {
                    state.yVel = JUMP_Y_VEL;
                    attackPhase = 4;
                    spikeballsFired = false;
                }
                animateSpriteChecked();
            }
            case 4 -> {
                // ROM: loc_39B44 — airborne, fire spikeballs at apex
                actionTimer--;
                if (actionTimer < 0) {
                    // ROM: loc_39B44 -> loc_39A7C when the airborne timer expires.
                    transitionToGroundRun();
                    return;
                }
                if (!spikeballsFired && state.yVel >= 0) {
                    spikeballsFired = true;
                    fireSpikeballs();
                    services().playSfx(Sonic2Sfx.SPIKE_SWITCH.id);
                }
                TerrainCheckResult floorAJS = ObjectTerrainUtils.checkFloorDist(state.x, state.y, Y_RADIUS);
                if (floorAJS.distance() < 0) {
                    // ROM: landed, snap Y to floor then fall through
                    // to loc_39B0A which re-applies gravity; the outer attack loop
                    // performs ObjectMove after child alignment. On the landing frame,
                    // yVel = GRAVITY ($38) pushes slightly below floor; next frame's
                    // ground-run snap corrects.
                    state.y += floorAJS.distance();
                    state.yFixed = state.y << 16;
                    state.yVel = 0;
                    state.yVel += GRAVITY;
                    animateSpriteChecked();
                    attackPhase = 5;
                    return;
                }
                state.yVel += GRAVITY;
                animateSpriteChecked();
            }
            case 5 -> {
                // ROM: ground-run phase — constant velocity with timer-based stop.
                // ROM: subq.b #1,objoff_2A(a0) / bmi.w loc_39A7C
                // Velocity stays constant (whatever was set during dash), timer counts down.
                // When timer reaches -1, instantly zero velocity, flip, transition.
                actionTimer--;
                if (actionTimer < 0) {
                    // ROM: loc_39A7C — stop, flip direction, anim=5
                    attackPhase = 6;
                    anim = 5;
                    state.xVel = 0;
                    state.yVel = 0;
                    facingLeft = !facingLeft;
                } else {
                    // ROM: loc_39B28 — ObjCheckFloorDist + add.w d1,y_pos(a0)
                    TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, Y_RADIUS);
                    if (floor != null) {
                        state.y += floor.distance();
                        state.yFixed = state.y << 16;
                    }
                    animateSpriteChecked();
                }
            }
            case 6 -> {
                animateSpriteChecked();
                if (animTerminatorReached) {
                    transitionToIdle();
                }
            }
        }
    }

    // ========================================================================
    // Attack helpers
    // ========================================================================

    /**
     * ROM loc_39A7C: stop the airborne attack, flip, and enter ground-run before
     * the outer attack loop performs its single ObjectMove.
     */
    private void transitionToGroundRun() {
        attackPhase = 5;
        anim = 5;
        facingLeft = !facingLeft;
        state.xVel = 0;
        state.yVel = 0;
    }

    /**
     * Start a dash using the ROM's direction toggle system.
     * ROM: loc_39D60 — ONLY sets velocity and toggles direction.
     * Does NOT set anim or play sound (callers handle those).
     */
    private void startDash(int speed) {
        int xVel = speed;
        if (!dashDirectionToggle) {
            xVel = -xVel;
        }
        dashDirectionToggle = !dashDirectionToggle;
        state.xVel = xVel;
        // facingLeft is NOT set here — ROM only toggles it at end-of-dash (loc_39A7C)
    }

    private void applyDeceleration() {
        // ROM: move.w #-$20,d0 / tst.w x_vel(a0) / bge.s + / neg.w d0
        // ROM pushes -$20 (left), then if xVel >= 0, negates to +$20 (right decel).
        // This means: xVel >= 0 -> subtract, xVel < 0 -> add. Note >= 0, not > 0.
        if (state.xVel >= 0) {
            state.xVel -= DECEL_RATE;
        } else {
            state.xVel += DECEL_RATE;
        }
    }

    private void fireSpikeballs() {
        for (int i = 0; i < 8; i++) {
            int xOffset = SPIKEBALL_DATA[i][0];
            int yOffset = SPIKEBALL_DATA[i][1];
            int xVelData = SPIKEBALL_DATA[i][2];
            int yVelData = SPIKEBALL_DATA[i][3];
            int mappingFrame = SPIKEBALL_DATA[i][4];
            int startX = state.x + xOffset;
            int startY = state.y + yOffset;
            spawnFreeChild(() -> new MechaSonicSpikeball(
                    this, startX, startY, xVelData, yVelData, mappingFrame));
        }
    }

    // ========================================================================
    // Routine C: Defeat sequence
    // ========================================================================

    private void updateDefeat(int vIntRunCount) {
        defeatTimer--;
        if (defeatTimer < 0) {
            Camera camera = services().camera();
            camera.setMaxX((short) 0x1000);
            Sonic2LevelEventManager eventManager = (Sonic2LevelEventManager) services().levelEventProvider();
            eventManager.setEventRoutine(eventManager.getEventRoutine() + 2);
            // ROM Silver Sonic (ObjAF) sets Current_Boss_ID=9 (s2.asm:77528) and
            // NEVER clears it — no S2 boss writes move.b #0,(Current_Boss_ID).w.
            // It stays 9 through the Death Egg Robot fight that follows in the
            // SAME act, so Sonic_LevelBound keeps the boss-strict right boundary
            // (Camera_Max_X + $128, no +$40 extension; s2.asm:37246-37248) for the
            // DEZ arena. Clearing it here reverted the player's right boundary to
            // the lenient +$40 (0x8A8 vs ROM 0x868), so the player ran past the
            // DEZ arena edge during the Death Egg Robot fight (DEZ1 f4933:
            // ROM clamps x at 0x868, engine kept x_speed). Leave it set; the
            // ending walk (Camera_Max_X=0x1000) is well within 0x1000+$128.
            services().playMusic(Sonic2Music.DEATH_EGG.id);
            // ROM loc_39BA4 only unlocks the camera, advances the dynamic-resize
            // event, resumes music, and deletes ObjAF; the ObjC6 transition
            // object is already present from the DEZ layout.
            setDestroyed(true);
            return;
        }
        if (defeatTimer % EXPLOSION_INTERVAL == 0) {
            spawnDefeatExplosion();
        }
        currentFrame = FRAME_STAND;
    }

    // ========================================================================
    // Collision
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        if (state.invulnerable || state.defeated) return 0;
        if (state.routine < ROUTINE_IDLE) return 0;
        // ROM loc_39D24 (s2.asm:78017-78039): collision_flags is $9A only when the
        // mapping_frame latched at the top of the update (collisionFrame) is a ball
        // frame (6/7/8); otherwise $1A. Using the latched frame, not the live one,
        // preserves the loc_39D1C one-frame lag.
        if (collisionFrame == FRAME_BALL_A || collisionFrame == FRAME_BALL_B
                || collisionFrame == FRAME_BALL_C) {
            return COLLISION_BALL;
        }
        return COLLISION_STANDING;
    }

    @Override
    protected int getCollisionSizeIndex() { return COLLISION_STANDING; }

    @Override
    protected int getInitialHitCount() { return DEFAULT_HIT_COUNT; }

    @Override
    protected void onHitTaken(int remainingHits) {
        // ROM: Animation selection is now per-frame in MechaSonicDEZWindow.update(),
        // not triggered by hit count thresholds. No action needed here.
    }

    @Override
    protected int getInvulnerabilityDuration() { return INVULN_DURATION; }

    @Override
    protected boolean usesDefeatSequencer() { return false; }

    /**
     * ObjAF selects defeat by overwriting the <strong>primary {@code routine}</strong>
     * ({@code loc_39CF0}: {@code move.b #$C,routine(a0)}, docs/s2disasm/s2.asm:78003-78004)
     * and dispatches on {@code routine(a0)} read once at the top of {@code ObjAF}
     * (docs/s2disasm/s2.asm:77412-77415). Routine $C's defeat countdown
     * ({@code loc_39B92}: {@code subq.w #1,objoff_32}, docs/s2disasm/s2.asm:77848-77853)
     * therefore first runs the frame after the hit. Because the engine runs touch
     * responses before this object's own update, this boss needs the one-frame defeat
     * dispatch deferral to match the ROM Camera_Max_X release timing
     * ({@code loc_39BA4}, docs/s2disasm/s2.asm:77856-77857). WFZ (ObjC5) uses
     * {@code routine_secondary} instead and is correctly left undeferred.
     */
    @Override
    protected boolean defeatDeferralAppliesToThisBoss() { return true; }

    @Override
    protected void onDefeatStarted() {
        state.routine = ROUTINE_DEFEAT;
        defeatTimer = DEFEAT_TIMER;
        state.xVel = 0;
        state.yVel = 0;
        ballForm = false;
        currentFrame = FRAME_STAND;
        if (targetingSensor != null) targetingSensor.setDestroyed(true);
        if (ledWindow != null) ledWindow.setDestroyed(true);
        if (dezWindow != null) dezWindow.setAnimId(1);
    }

    @Override
    public int getPriorityBucket() { return 4; }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;
        PatternSpriteRenderer renderer = renderManager.getRenderer(
                Sonic2ObjectArtKeys.DEZ_SILVER_SONIC);
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(currentFrame, state.x, state.y, facingLeft, false);
    }

    // ========================================================================
    // Accessors for tests
    // ========================================================================

    public int getAttackIndex() { return attackIndex; }
    public int getCurrentRoutine() { return state.routine; }
    public boolean isBallForm() { return ballForm; }
    public int getDefeatTimer() { return defeatTimer; }
    public int getCurrentFrame() { return currentFrame; }
    public boolean isDashDirectionToggle() { return dashDirectionToggle; }
    public int getAttackSubRoutine() { return attackSubRoutine; }

    @Override
    protected int getBossHitSfxId() {
        return Sonic2Sfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic2Sfx.BOSS_EXPLOSION.id;
    }

    @Override
    protected int getBossExplosionObjectId() {
        return com.openggf.game.sonic2.constants.Sonic2ObjectIds.BOSS_EXPLOSION;
    }

    private static Sonic2MechaSonicInstance nearestLiveBossForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        Sonic2MechaSonicInstance nearest = null;
        long bestDistance = Long.MAX_VALUE;
        ObjectSpawn spawn = ctx.spawn();
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (instance instanceof Sonic2MechaSonicInstance boss && !boss.isDestroyed()) {
                long dx = boss.getX() - (long) spawn.x();
                long dy = boss.getY() - (long) spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    nearest = boss;
                    bestDistance = distance;
                }
            }
        }
        return nearest;
    }

    // ========================================================================
    // Child Objects
    // ========================================================================

    static class MechaSonicDEZWindow extends AbstractBossChild implements RewindRecreatable {
        private static final int WINDOW_X = 0x2C0;
        private static final int WINDOW_Y = 0x139;
        private static final int[][] WINDOW_ANIMS = {
                {3, 4, 3, 2, 1, 0},
                {3, 0, 1, 2, 3, 4},
                {3, 5, 5},
                {3, 5, 6},
                {3, 7, 7},
        };

        private int animId;
        private int animFrame;
        private int animTimer;
        private int mappingFrame;
        /**
         * ROM: The window sits at routine $1C waiting for the parent's y_flip flag
         * (set when Silver Sonic lands after descent). Until signalled, the window
         * displays mapping_frame 4 (blinds fully closed) without animating.
         */
        private boolean waitingForLanding = true;
        /**
         * ROM: Routine $1E plays the blind-opening animation (anim 0: 4→3→2→1→0)
         * before transitioning to routine $20 (per-frame animation selection).
         * This flag gates the per-frame selection until the opening anim completes.
         */
        private boolean openingAnimPlaying = false;
        /**
         * ROM: Closing animation (animId==1) uses $FA terminator which means
         * "advance routine_secondary by 2" — it does NOT loop. Once complete,
         * the window stalls on the last frame.
         */
        private boolean closingComplete = false;

        MechaSonicDEZWindow(Sonic2MechaSonicInstance parent) {
            super(parent, "DEZ Window", 6, Sonic2ObjectIds.MECHA_SONIC);
            this.currentX = WINDOW_X;
            this.currentY = WINDOW_Y;
            this.animId = 0;
            this.animFrame = 0;
            this.animTimer = 0;
            this.mappingFrame = 4;
        }

        @Override
        public MechaSonicDEZWindow recreateForRewind(RewindRecreateContext ctx) {
            Sonic2MechaSonicInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            MechaSonicDEZWindow window = new MechaSonicDEZWindow(boss);
            boss.dezWindow = window;
            if (!boss.childComponents.contains(window)) {
                boss.childComponents.add(window);
            }
            return window;
        }

        /**
         * ROM: bclr #status.npc.y_flip — test-and-clear semantics.
         * Called when Silver Sonic lands; advances window from routine $1C
         * (waiting) to $1E (blind-opening animation). The ROM's bclr only
         * fires when the bit is still set, so subsequent calls are no-ops.
         * This ensures the opening animation plays exactly once (after the
         * initial descent landing) and is never replayed after attack cycles.
         */
        void signalLandingComplete() {
            if (!waitingForLanding) return;  // Already advanced past waiting — one-shot
            waitingForLanding = false;
            openingAnimPlaying = true;
            animId = 0;
            animFrame = 0;
            animTimer = 0;
            mappingFrame = WINDOW_ANIMS[0][1]; // First frame of opening anim
        }

        void setAnimId(int newAnimId) {
            if (newAnimId != animId && newAnimId >= 0 && newAnimId < WINDOW_ANIMS.length) {
                animId = newAnimId;
                animFrame = 0;
                animTimer = 0;
                mappingFrame = WINDOW_ANIMS[animId][1];
            }
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;
            // ROM: routine $1C — wait for parent's y_flip flag before animating
            if (waitingForLanding) {
                updateDynamicSpawn();
                return;
            }

            // ROM: routine $1E — play blind-opening animation before per-frame selection
            if (openingAnimPlaying) {
                int[] openAnim = WINDOW_ANIMS[animId];
                int speed = openAnim[0];
                int frameCount = openAnim.length - 1;
                animTimer++;
                if (animTimer > speed) {
                    animTimer = 0;
                    animFrame++;
                    if (animFrame >= frameCount) {
                        // Opening animation complete ($FC terminator) → advance to routine $20
                        openingAnimPlaying = false;
                        animFrame = frameCount - 1;
                        setAnimId(2); // Begin per-frame selection with watching face
                    } else {
                        mappingFrame = openAnim[animFrame + 1];
                    }
                }
                updateDynamicSpawn();
                return;
            }

            // ROM: routine $20 / loc_39C50 — per-frame animation selection based on game state
            Sonic2MechaSonicInstance mechParent = (Sonic2MechaSonicInstance) parent;
            if (mechParent.state.defeated) {
                // ROM: status.npc.misc set (boss defeated) → closing blinds animation
                setAnimId(1);
            } else if (player != null && player.isHurt()) {
                // ROM: player routine >= 4 (player is hurt/dying) → scared face
                setAnimId(3);
            } else if (mechParent.state.invulnerable) {
                // ROM: collision_flags == 0 (boss invulnerable/flashing) → laughing
                setAnimId(4);
            } else {
                // ROM: default → normal watching
                setAnimId(2);
            }

            int[] anim = WINDOW_ANIMS[animId];
            int speed = anim[0];
            int frameCount = anim.length - 1;

            // ROM: Closing animation (animId==1) uses $FA terminator — stall, don't loop
            if (closingComplete) {
                updateDynamicSpawn();
                return;
            }

            animTimer++;
            if (animTimer > speed) {
                animTimer = 0;
                animFrame++;
                if (animFrame >= frameCount) {
                    if (animId == 0) {
                        // Opening animation complete -> transition to watching
                        animFrame = frameCount - 1;
                        setAnimId(2);
                        return;
                    } else if (animId == 1) {
                        // ROM: $FA terminator — stall on last frame, do not loop
                        animFrame = frameCount - 1;
                        mappingFrame = anim[animFrame + 1];
                        closingComplete = true;
                        updateDynamicSpawn();
                        return;
                    } else {
                        animFrame = 0;
                    }
                }
                mappingFrame = anim[animFrame + 1];
            }
            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            this.currentX = WINDOW_X;
            this.currentY = WINDOW_Y;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (parent.isDestroyed()) return;
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_WINDOW);
            if (renderer == null || !renderer.isReady()) return;
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
        }
    }

    static class MechaSonicTargetingSensor extends AbstractBossChild implements RewindRecreatable {
        private static final int X_OFFSET_RIGHT = 0x0C;
        private static final int X_OFFSET_LEFT = -0x0C;
        private static final int Y_OFFSET = -0x0C;
        private boolean collisionEnabled;

        MechaSonicTargetingSensor(Sonic2MechaSonicInstance parent) {
            super(parent, "Targeting Sensor", 4, Sonic2ObjectIds.MECHA_SONIC);
            this.collisionEnabled = false;
        }

        @Override
        public MechaSonicTargetingSensor recreateForRewind(RewindRecreateContext ctx) {
            Sonic2MechaSonicInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            MechaSonicTargetingSensor sensor = new MechaSonicTargetingSensor(boss);
            boss.targetingSensor = sensor;
            if (!boss.childComponents.contains(sensor)) {
                boss.childComponents.add(sensor);
            }
            return sensor;
        }

        void setCollisionEnabled(boolean enabled) { this.collisionEnabled = enabled; }
        public int getCollisionFlags() { return collisionEnabled ? 0x98 : 0x00; }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;
            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            if (parent != null && !parent.isDestroyed()) {
                Sonic2MechaSonicInstance mechParent = (Sonic2MechaSonicInstance) parent;
                int xOff = mechParent.facingLeft ? X_OFFSET_LEFT : X_OFFSET_RIGHT;
                this.currentX = parent.getX() + xOff;
                this.currentY = parent.getY() + Y_OFFSET;
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }

    static class MechaSonicLEDWindow extends AbstractBossChild implements RewindRecreatable {
        private static final int[][] LED_ANIMS = {
                {1, 0x0B, 0x0C},
                {1, 0x0D, 0x0E},
                {1, 0x09, 0x0A},
        };

        private int animId;
        private int animFrame;
        private int animTimer;
        private int mappingFrame;
        /**
         * ROM routine-based visibility: routine $10 = visible, routine $12 = hidden.
         * Created visible at routine $10 with anim 0 (bottom jets) during descent,
         * hidden at loc_399D6 (transitionToIdle) after first landing, then toggled
         * by parent during Dash Across attack phases.
         */
        private boolean visible;

        MechaSonicLEDWindow(Sonic2MechaSonicInstance parent) {
            super(parent, "LED Window", 3, Sonic2ObjectIds.MECHA_SONIC);
            this.animId = 0;
            this.animFrame = 0;
            this.animTimer = 0;
            this.mappingFrame = 0x0B;
            // ROM: child created at routine $10 (visible) with default anim 0 (bottom jets)
            this.visible = true;
        }

        @Override
        public MechaSonicLEDWindow recreateForRewind(RewindRecreateContext ctx) {
            Sonic2MechaSonicInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            MechaSonicLEDWindow window = new MechaSonicLEDWindow(boss);
            boss.ledWindow = window;
            if (!boss.childComponents.contains(window)) {
                boss.childComponents.add(window);
            }
            return window;
        }

        void setVisible(boolean v) { this.visible = v; }

        void setAnimId(int newAnimId) {
            if (newAnimId != animId && newAnimId >= 0 && newAnimId < LED_ANIMS.length) {
                animId = newAnimId;
                animFrame = 0;
                animTimer = 0;
                mappingFrame = LED_ANIMS[animId][1];
            }
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;
            // ROM: AnimateSprite_Checked — animate with whatever anim was set by parent.
            // Anim selection is event-driven from parent (updateDashAcross, transitionToIdle),
            // not heuristic-based.
            int[] anim = LED_ANIMS[animId];
            int speed = anim[0];
            int frameCount = anim.length - 1;
            animTimer++;
            if (animTimer > speed) {
                animTimer = 0;
                animFrame = (animFrame + 1) % frameCount;
                mappingFrame = anim[animFrame + 1];
            }
            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            if (parent != null && !parent.isDestroyed()) {
                this.currentX = parent.getX();
                this.currentY = parent.getY();
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (parent.isDestroyed()) return;
            // ROM: LED visibility is routine-based. Routine $10 = visible (during
            // Dash Across standing/aiming phase), routine $12 = hidden (all other times).
            if (!visible) return;
            Sonic2MechaSonicInstance mechParent = (Sonic2MechaSonicInstance) parent;
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.DEZ_SILVER_SONIC);
            if (renderer == null || !renderer.isReady()) return;
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, mechParent.facingLeft, false);
        }
    }

    static class MechaSonicSpikeball extends AbstractBossChild implements RewindRecreatable {
        private static final int SCREEN_BOUNDS_HALF_WIDTH = 0x180;
        private int xVel;
        private int yVel;
        private int mappingFrame;
        private int xFixed;
        private int yFixed;

        MechaSonicSpikeball(Sonic2MechaSonicInstance parent,
                            int startX, int startY,
                            int xVel, int yVel, int mappingFrame) {
            super(parent, "Spikeball", 4, Sonic2ObjectIds.MECHA_SONIC);
            this.currentX = startX;
            this.currentY = startY;
            this.xFixed = startX << 16;
            this.yFixed = startY << 16;
            this.xVel = xVel;
            this.yVel = yVel;
            this.mappingFrame = mappingFrame;
        }

        MechaSonicSpikeball(Sonic2MechaSonicInstance parent,
                            int startX, int startY,
                            int xVel, int yVel) {
            this(parent, startX, startY, xVel, yVel, 0);
        }

        @Override
        public MechaSonicSpikeball recreateForRewind(RewindRecreateContext ctx) {
            Sonic2MechaSonicInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            int x = spawn == null ? boss.getX() : spawn.x();
            int y = spawn == null ? boss.getY() : spawn.y();
            return new MechaSonicSpikeball(boss, x, y, 0, 0, 0);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;
            xFixed += (xVel << 8);
            yFixed += (yVel << 8);
            currentX = xFixed >> 16;
            currentY = yFixed >> 16;
            updateDynamicSpawn();
            Camera camera = services().camera();
            int screenRelX = currentX - camera.getX() - 0xA0;
            if (Math.abs(screenRelX) >= SCREEN_BOUNDS_HALF_WIDTH) {
                setDestroyed(true);
            }
        }

        @Override
        public void syncPositionWithParent() {}

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.DEZ_SILVER_SONIC);
            if (renderer == null || !renderer.isReady()) return;
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
        }
    }
}
