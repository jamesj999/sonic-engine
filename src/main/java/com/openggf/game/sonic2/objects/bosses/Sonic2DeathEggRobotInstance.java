package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.scroll.SwScrlDez;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.function.Supplier;

/**
 * DEZ Death Egg Robot (Object 0xC7).
 * ROM Reference: s2.asm ObjC7 (Eggrobo)
 *
 * Final boss of Sonic 2. A giant mech with 10 articulated body parts and
 * 3 transient object types. Has 12 HP (not the usual 8). The head is the
 * only hittable part. Palette flash uses line 2.
 *
 * Body State Machine (routine_secondary, 8 sub-states):
 * - 0: Init - spawn 10 children, frame=3, priority=5, position children
 * - 2: WaitEggman - wait for Head's status.misc flag (Eggman boarding)
 * - 4: Countdown - 60 frames after music fade, then play final boss music
 * - 6: Rise - y_vel=-$100, rumbling sound, 121 frames ($79)
 * - 8: WaitReady - 31 frames ($1F), then collision_flags=$16, HP=12, init child collisions
 * - A: SelectAttack - cycle through attack pattern {2,0,2,4} using modulo-4 counter
 * - C: ExecuteAttack - run selected attack type
 * - E: Defeat - fall, bounce at floor Y=$15C, explode, then ending sequence
 *
 * 3 Attack Types (cycle: 2, 0, 2, 4):
 * - Type 0: Walk-and-Punch (forearm launch)
 * - Type 2: Jet-Stomp (fly up, target player, stomp down, screen shake)
 * - Type 4: Stomp-Turn-Bombs (walk toward player, drop 2 bombs)
 */
public class Sonic2DeathEggRobotInstance extends AbstractBossInstance implements RewindRecreatable {

    // ========================================================================
    // BODY STATE MACHINE CONSTANTS
    // ========================================================================

    /** Body routine_secondary values */
    private static final int BODY_INIT = 0x00;
    private static final int BODY_WAIT_EGGMAN = 0x02;
    private static final int BODY_COUNTDOWN = 0x04;
    private static final int BODY_RISE = 0x06;
    private static final int BODY_WAIT_READY = 0x08;
    private static final int BODY_SELECT_ATTACK = 0x0A;
    private static final int BODY_EXECUTE_ATTACK = 0x0C;
    private static final int BODY_DEFEAT = 0x0E;

    // ========================================================================
    // TIMING CONSTANTS (from ROM)
    // ========================================================================

    /** ROM: move.b #60,anim_frame_duration(a0) (loc_3D5A8) */
    private static final int COUNTDOWN_TIMER = 60;
    /** ROM: move.b #$79,anim_frame_duration(a0) (loc_3D5C2) */
    private static final int RISE_TIMER = 0x79;
    /** ROM: move.b #$1F,anim_frame_duration(a0) (loc_3D62E) */
    private static final int WAIT_READY_TIMER = 0x1F;
    /** ROM: move.b #$20,anim_frame_duration(a0) (loc_3D640) */
    private static final int ATTACK_SELECT_PAUSE = 0x20;
    /** ROM: move.b #$40,anim_frame_duration(a0) (loc_3D922) */
    private static final int DEFEAT_EXPLODE_TIMER = 0x40;
    /** ROM: move.b #60,objoff_2A(a0) - flash/invuln duration */
    private static final int DEZ_BOSS_INVULN_DURATION = 60; // $3C

    // ========================================================================
    // VELOCITY CONSTANTS (8.8 fixed-point)
    // ========================================================================

    /** ROM: move.w #-$100,y_vel(a0) (loc_3D5C2) */
    private static final int RISE_VELOCITY = -0x100;
    /** ROM: move.w #-$200,y_vel(a0) - jet stomp ascent */
    private static final int JET_ASCENT_VELOCITY = -0x200;
    /** ROM: move.w #$800,y_vel(a0) - jet stomp descent */
    private static final int JET_DESCENT_VELOCITY = 0x800;
    /** ROM: move.w #$800,d2 - forearm punch speed */
    private static final int FOREARM_PUNCH_SPEED = 0x800;

    // ========================================================================
    // COLLISION CONSTANTS
    // ========================================================================

    /** ROM: move.b #$16,collision_flags(a0) - body collision (hurts player) */
    static final int COLLISION_BODY = 0x16;
    /** ROM: move.b #$2A,collision_flags(a1) - head collision (hittable via Touch_Enemy routing) */
    static final int COLLISION_HEAD = 0x2A;
    /** HP = 12 (final boss, NOT the usual 8) */
    private static final int DEATH_EGG_ROBOT_HP = 12;

    /** Per-child collision flags from ObjC7_ChildCollision (s2.asm:83296-83306)
     *  Order matches ROM spawn order at loc_3D52A: Shoulder, FrontForearm, FrontLowerLeg, ... */
    static final int[] CHILD_COLLISION = {
            0x00,  // Shoulder
            0x9C,  // FrontForearm
            0x8F,  // FrontLowerLeg
            0x00,  // UpperArm
            0x86,  // FrontThigh
            0x2A,  // Head (hittable!)
            0x8B,  // Jet
            0x8F,  // BackLowerLeg
            0x9C,  // BackForearm
            0x8B   // BackThigh
    };

    // ========================================================================
    // ATTACK PATTERN (from ROM byte_3D680)
    // ========================================================================

    /** ROM: dc.b 2, 0, 2, 4 - attack type cycle */
    static final int[] ATTACK_PATTERN = { 2, 0, 2, 4 };

    // ========================================================================
    // DEFEAT CONSTANTS
    // ========================================================================

    /** ROM: cmpi.w #$15C,d0 - floor level for defeat bounce */
    static final int DEFEAT_FLOOR_Y = 0x15C;
    /** ROM: cmpi.w #$100,d0 - bounce threshold */
    static final int DEFEAT_BOUNCE_THRESHOLD = 0x100;
    /** ROM: move.w #$1000,(Camera_Max_X_pos).w */
    private static final int DEFEAT_CAMERA_MAX_X = 0x1000;
    /** ROM: cmpi.w #$840,(Camera_X_pos).w */
    private static final int DEFEAT_CAMERA_WALK_TARGET = 0x840;
    /** ROM: Player X threshold for ending trigger (ObjC7_SetupEnding, s2.asm:83050) */
    private static final int DEFEAT_ENDING_PLAYER_X = 0xEC0;
    /** ROM: Initial robot follow-offset behind player (ObjC7_SetupEnding) */
    private static final int INITIAL_ROBOT_FOLLOW_OFFSET = 0x20;
    /** ROM: Fade duration before credits transition ($16 = 22 frames) */
    private static final int FADE_DURATION = 0x16;
    /** Break-apart velocities for 8 body parts (from ObjC7_BreakSpeeds, s2.asm:83258-83267) */
    static final int[][] BREAK_VELOCITIES = {
            {  0x200, -0x400 },  // Shoulder
            { -0x100, -0x100 },  // FrontLowerLeg
            {  0x300, -0x300 },  // FrontForearm
            { -0x100, -0x400 },  // UpperArm
            {  0x180, -0x200 },  // FrontThigh
            { -0x200, -0x300 },  // BackLowerLeg
            {  0x000, -0x400 },  // BackForearm
            {  0x100, -0x300 }   // BackThigh
    };

    // ========================================================================
    // MAPPING FRAME INDICES (from ObjC7_MapUnc_3E5F8)
    // ========================================================================

    private static final int FRAME_BODY = 3;
    private static final int FRAME_SHOULDER = 4;
    private static final int FRAME_ARM = 5;
    private static final int FRAME_FOREARM = 6;
    private static final int FRAME_THIGH = 0x0A;
    private static final int FRAME_LOWER_LEG = 0x0B;
    private static final int FRAME_JET_OFF = 0x0C;
    private static final int FRAME_BOMB = 0x0E;

    // ========================================================================
    // CHILD POSITION DELTAS (from ObjC7_ChildDeltas, s2.asm:83536-83544)
    // ========================================================================

    /** Child deltas: [childIndex]{dx, dy} - only 7 children use these */
    private static final int[][] CHILD_DELTAS = {
            { -4, 60 },   // FrontLowerLeg (objoff_2E)
            { -12, 8 },   // FrontForearm (objoff_30)
            { 12, -8 },   // UpperArm (objoff_32)
            { 4, 36 },    // FrontThigh (objoff_34)
            { -4, 60 },   // BackLowerLeg (objoff_3A)
            { -12, 8 },   // BackForearm (objoff_3C)
            { 4, 36 }     // BackThigh (objoff_3E)
    };

    /** Shoulder position delta from body (loc_3E282 with byte_3DA38) */
    private static final int SHOULDER_DX = 0x0C;
    private static final int SHOULDER_DY = -0x14;

    /** Head position delta from body (loc_3E282 with byte_3DBF2) */
    private static final int HEAD_DX = 0;
    private static final int HEAD_DY = -0x34;

    /** Jet position delta from body (loc_3E282 with byte_3DC70) */
    private static final int JET_DX = 0x38;
    private static final int JET_DY = 0x18;

    /** Bomb spawn delta from body (loc_3E282 with byte_3DF00: dc.w $38, -$14) */
    private static final int BOMB_SPAWN_DX = 0x38;
    private static final int BOMB_SPAWN_DY = -0x14;

    // ========================================================================
    // ROM-ACCURATE GROUP ANIMATION SYSTEM (loc_3E1AA)
    //
    // Each signed byte delta is applied as: (byte << 4) << 8 = byte << 12
    // added to 32-bit position. Net effect: byte / 16 pixels per substep.
    // Substep counter increments each frame; when it reaches the speed
    // threshold, the keyframe advances.
    //
    // Keyframe format: { pieceCount, speed, {objoffId, dx, dy}... }
    //   objoffId: 0 = body, or the child's objoff slot index
    //   dx, dy: signed bytes (Java uses int for clarity)
    //
    // Script format: keyframe table reference + sequence of frame indices.
    //   Frame byte flags: $40 = play sound, $80 = reverse, $C0 = end.
    // ========================================================================

    // --- objoff slot identifiers (matching ROM SST offsets) ---
    private static final int SLOT_BODY = 0;
    private static final int SLOT_FRONT_LOWER_LEG = 0x2E;  // objoff_2E
    private static final int SLOT_FRONT_FOREARM = 0x30;     // objoff_30
    private static final int SLOT_UPPER_ARM = 0x32;         // objoff_32
    private static final int SLOT_FRONT_THIGH = 0x34;       // objoff_34
    private static final int SLOT_BACK_LOWER_LEG = 0x3A;    // objoff_3A
    private static final int SLOT_BACK_FOREARM = 0x3C;      // objoff_3C
    private static final int SLOT_BACK_THIGH = 0x3E;        // objoff_3E

    // --- Keyframe tables (from ROM ObjC7_GroupAni_*) ---
    // Each keyframe: { speed, slot0, dx0, dy0, slot1, dx1, dy1, ... }
    // First element is speed threshold. Then triplets of (slot, dx, dy).

    // ObjC7_GroupAni_3E318: 9 keyframes for half-step walk (off_3E2F6/off_3E300/off_3E30A)
    private static final int[][] HALF_STEP_KEYFRAMES = {
        // 0: byte_3E32A - speed 8
        { 8,  SLOT_BODY,0xE0,0x0C, SLOT_FRONT_FOREARM,0xE0,0x0C, SLOT_UPPER_ARM,0xE0,0x0C, SLOT_BACK_FOREARM,0xE0,0x0C, SLOT_FRONT_THIGH,0xF8,0x04, SLOT_BACK_THIGH,0xF8,0x04 },
        // 1: byte_3E33E - speed 8
        { 8,  SLOT_BODY,0xEC,0x14, SLOT_FRONT_FOREARM,0xEC,0x14, SLOT_UPPER_ARM,0xEC,0x14, SLOT_BACK_FOREARM,0xEC,0x14, SLOT_FRONT_THIGH,0xFA,0x06, SLOT_BACK_THIGH,0xFA,0x06 },
        // 2: byte_3E352 - speed 8
        { 8,  SLOT_BODY,0xF8,0x14, SLOT_FRONT_FOREARM,0xF8,0x14, SLOT_UPPER_ARM,0xF8,0x14, SLOT_BACK_FOREARM,0xF8,0x14, SLOT_FRONT_THIGH,0xFE,0x04, SLOT_BACK_THIGH,0xFE,0x04 },
        // 3: byte_3E366 - speed 8
        { 8,  SLOT_BODY,0xFC,0x0C, SLOT_FRONT_FOREARM,0xFC,0x0C, SLOT_UPPER_ARM,0xFC,0x0C, SLOT_BACK_FOREARM,0xFC,0x0C, SLOT_FRONT_THIGH,0x00,0x02, SLOT_BACK_THIGH,0x00,0x02 },
        // 4: byte_3E37A - speed 8 (body only, standing still)
        { 8,  SLOT_BODY,0x00,0x00 },
        // 5: byte_3E380 - speed 8
        { 8,  SLOT_BODY,0x04,0xE8, SLOT_FRONT_FOREARM,0x04,0xE8, SLOT_UPPER_ARM,0x04,0xE8, SLOT_BACK_FOREARM,0x04,0xE8, SLOT_FRONT_THIGH,0x02,0xFA, SLOT_BACK_THIGH,0x02,0xFA },
        // 6: byte_3E394 - speed 8
        { 8,  SLOT_BODY,0x0C,0xE8, SLOT_FRONT_FOREARM,0x0C,0xE8, SLOT_UPPER_ARM,0x0C,0xE8, SLOT_BACK_FOREARM,0x0C,0xE8, SLOT_FRONT_THIGH,0x04,0xFC, SLOT_BACK_THIGH,0x04,0xFC },
        // 7: byte_3E3A8 - speed 8
        { 8,  SLOT_BODY,0x18,0xF4, SLOT_FRONT_FOREARM,0x18,0xF4, SLOT_UPPER_ARM,0x18,0xF4, SLOT_BACK_FOREARM,0x18,0xF4, SLOT_FRONT_THIGH,0x04,0xFC, SLOT_BACK_THIGH,0x04,0xFC },
        // 8: byte_3E3BC - speed 8
        { 8,  SLOT_BODY,0x18,0xFC, SLOT_FRONT_FOREARM,0x18,0xFC, SLOT_UPPER_ARM,0x18,0xFC, SLOT_BACK_FOREARM,0x18,0xFC, SLOT_FRONT_THIGH,0x06,0xFE, SLOT_BACK_THIGH,0x06,0xFE },
    };

    // ObjC7_GroupAni_3E3D8: 3 keyframes for crouch/rise (off_3E3D0)
    private static final int[][] CROUCH_KEYFRAMES = {
        // 0: byte_3E3DE - speed $10 (crouch down: all children Y +4)
        { 0x10, SLOT_BODY,0x00,0x04, SLOT_FRONT_FOREARM,0x00,0x04, SLOT_UPPER_ARM,0x00,0x04, SLOT_BACK_FOREARM,0x00,0x04, SLOT_FRONT_THIGH,0x00,0x04, SLOT_BACK_THIGH,0x00,0x04 },
        // 1: byte_3E3F2 - speed $10 (hold: body only, no movement)
        { 0x10, SLOT_BODY,0x00,0x00 },
        // 2: byte_3E3F8 - speed 8 (spring up: all children Y -8)
        { 8,    SLOT_BODY,0x00,0xF8, SLOT_FRONT_FOREARM,0x00,0xF8, SLOT_UPPER_ARM,0x00,0xF8, SLOT_BACK_FOREARM,0x00,0xF8, SLOT_FRONT_THIGH,0x00,0xF8, SLOT_BACK_THIGH,0x00,0xF8 },
    };

    // ObjC7_GroupAni_3E438: 12 keyframes for full walk cycle (off_3E40C/off_3E42C)
    private static final int[][] WALK_CYCLE_KEYFRAMES = {
        // 0: byte_3E450 - speed $20
        { 0x20, SLOT_FRONT_THIGH,0xF8,0xF8, SLOT_FRONT_LOWER_LEG,0xF8,0xF8, SLOT_BODY,0x00,0xFC, SLOT_FRONT_FOREARM,0x04,0xFB, SLOT_UPPER_ARM,0x03,0xFB, SLOT_BACK_FOREARM,0xFC,0xFB, SLOT_BACK_THIGH,0x00,0xFE },
        // 1: byte_3E468 - speed $10
        { 0x10, SLOT_FRONT_THIGH,0xF0,0xFC, SLOT_FRONT_LOWER_LEG,0xF0,0xFC, SLOT_BODY,0xF0,0xFC, SLOT_FRONT_FOREARM,0xF4,0xFB, SLOT_UPPER_ARM,0xF3,0xFB, SLOT_BACK_FOREARM,0xEC,0xFB, SLOT_BACK_THIGH,0xF8,0x00 },
        // 2: byte_3E480 - speed $10
        { 0x10, SLOT_FRONT_THIGH,0xF8,0x04, SLOT_FRONT_LOWER_LEG,0xF8,0x04, SLOT_BODY,0xF8,0x04, SLOT_FRONT_FOREARM,0xFC,0x03, SLOT_UPPER_ARM,0xFB,0x03, SLOT_BACK_FOREARM,0xF4,0x03 },
        // 3: byte_3E494 - speed $10
        { 0x10, SLOT_FRONT_THIGH,0xFC,0x10, SLOT_FRONT_LOWER_LEG,0xF8,0x10, SLOT_BODY,0x00,0x08, SLOT_FRONT_FOREARM,0xF8,0x0A, SLOT_UPPER_ARM,0xFA,0x0A, SLOT_BACK_FOREARM,0x08,0x0A, SLOT_BACK_THIGH,0x00,0x08 },
        // 4: byte_3E4AC - speed $20
        { 0x20, SLOT_FRONT_THIGH,0xFE,0xFE, SLOT_BODY,0xF4,0xFC, SLOT_FRONT_FOREARM,0xF0,0xFD, SLOT_UPPER_ARM,0xF1,0xFD, SLOT_BACK_FOREARM,0xF8,0xFD, SLOT_BACK_THIGH,0xEC,0xFA, SLOT_BACK_LOWER_LEG,0xE8,0xFC },
        // 5: byte_3E4C4 - speed $20
        { 0x20, SLOT_BACK_THIGH,0xF8,0xFC, SLOT_BACK_LOWER_LEG,0xF8,0xFC, SLOT_FRONT_FOREARM,0xFC,0xFF, SLOT_UPPER_ARM,0xFD,0xFF, SLOT_BACK_FOREARM,0x04,0xFF },
        // 6: byte_3E4D6 - speed $10
        { 0x10, SLOT_BACK_THIGH,0xF0,0xFC, SLOT_BACK_LOWER_LEG,0xF0,0xFC, SLOT_BODY,0xF0,0xFC, SLOT_FRONT_FOREARM,0xEC,0xFB, SLOT_UPPER_ARM,0xED,0xFB, SLOT_BACK_FOREARM,0xF4,0xFB, SLOT_FRONT_THIGH,0xF8,0x00 },
        // 7: byte_3E4EE - speed $10
        { 0x10, SLOT_BACK_THIGH,0xF8,0x04, SLOT_BACK_LOWER_LEG,0xF8,0x04, SLOT_BODY,0xF8,0x04, SLOT_FRONT_FOREARM,0xF4,0x03, SLOT_UPPER_ARM,0xF5,0x03, SLOT_BACK_FOREARM,0xFC,0x03 },
        // 8: byte_3E502 - speed $10
        { 0x10, SLOT_BACK_THIGH,0xFC,0x10, SLOT_BACK_LOWER_LEG,0xF8,0x10, SLOT_BODY,0x00,0x08, SLOT_FRONT_FOREARM,0x08,0x0A, SLOT_UPPER_ARM,0x06,0x0A, SLOT_BACK_FOREARM,0xF8,0x0A, SLOT_FRONT_THIGH,0x00,0x08 },
        // 9: byte_3E51A - speed $20
        { 0x20, SLOT_BACK_THIGH,0xFE,0xFE, SLOT_BODY,0xF4,0xFC, SLOT_FRONT_FOREARM,0xF8,0xFD, SLOT_UPPER_ARM,0xF7,0xFD, SLOT_BACK_FOREARM,0xF1,0xFD, SLOT_FRONT_THIGH,0xEC,0xFA, SLOT_FRONT_LOWER_LEG,0xE8,0xFC },
        // 10: byte_3E532 - speed $20
        { 0x20, SLOT_FRONT_THIGH,0xF8,0xFC, SLOT_FRONT_LOWER_LEG,0xF8,0xFC, SLOT_FRONT_FOREARM,0x04,0xFF, SLOT_UPPER_ARM,0x03,0xFF, SLOT_BACK_FOREARM,0xFC,0xFF },
        // 11: byte_3E544 - speed $10 (landing: all children Y +8)
        { 0x10, SLOT_BACK_THIGH,0x00,0x08, SLOT_BACK_LOWER_LEG,0x00,0x08, SLOT_BODY,0x00,0x08, SLOT_FRONT_FOREARM,0x00,0x08, SLOT_UPPER_ARM,0x00,0x08, SLOT_BACK_FOREARM,0x00,0x08, SLOT_FRONT_THIGH,0x00,0x08 },
    };

    // --- Group animation script definitions ---
    // Each script entry: positive = keyframe index, negative = inline flag encoding
    // -1 = sound marker (plays SndID_Hammer at that point in the sequence)
    // <= -100 = reversed keyframe (e.g. -108 = reversed keyframe 8, computed as -(entry+100))
    // End of sequence = implicit when groupAnimFrameIdx reaches sequence.length

    // ROM script: off_3E2F6 — half-step walk forward (steps 0-3, end)
    // Keyframe table: HALF_STEP_KEYFRAMES
    private static final int SCRIPT_HALF_WALK_FWD = 0;
    // ROM script: off_3E300 — half-step walk backward (steps 5-8, end)
    private static final int SCRIPT_HALF_WALK_BWD = 1;
    // ROM script: off_3E30A — full walk cycle (steps 0-8, end)
    private static final int SCRIPT_FULL_WALK = 2;
    // ROM script: off_3E3D0 — crouch (steps 0-2, end)
    private static final int SCRIPT_CROUCH = 3;
    // ROM script: off_3E40C — walk cycle with hammer sounds (4 half-cycles)
    private static final int SCRIPT_WALK_ATTACK_FWD = 4;
    // ROM script: off_3E42C — walk backward reversed with hammer
    private static final int SCRIPT_WALK_ATTACK_BWD = 5;

    // Script data: sequences of keyframe indices and flags
    // Positive values = keyframe index; negative values = flags
    private static final int[][] SCRIPT_SEQUENCES = {
        // 0: off_3E2F6 — half-step walk forward
        { 0, 1, 2, 3 },
        // 1: off_3E300 — half-step walk backward
        { 5, 6, 7, 8 },
        // 2: off_3E30A — full walk cycle (used in stomp recovery)
        { 0, 1, 2, 3, 4, 5, 6, 7, 8 },
        // 3: off_3E3D0 — crouch
        { 0, 1, 2 },
        // 4: off_3E40C — walk attack forward (with hammer sounds at key points)
        // ROM: 0, 1, 2, 3, $40, SndID_Hammer, 4, 5, 6, 7, 8, $40, SndID_Hammer, ...
        // We encode sound triggers as negative markers and play them in the engine
        { 0, 1, 2, 3, -1/*sound*/, 4, 5, 6, 7, 8, -1/*sound*/, 9, 10, 1, 2, 3, -1/*sound*/, 4, 5, 6, 7, 8, -1/*sound*/ },
        // 5: off_3E42C — walk attack backward (reversed keyframes + landing)
        // ROM: $88, $87, $86, $85, $B, $40, SndID_Hammer
        // $88 = $80|8 = reversed keyframe 8, etc.
        { -108/*rev8*/, -107/*rev7*/, -106/*rev6*/, -105/*rev5*/, 11, -1/*sound*/ },
    };

    /** Get the keyframe table for a script */
    private static int[][] getKeyframeTable(int scriptId) {
        return switch (scriptId) {
            case SCRIPT_HALF_WALK_FWD, SCRIPT_HALF_WALK_BWD, SCRIPT_FULL_WALK -> HALF_STEP_KEYFRAMES;
            case SCRIPT_CROUCH -> CROUCH_KEYFRAMES;
            case SCRIPT_WALK_ATTACK_FWD, SCRIPT_WALK_ATTACK_BWD -> WALK_CYCLE_KEYFRAMES;
            default -> HALF_STEP_KEYFRAMES;
        };
    }

    // ========================================================================
    // HEAD GLOW ANIMATION (ROM: Ani_objC7_a)
    // Speed 7: frame changes every 8 VBlanks
    // Frames: $15,$15,$15,$15,$15,$15,$15,$15, 0, 1, 2, $FA (loop)
    // ========================================================================
    static final int[] HEAD_GLOW_FRAMES = {
        0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0, 1, 2
    };
    static final int HEAD_GLOW_SPEED = 7; // changes every (7+1)=8 frames

    // ========================================================================
    // JET FLAME ANIMATION (ROM: Ani_objC7_b)
    // ========================================================================
    // Anim 0: speed 1, $C, $D, $FF (idle flicker loop)
    static final int[] JET_ANIM_0 = { 0x0C, 0x0D };
    // Anim 1: speed 1, rising pattern (24 frames, $FA loop)
    static final int[] JET_ANIM_1 = {
        0x0C,0x0D,0x0C,0x0C,0x0D,0x0D,0x0C,0x0C,0x0C,0x0D,0x0D,0x0D,
        0x0C,0x0C,0x0C,0x0C,0x0C,0x0D,0x0D,0x0D,0x0D,0x0D,0x0D
    };
    // Anim 2: speed 1, descending pattern (23 frames, $FD -> go to anim 1)
    static final int[] JET_ANIM_2 = {
        0x0D,0x0D,0x0D,0x0D,0x0D,0x0D,0x0C,0x0C,0x0C,0x0C,0x0C,0x0D,
        0x0D,0x0D,0x0C,0x0C,0x0C,0x0D,0x0D,0x0C,0x0C,0x0D,0x0C
    };
    // Anim 3: speed 0, $D, $15, $FF (special: jet + head flicker)
    static final int[] JET_ANIM_3 = { 0x0D, 0x15 };

    // ========================================================================
    // TARGETING SENSOR ANIMATION (ROM: Ani_objC7_c)
    // Speed 3, frames: $13, $12, $11, $10, $16, $FF (loop)
    // ========================================================================
    static final int[] SENSOR_ANIM_FRAMES = { 0x13, 0x12, 0x11, 0x10, 0x16 };
    static final int SENSOR_ANIM_SPEED = 3;

    // ========================================================================
    // INTERNAL STATE
    // ========================================================================

    private int bodyRoutine;
    private int actionTimer;
    private int attackIndex;    // ROM: angle(a0) - cycles through ATTACK_PATTERN
    private int currentAttack;  // Current attack type (0, 2, or 4)
    private int attackPhase;    // ROM: prev_anim(a0) - sub-phase within attack
    private boolean facingLeft;
    private int currentFrame;

    // Flags for forearm punch coordination
    private boolean frontPunchTriggered;
    private boolean backPunchTriggered;

    // Group animation state (ROM: loc_3E1AA)
    private int groupAnimScript = -1;   // Currently playing script ID (-1 = none)
    private int groupAnimFrameIdx;      // Current index within the script sequence
    private int groupAnimSubStep;       // ROM: objoff_1F - sub-step counter within keyframe

    // Subpixel position tracking for body (32-bit fixed: 16.16)
    // Children track their own xFixed/yFixed via ArticulatedChild
    private long bodyXFixed;  // 32-bit position as long to avoid sign issues
    private long bodyYFixed;

    // Targeting sensor data
    private int targetedPlayerX; // ROM: objoff_28(a0) - reported by targeting sensor

    // Defeat sub-state
    private int defeatPhase;    // 0=fall, 2=explode, 4=walk, 6=setupEnding, 8=fade, 10=terminal

    // Setup-ending state (ObjC7_SetupEnding, s2.asm:83050-83124)
    private int robotFollowOffset;  // Robot trails player by this amount, decremented every 32 frames
    private int defeatFrameCounter; // Frame counter for setup-ending rumble timing
    private int defeatWalkFrameCounter; // Frames since loc_3D922 locked control before loc_3D93C force-right
    private int fadeTimer;          // Countdown for fade-to-white before credits

    // Child references (10 permanent)
    private ArticulatedChild shoulder;
    private ArticulatedChild frontLowerLeg;
    private ForearmChild frontForearm;
    private ArticulatedChild upperArm;
    private ArticulatedChild frontThigh;
    private HeadChild head;
    private JetChild jet;
    private ArticulatedChild backLowerLeg;
    private ForearmChild backForearm;
    private ArticulatedChild backThigh;

    // Targeting sensor (transient child)
    private SensorChild sensorChild;

    public Sonic2DeathEggRobotInstance(ObjectSpawn spawn) {
        super(spawn, "DeathEggRobot");
    }

    // ========================================================================
    // AbstractBossInstance OVERRIDES
    // ========================================================================

    @Override
    protected void initializeBossState() {
        bodyRoutine = BODY_INIT;
        state.routine = 0x02; // Body routine in ObjC7_Index
        currentFrame = FRAME_BODY;
        actionTimer = 0;
        attackIndex = 0;
        currentAttack = 0;
        attackPhase = 0;
        facingLeft = false; // ROM: x_flip = 0 (ObjC7_SubObjData render_flags bit 0 clear), art naturally faces LEFT
        defeatPhase = 0;
        robotFollowOffset = 0;
        defeatFrameCounter = 0;
        defeatWalkFrameCounter = 0;
        fadeTimer = 0;
        targetedPlayerX = 0;
        frontPunchTriggered = false;
        backPunchTriggered = false;
        groupAnimScript = -1;
        groupAnimFrameIdx = 0;
        groupAnimSubStep = 0;

        bodyXFixed = (long) state.x << 16;
        bodyYFixed = (long) state.y << 16;

        if (getSpawn().objectId() == Sonic2ObjectIds.DEATH_EGG_ROBOT) {
            // Spawn 10 permanent children (ROM: loc_3D52A). Rewind probe construction
            // uses a zero object id and must not leak child side effects.
            spawnChildren();
        }

        // Advance to WaitEggman (auto-skip Eggman boarding for now)
        bodyRoutine = BODY_WAIT_EGGMAN;
    }

    @Override
    public Sonic2DeathEggRobotInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new Sonic2DeathEggRobotInstance(ctx.spawn()));
    }

    private void spawnChildren() {
        // Front parts: priority 4 (in front of body at priority 5)
        shoulder = createPermanentChild(() -> new ArticulatedChild(this, "Shoulder", 4, FRAME_SHOULDER));
        frontLowerLeg = createPermanentChild(() -> new ArticulatedChild(this, "FrontLowerLeg", 4, FRAME_LOWER_LEG));
        frontForearm = createPermanentChild(() -> new ForearmChild(this, "FrontForearm", 4, true));
        upperArm = createPermanentChild(() -> new ArticulatedChild(this, "UpperArm", 4, FRAME_ARM));
        frontThigh = createPermanentChild(() -> new ArticulatedChild(this, "FrontThigh", 4, FRAME_THIGH));

        // Head: priority 4 (same as front parts, ROM: subObjData inherited)
        head = createPermanentChild(() -> new HeadChild(this, 4));

        // Jet: priority 4 (same as front parts, ROM: subObjData inherited)
        jet = createPermanentChild(() -> new JetChild(this, 4));
        // Back parts: priority 6 (behind body at priority 5).
        // ROM: Within the same VDP priority, earlier sprites appear in front. The body
        // is spawned before its children, so it appears in front of the back parts.
        // In the Java engine's painter's algorithm, later = in front (opposite of VDP).
        // Bucket 6 renders before bucket 5 (render loop goes high to low), so back parts
        // at priority 6 render first (behind), and the body at priority 5 renders after (in front).
        backLowerLeg = createPermanentChild(() -> new ArticulatedChild(this, "BackLowerLeg", 6, FRAME_LOWER_LEG));
        backForearm = createPermanentChild(() -> new ForearmChild(this, "BackForearm", 6, false));
        backThigh = createPermanentChild(() -> new ArticulatedChild(this, "BackThigh", 6, FRAME_THIGH));

        childComponents.add(shoulder);
        childComponents.add(frontForearm);
        childComponents.add(frontLowerLeg);
        childComponents.add(upperArm);
        childComponents.add(frontThigh);
        childComponents.add(head);
        childComponents.add(jet);
        childComponents.add(backLowerLeg);
        childComponents.add(backForearm);
        childComponents.add(backThigh);

        positionChildren();
    }

    private <T extends AbstractBossChild> T createPermanentChild(Supplier<T> factory) {
        return spawnFreeChild(factory);
    }

    @Override
    protected int getInitialHitCount() {
        return DEATH_EGG_ROBOT_HP;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // ROM: ObjC7_CheckHit - hits are processed on the body, head relays
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_BODY & 0x3F;
    }

    @Override
    public int getCollisionFlags() {
        if (bodyRoutine < BODY_WAIT_READY || state.defeated) {
            return 0; // No collision before fight starts or after defeat
        }
        if (state.invulnerable) {
            return 0;
        }
        return COLLISION_BODY;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return DEZ_BOSS_INVULN_DURATION;
    }

    @Override
    protected int getPaletteLineForFlash() {
        return 2; // DEZ boss flashes palette line 2, not 1
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // Custom defeat logic
    }

    @Override
    public boolean isPersistent() {
        // During the defeat walk/ending sequence, the robot body follows the player
        // far past its original spawn X ($0840). Without persistence, the Placement
        // system despawns it when the spawn leaves the camera window, killing the
        // explosion trail, player Y clamp, and credits trigger.
        return state.defeated;
    }

    /**
     * Route body hits through the same unified pathway as head hits.
     * ROM: ObjC7_CheckHit polls collision_flags on BOTH body and head, and both
     * go through the same flash timer and collision restoration logic. By routing
     * body hits through onHeadHit(), we prevent the inherited hitHandler.processHit()
     * from using the wrong defeat path (which would set state.defeated=true without
     * calling triggerDefeatSequence(), leaving state.invulnerable=true permanently).
     */
    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        onHeadHit();
    }

    // ========================================================================
    // MAIN UPDATE LOOP
    // ========================================================================

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (bodyRoutine) {
            case BODY_WAIT_EGGMAN -> updateWaitEggman();
            case BODY_COUNTDOWN -> updateCountdown();
            case BODY_RISE -> updateRise(vIntRunCount);
            case BODY_WAIT_READY -> updateWaitReady();
            case BODY_SELECT_ATTACK -> updateSelectAttack();
            case BODY_EXECUTE_ATTACK -> updateExecuteAttack(vIntRunCount, player);
            case BODY_DEFEAT -> updateDefeat(vIntRunCount, player);
        }
    }

    // ========================================================================
    // BODY STATE IMPLEMENTATIONS
    // ========================================================================

    /** State 2: WaitEggman - wait for the body's misc bit to be set.
     *  ROM loc_3D5A8: btst #status.npc.misc,status(a0) on the body's OWN status.
     *  That bit is set by the head at the end of its routine-6 countdown
     *  (loc_3DC2A), i.e. only after the head has observed Eggman board the
     *  cockpit, played its glow once, and counted down 64 frames. Polling the
     *  earlier p1_standing/boarding moment released the body ~150 frames early,
     *  walking its body west into Sonic's hurtbox (spurious HURT). */
    private void updateWaitEggman() {
        if (head != null && head.isBodyMiscSignaled()) {
            bodyRoutine = BODY_COUNTDOWN;
            actionTimer = COUNTDOWN_TIMER;
            services().fadeOutMusic();
        }
    }

    /** State 4: Countdown - 60 frames, then fade music */
    private void updateCountdown() {
        actionTimer--;
        if (actionTimer < 0) {
            bodyRoutine = BODY_RISE;
            actionTimer = RISE_TIMER;
            state.yVel = RISE_VELOCITY;
            // ROM: objoff_38 = jet. Set jet routine 4 to start jet animation.
            if (jet != null) {
                jet.setJetRoutine(4);
            }
            services().playMusic(Sonic2Music.FINAL_BOSS.id);
        }
    }

    /** State 6: Rise - move upward with rumbling sound */
    private void updateRise(int vIntRunCount) {
        actionTimer--;
        if (actionTimer == 0) {
            bodyRoutine = BODY_WAIT_READY;
            state.yVel = 0;
            actionTimer = WAIT_READY_TIMER;
            state.hitCount = DEATH_EGG_ROBOT_HP;
            initChildCollisions();
            if (jet != null) {
                jet.setJetRoutine(6);
            }
            return;
        }
        // ROM: SndID_Rumbling every frame during rise
        services().playSfx(Sonic2Sfx.RUMBLING.id);
        // ObjectMove (constant velocity, no gravity)
        bodyYFixed += ((long) state.yVel << 8);
        state.y = (int)(bodyYFixed >> 16);
        state.yFixed = (int) bodyYFixed;
        state.updatePositionFromFixed();
        positionChildren();
    }

    /** State 8: WaitReady - brief pause before attacks begin */
    private void updateWaitReady() {
        checkHit();
        actionTimer--;
        if (actionTimer < 0) {
            bodyRoutine = BODY_SELECT_ATTACK;
        }
    }

    /** State A: SelectAttack - pick next attack from pattern {2,0,2,4} */
    private void updateSelectAttack() {
        checkHit();
        bodyRoutine = BODY_EXECUTE_ATTACK;
        actionTimer = ATTACK_SELECT_PAUSE;

        // ROM: angle(a0) incremented, then andi #3
        attackIndex = (attackIndex + 1) & 3;
        currentAttack = ATTACK_PATTERN[attackIndex];
        attackPhase = 0;

        // ROM: for attack type 2 (jet stomp), signal jet
        if (currentAttack == 2 && jet != null) {
            jet.setJetRoutine(4);
            jet.setJetAnim(2);
        }
    }

    /** State C: ExecuteAttack - dispatch to current attack type */
    private void updateExecuteAttack(int vIntRunCount, AbstractPlayableSprite player) {
        checkHit();
        switch (currentAttack) {
            case 0 -> updateAttackWalkPunch(vIntRunCount, player);
            case 2 -> updateAttackJetStomp(vIntRunCount, player);
            case 4 -> updateAttackStompBombs(vIntRunCount, player);
        }
    }

    // ========================================================================
    // ATTACK TYPE 0: WALK AND PUNCH
    // ROM: loc_3D6AA (anim=0)
    // ========================================================================

    private void updateAttackWalkPunch(int vIntRunCount, AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> { // Wait $20 frames
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 2;
                    resetGroupAnim();
                }
            }
            case 2 -> { // Walk forward (ROM: off_3E40C walk cycle with hammer sounds)
                if (stepGroupAnimation(SCRIPT_WALK_ATTACK_FWD)) {
                    attackPhase = 4;
                    actionTimer = 0x40;
                }
            }
            case 4 -> { // Pause $40 frames
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 6;
                    resetGroupAnim();
                }
            }
            case 6 -> { // Walk backward (ROM: off_3E42C reversed walk cycle)
                if (stepGroupAnimation(SCRIPT_WALK_ATTACK_BWD)) {
                    bodyRoutine = BODY_SELECT_ATTACK;
                    actionTimer = 0x40;
                }
            }
        }
    }

    // ========================================================================
    // ATTACK TYPE 2: JET STOMP
    // ROM: loc_3D702 (anim=2)
    // ========================================================================

    private void updateAttackJetStomp(int vIntRunCount, AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> { // Wait
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 2;
                    resetGroupAnim();
                }
            }
            case 2 -> { // Crouch (ROM: off_3E3D0)
                if (stepGroupAnimation(SCRIPT_CROUCH)) {
                    attackPhase = 4;
                    actionTimer = 0x80;
                    state.xVel = 0;
                    state.yVel = JET_ASCENT_VELOCITY;
                }
            }
            case 4 -> { // Fly up for $80 frames
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 6;
                    state.yVel = 0;
                    // Spawn targeting sensor
                    targetedPlayerX = 0;
                    spawnSensor(player);
                    return;
                }
                // Fire sound every 32 frames
                if ((vIntRunCount & 0x1F) == 0) {
                    services().playSfx(Sonic2Sfx.FIRE.id);
                }
                // ObjectMove
                bodyYFixed += ((long) state.yVel << 8);
                state.y = (int)(bodyYFixed >> 16);
                state.yFixed = (int) bodyYFixed;
                state.updatePositionFromFixed();
                positionChildren();
            }
            case 6 -> { // Wait for sensor to report player X
                // ROM ObjC7 loc_3D784 reads the body's objoff_28 (the sensor's
                // reported X) at the START of the body's frame, but the targeting
                // sensor (ChildObjC7_TargettingSensor) is a separate object in a
                // HIGHER RAM slot, so it runs AFTER the body in ExecuteObjects and
                // writes objoff_28 only on its lock-on-report frame (loc_3DE62).
                // The body therefore observes the report ONE frame after the sensor
                // produces it. Check targetedPlayerX BEFORE advancing the sensor so
                // we read the prior frame's value, then advance the sensor for next
                // frame -- otherwise the engine descends one frame early (the body
                // reads the same-frame inline report), drifting every jet-stomp by a
                // frame across the DEZ fight.
                boolean sensorReported = targetedPlayerX != 0;
                if (sensorChild != null) {
                    sensorChild.update(vIntRunCount, player);
                }
                if (sensorReported) {
                    attackPhase = 8;
                    state.x = targetedPlayerX;
                    bodyXFixed = (long) state.x << 16;
                    state.xFixed = (int) bodyXFixed;
                    // Set facing based on position
                    facingLeft = targetedPlayerX < 0x780;
                    updateChildFacing();
                    state.yVel = JET_DESCENT_VELOCITY;
                    actionTimer = 0x20;
                }
            }
            case 8 -> { // Descend for $20 frames
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 0x0A;
                    state.yVel = 0;
                    // ROM: Screen_Shaking_Flag=1, DEZ_Shake_Timer=$40.
                    triggerDezScreenShake(0x40);
                    if (jet != null) {
                        jet.setJetRoutine(6);
                    }
                    services().playSfx(Sonic2Sfx.SMASH.id);
                    resetGroupAnim();
                    return;
                }
                bodyYFixed += ((long) state.yVel << 8);
                state.y = (int)(bodyYFixed >> 16);
                state.yFixed = (int) bodyYFixed;
                state.updatePositionFromFixed();
                positionChildren();
            }
            case 0x0A -> { // Stand up (ROM: off_3E30A full walk cycle)
                if (stepGroupAnimation(SCRIPT_FULL_WALK)) {
                    positionChildren();
                    boolean playerOnSameSide = isPlayerOnFacingSide(player);
                    // ROM: d0!=0 (player on facing side) -> spawn bombs (phase 0x0C)
                    //      d0==0 (player behind) -> return to select
                    if (playerOnSameSide) {
                        attackPhase = 0x0C;
                        actionTimer = 0x60;
                        spawnBombs(player);
                    } else {
                        bodyRoutine = BODY_SELECT_ATTACK;
                    }
                }
            }
            case 0x0C -> { // Wait after bombs
                actionTimer--;
                if (actionTimer < 0) {
                    bodyRoutine = BODY_SELECT_ATTACK;
                }
            }
        }
    }

    // ========================================================================
    // ATTACK TYPE 4: STOMP TURN BOMBS
    // ROM: loc_3D83C (anim=4)
    // ========================================================================

    private void updateAttackStompBombs(int vIntRunCount, AbstractPlayableSprite player) {
        switch (attackPhase) {
            case 0 -> { // Wait
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 2;
                    resetGroupAnim();
                    backPunchTriggered = false;
                }
            }
            case 2 -> { // Walk toward player (ROM: off_3E2F6 half-step forward)
                if (stepGroupAnimation(SCRIPT_HALF_WALK_FWD)) {
                    boolean playerOnSameSide = isPlayerOnFacingSide(player);
                    // ROM loc_3D856: d0!=0 (player on facing side) -> bombs and retreat
                    //                d0==0 (player behind) -> punch forward
                    if (playerOnSameSide) {
                        attackPhase = 10;
                        actionTimer = 0x20;
                        spawnBombs(player);
                    } else {
                        attackPhase = 4;
                        actionTimer = 0x40;
                        frontPunchTriggered = true;
                    }
                }
            }
            case 4 -> { // prev_anim 4 idle pause ($40 frames) — ROM loc_3D6C0
                // ROM: prev_anim 4 just counts anim_frame_duration down, then
                // advances prev_anim 4->6. No punch signal here.
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 6;
                    actionTimer = 0x40;
                }
            }
            case 6 -> { // prev_anim 6 — ROM loc_3D89E (s2.asm:82848-82857)
                // ROM loc_3D89E: subq.b #1,anim_frame_duration; bmi.s + ; rts
                //   On underflow: addq.b #2,prev_anim (6->8);
                //                 bset #p1_pushing,status(a0) (BACK punch signal, ONCE);
                //                 move.b #$40,anim_frame_duration.
                // CRITICAL: prev_anim 4 (loc_3D6C0, s2.asm:82647) advances 4->6 WITHOUT
                // resetting anim_frame_duration, so it enters prev_anim 6 already at -1.
                // loc_3D89E's subq therefore underflows on the VERY FIRST frame of
                // prev_anim 6, firing p1_pushing immediately (the 4->6 boundary) and
                // resetting the $40 timer for prev_anim 8. So the back punch is a
                // one-shot fired at the START of phase 6, NOT after a countdown.
                // The back forearm consumes p1_pushing via test-and-clear (bclr,
                // loc_3DD00, s2.asm:83372), so the one-shot never re-fires even if the
                // forearm's own punch outlasts this phase.
                backPunchTriggered = true; // one-shot edge, consumed by back forearm
                attackPhase = 8;
                actionTimer = 0x40;
            }
            case 8 -> { // prev_anim 8 idle pause ($40 frames) — ROM loc_3D6C0
                // ROM: after p1_pushing fires (6->8), prev_anim 8 idles $40 frames
                // before prev_anim $A walks backward. No punch signal here.
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 14;
                    resetGroupAnim();
                }
            }
            case 14 -> { // prev_anim $A walk backward — ROM loc_3D8B8 (off_3E300)
                if (stepGroupAnimation(SCRIPT_HALF_WALK_BWD)) {
                    bodyRoutine = BODY_SELECT_ATTACK;
                }
            }
            case 10 -> { // Wait after bombs (ROM: loc_3D6C0 at prev_anim=8)
                actionTimer--;
                if (actionTimer < 0) {
                    attackPhase = 12;  // advance to walk backward
                    resetGroupAnim();
                }
            }
            case 12 -> { // Walk backward before returning (ROM: loc_3D8B8 at prev_anim=$A)
                if (stepGroupAnimation(SCRIPT_HALF_WALK_BWD)) {
                    bodyRoutine = BODY_SELECT_ATTACK;
                }
            }
        }
    }

    // ========================================================================
    // DEFEAT SEQUENCE (State E)
    // ========================================================================

    private void updateDefeat(int vIntRunCount, AbstractPlayableSprite player) {
        switch (defeatPhase) {
            case 0 -> updateDefeatFall(vIntRunCount);
            case 2 -> updateDefeatExplode(vIntRunCount);
            case 4 -> updateDefeatWalkPlayer(vIntRunCount, player);
            case 6 -> updateDefeatSetupEnding(vIntRunCount, player);
            case 8 -> updateDefeatFade();
            case 10 -> {} // Terminal: ending triggered, waiting for game mode change
        }
    }

    /** Defeat phase 0: Fall with gravity, bounce at floor Y=$15C */
    private void updateDefeatFall(int vIntRunCount) {
        // ROM: Boss_LoadExplosion only spawns an explosion every 8th frame
        // (andi.b #7,(Vint_runcount+3).w / bne.s rts). vIntRunCount is the
        // Java equivalent of Vint_runcount at object execution.
        if ((vIntRunCount & 7) == 0) {
            spawnDefeatExplosion();
        }
        // ObjectMoveAndFall
        bodyYFixed += ((long) state.yVel << 8);
        state.yVel += GRAVITY;
        state.y = (int)(bodyYFixed >> 16);
        state.yFixed = (int) bodyYFixed;
        state.updatePositionFromFixed();

        // ROM: Head's ObjC7_Head routine calls loc_3E282 EVERY frame to reposition
        // relative to the parent body. Head is NOT included in ObjC7_Break — it stays
        // attached to the body throughout the defeat fall/bounce.
        positionNonAnimatedChildren();

        if (state.y >= DEFEAT_FLOOR_Y) {
            state.y = DEFEAT_FLOOR_Y;
            bodyYFixed = (long) state.y << 16;
            state.yFixed = (int) bodyYFixed;
            int absVel = state.yVel;
            if (absVel < 0) {
                defeatPhase = 2;
                actionTimer = DEFEAT_EXPLODE_TIMER;
                return;
            }
            int bounceVel = absVel >> 2;
            if (bounceVel >= DEFEAT_BOUNCE_THRESHOLD) {
                state.yVel = -bounceVel;
            } else {
                defeatPhase = 2;
                actionTimer = DEFEAT_EXPLODE_TIMER;
            }
        }
    }

    /** Defeat phase 2: 64 frames of explosions, then lock controls and walk player right */
    private void updateDefeatExplode(int vIntRunCount) {
        // ROM: Head stays attached during ground explosion phase (loc_3E282 every frame)
        positionNonAnimatedChildren();

        actionTimer--;
        if (actionTimer >= 0) {
            // ROM: Boss_LoadExplosion only spawns every 8th frame
            if ((vIntRunCount & 7) == 0) {
                spawnDefeatExplosion();
            }
        } else {
            defeatPhase = 4;
            defeatWalkFrameCounter = 0;
            Camera camera = services().camera();
            if (camera != null) {
                camera.setMaxX((short) DEFEAT_CAMERA_MAX_X);
                // ROM: Screen_Shaking_Flag=1, DEZ_Shake_Timer=$1000.
                triggerDezScreenShake(0x1000);
            }
        }
    }

    /** Defeat phase 4: Force player right, advance to setup-ending when camera reaches $840.
     *  ROM: ObjC7_Phase3 (s2.asm) - lock controls, walk right until Camera_X >= $840. */
    private void updateDefeatWalkPlayer(int vIntRunCount, AbstractPlayableSprite player) {
        if (player != null) {
            // ROM splits the ending handoff across two body dispatches:
            // loc_3D922 locks Control_Locked (s2.asm:82966-82975), so Obj01_Control
            // skips the Ctrl_1 -> Ctrl_1_Logical copy (s2.asm:36233-36235) and the
            // previous logical jump/left word survives for Sonic_JumpHeight.
            // loc_3D93C writes forced RIGHT on the next dispatch (s2.asm:82978-82980).
            int latchedLogicalInput = player.getLogicalInputState();
            player.setControlLocked(true);
            if (defeatWalkFrameCounter == 0) {
                player.setForcedInputMask(latchedLogicalInput);
            } else {
                player.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
            }
            // Prevent pit death: clamp player Y to ground level during ending walk.
            // The level collision may not extend far enough to the credits trigger X,
            // so force the player to stay at ground height.
            clampPlayerToGround(player);
        }
        defeatWalkFrameCounter++;

        Camera camera = services().camera();
        if (camera != null && camera.getX() >= DEFEAT_CAMERA_WALK_TARGET) {
            // ROM: ObjC7_SetupEnding (s2.asm:83050-83124)
            // Advance to setup-ending phase. Do NOT trigger credits yet.
            defeatPhase = 6;
            robotFollowOffset = INITIAL_ROBOT_FOLLOW_OFFSET;
            defeatFrameCounter = 0;

            // ROM: set screen shake flag and DEZ shake timer ($1000)
            triggerDezScreenShake(0x1000);

            if (head != null) {
                head.setDestroyed(true);
            }
        }
    }

    /**
     * Defeat phase 6: ObjC7_SetupEnding - robot follows player with decreasing offset.
     * ROM: Controls still locked, player keeps walking right.
     * Screen shake + rumble sound every 32 frames, decrement follow-offset.
     * Robot position: X = player_X - offset, Y = player_Y.
     * Wait until Player X >= $EC0 (3776 decimal) before advancing to fade.
     */
    private void updateDefeatSetupEnding(int vIntRunCount, AbstractPlayableSprite player) {
        // Keep player controls locked and forcing right
        if (player != null) {
            player.setControlLocked(true);
            player.setForceInputRight(true);
            // Prevent pit death: clamp player Y to ground level during ending walk.
            // The level collision may not extend far enough, so force the player to
            // stay at the defeat floor height (matching ROM where the player walks
            // on flat ground through to the credits trigger).
            clampPlayerToGround(player);
        }

        defeatFrameCounter++;

        // ROM: Every 32 frames: play rumble sound, decrement follow-offset
        if ((defeatFrameCounter & 0x1F) == 0) {
            services().playSfx(Sonic2Sfx.RUMBLING_2.id);
            if (robotFollowOffset > 0) {
                robotFollowOffset--;
            }
        }

        // ROM: ObjC7_SetupEnding calls loc_3DFBA EVERY frame to spawn an explosion
        // at the robot's position. This is a DIFFERENT function from Boss_LoadExplosion
        // (no 8-frame throttle) — it creates a continuous trail of explosions behind
        // the player as the robot follows.
        spawnDefeatExplosion();

        // Position robot following behind the player
        if (player != null) {
            state.x = player.getCentreX() - robotFollowOffset;
            state.y = player.getCentreY();
            bodyXFixed = (long) state.x << 16;
            bodyYFixed = (long) state.y << 16;
            state.xFixed = (int) bodyXFixed;
            state.yFixed = (int) bodyYFixed;

            // Check player X threshold for ending
            if (player.getCentreX() >= DEFEAT_ENDING_PLAYER_X) {
                defeatPhase = 8;
                fadeTimer = FADE_DURATION;
                services().fadeOutMusic();
            }
        }
    }

    /**
     * Defeat phase 8: Fade to white, then trigger credits transition.
     * ROM: Fade palette to white over ~22 frames, then set game mode to ending.
     */
    private void updateDefeatFade() {
        fadeTimer--;
        if (fadeTimer < 0) {
            // ROM: move.b #id_Ending,(v_gamemode).w
            services().requestCreditsTransition();
            defeatPhase = 10; // Terminal state
        }
    }

    // ========================================================================
    // HIT DETECTION (custom - head is only hittable part)
    // ========================================================================

    private void checkHit() {
        if (state.hitCount <= 0 && !state.defeated) {
            triggerDefeatSequence();
        }
    }

    void onHeadHit() {
        if (state.invulnerable || state.defeated) {
            return;
        }
        state.hitCount--;
        state.invulnerabilityTimer = DEZ_BOSS_INVULN_DURATION;
        state.invulnerable = true;
        services().playSfx(Sonic2Sfx.BOSS_HIT.id);
        paletteFlasher.startFlash();

        if (state.hitCount <= 0) {
            triggerDefeatSequence();
        }
    }

    private void triggerDefeatSequence() {
        if (state.defeated) return;
        state.defeated = true;
        services().gameState().addScore(1000);
        bodyRoutine = BODY_DEFEAT;
        defeatPhase = 0;
        state.xVel = 0;
        state.yVel = 0;
        removeAllCollision();
        breakApart();
        if (jet != null) {
            jet.setDestroyed(true);
        }
    }

    // ========================================================================
    // ROM-ACCURATE GROUP ANIMATION ENGINE (loc_3E1AA)
    // ========================================================================

    /** Reset group animation state for a new script */
    private void resetGroupAnim() {
        groupAnimScript = -1;
        groupAnimFrameIdx = 0;
        groupAnimSubStep = 0;
    }

    /**
     * Step through a group animation script. Returns true when the script completes.
     *
     * ROM: loc_3E1AA — reads per-keyframe delta tables and applies them
     * to body + children using signed byte deltas scaled to 1/16 pixel per substep.
     */
    private boolean stepGroupAnimation(int scriptId) {
        // Detect new animation start
        if (groupAnimScript != scriptId) {
            groupAnimScript = scriptId;
            groupAnimFrameIdx = 0;
            groupAnimSubStep = 0;
        }

        int[] sequence = SCRIPT_SEQUENCES[scriptId];
        int[][] keyframes = getKeyframeTable(scriptId);

        // Skip sound markers (encoded as -1 in the sequence)
        while (groupAnimFrameIdx < sequence.length && sequence[groupAnimFrameIdx] == -1) {
            // Play hammer sound
            services().playSfx(Sonic2Sfx.HAMMER.id);
            groupAnimFrameIdx++;
        }

        // Check for end of sequence
        if (groupAnimFrameIdx >= sequence.length) {
            resetGroupAnim();
            return true;
        }

        int entry = sequence[groupAnimFrameIdx];

        // Decode reversed keyframes (encoded as negative values <= -100)
        boolean reversed = false;
        int keyframeIdx;
        if (entry <= -100) {
            reversed = true;
            keyframeIdx = -(entry + 100); // -108 -> keyframe 8, -107 -> 7, etc.
        } else {
            keyframeIdx = entry;
        }

        // Look up keyframe data
        int[] kf = keyframes[keyframeIdx];
        int speed = kf[0];

        // Apply deltas to body and children
        applyKeyframeDeltas(kf, reversed);

        // Advance substep counter
        groupAnimSubStep++;
        if (groupAnimSubStep >= speed) {
            groupAnimSubStep = 0;
            groupAnimFrameIdx++;

            // Skip sound markers after advancing
            while (groupAnimFrameIdx < sequence.length && sequence[groupAnimFrameIdx] == -1) {
                services().playSfx(Sonic2Sfx.HAMMER.id);
                groupAnimFrameIdx++;
            }

            // ROM off_3Exxx scripts terminate with a $C0 end-marker "keyframe"
            // (e.g. off_3E3D0 crouch = 0,1,2,$C0; off_3E30A walk = 0..8,$C0).
            // ROM ObjC7_GroupAni (loc_3E1AA) reads anim_frame at the START of the
            // frame: it plays the last real keyframe's final substep on frame N,
            // then on frame N+1 reads the $C0 entry and returns "done" (loc_3E23E
            // -> loc_3E27E -> loc_3E236 clr anim_frame / moveq #1,d1) WITHOUT
            // applying deltas. So completion costs ONE extra frame after the last
            // keyframe's substeps finish. The engine's sequences omit the $C0
            // marker, so model that frame here: when idx now == sequence.length we
            // deliberately FALL THROUGH without returning true (the last real
            // keyframe's deltas were applied above this frame); the marker frame
            // (N+1) returns true via the top-of-method end check. Omitting this
            // 1-frame end cost made each crouch/walk advance one frame early,
            // drifting the whole DEZ attack clock (jet-stomp sensor snap 7-8f early).
        }

        // Update body pixel position from fixed-point
        state.x = (int)(bodyXFixed >> 16);
        state.y = (int)(bodyYFixed >> 16);
        state.xFixed = (int) bodyXFixed;
        state.yFixed = (int) bodyYFixed;

        // Update children pixel positions from their fixed-point
        syncChildPixelPositions();

        // Position children that aren't in the keyframe (shoulder, head, jet)
        positionNonAnimatedChildren();

        return false;
    }

    /**
     * Apply ROM-accurate keyframe deltas to body and children.
     * ROM math: signedByte -> ext.w -> asl.w #4 -> ext.l -> asl.l #8 -> add.l to pos
     * Net effect: delta_subpixels = signedByte << 12
     */
    private void applyKeyframeDeltas(int[] kf, boolean reversed) {
        // kf[0] = speed; then triplets of (slot, dx, dy) starting at index 1
        for (int i = 1; i < kf.length; i += 3) {
            int slot = kf[i];
            int dx = (byte) kf[i + 1]; // sign-extend to byte range
            int dy = (byte) kf[i + 2]; // sign-extend to byte range

            // ROM: asl.w #4, asl.l #8 = total shift left 12
            long dxFixed = (long) dx << 12;
            long dyFixed = (long) dy << 12;

            // ROM: if x-flipped, negate X delta
            if (facingLeft) {
                dxFixed = -dxFixed;
            }

            // ROM: if reversed ($80 prefix), negate both
            if (reversed) {
                dxFixed = -dxFixed;
                dyFixed = -dyFixed;
            }

            // Apply to the appropriate object
            if (slot == SLOT_BODY) {
                bodyXFixed += dxFixed;
                bodyYFixed += dyFixed;
            } else {
                ArticulatedChild child = resolveChildBySlot(slot);
                if (child != null) {
                    child.xFixed += dxFixed;
                    child.yFixed += dyFixed;
                }
            }
        }
    }

    /** Resolve a child by its ROM objoff slot identifier */
    private ArticulatedChild resolveChildBySlot(int slot) {
        return switch (slot) {
            case SLOT_FRONT_LOWER_LEG -> frontLowerLeg;
            case SLOT_FRONT_FOREARM -> frontForearm;
            case SLOT_UPPER_ARM -> upperArm;
            case SLOT_FRONT_THIGH -> frontThigh;
            case SLOT_BACK_LOWER_LEG -> backLowerLeg;
            case SLOT_BACK_FOREARM -> backForearm;
            case SLOT_BACK_THIGH -> backThigh;
            default -> null;
        };
    }

    /** Update children's pixel positions from their fixed-point tracking */
    private void syncChildPixelPositions() {
        if (frontLowerLeg != null) frontLowerLeg.syncFromFixed();
        if (frontForearm != null && !frontForearm.isPunching()) frontForearm.syncFromFixed();
        if (upperArm != null) upperArm.syncFromFixed();
        if (frontThigh != null) frontThigh.syncFromFixed();
        if (backLowerLeg != null) backLowerLeg.syncFromFixed();
        if (backForearm != null && !backForearm.isPunching()) backForearm.syncFromFixed();
        if (backThigh != null) backThigh.syncFromFixed();
    }

    // ========================================================================
    // CHILD POSITIONING
    // ========================================================================

    /** Position all children relative to body (ROM: ObjC7_PositionChildren) */
    private void positionChildren() {
        int flipSign = facingLeft ? -1 : 1;

        // 7 children positioned via ObjC7_ChildDeltas
        if (frontLowerLeg != null) {
            frontLowerLeg.setPositionAndSnap(state.x + (CHILD_DELTAS[0][0] * flipSign), state.y + CHILD_DELTAS[0][1]);
        }
        if (frontForearm != null && !frontForearm.isPunching()) {
            frontForearm.setPositionAndSnap(state.x + (CHILD_DELTAS[1][0] * flipSign), state.y + CHILD_DELTAS[1][1]);
        }
        if (upperArm != null) {
            upperArm.setPositionAndSnap(state.x + (CHILD_DELTAS[2][0] * flipSign), state.y + CHILD_DELTAS[2][1]);
        }
        if (frontThigh != null) {
            frontThigh.setPositionAndSnap(state.x + (CHILD_DELTAS[3][0] * flipSign), state.y + CHILD_DELTAS[3][1]);
        }
        if (backLowerLeg != null) {
            backLowerLeg.setPositionAndSnap(state.x + (CHILD_DELTAS[4][0] * flipSign), state.y + CHILD_DELTAS[4][1]);
        }
        if (backForearm != null && !backForearm.isPunching()) {
            backForearm.setPositionAndSnap(state.x + (CHILD_DELTAS[5][0] * flipSign), state.y + CHILD_DELTAS[5][1]);
        }
        if (backThigh != null) {
            backThigh.setPositionAndSnap(state.x + (CHILD_DELTAS[6][0] * flipSign), state.y + CHILD_DELTAS[6][1]);
        }

        positionNonAnimatedChildren();
    }

    /** Position children that use loc_3E282 (relative offset, NOT group animation) */
    private void positionNonAnimatedChildren() {
        int flipSign = facingLeft ? -1 : 1;

        if (shoulder != null) {
            shoulder.setPosition(state.x + (SHOULDER_DX * flipSign), state.y + SHOULDER_DY);
        }
        if (head != null) {
            head.setPosition(state.x + (HEAD_DX * flipSign), state.y + HEAD_DY);
        }
        if (jet != null) {
            jet.setPosition(state.x + (JET_DX * flipSign), state.y + JET_DY);
        }
    }

    /** Initialize child collision flags (ROM: ObjC7_InitCollision, s2.asm:83282) */
    private void initChildCollisions() {
        for (int i = 0; i < childComponents.size() && i < CHILD_COLLISION.length; i++) {
            if (childComponents.get(i) instanceof AbstractBossChild child) {
                child.setCollisionFlags(CHILD_COLLISION[i]);
            }
        }
    }

    /** Remove all child collision (ROM: ObjC7_RemoveCollision, s2.asm:83324) */
    private void removeAllCollision() {
        for (var component : childComponents) {
            if (component instanceof AbstractBossChild child) {
                child.setCollisionFlags(0);
            }
        }
    }

    /** Break apart body pieces with per-part velocities (ROM: ObjC7_Break, s2.asm:83241) */
    private void breakApart() {
        AbstractBossChild[] breakParts = {
                shoulder, frontLowerLeg, frontForearm, upperArm,
                frontThigh, backLowerLeg, backForearm, backThigh
        };
        for (int i = 0; i < breakParts.length && i < BREAK_VELOCITIES.length; i++) {
            // Check ForearmChild FIRST (more specific subclass of ArticulatedChild)
            if (breakParts[i] instanceof ForearmChild fc) {
                fc.startFalling(BREAK_VELOCITIES[i][0], BREAK_VELOCITIES[i][1]);
            } else if (breakParts[i] instanceof ArticulatedChild ac) {
                ac.startFalling(BREAK_VELOCITIES[i][0], BREAK_VELOCITIES[i][1]);
            }
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Prevent the player from falling into a pit during the ending walk sequence.
     * ROM: The player walks right on flat ground from the defeat position to the
     * credits trigger at X=$EC0. If the engine's level collision doesn't extend
     * that far, the player would fall and die. Clamp Y to a safe ground level
     * and zero any downward velocity to keep the player walking on solid ground.
     */
    private void clampPlayerToGround(AbstractPlayableSprite player) {
        // Use the defeat floor Y as the maximum safe ground level.
        // The player's centre Y at the arena floor is represented by DEFEAT_FLOOR_Y.
        // If the player's Y exceeds this by more than a small margin, they've fallen
        // off the level geometry — clamp them back.
        int maxSafeY = DEFEAT_FLOOR_Y + 0x20; // small margin for normal ground height variation
        if (player.getY() > maxSafeY) {
            player.setY((short) maxSafeY);
            player.setYSpeed((short) 0);
        }
    }

    private boolean isPlayerOnFacingSide(AbstractPlayableSprite player) {
        if (player == null) return false;
        int playerX = player.getCentreX();
        if (facingLeft) {
            return playerX < state.x;
        } else {
            return playerX > state.x;
        }
    }

    private void updateChildFacing() {
        for (var component : childComponents) {
            if (component instanceof AbstractBossChild child && !child.isDestroyed()) {
                child.setFlipX(facingLeft);
            }
        }
    }

    private void spawnBombs(AbstractPlayableSprite player) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null || services().objectManager() == null) return;

        int xSign = facingLeft ? -1 : 1;
        int spawnX = state.x + (BOMB_SPAWN_DX * xSign);
        int spawnY = state.y + BOMB_SPAWN_DY;

        BombChild bomb1 = spawnFreeChild(() -> new BombChild(this, spawnX, spawnY, 0x60 * xSign, -0x800));
        childComponents.add(bomb1);

        BombChild bomb2 = spawnFreeChild(() -> new BombChild(this, spawnX, spawnY, 0xC0 * xSign, -0xA00));
        childComponents.add(bomb2);
    }

    /** Spawn the targeting sensor child */
    private void spawnSensor(AbstractPlayableSprite player) {
        if (player == null) return;
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        int playerXVel = player.getXSpeed();
        int playerYVel = player.getYSpeed();
        // ROM loc_3D744 calls LoadChildObject, whose helper uses
        // AllocateObjectAfterCurrent (docs/s2disasm/s2.asm:82785-82786,
        // 72978-72986). Keeping the sensor above the body slot preserves the
        // body-before-sensor report handoff in loc_3D784/loc_3DE62.
        sensorChild = spawnChild(() -> new SensorChild(this, playerX, playerY, playerXVel, playerYVel));
    }

    /** Report player X from targeting sensor */
    void reportTargetedPlayerX(int x) {
        this.targetedPlayerX = x;
    }

    // ========================================================================
    // ACCESSORS (for tests and children)
    // ========================================================================

    public int getBodyRoutine() { return bodyRoutine; }
    public int getCurrentFrame() { return currentFrame; }
    public int getAttackIndex() { return attackIndex; }
    public int getCurrentAttack() { return currentAttack; }
    public int getDefeatPhase() { return defeatPhase; }
    public boolean isFacingLeft() { return facingLeft; }
    public HeadChild getHead() { return head; }
    public int getActionTimer() { return actionTimer; }

    /**
     * Signal that Eggman has boarded the cockpit (called by Sonic2DEZEggmanInstance).
     * ROM: ObjC7 head child polls (DEZ_Eggman).w for status.npc.p1_standing bit
     * (loc_3DC02). This flag mirrors that p1_standing bit; the head consumes it in
     * its routine-2 poll, then plays its glow + 64-frame countdown before setting
     * the body's misc bit that actually releases the body's WAIT_EGGMAN state.
     */
    public void setEggmanBoarded() {
        this.eggmanBoardedFlag = true;
    }

    private static Sonic2DeathEggRobotInstance nearestLiveBossForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = null;
        if (ctx != null) {
            objectManager = ctx.objectManager();
            if (objectManager == null && ctx.objectServices() != null) {
                objectManager = ctx.objectServices().objectManager();
            }
        }
        if (objectManager == null || ctx == null || ctx.spawn() == null) {
            return null;
        }
        Sonic2DeathEggRobotInstance nearest = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof Sonic2DeathEggRobotInstance boss && !boss.isDestroyed()) {
                long dx = boss.getX() - (long) ctx.spawn().x();
                long dy = boss.getY() - (long) ctx.spawn().y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = boss;
                }
            }
        }
        return nearest;
    }

    /** Eggman boarding flag, set by Sonic2DEZEggmanInstance when jump starts */
    private boolean eggmanBoardedFlag = false;

    private void triggerDezScreenShake(int frames) {
        if (services().gameState() != null) {
            services().gameState().setScreenShakeActive(true);
        }
        if (services().parallaxManager() == null) {
            return;
        }
        if (services().parallaxManager().getHandler(Sonic2ZoneConstants.ZONE_DEZ) instanceof SwScrlDez dez) {
            dez.triggerScreenShake(frames);
        }
    }

    boolean consumeFrontPunchTrigger() {
        boolean val = frontPunchTriggered;
        frontPunchTriggered = false;
        return val;
    }

    boolean consumeBackPunchTrigger() {
        boolean val = backPunchTriggered;
        backPunchTriggered = false;
        return val;
    }

    // ========================================================================
    // RENDERING
    // ========================================================================

    @Override
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM: ObjC7_SetupEnding does NOT call DisplaySprite — the robot body is
        // invisible during the ending phase. Only the trailing explosions are visible.
        if (defeatPhase >= 6) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_BOSS);
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(currentFrame, state.x, state.y, facingLeft, false);
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

    // ========================================================================
    // INNER CLASS: ArticulatedChild - Body part with subpixel position tracking
    // ========================================================================

    static class ArticulatedChild extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
        int frame;
        long xFixed;  // 32-bit subpixel position (as long for Java sign safety)
        long yFixed;
        boolean falling;  // package-private for ForearmChild access
        private int xVel;
        private int yVel;
        private int fallTimer;

        ArticulatedChild(Sonic2DeathEggRobotInstance parent, String name,
                         int priority, int frame) {
            super(parent, name, priority, Sonic2ObjectIds.DEATH_EGG_ROBOT);
            this.frame = frame;
            this.priority = priority;
            this.falling = false;
            this.xVel = 0;
            this.yVel = 0;
            this.fallTimer = 0x80;
            this.xFixed = (long) currentX << 16;
            this.yFixed = (long) currentY << 16;
        }

        @Override
        public ArticulatedChild recreateForRewind(RewindRecreateContext ctx) {
            Sonic2DeathEggRobotInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return new ArticulatedChild(boss, "RewindArticulated", 4, FRAME_SHOULDER);
        }

        void startFalling(int xVel, int yVel) {
            this.falling = true;
            this.xVel = xVel;
            this.yVel = yVel;
            this.fallTimer = 0x80;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;
            if (falling) {
                fallTimer--;
                if (fallTimer < 0) {
                    setDestroyed(true);
                    return;
                }
                currentX += (xVel >> 8);
                currentY += (yVel >> 8);
                yVel += GRAVITY;
            }
            updateDynamicSpawn();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = ((Sonic2DeathEggRobotInstance) parent).services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_BOSS);
            if (renderer == null || !renderer.isReady()) return;
            boolean flip = ((Sonic2DeathEggRobotInstance) parent).facingLeft;
            renderer.drawFrameIndex(frame, currentX, currentY, flip, false);
        }

        /** Sync pixel position from fixed-point (called by parent after group animation) */
        void syncFromFixed() {
            setPosition((int)(xFixed >> 16), (int)(yFixed >> 16));
        }

        /** Set pixel position and snap fixed-point to match (called by positionChildren) */
        void setPositionAndSnap(int x, int y) {
            setPosition(x, y);
            xFixed = (long) x << 16;
            yFixed = (long) y << 16;
        }

        public int getCollisionFlags() {
            return collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0; // HURT-category objects don't use HP
        }
    }

    // ========================================================================
    // INNER CLASS: ForearmChild - Forearm with punch mechanics + subpixel tracking
    // ========================================================================

    static class ForearmChild extends ArticulatedChild {
        private boolean isFront;
        private boolean punching;
        private int punchPhase;
        private int punchTimer;
        private int punchXVel;
        private int punchYVel;
        private int savedY;

        ForearmChild(Sonic2DeathEggRobotInstance parent, String name,
                     int priority, boolean isFront) {
            super(parent, name, priority, FRAME_FOREARM);
            this.isFront = isFront;
            this.punching = false;
            this.punchPhase = 0;
            this.punchTimer = 0;
            this.punchXVel = 0;
            this.punchYVel = 0;
            this.savedY = 0;
        }

        @Override
        public ForearmChild recreateForRewind(RewindRecreateContext ctx) {
            Sonic2DeathEggRobotInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return new ForearmChild(boss, "RewindForearm", 4, false);
        }

        boolean isPunching() {
            return punching;
        }

        @Override
        void startFalling(int xVel, int yVel) {
            super.startFalling(xVel, yVel);
            this.punching = false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;

            if (falling) {
                super.update(vIntRunCount, player);
                return;
            }

            Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;

            // Check if punch was triggered
            if (!punching) {
                boolean triggered = isFront ? boss.consumeFrontPunchTrigger()
                        : boss.consumeBackPunchTrigger();
                if (triggered) {
                    punching = true;
                    punchPhase = 0;
                    punchTimer = 0x10;
                    savedY = currentY;
                    punchYVel = 0;
                }
            }

            if (punching) {
                updatePunch(player);
            }
            updateDynamicSpawn();
        }

        private void updatePunch(AbstractPlayableSprite player) {
            Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;
            switch (punchPhase) {
                case 0 -> { // Wind up
                    punchTimer--;
                    if (punchTimer < 0) {
                        punchPhase = 2;
                        punchTimer = 0x20;
                        // ROM uses HORIZONTAL distance (x_pos - sonic_x_pos), NOT vertical
                        int dx = 0;
                        if (player != null) {
                            dx = Math.abs(player.getCentreX() - currentX);
                        }
                        // ROM: cmpi.w #$100,d2 / blo.s + / move.w #$FF,d2
                        // Clamp absolute distance to 0xFF before mask/shift
                        dx = Math.min(0xFF, dx);
                        // ROM: andi.w #$C0,d0; lsr.w #6,d0 — divide by 64
                        // dx 0-63->0, 64-127->1, 128-191->2, 192-255->3
                        int yVelIdx = (dx & 0xC0) >> 6;
                        int[] Y_VEL_TABLE = { 0x200, 0x100, 0x80, 0 };
                        punchYVel = Y_VEL_TABLE[yVelIdx];
                        if (player != null && player.getCentreY() < currentY) {
                            punchYVel = -punchYVel;
                        }
                        // ROM loc_3DACC: x_flip SET -> +$800, x_flip NOT SET -> -$800
                        // Punch goes AWAY from facing direction (toward the player behind)
                        punchXVel = boss.facingLeft ? FOREARM_PUNCH_SPEED : -FOREARM_PUNCH_SPEED;
                        services().playSfx(Sonic2Sfx.SPINDASH_RELEASE.id);
                    } else {
                        punchYVel += 0x20;
                        currentY += (punchYVel >> 8);
                    }
                }
                case 2 -> { // Travel
                    punchTimer--;
                    if (punchTimer < 0) {
                        punchPhase = 4;
                        punchXVel = -punchXVel;
                        punchTimer = 0x20;
                        int dy = savedY - currentY;
                        punchYVel = dy << 3;
                    } else {
                        currentX += (punchXVel >> 8);
                        currentY += (punchYVel >> 8);
                    }
                }
                case 4 -> { // Return
                    punchTimer--;
                    if (punchTimer < 0) {
                        punching = false;
                        punchXVel = 0;
                        punchYVel = 0;
                    } else {
                        currentX += (punchXVel >> 8);
                        currentY += (punchYVel >> 8);
                    }
                }
            }
        }
    }

    // ========================================================================
    // INNER CLASS: HeadChild - The only hittable part!
    // ROM: Ani_objC7_a: speed 7, $15×8, 0, 1, 2, $FA (loop)
    // ========================================================================

    static class HeadChild extends AbstractBossChild
            implements com.openggf.level.objects.TouchResponseProvider,
            com.openggf.level.objects.TouchResponseAttackable,
            RewindRecreatable {
        private int headRoutine;
        private int waitTimer;
        private boolean bodyMiscSignaled; // ROM: head r6 end -> bset misc on body (loc_3DC2A)
        private int eggmanPollTimer;      // Fallback timer when no DEZ_Eggman object is present
        private int glowIndex;     // Current index in HEAD_GLOW_FRAMES
        private int glowTimer;     // Frame counter for glow animation speed
        private boolean glowComplete; // ROM: $FA terminator — glow plays once then advances routine

        HeadChild(Sonic2DeathEggRobotInstance parent, int priority) {
            super(parent, "Head", priority, Sonic2ObjectIds.DEATH_EGG_ROBOT);
            this.headRoutine = 0;
            this.waitTimer = 0;
            this.bodyMiscSignaled = false;
            this.eggmanPollTimer = 0;
            this.glowIndex = 0;
            this.glowTimer = 0;
            this.glowComplete = false;
        }

        @Override
        public HeadChild recreateForRewind(RewindRecreateContext ctx) {
            Sonic2DeathEggRobotInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return new HeadChild(boss, 4);
        }

        /**
         * Whether the head has finished its boarding sequence and set the body's
         * misc bit, which is what releases the body's WAIT_EGGMAN state.
         *
         * ROM: the body's WAIT_EGGMAN (loc_3D5A8) polls {@code status.npc.misc}
         * on its OWN status word. That bit is set by the head at the END of its
         * routine 6 countdown (loc_3DC2A: {@code bset #status.npc.misc,status(a1)}
         * where a1 = the body via objoff_2C), i.e. only AFTER the head has:
         *   1. seen DEZ_Eggman set p1_standing (the cockpit jump, loc_3DC02),
         *   2. played the Ani_objC7_a glow once (head r4, loc_3DC1C), and
         *   3. counted down objoff_2A = $40 = 64 frames (head r6, loc_3DC2A).
         * The head drives this whole sequence in its own update(); the body must
         * NOT release at the earlier p1_standing moment, which is ~150 frames too
         * early and walks the boss body west into the player's hurtbox.
         */
        boolean isBodyMiscSignaled() {
            return bodyMiscSignaled;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;

            switch (headRoutine) {
                case 0 -> {
                    // ROM loc_3DBF6: set mapping_frame $15, advance to routine 2.
                    headRoutine = 2;
                }
                case 2 -> {
                    // ROM loc_3DC02: poll (DEZ_Eggman).w for status.npc.p1_standing
                    // (the cockpit-jump bit set by ObjC6 at loc_3CFC0). When set,
                    // arm the 64-frame countdown and advance to the glow routine.
                    Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;
                    if (boss.eggmanBoardedFlag) {
                        headRoutine = 4;
                        waitTimer = 0x40; // ROM: move.w #$40,objoff_2A(a0)
                    } else {
                        // Fallback for debug/test spawns without the DEZ_Eggman object.
                        eggmanPollTimer++;
                        if (eggmanPollTimer > 180) {
                            headRoutine = 4;
                            waitTimer = 0x40;
                        }
                    }
                }
                case 4 -> {
                    // ROM routine 4 (loc_3DC1C): ONLY animation (Ani_objC7_a glow).
                    // $FA terminator advances routine_secondary by 2 (4->6).
                    stepGlow();
                    if (glowComplete) {
                        headRoutine = 6;
                        // ROM keeps the objoff_2A value armed in routine 2; do NOT
                        // re-arm here (the $40 set at routine 2 is the countdown).
                    }
                }
                case 6 -> {
                    // ROM routine 6 (loc_3DC2A): countdown objoff_2A, then set the
                    // body's misc bit (releasing the body's WAIT_EGGMAN) and advance.
                    waitTimer--;
                    if (waitTimer < 0) {
                        bodyMiscSignaled = true; // ROM: bset #status.npc.misc,status(body)
                        headRoutine = 8;
                    }
                }
                case 8 -> {
                    // ROM routine 8 (loc_3DC46): Active fight state — head is hittable.
                    // ROM: move.b #-1,collision_property(a0) every frame
                    collisionFlags = COLLISION_HEAD;
                }
            }
            updateDynamicSpawn();
        }

        /** ROM-accurate head glow: speed 7, $15x8, 0, 1, 2, $FA (play once, then advance routine) */
        private void stepGlow() {
            if (glowComplete) return;
            glowTimer++;
            if (glowTimer > HEAD_GLOW_SPEED) { // > 7 means every 8th frame
                glowTimer = 0;
                glowIndex++;
                if (glowIndex >= HEAD_GLOW_FRAMES.length) {
                    // ROM: $FA terminator = advance routine_secondary by 2
                    glowIndex = HEAD_GLOW_FRAMES.length - 1; // hold last frame
                    glowComplete = true;
                }
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = ((Sonic2DeathEggRobotInstance) parent).services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_BOSS);
            if (renderer == null || !renderer.isReady()) return;
            boolean flip = ((Sonic2DeathEggRobotInstance) parent).facingLeft;
            int headFrame = HEAD_GLOW_FRAMES[glowIndex];
            renderer.drawFrameIndex(headFrame, currentX, currentY, flip, false);
        }

        @Override
        public int getCollisionFlags() {
            if (collisionFlags == 0) return 0;
            Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;
            if (boss.state.invulnerable || boss.state.defeated) return 0;
            return collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            // ROM: move.b #-1,collision_property(a0) every frame in routine 8.
            // Value -1 (0xFF unsigned) tells Touch_Enemy to always process the hit;
            // HP tracking is handled by the parent body's onHeadHit() method.
            return -1;
        }

        @Override
        public void onPlayerAttack(PlayableEntity playerEntity,
                                   com.openggf.level.objects.TouchResponseResult result) {
            ((Sonic2DeathEggRobotInstance) parent).onHeadHit();
        }
    }

    // ========================================================================
    // INNER CLASS: JetChild - Jet exhaust with ROM-accurate animations
    // ROM: Ani_objC7_b (4 animations)
    // ========================================================================

    static class JetChild extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
        private int jetRoutine;
        private int jetAnimId;  // Current animation ID (0-3)
        private int jetFrame;
        private int animIdx;    // Current frame index within animation
        private int animTimer;  // Speed counter

        JetChild(Sonic2DeathEggRobotInstance parent, int priority) {
            super(parent, "Jet", priority, Sonic2ObjectIds.DEATH_EGG_ROBOT);
            this.jetRoutine = 0;
            this.jetAnimId = 0;
            this.jetFrame = FRAME_JET_OFF;
            this.animIdx = 0;
            this.animTimer = 0;
        }

        @Override
        public JetChild recreateForRewind(RewindRecreateContext ctx) {
            Sonic2DeathEggRobotInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return new JetChild(boss, 4);
        }

        void setJetRoutine(int routine) {
            this.jetRoutine = routine;
            // ROM: routine 2/8 → anim 0 (idle), routine 4 → anim 3, routine 6 → anim 1
            switch (routine) {
                case 2, 8 -> setJetAnim(0);
                case 4 -> setJetAnim(3);
                case 6 -> setJetAnim(1);
            }
        }

        void setJetAnim(int anim) {
            if (this.jetAnimId != anim) {
                this.jetAnimId = anim;
                this.animIdx = 0;
                this.animTimer = 0;
            }
        }

        private int[] getCurrentAnimFrames() {
            return switch (jetAnimId) {
                case 0 -> JET_ANIM_0;
                case 1 -> JET_ANIM_1;
                case 2 -> JET_ANIM_2;
                case 3 -> JET_ANIM_3;
                default -> JET_ANIM_0;
            };
        }

        private int getAnimSpeed() {
            return switch (jetAnimId) {
                case 0, 1, 2 -> 1; // speed 1: every 2nd frame
                case 3 -> 0;       // speed 0: every frame
                default -> 1;
            };
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;

            Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;
            if (boss.bodyRoutine >= BODY_RISE && jetRoutine > 0) {
                int[] frames = getCurrentAnimFrames();
                int speed = getAnimSpeed();
                animTimer++;
                if (animTimer > speed) {
                    animTimer = 0;
                    animIdx++;
                    if (animIdx >= frames.length) {
                        // Handle terminators
                        if (jetAnimId == 2) {
                            // $FD: go to previous anim (anim 1)
                            setJetAnim(1);
                        } else {
                            // $FF/$FA: loop
                            animIdx = 0;
                        }
                    }
                }
                if (animIdx < frames.length) {
                    jetFrame = frames[animIdx];
                }
            }
            updateDynamicSpawn();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = ((Sonic2DeathEggRobotInstance) parent).services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_BOSS);
            if (renderer == null || !renderer.isReady()) return;
            boolean flip = ((Sonic2DeathEggRobotInstance) parent).facingLeft;
            renderer.drawFrameIndex(jetFrame, currentX, currentY, flip, false);
        }

        public int getCollisionFlags() {
            return collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0; // HURT-category objects don't use HP
        }
    }

    // ========================================================================
    // INNER CLASS: SensorChild - Targeting sensor with velocity-tracked animation
    // ROM: ObjC7_TargettingSensor (s2.asm:82951-83044)
    // ========================================================================

    static class SensorChild extends AbstractBossChild implements RewindRecreatable {
        private int sensorRoutine;  // 0=init, 2=tracking, 4=lock-on
        private int countdown;      // ROM: objoff_2A
        private int beepInterval;   // ROM: angle — current beep interval
        private int beepCounter;    // Frames until next beep
        private int animIdx;        // Current frame in SENSOR_ANIM_FRAMES
        private int animTimer;      // Speed counter

        // Lock-on visual state (ROM: ObjC7_TargettingLock child at routine $1A)
        // ROM spawns a separate child that renders mapping_frame $14 with palette
        // flashing (bchg #palette_bit_0,art_tile every 4 frames), priority 1,
        // high_priority flag set. We implement this inline for simplicity.
        static final int FRAME_LOCK_ON = 0x14;
        private boolean lockOnActive;       // True when in lock-on phase (sensorRoutine == 4)
        private boolean lockOnPaletteFlip;  // Toggles every 4 frames for palette flash
        private int lockOnFlashCounter;     // Frame counter for 4-frame palette toggle

        // ROM-accurate 4-slot velocity FIFO buffer (3-frame delay)
        // ROM: ObjC7_TargettingSensor uses 4 slots at offsets $30-$3F
        // (each slot = 4 bytes: xvel word + yvel word). A value written to
        // slot 0 traverses 3 shifts before being consumed at slot 3.
        private final int[] xVelBuffer = new int[4];
        private final int[] yVelBuffer = new int[4];

        SensorChild(Sonic2DeathEggRobotInstance parent, int playerX, int playerY) {
            this(parent, playerX, playerY, 0, 0);
        }

        SensorChild(Sonic2DeathEggRobotInstance parent, int playerX, int playerY,
                    int initialXVel, int initialYVel) {
            super(parent, "Sensor", 1, Sonic2ObjectIds.DEATH_EGG_ROBOT);
            this.currentX = playerX;
            this.currentY = playerY;
            this.xVelBuffer[0] = initialXVel;
            this.yVelBuffer[0] = initialYVel;
            this.sensorRoutine = 0;
            this.countdown = 0xA0; // 160 frames
            this.beepInterval = 0x18; // Initial interval = 24 frames
            this.beepCounter = 0; // ROM: angle byte = $00, first subq.b gives $FF (bit 7 set, bpl not taken = immediate beep)
            this.animIdx = 0;
            this.animTimer = 0;
            this.lockOnActive = false;
            this.lockOnPaletteFlip = false;
            this.lockOnFlashCounter = 0;
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            // The Java body routine owns the sensor step while waiting in phase 6
            // so the body can read objoff_28 before the sensor reports. Deferring
            // the manager's spawn-frame update prevents the same managed child
            // from consuming its init frame outside that parent-owned ordering.
            return true;
        }

        /**
         * The sensor is driven directly by {@code boss.sensorChild.update(...)} (see the
         * boss's own update dispatch), NOT by {@code AbstractBossInstance#updateChildren()}
         * iterating {@code childComponents} -- so unlike the body-part children, it is
         * deliberately never added to {@code childComponents} on construction
         * ({@code spawnSensorChild()} only assigns the dedicated {@code sensorChild}
         * field). {@code recreateForRewind()} re-wiring that same field is therefore the
         * complete registration step; opting out here prevents
         * {@code AbstractBossChild#onRecreatedForRewind()}'s generic re-registration from
         * adding a spurious duplicate entry a fresh construction never produces.
         */
        @Override
        protected boolean tracksViaChildComponents() {
            return false;
        }

        @Override
        public SensorChild recreateForRewind(RewindRecreateContext ctx) {
            Sonic2DeathEggRobotInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null || ctx == null || ctx.spawn() == null) {
                return null;
            }
            SensorChild sensor = new SensorChild(boss, ctx.spawn().x(), ctx.spawn().y());
            boss.sensorChild = sensor;
            return sensor;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;

            Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;

            switch (sensorRoutine) {
                case 0 -> { // Init
                    sensorRoutine = 2;
                }
                case 2 -> { // Tracking
                    countdown--;
                    // ROM: subq.w #1,objoff_2A(a0) / bmi.s — fires when result goes negative
                    if (countdown < 0) {
                        sensorRoutine = 4;
                        countdown = 0x40; // 64 frames for lock-on
                        beepCounter = 4;
                        // Activate lock-on visual (ROM: spawns ObjC7_TargettingLock child)
                        lockOnActive = true;
                        lockOnFlashCounter = 0;
                        lockOnPaletteFlip = false;
                        // ROM: Snap sensor to player position when transitioning to lock-on
                        if (player != null) {
                            currentX = player.getCentreX();
                            currentY = player.getCentreY();
                        }
                    } else {
                        // ROM loc_3DDA6: reads player's OWN velocity (x_vel/y_vel),
                        // NOT position delta. Snaps to player when stationary.
                        if (player != null) {
                            int playerXVel = player.getXSpeed();
                            int playerYVel = player.getYSpeed();

                            // ROM: snap sensor to player position when velocity is 0
                            if (playerXVel == 0) {
                                currentX = player.getCentreX();
                            }
                            if (playerYVel == 0) {
                                currentY = player.getCentreY();
                            }

                            // ROM loc_3DDA6 consumes objoff_3C/3E, shifts
                            // objoff_30..3B upward, then writes the current
                            // x_vel/y_vel into objoff_30/32 (s2.asm:83493-83516).
                            int applyXVel = xVelBuffer[3];
                            int applyYVel = yVelBuffer[3];
                            for (int i = 3; i > 0; i--) {
                                xVelBuffer[i] = xVelBuffer[i - 1];
                                yVelBuffer[i] = yVelBuffer[i - 1];
                            }
                            xVelBuffer[0] = playerXVel;
                            yVelBuffer[0] = playerYVel;
                            currentX += (applyXVel >> 8); // 8.8 fixed -> pixel
                            currentY += (applyYVel >> 8);
                        }

                        // Beep with decreasing interval
                        // ROM: subq.b #1,angle(a0) / bpl.s — fires when byte goes negative
                        beepCounter--;
                        if (beepCounter < 0) {
                            services().playSfx(Sonic2Sfx.BEEP.id);
                            beepCounter = beepInterval;
                            // ROM: subq.b #1,objoff_27(a0) — unconditional decrement
                            beepInterval--;
                        }
                    }
                    stepAnim();
                }
                case 4 -> { // Lock-on
                    countdown--;
                    // ROM: subq.w #1,objoff_2A(a0) / bmi.s — fires when result goes negative
                    if (countdown < 0) {
                        // Report final position to body and self-destruct
                        boss.reportTargetedPlayerX(currentX);
                        setDestroyed(true);
                        return;
                    }
                    // Fast beeping
                    // ROM: subq.b #1,angle(a0) / bpl.s — fires when byte goes negative
                    beepCounter--;
                    if (beepCounter < 0) {
                        services().playSfx(Sonic2Sfx.BEEP.id);
                        beepCounter = 4;
                    }
                    // ROM: ObjC7_TargettingLock — bchg #palette_bit_0,art_tile(a0) every 4 frames
                    lockOnFlashCounter++;
                    if ((lockOnFlashCounter & 3) == 0) {
                        lockOnPaletteFlip = !lockOnPaletteFlip;
                    }
                    stepAnim();
                }
            }
            updateDynamicSpawn();
        }

        private void stepAnim() {
            animTimer++;
            if (animTimer > SENSOR_ANIM_SPEED) {
                animTimer = 0;
                animIdx++;
                if (animIdx >= SENSOR_ANIM_FRAMES.length) {
                    animIdx = 0; // $FF loop
                }
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = ((Sonic2DeathEggRobotInstance) parent).services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_BOSS);
            if (renderer == null || !renderer.isReady()) return;
            renderer.drawFrameIndex(SENSOR_ANIM_FRAMES[animIdx], currentX, currentY, false, false);
            // ROM: ObjC7_TargettingLock — draw frame $14 on top during lock-on phase
            // with palette flashing (toggles palette line 0/1 every 4 frames)
            if (lockOnActive) {
                int palOverride = lockOnPaletteFlip ? 1 : -1;
                renderer.drawFrameIndex(FRAME_LOCK_ON, currentX, currentY, false, false, palOverride);
            }
        }

        boolean isLockOnActive() {
            return lockOnActive;
        }

        boolean isLockOnPaletteFlip() {
            return lockOnPaletteFlip;
        }

        public int getCollisionFlags() {
            return 0; // Sensor has no collision
        }
    }

    // ========================================================================
    // INNER CLASS: BombChild - Projectile with arc trajectory
    // ========================================================================

    static class BombChild extends AbstractBossChild implements RewindRecreatable, TouchResponseProvider {
        private int xVel;
        private int yVel;
        private int groundTimer;
        private boolean onGround;
        private boolean detonating;
        private int detonateFrame;
        private int detonateTimer;
        private static final int BOMB_GROUND_Y = 0x170;
        private static final int BOMB_GROUND_TIMER = 0x40;

        BombChild(Sonic2DeathEggRobotInstance parent, int spawnX, int spawnY,
                  int xVel, int yVel) {
            super(parent, "Bomb", 5, Sonic2ObjectIds.DEATH_EGG_ROBOT);
            this.currentX = spawnX;
            this.currentY = spawnY;
            this.xVel = xVel;
            this.yVel = yVel;
            this.onGround = false;
            this.detonating = false;
            this.groundTimer = BOMB_GROUND_TIMER;
        }

        @Override
        public BombChild recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null
                    || ctx.objectServices().objectManager() == null) {
                return null;
            }
            ObjectManager objectManager = ctx.objectServices().objectManager();
            ObjectSpawn spawn = ctx.spawn();
            Sonic2DeathEggRobotInstance boss = nearestLiveBoss(objectManager, spawn.x(), spawn.y());
            if (boss == null) {
                return null;
            }
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return new BombChild(boss, spawn.x(), spawn.y(), 0, 0);
        }

        private static Sonic2DeathEggRobotInstance nearestLiveBoss(ObjectManager objectManager, int x, int y) {
            Sonic2DeathEggRobotInstance nearest = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (instance instanceof Sonic2DeathEggRobotInstance boss && !boss.isDestroyed()) {
                    long dx = boss.getX() - (long) x;
                    long dy = boss.getY() - (long) y;
                    long distance = dx * dx + dy * dy;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        nearest = boss;
                    }
                }
            }
            return nearest;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) return;

            Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;

            if (detonating) {
                detonateTimer--;
                if (detonateTimer < 0) {
                    detonateTimer = 7;
                    detonateFrame++;
                    if (detonateFrame >= 7) {
                        setDestroyed(true);
                        return;
                    }
                }
                updateDynamicSpawn();
                return;
            }

            if (boss.state.defeated) {
                startDetonate();
                return;
            }

            if (!onGround) {
                currentX += (xVel >> 8);
                currentY += (yVel >> 8);
                yVel += GRAVITY;
                if (currentY >= BOMB_GROUND_Y) {
                    currentY = BOMB_GROUND_Y;
                    onGround = true;
                    xVel = 0;
                    yVel = 0;
                }
            } else {
                groundTimer--;
                if (groundTimer < 0) {
                    startDetonate();
                }
            }
            updateDynamicSpawn();
        }

        private void startDetonate() {
            detonating = true;
            detonateFrame = 0;
            detonateTimer = 7;
            services().playSfx(Sonic2Sfx.BOSS_EXPLOSION.id);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = ((Sonic2DeathEggRobotInstance) parent).services().renderManager();
            if (renderManager == null) return;
            PatternSpriteRenderer renderer = detonating
                    ? renderManager.getBossExplosionRenderer()
                    : renderManager.getRenderer(Sonic2ObjectArtKeys.DEZ_BOSS);
            if (renderer == null || !renderer.isReady()) return;
            if (detonating) {
                renderer.drawFrameIndex(detonateFrame, currentX, currentY, false, false);
            } else {
                renderer.drawFrameIndex(FRAME_BOMB, currentX, currentY, false, false);
            }
        }

        public int getCollisionFlags() {
            if (detonating && detonateFrame >= 5) return 0;
            return 0x89;
        }

        @Override
        public int getCollisionProperty() {
            return 0; // HURT-category objects don't use HP
        }
    }
}
