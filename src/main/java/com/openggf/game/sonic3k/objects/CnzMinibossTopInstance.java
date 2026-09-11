package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.Arrays;
import java.util.List;

/**
 * CNZ Act 1 miniboss top piece — the bouncing-ball projectile the player
 * ricochets off the arena walls and onto the miniboss base.
 *
 * <p>ROM anchors (all {@code sonic3k.asm}, S&amp;K-side):
 * <ul>
 *   <li>{@code Obj_CNZMinibossTop}          line 145004 — outer dispatch.</li>
 *   <li>{@code CNZMinibossTop_Index}        line 145011 — 4-routine table.</li>
 *   <li>{@code Obj_CNZMinibossTopInit}      line 145018 — routine 0.</li>
 *   <li>{@code Obj_CNZMinibossTopWait}      line 145026 — routine 2.</li>
 *   <li>{@code Obj_CNZMinibossTopWait2}     line 145040 — routine 4.</li>
 *   <li>{@code Obj_CNZMinibossTopGo}        line 145045 — {@code $34}
 *       post-wait handler that advances routine 2 to routine 4.</li>
 *   <li>{@code Obj_CNZMinibossTopMain}      line 145053 — routine 6
 *       (bouncing-ball body).</li>
 *   <li>{@code CNZMiniboss_BlockExplosion}  line 145204 — snaps impact
 *       coordinates to the 0x20-pixel arena block grid.</li>
 * </ul>
 *
 * <p>The ROM routine uses {@code MoveSprite2} (no gravity) — the ball
 * maintains constant speed and simply reverses the relevant velocity
 * component on contact with an arena edge. When the ball hits a wall,
 * ceiling or floor, the impact X/Y are published through
 * {@code Events_bg+$00/$02} so the base and the event bridge can drive
 * chunk-destruction and base-lowering sequences that live outside this
 * object. Those side effects flow through the explicit
 * {@link S3kCnzEventWriteSupport#queueArenaChunkDestruction} bridge to
 * keep the object -&gt; events dependency testable.
 */
public final class CnzMinibossTopInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_CNZMinibossTop} (docs/skdisasm/sonic3k.asm:145009) is spawned by its parent rather than from the
     * object pointer table; every routine in its code block lies in the
     * {@code $0006xxxx} bank.
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0006}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0006;
    }


    // ---- Routine indices (CNZMinibossTop_Index, sonic3k.asm:145011) ----
    /** Routine 0 — Obj_CNZMinibossTopInit (sonic3k.asm:145018). */
    private static final int ROUTINE_INIT = 0;
    /** Routine 2 — Obj_CNZMinibossTopWait (sonic3k.asm:145026). */
    private static final int ROUTINE_WAIT = 2;
    /** Routine 4 — Obj_CNZMinibossTopWait2 (sonic3k.asm:145040). */
    private static final int ROUTINE_WAIT2 = 4;
    /** Routine 6 — Obj_CNZMinibossTopMain (sonic3k.asm:145053). */
    private static final int ROUTINE_MAIN = 6;

    private static final int FRAME_TOP_WAIT = 7;
    private static final int FRAME_TOP_MAIN = 9;
    /**
     * ROM: {@code AniRaw_CNZMinibossTop} (sonic3k.asm:145709).
     * Byte 0 seeds {@code $2E(a0)}, byte 1 is the terminal {@code $2F(a0)}
     * loop count, and bytes from offset 2 are mapping frames until {@code $FC}.
     */
    private static final int TOP_SPINUP_INITIAL_DELAY = 0x07;
    private static final int TOP_SPINUP_TERMINAL_LOOPS = 0x08;
    private static final int[] TOP_SPINUP_MAPPING_FRAMES = {7, 8, 9};
    /** ROM: {@code AniRaw_CNZMinibossTop2} (sonic3k.asm:145711). */
    private static final int TOP_MAIN_DELAY = 0;
    private static final int[] TOP_MAIN_FRAMES = {7, 8, 9};
    private static final int TOP_COLLISION_FLAGS = 0xAA;
    private static final int TOP_Y_RADIUS = 8;
    // Obj_CNZMinibossTopMain passes d1=$13,d2=$C,d3=8 to SolidObjectFull
    // after MoveSprite2 (sonic3k.asm:145057-145063).
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x13, 0x0C, 0x08);
    private static final int PLAYER_BOUNCE_Y_OFFSET = 0x0C;
    private static final int PLAYER_BOUNCE_HALF_SIZE = 0x10;

    /** Mirrors the parent-boss reference used by {@code parent3(a0)} in ROM. */
    private CnzMinibossInstance boss;
    private int parentOffsetX;
    private int parentOffsetY;

    /** 16:8 motion state backing {@code x_pos}/{@code y_pos}/{@code x_vel}/{@code y_vel}. */
    private final SubpixelMotion.State motion;

    /** Current routine byte (ROM {@code routine(a0)} at offset 0x05). */
    private int routine;

    /** ROM: {@code mapping_frame(a0)}. */
    private int mappingFrame;
    /** ROM: status bit 5, set while Animate_RawGetFaster owns the raw script. */
    private boolean wait2RawActive;
    /** ROM: {@code $2E(a0)} for Animate_RawGetFaster. */
    private int wait2RawDelay;
    /** ROM: {@code $2F(a0)} for Animate_RawGetFaster. */
    private int wait2RawLoopCounter;
    /** ROM: {@code anim_frame(a0)}. */
    private int rawAnimFrame;
    /** ROM: {@code anim_frame_timer(a0)}. */
    private int rawAnimFrameTimer;
    private int mainFrameIndex;
    private int mainFrameTimer;
    private String diagnosticLastMainBranch = "none";
    private boolean diagnosticHitBaseThisFrame;
    private boolean diagnosticPlayerBounceThisFrame;
    private boolean nativeP2BounceUsesPreUpdateSolidPosition;
    private boolean diagnosticArenaImpactThisFrame;
    private int diagnosticArenaImpactX;
    private int diagnosticArenaImpactY;
    private String diagnosticLastP1Solid = "none";
    private String diagnosticLastP2Solid = "none";
    private static final int DIAGNOSTIC_BRANCH_HISTORY_SIZE = 16;
    private final int[] diagnosticBranchHistoryFrame = new int[DIAGNOSTIC_BRANCH_HISTORY_SIZE];
    private final int[] diagnosticBranchHistoryX = new int[DIAGNOSTIC_BRANCH_HISTORY_SIZE];
    private final int[] diagnosticBranchHistoryY = new int[DIAGNOSTIC_BRANCH_HISTORY_SIZE];
    private final int[] diagnosticBranchHistoryXVel = new int[DIAGNOSTIC_BRANCH_HISTORY_SIZE];
    private final int[] diagnosticBranchHistoryYVel = new int[DIAGNOSTIC_BRANCH_HISTORY_SIZE];
    private final String[] diagnosticBranchHistoryBranch = new String[DIAGNOSTIC_BRANCH_HISTORY_SIZE];
    private int diagnosticBranchHistoryCursor;
    private int diagnosticBranchHistoryCount;
    private int diagnosticCurrentVIntRunCount;

    // ---- Arena collision seam preserved from Task 7 scaffold ----
    private boolean arenaCollisionPending;
    private int pendingChunkWorldX;
    private int pendingChunkWorldY;

    public CnzMinibossTopInstance(ObjectSpawn spawn) {
        super(spawn, "CNZMinibossTop");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.routine = ROUTINE_INIT;
        this.mappingFrame = FRAME_TOP_WAIT;
        Arrays.fill(diagnosticBranchHistoryFrame, -1);
        Arrays.fill(diagnosticBranchHistoryBranch, "none");
    }

    @Override
    public CnzMinibossTopInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzMinibossInstance parent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, CnzMinibossInstance.class);
        if (parent == null) {
            return null;
        }
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(ctx.spawn());
        top.attachBossForTest(parent);
        return top;
    }

    /**
     * Test seam used to make the parent/base dependency explicit without
     * requiring the full child-object spawn chain from the real boss.
     *
     * <p>Preserved verbatim from the Task-7 scaffold. Kept {@code public}
     * because {@code TestS3kCnzMinibossArenaHeadless} (in
     * {@code com.openggf.tests}) consumes it cross-package; the
     * within-package physics tests go through the package-private
     * variants below.
     */
    public void attachBossForTest(CnzMinibossInstance boss) {
        this.boss = boss;
        if (boss != null) {
            parentOffsetX = motion.x - boss.getCentreX();
            parentOffsetY = motion.y - boss.getCentreY();
        }
    }

    /**
     * Schedules one ROM-shaped arena collision.
     *
     * <p>Preserved verbatim from the Task-7 scaffold. Inputs are
     * already expected to be world coordinates aligned to the same block
     * grid {@code CNZMiniboss_BlockExplosion} uses after masking with
     * {@code $FFE0} and adding {@code $10}. Kept {@code public} for the
     * same cross-package reason as {@link #attachBossForTest}.
     */
    public void forceArenaCollisionForTest(int chunkWorldX, int chunkWorldY) {
        arenaCollisionPending = true;
        pendingChunkWorldX = chunkWorldX;
        pendingChunkWorldY = chunkWorldY;
    }

    /**
     * Test seam: jumps directly into {@link #ROUTINE_MAIN} with a
     * ROM-shaped initial velocity ({@code 0x200}, {@code 0x200}) so the
     * bouncing-ball body has something to integrate against.
     *
     * <p>Mirrors the ROM state right after {@link #onTopGo()} fires —
     * routine 6 with {@code x_vel = y_vel = 0x200}. Tests that call this
     * skip routines 0/2/4 and observe only the bouncing body.
     *
     * <p>Package-private — only consumed by within-package physics tests
     * (e.g. {@code TestCnzMinibossTopPhysics}).
     */
    void forceTopMainForTest() {
        routine = ROUTINE_MAIN;
        motion.xVel = Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_X_VEL;
        motion.yVel = Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_Y_VEL;
        startMainAnimation();
    }

    void forceTopMainForTest(int x, int y, int xVel, int yVel) {
        routine = ROUTINE_MAIN;
        motion.x = x;
        motion.y = y;
        motion.xSub = 0;
        motion.ySub = 0;
        motion.xVel = xVel;
        motion.yVel = yVel;
        startMainAnimation();
        updateDynamicSpawn(motion.x, motion.y);
    }

    /**
     * Test seam: returns the current routine byte. Package-private — only
     * consumed by within-package physics tests.
     */
    int getCurrentRoutineForTest() {
        return routine;
    }

    boolean retainsParentForTest(CnzMinibossInstance expected) {
        return boss == expected;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        diagnosticCurrentVIntRunCount = vIntRunCount;
        // The saved x_pos passed to SolidObjectFull belongs only to this
        // object's current dispatch. A P2 rebound below may arm the folded
        // post-update solid checkpoint again for this frame; it must not leak
        // into the next dispatch while P2 is still travelling upward.
        nativeP2BounceUsesPreUpdateSolidPosition = false;
        resetTraceFrameFlags();
        // Arena collision seam is still driven by forceArenaCollisionForTest —
        // run it before the state machine so the Task-7 contract (attachBossForTest
        // + forceArenaCollisionForTest + update → bridge + base lowering) stays
        // byte-for-byte identical regardless of which routine we're in.
        if (arenaCollisionPending) {
            arenaCollisionPending = false;
            publishArenaChunkImpact(pendingChunkWorldX, pendingChunkWorldY);
        }

        switch (routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_WAIT -> updateWait();
            case ROUTINE_WAIT2 -> updateWait2();
            case ROUTINE_MAIN -> updateMain(player);
            default -> {
                // Out-of-range writes are a bug; silently ignore rather than
                // crashing a frame loop. The normal 4-routine dispatch above
                // covers every ROM entry, so this branch is unreachable in
                // the happy path.
            }
        }
        // ROM parity note: Obj_CNZMinibossTop writes its position through
        // Events_bg+$00/$02 on every frame, but those words are only read
        // by CNZMiniboss_BlockExplosion when an impact actually fires.
        // The engine's impact path already flows through
        // publishArenaChunkImpact() below, which writes through the CNZ
        // bridge at the correct moment. A per-frame publish here would
        // add no consumer-visible state while breaking the
        // arena-destruction counter (one 0x20-pixel row per queued
        // impact, not per tick) that downstream tests assert against,
        // so we deliberately skip the per-frame write.
    }

    /**
     * ROM: Obj_CNZMinibossTopInit (sonic3k.asm:145018):
     * <pre>
     *   lea ObjDat3_CNZMinibossTop(pc),a1
     *   jsr (SetUp_ObjAttributes3).l        ; addq.b #2,routine(a0) tail
     *   move.b #$10,x_radius(a0)
     *   move.b #8,y_radius(a0)
     *   rts
     * </pre>
     *
     * <p>The ROM's {@code SetUp_ObjAttributes3} tail advances
     * {@code routine(a0)} from 0 to 2, so Init runs exactly once. The
     * engine mirrors that by bumping {@link #routine} here rather than
     * relying on the external dispatch loop to advance it.
     */
    private void updateInit() {
        routine = ROUTINE_WAIT;
    }

    /**
     * ROM: Obj_CNZMinibossTopWait (sonic3k.asm:145026):
     * <pre>
     *   movea.w parent3(a0),a1
     *   btst    #1,$38(a1)
     *   bne.s   loc_6DC10             ; Wait for signal from main boss
     *   jmp     (Refresh_ChildPosition).l
     *
     * loc_6DC10:
     *   move.b  #4,routine(a0)
     *   move.l  #AniRaw_CNZMinibossTop,$30(a0)
     *   move.l  #Obj_CNZMinibossTopGo,$34(a0)
     *   rts
     * </pre>
     *
     * <p>The ROM gates the transition on {@code $38} bit 1 of the parent
     * boss — which {@code Obj_CNZMinibossGo2} (sonic3k.asm:144906,
     * {@code bset #1,$38(a0)}) sets during the base's Init/Lower/Move
     * handoff. When the boss is absent (for example, the physics test
     * builds the top piece without a parent), the engine falls through
     * to the ROM's "signal received" branch so the state machine can
     * still exercise the routine-4/6 cadence deterministically.
     */
    private void updateWait() {
        if (boss != null && !boss.isParentSignalBit1Set()) {
            diagnosticLastMainBranch = "wait_refresh";
            refreshChildPosition();
            // Still waiting — ROM tail is Refresh_ChildPosition which we model
            // via publishCentrePosition() in the caller.
            return;
        }
        // ROM sonic3k.asm:145034 — move.b #4,routine(a0).
        routine = ROUTINE_WAIT2;
        // ROM sonic3k.asm:145035-145036 — install AniRaw_CNZMinibossTop in
        // $30(a0) and Obj_CNZMinibossTopGo in $34(a0). Animate_RawGetFaster
        // claims and initializes the script on the next Wait2 update.
        startSpinupAnimation();
    }

    /**
     * ROM: Obj_CNZMinibossTopWait2 (sonic3k.asm:145040):
     * <pre>
     *   jsr (Refresh_ChildPosition).l
     *   jmp (Animate_RawGetFaster).l
     * </pre>
     *
     * <p>The ROM body is a pair of tail calls. {@code Animate_RawGetFaster}
     * advances {@code $30(a0)} against the {@code AniRaw_CNZMinibossTop}
     * script; when that script's {@code $FC} terminator hits,
     * {@code Obj_CNZMinibossTopGo} fires via {@code $34(a0)} in the same
     * raw-animation update.
     */
    private void updateWait2() {
        diagnosticLastMainBranch = "wait2";
        refreshChildPosition();
        animateRawGetFasterTop();
    }

    /**
     * ROM: Obj_CNZMinibossTopGo (sonic3k.asm:145045):
     * <pre>
     *   move.b #6,routine(a0)
     *   move.l #AniRaw_CNZMinibossTop2,$30(a0)
     *   move.w #$200,x_vel(a0)
     *   move.w #$200,y_vel(a0)              ; Set initial speed of top
     *   rts
     * </pre>
     *
     * <p>Fires from {@link #updateWait2()} when the ROM-script-equivalent
     * countdown expires. Seeds the Main-routine bouncing-ball velocities
     * (+{@code 0x200} on both axes, corresponding to 2px/frame in 16:8
     * fixed point).
     */
    private void onTopGo() {
        diagnosticLastMainBranch = "top_go";
        routine = ROUTINE_MAIN;
        motion.xVel = Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_X_VEL;
        motion.yVel = Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_Y_VEL;
        startMainAnimation();
    }

    private void resetTraceFrameFlags() {
        diagnosticLastMainBranch = "none";
        diagnosticHitBaseThisFrame = false;
        diagnosticPlayerBounceThisFrame = false;
        diagnosticArenaImpactThisFrame = false;
        diagnosticArenaImpactX = 0;
        diagnosticArenaImpactY = 0;
    }

    private void startSpinupAnimation() {
        wait2RawActive = false;
        wait2RawDelay = 0;
        wait2RawLoopCounter = 0;
        // ROM does not clear anim_frame or anim_frame_timer when Wait installs
        // $30/$34; the fresh object reaches Wait2 with both bytes still zero.
        rawAnimFrame = 0;
        rawAnimFrameTimer = 0;
    }

    /**
     * ROM: {@code Animate_RawGetFaster} (sonic3k.asm:177749) over
     * {@code AniRaw_CNZMinibossTop} (sonic3k.asm:145709). Fresh scripts
     * {@code bset #5,$38(a0)}, copy byte 0 to {@code $2E}, clear
     * {@code $2F}, and advance {@code anim_frame} before reading from
     * {@code 2(a1,d0)}, so frame 8 is the first visible Wait2 mapping frame.
     */
    private void animateRawGetFasterTop() {
        if (!wait2RawActive) {
            wait2RawActive = true;
            wait2RawDelay = TOP_SPINUP_INITIAL_DELAY;
            wait2RawLoopCounter = 0;
        }

        rawAnimFrameTimer--;
        if (rawAnimFrameTimer >= 0) {
            return;
        }

        int delay = wait2RawDelay & 0xFF;
        int nextFrame = (rawAnimFrame + 1) & 0xFF;
        if (nextFrame >= TOP_SPINUP_MAPPING_FRAMES.length) {
            rawAnimFrame = 0;
            mappingFrame = TOP_SPINUP_MAPPING_FRAMES[0];
            rawAnimFrameTimer = delay;
            if (delay == 0) {
                wait2RawLoopCounter = (wait2RawLoopCounter + 1) & 0xFF;
                if (wait2RawLoopCounter >= TOP_SPINUP_TERMINAL_LOOPS) {
                    wait2RawActive = false;
                    wait2RawLoopCounter = 0;
                    // ROM clears status bit 5 and $2F before jsr $34(a0).
                    // Obj_CNZMinibossTopGo changes routine/$30/x_vel/y_vel
                    // only; it does not rewrite mapping_frame.
                    onTopGo();
                }
                return;
            }

            delay = (delay - 1) & 0xFF;
            wait2RawDelay = delay;
            rawAnimFrameTimer = delay;
            return;
        }

        rawAnimFrame = nextFrame;
        mappingFrame = TOP_SPINUP_MAPPING_FRAMES[nextFrame];
        rawAnimFrameTimer = delay;
    }

    private void startMainAnimation() {
        mainFrameIndex = rawAnimFrame;
        mainFrameTimer = rawAnimFrameTimer;
    }

    private void animateMainTop() {
        mainFrameTimer--;
        if (mainFrameTimer >= 0) {
            return;
        }
        mainFrameIndex++;
        if (mainFrameIndex >= TOP_MAIN_FRAMES.length) {
            mainFrameIndex = 0;
        }
        mappingFrame = TOP_MAIN_FRAMES[mainFrameIndex];
        mainFrameTimer = TOP_MAIN_DELAY;
    }

    boolean isWait2RawActiveForTest() {
        return wait2RawActive;
    }

    int getWait2RawDelayForTest() {
        return wait2RawDelay & 0xFF;
    }

    int getWait2RawLoopCounterForTest() {
        return wait2RawLoopCounter & 0xFF;
    }

    int getWait2RawAnimFrameForTest() {
        return rawAnimFrame & 0xFF;
    }

    int getWait2RawFrameTimerForTest() {
        return rawAnimFrameTimer & 0xFF;
    }

    int getMappingFrameForTest() {
        return mappingFrame & 0xFF;
    }

    short getCurrentXVelForTest() {
        return (short) motion.xVel;
    }

    short getCurrentYVelForTest() {
        return (short) motion.yVel;
    }

    /**
     * ROM: Obj_CNZMinibossTopMain (sonic3k.asm:145053-145191).
     *
     * <p>The ROM body runs {@code MoveSprite2} (no gravity — the ball keeps
     * its speed) then dispatches a cascade of direction-specific edge
     * checks:
     * <ul>
     *   <li>If {@code x_vel >= 0}: right-wall probe via
     *       {@code ObjCheckRightWallDist}, right-edge probe against
     *       {@code $3380}, CNZMinibossTop_CheckHitBase. Any wall hit
     *       reverses {@code x_vel} (via {@code loc_6DD4C} for real tile
     *       collisions — which also publish {@code Events_bg} and call
     *       {@code CNZMiniboss_BlockExplosion} — or {@code loc_6DD8E}
     *       for the cheap arena-edge bounce).</li>
     *   <li>If {@code x_vel < 0}: mirror the above for the left wall
     *       against {@code $3200}.</li>
     *   <li>Then {@code CNZMinibossTop_CheckPlayerBounce}. If the
     *       player bounced off the ball, jump to {@code loc_6DDCC}
     *       (negate {@code y_vel}).</li>
     *   <li>Otherwise, if {@code y_vel >= 0}: floor probes (blocks,
     *       camera bottom, {@code $380} lower bound, base-hit). Block
     *       hits route through {@code loc_6DD94} which publishes
     *       {@code Events_bg} + {@code CNZMiniboss_BlockExplosion} and
     *       reverses {@code y_vel}. Camera/lower-bound/base-hit bounces
     *       simply reverse {@code y_vel} via {@code loc_6DDCC}.</li>
     *   <li>If {@code y_vel < 0}: ceiling probes mirroring the floor
     *       logic against {@code $240}.</li>
     * </ul>
     *
     * <p>The engine wires the tile probes, native P1/P2 cooperative bounce,
     * and miniboss-base hit detection through the shared terrain, player-query,
     * and boss-state paths. The arena fallback preserves the ROM's edges —
     * {@code $3200}/{@code $3380} on X and {@code $240}/{@code $380}
     * on Y — and reverses the relevant velocity whenever the next-frame
     * position would cross one of them. Horizontal arena-edge bounces
     * take the simple {@code loc_6DD8E} path (no {@code Events_bg}
     * publish); vertical arena-edge bounces (floor) take the
     * {@code loc_6DD94} path so they do publish a
     * {@code CNZMiniboss_BlockExplosion}-shaped write through the CNZ
     * bridge. This keeps the Task 7 arena-destruction seam alive
     * without inventing side effects the ROM doesn't emit.
     */
    private void updateMain(PlayableEntity player) {
        if (boss != null && boss.isDefeatedForChild()) {
            destroyAfterParentDefeat();
            return;
        }
        updateMainRom(player);
    }

    private void destroyAfterParentDefeat() {
        // ROM: Obj_CNZMinibossTopMain checks parent status bit 7 before
        // MoveSprite2/SolidObjectFull/terrain probes and jumps to loc_6DDD2
        // when the parent has entered CNZMiniboss_BossDefeated
        // (sonic3k.asm:145053-145057, 145190-145199).
        diagnosticLastMainBranch = "parent_destroyed";
        CnzMinibossBlockExplosionControllerChild controller = spawnChild(
                () -> new CnzMinibossBlockExplosionControllerChild(motion.x, motion.y));
        controller.dispatchCreation();
        setDestroyed(true);
        recordTraceBranchIfNotable();
    }

    private void updateMainRom(PlayableEntity player) {
        SubpixelMotion.moveSprite2(motion);
        animateMainTop();

        if (motion.xVel >= 0) {
            TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(
                    motion.x + Sonic3kConstants.CNZ_MINIBOSS_TOP_WALL_PROBE_DX, motion.y);
            if (isTerrainHit(wall)) {
                diagnosticLastMainBranch = "right_wall";
                handleWallTerrainHit();
                finishMainUpdate();
                return;
            }
            int baseProbeX = motion.x + Sonic3kConstants.CNZ_MINIBOSS_TOP_WALL_PROBE_DX;
            if (baseProbeX >= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_RIGHT
                    || checkHitBase(baseProbeX, motion.y)) {
                diagnosticLastMainBranch = "right_edge_base";
                motion.xVel = (short) -motion.xVel;
                finishMainUpdate();
                return;
            }
        } else {
            TerrainCheckResult wall = ObjectTerrainUtils.checkLeftWallDist(
                    motion.x - Sonic3kConstants.CNZ_MINIBOSS_TOP_WALL_PROBE_DX, motion.y);
            if (isTerrainHit(wall)) {
                diagnosticLastMainBranch = "left_wall";
                handleWallTerrainHit();
                finishMainUpdate();
                return;
            }
            int baseProbeX = motion.x - Sonic3kConstants.CNZ_MINIBOSS_TOP_WALL_PROBE_DX;
            if (baseProbeX < Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_LEFT
                    || checkHitBase(baseProbeX, motion.y)) {
                diagnosticLastMainBranch = "left_edge_base";
                motion.xVel = (short) -motion.xVel;
                finishMainUpdate();
                return;
            }
        }

        if (checkNativePlayerBounce(player)) {
            diagnosticLastMainBranch = "player_bounce";
            diagnosticPlayerBounceThisFrame = true;
            motion.yVel = (short) -motion.yVel;
            finishMainUpdate();
            return;
        }

        if (motion.yVel >= 0) {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(motion.x, motion.y, TOP_Y_RADIUS);
            if (isTerrainHit(floor)) {
                diagnosticLastMainBranch = "floor";
                handleVerticalTerrainHit();
                finishMainUpdate();
                return;
            }
            int d1 = motion.y + Sonic3kConstants.CNZ_MINIBOSS_TOP_FLOOR_PROBE_DY;
            // ROM: Obj_CNZMinibossTopMain checks Camera_Y_pos+$E0 before the
            // fixed $380 lower arena bound (sonic3k.asm:145101-145110).
            int cameraBottom = getCameraY() + 0xE0;
            if (d1 >= cameraBottom
                    || d1 > Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_BOTTOM
                    || checkHitBase(motion.x, d1)) {
                diagnosticLastMainBranch = "floor_edge_base";
                motion.yVel = (short) -motion.yVel;
                finishMainUpdate();
                return;
            }
        } else {
            TerrainCheckResult ceiling = ObjectTerrainUtils.checkCeilingDist(motion.x, motion.y, TOP_Y_RADIUS);
            if (isTerrainHit(ceiling)) {
                diagnosticLastMainBranch = "ceiling";
                handleVerticalTerrainHit();
                finishMainUpdate();
                return;
            }
            int d1 = motion.y - Sonic3kConstants.CNZ_MINIBOSS_TOP_FLOOR_PROBE_DY;
            // ROM: upward motion checks Camera_Y_pos before the fixed $240
            // upper arena bound (sonic3k.asm:145119-145126).
            if (d1 <= getCameraY()
                    || d1 <= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_TOP
                    || checkHitBase(motion.x, d1)) {
                diagnosticLastMainBranch = "ceiling_edge_base";
                motion.yVel = (short) -motion.yVel;
                finishMainUpdate();
                return;
            }
        }

        diagnosticLastMainBranch = "main_free";
        finishMainUpdate();
    }

    private int getCameraY() {
        return services().camera() != null ? services().camera().getY() & 0xFFFF : 0;
    }

    private void finishMainUpdate() {
        updateDynamicSpawn(motion.x, motion.y);
        recordTraceBranchIfNotable();
    }

    private void recordTraceBranchIfNotable() {
        if ("none".equals(diagnosticLastMainBranch) || "main_free".equals(diagnosticLastMainBranch)) {
            return;
        }
        diagnosticBranchHistoryFrame[diagnosticBranchHistoryCursor] = diagnosticCurrentVIntRunCount;
        diagnosticBranchHistoryX[diagnosticBranchHistoryCursor] = motion.x;
        diagnosticBranchHistoryY[diagnosticBranchHistoryCursor] = motion.y;
        diagnosticBranchHistoryXVel[diagnosticBranchHistoryCursor] = motion.xVel;
        diagnosticBranchHistoryYVel[diagnosticBranchHistoryCursor] = motion.yVel;
        diagnosticBranchHistoryBranch[diagnosticBranchHistoryCursor] = diagnosticLastMainBranch;
        diagnosticBranchHistoryCursor = (diagnosticBranchHistoryCursor + 1) % DIAGNOSTIC_BRANCH_HISTORY_SIZE;
        if (diagnosticBranchHistoryCount < DIAGNOSTIC_BRANCH_HISTORY_SIZE) {
            diagnosticBranchHistoryCount++;
        }
    }

    private String formatTraceBranchHistory() {
        if (diagnosticBranchHistoryCount == 0) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < diagnosticBranchHistoryCount; i++) {
            int source = Math.floorMod(diagnosticBranchHistoryCursor - diagnosticBranchHistoryCount + i,
                    DIAGNOSTIC_BRANCH_HISTORY_SIZE);
            if (i > 0) {
                builder.append(';');
            }
            builder.append('f').append(diagnosticBranchHistoryFrame[source])
                    .append(':').append(diagnosticBranchHistoryBranch[source])
                    .append('@').append(String.format("%04X,%04X",
                            diagnosticBranchHistoryX[source] & 0xFFFF,
                            diagnosticBranchHistoryY[source] & 0xFFFF))
                    .append('/').append(String.format("%04X,%04X",
                            diagnosticBranchHistoryXVel[source] & 0xFFFF,
                            diagnosticBranchHistoryYVel[source] & 0xFFFF));
        }
        return builder.toString();
    }

    private boolean checkPlayerBounce(PlayableEntity player) {
        return checkPlayerBounce(player, false);
    }

    private boolean checkPlayerBounce(PlayableEntity player, boolean projectPendingMovement) {
        if (player == null || player.getYSpeed() >= 0 || motion.yVel <= 0 || !player.getRolling()) {
            return false;
        }
        int playerX = player.getCentreX() & 0xFFFF;
        int playerY = player.getCentreY() & 0xFFFF;
        if (projectPendingMovement) {
            playerX = (playerX + (player.getXSpeed() >> 8)) & 0xFFFF;
            playerY = (playerY + (player.getYSpeed() >> 8)) & 0xFFFF;
        }
        int dx = playerX - motion.x;
        int dy = playerY - motion.y;
        if (Math.abs(dx) > PLAYER_BOUNCE_HALF_SIZE
                || Math.abs(dy - PLAYER_BOUNCE_Y_OFFSET) > PLAYER_BOUNCE_HALF_SIZE) {
            return false;
        }
        if ((player.getXSpeed() < 0 && motion.xVel > 0)
                || (player.getXSpeed() > 0 && motion.xVel < 0)) {
            motion.xVel = (short) -motion.xVel;
        }
        return (player.getYSpeed() < 0 && motion.yVel > 0)
                || (player.getYSpeed() > 0 && motion.yVel < 0);
    }

    private boolean checkNativePlayerBounce(PlayableEntity player) {
        // CNZMinibossTop_CheckPlayerBounce probes Player_1 first, then the
        // single native Player_2 slot when P1 did not rebound the top.
        if (checkPlayerBounce(player)) {
            return true;
        }
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        boolean nativeP2Bounce = nativeP2 != null && nativeP2 != player
                && checkPlayerBounce(nativeP2, true);
        if (nativeP2Bounce) {
            // The native top SST performs SolidObjectFull before the bounce
            // probe. Its following P2 checkpoint still sees the top's prior
            // publication; the folded engine checkpoint otherwise samples
            // the already-advanced upward position.
            nativeP2BounceUsesPreUpdateSolidPosition = true;
            return true;
        }
        return false;
    }

    private boolean isTerrainHit(TerrainCheckResult result) {
        return result != null && result.distance() < 0;
    }

    private void handleWallTerrainHit() {
        motion.xVel = (short) -motion.xVel;
        int impactX = motion.x + (motion.xVel < 0
                ? Sonic3kConstants.CNZ_MINIBOSS_TOP_WALL_PROBE_DX
                : -Sonic3kConstants.CNZ_MINIBOSS_TOP_WALL_PROBE_DX);
        if (impactX <= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_LEFT
                || impactX >= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_RIGHT) {
            return;
        }
        publishArenaChunkImpact(snappedBlockCentre(impactX), snappedBlockCentre(motion.y));
    }

    private void handleVerticalTerrainHit() {
        int oldYVel = motion.yVel;
        motion.yVel = (short) -motion.yVel;
        // ROM loc_6DD94 negates y_vel, then uses y_pos+8 for floor hits
        // and y_pos-8 for ceiling hits before CNZMiniboss_BlockExplosion.
        int impactY = motion.y + (oldYVel >= 0
                ? Sonic3kConstants.CNZ_MINIBOSS_TOP_FLOOR_PROBE_DY
                : -Sonic3kConstants.CNZ_MINIBOSS_TOP_FLOOR_PROBE_DY);
        if (motion.x <= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_LEFT
                || motion.x >= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_RIGHT
                || impactY >= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_BOTTOM) {
            return;
        }
        publishArenaChunkImpact(snappedBlockCentre(motion.x), snappedBlockCentre(impactY));
    }

    private int snappedBlockCentre(int world) {
        return (world & 0xFFE0) + 0x10;
    }

    /**
     * Publishes one ROM-shaped arena-chunk destruction through the CNZ event
     * bridge.
     *
     * <p>ROM {@code loc_6DD94} calls {@code CNZMiniboss_BlockExplosion},
     * which writes the impact coordinates and creates the visual child
     * (sonic3k.asm:145165-145185, 145204-145224). Base lowering is driven
     * later when CNZ's arena row scanner advances {@code Events_bg+$04}, then
     * {@code CNZMiniboss_MoveDown} arms {@code Obj_CNZMinibossLower2}
     * (sonic3k.asm:107388-107414, 145508-145515); a single top impact must
     * not directly move or arm the parent.
     */
    private void publishArenaChunkImpact(int worldX, int worldY) {
        diagnosticArenaImpactThisFrame = true;
        diagnosticArenaImpactX = worldX;
        diagnosticArenaImpactY = worldY;
        CnzMinibossBlockExplosionControllerChild controller =
                spawnChild(() -> new CnzMinibossBlockExplosionControllerChild(worldX, worldY));
        controller.dispatchCreation();
        S3kCnzEventWriteSupport.queueArenaChunkDestruction(
                services(), worldX, worldY);
    }

    private void refreshChildPosition() {
        if (boss == null) {
            return;
        }
        motion.x = boss.getCentreX() + parentOffsetX;
        motion.y = boss.getCentreY() + parentOffsetY;
        motion.xSub = 0;
        motion.ySub = 0;
        updateDynamicSpawn(motion.x, motion.y);
    }

    private boolean checkHitBase(int x, int y) {
        if (boss == null) {
            return false;
        }
        boolean baseHit = inRange(x, y, -0x18, 0x30, -0x10, 0x20);
        boolean coilHit = boss.isOpenForTopHit()
                ? inRange(x, y, -0x0C, 0x18, 0x10, 0x38)
                : inRange(x, y, -0x0C, 0x18, 0x10, 0x18);
        if (!baseHit && !coilHit) {
            return false;
        }
        boss.onTopPieceHitBase();
        diagnosticHitBaseThisFrame = true;
        return true;
    }

    private boolean inRange(int x, int y, int xOffset, int width, int yOffset, int height) {
        int left = boss.getCentreX() + xOffset;
        int top = boss.getCentreY() + yOffset;
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Obj_CNZMinibossTopMain runs MoveSprite2 before Draw_And_Touch_Sprite
        // publishes the SST pointer. The following player-slot Touch_Loop
        // dereferences that live post-movement x_pos/y_pos.
        // sonic3k.asm:145058-145064,178041-178043,20660-20712.
        return true;
    }

    @Override
    public int getCollisionFlags() {
        return isDestroyed() ? 0 : TOP_COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        // ROM Solid_Landed / loc_1E154 (sonic3k.asm:41611-41621) re-reads
        // width_pixels(a0) for the landing X gate. ObjDat3_CNZMinibossTop sets
        // width_pixels = $18 (sonic3k.asm:145662-145664) while
        // Obj_CNZMinibossTopMain passes d1 = $13 (sonic3k.asm:145064-145068),
        // so the default d1 - $B = $8 heuristic is $10px too narrow.
        return 0x18;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return routine == ROUTINE_MAIN && !isDestroyed();
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        if (!nativeP2BounceUsesPreUpdateSolidPosition) {
            return false;
        }
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        return player == nativeP2;
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        // Obj_CNZMinibossTopMain calls SolidObjectFull (sonic3k.asm:145057-145063),
        // whose wrapper skips Player_2 when render_flags bit 7 is clear
        // (sonic3k.asm:41003-41008).
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // SolidObjectFull_1P consumes this object's stale standing bit with
        // Status_InAir by clearing support and returning before SolidObject_cont
        // can reland the player (sonic3k.asm:41016-41035).
        return true;
    }

    @Override
    public boolean seedsNewRideCarryFromPreUpdateX() {
        // Obj_CNZMinibossTopMain saves x_pos before MoveSprite2 and passes the
        // saved value in d4 to SolidObjectFull (sonic3k.asm:145057-145063).
        return true;
    }

    @Override
    public boolean groundedSquashEdgeSideContactSetsPush() {
        // Obj_CNZMinibossTopMain calls SolidObjectFull (sonic3k.asm:145057-145063).
        // Its lower-half squash escape branches to loc_1E042 when |d0| < $10,
        // then loc_1E06E sets Status_Push for grounded side contact regardless
        // of movingInto (sonic3k.asm:41564-41568, 41473-41495).
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // SolidObjectFull stores P1/P2 standing and pushing bits in this top's
        // SST status byte (sonic3k.asm:41001-41010, 41492-41495, 41528-41532).
        // The engine rebuilds the top's dynamic spawn as it moves; key the
        // latch to the instance so the following no-contact frame clears the
        // same ROM-equivalent status bits.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (player == null || contact == null) {
            return;
        }
        String summary = String.format("%s/push=%s/air=%s/yv=%04X",
                contact.touchSide() ? "side"
                        : contact.standing() || contact.touchTop() ? "top"
                        : contact.touchBottom() ? "bottom"
                        : "none",
                contact.pushing(),
                player.getAir(),
                player.getYSpeed() & 0xFFFF);
        if (player.isCpuControlled()) {
            diagnosticLastP2Solid = summary;
        } else {
            diagnosticLastP1Solid = summary;
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        if (player == null) {
            return;
        }
        if (player.isCpuControlled()) {
            diagnosticLastP2Solid = "clear";
        } else {
            diagnosticLastP1Solid = "clear";
        }
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
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_MINIBOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, motion.x, motion.y, false, false);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // ObjDat3_CNZMinibossTop priority word $0200.
        return 4;
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "r=%02X v=%04X,%04X sub=%02X,%02X map=%02X raw=%s d=%02X l=%02X af=%02X ft=%02X main=%d/%d br=%s base=%s pb=%s impact=%s@%04X,%04X parentOff=%04X,%04X solid=%s/%s hist=%s",
                routine & 0xFF,
                motion.xVel & 0xFFFF,
                motion.yVel & 0xFFFF,
                motion.xSub & 0xFF,
                motion.ySub & 0xFF,
                mappingFrame & 0xFF,
                wait2RawActive,
                wait2RawDelay & 0xFF,
                wait2RawLoopCounter & 0xFF,
                rawAnimFrame & 0xFF,
                rawAnimFrameTimer & 0xFF,
                mainFrameIndex,
                mainFrameTimer,
                diagnosticLastMainBranch,
                diagnosticHitBaseThisFrame,
                diagnosticPlayerBounceThisFrame,
                diagnosticArenaImpactThisFrame,
                diagnosticArenaImpactX & 0xFFFF,
                diagnosticArenaImpactY & 0xFFFF,
                parentOffsetX & 0xFFFF,
                parentOffsetY & 0xFFFF,
                diagnosticLastP1Solid,
                diagnosticLastP2Solid,
                formatTraceBranchHistory());
    }
}
