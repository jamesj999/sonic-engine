package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcRequests;
import com.openggf.game.sonic2.objects.EggPrisonObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * MCZ Act 2 Boss (Object 0x57) - Drill-digger boss.
 * ROM Reference: s2.asm:65272-65922 (Obj57)
 *
 * The boss descends from the ceiling with drills running vertically, spawning
 * falling rocks and spikes. At ground level the drills rotate to horizontal
 * and the boss moves back and forth. After a countdown (or hitting arena walls)
 * it re-ascends to repeat the cycle.
 *
 * Multi-sprite rendering: The ROM uses 4 child sprites (sub2-sub5) within the
 * main object for diggers, face, and hover thingies. Our engine renders them
 * as separate frame draws in appendRenderCommands().
 *
 * State Machine (boss_routine):
 * - SUB0 (0x00): Rising / countdown before descent
 * - SUB2 (0x02): Descending with rocks/spikes
 * - SUB4 (0x04): Reaching ground, digger rotation to horizontal
 * - SUB6 (0x06): Horizontal battle phase
 * - SUB8 (0x08): Defeated - explosions
 * - SUBA (0x0A): Hover down after defeat
 * - SUBC (0x0C): Escape right
 */
public class Sonic2MCZBossInstance extends AbstractBossInstance
        implements SpawnRewindRecreatable, TouchResponseListener {

    // State machine constants (ROM: boss_routine values)
    private static final int SUB0_RISING = 0x00;
    private static final int SUB2_DESCENDING = 0x02;
    private static final int SUB4_GROUND = 0x04;
    private static final int SUB6_HORIZONTAL = 0x06;
    private static final int SUB8_DEFEATED = 0x08;
    private static final int SUBA_HOVER_DOWN = 0x0A;
    private static final int SUBC_ESCAPE = 0x0C;

    // Position constants (ROM: s2.asm:65294-65295)
    private static final int SPAWN_X = 0x21A0;
    private static final int SPAWN_Y = 0x560;
    /** Y threshold above screen where boss repositions (ROM: cmpi.w #$560) */
    private static final int CEILING_Y = 0x560;
    /** Y threshold where stones stop spawning (ROM: cmpi.w #$620) */
    private static final int STONE_THRESHOLD_Y = 0x620;
    /** Y position at ground level (ROM: cmpi.w #$660) */
    private static final int GROUND_Y = 0x660;
    /** Arena left boundary (ROM: cmpi.w #$2120) */
    private static final int ARENA_LEFT_X = 0x2120;
    /** Arena right boundary (ROM: cmpi.w #$2200) */
    private static final int ARENA_RIGHT_X = 0x2200;
    /** Player X threshold for side selection (ROM: cmpi.w #$2190) */
    private static final int PLAYER_X_THRESHOLD = 0x2190;
    /** Stone/spike spawn Y position (ROM: move.w #$5F0,y_pos(a1)) */
    private static final int DEBRIS_SPAWN_Y = 0x5F0;
    /** Stone/spike minimum X (ROM: addi.w #$20F0,d1) */
    private static final int DEBRIS_MIN_X = 0x20F0;
    /** Stone/spike maximum X (ROM: cmpi.w #$2230,d1) */
    private static final int DEBRIS_MAX_X = 0x2230;
    /** Debris delete boundary (ROM: cmpi.w #$6F0) */
    private static final int DEBRIS_DELETE_Y = 0x6F0;

    // Velocity constants (8.8 fixed-point)
    /** Initial descent velocity (ROM: move.w #$C0,(Boss_Y_vel).w) */
    private static final int INITIAL_DESCENT_VEL = 0xC0;
    /** Fast descent velocity after reposition (ROM: move.w #$100,(Boss_Y_vel).w) */
    private static final int FAST_DESCENT_VEL = 0x100;
    /** Ascent velocity (ROM: move.w #-$C0,(Boss_Y_vel).w) */
    private static final int ASCENT_VEL = -0xC0;
    /** Horizontal velocity (ROM: move.w #-$200,(Boss_X_vel).w) */
    private static final int HORIZONTAL_VEL = 0x200;
    /** Escape X velocity (ROM: move.w #$400,(Boss_X_vel).w) */
    private static final int ESCAPE_X_VEL = 0x400;
    /** Escape Y velocity (ROM: move.w #-$40,(Boss_Y_vel).w) */
    private static final int ESCAPE_Y_VEL = -0x40;
    /** SubA gravity (ROM: addi.w #$10,(Boss_Y_vel).w) */
    private static final int SUBA_GRAVITY = 0x10;
    /** SubA upward acceleration (ROM: subi_.w #8,(Boss_Y_vel).w) */
    private static final int SUBA_ACCEL_UP = -8;
    /** Digger fall gravity (ROM: addi.w #$38,obj57_sub5_y_vel) */
    private static final int DIGGER_FALL_GRAVITY = 0x38;

    // Timing constants
    /** Initial countdown before first descent (ROM: move.w #$28,(Boss_Countdown).w) */
    private static final int INITIAL_COUNTDOWN = 0x28;
    /** Countdown between cycles (ROM: move.w #$64,(Boss_Countdown).w) */
    private static final int CYCLE_COUNTDOWN = 0x64;
    /** Countdown threshold for enabling collision in SUB6 (ROM: cmpi.w #$28) */
    private static final int COLLISION_ENABLE_THRESHOLD = 0x28;
    /** Defeat explosion duration (ROM: move.w #$B3,(Boss_Countdown).w) */
    private static final int DEFEAT_COUNTDOWN = 0xB3;
    /** ROM BossCollision_MCZ side-digger HURT size: width=4, height=4 (Touch_Sizes index 0). */
    private static final int SIDE_DRILL_HURT_FLAGS = 0x80;
    /** ROM BossCollision_MCZ2 upward-digger HURT size: width=4, height=$10 (Touch_Sizes index 4). */
    private static final int UPWARD_DRILL_HURT_FLAGS = 0x84;
    /** ROM BossCollision_MCZ side-digger X offset (x_pos +/- $30). */
    private static final int SIDE_DRILL_X_OFFSET = 0x30;
    /** ROM BossCollision_MCZ side-digger Y offset (y_pos + 4). */
    private static final int SIDE_DRILL_Y_OFFSET = 4;
    /** ROM BossCollision_MCZ2 upward-digger X offsets (x_pos +/- $14). */
    private static final int UPWARD_DRILL_X_OFFSET = 0x14;
    /** ROM BossCollision_MCZ2 upward-digger Y offset (y_pos - $20). */
    private static final int UPWARD_DRILL_Y_OFFSET = -0x20;

    // Animation frame indices (from ROM Ani_obj57 / Obj57_MapUnc_316EC)
    // Main vehicle body
    private static final int FRAME_BODY_LIGHT_ON = 0;     // frame 0: tile $09 = lamp ON
    private static final int FRAME_BODY_LIGHT_OFF = 1;   // frame 1: tile $00 = lamp OFF
    // Digger frames (vertical)
    private static final int FRAME_DIGGER_VERT_1 = 2;    // frame 2: vertical digger phase 1
    private static final int FRAME_DIGGER_VERT_2 = 3;    // frame 3: vertical digger phase 2
    private static final int FRAME_DIGGER_VERT_3 = 4;    // frame 4: vertical digger phase 3
    // Hover thingies
    private static final int FRAME_HOVER_FIRE_ON_1 = 5;  // frame 5: hover fire on small
    private static final int FRAME_HOVER_FIRE_ON_2 = 6;  // frame 6: hover fire on large
    private static final int FRAME_HOVER_NO_FIRE = 7;    // frame 7: hover no fire
    // Digger frames (horizontal)
    private static final int FRAME_DIGGER_DIAG = 8;      // frame 8: diagonal transition
    private static final int FRAME_DIGGER_HORIZ_1 = 9;   // frame 9: horizontal phase 1
    private static final int FRAME_DIGGER_HORIZ_2 = 10;  // frame 10: horizontal phase 2
    private static final int FRAME_DIGGER_HORIZ_3 = 11;  // frame 11: horizontal phase 3
    // Face frames
    private static final int FRAME_FACE_NORMAL_1 = 14;   // frame 14 ($E): Robotnik normal 1
    private static final int FRAME_FACE_NORMAL_2 = 15;   // frame 15 ($F): Robotnik normal 2
    private static final int FRAME_FACE_GRIN_1 = 16;     // frame 16 ($10): Robotnik grin 1
    private static final int FRAME_FACE_GRIN_2 = 17;     // frame 17 ($11): Robotnik grin 2
    private static final int FRAME_FACE_HIT = 18;        // frame 18 ($12): Robotnik hit/grin when hit
    private static final int FRAME_FACE_BURNT = 19;      // frame 19 ($13): Robotnik burnt face

    // Digger offset from center (ROM: subi.w #$28,sub5_x_pos)
    private static final int DIGGER_X_OFFSET = 0x28;

    // Internal state
    private int countdown;
    /** Publication latch; keeps an equality-timed animal/explosion request retryable. */
    private boolean animalExplosionSubmitted;
    private boolean flipped; // render_flags.x_flip
    private boolean screenShaking; // ROM: Screen_Shaking_Flag
    private int sineCounter;
    private int currentVIntRunCount;

    // ── Digger animation sequences (pre-expanded from ROM Ani_obj57 chained anims) ──
    // Speed byte is always 1, so each data frame shows for 2 ticks.

    /** Startup chain: anims 3->4 (played on init before entering vertical loop) */
    private static final int[] DIGGER_SEQ_STARTUP = {
        2,2,2,2,2, 3,3,3,3,3, 4,4,4,4,
        2,2,2,2, 3,3,3, 4,4,4, 2,2, 3,3
    };

    /** Vertical->Horizontal transition chain: anims 6->7->8->9 */
    private static final int[] DIGGER_SEQ_VERT_TO_HORIZ = {
        2,3,4,4, 2,2, 3,3,3, 4,4,4, 2,2,2,
        2, 3,3,3,3, 4,4,4,4,4, 2, 8,8,8,
        9,9,9,9,9, 10,10,10,10,10, 11,11,11,11,
        9,9,9,9, 10,10,10, 11,11,11, 9,9, 10,10
    };

    /** Horizontal->Vertical transition chain: anims B->C->3->4 */
    private static final int[] DIGGER_SEQ_HORIZ_TO_VERT = {
        9,10,11,11, 9,9, 10,10,10, 11,11,11, 9,9,9,
        9, 10,10,10,10, 11,11,11,11,11, 9, 8,8,8,8,
        2,2,2,2,2, 3,3,3,3,3, 4,4,4,4,
        2,2,2,2, 3,3,3, 4,4,4, 2,2, 3,3
    };

    /** Vertical steady-state loop: anim 5 cycle {4,2,3} */
    private static final int[] DIGGER_LOOP_VERT = {4, 2, 3};

    /** Horizontal steady-state loop: anim A cycle {11,9,10} */
    private static final int[] DIGGER_LOOP_HORIZ = {11, 9, 10};

    // Animation state (simplified from ROM's Boss_AnimationArray)
    private int hoverFrame;
    private int hoverAnimTimer;
    private int diggerFrame;         // Current digger display frame
    private int diggerAnimTimer;     // Counts down from 1; frame advances when it underflows
    private int[] diggerSequence;    // Current transition sequence (null = in loop mode)
    private int diggerSeqIndex;      // Position within diggerSequence
    private int[] diggerLoopFrames;  // Current steady-state loop array
    private int diggerLoopIndex;     // Position within diggerLoopFrames
    private int bodyFrame;
    private int faceFrame;
    private int faceAnimTimer;
    private int faceAnimIndex;
    // ROM: boss_hurt_sonic flag - set by collision system when Sonic is hurt by
    // the boss's drills (BossCollision_MCZ checks invulnerable_time(a0) == $78),
    // NOT when Sonic hits the boss. Causes Eggman to grin before reascending.
    private boolean bossHurtSonic;

    // Defeat-specific state
    // Digger fall-apart positions (ROM: obj57_sub5/sub2 y_pos2, y_vel)
    private int leftDiggerXFixed;
    private int leftDiggerYFixed;
    private int leftDiggerYVel;
    private int rightDiggerXFixed;
    private int rightDiggerYFixed;
    private int rightDiggerYVel;
    private boolean diggersDetached;

    public Sonic2MCZBossInstance(ObjectSpawn spawn) {
        super(spawn, "MCZ Boss");
    }

    @Override
    protected void initializeBossState() {
        // ROM: Obj57_Init (s2.asm:65285-65327)
        state.x = SPAWN_X;
        state.y = SPAWN_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        // ROM: move.w #$C0,(Boss_Y_vel).w - initial descent
        state.xVel = 0;
        state.yVel = INITIAL_DESCENT_VEL;

        // ROM: move.b #2,boss_routine(a0) - boss starts descending from spawn
        state.routineSecondary = SUB2_DESCENDING;

        flipped = false;
        screenShaking = true; // ROM: move.b #1,(Screen_Shaking_Flag).w
        sineCounter = 0;
        bossHurtSonic = false;

        // ROM: move.w #$28,(Boss_Countdown).w
        countdown = INITIAL_COUNTDOWN;

        // Initialize animation state
        // ROM: Obj57_InitAnimationData (s2.asm:65330-65342)
        // Animation 0: hover thingies (fire on) - starts with frames 5,6 alternating
        hoverFrame = FRAME_HOVER_FIRE_ON_1;
        hoverAnimTimer = 1;
        // Animation 1: digger - ROM init sets anim 3 which chains 3->4->5 (vertical loop)
        diggerSequence = DIGGER_SEQ_STARTUP;
        diggerSeqIndex = 0;
        diggerFrame = DIGGER_SEQ_STARTUP[0];
        diggerAnimTimer = 1;
        diggerLoopFrames = DIGGER_LOOP_VERT;
        diggerLoopIndex = 0;
        // Animation 2: main vehicle - starts with light OFF
        bodyFrame = FRAME_BODY_LIGHT_OFF;
        // Animation 3: face - starts with frame 14 (normal)
        faceFrame = FRAME_FACE_NORMAL_1;
        faceAnimTimer = 7;
        faceAnimIndex = 0xD; // animation D = normal face

        // Digger fall-apart state
        leftDiggerYVel = -0x380;  // ROM: move.w #-$380,obj57_sub5_y_vel
        rightDiggerYVel = -0x380; // ROM: move.w #-$380,obj57_sub2_y_vel
        diggersDetached = false;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        currentVIntRunCount = vIntRunCount;

        // ROM's AnimateBoss is only called in Sub0/Sub2/Sub4/Sub6.
        // Sub8/SubA/SubC set mapframes directly and do NOT call AnimateBoss.
        if (state.routineSecondary < SUB8_DEFEATED) {
            updateAnimations();
        }

        switch (state.routineSecondary) {
            case SUB0_RISING -> updateSub0Rising(player);
            case SUB2_DESCENDING -> updateSub2Descending();
            case SUB4_GROUND -> updateSub4Ground(player);
            case SUB6_HORIZONTAL -> updateSub6Horizontal(player);
            case SUB8_DEFEATED -> updateSub8Defeated();
            case SUBA_HOVER_DOWN -> updateSubAHoverDown();
            case SUBC_ESCAPE -> updateSubCEscape();
        }
    }

    /**
     * SUB0: Boss ascending / countdown before repositioning and descending.
     * ROM: Obj57_Main_Sub0 (s2.asm:65362-65413)
     */
    private void updateSub0Rising(AbstractPlayableSprite player) {
        countdown--;
        if (countdown >= 0) {
            // Still counting down - continue rising or stationary
            // ROM: check countdown == $28 to disable collision
            if (countdown == COLLISION_ENABLE_THRESHOLD) {
                // ROM: move.b #0,(Boss_CollisionRoutine).w
                // The current engine response models only the generic body byte here.
            }
            handleHits();
            return;
        }

        // ROM: move.b #0,(Boss_AnimationArray+5).w - reset body light when countdown expires
        bodyFrame = FRAME_BODY_LIGHT_OFF;

        // Countdown finished - apply movement
        // ROM: bsr.w Boss_MoveObject
        applyBossMovement();

        // ROM: cmpi.w #$560,(Boss_Y_pos).w
        if (state.y > CEILING_Y) {
            // Still below ceiling, check for screen shake & stones
            if (state.y < STONE_THRESHOLD_Y) {
                setScreenShaking(true);
                spawnStoneOrSpike();
            }
            handleHits();
            return;
        }

        // Reached ceiling - reposition over player and start descent
        state.yVel = FAST_DESCENT_VEL;

        // ROM: Select X position based on player position
        if (player != null) {
            int playerX = player.getCentreX();
            if (playerX < PLAYER_X_THRESHOLD) {
                state.x = ARENA_RIGHT_X;
            } else {
                state.x = ARENA_LEFT_X;
            }
        }
        state.xFixed = state.x << 16;

        // ROM: addq.b #2,boss_routine(a0) - next routine (descending)
        state.routineSecondary = SUB2_DESCENDING;

        // Set flip based on player position
        flipped = false;
        if (player != null && player.getCentreX() > state.x) {
            flipped = true;
        }

        handleHits();
    }

    /**
     * SUB2: Boss descending with rocks/spikes falling.
     * ROM: Obj57_Main_Sub2 (s2.asm:65416-65440)
     */
    private void updateSub2Descending() {
        applyBossMovement();
        spawnStoneOrSpike();

        // ROM: cmpi.w #$620,(Boss_Y_pos).w
        if (state.y >= STONE_THRESHOLD_Y) {
            // Stop screen shake, move to next routine
            state.routineSecondary = SUB4_GROUND;
            setScreenShaking(false);
        }

        handleHits();
    }

    /**
     * SUB4: Reaching ground level, digger rotation to horizontal.
     * ROM: Obj57_Main_Sub4 (s2.asm:65443-65487)
     */
    private void updateSub4Ground(AbstractPlayableSprite player) {
        applyBossMovement();

        // ROM: cmpi.w #$660,(Boss_Y_pos).w
        if (state.y >= GROUND_Y) {
            state.y = GROUND_Y;
            state.yFixed = state.y << 16;

            // Transition to horizontal phase
            state.routineSecondary = SUB6_HORIZONTAL;

            // ROM: Set digger animations to horizontal transition (anim 6->7->8->9->A)
            diggerSequence = DIGGER_SEQ_VERT_TO_HORIZ;
            diggerSeqIndex = 0;
            diggerFrame = DIGGER_SEQ_VERT_TO_HORIZ[0];
            diggerAnimTimer = 1;
            diggerLoopFrames = DIGGER_LOOP_HORIZ;
            diggerLoopIndex = 0;

            // ROM: Set face to normal
            faceAnimIndex = 0xD;
            faceFrame = FRAME_FACE_NORMAL_1;
            faceAnimTimer = 7;

            // ROM: Set body light on - stays ON for entire SUB6 phase
            // ROM: $FC subanimation loop at anim_frame 2 holds frame 0 (light ON) indefinitely
            bodyFrame = FRAME_BODY_LIGHT_ON;

            // ROM: hover fire off
            hoverFrame = FRAME_HOVER_NO_FIRE;
            hoverAnimTimer = 0x30;

            // ROM: Set countdown and horizontal velocity
            countdown = CYCLE_COUNTDOWN;

            // Select direction based on player position
            flipped = false;
            if (player != null && player.getCentreX() > state.x) {
                flipped = true;
            }

            state.xVel = flipped ? HORIZONTAL_VEL : -HORIZONTAL_VEL;
            state.yVel = 0;
        }

        handleHits();
    }

    /**
     * SUB6: Horizontal battle phase - moving back and forth, hittable.
     * ROM: Obj57_Main_Sub6 (s2.asm:65490-65550)
     */
    private void updateSub6Horizontal(AbstractPlayableSprite player) {
        countdown--;

        // ROM: Enable collision when countdown drops below $28
        if (countdown <= COLLISION_ENABLE_THRESHOLD && countdown > 0) {
            // Collision enabled (handled by getCollisionFlags)
        }

        // ROM BossCollision_MCZ starts by clearing boss_hurt_sonic every
        // collision pass (s2.asm:85732-85733). While Boss_Countdown is still
        // nonnegative, Obj57_Main_Sub6 does not consume the flag
        // (s2.asm:65991-65996), so a previous-frame hurt cannot stay latched
        // until a later reascend decision.
        if (countdown >= 0) {
            bossHurtSonic = false;
        }

        if (countdown < 0) {
            // Timer expired - check for hurt sonic flag
            if (bossHurtSonic) {
                bossHurtSonic = false;
                // ROM: Obj57_Main_Sub6_ReAscend1 (s2.asm:65516-65518)
                // move.b #$30,7(a1) sets anim_frame=3, timer=0. Frame 3 in anim D's data
                // starts the grin subanimation: alternating $10/$11 for 8 occurrences
                // at speed 7. Total: 64 ticks of grinning.
                faceFrame = FRAME_FACE_GRIN_1;
                faceAnimTimer = 64;
                reascend();
                return;
            }

            // Apply movement and check arena boundaries
            applyBossMovement();

            // ROM: cmpi.w #$2120,(Boss_X_pos).w
            if (state.x <= ARENA_LEFT_X) {
                state.x = ARENA_LEFT_X;
                state.xFixed = state.x << 16;
                reascend();
                return;
            }

            // ROM: cmpi.w #$2200,(Boss_X_pos).w
            if (state.x >= ARENA_RIGHT_X) {
                state.x = ARENA_RIGHT_X;
                state.xFixed = state.x << 16;
                reascend();
                return;
            }
        }

        handleHits();
    }

    /**
     * Transition from horizontal phase back to rising (Sub0).
     * ROM: Obj57_Main_Sub6_ReAscend2 (s2.asm:65520-65532)
     */
    private void reascend() {
        state.xVel = 0;
        state.routineSecondary = SUB0_RISING;

        // ROM: Set digger animations back to vertical transition (anim B->C->3->4->5)
        diggerSequence = DIGGER_SEQ_HORIZ_TO_VERT;
        diggerSeqIndex = 0;
        diggerFrame = DIGGER_SEQ_HORIZ_TO_VERT[0];
        diggerAnimTimer = 1;
        diggerLoopFrames = DIGGER_LOOP_VERT;
        diggerLoopIndex = 0;

        // ROM: hover fire on
        hoverFrame = FRAME_HOVER_FIRE_ON_1;
        hoverAnimTimer = 0;

        // ROM: ReAscend2 does NOT touch Boss_AnimationArray bytes [4]/[5] (body light).
        // The $FC subanimation loop keeps showing frame 0 (light ON).
        // Light only turns off in SUB0 when countdown goes negative.

        // ROM: face normal
        faceAnimIndex = 0xD;
        faceFrame = FRAME_FACE_NORMAL_1;
        faceAnimTimer = 7;

        countdown = CYCLE_COUNTDOWN;
        state.yVel = ASCENT_VEL;
    }

    /**
     * SUB8: Boss defeated - explosions.
     * ROM: Obj57_Main_Sub8 (s2.asm:65725-65757)
     */
    private void updateSub8Defeated() {
        // ROM: st.b boss_defeated(a0) - state.defeated already set by triggerDefeat()
        setScreenShaking(false);

        countdown--;
        if (countdown >= 0) {
            // Explosion phase
            // ROM: move.b #$13,sub4_mapframe(a0) - burnt face
            faceFrame = FRAME_FACE_BURNT;
            // ROM: move.b #7,mainspr_mapframe(a0) - hover no fire
            hoverFrame = FRAME_HOVER_NO_FIRE;
            // Body is NOT touched - stays at frame 0 (light ON) from SUB6's $FC animation loop

            // ROM: Boss_LoadExplosion checks (Vint_runcount+3) & 7 == 0
            // Only spawn explosion every 8th frame (~22 total over 179 frames)
            if ((currentVIntRunCount & 7) == 0) {
                spawnDefeatExplosion();
            }
        } else {
            // Countdown finished - transition to hover down
            flipped = true; // ROM: bset x_flip
            state.xVel = 0;
            state.yVel = 0;
            state.routineSecondary = SUBA_HOVER_DOWN;

            // ROM: face grin when hit
            faceFrame = FRAME_FACE_HIT;

            // ROM: move.w #-$12,(Boss_Countdown).w
            countdown = -0x12;
        }

        transferDiggerPositions();
    }

    /**
     * SUBA: Slowly hovering down after defeat, no explosions.
     * ROM: Obj57_Main_SubA (s2.asm:65759-65817)
     */
    private void updateSubAHoverDown() {
        countdown++;
        if (countdown == 0) {
            // Reset Y velocity
            state.yVel = 0;
        } else if (countdown < 0) {
            // Still counting up to 0 - apply gravity
            if (state.y < STONE_THRESHOLD_Y) {
                countdown--; // Stay in this state longer if above threshold
            }
            state.yVel += SUBA_GRAVITY;
        } else if (countdown < 0x18) {
            // Accelerate upward
            state.yVel += SUBA_ACCEL_UP;
        } else if (countdown >= 0x18 && !animalExplosionSubmitted) {
            if (!Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_ANIMALS_MCZ,
                    Sonic2Constants.PLC_EXPLOSION)) return;
            animalExplosionSubmitted = true;
            // Play level music and load animal PLCs
            state.yVel = 0;
            services().playMusic(Sonic2Music.MYSTIC_CAVE.id);
        } else if (countdown >= 0x20) {
            // ROM writes to Boss_AnimationArray bytes here, but AnimateBoss is NEVER
            // called in SubA or SubC - those writes are inert. The actual mapframes
            // stay as set in Sub8: face=$12 (hit), hover=7 (no fire), body=0 (light on).
            state.routineSecondary = SUBC_ESCAPE;
        }

        // Apply movement with sine offset
        applyBossMovement();
        applySineOffset();
        transferDiggerPositions();
    }

    /**
     * SUBC: Escape right at high speed.
     * ROM: Obj57_Main_SubC (s2.asm:65820-65854)
     */
    private void updateSubCEscape() {
        state.xVel = ESCAPE_X_VEL;
        state.yVel = ESCAPE_Y_VEL;

        Camera camera = services().camera();
        // ROM: cmpi.w #$2240,(Camera_Max_X_pos).w
        if (camera.getMaxX() < 0x2240) {
            camera.setMaxX((short) (camera.getMaxX() + 2));
        } else {
            // Check if off screen
            if (!isOnScreen()) {
                // Spawn egg prison and delete self
                spawnEggPrison();
                setDestroyed(true);
                return;
            }
        }

        applyBossMovement();
        applySineOffset();
        transferDiggerPositions();
    }

    /**
     * Apply Boss_MoveObject: velocity to position.
     */
    private void applyBossMovement() {
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.updatePositionFromFixed();
    }

    /**
     * Apply sine wave offset to Y position.
     * ROM: Obj57_AddSinusOffset (s2.asm:65678-65685)
     */
    private void applySineOffset() {
        int sine = TrigLookupTable.sinHex(sineCounter & 0xFF);
        int offset = sine >> 6;
        state.y = (state.yFixed >> 16) + offset;
        sineCounter = (sineCounter + 2) & 0xFF;
    }

    /**
     * Transfer positions for digger sub-sprites during defeat fall-apart.
     * ROM: Obj57_TransferPositions / Obj57_FallApart (s2.asm:65553-65629)
     */
    private void transferDiggerPositions() {
        if (!state.defeated) {
            return;
        }
        if (!diggersDetached) {
            diggersDetached = true;
            // ROM: sub5 at boss_x ± $28, sub2 at boss_x (no offset)
            leftDiggerXFixed = flipped ? state.xFixed + (DIGGER_X_OFFSET << 16)
                                       : state.xFixed - (DIGGER_X_OFFSET << 16);
            leftDiggerYFixed = state.yFixed;
            rightDiggerXFixed = state.xFixed; // sub2 has no offset
            rightDiggerYFixed = state.yFixed;
        }

        // ROM: cmpi.w #$78,(Boss_Countdown).w - only fall apart when countdown < $78
        if (countdown >= 0x78) {
            return;
        }

        // Left digger drifts left and falls
        // ROM: subi_.w #1,sub5_x_pos(a0)
        leftDiggerXFixed -= (1 << 16);
        if (flipped) {
            leftDiggerXFixed += (2 << 16); // Opposite direction when flipped
        }
        leftDiggerYVel += DIGGER_FALL_GRAVITY;
        leftDiggerYFixed += (leftDiggerYVel << 8);
        if ((leftDiggerYFixed >> 16) >= DEBRIS_DELETE_Y) {
            leftDiggerYVel = 0;
        }

        // Right digger drifts right and falls
        // ROM: addi_.w #1,sub2_x_pos(a0)
        rightDiggerXFixed += (1 << 16);
        if (flipped) {
            rightDiggerXFixed -= (2 << 16); // Opposite direction when flipped
        }
        rightDiggerYVel += DIGGER_FALL_GRAVITY;
        rightDiggerYFixed += (rightDiggerYVel << 8);
        if ((rightDiggerYFixed >> 16) >= DEBRIS_DELETE_Y) {
            rightDiggerYVel = 0;
        }
    }

    /**
     * Spawn a stone or spike at random position.
     * ROM: Obj57_SpawnStoneSpike (s2.asm:65632-65665)
     */
    private void spawnStoneOrSpike() {
        if (services().objectManager() == null) {
            return;
        }

        // ROM: move.b (Vint_runcount+3).w,d1 - use the global gameplay frame
        // counter, not a boss-local phase, so stone/spike selection stays aligned
        // with Obj57_SpawnStoneSpike (s2.asm:66128-66160).
        int d1 = currentVIntRunCount & 0xFF;
        boolean isSpike;

        // ROM: sf d2 (d2=0=spike default), andi.b #$1F,d1 / beq.s Obj57_LoadStoneSpike
        // d2=0 means spike (falls through to set frame $14 + collision $B1)
        // d2=true means stone (branches over spike setup, keeps frame $D)
        if ((d1 & 0x1F) == 0) {
            isSpike = true; // ROM: d2=0 -> spike
        } else if ((d1 & 0x07) != 0) {
            return; // No spawn this frame
        } else {
            isSpike = false; // ROM: st.b d2 -> stone
        }

        // Generate random X position in [DEBRIS_MIN_X, DEBRIS_MAX_X]
        int x = Sonic2Rng.nextMczDebrisX(services().rng());

        spawnFreeChild(() -> new MCZFallingDebrisInstance(x, DEBRIS_SPAWN_Y, isSpike));
    }

    /**
     * Handle hit detection - delegates to parent but also checks for face animation.
     * ROM: Obj57_HandleHits (s2.asm:65668-65675)
     */
    private void handleHits() {
        // The parent's hitHandler manages invulnerability/flash
        // ROM: Obj57_HandleHits checks invulnerable_time == $1F for face grin
        if (state.invulnerable && state.invulnerabilityTimer == 0x1F) {
            faceFrame = FRAME_FACE_HIT;
            // ROM: $C0 encodes anim_frame=$C, timer=$0 in packed format.
            // Frame $C starts hit-face subanimation: 9 reps of frame $12 at speed 7 = 72 ticks.
            faceAnimTimer = 72;
        }
    }

    /**
     * Update animation state for all sub-sprites.
     * Simplified version of ROM's AnimateBoss with Boss_AnimationArray.
     */
    private void updateAnimations() {
        // Hover thingies animation (fire on/off cycle)
        if (hoverAnimTimer > 0) {
            hoverAnimTimer--;
        } else {
            // Toggle between fire frames
            if (hoverFrame == FRAME_HOVER_FIRE_ON_1) {
                hoverFrame = FRAME_HOVER_FIRE_ON_2;
                hoverAnimTimer = 1;
            } else if (hoverFrame == FRAME_HOVER_FIRE_ON_2) {
                hoverFrame = FRAME_HOVER_FIRE_ON_1;
                hoverAnimTimer = 1;
            }
            // FRAME_HOVER_NO_FIRE stays until explicitly changed
        }

        // Body light: frame set directly by state transitions (ON in SUB4->SUB6, OFF in SUB0 when countdown expires)
        // No auto-cycling needed - ROM uses $FC subanimation loop to hold frame indefinitely

        // Digger animation - simplified cycling through vertical or horizontal frames
        if (diggerAnimTimer > 0) {
            diggerAnimTimer--;
        } else {
            updateDiggerAnimation();
            diggerAnimTimer = 1;
        }

        // Face animation
        if (faceAnimTimer > 0) {
            faceAnimTimer--;
        } else {
            updateFaceAnimation();
            faceAnimTimer = 7;
        }
    }

    /**
     * Update digger frame based on current animation sequence or loop.
     * ROM: AnimateBoss processes chained animations via $FD (change anim) and
     * $FC (loop back) commands. We pre-expand the chains into flat arrays.
     */
    private void updateDiggerAnimation() {
        if (diggerSequence != null) {
            // Playing a transition sequence
            diggerSeqIndex++;
            if (diggerSeqIndex < diggerSequence.length) {
                diggerFrame = diggerSequence[diggerSeqIndex];
            } else {
                // Sequence finished - enter steady-state loop
                diggerSequence = null;
                diggerLoopIndex = 0;
                diggerFrame = diggerLoopFrames[0];
            }
        } else {
            // Steady-state loop
            diggerLoopIndex = (diggerLoopIndex + 1) % diggerLoopFrames.length;
            diggerFrame = diggerLoopFrames[diggerLoopIndex];
        }
    }

    /**
     * Update face animation frame.
     */
    private void updateFaceAnimation() {
        if (faceAnimIndex == 0xD) {
            // Normal face: alternate between normal frames
            faceFrame = (faceFrame == FRAME_FACE_NORMAL_1) ?
                    FRAME_FACE_NORMAL_2 : FRAME_FACE_NORMAL_1;
        }
        // Hit/grin/burnt frames are set directly and don't auto-cycle
    }

    private void setScreenShaking(boolean shaking) {
        this.screenShaking = shaking;
        services().gameState().setScreenShakeActive(shaking);
    }

    /** Returns true when Screen_Shaking_Flag is active (descent phases). */
    public boolean isScreenShaking() {
        return screenShaking;
    }

    private void spawnEggPrison() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn prisonSpawn = new ObjectSpawn(
                0x2180, 0x0660,
                Sonic2ObjectIds.EGG_PRISON,
                0, 0, false, 0
        );
        spawnChild(() -> new EggPrisonObjectInstance(prisonSpawn, "Egg Prison"));
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // ROM: move.b #8,boss_hitcount2(a0)
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // ROM: Obj57_HandleHits - face hit when invulnerability starts
        faceFrame = FRAME_FACE_HIT;
        // ROM: $C0 encodes anim_frame=$C, timer=$0. Hit-face subanimation = 72 ticks.
        faceAnimTimer = 72;
        // NOTE: bossHurtSonic is NOT set here. In the ROM, boss_hurt_sonic is set
        // by BossCollision_MCZ when the boss's drills hurt Sonic (checks
        // invulnerable_time(a0) == $78 on the player). It is NOT set when
        // Sonic successfully attacks the boss. See s2.asm:85248, 85273.
        // Harmful drill contact owns this flag in onTouchResponse(...); a successful
        // attack on the boss deliberately does not set it.
    }

    @Override
    protected int getCollisionSizeIndex() {
        return 0x0F; // ROM: move.b #$F,collision_flags(a0)
    }

    @Override
    public int getPreUpdateCollisionFlags() {
        // S2 runs TouchResponse once per character slot against shared object RAM.
        // If Sonic hits Obj57 first, BossHitHandler clears the live collision byte
        // before CPU Tails' later touch pass can read it. Position still comes from
        // the frame-start snapshot; only the mutable collision_flags byte must be live.
        return getCollisionFlags();
    }

    @Override
    public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
        int bodyFlags = getCollisionFlags();
        if (bodyFlags == 0) {
            return null;
        }

        TouchResponseProvider.TouchRegion body =
                new TouchResponseProvider.TouchRegion(state.x, state.y, bodyFlags);
        if (sideDrillCollisionRoutineActive()) {
            int drillX = flipped ? state.x + SIDE_DRILL_X_OFFSET : state.x - SIDE_DRILL_X_OFFSET;
            return new TouchResponseProvider.TouchRegion[] {
                new TouchResponseProvider.TouchRegion(
                        drillX,
                        state.y + SIDE_DRILL_Y_OFFSET,
                        SIDE_DRILL_HURT_FLAGS),
                body
            };
        }

        return new TouchResponseProvider.TouchRegion[] {
            new TouchResponseProvider.TouchRegion(
                    state.x + UPWARD_DRILL_X_OFFSET,
                    state.y + UPWARD_DRILL_Y_OFFSET,
                    UPWARD_DRILL_HURT_FLAGS),
            new TouchResponseProvider.TouchRegion(
                    state.x - UPWARD_DRILL_X_OFFSET,
                    state.y + UPWARD_DRILL_Y_OFFSET,
                    UPWARD_DRILL_HURT_FLAGS),
            body
        };
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (result.category() == TouchCategory.HURT
                && player != null
                && !player.isCpuControlled()
                && !player.getInvulnerable()) {
            // ROM BossCollision_MCZ sets boss_hurt_sonic when Boss_DoCollision
            // just raised the main character's invulnerable_time(a0) to $78.
            bossHurtSonic = true;
        }
    }

    private boolean sideDrillCollisionRoutineActive() {
        // ROM Obj57_Main_Sub6 writes Boss_CollisionRoutine=1 once countdown
        // reaches $28; Sub0 later clears it when its cycle countdown reaches $28.
        return state.routineSecondary == SUB6_HORIZONTAL && countdown <= COLLISION_ENABLE_THRESHOLD;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // MCZ boss has custom defeat logic
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        // Obj57_Main reads boss_routine(a0) ONCE at the top of the object's update and
        // jumps through Obj57_Main_Index (docs/s2disasm/s2.asm:65876-65890). The hit
        // handler Obj57_HandleHits_Main runs from inside the already-selected routine
        // (docs/s2disasm/s2.asm:66223-66226), and Obj57_FinalDefeat sets
        // Boss_Countdown=$B3 / boss_routine=8 (docs/s2disasm/s2.asm:66249-66254), so
        // Obj57_Main_Sub8 first runs on the NEXT frame. The engine runs touch responses
        // before this object's own update(), so defer the first defeat dispatch a frame.
        return true;
    }

    @Override
    protected void onDefeatStarted() {
        if (!isDefeatEntryPrepared() && !Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE)) return;
        // ROM: Obj57_FinalDefeat (s2.asm:65715-65722)
        countdown = DEFEAT_COUNTDOWN;
        state.routineSecondary = SUB8_DEFEATED;
    }

    @Override
    protected boolean prepareDefeatEntry() {
        return Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE);
    }

    @Override
    public int getCollisionFlags() {
        // Collision only enabled during horizontal phase when countdown < $28
        if (state.routineSecondary == SUB6_HORIZONTAL && countdown <= COLLISION_ENABLE_THRESHOLD && countdown > 0) {
            return super.getCollisionFlags();
        }
        // Also enabled during other phases that call HandleHits
        if (state.routineSecondary != SUB8_DEFEATED && state.routineSecondary != SUBA_HOVER_DOWN && state.routineSecondary != SUBC_ESCAPE) {
            return super.getCollisionFlags();
        }
        return 0;
    }

    @Override
    public int getPriorityBucket() {
        return 3; // ROM: move.b #3,priority(a0)
    }

    @Override
    public boolean isPersistent() {
        // Obj57 is event-spawned and its active routines end in Draw_Sprite, not
        // the shared MarkObjGone/off-screen unload tail. Its escape routine only
        // deletes after Camera_Max_X_pos reaches $2240 and the boss is off-screen
        // (Obj57_Main_SubC, docs/s2disasm/s2.asm:66316-66335).
        return true;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("sub=%02X cd=%04X flip=%s hurt=%s inv=%s:%02X hp=%02X",
                state.routineSecondary & 0xFF,
                countdown & 0xFFFF,
                flipped,
                bossHurtSonic,
                state.invulnerable,
                state.invulnerabilityTimer & 0xFF,
                state.hitCount & 0xFF);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MCZ_BOSS);
        if (renderer == null) return;

        int bx = state.x;
        int by = state.y;

        // VDP multi-sprite priority (front to back):
        //   mainspr(hover) > sub2(center digger) > sub3(body) > sub4(face) > sub5(offset digger)
        // Our engine: later drawFrameIndex calls render in front, so draw back-to-front.

        // 1. Offset digger (sub5) - BACK
        // ROM: sub5 at boss_x ± $28
        if (diggersDetached) {
            int leftX = leftDiggerXFixed >> 16;
            int leftY = leftDiggerYFixed >> 16;
            if (leftY < DEBRIS_DELETE_Y) {
                renderer.drawFrameIndex(diggerFrame, leftX, leftY, flipped, false);
            }
        } else {
            int offsetDiggerX = flipped ? bx + DIGGER_X_OFFSET : bx - DIGGER_X_OFFSET;
            renderer.drawFrameIndex(diggerFrame, offsetDiggerX, by, flipped, false);
        }

        // 2. Face (sub4)
        renderer.drawFrameIndex(faceFrame, bx, by, flipped, false);

        // 3. Body (sub3)
        renderer.drawFrameIndex(bodyFrame, bx, by, flipped, false);

        // 4. Center digger (sub2) - no X offset
        if (diggersDetached) {
            int rightX = rightDiggerXFixed >> 16;
            int rightY = rightDiggerYFixed >> 16;
            if (rightY < DEBRIS_DELETE_Y) {
                renderer.drawFrameIndex(diggerFrame, rightX, rightY, flipped, false);
            }
        } else {
            renderer.drawFrameIndex(diggerFrame, bx, by, flipped, false);
        }

        // 5. Hover thingies (mainspr) - FRONT
        renderer.drawFrameIndex(hoverFrame, bx, by, flipped, false);
    }

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
}
