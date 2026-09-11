package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcRequests;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.objects.boss.BossChildComponent;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * MTZ Act 3 Boss (Object 0x54) - Orbiting shield boss with laser attack.
 * ROM Reference: s2.asm:66680-67265 (Obj54)
 *
 * <p>The boss uses separate Boss_X/Y_pos variables (16.16 fixed-point) for
 * logical movement, with a sine-wave float applied to y_pos for display.
 * It uses multi-sprite rendering with 2 sub-sprites (Robotnik face, pod bottom).
 *
 * <p>State machine (boss_routine / 10 sub-states):
 * <ul>
 *   <li>Sub0: Descend until Y≥0x4A0, face player, start horizontal pacing</li>
 *   <li>Sub2: Horizontal pacing (X bounces 0x2AD0↔0x2BD0), advance after 2 turns</li>
 *   <li>Sub4: Decelerate to center (0x2B50), advance when stopped</li>
 *   <li>Sub6: Expand orbs (radius 0x27→0x68), advance when inner timer expires</li>
 *   <li>Sub8: Contract orbs (radius→0x27), then restart cycle (→Sub0)</li>
 *   <li>SubA: After hit - retract orbs, descend, advance to SubC</li>
 *   <li>SubC: Decision: attacks remain → re-expand + restart, else → laser dive (SubE)</li>
 *   <li>SubE: Laser dive attack (3 sub-phases: approach, dive+fire, return)</li>
 *   <li>Sub10: Defeat explosions ($EF frames), then flee</li>
 *   <li>Sub12: Flee right (xVel=$400, yVel=-$40), delete when off-screen</li>
 * </ul>
 *
 * <p>Children: 7 orbiting shield orbs (Obj53), 1 laser shooter (Obj54 subtype 6).
 */
public class Sonic2MTZBossInstance extends AbstractBossInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic2MTZBossInstance.class.getName());

    // =========================================================================
    // Position constants (ROM addresses inline in Obj54_Init)
    // =========================================================================

    /** Spawn X position. ROM: move.w #$2B50,x_pos(a0) */
    private static final int SPAWN_X = 0x2B50;
    /** Spawn Y position. ROM: move.w #$380,y_pos(a0) */
    private static final int SPAWN_Y = 0x380;
    /** Center X for return-to-center. ROM: cmpi.w #$2B50 in Sub4 */
    private static final int CENTER_X = 0x2B50;
    /** Left pacing boundary. ROM: cmpi.w #$2AD0 in Sub2 */
    private static final int BOUNDARY_LEFT = 0x2AD0;
    /** Right pacing boundary. ROM: cmpi.w #$2BD0 in Sub2 */
    private static final int BOUNDARY_RIGHT = 0x2BD0;
    /** Bottom Y for descent. ROM: cmpi.w #$4A0 in Sub0 */
    private static final int Y_BOTTOM = 0x4A0;
    /** Y threshold for deceleration. ROM: cmpi.w #$470 in Sub4/SubE */
    private static final int Y_DECEL = 0x470;
    /** Base float Y. ROM: cmpi.w #$420 in SubE phase 2 */
    private static final int Y_BASE = 0x420;

    // =========================================================================
    // Velocity constants
    // =========================================================================

    /** Horizontal pacing speed. ROM: move.w #$100 / #-$100 */
    private static final int VEL_HORIZONTAL = 0x100;
    /** Initial descent speed. ROM: move.w #$100,(Boss_Y_vel).w in Init */
    private static final int VEL_DESCEND = 0x100;
    /** Dive/retract Y speed. ROM: move.w #$180 / #-$180 */
    private static final int VEL_DIVE = 0x180;
    /** Flee X speed. ROM: move.w #$400,(Boss_X_vel).w in Sub12 */
    private static final int VEL_FLEE_X = 0x400;
    /** Flee Y speed. ROM: move.w #-$40,(Boss_Y_vel).w in Sub12 */
    private static final int VEL_FLEE_Y = -0x40;

    // =========================================================================
    // SubE (laser dive) boundaries
    // =========================================================================

    /** SubE phase 0: far-left trigger. ROM: cmpi.w #$2AF0 */
    private static final int DIVE_BOUNDARY_LEFT = 0x2AF0;
    /** SubE phase 0: far-right trigger. ROM: cmpi.w #$2BB0 */
    private static final int DIVE_BOUNDARY_RIGHT = 0x2BB0;

    // =========================================================================
    // Orb radius constants
    // =========================================================================

    /** Default outer orb radius. ROM: move.b #$27,objoff_33(a0) */
    private static final int ORB_RADIUS_DEFAULT = 0x27;
    /** Expanded outer orb radius. ROM: cmpi.b #$68,objoff_33(a0) */
    private static final int ORB_RADIUS_EXPANDED = 0x68;

    // =========================================================================
    // Timing / laser constants
    // =========================================================================

    /** Laser fire interval in frames. ROM: move.w #$1E,(Boss_Countdown).w */
    private static final int LASER_COUNTDOWN = 0x1E;
    /** Laser shots per dive attack. ROM: move.b #3,objoff_2D(a0) */
    private static final int LASER_SHOTS_PER_ATTACK = 3;
    /** SubE display pause after laser fire. ROM: move.b #$10,objoff_2F(a0) */
    private static final int LASER_FIRE_PAUSE = 0x10;
    /** Defeat timer start. ROM: move.w #$EF,(Boss_Countdown).w */
    private static final int DEFEAT_COUNTDOWN = 0xEF;
    /** Defeat explosion cutoff. ROM: cmpi.w #60,(Boss_Countdown).w */
    private static final int DEFEAT_EXPLOSION_CUTOFF = 60;
    /** Camera max X for flee phase. ROM: cmpi.w #$2BF0 */
    private static final int CAMERA_MAX_X_FLEE = 0x2BF0;

    // =========================================================================
    // Hit system constants
    // =========================================================================

    /** Invulnerability duration. ROM: move.b #$40,boss_invulnerable_time(a0) */
    private static final int INVULN_DURATION = 0x40;
    /** Initial attack cycle count. ROM: move.b #7,objoff_3E(a0) */
    private static final int INITIAL_ATTACK_CYCLES = 7;

    // =========================================================================
    // Mapping frames
    // =========================================================================

    /** Main boss body frame. ROM: move.b #2,mainspr_mapframe(a0) */
    private static final int FRAME_BODY = 2;
    /** Robotnik face sub-sprite frame. ROM: move.b #$C,sub2_mapframe(a0) */
    private static final int FRAME_FACE = 0x0C;
    /** Pod bottom sub-sprite frame. ROM: move.b #0,sub3_mapframe(a0) */
    private static final int FRAME_POD = 0;
    /** Laser shooter frame. ROM: move.b #$13,mapping_frame(a1) */
    private static final int FRAME_LASER_SHOOTER = 0x13;
    /** Laser projectile body frame. ROM: Obj54_Laser_Init move.b #$12,mapping_frame(a0) (s2.asm:67617) */
    private static final int FRAME_LASER = 0x12;
    /** Angry face frame (after hit). ROM: ori.b #5 to anim byte */
    private static final int FRAME_FACE_ANGRY = 0x0D;
    /** Laughing face frame (player hurt). ROM: ori.b #4 to anim byte */
    private static final int FRAME_FACE_LAUGH = 0x0E;
    /** Defeat face frame. ROM: move.b #7 to anim byte */
    private static final int FRAME_FACE_DEFEAT = 0x0F;

    // =========================================================================
    // State fields (matching ROM RAM variables)
    // =========================================================================

    // Boss_X_pos / Boss_Y_pos as 32-bit accumulators (16.16 fixed-point)
    private int bossXFixed;
    private int bossYFixed;

    /** objoff_2B: bit7=direction (0=left,1=right), bit6=turn counter */
    private int flags2B;

    /** objoff_2C: number of orbs that have broken away */
    private int orbBreakCount;

    /** objoff_2D: laser fire count (3→0 per attack) */
    private int laserFireCount;

    /** objoff_2E: SubE sub-phase (0, 2, 4) */
    private int diveSubPhase;

    /** objoff_2F: SubE display pause timer */
    private int divePauseTimer;

    /** objoff_33: outer orb orbit radius */
    private int outerOrbRadius;

    /** objoff_39: inner orbit parameter / contraction timer */
    private int innerOrbParam;

    /** objoff_3A: orb break state (0=normal, -1=expanding, 0x80=contracting) */
    private int orbBreakState;

    /**
     * objoff_38: per-hit "one orb should break away" flag.
     * ROM Obj54_AnimateFace @s2.asm:67117 sets it (st.b objoff_38) on the
     * invuln edge; ROM Obj53_Main @s2.asm:67344-67348 consumes it for exactly
     * one orb per frame, clearing it on the boss.
     */
    private boolean pendingOrbBreak;

    /** Defer the whole Obj54_AnimateFace hit reaction until after this frame's movement. */
    private boolean pendingHitReaction;

    /**
     * Defer the Obj54_Defeated transition the same way: ROM Obj54_CheckHit sets
     * boss_routine = $10 and Boss_Countdown = $EF AFTER the current routine's
     * movement ran this frame, so Sub10's first dispatch (and first countdown
     * decrement) lands one frame after the killing touch.
     */
    private boolean pendingDefeatReaction;

    /** objoff_3E: remaining attack cycles before laser dive */
    private int attackCyclesRemaining;

    /** boss_sine_count: float oscillation counter */
    private int sineCount;

    /** Boss_Countdown: shared timer for laser fire + defeat */
    private int bossCountdown;

    /** Current face animation frame index */
    private int faceFrame;

    /** Whether boss has been initialized */
    private boolean initialized;

    /**
     * ROM Obj54's first execution frame dispatches Obj54_Init (boss_subtype 0),
     * which sets up state, spawns the laser shooter and the Obj53 orb spawner,
     * and ends with rts — no Boss_MoveObject, no CheckHit (s2.asm:32298-323B8,
     * Obj54_Init ends `rts` before Obj54_Main). The engine performs that setup
     * in the constructor, so its first updateBossLogic dispatch must be consumed
     * as the init routine or the boss floats one frame ahead of ROM for the
     * whole fight. Children DO update on the init frame (ROM's freshly allocated
     * higher-slot orbs run Obj53_Main the same pass).
     */
    private boolean initDispatchPending = true;

    /**
     * Boss_defeated_flag. ROM Obj54_MainSub12 @s2.asm:67197-67200 sets it once
     * on the first flee frame and triggers the animal-explosion PLC; the level
     * layout's capsule (not the boss) handles the actual prison.
     */
    private boolean bossDefeatedFlag;

    // Laser shooter child reference
    private MTZLaserShooter laserShooter;

    public Sonic2MTZBossInstance(ObjectSpawn spawn) {
        super(spawn, "MTZ Boss");
    }

    @Override
    protected void initializeBossState() {
        // ROM: Obj54_Init (s2.asm:66694-66753)
        state.routine = 0; // boss_routine = Sub0
        state.x = SPAWN_X;
        state.y = SPAWN_Y;

        bossXFixed = SPAWN_X << 16;
        bossYFixed = SPAWN_Y << 16;

        state.xVel = 0;     // Boss_X_vel = 0
        state.yVel = VEL_DESCEND; // Boss_Y_vel = $100

        flags2B = 0;
        orbBreakCount = 0;
        laserFireCount = 0;
        diveSubPhase = 0;
        divePauseTimer = 0;
        outerOrbRadius = ORB_RADIUS_DEFAULT;  // objoff_33 = $27
        innerOrbParam = ORB_RADIUS_DEFAULT;    // objoff_39 = $27
        orbBreakState = 0;
        attackCyclesRemaining = INITIAL_ATTACK_CYCLES; // objoff_3E = 7
        sineCount = 0x40; // boss_sine_count = $40
        bossCountdown = 0;
        faceFrame = FRAME_FACE;
        pendingOrbBreak = false;
        pendingHitReaction = false;
        pendingDefeatReaction = false;
        bossDefeatedFlag = false;

        initialized = true;

        if (getSpawn().objectId() == Sonic2ObjectIds.MTZ_BOSS) {
            // Rewind probe construction uses a zero object id and must not leak
            // structural child side effects.
            laserShooter = spawnFreeChild(() -> new MTZLaserShooter(this));
            childComponents.add(laserShooter);
            spawnOrbs();
        }
    }

    private void spawnOrbs() {
        // ROM: Obj53_Init spawns 7 orbs with phase offsets
        // byte_329CC: $24, $6C, $B4, $FC, $48, $90, $D8
        int[] phaseOffsets = {0x24, 0x6C, 0xB4, 0xFC, 0x48, 0x90, 0xD8};
        // byte_329D3: 0, 1, 1, 0, 1, 1, 0
        int[] tiltFlags = {0, 1, 1, 0, 1, 1, 0};

        for (int i = 0; i < 7; i++) {
            int orbIndex = i;
            int phaseOffset = phaseOffsets[i];
            int tiltFlag = tiltFlags[i];
            MTZBossOrb orb = spawnFreeChild(() -> new MTZBossOrb(this, orbIndex, phaseOffset, tiltFlag));
            childComponents.add(orb);
        }
    }

    @Override
    public Sonic2MTZBossInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new Sonic2MTZBossInstance(ctx.spawn()));
    }

    private static Sonic2MTZBossInstance nearestLiveBossForRewind(RewindRecreateContext ctx) {
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
        Sonic2MTZBossInstance nearest = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof Sonic2MTZBossInstance boss && !boss.isDestroyed()) {
                long dx = boss.getX() - (long) ctx.spawn().x();
                long dy = boss.getY() - (long) ctx.spawn().y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    nearest = boss;
                    bestDistance = distance;
                }
            }
        }
        return nearest;
    }

    // =========================================================================
    // Boss_MoveObject equivalent
    // =========================================================================

    /**
     * ROM: Boss_MoveObject (s2.asm:60795-60808)
     * Updates Boss_X/Y_pos using Boss_X/Y_vel in 16.16 fixed-point.
     */
    private void bossMoveObject() {
        bossXFixed += (state.xVel << 8);
        bossYFixed += (state.yVel << 8);
    }

    /** Get Boss_X_pos integer part. */
    public int getBossX() {
        return bossXFixed >> 16;
    }

    /** Get Boss_Y_pos integer part. */
    public int getBossY() {
        return bossYFixed >> 16;
    }

    // =========================================================================
    // Obj54_Float: sine wave hover
    // =========================================================================

    /**
     * ROM: Obj54_Float (s2.asm:66808-66815)
     * y_pos = Boss_Y_pos + (sin(boss_sine_count) >> 6)
     * boss_sine_count += 4
     */
    private int applyFloat() {
        int sine = TrigLookupTable.sinHex(sineCount & 0xFF);
        int offset = sine >> 6;
        sineCount = (sineCount + 4) & 0xFF;
        return offset;
    }

    /**
     * ROM: loc_328C0 (Sub12 float variant with sine_count += 2)
     */
    private int applyFloatSlow() {
        int sine = TrigLookupTable.sinHex(sineCount & 0xFF);
        int offset = sine >> 6;
        sineCount = (sineCount + 2) & 0xFF;
        return offset;
    }

    // =========================================================================
    // Common tail routines
    // =========================================================================

    /** ROM: Obj54_MoveAndShow - update position + float, then display */
    private void moveAndShow() {
        state.x = getBossX();
        state.y = getBossY() + applyFloat();
    }

    /** ROM: Obj54_Display - animate face + display (no position update) */
    private void displayOnly() {
        // Face animation handled in animateFace()
    }

    // =========================================================================
    // Main update
    // =========================================================================

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            return;
        }
        if (initDispatchPending) {
            // ROM Obj54_Init dispatch frame: setup only, no movement/hit check.
            initDispatchPending = false;
            return;
        }

        switch (state.routine) {
            case 0x00 -> updateSub0Descend(player);
            case 0x02 -> updateSub2HorizontalPace();
            case 0x04 -> updateSub4ReturnToCenter();
            case 0x06 -> updateSub6ExpandOrbs();
            case 0x08 -> updateSub8ContractOrbs();
            case 0x0A -> updateSubARetractAfterHit();
            case 0x0C -> updateSubCDecision(player);
            case 0x0E -> updateSubELaserDive();
            case 0x10 -> updateSub10DefeatExplosions(vIntRunCount);
            case 0x12 -> updateSub12Flee();
        }

        applyPendingHitReactionAfterMove();

        // Check hits (except during defeat sequence)
        if (state.routine < 0x10) {
            checkHit(player);
        }

    }

    /**
     * ROM Obj54_AnimateFace's hit branch runs AFTER the current routine's
     * Boss_MoveObject within the same Obj54 pass (round-92 PC-probe dispatch
     * order: Obj54_Main -> Boss_MoveObject -> Obj54_AnimateFace -> Obj54_CheckHit
     * -> Obj53 dispatch; s2.asm:32690-326A5). Touch_Enemy reaches onHitTaken()
     * before Obj54's own update in the engine's player-slot-first order, so the
     * whole reaction (orb-break flag, angry face, SubA entry, velocity writes)
     * is latched there and applied here, after this frame's old-routine movement
     * and before the orbs update (they consume objoff_38 in the same pass).
     */
    private void applyPendingHitReactionAfterMove() {
        if (pendingDefeatReaction) {
            // ROM Obj54_CheckHit killing-hit branch: Obj54_Defeated replaces the
            // AnimateFace break reaction entirely (the invulnerable-time == $3F
            // branch never runs because CheckHit branched out before setting it).
            pendingDefeatReaction = false;
            pendingHitReaction = false;
            bossCountdown = DEFEAT_COUNTDOWN; // move.w #$EF,(Boss_Countdown).w
            state.routine = 0x10;             // move.b #$10,boss_routine => Sub10
            return;
        }
        if (!pendingHitReaction) {
            return;
        }
        pendingHitReaction = false;
        if (state.routine >= 0x10 || state.defeated) {
            // ROM's killing hit branches Obj54_CheckHit -> Obj54_Defeated before
            // the invulnerable-time == $3F break branch can run.
            return;
        }
        // ROM: st.b objoff_38(a0) — flag exactly one orb to break away this hit.
        pendingOrbBreak = true;
        // ROM: andi.b #$F0 / ori.b #5 to the anim byte — angry face.
        faceFrame = FRAME_FACE_ANGRY;
        // ROM: tst.b objoff_3E(a0) / beq.s + — only enter SubA when attack cycles remain.
        if (attackCyclesRemaining != 0) {
            state.routine = 0x0A;    // move.b #$A,boss_routine => Obj54_MainSubA
            state.yVel = -VEL_DIVE;  // move.w #-$180,(Boss_Y_vel)
            attackCyclesRemaining--; // subq.b #1,objoff_3E
        }
        // ROM: move.w #0,(Boss_X_vel).w — cleared on both AnimateFace hit paths.
        state.xVel = 0;
    }

    // =========================================================================
    // Sub0: Descend
    // ROM: Obj54_MainSub0 (s2.asm:66775-66805)
    // =========================================================================

    private void updateSub0Descend(AbstractPlayableSprite player) {
        bossMoveObject();

        if (getBossY() >= Y_BOTTOM) {
            state.routine = 0x02; // → Sub2
            state.yVel = 0;

            // Face player: set direction and X velocity
            flags2B &= ~0x80; // clear direction bit
            state.renderFlags &= ~1; // clear x_flip
            state.xVel = -VEL_HORIZONTAL; // default: move left

            int playerX = player.getCentreX();
            if (playerX >= getBossX()) {
                // Player is to the right
                state.xVel = VEL_HORIZONTAL;
                flags2B |= 0x80; // set direction bit (right)
                state.renderFlags |= 1; // set x_flip
            }
        }

        // ROM Obj54_MainSub0 writes Boss_Y_pos directly and does not call
        // Obj54_Float until Obj54_MoveAndShow in later substates
        // (docs/s2disasm/s2.asm:67271-67298, 67322-67333).
        state.x = getBossX();
        state.y = getBossY();
    }

    // =========================================================================
    // Sub2: Horizontal pacing
    // ROM: Obj54_MainSub2 (s2.asm:66818-66862)
    // =========================================================================

    private void updateSub2HorizontalPace() {
        bossMoveObject();

        boolean facingRight = (flags2B & 0x80) != 0;

        if (!facingRight) {
            // Moving left: check left boundary
            if (getBossX() < BOUNDARY_LEFT) {
                flags2B ^= 0x80; // toggle direction
                state.xVel = VEL_HORIZONTAL;
                state.renderFlags |= 1; // x_flip

                // Check turn counter (bit 6)
                if ((flags2B & 0x40) != 0) {
                    // Second turn: advance to Sub4
                    state.routine = 0x04;
                    state.yVel = -VEL_HORIZONTAL; // yVel = -$100
                } else {
                    flags2B |= 0x40; // set turn counter
                }
            }
        } else {
            // Moving right: check right boundary
            if (getBossX() >= BOUNDARY_RIGHT) {
                flags2B ^= 0x80; // toggle direction
                state.xVel = -VEL_HORIZONTAL;
                state.renderFlags &= ~1; // clear x_flip

                if ((flags2B & 0x40) != 0) {
                    state.routine = 0x04;
                    state.yVel = -VEL_HORIZONTAL;
                } else {
                    flags2B |= 0x40;
                }
            }
        }

        moveAndShow();
    }

    // =========================================================================
    // Sub4: Return to center
    // ROM: Obj54_MainSub4 (s2.asm:66865-66889)
    // =========================================================================

    private void updateSub4ReturnToCenter() {
        bossMoveObject();

        // Y clamp: stop rising at Y_DECEL
        if (getBossY() < Y_DECEL) {
            state.yVel = 0;
        }

        // X clamp: stop at center
        boolean facingRight = (flags2B & 0x80) != 0;
        if (!facingRight) {
            if (getBossX() < CENTER_X) {
                state.xVel = 0;
            }
        } else {
            if (getBossX() >= CENTER_X) {
                state.xVel = 0;
            }
        }

        // When both velocities are zero, advance
        if (state.xVel == 0 && state.yVel == 0) {
            state.routine = 0x06; // → Sub6
        }

        moveAndShow();
    }

    // =========================================================================
    // Sub6: Expand orbs
    // ROM: Obj54_MainSub6 (s2.asm:66892-66905)
    // =========================================================================

    private void updateSub6ExpandOrbs() {
        // Expand outer radius until max
        if (outerOrbRadius < ORB_RADIUS_EXPANDED) {
            outerOrbRadius++;
            innerOrbParam++;
        } else {
            // Contract inner param
            innerOrbParam--;
            if (innerOrbParam <= 0) {
                innerOrbParam = 0;
                state.routine = 0x08; // → Sub8
            }
        }

        moveAndShow();
    }

    // =========================================================================
    // Sub8: Contract orbs, restart cycle
    // ROM: Obj54_MainSub8 (s2.asm:66908-66923)
    // =========================================================================

    private void updateSub8ContractOrbs() {
        // Contract outer radius to default
        if (outerOrbRadius >= ORB_RADIUS_DEFAULT) {
            outerOrbRadius--;
        } else {
            // Expand inner param back to default
            innerOrbParam++;
            if (innerOrbParam >= ORB_RADIUS_DEFAULT) {
                // Restart cycle
                state.yVel = VEL_DESCEND; // yVel = $100
                state.routine = 0x00; // → Sub0
                flags2B &= ~0x40; // clear turn counter
            }
        }

        moveAndShow();
    }

    // =========================================================================
    // SubA: Retract after hit
    // ROM: Obj54_MainSubA (s2.asm:66926-66953)
    // =========================================================================

    private void updateSubARetractAfterHit() {
        // Retract inner param, signal orb break
        if (innerOrbParam > 0) {
            innerOrbParam--;
        } else {
            orbBreakState = -1; // signal orbs to detach (-1 = 0xFF)
        }

        // Contract outer radius
        if (outerOrbRadius >= ORB_RADIUS_DEFAULT) {
            outerOrbRadius--;
        }

        bossMoveObject();

        // Y clamp at Y_BASE
        if (getBossY() < Y_BASE) {
            state.yVel = 0;
        }

        // ROM Obj54_MainSubA waits while objoff_2C (active broken-orb count)
        // is nonzero. The engine's base touch pass can set Obj54's break flag
        // before this parent update, while Obj53 consumes it in the later child
        // update. Treat that pending flag as a same-frame effective count so
        // SubA cannot skip directly to SubC before the orb has detached.
        if (orbBreakCount != 0 || pendingOrbBreak) {
            // Still waiting for orbs
        } else {
            // All orbs done: signal and advance
            if (orbBreakState != 0) {
                orbBreakState = (byte) 0x80; // contracting state
            }
            state.routine = 0x0C; // → SubC
        }

        moveAndShow();
    }

    // =========================================================================
    // SubC: Decision point
    // ROM: Obj54_MainSubC (s2.asm:66956-66987)
    // =========================================================================

    private void updateSubCDecision(AbstractPlayableSprite player) {
        if (attackCyclesRemaining > 0) {
            // Still have attack cycles: check orb state
            if (orbBreakState != 0) {
                // Still processing orb contraction
            } else {
                // Re-expand inner param
                if (innerOrbParam < ORB_RADIUS_DEFAULT) {
                    innerOrbParam++;
                } else {
                    // Restart normal cycle
                    state.yVel = VEL_DESCEND;
                    state.routine = 0x00; // → Sub0
                    flags2B &= ~0x40;
                }
            }
        } else {
            // No attack cycles left: initiate laser dive
            state.yVel = -VEL_DIVE; // yVel = -$180
            // Face player
            state.xVel = -VEL_HORIZONTAL;
            state.renderFlags &= ~1;
            if ((flags2B & 0x80) != 0) {
                state.xVel = VEL_HORIZONTAL;
                state.renderFlags |= 1;
            }
            state.routine = 0x0E; // → SubE
            diveSubPhase = 0;
            divePauseTimer = 0;
            flags2B &= ~0x40; // clear turn counter
        }

        moveAndShow();
    }

    // =========================================================================
    // SubE: Laser dive attack (3 sub-phases)
    // ROM: Obj54_MainSubE (s2.asm:66990-67079)
    // =========================================================================

    private void updateSubELaserDive() {
        // ROM: pause timer check
        if (divePauseTimer > 0) {
            divePauseTimer--;
            state.x = getBossX();
            state.y = getBossY() + applyFloat();
            return;
        }

        switch (diveSubPhase) {
            case 0 -> updateDivePhase0Approach();
            case 2 -> updateDivePhase1DiveAndFire();
            case 4 -> updateDivePhase2Return();
        }
    }

    /**
     * SubE Phase 0 (loc_32650): Approach far edge, then start dive.
     * ROM: Fly to far boundary, then reverse with dive yVel.
     */
    private void updateDivePhase0Approach() {
        bossMoveObject();

        // Y clamp
        if (getBossY() < Y_BASE) {
            state.yVel = 0;
        }

        boolean facingRight = (flags2B & 0x80) != 0;
        if (!facingRight) {
            // Moving left: check far-left trigger
            if (getBossX() < DIVE_BOUNDARY_LEFT) {
                diveSubPhase = 2;
                state.yVel = VEL_DIVE; // yVel = $180 (dive down)
                laserFireCount = LASER_SHOTS_PER_ATTACK;
                bossCountdown = LASER_COUNTDOWN;
                state.renderFlags |= 1; // face right for return
            }
        } else {
            // Moving right: check far-right trigger
            if (getBossX() >= DIVE_BOUNDARY_RIGHT) {
                diveSubPhase = 2;
                state.yVel = VEL_DIVE;
                laserFireCount = LASER_SHOTS_PER_ATTACK;
                bossCountdown = LASER_COUNTDOWN;
                state.renderFlags &= ~1; // face left for return
            }
        }

        moveAndShow();
    }

    /**
     * SubE Phase 1 (loc_326B8): Dive down, fire lasers, bounce at bottom.
     */
    private void updateDivePhase1DiveAndFire() {
        bossMoveObject();

        // Bottom Y clamp: reverse Y and advance phase
        if (getBossY() >= Y_BOTTOM) {
            state.yVel = -VEL_DIVE; // reverse: yVel = -$180
            diveSubPhase = 4;
            flags2B ^= 0x80; // toggle direction
        } else {
            // X boundary clamping
            boolean facingRight = (flags2B & 0x80) != 0;
            if (!facingRight) {
                if (getBossX() < BOUNDARY_LEFT) {
                    state.xVel = 0;
                }
            } else {
                if (getBossX() >= BOUNDARY_RIGHT) {
                    state.xVel = 0;
                }
            }
        }

        // Fire laser
        fireLaser();

        moveAndShow();
    }

    /**
     * SubE Phase 2 (loc_32704): Return to base height, resume horizontal.
     */
    private void updateDivePhase2Return() {
        bossMoveObject();

        // At Y_DECEL: set horizontal velocity based on direction
        if (getBossY() < Y_DECEL) {
            boolean facingRight = (flags2B & 0x80) != 0;
            state.xVel = facingRight ? VEL_HORIZONTAL : -VEL_HORIZONTAL;
        }

        // At Y_BASE: stop Y movement, reset to phase 0
        if (getBossY() < Y_BASE) {
            state.yVel = 0;
            diveSubPhase = 0;
        }

        // Fire laser
        fireLaser();

        moveAndShow();
    }

    // =========================================================================
    // Laser firing
    // ROM: Obj54_FireLaser (s2.asm:67082-67096)
    // =========================================================================

    private void fireLaser() {
        // ROM: Obj54_FireLaser (s2.asm:67086-67100)
        // subi.w #1,(Boss_Countdown).w / bne.s + (rts)
        bossCountdown--;
        if (bossCountdown != 0) {
            // Use '!= 0' (matching the ROM 'bne') rather than '> 0': a wrapped
            // negative countdown must NOT pass this gate and fire every frame.
            return;
        }
        // ROM: tst.b objoff_2D(a0) / beq.s + (rts)
        if (laserFireCount == 0) {
            return;
        }
        // ROM: subq.b #1,objoff_2D(a0)
        laserFireCount--;

        // ROM: AllocateObject, id=ObjID_MTZBoss, boss_subtype=4 => Obj54_Laser,
        //      objoff_34(a1)=boss back-pointer (s2.asm:67092-67096).
        // The laser reuses the already-loaded MTZ boss art/mappings.
        final boolean facingRight = (state.renderFlags & 1) != 0;
        final int originX = getBossX();
        final int originY = getBossY();
        spawnChild(() -> new MTZBossLaser(this, originX, originY, facingRight));

        // ROM: move.w #$1E,(Boss_Countdown).w / move.b #$10,objoff_2F(a0)
        bossCountdown = LASER_COUNTDOWN;
        divePauseTimer = LASER_FIRE_PAUSE; // pause after firing
    }

    // =========================================================================
    // Sub10: Defeat explosions
    // ROM: Obj54_MainSub10 (s2.asm:67144-67178)
    // =========================================================================

    private void updateSub10DefeatExplosions(int vIntRunCount) {
        // ROM: Obj54_MainSub10 (s2.asm:67148-67166)
        // subq.w #1,(Boss_Countdown).w
        bossCountdown--;

        // ROM: cmpi.w #60,(Boss_Countdown).w / blo.s ++ (display-only/flee branch)
        if (bossCountdown >= DEFEAT_EXPLOSION_CUTOFF) {
            // ROM: bmi.s + handled above by the blo (unsigned) split; here
            // countdown >= 60 so it is non-negative => spawn explosions.
            // ROM: bsr.w Boss_LoadExplosion / move.b #7,anim (frame 7).
            // Boss_LoadExplosion itself gates on (Vint_runcount+3)&7 BEFORE
            // allocating and drawing RandomNumber (s2.asm:61419-61424), so a
            // non-multiple-of-8 frame spawns nothing and consumes no RNG.
            if ((vIntRunCount & 7) == 0) {
                spawnDefeatExplosion();
            }
            faceFrame = FRAME_FACE_DEFEAT;
        } else if (bossCountdown < 0) {
            // ROM: bmi.s + (the < 60 unsigned compare reaches here once it
            // actually goes negative): flee-transition.
            state.renderFlags |= 1; // bset #x_flip (face right)
            state.xVel = 0;         // clr.w (Boss_X_vel)
            state.yVel = 0;         // clr.w (Boss_Y_vel)
            state.routine = 0x12;   // addq.b #2,boss_routine => Obj54_MainSub12
            bossCountdown = -0x12;  // move.w #-$12,(Boss_Countdown)
            faceFrame = FRAME_FACE; // move.b #3,anim (laughing/flee face)

            // ROM: jsrto JmpTo7_PlayLevelMusic.
            // The engine lacks a generic PlayLevelMusic lookup here; METROPOLIS
            // is MTZ's level track, so this matches PlayLevelMusic for this zone.
            services().playMusic(Sonic2Music.METROPOLIS.id);
        }
        // else: 0 <= countdown < 60 => display-only (no explosion, no flee).

        // Update position from boss coordinates (ROM copies Boss_X/Y_pos)
        state.x = getBossX();
        state.y = getBossY();
    }

    // =========================================================================
    // Sub12: Flee
    // ROM: Obj54_MainSub12 (s2.asm:67181-67218)
    // =========================================================================

    private void updateSub12Flee() {
        // ROM: Obj54_MainSub12 (s2.asm:67185-67205)
        state.xVel = VEL_FLEE_X; // move.w #$400,(Boss_X_vel)
        state.yVel = VEL_FLEE_Y; // move.w #-$40,(Boss_Y_vel)

        // ROM: cmpi.w #$2BF0,(Camera_Max_X_pos) / bhs.s + / addq.w #2,...
        Camera camera = services().camera();
        if (camera.getMaxX() < CAMERA_MAX_X_FLEE) {
            camera.setMaxX((short) (camera.getMaxX() + 2));
        } else {
            // ROM: _btst #on_screen / _beq.s DeleteObject — delete once off-screen.
            if (!isOnScreen()) {
                setDestroyed(true);
                return;
            }
        }

        // ROM: tst.b (Boss_defeated_flag) / bne.s + / move.b #1,(Boss_defeated_flag)
        //      / jsrto JmpTo7_LoadPLC_AnimalExplosion (s2.asm:67197-67200).
        // The boss does NOT spawn the EggPrison; the level layout's capsule object
        // (Obj3E) handles the prison. We only set the defeated flag on the first
        // flee frame so dependent level/capsule logic can react.
        if (!bossDefeatedFlag) {
            if (!Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ,
                    Sonic2Constants.PLC_EXPLOSION)) return;
            bossDefeatedFlag = true;
            // KNOWN DISCREPANCY: the ROM also kicks off the animal-explosion PLC here
            // (LoadPLC_AnimalExplosion). No engine PLC hook is reachable from object
            // code in this file, so that art-load is omitted; gameplay state (the
            // defeated flag) is still set.
        }

        // ROM: bsr.w Boss_MoveObject / bsr.w loc_328C0 (slow float).
        bossMoveObject();

        // Float with slower sine increment (loc_328C0: boss_sine_count += 2).
        state.x = getBossX();
        state.y = getBossY() + applyFloatSlow();
    }

    // =========================================================================
    // Hit checking
    // ROM: Obj54_CheckHit (s2.asm:67230-67265)
    // =========================================================================

    /**
     * ROM: Obj54_AnimateFace face-update tail (s2.asm:67131-67145).
     * Shows the laughing face when the player is in the hurt routine. The actual
     * hit-reaction (angry face, orb-break flag, SubA entry) is in {@link #onHitTaken}.
     */
    private void checkHit(AbstractPlayableSprite player) {
        if (state.routine >= 0x10) {
            return; // no hit check during defeat
        }

        // Face animation: show laughing face if player is hurt
        // ROM: cmpi.b #4,(MainCharacter+routine).w
        if (player.isHurt()) {
            if (faceFrame != FRAME_FACE_LAUGH) {
                faceFrame = FRAME_FACE_LAUGH;
            }
        }
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // ROM's hit reaction lives in Obj54_AnimateFace, which runs AFTER the
        // current routine's Boss_MoveObject in the same Obj54 pass. This method
        // is reached from the player's touch pass, before Obj54's own update, so
        // latch the whole reaction and apply it post-movement in
        // applyPendingHitReactionAfterMove(). Applying any of it here makes the
        // boss enter SubA (and move the orbs' break centre) one frame ahead of ROM.
        pendingHitReaction = true;

        // NOTE: ROM Obj54_CheckHit also flashes $EEE -> Normal_palette_line2+2
        // (s2.asm:67247-67253). The base AbstractBossInstance.BossPaletteFlasher already
        // drives an invulnerability palette flash on hit, so the flash is handled there
        // rather than routed through PaletteOwnershipRegistry from this file.
    }

    @Override
    public boolean isPersistent() {
        // ROM Obj54 has no out_of_range/RememberState tail: it lives until its
        // own Sub12 flee path deletes it via the on_screen render bit once
        // Camera_Max_X has opened to $2BF0 (Obj54_MainSub12). The engine's
        // generic off-screen cull must not unload it mid-flee, or the +2/frame
        // camera opening stalls when the boss crosses the cull threshold.
        return true;
    }

    @Override
    protected void onDefeatStarted() {
        if (!isDefeatEntryPrepared() && !Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE)) return;
        // ROM: Obj54_Defeated (s2.asm:67258-67265) runs from Obj54_CheckHit,
        // after this frame's old-routine movement. Latch and apply post-move in
        // applyPendingHitReactionAfterMove(); dispatching Sub10 on the killing
        // touch frame starts the defeat countdown (and later the Sub12 flee
        // camera opening) one frame ahead of ROM.
        pendingDefeatReaction = true;
    }

    @Override
    protected boolean prepareDefeatEntry() {
        return Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE);
    }

    @Override
    protected int getInitialHitCount() {
        return DEFAULT_HIT_COUNT;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULN_DURATION; // 0x40 = 64 frames
    }

    @Override
    protected int getCollisionSizeIndex() {
        // ROM: move.b #$F,collision_flags(a0)
        return 0x0F;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // Custom defeat logic in Sub10/Sub12
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public int getPriorityBucket() {
        return 3;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean flipped = (state.renderFlags & 1) != 0;

        // ROM multi-sprite rendering: body (frame 2), face (frame $C), pod (frame 0)
        // Draw in back-to-front order
        renderer.drawFrameIndex(FRAME_POD, state.x, state.y, flipped, false);
        renderer.drawFrameIndex(FRAME_BODY, state.x, state.y, flipped, false);
        renderer.drawFrameIndex(faceFrame, state.x, state.y, flipped, false);
    }

    @Override
    public int getCollisionFlags() {
        if (state.routine >= 0x10) {
            return 0; // No collision during defeat/flee
        }
        if (state.defeated || state.invulnerable) {
            return 0;
        }
        return 0xC0 | (getCollisionSizeIndex() & 0x3F);
    }

    // Uses inherited isOnScreen() from AbstractObjectInstance

    // =========================================================================
    // Public accessors for child components (orbs, laser shooter)
    // =========================================================================

    public int getOuterOrbRadius() {
        return outerOrbRadius;
    }

    public int getInnerOrbParam() {
        return innerOrbParam;
    }

    public int getOrbBreakState() {
        return orbBreakState;
    }

    public void setOrbBreakState(int value) {
        this.orbBreakState = value;
    }

    public int getOrbBreakCount() {
        return orbBreakCount;
    }

    public void incrementOrbBreakCount() {
        this.orbBreakCount++;
    }

    public boolean consumeOrbBreak() {
        if (!pendingOrbBreak) {
            return false;
        }
        pendingOrbBreak = false;
        return true;
    }

    public void decrementOrbBreakCount() {
        if (orbBreakCount > 0) {
            orbBreakCount--;
        }
    }

    public int getFlags2B() {
        return flags2B;
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

    // =========================================================================
    // Inner class: MTZ Boss Orb (Obj53)
    // ROM: s2.asm:67271-67467
    // =========================================================================

    /**
     * Shield orb that orbits the MTZ boss.
     * 7 orbs with different phase offsets create the rotating shield.
     * Each orb can break away when the boss is hit, bouncing off-screen.
     */
    public static class MTZBossOrb extends AbstractBossChild
            implements TouchResponseProvider, TouchResponseAttackable, TouchResponseListener, RewindRecreatable {

        /** Obj53 routine states (Obj53_Index, s2.asm:67282-67287). */
        private static final int RT_INIT = 0;
        private static final int RT_MAIN = 2;
        private static final int RT_BREAK_AWAY = 4;
        private static final int RT_BOUNCE_AROUND = 6;
        private static final int RT_BURST = 8;

        /**
         * ROM intact-orb collision_flags = $87 (s2.asm:67806). S2 TouchResponse
         * decodes the top two bits directly: $87 & $C0 = $80, which branches to
         * Touch_ChkHurt rather than Touch_Special (s2.asm:85252-85264). The
         * MTZ3 trace confirms this path when Sonic touches the intact shield at
         * f12594 and enters hurt routine before the boss body can rebound him.
         */
        private static final int ORB_COLLISION = 0x87;
        /**
         * ROM break/bounce-orb collision_flags = $DA (s2.asm:67481/67527), set once the
         * objoff_32 timer expires. $DA & $C0 = $C0 = BOSS category, so a rolling/attacking
         * player triggers onPlayerAttack (=> Obj53_Burst). Size index = $DA & $3F = $1A.
         */
        private static final int ORB_COLLISION_HIT = 0xDA;
        /** ROM: move.b #2,collision_property (s2.asm:67313). */
        private static final int ORB_COLLISION_PROPERTY = 2;

        /** ROM: move.b #$40,objoff_29 (s2.asm:67311) — fixed vertical-scale angle. */
        private static final int TILT_SCALE_ANGLE = 0x40;
        /** Ground line. ROM: cmpi.w #$4AC,y_pos (s2.asm:67489 etc.). */
        private static final int GROUND_Y = 0x4AC;
        /** Break burst mapping frame. ROM: move.b #$14,mapping_frame (s2.asm:67515). */
        private static final int FRAME_BURST = 0x14;
        /** ObjectMoveAndFall gravity. ROM: addi.w #$38,y_vel (s2.asm). */
        private static final int ORB_GRAVITY = 0x38;

        // Orbit state
        private int orbIndex;
        private int orbitAngle;       // objoff_28: horizontal orbit angle
        private int verticalAngle;    // objoff_3B: vertical orbit angle
        private int tiltFlag;         // objoff_3A(orb): 0 or 1 selects flatten/tilt branch
        private int flattenAngle;     // objoff_3C: 0..$40 expand/contract angle (tilt branch)
        private int depth;            // objoff_30: high word used by SetAnimPriority

        // Break-away / bounce state (Obj53 routine machine)
        private int routine = RT_MAIN;
        private int breakTimer;       // objoff_32: counts down, then flags collide-with-player
        private int bounceAngle;      // objoff_2C (orb): sine angle for ground bounce
        private int bounceBaseY;      // objoff_2E: ground baseline for bounce
        private int xVel;             // x_vel (8.8)
        private int yVel;             // y_vel (8.8)
        private int xSub;             // low word of x_pos during ObjectMoveAndFall
        private int ySub;             // low word of y_pos during ObjectMoveAndFall
        private boolean flipX2;       // render_flags.x_flip for break/bounce/face
        private boolean burstCollisionProperty; // ROM collision_property <= -2 latch

        // Display
        private int mappingFrame = 5; // ROM: move.b #5,mapping_frame (s2.asm:67307)
        private int orbPriority = 3;

        // ROM anim / prev_anim / anim_frame / anim_frame_duration driving
        // mapping_frame through Ani_obj53 via AnimateSprite. Only the break
        // path (anim 2) and burst pop (anim 6, whose $FC command advances the
        // routine) run post-break.
        private int animId;
        private int prevAnimId;
        private int animFrame;
        private int animFrameTimer;

        public MTZBossOrb(Sonic2MTZBossInstance parent, int index, int phaseOffset, int tilt) {
            super(parent, "MTZ Orb " + index, 3, Sonic2ObjectIds.MTZ_BOSS_ORB);
            this.orbIndex = index;
            // ROM: objoff_28 = objoff_3B = byte_329CC[i]; objoff_3A = byte_329D3[i].
            this.orbitAngle = phaseOffset;
            this.verticalAngle = phaseOffset;
            this.tiltFlag = tilt;
            this.flattenAngle = 0; // ROM: move.b #0,objoff_3C
            this.breakTimer = -1;  // objoff_32 starts unset (negative => no timer)
            // ROM Obj53_Init reuses the initializer's own slot for orb 0
            // (movea.l a0,a1 before the AllocateObject loop, s2.asm:67782-67798):
            // that slot's dispatch on the spawn frame is Obj53_Init itself, so
            // orb 0 first runs Obj53_Main (and orbits) one frame after orbs 1-6,
            // which are AllocateObject'd into higher slots mid-pass and run their
            // first Obj53_Main the same frame. Model that by starting orb 0 in
            // routine 0 and consuming its first dispatch as the init pass.
            this.routine = (index == 0) ? RT_INIT : RT_MAIN;
        }

        @Override
        public MTZBossOrb recreateForRewind(RewindRecreateContext ctx) {
            Sonic2MTZBossInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return new MTZBossOrb(boss, 0, 0, 0);
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            // ROM Obj53_Init reuses the initializer's own slot for orb 0
            // (movea.l a0,a1 before the AllocateObject loop, s2.asm:67782-67798):
            // that slot's dispatch this frame was already spent on Obj53_Init, so
            // orb 0 first runs Obj53_Main (and orbits) on the NEXT frame. Orbs 1-6
            // are AllocateObject'd into higher slots mid-pass and run their first
            // Obj53_Main/orbit the same frame.
            return orbIndex == 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!shouldUpdate(vIntRunCount)) {
                return;
            }

            Sonic2MTZBossInstance boss = (Sonic2MTZBossInstance) parent;

            switch (routine) {
                // ROM Obj53_Init (s2.asm:67782-67798): orb 0's spawn-frame
                // dispatch is the init pass itself — no orbit, no display.
                case RT_INIT -> routine = RT_MAIN;
                case RT_MAIN -> updateMain(boss, player);
                case RT_BREAK_AWAY -> updateBreakAway(player);
                case RT_BOUNCE_AROUND -> updateBounceAround(player);
                case RT_BURST -> finishBurst();
                default -> { }
            }


            updateDynamicSpawn();
        }

        /**
         * ROM: Obj53_Main (s2.asm:67340-67375).
         * Mirrors the boss position, then consumes the boss break flag for exactly
         * one orb per frame (objoff_38). On break: increment boss objoff_2C, advance
         * to BreakAway, set y_vel=-$400, x_vel=+/-$80 clamped at the dive boundaries.
         */
        private void updateMain(Sonic2MTZBossInstance boss, AbstractPlayableSprite player) {
            // ROM: tst.b objoff_38(boss) / beq Obj53_ClearBossCollision.
            // consumeOrbBreak() returns true for the first orb that asks this frame
            // (and clears the boss flag), matching the per-frame one-orb ordering.
            if (boss.consumeOrbBreak()) {
                // ROM: addi_.b #1,objoff_2C(boss) — count this broken orb.
                boss.incrementOrbBreakCount();
                routine = RT_BREAK_AWAY;     // addq.b #2,routine
                breakTimer = 60;             // move.b #60,objoff_32
                animId = 2;                  // move.b #2,anim (break-away script)
                yVel = -0x400;               // move.w #-$400,y_vel

                // ROM: d1 = -$80; if (MainCharacter x - x_pos) >= 0 keep, else neg.
                int playerX = (player != null) ? player.getCentreX() : currentX;
                int d1 = -0x80;
                if (playerX - currentX >= 0) {
                    // player to the right => push left (d1 stays -$80 since bpl keeps)
                    d1 = -0x80;
                } else {
                    d1 = 0x80; // neg.w d1
                }
                // ROM: clamp at dive boundaries.
                if (currentX < DIVE_BOUNDARY_LEFT) {
                    d1 = 0x80;
                }
                if (currentX >= DIVE_BOUNDARY_RIGHT) {
                    d1 = -0x80;
                }
                // ROM: bclr x_flip; if d1 >= 0 bset x_flip.
                flipX2 = d1 >= 0;
                xVel = d1;
                xSub = 0;
                ySub = 0;
                // ROM: the break path ends with `bra.s +` into the tail of
                // Obj53_ClearBossCollision (s2.asm:67865-67875), so the orb still
                // runs Obj53_OrbitBoss + Obj53_SetAnimPriority on the same pass:
                // the launch velocities above are computed from the pre-orbit
                // x_pos, then the orbit overwrites x_pos/y_pos so BreakAway
                // starts falling from the freshly orbited position next frame.
                orbitBoss(boss);
                setAnimPriority();
                return;
            }

            // ROM: Obj53_ClearBossCollision + OrbitBoss + SetAnimPriority.
            orbitBoss(boss);
            setAnimPriority();
        }

        /**
         * ROM: Obj53_OrbitBoss (s2.asm:67386-67443). Register-faithful port.
         * a1 = boss, a0 = orb. objoff_38(orb) holds the boss x_pos, objoff_2A(orb)
         * holds boss y_pos - 4 (both copied in Obj53_Main before this call).
         */
        private void orbitBoss(Sonic2MTZBossInstance boss) {
            int bossX = boss.getX();
            int bossY = boss.getY() - 4; // ROM: move.w y_pos(boss),objoff_2A; subi_.w #4

            int outerR = boss.getOuterOrbRadius() & 0xFF; // objoff_33(boss), unsigned byte
            int breakState = boss.getOrbBreakState();

            // d0 = CalcSine(objoff_29); d3 = d0  (objoff_29 = $40 constant)
            int d3 = TrigLookupTable.sinHex(TILT_SCALE_ANGLE);
            // d1 = objoff_33(boss); muls.w d1,d0; d5 = d4 = d0
            int d0 = (short) d3 * outerR; // muls.w: operands are 16-bit signed
            int d5 = d0;
            int d4 = d0;
            // d2 = objoff_39(boss); if objoff_3A(boss) != 0 -> d2 = $10
            // (docs/s2disasm/s2.asm:67896-67901). The per-orb objoff_3A tilt
            // byte from Obj53_Init is not the gate for this flattening branch.
            int d2 = boss.getInnerOrbParam() & 0xFF;
            if (breakState != 0) {
                d2 = 0x10;
            }
            // muls.w d3,d2 (16-bit signed operands)
            d2 = (short) d2 * (short) d3;
            // d6 = objoff_38(orb) = bossX
            int d6 = bossX;
            // d0 = CalcSine(objoff_28); muls.w d0,d5; swap d5 (>>16); add.w d6,d5
            d0 = TrigLookupTable.sinHex(orbitAngle & 0xFF);
            d5 = ((short) d0 * (short) d5) >> 16; // muls.w then swap (take high word)
            d5 += d6;
            currentX = (short) d5; // move.w d5,x_pos
            // muls.w d1,d4; swap d4; move.w d4,objoff_30
            d4 = ((short) d4 * outerR) >> 16;
            depth = (short) d4; // move.w d4,objoff_30
            // d6 = objoff_2A(orb) = bossY - 4
            d6 = bossY;
            // d0 = CalcSine(objoff_3B); if objoff_3A(boss) != 0 -> CalcSine(objoff_3C)
            int vertAngle = (breakState != 0) ? (flattenAngle & 0xFF) : (verticalAngle & 0xFF);
            d0 = TrigLookupTable.sinHex(vertAngle);
            // muls.w d0,d2; swap d2 (>>16); add.w d6,d2
            d2 = ((short) d0 * (short) d2) >> 16;
            d2 += d6;
            currentY = (short) d2; // move.w d2,y_pos

            // ROM: addq.b #4,objoff_28
            orbitAngle = (orbitAngle + 4) & 0xFF;

            if (breakState == 0) {
                // ROM: tst.b objoff_3A(boss); bne.s +; addq.b #8,objoff_3B; rts
                verticalAngle = (verticalAngle + 8) & 0xFF;
                return;
            }

            // tilt branch: expand/contract objoff_3C based on boss objoff_3A state.
            // ROM: cmpi.b #-1,objoff_3A(boss) -> expanding; #$80 -> contracting.
            if (breakState == -1) {
                // ROM: cmpi.b #$40,objoff_3C / bhs return / addq.b #2,objoff_3C
                if (flattenAngle < 0x40) {
                    flattenAngle += 2;
                }
            } else if (breakState == (byte) 0x80) {
                // ROM: subq.b #2,objoff_3C / bpl return / clr objoff_3C; objoff_3A(boss)=0
                flattenAngle -= 2;
                if (flattenAngle < 0) {
                    // ROM: clr.b objoff_3C then move.b #0,objoff_3A(boss) — nothing
                    // else. objoff_3B is NOT touched on the contraction-complete
                    // frame; the clearing orb resumes its +8 increments the next
                    // frame, while orbs running later in the same pass already see
                    // objoff_3A == 0 and increment immediately (s2.asm Obj53_OrbitBoss
                    // tilt tail).
                    flattenAngle = 0;
                    boss.setOrbBreakState(0); // move.b #0,objoff_3A(boss)
                }
            }
        }

        /** Ani_obj53 (s2.asm Obj53 animation table): delay byte then frame/command codes. */
        private static final int[][] ANI_OBJ53 = {
            {0x0F, 2, 0xFF},
            {1, 0, 1, 0xFF},
            {3, 5, 5, 5, 5, 5, 5, 5, 5, 6, 7, 6, 7, 6, 7, 8, 9, 0x0A, 0x0B, 0xFE, 1},
            {7, 0x0C, 0x0D, 0xFF},
            {7, 0x0E, 0x0F, 0x0E, 0x0F, 0x0E, 0x0F, 0x0E, 0x0F, 0xFD, 3},
            {7, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0xFD, 3},
            {1, 0x14, 0xFC},
            {7, 0x11, 0xFF},
        };

        /**
         * ROM: AnimateSprite (s2.asm:30422-30490) against Ani_obj53. Faithful
         * port of the commands Obj53 reaches: plain frames, $FF restart,
         * $FE,n jump-back, $FD,n switch-anim, $FC routine advance.
         */
        private void animateSprite() {
            int[] script = ANI_OBJ53[animId & 7];
            if (animId != prevAnimId) {
                prevAnimId = animId;
                animFrame = 0;
                animFrameTimer = 0;
            }
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }
            animFrameTimer = script[0];
            int code = script[1 + animFrame];
            if (code < 0x80) {
                mappingFrame = code & 0x7F;
                animFrame++;
                return;
            }
            switch (code) {
                case 0xFF -> {
                    mappingFrame = script[1] & 0x7F;
                    animFrame = 1;
                }
                case 0xFE -> {
                    int back = script[2 + animFrame];
                    animFrame -= back;
                    mappingFrame = script[1 + animFrame] & 0x7F;
                    animFrame++;
                }
                case 0xFD -> animId = script[2 + animFrame];
                case 0xFC -> routine += 2; // addq.b #2,routine (Anim_End_FC)
                default -> { }
            }
        }

        /**
         * ROM: Obj53_Animate prologue + AnimateSprite (no-fixBugs). While
         * collision_property is at or below -2, EVERY call re-latches the burst
         * frame and advances the routine by 2, so an attacked orb steps
         * 4 -> 6 -> 8 on consecutive frames before Obj53_Burst deletes it.
         */
        private void animateWithBurstLatch() {
            if (burstCollisionProperty) {
                mappingFrame = FRAME_BURST; // move.b #$14,mapping_frame
                animId = 6;                 // move.b #6,anim
                routine += 2;               // addq.b #2,routine
            }
            animateSprite();
        }

        /**
         * ROM: Obj53_SetAnimPriority (s2.asm:67449-67473).
         * Uses the orb's own depth word (objoff_30) vs +$C / -$C, not a re-derived sine.
         */
        private void setAnimPriority() {
            int d0 = depth; // objoff_30 (signed word)
            if (d0 >= 0) {
                if (d0 >= 0x0C) {
                    mappingFrame = 3;
                    orbPriority = 1;
                } else {
                    mappingFrame = 4;
                    orbPriority = 2;
                }
            } else if (d0 >= -0x0C) {
                // ROM: cmpi.w #-$C,d0 / blt + (so -$C <= d0 < 0 here)
                mappingFrame = 4;
                orbPriority = 6;
            } else {
                mappingFrame = 5;
                orbPriority = 7;
            }
            priority = orbPriority;
        }

        /**
         * ROM: Obj53_BreakAway (s2.asm:67476-67519).
         * ObjectMoveAndFall adds +$38, then subi #$20 (net +$18/frame), clamp y_vel
         * to max $180, floor at y=$4AC, then advance to BounceAround.
         */
        private void updateBreakAway(AbstractPlayableSprite player) {
            // ROM: tst.b objoff_32 / bmi + / subq.b #1 / bpl + / move.b #$DA,collision_flags
            if (breakTimer >= 0) {
                breakTimer--;
                // when it goes negative this frame, switch to the hit collision flags
            }

            // ROM: ObjectMoveAndFall moves x_pos/y_pos as longwords, then Obj53
            // subtracts $20 from y_vel. Preserve x_sub/y_sub so ±$80 horizontal
            // velocity advances one pixel every other frame.
            SubpixelMotion.State motion = new SubpixelMotion.State(currentX, currentY, xSub, ySub, xVel, yVel);
            SubpixelMotion.objectFallXY(motion, ORB_GRAVITY);
            currentX = motion.x;
            currentY = motion.y;
            xSub = motion.xSub;
            ySub = motion.ySub;
            yVel = motion.yVel - 0x20; // subi.w #$20,y_vel => net +$18
            // ROM: cmpi.w #$180,y_vel / blt + / move.w #$180,y_vel
            if (yVel >= 0x180) {
                yVel = 0x180;
            }

            // ROM: cmpi.w #$4AC,y_pos / blo Obj53_Animate (still falling)
            if (currentY >= GROUND_Y) {
                currentY = GROUND_Y;            // move.w #$4AC,y_pos
                bounceBaseY = GROUND_Y;         // move.w #$4AC,objoff_2E
                bounceAngle = 1;                // move.b #1,objoff_2C(orb)
                routine = RT_BOUNCE_AROUND;     // addq.b #2,routine
                faceLeader(player);             // bsr Obj53_FaceLeader
            }

            // ROM: both the still-falling and just-landed paths end in
            // Obj53_Animate (property latch + AnimateSprite), advancing the
            // break animation toward the settled frame $B during the fall.
            animateWithBurstLatch();
        }

        /**
         * ROM: Obj53_BounceAround (s2.asm:67522-67561).
         * y = $4AC - (sin(objoff_2C) >> 2); increment objoff_2C; +/-1px x drift on
         * objoff_2C bit0; FaceLeader on ground touch. Stays alive until burst.
         */
        private void updateBounceAround(AbstractPlayableSprite player) {
            // ROM: tst.b objoff_32 / subq.b #1 / bpl + / move.b #$DA,collision_flags
            if (breakTimer >= 0) {
                breakTimer--;
            }

            // ROM: bsr Obj53_CheckPlayerHit: the bouncing orb pops (burst frame
            // + anim 6, NO routine change here) when either player is in the
            // hurt routine, or when this orb has been attacked
            // (collision_property <= -2). The routine advance happens in
            // Obj53_Animate's prologue (attacked) or via anim 6's $FC command.
            boolean anyPlayerHurt = player != null && player.isHurt();
            if (!anyPlayerHurt
                    && services().playerQuery().nativeP2OrNull()
                            instanceof AbstractPlayableSprite sidekick
                    && sidekick.isHurt()) {
                anyPlayerHurt = true;
            }
            if (anyPlayerHurt || burstCollisionProperty) {
                mappingFrame = FRAME_BURST; // move.b #$14,mapping_frame
                animId = 6;                 // move.b #6,anim
            }

            // ROM: cmpi.b #$B,mapping_frame / bne.s Obj53_Animate: the sine
            // bounce only runs once the break animation settles on frame $B;
            // until then the orb holds its landing position and animates.
            if (mappingFrame != 0x0B) {
                animateWithBurstLatch();
                return;
            }

            // ROM: d0 = CalcSine(objoff_2C); neg; asr #2; d0 += objoff_2E
            int d0 = -TrigLookupTable.sinHex(bounceAngle & 0xFF);
            d0 = d0 >> 2;
            d0 += bounceBaseY;
            if (d0 >= GROUND_Y) {
                // ROM: cmpi.w #$4AC,d0 / bhs + : touched ground, re-face leader.
                currentY = GROUND_Y;        // move.w #$4AC,y_pos
                faceLeader(player);         // bsr Obj53_FaceLeader
                bounceAngle = 1;            // move.b #1,objoff_2C(orb)
                return;
            }
            currentY = d0;                  // move.w d0,y_pos
            bounceAngle = (bounceAngle + 1) & 0xFF; // addq.b #1,objoff_2C(orb)
            // ROM: btst #0,objoff_2C / beq display ; else +/-1px x by x_flip.
            if ((bounceAngle & 1) != 0) {
                int dx = -1;
                if (flipX2) {
                    dx = 1; // neg.w d0 when x_flip set
                }
                currentX += dx;
            }
        }

        /**
         * ROM: Obj53_FaceLeader (s2.asm:67564-67573).
         * Face toward MainCharacter (clear x_flip if player is left, set if right).
         */
        private void faceLeader(AbstractPlayableSprite player) {
            if (player == null) {
                return;
            }
            int d0 = player.getCentreX() - currentX;
            // ROM: bpl + (player at/right => set x_flip); else clear.
            flipX2 = d0 >= 0;
        }

        /**
         * ROM: Obj53_Burst (s2.asm:67593-67598).
         * PlaySound boss-explosion; decrement boss objoff_2C; DeleteObject.
         */
        private void finishBurst() {
            Sonic2MTZBossInstance boss = (Sonic2MTZBossInstance) parent;
            boss.services().playSfx(Sonic2Sfx.BOSS_EXPLOSION.id);
            boss.decrementOrbBreakCount(); // ROM: subi_.b #1,objoff_2C(boss)
            setDestroyed(true);            // ROM: DeleteObject
        }

        // ====================================================================
        // Touch response (ROM: collision_flags $87 intact, $DA after timer;
        // collision_property = 2 so the per-frame poll flips to Burst on a
        // rolling-player hit — no already-hit latch).
        // ====================================================================

        @Override
        public int getCollisionFlags() {
            if (routine == RT_BURST) {
                return 0;
            }
            // ROM Touch_Enemy_Part2 clears the attacked orb's collision_flags in
            // RAM the moment the hit lands (col=$DA,prop=$02 -> col=$00,prop=$FE,
            // round-93 PC probe). The other player's touch pass in the same or the
            // following frame must therefore skip this orb even though its routine
            // only advances to Obj53_Burst on its own next update.
            if (burstCollisionProperty) {
                return 0;
            }
            // ROM: $DA once objoff_32 has expired during break/bounce; $87 otherwise.
            if ((routine == RT_BREAK_AWAY || routine == RT_BOUNCE_AROUND) && breakTimer < 0) {
                return ORB_COLLISION_HIT; // 0xDA (BOSS category)
            }
            return ORB_COLLISION; // 0x87 (SPECIAL category)
        }

        @Override
        public int getCollisionProperty() {
            return ORB_COLLISION_PROPERTY; // ROM: collision_property = 2
        }

        @Override
        public boolean requiresContinuousTouchCallbacks() {
            // ROM Obj53_CheckPlayerHit polls every frame while overlapping.
            return true;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TouchResponseProfile.fromProvider(this);
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TouchResponseProfile.fromProvider(this, multiRegionSource);
        }

        @Override
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            // ROM: a rolling player hitting the $DA (BOSS-category) orb drives
            // collision_property <= -2. Obj53's own update later advances through
            // Obj53_Animate/Obj53_Burst; Touch_Boss does not decrement the parent
            // broken-orb count directly.
            if (routine == RT_BREAK_AWAY || routine == RT_BOUNCE_AROUND) {
                burstCollisionProperty = true;
            }
        }

        @Override
        public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
            // Listener-only path for the SPECIAL ($87) intact orb: no harm to the
            // player and no burst (ROM Obj53 SPECIAL touch is inert).
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager =
                    ((Sonic2MTZBossInstance) parent).services().renderManager();
            if (renderManager == null) {
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            // While orbiting, the orb follows the boss facing; after break it uses
            // its own x_flip (ROM render_flags.x_flip on the orb).
            boolean flipped = (routine == RT_MAIN)
                    ? (parent.getState().renderFlags & 1) != 0
                    : flipX2;
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, flipped, false);
        }

        @Override
        public int getPriorityBucket() {
            return orbPriority;
        }
    }

    // =========================================================================
    // Inner class: Laser Shooter (Obj54 subtype 6)
    // ROM: Obj54_LaserShooter (s2.asm:67641-67691)
    // =========================================================================

    /**
     * Laser shooter child that follows boss position.
     * ROM: Positioned at boss x/y, renders frame $13.
     */
    public static class MTZLaserShooter extends AbstractBossChild implements RewindRecreatable {

        public MTZLaserShooter(Sonic2MTZBossInstance parent) {
            super(parent, "MTZ Laser Shooter", 6, Sonic2ObjectIds.MTZ_BOSS);
        }

        @Override
        public MTZLaserShooter recreateForRewind(RewindRecreateContext ctx) {
            Sonic2MTZBossInstance boss = nearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            MTZLaserShooter shooter = new MTZLaserShooter(boss);
            boss.laserShooter = shooter;
            // Re-registration into boss.childComponents is handled generically by
            // AbstractBossChild#onRecreatedForRewind() -- single mechanism, no local duplicate.
            return shooter;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!shouldUpdate(vIntRunCount)) {
                return;
            }
            // Follow boss position
            syncPositionWithParent();
            updateDynamicSpawn();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager =
                    ((Sonic2MTZBossInstance) parent).services().renderManager();
            if (renderManager == null) {
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            boolean flipped = (parent.getState().renderFlags & 1) != 0;
            renderer.drawFrameIndex(FRAME_LASER_SHOOTER, currentX, currentY, flipped, false);
        }
    }

    // =========================================================================
    // Inner class: Laser projectile (Obj54 subtype 4 => Obj54_Laser)
    // ROM: Obj54_Laser_Init / Obj54_Laser_Main (s2.asm:67601-67642)
    // =========================================================================

    /**
     * Horizontal laser fired by the MTZ boss during the dive attack.
     *
     * <p>ROM: spawned by Obj54_FireLaser (boss_subtype=4 => Obj54_Laser). It is an
     * independent projectile (NOT a boss child) so it keeps travelling and hurting
     * the player even after the boss moves. It reuses the already-loaded MTZ boss
     * art/mappings ({@link Sonic2ObjectArtKeys#MTZ_BOSS}), rendering frame $12.
     *
     * <p>Touch model mirrors {@link SlicerPincerInstance}: HURT collision via
     * {@code getCollisionFlags()} polled every frame (no already-hit latch).
     */
    public static class MTZBossLaser extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {

        /** ROM: Obj54_Laser_Init move.w #-$400,d0 (s2.asm:67625) — base x velocity. */
        private static final int LASER_SPEED = 0x400;
        /** ROM: move.b #$99,collision_flags (s2.asm:67632). $99 & $C0 = $80 = HURT. */
        private static final int COLLISION_FLAGS = 0x99;
        /** ROM: Obj54_Laser_Main left delete bound, cmpi.w #$2AB0 (s2.asm:67638). */
        private static final int DELETE_LEFT = 0x2AB0;
        /** ROM: Obj54_Laser_Main right delete bound, cmpi.w #$2BF0 (s2.asm:67640). */
        private static final int DELETE_RIGHT = 0x2BF0;

        private int currentX;
        private int currentY;
        // Un-final for rewind: GenericFieldCapturer skips final scalars and xVel
        // is NOT spawn-derivable (getSpawn() reports current position, not firing
        // direction). The parent-relink recreate hook passes a placeholder and the
        // capturer reapplies xVel on restore.
        private int xVel; // 8.8 fixed point
        private boolean firstUpdate = true;

        public MTZBossLaser(Sonic2MTZBossInstance boss, int bossX, int bossY, boolean facingRight) {
            super(new ObjectSpawn(bossX, bossY, Sonic2ObjectIds.MTZ_BOSS, 0, 0, false, 0),
                    "MTZ Boss Laser");
            // ROM Obj54_Laser_Init (s2.asm:67619-67631):
            //   x = boss x; y = boss y + 7; x -= 4; d0 = -$400.
            //   if boss x_flip set: neg d0 (=> +$400) and x += 8.
            int x = bossX - 4;
            int v = -LASER_SPEED;
            if (facingRight) {
                v = LASER_SPEED;
                x += 8;
            }
            this.currentX = x;
            this.currentY = bossY + 7;
            this.xVel = v;
            // NOTE: the ROM PlaySound SndID_LaserBurst is in Init, but objects must not
            // call services() during construction (TestNoServicesInObjectConstructors),
            // so the SFX is emitted on the first update() below instead.
        }

        MTZBossLaser() {
            this(null, 0, 0, false);
        }

        @Override
        public MTZBossLaser recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            int x = spawn == null ? 0 : spawn.x();
            int y = spawn == null ? 0 : spawn.y();
            return new MTZBossLaser(null, x, y, false);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (firstUpdate) {
                firstUpdate = false;
                // ROM: Obj54_Laser_Init PlaySound SndID_LaserBurst (== Sonic2Sfx.LASER_BURST $EA).
                services().playSfx(Sonic2Sfx.LASER_BURST.id);
            }
            // ROM: Obj54_Laser_Main (s2.asm:67636-67642).
            // ObjectMove (16.8 fixed): x_pos += x_vel.
            currentX += (xVel >> 8);
            // ROM: cmpi.w #$2AB0,x_pos / blo DeleteObject ; cmpi.w #$2BF0 / bhs DeleteObject.
            if (currentX < DELETE_LEFT || currentX >= DELETE_RIGHT) {
                setDestroyed(true);
            }
        }

        @Override
        public int getCollisionFlags() {
            // ROM: $99 (HURT category). Mirrors SlicerPincer's HURT-style provider flags.
            return COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public boolean requiresContinuousTouchCallbacks() {
            // Hurt the player every frame the overlap persists (no consumed-once latch).
            return true;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TouchResponseProfile.fromProvider(this);
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TouchResponseProfile.fromProvider(this, multiRegionSource);
        }

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
            // ROM: move.b #5,priority (s2.asm:67616).
            return 5;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            // ROM frame $12; flip to match travel direction (positive vel = facing right).
            boolean flipped = xVel > 0;
            renderer.drawFrameIndex(FRAME_LASER, currentX, currentY, flipped, false);
        }
    }
}
